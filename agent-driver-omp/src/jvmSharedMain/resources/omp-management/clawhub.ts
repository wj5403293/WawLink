import * as fs from "node:fs/promises";
import type { FileHandle } from "node:fs/promises";
import { constants } from "node:fs";
import * as path from "node:path";
import type { SkillContext } from "./skill-files.ts";
import { ManagementDiagnostic } from "./diagnostics.mjs";
import { ClawHubHttp, PREVIEW_BYTES, apiUrl, canonical, cursor, exactVersion, fail, identity, record, skillUrl, text } from "./clawhub-http.ts";
import { githubBundle, githubHandoff, githubVersion, hostedBundle, hostedFile, parallelFiles, resourcePath, verifyFile, versionSummary, type Bundle, type Resource } from "./clawhub-source.ts";

const RECEIPT = ".wawlink-clawhub.json";
const DIALECT_WARNING = "ClawHub/OpenClaw 技能并非 OMP 官方 Skillshare 包；运行时、工具名、依赖和权限声明可能不兼容。安装只保存原始文件，不执行脚本或安装依赖。";
const SCAN_WARNING = "审核与扫描不是安全保证；即使状态为 clean 也可能有警告。使用前请阅读全部资源，并自行确认网络、命令和凭据权限。";
function item(value: unknown, fallbackOwner?: string, fallbackVersion?: string | null): Record<string, unknown> | null {
  if (!record(value)) return null;
  const ownerRecord = record(value.owner) ? value.owner : null;
  const owner = value.ownerHandle ?? ownerRecord?.handle ?? (typeof value.owner === "string" ? value.owner : fallbackOwner);
  if (typeof owner !== "string" || typeof value.slug !== "string") return null;
  try { identity(owner); identity(value.slug); } catch { return null; }
  const stats = record(value.stats) ? value.stats : record(value.metrics) ? value.metrics : {};
  const latest = record(value.latestVersion) ? value.latestVersion : {};
  const tags = record(value.tags) ? value.tags : {};
  const numeric = (n: unknown): number | null => typeof n === "number" && Number.isFinite(n) && n >= 0 ? n : null;
  return { owner, slug: value.slug, name: text(value.displayName ?? value.name, 1000) ?? value.slug,
    description: text(value.summary ?? value.description, 10000) ?? "",
    version: text(latest.version ?? value.version ?? tags.latest, 200) ?? fallbackVersion ?? null,
    downloads: numeric(stats.downloads ?? value.downloads), stars: numeric(stats.stars ?? value.stars), url: canonical(owner, value.slug) };
}
function listItems(values: unknown): Record<string, unknown>[] {
  if (!Array.isArray(values) || values.length > 200) fail("ClawHub 目录响应结构无效，请重新加载");
  return values.map(value => item(value)).filter((value): value is Record<string, unknown> => value !== null);
}
function preview(bytes: Buffer): { content: string | null; binary: boolean } {
  let content: string;
  try { content = new TextDecoder("utf-8", { fatal: true, ignoreBOM: true }).decode(bytes); }
  catch { return { content: null, binary: true }; }
  if (bytes.includes(0)) return { content: null, binary: true };
  return { content: bytes.length > PREVIEW_BYTES ? null : content, binary: false };
}
function securitySnapshot(value: Record<string, unknown> | null): Record<string, unknown> | null {
  if (!value) return null;
  const result: Record<string, unknown> = {};
  for (const key of ["status", "hasWarnings", "hasScanResult", "checkedAt"]) {
    const v = value[key];
    if (typeof v === "string") result[key] = v.slice(0, 100);
    else if (typeof v === "boolean" || typeof v === "number" && Number.isFinite(v)) result[key] = v;
  }
  if (record(value.scanners)) {
    const scanners: Record<string, unknown> = {};
    for (const [name, scanner] of Object.entries(value.scanners).slice(0, 30)) {
      if (!/^[a-zA-Z0-9_-]{1,50}$/.test(name) || !record(scanner)) continue;
      const entry: Record<string, unknown> = {};
      for (const key of ["status", "verdict", "normalizedStatus", "severity", "recommendation", "score", "issueCount", "checkedAt"]) {
        const v = scanner[key];
        if (typeof v === "string") entry[key] = v.slice(0, 100);
        else if (typeof v === "number" && Number.isFinite(v)) entry[key] = v;
      }
      scanners[name] = entry;
    }
    result.scanners = scanners;
  }
  return result;
}
function scanWarnings(moderation: unknown, security: Record<string, unknown> | null, github = false): string[] {
  const warnings = [DIALECT_WARNING, SCAN_WARNING];
  if (record(moderation)) {
    if (moderation.isMalwareBlocked === true || moderation.verdict === "malicious") fail("ClawHub 已阻止该技能的恶意内容，不能读取或安装");
    if (moderation.isPendingScan === true || moderation.isHiddenByMod === true || moderation.isRemoved === true) fail("ClawHub 该来源正在审核、隐藏或已移除，不能安装");
    if (moderation.isSuspicious === true || moderation.verdict === "suspicious") warnings.push("ClawHub 技能级审核标记为可疑；此标记可能针对不同版本，请谨慎核对原站审核记录。");
    if (Array.isArray(moderation.reasonCodes) && moderation.reasonCodes.length) warnings.push("ClawHub 技能级审核存在风险原因记录，请在原站查看详情。");
  }
  if (!security) warnings.push(github ? "GitHub 来源已通过 ClawHub 公开下载门禁，但公开交接不提供逐扫描器报告；不能据此视为无风险。" : "该精确版本未提供完整扫描状态，不能据此视为无风险。");
  else {
    if (["malicious", "blocked"].includes(String(security.status))) fail("ClawHub 精确版本扫描已阻止该内容，不能安装");
    if (security.hasWarnings === true) warnings.push("该精确版本扫描明确包含警告（hasWarnings）；整体 clean 状态不代表没有问题。");
    if (security.hasScanResult !== true) warnings.push("该精确版本没有完整扫描结果；请自行审核全部资源。");
    if (!["clean", "benign"].includes(String(security.status))) warnings.push("该精确版本扫描状态并非明确 clean，请查看原站结果并谨慎使用。");
    if (record(security.scanners)) {
      for (const [name, scanner] of Object.entries(security.scanners).slice(0, 30)) {
        if (!record(scanner)) continue;
        if (["malicious", "blocked"].includes(String(scanner.normalizedStatus ?? scanner.status))) fail("ClawHub 扫描器报告恶意或阻止结果，不能安装");
        if (scanner.normalizedStatus === "suspicious" || scanner.status === "suspicious" || Number(scanner.issueCount) > 0) {
          const label = name === "skillspector" ? "Skillspector" : name === "vt" ? "VirusTotal" : name === "llm" ? "LLM" : "上游";
          warnings.push(`${label} 扫描器报告可疑结果或风险问题，请在原站核对；其他扫描器的 clean 不会取消此警告。`);
        }
      }
    }
  }
  return warnings;
}
async function dialectWarnings(content: string | null): Promise<string[]> {
  if (content === null) return ["SKILL.md 未在本地完整预览；请在使用前阅读原文并核对 OMP 兼容性。"];
  const match = /^\uFEFF?---[ \t]*\r?\n([\s\S]*?)\r?\n---[ \t]*(?:\r?\n|$)/.exec(content);
  if (!match) return ["原始 SKILL.md 缺少标准 frontmatter；OMP 本地发现可能无法正确加载。"];
  try {
    // Bun is container-specific; lazy loading keeps local receipt discovery usable without that runtime.
    const { YAML } = await import("bun");
    const fields: unknown = YAML.parse(match[1]);
    if (!record(fields)) return ["原始 frontmatter 不是映射，OMP 可能无法加载；不会改写原文。"];
    const warnings: string[] = [];
    if (typeof fields.name !== "string" || !fields.name.trim() || typeof fields.description !== "string" || !fields.description.trim()) warnings.push("原始 frontmatter 缺少有效 name 或 description；OMP 本地加载可能失败。");
    const allowed: Record<string, true> = { name: true, description: true, license: true, "allowed-tools": true, metadata: true, compatibility: true };
    if (Object.keys(fields).some(key => !Object.hasOwn(allowed, key))) warnings.push("原始 frontmatter 含 OMP 严格发布格式未允许的顶层字段；普通本地发现较宽松，但不能直接作为官方 Skillshare 包发布。");
    if (fields.metadata !== undefined && (!record(fields.metadata) || Object.values(fields.metadata).some(value => typeof value !== "string"))) warnings.push("原始 metadata 不是纯字符串映射（可能包含 OpenClaw 嵌套声明）；OMP 严格发布校验不兼容，不会删除或改写这些字段。");
    if (/openclaw|clawdis|moltbot|clawdbot/i.test(match[1])) warnings.push("原始声明包含 OpenClaw 方言；OMP 不保证解释其依赖、环境、平台或运行时约束。");
    return warnings;
  } catch { return ["原始 SKILL.md YAML 无法解析；已保留原字节，OMP 本地加载可能失败。请先人工检查。"]; }
}
interface Selection { metadata: Record<string, unknown>; skill: Record<string, unknown>; bundle: Bundle; warnings: string[]; }
async function select(http: ClawHubHttp, owner: string, slug: string, requested: unknown, fresh: boolean): Promise<Selection> {
  const metadata = await http.json(skillUrl(owner, slug), fresh);
  if (!record(metadata.skill) || !record(metadata.owner) || typeof metadata.owner.handle !== "string" || metadata.owner.handle.toLowerCase() !== owner.toLowerCase() || metadata.skill.slug !== slug) fail("ClawHub 返回的发布者或技能身份不一致，已停止读取");
  // Do not obtain source bytes if the public skill metadata already blocks access.
  scanWarnings(metadata.moderation, null);
  const latest = record(metadata.latestVersion) ? metadata.latestVersion : null;
  let bundle: Bundle;
  const auditWarnings: string[] = [];
  if (latest && typeof latest.version === "string") {
    const version = requested === undefined || requested === null ? exactVersion(latest.version) : exactVersion(requested);
    if (version.startsWith("github:")) fail("该技能来源类型已变化，请重新阅读当前公开版本");
    bundle = await hostedBundle(http, owner, slug, version, fresh);
    if (fresh) {
      const scan = await http.json(skillUrl(owner, slug, "/scan", { version }), true);
      if (!record(scan.version) || scan.version.version !== version) fail("ClawHub 安装前审核的版本身份不一致");
      auditWarnings.push(...scanWarnings(scan.moderation, record(scan.security) ? scan.security : bundle.security));
      if (record(scan.security)) bundle.security = scan.security;
    }
  } else {
    const source = await githubHandoff(http, owner, slug, fresh);
    bundle = await githubBundle(http, source, requested === undefined || requested === null ? undefined : exactVersion(requested));
  }
  const skill = item({ ...metadata.skill, latestVersion: { version: bundle.version } }, metadata.owner.handle, bundle.version);
  if (!skill) fail("ClawHub 技能身份无效，请重新选择");
  return { metadata, skill, bundle, warnings: [...scanWarnings(metadata.moderation, bundle.security, !!bundle.source), ...auditWarnings] };
}
async function versionPage(http: ClawHubHttp, owner: string, slug: string, pageCursor: string | null): Promise<Record<string, unknown>> {
  const response = await http.json(skillUrl(owner, slug, "/versions", { limit: "20", cursor: pageCursor }));
  if (!Array.isArray(response.items) || response.items.length > 50) fail("ClawHub 版本列表响应无效");
  return { versions: response.items.map(versionSummary).filter(value => value !== null), nextCursor: cursor(response.nextCursor) };
}
async function bytesFor(http: ClawHubHttp, owner: string, slug: string, bundle: Bundle, file: Resource): Promise<Buffer> {
  const bytes = bundle.assets?.get(file.path) ?? await hostedFile(http, owner, slug, bundle, file);
  verifyFile(file, bytes); return bytes;
}
function localName(value: unknown): string {
  if (typeof value !== "string" || !value.trim() || value.length > 180 || value === "." || value === ".." || value.startsWith(".") || /[\\/\u0000-\u001f\u007f]/.test(value)) fail("本地技能目录名无效，请使用不含路径分隔符的普通名称");
  return value;
}
function inside(root: string, file: string): boolean {
  const rel = path.relative(root, file); return !rel || rel !== ".." && !rel.startsWith(`..${path.sep}`) && !path.isAbsolute(rel);
}
async function absent(file: string): Promise<boolean> {
  try { await fs.lstat(file); return false; } catch (error) { if (record(error) && error.code === "ENOENT") return true; throw error; }
}
async function targetPath(ctx: SkillContext, name: string, create: boolean): Promise<string> {
  const boundary = path.resolve(ctx.cwd ?? ctx.agentDir), canonicalBoundary = await fs.realpath(boundary);
  let root = boundary;
  for (const segment of ctx.cwd ? [".omp", "skills"] : ["skills"]) {
    root = path.join(root, segment);
    if (await absent(root)) {
      if (!create) return path.join(root, ...(ctx.cwd && segment === ".omp" ? ["skills"] : []), name);
      try { await fs.mkdir(root, { mode: 0o755 }); } catch (error) { if (!record(error) || error.code !== "EEXIST") throw error; }
    }
    const stat = await fs.lstat(root);
    if (!stat.isDirectory() || stat.isSymbolicLink() || !inside(canonicalBoundary, await fs.realpath(root))) fail("目标技能目录含链接或越过当前作用域，已停止安装");
  }
  const target = path.join(root, name);
  if (!await absent(target)) fail("目标已有同名技能目录或文件，安装冲突；不会覆盖，请选择其他目录名");
  return target;
}
async function install(args: Record<string, unknown>, ctx: SkillContext, http: ClawHubHttp, owner: string, slug: string): Promise<Record<string, unknown>> {
  if (args.confirm !== true) fail("安装前必须明确确认第三方来源、精确版本及风险");
  const version = exactVersion(args.version), name = localName(args.name ?? slug);
  await targetPath(ctx, name, false);
  const selected = await select(http, owner, slug, version, true), bundle = selected.bundle;
  const target = await targetPath(ctx, name, true);
  const root = path.dirname(target), realRoot = await fs.realpath(root);
  const staging = await fs.mkdtemp(path.join(root, ".clawhub-install-"));
  let reserved: { dev: number; ino: number } | null = null;
  const origin: Record<string, unknown> = { provider: "clawhub", owner, slug, version: bundle.version, url: canonical(owner, slug), installedAt: new Date().toISOString() };
  try {
    await parallelFiles(bundle.files, async file => {
      http.check();
      const bytes = await bytesFor(http, owner, slug, bundle, file);
      const destination = path.join(staging, file.path);
      await fs.mkdir(path.dirname(destination), { recursive: true, mode: 0o755 });
      await fs.writeFile(destination, bytes, { flag: "wx", mode: bundle.executable?.has(file.path) ? 0o755 : 0o644 });
    });
    const skillBytes = await fs.readFile(path.join(staging, "SKILL.md"));
    const skillPreview = preview(skillBytes);
    if (skillPreview.binary) fail("原始 SKILL.md 不是有效 UTF-8 文本，无法作为本地 OMP 技能安装");
    selected.warnings.push(...await dialectWarnings(skillBytes.length <= PREVIEW_BYTES ? skillPreview.content : null));
    await fs.writeFile(path.join(staging, RECEIPT), JSON.stringify({ ...origin, ...(bundle.source ? { source: bundle.source } : {}) }, null, 2) + "\n", { flag: "wx", mode: 0o644 });
    http.check();
    if (await fs.realpath(root) !== realRoot || (await fs.lstat(root)).isSymbolicLink()) fail("安装目标目录在操作期间发生变化，已停止写入");
    // mkdir is the exclusive reservation; rename alone could replace an existing empty directory.
    try { await fs.mkdir(target, { mode: 0o755 }); }
    catch (error) { if (record(error) && error.code === "EEXIST") fail("目标已有同名技能目录或文件，安装冲突；不会覆盖，请选择其他目录名"); throw error; }
    const stat = await fs.lstat(target); reserved = { dev: stat.dev, ino: stat.ino };
    await fs.chmod(staging, 0o755);
    await fs.rename(staging, target);
    reserved = null;
    return { changed: true, scope: ctx.cwd ? "PROJECT" : "GLOBAL", path: target, origin, warnings: [...new Set(selected.warnings)] };
  } catch (error) {
    if (reserved) {
      const current = await fs.lstat(target).catch(() => null);
      if (current && current.dev === reserved.dev && current.ino === reserved.ino && !current.isSymbolicLink()) await fs.rmdir(target).catch(() => {});
    }
    throw error;
  } finally { await fs.rm(staging, { recursive: true, force: true }); }
}

/** Read-only, bounded receipt projection. It does not certify the current edited files. */
export async function readClawHubOrigin(directory: string): Promise<Record<string, unknown> | null> {
  let handle: FileHandle | null = null;
  try {
    const directoryStat = await fs.lstat(directory);
    if (!directoryStat.isDirectory() || directoryStat.isSymbolicLink()) return null;
    handle = await fs.open(path.join(directory, RECEIPT), constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
    const stat = await handle.stat();
    if (!stat.isFile() || stat.size > 8192) return null;
    // Only whitelisted installation identity leaves this module, never source file contents.
    const bytes = Buffer.alloc(stat.size + 1);
    let offset = 0;
    while (offset < bytes.length) {
      const { bytesRead } = await handle.read(bytes, offset, bytes.length - offset, offset);
      if (!bytesRead) break;
      offset += bytesRead;
    }
    if (offset !== stat.size) return null;
    const value: unknown = JSON.parse(bytes.subarray(0, offset).toString("utf8"));
    if (!record(value) || value.provider !== "clawhub") return null;
    const owner = identity(value.owner), slug = identity(value.slug), version = exactVersion(value.version);
    if (value.url !== canonical(owner, slug) || typeof value.installedAt !== "string" || value.installedAt.length > 40 || !Number.isFinite(Date.parse(value.installedAt))) return null;
    return { provider: "clawhub", owner, slug, version, url: canonical(owner, slug), installedAt: value.installedAt };
  } catch { return null; }
  finally { await handle?.close().catch(() => {}); }
}

export async function dispatchClawHub(operation: string, args: Record<string, unknown>, ctx: SkillContext): Promise<Record<string, unknown>> {
  try {
    const op = operation.replace(/^skills\.clawhub\./, "").replace(/^clawhub\./, ""), http = new ClawHubHttp();
    if (op === "list") {
      const sort = args.sort ?? "downloads";
      if (!["downloads", "updated", "trending"].includes(String(sort))) fail("ClawHub 不支持此排序方式");
      const pageCursor = cursor(args.cursor);
      if (sort === "trending" && pageCursor) fail("ClawHub 趋势列表不支持游标翻页，请重新加载");
      const result = await http.json(apiUrl("/skills", { limit: "20", sort: String(sort), cursor: pageCursor, nonSuspiciousOnly: "true" }));
      return { items: listItems(result.items), nextCursor: sort === "trending" ? null : cursor(result.nextCursor), provider: "clawhub" };
    }
    if (op === "search") {
      if (typeof args.query !== "string" || !args.query.trim() || args.query.length > 500 || /[\u0000-\u001f\u007f]/.test(args.query)) fail("请输入不超过 500 字符的 ClawHub 搜索内容");
      const result = await http.json(apiUrl("/search", { q: args.query.trim(), limit: "20", nonSuspiciousOnly: "true" }));
      return { items: listItems(result.results), nextCursor: null, provider: "clawhub" };
    }
    const owner = identity(args.owner), slug = identity(args.slug);
    if (op === "install") return await install(args, ctx, http, owner, slug);
    if (op === "versions") {
      const pageCursor = cursor(args.cursor), page = await versionPage(http, owner, slug, pageCursor);
      if (!pageCursor && Array.isArray(page.versions) && !page.versions.length && page.nextCursor === null) {
        const metadata = await http.json(skillUrl(owner, slug));
        if (metadata.latestVersion === null) {
          const source = await githubHandoff(http, owner, slug, false);
          return { versions: [{ version: githubVersion(source) }], nextCursor: null };
        }
      }
      return page;
    }
    if (op !== "detail" && op !== "file") fail("未知 ClawHub 操作");
    if (op === "file") exactVersion(args.version);
    const selected = await select(http, owner, slug, args.version, false), bundle = selected.bundle;
    if (op === "file") {
      const relative = resourcePath(args.path), file = bundle.files.find(file => file.path === relative);
      if (!file) fail("资源不在该精确版本的普通文件清单中，不能预览");
      return { path: relative, ...preview(await bytesFor(http, owner, slug, bundle, file)), size: file.size };
    }
    const file = bundle.files.find(file => file.path === "SKILL.md")!;
    const content = file.size > PREVIEW_BYTES ? null : preview(await bytesFor(http, owner, slug, bundle, file)).content;
    selected.warnings.push(...await dialectWarnings(content));
    if (bundle.source) selected.warnings.push("此技能直接来自 GitHub；版本标识为固定提交而非 ClawHub 发布版本。只安装该提交中指定技能目录的完整普通文件，不复制整个仓库。");
    const versions = bundle.source ? { versions: [{ version: bundle.version }], nextCursor: null } : await versionPage(http, owner, slug, null);
    return { skill: selected.skill, version: { version: bundle.version, ...(bundle.createdAt !== undefined ? { createdAt: bundle.createdAt } : {}), ...(bundle.changelog !== undefined ? { changelog: bundle.changelog } : {}), ...(bundle.license !== undefined ? { license: bundle.license } : {}), files: bundle.files, security: securitySnapshot(bundle.security), ...(bundle.source ? { source: bundle.source } : {}) },
      versions: versions.versions, nextCursor: versions.nextCursor, content, previewTooLarge: file.size > PREVIEW_BYTES, warnings: [...new Set(selected.warnings)] };
  } catch (error) {
    if (error instanceof ManagementDiagnostic) throw error;
    const code = record(error) ? error.code : null;
    if (code === "ENOSPC") fail("容器磁盘空间不足，安装已停止并清理未完成目录；请释放空间后重试");
    if (code === "EACCES" || code === "EPERM" || code === "EROFS") fail("当前作用域目录不可写，请检查容器权限；不会覆盖已有内容");
    if (code === "ENOENT" || code === "ENOTDIR") fail("当前作用域目录已变化或不存在，请重新读取容器状态后重试");
    fail("ClawHub 操作失败，请重新读取当前状态后重试；未信任远端错误内容，也未执行任何技能脚本");
  }
}
