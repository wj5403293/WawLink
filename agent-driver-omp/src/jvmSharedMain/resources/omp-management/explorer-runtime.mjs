import { basename, join } from 'node:path';
import { access, mkdir, stat } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { AgentRegistry, MAIN_AGENT_ID } from '@oh-my-pi/pi-coding-agent/registry/agent-registry';
import { AgentLifecycleManager } from '@oh-my-pi/pi-coding-agent/registry/agent-lifecycle';
import { registerPersistedSubagents, sessionFileBelongsToRoot } from '@oh-my-pi/pi-coding-agent/registry/persisted-agents';
import { AgentActivityIndex } from '/opt/omp/src/activity/index.ts';
// Not an exported host module: this native parser owns only per-instance file
// caches, never a registry/lifecycle singleton. Stateful imports use host aliases.
import { loadSessionFile, resolveBlobRefsInEntries } from '@oh-my-pi/pi-coding-agent/session/session-loader';
import { resolveSessionLeafId } from '@oh-my-pi/pi-coding-agent/session/session-entries';
import { sessionMessagePersistenceKey, sameMessageContent } from '@oh-my-pi/pi-coding-agent/session/turn-persistence';
import { isUserAuthoredQueuedMessage, toRestoredQueuedMessage } from '@oh-my-pi/pi-coding-agent/session/queued-messages';
import { BlobStore } from '@oh-my-pi/pi-coding-agent/session/blob-store';
import { getBlobsDir } from '@oh-my-pi/pi-utils/dirs';
import { requestRpcAskDialog, requestRpcSelect, requestRpcEditor, requestRpcDialog } from '@oh-my-pi/pi-coding-agent/modes/rpc/rpc-mode';
import { askSubmission } from './explorer-history.mjs';
import { safeReason } from './diagnostics.mjs';

const registry = () => AgentRegistry.global();
const keyOf = (mainId, ref) => JSON.stringify([mainId, ref.id, ref.sessionFile ?? ref.session?.sessionId ?? null]);
const textOf = content => typeof content === 'string' ? content : (Array.isArray(content) ? content : []).filter(b => b.type === 'text').map(b => b.text).join('\n');
const number = value => typeof value === 'number' && Number.isFinite(value) ? value : null;
const identity = message => message.role === 'assistant'
  ? `assistant:${message.timestamp}:${message.provider}:${message.model}`
  : sessionMessagePersistenceKey(message) ?? `${message.role}:${message.timestamp ?? ''}:${message.toolCallId ?? ''}`;

export async function readTranscript(file) {
  const loaded = await loadSessionFile(file, undefined, { throwIfMissing: true });
  if (loaded.invalidHeader || !loaded.entries.some(e => e.type === 'session')) throw Error('Native transcript has no valid session header');
  if (loaded.malformedRecords) throw Error(`Native transcript contains ${loaded.malformedRecords} malformed records`);
  const header = loaded.entries.find(e => e.type === 'session');
  const entries = loaded.entries.filter(e => e.type !== 'session');
  await resolveBlobRefsInEntries(loaded.entries, new BlobStore(getBlobsDir()));
  return { header, entries, leafId: resolveSessionLeafId(entries) };
}

function queueSnapshot(session) {
  const queue = session.getQueuedMessages();
  const records = {
    steering: [...session.agent.peekLiveSteeredMessages(), ...session.agent.peekSteeringQueue()].filter(isUserAuthoredQueuedMessage),
    followUp: session.agent.peekFollowUpQueue().filter(isUserAuthoredQueuedMessage),
  };
  return ['steering', 'followUp'].flatMap(kind => queue[kind].map((text, index) => ({
    id: JSON.stringify([kind, index, text]), queue: kind, text,
    images: records[kind][index] ? toRestoredQueuedMessage(records[kind][index]).images : [],
    removable: kind !== 'steering' || index >= queue.liveSteered,
  })));
}

// Public native tool instances retain their original execute implementation and
// complete context. Only this child's UI fields change; approval remains inside
// ExtensionToolWrapper and Ask still runs the native AskTool.
export function attachChildUI(session, ui) {
  const runner = session.extensionRunner;
  if (!runner || typeof runner.getUIContext !== 'function' || typeof runner.hasUI !== 'function')
    throw Error('Native child extension UI capability is unavailable');
  const previousGet = runner.getUIContext;
  const previousHas = runner.hasUI;
  runner.getUIContext = getUI;
  runner.hasUI = hasUI;
  const restores = [], wrappedTools = new WeakSet();
  const refresh = () => {
    for (const name of session.getAllToolNames()) {
      const tool = session.getToolByName(name);
      if (!tool || typeof tool.execute !== 'function' || wrappedTools.has(tool)) continue;
      const execute = tool.execute;
      const wrapped = function(callId, params, signal, onUpdate, context) {
        return execute.call(this, callId, params, signal, onUpdate, { ...context, ui, hasUI: true });
      };
      tool.execute = wrapped;
      wrappedTools.add(tool);
      restores.push(() => { if (tool.execute === wrapped) tool.execute = execute; });
    }
  };
  refresh();
  const restore = () => {
    if (runner.getUIContext === getUI) runner.getUIContext = previousGet;
    if (runner.hasUI === hasUI) runner.hasUI = previousHas;
    for (const restoreTool of restores) restoreTool();
  };
  restore.refresh = refresh;
  return restore;
  function getUI() { return ui; }
  function hasUI() { return true; }
}

export function createRuntimeExplorer(getMain, getContext) {
  const observers = new Map();
  let activity = new AgentActivityIndex();
  let rootId;
  function clear() {
    for (const observer of observers.values()) { observer.unsubscribe?.(); observer.restoreUI?.(); observer.cancelRequests(); }
    observers.clear();
    activity = new AgentActivityIndex();
  }
  function current() {
    const context = getContext();
    if (!context) throw Error('Native session is unavailable');
    const mainId = context.sessionManager.getSessionId();
    if (rootId !== mainId) { clear(); rootId = mainId; }
    return { context, mainId, file: context.sessionManager.getSessionFile() };
  }
  async function refs() {
    const root = current();
    if (root.file) await registerPersistedSubagents(registry(), root.file, { hydrateHistory: true,
      shouldContinue: () => getContext()?.sessionManager.getSessionId() === root.mainId });
    if (getContext()?.sessionManager.getSessionId() !== root.mainId) throw Error('Main session changed during discovery');
    return registry().list().filter(ref => ref.id !== MAIN_AGENT_ID && ref.kind !== 'main' &&
      (ref.sessionFile && root.file ? sessionFileBelongsToRoot(ref.sessionFile, root.file) : belongsByParent(ref, root.mainId)));
  }
  function belongsByParent(ref, mainId) {
    const seen = new Set();
    while (ref && !seen.has(ref.id)) {
      seen.add(ref.id);
      if (ref.parentId === MAIN_AGENT_ID) return registry().get(MAIN_AGENT_ID)?.session?.sessionId === mainId;
      ref = ref.parentId ? registry().get(ref.parentId) : undefined;
    }
    return false;
  }
  async function resolveRef(request, live = false) {
    const root = current();
    if (request.sessionId && request.sessionId !== root.mainId) throw Error('Stale main-session control target');
    const ref = (await refs()).find(ref => keyOf(root.mainId, ref) === request.instanceKey);
    if (!ref) throw Error('Agent instance is unavailable or its transcript identity changed');
    if (live && (ref.kind === 'advisor' || !ref.session || ref.status === 'aborted'))
      throw Error(ref.kind === 'advisor' ? 'Advisor transcripts are observation-only' : 'Agent is not live; explicitly revive it before interacting');
    return ref;
  }
  function observe(ref) {
    const key = keyOf(current().mainId, ref);
    let state = observers.get(key);
    if (state && state.session === ref.session) return state;
    state?.unsubscribe?.(); state?.restoreUI?.(); state?.cancelRequests();
    const session = ref.session;
    const generation = randomUUID();
    const pending = new Map(), requests = new Map(), messageIds = new Map(), records = [], open = [];
    state = { session, generation, sequence: 0, base: 0, events: [], pending, requests, messageIds, records, open,
      cancelRequests() { for (const request of [...pending.values()]) request.resolve({ cancelled: true }); pending.clear(); requests.clear(); },
      reconcile(entries) {
        const claimed = new Set(records.filter(record => record.entryId).map(record => record.entryId));
        for (const record of records) if (!record.entryId) {
          const entry = entries.find(entry => entry.type === 'message' && !claimed.has(entry.id) &&
            identity(entry.message) === identity(record.message) && sameMessageContent(entry.message, record.message));
          if (entry) { record.entryId = entry.id; claimed.add(entry.id); messageIds.set(entry.id, record.id); }
        }
        for (const entry of entries) if (entry.type === 'message' && !claimed.has(entry.id)) {
          const id = `entry:${entry.id}`;
          records.push({ id, entryId: entry.id, message: entry.message }); messageIds.set(entry.id, id);
        }
      },
      emit(event) {
        const frame = { ...structuredClone(event), sequence: ++state.sequence, sessionFile: ref.sessionFile,
          sessionId: session?.sessionId ?? null };
        if (frame.message && ['message_start', 'message_update', 'message_end'].includes(frame.type)) {
          let record = frame.type === 'message_start' ? undefined : open.at(-1);
          if (!record) { record = { id: `message:${generation}:${state.sequence}`, message: frame.message }; records.push(record); open.push(record); }
          record.message = frame.message;
          frame.messageId = record.id;
          if (frame.type === 'message_end') open.pop();
          if (session) state.reconcile(session.sessionManager.getEntries());
          if (record.entryId) frame.entryId = record.entryId;
        }
        state.events.push(frame);
        if (state.events.length > 4096) { const removed = state.events.splice(0, 1024); state.base = removed.at(-1).sequence; }
      },
    };
    if (session) {
      state.reconcile(session.sessionManager.getEntries());
      state.unsubscribe = session.subscribe(event => state.emit(event));
    }
    observers.set(key, state);
    return state;
  }
  function bindUI(ref, state) {
    if (state.restoreUI) { state.restoreUI.refresh(); return; }
    const output = frame => {
      if (frame.method === 'cancel') state.requests.delete(frame.targetId);
      else if (['ask', 'select', 'confirm', 'input', 'editor'].includes(frame.method)) state.requests.set(frame.id, frame);
      state.emit(frame);
    };
    const ui = {
      supportsEditor: true,
      askDialog: (questions, options) => requestRpcAskDialog(state.pending, output, questions, options),
      select: (title, options, dialog) => requestRpcSelect(state.pending, output, title, options, dialog),
      editor: (title, prefill, dialog, options) => requestRpcEditor(state.pending, output, title, prefill, dialog, options),
      input: (title, placeholder, options) => requestRpcDialog(state.pending, output, options, undefined,
        { method: 'input', title, placeholder }, response => response.cancelled ? undefined : response.value),
      confirm: (title, message, options) => requestRpcDialog(state.pending, output, options, false,
        { method: 'confirm', title, message }, response => response.confirmed === true && !response.cancelled),
      notify: (message, type) => output({ type: 'extension_ui_request', id: randomUUID(), method: 'notify', message, notifyType: type }),
    };
    state.restoreUI = attachChildUI(ref.session, ui);
    state.ui = ui;
  }
  async function reviveCapability(ref) {
    if (ref.kind === 'advisor') return { canRevive: false, unavailableReason: 'Advisor transcripts are observation-only' };
    if (ref.session) return { canRevive: false, unavailableReason: null };
    if (!ref.sessionFile) return { canRevive: false, unavailableReason: 'Native transcript is unavailable' };
    const { SessionManager } = await import('@oh-my-pi/pi-coding-agent/session/session-manager');
    let peek;
    try { peek = await SessionManager.peekSessionInit(ref.sessionFile); }
    catch (error) { return { canRevive: false, unavailableReason: safeReason(error?.message ?? error) }; }
    if (!peek?.init) return { canRevive: false, unavailableReason: 'Persisted native session contract is missing' };
    if (peek.init.isolated) return { canRevive: false, unavailableReason: 'Isolated native runs cannot be cold-revived' };
    try { await access(peek.cwd); } catch { return { canRevive: false, unavailableReason: 'Original native workspace is unavailable' }; }
    return { canRevive: true, unavailableReason: null };
  }
  async function rosterRow(ref, mainEntries) {
    const session = ref.session;
    let persisted, transcriptError;
    if (!session && ref.sessionFile) {
      try { persisted = await readTranscript(ref.sessionFile); }
      catch (error) { transcriptError = safeReason(error?.message ?? error); }
    }
    const entries = session?.sessionManager.getEntries() ?? persisted?.entries ?? [];
    const messages = entries.filter(e => e.type === 'message').map(e => e.message);
    const init = entries.find(e => e.type === 'session_init');
    const users = messages.filter(m => m.role === 'user');
    const lastAssistant = messages.findLast(m => m.role === 'assistant');
    const failures = messages.filter(m => m.isError || m.stopReason === 'error' || m.stopReason === 'aborted');
    const serving = session?.servingModel;
    const selector = serving?.selector ?? ref.history?.resolvedModel;
    const slash = selector?.indexOf('/') ?? -1;
    const stats = session?.getSessionStats();
    const usageMessages = messages.filter(m => m.role === 'assistant' && m.usage);
    const sum = key => usageMessages.length ? usageMessages.reduce((total, m) => total + (m.usage[key] ?? 0), 0) : null;
    const artifacts = [];
    for (const [title, file] of [['Task output', ref.history?.outputPath], ['Patch', ref.history?.patchPath],
      ...(ref.history?.nestedPatchPaths ?? []).map(file => ['Nested patch', file])]) if (file) artifacts.push({ title, path: file, icon: title.includes('Patch') ? 'difference' : 'draft' });
    let ownerEntries = mainEntries;
    if (ref.parentId && ref.parentId !== MAIN_AGENT_ID) {
      const owner = registry().get(ref.parentId);
      ownerEntries = owner?.session?.sessionManager.getEntries() ?? (owner?.sessionFile ?
        (await readTranscript(owner.sessionFile).catch(() => ({ entries: [] }))).entries : []);
    }
    const dispatch = ownerEntries.find(e => e.type === 'message' && e.message.role === 'assistant' &&
      (e.message.content ?? []).some(b => b.type === 'toolCall' && b.name === 'task' &&
        (Array.isArray(b.arguments?.tasks) ? b.arguments.tasks : [b.arguments]).some(task => task?.name === ref.id)));
    const capability = await reviveCapability(ref);
    const live = !!session && ref.kind !== 'advisor' && ref.status !== 'aborted';
    const telemetry = ref.history?.metrics;
    return { instanceKey: keyOf(current().mainId, ref), id: ref.id, name: ref.displayName, parentId: ref.parentId === MAIN_AGENT_ID ? null : ref.parentId ?? null,
      lifecycle: ref.status, advisor: ref.kind === 'advisor', task: textOf(users[0]?.content), activity: ref.activity ?? '',
      result: ref.lifecycle?.acceptedAt ? (failures.length ? 'failed' : 'completed') : ref.status === 'aborted' ? 'cancelled' : null,
      error: transcriptError ?? (failures.length ? safeReason(failures.at(-1).errorMessage ?? textOf(failures.at(-1).content)) : null),
      modelId: session?.model?.id ?? (slash >= 0 ? selector.slice(slash + 1) : selector ?? lastAssistant?.model ?? null),
      providerId: session?.model?.provider ?? (slash >= 0 ? selector.slice(0, slash) : lastAssistant?.provider ?? null),
      modelRole: ref.history?.modelRole ?? null, thinking: session?.configuredThinkingLevel() ?? null,
      fallback: serving?.isFallback || ref.history?.resolvedModelIsFallback ? selector ?? null : null,
      workspace: session?.sessionManager.getCwd() ?? persisted?.header.cwd ?? null, sessionFile: ref.sessionFile,
      createdAtMillis: number(ref.createdAt), lastActivityMillis: number(ref.lastActivity), unread: null,
      stats: { elapsedMillis: number(telemetry?.durationMs), requests: stats ? stats.assistantMessages : number(telemetry?.requests),
        tools: stats?.toolCalls ?? number(telemetry?.tools), inputTokens: stats?.tokens.input ?? sum('input'),
        outputTokens: stats?.tokens.output ?? sum('output'), cacheReadTokens: stats?.tokens.cacheRead ?? sum('cacheRead'),
        cacheWriteTokens: stats?.tokens.cacheWrite ?? sum('cacheWrite'), cost: stats?.cost ?? number(ref.history?.directCost),
        contextTokens: session ? number(session.lastPromptTokens()) : number(telemetry?.contextTokens), contextWindow: number(session?.model?.contextWindow ?? telemetry?.contextWindow) },
      artifacts, jobs: jobs(session), canSend: live, canStop: live && session.isStreaming,
      canKill: ref.kind !== 'advisor' && ref.status !== 'aborted', canSetModel: live, ...capability,
      dispatchEntryId: dispatch?.id ?? null, dispatchChatItemId: dispatch ? `entry:${dispatch.id}` : null,
      definitionName: ref.history?.agent ?? init?.agent ?? null };
  }
  function jobs(session) {
    const snapshot = session?.getAsyncJobSnapshot({ recentLimit: 200 });
    return [...(snapshot?.running ?? []), ...(snapshot?.recent ?? [])].map(job => {
      const detail = session.inspectAsyncJob(job.id);
      return { id: job.id, command: detail?.command ?? job.command ?? job.label ?? '', cwd: detail?.cwd ?? null,
        pid: detail?.pids?.[0] ?? null, status: job.status, exitCode: detail?.exitCode ?? null,
        output: detail?.output ?? '', outputPath: detail?.artifactId ? `artifact://${detail.artifactId}` : null };
    });
  }
  async function snapshot(request) {
    const ref = await resolveRef(request);
    const state = observe(ref);
    const session = ref.session;
    // A scoped UI on an already-live session does not revive it or start a
    // turn; future native Ask/approval waits stay with this child.
    if (session && ref.kind !== 'advisor') bindUI(ref, state);
    const prefix = `${state.generation}:`;
    const cursor = typeof request.cursor === 'string' && request.cursor.startsWith(prefix) ? Number(request.cursor.slice(prefix.length)) : NaN;
    let reset = !Number.isSafeInteger(cursor) || cursor < state.base || cursor > state.sequence;
    let transcript;
    if (!session) {
      if (!ref.sessionFile) throw Error('Native child transcript is unavailable');
      const fileStat = await stat(ref.sessionFile);
      const stamp = `${fileStat.ino}:${fileStat.size}:${fileStat.mtimeMs}`;
      if (state.fileStamp !== stamp) { reset = true; state.fileStamp = stamp; }
      if (reset) state.transcript = await readTranscript(ref.sessionFile);
      transcript = state.transcript;
    }
    // Capture a synchronous baseline after persistence settles, with the listener
    // already installed. Events at/before the baseline are represented in entries.
    if (session && reset) await session.settleInFlightMessagePersistence();
    const baseline = state.sequence;
    const entries = reset ? session?.sessionManager.getEntries() ?? transcript?.entries ?? [] : undefined;
    if (session) state.reconcile(session.sessionManager.getEntries());
    const events = reset ? [] : state.events.filter(event => event.sequence > cursor).map(event => {
      const record = state.records.find(record => record.id === event.messageId);
      return record?.entryId ? { ...event, entryId: record.entryId } : event;
    });
    if (reset && session) {
      const live = session.agent.state.streamMessage;
      if (live) {
        let record = state.open.at(-1);
        if (!record) { record = { id: `stream:${state.generation}:${baseline}`, message: live }; state.records.push(record); state.open.push(record); }
        const messageId = record.id;
        events.push({ type: 'message_start', message: structuredClone(live), messageId, sessionFile: ref.sessionFile, sessionId: session.sessionId });
        events.push({ type: 'message_update', message: structuredClone(live), messageId, assistantMessageEvent: { type: 'start', partial: structuredClone(live) } });
      }
      for (const event of session.activeToolExecutionUpdates()) events.push(structuredClone(event));
    }
    const messageIds = entries ? Object.fromEntries(entries.filter(e => e.type === 'message').map(e => [e.id, state.messageIds.get(e.id) ?? `entry:${e.id}`])) : undefined;
    return { sessionId: session?.sessionId ?? transcript?.header.id ?? null, sessionFile: ref.sessionFile,
      workspace: session?.sessionManager.getCwd() ?? transcript?.header.cwd ?? null,
      providerId: session?.model?.provider ?? transcript?.entries.findLast(e => e.type === 'message' && e.message.role === 'assistant')?.message.provider ?? null,
      modelId: session?.model?.id ?? transcript?.entries.findLast(e => e.type === 'message' && e.message.role === 'assistant')?.message.model ?? null,
      thinking: session?.configuredThinkingLevel() ?? transcript?.entries.findLast(e => e.type === 'thinking_level_change')?.thinkingLevel ?? null,
      isRunning: session?.isStreaming === true,
      reset, ...(entries ? { entries, messageIds, leafId: session?.sessionManager.getLeafId() ?? transcript?.leafId ?? null } : {}),
      events, cursor: `${state.generation}:${baseline}`, requests: [...state.requests.values()], queue: session ? queueSnapshot(session) : [] };
  }
  async function exportAgent(ref) {
    if (!ref.sessionFile) throw Error('Cannot export a child without a native transcript');
    const directory = join(current().context.sessionManager.getCwd(), '.omp', 'exports');
    await mkdir(directory, { recursive: true, mode: 0o700 });
    const outputPath = join(directory, `agent-${basename(ref.sessionFile, '.jsonl')}-${randomUUID()}.html`);
    let path;
    if (ref.session) path = await ref.session.exportToHtml(outputPath);
    else {
      // A readonly manager-shaped view is sufficient for the native HTML exporter;
      // opening a writable SessionManager here would repair/mutate viewed history.
      const transcript = await readTranscript(ref.sessionFile);
      const { exportSessionToHtml } = await import('@oh-my-pi/pi-coding-agent/export/html');
      path = await exportSessionToHtml({ getSessionFile: () => ref.sessionFile, getHeader: () => transcript.header,
        getEntries: () => transcript.entries, getLeafId: () => transcript.leafId }, undefined, { outputPath, includeSubSessions: true });
    }
    return { path, name: basename(path), mimeType: 'text/html' };
  }
  async function setModel(ref, request) {
    const session = ref.session;
    const model = session.getAvailableModels().find(model => model.provider === request.providerId && model.id === request.modelId);
    if (!model) throw Error('Requested model is not available to the native child session');
    await session.setModelTemporary(model);
    return { providerId: session.model?.provider, modelId: session.model?.id, thinking: session.configuredThinkingLevel() };
  }
  async function prompt(ref, request) {
    const text = request.text;
    if (typeof text !== 'string' || (!text.trim() && !request.images?.length)) throw Error('Message is empty');
    if (/^\s*[\/!$%]/.test(text)) throw Error('Use a supported focused command or return to Main; shell/Python/slash input is not a child prompt');
    if (!['prompt', 'steer', 'followUp'].includes(request.behavior ?? 'prompt')) throw Error('Unknown child prompt behavior');
    const session = ref.session, state = observe(ref);
    bindUI(ref, state);
    const oldIds = new Set(session.sessionManager.getEntries().map(e => e.id));
    const admitted = Promise.withResolvers();
    const run = session.prompt(text, { images: request.images, streamingBehavior: request.behavior === 'followUp' ? 'followUp' : 'steer',
      runCommands: false, throwOnDrop: true, expandPromptTemplates: false, onPromptAdmitted: () => admitted.resolve() });
    run.then(() => admitted.resolve(), error => admitted.reject(error));
    run.catch(error => state.emit({ type: 'notice', level: 'error', message: safeReason(error?.message ?? error) }));
    await admitted.promise;
    await session.settleInFlightMessagePersistence();
    const entry = session.sessionManager.getEntries().find(e => !oldIds.has(e.id) && e.type === 'message' && e.message.role === 'user');
    const queued = queueSnapshot(session).some(item => item.text === text);
    if (entry) state.reconcile(session.sessionManager.getEntries());
    return { accepted: true, queued, ...(entry ? { entryId: entry.id, messageId: state.messageIds.get(entry.id) ?? `entry:${entry.id}` } : {}) };
  }
  async function control(ref, request) {
    if (ref.kind === 'advisor') throw Error('Advisor transcripts are observation-only');
    const session = ref.session;
    const owner = current().mainId;
    if (request.operation === 'revive') {
      const capability = await reviveCapability(ref);
      if (!capability.canRevive) throw Error(capability.unavailableReason ?? 'Agent is already live');
      if (current().mainId !== owner || registry().get(ref.id) !== ref) throw Error('Agent instance changed before revival');
      const live = await AgentLifecycleManager.global().ensureLive(ref.id);
      if (registry().get(ref.id)?.session !== live || current().mainId !== owner) throw Error('Agent changed while reviving');
      return { lifecycle: registry().get(ref.id).status };
    }
    if (request.operation === 'kill') {
      if (!(await AgentLifecycleManager.global().release(ref.id, ref, { tombstone: true }))) throw Error('Agent instance changed before kill');
      return { lifecycle: registry().get(ref.id)?.status };
    }
    if (!session || ref.status === 'aborted') throw Error('Agent is not live');
    if (request.operation === 'stop') { await session.abort({ reason: 'User stopped the child execution' }); return { isRunning: session.isStreaming }; }
    if (request.operation === 'approval_mode') throw Error('This native release has no session-local approval-mode setter');
    if (['update_queue', 'remove_queue'].includes(request.operation)) {
      const item = queueSnapshot(session).find(item => item.id === request.queueId);
      if (!item || !item.removable) throw Error('Queued message has already been delivered or changed');
      if (request.operation === 'update_queue') {
        if (typeof request.text !== 'string' || !request.text.trim()) throw Error('Queued message is empty');
        if (/^\s*[\/!$%]/.test(request.text)) throw Error('Focused queue cannot execute commands');
      }
      const removed = session.takeQueuedMessage(item.text, item.queue);
      if (!removed) throw Error('Queued message has already been delivered');
      if (request.operation === 'update_queue') {
        try { await session[item.queue === 'steering' ? 'steer' : 'followUp'](request.text, removed.images); }
        catch (error) { await session[item.queue === 'steering' ? 'steer' : 'followUp'](removed.text, removed.images); throw error; }
      }
      return { queue: queueSnapshot(session) };
    }
    throw Error('Unknown native child control');
  }
  return async request => {
    if (request.op === 'explorer_hub') {
      const all = await refs(), root = current();
      const mainEntries = root.context.sessionManager.getEntries();
      const agents = await Promise.all(all.map(ref => rosterRow(ref, mainEntries)));
      await Promise.all(all.map(ref => activity.sync(ref.id, ref.sessionFile)));
      const activityLimit = request.activityLimit === 2000 ? 2000 : 20;
      const byId = new Map(agents.map(agent => [agent.id, agent]));
      const rows = activity.query({ agentIds: new Set(byId.keys()), limit: activityLimit }).map(row => ({
        id: row.id, agentId: row.agentId, instanceKey: byId.get(row.agentId).instanceKey,
        kind: row.status === 'error' ? 'error' : row.kind === 'tool' ? 'tool' : row.kind === 'message' ? 'communication' : row.kind,
        text: [row.title, row.summary].filter(Boolean).join(' · '), timestamp: new Date(row.timestamp).toISOString(),
        entryId: row.entryId ?? null, chatItemId: row.entryId ? `entry:${row.entryId}` : null }));
      for (const ref of all) {
        const entries = ref.session?.sessionManager.getEntries() ?? (ref.sessionFile ? (await readTranscript(ref.sessionFile).catch(() => ({ entries: [] }))).entries : []);
        for (const entry of entries) {
          const message = entry.type === 'message' ? entry.message : entry.type === 'custom_message' ? entry : null;
          if (!message || !['irc:message', 'irc:relay', 'irc'].includes(message.customType)) continue;
          rows.push({ id: `communication:${ref.id}:${entry.id}`, agentId: ref.id, instanceKey: keyOf(root.mainId, ref),
            kind: 'communication', text: textOf(message.content), timestamp: entry.timestamp,
            entryId: entry.id, chatItemId: `entry:${entry.id}` });
        }
      }
      for (const ref of all) if (ref.lifecycle?.acceptedAt || ref.status === 'aborted' || ref.status === 'parked') rows.push({
        id: `lifecycle:${keyOf(root.mainId, ref)}:${ref.status}:${ref.lastActivity}`, agentId: ref.id, instanceKey: keyOf(root.mainId, ref),
        kind: 'lifecycle', text: ref.status, timestamp: new Date(ref.lastActivity).toISOString(), entryId: null });
      rows.sort((a, b) => a.timestamp.localeCompare(b.timestamp) || a.id.localeCompare(b.id));
      return { mainSessionId: root.mainId, agents, activity: rows.slice(-activityLimit), activityLimit, connected: true };
    }
    if (request.op === 'explorer_agent_snapshot') return snapshot(request);
    const mutating = ['explorer_agent_prompt', 'explorer_agent_model', 'explorer_agent_thinking', 'explorer_agent_respond'];
    const ref = await resolveRef(request, mutating.includes(request.op));
    if (request.instanceKey !== keyOf(current().mainId, ref) || registry().get(ref.id) !== ref) throw Error('Stale native child target');
    switch (request.op) {
      case 'explorer_agent_export': return exportAgent(ref);
      case 'explorer_agent_control': return control(ref, request);
      case 'explorer_agent_prompt': return prompt(ref, request);
      case 'explorer_agent_model': return setModel(ref, request);
      case 'explorer_agent_thinking': {
        if (!ref.session.getAvailableEffortSelectors().includes(request.level)) throw Error('Thinking level is not supported by the native child model');
        ref.session.setThinkingLevel(request.level, false);
        return { thinking: ref.session.configuredThinkingLevel() };
      }
      case 'explorer_agent_respond': {
        const state = observe(ref), pending = state.pending.get(request.requestId), presentation = state.requests.get(request.requestId);
        if (!pending || !presentation) throw Error('Child UI request has expired or belongs to another instance');
        const response = { ...request, type: 'extension_ui_response', id: request.requestId };
        if (presentation.method === 'ask' && !request.cancelled) {
          const result = askSubmission(presentation.questions, request.answers ?? request.results);
          response.answers = result.results.map(answer => ({ id: answer.id, selectedOptions: answer.selectedOptions, customInput: answer.customInput }));
        }
        pending.resolve(response); state.requests.delete(request.requestId);
        return { accepted: true };
      }
      case 'explorer_agent_commands': return { commands: ref.kind === 'advisor' || !ref.session ? ['/export'] : ['/btw', '/model', '/think', '/export', '/jobs', '/cost'] };
      case 'explorer_agent_command': {
        const command = String(request.command ?? '').replace(/^\//, '');
        const args = String(request.args ?? '').trim();
        if (!['btw', 'model', 'think', 'export', 'jobs', 'cost'].includes(command)) throw Error('Command is unavailable in focused child view; return to Main');
        if (command === 'export') return exportAgent(ref);
        if (!ref.session || ref.kind === 'advisor' || ref.status === 'aborted') throw Error('Command requires a live ordinary child');
        if (command === 'jobs') { if (args) throw Error('Focused /jobs is read-only'); return { jobs: jobs(ref.session) }; }
        if (command === 'cost') { if (args) throw Error('Focused /cost accepts no arguments'); return { stats: ref.session.getSessionStats() }; }
        if (command === 'model') {
          if (!args) return { models: ref.session.getAvailableModels().map(m => ({ providerId: m.provider, modelId: m.id, name: m.name })) };
          const slash = args.indexOf('/');
          return setModel(ref, { providerId: args.slice(0, slash), modelId: args.slice(slash + 1) });
        }
        if (command === 'think') {
          if (!args) return { thinking: ref.session.configuredThinkingLevel(), levels: ref.session.getAvailableEffortSelectors() };
          if (!ref.session.getAvailableEffortSelectors().includes(args)) throw Error('Thinking selector is unavailable for this child model');
          ref.session.setThinkingLevel(args, false); return { thinking: ref.session.configuredThinkingLevel() };
        }
        if (!args) throw Error('/btw requires a question');
        const result = await ref.session.runEphemeralTurn({ promptText: args });
        return { text: result.replyText, result };
      }
      default: throw Error('Unknown native explorer operation');
    }
  };
}
