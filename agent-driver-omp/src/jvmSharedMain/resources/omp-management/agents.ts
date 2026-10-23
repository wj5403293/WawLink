import * as fs from "node:fs/promises";
import * as path from "node:path";
import * as os from "node:os";
import { createHash } from "node:crypto";
import { YAML } from "bun";
import type { Dirent } from "node:fs";
import type { Settings } from "/opt/omp/src/config/settings.ts";
import type { EffectiveExtensionRoots } from "/opt/omp/src/capability/types.ts";
import type { AgentDefinition } from "/opt/omp/src/task/types.ts";
import type { ConfigCandidate } from "/opt/omp/src/advisor/watchdog.ts";
import type { DiscoveredAdvisors } from "/opt/omp/src/advisor/config.ts";
import type * as NativeAgents from "/opt/omp/src/task/agents.ts";
import type * as NativeDiscovery from "/opt/omp/src/task/discovery.ts";
import type * as NativeAdvisors from "/opt/omp/src/advisor/config.ts";
import type * as NativeWatchdog from "/opt/omp/src/advisor/watchdog.ts";
import type * as NativeImports from "/opt/omp/src/discovery/at-imports.ts";
import type * as NativeCapabilities from "/opt/omp/src/discovery/index.ts";
import type * as NativeRegistry from "/opt/omp/src/capability/index.ts";
import type * as NativeExtensionSettings from "/opt/omp/src/extensibility/settings.ts";
import type * as NativeSkills from "/opt/omp/src/extensibility/skills.ts";
import type * as NativeTools from "/opt/omp/src/tools/builtin-names.ts";
import type { CustomTool } from "/opt/omp/src/capability/tool.ts";

interface Context {
	cwd: string | null;
	agentDir: string;
	send: (event: object) => void;
	settings: () => Promise<Settings>;
	version: (file: string) => Promise<string>;
	patchYaml: (file: string, changes: Record<string, unknown>, version?: string, body?: string) => Promise<void>;
	atomicWrite: (file: string, content: string, version?: string) => Promise<void>;
	atomicDelete: (file: string, version: string) => Promise<void>;
}
interface ManagedAgent {
	name: string; description: string; source: string; path: string; readOnly: boolean;
	fields: Record<string, unknown>; systemPrompt: string; content: string; version: string;
	shadows: { source: string; path: string; description: string }[];
}
interface AdvisorEntry extends Record<string, unknown> { name: string }
interface FileReference {
	path: string; version: string; source?: string; readOnly?: boolean; scope?: string; id?: string;
}
interface ManagedAdvisor extends FileReference {
	id: string; name: string; scope: string; configuration: AdvisorEntry; runtimeStatus: null;
}
interface NativeModules {
	"task/agents": typeof NativeAgents;
	"task/discovery": typeof NativeDiscovery;
	"advisor/config": typeof NativeAdvisors;
	"advisor/watchdog": typeof NativeWatchdog;
	"discovery/at-imports": typeof NativeImports;
	"discovery/index": typeof NativeCapabilities;
	"capability/index": typeof NativeRegistry;
	"extensibility/settings": typeof NativeExtensionSettings;
	"extensibility/skills": typeof NativeSkills;
	"tools/builtin-names": typeof NativeTools;
}
type AdvisorObservation = { path: string; version: string };
const advisorVersions = new Map<string, Map<string, AdvisorObservation>>();
const contextIdentity = (ctx: Context): string => JSON.stringify([path.resolve(ctx.agentDir), ctx.cwd === null ? null : path.resolve(ctx.cwd)]);

const native = <K extends keyof NativeModules>(file: K): Promise<NativeModules[K]> => import(`/opt/omp/src/${file}.ts`);
const hash = (text: string): string => createHash("sha256").update(text).digest("hex");
const fail = (message: string): never => { throw new Error(`UNAVAILABLE: ${message}`); };
const conflict = (): never => { throw new Error("CONFLICT: Configuration changed on disk. Reload before saving."); };
const record = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === "object" && !Array.isArray(value);
const scope = (value: unknown): "GLOBAL" | "PROJECT" => {
	if (value !== "GLOBAL" && value !== "PROJECT") return fail("Unknown configuration scope.");
	return value;
};
const inside = (root: string, file: string): boolean => {
	const relative = path.relative(path.resolve(root), path.resolve(file));
	return relative === "" || (!relative.startsWith(`..${path.sep}`) && relative !== ".." && !path.isAbsolute(relative));
};
async function text(file: string): Promise<string | null> {
	try { return await fs.readFile(file, "utf8"); }
	catch (error: unknown) { if (record(error) && error.code === "ENOENT") return null; return fail("Cannot read configuration file."); }
}
function mapping(content: string): Record<string, unknown> {
	try {
		const value = YAML.parse(content);
		if (value == null && !content.trim()) return {};
		if (record(value)) return value;
	} catch { /* Never include YAML diagnostics, which may quote private contents. */ }
	return fail("Configuration must contain a valid YAML mapping; use the raw editor to repair it.");
}
function metadata(content: string): Record<string, unknown> {
	const match = /^\uFEFF?---[ \t]*\r?\n([\s\S]*?)\r?\n---[ \t]*(?:\r?\n|$)/.exec(content);
	return match ? mapping(match[1]) : {};
}
async function parsed(content: string, file = "draft.md", source: AgentDefinition["source"] = "user"): Promise<ManagedAgent> {
	const { parseAgent } = await native("task/agents");
	let definition: AgentDefinition;
	try { definition = parseAgent(file, content, source, "off"); }
	catch { return fail("Invalid agent definition. Name and description are required; main and sub are reserved."); }
	return {
		name: definition.name, description: definition.description, source, path: file,
		readOnly: false, fields: metadata(content), systemPrompt: definition.systemPrompt,
		content, version: hash(content), shadows: [],
	};
}
async function render(name: unknown, fields: unknown, systemPrompt: unknown): Promise<string> {
	if (!record(fields) || typeof name !== "string" || typeof systemPrompt !== "string") return fail("Invalid agent editor values.");
	const yaml = YAML.stringify({ ...fields, name }).trimEnd();
	const content = `---\n${yaml}\n---\n${systemPrompt}`;
	await parsed(content);
	return content;
}
function root(ctx: Context, targetScope: string): string {
	if (targetScope === "GLOBAL") return ctx.agentDir;
	if (!ctx.cwd) return fail("Project configuration requires an open project.");
	return ctx.cwd;
}
function editableAgent(ctx: Context, file: string): boolean {
	return path.dirname(path.resolve(file)) === path.resolve(ctx.agentDir, "agents") ||
		(ctx.cwd !== null && inside(ctx.cwd, file) && path.basename(path.dirname(file)) === "agents" && path.basename(path.dirname(path.dirname(file))) === ".omp");
}
async function assertVersion(ctx: Context, file: string, expected: unknown): Promise<void> {
	if (typeof expected !== "string" || !expected || await ctx.version(file) !== expected) conflict();
}
async function definitions(ctx: Context, extensionRoots: EffectiveExtensionRoots): Promise<ManagedAgent[]> {
	const { discoverAgents } = await native("task/discovery");
	const { loadBundledAgents } = await native("task/agents");
	const cwd = ctx.cwd ?? path.parse(ctx.agentDir).root;
	const found = await discoverAgents(cwd, os.homedir(), extensionRoots);
	const winningPaths = new Map(found.agents.map(agent => [agent.filePath, agent]));
	const all: ManagedAgent[] = [];
	for (const dir of found.searchedDirs ?? []) {
		if (!ctx.cwd && path.resolve(dir) === path.resolve(cwd, ".omp", "agents")) continue;
		let entries: Dirent[];
		try { entries = await fs.readdir(dir, { withFileTypes: true }); }
		catch (error: unknown) { if (record(error) && (error.code === "ENOENT" || error.code === "ENOTDIR")) continue; return fail("Cannot discover agent directory."); }
		for (const entry of entries.sort((a, b) => a.name.localeCompare(b.name))) {
			if (!(entry.isFile() || entry.isSymbolicLink()) || !entry.name.endsWith(".md")) continue;
			const file = path.join(dir, entry.name);
			const content = await text(file);
			if (content === null) continue;
			const writable = editableAgent(ctx, file);
			const source = writable ? (inside(ctx.agentDir, file) ? "user" : "project") : "extension";
			let agent: ManagedAgent;
			try { agent = await parsed(content, file, winningPaths.get(file)?.source ?? "user"); }
			catch { continue; } // Native discovery also excludes malformed definitions.
			agent.source = source;
			agent.readOnly = !writable;
			if (writable) {
				const real = await fs.realpath(file);
				agent.readOnly = !inside(inside(ctx.agentDir, file) ? ctx.agentDir : ctx.cwd!, real);
			}
			all.push(agent);
		}
	}
	for (const bundled of loadBundledAgents()) {
		const fields = Object.fromEntries(Object.entries(bundled).filter(([key, value]) => !["filePath", "source", "systemPrompt"].includes(key) && value !== undefined));
		const content = await render(bundled.name, fields, bundled.systemPrompt);
		all.push({ ...await parsed(content, bundled.filePath, "bundled"), readOnly: true });
	}
	const winners = new Map<string, ManagedAgent>();
	for (const agent of all) {
		const winner = winners.get(agent.name);
		if (winner) winner.shadows.push({ source: agent.source, path: agent.path, description: agent.description });
		else winners.set(agent.name, agent);
	}
	return [...winners.values()];
}
async function watchdogCandidates(ctx: Context): Promise<ConfigCandidate[]> {
	if (ctx.cwd) {
		const { collectConfigCandidates } = await native("advisor/watchdog");
		return collectConfigCandidates(ctx.cwd, ctx.agentDir, ["WATCHDOG.yml", "WATCHDOG.yaml"]);
	}
	const items: ConfigCandidate[] = [];
	for (const name of ["WATCHDOG.yml", "WATCHDOG.yaml"]) {
		const file = path.join(ctx.agentDir, name);
		const content = await text(file);
		if (content !== null) items.push({ path: file, content, level: "user", depth: 0 });
	}
	return items;
}
async function advisorState(ctx: Context, observations: Map<string, AdvisorObservation>): Promise<{ advisors: ManagedAdvisor[]; advisorInstructions: string; advisorMaxNotes: number | null; advisorSharedByScope: Record<string, Record<string, unknown>> }> {
	const { slugifyAdvisorName } = await native("advisor/config");
	const advisors = new Map<string, ManagedAdvisor>();
	for (const candidate of await watchdogCandidates(ctx)) {
		let doc: Record<string, unknown>;
		try { doc = mapping(candidate.content); } catch { continue; }
		for (const entry of Array.isArray(doc.advisors) ? doc.advisors : []) {
			try { validateAdvisor(entry); } catch { continue; }
			const id = slugifyAdvisorName(entry.name);
			advisors.set(id, {
				id, name: entry.name, scope: candidate.level === "user" ? "GLOBAL" : "PROJECT",
				path: candidate.path, configuration: entry, version: hash(candidate.content), runtimeStatus: null,
			});
		}
	}
	// Native discovery owns shared instruction import expansion and precedence.
	const { discoverAdvisorConfigs } = await native("advisor/config");
	let effective: Pick<DiscoveredAdvisors, "sharedInstructions" | "sharedMaxNotesPerUpdate">;
	if (ctx.cwd) effective = await discoverAdvisorConfigs(ctx.cwd, ctx.agentDir);
	else {
		const { expandAtImports } = await native("discovery/at-imports");
		const shared: string[] = [];
		let maximum: number | undefined;
		for (const candidate of await watchdogCandidates(ctx)) {
			let doc: Record<string, unknown>;
			try { doc = mapping(candidate.content); } catch { continue; }
			if (typeof doc.instructions === "string" && doc.instructions.trim()) shared.push((await expandAtImports(doc.instructions, candidate.path)).trim());
			if (typeof doc.maxNotesPerUpdate === "number" && Number.isFinite(doc.maxNotesPerUpdate) && doc.maxNotesPerUpdate >= 1) maximum = Math.trunc(doc.maxNotesPerUpdate);
		}
		effective = { sharedInstructions: shared.join("\n\n"), sharedMaxNotesPerUpdate: maximum };
	}
	const advisorSharedByScope: Record<string, Record<string, unknown>> = { GLOBAL: {}, PROJECT: {} };
	for (const targetScope of ctx.cwd ? ["GLOBAL", "PROJECT"] : ["GLOBAL"]) {
		const file = await watchdogFile(ctx, targetScope);
		const version = await ctx.version(file);
		const content = await text(file);
		if (await ctx.version(file) !== version) conflict();
		observations.set(targetScope, { path: file, version });
		if (content === null) continue;
		let doc: Record<string, unknown>;
		try { doc = mapping(content); } catch { continue; }
		for (const key of ["instructions", "maxNotesPerUpdate"]) {
			if (Object.hasOwn(doc, key)) advisorSharedByScope[targetScope][key] = doc[key];
		}
	}
	return { advisors: [...advisors.values()], advisorInstructions: effective.sharedInstructions ?? "", advisorMaxNotes: effective.sharedMaxNotesPerUpdate ?? null, advisorSharedByScope };
}
function validateAdvisor(value: unknown): asserts value is AdvisorEntry {
	if (!record(value) || typeof value.name !== "string") fail("Advisor name must be text.");
	for (const key of ["model", "instructions"]) if (value[key] !== undefined && typeof value[key] !== "string") fail(`Advisor ${key} must be text.`);
	if (value.tools !== undefined && (!Array.isArray(value.tools) || value.tools.some(tool => typeof tool !== "string"))) fail("Advisor tools must be a list of names.");
	if (value.enabled !== undefined && typeof value.enabled !== "boolean") fail("Advisor enabled must be a boolean.");
	if (value.reviewMode !== undefined && value.reviewMode !== "turn" && value.reviewMode !== "agent-end") fail("Advisor review mode must be turn or agent-end.");
	if (value.syncBacklog !== undefined && !["off", "1", "3", "5", "strict", 1, 3, 5].some(mode => mode === value.syncBacklog)) fail("Invalid advisor sync backlog mode.");
	if (value.reviewInterval !== undefined && (typeof value.reviewInterval !== "number" || !Number.isSafeInteger(value.reviewInterval) || value.reviewInterval < 1)) fail("Advisor review interval must be a positive integer.");
	if (value.maxNotesPerUpdate !== undefined && (typeof value.maxNotesPerUpdate !== "number" || !Number.isFinite(value.maxNotesPerUpdate))) fail("Advisor maximum notes must be a number.");
}
async function watchdogFile(ctx: Context, targetScope: string, original?: FileReference): Promise<string> {
	const dir = root(ctx, targetScope);
	if (original && original.scope === targetScope) {
		const file = path.resolve(original.path);
		if (!inside(dir, file) || !["WATCHDOG.yml", "WATCHDOG.yaml"].includes(path.basename(file))) return fail("Advisor source is outside the editable scope.");
		return file;
	}
	const { resolveAdvisorConfigEditPath } = await native("advisor/config");
	return resolveAdvisorConfigEditPath(targetScope === "GLOBAL" ? "user" : "project", { projectDir: ctx.cwd ?? ctx.agentDir, agentDir: ctx.agentDir });
}
function reference(value: unknown): FileReference | undefined {
	if (value == null) return undefined;
	if (!record(value) || typeof value.path !== "string" || typeof value.version !== "string") return fail("Invalid original configuration reference.");
	for (const key of ["id", "source", "scope"]) if (value[key] !== undefined && typeof value[key] !== "string") return fail("Invalid configuration source.");
	if (value.readOnly !== undefined && typeof value.readOnly !== "boolean") return fail("Invalid read-only flag.");
	return { path: value.path, version: value.version, id: typeof value.id === "string" ? value.id : undefined, source: typeof value.source === "string" ? value.source : undefined, scope: typeof value.scope === "string" ? value.scope : undefined, readOnly: value.readOnly === true };
}

export async function snapshot(ctx: Context, _online = false): Promise<object> {
	const settings = await ctx.settings();
	const discovery = await native("discovery/index");
	const capability = await native("capability/index");
	const ext = await native("extensibility/settings");
	const configured = ext.cfgExtensions.get(settings);
	const extensionRoots: EffectiveExtensionRoots = { explicit: [], mode: "merge", configured, configuredLevel: settings.getProvenance(ext.cfgExtensions) === "project" ? "project" : "user" };
	const release = capability.initializeWithSettings(settings);
	try {
		const cwd = ctx.cwd ?? path.parse(ctx.agentDir).root;
		const observations = new Map<string, AdvisorObservation>();
		const [agents, customTools, skillResult, advisors, builtin] = await Promise.all([
			definitions(ctx, extensionRoots),
			discovery.loadCapability<CustomTool>("tools", { cwd, agentDir: ctx.agentDir, extensionRoots, includeDisabled: true, filter: item => Boolean(ctx.cwd) || item._source?.level !== "project" }),
			native("extensibility/skills").then(module => module.loadSkills({ ...ext.cfgSkills.get(settings), enabled: true, cwd, extensionRoots, ...(ctx.cwd ? {} : { enableClaudeProject: false, enablePiProject: false, enableAgentsProject: false }) })),
			advisorState(ctx, observations), native("tools/builtin-names"),
		]);
		const tools = new Map<string, { name: string; description: string; source: string }>(builtin.BUILTIN_TOOL_NAMES.map(name => [name, { name, description: "", source: "builtin" }]));
		for (const tool of customTools.items) if (!tools.has(tool.name)) tools.set(tool.name, { name: tool.name, description: tool.description ?? "", source: `${tool._source?.provider ?? "custom"}:${tool._source?.level ?? tool.level}` });
		advisorVersions.set(contextIdentity(ctx), observations);
		return { agents, tools: [...tools.values()], skills: skillResult.skills.filter(skill => Boolean(ctx.cwd) || skill._source?.level !== "project").map(skill => ({ name: skill.name, description: skill.description, source: skill.source })), ...advisors };
	} finally { release(); }
}

export async function dispatch(op: string, args: unknown, ctx: Context): Promise<unknown> {
	if (!record(args)) return fail("Invalid management arguments.");
	if (op === "parseAgent") {
		if (typeof args.content !== "string") return fail("Agent content must be text.");
		return parsed(args.content);
	}
	if (op === "renderAgent") return render(args.name, args.fields, args.systemPrompt);
	if (op === "saveAgent") {
		const targetScope = scope(args.scope);
		const base = root(ctx, targetScope);
		const name = args.name;
		if (typeof name !== "string" || !/^[a-zA-Z0-9][a-zA-Z0-9_-]*$/.test(name)) return fail("Agent filename must use letters, numbers, hyphens or underscores.");
		const original = reference(args.original);
		const sameScope = original && !original.readOnly && original.source === (targetScope === "GLOBAL" ? "user" : "project");
		const file = sameScope ? path.resolve(original.path) : path.join(base, targetScope === "GLOBAL" ? "agents" : ".omp/agents", `${name}.md`);
		if (!editableAgent(ctx, file) || !inside(base, file)) return fail("Agent source is outside the editable scope.");
		const content = await render(name, args.fields, args.systemPrompt);
		if (sameScope) {
			await assertVersion(ctx, file, original.version);
			const current = await text(file);
			if (current === null) conflict();
			const oldFields = metadata(current);
			if (!record(args.fields) || typeof args.systemPrompt !== "string") return fail("Invalid agent editor values.");
			const nextFields: Record<string, unknown> = { ...args.fields, name };
			const changes: Record<string, unknown> = {};
			for (const key of new Set([...Object.keys(oldFields), ...Object.keys(nextFields)])) {
				if (JSON.stringify(oldFields[key]) !== JSON.stringify(nextFields[key])) changes[key] = nextFields[key] ?? null;
			}
			await ctx.patchYaml(file, changes, original.version, args.systemPrompt);
		} else {
			const version = await ctx.version(file);
			if (await text(file) !== null) conflict();
			await ctx.atomicWrite(file, content, version);
		}
		return { path: file };
	}
	if (op === "deleteAgent") {
		const agent = reference(args.agent);
		if (!agent || agent.readOnly || !editableAgent(ctx, agent.path)) return fail("This agent definition is read-only.");
		await assertVersion(ctx, agent.path, agent.version);
		await ctx.atomicDelete(agent.path, agent.version);
		return null;
	}
	if (["saveAdvisor", "deleteAdvisor", "updateAdvisorShared"].includes(op)) {
		const original = reference(op === "deleteAdvisor" ? args.advisor : args.original);
		const targetScope = scope(op === "deleteAdvisor" ? original?.scope : args.scope);
		const file = await watchdogFile(ctx, targetScope, original);
		const version = await ctx.version(file);
		const content = await text(file);
		if (await ctx.version(file) !== version) conflict();
		if (original?.path === file) await assertVersion(ctx, file, original.version);
		const doc = content === null ? {} : mapping(content);
		if (op === "updateAdvisorShared") {
			const observed = advisorVersions.get(contextIdentity(ctx))?.get(targetScope);
			if (!observed || observed.path !== file || observed.version !== version) conflict();
			if (typeof args.instructions !== "string" || (args.maxNotes != null && (typeof args.maxNotes !== "number" || !Number.isSafeInteger(args.maxNotes) || args.maxNotes < 1))) return fail("Shared instructions must be text and maximum notes a positive integer.");
			await ctx.patchYaml(file, { instructions: args.instructions || null, maxNotesPerUpdate: args.maxNotes ?? null }, version);
			advisorVersions.get(contextIdentity(ctx))?.delete(targetScope);
			return null;
		}
		if (doc.advisors !== undefined && !Array.isArray(doc.advisors)) return fail("WATCHDOG advisors must be a list; repair the raw file first.");
		const entries: unknown[] = Array.isArray(doc.advisors) ? [...doc.advisors] : [];
		const { slugifyAdvisorName } = await native("advisor/config");
		const originalId = original?.id;
		const index = original && original.path === file ? entries.findIndex(entry => record(entry) && typeof entry.name === "string" && slugifyAdvisorName(entry.name) === originalId) : -1;
		if (original?.path === file && index < 0) conflict();
		if (op === "deleteAdvisor") {
			if (!original || original.path !== file || index < 0) conflict();
			entries.splice(index, 1);
		}
		else {
			validateAdvisor(args.configuration);
			const id = slugifyAdvisorName(args.configuration.name);
			if (entries.some((entry, at) => at !== index && record(entry) && typeof entry.name === "string" && slugifyAdvisorName(entry.name) === id)) return fail("An advisor with this name already exists in the selected scope.");
			if (index < 0) entries.push(args.configuration); else entries[index] = args.configuration;
		}
		await ctx.patchYaml(file, { advisors: entries }, version);
		advisorVersions.get(contextIdentity(ctx))?.delete(targetScope);
		return null;
	}
	if (op === "generateAgent") {
		if (typeof args.description !== "string" || !args.description.trim()) return fail("Describe the agent to generate.");
		// Container-only inference modules must not gate definition discovery or editing.
		const [{ getRegistry }, { completeSimple }, { pickDefaultAvailableModel, resolveModelRoleValue }, { getRoleInfo }, { cfgDefaultThinkingLevel }] = await Promise.all([
			import("./auth-models.ts"),
			import("/opt/omp/node_modules/@oh-my-pi/pi-ai/src/index.ts"),
			import("/opt/omp/src/config/model-resolver.ts"),
			import("/opt/omp/src/config/model-roles.ts"),
			import("/opt/omp/src/session/settings.ts"),
		]);
		const settings = await ctx.settings();
		const registry = await getRegistry(ctx);
		const available = registry.getAvailable("all").filter(getRoleInfo("default", settings).accepts);
		const configured = settings.getModelRole("default");
		const resolved = resolveModelRoleValue(configured || "@default", available, { settings });
		if (!configured && !resolved.model) resolved.model = pickDefaultAvailableModel(available, provider => registry.hasConcreteAuth(provider));
		const selected = resolved.model;
		if (!selected) return fail("No available model matches the default role. Configure its model and credentials first.");
		const thinking = resolved.thinkingLevel ?? cfgDefaultThinkingLevel.get(settings);
		const systemPrompt = await fs.readFile("/opt/omp/src/prompts/system/agent-creation-architect.md", "utf8");
		try {
			// A stateless native completion cannot start advisors, auto-thinking judges,
			// prewalks, maintenance agents, or retry chains on another model.
			const message = await completeSimple(selected, {
				systemPrompt,
				messages: [{ role: "user", content: [{ type: "text", text: `Custom agent request:\n\n${args.description}\n\nMUST return only JSON object required by system instructions. NEVER include markdown fences.` }], timestamp: Date.now() }],
				tools: [],
			}, { apiKey: registry.resolver(selected), reasoning: thinking === "off" || thinking === "inherit" ? undefined : thinking, disableReasoning: thinking === "off" });
			if (message.stopReason === "error" || message.stopReason === "aborted") return fail("Agent generation failed; no specification was saved.");
			const raw = message.content.flatMap(block => block.type === "text" ? [block.text] : []).join("\n");
			let spec: unknown;
			try { spec = JSON.parse(raw); } catch { return fail("Generation returned an invalid agent specification; no file was saved."); }
			if (!record(spec) || typeof spec.identifier !== "string" || !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(spec.identifier) || typeof spec.whenToUse !== "string" || typeof spec.systemPrompt !== "string") return fail("Generation returned an invalid agent specification; no file was saved.");
			return render(spec.identifier, { description: spec.whenToUse }, spec.systemPrompt);
		} catch (error: unknown) {
			if (error instanceof Error && error.message.startsWith("UNAVAILABLE:")) throw error;
			return fail("Agent generation failed with the default model. Check provider availability and credentials; no specification was saved.");
		}
	}
	return fail("Unsupported agents management operation.");
}
