import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { createContext, SourceTextModule, SyntheticModule } from 'node:vm';

// node --experimental-vm-modules --test explorer-attachments.test.mjs
// Production history/runtime consumers execute; only the installed native OMP
// boundary is replaced with deterministic session/registry contracts.
async function modules(registry = { list: () => [], get: () => undefined }) {
  const context = createContext({ structuredClone, Promise });
  const cache = new Map();
  const native = {
    '@oh-my-pi/pi-coding-agent/tools/ask': { AskTool: class {}, recoverAskQuestions: () => undefined },
    '@oh-my-pi/pi-coding-agent/session/turn-persistence': {
      sameMessageContent: (left, right) => JSON.stringify(left.content) === JSON.stringify(right.content),
      sessionMessagePersistenceKey: message => `${message.role}:${message.timestamp}`,
    },
    '@oh-my-pi/pi-tui/chat/transcript-entry': {
      isUserRequestEntry: entry => entry.type === 'message' && entry.message.role === 'user',
      transcriptEntryMessage: entry => entry.message,
      userTurnDraft: entry => typeof entry.message.content === 'string' ? entry.message.content
        : entry.message.content.filter(block => block.type === 'text').map(block => block.text).join('\n'),
    },
    '@oh-my-pi/pi-coding-agent/registry/agent-registry': { AgentRegistry: { global: () => registry }, MAIN_AGENT_ID: 'main' },
    '@oh-my-pi/pi-coding-agent/registry/agent-lifecycle': { AgentLifecycleManager: { global: () => ({}) } },
    '@oh-my-pi/pi-coding-agent/registry/persisted-agents': {
      registerPersistedSubagents: async () => {}, sessionFileBelongsToRoot: (file, root) => file.startsWith(`${root.slice(0, -6)}/`),
    },
    '@oh-my-pi/pi-coding-agent/activity/index': { AgentActivityIndex: class {} },
    '@oh-my-pi/pi-coding-agent/session/session-loader': { loadSessionFile: async () => { throw Error('Transcript read was not requested'); }, resolveBlobRefsInEntries: async () => {} },
    '@oh-my-pi/pi-coding-agent/session/session-entries': { EPHEMERAL_MODEL_CHANGE_ROLE: 'ephemeral_model_change', resolveSessionLeafId: entries => entries.at(-1)?.id },
    '@oh-my-pi/pi-coding-agent/session/messages': { assistantTurnProducedOutput: () => false },
    '@oh-my-pi/pi-coding-agent/session/queued-messages': { isUserAuthoredQueuedMessage: () => true, toRestoredQueuedMessage: item => item },
    '@oh-my-pi/pi-coding-agent/session/blob-store': { BlobStore: class {} },
    '@oh-my-pi/pi-utils/dirs': { getBlobsDir: () => '/unused' },
    '@oh-my-pi/pi-coding-agent/modes/rpc/rpc-mode': {
      requestRpcAskDialog() {}, requestRpcSelect() {}, requestRpcEditor() {}, requestRpcDialog() {},
    },
    './diagnostics.mjs': { safeReason: error => String(error) },
  };
  const namespaceModule = namespace => new SyntheticModule(Object.keys(namespace), function () {
    for (const [key, value] of Object.entries(namespace)) this.setExport(key, value);
  }, { context });
  async function load(specifier) {
    if (cache.has(specifier)) return cache.get(specifier);
    let module;
    if (specifier.startsWith('node:')) module = namespaceModule(await import(specifier));
    else if (native[specifier]) module = namespaceModule(native[specifier]);
    else if (['./explorer-history.mjs', './explorer-runtime.mjs'].includes(specifier)) {
      const source = await readFile(new URL(`../../jvmSharedMain/resources/omp-management/${specifier.slice(2)}`, import.meta.url), 'utf8');
      module = new SourceTextModule(source, { context, identifier: specifier });
    } else throw Error(`Unexpected native import: ${specifier}`);
    cache.set(specifier, module);
    return module;
  }
  const history = await load('./explorer-history.mjs');
  await history.link(load); await history.evaluate();
  const runtime = await load('./explorer-runtime.mjs');
  await runtime.link(load); await runtime.evaluate();
  return { history: history.namespace, runtime: runtime.namespace };
}
const marker = '\n\n附件（容器内真实文件）：\n';
const wire = (text, name = 'note.txt', path = '/workspace/note.txt') => `${text}${marker}- ${name}: ${path}`;
const plain = value => JSON.parse(JSON.stringify(value));
const entry = (id, text) => ({ id, parentId: null, type: 'message', message: { role: 'user', content: text, timestamp: 1 } });

test('history index, selected detail and navigation restore attachments without exposing the suffix', async () => {
  const { history } = await modules();
  const raw = '  原始文字\n  ';
  const entries = [entry('text', wire(raw)), entry('only', wire('', '图.svg', '/workspace/图.svg'))];
  const manager = {
    getSessionId: () => 'native', getSessionFile: () => '/session/main.jsonl', getEntries: () => entries, getLeafId: () => 'only',
  };
  const snapshot = history.historySnapshot(manager);
  assert.equal(snapshot.nodes[0].searchText, `${raw}\nnote.txt`);
  assert.ok(!snapshot.nodes[0].title.includes('容器内真实文件'));
  assert.ok(!snapshot.nodes[0].searchText.includes('/workspace/'));
  assert.equal(snapshot.nodes[1].title, '用户 · 1个附件');
  assert.equal(snapshot.nodes[1].hasCopyContent, false);
  assert.ok(snapshot.nodes.every(node => !('entry' in node) && !('attachments' in node) && !('rawRecord' in node)));
  const selected = history.historyDetail(manager, { kind: 'preview', entryId: 'text', version: snapshot.nodes[0].version });
  assert.equal(selected.userText, raw);
  assert.equal(selected.attachments[0].path, '/workspace/note.txt');
  assert.equal(selected.attachments[0].source, 'CONTAINER');
  assert.equal(selected.entry, entries[0]);
  const only = history.historyDetail(manager, { kind: 'copy', entryId: 'only', version: snapshot.nodes[1].version });
  assert.equal(only.userText, '');
  assert.equal(only.attachments[0].mimeType, 'image/svg+xml');
  const restored = history.restoredNavigation({ editorText: entries[1].message.content }).prompt;
  assert.equal(restored.text, '');
  assert.equal(restored.wireText, entries[1].message.content);
  assert.deepEqual(plain(restored.attachments), plain(only.attachments));
  const originalVersion = snapshot.nodes[0].version;
  entries[0] = entry('text', wire('changed'));
  const delta = history.historySnapshot(manager, { since: snapshot.revision });
  assert.equal(delta.reset, false);
  assert.equal(delta.nodes.length, 1);
  assert.throws(() => history.historyDetail(manager, { kind: 'preview', entryId: 'text', version: originalVersion }), /Stale/);
});

test('strict history parser preserves malformed, fenced and nontrailing examples and stable metadata', async () => {
  const { history } = await modules();
  const examples = [
    'inline 附件（容器内真实文件）：\n- note.txt: /workspace/note.txt',
    `${wire('example')}\nordinary text`, `${wire('example')}\n`,
    `${marker}- note.txt: relative/path`, `${marker}- note.txt: /a/../note.txt`,
    `${marker}- dir/note.txt: /workspace/note.txt`, `${marker}- note.txt: /workspace/note.txt\r`,
    `~~~text${wire('example')}\n~~~`,
  ];
  for (const example of examples) assert.equal(history.parseAttachmentPrompt(example).text, example);
  const metadata = { id: 'original', name: 'note.txt', path: '/workspace/note.txt', source: 'WORKSPACE', mimeType: 'text/plain', sizeBytes: 12 };
  assert.equal(history.parseAttachmentPrompt(wire('  \n  '), [metadata]).attachments[0], metadata);
  const restored = history.parseAttachmentPrompt(wire('')).attachments[0];
  assert.equal(restored.id, `legacy:${createHash('sha256').update('note.txt\n/workspace/note.txt').digest('hex')}`);
  const originalExample = wire('literal example');
  assert.equal(history.parseAttachmentPrompt(wire(originalExample, 'real.png', '/real.png')).text, originalExample);
  assert.equal(history.parseAttachmentPrompt(wire('```text')).text, '```text');
  assert.equal(history.parseAttachmentPrompt(wire('', ' literal\\name.txt ', '/workspace/literal\\name.txt')).attachments[0].name, ' literal\\name.txt ');
});

test('child prompt, native snapshots, blank queue edit and take keep transport identity isolated', async () => {
  const listeners = new Set(), entries = [], queue = { steering: [], followUp: [] }, effects = [];
  let busy = true;
  const manager = {
    getSessionId: () => 'native-child', getSessionFile: () => '/session/main/child.jsonl', getCwd: () => '/workspace',
    getEntries: () => entries, getLeafId: () => entries.at(-1)?.id ?? null,
  };
  const session = {
    sessionId: 'native-child', sessionManager: manager, isStreaming: false,
    extensionRunner: { getUIContext: () => ({}), hasUI: () => false }, getAllToolNames: () => [],
    subscribe: listener => { listeners.add(listener); return () => listeners.delete(listener); },
    settleInFlightMessagePersistence: async () => {}, activeToolExecutionUpdates: () => [],
    configuredThinkingLevel: () => 'high', getAvailableModels: () => [], getAvailableEffortSelectors: () => ['high'],
    agent: { state: {}, peekLiveSteeredMessages: () => [], peekSteeringQueue: () => queue.steering, peekFollowUpQueue: () => queue.followUp },
    getQueuedMessages: () => ({ steering: queue.steering.map(item => item.text), followUp: queue.followUp.map(item => item.text), liveSteered: 0 }),
    prompt: async (text, options) => {
      effects.push(['prompt', text]);
      if (busy) queue[options.streamingBehavior === 'followUp' ? 'followUp' : 'steering'].push({ text, images: options.images ?? [] });
      else {
        const item = entry(`entry-${entries.length}`, text); entries.push(item);
        for (const listener of listeners) listener({ type: 'message_end', message: item.message });
      }
      options.onPromptAdmitted();
    },
    takeQueuedMessage: (text, kind) => {
      effects.push(['take', text, kind]);
      const index = queue[kind].findIndex(item => item.text === text);
      return index < 0 ? undefined : queue[kind].splice(index, 1)[0];
    },
    steer: async (text, images) => { effects.push(['steer', text]); queue.steering.push({ text, images }); },
    followUp: async (text, images) => { effects.push(['followUp', text]); queue.followUp.push({ text, images }); },
  };
  const child = { id: 'child', parentId: 'main', kind: 'agent', status: 'idle', sessionFile: '/session/main/child.jsonl', session };
  const main = { id: 'main', session: { sessionId: 'native-main' } };
  const registry = { list: () => [main, child], get: id => id === 'main' ? main : id === 'child' ? child : undefined };
  const { runtime } = await modules(registry);
  const explorer = runtime.createRuntimeExplorer(() => main.session, () => ({ sessionManager: {
    getSessionId: () => 'native-main', getSessionFile: () => '/session/main.jsonl', getCwd: () => '/workspace',
  } }));
  const target = { instanceKey: JSON.stringify(['native-main', 'child', child.sessionFile]), sessionId: 'native-main' };
  const call = (op, body = {}) => explorer({ ...target, op, ...body });
  const raw = '  \n保留\n  ', sent = wire(raw);
  const admission = await call('explorer_agent_prompt', { text: sent, behavior: 'followUp' });
  assert.equal(admission.queued, true);
  let snapshot = await call('explorer_agent_snapshot');
  let queued = snapshot.queue[0];
  assert.equal(queued.text, raw); assert.equal(queued.wireText, sent);
  assert.equal(queued.attachments[0].name, 'note.txt'); assert.equal(queued.queue, 'followUp');
  const emptyWire = wire('');
  await call('explorer_agent_control', { operation: 'update_queue', queueId: queued.id, queue: queued.queue, originalText: queued.wireText, text: emptyWire });
  assert.deepEqual(effects.at(-2), ['take', sent, 'followUp']);
  assert.deepEqual(effects.at(-1), ['followUp', emptyWire]);
  snapshot = await call('explorer_agent_snapshot', { cursor: snapshot.cursor });
  queued = snapshot.queue[0];
  assert.equal(queued.text, ''); assert.equal(queued.attachments.length, 1);
  const count = effects.length;
  await assert.rejects(call('explorer_agent_control', { operation: 'remove_queue', queueId: queued.id, queue: queued.queue, originalText: '' }), /changed/);
  await assert.rejects(call('explorer_agent_prompt', { sessionId: 'old-main', text: sent }), /Stale/);
  assert.equal(effects.length, count);
  await call('explorer_agent_control', { operation: 'remove_queue', queueId: queued.id, queue: queued.queue, originalText: queued.wireText });
  assert.deepEqual(effects.at(-1), ['take', emptyWire, 'followUp']);
  busy = false;
  await call('explorer_agent_prompt', { text: emptyWire, behavior: 'prompt' });
  const reset = await call('explorer_agent_snapshot');
  assert.equal(reset.entries[0].userText, ''); assert.equal(reset.entries[0].attachments.length, 1);
  assert.equal(reset.entries[0].message.content, emptyWire);
  const live = await call('explorer_agent_snapshot', { cursor: snapshot.cursor });
  assert.equal(live.events[0].userText, ''); assert.equal(live.events[0].attachments.length, 1);
  assert.equal(live.events[0].message.content, emptyWire);
});
