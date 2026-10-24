import * as fs from "node:fs/promises";
import * as path from "node:path";
import * as os from "node:os";
import type { Skill as NativeSkill } from "/opt/omp/src/capability/skill.ts";
import { nativeSkill, skillCopy, skillHash, skillInside, skillName, skillParse, skillRecord, skillResources, skillSafeFile, skillScope, skillTarget, skillValidate, skillWritable, type SkillContext } from "./skill-files.ts";
import { skillRegistryDispatch } from "./skill-registry.ts";
import { ManagementDiagnostic } from "./diagnostics.mjs";

const settingLabels: Record<string, string> = {
  "skills.enabled": "发现并加载技能", "skillful": "在系统提示里列出技能目录", "skills.enableSkillCommands": "注册技能斜杠命令",
  "skills.enablePiUser": "用户 .omp 技能目录", "skills.enablePiProject": "项目 .omp 技能目录",
  "skills.enableAgentsUser": "用户 .agents 目录", "skills.enableAgentsProject": "项目 .agents 目录",
  "skills.enableClaudeUser": "用户 Claude 目录", "skills.enableClaudeProject": "项目 Claude 目录", "skills.enableCodexUser": "用户 Codex 目录",
  "skills.customDirectories": "自定义技能目录（按顺序扫描）", "skills.includeSkills": "只加载这些最终别名", "skills.ignoredSkills": "忽略原始名或别名",
  "skills.registryUrl": "Skillshare 地址",
  "enabledProviders": "用户来源 opt-in（共享发现：opencode 等）", "disabledProviders": "禁用发现来源（共享：github 等）",
};
function lookup(value: unknown, key: string): unknown {
  if (!skillRecord(value)) return null;
  if (Object.hasOwn(value, key)) return value[key];
  let cursor: unknown = value;
  for (const part of key.split(".")) { if (!skillRecord(cursor)) return null; cursor = cursor[part]; }
  return cursor ?? null;
}
async function settingsState(ctx: SkillContext) {
  const settings = await ctx.settings(), ext = await nativeSkill("extensibility/settings");
  const providers = await nativeSkill("config/model-settings");
  const registry = await nativeSkill("config/registry");
  const skillful = registry.all().find(setting => setting.id === "skillful");
  const descriptors = [ext.cfgSkillsEnabled, ext.cfgSkillsEnableSkillCommands, ext.cfgSkillsEnablePiUser, ext.cfgSkillsEnablePiProject,
    ext.cfgSkillsEnableAgentsUser, ext.cfgSkillsEnableAgentsProject, ext.cfgSkillsEnableClaudeUser, ext.cfgSkillsEnableClaudeProject,
    ext.cfgSkillsEnableCodexUser, ext.cfgSkillsCustomDirectories, ext.cfgSkillsIncludeSkills, ext.cfgSkillsIgnoredSkills, ext.cfgSkillsRegistryUrl, providers.cfgEnabledProviders, providers.cfgDisabledProviders];
  const global = settings.getGlobalSettings(), project = settings.getProjectSettings();
  const rows = descriptors.map(d => ({ key: d.id, title: settingLabels[d.id], value: d.get(settings), defaultValue: d.default ?? null, user: lookup(global, d.id), project: lookup(project, d.id), provenance: settings.getProvenance(d), type: d.id.endsWith("Directories") || d.id.endsWith("Skills") || d.id.endsWith("Providers") ? "list" : d.id.endsWith("Url") ? "string" : "boolean" }));
  rows.splice(1, 0, { key: "skillful", title: settingLabels.skillful, value: skillful?.get(settings) ?? true, defaultValue: skillful?.default ?? true, user: lookup(global, "skillful"), project: lookup(project, "skillful"), provenance: skillful ? settings.getProvenance(skillful) : "default", type: "boolean" });
  return { settings: rows, scope: skillScope(ctx), registryUrl: ext.cfgSkillsRegistryUrl.get(settings) };
}
function roots(ctx: SkillContext, configured: string[], configuredLevel: "project" | "user") { return { explicit: [], mode: "merge" as const, configured, configuredLevel }; }
async function discover(ctx: SkillContext) {
  const settings = await ctx.settings(), ext = await nativeSkill("extensibility/settings"), cap = await nativeSkill("capability/index"), discovery = await nativeSkill("discovery/index"), loader = await nativeSkill("extensibility/skills");
  const configured = ext.cfgExtensions.get(settings), extensionRoots = roots(ctx, configured, settings.getProvenance(ext.cfgExtensions) === "project" ? "project" : "user");
  const cwd = ctx.cwd ?? path.parse(ctx.agentDir).root, opts = ext.cfgSkills.get(settings), disabled = ext.cfgDisabledExtensions.get(settings);
  const release = cap.initializeWithSettings(settings);
  try {
    const result = await discovery.loadCapability<NativeSkill>("skills", { cwd, agentDir: ctx.agentDir, extensionRoots, includeDisabled: true });
    const effective = await loader.loadSkills({ ...opts, cwd, extensionRoots, disabledExtensions: disabled, ...(ctx.cwd ? {} : { enablePiProject: false, enableAgentsProject: false, enableClaudeProject: false }) });
    const inventory = await loader.loadSkills({ ...opts, enabled: true, cwd, extensionRoots, ignoredSkills: [], includeSkills: [], disabledExtensions: [], enablePiUser: true, enablePiProject: true, enableAgentsUser: true, enableAgentsProject: true, enableClaudeUser: true, enableClaudeProject: true, enableCodexUser: true });
    const candidates = result.all.filter(s => !ctx.cwd || s._source.level === "project");
    const files = new Map(candidates.filter(s => ctx.cwd || s._source.level !== "project").map(s => [s.path, s]));
    for (const s of inventory.skills) if ((ctx.cwd ? s._source?.level === "project" : s._source?.level !== "project") && !files.has(s.filePath)) {
      const parsed = skillParse(await fs.readFile(s.filePath, "utf8"));
      files.set(s.filePath, { name: String(parsed.fields.name ?? s.name), path: s.filePath, content: parsed.body, frontmatter: parsed.fields, level: ctx.cwd ? "project" : "user", _source: s._source! } as NativeSkill);
    }
    const agents = await (await nativeSkill("task/discovery")).discoverAgents(cwd, os.homedir(), extensionRoots);
    const entries = await Promise.all([...files.values()].map(async s => {
      const content = await fs.readFile(s.path, "utf8"), parsed = skillParse(content), actual = effective.skills.find(v => v.filePath === s.path), resolved = inventory.skills.find(v => v.filePath === s.path);
      const alias = actual?.name ?? resolved?.name ?? s.name, source = s._source.provider, rawName = String(parsed.fields.name ?? s.name);
      const flags: string[] = [];
      if (!actual) flags.push(disabled.includes(`skill:${rawName}`) || disabled.includes(`skill:${alias}`) ? "disabled" : opts.ignoredSkills?.some(p => new Bun.Glob(p).match(rawName) || new Bun.Glob(p).match(alias)) ? "ignored" : opts.includeSkills?.length && !opts.includeSkills.some(p => new Bun.Glob(p).match(alias)) ? "excluded" : "inactive");
      if (actual?.hide || parsed.fields.hide === true || parsed.fields["disable-model-invocation"] === true) flags.push("hidden");
      if (alias !== rawName) flags.push("conflict");
      const dir = path.dirname(s.path), editableSource = source === "native" || source === "custom";
      const writable = editableSource && (ctx.cwd ? skillInside(ctx.cwd, dir) : skillInside(path.join(ctx.agentDir, "skills"), dir));
      const definitions = agents.agents;
      const priorities: Record<string, number> = { native: 100, custom: 100, skillshare: 95, "omp-plugins": 90, claude: 80, "agent-plugins": 75, codex: 70, agents: 70, "claude-plugins": 70, opencode: 55, github: 30, managed: 5 };
      const packageMatch = source === "skillshare" ? /(?:^|[\\/])(@[^\\/]+)[\\/]([^\\/]+)[\\/]([^\\/]+)$/.exec(dir) : null;
      return { id: s.path, path: dir, filePath: s.path, name: rawName, alias, description: String(parsed.fields.description ?? ""), source, sourceMeta: s._source, priority: priorities[source] ?? null,
        fields: parsed.fields, body: parsed.body, content, version: skillHash(content), flags, writable,
        packageId: packageMatch ? `${packageMatch[1]}/${packageMatch[2]}` : null, packageVersion: packageMatch?.[3] ?? null,
        shadows: [...files.values()].filter(other => other.name === s.name && other.path !== s.path).map(other => ({ path: other.path, source: other._source.provider })),
        agentRefs: definitions.filter(a => a.autoloadSkills?.includes(rawName) || a.autoloadSkills?.includes(alias)).map(a => a.name) };
    }));
    entries.sort((a, b) => Number(b.priority ?? 0) - Number(a.priority ?? 0) || a.alias.localeCompare(b.alias));
    return { skills: entries, warnings: [...effective.warnings, ...inventory.warnings, ...result.warnings.map(message => ({ skillPath: "发现来源", message: /YAML|parse|syntax|invalid/i.test(message) ? "原生发现无法解析技能文件，请检查 YAML 与目录；原文件保留" : message }))], scope: skillScope(ctx), agentDir: ctx.agentDir, paths: { authored: ctx.cwd ? path.join(ctx.cwd, ".omp/skills") : path.join(ctx.agentDir, "skills"), managed: path.join(ctx.agentDir, "managed-skills") } };
  } finally { release(); }
}
async function selected(ctx: SkillContext, id: unknown) {
  if (typeof id !== "string") throw Error("需要稳定的技能文件身份");
  const entry = (await discover(ctx)).skills.find(s => s.id === id);
  if (!entry) throw Error("技能已变化或不属于当前范围，请重新读取");
  return entry;
}
async function dispatchSkill(operation: string, args: Record<string, unknown>, ctx: SkillContext): Promise<object> {
  const op = operation.replace(/^skills\./, "");
  if (op === "snapshot") {
    const manifest: unknown = JSON.parse(await fs.readFile("/opt/omp/package.json", "utf8"));
    return { ...await discover(ctx), ...await settingsState(ctx), ...(await skillRegistryDispatch("installed", {}, ctx)), ompVersion: skillRecord(manifest) && typeof manifest.version === "string" ? manifest.version : null };
  }
  if (op === "settings") return settingsState(ctx);
  if (op === "setSetting") {
    if (typeof args.key !== "string" || !Object.hasOwn(settingLabels, args.key)) throw Error("未知技能配置键");
    await ctx.editSettings(skillScope(ctx), { [args.key]: args.value ?? null }); return { changed: true, scope: skillScope(ctx) };
  }
  if (op === "validate") return skillValidate(String(args.content ?? ""), args.strict === true, typeof args.directory === "string" ? args.directory : undefined);
  if (op === "create") {
    const content = String(args.content ?? ""), validation = await skillValidate(content, args.strict === true);
    if (validation.errors.length) return { validation, saved: false };
    const parsed = skillParse(content), target = await skillTarget(ctx, parsed.fields.name);
    await fs.mkdir(target); try { await ctx.atomicWrite(path.join(target, "SKILL.md"), content); } catch (e) { await fs.rmdir(target).catch(() => {}); throw e; }
    return { changed: true, scope: skillScope(ctx), id: path.join(target, "SKILL.md") };
  }
  if (op === "inspectImport" || op === "importDirectory") {
    if (typeof args.path !== "string") throw Error("需要容器内目录");
    const source = path.resolve(args.path.startsWith("~/") ? path.join(os.homedir(), args.path.slice(2)) : args.path);
    const safe = await skillSafeFile(source, "SKILL.md"), content = await fs.readFile(safe.file, "utf8");
    if (op === "inspectImport") return { path: source, ...skillParse(content), resources: await skillResources(source) };
    return skillCopy(ctx, source, args.name);
  }
  if (["detail", "resource", "save", "delete", "copy", "disable"].includes(op)) {
    const s = await selected(ctx, args.id);
    if (op === "detail") return { ...s, resources: await skillResources(s.path) };
    if (op === "resource") {
      const { file, stat } = await skillSafeFile(s.path, String(args.file ?? "SKILL.md"));
      if (stat.size > 8 * 1024 * 1024) throw Error("预览文件超过 8 MiB");
      const bytes = await fs.readFile(file), image = /\.(png|jpg|jpeg|webp|gif)$/i.test(file);
      return { file: args.file, content: image ? null : bytes.includes(0) ? null : bytes.toString("utf8"), base64: image ? bytes.toString("base64") : null, binary: !image && bytes.includes(0), size: stat.size };
    }
    if (op === "copy") return skillCopy(ctx, s.path, args.name);
    if (op === "disable") {
      const ext = await nativeSkill("extensibility/settings"), existing = ext.cfgDisabledExtensions.get(await ctx.settings()), key = `skill:${s.alias}`;
      await ctx.editSettings(skillScope(ctx), { disabledExtensions: args.disabled === true ? [...new Set([...existing, key])] : existing.filter(x => x !== key && x !== `skill:${s.name}`) });
      return { changed: true, scope: skillScope(ctx) };
    }
    if (!s.writable) throw Error("该技能来源只读；请复制为独立技能");
    await skillWritable(ctx, s.path);
    if (typeof args.version !== "string" || await ctx.version(s.filePath) !== args.version) return { conflict: true, external: s.content, externalVersion: s.version, draft: args.content ?? null };
    if (op === "save") {
      const content = String(args.content ?? ""), validation = await skillValidate(content, args.strict === true, path.basename(s.path));
      if (validation.errors.length) return { validation, saved: false };
      await ctx.atomicWrite(s.filePath, content, args.version);
      return { changed: true, scope: skillScope(ctx), version: skillHash(content), saved: true };
    }
    const resources = await skillResources(s.path);
    if (resources.some(r => r.skipped)) throw Error("目录包含链接或特殊文件，为避免删除越界对象，请先在容器文件管理中处理");
    await fs.rm(s.path, { recursive: true }); return { changed: true, scope: skillScope(ctx) };
  }
  return skillRegistryDispatch(op, args, ctx);
}

export async function dispatch(operation: string, args: Record<string, unknown>, ctx: SkillContext): Promise<object> {
  try { return await dispatchSkill(operation, args, ctx); }
  catch (error: unknown) {
    const message = error instanceof Error ? error.message : "";
    if (message.startsWith("SCRIPT_APPROVAL:")) {
      const approval: unknown = JSON.parse(message.slice("SCRIPT_APPROVAL:".length));
      if (skillRecord(approval)) return { scriptApproval: approval };
    }
    const status = skillRecord(error) && typeof error.status === "number" ? error.status : null;
    const reason = status === 401 ? "401：需要 Stencil 登录；发布可用 SKILLS_TOKEN + 明确 scope，其余维护必须登录账号"
      : status === 403 ? "403：当前账号无此包或此操作权限，请检查发布者与维护者身份"
      : status === 409 ? "409：该版本已发布且不可覆盖，或资源冲突；请提升 metadata.version 后重新预检"
      : status === 404 ? "404：市场中不存在该包或版本，请重新选择"
      : /integrity|sha512/i.test(message) ? "完整性不匹配，未信任下载内容；检查市场来源、锁文件和共享 store"
      : /secret/i.test(message) ? "检测到疑似凭据；移除或通过 .skillignore 排除后重新预检"
      : /ENOSPC/i.test(message) ? "容器磁盘空间不足，请释放空间后重试；草稿保留"
      : /EACCES|EPERM|read.only/i.test(message) ? "目录或文件只读，请检查容器权限；草稿保留"
      : /ENOENT|ENOTDIR/i.test(message) ? "目录或资源不存在，请重新读取当前容器路径"
      : /fetch|network|ECONN|ENOTFOUND|reach.*registry/i.test(message) ? "无法连接技能市场，请检查 skills.registryUrl、容器网络与认证后重试"
      : /^(?:名称不能为空|缺少 description|技能 YAML 必须|技能目录名无效|该来源只读|该技能来源只读|目标已有同名|目标技能目录链接|不能修改链接目录|资源路径超出|只支持普通文件|技能已变化|需要稳定的技能文件身份|未知技能配置键|请选择|需要容器内目录|预览文件超过|目录包含链接|发布前必须确认|发布者需要|请输入令牌名称|包限制必须|latest 不能删除|弃用说明最多|没有等待回答|文件不属于当前安装范围|技能字段必须)/.test(message) ? message
      : /version|semver|metadata/i.test(message) ? "版本或 metadata 不符合原生规则；请填写严格递增的 metadata.version 后重新校验"
      : /SKILL.md|frontmatter|name|description|field|YAML/i.test(message) ? "原生技能格式校验未通过；请检查 name、description、目录名、封闭顶层字段和 metadata 字符串映射"
      : /symlink|symbolic|path|regular|file/i.test(message) ? "技能资源含不支持的链接、路径或文件类型；请检查目录与 .skillignore"
      : "原生技能操作失败；请重新读取实际状态，检查当前容器配置、市场地址与权限后重试";
    throw new ManagementDiagnostic(reason);
  }
}
