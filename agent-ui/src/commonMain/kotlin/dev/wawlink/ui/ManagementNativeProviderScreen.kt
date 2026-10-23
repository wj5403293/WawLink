@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.wawlink.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.sp
import dev.wawlink.protocol.*
import kotlinx.serialization.json.Json

private val nativeApis = linkedMapOf(
    "openai-completions" to "Chat", "openai-responses" to "Response", "anthropic-messages" to "Message",
    "google-generative-ai" to "Google", "google-gemini-cli" to "Gemini CLI", "google-vertex" to "Vertex", "bedrock-converse-stream" to "Bedrock",
    "azure-openai-responses" to "Azure", "openai-codex-responses" to "Codex", "openrouter-decisions" to "OpenRouter Decisions",
    "openai-decisions" to "OpenAI Decisions", "workers-ai-systemone" to "Workers AI", "typesafe" to "Typesafe")
private val nativeDiscovery = linkedMapOf("ollama" to "Ollama", "llama.cpp" to "llama.cpp", "lm-studio" to "LM Studio", "openai-models-list" to "OpenAI 模型列表", "proxy" to "代理", "litellm" to "LiteLLM")
private val nativeModelKindLabels = linkedMapOf("chat" to "对话", "image" to "图片生成", "tts" to "语音合成", "stt" to "语音识别", "embedding" to "向量", "tiny" to "轻量", "judge" to "评审", "rerank" to "重排", "video" to "视频")
private val nativeEnvironmentVariable = Regex("^[A-Z][A-Z0-9_]+$")
private val remoteListApis = setOf("openai-completions", "openai-responses", "anthropic-messages")
private fun nativeRuntimeSecret(value: String): Boolean = value.startsWith("!") || value.startsWith("$") || nativeEnvironmentVariable.matches(value)

internal val NativeProviderFieldsSaver = Saver<Map<String, Any?>, String>(
    save = { configurationJson(it).toString() },
    restore = { configurationMap(configurationFromJson(Json.parseToJsonElement(it))) },
)

internal fun mergeProviderFirstRead(initial: Map<String, Any?>, draft: Map<String, Any?>, edited: List<String>): Map<String, Any?> =
    (initial - edited.toSet()) + draft.filterKeys { it in edited }

private val NativeModelEditSaver = Saver<NativeModelEdit?, String>(
    save = { edit -> edit?.let { configurationJson(mapOf("id" to it.id, "values" to it.values, "override" to it.override)).toString() } },
    restore = { text -> configurationMap(configurationFromJson(Json.parseToJsonElement(text))).let { NativeModelEdit(it["id"] as? String, configurationMap(it["values"]), it["override"] == true) } },
)

@Composable
internal fun ManagementNativeProviderScreen(repository: AgentManagementRepository, providerId: String, onBack: () -> Unit, registerBack: ((() -> Boolean)?) -> Unit, onAccounts: () -> Unit = {}, providerRepository: ProviderConfigRepository? = null) {
    val state by repository.state.collectAsState()
    val actions = LocalManagementActions.current
    var document by remember { mutableStateOf<ConfigurationDocument?>(null) }
    var draft by rememberSaveable(providerId, stateSaver = NativeProviderFieldsSaver) { mutableStateOf<Map<String, Any?>>(emptyMap()) }
    var editedFields by rememberSaveable(providerId) { mutableStateOf(emptyList<String>()) }
    var dirty by rememberSaveable(providerId) { mutableStateOf(false) }
    var id by rememberSaveable(providerId) { mutableStateOf(providerId) }
    var originalId by rememberSaveable(providerId) { mutableStateOf(providerId) }
    var keySource by rememberSaveable(providerId) { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    var model by rememberSaveable(providerId, stateSaver = NativeModelEditSaver) { mutableStateOf<NativeModelEdit?>(null) }
    val formStates = rememberSaveableStateHolder()
    val providerScroll = rememberScrollState()
    LaunchedEffect(providerId) { providerScroll.scrollTo(0) }
    var modelBack by remember { mutableStateOf<(() -> Boolean)?>(null) }
    var compatibility by remember { mutableStateOf(false) }
    var postProcessing by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var sourceEditing by remember { mutableStateOf(false) }
    var clearAuthentication by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    var confirmTest by remember { mutableStateOf<ManagedModel?>(null) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var readAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(repository, providerId, readAttempt) {
        actions.launch {
            val next = repository.readDocument("models")
            val initial = configurationMap(configurationMap(next.values["providers"])[originalId])
            draft = if (dirty) mergeProviderFirstRead(initial, draft, editedFields) else initial
            document = next
        }
    }
    fun change(key: String, value: Any?) {
        draft = if (value == null) draft - key else draft + (key to value)
        editedFields = (editedFields + key).distinct()
        dirty = true
    }
    fun leave() {
        if (providerId.isEmpty() && !dirty) {
            id = ""
            originalId = ""
            draft = emptyMap()
            editedFields = emptyList()
            keySource = null
        }
        onBack()
    }
    fun back(): Boolean {
        if (sourceEditing) { modelBack?.invoke(); return true }
        if (model != null) { modelBack?.invoke(); return true }
        if (dirty) discard = true else leave()
        return true
    }
    val latestBack by rememberUpdatedState(::back)
    val currentRegistration by rememberUpdatedState(registerBack)
    DisposableEffect(repository) { currentRegistration { latestBack() }; onDispose { currentRegistration(null) } }
    fun save(after: () -> Unit = {}) {
        val source = document ?: return
        val currentDraft = draft
        val currentId = id.trim()
        actions.launch("已保存原生服务配置") {
            require(currentId.isNotBlank() && '/' !in currentId && currentId !in listOf("__proto__", "constructor", "prototype")) { "服务商标识无效" }
            val configured = configurationMap(source.values["providers"])
            require(originalId.isNotEmpty() || currentId !in configured) { "服务商标识已存在" }
            val providerValues = if (state.providers.none { it.id == currentId && it.builtin } && currentDraft["models"] is List<*> && currentDraft["api"] == null) currentDraft + ("api" to "openai-completions") else currentDraft
            val all = configured - originalId + (currentId to providerValues)
            repository.saveDocumentValues(source, source.values + ("providers" to all))
            val next = repository.readDocument("models")
            document = next
            originalId = currentId
            if (draft == currentDraft && id.trim() == currentId) { dirty = false; editedFields = emptyList(); after() }
        }
    }
    val customModels = (draft["models"] as? List<*>)?.map(::configurationMap).orEmpty()
    val overrides = configurationMap(draft["modelOverrides"])
    val descriptor = state.providers.firstOrNull { it.id == id }
    val nativeModel = state.models.firstOrNull { it.provider == id }
    val builtin = descriptor?.builtin == true
    if (sourceEditing) {
        document?.let { source -> ManagementConfigurationDocumentScreen(repository, source, { sourceEditing = false; actions.launch { val next = repository.readDocument("models"); document = next; if (!dirty) draft = configurationMap(configurationMap(next.values["providers"])[originalId]) } }, { handler -> modelBack = handler }) }
        return
    }
    model?.let { edit ->
        val modelRoute = "model:${edit.id ?: "new"}"
        formStates.SaveableStateProvider(modelRoute) {
        NativeModelScreen(id, edit, state, onBack = { model = null; formStates.removeState(modelRoute) }, onSave = { modelId, fields ->
            if (edit.override) change("modelOverrides", if (fields.isEmpty()) overrides - modelId else overrides + (modelId to fields))
            else change("models", customModels.filterNot { it["id"] == edit.id || it["id"] == modelId } + (fields + ("id" to modelId)))
            model = null
            formStates.removeState(modelRoute)
        }, onDelete = {
            if (edit.override) change("modelOverrides", overrides - edit.id) else change("models", customModels.filterNot { it["id"] == edit.id })
            model = null
            formStates.removeState(modelRoute)
        }, registerBack = { modelBack = it }, existingIds = customModels.mapNotNull { it["id"] as? String }.toSet())
        }
        return
    }
    formStates.SaveableStateProvider("provider-form") {
    ManagementPage(draft["name"] as? String ?: descriptor?.name ?: id.ifEmpty { "添加服务" }, { back() }, scroll = providerScroll, actions = { Box {
        AgentIconButton("more_vert", "更多", { more = true })
        ManagementMenu(more, { more = false }) {
            ManagementMenuItem(text = { Text("复制配置") }, leadingIcon = { Symbol("content_copy") }, onClick = { more = false; clipboard.setText(AnnotatedString(configurationJson(draft - "apiKey" - "headers").toString())) })
            ManagementMenuItem(text = { Text("查看来源") }, leadingIcon = { Symbol("data_object") }, onClick = { more = false; sourceEditing = true })
            HorizontalDivider(Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            if (state.accounts.any { it.provider == id }) ManagementMenuItem(text = { Text("清除认证记录", color = LocalAgentColors.current.error) }, leadingIcon = { Symbol("key_off", tint = LocalAgentColors.current.error) }, onClick = { more = false; clearAuthentication = true })
        }
    } },
        footer = { ManagementSaveBar(dirty, actions.busy, { save() }, { val source = document; draft = configurationMap(configurationMap(source?.values?.get("providers"))[originalId]); dirty = false; editedFields = emptyList(); keySource = null }, actions.error, canSave = document != null && id.isNotBlank()) }) {
        if (document == null) {
            if (actions.error == null) Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else TextButton({ readAttempt++ }) { Text("重新读取配置") }
        }
        state.modelsError?.let { ManagementBanner(it, "sync_problem", error = true) }
        actions.error?.let { ManagementBanner(it, "error", error = true); if (document != null) TextButton({ readAttempt++ }, enabled = !actions.busy) { Text("重新读取配置") } }
        ManagementField("服务商标识", id, { id = it; dirty = true }, mono = true, readOnly = originalId.isNotEmpty())
        val selectedApi = draft["api"] as? String ?: descriptor?.api ?: nativeModel?.api ?: "openai-completions"
        NativeEnum("接口协议", draft["api"] as? String, nativeApis, { change("api", it) }, inherited = if (builtin && descriptor?.api == null) "按模型" else nativeApis[selectedApi] ?: selectedApi)
        ManagementField("接口地址", draft["baseUrl"] as? String ?: descriptor?.baseUrl ?: "", { change("baseUrl", it.takeIf(String::isNotBlank)) }, mono = true)
        ManagementGroup("认证") {
            SettingsLabel("认证方式")
            ManagementSegments(listOf("apiKey" to "API Key", "none" to "无需认证"), if (draft["auth"] == "none") "none" else "apiKey", { change("auth", it) })
            if (draft["auth"] != "none") {
                val key = draft["apiKey"] as? String ?: ""
                val source = keySource ?: when { key.startsWith("!") -> "command"; key.startsWith("$") || nativeEnvironmentVariable.matches(key) -> "env"; else -> "literal" }
                SettingsLabel("Key 来源")
                ManagementSegments(listOf("literal" to "字面值", "env" to "环境变量", "command" to "命令"), source, { value -> keySource = value; change("apiKey", null) })
                SettingsLabel(if (source == "literal") "API Key" else if (source == "env") "变量名" else "取密钥命令")
                SettingsField(if (source == "command") key.removePrefix("!") else key, { value -> change("apiKey", value.takeIf(String::isNotBlank)?.let { if (source == "command") "!$it" else it }) }, mono = true, secret = source == "literal" && !showKey, trailing = if (source == "literal") ({ AgentIconButton(if (showKey) "visibility_off" else "visibility", "显示或隐藏 Key", { showKey = !showKey }) }) else null)
            }
            NativeOptionalBoolean("发送认证请求头", draft["authHeader"] as? Boolean, { change("authHeader", it) })
        }
        if (descriptor?.oauth == true) {
            Button({ actions.launch { repository.login(id) } }, enabled = !actions.busy, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp)) { Symbol("login"); Spacer(Modifier.width(8.dp)); Text("OAuth 登录") }
            if (state.accounts.any { it.provider == id }) ManagementRow("管理账号", "${state.accounts.count { it.provider == id }} 个账号", "manage_accounts", onAccounts)
        }
        ManagementGroup("自定义请求头") { NativeStringMap(configurationMap(draft["headers"]), { change("headers", it.takeIf { values -> values.isNotEmpty() }) }, sensitive = true) }
        ManagementGroup("模型目录") {
            val discovery = configurationMap(draft["discovery"])
            NativeEnum("自动发现", discovery["type"] as? String, nativeDiscovery, { type -> change("discovery", type?.let { mapOf("type" to it) }) }, inherited = "关闭")
            if (discovery.isNotEmpty()) {
                NativeNumber("发现超时 · 毫秒", discovery["timeoutMs"], { value -> change("discovery", if (value == null) discovery - "timeoutMs" else discovery + ("timeoutMs" to value)) })
                if (discovery["type"] == "openai-models-list") NativeOptionalBoolean("自动补全 /v1", discovery["injectV1"] as? Boolean, { value -> change("discovery", if (value == null) discovery - "injectV1" else discovery + ("injectV1" to value)) })
            }
            FlowRow(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val draftApi = draft["api"] as? String ?: descriptor?.api ?: nativeModel?.api ?: "openai-completions"
                val supportedDraftApi = draftApi in remoteListApis
                val draftKey = draft["apiKey"] as? String ?: ""
                val literalCredentials = (draft["auth"] == "none" || keySource != "env" && keySource != "command" && !nativeRuntimeSecret(draftKey)) &&
                    configurationMap(draft["headers"]).values.none { nativeRuntimeSecret(it.toString()) }
                val canFetchDraft = !builtin && providerRepository != null && supportedDraftApi && literalCredentials
                if (canFetchDraft || descriptor?.canFetchModels == true) Button({
                    if (canFetchDraft) {
                        val snapshot = draft
                        val snapshotId = id
                        actions.launch {
                            val request = ProviderConfig(id = snapshotId, name = snapshot["name"] as? String ?: snapshotId,
                                apiType = when (draftApi) { "anthropic-messages" -> ProviderApiType.MESSAGE; "openai-responses" -> ProviderApiType.RESPONSE; else -> ProviderApiType.CHAT },
                                baseUrl = snapshot["baseUrl"] as? String ?: "", apiKey = if (snapshot["auth"] == "none") "" else draftKey,
                                headers = configurationMap(snapshot["headers"]).mapValues { it.value.toString() })
                            val fetched = requireNotNull(providerRepository).fetchRemoteModels(request).getOrThrow()
                            check(draft == snapshot && id == snapshotId) { "配置已变更，请重新获取模型" }
                            val known = customModels.mapNotNull { it["id"] as? String }.toSet()
                            val added = fetched.filter { it.id.isNotBlank() && it.id !in known }.distinctBy { it.id }.map { fetchedModel ->
                                buildMap<String, Any?> {
                                    put("id", fetchedModel.id)
                                    put("name", fetchedModel.name)
                                    put("contextWindow", fetchedModel.contextWindow)
                                    put("maxTokens", fetchedModel.maxTokens)
                                    put("input", fetchedModel.input)
                                    fetchedModel.reasoning?.let { put("reasoning", it) }
                                    fetchedModel.cost?.let { cost -> put("cost", mapOf("input" to cost.input, "output" to cost.output, "cacheRead" to cost.cacheRead, "cacheWrite" to cost.cacheWrite)) }
                                }
                            }
                            if (added.isNotEmpty()) change("models", customModels + added)
                            testResult = if (added.isEmpty()) "没有新模型" else "获取到 ${added.size} 个新模型"
                        }
                    } else {
                        val fetch = { actions.launch("已获取模型") { repository.fetchProviderModels(id) } }
                        if (dirty) save(fetch) else fetch()
                    }
                }, enabled = !actions.busy && document != null && id.isNotBlank(), modifier = Modifier.widthIn(min = 148.dp).heightIn(min = 48.dp), colors = ButtonDefaults.buttonColors(containerColor = LocalAgentColors.current.primary, contentColor = LocalAgentColors.current.onPrimary)) { Symbol("sync"); Spacer(Modifier.width(8.dp)); Text("获取模型") }
                OutlinedButton({ confirmTest = state.models.firstOrNull { it.provider == id && it.kind == "chat" && it.available } }, enabled = !dirty && state.models.any { it.provider == id && it.kind == "chat" && it.available }, modifier = Modifier.widthIn(min = 148.dp).heightIn(min = 48.dp)) { Symbol("network_check"); Spacer(Modifier.width(8.dp)); Text("推理测试") }
            }
            testResult?.let { ManagementBanner(it, "network_check") }
        }
        ManagementGroup("模型", action = { TextButton({ model = NativeModelEdit(null, emptyMap(), false) }, enabled = document != null) { Symbol("add", size = 20.dp); Text("手工添加") } }) {
            val catalog = state.models.filter { it.provider == id }
            val names = (customModels.mapNotNull { it["id"] as? String } + catalog.map { it.id } + overrides.keys).distinct()
            LazyColumn(Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 480.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                itemsIndexed(names, key = { _, modelId -> modelId }) { index, modelId ->
                    val custom = customModels.firstOrNull { it["id"] == modelId }
                    val known = catalog.firstOrNull { it.id == modelId }
                    ManagementRow(known?.name ?: custom?.get("name") as? String ?: modelId, if (custom != null) "自定义模型" else if (modelId in overrides) "内置目录，有覆盖" else "内置目录", "", { model = NativeModelEdit(modelId, custom ?: configurationMap(overrides[modelId]), custom == null) }, index, names.size,
                        lead = { ModelIcon(modelId, known?.name ?: modelId, Modifier.size(44.dp)) }, enabled = document != null)
                }
            }
            if (names.isEmpty()) SettingsEmpty("还没有模型", "playlist_add")
        }
        ManagementGroup("协议与传输") {
            ManagementRow("协议兼容项", "消息角色、工具调用、推理格式与流式用量", "swap_calls", { compatibility = true }, 0, 2)
            ManagementRow("远程压缩", configurationDisplay(draft["remoteCompaction"]), "compress", { postProcessing = true }, 1, 2)
            if (draft["transport"] != null) NativeEnum("网关传输", draft["transport"] as? String, mapOf("pi-native" to "OMP 原生网关"), { change("transport", it) })
            NativeOptionalBoolean("禁用严格工具参数", draft["disableStrictTools"] as? Boolean, { change("disableStrictTools", it) })
        }
        if (draft["api"] == "bedrock-converse-stream") ManagementGroup("Bedrock") {
            ManagementField("Guardrail 标识", draft["guardrailIdentifier"] as? String ?: "", { change("guardrailIdentifier", it.takeIf(String::isNotBlank)) }, mono = true)
            ManagementField("Guardrail 版本", draft["guardrailVersion"] as? String ?: "", { change("guardrailVersion", it.takeIf(String::isNotBlank)) })
            NativeEnum("Guardrail 跟踪", draft["guardrailTrace"] as? String, mapOf("enabled" to "启用", "disabled" to "禁用", "enabled_full" to "完整跟踪"), { change("guardrailTrace", it) })
            NativeStringMap(configurationMap(draft["requestMetadata"]), { change("requestMetadata", it) })
        }
        if (configurationMap(document?.values?.get("providers")).containsKey(originalId)) TextButton({ confirmDelete = true }, modifier = Modifier.padding(top = 24.dp)) { Symbol("delete", tint = LocalAgentColors.current.error); Spacer(Modifier.width(8.dp)); Text("清除服务配置", color = LocalAgentColors.current.error) }
    }
    }
    ManagementAuthenticationChallenge(repository, state)
    if (clearAuthentication) AlertDialog(onDismissRequest = { clearAuthentication = false }, icon = { Symbol("key_off", tint = LocalAgentColors.current.error) }, title = { Text("清除认证记录") }, text = { Text("只删除 $id 的凭据，服务配置和模型保留。") }, confirmButton = { TextButton({ actions.launch("已清除认证记录") { state.accounts.filter { it.provider == id }.forEach { repository.logout(id, it.id) }; clearAuthentication = false } }) { Text("清除", color = LocalAgentColors.current.error) } }, dismissButton = { TextButton({ clearAuthentication = false }) { Text("取消") } })
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("有未保存的修改") }, text = { Text("离开后未保存的修改会丢失。") }, confirmButton = { TextButton({ save { discard = false; leave() } }, enabled = document != null && !actions.busy) { Text("保存") } }, dismissButton = { Row { TextButton({ discard = false; dirty = false; editedFields = emptyList(); draft = configurationMap(configurationMap(document?.values?.get("providers"))[originalId]); id = originalId; leave() }) { Text("放弃修改") }; TextButton({ discard = false }) { Text("继续编辑") } } })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("清除服务配置") }, text = { Text("删除用户配置，内置目录、认证记录和角色绑定不删除。引用自定义模型的角色可能失去候选。") }, confirmButton = { TextButton({ val source = document ?: return@TextButton; actions.launch("已清除服务配置") { repository.saveDocumentValues(source, source.values + ("providers" to (configurationMap(source.values["providers"]) - originalId))); dirty = false; leave() } }) { Text("清除", color = LocalAgentColors.current.error) } }, dismissButton = { TextButton({ confirmDelete = false }) { Text("取消") } })
    confirmTest?.let { selected -> AlertDialog(onDismissRequest = { confirmTest = null }, title = { Text("推理测试") }, text = { Text("将使用 ${selected.key} 发送一次真实请求，可能产生费用。") }, confirmButton = { TextButton({ confirmTest = null; actions.launch { val result = repository.probeModel(selected.provider, selected.id); testResult = "${result.message}，${result.latencyMs} 毫秒" } }) { Text("发送测试") } }, dismissButton = { TextButton({ confirmTest = null }) { Text("取消") } }) }
    if (compatibility) NativeCompatSheet(configurationMap(draft["compat"]), draft["api"] as? String ?: descriptor?.api ?: nativeModel?.api, { compatibility = false }, onSave = { change("compat", it.takeIf { values -> values.isNotEmpty() }); compatibility = false })
    if (postProcessing) NativeRemoteCompaction(configurationMap(draft["remoteCompaction"]), { postProcessing = false }) { change("remoteCompaction", it.takeIf { values -> values.isNotEmpty() }); postProcessing = false }
}
private data class NativeModelEdit(val id: String?, val values: Map<String, Any?>, val override: Boolean)

@Composable
private fun NativeSection(title: String, initial: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable { mutableStateOf(initial) }
    val angle by animateFloatAsState(if (open) 180f else 0f, tween(400, easing = EmphEasing))
    Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(start = 4.dp, end = 4.dp, top = 24.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = LocalAgentColors.current.primary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Symbol("expand_more", Modifier.rotate(angle), tint = LocalAgentColors.current.onSurfaceVariant)
    }
    AnimatedVisibility(open, enter = expandVertically(tween(500, easing = EmphEasing)) + fadeIn(tween(400)), exit = shrinkVertically(tween(500, easing = EmphEasing)) + fadeOut(tween(250))) { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp), content = content) }
}
@Composable
private fun NativeEnum(label: String, value: String?, options: Map<String, String>, onValue: (String?) -> Unit, inherited: String = "使用默认值") {
    var open by remember { mutableStateOf(false) }
    SettingsLabel(label)
    Box {
        ManagementRow(options[value] ?: value ?: inherited, icon = "", onClick = { open = true }, trailing = { Symbol("arrow_drop_down") })
        ManagementMenu(open, { open = false }) {
            ManagementMenuItem(text = { Text(inherited) }, leadingIcon = { Symbol("link") }, onClick = { onValue(null); open = false })
            options.forEach { (id, name) -> ManagementMenuItem(text = { Text(name) }, selected = id == value, trailingIcon = { if (id == value) Symbol("check") }, onClick = { onValue(id); open = false }) }
        }
    }
}
@Composable
private fun NativeOptionalBoolean(label: String, value: Boolean?, onValue: (Boolean?) -> Unit) {
    NativeEnum(label, value?.toString(), linkedMapOf("true" to "开启", "false" to "关闭"), { onValue(it?.toBooleanStrictOrNull()) })
}
@Composable
private fun NativeNumber(label: String, value: Any?, onValue: (Any?) -> Unit) {
    var text by remember { mutableStateOf(value?.toString().orEmpty()) }
    LaunchedEffect(value) {
        if (value?.toString() != text && (value !is Number || text.toDoubleOrNull() != value.toDouble())) text = value?.toString().orEmpty()
    }
    ManagementField(label, text, { next -> text = next; onValue(if (next.isBlank()) null else next.toLongOrNull() ?: next.toDoubleOrNull()?.takeIf(Double::isFinite) ?: next) }, mono = true)
    if (text.isNotBlank() && text.toDoubleOrNull()?.isFinite() != true) ManagementHint("请输入有效数值", "error", error = true)
}
@Composable
private fun NativeStringMap(values: Map<String, Any?>, onChange: (Map<String, Any?>) -> Unit, sensitive: Boolean = false) {
    var editing by remember { mutableStateOf<String?>(null) }
    var add by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        values.entries.forEachIndexed { index, (name, content) -> ManagementRow(name, if (sensitive) "已配置，点击编辑来源" else content.toString(), "", { key = name; value = content.toString(); editing = name; add = true }, index, values.size, trailing = { AgentIconButton("remove_circle", "删除 $name", { onChange(values - name) }) }) }
        TextButton({ editing = null; key = ""; value = ""; add = true }) { Symbol("add", size = 20.dp); Spacer(Modifier.width(6.dp)); Text("添加") }
    }
    if (add) AgentSheet(if (editing == null) "添加字段" else "编辑字段", { add = false }) {
        ManagementField("名称", key, { key = it }, mono = true)
        ManagementField("值", value, { value = it }, mono = true)
        Button({ onChange(values - editing.orEmpty() + (key.trim() to value)); add = false }, enabled = key.isNotBlank() && (key.trim() == editing || key.trim() !in values), modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) { Text("完成") }
    }
}

@Composable
private fun NativeModelScreen(provider: String, edit: NativeModelEdit, state: AgentManagementState, onBack: () -> Unit, onSave: (String, Map<String, Any?>) -> Unit, onDelete: () -> Unit, registerBack: ((() -> Boolean)?) -> Unit, existingIds: Set<String>) {
    var id by rememberSaveable { mutableStateOf(edit.id.orEmpty()) }
    var draft by rememberSaveable(stateSaver = NativeProviderFieldsSaver) { mutableStateOf(edit.values) }
    var dirty by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var compat by remember { mutableStateOf(false) }
    var remote by remember { mutableStateOf(false) }
    var original by remember { mutableStateOf(false) }
    val repository = requireNotNull(LocalManagementRepository.current)
    var catalog by remember(provider, edit.id) { mutableStateOf<ManagedModel?>(null) }
    var detailsError by remember(provider, edit.id) { mutableStateOf<String?>(null) }
    var detailsLoading by remember(provider, edit.id) { mutableStateOf(edit.override && edit.id != null) }
    var detailsAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(repository, provider, edit.id, detailsAttempt) {
        val modelId = edit.id ?: return@LaunchedEffect
        if (!edit.override) return@LaunchedEffect
        detailsLoading = true
        detailsError = null
        try { catalog = repository.modelDetails(provider, modelId) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { detailsError = failure.message ?: "模型详情读取失败" }
        finally { detailsLoading = false }
    }
    val base = configurationMap(catalog?.metadata?.get("catalogueBaseline")).ifEmpty { configurationMap(catalog?.metadata?.get("baseline")) }
    fun field(key: String): Any? = if (draft.containsKey(key)) draft[key] else if (edit.override) base[key] else null
    fun change(key: String, value: Any?) { draft = if (value == null) draft - key else draft + (key to value); dirty = true }
    val duplicateId = id.trim() != edit.id && id.trim() in existingIds
    val configuredCost = configurationMap(draft["cost"])
    val completeCost = edit.override || configuredCost.isEmpty() || listOf("input", "output", "cacheRead", "cacheWrite").all { configuredCost[it] is Number }
    fun back(): Boolean { if (dirty) confirmDiscard = true else onBack(); return true }
    val latestBack by rememberUpdatedState(::back)
    val currentRegistration by rememberUpdatedState(registerBack)
    DisposableEffect(edit) { currentRegistration { latestBack() }; onDispose { currentRegistration(null) } }
        ManagementPage(if (edit.id == null) "添加模型" else "编辑模型", { if (dirty) confirmDiscard = true else onBack() },
            footer = { ManagementSaveBar(dirty, detailsLoading, { onSave(id.trim(), draft) }, { draft = edit.values; dirty = false }, error = detailsError, canSave = !detailsLoading && detailsError == null && id.isNotBlank() && !duplicateId && completeCost) }) {
            if (detailsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            detailsError?.let {
                ManagementBanner(it, "error", error = true)
                TextButton({ detailsAttempt++ }) { Text("重新读取") }
            }
            if (detailsLoading || detailsError != null) return@ManagementPage
            if (edit.override) {
                Row { TextButton({ original = true }) { Text("对照原始值") }; TextButton({ draft = emptyMap(); dirty = true }) { Text("清除全部覆盖") } }
            }
            NativeSection("身份与类型", true) {
                ManagementField("模型 ID", id, { id = it; dirty = true }, mono = true, readOnly = edit.override)
                if (duplicateId) ManagementHint("模型 ID 已存在", "error", error = true)
                ManagementField("显示名称", field("name") as? String ?: "", { change("name", it.takeIf(String::isNotBlank)) })
                val nativeKinds = configurationStrings(catalog?.metadata?.get("modelKinds") ?: state.models.firstOrNull()?.metadata?.get("modelKinds")).filter { it != "search" }.ifEmpty { nativeModelKindLabels.keys.toList() }
                NativeEnum("类型", field("kind") as? String, nativeKinds.associateWith { nativeModelKindLabels[it] ?: it }, { change("kind", it) })
                val runnerApis = configurationMap(catalog?.metadata?.get("runnerApiKinds") ?: state.models.firstOrNull()?.metadata?.get("runnerApiKinds")).keys.filter { it != "web-search" }.associateWith { it }
                NativeEnum("接口协议", field("api") as? String, nativeApis + runnerApis + state.models.map { it.api }.filter(String::isNotBlank).associateWith { it }, { change("api", it) })
            }
            NativeSection("输入与工具") {
                val input = configurationStrings(field("input"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("text" to "文本", "image" to "图片").forEach { (key, name) -> ManagementChip(name, key in input, { change("input", if (key in input) input - key else input + key) }) } }
                NativeOptionalBoolean("支持工具调用", field("supportsTools") as? Boolean, { change("supportsTools", it) })
                NativeEnum("图像解码器", field("imageInputDecoder") as? String, mapOf("stb" to "STB"), { change("imageInputDecoder", it) })
                NativeNumber("图片数量预算", field("imageCountBudget"), { change("imageCountBudget", it) })
                NativeEnum("分词器", field("tokenizer") as? String, listOf("claude-v3", "claude-v47", "claude-v5", "claude-v5-sonnet", "qwen3", "deepseek-v3", "kimi-k2", "glm5", "o200k").associateWith { it }, { change("tokenizer", it) })
            }
            NativeSection("上下文与输出", true) {
                listOf("contextWindow" to "上下文窗口", "maxContextWindow" to "最大上下文窗口", "maxTokens" to "输出上限").forEach { (key, name) -> NativeNumber("$name · tokens", field(key), { change(key, it) }) }
                NativeOptionalBoolean("请求中不发送输出上限", field("omitMaxOutputTokens") as? Boolean, { change("omitMaxOutputTokens", it) })
            }
            NativeSection("思考能力", true) {
                NativeOptionalBoolean("支持思考", field("reasoning") as? Boolean, { change("reasoning", it) })
                val thinking = configurationMap(field("thinking"))
                fun thought(key: String, value: Any?) { change("thinking", if (value == null) thinking - key else thinking + (key to value)) }
                NativeEnum("控制方式", thinking["mode"] as? String, linkedMapOf("effort" to "档位", "budget" to "数值预算", "google-level" to "Google 档位", "anthropic-adaptive" to "Anthropic 自适应", "anthropic-budget-effort" to "Anthropic 预算与档位"), { thought("mode", it) })
                SettingsLabel("支持的档位")
                val efforts = configurationStrings(thinking["efforts"])
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { listOf("minimal", "low", "medium", "high", "xhigh", "max").forEach { effort -> ManagementChip(managementThinkingLabel(effort), effort in efforts, { thought("efforts", if (effort in efforts) efforts - effort else efforts + effort) }) } }
                NativeEnum("默认档位", thinking["defaultLevel"] as? String, (efforts + listOfNotNull(thinking["defaultLevel"] as? String)).distinct().associateWith(::managementThinkingLabel), { thought("defaultLevel", it) })
                NativeOptionalBoolean("必须指定档位", thinking["requiresEffort"] as? Boolean, { thought("requiresEffort", it) })
                NativeOptionalBoolean("返回思考内容", thinking["supportsDisplay"] as? Boolean, { thought("supportsDisplay", it) })
                SettingsLabel("服务端档位映射")
                NativeStringMap(configurationMap(thinking["effortMap"]), { thought("effortMap", it) })
            }
            NativeSection("费用与缓存") {
                val cost = configurationMap(field("cost"))
                listOf("input" to "输入", "output" to "输出", "cacheRead" to "缓存读取", "cacheWrite" to "缓存写入").forEach { (key, label) -> NativeNumber("$label · 美元 / 百万 token", cost[key], { value ->
                    val next = if (value == null) cost - key else cost + (key to value)
                    change("cost", next.takeIf { it.isNotEmpty() })
                }) }
                if (!completeCost) ManagementHint("请填写完整的四项价格，或全部留空", "error", error = true)
                NativeNumber("倍率", field("premiumMultiplier"), { change("premiumMultiplier", it) })
                val cache = configurationMap(field("promptCache"))
                listOf("short" to "短期缓存时长", "long" to "长期缓存时长").forEach { (key, label) -> NativeNumber(label, cache[key], { value -> change("promptCache", if (value == null) cache - key else cache + (key to value)) }) }
            }
            NativeSection("传输与兼容") {
                NativeOptionalBoolean("优先使用 WebSocket", field("preferWebsockets") as? Boolean, { change("preferWebsockets", it) })
                ManagementRow("协议兼容项", "消息角色、工具调用、推理格式、流式用量", "swap_calls", { compat = true })
                ManagementField("上下文提升目标", field("contextPromotionTarget") as? String ?: "", { change("contextPromotionTarget", it.takeIf(String::isNotBlank)) }, mono = true)
                ManagementField("压缩模型", field("compactionModel") as? String ?: "", { change("compactionModel", it.takeIf(String::isNotBlank)) }, mono = true)
                ManagementRow("远程压缩", configurationDisplay(field("remoteCompaction")), "compress", { remote = true })
                ManagementField("模型专用地址", field("baseUrl") as? String ?: "", { change("baseUrl", it.takeIf(String::isNotBlank)) }, mono = true)
                SettingsLabel("模型请求头")
                NativeStringMap(configurationMap(field("headers")), { change("headers", it) }, sensitive = true)
            }
            if (edit.id != null) TextButton({ delete = true }, modifier = Modifier.padding(top = 20.dp)) { Symbol("delete"); Text(if (edit.override) "清除模型覆盖" else "删除模型") }
        }
        if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, title = { Text("有未保存的修改") }, text = { Text("保存模型后仍需在服务页面保存配置。") }, confirmButton = { TextButton({ onSave(id.trim(), draft) }, enabled = id.isNotBlank() && !duplicateId && completeCost) { Text("保存") } }, dismissButton = { Row { TextButton(onBack) { Text("放弃修改") }; TextButton({ confirmDiscard = false }) { Text("继续编辑") } } })
        if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text(if (edit.override) "清除模型覆盖" else "删除模型") }, text = { Text("引用此模型的角色可能会跳过它；服务页面保存后生效。") }, confirmButton = { TextButton(onDelete) { Text("确认") } }, dismissButton = { TextButton({ delete = false }) { Text("取消") } })
        if (original) AgentSheet("原始值与覆盖", { original = false }) { Column(Modifier.verticalScroll(rememberScrollState())) { (base.keys + draft.keys).distinct().filter { base[it] != null || draft[it] != null }.forEach { key -> ManagementLayers(listOf(ManagementLayer(key, configurationDisplay(base[key]), "inventory_2"), ManagementLayer("本层覆盖", configurationDisplay(draft[key]), "edit", selected = key in draft))) } } }
        if (compat) NativeCompatSheet(configurationMap(field("compat")), field("api") as? String, { compat = false }, onSave = { change("compat", it); compat = false })
        if (remote) NativeRemoteCompaction(configurationMap(field("remoteCompaction")), { remote = false }) { change("remoteCompaction", it); remote = false }
}

private val compatBooleanLabels = linkedMapOf(
    "supportsStore" to "支持存储请求", "supportsDeveloperRole" to "支持 developer 角色", "supportsMultipleSystemMessages" to "允许多条系统消息", "supportsReasoningEffort" to "支持推理档位", "supportsUsageInStreaming" to "流式返回用量", "requiresToolResultName" to "工具结果包含名称", "requiresMistralToolIds" to "使用 Mistral 工具 ID", "requiresAssistantAfterToolResult" to "工具结果后追加助手消息", "requiresThinkingAsText" to "将思考作为文本发送", "requiresReasoningContentForToolCalls" to "工具调用必须带推理内容", "allowsSyntheticReasoningContentForToolCalls" to "允许补充推理内容", "requiresAssistantContentForToolCalls" to "工具调用必须带正文", "supportsToolChoice" to "支持工具选择", "supportsForcedToolChoice" to "支持强制指定工具", "disableReasoningOnForcedToolChoice" to "强制工具时关闭推理", "disableReasoningOnToolChoice" to "指定工具时关闭推理", "disableReasoningWithTools" to "携带工具时关闭推理", "qwenTemplateReasoningEffort" to "Qwen 模板包含推理档位", "supportsStrictMode" to "支持严格参数", "supportsLongPromptCacheRetention" to "支持长期缓存", "supportsReasoningParams" to "支持推理参数", "supportsReasoningSummary" to "支持推理概要", "statefulResponses" to "保留 Responses 状态", "alwaysSendMaxTokens" to "始终发送输出上限", "strictResponsesPairing" to "严格配对 Responses 工具结果", "supportsImageDetailOriginal" to "支持原图细节", "supportsConfigurationUpdate" to "支持配置更新", "supportsSteering" to "支持追加指令", "stripImageInput" to "移除图像输入", "supportsContextManagement" to "支持上下文管理", "supportsEagerToolInputStreaming" to "流式接收工具参数", "allowAnthropicHeaderOverrides" to "允许覆盖 Anthropic 请求头", "requiresToolResultId" to "工具结果包含 ID", "replayUnsignedThinking" to "重放无签名思考", "bedrockMessagesApi" to "使用 Bedrock Messages", "supportsThinkingBindingControls" to "支持思考绑定控制")
@Composable
private fun NativeCompatSheet(initial: Map<String, Any?>, api: String?, onDismiss: () -> Unit, onSave: (Map<String, Any?>) -> Unit, conditional: Boolean = false) {
    var draft by remember { mutableStateOf(initial) }
    var extra by remember { mutableStateOf<String?>(null) }
    var whenThinking by remember { mutableStateOf(false) }
    fun change(key: String, value: Any?) { draft = if (value == null) draft - key else draft + (key to value) }
    AgentSheet(if (conditional) "思考开启时" else "协议兼容项", onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            val anthropicKeys = setOf("supportsContextManagement", "supportsEagerToolInputStreaming", "allowAnthropicHeaderOverrides", "requiresToolResultId", "replayUnsignedThinking", "bedrockMessagesApi")
            compatBooleanLabels.filterKeys { key -> key in draft || when (api) { "anthropic-messages" -> key in anthropicKeys; "bedrock-converse-stream" -> key in setOf("supportsLongPromptCacheRetention", "supportsThinkingBindingControls"); else -> key !in anthropicKeys && key != "supportsThinkingBindingControls" } }.forEach { (key, label) -> NativeOptionalBoolean(label, draft[key] as? Boolean, { change(key, it) }) }
            val enumerations = linkedMapOf(
                "maxTokensField" to ("输出上限字段" to mapOf("max_completion_tokens" to "max_completion_tokens", "max_tokens" to "max_tokens")),
                "reasoningContentField" to ("推理内容字段" to listOf("reasoning_content", "reasoning", "reasoning_text").associateWith { it }),
                "thinkingFormat" to ("推理请求格式" to listOf("openai", "openrouter", "zai", "qwen", "qwen-chat-template").associateWith { it }),
                "toolStrictMode" to ("严格工具模式" to mapOf("all_strict" to "全部严格", "none" to "不使用严格模式")),
                "streamMarkupHealingPattern" to ("流式标记修复" to listOf("kimi", "dsml", "qwen", "thinking").associateWith { it }),
                "cacheControlFormat" to ("缓存控制格式" to mapOf("anthropic" to "Anthropic")),
                "promptCacheMode" to ("缓存模式" to mapOf("none" to "关闭", "automatic" to "自动", "explicit" to "显式")))
            enumerations.forEach { (key, pair) -> if (api != "bedrock-converse-stream" && key != "promptCacheMode" || api == "bedrock-converse-stream" && key == "promptCacheMode" || key in draft) NativeEnum(pair.first, draft[key] as? String, pair.second, { change(key, it) }) }
            NativeNumber("流式空闲超时 · 毫秒", draft["streamIdleTimeoutMs"], { change("streamIdleTimeoutMs", it) })
            if (api == "bedrock-converse-stream") {
                NativeNumber("缓存最小词元数", draft["promptCacheMinimumTokens"], { change("promptCacheMinimumTokens", it) })
                NativeNumber("缓存最多检查点", draft["promptCacheMaximumCheckpoints"], { change("promptCacheMaximumCheckpoints", it) })
            }
            ManagementGroup("推理档位映射") { NativeStringMap(configurationMap(draft["reasoningEffortMap"]), { change("reasoningEffortMap", it) }) }
            listOf("openRouterRouting" to "OpenRouter 路由", "vercelGatewayRouting" to "Vercel 路由", "extraBody" to "额外请求体").forEach { (key, label) -> ManagementRow(label, configurationDisplay(draft[key]), "data_object", { extra = key }) }
            if (!conditional) ManagementGroup("思考时的条件覆盖") { ManagementRow("思考开启时", "${configurationMap(draft["whenThinking"]).size} 项覆盖", "call_split", { whenThinking = true }) }
            Button({ onSave(draft) }, modifier = Modifier.fillMaxWidth().padding(top = 20.dp).height(48.dp)) { Text("完成") }
        }
    }
    extra?.let { key -> NativeObjectEditor(key, draft[key], { extra = null }) { change(key, it); extra = null } }
    if (whenThinking) NativeCompatSheet(configurationMap(draft["whenThinking"]), api, { whenThinking = false }, { change("whenThinking", it); whenThinking = false }, conditional = true)
}
@Composable
private fun NativeRemoteCompaction(initial: Map<String, Any?>, onDismiss: () -> Unit, onSave: (Map<String, Any?>) -> Unit) {
    var draft by remember { mutableStateOf(initial) }
    fun change(key: String, value: Any?) { draft = if (value == null) draft - key else draft + (key to value) }
    AgentSheet("远程压缩", onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            NativeOptionalBoolean("启用远程压缩", draft["enabled"] as? Boolean, { change("enabled", it) })
            NativeEnum("协议", draft["api"] as? String, nativeApis, { change("api", it) })
            listOf("endpoint" to "请求端点", "model" to "模型", "v2Endpoint" to "V2 端点", "streamingEndpoint" to "流式端点").forEach { (key, label) -> ManagementField(label, draft[key] as? String ?: "", { change(key, it.takeIf(String::isNotBlank)) }, mono = true) }
            NativeOptionalBoolean("V2 流式压缩", draft["v2StreamingEnabled"] as? Boolean, { change("v2StreamingEnabled", it) })
            Button({ onSave(draft) }, modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) { Text("完成") }
        }
    }
}
@Composable
private fun NativeObjectEditor(title: String, initial: Any?, onDismiss: () -> Unit, onSave: (Any?) -> Unit) {
    var draft by remember { mutableStateOf(configurationJson(initial ?: emptyMap<String, Any?>()).toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    AgentSheet(title, onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            ManagementField("JSON 对象", draft, { draft = it; error = null }, mono = true, multiline = true)
            error?.let { ManagementHint(it, "error", error = true) }
            Row(Modifier.padding(top = 20.dp)) { TextButton({ onSave(null) }) { Text("清除覆盖") }; Spacer(Modifier.weight(1f)); Button({ try { val value = configurationFromJson(Json.parseToJsonElement(draft)); require(value is Map<*, *>) { "需要 JSON 对象" }; onSave(value) } catch (failure: Exception) { error = failure.message ?: "JSON 无效" } }) { Text("完成") } }
        }
    }
}
