@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.wawlink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.wawlink.protocol.*
import kotlin.random.Random

internal val managementBuiltinRoles = setOf("default", "smol", "slow", "vision", "plan", "commit", "tiny", "memory", "task", "advisor", "widget", "image", "web", "speech", "dictation", "judge")
internal fun managementRoleCandidates(value: Any?): List<String> = when (value) {
    is String -> value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    is List<*> -> value.filterIsInstance<String>().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
    else -> emptyList()
}
internal fun managementRoleName(state: AgentManagementState, id: String): String = state.roles.firstOrNull { it.id == id }?.name ?: id

@Composable
fun ManagementRolesScreen(repository: AgentManagementRepository, onBack: () -> Unit, registerBack: ((() -> Boolean)?) -> Unit) {
    val state by repository.state.collectAsState()
    val actions = LocalManagementActions.current
    val session = LocalManagementSession.current
    val currentSession = session?.state?.collectAsState()?.value
    val pageStates = rememberSaveableStateHolder()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var draftId by rememberSaveable { mutableStateOf("") }
    var draftName by rememberSaveable { mutableStateOf("") }
    var draftColor by rememberSaveable { mutableStateOf("") }
    var draftHidden by rememberSaveable { mutableStateOf(false) }
    var draftCandidates by remember { mutableStateOf<List<String>>(emptyList()) }
    var draftInherited by rememberSaveable { mutableStateOf(true) }
    var initialDraft by remember { mutableStateOf<List<Any?>>(emptyList()) }
    var preview by remember { mutableStateOf<ManagedRole?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var clearPreview by remember { mutableStateOf<ManagedRole?>(null) }
    var clearing by rememberSaveable { mutableStateOf(false) }
    var deleteDialog by rememberSaveable { mutableStateOf(false) }
    var discardDialog by rememberSaveable { mutableStateOf(false) }
    var storageDialog by rememberSaveable { mutableStateOf(false) }
    var showCycle by rememberSaveable { mutableStateOf(false) }
    var cycleScope by rememberSaveable { mutableStateOf(ConfigurationScope.GLOBAL) }
    val storage = if (configurationValue(state.effectiveValues, "modelRoleStorage") == "project") ConfigurationScope.PROJECT else ConfigurationScope.GLOBAL
    val hasProject = state.context.projectPath != null
    val canEdit = storage != ConfigurationScope.PROJECT || hasProject
    val layer = if (storage == ConfigurationScope.GLOBAL) state.globalValues else state.projectValues
    val selected = state.roles.firstOrNull { it.id == selectedId }
    val tags = configurationMap(configurationValue(state.effectiveValues, "modelTags"))
    fun signature() = listOf(draftId, draftName, draftColor, draftHidden, draftCandidates, draftInherited)
    val dirty = selectedId != null && (creating || signature() != initialDraft)
    fun open(id: String, new: Boolean = false) {
        val role = state.roles.firstOrNull { it.id == id }
        val tag = configurationMap(tags[id])
        val selector = configurationMap(configurationValue(layer, "modelRoles"))[id]
        selectedId = id; creating = new; draftId = id
        draftName = tag["name"] as? String ?: role?.name ?: "新角色"
        draftColor = tag["color"] as? String ?: ""
        draftHidden = tag["hidden"] == true
        draftCandidates = managementRoleCandidates(selector)
        draftInherited = selector == null
        initialDraft = signature(); preview = role; previewError = null
    }
    fun close() { selectedId = null; creating = false; preview = null; clearPreview = null }
    fun back(): Boolean = when {
        showCycle -> { showCycle = false; true }
        storageDialog -> { storageDialog = false; true }
        clearPreview != null || clearing -> { clearPreview = null; clearing = false; true }
        deleteDialog -> { deleteDialog = false; true }
        discardDialog -> { discardDialog = false; true }
        selectedId != null -> { if (dirty) discardDialog = true else close(); true }
        else -> false
    }
    DisposableEffect(registerBack, selectedId, dirty, showCycle, storageDialog, clearPreview, clearing, deleteDialog, discardDialog) {
        registerBack(::back); onDispose { registerBack(null) }
    }
    LaunchedEffect(selectedId, draftId, storage, draftCandidates, draftInherited) {
        if (selectedId != null && canEdit) {
            try {
                preview = repository.previewRole(draftId, storage, if (draftInherited) null else draftCandidates.joinToString(","))
                previewError = null
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { previewError = e.message ?: "解析失败" }
        }
    }
    if (showCycle) {
        ManagementPoliciesScreen(repository, cycleScope, { cycleScope = it }, { showCycle = false }, { handler ->
            registerBack(handler?.let { child -> { if (child()) true else { showCycle = false; true } } })
        }, "allow")
        return
    }
    val invalidId = creating && (!Regex("^[a-zA-Z][\\w-]*$").matches(draftId) || state.roles.any { it.id == draftId })
    pageStates.SaveableStateProvider(selectedId ?: "roles") {
    ManagementPage(if (selectedId == null) "默认模型与角色" else draftName, { if (!back()) onBack() }, actions = {
        if (selectedId == null) Box {
            TextButton(onClick = { storageDialog = true }) { Symbol(if (storage == ConfigurationScope.PROJECT) "folder" else "public"); Text(storage.label) }
            ManagementMenu(storageDialog, { storageDialog = false }) {
                ConfigurationScope.entries.forEach { scope ->
                    ManagementMenuItem(text = { Text(scope.label) }, selected = storage == scope, leadingIcon = { Symbol(if (scope == ConfigurationScope.PROJECT) "folder" else "public") }, trailingIcon = { if (storage == scope) Symbol("check") }, onClick = {
                        actions.launch("角色保存位置已更新") { repository.updateSettings(ConfigurationScope.GLOBAL, mapOf("modelRoleStorage" to if (scope == ConfigurationScope.GLOBAL) "global" else "project")); storageDialog = false }
                    })
                }
            }
        }
    }, fab = if (selectedId == null) ({ ManagementFab("添加角色", onClick = {
        var id: String
        do { id = "role-${Random.nextLong().toULong().toString(36)}" } while (state.roles.any { it.id == id })
        open(id, true)
    }) }) else null, footer = if (selectedId != null) ({
        ManagementSaveBar(dirty, actions.busy, canSave = canEdit && !invalidId && draftName.isNotBlank(), onSave = save@{
            if (!dirty || !canEdit || invalidId || draftName.isBlank()) return@save
            val id = draftId
            val binding = if (draftInherited) null else draftCandidates.joinToString(",")
            val localTags = configurationMap(configurationValue(layer, "modelTags")).toMutableMap()
            localTags[id] = configurationMap(tags[id]).toMutableMap().apply {
                this["name"] = draftName.trim(); this["hidden"] = draftHidden
                if (draftColor.isBlank()) remove("color") else this["color"] = draftColor
            }
            val savedDraft = signature()
            actions.launch("角色已保存") {
                repository.updateSettings(storage, mapOf("modelRoles.$id" to binding, "modelTags" to localTags))
                creating = false; selectedId = id; initialDraft = savedDraft
            }
        }, onDiscard = { if (creating) close() else selectedId?.let { open(it) } }, error = when { invalidId -> "标识必须以英文字母开头，只含字母、数字、下划线或连字符，且不能重复。"; draftName.isBlank() -> "名称不能为空"; !canEdit -> "未打开项目，无法保存"; else -> actions.error })
    }) else null) {
        state.error?.let { ManagementBanner(it, error = true) }
        if (state.modelsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.modelsError?.let {
            ManagementBanner(it, "sync_problem", error = true)
            TextButton({ actions.launch { repository.reload(online = true) } }, enabled = !actions.busy) { Text("重新读取") }
        }
        if (!canEdit) ManagementBanner("角色保存位置是项目，但尚未打开项目；修改已禁用。可返回切换到全局。", warning = true)
        if (selectedId == null) {
            actions.error?.let { ManagementBanner(it, error = true) }
            ManagementLayers(listOf(
                ManagementLayer("当前会话", currentSession?.let { "${it.providerId}/${it.modelId} · ${it.thinkingLevel.label}" } ?: "未选择具体会话", "bolt"),
                ManagementLayer("保存的默认", state.roles.firstOrNull { it.id == "default" }?.let { it.model ?: it.error } ?: "未配置", "star", selected = true),
                ManagementLayer("默认思考", managementThinkingLabel(configurationValue(state.effectiveValues, "defaultThinkingLevel") as? String), "lightbulb"),
            ))
            val groups = listOf("主会话与工作用途" to state.roles.filter { it.id in setOf("default", "smol", "slow", "vision", "plan", "commit", "task", "advisor", "widget") },
                "轻量与记忆" to state.roles.filter { it.id in setOf("tiny", "memory") },
                "专门能力" to state.roles.filter { it.id in setOf("image", "web", "speech", "dictation", "judge") },
                "自定义" to state.roles.filter { it.id !in managementBuiltinRoles })
            groups.forEach { (title, roles) ->
                Spacer(Modifier.height(16.dp))
                ManagementGroup(title) {
                    if (roles.isEmpty()) ManagementHint("尚无自定义角色")
                    roles.forEachIndexed { index, role ->
                        val tag = configurationMap(tags[role.id])
                        ManagementRow(role.name, listOfNotNull(role.model ?: role.error ?: "自动选择", role.source?.let(::managementSourceLabel), if (tag["hidden"] == true) "在选择器中隐藏" else null).joinToString(" · "),
                            icon = "label", onClick = { open(role.id) }, index = index, count = roles.size, error = role.error != null,
                            lead = { RoleColorSwatch(tag["color"] as? String ?: "") })
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            ManagementGroup("快速轮换") { ManagementRow("轮换顺序", configurationStrings(configurationValue(state.effectiveValues, "cycleOrder")).joinToString(" → ") { managementRoleName(state, it) }, "sync_alt", onClick = { cycleScope = storage; showCycle = true }) }
        } else {
            val id = selectedId!!
            val globalBinding = configurationMap(configurationValue(state.globalValues, "modelRoles"))[id]
            val projectBinding = configurationMap(configurationValue(state.projectValues, "modelRoles"))[id]
            ManagementLayers(listOf(
                ManagementLayer("全局", configurationDisplay(globalBinding ?: "未设置"), "public", selected = preview?.source == "global"),
                ManagementLayer("项目", if (hasProject) configurationDisplay(projectBinding ?: "继承全局") else "未打开项目", "folder", selected = preview?.source == "project"),
                ManagementLayer("最终解析", preview?.model?.let { "$it · ${managementThinkingLabel(preview?.thinking)}" } ?: preview?.error ?: "正在解析", "done_all", selected = true, error = preview?.error != null),
            ))
            previewError?.let { ManagementBanner(it, error = true) }
            preview?.error?.let { ManagementBanner(it, "sync_problem", error = true) }
            preview?.diagnostics?.forEach { ManagementBanner(it, warning = true) }
            if (preview?.automatic == true) ManagementBanner("未绑定，自动选择可用模型", "auto_mode")
            Spacer(Modifier.height(16.dp))
            ManagementGroup("${storage.label}候选") {
                if (draftInherited) ManagementRow("本层未绑定", if (storage == ConfigurationScope.PROJECT) "继承全局；点击复制全局候选并覆盖" else "使用原生自动选择；点击设置候选", "subdirectory_arrow_right", enabled = canEdit,
                    onClick = { draftCandidates = managementRoleCandidates(if (storage == ConfigurationScope.PROJECT) globalBinding else null); draftInherited = false })
                else ManagementCandidates(draftCandidates, state, onChange = { draftCandidates = it }, kind = if (creating) "chat" else id, readOnly = !canEdit)
            }
            ManagementHint("按顺序取第一个可用模型，不会同时调用，也不是请求失败后的故障转移", "format_list_numbered")
            ManagementHint("此角色只接受${selected?.kind ?: "chat"}模型", "filter_alt")
            if (!draftInherited) TextButton(enabled = canEdit && !clearing && !actions.busy, onClick = {
                clearing = true
                actions.launch {
                    try { clearPreview = repository.previewRole(draftId, storage, null) }
                    finally { clearing = false }
                }
            }) { Text(if (clearing) "正在计算继承结果…" else "清除${storage.label}绑定…") }
            Spacer(Modifier.height(16.dp))
            ManagementGroup("显示") {
                if (creating || id !in managementBuiltinRoles) {
                    ManagementField("名称", draftName, { draftName = it }, readOnly = !canEdit)
                    ManagementField("标识", draftId, { draftId = it }, mono = true, readOnly = !creating || !canEdit)
                    if (invalidId) ManagementHint("标识必须唯一，以英文字母开头，仅含字母、数字、下划线及连字符。", error = true)
                }
                SettingsLabel("颜色")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("", "success", "accent", "warning", "error", "muted").forEach { token ->
                        val color = when (token) { "success" -> Color(0xFF146C2E); "accent" -> Color(0xFF8E24AA); "warning" -> Color(0xFFC5551A); "error" -> Color(0xFFB3261E); "muted" -> Color(0xFF5F6368); else -> Color(0xFF0B57D0) }
                        Box(Modifier.size(44.dp).clip(if (draftColor == token) RoundedCornerShape(14.dp) else CircleShape).background(color).clickable(enabled = canEdit) { draftColor = token }, contentAlignment = Alignment.Center) {
                            if (draftColor == token) Symbol("check", tint = Color.White)
                        }
                    }
                }
                ManagementRow("在选择器中隐藏", "隐藏不等于停用", "visibility_off", trailing = { Switch(draftHidden, { draftHidden = it }, enabled = canEdit) })
            }
            if (id !in managementBuiltinRoles && !creating) {
                val localDefinition = configurationMap(configurationValue(layer, "modelRoles")).containsKey(id)
                    || configurationMap(configurationValue(layer, "modelTags")).containsKey(id)
                    || id in configurationStrings(configurationValue(layer, "cycleOrder"))
                if (localDefinition) TextButton(onClick = { deleteDialog = true }, enabled = canEdit && !actions.busy) { Text("删除本层自定义角色", color = MaterialTheme.colorScheme.error) }
                else ManagementHint("本层没有此角色定义。若要删除继承的自定义角色，请返回切换到定义所在的保存层。", "layers")
            }
        }
    }
    }
    clearPreview?.let { result -> AlertDialog(onDismissRequest = { clearPreview = null }, title = { Text("清除${storage.label}绑定") }, text = {
        Text("清除后${if (result.automatic) "自动选择" else "继承并解析为"}：${result.model ?: result.error ?: "没有可用模型"}。此操作先保留为草稿，保存后写入。")
    }, confirmButton = { TextButton(onClick = { draftCandidates = emptyList(); draftInherited = true; clearPreview = null }) { Text("清除") } }, dismissButton = { TextButton(onClick = { clearPreview = null }) { Text("取消") } }) }
    if (deleteDialog) AlertDialog(onDismissRequest = { deleteDialog = false }, title = { Text("删除角色") }, text = { Text("引用 @$draftId 的 Agent、其他角色和预设不会被自动改写，可能无法解析。此操作会删除当前${storage.label}层的绑定、显示元数据与轮换项；其他层同名定义仍可能存在。") },
        confirmButton = { TextButton(enabled = !actions.busy, onClick = {
            val id = draftId
            val localTags = configurationMap(configurationValue(layer, "modelTags")).toMutableMap().apply { remove(id) }
            val cycle = configurationStrings(configurationValue(state.effectiveValues, "cycleOrder")).filterNot { it == id }
            actions.launch("角色已删除") { repository.updateSettings(storage, mapOf("modelRoles.$id" to null, "modelTags" to localTags, "cycleOrder" to cycle)); deleteDialog = false; close() }
        }) { Text("删除", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("取消") } })
    if (discardDialog) AlertDialog(onDismissRequest = { discardDialog = false }, title = { Text("放弃未保存的修改？") }, text = { Text("保存失败时草稿会保留；现在返回将放弃此角色草稿。") },
        confirmButton = { TextButton(onClick = { discardDialog = false; close() }) { Text("放弃") } }, dismissButton = { TextButton(onClick = { discardDialog = false }) { Text("继续编辑") } })
}

@Composable
private fun RoleColorSwatch(token: String) {
    val colors = LocalAgentColors.current
    val color = when (token) {
        "success" -> Color(0xFF146C2E)
        "warning" -> Color(0xFFC5551A)
        "error" -> MaterialTheme.colorScheme.error
        "muted", "dim" -> colors.onSurfaceVariant
        else -> colors.primary
    }
    Box(Modifier.size(40.dp).background(color.copy(alpha = .16f), CircleShape).padding(8.dp)) { Symbol("label", tint = color) }
}

