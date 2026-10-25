import { basename, dirname, join } from 'node:path';
import { access, mkdir, stat } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { setImmediate } from 'node:timers/promises';
import { AgentRegistry, MAIN_AGENT_ID } from '@oh-my-pi/pi-coding-agent/registry/agent-registry';
import { AgentLifecycleManager } from '@oh-my-pi/pi-coding-agent/registry/agent-lifecycle';
import { registerPersistedSubagents, sessionFileBelongsToRoot } from '@oh-my-pi/pi-coding-agent/registry/persisted-agents';
import { AgentActivityIndex } from '/opt/omp/src/activity/index.ts';
// Not an exported host module: this native parser owns only per-instance file
// caches, never a registry/lifecycle singleton. Stateful imports use host aliases.
import { loadSessionFile, resolveBlobRefsInEntries } from '@oh-my-pi/pi-coding-agent/session/session-loader';
import { EPHEMERAL_MODEL_CHANGE_ROLE, resolveSessionLeafId } from '@oh-my-pi/pi-coding-agent/session/session-entries';
import { assistantTurnProducedOutput } from '@oh-my-pi/pi-coding-agent/session/messages';
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
  let rootId, hub;
  function clear() {
    for (const observer of observers.values()) { observer.unsubscribe?.(); observer.restoreUI?.(); observer.cancelRequests(); }
    observers.clear();
    for (const watcher of hub?.watchers.values() ?? []) watcher.unsubscribe();
    hub = { cache: new Map(), watchers: new Map(), directories: new Map(), initialized: false,
      discovering: true, discoveryError: null, flight: null, activity: new AgentActivityIndex() };
  }
  function current() {
    const context = getContext();
    if (!context) throw Error('Native session is unavailable');
    const mainId = context.sessionManager.getSessionId(), file = context.sessionManager.getSessionFile();
    const id = JSON.stringify([mainId, file]);
    if (rootId !== id) { clear(); rootId = id; }
    return { context, mainId, file, hub };
  }
  function isCurrent(root) {
    const manager = getContext()?.sessionManager;
    return hub === root.hub && manager?.getSessionId() === root.mainId && manager?.getSessionFile() === root.file;
  }
  function refs(root = current()) {
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
    let ref = refs(root).find(ref => keyOf(root.mainId, ref) === request.instanceKey);
    if (!ref) {
      await refresh(root);
      if (!isCurrent(root)) throw Error('Main session changed during discovery');
      ref = refs(root).find(ref => keyOf(root.mainId, ref) === request.instanceKey);
    }
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
  function watchSession(state, key, session) {
    let watcher = state.watchers.get(key);
    if (watcher && watcher.session === session) return watcher;
    watcher?.unsubscribe();
    watcher = { session, revision: 0, unsubscribe: () => {} };
    if (session) watcher.unsubscribe = session.subscribe(event => {
      if (['message_end', 'turn_end', 'agent_end', 'model_changed', 'auto_compaction_end', 'branch_summary_end'].includes(event.type)) watcher.revision++;
    });
    state.watchers.set(key, watcher);
    return watcher;
  }
  async function fileStamp(file) {
    if (!file) return null;
    try {
      const value = await stat(file);
      return `${value.dev}:${value.ino}:${value.size}:${value.mtimeMs}:${value.ctimeMs}`;
    } catch (error) {
      if (error?.code === 'ENOENT' || error?.code === 'ENOTDIR') return null;
      throw error;
    }
  }
  async function directoryStamps(root) {
    const directories = new Set();
    if (root.file?.endsWith('.jsonl')) {
      const base = root.file.slice(0, -6);
      directories.add(base);
      for (const ref of refs(root)) if (ref.sessionFile && sessionFileBelongsToRoot(ref.sessionFile, root.file)) {
        for (let dir = dirname(ref.sessionFile); dir !== base && dir.startsWith(`${base}/`); dir = dirname(dir)) directories.add(dir);
      }
    }
    return new Map(await Promise.all([...directories].map(async dir => [dir, await fileStamp(dir)])));
  }
  function recordDispatch(entry, dispatch) {
    if (entry.type !== 'message' || entry.message.role !== 'assistant') return;
    for (const block of Array.isArray(entry.message.content) ? entry.message.content : []) if (block.type === 'toolCall' && block.name === 'task') {
      for (const task of Array.isArray(block.arguments?.tasks) ? block.arguments.tasks : [block.arguments])
        if (typeof task?.name === 'string' && !dispatch.has(task.name)) dispatch.set(task.name, entry.id);
    }
  }
  function mainDispatch(entries, cached) {
    const append = cached?.entryCount <= entries.length &&
      (cached.entryCount === 0 || entries[cached.entryCount - 1]?.id === cached.lastEntryId);
    const dispatch = append ? cached.summary.dispatch : new Map();
    for (let index = append ? cached.entryCount : 0; index < entries.length; index++) recordDispatch(entries[index], dispatch);
    return { dispatch };
  }
  function summarize(entries, header, leafId) {
    const dispatch = new Map(), communications = [], byId = new Map();
    const totals = { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 };
    let init, task = '', hasUser = false, lastAssistant, failure, usageCount = 0, directCost = 0;
    for (const entry of entries) {
      byId.set(entry.id, entry);
      if (entry.type === 'session_init' && !init) init = entry;
      if (entry.type === 'model_usage') directCost += entry.usage?.cost?.total ?? 0;
      const message = entry.type === 'message' ? entry.message : entry.type === 'custom_message' ? entry : null;
      if (!message) continue;
      if (['irc:message', 'irc:relay', 'irc'].includes(message.customType)) communications.push({
        id: entry.id, text: textOf(message.content), timestamp: entry.timestamp });
      if (entry.type !== 'message') continue;
      if (message.role === 'user' && !hasUser) { task = textOf(message.content); hasUser = true; }
      if (message.isError || message.stopReason === 'error' || message.stopReason === 'aborted')
        failure = safeReason(message.errorMessage ?? textOf(message.content));
      if (message.role !== 'assistant') continue;
      lastAssistant = { model: message.model, provider: message.provider };
      if (message.usage) {
        usageCount++;
        for (const key of Object.keys(totals)) totals[key] += message.usage[key] ?? 0;
        directCost += message.usage.cost?.total ?? 0;
      }
      recordDispatch(entry, dispatch);
    }
    const visited = new Set(), metrics = { requests: 0, tools: 0 };
    let modelRole, servedModel, resolvedModel, resolvedModelIsFallback, latestModelChange, contextTokens;
    for (let id = leafId; id && !visited.has(id); id = byId.get(id)?.parentId) {
      visited.add(id);
      const entry = byId.get(id);
      if (entry?.type === 'model_change') {
        latestModelChange ??= entry;
        if (entry.role && entry.role !== EPHEMERAL_MODEL_CHANGE_ROLE) modelRole ??= entry.role;
        if (servedModel && !resolvedModel && (entry.model === servedModel || entry.model?.startsWith(`${servedModel}@`))) {
          resolvedModel = entry.model; resolvedModelIsFallback = entry.resolvedModelIsFallback;
        }
      }
      if (entry?.type !== 'message' || entry.message.role !== 'assistant') continue;
      const message = entry.message;
      if (!servedModel && message.provider && message.model && assistantTurnProducedOutput(message)) servedModel = `${message.provider}/${message.model}`;
      metrics.requests++;
      metrics.tools += (Array.isArray(message.content) ? message.content : []).filter(block => block.type === 'toolCall').length;
      contextTokens ??= message.usage?.totalTokens || undefined;
    }
    resolvedModel ??= servedModel ?? latestModelChange?.model;
    resolvedModelIsFallback ??= servedModel ? false : latestModelChange?.resolvedModelIsFallback;
    const start = Date.parse(header?.timestamp ?? entries[0]?.timestamp), end = Date.parse(entries.at(-1)?.timestamp);
    return { task, failure, lastAssistant, definitionName: init?.agent, workspace: header?.cwd,
      dispatch, communications, totals: usageCount ? totals : {}, directCost,
      modelRole, resolvedModel, resolvedModelIsFallback,
      metrics: metrics.requests ? { ...metrics, contextTokens, durationMs: Number.isFinite(start) && Number.isFinite(end) ? Math.max(0, end - start) : null } : null };
  }
  function refresh(root) {
    const state = root.hub;
    if (state.flight) return state.flight;
    // Yield an event-loop turn, not just a microtask: the caller must be able
    // to serialize and begin persisting the registry-only response first.
    state.flight = setImmediate().then(async () => {
      let directories = await directoryStamps(root);
      if (!isCurrent(root)) return;
      const changed = !state.initialized || directories.size !== state.directories.size ||
        [...directories].some(([dir, stamp]) => state.directories.get(dir) !== stamp);
      if (changed) {
        state.discovering = true;
        if (root.file) await registerPersistedSubagents(registry(), root.file, {
          hydrateHistory: false, shouldContinue: () => isCurrent(root) });
        if (!isCurrent(root)) return;
        const beforeScan = directories;
        directories = await directoryStamps(root);
        if (!isCurrent(root)) return;
        // Retain pre-scan stamps so a change racing discovery is retried.
        state.directories = new Map([...directories].map(([dir, stamp]) => [dir, beforeScan.has(dir) ? beforeScan.get(dir) : stamp]));
        state.initialized = true;
      }
      const all = refs(root), keys = new Set(all.map(ref => keyOf(root.mainId, ref)));
      const mainKey = JSON.stringify([root.mainId, MAIN_AGENT_ID, root.file]);
      keys.add(mainKey);
      for (const [key, watcher] of state.watchers) if (!keys.has(key)) { watcher.unsubscribe(); state.watchers.delete(key); state.cache.delete(key); }
      const main = getMain();
      const validTarget = (ref, key, session) => isCurrent(root) && ref.session === session &&
        (ref.id === MAIN_AGENT_ID || (registry().get(ref.id) === ref && keyOf(root.mainId, ref) === key));
      const targets = [{ id: MAIN_AGENT_ID, session: main, sessionFile: root.file }, ...all];
      let next = 0;
      await Promise.all(Array.from({ length: Math.min(4, targets.length) }, async () => {
        for (;;) {
          const ref = targets[next++];
          if (!ref || !isCurrent(root)) return;
          const key = ref.id === MAIN_AGENT_ID ? mainKey : keyOf(root.mainId, ref);
          const session = ref.session;
          const watcher = watchSession(state, key, session), revision = watcher.revision;
          let cached = state.cache.get(key), stamp;
          try {
            stamp = await fileStamp(ref.sessionFile);
            if (!isCurrent(root)) return;
            if (!validTarget(ref, key, session)) continue;
            const leafId = session?.sessionManager.getLeafId();
            if (!cached || cached.error || cached.stamp !== stamp || cached.session !== session || cached.revision !== revision || cached.leafId !== leafId) {
              state.discovering = true;
              const transcript = session ? null : ref.sessionFile ? await readTranscript(ref.sessionFile) : null;
              if (!validTarget(ref, key, session)) continue;
              const manager = session?.sessionManager;
              const entries = manager?.getEntries() ?? transcript?.entries ?? [];
              const summary = ref.id === MAIN_AGENT_ID ? mainDispatch(entries, cached)
                : summarize(entries, transcript?.header ?? manager?.getHeader?.(), manager?.getLeafId() ?? transcript?.leafId);
              const capability = ref.id === MAIN_AGENT_ID ? null : await reviveCapability(ref);
              if (!validTarget(ref, key, session)) continue;
              cached = { stamp, session, revision, leafId, summary, capability,
                entryCount: entries.length, lastEntryId: entries.at(-1)?.id,
                stats: ref.id === MAIN_AGENT_ID ? null : session?.getSessionStats(), error: null, activityStamp: cached?.activityStamp };
              state.cache.set(key, cached);
            }
            if (ref.id !== MAIN_AGENT_ID) cached.jobs = jobs(session);
            if (ref.id !== MAIN_AGENT_ID && cached.activityStamp !== stamp) {
              await state.activity.sync(ref.id, ref.sessionFile);
              if (validTarget(ref, key, session)) cached.activityStamp = stamp;
            }
          } catch (error) {
            if (!validTarget(ref, key, session)) continue;
            state.cache.set(key, { ...cached, session, stamp, revision, error: safeReason(error?.message ?? error) });
          }
        }
      }));
      if (isCurrent(root)) state.discoveryError = null;
    }).catch(error => {
      if (isCurrent(root)) state.discoveryError = safeReason(error?.message ?? error);
    }).finally(() => {
      state.flight = null;
      if (isCurrent(root)) state.discovering = false;
    });
    return state.flight;
  }
  function rosterRow(ref, root) {
    const session = ref.session, cached = root.hub.cache.get(keyOf(root.mainId, ref));
    const summary = cached?.summary, stats = cached?.stats;
    const serving = session?.servingModel;
    const selector = serving?.selector ?? summary?.resolvedModel ?? ref.history?.resolvedModel;
    const slash = selector?.indexOf('/') ?? -1;
    const artifacts = [];
    for (const [title, file] of [['Task output', ref.history?.outputPath], ['Patch', ref.history?.patchPath],
      ...(ref.history?.nestedPatchPaths ?? []).map(file => ['Nested patch', file])]) if (file) artifacts.push({ title, path: file, icon: title.includes('Patch') ? 'difference' : 'draft' });
    const owner = ref.parentId && ref.parentId !== MAIN_AGENT_ID ? registry().get(ref.parentId) : null;
    const ownerKey = ref.parentId && ref.parentId !== MAIN_AGENT_ID ? (owner ? keyOf(root.mainId, owner) : null)
      : JSON.stringify([root.mainId, MAIN_AGENT_ID, root.file]);
    const dispatch = root.hub.cache.get(ownerKey)?.summary?.dispatch.get(ref.id);
    const live = !!session && ref.kind !== 'advisor' && ref.status !== 'aborted';
    const telemetry = summary?.metrics ?? ref.history?.metrics;
    const capability = ref.kind === 'advisor' ? { canRevive: false, unavailableReason: 'Advisor transcripts are observation-only' }
      : session ? { canRevive: false, unavailableReason: null }
      : cached?.capability ?? { canRevive: false, unavailableReason: cached?.error ?? (ref.sessionFile ? 'Native session details are loading' : 'Native transcript is unavailable') };
    return { instanceKey: keyOf(root.mainId, ref), id: ref.id, name: ref.displayName, parentId: ref.parentId === MAIN_AGENT_ID ? null : ref.parentId ?? null,
      lifecycle: ref.status, advisor: ref.kind === 'advisor', task: summary?.task ?? '', activity: ref.activity ?? '',
      result: ref.lifecycle?.acceptedAt ? (summary?.failure ? 'failed' : 'completed') : ref.status === 'aborted' ? 'cancelled' : null,
      error: cached?.error ?? summary?.failure ?? null,
      modelId: session?.model?.id ?? (slash >= 0 ? selector.slice(slash + 1) : selector ?? summary?.lastAssistant?.model ?? null),
      providerId: session?.model?.provider ?? (slash >= 0 ? selector.slice(0, slash) : summary?.lastAssistant?.provider ?? null),
      modelRole: summary?.modelRole ?? ref.history?.modelRole ?? null, thinking: session?.configuredThinkingLevel() ?? null,
      fallback: serving?.isFallback || summary?.resolvedModelIsFallback || (!summary && ref.history?.resolvedModelIsFallback) ? selector ?? null : null,
      workspace: session?.sessionManager.getCwd() ?? summary?.workspace ?? null, sessionFile: ref.sessionFile,
      createdAtMillis: number(ref.createdAt), lastActivityMillis: number(ref.lastActivity), unread: null,
      stats: { elapsedMillis: number(telemetry?.durationMs), requests: stats ? stats.assistantMessages : number(telemetry?.requests),
        tools: stats?.toolCalls ?? number(telemetry?.tools), inputTokens: stats?.tokens.input ?? number(summary?.totals.input),
        outputTokens: stats?.tokens.output ?? number(summary?.totals.output), cacheReadTokens: stats?.tokens.cacheRead ?? number(summary?.totals.cacheRead),
        cacheWriteTokens: stats?.tokens.cacheWrite ?? number(summary?.totals.cacheWrite), cost: stats?.cost ?? number(summary?.directCost ?? ref.history?.directCost),
        contextTokens: session ? number(session.lastPromptTokens()) : number(telemetry?.contextTokens), contextWindow: number(session?.model?.contextWindow ?? telemetry?.contextWindow) },
      artifacts, jobs: cached?.jobs ?? [], canSend: live, canStop: live && session.isStreaming,
      canKill: ref.kind !== 'advisor' && ref.status !== 'aborted', canSetModel: live, ...capability,
      dispatchEntryId: dispatch ?? null,
      definitionName: ref.history?.agent ?? summary?.definitionName ?? null };
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
      const root = current(), all = refs(root);
      // Nothing on this path reads a transcript or waits for discovery. The
      // single background flight publishes enrichment to subsequent polls.
      if (!root.hub.discoveryError) for (const ref of all)
        if (!root.hub.cache.has(keyOf(root.mainId, ref))) root.hub.discovering = true;
      refresh(root);
      const agents = all.map(ref => rosterRow(ref, root));
      const activityLimit = request.activityLimit === 2000 ? 2000 : 20;
      const byId = new Map(agents.map(agent => [agent.id, agent]));
      const rows = root.hub.activity.query({ agentIds: new Set(byId.keys()), limit: activityLimit }).map(row => ({
        id: row.id, agentId: row.agentId, instanceKey: byId.get(row.agentId).instanceKey,
        kind: row.status === 'error' ? 'error' : row.kind === 'tool' ? 'tool' : row.kind === 'message' ? 'communication' : row.kind,
        text: [row.title, row.summary].filter(Boolean).join(' · '), timestamp: new Date(row.timestamp).toISOString(),
        entryId: row.entryId ?? null, chatItemId: row.entryId ? `entry:${row.entryId}` : null }));
      for (const ref of all) {
        for (const entry of root.hub.cache.get(keyOf(root.mainId, ref))?.summary?.communications ?? []) {
          rows.push({ id: `communication:${ref.id}:${entry.id}`, agentId: ref.id, instanceKey: keyOf(root.mainId, ref),
            kind: 'communication', text: entry.text, timestamp: entry.timestamp,
            entryId: entry.id, chatItemId: `entry:${entry.id}` });
        }
      }
      for (const ref of all) if (ref.lifecycle?.acceptedAt || ref.status === 'aborted' || ref.status === 'parked') rows.push({
        id: `lifecycle:${keyOf(root.mainId, ref)}:${ref.status}:${ref.lastActivity}`, agentId: ref.id, instanceKey: keyOf(root.mainId, ref),
        kind: 'lifecycle', text: ref.status, timestamp: new Date(ref.lastActivity).toISOString(), entryId: null });
      rows.sort((a, b) => a.timestamp.localeCompare(b.timestamp) || a.id.localeCompare(b.id));
      return { mainSessionId: root.mainId, agents, activity: rows.slice(-activityLimit), activityLimit, connected: true,
        discovering: root.hub.discovering, discoveryError: root.hub.discoveryError };
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
