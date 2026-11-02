package dev.wawlink.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wawlink.protocol.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

private val excludedSettingIds = setOf("composer.shape", "showHardwareCursor", "symbolPreset", "images.blockImages", "input.bareExitOnEmptySession", "doubleEscapeAction")
private val excludedSettingPrefixes = listOf("tui.", "statusLine.", "terminal.", "appearance.")
private val numberStepperIcons = listOf("remove", "restart_alt", "add")
private fun allowedSetting(id: String) = excludedSettingIds.none { it.equals(id, ignoreCase = true) } && excludedSettingPrefixes.none { id.startsWith(it, ignoreCase = true) }
private data class VisibleSettingsGroup(val categoryId: String, val group: SettingGroup) {
    val key: String get() = "$categoryId/${group.id}"
}

@Composable
fun AgentConfigScreen(repository: AgentSettingsRepository, onBack: () -> Unit) {
    val source by repository.categories.collectAsState()
    val synchronizationError by repository.error.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val notify: (String) -> Unit = remember(scope) { { text -> scope.launch { snackbar.showSnackbar(text) }; Unit } }
    val queue = remember(repository) { SettingsWriteQueue(scope, notify) }
    val overrides = remember(repository) { mutableStateMapOf<String, Any?>() }
    val categories = source.filterNot { it.id.equals("appearance", true) }.mapNotNull { category ->
        val groups = category.groups.mapNotNull { group ->
            val items = group.items.filter { allowedSetting(it.id) }.map { if (overrides.containsKey(it.id)) it.copy(value = overrides[it.id]) else it }
            group.copy(items = items).takeIf { items.isNotEmpty() }
        }
        category.copy(groups = groups).takeIf { groups.isNotEmpty() }
    }
    val allItems = categories.flatMap { it.groups }.flatMap { it.items }
    // Once a write settles, external YAML updates must become visible instead of stale optimistic values.
    LaunchedEffect(source, queue.pending) {
        if (queue.pending == 0 && "*all" !in queue.failures) {
            overrides.keys.toList().filterNot { it in queue.failures }.forEach(overrides::remove)
        }
    }
    var categoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var onlyModified by rememberSaveable { mutableStateOf(false) }
    var activeId by remember { mutableStateOf<String?>(null) }
    var resetDialog by remember { mutableStateOf(false) }
    val closedGroups = rememberSaveable(saver = BooleanStateMapSaver) { mutableStateMapOf<String, Boolean>() }
    val listState = rememberLazyListState()
    val subState = rememberLazyListState()
    val categoryState = rememberLazyListState()
    val colors = LocalAgentColors.current
    val selectedCategory = categories.firstOrNull { it.id == categoryId } ?: categories.firstOrNull()
    val filtering = search.isNotBlank() || onlyModified
    val query = search.trim()
    val visible = categories.filter { filtering || it.id == selectedCategory?.id }.flatMap { category ->
        category.groups.mapNotNull { group ->
            val items = group.items.filter { item -> (!onlyModified || item.modified) && (query.isEmpty() || item.title.contains(query, true) || item.id.contains(query, true) || displaySettingValue(item.value).contains(query, true) || group.title.contains(query, true)) }
            VisibleSettingsGroup(category.id, group.copy(items = items)).takeIf { items.isNotEmpty() }
        }
    }
    val activeGroup by remember(visible, listState) { derivedStateOf { visible.getOrNull(listState.firstVisibleItemIndex)?.group?.id } }
    LaunchedEffect(activeGroup, selectedCategory?.id) {
        val index = selectedCategory?.groups?.indexOfFirst { it.id == activeGroup } ?: -1
        if (!filtering && index >= 0) subState.animateScrollToItem(index)
    }
    fun save(id: String) {
        queue.submit(id, "保存设置失败") { repository.updateSetting(id, overrides[id]) }
    }
    fun update(item: SettingItem, value: Any?) { overrides[item.id] = value; save(item.id) }
    fun reset(item: SettingItem) {
        overrides[item.id] = item.defaultValue
        queue.submit(item.id, "恢复默认值失败") { repository.resetSetting(item.id); notify("已恢复默认值") }
    }
    fun resetAll() {
        allItems.forEach { overrides[it.id] = it.defaultValue }
        queue.submit("*all", "恢复全部默认设置失败") {
            repository.resetAll()
            // Edits entered while reset was queued still win; never persist an older snapshot.
            for (item in allItems) {
                if (overrides[item.id] != item.defaultValue) repository.updateSetting(item.id, overrides[item.id])
                queue.failures.remove(item.id)
            }
            notify("已恢复全部默认设置")
        }
    }
    fun retry() {
        if (queue.failures.containsKey("*all")) {
            queue.submit("*all", "保存配置失败") {
                for (item in allItems) if (overrides.containsKey(item.id)) repository.updateSetting(item.id, overrides[item.id])
                queue.failures.clear()
            }
        } else queue.failures.keys.toList().forEach(::save)
    }
    fun back() {
        if (search.isNotEmpty() || onlyModified) { search = ""; onlyModified = false; scope.launch { listState.scrollToItem(0) } }
        else queue.afterSaved(onBack)
    }
    Box(Modifier.fillMaxSize().background(colors.surface), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = AgentPageMaxWidth).fillMaxSize()) {
            val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 4 } }
            val headerColor by animateColorAsState(if (scrolled) colors.surfaceContainer else colors.surface, tween(350, easing = EmphEasing))
            Column(Modifier.fillMaxWidth().background(headerColor)) {
                AgentTopBar("框架设置", ::back) {
                    val shape by animateFloatAsState(if (onlyModified) 16f else 24f, tween(400, easing = EmphEasing))
                    Box(Modifier.background(if (onlyModified) colors.primaryContainer else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(shape.dp))) {
                        AgentIconButton("tune", "仅显示已修改项", { onlyModified = !onlyModified; scope.launch { listState.scrollToItem(0) }; notify(if (onlyModified) "仅显示已修改项" else "显示全部设置项") }, filled = onlyModified)
                    }
                    AgentIconButton("restart_alt", "恢复全部默认设置", { if (allItems.none { it.modified }) notify("当前已是默认配置") else resetDialog = true })
                }
                SettingsFailure(queue, ::retry)
                SettingsSyncError(synchronizationError)
                SettingsSearch(search, { search = it; scope.launch { listState.scrollToItem(0) } }, "搜索设置项", Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp))
                LazyRow(state = categoryState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(categories, key = { it.id }) { category ->
                        val selected = category.id == selectedCategory?.id && !filtering
                        val radius by animateFloatAsState(if (selected) 16f else 24f, tween(450, easing = EmphEasing))
                        val bg by animateColorAsState(if (selected) colors.primary else if (scrolled) colors.surface else colors.surfaceContainerLow, tween(300, easing = EmphEasing))
                        Surface(onClick = {
                            categoryId = category.id; search = ""; onlyModified = false
                            scope.launch { categoryState.animateScrollToItem(categories.indexOf(category)); listState.animateScrollToItem(0) }
                        }, shape = RoundedCornerShape(radius.dp), color = bg, contentColor = if (selected) colors.onPrimary else colors.onSurfaceVariant) {
                            Row(Modifier.height(48.dp).padding(start = 14.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Symbol(category.icon, filled = selected)
                                Text(category.title, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
                AnimatedVisibility(!filtering, enter = expandVertically(tween(400, easing = EmphEasing)) + fadeIn(), exit = shrinkVertically(tween(300, easing = EmphEasing)) + fadeOut()) {
                    LazyRow(state = subState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(selectedCategory?.groups.orEmpty(), key = { it.id }) { group ->
                            val selected = activeGroup == group.id
                            val bg by animateColorAsState(if (selected) colors.primaryContainer else if (scrolled) colors.surface else colors.surfaceContainer, tween(300, easing = EmphEasing))
                            Surface(onClick = {
                                val index = visible.indexOfFirst { it.group.id == group.id }
                                if (index >= 0) {
                                    closedGroups[visible[index].key] = false
                                    scope.launch { listState.animateScrollToItem(index) }
                                }
                            }, color = bg, contentColor = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant, shape = RoundedCornerShape(20.dp)) {
                                Row(Modifier.height(40.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Symbol(group.icon, size = 20.dp)
                                    Text(group.title, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            }
            LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                items(visible, key = { it.key }) { section ->
                    SettingsSection(section.group, filtering || closedGroups[section.key] != true, { closedGroups[section.key] = closedGroups[section.key] != true }, { item ->
                        if (item.type == SettingType.BOOLEAN) update(item, (if (overrides.containsKey(item.id)) overrides[item.id] else item.value) != true) else activeId = item.id
                    }, ::reset)
                }
                if (visible.isEmpty()) item { SettingsEmpty(if (onlyModified) "暂无已修改的设置项" else if (categories.isEmpty()) "暂无可用设置" else "没有匹配的设置项", if (onlyModified) "tune" else "search_off") }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).widthIn(max = AgentPageMaxWidth).padding(16.dp))
    }
    if (resetDialog) AlertDialog(onDismissRequest = { resetDialog = false }, title = { Text("恢复全部默认设置？") }, text = { Text("将重置全部框架配置。应用外观和终端界面配置不受影响。") }, confirmButton = { TextButton({ resetDialog = false; resetAll() }) { Text("恢复默认") } }, dismissButton = { TextButton({ resetDialog = false }) { Text("取消") } })
    val active = allItems.firstOrNull { it.id == activeId }
    if (active != null) key(active.id) {
        SettingEditorSheet(active, { activeId = null }, { value, close -> update(active, value); if (close) activeId = null }, { reset(active) })
    }
}

@Composable
private fun SettingsSection(group: SettingGroup, expanded: Boolean, onToggle: () -> Unit, onEdit: (SettingItem) -> Unit, onReset: (SettingItem) -> Unit) {
    val colors = LocalAgentColors.current
    var entered by remember(group.id) { mutableStateOf(false) }
    LaunchedEffect(group.id) { entered = true }
    val entrance by animateFloatAsState(if (entered) 1f else 0f, tween(550, easing = EmphDecelEasing))
    val angle by animateFloatAsState(if (expanded) 180f else 0f, tween(480, easing = EmphEasing))
    val radius by animateFloatAsState(if (expanded) 20f else 12f, tween(450, easing = EmphEasing))
    Column(Modifier.graphicsLayer {
        alpha = entrance
        translationY = (1f - entrance) * 22.dp.toPx()
        scaleX = .98f + entrance * .02f
        scaleY = .98f + entrance * .02f
    }) {
        Surface(onClick = onToggle, shape = RoundedCornerShape(20.dp), color = colors.surface) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).background(colors.primaryContainer, RoundedCornerShape(radius.dp)), contentAlignment = Alignment.Center) { Symbol(group.icon, size = 22.dp, tint = colors.onPrimaryContainer) }
                Text(group.title, Modifier.weight(1f), fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Symbol("expand_more", Modifier.rotate(angle), tint = colors.onSurfaceVariant)
            }
        }
        AnimatedVisibility(expanded, enter = expandVertically(tween(500, easing = EmphEasing)) + fadeIn(tween(380, 60, EmphDecelEasing)), exit = shrinkVertically(tween(480, easing = EmphEasing)) + fadeOut(tween(240, easing = EmphAccelEasing))) {
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                group.items.forEachIndexed { index, item -> SettingsRow(item, index, group.items.size, { onEdit(item) }, { onReset(item) }) }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SettingsRow(item: SettingItem, index: Int, count: Int, onEdit: () -> Unit, onReset: () -> Unit) {
    val colors = LocalAgentColors.current
    val bg by animateColorAsState(if (item.modified) colors.surfaceContainer else colors.surfaceContainerLow, tween(250, easing = EmphEasing))
    val iconRadius by animateFloatAsState(if (item.modified) 20f else 12f, tween(400, easing = EmphEasing))
    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).background(bg, segmentedSettingsShape(index, count)).combinedClickable(onClick = onEdit, onLongClick = onReset, onLongClickLabel = "恢复默认值").padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(40.dp).background(if (item.modified) colors.primaryContainer else colors.surface, RoundedCornerShape(iconRadius.dp)), contentAlignment = Alignment.Center) { Symbol(item.icon, tint = if (item.modified) colors.onPrimaryContainer else colors.onSurfaceVariant) }
        Text(item.title, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Medium)
        if (item.type == SettingType.BOOLEAN) Switch(item.value == true, { onEdit() })
        else Row(Modifier.widthIn(max = 150.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (item.type == SettingType.ARRAY || item.type == SettingType.RECORD) {
                Surface(shape = RoundedCornerShape(18.dp), color = colors.surface) {
                    Row(Modifier.height(36.dp).padding(start = 10.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Symbol(if (item.type == SettingType.ARRAY) "format_list_bulleted" else "data_object", size = 20.dp, tint = colors.primary)
                        Text(when (val value = item.value) { is Collection<*> -> value.size; is Map<*, *> -> value.size; else -> 0 }.toString(), fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    }
                }
            } else {
                val unset = item.value == null || item.value == "" || (item.type == SettingType.NUMBER && (item.value as? Number)?.toDouble() == -1.0)
                val label = if (unset) { if (item.type == SettingType.NUMBER) "默认" else "未设置" } else if (item.type == SettingType.ENUM) item.options.firstOrNull { it.value == item.value.toString() }?.label ?: item.value.toString() else displaySettingValue(item.value)
                Text(label, Modifier.weight(1f, fill = false), fontSize = if (item.type == SettingType.NUMBER && !unset) 16.sp else 17.sp, fontFamily = if (item.type == SettingType.NUMBER && !unset) LocalAgentMono.current else MaterialTheme.typography.bodyLarge.fontFamily, fontWeight = if (unset) FontWeight.Normal else FontWeight.Medium, color = if (unset) colors.onSurfaceVariant else colors.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Symbol(if (item.type == SettingType.ENUM) "expand_more" else "chevron_right", size = 22.dp, tint = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingEditorSheet(item: SettingItem, onDismiss: () -> Unit, onUpdate: (Any?, Boolean) -> Unit, onReset: () -> Unit) {
    var text by remember(item.value) { mutableStateOf(item.value?.let(::displaySettingValue).orEmpty()) }
    var entry by remember { mutableStateOf("") }
    var entryKey by remember { mutableStateOf("") }
    var editIndex by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val colors = LocalAgentColors.current
    AgentSheet(item.title, onDismiss, actions = { AgentIconButton("restart_alt", "恢复默认值", onReset) }, contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (item.type) {
                SettingType.BOOLEAN -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("启用", Modifier.weight(1f)); Switch(item.value == true, { onUpdate(it, false) }) }
                SettingType.ENUM -> item.options.forEach { option ->
                    val selected = item.value?.toString() == option.value
                    Surface(onClick = { onUpdate(option.value, true) }, shape = RoundedCornerShape(if (selected) 28.dp else 20.dp), color = if (selected) colors.primaryContainer else colors.surfaceContainerLow, contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Symbol(if (selected) "radio_button_checked" else "radio_button_unchecked", tint = if (selected) colors.primary else colors.outline)
                            Text(option.label, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                SettingType.NUMBER -> {
                    SettingsField(text, { text = it; error = null }, mono = true, round = 20, keyboardType = KeyboardType.Decimal, leading = { Symbol(item.icon, tint = colors.onSurfaceVariant) })
                    item.min?.let { min -> Text("范围：${displayNumber(min)} ～ ${item.max?.let(::displayNumber) ?: "不限"} · 步长 ${displayNumber(item.step)}", fontSize = 14.sp, color = colors.onSurfaceVariant) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        numberStepperIcons.forEach { icon ->
                            FilledTonalButton(onClick = {
                                val value = text.toDoubleOrNull() ?: (item.defaultValue as? Number)?.toDouble() ?: 0.0
                                text = if (icon == "restart_alt") item.defaultValue?.toString().orEmpty() else {
                                    var next = value + if (icon == "remove") -item.step else item.step
                                    item.min?.let { next = next.coerceAtLeast(it) }; item.max?.let { next = next.coerceAtMost(it) }
                                    displayNumber(kotlin.math.round(next * 1e8) / 1e8)
                                }
                            }, modifier = Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(20.dp)) { Symbol(icon, size = 28.dp) }
                        }
                    }
                    SettingsSaveButton({
                        val value = text.toDoubleOrNull()
                        val minimum = item.min
                        val maximum = item.max
                        error = when { value == null || !value.isFinite() -> "请输入有效数字"; minimum != null && value < minimum -> "不能小于 ${displayNumber(minimum)}"; maximum != null && value > maximum -> "不能大于 ${displayNumber(maximum)}"; else -> null }
                        if (error == null && value != null) onUpdate(settingNumber(value, item.value ?: item.defaultValue), true)
                    }, modifier = Modifier.fillMaxWidth())
                }
                SettingType.STRING -> {
                    SettingsField(text, { text = it }, round = 20, placeholder = "未设置", leading = { Symbol(item.icon, tint = colors.onSurfaceVariant) }, trailing = { AgentIconButton("backspace", "清空", { text = "" }) })
                    SettingsSaveButton({ onUpdate(text.trim(), true) }, modifier = Modifier.fillMaxWidth())
                }
                SettingType.ARRAY -> {
                    val values = (item.value as? List<*>)?.toList() ?: emptyList()
                    SettingsField(entry, { entry = it; error = null }, round = 20, placeholder = if (editIndex == null) "输入新增项" else "编辑列表项", trailing = {
                        AgentIconButton(if (editIndex == null) "add" else "check", if (editIndex == null) "添加" else "更新", {
                            if (entry.isBlank()) { error = "请填写列表项" }
                            else try {
                                val parsed = parseSettingEntry(entry)
                                val updated = values.toMutableList()
                                val index = editIndex
                                if (index != null && index in updated.indices) updated[index] = parsed else updated.add(parsed)
                                onUpdate(updated, false)
                                entry = ""; editIndex = null
                            } catch (failure: IllegalArgumentException) { error = "格式错误：${failure.message}" }
                        }, filled = true)
                    })
                    Text("普通文字直接输入；数字、布尔值、对象可使用 JSON。点击现有项编辑。", fontSize = 14.sp, color = colors.onSurfaceVariant)
                    values.forEachIndexed { index, value ->
                        Surface(onClick = { editIndex = index; entry = editableSettingValue(value) }, shape = RoundedCornerShape(20.dp), color = colors.surfaceContainerLow) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Symbol("label", tint = colors.primary)
                                Text(displaySettingValue(value), Modifier.weight(1f), fontFamily = LocalAgentMono.current, fontSize = 16.sp)
                                AgentIconButton("delete", "删除第 ${index + 1} 项", { onUpdate(values.filterIndexed { i, _ -> i != index }, false) })
                            }
                        }
                    }
                    if (values.isEmpty()) SettingsEmpty("列表为空")
                }
                SettingType.RECORD -> {
                    val values = (item.value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap()
                    SettingsField(entryKey, { entryKey = it; error = null }, round = 20, placeholder = "键名", mono = true)
                    SettingsField(entry, { entry = it; error = null }, round = 20, placeholder = "键值", mono = true, trailing = {
                        AgentIconButton("add", "添加或更新", {
                            if (entryKey.isBlank()) error = "请填写键名"
                            else try { onUpdate(values + (entryKey.trim() to parseSettingEntry(entry)), false); entryKey = ""; entry = "" }
                            catch (failure: IllegalArgumentException) { error = "格式错误：${failure.message}" }
                        }, filled = true)
                    })
                    Text("普通文字直接输入；嵌套配置使用 JSON。点击现有项编辑。", fontSize = 14.sp, color = colors.onSurfaceVariant)
                    values.forEach { (key, value) ->
                        Surface(onClick = { entryKey = key; entry = editableSettingValue(value) }, shape = RoundedCornerShape(20.dp), color = colors.surfaceContainerLow) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(key, Modifier.weight(1f), fontFamily = LocalAgentMono.current, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.primary)
                                Symbol("arrow_forward", size = 18.dp, tint = colors.outline)
                                Text(displaySettingValue(value), Modifier.weight(1f), fontFamily = LocalAgentMono.current, fontSize = 16.sp, color = colors.onSurfaceVariant)
                                AgentIconButton("delete", "删除 $key", { onUpdate(values - key, false) })
                            }
                        }
                    }
                    if (values.isEmpty()) SettingsEmpty("暂无配置项", "data_object")
                }
            }
            error?.let { ErrorMessage(it, color = colors.error, fontSize = 16.sp, details = it) }
        }
    }
}

private fun displayNumber(value: Double): String = if (value.isFinite() && value % 1.0 == 0.0 && value >= Long.MIN_VALUE.toDouble() && value < Long.MAX_VALUE.toDouble()) value.toLong().toString() else value.toString()
private fun displaySettingValue(value: Any?): String = when (value) {
    null -> ""
    is String -> value
    is Double -> displayNumber(value)
    is Map<*, *>, is Collection<*> -> settingJson(value).toString()
    else -> value.toString()
}
private fun editableSettingValue(value: Any?): String = settingJson(value).toString()
private fun settingJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to settingJson(it.value) })
    is Collection<*> -> JsonArray(value.map(::settingJson))
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    else -> JsonPrimitive(value.toString())
}
private fun parseSettingEntry(text: String): Any? {
    val clean = text.trim()
    val json = clean.startsWith("{") || clean.startsWith("[") || clean.startsWith("\"") || clean == "true" || clean == "false" || clean == "null" || clean.toDoubleOrNull() != null
    if (!json) return text
    val element = try { Json.parseToJsonElement(clean) } catch (error: Exception) { throw IllegalArgumentException("请输入有效 JSON", error) }
    return settingNative(element)
}
private fun settingNative(element: JsonElement): Any? = when (element) {
    JsonNull -> null
    is JsonObject -> element.mapValues { settingNative(it.value) }
    is JsonArray -> element.map(::settingNative)
    is JsonPrimitive -> if (element.isString) element.content else element.booleanOrNull ?: element.longOrNull ?: element.doubleOrNull ?: element.content
}

private fun settingNumber(value: Double, original: Any?): Number = when (original) {
    is Int -> if (value % 1.0 == 0.0 && value >= Int.MIN_VALUE && value <= Int.MAX_VALUE) value.toInt() else value
    is Long -> if (value % 1.0 == 0.0 && value >= Long.MIN_VALUE.toDouble() && value < Long.MAX_VALUE.toDouble()) value.toLong() else value
    is Float -> value.toFloat()
    is Double -> value
    else -> if (value % 1.0 == 0.0 && value >= Long.MIN_VALUE.toDouble() && value < Long.MAX_VALUE.toDouble()) value.toLong() else value
}

private val SettingItem.modified: Boolean get() = !settingValuesEqual(value, defaultValue)
private fun settingValuesEqual(first: Any?, second: Any?): Boolean = when {
    first is Number && second is Number -> first.toDouble() == second.toDouble()
    first is Map<*, *> && second is Map<*, *> -> first.keys == second.keys && first.all { (key, value) -> settingValuesEqual(value, second[key]) }
    first is List<*> && second is List<*> -> first.size == second.size && first.indices.all { settingValuesEqual(first[it], second[it]) }
    else -> first == second
}
