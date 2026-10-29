import * as fs from "node:fs/promises";
import * as path from "node:path";
import { createHash } from "node:crypto";
import type { AuthStorage } from "/opt/omp/src/session/auth-storage.ts";
import type { SkillInstallHooks } from "/opt/omp/src/skillshare/installer.ts";
import { nativeSkill, skillHash, skillRecord, skillScope, skillWritable, type SkillContext } from "./skill-files.ts";
import { ManagementDiagnostic } from "./diagnostics.mjs";
// Platform-specific native dependency exists only inside the installed container.
const { STREAM_AUTH_PROVIDER } = await import("/opt/omp/node_modules/@oh-my-pi/pi-wire/src/index.ts");
interface Login { controller: AbortController; status: string; url?: string; instructions?: string; prompt?: string; secret?: boolean; error?: string; answer?: (value: string) => void; }
const stores = new WeakMap<SkillContext, Promise<AuthStorage>>(), logins = new WeakMap<SkillContext, Login>();
async function storage(ctx: SkillContext) {
  let current = stores.get(ctx);
  if (!current) { current = nativeSkill("session/auth-broker-config").then(m => m.discoverAuthStorage(ctx.agentDir)); stores.set(ctx, current); }
  return current;
}
function publicLogin(state: Login | undefined) { return state ? { status: state.status, url: state.url ?? null, instructions: state.instructions ?? null, prompt: state.prompt ?? null, secret: state.secret ?? false, error: state.error ?? null } : { status: "idle" }; }
async function authStatus(ctx: SkillContext) {
  const auth = await storage(ctx); await auth.credentials.reload();
  const accounts = auth.credentials.list(STREAM_AUTH_PROVIDER).map(a => ({ id: String(a.id), type: a.credential.type }));
  const key = await auth.keys.get(STREAM_AUTH_PROVIDER);
  let username: string | null = null;
  if (key?.split(".").length === 3) { try { const v: unknown = JSON.parse(Buffer.from(key.split(".")[1], "base64url").toString()); if (skillRecord(v) && typeof v.preferred_username === "string") username = v.preferred_username; } catch { /* Identity remains unknown, never treat decoded claims as permission. */ } }
  return { signedIn: !!key, username, accounts, ...publicLogin(logins.get(ctx)), environmentCredential: !!process.env.STENCIL_API_KEY };
}
export async function skillRegistryDispatch(op: string, args: Record<string, unknown>, ctx: SkillContext): Promise<Record<string, unknown>> {
  if (op === "cancel") { logins.get(ctx)?.controller.abort(); logins.delete(ctx); return { cancelled: true }; }
  if (op === "loginStatus" || op === "author") return authStatus(ctx);
  if (op === "login") {
    logins.get(ctx)?.controller.abort();
    const auth = await storage(ctx), state: Login = { controller: new AbortController(), status: "waiting" }; logins.set(ctx, state);
    void auth.oauth.login(STREAM_AUTH_PROVIDER, {
      signal: state.controller.signal,
      onAuth: info => { state.url = info.url; state.instructions = info.instructions; },
      onPrompt: prompt => {
        state.prompt = prompt.message; state.secret = prompt.secret === true;
        const { promise, resolve, reject } = Promise.withResolvers<string>();
        state.answer = value => { state.prompt = undefined; state.answer = undefined; resolve(value); };
        state.controller.signal.addEventListener("abort", () => reject(Error("登录已取消")), { once: true });
        return promise;
      },
      onProgress: message => { state.instructions = message; },
    }).then(() => { state.status = "complete"; state.url = undefined; state.prompt = undefined; }).catch(error => { state.status = "failed"; state.error = state.controller.signal.aborted ? "登录已取消" : "原生 Stencil 登录失败，请重试；可在容器运行 omp /login → Stencil"; });
    return publicLogin(state);
  }
  if (op === "answerLogin") { const state = logins.get(ctx); if (!state?.answer || typeof args.value !== "string") throw Error("没有等待回答的登录提示"); state.answer(args.value); return publicLogin(state); }
  if (op === "logout") { logins.get(ctx)?.controller.abort(); await (await storage(ctx)).credentials.remove(STREAM_AUTH_PROVIDER); return { loggedOut: true, environmentCredential: !!process.env.STENCIL_API_KEY }; }
  const manifest = await nativeSkill("skillshare/manifest");
  const global = !ctx.cwd, cwd = ctx.cwd ?? ctx.agentDir;
  if (op === "installed") {
    const paths = await manifest.getSkillsInstallPaths({ global, cwd });
    const errors: { path: string; message: string; content: string | null; version: string | null }[] = [];
    for (const [file, reader] of [[paths.manifest, manifest.readSkillsManifest], [paths.lock, manifest.readSkillsLock]] as const) {
      try { await reader(file); } catch (error) { const content = await fs.readFile(file, "utf8").catch(() => null); const message = error instanceof Error && /version/i.test(error.message) ? "锁版本或条目版本无效；请修复原文件" : "JSON/schema/包身份无效；原文件保留，请在原生编辑器修复"; errors.push({ path: file, message, content, version: content === null ? null : skillHash(content) }); }
    }
    if (errors.length) return { installed: [], packageErrors: errors, packagePaths: paths, store: manifest.getSkillshareStoreDir() };
    const declared = await manifest.readSkillsManifest(paths.manifest), locked = await manifest.readSkillsLock(paths.lock);
    const installed = await Promise.all([...new Set([...Object.keys(declared.skills), ...Object.keys(locked.skills)])].sort().map(async id => {
      const coordinates = manifest.parseSkillId(id)!, lock = locked.skills[id];
      const stored = !!lock && await manifest.readStoredIntegrity(manifest.getSkillStorePath(coordinates.scope, coordinates.name, lock.version)) === lock.integrity;
      return { id, range: declared.skills[id] ?? null, version: lock?.version ?? null, integrity: lock?.integrity ?? null, stored, scope: global ? "user" : "project" };
    }));
    return { installed, packageErrors: [], packagePaths: paths, store: manifest.getSkillshareStoreDir() };
  }
  if (op === "repairPackageFile") {
    const paths = await manifest.getSkillsInstallPaths({ global, cwd }), file = String(args.path);
    if (![paths.manifest, paths.lock].includes(file)) throw Error("文件不属于当前安装范围");
    const content = String(args.content ?? "");
    if (file === paths.manifest) manifest.parseSkillsManifest(content, file); else manifest.parseSkillsLock(content, file);
    await ctx.atomicWrite(file, content, String(args.version)); return { changed: true, scope: skillScope(ctx) };
  }
  const installer = await nativeSkill("skillshare/installer");
  const ext = await nativeSkill("extensibility/settings"), registryUrl = ext.cfgSkillsRegistryUrl.get(await ctx.settings());
  const { SkillshareClient, parseSkillSpec } = await nativeSkill("skillshare/client");
  const client = await SkillshareClient.create({ registryUrl, forPublish: op === "publish" });
  try {
    if (op === "home") return { ...await client.home(), registryUrl };
    if (op === "search" || op === "myPackages") {
      const sort = args.sort === "downloads" || args.sort === "recent" ? args.sort : "relevance";
      const q = [String(args.query ?? ""), args.owner ? `owner:${String(args.owner)}` : "", args.keyword ? `keyword:${String(args.keyword)}` : ""].filter(Boolean).join(" ");
      return { ...await client.search(q, { sort, page: Math.max(1, Number(args.page) || 1) }), registryUrl };
    }
    if (op === "tokens") return { tokens: await client.listTokens() };
    if (op === "createToken") {
      const name = String(args.name ?? "").trim(), expiresInDays = Number(args.days);
      if (!name || !Number.isFinite(expiresInDays) || expiresInDays <= 0) throw Error("请输入令牌名称与正数有效天数");
      const packages = Array.isArray(args.packages) ? args.packages.filter((p): p is string => typeof p === "string") : [];
      if (packages.some(p => !manifest.parseSkillId(p))) throw Error("包限制必须为 @scope/name");
      return { created: await client.createToken({ name, expiresInDays, ...(packages.length ? { packages } : {}) }) };
    }
    if (op === "revokeToken") { await client.revokeToken(String(args.tokenId)); return { revoked: true }; }
    if (op === "preflight" || op === "publish" || op === "bumpVersion") {
      if (typeof args.path !== "string") throw Error("请选择容器内技能目录");
      const { packSkill, bumpVersion } = await nativeSkill("skillshare/pack"), dir = path.resolve(args.path);
      await skillWritable(ctx, dir);
      if (op === "bumpVersion") { const result = await bumpVersion(dir, String(args.kind ?? "patch")); return { result, changed: true, scope: skillScope(ctx) }; }
      const pack = await packSkill(dir);
      const report = { name: pack.name, version: pack.version, description: pack.description, files: pack.files, integrity: pack.integrity, packedSize: pack.tgz.length, unpackedSize: pack.files.reduce((n, f) => n + f.size, 0), hasScripts: pack.hasScripts, secrets: pack.secrets };
      if (op === "preflight") return { ...report, publishable: pack.secrets.length === 0 };
      if (args.confirm !== true || args.integrity !== pack.integrity) throw Error("发布前必须确认当前预检结果；文件变化后请重新预检");
      if (pack.secrets.length) throw Error("检测到疑似密钥，不允许上传；请移除或通过 .skillignore 排除后重新预检");
      const scope = String(args.publisher ?? "");
      if (!/^[a-z0-9][a-z0-9_]{2,31}$/.test(scope)) throw Error("发布者需要 3–32 位小写字母、数字、下划线；权限由服务器校验");
      return { published: await client.publish(scope, pack.name, pack.version, pack.tgz, { tag: typeof args.tag === "string" ? args.tag : undefined }) };
    }
    if (op === "importSkill") {
      if (args.confirm !== true) throw Error(".skill 会上传并公开发布，必须明确确认");
      let bytes: Uint8Array;
      if (typeof args.base64 === "string") bytes = Buffer.from(args.base64, "base64");
      else { const file = String(args.path ?? ""); if (!file.endsWith(".skill") || !(await fs.lstat(file)).isFile()) throw Error("请选择普通 .skill 文件"); bytes = await fs.readFile(file); }
      return { published: await client.importSkill(bytes) };
    }
    const warnings: string[] = [];
    const hooks: SkillInstallHooks = { warn: warning => warnings.push(warning), confirmScripts: async request => {
      const approvals = Array.isArray(args.approvals) ? args.approvals : [];
      if (approvals.includes(`${request.id}@${request.version}`)) return true;
      // Structured exception is consumed by the Compose confirmation sheet; native installer has not downloaded this plan yet.
      throw Error(`SCRIPT_APPROVAL:${JSON.stringify(request)}`);
    } };
    if (op === "restore" && typeof args.id === "string") {
      const id = args.id, coordinates = manifest.parseSkillId(id);
      if (!coordinates) throw Error("需要有效的 @scope/name 技能包身份");
      const paths = await manifest.getSkillsInstallPaths({ global, cwd });
      const [declarations, lock] = await Promise.all([manifest.readSkillsManifest(paths.manifest), manifest.readSkillsLock(paths.lock)]);
      const locked = lock.skills[id];
      if (!locked) throw Error("该技能包尚未锁定，请使用范围内更新解析声明");
      const store = manifest.getSkillStorePath(coordinates.scope, coordinates.name, locked.version);
      if (await manifest.readStoredIntegrity(store) === locked.integrity) return { changes: [], warnings, changed: false, scope: skillScope(ctx) };
      const summary = await client.version(coordinates.scope, coordinates.name, locked.version);
      if (summary.integrity !== locked.integrity) throw Error("integrity mismatch with lock");
      if (summary.hasScripts) await hooks.confirmScripts({ id, version: locked.version, files: summary.files.filter(f => f.executable || f.path.startsWith("scripts/")) });
      await installer.storeSkillVersion(client, { ...coordinates, version: locked.version, integrity: locked.integrity });
      return { changes: [{ id, from: locked.version, to: locked.version, range: declarations.skills[id] ?? null, restored: true }], warnings, changed: true, scope: skillScope(ctx) };
    }
    if (op === "install" || op === "restore" || op === "update") {
      const specs = op === "install" ? [String(args.spec ?? "")] : [], names = typeof args.id === "string" ? [args.id] : [];
      const changes = op === "update" ? await installer.updateSkillPackages(client, { global, cwd, names, yes: false }, hooks) : await installer.installSkillPackages(client, { global, cwd, specs, yes: false }, hooks);
      return { changes, warnings, changed: changes.length > 0, scope: skillScope(ctx) };
    }
    if (op === "uninstall") return { removed: await installer.uninstallSkillPackages({ global, cwd, names: [String(args.id)] }), changed: true, scope: skillScope(ctx) };
    const spec = parseSkillSpec(String(args.id ?? ""));
    if (!spec) throw Error("需要有效的 @scope/name 技能包身份");
    if (op === "package") {
      const packument = await client.packument(spec.scope, spec.name), request = String(args.version ?? packument.distTags.latest ?? "");
      const resolved = installer.resolveSkillVersion(packument, request);
      return { packument, version: await client.version(spec.scope, spec.name, resolved.version), warnings: resolved.warnings, registryUrl };
    }
    if (op === "packageFile") {
      const version = String(args.version ?? ""), file = String(args.path ?? "");
      const { SEMVER_RE } = await nativeSkill("skillshare/pack");
      if (spec.range || !SEMVER_RE.test(version)) throw new ManagementDiagnostic("请选择明确的技能包和精确版本");
      if (!file || file.length > 255 || file.split("/").some(part => !part || part === "." || part === ".." || /[\\\u0000-\u001f\u007f]/.test(part))) {
        throw new ManagementDiagnostic("技能包资源路径无效，请重新读取文件清单");
      }
      const info = await client.version(spec.scope, spec.name, version);
      if (info.scope !== spec.scope || info.name !== spec.name || info.version !== version) throw new ManagementDiagnostic("技能包版本身份不匹配，请重新读取");
      const matches = info.files.filter(entry => entry.path === file);
      if (matches.length !== 1) throw new ManagementDiagnostic("技能包文件不在当前版本清单中，请重新读取");
      const entry = matches[0], maximum = 512 * 1024;
      if (!Number.isSafeInteger(entry.size) || entry.size < 0 || !/^[a-f0-9]{64}$/i.test(entry.sha256)) throw new ManagementDiagnostic("技能包文件清单无效，请重新读取");
      if (entry.size > maximum) throw new ManagementDiagnostic("预览文件超过 512 KiB，无法在应用中预览");
      // The wire module exists only in the guest; OMP 18.12.6 has this route but no client.file().
      const { SKILLS_ROUTES } = await import("/opt/omp/node_modules/@oh-my-pi/pi-wire/src/skillshare.ts");
      const route = SKILLS_ROUTES.file(spec.scope, spec.name, encodeURIComponent(version), file.split("/").map(encodeURIComponent).join("/"));
      let response: Response;
      try { response = await fetch(client.registryUrl + route, { redirect: "error", credentials: "omit", signal: AbortSignal.timeout(25000) }); }
      catch { throw new ManagementDiagnostic("无法读取技能包文件，请检查市场地址和容器网络后重试"); }
      if (!response.ok) { await response.body?.cancel(); throw new ManagementDiagnostic(`读取技能包文件失败：HTTP ${response.status}`); }
      if (Number(response.headers.get("content-length")) > maximum) { await response.body?.cancel(); throw new ManagementDiagnostic("预览文件超过 512 KiB，无法在应用中预览"); }
      const reader = response.body?.getReader();
      if (!reader) throw new ManagementDiagnostic("技能包文件响应为空，请重试");
      const chunks: Uint8Array[] = []; let size = 0;
      try {
        for (;;) {
          const { value, done } = await reader.read();
          if (done) break;
          size += value.byteLength;
          if (size > maximum || size > entry.size) { await reader.cancel(); throw new ManagementDiagnostic("技能包文件大小与清单不符，已停止预览"); }
          chunks.push(value);
        }
      } finally { reader.releaseLock(); }
      const bytes = Buffer.concat(chunks, size);
      if (size !== entry.size || createHash("sha256").update(bytes).digest("hex") !== entry.sha256.toLowerCase()) {
        throw new ManagementDiagnostic("技能包文件大小或 SHA-256 与清单不符，已停止预览");
      }
      const image = /\.(png|jpg|jpeg|webp|gif)$/i.test(file);
      let content: string | null = null, binary = false;
      if (!image) {
        try { content = new TextDecoder("utf-8", { fatal: true, ignoreBOM: true }).decode(bytes); binary = bytes.includes(0); }
        catch { binary = true; }
        if (binary) content = null;
      }
      return { file, content, base64: image ? bytes.toString("base64") : null, binary, size };
    }
    if (op === "setTag") await client.setTag(spec.scope, spec.name, String(args.tag), String(args.version));
    else if (op === "removeTag") { if (args.tag === "latest") throw Error("latest 不能删除，只能修改指向"); await client.removeTag(spec.scope, spec.name, String(args.tag)); }
    else if (op === "yank") await client.yank(spec.scope, spec.name, String(args.version), args.yanked === true);
    else if (op === "deprecate") { const message = args.message === null ? null : String(args.message); if (message && message.length > 500) throw Error("弃用说明最多 500 字符"); await client.deprecate(spec.scope, spec.name, String(args.version), message); }
    else if (op === "addOwner") await client.addOwner(spec.scope, spec.name, String(args.username));
    else if (op === "removeOwner") await client.removeOwner(spec.scope, spec.name, String(args.username));
    else throw Error(`未知技能操作：${op}`);
    return { updated: true };
  } finally { client.close(); }
}
