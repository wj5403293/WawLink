import * as fs from "node:fs/promises";
import { createHash } from "node:crypto";
import { join } from "node:path";
import { ManagementDiagnostic, validateNativeModels } from "./diagnostics.mjs";
import type { AuthStorage as NativeAuth, StoredAuthCredential as NativeAccount } from "/opt/omp/src/session/auth-storage.ts";
import type { ModelRegistry as NativeRegistry } from "/opt/omp/src/config/model-registry.ts";
import type { Settings as NativeSettings } from "/opt/omp/src/config/settings.ts";
import type { Model as NativeModel } from "/opt/omp/node_modules/@oh-my-pi/pi-catalog/src/types.ts";
import type { OAuthProviderInfo as NativeProvider } from "/opt/omp/node_modules/@oh-my-pi/pi-ai/src/registry/oauth/types.ts";
import type { ModelRoleInfo as NativeRoleInfo } from "/opt/omp/src/config/model-roles.ts";

// Platform-specific sources exist only inside the installed container; their exports are ESM-only.
const ai = await import("/opt/omp/node_modules/@oh-my-pi/pi-ai/src/index.ts");
const { getOAuthCredentialProvider } = await import("/opt/omp/node_modules/@oh-my-pi/pi-ai/src/registry/oauth/index.ts");
const { authPolicyFor, authProviders: builtInAuthProviders } = await import("/opt/omp/node_modules/@oh-my-pi/pi-catalog/src/compat/auth.ts");
const { modelKind, MODEL_KINDS, RUNNER_API_KINDS } = await import("/opt/omp/node_modules/@oh-my-pi/pi-catalog/src/types.ts");
const { getSupportedEfforts } = await import("/opt/omp/node_modules/@oh-my-pi/pi-catalog/src/model-thinking.ts");
const { getBundledModel, getBundledProviders, isGeneratedProvider } = await import("/opt/omp/node_modules/@oh-my-pi/pi-catalog/src/models.ts");
const { ModelRegistry } = await import("/opt/omp/src/config/model-registry.ts");
const { ModelsConfigFile } = await import("/opt/omp/src/config/models-config.ts");
const { buildCustomModelOverlay, finalizeCustomModel } = await import("/opt/omp/src/config/custom-models.ts");
const resolver = await import("/opt/omp/src/config/model-resolver.ts");
const roles = await import("/opt/omp/src/config/model-roles.ts");
const { cfgDefaultThinkingLevel } = await import("/opt/omp/src/session/settings.ts");
const { cfgModelRoleStorage } = await import("/opt/omp/src/config/model-settings.ts");
const { discoverAuthStorage, getAuthBrokerTokenFilePath, loadEffectiveAuthAccountPolicyConfig } = await import("/opt/omp/src/session/auth-broker-config.ts");
const { SPECIAL_MODEL_MANAGER_PROVIDER_IDS } = await import("/opt/omp/src/config/model-provider-discovery.ts");
const { PROVIDER_DESCRIPTORS } = await import("/opt/omp/node_modules/@oh-my-pi/pi-catalog/src/provider-models/index.ts");
const builtinProviderIds = new Set([...getBundledProviders(), ...builtInAuthProviders().map(provider => getOAuthCredentialProvider(provider.id)), "local", "web", "ollama", "llama.cpp", "lm-studio", "apple"]);
// A models.dev-only remote metadata layer is not a provider model-list API.
// Factories only construct options/closures. A non-secret presence marker enables
// credential-gated closures for capability inspection; none are invoked here.
const builtinDiscoveryIds = new Set([...SPECIAL_MODEL_MANAGER_PROVIDER_IDS,
  ...PROVIDER_DESCRIPTORS.filter(descriptor => typeof descriptor.createModelManagerOptions({ apiKey: "wawlink-capability-inspection" }).fetchDynamicModels === "function").map(descriptor => descriptor.providerId)]);

interface RoleLookup { getModelRole(role: string): string | undefined; }
interface Challenge { provider: string; url?: string | null; instructions?: string | null; prompt?: string | null; secret?: boolean; }
interface PendingLogin {
  provider: string;
  controller: AbortController;
  challenge: Challenge;
  answer?: (value: string) => void;
  done?: Promise<void>;
}
interface ManagementContext {
  cwd: string | null;
  agentDir: string;
  sessionId?: string | null;
  settings(): Promise<NativeSettings>;
  send(event: object): void;
  pendingLogin?: PendingLogin;
  pinAccount?(provider: string, accountId: string | null): Promise<unknown>;
  accountPins?(): Promise<Record<string, string>>;
  browserSession?(request: { url: string; cookieNames: readonly string[] }, signal?: AbortSignal): Promise<string>;
}

interface AuthEntry { key: string; ready: Promise<NativeAuth>; }
interface RegistryEntry {
  settingsKey: string;
  modelsKey: string;
  credentialsKey: string;
  registry: NativeRegistry;
  extensionsLoaded: boolean;
  registeredProviders: Set<string>;
}
const stores = new WeakMap<object, AuthEntry>();
const registries = new WeakMap<object, RegistryEntry>();
const registryLoads = new WeakMap<object, Promise<NativeRegistry>>();
const digest = (value: unknown): string => createHash("sha256").update(JSON.stringify(value)).digest("hex");
async function fileVersion(file: string): Promise<string | null> {
  try { return createHash("sha256").update(await fs.readFile(file)).digest("hex"); }
  catch (error) {
    if ((error as NodeJS.ErrnoException)?.code === "ENOENT") return null;
    throw Error("UNAVAILABLE: Cannot read native model or authentication configuration");
  }
}
async function modelsVersion(ctx: ManagementContext): Promise<string> {
  return digest(await Promise.all(["models.yml", "models.yaml", "models.json"].map(name => fileVersion(join(ctx.agentDir, name)))));
}
async function storage(ctx: ManagementContext, settings: NativeSettings): Promise<NativeAuth> {
  const global = settings.getGlobalSettings();
  const authConfig = global.auth as { broker?: unknown } | undefined;
  const key = digest([ctx.agentDir, authConfig?.broker, global["auth.broker.url"], global["auth.broker.token"],
    process.env.OMP_AUTH_BROKER_URL, process.env.OMP_AUTH_BROKER_TOKEN, await fileVersion(getAuthBrokerTokenFilePath())]);
  const policy = await loadEffectiveAuthAccountPolicyConfig({ settings });
  let entry = stores.get(ctx);
  const reused = entry?.key === key;
  if (!reused) {
    if (ctx.pendingLogin) await cancelLogin(ctx);
    registries.get(ctx)?.registry.syncExtensionSources([]);
    registries.delete(ctx);
    const previous = await entry?.ready.catch(() => undefined);
    previous?.close();
    const ready = discoverAuthStorage(ctx.agentDir, { accountPolicies: policy.accountPolicies, authStorageOptions: { defaultReservePct: policy.defaultReservePct } });
    entry = { key, ready };
    stores.set(ctx, entry);
    ready.catch(() => { if (stores.get(ctx)?.ready === ready) stores.delete(ctx); });
  }
  const auth = await entry!.ready;
  if (reused) await auth.credentials.reload();
  auth.setAccountPolicies(policy);
  return auth;
}

async function assertRegistryConfiguration(ctx: ManagementContext, registry: NativeRegistry): Promise<void> {
  const error = registry.getError();
  if (!error) return;
  for (const name of ["models.yml", "models.yaml"]) {
    const file = join(ctx.agentDir, name);
    let content: string;
    try { content = await fs.readFile(file, "utf8"); }
    catch (failure) { if ((failure as NodeJS.ErrnoException)?.code === "ENOENT") continue; throw failure; }
    await validateNativeModels(content, file);
    // A cached ConfigError may contain values from a previous document. The
    // fresh schema/aux validator above supplies all current field diagnostics;
    // never fall back to an old native message or source excerpt.
    const stage = String(error.other?.stage ?? "model loading").replace(/[^a-zA-Z0-9 ().-]/g, "");
    throw new ManagementDiagnostic(`UNAVAILABLE: ${file}: Native registry rejected configuration during ${stage}`);
  }
  throw new ManagementDiagnostic(`UNAVAILABLE: ${join(ctx.agentDir, "models.yml")}: Native model configuration could not be loaded`);
}

async function updateRegistry(ctx: ManagementContext, settings: NativeSettings, online: boolean): Promise<NativeRegistry> {
  const auth = await storage(ctx, settings);
  const settingsKey = digest([ctx.agentDir, ctx.cwd, settings.getGlobalSettings(), settings.getProjectSettings()]);
  const modelsKey = await modelsVersion(ctx);
  const credentialsKey = digest(auth.credentials.list());
  let entry = registries.get(ctx);
  if (!entry || entry.settingsKey !== settingsKey || entry.registry.authStorage !== auth) {
    entry?.registry.syncExtensionSources([]);
    auth.keys.clearConfig();
    // The default-path ConfigFile is a singleton, even across registry instances.
    ModelsConfigFile.invalidate();
    const registry = new ModelRegistry(auth, join(ctx.agentDir, "models.yml"), { settings });
    entry = { settingsKey, modelsKey: await modelsVersion(ctx), credentialsKey, registry, extensionsLoaded: false, registeredProviders: new Set() };
    registries.set(ctx, entry);
  } else if (entry.modelsKey !== modelsKey || entry.credentialsKey !== credentialsKey) {
    entry.modelsKey = modelsKey;
    entry.credentialsKey = credentialsKey;
    // Force native invalidation, including same-mtime edits and missing-file transitions.
    // This preserves runtime extension registrations and rehydrates credential-scoped caches.
    await entry.registry.reapplyModelPolicies();
  }
  const registry = entry.registry;
  await assertRegistryConfiguration(ctx, registry);
  if (!entry.extensionsLoaded || online) {
    // Installed-container SDK modules are unavailable on the JVM build host and
    // must stay outside configuration loading and invalid-file repair paths.
    const { loadSessionExtensions } = await import("/opt/omp/src/sdk.ts");
    const { EventBus } = await import("/opt/omp/src/utils/event-bus.ts");
    // Match loadCliExtensionProviders' native registration lifecycle, retaining
    // IDs as well: discovery-only extensions can legitimately have no models.
    const extensions = await loadSessionExtensions({}, ctx.cwd ?? "/", settings, new EventBus());
    const sources = extensions.extensions.map(extension => extension.path);
    registry.syncExtensionSources(sources);
    for (const source of new Set(sources)) registry.clearSourceRegistrations(source);
    entry.registeredProviders.clear();
    for (const { name, config, sourceId } of extensions.runtime.pendingProviderRegistrations) {
      registry.registerProvider(name, config, sourceId);
      entry.registeredProviders.add(name);
    }
    extensions.runtime.pendingProviderRegistrations = [];
    entry.extensionsLoaded = true;
    await registry.refresh(online ? "online" : "offline");
  }
  await assertRegistryConfiguration(ctx, registry);
  return registry;
}

async function registryFor(ctx: ManagementContext, settings: NativeSettings, online = false): Promise<NativeRegistry> {
  const pending = (registryLoads.get(ctx) ?? Promise.resolve()).catch(() => undefined).then(() => updateRegistry(ctx, settings, online));
  registryLoads.set(ctx, pending);
  try { return await pending; }
  finally { if (registryLoads.get(ctx) === pending) registryLoads.delete(ctx); }
}

/** Also used by agent generation, so generation and management share native credentials. */
export async function getRegistry(ctx: ManagementContext): Promise<NativeRegistry> {
  return registryFor(ctx, await ctx.settings());
}

function text(value: unknown): string | undefined {
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}

function accountId(value: unknown): number {
  if (typeof value !== "string" || !/^[1-9]\d*$/.test(value)) throw Error("UNAVAILABLE: Invalid stored account identifier");
  const id = Number(value);
  if (!Number.isSafeInteger(id)) throw Error("UNAVAILABLE: Invalid stored account identifier");
  return id;
}

function accountLabel(row: NativeAccount): string {
  const value = row.credential;
  if (value.type === "api_key") return `API key #${row.id}`;
  const base = text(value.email) ?? text(value.accountId) ?? text(value.projectId) ?? `OAuth account #${row.id}`;
  const org = text(value.orgName) ?? text(value.orgId);
  return org && org !== base ? `${base} (${org})` : base;
}


function resolveRole(registry: NativeRegistry, settings: NativeSettings, role: string, lookup: RoleLookup = settings, source?: string, availableModels = registry.getAvailable("all")) {
  const info = roles.getRoleInfo(role, settings);
  const pool = availableModels.filter(info.accepts);
  const configured = lookup.getModelRole(role);
  const selector = configured || roles.formatModelRoleAlias(role);
  const result = resolver.resolveModelRoleValue(selector, pool, { settings, roleLookup: lookup });
  // Default has no built-in alias chain; use the very same startup fallback.
  if (role === "default" && !configured && !result.model) {
    result.model = resolver.pickDefaultAvailableModel(pool, (provider: string) => registry.hasConcreteAuth(provider));
  }
  const patterns = resolver.resolveConfiguredModelPatterns(selector, lookup);
  const diagnostics: string[] = [];
  for (const pattern of patterns.slice(0, result.model ? result.matchedPatternIndex ?? 0 : patterns.length)) {
    const skipped = resolver.resolveModelRoleValue(pattern, pool, { settings, roleLookup: lookup });
    if (skipped.warning) diagnostics.push(skipped.warning);
    if (!skipped.model) diagnostics.push(`No available model accepted by this role matches ${pattern}`);
  }
  if (result.warning) diagnostics.push(result.warning);
  return {
    id: role,
    name: info.name,
    kind: ({ image: "image", web: "search", speech: "tts", dictation: "stt", judge: "judge" } as Record<string, string>)[role] ?? "chat",
    model: result.model ? resolver.formatModelStringWithRouting(result.model) : null,
    thinking: result.thinkingLevel ?? (role === "default" ? cfgDefaultThinkingLevel.get(settings) : null),
    error: result.model ? null : "No available model matches this role's native candidate chain",
    source: source ?? settings.getModelRoleProvenance(role),
    automatic: !configured,
    diagnostics,
  };
}

async function previewRole(args: Record<string, unknown>, ctx: ManagementContext) {
  const settings = await ctx.settings();
  const registry = await registryFor(ctx, settings);
  const role = text(args.role);
  if (!role || !roles.getKnownRoleIds(settings).includes(role)) throw Error("UNAVAILABLE: Unknown native model role");
  const scope = String(args.scope).toUpperCase();
  if (scope !== "GLOBAL" && scope !== "PROJECT") throw Error("UNAVAILABLE: Invalid configuration scope");
  if (scope === "PROJECT" && !ctx.cwd) throw Error("UNAVAILABLE: A project is required for project role previews");
  if (args.selector !== null && typeof args.selector !== "string") throw Error("UNAVAILABLE: Invalid model selector");
  const selectedLayer = scope.toLowerCase();
  const provenance = settings.getModelRoleProvenance(role);
  const projectEnabled = cfgModelRoleStorage.get(settings) === "project";
  const shadowed = provenance === "runtime" || provenance === "overlay" ||
    (scope === "GLOBAL" && provenance === "project") || (scope === "PROJECT" && !projectEnabled);
  let value = settings.getModelRole(role);
  let source: string = provenance;
  if (!shadowed) {
    if (args.selector === null || args.selector.trim() === "") {
      value = scope === "PROJECT" ? settings.getGlobalModelRole(role) : undefined;
      source = value ? "global" : "default";
    } else {
      value = args.selector.trim() || undefined;
      source = value ? selectedLayer : "default";
    }
  }
  const lookup = {
    getModelRole: (id: string) => id === role ? value : settings.getModelRole(id),
    getModelRoles: () => ({ ...settings.getModelRoles(), [role]: value }),
  };
  return resolveRole(registry, settings, role, lookup, source);
}

/** Redacted display reference only: never round-trip this object as a provider edit. */
function declarationValue(value: unknown): unknown {
  if (typeof value === "string") {
    if (!/^[a-z][a-z0-9+.-]*:\/\//i.test(value)) return value;
    try {
      const url = new URL(value);
      url.username = "";
      url.password = "";
      url.search = "";
      url.hash = "";
      return url.toString();
    } catch { return undefined; }
  }
  if (value === null || typeof value === "boolean" || typeof value === "number") return value;
  if (Array.isArray(value)) return value.map(declarationValue).filter(item => item !== undefined);
  if (value && typeof value === "object") {
    const result: Record<string, unknown> = {};
    for (const [key, child] of Object.entries(value)) {
      if (/api.?key|authorization|cookie|secret|password|credential|headers|extraBody|bearer|accountAccess|requestMetadata|^(?:(?:api|auth|id|session|access|refresh)[_-]?)?token$/i.test(key)) continue;
      const safe = declarationValue(child);
      if (safe !== undefined) result[key] = safe;
    }
    return result;
  }
  return undefined;
}

// Display-only allowlist: never expose header resolvers, per-account access,
// tenant identities or opaque request metadata contributed by provider discovery.
function modelDeclaration(model: NativeModel): unknown {
  return declarationValue({
    id: model.id, provider: model.provider, name: model.name, api: model.api,
    kind: modelKind(model), baseUrl: model.baseUrl, input: model.input,
    reasoning: model.reasoning, thinking: model.thinking,
    contextWindow: model.contextWindow, maxContextWindow: model.maxContextWindow,
    maxTokens: model.maxTokens, cost: model.cost, pricingStatus: model.pricingStatus,
    promptCache: model.promptCache, premiumMultiplier: model.premiumMultiplier,
    tokenizer: model.tokenizer, imageInputDecoder: model.imageInputDecoder,
    imageCountBudget: model.imageCountBudget, supportsTools: model.supportsTools,
    omitMaxOutputTokens: model.omitMaxOutputTokens, preferWebsockets: model.preferWebsockets,
    compat: model.compat, contextPromotionTarget: model.contextPromotionTarget,
    compactionModel: model.compactionModel, remoteCompaction: model.remoteCompaction,
    requestModelId: model.requestModelId, webSearch: model.webSearch,
    webSearchModel: model.webSearchModel, hostedImage: model.hostedImage,
    imageModel: model.imageModel, serviceTiers: model.serviceTiers,
    kindConfig: model.kindConfig, identity: model.identity, providerType: model.providerType,
    reasoningMode: model.reasoningMode, description: model.description,
    requiresGlyphTokenization: model.requiresGlyphTokenization,
    requiresCursorToolSchemaProjection: model.requiresCursorToolSchemaProjection,
    toolSchemasInContext: model.toolSchemasInContext,
    requiresToolResultImageHoisting: model.requiresToolResultImageHoisting,
    supportsAssistantPrefill: model.supportsAssistantPrefill,
    supportsComputerUse: model.supportsComputerUse, supportsComputerUseConfig: model.supportsComputerUseConfig,
    cursorMaxMode: model.cursorMaxMode, cursorMaxModeRoutes: model.cursorMaxModeRoutes,
    cursorModelParameters: model.cursorModelParameters, cursorModelRoutes: model.cursorModelRoutes,
    cursorPrice: model.cursorPrice, cursorRequiresDataRetention: model.cursorRequiresDataRetention,
    cursorSupportsAgent: model.cursorSupportsAgent, cursorSupportsSandboxing: model.cursorSupportsSandboxing,
    factoryDroidApiProviders: model.factoryDroidApiProviders, factoryDroidRoutingSource: model.factoryDroidRoutingSource,
    factoryDroidCredits: model.factoryDroidCredits, promptCacheConfig: model.promptCacheConfig,
    contextWindowAuthoritative: model.contextWindowAuthoritative, longContextWindowUncapped: model.longContextWindowUncapped,
    transport: model.transport, useResponsesLite: model.useResponsesLite,
    priority: model.priority, serviceTierCost: model.serviceTierCost,
    isNew: model.isNew, isBeta: model.isBeta, isProviderDefault: model.isProviderDefault, isRecommended: model.isRecommended,
    int: model.int, tps: model.tps, compatConfig: model.compatConfig,
    applyPatchToolType: model.applyPatchToolType, editPromptVariant: model.editPromptVariant, isOAuth: model.isOAuth,
    guardrailIdentifier: model.guardrailIdentifier, guardrailVersion: model.guardrailVersion, guardrailTrace: model.guardrailTrace,
  });
}

type RoleInfo = { id: string; info: NativeRoleInfo };
function modelSummary(model: NativeModel, registry: NativeRegistry, roleInfo: RoleInfo[], available: boolean, disabled: Set<string>) {
  const discovery = registry.getProviderDiscoveryState(model.provider);
  return {
    provider: model.provider, id: model.id, name: model.name ?? model.id,
    kind: modelKind(model), api: model.api, input: model.input ?? [], available,
    unavailableReason: available ? null : disabled.has(model.provider) ? "Provider disabled by native settings" : "No native authentication source is configured",
    metadata: {
      builtin: isGeneratedProvider(model.provider) && getBundledModel(model.provider, model.id) !== undefined,
      acceptedRoles: roleInfo.filter(({ info }) => info.accepts(model)).map(({ id }) => id),
      reasoning: model.reasoning === true, thinkingEfforts: [...getSupportedEfforts(model)],
      thinkingDefault: model.thinking?.defaultLevel ?? null, thinkingMode: model.thinking?.mode ?? null,
      contextWindow: model.contextWindow ?? null, maxTokens: model.maxTokens ?? null,
      supportsTools: model.supportsTools ?? null,
      ...(discovery ? { discoveryStatus: discovery.status, discoveryStale: discovery.stale === true } : {}),
    },
  };
}

export async function modelDetails(ctx: ManagementContext, provider: unknown, modelId: unknown): Promise<object> {
  if (typeof provider !== "string" || !provider || typeof modelId !== "string" || !modelId) throw Error("UNAVAILABLE: Invalid model detail target");
  const settings = await ctx.settings();
  const registry = await registryFor(ctx, settings);
  // Native find() resolves aliases and hides disabled providers. Inspection must
  // instead return only the exact provider/id requested, including disabled rows.
  const model = registry.getProviderModels(provider).find(candidate => candidate.provider === provider && candidate.id === modelId);
  if (!model) throw Error("UNAVAILABLE: The model is no longer in the native catalog");
  const roleInfo = roles.getKnownRoleIds(settings).map(id => ({ id, info: roles.getRoleInfo(id, settings) }));
  const available = registry.getAvailableForProviders(new Set([provider]), "all").some(candidate => candidate.provider === provider && candidate.id === modelId);
  const summary = modelSummary(model, registry, roleInfo, available, resolver.disabledProviderIds(settings));
  const catalogue = isGeneratedProvider(provider) ? getBundledModel(provider, modelId) : undefined;
  return {
    ...summary,
    metadata: { ...summary.metadata, modelKinds: MODEL_KINDS, runnerApiKinds: RUNNER_API_KINDS,
      baseline: modelDeclaration(model), catalogueBaseline: catalogue ? modelDeclaration(catalogue) : null },
  };
}

export function modelDefaults(provider: unknown, modelId: unknown, api: unknown): unknown {
  if (typeof provider !== "string" || typeof modelId !== "string" || typeof api !== "string" || !api) throw Error("UNAVAILABLE: Invalid model default context");
  const catalogue = isGeneratedProvider(provider) ? getBundledModel(provider, modelId) : undefined;
  if (catalogue) return modelDeclaration(catalogue);
  // Reuse the same native reference inheritance and defaults as a new custom row.
  // No credentials, endpoint resolution, registry mutation or model request is needed.
  const overlay = buildCustomModelOverlay(provider, "", api, undefined, undefined, undefined, undefined, undefined, undefined, { id: modelId });
  if (!overlay) throw Error("UNAVAILABLE: Native model defaults require an API");
  return modelDeclaration(finalizeCustomModel(overlay, { useDefaults: true }));
}

function registeredLoginProviders() {
  return ai.getOAuthProviders().filter((provider: NativeProvider) => provider.available).map((provider: NativeProvider) => ({
    id: provider.id, name: provider.name,
    // auth: oauth in models.yml is only a resolver policy, never registration.
    oauth: authPolicyFor(provider.id)?.login?.kind !== "api-key",
  }));
}

function providerSummary(id: string, registry: NativeRegistry, disabled: ReadonlySet<string>, availableProviders: Set<string>, authProviders = registeredLoginProviders()) {
  const oauth = authProviders.find(provider => provider.id === id)?.oauth === true;
  const enabled = !disabled.has(id) && (availableProviders.has(id) || registry.hasConcreteAuth(id) ||
    registry.authStorage.keys.source(id) !== undefined || registry.authStorage.keys.keyless(id));
  const providerModels = registry.getProviderModels(id);
  const apis = new Set(providerModels.map(model => model.api));
  return {
    id, name: authProviders.find(provider => provider.id === id)?.name ?? authPolicyFor(id)?.name ?? id,
    builtin: builtinProviderIds.has(id), enabled, oauth,
    canFetchModels: enabled && (registry.getDiscoveryProviderId(id) !== undefined || builtinDiscoveryIds.has(id)),
    baseUrl: declarationValue(registry.getProviderBaseUrl(id)) ?? null,
    api: apis.size === 1 ? [...apis][0] : null,
  };
}

export async function modelsSnapshot(ctx: ManagementContext, online = false): Promise<object> {
  const settings = await ctx.settings();
  const registry = await registryFor(ctx, settings, online);
  const knownRoles: string[] = roles.getKnownRoleIds(settings);
  const roleInfo = knownRoles.map(id => ({ id, info: roles.getRoleInfo(id, settings) }));
  const availableModels = registry.getAvailable("all");
  const available = new Set(availableModels.map(model => `${model.provider}\0${model.id}`));
  const disabled = resolver.disabledProviderIds(settings);
  const models = registry.getAll("all").map(model => modelSummary(model, registry, roleInfo, available.has(`${model.provider}\0${model.id}`), disabled));
  const auth = registry.authStorage;
  const pins = ctx.sessionId && typeof ctx.accountPins === "function" ? await ctx.accountPins() : {};
  const accounts = auth.credentials.list().map((row: NativeAccount) => ({
    provider: row.provider, id: String(row.id), label: accountLabel(row), type: row.credential.type,
    status: row.disabledCause ? "disabled" : "stored", quota: null,
    pinned: String(pins?.[row.provider] ?? "") === String(row.id),
  }));
  const authProviders = registeredLoginProviders();
  const configuredIds = Object.keys(ModelsConfigFile.relocate(join(ctx.agentDir, "models.yml")).load()?.providers ?? {});
  const providerIds = new Set([...builtinProviderIds, ...configuredIds, ...registries.get(ctx)!.registeredProviders, ...models.map(model => model.provider), ...registry.getDiscoverableProviders(), ...authProviders.map(provider => getOAuthCredentialProvider(provider.id)), ...accounts.map(account => account.provider)]);
  const availableProviders = new Set(availableModels.map(model => model.provider));
  const providers = [...providerIds].map(id => providerSummary(id, registry, disabled, availableProviders, authProviders));
  return {
    models, providers, roles: knownRoles.map(role => resolveRole(registry, settings, role, settings, undefined, availableModels)), accounts, authProviders,
    authentication: ctx.pendingLogin?.challenge ?? null,
  };
}

function publish(ctx: ManagementContext, pending: PendingLogin, fields: Partial<Challenge>): void {
  if (ctx.pendingLogin !== pending || pending.controller.signal.aborted) return;
  pending.challenge = { ...pending.challenge, ...fields, provider: pending.provider };
  ctx.send({ event: "authentication", challenge: pending.challenge });
}

function ask(ctx: ManagementContext, pending: PendingLogin, prompt: string, secret = false, signal?: AbortSignal): Promise<string> {
  const { promise, resolve, reject } = Promise.withResolvers<string>();
  if (pending.answer) { reject(Error("UNAVAILABLE: Authentication already has a pending prompt")); return promise; }
  const loginSignal = pending.controller.signal;
  const cleanup = () => {
    loginSignal.removeEventListener("abort", cancel);
    signal?.removeEventListener("abort", cancel);
    if (pending.answer === answer) pending.answer = undefined;
  };
  const cancel = () => { cleanup(); reject(Error("UNAVAILABLE: Authentication cancelled")); };
  const answer = (value: string) => { cleanup(); resolve(value); };
  if (loginSignal.aborted || signal?.aborted) { cancel(); return promise; }
  pending.answer = answer;
  loginSignal.addEventListener("abort", cancel, { once: true });
  signal?.addEventListener("abort", cancel, { once: true });
  publish(ctx, pending, { prompt, secret });
  return promise;
}

async function cancelLogin(ctx: ManagementContext): Promise<void> {
  const pending = ctx.pendingLogin;
  if (pending) {
    // Remove ownership first so callbacks from an old flow cannot replace a new challenge.
    ctx.pendingLogin = undefined;
    pending.controller.abort();
    await pending.done;
  }
  ctx.send({ event: "authentication", challenge: null });
}

async function login(provider: unknown, ctx: ManagementContext): Promise<boolean> {
  if (typeof provider !== "string") throw Error("UNAVAILABLE: Invalid authentication provider");
  await cancelLogin(ctx);
  const registry = await getRegistry(ctx);
  if (!ai.getOAuthProviders().some((entry: NativeProvider) => entry.id === provider && entry.available)) {
    throw Error("UNAVAILABLE: Authentication provider is unavailable");
  }
  const pending: PendingLogin = { provider, controller: new AbortController(), challenge: { provider } };
  ctx.pendingLogin = pending;
  // The command acknowledges immediately; native OAuth proceeds independently of the request queue.
  pending.done = Promise.resolve().then(async () => {
    let failure: string | undefined;
    try {
      await registry.authStorage.oauth.login(provider, {
        signal: pending.controller.signal,
        onAuth: (info: { url: string; instructions?: string }) => publish(ctx, pending, { url: info.url, instructions: info.instructions ?? null, prompt: null, secret: false }),
        onPrompt: (prompt: { message: string; secret?: boolean }) => ask(ctx, pending, prompt.message, prompt.secret === true),
        ...(ai.PASTE_CODE_LOGIN_PROVIDERS.has(provider) ? {
          onManualCodeInput: (signal?: AbortSignal) => ask(ctx, pending, "Paste the authorization code or full redirect URL", false, signal),
        } : {}),
        ...(typeof ctx.browserSession === "function" ? {
          onBrowserSession: (request: { url: string; cookieNames: readonly string[] }, signal?: AbortSignal) => ctx.browserSession!(request, signal ?? pending.controller.signal),
        } : {}),
      });
      if (!pending.controller.signal.aborted) {
        await registry.refreshProvider(getOAuthCredentialProvider(provider), "online");
      }
    } catch (error) {
      if (ctx.pendingLogin === pending && !pending.controller.signal.aborted) {
        const message = error instanceof Error ? error.message : "";
        const http = message.match(/(?:HTTP|status(?: code)?)\s*[:=]?\s*([1-5]\d\d)\b/i)?.[1];
        const reason = http ? `HTTP ${http}` : /invalid_grant|invalid.*(?:code|token)|access.denied/i.test(message) ? "authorization was rejected"
          : /expired/i.test(message) ? "authorization expired"
          : /timed?\s*out|timeout/i.test(message) ? "request timed out"
          : /browser.*unavailable|unsupported.*browser/i.test(message) ? "this login requires an unavailable browser capability"
          : /ECONNREFUSED|ENOTFOUND|fetch failed|connection/i.test(message) ? "endpoint connection failed"
          : "native sign-in did not complete";
        failure = `Authentication failed: ${reason}; retry sign-in`;
      }
    } finally {
      if (ctx.pendingLogin === pending) {
        ctx.pendingLogin = undefined;
        ctx.send({ event: "authentication", challenge: null, ...(failure ? { error: failure } : {}) });
        ctx.send({ event: "changed" });
      }
    }
  });
  return true;
}

export async function dispatch(op: string, args: Record<string, unknown>, ctx: ManagementContext): Promise<unknown> {
  if (op === "fetchProviderModels") {
    if (typeof args.provider !== "string" || !args.provider) throw Error("UNAVAILABLE: Invalid model discovery provider");
    const settings = await ctx.settings();
    const registry = await registryFor(ctx, settings);
    const available = new Set(registry.getAvailable("all").map(model => model.provider));
    if (!providerSummary(args.provider, registry, resolver.disabledProviderIds(settings), available).canFetchModels) {
      throw Error("UNAVAILABLE: Online model discovery is unavailable for this provider or authentication state");
    }
    // Unlike a catalogue reload, online forces this provider's native HTTP/model
    // manager fetch and preserves unrelated runtime discovery registrations.
    await registry.refreshDiscoverableProviders([args.provider], "online");
    const state = registry.getProviderDiscoveryState(args.provider);
    if (!state || state.error || ["unavailable", "unauthenticated", "idle"].includes(state.status)) {
      const status = state?.status ?? "unavailable";
      // Response bodies and resolved headers are provider-controlled secrets.
      const native = state?.error ?? "";
      const http = native.match(/(?:HTTP|status(?: code)?)\s*[:=]?\s*([1-5]\d\d)\b/i)?.[1];
      const detail = http ? `; HTTP ${http}` : /timed?\s*out|timeout/i.test(native) ? "; request timed out" : /ECONNREFUSED|ENOTFOUND|fetch failed|connection/i.test(native) ? "; endpoint connection failed" : "";
      throw new ManagementDiagnostic(`UNAVAILABLE: Provider ${args.provider.replace(/[^a-zA-Z0-9_.-]/g, "_")}: model discovery ${status}${detail}`);
    }
    return true;
  }
  if (op === "probeModel") {
    if (typeof args.provider !== "string" || typeof args.modelId !== "string") throw Error("UNAVAILABLE: Invalid model probe target");
    const registry = await getRegistry(ctx);
    const model = registry.find(args.provider, args.modelId);
    if (!model || !registry.hasConfiguredAuth(model)) throw Error("UNAVAILABLE: The model is unavailable for native inference");
    const kind = modelKind(model);
    if (kind !== "chat" && kind !== "tiny") throw Error("UNAVAILABLE: This runner requires modality-specific inputs and cannot run a text latency probe");
    const started = performance.now();
    try {
      const headers = await registry.resolveModelHeaders(model);
      const result = await ai.completeSimple({ ...model, headers }, {
        messages: [{ role: "user", content: "Reply with OK.", timestamp: Date.now() }],
      }, {
        apiKey: registry.resolver(model, ctx.sessionId ?? undefined),
        signal: AbortSignal.timeout(30_000), maxTokens: 16, forceReasoningOff: true,
      });
      if (result.stopReason === "error" || result.stopReason === "aborted") {
        return { latencyMs: Math.round(performance.now() - started), statusCode: 0, message: "Native inference failed" };
      }
      return { latencyMs: Math.round(performance.now() - started), statusCode: 200, message: "Native inference completed" };
    } catch {
      return { latencyMs: Math.round(performance.now() - started), statusCode: 0, message: "Native inference failed" };
    }
  }
  if (op === "login") return login(args.provider, ctx);
  if (op === "answerLogin") {
    const pending = ctx.pendingLogin;
    if (!pending?.answer || typeof args.value !== "string") throw Error("UNAVAILABLE: No authentication prompt is awaiting an answer");
    const answer = pending.answer;
    publish(ctx, pending, { prompt: null, secret: false });
    answer(args.value);
    return true;
  }
  if (op === "cancelLogin") { await cancelLogin(ctx); return true; }
  if (op === "previewRole") return previewRole(args, ctx);
  if (op === "previewPreset") {
    const settings = await ctx.settings();
    const registry = await registryFor(ctx, settings);
    const preset = args.preset;
    if (!preset || typeof preset !== "object" || !("modelRoles" in preset)) throw Error("UNAVAILABLE: Invalid native preset");
    const bindings = preset.modelRoles;
    if (!bindings || typeof bindings !== "object" || Array.isArray(bindings) || Object.values(bindings).some(value => typeof value !== "string")) throw Error("UNAVAILABLE: Invalid native preset roles");
    const overlay = settings.overlay({ modelRoles: bindings });
    return resolveRole(registry, overlay, "default");
  }
  if (op === "logout" || op === "pinAccount") {
    if (typeof args.provider !== "string") throw Error("UNAVAILABLE: Invalid authentication provider");
    const registry = await getRegistry(ctx);
    const provider = getOAuthCredentialProvider(args.provider);
    if (op === "logout") {
      if (ctx.pendingLogin && getOAuthCredentialProvider(ctx.pendingLogin.provider) === provider) await cancelLogin(ctx);
      if (args.accountId == null) await registry.authStorage.credentials.remove(provider);
      else if (!await registry.authStorage.credentials.removeById(provider, accountId(args.accountId))) {
        throw Error("UNAVAILABLE: The stored account is no longer available");
      }
      await registry.refreshProvider(provider, "online");
      return true;
    }
    if (!ctx.sessionId || typeof ctx.pinAccount !== "function") throw Error("UNAVAILABLE: Account pinning requires a live agent session");
    if (args.accountId != null) {
      const id = accountId(args.accountId);
      if (!registry.authStorage.credentials.list(provider).some((row: NativeAccount) => row.id === id && row.credential.type === "oauth")) {
        throw Error("UNAVAILABLE: Only an available stored OAuth account can be pinned");
      }
    }
    // Host forwards to that session's actual AuthStorage; never create a bridge-local affinity.
    await ctx.pinAccount(provider, typeof args.accountId === "string" ? args.accountId : null);
    return true;
  }
  throw Error("UNAVAILABLE: Unsupported native authentication or model operation");
}
