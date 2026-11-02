import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile, readdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createContext, SourceTextModule, SyntheticModule } from 'node:vm';
import * as fs from 'node:fs/promises';
import * as path from 'node:path';
import * as crypto from 'node:crypto';

// Run with node --experimental-vm-modules --test session.test.mjs.
// Only the native explorer dependency is replaced; the production poller,
// filesystem transport, account operations, and instance admission all execute.
test('session admits its own instance and preserves private receipts', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'wawlink-session-instance-'));
  const instance = 'new-instance';
  const handlers = new Map();
  const effects = [];
  // The environment is local to the production module, not the test process.
  const vmContext = createContext({
    process: { env: { WAWLINK_MANAGEMENT_DIRECTORY: directory, WAWLINK_MANAGEMENT_INSTANCE: instance }, stderr: process.stderr },
    setTimeout, clearTimeout,
  });
  const explorer = new SyntheticModule(['createSessionExplorer', 'explorerError'], function () {
    this.setExport('createSessionExplorer', () => async request => {
      effects.push(['explorer', request.id]);
      return { success: true };
    });
    this.setExport('explorerError', error => String(error));
  }, { context: vmContext });
  const namespaceModule = namespace => new SyntheticModule(Object.keys(namespace), function () {
    for (const [key, value] of Object.entries(namespace)) this.setExport(key, value);
  }, { context: vmContext });
  const source = await readFile(new URL('../../jvmSharedMain/resources/omp-management/session.mjs', import.meta.url), 'utf8');
  const module = new SourceTextModule(source, {
    context: vmContext,
  });
  await module.link(specifier => {
    if (specifier === 'node:fs/promises') return namespaceModule(fs);
    if (specifier === 'node:path') return namespaceModule(path);
    if (specifier === 'node:crypto') return namespaceModule(crypto);
    if (specifier === './explorer.mjs') return explorer;
    throw Error(`Unexpected import: ${specifier}`);
  });
  await module.evaluate();
  module.namespace.default({ on: (name, handler) => handlers.set(name, handler) });
  const context = {
    modelRegistry: { authStorage: {
      credentials: { reload: async () => effects.push(['reload']) },
      sessions: { pin: () => { effects.push(['pin']); return true; } },
    } },
    sessionManager: { getSessionId: () => 'session' },
    isIdle: () => true,
    hasPendingMessages: () => false,
  };
  let nextId = 0;
  const request = async (op, target) => {
    const id = (++nextId).toString(16);
    await writeFile(join(directory, `request-${id}.json`), JSON.stringify({ id, op, instance: target, provider: 'vendor', accountId: '1' }));
    return id;
  };
  const waitFor = async predicate => {
    const deadline = Date.now() + 5000;
    while (!await predicate()) {
      assert.ok(Date.now() < deadline, 'session poller did not finish');
      await new Promise(resolve => setTimeout(resolve, 10));
    }
  };
  try {
    for (const op of ['pin', 'explorer_label']) {
      await request(op, 'old-instance');
      await request(op, undefined);
    }
    await handlers.get('session_start')({}, context);
    assert.equal((await fs.stat(join(directory, 'channel.json'))).mode & 0o777, 0o600);
    assert.equal((await fs.stat(directory)).mode & 0o777, 0o700);
    assert.deepEqual(effects, []);
    assert.deepEqual((await readdir(directory)).filter(name => name.startsWith('request-')), []);
    for (const op of ['pin', 'explorer_label']) {
      await request(op, 'old-instance');
      await request(op, undefined);
    }
    await waitFor(async () => !(await readdir(directory)).some(name => name.startsWith('request-')));
    assert.deepEqual(effects, []);
    assert.deepEqual((await readdir(directory)).filter(name => name.startsWith('response-')), []);
    for (const op of ['pin', 'explorer_label']) {
      const id = await request(op, instance);
      const response = join(directory, `response-${id}.json`);
      await waitFor(async () => (await readdir(directory)).includes(`response-${id}.json`));
      assert.deepEqual(JSON.parse(await readFile(response, 'utf8')), { success: true, instance });
      assert.equal((await fs.stat(response)).mode & 0o777, 0o600);
      assert.equal((await fs.stat(response)).gid, (await fs.stat(directory)).gid);
    }
    assert.deepEqual(effects.map(effect => effect[0]), ['reload', 'pin', 'explorer']);
    assert.deepEqual((await readdir(directory)).filter(name => name.endsWith('.tmp')), []);
  } finally {
    handlers.get('session_shutdown')();
    await rm(directory, { recursive: true, force: true });
  }
});
