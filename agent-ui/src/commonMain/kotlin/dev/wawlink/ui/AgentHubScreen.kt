package dev.wawlink.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wawlink.protocol.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Runtime instances belong to this explorer, never to the similarly named Agent definition. */
@Composable
fun AgentHubScreen(
    explorer: SessionExplorer,
    mainController: AgentSessionController,
    providers: ProviderConfigRepository,
    modelRepository: AgentManagementRepository,
    onBack: () -> Unit,
    onMain: () -> Unit,
    onLocateMain: (String) -> Unit,
    onOpenDefinition: (String?) -> Unit,
    onPickFiles: suspend () -> List<AttachmentFile>,
    onPickContainerFiles: suspend (String) -> List<AttachmentFile>,
    onOpenFile: (String, String) -> Unit,
    onExport: suspend (SessionExport) -> Boolean,
    registerBack: ((() -> Boolean)?) -> Unit,
) {
    val hub by explorer.hub.collectAsState()
    val main by mainController.state.collectAsState()
    val scope = rememberCoroutineScope()
    val holder = rememberSaveableStateHolder()
    var pages by rememberSaveable(main.id) { mutableStateOf(listOf<String>()) }
    var locate by rememberSaveable(main.id) { mutableStateOf<String?>(null) }
    var pending by remember(main.id) { mutableStateOf(false) }
    var failure by remember(main.id) { mutableStateOf<String?>(null) }
    var retry by remember(main.id) { mutableStateOf<(suspend () -> Unit)?>(null) }
    var confirmation by remember(main.id) { mutableStateOf<Pair<RuntimeAgent, RuntimeAgentOperation>?>(null) }
    var exportAgent by remember(main.id) { mutableStateOf<RuntimeAgent?>(null) }
    var exportResult by remember(main.id) { mutableStateOf<String?>(null) }
    var forwards by remember(main.id) { mutableStateOf(true) }
    fun perform(block: suspend () -> Unit) {
        if (pending) return
        pending = true; failure = null; retry = block
        scope.launch {
            try { block(); retry = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure = error.message ?: "操作失败" }
            finally { pending = false }
        }
    }
    fun open(agent: RuntimeAgent, message: String? = null) {
        locate = message
        val existing = pages.indexOf(agent.instanceKey)
        forwards = existing < 0
        pages = if (existing >= 0) pages.take(existing + 1) else pages + agent.instanceKey
    }
    fun back(): Boolean {
        if (confirmation != null) { confirmation = null; return true }
        if (exportAgent != null) { exportAgent = null; return true }
        if (pages.isEmpty()) return false
        forwards = false
        pages = pages.dropLast(1); locate = null
        return true
    }
    val currentBack by rememberUpdatedState<() -> Boolean> { back() }
    DisposableEffect(registerBack) { registerBack { currentBack() }; onDispose { registerBack(null) } }
    LaunchedEffect(explorer, main.id) {
        while (true) {
            try { explorer.refreshHub() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure = error.message ?: "无法读取 Agent" }
            delay(2500)
        }
    }
    val colors = LocalAgentColors.current
    val primaryColors = remember(colors) { colors.copy(material = colors.material.copy(secondaryContainer = colors.primaryContainer, onSecondaryContainer = colors.onPrimaryContainer)) }
    CompositionLocalProvider(LocalAgentColors provides primaryColors) {
    Surface(color = colors.surface, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = AgentPageMaxWidth).fillMaxSize().navigationBarsPadding().imePadding()) {
                AnimatedContent(pages.lastOrNull().orEmpty(), transitionSpec = {
                    if (forwards) (slideInHorizontally(tween(550, easing = EmphDecelEasing)) { it } + fadeIn(tween(400))) togetherWith
                        (slideOutHorizontally(tween(360, easing = EmphAccelEasing)) { -it / 5 } + fadeOut(tween(360)))
                    else (slideInHorizontally(tween(550, easing = EmphDecelEasing)) { -it / 5 } + fadeIn(tween(400))) togetherWith
                        (slideOutHorizontally(tween(360, easing = EmphAccelEasing)) { it } + fadeOut(tween(360)))
                }, modifier = Modifier.weight(1f)) { page ->
                    holder.SaveableStateProvider("${main.id}:$page") {
                        val displayed = hub.agents.firstOrNull { it.instanceKey == page }
                        if (page.isEmpty()) HubOverview(hub, explorer, main.title, pending || !hub.connected, onBack, onMain,
                            onRefresh = { perform { explorer.refreshHub() } },
                            onOpen = ::open, onDefinition = onOpenDefinition,
                            onControl = { a, op -> if (hub.connected) confirmation = a to op else failure = "运行时未连接，不能控制这个实例" })
                        else if (displayed != null) SubagentScreen(
                            displayed, hub, explorer, providers, modelRepository, pending,
                            onBack = { back() }, onMain = onMain, onOpen = ::open,
                            onDefinition = onOpenDefinition, onControl = { a, op -> if (hub.connected) confirmation = a to op else failure = "运行时未连接，不能控制这个实例" },
                            onExport = { exportAgent = it }, onPickFiles = onPickFiles,
                            onPickContainerFiles = onPickContainerFiles, onOpenFile = onOpenFile,
                            onDispatch = { agent ->
                                val target = agent.dispatchEntryId
                                if (target == null) failure = "记录没有可定位的派发节点"
                                else {
                                    val parent = hub.agents.firstOrNull { it.id == agent.parentId }
                                    when {
                                        parent != null -> open(parent, target)
                                        agent.parentId == null -> perform {
                                            val chatId = explorer.chatItemIdForEntry(target) ?: error("派发记录在当前聊天中没有可定位的内容")
                                            onLocateMain(chatId)
                                        }
                                        else -> failure = "派发所属上级 ${agent.parentId} 的记录当前不可用；未跳转到主会话"
                                    }
                                }
                            }, locateMessage = if (page == pages.lastOrNull()) locate else null, onLocated = { if (page == pages.lastOrNull()) locate = null })
                        else Column(Modifier.fillMaxSize()) {
                            AgentTopBar("Agent", { back() })
                            HubEmpty("link_off", "当前实例不在这个主会话的运行记录中")
                            TextButton({ perform { explorer.refreshHub() } }) { Text("重新读取") }
                        }
                    }
                }
                val error = failure ?: hub.error ?: hub.discoveryError
                if (error != null) Row(Modifier.fillMaxWidth().background(colors.errorContainer).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Symbol("error", tint = colors.error)
                    Text(error, Modifier.weight(1f).padding(horizontal = 10.dp), color = colors.error, fontSize = 16.sp)
                    TextButton({ val action = retry; perform { if (action != null) action() else explorer.refreshHub() } }, enabled = !pending) { Text("重试") }
                    if (failure != null) AgentIconButton("close", "关闭错误", { failure = null })
                }
            }
            if (pending || hub.loading || hub.discovering) LinearProgressIndicator(Modifier.widthIn(max = AgentPageMaxWidth).fillMaxWidth().align(Alignment.TopCenter))
        }
    }
    }
    confirmation?.let { (agent, operation) ->
        val descendants = hubSubtree(hub.agents, agent.id).count { it.id != agent.id && it.canKill }
        val title = when (operation) { RuntimeAgentOperation.KILL -> "终止 ${agent.name}"; RuntimeAgentOperation.STOP -> "停止当前执行"; RuntimeAgentOperation.REVIVE -> "恢复 ${agent.name}" }
        val text = when (operation) {
            RuntimeAgentOperation.KILL -> "编号 ${agent.id}。会话记录和终止标记会保留；已经做的文件修改不会撤销。" + if (descendants > 0) "\n$descendants 个下级也会终止。" else ""
            RuntimeAgentOperation.STOP -> "只中断正在进行的执行，子代理可以继续对话。要彻底结束请用“终止”。"
            RuntimeAgentOperation.REVIVE -> "重新启动这个子代理的会话，在原来的对话上继续。不是从头重跑任务，也不会再次合并产物。"
        }
        AlertDialog(onDismissRequest = { confirmation = null }, icon = { Symbol(if (operation == RuntimeAgentOperation.REVIVE) "restart_alt" else "cancel") }, title = { Text(title) }, text = { Text(text) },
            confirmButton = { TextButton({ confirmation = null; perform { explorer.controlAgent(agent.instanceKey, operation); explorer.refreshHub() } }, enabled = !pending) { Text(if (operation == RuntimeAgentOperation.REVIVE) "恢复" else if (operation == RuntimeAgentOperation.STOP) "停止" else "终止", color = if (operation == RuntimeAgentOperation.KILL) colors.error else colors.primary) } },
            dismissButton = { TextButton({ confirmation = null }) { Text("取消") } }, containerColor = colors.surface, shape = RoundedCornerShape(28.dp))
    }
    exportAgent?.let { agent -> AgentSheet("导出会话", { exportAgent = null }) {
        HubKeyValues(listOf("对象" to "${agent.name} ${agent.id}", "格式" to "HTML", "包含" to "这个子代理和它的下级，不包含主会话"))
        Button({ perform { val file = explorer.exportAgent(agent.instanceKey); if (onExport(file)) { exportAgent = null; exportResult = "已保存 ${file.name}" } } }, enabled = !pending, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text(if (pending) "正在导出" else "选择保存位置") }
    } }
    exportResult?.let { result -> AlertDialog(onDismissRequest = { exportResult = null }, title = { Text("导出会话") }, text = { Text(result) }, confirmButton = { TextButton({ exportResult = null }) { Text("完成") } }) }
}

@Composable
private fun HubOverview(hub: AgentHubState, explorer: SessionExplorer, mainTitle: String, busy: Boolean, onBack: () -> Unit, onMain: () -> Unit,
    onRefresh: () -> Unit, onOpen: (RuntimeAgent, String?) -> Unit, onDefinition: (String?) -> Unit,
    onControl: (RuntimeAgent, RuntimeAgentOperation) -> Unit) {
    var tab by rememberSaveable { mutableStateOf("agents") }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("all") }
    var view by rememberSaveable { mutableStateOf("tree") }
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        AgentTopBar("Agent", onBack, actions = { AgentIconButton("refresh", "重新读取", onRefresh) })
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (!hub.connected && !hub.loading) ManagementBanner("运行时未连接，已有记录仍可查看；控制操作不可用", "link_off", warning = true)
            ManagementRow("主会话", mainTitle, "home", onMain, trailing = { Symbol("arrow_forward") })
            Box(Modifier.padding(top = 14.dp, bottom = 8.dp)) { HubSegments(listOf("agents" to "Agent", "activity" to "活动"), tab) { tab = it } }
            if (tab == "activity") HubActivity(hub, explorer, onOpen = onOpen, busy = busy)
            else {
                SettingsSearch(query, { query = it }, "搜索名称或编号", Modifier.padding(vertical = 8.dp))
                val filters = listOf("all" to "全部", "running" to "运行中", "idle" to "空闲", "parked" to "已停放", "aborted" to "已终止", "advisor" to "顾问")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    filters.forEach { (key, label) -> val count = hub.agents.count { hubMatchesFilter(it, key) }; HubChip("$label $count", filter == key) { filter = key } }
                }
                Box(Modifier.padding(vertical = 8.dp)) { HubSegments(listOf("tree" to "层级", "flat" to "平铺"), view, fillWidth = false) { view = it } }
                val matches = hub.agents.filter { hubMatchesFilter(it, filter) && (query.isBlank() || it.name.contains(query, true) || it.id.contains(query, true)) }.map { it.instanceKey }.toSet()
                val rows = if (view == "flat") hub.agents.filter { it.instanceKey in matches }.map { it to 0 } else {
                    val seen = mutableSetOf<String>()
                    buildList {
                        fun visit(agent: RuntimeAgent, depth: Int) {
                            if (!seen.add(agent.instanceKey)) return
                            if (agent.instanceKey in matches || hubSubtree(hub.agents, agent.id).any { it.instanceKey in matches }) add(agent to depth)
                            hub.agents.filter { it.parentId == agent.id }.forEach { visit(it, depth + 1) }
                        }
                        hub.agents.filter { it.parentId == null || hub.agents.none { p -> p.id == it.parentId } }.forEach { visit(it, 0) }
                        hub.agents.filter { it.instanceKey !in seen }.forEach { visit(it, 0) }
                    }
                }
                if (query.isNotBlank() || filter != "all") Text("${matches.size} 个匹配，共 ${hub.agents.size} 个", Modifier.padding(4.dp), color = LocalAgentColors.current.primary, fontSize = 16.sp)
                if (rows.isEmpty() && !hub.loading && !hub.discovering) HubEmpty(if (hub.agents.isEmpty()) "smart_toy" else "search_off", if (hub.agents.isEmpty()) "这个会话还没有派发过子代理" else "没有匹配的 Agent")
                rows.forEachIndexed { index, (agent, depth) -> HubAgentRow(agent, hub.agents, depth, view == "flat", agent.instanceKey !in matches, index, rows.size, busy, { onOpen(agent, null) }, onDefinition, onControl) }
            }
        }
    }
}

private fun hubMatchesFilter(agent: RuntimeAgent, filter: String): Boolean = when (filter) {
    "all" -> true; "advisor" -> agent.advisor; else -> !agent.advisor && agent.lifecycle.name.equals(filter, true)
}
internal fun hubSubtree(agents: List<RuntimeAgent>, id: String): List<RuntimeAgent> {
    val seen = mutableSetOf<String>()
    return buildList {
        fun visit(current: String) { agents.filter { it.id == current }.forEach { a -> if (seen.add(a.instanceKey)) { add(a); agents.filter { it.parentId == current }.forEach { visit(it.id) } } } }
        visit(id)
    }
}
internal fun hubLineage(agents: List<RuntimeAgent>, agent: RuntimeAgent): List<RuntimeAgent> {
    val result = mutableListOf<RuntimeAgent>(); val seen = mutableSetOf<String>(); var item: RuntimeAgent? = agent
    while (item != null && seen.add(item.instanceKey)) { result.add(0, item); item = agents.firstOrNull { it.id == item?.parentId } }
    return result
}

@Composable
private fun HubAgentAvatar(agent: RuntimeAgent, dim: Boolean = false) {
    val c = LocalAgentColors.current
    val colorIndex = remember(agent.name) {
        (agent.name.fold(7u) { hash, character -> hash * 31u + character.code.toUInt() } % 4u).toInt()
    }
    val (background, foreground) = if (agent.advisor) c.warnContainer to c.warn
        else when (colorIndex) {
            0 -> c.material.primary to c.material.onPrimary
            1 -> c.material.tertiary to c.material.onTertiary
            2 -> c.material.error to c.material.onError
            else -> c.material.secondary to c.material.onSecondary
        }
    Box(Modifier.size(44.dp).background(background.copy(alpha = if (dim) .45f else 1f), CircleShape), contentAlignment = Alignment.Center) {
        Symbol(if (agent.advisor) "shield_person" else "smart_toy", size = 22.dp, tint = foreground.copy(alpha = if (dim) .45f else 1f))
    }
}

@Composable
internal fun HubAgentRow(agent: RuntimeAgent, agents: List<RuntimeAgent>, depth: Int = 0, flat: Boolean = false, dim: Boolean = false,
    index: Int = 0, count: Int = 1, busy: Boolean = false, onOpen: () -> Unit, onDefinition: (String?) -> Unit, onControl: (RuntimeAgent, RuntimeAgentOperation) -> Unit) {
    val c = LocalAgentColors.current
    val shape = RoundedCornerShape(topStart = if (index == 0) 24.dp else 6.dp, topEnd = if (index == 0) 24.dp else 6.dp, bottomStart = if (index == count - 1) 24.dp else 6.dp, bottomEnd = if (index == count - 1) 24.dp else 6.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(c.surfaceContainerLow).clickable(onClick = onOpen).padding(start = (16 + depth * 28).dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        HubAgentAvatar(agent, dim)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(agent.name, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (dim) c.onSurfaceVariant else c.onSurface, modifier = Modifier.weight(1f, false))
                Text(agent.id, fontFamily = LocalAgentMono.current, fontSize = 14.sp, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (agent.advisor) HubTag("顾问", "shield_person", warning = true)
                HubLife(agent)
                agent.result?.let { HubTag(hubResult(it), error = it == "failed" || it == "merge_failed") }
                agent.unread?.takeIf { it > 0 }?.let { HubTag("$it 条未读") }
            }
            Text(agent.activity.ifBlank { agent.task }, color = c.onSurfaceVariant, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (flat && agent.parentId != null) Text("↳ " + hubLineage(agents, agent).dropLast(1).joinToString(" › ") { it.name }, fontSize = 16.sp, color = c.onSurfaceVariant)
        }
        HubAgentMenu(agent, busy, onOpen, onDefinition, onControl)
    }
}

@Composable
internal fun HubAgentMenu(agent: RuntimeAgent, busy: Boolean, onOpen: () -> Unit, onDefinition: (String?) -> Unit, onControl: (RuntimeAgent, RuntimeAgentOperation) -> Unit,
    onExport: (() -> Unit)? = null, onDispatch: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }; val clipboard = LocalClipboardManager.current
    var copyNotice by remember { mutableStateOf<String?>(null) }
    Box {
        AgentIconButton("more_vert", "更多操作", { menu = true })
        ManagementMenu(menu, { menu = false }) {
            ManagementMenuItem(text = { Text("打开会话") }, leadingIcon = { Symbol("open_in_new") }, onClick = { menu = false; onOpen() })
            ManagementMenuItem(text = { Text("复制编号") }, leadingIcon = { Symbol("tag") }, onClick = { menu = false; try { clipboard.setText(AnnotatedString(agent.id)); copyNotice = "已复制：${agent.id}" } catch (failure: Exception) { copyNotice = failure.message ?: "复制失败" } })
            if (onExport != null) ManagementMenuItem(text = { Text("导出这个子代理的会话") }, leadingIcon = { Symbol("download") }, enabled = !busy, onClick = { menu = false; onExport() })
            if (onDispatch != null) ManagementMenuItem(text = { Text("在上级中定位派发") }, leadingIcon = { Symbol("reply") }, onClick = { menu = false; onDispatch() })
            ManagementMenuItem(text = { Text("查看 Agent 定义") }, leadingIcon = { Symbol("description") }, enabled = !agent.advisor, onClick = { menu = false; onDefinition(agent.definitionName ?: agent.name) })
            HorizontalDivider()
            ManagementMenuItem(text = { Text("恢复") }, leadingIcon = { Symbol("restart_alt") }, enabled = agent.canRevive && !agent.advisor && !busy, onClick = { menu = false; onControl(agent, RuntimeAgentOperation.REVIVE) })
            ManagementMenuItem(text = { Text("终止", color = LocalAgentColors.current.error) }, leadingIcon = { Symbol("cancel", tint = LocalAgentColors.current.error) }, enabled = agent.canKill && !agent.advisor && !busy, onClick = { menu = false; onControl(agent, RuntimeAgentOperation.KILL) })
        }
    }
    copyNotice?.let { message -> AlertDialog(onDismissRequest = { copyNotice = null }, title = { Text("复制编号") }, text = { Text(message) }, confirmButton = { TextButton({ copyNotice = null }) { Text("完成") } }) }
}

@Composable
internal fun HubActivity(hub: AgentHubState, explorer: SessionExplorer, root: RuntimeAgent? = null, onOpen: (RuntimeAgent, String?) -> Unit, busy: Boolean) {
    var query by rememberSaveable { mutableStateOf("") }; var kind by rememberSaveable { mutableStateOf("all") }
    var selected by rememberSaveable { mutableStateOf(root?.instanceKey) }; var subtree by rememberSaveable { mutableStateOf(root != null) }
    var follow by rememberSaveable { mutableStateOf(true) }; var scopeMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var failure by remember { mutableStateOf<String?>(null) }
    val activityQuery = AgentActivityQuery(selected, subtree, AgentActivityKind.entries.firstOrNull { it.name == kind }, query)
    val currentQuery by rememberUpdatedState(activityQuery)
    LaunchedEffect(explorer, activityQuery) {
        failure = null
        try { explorer.refreshHub(20, activityQuery) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { if (currentQuery == activityQuery) failure = error.message ?: "无法读取活动" }
    }
    val list = rememberLazyListState()
    LaunchedEffect(list) { list.interactionSource.interactions.collect { if (it is androidx.compose.foundation.interaction.DragInteraction.Start) follow = false } }
    val scopedAgent = hub.agents.firstOrNull { it.instanceKey == selected }
    val activities = if (hub.activityQuery == activityQuery) hub.activity.asReversed() else emptyList()
    LaunchedEffect(activities.firstOrNull()?.id, follow) { if (follow && activities.isNotEmpty()) list.animateScrollToItem(0) }
    SettingsSearch(query, { query = it }, "搜索活动", Modifier.padding(vertical = 8.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HubChip("全部", kind == "all") { kind = "all" }
        listOf(AgentActivityKind.ERROR, AgentActivityKind.RESPONSE, AgentActivityKind.TOOL, AgentActivityKind.COMMUNICATION, AgentActivityKind.LIFECYCLE).forEach { value -> HubChip(value.label, kind == value.name) { kind = value.name } }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            HubChip(scopedAgent?.let { "${it.name}，${it.id}" } ?: if (selected != null) "实例已不可用" else "全部 Agent", selected != null, "filter_list") { scopeMenu = true }
            ManagementMenu(scopeMenu, { scopeMenu = false }) {
                ManagementMenuItem(text = { Text("全部 Agent") }, selected = selected == null, onClick = { selected = null; scopeMenu = false })
                hub.agents.forEach { a -> ManagementMenuItem(text = { Text("${a.name}，${a.id}") }, selected = selected == a.instanceKey, onClick = { selected = a.instanceKey; scopeMenu = false }) }
            }
        }
        if (selected != null) HubChip("含下级", subtree) { subtree = !subtree }
        HubChip(if (follow) "跟随最新" else "已暂停跟随", follow, if (follow) "vertical_align_bottom" else "pause") { follow = !follow }
    }
    failure?.let { message -> ManagementBanner(message, "error", error = true) }
    if (activities.isEmpty() && !hub.loading && !hub.discovering) HubEmpty("event_busy", "没有符合条件的活动")
    else if (activities.isEmpty()) Spacer(Modifier.height(24.dp))
    else LazyColumn(state = list, modifier = Modifier.fillMaxWidth().heightIn(max = 580.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        itemsIndexed(activities, key = { _, a -> a.id }) { index, activity ->
            val agent = hub.agents.firstOrNull { it.instanceKey == activity.instanceKey }
            val title = if (activity.kind == AgentActivityKind.LIFECYCLE) RuntimeAgentLifecycle.entries.firstOrNull { it.name.equals(activity.text, true) }?.label ?: activity.text else activity.text
            ManagementRow(title, "${agent?.name ?: activity.agentId}  ${activity.agentId}  ${explorerTimestamp(activity.timestamp)}", activity.kind.icon,
                onClick = agent?.let { { onOpen(it, activity.chatItemId ?: activity.entryId) } }, index = index, count = activities.size, error = activity.kind == AgentActivityKind.ERROR)
        }
    }
    if ((hub.activityHasMore && hub.activityQuery == activityQuery) || failure != null) TextButton({
        scope.launch {
            if (currentQuery != activityQuery) return@launch
            try {
                explorer.refreshHub(if (failure != null) hub.activityLimit else hub.activityLimit + 200, activityQuery)
                if (currentQuery == activityQuery) failure = null
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (currentQuery == activityQuery) failure = error.message ?: "读取失败" }
        }
    }, enabled = !busy && !hub.loading, modifier = Modifier.fillMaxWidth()) { Symbol("history"); Spacer(Modifier.width(8.dp)); Text(if (hub.loading) "读取中" else if (failure != null) "重试" else "加载更早的活动") }
    else if (activities.isNotEmpty()) HubTag("${activities.size} 条匹配活动", "history")
}

@Composable
internal fun HubSegments(options: List<Pair<String, String>>, selected: String, fillWidth: Boolean = true, onSelect: (String) -> Unit) {
    val c = LocalAgentColors.current
    Row(Modifier.then(if (fillWidth) Modifier.fillMaxWidth() else Modifier.wrapContentWidth()).heightIn(min = 52.dp).clip(RoundedCornerShape(24.dp)).border(1.dp, c.outline, RoundedCornerShape(24.dp))) {
        options.forEachIndexed { index, (id, label) ->
            val chosen = id == selected; val bg by animateColorAsState(if (chosen) c.primaryContainer else c.surface, tween(300, easing = EmphEasing))
            if (index > 0) Spacer(Modifier.width(1.dp).height(52.dp).background(c.outline))
            val weight = if (options.size <= 2) 1f else (label.length + 2).toFloat()
            Row(Modifier.then(if (fillWidth) Modifier.weight(weight) else Modifier.width(if (chosen) 72.dp else 56.dp)).heightIn(min = 52.dp).background(bg).clickable { onSelect(id) }.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                AnimatedVisibility(chosen, enter = expandHorizontally(tween(350, easing = EmphEasing)) + fadeIn(), exit = shrinkHorizontally(tween(250, easing = EmphAccelEasing)) + fadeOut()) { Symbol("check", Modifier.padding(end = 6.dp), size = 20.dp, tint = c.primary) }
                Text(label, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, color = if (chosen) c.onPrimaryContainer else c.onSurface)
            }
        }
    }
}
@Composable
internal fun HubChip(label: String, selected: Boolean, icon: String? = null, onClick: () -> Unit) {
    val c = LocalAgentColors.current
    FilterChip(selected, onClick, { Text(label, fontSize = 16.sp) }, Modifier.heightIn(min = 40.dp), shape = RoundedCornerShape(12.dp),
        leadingIcon = if (selected || icon != null) ({ Symbol(icon ?: "check", size = 20.dp) }) else null,
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.primaryContainer, selectedLabelColor = c.onPrimaryContainer, selectedLeadingIconColor = c.primary))
}
@Composable
internal fun HubTag(text: String, icon: String? = null, error: Boolean = false, warning: Boolean = false) {
    val c = LocalAgentColors.current
    Row(Modifier.heightIn(min = 28.dp).background(when { error -> c.errorContainer; warning -> c.warnContainer; else -> c.primaryContainer }, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (icon != null) Symbol(icon, size = 16.dp, tint = if (error) c.error else if (warning) c.warn else c.primary)
        Text(text, fontSize = 15.sp, color = if (error) c.error else if (warning) c.warn else c.onPrimaryContainer)
    }
}
@Composable
internal fun HubLife(agent: RuntimeAgent) {
    if (agent.lifecycle == RuntimeAgentLifecycle.RUNNING) Row(Modifier.height(28.dp).background(LocalAgentColors.current.primaryContainer, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Symbol("play_arrow", size = 16.dp, tint = LocalAgentColors.current.primary); Text(agent.lifecycle.label, fontSize = 15.sp, color = LocalAgentColors.current.onPrimaryContainer)
    } else HubTag(agent.lifecycle.label, when (agent.lifecycle) { RuntimeAgentLifecycle.IDLE -> "pause_circle"; RuntimeAgentLifecycle.PARKED -> "local_parking"; else -> "cancel" }, error = agent.lifecycle == RuntimeAgentLifecycle.ABORTED)
}
internal fun hubResult(result: String): String = when (result) { "done", "completed" -> "已完成"; "failed" -> "失败"; "cancelled" -> "已取消"; "merge_failed" -> "合并失败"; else -> result }
@Composable
internal fun HubEmpty(icon: String, text: String) { Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) { Symbol(icon, size = 48.dp, tint = LocalAgentColors.current.outline); Text(text, fontSize = 17.sp, color = LocalAgentColors.current.onSurfaceVariant) } }
@Composable
internal fun HubKeyValues(rows: List<Pair<String, String>>) {
    val c = LocalAgentColors.current
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        rows.forEachIndexed { index, (label, value) ->
            val shape = RoundedCornerShape(topStart = if (index == 0) 20.dp else 6.dp, topEnd = if (index == 0) 20.dp else 6.dp, bottomStart = if (index == rows.lastIndex) 20.dp else 6.dp, bottomEnd = if (index == rows.lastIndex) 20.dp else 6.dp)
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).background(c.surfaceContainerLow, shape).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(label, Modifier.widthIn(min = 84.dp), color = c.onSurfaceVariant, fontSize = 16.sp)
                androidx.compose.foundation.text.selection.SelectionContainer(Modifier.weight(1f)) { Text(value, Modifier.fillMaxWidth(), fontSize = 17.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End) }
            }
        }
    }
}
