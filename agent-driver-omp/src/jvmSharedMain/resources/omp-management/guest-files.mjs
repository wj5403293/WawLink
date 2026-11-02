import fs from 'node:fs/promises';
import { constants } from 'node:fs';
import { dirname, basename, join, posix } from 'node:path';
import { createHash, randomUUID } from 'node:crypto';
import { createInterface } from 'node:readline';

// This process runs as the configured guest account. It never chmods an existing
// inode or exposes a host rootfs path. Only error codes and guest paths leave it.
const home = process.env.WAWLINK_FILE_HOME;
const mounts = JSON.parse(process.env.WAWLINK_FILE_MOUNTS || '[]');
const virtual = ['/proc', '/sys', '/dev', '/run', '/host-rootfs'];
const handles = new Map();
const active = new Map();
const chunkSize = 64 * 1024;
let closing = false;
function fail(code, path) { throw Object.assign(new Error(code), { code, path }); }
function within(path, root) { return path === root || path.startsWith(`${root.replace(/\/$/, '')}/`); }
function normalized(path) {
  if (typeof path !== 'string' || !path.startsWith('/') || path.includes('\0') || posix.normalize(path) !== path) fail('EINVAL', path);
  if (virtual.some(root => within(path, root))) fail('EPERM', path);
  if (home && within(path, `${home}/.omp`) && mounts.some(m => within(path, m.target) || within(m.target, `${home}/.omp`))) fail('EPERM', path);
  return path;
}
function mutable(path) {
  normalized(path);
  if (['/', '/workspace', home, `${home}/.omp`, `${home}/.omp/agent`].includes(path) ||
      mounts.some(m => path === m.target || within(m.target, path) || !m.readWrite && within(path, m.target))) fail('EPERM', path);
}
async function info(path) { try { return await fs.lstat(path); } catch (e) { if (e.code === 'ENOENT') return null; throw e; } }
async function checked(path, missing = false) {
  normalized(path);
  if (home && within(path, `${home}/.omp`)) {
    for (const mount of mounts) {
      let canonical;
      try { canonical = await fs.realpath(mount.target); } catch (error) { if (error.code === 'ENOENT') continue; throw error; }
      if (within(path, canonical) || within(canonical, `${home}/.omp`)) fail('EPERM', path);
    }
  }
  if (path !== '/') await checkedDirectory(dirname(path), missing);
  const st = await info(path);
  if (!st) { if (missing) return null; fail('ENOENT', path); }
  if (st.isSymbolicLink()) fail('ELOOP', path);
  if (!st.isDirectory() && !st.isFile()) fail('EINVAL', path);
  return st;
}
async function checkedDirectory(path, missing = false) {
  const st = await checked(path, missing);
  if (st && !st.isDirectory()) fail('ENOTDIR', path);
  return st;
}
async function mkdirs(path) {
  normalized(path);
  const st = await checked(path, true);
  if (st) { if (!st.isDirectory()) fail('ENOTDIR', path); return; }
  if (mounts.some(m => !m.readWrite && within(path, m.target))) fail('EROFS', path);
  await mkdirs(dirname(path));
  try { await fs.mkdir(path, { mode: 0o700 }); } catch (e) { if (e.code !== 'EEXIST') throw e; }
  await checkedDirectory(path);
}
function entry(path, st) { return { name: basename(path), directory: st.isDirectory(), regular: st.isFile(), size: st.size, modified: Math.trunc(st.mtimeMs) }; }
function identity(st) { return st ? `${st.dev}:${st.ino}:${st.size}:${st.mtimeMs}:${st.ctimeMs}` : ''; }
async function readHandle(path) {
  const st = await checked(path, true);
  if (!st) return null;
  if (!st.isFile()) fail('EISDIR', path);
  const fd = await fs.open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
  try {
    const opened = await fd.stat();
    if (!opened.isFile() || opened.dev !== st.dev || opened.ino !== st.ino) fail('ESTALE', path);
    return fd;
  } catch (e) { await fd.close(); throw e; }
}
async function version(path) {
  const fd = await readHandle(path);
  if (!fd) return '';
  try {
    const hash = createHash('sha256'), buffer = Buffer.allocUnsafe(chunkSize);
    for (;;) { const { bytesRead } = await fd.read(buffer); if (!bytesRead) break; hash.update(buffer.subarray(0, bytesRead)); }
    return hash.digest('hex');
  } finally { await fd.close(); }
}
async function beginWrite(path, expectedVersion, createOnly) {
  mutable(path);
  await mkdirs(dirname(path));
  const before = await checked(path, true);
  if (before && (!before.isFile() || before.nlink !== 1)) fail('EPERM', path);
  if (createOnly && before) fail('EEXIST', path);
  if (expectedVersion != null && await version(path) !== expectedVersion) fail('CONFLICT', path);
  const temporary = join(dirname(path), `.wawlink-${randomUUID()}.tmp`);
  const fd = await fs.open(temporary, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, 0o600);
  return { path, temporary, fd, before: identity(before), expectedVersion, createOnly, offset: 0, write: true };
}
async function abortWrite(handle) {
  await handle.fd.close();
  try { await fs.unlink(handle.temporary); } catch (e) { if (e.code !== 'ENOENT') throw e; }
}
async function commitWrite(handle) {
  const { path, temporary, fd, expectedVersion, createOnly } = handle;
  try {
    await fd.sync();
    await checkedDirectory(dirname(path));
    const st = await checked(path, true);
    if (st && (!st.isFile() || st.nlink !== 1)) fail('EPERM', path);
    if (identity(st) !== handle.before || expectedVersion != null && await version(path) !== expectedVersion) fail('CONFLICT', path);
    if (closing) fail('ECANCELED', path);
    if (createOnly) { await fs.link(temporary, path); await fs.unlink(temporary); }
    else await fs.rename(temporary, path);
  } finally { await abortWrite(handle); }
}
async function writeAll(fd, buffer) {
  let offset = 0;
  while (offset < buffer.length) { const { bytesWritten } = await fd.write(buffer, offset, buffer.length - offset); if (!bytesWritten) fail('EIO'); offset += bytesWritten; }
}
async function tree(path, result) {
  const st = await checked(path);
  mutable(path);
  if (st.isDirectory()) for (const name of await fs.readdir(path)) await tree(join(path, name), result);
  result.push({ path, directory: st.isDirectory(), identity: identity(st) });
}
function titleSlot(title) {
  const record = { type: 'title', v: 1, title: '', source: 'user', updatedAt: new Date().toISOString(), pad: '' };
  for (const point of title) {
    const candidate = record.title + point;
    if (Buffer.byteLength(JSON.stringify({ ...record, title: candidate })) >= 256) break;
    record.title = candidate;
  }
  record.pad = ' '.repeat(255 - Buffer.byteLength(JSON.stringify(record)));
  return Buffer.from(JSON.stringify(record) + '\n');
}
async function rewriteHistory(args) {
  const input = await readHandle(args.path);
  if (!input) fail('ENOENT', args.path);
  let output;
  try {
    output = await beginWrite(args.path, null, false);
    const buffer = Buffer.allocUnsafe(chunkSize);
    let pending = Buffer.alloc(0), header;
    while (!header) {
      const { bytesRead } = await input.read(buffer);
      if (!bytesRead) fail('EINVAL', args.path);
      pending = Buffer.concat([pending, buffer.subarray(0, bytesRead)]);
      for (;;) {
        const end = pending.indexOf(10);
        if (end < 0) break;
        const record = JSON.parse(pending.subarray(0, end).toString('utf8'));
        pending = pending.subarray(end + 1);
        if (record.type === 'title' && record.v === 1) continue;
        if (record.type !== 'session') fail('EINVAL', args.path);
        header = { ...record, cwd: args.workspace, title: args.title, titleSource: 'user' };
        if (args.newId != null) header.id = args.newId;
        if (args.parent != null) header.parentSession = args.parent;
        break;
      }
    }
    await writeAll(output.fd, titleSlot(args.title));
    await writeAll(output.fd, Buffer.from(JSON.stringify(header) + '\n'));
    await writeAll(output.fd, pending);
    for (;;) { const { bytesRead } = await input.read(buffer); if (!bytesRead) break; await writeAll(output.fd, buffer.subarray(0, bytesRead)); }
    const committing = output; output = null;
    await commitWrite(committing);
    return true;
  } finally { await input.close(); if (output) await abortWrite(output); }
}
async function readJson(path) {
  const handle = await readHandle(path);
  if (!handle) return null;
  try { return JSON.parse(await handle.readFile({ encoding: 'utf8' })); } finally { await handle.close(); }
}
async function dispatch(op, args, signal) {
  const path = args.path;
  switch (op) {
    case 'stat': { const st = await checked(path, true); return st ? entry(path, st) : null; }
    case 'listOpen': {
      await checkedDirectory(path);
      const directory = await fs.opendir(path), token = randomUUID();
      handles.set(token, { directory, path });
      return token;
    }
    case 'listChunk': {
      const handle = handles.get(args.token); if (!handle?.directory) fail('EBADF');
      const result = [];
      while (result.length < 256) {
        const next = await handle.directory.read(); if (!next) return { entries: result, done: true };
        const child = join(handle.path, next.name), st = await info(child);
        if (st) result.push(entry(child, st));
      }
      return { entries: result, done: false };
    }
    case 'listClose': {
      const handle = handles.get(args.token); if (handle?.directory) { handles.delete(args.token); await handle.directory.close(); }
      return true;
    }
    case 'mkdirs': await mkdirs(path); return true;
    case 'version': return version(path);
    case 'readOpen': {
      const fd = await readHandle(path); if (!fd) return null;
      const token = randomUUID(); handles.set(token, { fd, path, offset: 0 }); return token;
    }
    case 'readChunk': {
      const handle = handles.get(args.token); if (!handle || handle.write) fail('EBADF');
      const buffer = Buffer.allocUnsafe(chunkSize), { bytesRead } = await handle.fd.read(buffer);
      return bytesRead ? buffer.subarray(0, bytesRead).toString('base64') : null;
    }
    case 'readClose': {
      const handle = handles.get(args.token); if (handle && !handle.write) { handles.delete(args.token); await handle.fd.close(); } return true;
    }
    case 'writeOpen': { const handle = await beginWrite(path, args.expectedVersion, args.createOnly); const token = randomUUID(); handles.set(token, handle); return token; }
    case 'writeChunk': {
      const handle = handles.get(args.token); if (!handle?.write) fail('EBADF');
      if (typeof args.data !== 'string' || args.data.length > Math.ceil(chunkSize / 3) * 4 || args.offset !== handle.offset) fail('EINVAL');
      const buffer = Buffer.from(args.data, 'base64'); await writeAll(handle.fd, buffer); handle.offset += buffer.length; return true;
    }
    case 'writeCommit': { const handle = handles.get(args.token); if (!handle?.write) fail('EBADF'); handles.delete(args.token); await commitWrite(handle); return true; }
    case 'writeAbort': { const handle = handles.get(args.token); if (handle?.write) { handles.delete(args.token); await abortWrite(handle); } return true; }
    case 'delete': {
      mutable(path); const st = await checked(path, true); if (!st) return true;
      if (args.expectedVersion != null && await version(path) !== args.expectedVersion) fail('CONFLICT', path);
      const items = [];
      if (args.recursive) await tree(path, items); else items.push({ path, directory: st.isDirectory(), identity: identity(st) });
      // Complete preflight before deletion. Recheck each inode without following links.
      for (const item of items) {
        const current = await checked(item.path);
        if (current.dev + ':' + current.ino !== item.identity.split(':').slice(0, 2).join(':')) fail('ESTALE', item.path);
        if (item.directory) await fs.rmdir(item.path); else await fs.unlink(item.path);
      }
      return true;
    }
    case 'rewriteHistory': return rewriteHistory(args);
    case 'awaitResponse': {
      if (!/^[a-f0-9-]+$/.test(args.requestId) || typeof args.instance !== 'string') fail('EINVAL');
      const deadline = Date.now() + Math.min(Math.max(args.timeoutMs, 1), 300000);
      for (;;) {
        if (signal.aborted || closing) fail('ECANCELED');
        const response = await readJson(join(args.directory, `response-${args.requestId}.json`));
        if (response) {
          if (response.instance !== args.instance) fail('INSTANCE_MISMATCH', args.directory);
          return true;
        }
        const channel = await readJson(join(args.directory, 'channel.json'));
        if (channel && channel.instance === args.instance && !channel.ready) fail('CHANNEL', args.directory);
        if (Date.now() >= deadline) fail('ETIMEDOUT', args.directory);
        await new Promise(resolve => setTimeout(resolve, 50));
      }
    }
    default: fail('ENOSYS');
  }
}
function send(value) { if (!closing) process.stdout.write(JSON.stringify(value) + '\n'); }
const lines = createInterface({ input: process.stdin, crlfDelay: Infinity });
lines.on('line', line => {
  let message;
  try { message = JSON.parse(line); } catch { return; }
  if (typeof message.id !== 'string') return;
  if (message.op === 'cancel') { active.get(message.args?.id)?.abort(); return; }
  const controller = new AbortController(); active.set(message.id, controller);
  Promise.resolve().then(() => dispatch(message.op, message.args || {}, controller.signal))
    .then(result => send({ id: message.id, result }))
    .catch(error => send({ id: message.id, error: { code: /^[A-Z_]+$/.test(error.code || '') ? error.code : 'EIO', path: typeof error.path === 'string' ? error.path : '' } }))
    .finally(() => active.delete(message.id));
});
lines.on('close', async () => {
  closing = true;
  for (const controller of active.values()) controller.abort();
  for (const handle of handles.values()) try { if (handle.write) await abortWrite(handle); else if (handle.directory) await handle.directory.close(); else await handle.fd.close(); } catch {}
  handles.clear();
});
