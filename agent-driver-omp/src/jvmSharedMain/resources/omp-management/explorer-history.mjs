import { AskTool, recoverAskQuestions } from '@oh-my-pi/pi-coding-agent/tools/ask';
import { mkdtemp } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { randomUUID } from 'node:crypto';
import { isDeepStrictEqual } from 'node:util';
import { sameMessageContent, sessionMessagePersistenceKey } from '@oh-my-pi/pi-coding-agent/session/turn-persistence';
import { isUserRequestEntry, transcriptEntryMessage, userTurnDraft } from '@oh-my-pi/pi-tui/chat/transcript-entry';

const PAGE_SIZE = 256;
const MAX_SNAPSHOTS = 4;
const MAX_REVISIONS = 64;
const MAX_RETAINED_CHANGES = 8192;
const indexes = new WeakMap();

// Session entries are native references, not body clones. Native persistence
// replaces message payloads; rollback may also reparent an existing entry.
function stamp(value) {
  return value && typeof value === 'object' ? Object.entries(value) : null;
}
function matches(value, saved) {
  if (!saved) return value == null;
  if (!value || typeof value !== 'object') return false;
  let count = 0;
  for (const key in value) if (Object.hasOwn(value, key)) count++;
  return count === saved.length && saved.every(([key, item]) => value[key] === item);
}
function textPrefix(content, limit = 200) {
  if (typeof content === 'string') return content.slice(0, limit);
  let text = '';
  if (Array.isArray(content)) for (const block of content) {
    if (block.type !== 'text' || typeof block.text !== 'string') continue;
    if (text) text += '\n';
    text += block.text.slice(0, limit - text.length);
    if (text.length >= limit) break;
  }
  return text.slice(0, limit);
}
function summary(entry) {
  const message = entry.type === 'message' ? entry.message : undefined;
  const content = message?.content ?? entry.content;
  const userText = isUserRequestEntry(entry) ? userTurnDraft(entry) : undefined;
  const searchText = textPrefix(userText ?? content ?? entry.text ?? entry.summary ?? entry.model ?? entry.title ?? entry.label ?? entry.name ?? '');
  const role = message?.role ?? (entry.type === 'custom_message' ? 'custom' : null);
  const type = role === 'toolResult' && message.toolName === 'ask' ? 'ask' : entry.type;
  const prefix = role === 'user' ? '用户' : role === 'assistant' ? '助手' : role === 'toolResult'
    ? message.toolName ?? '工具结果' : entry.type;
  const title = `${prefix}${searchText ? ` · ${searchText.replace(/\s+/g, ' ').trim()}` : ''}`.slice(0, 120);
  const hasText = typeof content === 'string' ? content.length > 0 : Array.isArray(content) && content.some(block => block.type === 'text' && block.text?.length > 0);
  const hasCommand = role === 'assistant' && Array.isArray(content) && content.some(block => block.type === 'toolCall' &&
    (typeof block.arguments?.command === 'string' || typeof block.arguments?.code === 'string'));
  return { id: entry.id, parentId: entry.parentId, type, role, title, searchText,
    timestamp: entry.timestamp ?? message?.timestamp ?? null, navigable: entry.type !== 'label',
    hasCopyContent: !!(hasText || hasCommand || entry.summary?.length), copyBlockCount: null };
}
function refresh(manager) {
  const sessionId = manager.getSessionId();
  let state = indexes.get(manager);
  if (!state || state.sessionId !== sessionId) {
    state = { sessionId, generation: randomUUID(), sequence: 0, nodeSequence: 0, pass: 0,
      records: new Map(), order: [], revisions: [], retainedChanges: 0, snapshots: new Map() };
    indexes.set(manager, state);
  }
  const entries = manager.getEntries(); // A shallow array copy is intentional.
  const pass = ++state.pass, labels = new Map(), upserts = new Map(), removedIds = [];
  let orderChanged = entries.length !== state.order.length;
  const reordered = entries.some((entry, index) => index < state.order.length && state.order[index] !== entry.id);
  for (let index = 0; index < entries.length; index++) {
    const entry = entries[index];
    if (state.order[index] !== entry.id) orderChanged = true;
    let record = state.records.get(entry.id);
    if (!record || record.entry !== entry || !matches(entry, record.stamp) || !matches(entry.message, record.messageStamp)) {
      record = { entry, stamp: stamp(entry), messageStamp: stamp(entry.message), base: summary(entry),
        ownVersion: ++state.nodeSequence, node: record?.node,
        calls: entry.type === 'message' && entry.message.role === 'assistant' && Array.isArray(entry.message.content)
          ? entry.message.content.filter(block => block.type === 'toolCall') : [],
        userRequest: isUserRequestEntry(entry) };
      state.records.set(entry.id, record);
    }
    record.seen = pass;
    if (entry.type === 'label') {
      if (entry.label == null) labels.delete(entry.targetId);
      else labels.set(entry.targetId, entry.label);
    }
  }
  for (const [id, record] of state.records) if (record.seen !== pass) {
    state.records.delete(id); removedIds.push(id);
  }
  // Resolve the nearest assistant on this exact ancestry, not a global tool-id
  // map: forked branches can legitimately contain identical tool-call IDs.
  const pending = [];
  function ancestry(record) {
    if (!record || record.ancestryPass === pass) return record?.assistant;
    let cursor = record, owner;
    while (cursor && cursor.ancestryPass !== pass) {
      if (cursor.walkPass === pass) throw Error('Native history contains a parent cycle');
      cursor.walkPass = pass;
      pending.push(cursor);
      if (cursor.entry.type === 'message' && cursor.entry.message.role === 'assistant') { owner = cursor; break; }
      if (cursor.userRequest) break;
      cursor = state.records.get(cursor.entry.parentId);
    }
    if (cursor?.ancestryPass === pass) owner = cursor.assistant;
    while (pending.length) {
      const item = pending.pop();
      item.assistant = owner; item.ancestryPass = pass;
    }
    return record.assistant;
  }
  for (const entry of entries) {
    const record = state.records.get(entry.id);
    const owner = ancestry(record);
    const dependency = entry.type === 'message' && entry.message.role === 'toolResult' ? owner : undefined;
    const version = `${state.generation}:${record.ownVersion}:${dependency?.ownVersion ?? 0}`;
    const label = labels.get(entry.id) ?? null;
    if (!record.node || record.node.version !== version || record.node.label !== label) {
      record.node = Object.freeze({ ...record.base, version, label });
      upserts.set(entry.id, record.node);
    }
  }
  const sessionFile = manager.getSessionFile() ?? null, leafId = manager.getLeafId();
  if (!state.revision || upserts.size || removedIds.length || orderChanged || state.leafId !== leafId || state.sessionFile !== sessionFile) {
    state.revision = `${state.generation}:${++state.sequence}`;
    const change = { revision: state.revision, nodes: [...upserts.values()], removedIds, reordered };
    state.revisions.push(change);
    state.retainedChanges += change.nodes.length + removedIds.length;
    while (state.revisions.length > MAX_REVISIONS || (state.retainedChanges > MAX_RETAINED_CHANGES && state.revisions.length > 1)) {
      const removed = state.revisions.shift();
      state.retainedChanges -= removed.nodes.length + removed.removedIds.length;
    }
    // The oldest revision is a baseline, not a retained full-history delta.
    if (state.retainedChanges > MAX_RETAINED_CHANGES) {
      state.revisions[0] = { revision: state.revision, nodes: [], removedIds: [] };
      state.retainedChanges = 0;
    }
  }
  if (orderChanged) state.order = entries.map(entry => entry.id);
  state.sessionFile = sessionFile; state.leafId = leafId;
  return state;
}

export function historySnapshot(manager, request = {}) {
  let state = indexes.get(manager);
  if (request.cursor != null) {
    if (typeof request.cursor !== 'string' || request.since != null) throw Error('Invalid native history cursor');
    const [id, offsetText, extra] = request.cursor.split(':');
    const snapshot = state?.sessionId === manager.getSessionId() && state.snapshots.get(id);
    const offset = Number(offsetText);
    if (!snapshot || extra !== undefined || !Number.isSafeInteger(offset) || offset <= 0 || offset % PAGE_SIZE || offset >= snapshot.nodes.length + snapshot.removedIds.length)
      throw Error('Native history cursor expired; reload the history index');
    return page(snapshot, offset);
  }
  state = refresh(manager);
  const sinceIndex = state.revisions.findIndex(change => change.revision === request.since);
  const reset = request.since == null || sinceIndex < 0 || state.revisions.slice(sinceIndex + 1).some(change => change.reordered);
  let nodes, removedIds;
  if (reset) {
    nodes = state.order.map(id => state.records.get(id).node); removedIds = [];
  } else {
    const changed = new Map(), removed = new Set();
    for (let index = sinceIndex + 1; index < state.revisions.length; index++) {
      const change = state.revisions[index];
      for (const id of change.removedIds) { changed.delete(id); removed.add(id); }
      for (const node of change.nodes) { removed.delete(node.id); changed.set(node.id, node); }
    }
    nodes = [...changed.values()]; removedIds = [...removed];
  }
  const snapshot = { id: randomUUID(), sessionId: state.sessionId, sessionFile: state.sessionFile,
    leafId: state.leafId, revision: state.revision, reset, nodes, removedIds };
  if (nodes.length + removedIds.length > PAGE_SIZE) {
    state.snapshots.set(snapshot.id, snapshot);
    while (state.snapshots.size > MAX_SNAPSHOTS) state.snapshots.delete(state.snapshots.keys().next().value);
  }
  return page(snapshot, 0);
}
function page(snapshot, offset) {
  return { sessionId: snapshot.sessionId, sessionFile: snapshot.sessionFile, leafId: snapshot.leafId,
    revision: snapshot.revision, reset: snapshot.reset, nodes: snapshot.nodes.slice(offset, offset + PAGE_SIZE),
    removedIds: snapshot.removedIds.slice(Math.max(0, offset - snapshot.nodes.length), Math.max(0, offset + PAGE_SIZE - snapshot.nodes.length)),
    nextCursor: offset + PAGE_SIZE < snapshot.nodes.length + snapshot.removedIds.length ? `${snapshot.id}:${offset + PAGE_SIZE}` : null };
}

export function historyDetail(manager, request) {
  if (!['preview', 'raw', 'copy', 'ask', 'identity'].includes(request.kind)) throw Error('Unknown native history detail kind');
  const state = refresh(manager), record = state.records.get(request.entryId);
  if (!record || (request.kind !== 'identity' || request.version != null) &&
    (typeof request.version !== 'string' || record.node.version !== request.version))
    throw Error('Stale native history entry; refresh the index and retry');
  const result = { sessionId: state.sessionId, entryId: record.entry.id, version: record.node.version };
  if (request.kind === 'raw') return { ...result, rawRecord: JSON.stringify(record.entry) };
  if (request.kind === 'identity') return { ...result, entry: record.entry };
  const message = record.entry.type === 'message' ? record.entry.message : undefined;
  const calls = message?.role === 'toolResult'
    ? (record.assistant?.calls ?? []).filter(call => call.id === message.toolCallId) : record.calls;
  return { ...result, entry: record.entry, calls,
    ...(record.userRequest ? { userText: userTurnDraft(record.entry) } : {}) };
}

// RPC omits undefined object properties (native custom messages keep optional
// fields explicitly undefined). Compare the complete wire structure without
// cloning/stringifying any bodies; only the selected identity candidates reach
// this path. No text, timestamp, or metadata is relaxed.
function sameWireMessage(left, right) {
  if (left === right) return true;
  if (!left || !right || typeof left !== 'object' || typeof right !== 'object') return isDeepStrictEqual(left, right);
  if (Array.isArray(left) || Array.isArray(right)) {
    return Array.isArray(left) && Array.isArray(right) && left.length === right.length &&
      left.every((item, index) => sameWireMessage(item === undefined ? null : item, right[index]));
  }
  let count = 0;
  for (const key in left) if (Object.hasOwn(left, key) && left[key] !== undefined) {
    count++;
    if (!Object.hasOwn(right, key) || !sameWireMessage(left[key], right[key])) return false;
  }
  return count === Object.keys(right).length;
}

export function historyFind(manager, request) {
  const message = request.message;
  if (!message || typeof message !== 'object') throw Error('A selected native message is required');
  const key = sessionMessagePersistenceKey(message);
  const state = refresh(manager), candidates = [];
  for (const id of state.order) {
    const entry = state.records.get(id).entry;
    if (key !== undefined) {
      if (entry.type === 'message' && sessionMessagePersistenceKey(entry.message) === key &&
        (message.role !== 'assistant' || entry.message.errorMessage === message.errorMessage) && sameMessageContent(entry.message, message)) candidates.push(entry.id);
    } else {
      // These native roles use dedicated append paths, not turn-persistence
      // keys. Require the entire restored message, never a text/title match.
      const candidate = entry.type === 'message' ? entry.message
        : entry.type === 'custom_message' && message.role === 'custom' && entry.customType === message.customType
          ? transcriptEntryMessage(entry) : undefined;
      if (candidate?.role === message.role && candidate.timestamp === message.timestamp && sameWireMessage(candidate, message)) candidates.push(entry.id);
    }
  }
  if (!candidates.length) throw Error('Selected message is not persisted in this native session');
  if (candidates.length > 1) {
    const onBranch = new Set(manager.getBranch().map(entry => entry.id));
    const current = candidates.filter(id => onBranch.has(id));
    if (current.length === 1) return { sessionId: state.sessionId, entryId: current[0] };
    throw Error('Selected native message identity is ambiguous');
  }
  return { sessionId: state.sessionId, entryId: candidates[0] };
}

export function historyBranch(manager) {
  return { sessionId: manager.getSessionId(), leafId: manager.getLeafId(), entries: manager.getBranch() };
}

export function historyPosition(manager) {
  return { sessionId: manager.getSessionId(), leafId: manager.getLeafId() };
}

export function requireIdle(session, action) {
  if (session.isBusyForSnapshot || session.isStreaming || session.queuedMessageCount > 0)
    throw Error(`Cannot ${action} while the native session is busy`);
}

export function restoredNavigation(result) {
  return { cancelled: result.cancelled === true, aborted: result.aborted === true,
    ...(result.editorText !== undefined ? { prompt: { text: result.editorText, images: result.editorImages ?? [] } } : {}),
    ...(result.summaryEntry ? { summaryEntry: result.summaryEntry } : {}) };
}

export function askSubmission(questions, answers) {
  if (!Array.isArray(answers) || answers.length !== questions.length) throw Error('Answers must include every original question');
  const byId = new Map();
  for (const answer of answers) {
    const id = answer.questionId ?? answer.id;
    if (byId.has(id)) throw Error('A question was answered more than once');
    byId.set(id, answer);
  }
  return { kind: 'submit', results: questions.map(question => {
    const answer = byId.get(question.id);
    if (!answer) throw Error(`Missing answer for question ${question.id}`);
    const selected = answer.selectedOptions;
    if (!Array.isArray(selected) || selected.some(label => typeof label !== 'string' || !question.options.some(o => o.label === label)) || new Set(selected).size !== selected.length)
      throw Error(`Invalid selected option for question ${question.id}`);
    if (answer.customInput != null && typeof answer.customInput !== 'string') throw Error('Custom answer must be text');
    const customInput = answer.customInput?.trim() || undefined;
    if (!question.multi && (selected.length > 1 || (selected.length && customInput) || (!selected.length && !customInput)))
      throw Error(`Question ${question.id} requires exactly one answer`);
    return { id: question.id, selectedOptions: selected, customInput,
      ...(answer.note !== undefined ? { note: answer.note } : {}),
      ...(answer.customInputImages ? { customInputImages: answer.customInputImages } : {}),
      ...(answer.noteImages ? { noteImages: answer.noteImages } : {}) };
  }) };
}

export async function reanswer(session, request) {
  requireIdle(session, 're-answer a historical Ask');
  const entry = session.sessionManager.getEntry(request.entryId);
  if (entry?.type !== 'message' || entry.message.role !== 'toolResult' || entry.message.toolName !== 'ask')
    throw Error('Selected entry is not a historical Ask result');
  // Native navigation falls through to a plain move for an unrecoverable Ask.
  // Reject before probing so a failed re-answer cannot change the current leaf.
  let ancestorId = entry.parentId, questions;
  while (ancestorId !== null) {
    const ancestor = session.sessionManager.getEntry(ancestorId);
    if (!ancestor) break;
    if (ancestor.type === 'message' && ancestor.message.role === 'assistant') {
      const call = ancestor.message.content.find(block => block.type === 'toolCall' && block.id === entry.message.toolCallId && block.name === 'ask');
      questions = call && recoverAskQuestions(call.arguments);
      break;
    }
    if (ancestor.type === 'message' && ancestor.message.role === 'user') break;
    ancestorId = ancestor.parentId;
  }
  if (!questions) throw Error('Original Ask questions are unavailable; re-answering is not possible');
  askSubmission(questions, request.answers);
  const probe = await session.navigateTree(request.entryId, { allowAskReopen: true, summarize: false });
  if (probe.cancelled) return { ...restoredNavigation(probe), ...historyPosition(session.sessionManager) };
  if (!probe.reopenAsk) throw Error('Original Ask questions are unavailable; re-answering is not possible');
  const submission = askSubmission(probe.reopenAsk.questions, request.answers);
  const ui = { askDialog: async () => submission, select: async () => { throw Error('Rich Ask submission is required'); },
    editor: async () => { throw Error('Rich Ask submission is required'); }, supportsEditor: true, notify() {} };
  const tool = new AskTool({ cwd: session.sessionManager.getCwd(), hasUI: true, settings: session.settings,
    getSessionFile: () => session.sessionFile ?? null, getSessionSpawns: () => null,
    getPlanModeState: () => session.getPlanModeState() });
  const result = await tool.execute('wawlink-history-reanswer', { questions: probe.reopenAsk.questions }, undefined, undefined,
    session.buildAskReanswerContext(ui));
  if (result.isError || result.details?.chatRedirect) throw Error('Native Ask did not produce a historical answer');
  const navigation = await session.navigateTree(request.entryId, { allowAskReopen: true, summarize: false, reanswerAskResult: result });
  await session.sessionManager.flush();
  if (navigation.askReanswerCommitted) session.resumeAfterAskReanswer();
  return { ...restoredNavigation(navigation), ...historyPosition(session.sessionManager), resumed: navigation.askReanswerCommitted === true };
}

// SessionManager owns the copy and artifact persistence. The native fork boundary
// is extended through recorded results, matching AgentSession's tool-batch rule;
// neither the live manager nor its current leaf is ever changed.
function completeToolBatch(manager, entryId) {
  if (!manager.getEntry(entryId)) throw Error(`Entry ${entryId} not found`);
  const answered = new Set();
  let batch;
  const branch = manager.getBranch(entryId);
  for (let index = branch.length - 1; index >= 0; index--) {
    const entry = branch[index];
    if (entry.type !== 'message') continue;
    if (entry.message.role === 'toolResult') { answered.add(entry.message.toolCallId); continue; }
    if (entry.message.role === 'assistant') batch = entry.message;
    break;
  }
  const pending = new Set((batch?.content ?? []).filter(b => b.type === 'toolCall' && !answered.has(b.id)).map(b => b.id));
  let leaf = entryId, cursor = entryId;
  while (pending.size) {
    const children = manager.getChildren(cursor);
    const result = children.findLast(e => e.type === 'message' && e.message.role === 'toolResult' && pending.has(e.message.toolCallId));
    if (result) { pending.delete(result.message.toolCallId); leaf = cursor = result.id; continue; }
    if (children.length !== 1 || children[0].type === 'message') break;
    cursor = children[0].id;
  }
  if (pending.size) throw Error('Cannot fork an incomplete native tool-call batch');
  return leaf;
}

export async function forkHistory(session, request) {
  requireIdle(session, 'fork the session');
  if (!session.sessionFile) throw Error('Cannot fork an unpersisted session');
  let leaf = completeToolBatch(session.sessionManager, request.entryId ?? session.sessionManager.getLeafId());
  if (session.extensionRunner?.hasHandlers('session_before_branch')) {
    const result = await session.extensionRunner.emit({ type: 'session_before_branch', reason: 'fork', entryId: leaf });
    if (result?.cancel) return { cancelled: true };
  }
  await session.settleInFlightMessagePersistence();
  await session.sessionManager.flush();
  requireIdle(session, 'fork the session');
  leaf = completeToolBatch(session.sessionManager, leaf);
  const copy = session.sessionManager.cloneCurrentSession();
  try {
    const destination = await mkdtemp(join(tmpdir(), 'wawlink-session-fork-'));
    copy.restoreState({ ...copy.captureState(), sessionDir: destination });
    const sessionFile = copy.createBranchedSession(leaf, { copyArtifacts: true });
    if (!sessionFile) throw Error('Native session is not persisted');
    await copy.flush();
    return { sessionFile, sessionId: copy.getSessionId(), sourceSessionFile: session.sessionFile, entryId: leaf };
  } finally { await copy.close(); }
}
