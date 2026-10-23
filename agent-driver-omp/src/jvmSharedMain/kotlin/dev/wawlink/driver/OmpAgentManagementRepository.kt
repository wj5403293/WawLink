package dev.wawlink.driver

import dev.wawlink.protocol.*
import dev.wawlink.runtime.ContainerRuntime
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** A container-owned native management process. No host environment or credentials are imported. */
internal class OmpAgentManagementRepository(
    private val runtime: ContainerRuntime,
    private val context: AgentConfigurationContext,
    private val directory: () -> File,
    private val resolveGuest: (String, Boolean) -> File,
    parentScope: CoroutineScope,
    private val changed: suspend (ConfigurationScope, Set<String>) -> Unit,
    private val pin: suspend (String, String?) -> Unit,
    private val pins: suspend () -> Map<String, String>,
    private val applyDefault: suspend (ManagedRole) -> Unit,
) : AgentManagementRepository, AutoCloseable {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job + Dispatchers.IO)
    private val mutable = MutableStateFlow(AgentManagementState(context))
    override val state: StateFlow<AgentManagementState> = mutable.asStateFlow()
    private val lifecycle = Mutex()
    private val operations = Mutex()
    private val output = Mutex()
    private val sequence = AtomicLong()
    private data class PendingRequest(val child: Process, val result: CompletableDeferred<JsonElement>, val models: MutableList<ManagedModel>?)
    private val pending = ConcurrentHashMap<String, PendingRequest>()
    @Volatile private var process: Process? = null
    @Volatile private var closed = false
    private val versions = mutableMapOf<String, String>()

    init { scope.launch { reload() } }

    private suspend fun start() = lifecycle.withLock {
        check(!closed) { "Agent management is closed" }
        if (process?.isAlive == true) return@withLock
        val root = directory()
        val destination = File(root, "wawlink/management")
        require(destination.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) { "Management bridge path escapes container" }
        for (name in listOf("bootstrap.mjs", "main.mjs", "agents.ts", "auth-models.ts", "diagnostics.mjs")) {
            val source = requireNotNull(javaClass.getResourceAsStream("/omp-management/$name")) { "Missing bundled native management bridge" }.bufferedReader().use { it.readText() }
            val file = File(destination, name)
            if (!file.exists() || file.readText() != source) repoAtomicWrite(file, source)
        }
        val child = runtime.openProcess(context.containerId, listOf("/usr/local/bin/bun", "/root/.omp/agent/wawlink/management/bootstrap.mjs"), context.projectPath ?: "/", mapOf("PI_NO_PTY" to "1", "PI_NO_TITLE" to "1"))
        process = child
        scope.launch {
            // Never retain native stderr: provider libraries may print credential-bearing errors.
            try { child.errorStream.use { stream -> val bytes = ByteArray(4096); while (stream.read(bytes) >= 0) { } } } catch (_: java.io.IOException) { }
        }
        scope.launch {
            var failure: Exception? = null
            try {
                readManagementFrames(child.inputStream) { frame ->
                    when (frame.text("event")) {
                        "authentication" -> mutable.update { it.copy(error = frame.optional("error"), authentication = (frame["challenge"] as? JsonObject)?.let { value -> AuthenticationChallenge(value.text("provider"), value.optional("url"), value.optional("instructions"), value.optional("prompt"), value["secret"]?.jsonPrimitive?.booleanOrNull == true) }) }
                        "changed" -> scope.launch { reload() }
                        "models_page" -> {
                            val response = pending[frame.text("id")]
                            if (response?.child === child && response.models != null) {
                                val page = frame["models"] as? JsonArray ?: throw IOException("原生模型目录响应无效")
                                for (value in page) response.models.add(decodeModel(value as? JsonObject ?: throw IOException("原生模型目录响应无效")))
                            }
                        }
                        "host" -> scope.launch {
                            try { handleHost(frame, child) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) {
                                if (process === child && !closed) mutable.update { it.copy(loading = false, modelsLoading = false, error = managementFailure(failure)) }
                                stop(child)
                            }
                        }
                        else -> {
                            val id = frame.text("id")
                            val response = pending[id]
                            if (response?.child === child && pending.remove(id, response)) {
                                if (frame["error"] != null) response.result.completeExceptionally(IllegalStateException(frame.text("error")))
                                else response.result.complete(frame["result"] ?: JsonNull)
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = error
            } finally {
                val reason = failure ?: IOException("原生配置进程已退出，请重新加载")
                pending.entries.forEach { (id, request) ->
                    if (request.child === child && pending.remove(id, request)) request.result.completeExceptionally(reason)
                }
                if (process === child) {
                    process = null
                    if (!closed) mutable.update { it.copy(loading = false, modelsLoading = false, error = managementFailure(reason)) }
                }
                stop(child)
            }
        }
        try {
            requestStarted("init", mapOf("workspace" to context.projectPath, "sessionId" to context.sessionId))
        } catch (failure: Exception) {
            if (process === child) process = null
            stop(child)
            throw failure
        }
    }

    private suspend fun requestStarted(op: String, args: Map<String, Any?> = emptyMap(), models: MutableList<ManagedModel>? = null): JsonElement {
        val child = checkNotNull(process) { "Native management process is unavailable" }
        val id = sequence.incrementAndGet().toString()
        val result = CompletableDeferred<JsonElement>()
        pending[id] = PendingRequest(child, result, models)
        try {
            send(child, buildJsonObject { put("id", id); put("op", op); put("args", args.element()) })
            return withTimeout(if (op == "generateAgent") 300_000 else 180_000) { result.await() }
        } catch (failure: CancellationException) {
            // Killing the owned bridge also cancels outstanding model/OAuth/network operations.
            stop(child)
            throw failure
        } finally { pending.remove(id) }
    }
    private suspend fun request(op: String, args: Map<String, Any?> = emptyMap(), models: MutableList<ManagedModel>? = null): JsonElement = withContext(Dispatchers.IO) { start(); requestStarted(op, args, models) }
    private suspend fun send(child: Process, frame: JsonObject) = output.withLock {
        withContext(Dispatchers.IO) { child.outputStream.write((frame.toString() + "\n").toByteArray()); child.outputStream.flush() }
    }

    private suspend fun handleHost(frame: JsonObject, child: Process) {
        val args = frame["args"]?.jsonObject ?: JsonObject(emptyMap())
        var result: Any? = null
        var failureMessage: String? = null
        try {
            when (frame.text("op")) {
                "pinAccount" -> pin(args.text("provider"), args.optional("accountId"))
                "accountPins" -> result = pins()
                "editSettings" -> editSettings(ConfigurationScope.valueOf(args.text("scope")), args["changes"].configMap(), checkVersion = true)
                "atomicWrite", "atomicDelete", "patchYaml" -> {
                    val file = writable(args.text("path"))
                    val expected = args.optional("expectedVersion")
                    if (expected != null) require(version(file) == expected) { "Configuration changed on disk; reload before saving" }
                    when (frame.text("op")) {
                        "atomicWrite" -> repoAtomicWrite(file, args.text("content"))
                        "atomicDelete" -> check(!file.exists() || file.delete()) { "Cannot delete definition" }
                        else -> {
                            val body = args.optional("body")
                            val current = if (file.exists()) file.readText() else ""
                            val yamlText = if (body != null && current.removePrefix("\uFEFF").startsWith("---")) current.lineSequence().drop(1).takeWhile { it.trim() != "---" }.joinToString("\n") else if (body != null) "" else current
                            val yaml = safeYaml(yamlText)
                            args["changes"].configMap().forEach { (key, value) -> yaml.set(if (body != null) listOf(key) else key.split('.'), value) }
                            repoAtomicWrite(file, if (body == null) yaml.render() else "---\n${yaml.render()}---\n$body")
                        }
                    }
                }
                else -> error("Unsupported host management operation")
            }
        } catch (failure: Exception) {
            failureMessage = when (failure.message) {
                "Configuration changed on disk; reload before saving" -> "CONFLICT: Configuration changed on disk; reload before saving"
                "Invalid configuration path", "Definition is outside the selected configuration scope", "Configuration symlink escapes scope" -> "UNAVAILABLE: Configuration path is outside the selected writable scope"
                "Cannot delete definition" -> "UNAVAILABLE: Cannot delete the selected configuration definition"
                else -> "UNAVAILABLE: Cannot persist configuration or access the selected session capability"
            }
        }
        send(child, buildJsonObject { put("id", frame.text("id")); put("hostResponse", true); if (failureMessage != null) put("error", failureMessage) else put("result", result.element()) })
    }

    private fun writable(guest: String): File {
        require(guest.startsWith('/') && guest.split('/').none { it == ".." }) { "Invalid configuration path" }
        val global = "/root/.omp/agent/"
        val project = context.projectPath?.trimEnd('/')?.plus("/")
        require(guest.startsWith(global) || (project != null && guest.startsWith(project))) { "Definition is outside the selected configuration scope" }
        val file = resolveGuest(guest, true)
        val root = if (guest.startsWith(global)) directory() else resolveGuest(requireNotNull(context.projectPath), true)
        require(file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) { "Configuration symlink escapes scope" }
        return file
    }
    private fun config(scope: ConfigurationScope): File = if (scope == ConfigurationScope.GLOBAL) {
        File(directory(), "config.yml").let { yml -> writable("/root/.omp/agent/" + if (yml.exists() || !File(directory(), "config.yaml").exists()) "config.yml" else "config.yaml") }
    } else writable(requireNotNull(context.projectPath) { "No project selected" }.trimEnd('/') + "/.omp/config.yml")
    private fun version(file: File): String {
        if (!file.exists()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private suspend fun editSettings(scope: ConfigurationScope, changes: Map<String, Any?>, checkVersion: Boolean) {
        val file = config(scope)
        if (checkVersion) versions[file.path]?.let { require(version(file) == it) { "Configuration changed on disk; reload before saving" } }
        val yaml = safeYaml(if (file.exists()) file.readText() else "")
        changes.forEach { (key, value) -> require(key.split('.').none { it in setOf("__proto__", "prototype", "constructor") }); yaml.set(key.split('.'), value) }
        yaml.write(file)
        versions[file.path] = version(file)
        changed(scope, changes.keys)
    }

    override suspend fun reload(online: Boolean) = withContext(Dispatchers.IO) {
        operations.withLock { ompConfigMutex.withLock {
            try { reloadLocked(online) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(loading = false, modelsLoading = false, error = managementFailure(failure)) } }
        } }
    }
    private suspend fun prepareNativeConfiguration(file: File, id: String) {
        if (!file.exists()) return
        val content = file.readText()
        val guest = if (id == "project") context.projectPath?.trimEnd('/') + "/.omp/config.yml" else "/root/.omp/agent/${file.name}"
        val normalized = OmpYaml.parse(content, guest).render()
        if (normalized == content) return
        // Legacy pretty-flow is legal to SnakeYAML but not to Bun. Validate the
        // complete normalized candidate before migration so a rejected document
        // remains byte-for-byte intact and can still be repaired in the editor.
        request("validateDocument", mapOf("id" to id, "path" to guest, "content" to normalized))
        repoAtomicWrite(file, normalized)
    }
    private suspend fun reloadLocked(online: Boolean = false) {
        mutable.update { it.copy(modelsLoading = true, error = null, modelsError = null) }
        try {
            val before = ConfigurationScope.entries.filter { it == ConfigurationScope.GLOBAL || context.projectPath != null }.associate {
                val file = config(it)
                prepareNativeConfiguration(file, if (it == ConfigurationScope.GLOBAL) "global" else "project")
                file.path to version(file)
            }
            val value = request("snapshot").jsonObject
            require(before.all { (path, hash) -> version(File(path)) == hash }) { "Configuration changed while loading; reload before saving" }
            versions.putAll(before)
            mutable.update { decode(value, it).copy(loading = false, modelsLoading = true) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutable.update { it.copy(loading = false, modelsLoading = false, error = managementFailure(failure)) }
            throw failure
        }
        try {
            mutable.value.files.firstOrNull { it.id == "models" }?.let { prepareNativeConfiguration(writable(it.path), "models") }
            val models = ArrayList<ManagedModel>(mutable.value.models.size)
            val value = request("modelsSnapshot", mapOf("online" to online), models).jsonObject
            require(value["modelsCount"]?.jsonPrimitive?.intOrNull == models.size) { "原生模型目录传输不完整，请重新加载" }
            val roles = value.list("roles").map(::decodeRole)
            val accounts = value.list("accounts").map { ManagedAccount(it.text("provider"), it.text("id"), it.text("label"), it.text("type"), it.text("status"), it.optional("quota"), it["pinned"]?.jsonPrimitive?.booleanOrNull == true) }
            val authProviders = value.list("authProviders").map { ManagedAuthProvider(it.text("id"), it.text("name"), it["oauth"]?.jsonPrimitive?.booleanOrNull == true) }
            val providers = value.list("providers").map { ManagedProvider(it.text("id"), it.text("name", it.text("id")), it["builtin"]?.jsonPrimitive?.booleanOrNull == true, it["enabled"]?.jsonPrimitive?.booleanOrNull == true, it["oauth"]?.jsonPrimitive?.booleanOrNull == true, it["canFetchModels"]?.jsonPrimitive?.booleanOrNull == true, it.optional("baseUrl"), it.optional("api")) }
            mutable.update { it.copy(models = models, roles = roles, accounts = accounts, authProviders = authProviders, providers = providers, modelsLoading = false, modelsError = null) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutable.update { it.copy(modelsLoading = false, modelsError = managementFailure(failure)) }
        }
    }
    override suspend fun updateSettings(scope: ConfigurationScope, changes: Map<String, Any?>) = withContext(Dispatchers.IO) {
        operations.withLock { ompConfigMutex.withLock {
            request("updateSettings", mapOf("scope" to scope.name, "changes" to changes)); reloadLocked()
            if (changes.keys.any { it == "modelRoles" || it == "modelRoles.default" || it == "defaultThinkingLevel" }) applyResolvedDefault()
        } }
    }
    override suspend fun resetRuntimeSettings(scope: ConfigurationScope) {
        val keys = state.value.categories.flatMap { it.groups }.flatMap { it.items }.map { it.id }.filterNot { it in setOf("modelRoles", "modelTags", "modelPresets", "modelRoleStorage", "enabledModels", "enabledProviders", "disabledProviders", "modelProviderOrder", "cycleOrder", "retry.fallbackChains") }
        updateSettings(scope, keys.associateWith { null })
    }
    private suspend fun mutation(op: String, args: Map<String, Any?>) = withContext(Dispatchers.IO) {
        operations.withLock { ompConfigMutex.withLock {
            request(op, args)
            if (op !in setOf("applyPreset", "savePreset", "deletePreset")) changed(if (args["scope"] == "PROJECT") ConfigurationScope.PROJECT else ConfigurationScope.GLOBAL, emptySet())
            reloadLocked()
            if (op == "applyPreset") applyResolvedDefault()
        } }
    }
    private suspend fun applyResolvedDefault() {
        if (context.sessionId == null || state.value.modelsError != null) return
        state.value.roles.firstOrNull { it.id == "default" }?.let { role ->
            applyDefault(role.copy(thinking = role.thinking ?: state.value.effectiveValues["defaultThinkingLevel"] as? String))
        }
    }
    private fun safeYaml(content: String): OmpYaml = try { OmpYaml.parse(content) } catch (_: Exception) { throw IllegalArgumentException("Invalid YAML configuration; content is not included for credential safety") }
    override suspend fun saveAgent(scope: ConfigurationScope, name: String, fields: Map<String, Any?>, systemPrompt: String, original: ManagedAgent?) = mutation("saveAgent", mapOf("scope" to scope.name, "name" to name, "fields" to fields, "systemPrompt" to systemPrompt, "original" to original?.values()))
    override suspend fun deleteAgent(agent: ManagedAgent) = mutation("deleteAgent", mapOf("agent" to agent.values(), "scope" to if (context.projectPath?.let { agent.path.startsWith(it.trimEnd('/') + "/") } == true) "PROJECT" else "GLOBAL"))
    override suspend fun generateAgent(description: String): String = request("generateAgent", mapOf("description" to description)).jsonPrimitive.content
    override suspend fun parseAgent(content: String): ManagedAgent = decodeAgent(request("parseAgent", mapOf("content" to content)).jsonObject)
    override suspend fun renderAgent(name: String, fields: Map<String, Any?>, systemPrompt: String): String = request("renderAgent", mapOf("name" to name, "fields" to fields, "systemPrompt" to systemPrompt)).jsonPrimitive.content
    override suspend fun previewRole(role: String, scope: ConfigurationScope, selector: String?): ManagedRole = decodeRole(request("previewRole", mapOf("role" to role, "scope" to scope.name, "selector" to selector)).jsonObject)
    override suspend fun modelDetails(provider: String, modelId: String): ManagedModel = withContext(Dispatchers.IO) {
        operations.withLock { decodeModel(request("modelDetails", mapOf("provider" to provider, "modelId" to modelId)).jsonObject) }
    }
    override suspend fun fetchProviderModels(provider: String) = withContext(Dispatchers.IO) {
        operations.withLock { ompConfigMutex.withLock {
            request("fetchProviderModels", mapOf("provider" to provider))
            reloadLocked()
        } }
    }
    override suspend fun saveAdvisor(scope: ConfigurationScope, configuration: Map<String, Any?>, original: ManagedAdvisor?) = mutation("saveAdvisor", mapOf("scope" to scope.name, "configuration" to configuration, "original" to original?.values()))
    override suspend fun deleteAdvisor(advisor: ManagedAdvisor) = mutation("deleteAdvisor", mapOf("advisor" to advisor.values(), "scope" to advisor.scope.name))
    override suspend fun updateAdvisorShared(scope: ConfigurationScope, instructions: String, maxNotes: Int?) = mutation("updateAdvisorShared", mapOf("scope" to scope.name, "instructions" to instructions, "maxNotes" to maxNotes))

    override suspend fun readDocument(id: String): ConfigurationDocument = withContext(Dispatchers.IO) { ompConfigMutex.withLock {
        val definition = state.value.files.firstOrNull { it.id == id } ?: error("Unknown configuration document")
        val file = writable(definition.path)
        val content = if (file.exists()) file.readText() else ""
        ConfigurationDocument(id, definition.path, content, version(file), definition.writable, if (content.isBlank()) emptyMap() else runCatching { safeYaml(content).values() }.getOrDefault(emptyMap()))
    } }
    override suspend fun saveDocument(document: ConfigurationDocument, content: String, overwrite: Boolean) = withContext(Dispatchers.IO) {
        operations.withLock { ompConfigMutex.withLock {
            require(document.writable)
            val file = writable(document.path)
            require(state.value.files.any { it.id == document.id && it.path == document.path && it.writable }) { "Unknown configuration document" }
            require(overwrite || version(file) == document.version) { "Configuration changed on disk; reload before saving" }
            request("validateDocument", mapOf("id" to document.id, "path" to document.path, "content" to content))
            request("writeDocument", mapOf("path" to document.path, "content" to content, "expectedVersion" to if (overwrite) null else document.version))
            changed(if (document.id == "project") ConfigurationScope.PROJECT else ConfigurationScope.GLOBAL, emptySet()); reloadLocked()
        } }
    }
    override suspend fun saveDocumentValues(document: ConfigurationDocument, values: Map<String, Any?>) {
        val yaml = safeYaml(document.content)
        yaml.replace(values)
        saveDocument(document, yaml.render())
    }
    override suspend fun exportRedacted(): String = withContext(Dispatchers.IO) {
        operations.withLock {
            val configuration = request("snapshot").jsonObject
            val models = ArrayList<ManagedModel>(mutable.value.models.size)
            val catalogue = request("modelsSnapshot", models = models).jsonObject
            require(catalogue["modelsCount"]?.jsonPrimitive?.intOrNull == models.size) { "原生模型目录传输不完整，请重新加载" }
            val entries = JsonArray(models.map { model -> buildJsonObject {
                put("provider", model.provider); put("id", model.id); put("name", model.name)
                put("kind", model.kind); put("api", model.api); put("input", model.input.element())
                put("available", model.available); put("unavailableReason", model.unavailableReason?.let(::JsonPrimitive) ?: JsonNull)
                put("metadata", model.metadata.element())
            } })
            JsonObject(configuration + catalogue + ("models" to entries)).toString()
        }
    }
    override suspend fun applyPreset(name: String) = mutation("applyPreset", mapOf("name" to name))
    override suspend fun savePreset(name: String) = mutation("savePreset", mapOf("name" to name))
    override suspend fun deletePreset(name: String) = mutation("deletePreset", mapOf("name" to name))
    override suspend fun probeModel(provider: String, modelId: String): LatencyInfo {
        val value = request("probeModel", mapOf("provider" to provider, "modelId" to modelId)).jsonObject
        return LatencyInfo(value["latencyMs"]?.jsonPrimitive?.longOrNull ?: 0, value["statusCode"]?.jsonPrimitive?.intOrNull ?: 0, value.text("message"))
    }
    override suspend fun login(provider: String) { request("login", mapOf("provider" to provider)) }
    override suspend fun answerLogin(value: String) { request("answerLogin", mapOf("value" to value)) }
    override fun cancelLogin() { if (!closed && process?.isAlive == true) scope.launch { runCatching { requestStarted("cancelLogin") } } }
    override suspend fun logout(provider: String, accountId: String?) { mutation("logout", mapOf("provider" to provider, "accountId" to accountId)) }
    override suspend fun pinAccount(provider: String, accountId: String?) { require(context.sessionId != null) { "Runtime account pinning requires an active session" }; request("pinAccount", mapOf("provider" to provider, "accountId" to accountId)); reload() }

    private fun decode(v: JsonObject, previous: AgentManagementState): AgentManagementState = AgentManagementState(
        context = context, globalValues = v["globalValues"].configMap(), projectValues = v["projectValues"].configMap(), effectiveValues = v["effectiveValues"].configMap(), defaultValues = v["defaultValues"].configMap(), provenance = v["provenance"].configMap().mapValues { it.value.toString() },
        categories = localizedCategories(v.list("categories").map { c ->
            SettingCategory(c.text("id"), c.text("name"), c.text("icon"), c.list("groups").map { g ->
                SettingGroup(g.text("id"), g.text("name"), g.text("icon"), g.list("items").map { i ->
                    SettingItem(id = i.text("id"), title = i.text("id"), icon = i.text("icon"), type = SettingType.valueOf(i.text("type")),
                        value = i["value"]?.toConfigValue(), defaultValue = i["defaultValue"]?.toConfigValue(),
                        options = i.list("options").map { SettingOption(it.text("value"), it.text("label")) },
                        min = i["min"]?.jsonPrimitive?.doubleOrNull, max = i["max"]?.jsonPrimitive?.doubleOrNull,
                        step = i["step"]?.jsonPrimitive?.doubleOrNull ?: 1.0)
                })
            })
        }),
        agents = v.list("agents").map(::decodeAgent), tools = v.list("tools").map { ConfigurationCapability(it.text("name"), it.text("description"), it.text("source", "builtin")) }, skills = v.list("skills").map { ConfigurationCapability(it.text("name"), it.text("description"), it.text("source", "builtin")) },
        models = previous.models, roles = previous.roles,
        advisors = v.list("advisors").map { ManagedAdvisor(it.text("id"), it.text("name"), ConfigurationScope.valueOf(it.text("scope")), it.text("path"), it["configuration"].configMap(), it.optional("runtimeStatus"), it.text("version")) }, advisorInstructions = v.text("advisorInstructions"), advisorMaxNotes = v["advisorMaxNotes"]?.jsonPrimitive?.intOrNull,
        advisorSharedByScope = v["advisorSharedByScope"].configMap().mapNotNull { (key, value) -> ConfigurationScope.entries.firstOrNull { it.name == key }?.let { nativeScope -> nativeScope to (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty() } }.toMap(),
        files = v.list("files").map { ConfigurationFile(it.text("id"), when(it.text("id")) { "global" -> "全局配置"; "project" -> "项目配置"; "models" -> "模型与服务配置"; else -> it.text("id") }, it.text("path"), it["writable"]?.jsonPrimitive?.booleanOrNull != false) },
        accounts = previous.accounts, authProviders = previous.authProviders, providers = previous.providers, authentication = previous.authentication,
    )
    private fun localizedCategories(live: List<SettingCategory>): List<SettingCategory> {
        val byId = live.flatMap { it.groups }.flatMap { it.items }.associateBy { it.id }
        val known = OmpSettingsSchema.items
        val translated = OmpSettingsSchema.categories.mapNotNull { category ->
            val groups = category.groups.mapNotNull { group ->
                val items = group.items.mapNotNull { label -> byId[label.id]?.let { item ->
                    item.copy(title = label.title, icon = label.icon, options = item.options.map { option -> option.copy(label = label.options.firstOrNull { it.value == option.value }?.label ?: option.value) })
                } }
                group.copy(items = items).takeIf { items.isNotEmpty() }
            }
            category.copy(groups = groups).takeIf { groups.isNotEmpty() }
        }
        val additional = live.mapNotNull { category ->
            val groups = category.groups.mapNotNull { group -> group.copy(title = group.id, items = group.items.filterNot { it.id in known }).takeIf { it.items.isNotEmpty() } }
            category.copy(title = when(category.id) { "advanced" -> "高级设置"; else -> OmpSettingsSchema.categories.firstOrNull { it.id == category.id }?.title ?: category.id }, groups = groups).takeIf { groups.isNotEmpty() }
        }
        return translated + additional
    }
    private fun decodeModel(v: JsonObject) = ManagedModel(v.text("provider"), v.text("id"), v.text("name", v.text("id")), v.text("kind", "chat"), v.text("api"), v["input"].strings(), v["available"]?.jsonPrimitive?.booleanOrNull == true,
        when (val reason = v.optional("unavailableReason")) { "Provider disabled by native settings" -> "服务已停用"; "No native authentication source is configured" -> "尚未配置此服务的认证"; else -> reason }, v["metadata"].configMap())
    private fun decodeAgent(v: JsonObject) = ManagedAgent(v.text("name"), v.text("description"), v.text("source"), v.text("path"), v["readOnly"]?.jsonPrimitive?.booleanOrNull != false, v["fields"].configMap(), v.text("systemPrompt"), v.text("content"), v.text("version"), v.list("shadows").map { AgentDefinitionSource(it.text("source"), it.text("path"), it.text("description")) })
    private fun decodeRole(v: JsonObject) = ManagedRole(v.text("id"), nativeRoleLabel(v.text("id"), v.text("name")), v.text("kind", "chat"), v.optional("model"), v.optional("thinking"),
        v.optional("error").let { if (it == "No available model matches this role's native candidate chain") "没有可用模型匹配此角色的候选链" else it }, v.optional("source"), v["automatic"]?.jsonPrimitive?.booleanOrNull == true, v["diagnostics"].strings())
    private fun ManagedAgent.values() = mapOf("name" to name,"description" to description,"source" to source,"path" to path,"readOnly" to readOnly,"fields" to fields,"systemPrompt" to systemPrompt,"content" to content,"version" to version)
    private fun ManagedAdvisor.values() = mapOf("id" to id,"name" to name,"scope" to this.scope.name,"path" to path,"configuration" to configuration,"version" to version)
    override fun close() { closed = true; job.cancel(); process?.let(::stop); process = null; pending.values.forEach { it.result.cancel() }; pending.clear() }
    private fun stop(child: Process) { runCatching { child.outputStream.close() }; child.destroy(); if (!child.waitFor(2, TimeUnit.SECONDS)) { child.destroyForcibly(); child.waitFor(2, TimeUnit.SECONDS) }; runCatching { child.inputStream.close() }; runCatching { child.errorStream.close() } }
}

private fun JsonObject.text(key: String, default: String = "") = this[key]?.jsonPrimitive?.contentOrNull ?: default
private fun JsonObject.optional(key: String) = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.list(key: String) = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
private fun JsonElement?.strings() = (this as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
private fun JsonElement?.configMap(): Map<String, Any?> = (this as? JsonObject)?.mapValues { it.value.toConfigValue() }.orEmpty()
private fun Any?.element(): JsonElement = when (this) { null -> JsonNull; is JsonElement -> this; is Map<*, *> -> JsonObject(entries.associate { it.key.toString() to it.value.element() }); is List<*> -> JsonArray(map { it.element() }); is Boolean -> JsonPrimitive(this); is Number -> JsonPrimitive(this); else -> JsonPrimitive(toString()) }

private fun managementFailure(failure: Throwable): String = when (failure.message) {
    "UNAVAILABLE: Invalid native model provider configuration" -> "模型服务配置无效，请在模型服务或原始配置中修复"
    else -> failure.message ?: "无法读取原生配置，请重新加载"
}

/** JSONL frames are bounded before allocating the full string or JSON tree. */
internal fun readManagementFrames(input: InputStream, maxCharacters: Int = 4 * 1024 * 1024, consume: (JsonObject) -> Unit) {
    require(maxCharacters > 0)
    input.bufferedReader().use { reader ->
        val buffer = CharArray(8192)
        val frame = StringBuilder(minOf(8192, maxCharacters))
        fun append(start: Int, end: Int) {
            if (end - start > maxCharacters - frame.length) throw IOException("原生配置响应过大，请缩小配置后重新加载")
            frame.append(buffer, start, end - start)
        }
        fun deliver() {
            if (frame.isNotEmpty()) {
                val value = try { Json.parseToJsonElement(frame.toString()) as? JsonObject }
                catch (_: Exception) { null }
                if (value == null) throw IOException("原生配置响应无效，请重新加载")
                frame.setLength(0)
                consume(value)
            }
        }
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            var start = 0
            for (index in 0 until count) {
                if (buffer[index] == '\n') {
                    append(start, index)
                    deliver()
                    start = index + 1
                }
            }
            append(start, count)
        }
        deliver()
    }
}

private fun nativeRoleLabel(id: String, name: String): String = when (id) {
    "default" -> if (name == "Default") "默认" else name
    "smol" -> if (name == "Fast") "快速" else name
    "slow" -> if (name == "Thinking") "深度思考" else name
    "vision" -> if (name == "Vision") "视觉" else name
    "plan" -> if (name == "Architect") "规划" else name
    "commit" -> if (name == "Commit") "提交" else name
    "tiny" -> if (name == "Tiny") "轻量" else name
    "memory" -> if (name == "Memory") "记忆" else name
    "task" -> if (name == "Subtask") "子任务" else name
    "advisor" -> if (name == "Advisor") "顾问" else name
    "widget" -> if (name == "Job widgets") "任务组件" else name
    "image" -> if (name == "Image generation") "图像生成" else name
    "web" -> if (name == "Web search") "网页搜索" else name
    "speech" -> if (name == "Speech") "语音合成" else name
    "dictation" -> if (name == "Dictation") "语音转写" else name
    "judge" -> if (name == "Judge") "评判" else name
    else -> name
}
