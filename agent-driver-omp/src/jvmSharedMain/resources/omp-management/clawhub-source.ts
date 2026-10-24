import { createHash } from "node:crypto";
import { BUNDLE_BYTES, ClawHubHttp, FILE_BYTES, apiUrl, exactVersion, fail, record, skillUrl, text } from "./clawhub-http.ts";

export interface Resource { path: string; size: number; sha256: string; contentType?: string; }
export interface GitHubSource { sourceRef: "public-github"; repo: string; commit: string; path: string; contentHash: string; }
export interface Bundle {
  version: string; files: Resource[]; security: Record<string, unknown> | null;
  createdAt?: number; changelog?: string; license?: string;
  source?: GitHubSource; assets?: Map<string, Buffer>; executable?: Set<string>;
}
export const sha256 = (bytes: Uint8Array): string => createHash("sha256").update(bytes).digest("hex");
const RESERVED = ".wawlink-clawhub.json";
export function resourcePath(value: unknown): string {
  if (typeof value !== "string" || !value || value.length > 1024 || value.startsWith("/") || /[\\:\u0000-\u001f\u007f]/.test(value) || value.split("/").some(part => !part || part === "." || part === ".." || part.length > 255) || value.toLowerCase() === RESERVED) fail("技能资源包含不安全路径或占用了安装收据名称，已停止读取");
  return value;
}
function validateFiles(values: unknown): Resource[] {
  if (!Array.isArray(values) || !values.length || values.length > 8192) fail("技能完整资源清单为空或超过安全数量上限");
  const paths = new Set<string>();
  let total = 0;
  const files = values.map(value => {
    if (!record(value)) fail("技能资源清单结构无效");
    const path = resourcePath(value.path);
    if (paths.has(path) || !Number.isSafeInteger(value.size) || Number(value.size) < 0 || Number(value.size) > FILE_BYTES || typeof value.sha256 !== "string" || !/^[a-fA-F0-9]{64}$/.test(value.sha256)) fail("技能资源清单存在重复路径、无效大小或无效 SHA256");
    paths.add(path); total += Number(value.size);
    if (total > BUNDLE_BYTES) fail("技能完整资源超过 50 MiB 安全上限");
    return { path, size: Number(value.size), sha256: value.sha256.toLowerCase(), ...(typeof value.contentType === "string" ? { contentType: value.contentType.slice(0, 200) } : {}) };
  });
  for (const file of files) {
    let prefix = "";
    for (const part of file.path.split("/").slice(0, -1)) {
      prefix += (prefix ? "/" : "") + part;
      if (paths.has(prefix)) fail("技能资源文件与目录路径冲突，已停止安装");
    }
  }
  if (!paths.has("SKILL.md")) fail("技能完整资源中缺少原始 SKILL.md，不能作为本地 OMP 技能安装");
  return files;
}
export function verifyFile(file: Resource, bytes: Buffer): void {
  if (bytes.length !== file.size || sha256(bytes) !== file.sha256) fail("技能资源大小或 SHA256 完整性核验失败；不会安装未经核验的内容");
}
export async function hostedBundle(http: ClawHubHttp, owner: string, slug: string, version: string, fresh: boolean): Promise<Bundle> {
  const response = await http.json(skillUrl(owner, slug, `/versions/${encodeURIComponent(version)}`), fresh);
  const item = response.version;
  if (!record(item) || item.version !== version || !record(response.skill) || response.skill.slug !== slug) fail("ClawHub 精确版本身份不一致，已停止读取");
  const bundle: Bundle = { version, files: validateFiles(item.files), security: record(item.security) ? item.security : null };
  if (typeof item.createdAt === "number" && Number.isFinite(item.createdAt)) bundle.createdAt = item.createdAt;
  if (typeof item.changelog === "string") bundle.changelog = item.changelog.slice(0, 100000);
  if (typeof item.license === "string") bundle.license = item.license.slice(0, 1000);
  return bundle;
}
export async function hostedFile(http: ClawHubHttp, owner: string, slug: string, bundle: Bundle, file: Resource): Promise<Buffer> {
  const { bytes } = await http.bytes(skillUrl(owner, slug, "/file", { version: bundle.version, path: file.path }), file.size);
  verifyFile(file, bytes); return bytes;
}
export async function githubHandoff(http: ClawHubHttp, owner: string, slug: string, fresh: boolean): Promise<GitHubSource> {
  // The official endpoint intentionally rejects ?version for unhosted GitHub skills.
  const value = await http.json(apiUrl("/download", { slug, ownerHandle: owner }), fresh);
  if (value.sourceRef !== "public-github" || typeof value.repo !== "string" || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,99}\/[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/.test(value.repo) || typeof value.commit !== "string" || !/^[a-fA-F0-9]{40}$/.test(value.commit) || typeof value.contentHash !== "string" || !/^[a-fA-F0-9]{64}$/.test(value.contentHash)) fail("ClawHub GitHub 来源交接数据不完整或未固定提交，已停止读取");
  const folder = resourcePath(value.path);
  return { sourceRef: "public-github", repo: value.repo, commit: value.commit.toLowerCase(), path: folder, contentHash: value.contentHash.toLowerCase() };
}
export function githubVersion(source: GitHubSource): string { return `github:${source.commit}`; }
interface GitEntry { path: string; sha: string; mode: string; type: string; size?: number; }
async function tree(http: ClawHubHttp, repo: string, sha: string, recursive = false): Promise<GitEntry[]> {
  const value = await http.json(`https://api.github.com/repos/${repo}/git/trees/${sha}${recursive ? "?recursive=1" : ""}`);
  if (value.sha !== sha || value.truncated === true || !Array.isArray(value.tree) || value.tree.length > 10000) fail("GitHub 资源树不完整或超过安全上限，不能保证完整安装");
  return value.tree.map(entry => {
    if (!record(entry) || typeof entry.sha !== "string" || !/^[a-f0-9]{40}$/.test(entry.sha) || typeof entry.mode !== "string" || typeof entry.type !== "string") fail("GitHub 资源树结构无效");
    return { path: resourcePath(entry.path), sha: entry.sha, mode: entry.mode, type: entry.type, ...(typeof entry.size === "number" ? { size: entry.size } : {}) };
  });
}
export async function githubBundle(http: ClawHubHttp, source: GitHubSource, requested?: string): Promise<Bundle> {
  const version = githubVersion(source);
  if (requested !== undefined && exactVersion(requested) !== version) fail("GitHub 当前公开来源提交已变化，无法重新取得此前版本；请重新阅读并确认当前精确提交");
  const commit = await http.json(`https://api.github.com/repos/${source.repo}/git/commits/${source.commit}`);
  if (commit.sha !== source.commit || !record(commit.tree) || typeof commit.tree.sha !== "string" || !/^[a-f0-9]{40}$/.test(commit.tree.sha)) fail("GitHub 来源未能解析到固定提交的资源树");
  let current = commit.tree.sha;
  for (const part of source.path.split("/")) {
    const entry = (await tree(http, source.repo, current)).find(entry => entry.path === part);
    if (!entry || entry.mode !== "040000" || entry.type !== "tree") fail("GitHub 技能目录不存在或包含链接，已停止读取");
    current = entry.sha;
  }
  const entries = await tree(http, source.repo, current, true);
  const blobs: GitEntry[] = [];
  let total = 0;
  for (const entry of entries) {
    if (entry.type === "tree" && entry.mode === "040000") continue;
    if (entry.type !== "blob" || !["100644", "100755"].includes(entry.mode)) fail("GitHub 技能目录含符号链接、子模块或特殊文件，已拒绝安装");
    if (!Number.isSafeInteger(entry.size) || Number(entry.size) < 0 || Number(entry.size) > FILE_BYTES) fail("GitHub 单文件超过 10 MiB 或缺少有效大小");
    total += Number(entry.size);
    if (total > BUNDLE_BYTES || blobs.length >= 8192) fail("GitHub 技能资源超过安全大小或数量上限");
    blobs.push(entry);
  }
  const assets = new Map<string, Buffer>(), executable = new Set<string>();
  const files: Resource[] = [];
  await parallelFiles(blobs, async entry => {
    const rawPath = `${source.path}/${entry.path}`.split("/").map(encodeURIComponent).join("/");
    const { bytes } = await http.bytes(`https://raw.githubusercontent.com/${source.repo}/${source.commit}/${rawPath}`, Number(entry.size));
    const blobHash = createHash("sha1").update(`blob ${bytes.length}\0`).update(bytes).digest("hex");
    if (bytes.length !== entry.size || blobHash !== entry.sha) fail("GitHub 固定提交资源的 Git blob 完整性核验失败");
    assets.set(entry.path, bytes);
    files.push({ path: entry.path, size: bytes.length, sha256: sha256(bytes) });
    if (entry.mode === "100755") executable.add(entry.path);
  });
  files.sort((a, b) => a.path.localeCompare(b.path));
  const valid = validateFiles(files);
  // Exact algorithm used by ClawHub computeGitHubSkillFolderContentHash.
  const hash = sha256(Buffer.from(valid.map(file => `${file.path}\0${file.size}\0${file.sha256}`).join("\n"), "utf8"));
  if (hash !== source.contentHash) fail("GitHub 完整技能内容与 ClawHub 审核来源哈希不一致，已停止安装");
  return { version, files: valid, security: null, source, assets, executable };
}
/** Wait for all in-flight reads before surfacing a failure; never leave writers running. */
export async function parallelFiles<T>(items: readonly T[], action: (item: T) => Promise<void>): Promise<void> {
  let next = 0, failure: unknown = null;
  await Promise.all(Array.from({ length: Math.min(4, items.length) }, async () => {
    while (failure === null) {
      const index = next++;
      if (index >= items.length) return;
      try { await action(items[index]); } catch (error) { failure ??= error; }
    }
  }));
  if (failure !== null) throw failure;
}
export function versionSummary(value: unknown): Record<string, unknown> | null {
  if (!record(value) || typeof value.version !== "string" || !value.version) return null;
  return { version: value.version.slice(0, 200), ...(typeof value.createdAt === "number" ? { createdAt: value.createdAt } : {}), ...(text(value.changelog, 100000) !== null ? { changelog: text(value.changelog, 100000) } : {}) };
}
