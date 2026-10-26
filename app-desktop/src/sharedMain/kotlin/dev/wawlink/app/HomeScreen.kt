@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.wawlink.app

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wawlink.protocol.*
import dev.wawlink.runtime.*
import dev.wawlink.ui.*

@Composable
internal fun HomeScreen(app: AppCoordinator, files: HostFiles, onSettings: () -> Unit, onNewSession: () -> Unit, onEnterSession: (String) -> Unit, onContainer: (String) -> Unit, onInstall: (Boolean) -> Unit, onFeature: (String) -> Unit, onExtension: (ContainerFeature) -> Unit, navigation: HostNavigation? = null) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var batch by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("全部") }
    var environment by rememberSaveable { mutableStateOf<String?>(null) }
    var environmentMenu by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<SessionRecord?>(null) }
    var switching by remember { mutableStateOf<SessionRecord?>(null) }
    var deleting by remember { mutableStateOf(emptySet<String>()) }
    val sessions by app.sessions.collectAsState()
    val containers by app.runtime.containers.collectAsState()
    val currentContainerId by app.selectedContainerId.collectAsState()
    val tabStates = rememberSaveableStateHolder()
    val featureNavigation = remember { HostNavigation() }
    val visible = sessions.filter { session ->
        if (app.isEmptyDraft(session)) return@filter false
        val r = session.record; val c = containers.firstOrNull { it.config.id == r.containerId }?.config
        (if (filter == "已归档") r.archived else !r.archived) &&
            (filter != "运行中" || session.state.status == SessionStatus.RUNNING) &&
            (filter != "待批准" || session.state.status == SessionStatus.WAITING) &&
            (filter != "已置顶" || r.pinned) && (environment == null || environment == r.containerId) &&
            (query.isBlank() || "${r.title} ${r.workspace} ${c?.displayName.orEmpty()} ${c?.distro.orEmpty()}".contains(query.trim(), true))
    }
    fun exitBatch() { batch = false; selected = emptySet() }
    SideEffect {
        navigation?.onBack = {
            when {
                tab == 1 -> featureNavigation.onBack?.invoke() == true
                batch -> { exitBatch(); true }
                else -> false
            }
        }
    }
    DisposableEffect(navigation) { onDispose { navigation?.onBack = null } }
    Scaffold(topBar = {
        if (tab != 1) AgentTopBar(if (batch) "已选 ${selected.size} 项" else listOf("会话", "功能", "容器")[tab], actions = {
            if (batch) {
                AgentIconButton("select_all", "全选", { selected = if (visible.all { it.record.id in selected }) selected - visible.map { it.record.id }.toSet() else selected + visible.map { it.record.id } })
                AgentIconButton("push_pin", "批量置顶", { val ids = selected; app.launch { app.togglePin(ids) }; exitBatch() })
                AgentIconButton("inventory_2", "批量归档", { val ids = selected; app.launch { app.toggleArchive(ids) }; exitBatch() })
                AgentIconButton("delete", "批量删除", { deleting = selected })
                AgentIconButton("close", "退出批量管理", ::exitBatch)
            } else {
                if (tab == 0) {
                    val running = sessions.count { it.state.status == SessionStatus.RUNNING && !it.record.archived }
                    if (running > 0) FilledTonalButton({ filter = if (filter == "运行中") "全部" else "运行中" }, contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.height(38.dp)) { Symbol("play_arrow", size = 18.dp); Spacer(Modifier.width(4.dp)); Text("$running") }
                    AgentIconButton("checklist", "批量管理", { expanded = null; batch = true })
                    AgentIconButton("edit_square", "新建会话", onNewSession)
                } else if (tab == 2) {
                    AgentIconButton("power_settings_new", "当前容器休眠", { currentContainerId?.let { app.launch { app.runtime.stop(it) } } })
                    AgentIconButton("add_box", "添加容器", { onInstall(false) })
                }
                AgentIconButton("settings", "应用设置", onSettings)
            }
        })
    }, bottomBar = {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface, modifier = Modifier.height(80.dp)) {
            NavigationBarItem(tab == 0, { tab = 0; exitBatch() }, { Symbol("forum", filled = tab == 0) }, label = { Text("会话", fontSize = 16.sp) }, colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer, selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer))
            NavigationBarItem(tab == 1, { tab = 1; exitBatch() }, { Symbol("widgets", filled = tab == 1) }, label = { Text("功能", fontSize = 16.sp) }, colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer, selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer))
            NavigationBarItem(tab == 2, { tab = 2; exitBatch() }, { Symbol("deployed_code", filled = tab == 2) }, label = { Text("容器", fontSize = 16.sp) }, colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer, selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer))
        }
    }, floatingActionButton = {
        if (!batch && tab != 1) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (tab == 2) ExtendedFloatingActionButton(onClick = { onInstall(true) }, icon = { Symbol("folder_zip") }, text = { Text("导入 rootfs", fontSize = 17.sp) }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(18.dp))
            ExtendedFloatingActionButton(onClick = { if (tab == 0) onNewSession() else onInstall(false) }, icon = { Symbol("add") }, text = { Text(if (tab == 0) "新建会话" else "安装发行版", fontSize = 18.sp) }, shape = RoundedCornerShape(18.dp))
        }
    }) { padding ->
        AnimatedContent(tab, modifier = Modifier.padding(padding), transitionSpec = {
            val forward = targetState > initialState
            (fadeIn(tween(420, easing = EmphDecelEasing)) + slideInHorizontally(tween(420, easing = EmphDecelEasing)) { if (forward) it / 5 else -it / 5 }) togetherWith
                (fadeOut(tween(180, easing = EmphAccelEasing)) + slideOutHorizontally(tween(280, easing = EmphAccelEasing)) { if (forward) -it / 5 else it / 5 })
        }) { current ->
            tabStates.SaveableStateProvider(current) {
            if (current == 0) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 96.dp)) {
                Row(Modifier.padding(top = 8.dp, bottom = 6.dp).fillMaxWidth().height(56.dp).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(28.dp)).padding(start = 18.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Symbol("search")
                    androidx.compose.foundation.text.BasicTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp), decorationBox = { inner -> if (query.isEmpty()) Text("搜索会话或项目", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp); inner() })
                    Box {
                        AgentIconButton("deployed_code", "按容器筛选", { environmentMenu = true }, filled = environment != null)
                        DropdownMenu(environmentMenu, { environmentMenu = false }) {
                            DropdownMenuItem({ Text("全部容器") }, { environment = null; environmentMenu = false })
                            containers.forEach { c -> DropdownMenuItem({ Text(c.config.displayName) }, { environment = c.config.id; environmentMenu = false }, leadingIcon = { ContainerAvatar(c.config, size = 24.dp) }, trailingIcon = { if (environment == c.config.id) Symbol("check") }) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("全部" to "forum", "运行中" to "play_arrow", "待批准" to "gpp_maybe", "已置顶" to "push_pin", "已归档" to "inventory_2").forEach { (label, icon) ->
                        FilterChip(filter == label, { filter = label }, { Text(label, fontSize = 17.sp) }, leadingIcon = { Symbol(icon, size = 20.dp) }, shape = RoundedCornerShape(if (filter == label) 22.dp else 14.dp), modifier = Modifier.heightIn(min = 44.dp), colors = FilterChipDefaults.filterChipColors(iconColor = MaterialTheme.colorScheme.onSurfaceVariant, selectedContainerColor = MaterialTheme.colorScheme.primaryContainer, selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer, selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer), border = FilterChipDefaults.filterChipBorder(enabled = true, selected = filter == label, borderColor = MaterialTheme.colorScheme.outlineVariant))
                    }
                }
                if (visible.isEmpty()) EmptyHost("没有匹配的会话")
                val pinned = if (filter != "已归档") visible.filter { it.record.pinned } else emptyList()
                val recent = visible - pinned.toSet()
                listOf(Triple("已置顶", "push_pin", pinned), Triple(if (filter == "已归档") "已归档会话" else if (pinned.isNotEmpty()) "最近会话" else "全部会话", "history", recent)).forEach { (title, icon, items) ->
                    if (items.isNotEmpty()) {
                        SectionLabel(title, icon, "${items.size}")
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            items.forEachIndexed { index, session ->
                                SessionCard(session, containers.firstOrNull { it.config.id == session.record.containerId }?.config?.displayName ?: "容器已移除", expanded == session.record.id && !batch, batch, session.record.id in selected, index == 0, index == items.lastIndex,
                                    onExpand = { val id = session.record.id; if (batch) { expanded = null; selected = if (id in selected) selected - id else selected + id } else expanded = if (expanded == id) null else id },
                                    onLongPress = { expanded = null; batch = true; selected = selected + session.record.id },
                                    onEnter = { onEnterSession(session.record.id) }, onFork = { app.launch { val id = app.fork(session.record.id); onEnterSession(id) } },
                                    onEdit = { editing = session.record }, onSwitch = { switching = session.record },
                                    onPin = { app.launch { app.togglePin(setOf(session.record.id)) } }, onArchive = { app.launch { app.toggleArchive(setOf(session.record.id)) } }, onDelete = { deleting = setOf(session.record.id) },
                                    onApproval = { node, decision -> runCatching { requireNotNull(app.openedSession(session.record.id)) { "请先进入会话" }.respondApproval(node, decision) }.onFailure(app::showError) })
                            }
                        }
                    }
                }
            } else if (current == 2) ContainersHome(app, onContainer)
            else key(currentContainerId) { ContainerFeaturesScreen(app, app.features, currentContainerId, onFeature, onExtension, onSettings, navigation = featureNavigation) }
            }
        }
    }
    editing?.let { record -> SessionEditor(app, files, record, { editing = null }) { editing = null } }
    switching?.let { record -> AgentActionSheet({ switching = null }, title = "切换绑定容器") {
        containers.forEach { c ->
            Row(Modifier.fillMaxWidth().clickable {
                app.launch { app.editSession(record.id, record.title, record.workspace, c.config.id, manualTitle = false); switching = null }
            }.heightIn(min = 64.dp).padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ContainerAvatar(c.config, size = 32.dp)
                Column(Modifier.weight(1f)) {
                    Text(c.config.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(c.config.metadata.description.ifBlank { "${c.config.distro} ${c.config.version}" },
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (record.containerId == c.config.id) Symbol("check_circle")
            }
        }
    } }
    if (deleting.isNotEmpty()) AlertDialog(onDismissRequest = { deleting = emptySet() }, title = { Text("删除 ${deleting.size} 个会话？") }, text = { Text("将停止相关进程并永久删除会话历史，无法撤销。") }, confirmButton = { TextButton({ val ids = deleting; deleting = emptySet(); app.launch { app.deleteSessions(ids) }; exitBatch() }) { Text("删除", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton({ deleting = emptySet() }) { Text("取消") } })
}

@Composable private fun RunningIndicator() {
    CircularProgressIndicator(Modifier.size(26.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
}

@Composable
private fun SessionCard(session: HomeSession, container: String, open: Boolean, batch: Boolean, selected: Boolean, first: Boolean, last: Boolean, onExpand: () -> Unit, onLongPress: () -> Unit, onEnter: () -> Unit, onFork: () -> Unit, onEdit: () -> Unit, onSwitch: () -> Unit, onPin: () -> Unit, onArchive: () -> Unit, onDelete: () -> Unit, onApproval: (String, ApprovalDecision) -> Unit) {
    val s = session.state; val r = session.record
    var more by remember { mutableStateOf(false) }
    val rounding by animateDpAsState(if (open || selected || first && last) 26.dp else 8.dp, tween(260, easing = EmphEasing))
    val shape = if (open || selected || first && last) RoundedCornerShape(rounding) else RoundedCornerShape(if (first) 24.dp else 8.dp, if (first) 24.dp else 8.dp, if (last) 24.dp else 8.dp, if (last) 24.dp else 8.dp)
    val background by animateColorAsState(if (selected) MaterialTheme.colorScheme.primaryContainer else if (open) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLow, tween(220))
    Column(Modifier.fillMaxWidth().clip(shape).background(background)) {
        Row(Modifier.fillMaxWidth().semantics { if (batch) this.selected = selected }
            .combinedClickable(
                role = if (batch) Role.Checkbox else Role.Button,
                onClickLabel = if (batch) if (selected) "取消选择会话" else "选择会话" else if (open) "收起会话操作" else "展开会话操作",
                onLongClickLabel = "选择会话并进入批量管理",
                onClick = onExpand,
                onLongClick = onLongPress,
            ).padding(vertical = 14.dp, horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val lead = if (selected || s.status == SessionStatus.RUNNING) MaterialTheme.colorScheme.primary else if (s.status == SessionStatus.WAITING) Color(0xFFFFDCC1) else if (s.status == SessionStatus.DONE) Color(0xFFC4EED0) else MaterialTheme.colorScheme.surfaceContainerHigh
            Box(Modifier.size(48.dp).background(lead, RoundedCornerShape(if (open || selected || s.status == SessionStatus.RUNNING) 24.dp else 14.dp)), contentAlignment = Alignment.Center) {
                CompositionLocalProvider(LocalContentColor provides if (selected || s.status == SessionStatus.RUNNING) Color.White else MaterialTheme.colorScheme.onSurface) {
                    if (!batch && s.status == SessionStatus.RUNNING) RunningIndicator() else Symbol(if (batch) if (selected) "check" else "radio_button_unchecked" else when (s.status) { SessionStatus.WAITING -> "gpp_maybe"; SessionStatus.DONE -> "check_circle"; SessionStatus.ERROR -> "info"; else -> "terminal" }, size = 26.dp)
                }
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(r.title, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (r.pinned) Symbol("push_pin", size = 16.dp, tint = MaterialTheme.colorScheme.primary)
                    if (s.isRunning) {
                        if (s.elapsedSec >= 1) {
                            Symbol("timer", size = 18.dp, tint = MaterialTheme.colorScheme.primary)
                            Text(if (s.elapsedSec < 60) "${s.elapsedSec}s" else "${s.elapsedSec / 60}:${(s.elapsedSec % 60).toString().padStart(2, '0')}", fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    } else Text(remember(r.updatedAt) { java.time.Instant.ofEpochMilli(r.updatedAt).atZone(java.time.ZoneId.systemDefault()).toLocalTime().format(java.time.format.DateTimeFormatter.ISO_LOCAL_TIME).take(5) }, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Meta("deployed_code", container); Meta("folder", r.workspace.substringAfterLast('/').ifBlank { "/" }); Text("+${s.addedLines}", color = Color(0xFF146C2E), fontSize = 16.sp); Text("−${s.removedLines}", color = MaterialTheme.colorScheme.error, fontSize = 16.sp); Meta("token", "${s.usage.totalTokens}")
                }
            }
        }
        AnimatedVisibility(open, enter = expandVertically(tween(260, easing = EmphEasing)) + fadeIn(tween(160)), exit = shrinkVertically(tween(220, easing = EmphEasing)) + fadeOut(tween(120))) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                pendingApprovals(s.feed).forEach { approval ->
                    Column(Modifier.fillMaxWidth().background(Color(0xFFFFDCC1), RoundedCornerShape(18.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("需要你的批准", color = Color(0xFF2E1500), fontWeight = FontWeight.SemiBold)
                        Text(approval.commandPreview.ifBlank { approval.description }, Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).padding(10.dp), color = Color(0xFF2D5016), fontFamily = LocalAgentMono.current, fontSize = 15.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton({ onApproval(approval.id, ApprovalDecision.DENY) }) { Text("拒绝") }; Button({ onApproval(approval.id, ApprovalDecision.ALLOW_ONCE) }, Modifier.weight(1f)) { Text("允许执行") } }
                        TextButton({ onApproval(approval.id, ApprovalDecision.ALLOW_ALWAYS_IN_SESSION) }) { Text("本次会话始终允许") }
                    }
                }
                val summary = remember(s.feed, s.status, s.error, s.actionText) {
                    s.error ?: if (s.isRunning || s.status == SessionStatus.STOPPED) s.actionText
                    else (s.feed.lastOrNull { it is AssistantMessageItem } as? AssistantMessageItem)?.markdownText?.takeIf { it.isNotBlank() } ?: s.actionText
                }
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Symbol("forum", tint = MaterialTheme.colorScheme.primary)
                    Text(summary, Modifier.weight(1f), fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HostAction("进入会话", "play_arrow", onEnter)
                    AgentIconButton("call_split", "创建分支", onFork)
                    AgentIconButton("edit", "重命名", onEdit)
                    Box { AgentIconButton("more_horiz", "更多", { more = true }); DropdownMenu(more, { more = false }) {
                        DropdownMenuItem({ Text(if (r.pinned) "取消置顶" else "置顶会话") }, { more = false; onPin() }, leadingIcon = { Symbol("push_pin") })
                        DropdownMenuItem({ Text("切换运行容器") }, { more = false; onSwitch() }, leadingIcon = { Symbol("deployed_code") })
                        DropdownMenuItem({ Text(if (r.archived) "移出归档" else "归档会话") }, { more = false; onArchive() }, leadingIcon = { Symbol("inventory_2") })
                        DropdownMenuItem({ Text("删除会话", color = MaterialTheme.colorScheme.error) }, { more = false; onDelete() }, leadingIcon = { Symbol("delete") })
                    } }
                }
            }
        }
    }
}
private fun pendingApprovals(feed: List<FeedItem>): List<ApprovalCardItem> = feed.flatMap { if (it is StepGroupNodeItem) pendingApprovals(it.children) else if (it is ApprovalCardItem && it.resolution == null) listOf(it) else emptyList() }
@Composable internal fun Meta(icon: String, text: String) { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { Symbol(icon, size = 19.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant); Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp, maxLines = 1) } }

@Composable
internal fun SessionEditor(app: AppCoordinator, files: HostFiles, record: SessionRecord, onDismiss: () -> Unit, onSaved: (String) -> Unit) {
    val containers by app.runtime.containers.collectAsState()
    var title by remember(record.id) { mutableStateOf(record.title) }
    var workspace by remember(record.id) { mutableStateOf(record.workspace) }
    var containerId by remember(record.id) { mutableStateOf(record.containerId) }
    var saving by remember { mutableStateOf(false) }
    var pickingWorkspace by remember { mutableStateOf(false) }
    val pickerNavigation = remember { HostNavigation() }
    AgentSheet("重命名会话", onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HostField("任务描述或标题", title, { title = it })
            SectionLabel("选择运行容器", "deployed_code")
            HostChips(containers.map { it.config.id }, containerId, { id -> containers.first { it.config.id == id }.config.displayName }, { containerId = it }, "deployed_code")
            OutlinedTextField(workspace, { workspace = it }, Modifier.fillMaxWidth().padding(vertical = 6.dp), label = { Text("工作区目录") }, singleLine = true, shape = RoundedCornerShape(16.dp), trailingIcon = {
                IconButton({ pickingWorkspace = true }, enabled = !saving && containers.any { it.config.id == containerId }) { Symbol("folder_open", contentDescription = "浏览容器目录选择工作区") }
            })
            HostAction(if (saving) "正在保存" else "保存修改", "check", {
                saving = true
                app.launch { try { app.editSession(record.id, title, workspace, containerId); onSaved(record.id) } finally { saving = false } }
            }, Modifier.fillMaxWidth().padding(top = 16.dp), enabled = !saving && title.isNotBlank() && containerId.isNotEmpty() && workspace.isNotBlank())
        }
    }
    if (pickingWorkspace) {
        val selectedContainer = containers.firstOrNull { it.config.id == containerId }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { if (pickerNavigation.onBack?.invoke() != true) pickingWorkspace = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(Modifier.fillMaxSize()) {
                if (selectedContainer != null) key(selectedContainer.config.id) {
                    ContainerFilesScreen(app, files, selectedContainer.config, { pickingWorkspace = false }, { pickerNavigation.onBack = it },
                        initialPath = workspace.ifEmpty { "/workspace" }, onChooseDirectory = { chosen -> workspace = chosen; pickingWorkspace = false })
                } else Column { AgentTopBar("容器不存在", { pickingWorkspace = false }); EmptyHost("请重新选择运行容器") }
            }
        }
    }
}
