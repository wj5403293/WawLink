package dev.wawlink.driver

import dev.wawlink.protocol.AgentSettingsRepository
import dev.wawlink.protocol.LatencyInfo
import dev.wawlink.protocol.ModelConfig
import dev.wawlink.protocol.ModelCost
import dev.wawlink.protocol.ProviderApiType
import dev.wawlink.protocol.ProviderConfig
import dev.wawlink.protocol.ProviderConfigRepository
import dev.wawlink.protocol.SettingCategory
import dev.wawlink.protocol.ThinkingLevel
import java.io.File
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.NodeTuple
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Representer

// Provider enable switches and ordinary settings share config.yml; serialize their reload/edit/write cycles.
private val ompConfigMutex = Mutex()
private val providerApis = mapOf(
    ProviderApiType.CHAT to "openai-completions",
    ProviderApiType.RESPONSE to "openai-responses",
    ProviderApiType.MESSAGE to "anthropic-messages",
)
private val thinkingEfforts = listOf("minimal", "low", "medium", "high", "xhigh", "max")

/** Only container-rootfs configuration is accepted; never resolve the host's ~/.omp. */
private fun agentDirectory(directory: () -> File): File {
    val dir = directory().absoluteFile.normalize()
    require(dir.name == "agent" && dir.parentFile?.name == ".omp" && dir.parentFile?.parentFile?.name == "root") {
        "OMP configuration directory must be container rootfs/root/.omp/agent"
    }
    val rootfs = requireNotNull(dir.parentFile.parentFile.parentFile)
    require(rootfs.canonicalFile.parentFile != null && dir.canonicalFile.toPath().startsWith(rootfs.canonicalFile.toPath())) {
        "OMP configuration directory escapes its container rootfs"
    }
    return dir
}

private fun configFile(directory: File, name: String): File = File(directory, name).also {
    require(it.canonicalFile.parentFile == directory.canonicalFile) { "Configuration symlink escapes the container agent directory: $name" }
}

private fun mainConfigFile(directory: File): File {
    val yml = configFile(directory, "config.yml")
    val yaml = configFile(directory, "config.yaml")
    return if (yml.exists() || !yaml.exists()) yml else yaml
}

private fun repoAtomicWrite(file: File, content: String) {
    Files.createDirectories(file.parentFile.toPath())
    val temporary = Files.createTempFile(file.parentFile.toPath(), ".${file.name}.", ".tmp")
    try {
        try {
            Files.setPosixFilePermissions(temporary, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        } catch (_: UnsupportedOperationException) {
            // Android filesystems need not implement POSIX permission attributes.
        }
        java.io.FileOutputStream(temporary.toFile()).use { stream ->
            stream.write(content.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        try {
            Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}

/** Re-emit legacy pretty-flow documents before Bun reads them; credentials and YAML nodes stay intact. */
internal suspend fun prepareOmpConfiguration(directory: () -> File) = ompConfigMutex.withLock {
    val dir = agentDirectory(directory)
    for (file in listOf(configFile(dir, "models.yml"), mainConfigFile(dir))) {
        if (file.exists()) OmpYaml(file).write(file)
    }
}

/** Mutate composed YAML nodes, not a load/dump map: comments, tags, unknown keys and scalar styles survive. */
private class OmpYaml private constructor(private val yaml: Yaml, val root: MappingNode) {
    constructor(file: File) : this(newYaml(), file)
    private constructor(yaml: Yaml, file: File) : this(yaml, if (file.exists()) {
        file.reader(Charsets.UTF_8).use { reader ->
            val node = yaml.compose(reader)
            require(node == null || node is MappingNode) { "${file.name} must contain a YAML mapping" }
            node as? MappingNode ?: mapping()
        }
    } else mapping())

    fun node(vararg path: String): Node? {
        var current: Node = root
        for (key in path) current = (current as? MappingNode)?.entry(key)?.valueNode ?: return null
        return current
    }
    fun value(vararg path: String): Any? = node(*path)?.let(::value)
    fun value(node: Node): Any? {
        // A subnode's inline comment belongs to a mapping value, not a document root.
        val wrapper = mapping().apply { value.add(NodeTuple(scalar("value"), node)) }
        val text = StringWriter().also { yaml.serialize(wrapper, it) }.toString()
        return normalizeConfigValue(yaml.load<Map<String, Any?>>(text)["value"])
    }
    fun set(path: List<String>, value: Any?) {
        var parent = root
        for (key in path.dropLast(1)) {
            val existing = parent.entry(key)
            if (existing == null) {
                val child = mapping()
                parent.value.add(NodeTuple(scalar(key), child))
                parent = child
            } else {
                require(existing.valueNode is MappingNode) { "Cannot edit nested setting through a non-mapping: $key" }
                parent = existing.valueNode as MappingNode
            }
        }
        set(parent, path.last(), value?.let { yaml.represent(it) })
    }
    fun set(parent: MappingNode, key: String, value: Node?) {
        val index = parent.value.indexOfFirst { (it.keyNode as? ScalarNode)?.value == key }
        if (value == null) {
            if (index >= 0) parent.value.removeAt(index)
        } else if (index < 0) parent.value.add(NodeTuple(scalar(key), value)) else {
            val old = parent.value[index]
            if (value === old.valueNode || this.value(value) == this.value(old.valueNode)) return
            if (old.valueNode is MappingNode && value is MappingNode) {
                val original = old.valueNode as MappingNode
                val retained = value.value.map { (it.keyNode as ScalarNode).value }.toSet()
                original.value.removeAll { (it.keyNode as? ScalarNode)?.value !in retained }
                value.value.forEach { replacement -> set(original, (replacement.keyNode as ScalarNode).value, replacement.valueNode) }
                return
            }
            value.blockComments = old.valueNode.blockComments
            value.inLineComments = old.valueNode.inLineComments
            value.endComments = old.valueNode.endComments
            parent.value[index] = NodeTuple(old.keyNode, value)
        }
    }
    fun remove(path: List<String>) {
        val parent = if (path.size == 1) root else node(*path.dropLast(1).toTypedArray()) as? MappingNode ?: return
        set(parent, path.last(), null)
    }
    fun write(file: File) {
        val content = StringWriter().also { yaml.serialize(root, it) }.toString()
        if (!file.exists() || file.readText(Charsets.UTF_8) != content) repoAtomicWrite(file, content)
    }
    fun represent(value: Any): Node = yaml.represent(value)

    companion object {
        fun mapping() = MappingNode(Tag.MAP, mutableListOf<NodeTuple>(), DumperOptions.FlowStyle.BLOCK)
        fun scalar(value: String) = ScalarNode(Tag.STR, value, null, null, DumperOptions.ScalarStyle.PLAIN)
        private fun newYaml(): Yaml {
            val loader = LoaderOptions().apply { isProcessComments = true; isAllowDuplicateKeys = false }
            // Bun rejects pretty-flow output when composed flow containers receive block-style children.
            val dumper = DumperOptions().apply { isProcessComments = true; defaultFlowStyle = DumperOptions.FlowStyle.BLOCK }
            return Yaml(SafeConstructor(loader), Representer(dumper), dumper, loader)
        }
    }
}

private fun MappingNode.entry(key: String): NodeTuple? = value.firstOrNull { (it.keyNode as? ScalarNode)?.value == key }
private fun Any?.asStringMap(): Map<String, Any?> = (this as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty()
private fun Map<String, Any?>.string(key: String): String? = this[key] as? String
private fun Map<String, Any?>.int(key: String, default: Int): Int = (this[key] as? Number)?.toInt() ?: default
private fun Map<String, Any?>.cost(): ModelCost? = this["cost"]?.asStringMap()?.let { value ->
    ModelCost(
        (value["input"] as? Number)?.toDouble() ?: 0.0,
        (value["output"] as? Number)?.toDouble() ?: 0.0,
        (value["cacheRead"] as? Number)?.toDouble() ?: 0.0,
        (value["cacheWrite"] as? Number)?.toDouble() ?: 0.0,
    )
}

/** 18.12.6 stores OAuth in its own SQLite store. We never rewrite auth.json or auth database rows. */
internal class OmpProviderRepository(
    private val directory: () -> File,
    scope: CoroutineScope,
    private val catalog: KnownModelCatalog = KnownModelCatalog.shared,
    private val onChanged: suspend (String?, Any?) -> Unit,
) : ProviderConfigRepository, AutoCloseable {
    private val job = SupervisorJob(scope.coroutineContext[kotlinx.coroutines.Job])
    private val worker = CoroutineScope(scope.coroutineContext + job + Dispatchers.IO)
    private val mutableProviders = MutableStateFlow<List<ProviderConfig>>(emptyList())
    override val providers: StateFlow<List<ProviderConfig>> = mutableProviders.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = mutableError.asStateFlow()
    private var generation: List<String?>? = null

    init {
        worker.launch {
            while (isActive) {
                try {
                    val changed = ompConfigMutex.withLock { refresh() }
                    if (changed) onChanged(null, null)
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    mutableError.value = e.message ?: "无法读取容器提供商配置"
                }
                delay(1_000)
            }
        }
    }

    private fun refresh(): Boolean {
        val dir = agentDirectory(directory)
        val modelsFile = configFile(dir, "models.yml")
        val config = mainConfigFile(dir)
        val namesFile = configFile(dir, "wawlink-provider-ui.json")
        val budgetsFile = configFile(dir, "wawlink-model-budgets.json")
        val next = listOf(dir.path, modelsFile.takeIf { it.exists() }?.readText(), config.takeIf { it.exists() }?.readText(), namesFile.takeIf { it.exists() }?.readText(), budgetsFile.takeIf { it.exists() }?.readText())
        if (generation == next) return false
        val models = OmpYaml(modelsFile).value("providers").asStringMap()
        val settings = OmpYaml(config)
        val disabled = (settings.value("disabledProviders") as? List<*>)?.filterIsInstance<String>().orEmpty()
        val metadata = namesFile.takeIf { it.exists() }?.let { Json.parseToJsonElement(it.readText()).jsonObject }.orEmpty()
        val budgets = ModelPayloadExtension.readBudgets(dir)
        val unsupported = mutableListOf<String>()
        val result = models.mapNotNull { (id, raw) ->
            val provider = raw.asStringMap()
            val modelMaps = (provider["models"] as? List<*>)?.map { it.asStringMap() }.orEmpty()
            val apiName = provider.string("api") ?: modelMaps.mapNotNull { it.string("api") }.distinct().singleOrNull()
            val api = providerApis.entries.firstOrNull { it.value == apiName }?.key
            if (api == null || modelMaps.any { it.string("api")?.let { modelApi -> modelApi != apiName } == true }) {
                unsupported += "$id (${apiName ?: "继承内置协议"})"
                return@mapNotNull null
            }
            ProviderConfig(
                id = id, name = (metadata[id] as? JsonObject)?.get("name")?.jsonPrimitive?.content ?: id,
                apiType = api, baseUrl = provider.string("baseUrl") ?: modelMaps.firstNotNullOfOrNull { it.string("baseUrl") }.orEmpty(),
                apiKey = provider.string("apiKey").orEmpty(), enabled = id !in disabled,
                headers = provider["headers"].asStringMap().mapValues { it.value.toString() },
                models = modelMaps.mapNotNull { model ->
                    val modelId = model.string("id") ?: return@mapNotNull null
                    val thinking = model["thinking"].asStringMap()
                    val effort = thinking.string("defaultLevel")
                    val defaultOverride = ((metadata[id] as? JsonObject)?.get("modelDefaults") as? JsonObject)?.get(modelId)?.jsonPrimitive?.content
                    val level = defaultOverride?.let { value -> ThinkingLevel.entries.firstOrNull { it.name == value } }
                        ?: if (model["reasoning"] == false) ThinkingLevel.NONE else effort.toThinkingLevel()
                    ModelConfig(
                        id = modelId, name = model.string("name") ?: modelId, thinkingLevel = level,
                        reasoningEffort = effort?.let { thinking["effortMap"].asStringMap().string(it) ?: it },
                        thinkingBudget = budgets["$id/$modelId"], contextWindow = model.int("contextWindow", 200_000), maxTokens = model.int("maxTokens", 16_384),
                        reasoning = model["reasoning"] as? Boolean,
                        input = (model["input"] as? List<*>)?.filterIsInstance<String>() ?: listOf("text"),
                        cost = model.cost(),
                        thinkingLevelMap = thinking["effortMap"].asStringMap().mapNotNull { (key, value) ->
                            key.toThinkingLevelOrNull()?.let { it to (value as? String ?: return@mapNotNull null) }
                        }.toMap(),
                    )
                },
            )
        }
        val changed = generation != null
        generation = next
        mutableProviders.value = result
        mutableError.value = unsupported.takeIf { it.isNotEmpty() }?.joinToString(prefix = "当前界面不支持编辑这些上游协议（配置已保留）：")
        return changed
    }

    override suspend fun saveProvider(provider: ProviderConfig) = withContext(Dispatchers.IO) {
        require(provider.id.isNotBlank()) { "提供商 ID 不能为空" }
        require(provider.models.map { it.id }.distinct().size == provider.models.size && provider.models.all { it.id.isNotBlank() }) { "模型 ID 必须非空且唯一" }
        provider.models.forEach { model ->
            require(model.contextWindow > 0 && model.maxTokens > 0) { "模型上下文和输出上限必须为正整数" }
            require(model.input.isNotEmpty() && model.input.all { it == "text" || it == "image" }) { "OMP 输入类型仅支持 text/image" }
            model.cost?.let { cost ->
                require(listOf(cost.input, cost.output, cost.cacheRead, cost.cacheWrite).all { it.isFinite() && it >= 0 }) { "模型价格必须是有限非负数" }
            }
            require(model.thinkingLevelMap.keys.none { it == ThinkingLevel.NONE } && model.thinkingLevelMap.values.all { it.isNotBlank() }) { "思考映射必须使用启用档位和非空接口值" }
            model.thinkingBudget?.let { budget ->
                require(provider.apiType == ProviderApiType.MESSAGE) { "数值思考预算仅适用于 Message 接口" }
                require(budget >= 1024 && budget < model.maxTokens && model.thinkingLevel != ThinkingLevel.NONE && model.reasoning != false) { "思考预算必须至少为 1024、小于模型输出上限，且启用思考" }
            }
        }
        ompConfigMutex.withLock {
            val dir = agentDirectory(directory)
            val file = configFile(dir, "models.yml")
            val yaml = OmpYaml(file) // Reload immediately before editing; never save an old StateFlow snapshot.
            if (yaml.node("providers") == null) yaml.set(listOf("providers"), emptyMap<String, Any>())
            val all = yaml.node("providers") as? MappingNode ?: error("models.yml providers 必须是映射")
            val existing = all.entry(provider.id)?.valueNode
            require(existing == null || existing is MappingNode) { "提供商配置必须是映射" }
            val node = existing as? MappingNode ?: OmpYaml.mapping()
            fun set(key: String, value: Any?) = yaml.set(node, key, value?.let(yaml::represent))
            set("api", providerApis.getValue(provider.apiType))
            set("baseUrl", provider.baseUrl.takeIf { it.isNotBlank() })
            set("apiKey", provider.apiKey.takeIf { it.isNotEmpty() })
            // Do not erase unknown header values when editing a representable provider.
            set("headers", provider.headers.takeIf { it.isNotEmpty() })
            val oldModels = (node.entry("models")?.valueNode as? SequenceNode)?.value.orEmpty()
            val modelNodes = provider.models.map { model ->
                val old = oldModels.firstOrNull { (it as? MappingNode)?.entry("id")?.valueNode.let { id -> (id as? ScalarNode)?.value == model.id } }
                val modelNode = old as? MappingNode ?: OmpYaml.mapping()
                fun setModel(key: String, value: Any?) = yaml.set(modelNode, key, value?.let(yaml::represent))
                setModel("id", model.id); setModel("name", model.name)
                if (modelNode.entry("api") != null) setModel("api", providerApis.getValue(provider.apiType))
                setModel("contextWindow", model.contextWindow); setModel("maxTokens", model.maxTokens)
                setModel("reasoning", model.reasoning)
                setModel("input", model.input)
                val costNode = (modelNode.entry("cost")?.valueNode as? MappingNode) ?: OmpYaml.mapping()
                model.cost?.let { cost ->
                    mapOf("input" to cost.input, "output" to cost.output, "cacheRead" to cost.cacheRead, "cacheWrite" to cost.cacheWrite).forEach { (key, value) ->
                        yaml.set(costNode, key, yaml.represent(value))
                    }
                }
                yaml.set(modelNode, "cost", costNode.takeIf { model.cost != null })
                val unchangedImplicitThinking = modelNode.entry("thinking") == null && model.thinkingLevel == ThinkingLevel.MEDIUM && model.reasoningEffort == null && model.thinkingLevelMap.isEmpty()
                if (!unchangedImplicitThinking && (modelNode.entry("thinking") != null || model.thinkingLevel != ThinkingLevel.NONE || model.thinkingLevelMap.isNotEmpty())) {
                    val effort = if (model.thinkingLevel == ThinkingLevel.NONE) null else if (model.thinkingLevel == ThinkingLevel.LOW && model.reasoningEffort == "minimal") "minimal" else model.thinkingLevel.toEffort()
                    val thinking = (modelNode.entry("thinking")?.valueNode as? MappingNode) ?: OmpYaml.mapping()
                    if (thinking.entry("mode") == null) yaml.set(thinking, "mode", yaml.represent(if (provider.apiType == ProviderApiType.MESSAGE) "budget" else "effort"))
                    val configuredEfforts = (thinking.entry("efforts")?.valueNode as? SequenceNode)?.value?.mapNotNull { (it as? ScalarNode)?.value }
                        ?: (thinking.entry("levels")?.valueNode as? SequenceNode)?.value?.mapNotNull { (it as? ScalarNode)?.value }
                        ?: thinkingEfforts
                    val requiredEfforts = model.thinkingLevelMap.keys.map { it.toEffort() } + listOfNotNull(effort)
                    yaml.set(thinking, "efforts", yaml.represent(thinkingEfforts.filter { it in configuredEfforts || it in requiredEfforts }))
                    yaml.set(thinking, "defaultLevel", effort?.let(yaml::represent))
                    val effortMap = (thinking.entry("effortMap")?.valueNode as? MappingNode) ?: OmpYaml.mapping()
                    // Edit recognized keys in place, retaining future keys and their comments.
                    thinkingEfforts.filter { it != "minimal" }.forEach { key ->
                        val mapped = model.thinkingLevelMap[key.toThinkingLevel()]
                        yaml.set(effortMap, key, mapped?.let(yaml::represent))
                    }
                    model.reasoningEffort?.takeIf { it.isNotBlank() && model.thinkingLevel !in model.thinkingLevelMap }?.let { wire ->
                        if (effort != null) yaml.set(effortMap, effort, wire.takeIf { it != effort }?.let(yaml::represent))
                    }
                    yaml.set(thinking, "effortMap", effortMap.takeIf { it.value.isNotEmpty() })
                    yaml.set(modelNode, "thinking", thinking)
                }
                modelNode as Node
            }
            yaml.set(node, "models", SequenceNode(Tag.SEQ, modelNodes, DumperOptions.FlowStyle.BLOCK))
            yaml.set(all, provider.id, node)
            val settingsFile = mainConfigFile(dir)
            val config = OmpYaml(settingsFile)
            val disabled = (config.value("disabledProviders") as? List<*>)?.toMutableList() ?: mutableListOf()
            disabled.removeAll { it == provider.id }
            if (!provider.enabled) disabled.add(provider.id)
            config.set(listOf("disabledProviders"), disabled)
            val metadataFile = configFile(dir, "wawlink-provider-ui.json")
            val metadata = metadataFile.takeIf { it.exists() }?.let { Json.parseToJsonElement(it.readText()).jsonObject.toMutableMap() } ?: mutableMapOf()
            val oldMetadata = (metadata[provider.id] as? JsonObject).orEmpty()
            val modelDefaults = JsonObject(provider.models.filter { it.thinkingLevel == ThinkingLevel.NONE && it.reasoning != false }
                .associate { it.id to JsonPrimitive(it.thinkingLevel.name) })
            metadata[provider.id] = JsonObject(oldMetadata + mapOf("name" to JsonPrimitive(provider.name), "modelDefaults" to modelDefaults))
            yaml.write(file); config.write(settingsFile)
            repoAtomicWrite(metadataFile, JsonObject(metadata).toString() + "\n")
            mergeProviderBudgets(dir, provider.id, provider.models)
            refresh()
        }
        onChanged(null, null)
    }

    override suspend fun deleteProvider(providerId: String) = withContext(Dispatchers.IO) {
        ompConfigMutex.withLock {
            val dir = agentDirectory(directory)
            val file = configFile(dir, "models.yml")
            val yaml = OmpYaml(file)
            (yaml.node("providers") as? MappingNode)?.let { yaml.set(it, providerId, null) }
            yaml.write(file)
            val settingsFile = mainConfigFile(dir)
            val config = OmpYaml(settingsFile)
            val disabled = (config.value("disabledProviders") as? List<*>)?.filter { it != providerId }
            if (disabled != null) { config.set(listOf("disabledProviders"), disabled); config.write(settingsFile) }
            val metadataFile = configFile(dir, "wawlink-provider-ui.json")
            if (metadataFile.exists()) {
                val metadata = Json.parseToJsonElement(metadataFile.readText()).jsonObject.toMutableMap()
                metadata.remove(providerId)
                repoAtomicWrite(metadataFile, JsonObject(metadata).toString() + "\n")
            }
            // Deliberately leave upstream-managed OAuth/account credentials intact.
            mergeProviderBudgets(dir, providerId, emptyList())
            refresh()
        }
        onChanged(null, null)
    }

    private fun mergeProviderBudgets(dir: File, providerId: String, models: List<ModelConfig>) {
        val file = configFile(dir, "wawlink-model-budgets.json")
        val values = file.takeIf { it.exists() }?.let { Json.parseToJsonElement(it.readText()).jsonObject.toMutableMap() } ?: mutableMapOf()
        values.keys.removeAll { it.startsWith("$providerId/") }
        models.forEach { model -> model.thinkingBudget?.let { values["$providerId/${model.id}"] = JsonPrimitive(it) } }
        repoAtomicWrite(file, JsonObject(values).toString() + "\n")
    }

    override suspend fun testConnection(provider: ProviderConfig): Result<LatencyInfo> = resultOnIo {
        val model = provider.models.firstOrNull() ?: error("请先配置一个模型；连接测试会真实调用推理接口")
        val path: String
        val payload = buildJsonObject {
            put("model", model.id)
            when (provider.apiType) {
                ProviderApiType.CHAT -> {
                    put("messages", JsonArray(listOf(buildJsonObject { put("role", "user"); put("content", "Reply OK") })))
                    put("max_tokens", 16)
                }
                ProviderApiType.RESPONSE -> { put("input", "Reply OK"); put("max_output_tokens", 16) }
                ProviderApiType.MESSAGE -> {
                    put("messages", JsonArray(listOf(buildJsonObject { put("role", "user"); put("content", "Reply OK") })))
                    put("max_tokens", 16)
                }
            }
        }
        path = when (provider.apiType) {
            ProviderApiType.CHAT -> "chat/completions"
            ProviderApiType.RESPONSE -> "responses"
            ProviderApiType.MESSAGE -> "messages"
        }
        val start = System.nanoTime()
        val response = request(provider, path, payload.toString())
        val body = Json.parseToJsonElement(response.second).jsonObject
        val actual = when (provider.apiType) {
            ProviderApiType.CHAT -> body["choices"] is JsonArray && body.getValue("choices").jsonArray.isNotEmpty()
            ProviderApiType.RESPONSE -> body["output"] is JsonArray && body.getValue("output").jsonArray.isNotEmpty()
            ProviderApiType.MESSAGE -> body["content"] is JsonArray && body.getValue("content").jsonArray.isNotEmpty()
        }
        check(actual && body["error"] == null) { "HTTP ${response.first} 未返回有效推理结果" }
        LatencyInfo((System.nanoTime() - start) / 1_000_000, response.first, "推理连接成功（已实际调用模型）")
    }

    override suspend fun fetchRemoteModels(provider: ProviderConfig): Result<List<ModelConfig>> = resultOnIo {
        val result = mutableListOf<String>()
        var path = "models"
        val seenPages = mutableSetOf<String>()
        while (true) {
            check(seenPages.add(path)) { "模型列表分页游标重复" }
            val response = request(provider, path, null)
            val body = Json.parseToJsonElement(response.second).jsonObject
            val data = body["data"] as? JsonArray ?: error("HTTP ${response.first} 模型列表缺少 data 数组")
            result += data.map { it.jsonObject.getValue("id").jsonPrimitive.content }
            val more = (body["has_more"] as? JsonPrimitive)?.content == "true"
            if (provider.apiType != ProviderApiType.MESSAGE || !more) break
            val last = body["last_id"]?.jsonPrimitive?.content ?: error("模型列表分页缺少 last_id")
            path = "models?after_id=" + java.net.URLEncoder.encode(last, "UTF-8")
        }
        // Existing manual entries are authoritative; discovery never saves or replaces them.
        val existing = provider.models.associateBy { it.id }
        val index = if (result.any { it !in existing }) {
            try { catalog.index() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        } else null
        result.distinct().map { id -> existing[id] ?: index?.match(id) ?: ModelConfig(id) }
    }

    override suspend fun matchModel(id: String): Result<ModelConfig?> = resultOnIo { catalog.index().match(id) }

    private fun request(provider: ProviderConfig, path: String, payload: String?): Pair<Int, String> {
        require(!provider.apiKey.startsWith("!")) { "命令型 API 密钥只能由容器内 OMP 解析；请使用明文 API 密钥测试" }
        require(provider.headers.values.none { it.startsWith("!") }) { "命令型请求头只能由容器内 OMP 解析" }
        val rawBase = provider.baseUrl.trimEnd('/')
        // The actual Anthropic adapter strips a trailing /v1, then its SDK appends /v1/messages.
        val normalizedBase = if (provider.apiType == ProviderApiType.MESSAGE) rawBase.removeSuffix("/v1") + "/v1" else rawBase
        val base = URI(normalizedBase + "/")
        require(base.scheme == "https" || base.scheme == "http") { "提供商 URL 必须使用 HTTP 或 HTTPS" }
        require(base.rawUserInfo == null && base.rawQuery == null && base.rawFragment == null) { "提供商 URL 不能包含凭据、查询或片段" }
        val connection = base.resolve(path).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000; connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.requestMethod = if (payload == null) "GET" else "POST"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json")
            if (provider.apiType == ProviderApiType.MESSAGE) {
                connection.setRequestProperty("anthropic-version", "2023-06-01")
                if (provider.apiKey.isNotEmpty()) connection.setRequestProperty("x-api-key", provider.apiKey)
            } else if (provider.apiKey.isNotEmpty()) connection.setRequestProperty("Authorization", "Bearer ${provider.apiKey}")
            provider.headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            if (payload != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            check(status in 200..299) { "HTTP $status：${response.take(1000).replace(provider.apiKey.takeIf { it.isNotEmpty() } ?: "\u0000", "[redacted]")}" }
            return status to response
        } finally { connection.disconnect() }
    }

    override fun close() { worker.cancel() }
}

internal class OmpSettingsRepository(
    private val directory: () -> File,
    scope: CoroutineScope,
    private val onChanged: suspend (String?, Any?) -> Unit,
) : AgentSettingsRepository, AutoCloseable {
    private val job = SupervisorJob(scope.coroutineContext[kotlinx.coroutines.Job])
    private val worker = CoroutineScope(scope.coroutineContext + job + Dispatchers.IO)
    private val mutableCategories = MutableStateFlow(OmpSettingsSchema.categories)
    override val categories: StateFlow<List<SettingCategory>> = mutableCategories.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = mutableError.asStateFlow()
    private var generation: Pair<String, String?>? = null

    init {
        worker.launch {
            while (isActive) {
                try {
                    val changes = ompConfigMutex.withLock { refresh() }
                    for ((id, value) in changes) onChanged(id, value)
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    mutableError.value = e.message ?: "无法读取容器代理配置"
                }
                delay(1_000)
            }
        }
    }

    private fun refresh(): List<Pair<String, Any?>> {
        val dir = agentDirectory(directory)
        val file = mainConfigFile(dir)
        val next = file.path to file.takeIf { it.exists() }?.readText()
        if (generation == next) return emptyList()
        val yaml = OmpYaml(file)
        val values = yaml.value(yaml.root).asStringMap()
        val categories = OmpSettingsSchema.effective { id ->
            var value: Any? = values
            for (segment in id.split('.')) value = (value as? Map<*, *>)?.get(segment)
            value
        }
        val old = mutableCategories.value.flatMap { it.groups }.flatMap { it.items }.associate { it.id to it.value }
        val changes = if (generation == null) emptyList() else categories.flatMap { it.groups }.flatMap { it.items }
            .filter { old[it.id] != it.value }.map { it.id to it.value }
        generation = next
        mutableCategories.value = categories
        mutableError.value = null
        return changes
    }

    override suspend fun updateSetting(id: String, value: Any?) {
        val normalized = OmpSettingsSchema.validate(id, value)
        editSettings(listOf(id)) { yaml -> yaml.set(id.split('.'), normalized) }
    }
    override suspend fun resetSetting(id: String) {
        require(id in OmpSettingsSchema.items) { "Unknown or excluded OMP setting: $id" }
        editSettings(listOf(id)) { it.remove(id.split('.')) }
    }
    override suspend fun resetAll() {
        editSettings(OmpSettingsSchema.items.keys.toList()) { yaml ->
            OmpSettingsSchema.items.keys.forEach { yaml.remove(it.split('.')) }
        }
    }
    private suspend fun editSettings(ids: List<String>, edit: (OmpYaml) -> Unit) = withContext(Dispatchers.IO) {
        val effective = ompConfigMutex.withLock {
            val file = mainConfigFile(agentDirectory(directory))
            val yaml = OmpYaml(file)
            edit(yaml)
            yaml.write(file)
            refresh()
            val values = mutableCategories.value.flatMap { it.groups }.flatMap { it.items }.associate { it.id to it.value }
            ids.map { it to values[it] }
        }
        // Unset/reset hooks receive the effective upstream default, never null as a stand-in for reset.
        for ((id, value) in effective) onChanged(id, value)
    }
    override fun close() { worker.cancel() }
}

private fun String.toThinkingLevelOrNull(): ThinkingLevel? = when (this) {
    "low" -> ThinkingLevel.LOW
    "medium" -> ThinkingLevel.MEDIUM
    "high" -> ThinkingLevel.HIGH
    "xhigh" -> ThinkingLevel.XHIGH
    "max" -> ThinkingLevel.MAX
    else -> null
}
private fun String?.toThinkingLevel(): ThinkingLevel = when (this) {
    "minimal" -> ThinkingLevel.LOW
    "low" -> ThinkingLevel.LOW
    "medium" -> ThinkingLevel.MEDIUM
    "high" -> ThinkingLevel.HIGH
    "xhigh" -> ThinkingLevel.XHIGH
    "max" -> ThinkingLevel.MAX
    else -> ThinkingLevel.MEDIUM
}
private fun ThinkingLevel.toEffort(): String = when (this) {
    ThinkingLevel.NONE -> error("Disabled thinking has no effort")
    ThinkingLevel.LOW -> "low"
    ThinkingLevel.MEDIUM -> "medium"
    ThinkingLevel.HIGH -> "high"
    ThinkingLevel.XHIGH -> "xhigh"
    ThinkingLevel.MAX -> "max"
}
private suspend fun <T> resultOnIo(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
    try { Result.success(block()) } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
}
