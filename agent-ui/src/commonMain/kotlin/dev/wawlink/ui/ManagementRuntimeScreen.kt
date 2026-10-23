package dev.wawlink.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.wawlink.protocol.*

private val dedicatedManagementSettingIds = setOf(
    "modelRoles", "modelTags", "modelPresets", "modelRoleStorage", "enabledModels", "enabledProviders",
    "disabledProviders", "modelProviderOrder", "cycleOrder", "retry.fallbackChains",
)

internal fun managementRuntimeSetting(id: String): Boolean = id !in dedicatedManagementSettingIds

@Composable
fun ManagementRuntimeScreen(
    repository: AgentManagementRepository,
    configurationScope: ConfigurationScope,
    onScopeChange: (ConfigurationScope) -> Unit,
    onBack: () -> Unit,
    registerBack: ((() -> Boolean)?) -> Unit,
    initialQuery: String = "",
) {
    val state by repository.state.collectAsState()
    val actions = LocalManagementActions.current
    val pageStates = rememberSaveableStateHolder()
    var categoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var domainId by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var onlyModified by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var resetIds by remember { mutableStateOf<List<String>?>(null) }
    val layer = if (configurationScope == ConfigurationScope.GLOBAL) state.globalValues else state.projectValues
    val hasProject = state.context.projectPath != null
    val canEdit = configurationScope != ConfigurationScope.PROJECT || hasProject
    val categories = remember(state.categories) {
        state.categories.mapNotNull { category ->
            val groups = category.groups.mapNotNull { group ->
                val items = group.items.filter { managementRuntimeSetting(it.id) }
                group.copy(items = items).takeIf { items.isNotEmpty() }
            }
            category.copy(groups = groups).takeIf { groups.isNotEmpty() }
        }
    }
    val category = categories.firstOrNull { it.id == categoryId }
    val domain = category?.groups?.firstOrNull { it.id == domainId }
    val allItems = remember(categories) { categories.flatMap { it.groups }.flatMap { it.items } }
    val filtering = query.isNotBlank() || onlyModified
    fun modified(item: SettingItem) = configurationValue(layer, item.id) != null
    fun back(): Boolean = when {
        editingId != null -> { editingId = null; true }
        resetIds != null -> { resetIds = null; true }
        domainId != null || categoryId != null -> { domainId = null; categoryId = null; true }
        filtering -> { query = ""; onlyModified = false; true }
        else -> false
    }
    DisposableEffect(registerBack, categoryId, domainId, query, onlyModified, editingId, resetIds) {
        registerBack(::back)
        onDispose { registerBack(null) }
    }
    val title = domain?.title ?: category?.title ?: "运行设置"
    pageStates.SaveableStateProvider("${categoryId ?: "categories"}/${domainId ?: "domains"}/${if (filtering) "search" else "browse"}") {
    ManagementPage(title, { if (!back()) onBack() }, actions = {
        ManagementScopeChip(configurationScope, onScopeChange, hasProject)
    }) {
        if (!canEdit) ManagementBanner("未打开项目，项目层不可编辑。请选择全局层。", warning = true)
        state.error?.let { ManagementBanner(it, error = true) }
        actions.error?.let { ManagementBanner(it, error = true) }
        when {
            filtering || category != null -> {
                val visibleCategories = if (filtering) categories else listOfNotNull(category)
                var count = 0
                visibleCategories.forEach { c ->
                    c.groups.filter { filtering || domainId == null || it.id == domainId }.forEach { group ->
                        val visible = group.items.filter { item ->
                            (!onlyModified || modified(item)) && (query.isBlank() || listOf(item.title, item.id, configurationDisplay(item.value), c.title, group.title).any { it.contains(query.trim(), true) })
                        }
                        if (visible.isNotEmpty()) {
                            count += visible.size
                            ManagementGroup(if (filtering) "${c.title} · ${group.title}" else group.title, action = {
                                val ids = visible.filter(::modified).map { it.id }
                                if (ids.isNotEmpty()) TextButton(onClick = { resetIds = ids }, enabled = canEdit && !actions.busy) { Text("清除本组 ${ids.size} 项") }
                            }) {
                                visible.forEachIndexed { index, item ->
                                    val source = state.provenance[item.id] ?: "default"
                                    ManagementRow(item.title,
                                        configurationDisplay(configurationValue(state.effectiveValues, item.id) ?: item.value),
                                        "", onClick = { editingId = item.id }, index = index, count = visible.size,
                                        trailing = {
                                            if (item.type == SettingType.BOOLEAN) Switch(
                                                checked = (configurationValue(state.effectiveValues, item.id) ?: item.value) == true,
                                                onCheckedChange = { value -> actions.launch("已保存") { repository.updateSettings(configurationScope, mapOf(item.id to value)) } },
                                                enabled = canEdit && !actions.busy,
                                            ) else if (modified(item)) AgentIconButton("undo", "清除本层", {
                                                if (canEdit && !actions.busy) actions.launch("已恢复继承") { repository.updateSettings(configurationScope, mapOf(item.id to null)) }
                                            })
                                        })
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
                if (count == 0) ManagementHint("没有匹配的运行设置。", "search_off")
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                categories.forEachIndexed { index, item ->
                    val settings = item.groups.flatMap { it.items }
                    val modified = settings.count(::modified)
                    ManagementRow(item.title, "${settings.size} 项${if (modified > 0) " · $modified 项本层已修改" else ""}", item.icon,
                        onClick = { categoryId = item.id }, index = index, count = categories.size)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (category == null) ManagementHint("有效值按运行时覆盖、启动参数、项目、全局、默认值的顺序决定，个别环境变量只作兜底", "layers")
    }
    }
    allItems.firstOrNull { it.id == editingId }?.let { item ->
        ManagementSettingEditor(item, state, configurationScope, onDismiss = { editingId = null }, onSave = { value ->
            if (!canEdit) return@ManagementSettingEditor
            actions.launch("已保存") {
                repository.updateSettings(configurationScope, mapOf(item.id to value))
                editingId = null
            }
        })
    }
    resetIds?.let { ids ->
        AlertDialog(onDismissRequest = { resetIds = null }, title = { Text("清除${configurationScope.label}层覆盖") },
            text = { Text("清除 ${ids.size} 项运行设置覆盖，恢复上层或原生默认值。其他模型、Agent 和框架配置不变。") },
            confirmButton = { TextButton(enabled = canEdit && !actions.busy, onClick = {
                actions.launch("已恢复继承") { repository.updateSettings(configurationScope, ids.associateWith { null }); resetIds = null }
            }) { Text("清除") } }, dismissButton = { TextButton(onClick = { resetIds = null }) { Text("取消") } })
    }
}
