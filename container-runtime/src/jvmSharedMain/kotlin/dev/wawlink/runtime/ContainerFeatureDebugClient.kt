package dev.wawlink.runtime

import kotlinx.serialization.json.*

/** Installed by the configured guest identity; root may reclaim checked host-created managed paths. */
internal suspend fun installFeatureDebugClient(runtime: ContainerRuntime, containerId: String, endpoint: JsonObject) {
    val payload = buildJsonObject {
        put("endpoint", endpoint)
        put("client", FEATURE_DEBUG_CLIENT)
    }.toString().toByteArray()
    featureCommand(runtime, containerId,
        "exec /usr/local/bin/bun -e " + shellQuote(FEATURE_DEBUG_INSTALL),
        runningOnly = true, stdinBytes = payload)
}

private const val FEATURE_DEBUG_INSTALL = """
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
let temp;
try {
  const payload = JSON.parse(fs.readFileSync(0, 'utf8'));
  const home = fs.realpathSync(process.env.HOME || '');
  if (!path.isAbsolute(home)) throw Error('Guest HOME must be absolute');
  const uid = process.getuid(), gid = process.getgid();
  const mappedUid = process.env.WAWLINK_MAPPED_HOST_UID;
  const mappedGid = process.env.WAWLINK_MAPPED_HOST_GID;
  const mapped = /^\d+$/.test(mappedUid || '') && /^\d+$/.test(mappedGid || '') &&
    Number.isSafeInteger(Number(mappedUid)) && Number.isSafeInteger(Number(mappedGid));
  const owned = info => info.uid === uid || (mapped && info.uid === Number(mappedUid));
  function checkOwner(info, created = false) {
    if (!owned(info) && uid !== 0 && !created) throw Error('Debugger path has a different owner');
  }
  function claim(fd, info, created = false) {
    checkOwner(info, created);
    if (info.uid === uid && info.gid === gid) return;
    try { fs.fchownSync(fd, uid, gid); }
    catch (error) {
      // UID mapping may expose the real App owner, and setgid may retain an inherited GID.
      // Only an already-owned inode can survive an unsupported ownership adjustment.
      if (!['EPERM', 'EINVAL', 'ENOSYS'].includes(error.code) || !owned(info) || !owned(fs.fstatSync(fd))) throw error;
    }
  }
  function checked(location, directory, action) {
    const before = fs.lstatSync(location);
    if (before.isSymbolicLink() || (directory ? !before.isDirectory() : !before.isFile() || before.nlink !== 1)) throw Error('Debugger managed path has an unsafe type or hard link');
    if (fs.realpathSync(location) !== location || !location.startsWith(home.endsWith(path.sep) ? home : home + path.sep)) throw Error('Debugger path escapes HOME');
    const fd = fs.openSync(location, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW | (directory ? fs.constants.O_DIRECTORY : 0));
    try {
      const info = fs.fstatSync(fd);
      if (info.dev !== before.dev || info.ino !== before.ino || (directory ? !info.isDirectory() : !info.isFile() || info.nlink !== 1)) throw Error('Debugger managed path changed');
      return action(fd, info);
    } finally { fs.closeSync(fd); }
  }
  function directory(parent, name, privateMode) {
    const location = path.join(parent, name);
    let created = false;
    try { fs.mkdirSync(location, { mode: privateMode ? 0o700 : 0o755 }); created = true; }
    catch (error) { if (error.code !== 'EEXIST') throw error; }
    checked(location, true, (fd, info) => {
      if (privateMode && !created) { checkOwner(info); return; }
      // The engine may share .wawlink with the host App GID via setgid; retain that policy.
      if (created || name !== '.wawlink' || !owned(info)) claim(fd, info, created);
      if ((fs.fstatSync(fd).mode & 0o500) !== 0o500) throw Error('Debugger directory is not readable and searchable');
    });
    return location;
  }
  const root = directory(home, '.wawlink', false);
  const bin = directory(root, 'bin', false);
  const privateDir = directory(root, 'feature-debug', true);
  const marker = path.join(privateDir, 'owner');
  const owner = 'wawlink-feature-debug-v1\n';
  function exists(location) {
    try { fs.lstatSync(location); return true; }
    catch (error) { if (error.code === 'ENOENT') return false; throw error; }
  }
  function regular(location) {
    return checked(location, false, (fd, info) => { claim(fd, info); return info; });
  }
  function readOwned(location) {
    return checked(location, false, (fd, info) => { checkOwner(info); return fs.readFileSync(fd, 'utf8'); });
  }
  function secure(location, mode, directory = false) {
    checked(location, directory, (fd, info) => { claim(fd, info); fs.fchmodSync(fd, mode); });
  }
  function writeNew(location, content, mode) {
    const fd = fs.openSync(location, fs.constants.O_WRONLY | fs.constants.O_CREAT | fs.constants.O_EXCL | fs.constants.O_NOFOLLOW, mode);
    try {
      claim(fd, fs.fstatSync(fd), true);
      fs.fchmodSync(fd, mode);
      fs.writeFileSync(fd, content);
    } finally { fs.closeSync(fd); }
  }
  if (!exists(marker)) {
    if (fs.readdirSync(privateDir).length !== 0) throw Error('Debugger directory contains user files; refusing to overwrite');
    writeNew(marker, owner, 0o600);
  }
  if (readOwned(marker) !== owner) throw Error('Debugger directory is not managed by WawLink');
  secure(marker, 0o600);
  secure(privateDir, 0o700, true);
  function publish(location, content, mode, replace = true) {
    if (!replace) {
      let fd, created;
      try {
        fd = fs.openSync(location, fs.constants.O_WRONLY | fs.constants.O_CREAT | fs.constants.O_EXCL | fs.constants.O_NOFOLLOW, mode);
        created = fs.fstatSync(fd);
        claim(fd, created, true);
        fs.fchmodSync(fd, mode);
        fs.writeFileSync(fd, content);
        fs.closeSync(fd);
        fd = undefined;
      } catch (error) {
        if (fd !== undefined) { try { fs.closeSync(fd); } catch {} }
        if (created) {
          try {
            const current = fs.lstatSync(location);
            if (!current.isSymbolicLink() && current.dev === created.dev && current.ino === created.ino) fs.unlinkSync(location);
          } catch {}
        }
        throw error;
      }
      return;
    }
    if (exists(location)) regular(location);
    temp = path.join(path.dirname(location), '.wawlink-feature-' + crypto.randomBytes(12).toString('hex'));
    writeNew(temp, content, mode);
    fs.renameSync(temp, location);
    temp = undefined;
  }
  const executable = path.join(bin, 'wawlink-feature');
  const record = path.join(privateDir, 'installation.json');
  const digest = content => crypto.createHash('sha256').update(content).digest('hex');
  let previous;
  if (exists(record)) previous = JSON.parse(readOwned(record));
  if (exists(executable)) {
    const current = readOwned(executable);
    if (current !== payload.client && (!previous || digest(current) !== previous.sha256)) throw Error('wawlink-feature was created or modified by the user; refusing to overwrite');
    if (current !== payload.client) publish(executable, payload.client, 0o700);
    else secure(executable, 0o700);
  } else {
    publish(executable, payload.client, 0o700, false);
  }
  publish(record, JSON.stringify({ sha256: digest(payload.client) }), 0o600);
  publish(path.join(privateDir, 'endpoint.json'), JSON.stringify(payload.endpoint), 0o600);
} catch (error) {
  if (temp) { try { fs.unlinkSync(temp); } catch {} }
  console.error('Feature debugger installation failed: ' + (error.message || 'unknown error'));
  process.exitCode = 1;
}
"""

private const val FEATURE_DEBUG_CLIENT = """#!/usr/local/bin/bun
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const MAX_RESPONSE = 32 * 1024 * 1024;
let secret = '';
function fail(message) { throw Error(message); }
const uid = process.getuid(), gid = process.getgid();
const mappedUid = process.env.WAWLINK_MAPPED_HOST_UID;
const mappedGid = process.env.WAWLINK_MAPPED_HOST_GID;
const mapped = /^\d+$/.test(mappedUid || '') && /^\d+$/.test(mappedGid || '') &&
  Number.isSafeInteger(Number(mappedUid)) && Number.isSafeInteger(Number(mappedGid));
const owned = info => info.uid === uid || (mapped && info.uid === Number(mappedUid));
function claim(fd, info, created = false, shared = false) {
  if (info.uid === uid && (shared || info.gid === gid)) return;
  if (!owned(info) && uid !== 0 && !created) fail('Debugger configuration has a different owner');
  try { fs.fchownSync(fd, uid, gid); }
  catch (error) {
    if (!['EPERM', 'EINVAL', 'ENOSYS'].includes(error.code) || !owned(info) || !owned(fs.fstatSync(fd))) throw error;
  }
}
function checked(location, directory, privateMode, action, shared = false) {
  const before = fs.lstatSync(location);
  if (before.isSymbolicLink() || (directory ? !before.isDirectory() : !before.isFile() || before.nlink !== 1)) fail('Debugger configuration has an unsafe type or hard link');
  if (fs.realpathSync(location) !== location) fail('Debugger configuration escapes HOME');
  const fd = fs.openSync(location, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW | (directory ? fs.constants.O_DIRECTORY : 0));
  try {
    const info = fs.fstatSync(fd);
    if (info.dev !== before.dev || info.ino !== before.ino || (directory ? !info.isDirectory() : !info.isFile() || info.nlink !== 1)) fail('Debugger configuration changed');
    if (privateMode && (info.mode & 0o077) !== 0) fail('Debugger configuration is not private');
    const required = directory ? 0o500 : 0o400;
    if ((info.mode & required) !== required) fail('Debugger configuration is not readable or searchable');
    claim(fd, info, false, shared);
    return action ? action(fd, info) : undefined;
  } finally { fs.closeSync(fd); }
}
function readPrivate(location) {
  return checked(location, false, true, (fd, info) => {
    if (info.size > 16384) fail('Debugger configuration is too large');
    return fs.readFileSync(fd, 'utf8');
  });
}
async function main() {
  const args = process.argv.slice(2), positional = [];
  if (args.length === 0 || args[0] === '--help' || args[0] === '-h' || args[0] === 'help') {
    process.stdout.write(JSON.stringify({
      command: 'wawlink-feature',
      description: '调试 WawLink 中已挂载的真实 HTML 卡片或功能页面；不会启动容器、功能或浏览器。',
      usage: ['wawlink-feature list [--surface card|page]', 'wawlink-feature inspect ID [--surface card|page]', 'wawlink-feature logs ID [--surface card|page]', 'wawlink-feature eval ID EXPR [--surface card|page]', 'wawlink-feature screenshot ID --output PATH [--surface card|page]', 'wawlink-feature reload ID [--surface card|page]'],
      notes: ['默认 --surface card；page 指完整功能页面。', 'eval 等待 Promise；拒绝和真实错误写入 stderr 并以非零状态退出。', '截图输出必须是尚不存在的路径；返回 PNG 文件元数据。', 'eval 使用受信任文档的原有 LuaJ 桥权限，不是沙箱。']
    }) + '\n');
    return;
  }
  let surface = 'card', output, options = true;
  for (let i = 0; i < args.length; i++) {
    const value = args[i];
    if (options && value === '--') { options = false; continue; }
    if (options && (value === '--surface' || value === '--output')) {
      if (++i >= args.length) fail(value + ' requires a value');
      if (value === '--surface') surface = args[i]; else output = args[i];
    } else positional.push(value);
  }
  if (surface !== 'card' && surface !== 'page') fail('--surface must be card or page');
  const [operation, featureId, expression] = positional;
  if (!['list', 'inspect', 'logs', 'eval', 'screenshot', 'reload'].includes(operation)) fail('用法：wawlink-feature --help');
  const expected = operation === 'list' ? 1 : operation === 'eval' ? 3 : 2;
  if (positional.length !== expected) fail(operation + ' received an incorrect number of arguments');
  if (operation !== 'list' && !/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(featureId)) fail('Invalid feature ID');
  if (operation === 'screenshot' ? !output : output !== undefined) fail('--output PATH is required only for screenshot');
  if (operation === 'eval' && Buffer.byteLength(expression, 'utf8') > 65536) fail('Eval expression exceeds 64 KiB');
  const home = fs.realpathSync(process.env.HOME || '');
  const privateDir = path.join(home, '.wawlink', 'feature-debug');
  checked(path.join(home, '.wawlink'), true, false, undefined, true);
  checked(path.join(home, '.wawlink', 'bin'), true, false);
  checked(privateDir, true, true);
  const config = JSON.parse(readPrivate(path.join(privateDir, 'endpoint.json')));
  if (config.version !== 1 || typeof config.token !== 'string' || !/^[a-f0-9]{64}$/.test(config.token)) fail('Invalid debugger configuration');
  secret = config.token;
  const url = new URL(config.url);
  if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1' || !url.port || url.pathname !== '/v1' || url.search || url.hash || url.username || url.password) fail('Invalid debugger loopback endpoint');
  const payload = { operation, surface };
  if (operation !== 'list') payload.featureId = featureId;
  if (operation === 'eval') payload.expression = expression;
  const response = await fetch(url, {
    method: 'POST', redirect: 'manual', signal: AbortSignal.timeout(35000),
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + secret },
    body: JSON.stringify(payload),
  });
  const chunks = [], reader = response.body.getReader();
  let count = 0;
  while (true) {
    const next = await reader.read();
    if (next.done) break;
    count += next.value.byteLength;
    if (count > MAX_RESPONSE) { await reader.cancel(); fail('Debugger response exceeds 32 MiB'); }
    chunks.push(next.value);
  }
  const message = JSON.parse(Buffer.concat(chunks, count).toString('utf8'));
  if (!response.ok || message.ok !== true) fail(typeof message.error === 'string' ? message.error : 'Debugger request failed (HTTP ' + response.status + ')');
  let result = message.result;
  if (operation === 'screenshot') {
    if (!result || result.mimeType !== 'image/png' || typeof result.base64 !== 'string' || result.base64.length > 22369624 || result.base64.length % 4 !== 0 || !/^[A-Za-z0-9+/]*={0,2}$/.test(result.base64)) fail('Renderer returned invalid PNG data');
    const png = Buffer.from(result.base64, 'base64');
    if (png.length < 24 || png.length > 16 * 1024 * 1024 || !png.subarray(0, 8).equals(Buffer.from([137,80,78,71,13,10,26,10])) || png.toString('ascii', 12, 16) !== 'IHDR') fail('Renderer returned invalid PNG bytes');
    const width = png.readUInt32BE(16), height = png.readUInt32BE(20);
    if (!width || !height || width * height > 8000000 || result.width !== width || result.height !== height) fail('Renderer returned inconsistent PNG dimensions');
    if (!output || output.includes('\0')) fail('Invalid screenshot output path');
    const destination = path.resolve(output);
    let fd, created;
    try {
      fd = fs.openSync(destination, fs.constants.O_WRONLY | fs.constants.O_CREAT | fs.constants.O_EXCL | fs.constants.O_NOFOLLOW, 0o600);
      created = fs.fstatSync(fd);
      claim(fd, created, true);
      fs.fchmodSync(fd, 0o600);
      fs.writeFileSync(fd, png);
      fs.closeSync(fd);
      fd = undefined;
    } catch (error) {
      if (fd !== undefined) { try { fs.closeSync(fd); } catch {} }
      if (created) {
        try {
          const current = fs.lstatSync(destination);
          if (!current.isSymbolicLink() && current.dev === created.dev && current.ino === created.ino) fs.unlinkSync(destination);
        } catch {}
      }
      if (error.code === 'EEXIST') fail('截图输出路径已存在；拒绝覆盖文件或符号链接');
      throw error;
    }
    result = { containerId: config.containerId, featureId, surface, mimeType: 'image/png', output: destination, width, height, bytes: png.length };
  }
  const json = JSON.stringify(result);
  process.stdout.write((secret ? json.split(secret).join('[redacted]') : json) + '\n');
}
main().catch(error => {
  let message = error && error.message ? error.message : String(error);
  if (secret) message = message.split(secret).join('[redacted]');
  process.stderr.write('wawlink-feature: ' + message + '\n');
  process.exitCode = 1;
});
"""
