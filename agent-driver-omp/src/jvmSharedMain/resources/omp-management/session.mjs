import { mkdir, readdir, readFile, writeFile, rename, unlink } from 'node:fs/promises';
import { join } from 'node:path';
import { createSessionExplorer, explorerError } from './explorer.mjs';

/** Native-session control, isolated by an app-owned request directory and acknowledged by that session. */
export default function managementSessionExtension(pi) {
  const directory = process.env.WAWLINK_MANAGEMENT_DIRECTORY;
  const instance = process.env.WAWLINK_MANAGEMENT_INSTANCE;
  if (!directory || !instance) return;
  let poller;
  let generation = 0;
  let context;
  const explorer = createSessionExplorer(pi, () => context);
  async function respond(request, response) {
    const destination = join(directory, `response-${request.id}.json`);
    await writeFile(destination + '.tmp', JSON.stringify(response), { mode: 0o600 });
    await rename(destination + '.tmp', destination);
  }
  async function channelState(ready, error) {
    const destination = join(directory, 'channel.json');
    await writeFile(destination + '.tmp', JSON.stringify({ instance, ready, error }), { mode: 0o600 });
    await rename(destination + '.tmp', destination);
  }
  async function drain() {
    if (!context) return;
    for (const name of await readdir(directory)) {
      if (!/^request-[a-f0-9-]+\.json$/.test(name)) continue;
      const file = join(directory, name);
      let request;
      try { request = JSON.parse(await readFile(file, 'utf8')); } catch { continue; }
      await unlink(file);
      // Navigation/Ask/model admission may wait. Their cancellation and replies
      // must overtake that wait instead of deadlocking behind the drain queue.
      // Explorer reads do not reload credentials or alter explicit account pins.
      if (typeof request.op === 'string' && request.op.startsWith('explorer_')) {
        void explorer(request).then(response => respond(request, response)).catch(error => {
          process.stderr.write(`Session explorer response failed: ${explorerError(error, request)}\n`);
        });
        continue;
      }
      let response;
      try {
        const storage = context.modelRegistry.authStorage;
        const sessionId = context.sessionManager.getSessionId();
        await storage.credentials.reload();
        if (request.op === 'pin') {
          if (!context.isIdle() || context.hasPendingMessages()) throw Error('Session is busy');
          if (typeof request.provider !== 'string') throw Error('Invalid provider');
          if (request.accountId == null) {
            storage.sessions.release(request.provider, sessionId);
          } else {
            const id = Number(request.accountId);
            if (!Number.isSafeInteger(id) || !storage.sessions.pin(request.provider, sessionId, id)) throw Error('Account is unavailable');
          }
          response = { success: true };
        } else if (request.op === 'pins') {
          const explicit = new Set();
          // Native API reports explicitness while a false include predicate leaves every affinity unchanged.
          storage.sessions.inherit(sessionId, `wawlink-inspect-${request.id}`, (provider, isExplicit) => { if (isExplicit) explicit.add(provider); return false; });
          const pins = {};
          for (const provider of explicit) {
            const active = storage.oauth.accounts(provider, sessionId).find(account => account.active);
            if (active) pins[provider] = String(active.credentialId);
          }
          response = { success: true, pins };
        } else throw Error('Unknown management operation');
      } catch (error) { response = { success: false, error: explorerError(error, request) }; }
      await respond(request, response);
    }
  }
  async function poll(currentGeneration) {
    if (currentGeneration !== generation || !context) return;
    try {
      await drain();
    } catch (error) {
      if (currentGeneration === generation) await channelState(false, explorerError(error));
      return;
    }
    if (currentGeneration === generation && context) {
      // Bun uses raw inotify syscalls which Proroot does not translate. Schedule
      // only after this scan finishes, without overlapping or queuing scans.
      poller = setTimeout(() => {
        void poll(currentGeneration).catch(error => {
          process.stderr.write(`Session management channel failed: ${explorerError(error)}\n`);
        });
      }, 100);
      poller.unref();
    }
  }
  pi.on('session_start', async (_event, ctx) => {
    const currentGeneration = ++generation;
    clearTimeout(poller);
    context = ctx;
    try {
      await mkdir(directory, { recursive: true, mode: 0o700 });
      await channelState(true);
      await poll(currentGeneration);
    } catch (error) {
      await channelState(false, explorerError(error));
      throw error;
    }
  });
  pi.on('session_shutdown', () => {
    ++generation;
    clearTimeout(poller);
    poller = undefined;
    context = undefined;
  });
}
