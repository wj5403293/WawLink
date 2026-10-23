package dev.wawlink.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.wawlink.protocol.*

@Composable
fun ManagementAdvisorsScreen(repository: AgentManagementRepository, configurationScope: ConfigurationScope,
    onScopeChange: (ConfigurationScope) -> Unit, onBack: () -> Unit, registerBack: ((() -> Boolean)?) -> Unit) {
    val state by repository.state.collectAsState()
    val actions = LocalManagementActions.current
    var selected by remember { mutableStateOf<ManagedAdvisor?>(null) }
    var editing by remember { mutableStateOf(false) }
    var instructions by remember { mutableStateOf(false) }
    var limit by remember { mutableStateOf(false) }
    var limitText by remember { mutableStateOf("") }
    var document by remember { mutableStateOf<ConfigurationDocument?>(null) }
    var editorBack by remember { mutableStateOf<(() -> Unit)?>(null) }
    val shared = state.advisorSharedByScope[configurationScope].orEmpty()
    val sharedInstructions = shared["instructions"] as? String ?: ""
    val sharedMax = (shared["maxNotesPerUpdate"] as? Number)?.toInt()
    val pageStates = rememberSaveableStateHolder()
    pageStates.SaveableStateProvider(if (editing) "edit:${selected?.path.orEmpty()}:${selected?.id.orEmpty()}" else "list") {
    if (editing) {
        AdvisorDefinitionEditor(repository, state, selected, configurationScope, onScopeChange, { editing = false; selected = null }, registerBack)
    } else {
        AgentManagementBack(registerBack) {
            when { instructions || document?.writable == true -> { editorBack?.invoke(); true }
                document != null -> { document = null; true }; limit -> { limit = false; true }; else -> false }
        }
        ManagementPage("顾问", onBack, actions = {
            ManagementScopeChip(configurationScope, onScopeChange, state.context.projectPath != null)
        }, fab = { ManagementFab("添加顾问", onClick = { selected = null; editing = true }) }) {
            ManagementGroup("共享配置") {
                ManagementRow("共享指令", sharedInstructions.ifBlank { "当前${configurationScope.label}层未设置指令" }, "description", { instructions = true }, index = 0, count = 2)
                ManagementRow("共享建议上限", sharedMax?.let { "当前层每次最多 $it 条" } ?: "当前层未覆盖", "format_list_numbered",
                    { limitText = sharedMax?.toString().orEmpty(); limit = true }, index = 1, count = 2)
            }
            val files = state.files.filter { it.path.substringAfterLast('/').equals("WATCHDOG.yml", true) || it.path.substringAfterLast('/').equals("WATCHDOG.yaml", true) }
            if (files.isNotEmpty()) ManagementGroup("原始 WATCHDOG") {
                files.forEachIndexed { index, file ->
                    ManagementRow(file.title, file.path, "data_object", { actions.launch { document = repository.readDocument(file.id) } }, index = index, count = files.size)
                }
            }
            ManagementGroup("顾问列表") {
                state.advisors.forEachIndexed { index, advisor ->
                    val enabled = advisor.configuration["enabled"] != false
                    ManagementRow(advisor.name, "${advisor.scope.label} · 已保存：${if (enabled) "启用" else "停用"}",
                        "support_agent", { selected = advisor; editing = true }, index = index, count = state.advisors.size,
                        detail = "运行：${advisor.runtimeStatus ?: "未连接实时运行状态"}", trailing = {
                            Switch(enabled, { checked -> actions.launch {
                                repository.saveAdvisor(advisor.scope, advisor.configuration + ("enabled" to checked), advisor)
                            } }, enabled = !actions.busy)
                        })
                }
                if (state.advisors.isEmpty()) ManagementHint("尚无顾问", "support_agent")
            }
            ManagementGroup("关联配置") {
                state.roles.filter { it.id == "advisor" }.forEach { ManagementRow("顾问角色", it.model ?: "未配置", "neurology") }
            }
        }
    }
    }
    if (instructions) {
        var instructionDraft by remember { mutableStateOf(sharedInstructions) }
        AgentSheet("共享指令", { instructions = false }) {
            ManagementField("", instructionDraft, { instructionDraft = it }, multiline = true)
            Button({ actions.launch { repository.updateAdvisorShared(configurationScope, instructionDraft, sharedMax); instructions = false } }, enabled = !actions.busy, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("保存") }
        }
    }
    if (limit) AgentSheet("共享建议上限", { limit = false }) {
        ManagementField("建议上限 · 条", limitText, { limitText = it })
        if (limitText.isNotBlank() && (limitText.toIntOrNull() ?: 0) < 1) ManagementHint("请输入正整数", error = true)
        Button({ actions.launch {
            val value = limitText.trim().takeIf { it.isNotEmpty() }?.let { requireNotNull(it.toIntOrNull()) { "请输入正整数" } }
            require(value == null || value >= 1) { "建议上限必须大于零" }
            repository.updateAdvisorShared(configurationScope, sharedInstructions, value); limit = false
        } }, enabled = !actions.busy, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("保存") }
    }
    document?.let { doc ->
        if (doc.writable) ManagementEditableCodeScreen("WATCHDOG · ${doc.path}", doc.content, "yaml", { document = null }, { editorBack = it }, error = actions.error, saving = actions.busy) { value ->
            actions.launch { repository.saveDocument(doc, value); document = null }
        } else CodeDetailScreen(doc.path, doc.content, "yaml") { document = null }
    }
}

@Composable
private fun AdvisorDefinitionEditor(repository: AgentManagementRepository, state: AgentManagementState, original: ManagedAdvisor?,
    defaultScope: ConfigurationScope, onScopeChange: (ConfigurationScope) -> Unit, onBack: () -> Unit,
    registerBack: ((() -> Boolean)?) -> Unit) {
    val actions = LocalManagementActions.current
    var configuration by remember { mutableStateOf(original?.configuration ?: mapOf<String, Any?>("enabled" to false)) }
    var baseline by remember { mutableStateOf(configuration) }
    var scope by remember { mutableStateOf(original?.scope ?: defaultScope) }
    var baselineScope by remember { mutableStateOf(scope) }
    var full by remember { mutableStateOf<String?>(null) }
    var modelPicker by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var editorBack by remember { mutableStateOf<(() -> Unit)?>(null) }
    val dirty = configuration != baseline || scope != baselineScope || original == null
    fun set(key: String, value: Any?) { configuration = if (value == null) configuration - key else configuration + (key to value) }
    fun leave() { if (dirty) discard = true else onBack() }
    AgentManagementBack(registerBack) {
        when { full != null -> { editorBack?.invoke(); true }; modelPicker -> { modelPicker = false; true }
            deleting -> { deleting = false; true }; discard -> { discard = false; true }; else -> { leave(); true } }
    }
    ManagementPage(original?.name ?: "添加顾问", { leave() }, actions = {
        if (original == null) ManagementScopeChip(scope, { scope = it; onScopeChange(it) }, state.context.projectPath != null)
        else Text(scope.label)
    }, footer = { ManagementSaveBar(dirty, actions.busy, { actions.launch("已保存顾问配置") {
        repository.saveAdvisor(scope, configuration, original)
        baseline = configuration; baselineScope = scope
        onBack()
    } }, { if (original == null) onBack() else { configuration = baseline; scope = baselineScope } }, actions.error,
        canSave = configuration["name"]?.toString()?.isNotBlank() == true && advisorPositiveInteger(configuration["reviewInterval"]) && advisorPositiveInteger(configuration["maxNotesPerUpdate"])) }) {
        ManagementGroup("状态") {
            ManagementLayers(listOf(
                ManagementLayer("已保存状态", if (original == null) "尚未保存" else if (original.configuration["enabled"] != false) "启用" else "停用"),
                ManagementLayer("运行状态", original?.runtimeStatus ?: "未连接实时运行状态"),
            ))
            if (configuration["enabled"] == true && (configuration["model"] as? String).isNullOrBlank()) ManagementBanner("未设置模型，沿用默认顾问角色", "link")
        }
        ManagementGroup("基本信息") {
            ManagementField("名称", configuration["name"]?.toString().orEmpty(), { set("name", it) })
            ManagementRow("启用", "修改的是保存配置，需点击保存", "power_settings_new", trailing = {
                Switch(configuration["enabled"] != false, { set("enabled", it) })
            })
            ManagementHint(original?.path ?: if (scope == ConfigurationScope.PROJECT) ".omp/WATCHDOG.yml" else "用户 Agent 目录 / WATCHDOG.yml", "description")
        }
        ManagementGroup("模型") {
            ManagementCandidates(configurationStrings(configuration["model"]), state, { candidates -> set("model", candidates.singleOrNull()) }, kind = "chat", maxCandidates = 1)
            ManagementRow("选择模型", configuration["model"]?.toString() ?: "沿用原生默认", "neurology", { modelPicker = true })
            if (configuration.containsKey("model")) TextButton(onClick = { set("model", null) }) { Text("恢复默认模型") }
        }
        ManagementGroup("工具策略") {
            ManagementSegments(listOf("default" to "默认工具", "list" to "指定列表"), if (configuration.containsKey("tools")) "list" else "default",
                { set("tools", if (it == "default") null else configurationStrings(configuration["tools"])) })
            if (configuration.containsKey("tools")) {
                AgentCatalogueSelection("允许的工具", state.tools, configurationStrings(configuration["tools"])) { set("tools", it) }
                if (configurationStrings(configuration["tools"]).isEmpty()) ManagementBanner("显式空列表：顾问不使用默认工具。", "block", warning = true)
            }
        }
        ManagementGroup("专属指令", action = { TextButton(onClick = { full = "instructions" }) { Text("全屏") } }) {
            ManagementField("Markdown", configuration["instructions"]?.toString().orEmpty(), { set("instructions", it) }, multiline = true)
        }
        ManagementGroup("审查时机") {
            ManagementSegments(listOf("turn" to "每轮结束", "agent-end" to "Agent 结束"), configuration["reviewMode"]?.toString() ?: "turn", { set("reviewMode", it) })
            AdvisorIntegerField("审查间隔（轮）", configuration["reviewInterval"], { set("reviewInterval", it) })
        }
        ManagementGroup("同步积压处理") {
            AgentChoiceChips(listOf("off" to "不等待", "1" to "积压 1 轮", "3" to "积压 3 轮", "5" to "积压 5 轮", "strict" to "严格同步"),
                configuration["syncBacklog"]?.toString() ?: "off", { set("syncBacklog", it) })
        }
        ManagementGroup("建议上限") {
            AdvisorIntegerField("每次更新的建议数（空白沿用共享）", configuration["maxNotesPerUpdate"], { set("maxNotesPerUpdate", it) })
        }
        ManagementGroup("完整配置", action = { TextButton(onClick = { full = "configuration" }) { Text("编辑 JSON") } }) {
            configuration.filterKeys { it !in advisorKnownFields }.forEach { (key, value) -> ManagementRow(key, configurationDisplay(value), "data_object") }
        }
        if (original != null) TextButton(onClick = { deleting = true }) { Text("删除顾问", color = MaterialTheme.colorScheme.error) }
    }
    if (modelPicker) ManagementModelPicker(state, configuration["model"] as? String, kind = "chat", onDismiss = { modelPicker = false }, onPick = { set("model", it); modelPicker = false })
    full?.let { which -> ManagementEditableCodeScreen(if (which == "instructions") "专属指令" else "完整顾问配置",
        if (which == "instructions") configuration["instructions"]?.toString().orEmpty() else agentJson(configuration),
        if (which == "instructions") "markdown" else "json", { full = null }, { editorBack = it }, error = actions.error, saving = actions.busy) { text ->
        actions.launch {
            if (which == "instructions") set("instructions", text)
            else { val value = agentParseJson(text); require(value is Map<*, *>) { "配置必须为 JSON 对象" }; configuration = configurationMap(value) }
            full = null
        }
    } }
    if (discard) AgentDiscardDialog({ discard = false }, { discard = false; onBack() })
    if (deleting && original != null) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("删除顾问？") },
        text = { Text("将从 ${original.path} 删除 ${original.name} 的配置，其他顾问和共享指令保留。") },
        confirmButton = { TextButton(onClick = { actions.launch { repository.deleteAdvisor(original); deleting = false; onBack() } }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } })
}

private val advisorKnownFields = setOf("name", "enabled", "model", "tools", "instructions", "reviewMode", "reviewInterval", "syncBacklog", "maxNotesPerUpdate")

@Composable
private fun AdvisorIntegerField(label: String, value: Any?, onChange: (Any?) -> Unit) {
    var text by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    ManagementField(label, text, {
        text = it
        onChange(it.takeIf { text -> text.isNotBlank() }?.let { text -> text.toIntOrNull() ?: text })
    })
    if (text.isNotBlank() && (text.toIntOrNull() ?: 0) < 1) ManagementHint("请输入正整数；无效配置无法保存。", error = true)
}

private fun advisorPositiveInteger(value: Any?): Boolean = value == null ||
    (value is Number && value.toDouble().isFinite() && value.toDouble() >= 1 && value.toDouble() % 1.0 == 0.0)
