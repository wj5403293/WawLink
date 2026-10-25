import { AskTool, recoverAskQuestions } from '@oh-my-pi/pi-coding-agent/tools/ask';
import { mkdtemp } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { isUserRequestEntry, userTurnDraft } from '@oh-my-pi/pi-tui/chat/transcript-entry';

export function historySnapshot(manager) {
  const entries = manager.getEntries();
  const labels = {};
  for (const entry of entries) if (entry.type === 'label') {
    if (entry.label == null) delete labels[entry.targetId];
    else labels[entry.targetId] = entry.label;
  }
  return { sessionId: manager.getSessionId(), sessionFile: manager.getSessionFile() ?? null,
    leafId: manager.getLeafId(), entries, labels,
    messageIds: Object.fromEntries(entries.filter(e => e.type === 'message').map(e => [e.id, `entry:${e.id}`])),
    userInputs: Object.fromEntries(entries.filter(isUserRequestEntry).map(entry => [entry.id, userTurnDraft(entry)])) };
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
  if (probe.cancelled) return restoredNavigation(probe);
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
  return { ...restoredNavigation(navigation), ...historySnapshot(session.sessionManager), resumed: navigation.askReanswerCommitted === true };
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
