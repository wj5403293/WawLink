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
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
import org.yaml.snakeyaml.error.MarkedYAMLException
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.NodeTuple
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Representer

// Provider enable switches and ordinary settings share config.yml; serialize their reload/edit/write cycles.
internal val ompConfigMutex = Mutex()
private val providerApis = mapOf(
    ProviderApiType.CHAT to "openai-completions",
    ProviderApiType.RESPONSE to "openai-responses",
    ProviderApiType.MESSAGE to "anthropic-messages",
)
private val thinkingEfforts = listOf("minimal", "low", "medium", "high", "xhigh", "max")

/** Configuration locations are explicit normalized guest paths, never host filesystem paths. */
internal fun ompAgentDirectory(directory: String): String {
    require(directory.startsWith('/') && directory.split('/').drop(1).all { it.isNotEmpty() && it != "." && it != ".." } &&
        directory.endsWith("/.omp/agent")) { "OMP configuration directory must be an absolute normalized /.omp/agent guest path" }
    return directory
}

internal fun ompAgentDirectory(files: OmpFileSession, directory: String): String = ompAgentDirectory(directory).also {
    require(it == files.agentDirectory) { "OMP configuration directory must match the selected container home" }
}

private fun configFile(directory: String, name: String): String = "$directory/$name"

private suspend fun mainConfigFile(files: OmpFileSession, directory: String): String = configurationFile(files, directory, "config")
private suspend fun modelsConfigFile(files: OmpFileSession, directory: String): String = configurationFile(files, directory, "models")
private suspend fun configurationFile(files: OmpFileSession, directory: String, name: String): String {
    val yml = configFile(directory, "$name.yml")
    val yaml = configFile(directory, "$name.yaml")
    return if (files.stat(yml) != null || files.stat(yaml) == null) yml else yaml
}

internal fun contentVersion(content: String?): String {
    if (content == null) return ""
    val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
    val hex = "0123456789abcdef"
    return buildString(digest.size * 2) {
        for (byte in digest) { val value = byte.toInt() and 0xff; append(hex[value ushr 4]); append(hex[value and 15]) }
    }
}

private data class ConfigDocument(val path: String, val content: String?) {
    fun yaml(): OmpYaml = OmpYaml.parse(content.orEmpty(), path)
}

private suspend fun readConfigDocument(files: OmpFileSession, path: String): ConfigDocument = ConfigDocument(path, files.readText(path))

private suspend fun <T> withConfigurationIo(files: OmpGuestFiles, block: suspend (Long) -> T): T {
    val stopGeneration = files.stopGeneration // Capture before dispatch; queued work must not undo an explicit stop.
    return withContext(Dispatchers.IO) { block(stopGeneration) }
}

/** Render every candidate before committing; rollback only our exact writes, in the same guest generation. */
private suspend fun repoAtomicWriteBatch(files: OmpFileSession, documents: Map<ConfigDocument, String>) {
    val committed = ArrayList<Pair<ConfigDocument, String>>(documents.size)
    try {
        for ((document, content) in documents) {
            val version = contentVersion(document.content)
            if (document.content == content) {
                if (files.version(document.path) != version) throw OmpGuestFileException("CONFLICT", document.path)
                continue
            }
            files.writeText(document.path, content, expectedVersion = version, createOnly = document.content == null)
            committed.add(document to content)
        }
    } catch (failure: Exception) {
        withContext(NonCancellable) {
            for ((document, content) in committed.asReversed()) {
                try {
                    val version = contentVersion(content)
                    if (document.content == null) files.request("delete", mapOf("path" to document.path, "expectedVersion" to version))
                    else files.writeText(document.path, document.content, expectedVersion = version)
                } catch (restore: Exception) { failure.addSuppressed(restore) }
            }
        }
        throw failure
    }
}

/** Re-emit legacy pretty-flow documents before Bun reads them; credentials and YAML nodes stay intact. */
internal suspend fun prepareOmpConfiguration(files: OmpFileSession, directory: String) = ompConfigMutex.withLock {
    val dir = ompAgentDirectory(files, directory)
    val documents = linkedMapOf<ConfigDocument, String>()
    for (path in listOf(modelsConfigFile(files, dir), mainConfigFile(files, dir))) {
        val document = readConfigDocument(files, path)
        if (document.content != null) documents[document] = document.yaml().render()
    }
    repoAtomicWriteBatch(files, documents)
}

/** Mutate composed YAML nodes, not a load/dump map: comments, tags, unknown keys and scalar styles survive. */
internal class OmpYaml private constructor(private val yaml: Yaml, val root: MappingNode) {

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
            if (old.valueNode is SequenceNode && value is SequenceNode) {
                val original = old.valueNode as SequenceNode
                fun identity(node: Node): String? = (node as? MappingNode)?.let { mapping ->
                    ((mapping.entry("id") ?: mapping.entry("name"))?.valueNode as? ScalarNode)?.value
                }
                val byIdentity = original.value.mapNotNull { node -> identity(node)?.let { it to node } }.toMap()
                val merged = value.value.mapIndexed { index, node ->
                    val previous = identity(node)?.let(byIdentity::get) ?: original.value.getOrNull(index)?.takeIf { identity(it) == null }
                    if (previous is MappingNode && node is MappingNode) { mergeMapping(previous, node); previous } else node
                }
                original.value.clear()
                original.value.addAll(merged)
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
    fun represent(value: Any): Node = yaml.represent(value)
    fun render(): String = StringWriter().also { yaml.serialize(root, it) }.toString()
    fun values(): Map<String, Any?> = value(root).asStringMap()
    fun replace(values: Map<String, Any?>) { mergeMapping(root, yaml.represent(values) as MappingNode) }
    private fun mergeMapping(original: MappingNode, replacement: MappingNode) {
        val retained = replacement.value.map { (it.keyNode as ScalarNode).value }.toSet()
        original.value.removeAll { (it.keyNode as? ScalarNode)?.value !in retained }
        replacement.value.forEach { set(original, (it.keyNode as ScalarNode).value, it.valueNode) }
    }

    companion object {
        fun parse(content: String, path: String = "configuration"): OmpYaml {
            val yaml = newYaml()
            return OmpYaml(yaml, compose(yaml, content.reader(), path))
        }
        private fun compose(yaml: Yaml, reader: java.io.Reader, path: String): MappingNode {
            val node = try { yaml.compose(reader) } catch (failure: MarkedYAMLException) {
                val line = failure.problemMark?.let { ": line ${it.line + 1}, column ${it.column + 1}" }.orEmpty()
                val problem = failure.problem.orEmpty()
                val reason = if (problem.contains("duplicate", ignoreCase = true)) "duplicate mapping key" else problem.substringBefore("but found").replace(Regex("\"[^\"]*\"|'[^']*'|`[^`]*`"), "[value]").take(240)
                throw IllegalArgumentException("$path$line: YAML ${reason.ifBlank { "syntax error" }}")
            }
            require(node == null || node is MappingNode) { "$path must contain a YAML mapping" }
            return node as? MappingNode ?: mapping()
        }
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
    private val files: OmpGuestFiles,
    private val directory: () -> String,
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
    private val initialStopGeneration = files.stopGeneration

    init {
        worker.launch {
            var activate = true
            while (isActive) {
                try {
                    val initial = activate
                    activate = false // Construction is the one explicit activation; later polls never start a stopped container.
                    val session = files.session(expectedStopGeneration = if (initial) initialStopGeneration else null, runningOnly = !initial)
                    val changed = ompConfigMutex.withLock { refresh(session) }
                    if (changed) onChanged(null, null)
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    mutableError.value = e.message ?: "无法读取容器提供商配置"
                }
                delay(1_000)
            }
        }
    }

    private suspend fun refresh(session: OmpFileSession): Boolean {
        val dir = ompAgentDirectory(session, directory())
        val modelsFile = modelsConfigFile(session, dir)
        val config = mainConfigFile(session, dir)
        val modelsText = session.readText(modelsFile)
        val configText = session.readText(config)
        val metadataText = session.readText(configFile(dir, "wawlink-provider-ui.json"))
        val budgetsText = session.readText(configFile(dir, "wawlink-model-budgets.json"))
        val next = listOf(dir, modelsFile, config, modelsText, configText, metadataText, budgetsText)
        if (generation == next) { mutableError.value = null; return false }
        val models = OmpYaml.parse(modelsText.orEmpty(), modelsFile).value("providers").asStringMap()
        val settings = OmpYaml.parse(configText.orEmpty(), config)
        val disabled = (settings.value("disabledProviders") as? List<*>)?.filterIsInstance<String>().orEmpty()
        val metadata = metadataText?.let { Json.parseToJsonElement(it).jsonObject }.orEmpty()
        val budgets = ModelPayloadExtension.parseBudgets(budgetsText)
        val result = models.mapNotNull { (id, raw) ->
            val provider = raw.asStringMap()
            val modelMaps = (provider["models"] as? List<*>)?.map { it.asStringMap() }.orEmpty()
            val apiName = provider.string("api") ?: modelMaps.mapNotNull { it.string("api") }.distinct().singleOrNull()
            val api = providerApis.entries.firstOrNull { it.value == apiName }?.key
            if (apiName == null || modelMaps.any { it.string("api")?.let { modelApi -> modelApi != apiName } == true }) {
                return@mapNotNull null
            }
            ProviderConfig(
                id = id, name = (metadata[id] as? JsonObject)?.get("name")?.jsonPrimitive?.content ?: id,
                apiType = api ?: ProviderApiType.CHAT, nativeApi = apiName.takeIf { api == null }, baseUrl = provider.string("baseUrl") ?: modelMaps.firstNotNullOfOrNull { it.string("baseUrl") }.orEmpty(),
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
        mutableError.value = null // Inherited/mixed protocols are edited through native management.
        return changed
    }

    override suspend fun saveProvider(provider: ProviderConfig) = withConfigurationIo(files) { stopGeneration ->
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
        val session = files.session(expectedStopGeneration = stopGeneration)
        ompConfigMutex.withLock {
            val dir = ompAgentDirectory(session, directory())
            val file = readConfigDocument(session, modelsConfigFile(session, dir))
            val yaml = file.yaml() // Reload immediately before editing; never save an old StateFlow snapshot.
            if (yaml.node("providers") == null) yaml.set(listOf("providers"), emptyMap<String, Any>())
            val all = yaml.node("providers") as? MappingNode ?: error("models.yml providers 必须是映射")
            val existing = all.entry(provider.id)?.valueNode
            require(existing == null || existing is MappingNode) { "提供商配置必须是映射" }
            val node = existing as? MappingNode ?: OmpYaml.mapping()
            if (provider.models.isNotEmpty()) {
                require(provider.baseUrl.isNotBlank()) { "providers.${provider.id}.baseUrl: 原生自定义模型必须配置接口地址" }
                val authMode = (node.entry("auth")?.valueNode as? ScalarNode)?.value
                require(provider.apiKey.isNotEmpty() || authMode == "none" || authMode == "oauth") { "providers.${provider.id}.apiKey: 原生自定义模型需要 API Key，或在原生配置中声明 auth: none / 已注册服务的 oauth" }
            } else if (provider.baseUrl.isBlank() && provider.apiKey.isEmpty() && provider.headers.isEmpty()) {
                val inherited = listOf("compat", "disableStrictTools", "guardrailIdentifier", "requestMetadata", "remoteCompaction", "modelOverrides", "discovery").any { node.entry(it) != null }
                require(inherited || (node.entry("auth")?.valueNode as? ScalarNode)?.value == "none") { "providers.${provider.id}: 原生服务必须包含接口地址、认证、模型覆盖或发现配置" }
            }
            fun set(key: String, value: Any?) = yaml.set(node, key, value?.let(yaml::represent))
            set("api", provider.nativeApi ?: providerApis.getValue(provider.apiType))
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
                if (modelNode.entry("api") != null) setModel("api", provider.nativeApi ?: providerApis.getValue(provider.apiType))
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
            val settingsFile = readConfigDocument(session, mainConfigFile(session, dir))
            val config = settingsFile.yaml()
            val disabled = (config.value("disabledProviders") as? List<*>)?.toMutableList() ?: mutableListOf()
            disabled.removeAll { it == provider.id }
            if (!provider.enabled) disabled.add(provider.id)
            config.set(listOf("disabledProviders"), disabled)
            val metadataFile = readConfigDocument(session, configFile(dir, "wawlink-provider-ui.json"))
            val metadata = metadataFile.content?.let { Json.parseToJsonElement(it).jsonObject.toMutableMap() } ?: mutableMapOf()
            val oldMetadata = (metadata[provider.id] as? JsonObject).orEmpty()
            val modelDefaults = JsonObject(provider.models.filter { it.thinkingLevel == ThinkingLevel.NONE && it.reasoning != false }
                .associate { it.id to JsonPrimitive(it.thinkingLevel.name) })
            metadata[provider.id] = JsonObject(oldMetadata + mapOf("name" to JsonPrimitive(provider.name), "modelDefaults" to modelDefaults))
            val budgetsFile = readConfigDocument(session, configFile(dir, "wawlink-model-budgets.json"))
            val budgetsContent = renderProviderBudgets(budgetsFile, provider.id, provider.models)
            repoAtomicWriteBatch(session, linkedMapOf(file to yaml.render(), settingsFile to config.render(), metadataFile to (JsonObject(metadata).toString() + "\n"), budgetsFile to budgetsContent))
            refresh(session)
        }
        onChanged(null, null)
    }

    override suspend fun deleteProvider(providerId: String) = withConfigurationIo(files) { stopGeneration ->
        val session = files.session(expectedStopGeneration = stopGeneration)
        ompConfigMutex.withLock {
            val dir = ompAgentDirectory(session, directory())
            val file = readConfigDocument(session, modelsConfigFile(session, dir))
            val yaml = file.yaml()
            (yaml.node("providers") as? MappingNode)?.let { yaml.set(it, providerId, null) }
            val documents = linkedMapOf(file to yaml.render())
            val settingsFile = readConfigDocument(session, mainConfigFile(session, dir))
            val config = settingsFile.yaml()
            val disabled = (config.value("disabledProviders") as? List<*>)?.filter { it != providerId }
            if (disabled != null) { config.set(listOf("disabledProviders"), disabled); documents[settingsFile] = config.render() }
            val metadataFile = readConfigDocument(session, configFile(dir, "wawlink-provider-ui.json"))
            if (metadataFile.content != null) {
                val metadata = Json.parseToJsonElement(metadataFile.content).jsonObject.toMutableMap()
                metadata.remove(providerId)
                documents[metadataFile] = JsonObject(metadata).toString() + "\n"
            }
            // Deliberately leave upstream-managed OAuth/account credentials intact.
            val budgetsFile = readConfigDocument(session, configFile(dir, "wawlink-model-budgets.json"))
            documents[budgetsFile] = renderProviderBudgets(budgetsFile, providerId, emptyList())
            repoAtomicWriteBatch(session, documents)
            refresh(session)
        }
        onChanged(null, null)
    }

    private fun renderProviderBudgets(file: ConfigDocument, providerId: String, models: List<ModelConfig>): String {
        val values = file.content?.let { Json.parseToJsonElement(it).jsonObject.toMutableMap() } ?: mutableMapOf()
        values.keys.removeAll { it.startsWith("$providerId/") }
        models.forEach { model -> model.thinkingBudget?.let { values["$providerId/${model.id}"] = JsonPrimitive(it) } }
        return JsonObject(values).toString() + "\n"
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
        require(provider.nativeApi == null) { "This native protocol must be tested through Agent management's native model probe" }
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
            check(status in 200..299) {
                val reason = when (status) {
                    401 -> "服务拒绝认证，请核对 API Key 和认证请求头"
                    403 -> "服务拒绝访问，请核对账户权限"
                    404 -> "服务未提供此接口，请核对接口地址"
                    405 -> "服务不支持此请求方法"
                    429 -> "服务请求频率或配额已达限制"
                    in 500..599 -> "远端服务暂时不可用"
                    else -> "服务拒绝请求"
                }
                // Error bodies can echo credentials and entire request documents.
                "HTTP $status：$reason"
            }
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return status to response
        } finally { connection.disconnect() }
    }

    override fun close() { worker.cancel() }
}

internal class OmpSettingsRepository(
    private val files: OmpGuestFiles,
    private val directory: () -> String,
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
    private val initialStopGeneration = files.stopGeneration

    init {
        worker.launch {
            var activate = true
            while (isActive) {
                try {
                    val initial = activate
                    activate = false
                    val session = files.session(expectedStopGeneration = if (initial) initialStopGeneration else null, runningOnly = !initial)
                    val changes = ompConfigMutex.withLock { refresh(session) }
                    for ((id, value) in changes) onChanged(id, value)
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    mutableError.value = e.message ?: "无法读取容器代理配置"
                }
                delay(1_000)
            }
        }
    }

    private suspend fun refresh(session: OmpFileSession): List<Pair<String, Any?>> {
        val dir = ompAgentDirectory(session, directory())
        val file = mainConfigFile(session, dir)
        val text = session.readText(file)
        val next = file to text
        if (generation == next) { mutableError.value = null; return emptyList() }
        val yaml = OmpYaml.parse(text.orEmpty(), file)
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
    private suspend fun editSettings(ids: List<String>, edit: (OmpYaml) -> Unit) = withConfigurationIo(files) { stopGeneration ->
        val session = files.session(expectedStopGeneration = stopGeneration)
        val effective = ompConfigMutex.withLock {
            val file = readConfigDocument(session, mainConfigFile(session, ompAgentDirectory(session, directory())))
            val yaml = file.yaml()
            edit(yaml)
            repoAtomicWriteBatch(session, mapOf(file to yaml.render()))
            refresh(session)
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
