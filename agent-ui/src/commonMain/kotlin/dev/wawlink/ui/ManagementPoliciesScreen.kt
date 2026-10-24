@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.wawlink.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.wawlink.protocol.*
import kotlin.math.abs

private data class ManagementPresetEntry(val name: String, val source: String, val value: Map<String, Any?>, val shadowed: Boolean)

@Composable
fun ManagementPoliciesScreen(
    repository: AgentManagementRepository,
    configurationScope: ConfigurationScope,
    onScopeChange: (ConfigurationScope) -> Unit,
    onBack: () -> Unit,
    registerBack: ((() -> Boolean)?) -> Unit,
    section: String,
) {
    val state by repository.state.collectAsState()
    val actions = LocalManagementActions.current
    val pageStates = rememberSaveableStateHolder()
    val scope = if (section == "fallback") ConfigurationScope.GLOBAL else configurationScope
    val hasProject = state.context.projectPath != null
    val canEdit = scope != ConfigurationScope.PROJECT || hasProject
    val layer = if (scope == ConfigurationScope.GLOBAL) state.globalValues else state.projectValues
    val pending = remember(repository, scope, section) { mutableStateMapOf<String, Any?>() }
    var chainKey by rememberSaveable(section) { mutableStateOf<String?>(null) }
    var addChain by rememberSaveable { mutableStateOf(false) }
    var targetKind by rememberSaveable { mutableStateOf("role") }
    var rawTarget by rememberSaveable { mutableStateOf("") }
    var chooseModel by rememberSaveable { mutableStateOf(false) }
    var deleteChain by rememberSaveable { mutableStateOf(false) }
    var editingSetting by rememberSaveable { mutableStateOf<String?>(null) }
    var ruleSetting by rememberSaveable { mutableStateOf<String?>(null) }
    var ruleIndex by rememberSaveable { mutableStateOf(-1) }
    var presetName by rememberSaveable { mutableStateOf<String?>(null) }
    var presetSource by rememberSaveable { mutableStateOf<String?>(null) }
    var savePreset by rememberSaveable { mutableStateOf(false) }
    var newPresetName by rememberSaveable { mutableStateOf("") }
    var overwritePreset by rememberSaveable { mutableStateOf(false) }
    var deletePreset by rememberSaveable { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var nextScope by remember { mutableStateOf<ConfigurationScope?>(null) }
    var sourcePicker by rememberSaveable { mutableStateOf(false) }
    var sourceDocument by remember { mutableStateOf<ConfigurationDocument?>(null) }
    val registry = state.categories.flatMap { it.groups }.flatMap { it.items }
    fun value(id: String): Any? = if (pending.containsKey(id)) pending[id]
        ?: (if (scope == ConfigurationScope.PROJECT) configurationValue(state.globalValues, id) else null)
        ?: configurationValue(state.defaultValues, id) ?: registry.firstOrNull { it.id == id }?.defaultValue
        else configurationValue(state.effectiveValues, id) ?: registry.firstOrNull { it.id == id }?.value
    fun rawList(id: String): List<Any?> = ((if (pending.containsKey(id)) value(id) else configurationValue(layer, id) ?: configurationValue(state.effectiveValues, id)) as? List<*>)?.toList().orEmpty()
    fun chains(): Map<String, Any?> = configurationMap(value("retry.fallbackChains"))
    fun putChain(key: String, candidates: List<String>?) {
        val updated = chains().toMutableMap()
        if (candidates == null) updated.remove(key) else updated[key] = candidates
        pending["retry.fallbackChains"] = updated
    }
    fun back(): Boolean = when {
        discard -> { discard = false; nextScope = null; true }
        deleteChain -> { deleteChain = false; true }
        deletePreset -> { deletePreset = false; true }
        overwritePreset -> { overwritePreset = false; true }
        sourceDocument != null -> { sourceDocument = null; true }
        sourcePicker -> { sourcePicker = false; true }
        ruleSetting != null -> { ruleSetting = null; true }
        editingSetting != null -> { editingSetting = null; true }
        chooseModel -> { chooseModel = false; true }
        addChain -> { addChain = false; true }
        chainKey != null -> { chainKey = null; true }
        presetName != null -> { presetName = null; true }
        savePreset -> { savePreset = false; true }
        pending.isNotEmpty() -> { discard = true; true }
        else -> false
    }
    DisposableEffect(registerBack, chainKey, presetName, pending.toMap(), addChain, editingSetting, ruleSetting, savePreset, sourceDocument, sourcePicker, chooseModel) {
        registerBack(::back); onDispose { registerBack(null) }
    }
    val globalPresets = configurationMap(configurationValue(state.globalValues, "modelPresets"))
    val projectPresets = configurationMap(configurationValue(state.projectValues, "modelPresets"))
    val effectivePresets = configurationMap(configurationValue(state.effectiveValues, "modelPresets"))
    val presets = buildList {
        globalPresets.forEach { (name, preset) -> add(ManagementPresetEntry(name, "global", configurationMap(preset), projectPresets.containsKey(name) || effectivePresets[name] != preset)) }
        projectPresets.forEach { (name, preset) -> add(ManagementPresetEntry(name, "project", configurationMap(preset), effectivePresets[name] != preset)) }
        effectivePresets.filterKeys { it !in globalPresets && it !in projectPresets }.forEach { (name, preset) -> add(ManagementPresetEntry(name, state.provenance["modelPresets.$name"] ?: "runtime", configurationMap(preset), false)) }
    }.sortedWith(compareBy<ManagementPresetEntry> { it.name }.thenBy { it.source })
    val preset = presets.firstOrNull { it.name == presetName && it.source == presetSource }
    val title = when (section) { "presets" -> "模型预设"; "allow" -> "允许范围与快速切换"; else -> chainKey ?: "故障转移" }
    val source = sourceDocument
    if (source != null) {
        EditableCodeScreen(source.path, source.content, "yaml", { sourceDocument = null }) { content ->
            actions.launch("源文件已保存") { repository.saveDocument(source, content); sourceDocument = null }
        }
        return
    }
    pageStates.SaveableStateProvider("$section/${chainKey ?: presetName ?: "list"}/${presetSource ?: ""}") {
        ManagementPage(title, { if (!back()) onBack() }, actions = {
            if (section != "presets") ManagementScopeChip(scope, { requested ->
                if (pending.isEmpty()) onScopeChange(requested) else { nextScope = requested; discard = true }
            }, hasProject, globalOnly = section == "fallback")
            if (chainKey != null) AgentIconButton("delete", "删除规则", { deleteChain = true })
        }, fab = when {
            section == "presets" && presetName == null -> ({ ManagementFab("保存当前组合", "bookmark_add", { savePreset = true }) })
            section == "fallback" && chainKey == null && value("retry.modelFallback") == true -> ({ Box {
                ManagementFab("添加规则", onClick = { targetKind = ""; addChain = true })
                ManagementMenu(addChain, { addChain = false }) {
                    fun add(key: String) { if (!chains().containsKey(key)) putChain(key, emptyList()); chainKey = key; addChain = false }
                    when (targetKind) {
                        "role" -> state.roles.forEach { role -> ManagementMenuItem(text = { Text(role.name) }, leadingIcon = { Symbol("label") }, onClick = { add(role.id) }) }
                        "provider" -> (state.models.map { it.provider } + state.authProviders.map { it.id }).distinct().sorted().forEach { provider -> ManagementMenuItem(text = { Text(provider) }, leadingIcon = { Symbol("deployed_code") }, onClick = { add("$provider/*") }) }
                        else -> {
                            ManagementMenuItem(text = { Text("为角色") }, leadingIcon = { Symbol("label") }, onClick = { targetKind = "role" })
                            ManagementMenuItem(text = { Text("为具体模型") }, leadingIcon = { Symbol("deployed_code") }, onClick = { addChain = false; chooseModel = true })
                            ManagementMenuItem(text = { Text("为整个服务") }, leadingIcon = { Symbol("deployed_code") }, onClick = { targetKind = "provider" })
                        }
                    }
                }
            } })
            else -> null
        }, footer = if (section != "presets") ({
            ManagementSaveBar(pending.isNotEmpty(), actions.busy, canSave = canEdit, onSave = {
                if (canEdit && pending.isNotEmpty()) {
                    val changes = pending.toMap()
                    actions.launch("已保存${scope.label}配置") {
                        repository.updateSettings(scope, changes)
                        changes.forEach { (id, saved) -> if (pending[id] == saved) pending.remove(id) }
                    }
                }
            }, onDiscard = { pending.clear() }, error = actions.error)
        }) else null) {
            state.error?.let { ManagementBanner(it, error = true) }
            actions.error?.let { ManagementBanner(it, error = true) }
            if (!canEdit) ManagementBanner("未打开项目，项目层不可编辑。", warning = true)
            when (section) {
                "presets" -> {
                        ManagementGroup("保存的组合") {
                            if (presets.isEmpty()) ManagementHint("尚无模型预设。保存当前配置可创建。", "bookmark")
                            presets.forEachIndexed { index, entry ->
                                ManagementRow(entry.name, "${managementSourceLabel(entry.source)} · ${configurationMap(entry.value["modelRoles"]).size} 个角色 · ${managementThinkingLabel(entry.value["defaultThinkingLevel"] as? String)}${if (entry.shadowed) " · 被同名高层预设遮蔽" else ""}", "bookmark", onClick = { presetName = entry.name; presetSource = entry.source }, index = index, count = presets.size)
                            }
                        }
                }
                "allow" -> {
                    listOf("enabledModels" to "允许的模型", "enabledProviders" to "启用的服务", "disabledProviders" to "停用的服务").forEach { (id, label) ->
                        val rules = rawList(id)
                        ManagementGroup(label, action = { TextButton(enabled = canEdit, onClick = { ruleSetting = id; ruleIndex = -1 }) { Text("添加规则") } }) {
                            if (rules.isEmpty()) ManagementHint(if (id == "disabledProviders") "没有额外停用的服务" else "列表为空，不额外限制", "all_inclusive")
                            rules.forEachIndexed { index, rule ->
                                val map = configurationMap(rule)
                                val subtitle = if (rule is String) "所有路径" else "路径：${configurationDisplay(map["path"] ?: map["paths"] ?: map["pathPrefix"] ?: map["pathPrefixes"])}"
                                val text = if (rule is String) rule else configurationDisplay(map["values"] ?: map["items"] ?: map[if (id == "enabledModels") "models" else "providers"])
                                ManagementRow(text, subtitle, "filter_alt", onClick = { ruleSetting = id; ruleIndex = index }, index = index, count = rules.size, enabled = canEdit,
                                    trailing = { AgentIconButton("remove_circle", "移除规则", { if (canEdit) pending[id] = rules.toMutableList().apply { removeAt(index) } }) })
                            }
                            if (configurationValue(layer, id) != null || pending.containsKey(id)) TextButton(enabled = canEdit, onClick = { pending[id] = null }) { Text("清除此层覆盖，恢复继承") }
                        }
                    }
                    val providers = (state.models.map { it.provider } + state.authProviders.map { it.id }).distinct().sorted()
                    ManagementGroup("服务优先顺序") {
                        val order = configurationStrings(value("modelProviderOrder"))
                        ManagementOrderedValues(order, onChange = { pending["modelProviderOrder"] = it }, enabled = canEdit)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { providers.filterNot { it in order }.forEach { provider -> ManagementChip("+ $provider", false, { pending["modelProviderOrder"] = order + provider }, enabled = canEdit) } }
                        ManagementHint("同名模型在多个服务都可用时，按此顺序选择", "swap_vert")
                    }
                    ManagementGroup("快速轮换的角色") {
                        val cycle = configurationStrings(value("cycleOrder"))
                        ManagementOrderedValues(cycle, labels = cycle.associateWith { managementRoleName(state, it) }, onChange = { pending["cycleOrder"] = it }, enabled = canEdit)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { state.roles.filterNot { it.id in cycle }.forEach { role -> ManagementChip("+ ${role.name}", false, { pending["cycleOrder"] = cycle + role.id }, enabled = canEdit) } }
                    }
                }
                else -> {
                    if (chainKey != null) {
                        val key = chainKey!!
                        val role = state.roles.firstOrNull { it.id == key }
                        ManagementLayers(listOf(ManagementLayer("原始规则", key, "code"), ManagementLayer("规则类型", if (!key.contains('/')) "角色" else if (key.endsWith("/*")) "服务或模型前缀通配符" else "具体模型", "label")) + if (role != null) listOf(ManagementLayer("当前解析", role.model ?: role.error ?: "未解析", "done_all", error = role.error != null)) else emptyList())
                        ManagementGroup("备选顺序") { ManagementCandidates(configurationStrings(chains()[key]), state, { putChain(key, it) }, readOnly = !canEdit) }
                    } else {
                        val enabled = value("retry.modelFallback") == true
                        PolicySettingRow(registry.firstOrNull { it.id == "retry.modelFallback" }, value("retry.modelFallback"), canEdit, { pending["retry.modelFallback"] = it }, { editingSetting = "retry.modelFallback" })
                        AnimatedVisibility(enabled) {
                            Column {
                                ManagementGroup("故障转移规则", action = { TextButton(onClick = { addChain = true }) { Text("添加") } }) {
                                    if (chains().isEmpty()) ManagementHint("尚无自定义故障转移链")
                                    chains().entries.forEachIndexed { index, (key, candidates) ->
                                        val list = configurationStrings(candidates)
                                        ManagementRow(managementRoleName(state, key), "${list.size} 个备选${list.firstOrNull()?.let { " · 首选 $it" } ?: " · 显式禁用此目标"}", if (key.contains('/')) "deployed_code" else "label", onClick = { chainKey = key }, index = index, count = chains().size)
                                    }
                                }
                            }
                        }
                        val groups = listOf(
                            "触发条件" to listOf("retry.fallbackOn"),
                            "重试与等待" to listOf("retry.enabled", "retry.maxRetries", "retry.baseDelayMs", "retry.maxDelayMs", "retry.waitForUsageReset", "retry.fallbackRevertPolicy"),
                            "额度预警" to listOf("retry.usageAwareFallback", "retry.usageReservePct", "retry.usageReservePolicy"),
                        )
                        groups.forEach { (label, ids) ->
                            ManagementGroup(label) {
                                ids.mapNotNull { id -> registry.firstOrNull { it.id == id } }.forEach { item ->
                                    PolicySettingRow(item, value(item.id), canEdit, { pending[item.id] = it }, { editingSetting = item.id })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (preset != null) AgentSheet(preset.name, { presetName = null }) {
        Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState())) {
                        ManagementBanner("来源：${managementSourceLabel(preset.source)}${if (preset.shadowed) "；被同名高层预设遮蔽，不能直接应用此版本" else ""}", "layers", warning = preset.shadowed)
                        val roles = configurationMap(preset.value["modelRoles"])
                        val presetInvalid = preset.value["modelRoles"] !is Map<*, *> || roles.values.any { it !is String || it.isBlank() }
                        if (presetInvalid) ManagementBanner("预设的 modelRoles 必须是非空选择器字符串映射。请编辑源文件修复；原生应用不会写入无效预设。", error = true)
                        val current = configurationMap(configurationValue(state.effectiveValues, "modelRoles"))
                        ManagementGroup("与当前有效配置的差异") {
                            val ids = (roles.keys + current.keys).distinct()
                            ManagementLayers(ids.map { id ->
                                val before = current[id]; val after = roles[id]
                                ManagementLayer(managementRoleName(state, id), if (before == after) "无变化 · ${configurationDisplay(after)}" else "${configurationDisplay(before)} → ${configurationDisplay(after ?: "移除绑定")}", "label", selected = before != after)
                            } + listOf(ManagementLayer("默认思考", if (preset.value.containsKey("defaultThinkingLevel")) "${managementThinkingLabel(configurationValue(state.effectiveValues, "defaultThinkingLevel") as? String)} → ${managementThinkingLabel(preset.value["defaultThinkingLevel"] as? String)}" else "保持当前值", "lightbulb", selected = preset.value.containsKey("defaultThinkingLevel") && preset.value["defaultThinkingLevel"] != configurationValue(state.effectiveValues, "defaultThinkingLevel"))))
                        }
                        val roleStorage = configurationValue(state.effectiveValues, "modelRoleStorage") as? String ?: "global"
                        val blocked = if (roleStorage == "global") roles.keys.filter { configurationMap(configurationValue(state.projectValues, "modelRoles")).containsKey(it) } else emptyList()
                        if (blocked.isNotEmpty()) ManagementBanner("应用到全局后仍可能被项目覆盖：${blocked.joinToString { managementRoleName(state, it) }}。原生操作会报告实际覆盖或解析错误。", warning = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !presetInvalid && !preset.shadowed && !actions.busy && (roleStorage != "project" || hasProject), onClick = {
                                actions.launch("预设已应用") { repository.applyPreset(preset.name) }
                            }) { Text("应用") }
                            if (preset.source == "global") TextButton(onClick = { deletePreset = true }, enabled = !actions.busy) { Text("删除", color = MaterialTheme.colorScheme.error) }
                            TextButton(onClick = { sourcePicker = true }) { Text("编辑源文件") }
                        }
        }
    }
    registry.firstOrNull { it.id == editingSetting }?.let { item ->
        val staged = if (pending.containsKey(item.id)) item.copy(value = value(item.id)) else item
        val editorState = if (pending.isEmpty()) state else state.copy(effectiveValues = state.effectiveValues + pending)
        ManagementSettingEditor(staged, editorState, scope, { editingSetting = null }) { next -> pending[item.id] = next; editingSetting = null }
    }
    ruleSetting?.let { id ->
        val rules = rawList(id)
        ManagementAllowRuleEditor(id, rules.getOrNull(ruleIndex), state, onDismiss = { ruleSetting = null }, onSave = { replacement ->
            pending[id] = rules.toMutableList().apply {
                if (ruleIndex in indices) { removeAt(ruleIndex); addAll(ruleIndex, replacement) } else addAll(replacement)
            }
            ruleSetting = null
        })
    }
    if (chooseModel) ManagementModelPicker(state, allowRole = false, allowRaw = false, onDismiss = { chooseModel = false }, onPick = { if (!chains().containsKey(it)) putChain(it, emptyList()); chainKey = it; chooseModel = false })
    if (deleteChain) AlertDialog(onDismissRequest = { deleteChain = false }, title = { Text("删除规则") }, text = { Text("此目标将不再拥有独立故障转移链。非专用角色可能恢复继承 default 链。保存后生效。") }, confirmButton = { TextButton(onClick = { chainKey?.let { putChain(it, null) }; chainKey = null; deleteChain = false }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleteChain = false }) { Text("取消") } })
    if (savePreset) AgentSheet("保存当前组合", { savePreset = false }) {
        ManagementField("名称", newPresetName, { newPresetName = it })
        ManagementHint("保存到全局，包含角色绑定和默认思考强度", "public")
        if (projectPresets.containsKey(newPresetName.trim())) ManagementBanner("项目有同名预设。全局保存后仍会被项目遮蔽。", warning = true)
        Button(enabled = Regex("^[a-zA-Z][\\w-]*$").matches(newPresetName.trim()) && !actions.busy, onClick = {
            val name = newPresetName.trim()
            if (globalPresets.containsKey(name)) overwritePreset = true else actions.launch("预设已保存") { repository.savePreset(name); savePreset = false; newPresetName = "" }
        }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("保存") }
    }
    if (overwritePreset) AlertDialog(onDismissRequest = { overwritePreset = false }, title = { Text("覆盖预设") }, text = { Text("全局已有“${newPresetName.trim()}”，覆盖后原内容将丢失。项目同名预设不受影响。") }, confirmButton = { TextButton(enabled = !actions.busy, onClick = {
        val name = newPresetName.trim()
        actions.launch("预设已覆盖") { repository.savePreset(name); overwritePreset = false; savePreset = false; newPresetName = "" }
    }) { Text("覆盖") } }, dismissButton = { TextButton(onClick = { overwritePreset = false }) { Text("取消") } })
    if (deletePreset && preset != null) AlertDialog(onDismissRequest = { deletePreset = false }, title = { Text("删除全局预设") }, text = { Text(if (projectPresets.containsKey(preset.name)) "删除后，项目同名预设仍然存在并继续生效。" else "删除后无法恢复；当前角色绑定不会改变。") }, confirmButton = { TextButton(enabled = !actions.busy, onClick = { actions.launch("全局预设已删除") { repository.deletePreset(preset.name); deletePreset = false; presetName = null } }) { Text("删除", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { deletePreset = false }) { Text("取消") } })
    if (sourcePicker) AlertDialog(onDismissRequest = { sourcePicker = false }, title = { Text("配置源文件") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) { state.files.forEach { file ->
            ManagementRow(file.title, "${file.path}${if (!file.writable) " · 只读" else ""}", "description", enabled = file.writable, onClick = { actions.launch { sourceDocument = repository.readDocument(file.id); sourcePicker = false } })
        } }
    }, confirmButton = { TextButton(onClick = { sourcePicker = false }) { Text("关闭") } })
    if (discard) AlertDialog(onDismissRequest = { discard = false; nextScope = null }, title = { Text("放弃未保存的配置？") }, text = { Text("草稿尚未写入。操作失败时可以保留草稿重试。") }, confirmButton = { TextButton(onClick = { pending.clear(); discard = false; val requested = nextScope; nextScope = null; if (requested != null) onScopeChange(requested) else onBack() }) { Text("放弃") } }, dismissButton = { TextButton(onClick = { discard = false; nextScope = null }) { Text("继续编辑") } })
}

@Composable
private fun PolicySettingRow(item: SettingItem?, value: Any?, enabled: Boolean, onValue: (Any?) -> Unit, onEdit: () -> Unit) {
    if (item == null) return
    val label = item.options.firstOrNull { it.value == value }?.label ?: configurationDisplay(value)
    ManagementRow(item.title, "$label · ${item.id}", item.icon, onClick = onEdit, trailing = if (item.type == SettingType.BOOLEAN) ({ Switch(value == true, { onValue(it) }, enabled = enabled) }) else null)
}

/** Concrete native ordered list controls; dragging never turns IDs into display labels. */
@Composable
internal fun ManagementOrderedValues(values: List<String>, labels: Map<String, String> = emptyMap(), enabled: Boolean = true, onChange: (List<String>) -> Unit) {
    val currentValues by rememberUpdatedState(values)
    val change by rememberUpdatedState(onChange)
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    values.forEachIndexed { index, value ->
        key(value, values.take(index).count { it == value }) {
        ManagementRow(labels[value] ?: value, if (labels.containsKey(value)) value else "", "drag_indicator", index = index, count = values.size,
            lead = {
                var position by remember { mutableIntStateOf(index) }
                var distance by remember { mutableFloatStateOf(0f) }
                Box(Modifier.size(36.dp).pointerInput(enabled, value, threshold) {
                    if (enabled) detectDragGesturesAfterLongPress(onDragStart = { position = currentValues.indexOf(value); distance = 0f }, onDrag = { event, delta ->
                        event.consume(); distance += delta.y
                        if (abs(distance) >= threshold) {
                            val next = (position + if (distance > 0) 1 else -1).coerceIn(0, currentValues.lastIndex)
                            if (next != position && position in currentValues.indices) {
                                change(currentValues.toMutableList().apply { add(next, removeAt(position)) }); position = next
                            }
                            distance = 0f
                        }
                    })
                }) { Symbol("drag_indicator") }
            }, trailing = {
                Row {
                    IconButton(enabled = enabled && index > 0, onClick = { onChange(values.toMutableList().apply { add(index - 1, removeAt(index)) }) }) { Symbol("arrow_upward", contentDescription = "上移") }
                    IconButton(enabled = enabled && index < values.lastIndex, onClick = { onChange(values.toMutableList().apply { add(index + 1, removeAt(index)) }) }) { Symbol("arrow_downward", contentDescription = "下移") }
                    IconButton(enabled = enabled, onClick = { onChange(values.toMutableList().apply { removeAt(index) }) }) { Symbol("remove_circle", contentDescription = "移除") }
                }
            })
        }
    }
}

@Composable
private fun ManagementAllowRuleEditor(id: String, original: Any?, state: AgentManagementState, onDismiss: () -> Unit, onSave: (List<Any>) -> Unit) {
    val map = configurationMap(original)
    fun entries(value: Any?) = configurationStrings(value)
    var scoped by remember { mutableStateOf(original is Map<*, *>) }
    var paths by remember { mutableStateOf(listOf("path", "paths", "pathPrefix", "pathPrefixes").flatMap { entries(map[it]) }) }
    var values by remember { mutableStateOf(if (original is String) listOf(original) else listOf("values", "items", if (id == "enabledModels") "models" else "providers").flatMap { entries(map[it]) }) }
    var newPath by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }
    var picker by remember { mutableStateOf(false) }
    AgentSheet(if (id == "enabledModels") "添加允许规则" else "服务范围规则", onDismiss) {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ManagementRow("仅匹配路径前缀", "匹配当前工作目录；全路径规则不需要此开关", "folder", trailing = { Switch(scoped, { scoped = it }) })
            if (scoped) {
                ManagementOrderedValues(paths, onChange = { paths = it })
                ManagementField("新路径前缀", newPath, { newPath = it }, mono = true)
                TextButton(enabled = newPath.isNotBlank(), onClick = { paths = paths + newPath.trim(); newPath = "" }) { Text("添加路径") }
            }
            ManagementOrderedValues(values, onChange = { values = it })
            if (id == "enabledModels") {
                TextButton(onClick = { picker = true }) { Text("从模型目录选择") }
                ManagementField("模型匹配规则（如 provider/model*）", newValue, { newValue = it }, mono = true)
                TextButton(enabled = newValue.isNotBlank(), onClick = { values = values + newValue.trim(); newValue = "" }) { Text("添加规则") }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (state.models.map { it.provider } + state.authProviders.map { it.id }).distinct().sorted().filterNot { it in values }.forEach { provider -> ManagementChip(provider, false, { values = values + provider }) }
                }
                ManagementField("其他原生服务 ID", newValue, { newValue = it }, mono = true)
                TextButton(enabled = newValue.isNotBlank(), onClick = { values = values + newValue.trim(); newValue = "" }) { Text("添加服务") }
            }
        }
        Button(enabled = (values.isNotEmpty() || newValue.isNotBlank()) && (!scoped || paths.isNotEmpty()), onClick = {
        if (newValue.isNotBlank()) values = values + newValue.trim()
        if (!scoped) onSave(values)
        else onSave(listOf(map.toMutableMap().apply {
            listOf("path", "paths", "pathPrefix", "pathPrefixes", "values", "items", "models", "providers").forEach(::remove)
            this["paths"] = paths; this["values"] = values
        }))
        }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("完成") }
    }
    if (picker) ManagementModelPicker(state, allowRole = true, allowRaw = true, onDismiss = { picker = false }, onPick = { values = values + it; picker = false })
}
