package dev.wawlink.runtime

import kotlinx.serialization.json.*

/** Installed through the configured guest identity, never by writing into the host rootfs as root. */
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
  function directory(parent, name, privateMode) {
    const location = path.join(parent, name);
    try { fs.mkdirSync(location, { mode: privateMode ? 0o700 : 0o755 }); }
    catch (error) { if (error.code !== 'EEXIST') throw error; }
    const info = fs.lstatSync(location);
    if (!info.isDirectory() || info.isSymbolicLink() || fs.realpathSync(location) !== location) throw Error('Debugger directory is not a real HOME directory');
    if (typeof process.getuid === 'function' && info.uid !== process.getuid()) throw Error('Debugger directory has a different owner');
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
    const info = fs.lstatSync(location);
    if (!info.isFile() || info.isSymbolicLink()) throw Error('Debugger managed path is not a regular file');
    return info;
  }
  function readOwned(location) {
    regular(location);
    const fd = fs.openSync(location, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW);
    try { return fs.readFileSync(fd, 'utf8'); } finally { fs.closeSync(fd); }
  }
  if (!exists(marker)) {
    if (fs.readdirSync(privateDir).length !== 0) throw Error('Debugger directory contains user files; refusing to overwrite');
    fs.writeFileSync(marker, owner, { flag: 'wx', mode: 0o600 });
  }
  if (readOwned(marker) !== owner) throw Error('Debugger directory is not managed by WawLink');
  fs.chmodSync(privateDir, 0o700);
  function publish(location, content, mode, replace = true) {
    if (!replace) {
      let fd, created;
      try {
        fd = fs.openSync(location, fs.constants.O_WRONLY | fs.constants.O_CREAT | fs.constants.O_EXCL | fs.constants.O_NOFOLLOW, mode);
        created = fs.fstatSync(fd);
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
    fs.writeFileSync(temp, content, { flag: 'wx', mode });
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
    else fs.chmodSync(executable, 0o700);
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
function readPrivate(location) {
  const info = fs.lstatSync(location);
  if (!info.isFile() || info.isSymbolicLink() || (info.mode & 0o077) !== 0) fail('Debugger configuration is not a private regular file');
  if (typeof process.getuid === 'function' && info.uid !== process.getuid()) fail('Debugger configuration has a different owner');
  const fd = fs.openSync(location, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW);
  try {
    if (info.size > 16384) fail('Debugger configuration is too large');
    return fs.readFileSync(fd, 'utf8');
  } finally { fs.closeSync(fd); }
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
  const directory = fs.lstatSync(privateDir);
  if (!directory.isDirectory() || directory.isSymbolicLink() || fs.realpathSync(privateDir) !== privateDir || (directory.mode & 0o077) !== 0) fail('Debugger directory is not private');
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
