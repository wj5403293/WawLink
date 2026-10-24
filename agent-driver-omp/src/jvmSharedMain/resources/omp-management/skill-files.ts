import * as fs from "node:fs/promises";
import * as path from "node:path";
import { createHash, randomUUID } from "node:crypto";
import { YAML } from "bun";
import type { Settings } from "/opt/omp/src/config/settings.ts";
import type * as Format from "/opt/omp/src/discovery/agent-plugin-format.ts";
import type * as Pack from "/opt/omp/src/skillshare/pack.ts";
import type * as Client from "/opt/omp/src/skillshare/client.ts";
import type * as Installer from "/opt/omp/src/skillshare/installer.ts";
import type * as Manifest from "/opt/omp/src/skillshare/manifest.ts";
import type * as ExtSettings from "/opt/omp/src/extensibility/settings.ts";
import type * as Skills from "/opt/omp/src/extensibility/skills.ts";
import type * as Capability from "/opt/omp/src/capability/index.ts";
import type * as Discovery from "/opt/omp/src/discovery/index.ts";
import type * as Agents from "/opt/omp/src/task/discovery.ts";
import type * as Auth from "/opt/omp/src/session/auth-broker-config.ts";
import type * as ModelSettings from "/opt/omp/src/config/model-settings.ts";
import type * as Registry from "/opt/omp/src/config/registry.ts";

export interface SkillContext {
  cwd: string | null; agentDir: string; settings(): Promise<Settings>;
  editSettings(scope: string, changes: Record<string, unknown>): Promise<unknown>;
  version(file: string): Promise<string>; atomicWrite(file: string, content: string, version?: string): Promise<unknown>;
  atomicDelete(file: string, version: string): Promise<unknown>; send(event: object): void;
}
interface SkillNativeModules {
  "discovery/agent-plugin-format": typeof Format; "skillshare/pack": typeof Pack;
  "skillshare/client": typeof Client; "skillshare/installer": typeof Installer; "skillshare/manifest": typeof Manifest;
  "extensibility/settings": typeof ExtSettings; "extensibility/skills": typeof Skills;
  "capability/index": typeof Capability; "discovery/index": typeof Discovery; "task/discovery": typeof Agents;
  "session/auth-broker-config": typeof Auth;
  "config/model-settings": typeof ModelSettings;
  "config/registry": typeof Registry;
}
// Native TS sources are installed only inside the container, not on the Kotlin build host.
export const nativeSkill = <K extends keyof SkillNativeModules>(file: K): Promise<SkillNativeModules[K]> => import(`/opt/omp/src/${file}.ts`);
export const skillRecord = (v: unknown): v is Record<string, unknown> => !!v && typeof v === "object" && !Array.isArray(v);
export const skillHash = (text: string) => createHash("sha256").update(text).digest("hex");
export function skillInside(root: string, file: string): boolean {
  const rel = path.relative(path.resolve(root), path.resolve(file));
  return rel === "" || (rel !== ".." && !rel.startsWith(`..${path.sep}`) && !path.isAbsolute(rel));
}
export function skillScope(ctx: SkillContext): "GLOBAL" | "PROJECT" { return ctx.cwd ? "PROJECT" : "GLOBAL"; }
export function skillParse(content: string) {
  const m = /^\uFEFF?---[ \t]*\r?\n([\s\S]*?)\r?\n---[ \t]*(?:\r?\n|$)/.exec(content);
  let fields: Record<string, unknown> = {};
  if (m) {
    try { const value: unknown = YAML.parse(m[1]); if (!skillRecord(value)) throw Error(); fields = value; } catch { throw Error("SKILL.md YAML 无效；原始文件与草稿未被修改"); }
    if (!fields || typeof fields !== "object" || Array.isArray(fields)) throw Error("技能 YAML 必须是映射");
  }
  return { fields, body: m ? content.slice(m[0].length) : content, frontmatter: m?.[1] ?? "" };
}
export async function skillValidate(content: string, strict: boolean, directory?: string) {
  const errors: string[] = [], warnings: string[] = [];
  let parsed;
  try { parsed = skillParse(content); } catch (e) { return { errors: [(e as Error).message], warnings }; }
  const f = parsed.fields, name = f.name;
  if (typeof name !== "string" || !name.trim() || /[\\/\0]/.test(name)) errors.push("名称不能为空或包含路径分隔符");
  if (typeof f.description !== "string" || !f.description.trim()) warnings.push("缺少 description，部分来源将无法加载");
  if (strict) {
    const { validateAgentSkillFrontmatter } = await nativeSkill("discovery/agent-plugin-format");
    const violation = validateAgentSkillFrontmatter(f, directory ?? (typeof name === "string" ? name : ""));
    if (violation) errors.push(violation);
    if (typeof name !== "string" || !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(name) || name.length > 64) errors.push("发布名称必须为不超过 64 字符的 ASCII kebab-case");
    const { SEMVER_RE } = await nativeSkill("skillshare/pack");
    const metadata = skillRecord(f.metadata) ? f.metadata : {};
    if (typeof metadata.version !== "string" || !SEMVER_RE.test(metadata.version)) errors.push("metadata.version 必须为严格语义化版本");
    const keywords = String(metadata.keywords ?? "").split(",").map((x: string) => x.trim()).filter(Boolean);
    if (keywords.length > 20 || keywords.some((x: string) => x.length > 50)) errors.push("关键词最多 20 个，每个最多 50 字符");
    for (const key of ["repository", "homepage"]) if (metadata[key]) {
      try { if (typeof metadata[key] !== "string" || new URL(metadata[key]).protocol !== "https:") throw Error(); } catch { errors.push(`metadata.${key} 必须为 HTTPS URL`); }
    }
  }
  return { errors, warnings, ...parsed };
}
export async function skillResources(root: string) {
  const canonical = await fs.realpath(root), files: { path: string; skipped?: boolean; reason?: string; directory?: boolean; mode?: number; size?: number; executable?: boolean }[] = [];
  async function walk(dir: string) {
    for (const entry of (await fs.readdir(dir, { withFileTypes: true })).sort((a, b) => a.name.localeCompare(b.name))) {
      const file = path.join(dir, entry.name), rel = path.relative(root, file), stat = await fs.lstat(file);
      if (stat.isSymbolicLink() || !skillInside(canonical, await fs.realpath(file))) { files.push({ path: rel, skipped: true, reason: "符号链接或越界文件，不读取或复制" }); continue; }
      if (stat.isDirectory()) { files.push({ path: `${rel}/`, directory: true, mode: stat.mode & 0o777 }); await walk(file); }
      else if (stat.isFile()) files.push({ path: rel, size: stat.size, executable: (stat.mode & 0o111) !== 0, mode: stat.mode & 0o777 });
      else files.push({ path: rel, skipped: true, reason: "非普通文件" });
    }
  }
  await walk(root); return files;
}
export async function skillSafeFile(root: string, relative: string) {
  const file = path.resolve(root, relative), canonical = await fs.realpath(root);
  if (!skillInside(root, file) || !skillInside(canonical, await fs.realpath(file))) throw Error("资源路径超出技能目录");
  const stat = await fs.lstat(file);
  if (!stat.isFile() || stat.isSymbolicLink()) throw Error("只支持普通文件");
  return { file, stat };
}
export async function skillWritable(ctx: SkillContext, dir: string) {
  const roots = [path.join(ctx.agentDir, "skills"), ...(ctx.cwd ? [ctx.cwd] : [])];
  const canonical = await fs.realpath(dir);
  if (!roots.some(r => skillInside(r, dir)) || !roots.some(r => skillInside(r, canonical))) throw Error("该来源只读；请复制到当前范围的独立技能目录");
  if ((await fs.lstat(dir)).isSymbolicLink()) throw Error("不能修改链接目录");
}
export function skillName(name: unknown): string {
  if (typeof name !== "string" || !name.trim() || name === "." || name === ".." || /[\\/\u0000-\u001f\u007f]/.test(name)) throw Error("技能目录名无效");
  return name;
}
export async function skillTarget(ctx: SkillContext, name: unknown) {
  const root = ctx.cwd ? path.join(ctx.cwd, ".omp", "skills") : path.join(ctx.agentDir, "skills");
  await fs.mkdir(root, { recursive: true });
  const real = await fs.realpath(root), boundary = await fs.realpath(ctx.cwd ?? ctx.agentDir);
  if (!skillInside(boundary, real)) throw Error("目标技能目录链接越过当前范围");
  return path.join(root, skillName(name));
}
export async function skillCopy(ctx: SkillContext, source: string, name: unknown) {
  const target = await skillTarget(ctx, name), resources = await skillResources(source);
  if (await fs.lstat(target).catch(e => { if (e.code === "ENOENT") return null; throw e; })) throw Error("目标已有同名目录，不会覆盖");
  const staging = path.join(path.dirname(target), `.skill-copy-${randomUUID()}`);
  await fs.mkdir(staging, { mode: (await fs.stat(source)).mode & 0o777 });
  try {
    for (const entry of resources) {
      if (entry.skipped) continue;
      const destination = path.join(staging, entry.path);
      if (entry.directory) await fs.mkdir(destination, { recursive: true, mode: entry.mode });
      else {
        const safe = await skillSafeFile(source, entry.path);
        await fs.mkdir(path.dirname(destination), { recursive: true });
        await fs.copyFile(safe.file, destination, fs.constants.COPYFILE_EXCL);
        await fs.chmod(destination, entry.mode ?? 0o644);
      }
    }
    const file = path.join(staging, "SKILL.md");
    const content = await fs.readFile(file, "utf8"), parsed = skillParse(content);
    // Only replace the name scalar, preserving unknown YAML and comments verbatim.
    const yamlName = YAML.stringify(String(name)).trim();
    const updated = /^name\s*:/m.test(parsed.frontmatter)
      ? content.replace(/^name[ \t]*:[^\r\n]*(?:\r?\n[ \t]+[^\r\n]*)*/m, () => `name: ${yamlName}`)
      : `---\nname: ${yamlName}\n${parsed.frontmatter}\n---\n${parsed.body}`;
    await fs.writeFile(file, updated);
    // mkdir reserves the name without replacing an independently created target.
    await fs.mkdir(target);
    try { for (const entry of await fs.readdir(staging)) await fs.rename(path.join(staging, entry), path.join(target, entry)); }
    catch (e) { await fs.rm(target, { recursive: true, force: true }); throw e; }
    for (const entry of [...resources].reverse()) if (entry.directory && !entry.skipped) await fs.chmod(path.join(target, entry.path), entry.mode ?? 0o755);
    await fs.chmod(target, (await fs.stat(source)).mode & 0o777);
    return { changed: true, scope: skillScope(ctx), path: target, skipped: resources.filter(x => x.skipped) };
  } finally { await fs.rm(staging, { recursive: true, force: true }); }
}
