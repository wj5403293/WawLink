package dev.wawlink.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wawlink.protocol.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first

/** Browsing and selecting never changes the native leaf. Only explicitly confirmed actions do. */
@Composable
fun SessionHistorySheet(
    explorer: SessionExplorer,
    controller: AgentSessionController,
    onDismiss: () -> Unit,
    onLocate: (String) -> Unit,
    onRestorePrompt: (RestoredSessionPrompt, Boolean) -> Unit,
    hasDraft: Boolean,
    onFork: suspend (String) -> String,
    onOpenFork: (String) -> Unit,
    focusChatItemId: String? = null,
    copyFocusedMessage: Boolean = false,
) {
    val history by explorer.history.collectAsState()
    val session by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    val c = LocalAgentColors.current
    var query by rememberSaveable(session.id) { mutableStateOf("") }
    var filter by rememberSaveable(session.id) { mutableStateOf("default") }
    var selectedId by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
    var initialized by rememberSaveable(session.id) { mutableStateOf(false) }
    var focusApplied by remember(session.id, focusChatItemId, copyFocusedMessage) { mutableStateOf(false) }
    var historyRead by remember(explorer, session.id) { mutableStateOf(false) }
    val list = key(session.id) { rememberLazyListState() }
    var panel by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
    var returnPanel by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
    var searchOriginIndex by rememberSaveable(session.id) { mutableIntStateOf(0) }
    var searchOriginOffset by rememberSaveable(session.id) { mutableIntStateOf(0) }
    var searchOriginSelection by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
    var targetId by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
    var summaryPreset by rememberSaveable(session.id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var localBusy by remember(session.id) { mutableStateOf(false) }
    var error by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
    var notice by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
    var retry by remember(session.id) { mutableStateOf<(suspend () -> Unit)?>(null) }
    var forkFile by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
    var forkTargetId by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
    var forkAppId by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
    var pendingAnswers by remember { mutableStateOf<List<AskAnswer>?>(null) }
    var openingLink by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<FeedDetail?>(null) }
    val expanded = rememberSaveable(session.id, saver = BooleanStateMapSaver) { mutableStateMapOf<String, Boolean>() }
    val busy = localBusy || history.busy
    val rows = remember(history.nodes, filter, query) { historyRows(history.nodes, filter, query) }
    val panelState = rememberSaveableStateHolder()
    val byId = remember(history.nodes) { history.nodes.associateBy { it.id } }
    val path = remember(history.nodes, history.leafId) { historyPath(history.nodes, history.leafId) }
    val selected = byId[selectedId]
    // Hold the operation target independent of subsequent selection and incoming history updates.
    val target = byId[targetId]

    fun operation(action: suspend () -> Unit) {
        if (localBusy) return
        localBusy = true
        error = null
        notice = null
        retry = action
        scope.launch {
            try {
                action()
                retry = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cancelled: SessionNavigationCancelledException) {
                notice = cancelled.message
                retry = null
                panel = null
            } catch (failure: Exception) {
                error = failure.message ?: "原生操作失败"
            } finally {
                localBusy = false
            }
        }
    }
    fun show(which: String, node: SessionHistoryNode? = selected, summary: Boolean = false) {
        if (which == "fork" && forkTargetId != node?.id) {
            forkFile = null
            forkTargetId = node?.id
        }
        returnPanel = when {
            which == "blocks" && panel == "turns" -> "turns"
            which == "preview" && panel == "rewind" -> "rewind"
            else -> null
        }
        targetId = node?.id
        panel = which
        summaryPreset = summary
        error = null
        notice = null
        retry = null
    }
    fun search(value: String) {
        if (query.isBlank() && value.isNotBlank()) {
            searchOriginIndex = list.firstVisibleItemIndex
            searchOriginOffset = list.firstVisibleItemScrollOffset
            searchOriginSelection = selectedId
        }
        val restoring = value.isBlank() && query.isNotBlank()
        query = value
        if (restoring) {
            selectedId = searchOriginSelection
            scope.launch {
                withFrameNanos { }
                list.scrollToItem(searchOriginIndex.coerceAtMost((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)), searchOriginOffset)
            }
        }
    }
    fun focus(id: String, reveal: Boolean = false) {
        selectedId = id
        val visibleIndex = rows.indexOfFirst { it.node.id == id }
        if (visibleIndex >= 0) scope.launch { list.animateScrollToItem(visibleIndex + if (query.isNotBlank()) 1 else 0) }
        else if (reveal) {
            filter = "all"
            query = ""
            scope.launch {
                withFrameNanos { }
                val index = historyRows(history.nodes, "all", "").indexOfFirst { it.node.id == id }
                if (index >= 0) list.animateScrollToItem(index)
            }
        }
    }
    fun navigate(step: String) {
        if (step == "current") { history.leafId?.let { focus(it, true) }; return }
        val index = rows.indexOfFirst { it.node.id == selectedId }
        val next = when (step) {
            "previous" -> rows.getOrNull(if (index < 0) 0 else index - 1)?.node
            "next" -> rows.getOrNull(index + 1)?.node
            "previousUser" -> rows.take(if (index < 0) 0 else index).lastOrNull { historyIsUser(it.node) }?.node
            "nextUser" -> rows.drop(index + 1).firstOrNull { historyIsUser(it.node) }?.node
            else -> {
                var ancestor = selected
                var sibling: SessionHistoryNode? = null
                val visited = mutableSetOf<String>()
                while (ancestor != null && visited.add(ancestor.id)) {
                    val current = ancestor
                    val siblings = history.nodes.filter { it.parentId == current.parentId }
                    if (siblings.size > 1) {
                        sibling = siblings[(siblings.indexOfFirst { it.id == current.id } + 1) % siblings.size]
                        break
                    }
                    ancestor = byId[current.parentId]
                }
                if (sibling == null) notice = "这条路径上没有分叉"
                sibling
            }
        }
        next?.let { focus(it.id, step == "sibling") }
    }
    fun copy(text: String, title: String) {
        if (text.isEmpty()) { error = "没有可复制的内容"; return }
        try {
            clipboard.setText(AnnotatedString(text))
            notice = "已复制：$title"
            error = null
        } catch (failure: Exception) {
            error = "复制失败：${failure.message ?: "剪贴板不可用"}"
        }
    }
    fun switch(node: SessionHistoryNode, summary: HistorySummary, replace: Boolean) {
        operation {
            val prompt = explorer.navigate(node.id, summary)
            explorer.history.value.error?.let { kotlin.error(it) }
            if (prompt != null) onRestorePrompt(prompt, replace)
            notice = if (historyIsUser(node)) {
                if (hasDraft && !replace) "已回退，保留了你的草稿" else "已回退，原输入和附件已恢复"
            } else "已切换，原路径保留为分支"
            panel = null
        }
    }
    LaunchedEffect(explorer, session.id) {
        while (isActive) {
            try {
                explorer.refreshHistory()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message ?: "历史读取失败"
            } finally {
                historyRead = true
            }
            delay(2500)
        }
    }
    LaunchedEffect(history.nodes, history.leafId) {
        if (!initialized && history.nodes.isNotEmpty()) {
            selectedId = selectedId?.takeIf { it in byId } ?: history.leafId ?: history.nodes.last().id
            initialized = true
            val index = rows.indexOfFirst { it.node.id == selectedId }
            if (index >= 0) list.scrollToItem(index + if (query.isNotBlank()) 1 else 0)
            else selectedId?.let { focus(it, true) }
        }
    }
    LaunchedEffect(explorer, session.id, focusChatItemId, copyFocusedMessage) {
        if (!focusApplied && focusChatItemId != null) {
            snapshotFlow { historyRead && !history.loading && history.error == null }.first { it }
            try {
                val entryId = explorer.entryIdForChatItem(focusChatItemId)
                val node = explorer.history.value.nodes.firstOrNull { it.id == entryId }
                if (node != null) {
                    initialized = true
                    focusApplied = true
                    error = null
                    focus(node.id, true)
                    if (copyFocusedMessage) show("blocks", node)
                } else if (history.error == null && error == null) {
                    error = "这条消息尚未写入原生历史"
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message ?: "消息定位失败"
            }
        }
    }

    HistorySurface("历史树", onDismiss = { if (!busy) onDismiss() }, full = true, dismissible = !busy, actions = {
        AgentIconButton("content_copy", "复制消息", { show("turns", null) }, enabled = !busy)
        AgentIconButton("history", "回退", { show("rewind", null) }, enabled = !busy)
    }, footer = {
        selected?.let { node ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                AgentIconButton("visibility", "查看完整节点", { show("preview", node) }, enabled = !busy)
                AgentIconButton("bookmark", "节点标签", { show("label", node) }, filled = !node.label.isNullOrBlank(), enabled = !busy)
                Box {
                    AgentIconButton("more_vert", "更多节点操作", { menu = true }, enabled = !busy)
                    DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(24.dp), containerColor = c.surface) {
                        if (historyIsAsk(node)) HistoryMenuItem("切换到这里", "subdirectory_arrow_right", node.navigable && node.id != history.leafId && !session.isRunning) { menu = false; show("switch", node) }
                        HistoryMenuItem("在聊天中定位", "chat", node.onCurrentPath) {
                            menu = false
                            operation {
                                val chatId = explorer.chatItemIdForEntry(node.id) ?: kotlin.error("这个节点在当前聊天中没有可定位的内容")
                                onLocate(chatId); onDismiss()
                            }
                        }
                        HistoryMenuItem("复制内容", "content_copy") { menu = false; show("blocks", node) }
                        HistoryMenuItem("摘要并切换", "summarize", node.navigable && node.id != history.leafId && !session.isRunning) { menu = false; show("switch", node, true) }
                        HistoryMenuItem("从这里创建独立会话", "call_split", !session.isRunning) { menu = false; show("fork", node) }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (historyIsAsk(node)) {
                    FilledTonalButton({ show("reanswer", node) }, enabled = !busy && !session.isRunning, modifier = Modifier.heightIn(min = 48.dp), colors = historyTonalColors()) {
                        Symbol("edit_note", size = 20.dp); Spacer(Modifier.width(6.dp)); Text("重新回答")
                    }
                } else {
                    Button({ show("switch", node) }, enabled = !busy && !session.isRunning && node.navigable && node.id != history.leafId, modifier = Modifier.heightIn(min = 48.dp)) {
                        Symbol(if (node.id == history.leafId) "check" else "subdirectory_arrow_right", size = 20.dp)
                        Spacer(Modifier.width(6.dp)); Text(if (node.id == history.leafId) "已在这里" else "切换到这里")
                    }
                }
            }
        }
    }) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 8.dp)) {
                HistorySearch(query, ::search, { search("") })
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    historyScopes.forEach { (key, title) -> HistoryChip(title, filter == key, { filter = key }) }
                }
                HistoryNavigation(selectedId, rows, history.leafId, { navigate(it) })
            }
            if (history.loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.primary)
            val visibleError = error ?: history.error
            if (visibleError != null) HistoryBanner(visibleError, error = true, action = "重试", onAction = {
                operation(retry ?: { explorer.refreshHistory() })
            })
            if (notice != null) HistoryBanner(notice!!, action = "关闭", onAction = { notice = null })
            if (session.isRunning) HistoryHint("progress_activity", "会话正在运行。可以查看和复制，停止后才能回退、重答或创建独立会话。")
            when {
                history.nodes.isEmpty() && !history.loading -> HistoryEmpty("forum", "这个会话还没有历史")
                rows.isEmpty() && !history.loading -> HistoryEmpty("search_off", if (query.isBlank()) "这个范围下没有节点" else "没有匹配的节点", if (query.isBlank()) null else "清除搜索", { search("") })
                else -> LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
                    if (query.isNotBlank()) item { Text("${rows.size} 个结果", Modifier.padding(horizontal = 4.dp, vertical = 12.dp), color = c.primary, fontSize = 16.sp) }
                    items(rows, key = { it.node.id }) { row ->
                        HistoryTreeRow(row, selectedId == row.node.id, history.leafId == row.node.id, query) { selectedId = row.node.id }
                    }
                }
            }
        }
    }

    fun dismissPanel() { if (!busy) { panel = returnPanel; returnPanel = null; pendingAnswers = null; error = null; retry = null } }
    panelState.SaveableStateProvider(if (panel in listOf("preview", "label", "switch", "reanswer", "blocks")) "$panel:${targetId.orEmpty()}" else panel ?: "tree") {
    when (panel) {
        "preview" -> target?.let { node ->
            HistoryPreview(explorer, node, history.sessionId, controller, expanded, { dismissPanel() }, { detail = it })
        }
        "label" -> target?.let { node ->
            HistoryLabel(node, busy, error, { dismissPanel() }) { label ->
                operation {
                    explorer.setLabel(node.id, label)
                    explorer.history.value.error?.let { kotlin.error(it) }
                    notice = if (label == null) "已清除标签" else "已保存标签"
                    panel = null
                }
            }
        }
        "switch" -> target?.let { node ->
            HistorySwitch(node, hasDraft, summaryPreset, busy, error, { dismissPanel() }, {
                scope.launch {
                    try { explorer.cancelNavigation() }
                    catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        error = failure.message ?: "取消失败"
                    }
                }
            }) { summary, replace -> switch(node, summary, replace) }
        }
        "rewind" -> HistoryRewind(history.nodes, path, history.leafId, session.isRunning || busy, { dismissPanel() }, { node -> show("switch", node) }, { node -> show("preview", node) })
        "turns" -> HistoryTurns(explorer, path, { dismissPanel() }, { node -> show("blocks", node) }, ::copy, { openingLink = it }, error, notice)
        "blocks" -> target?.let { node -> HistoryBlocks(explorer, node, { dismissPanel() }, ::copy, { openingLink = it }, error, notice) }
        "reanswer" -> target?.let { node ->
            HistorySurface("重新回答", { dismissPanel() }, full = true) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HistoryBanner("原答案会保留，新答案成为兄弟分支。提交后 Agent 会继续运行，可能调用工具和修改文件。", warning = true)
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let { HistoryBanner(it, error = true) }
                    HistoryDetailContent(explorer, node, HistoryDetailKind.ASK) { loaded ->
                    val ask = loaded.ask
                    if (ask == null || ask.questions.isEmpty()) {
                        HistoryBanner("原问题和完整选项没有保存下来，不能重新回答。仍可查看原始记录和原答案。", error = true)
                    } else {
                        val form = remember(node.id, ask) { ask.copy(id = "history-reanswer:${history.sessionId}:${node.id}", answers = null, cancelled = false) }
                        val formController = remember(controller, form.id) {
                            object : AgentSessionController by controller {
                                override fun respondAsk(nodeId: String, answers: List<AskAnswer>) { if (!localBusy) pendingAnswers = answers }
                            }
                        }
                        if (ask.answers != null) {
                            Text("原答案", color = c.primary, fontWeight = FontWeight.Medium)
                            ask.questions.forEach { question ->
                                val answer = ask.answers?.firstOrNull { it.questionId == question.id }
                                Text("${question.header ?: question.id}：${answer?.let { (it.selectedOptions + listOfNotNull(it.customInput)).joinToString("；").ifBlank { "不选择任何选项" } } ?: "无已保存答案"}", fontSize = 15.sp, color = c.onSurfaceVariant)
                            }
                        }
                        key(form.id) { FeedNode(form, formController, expanded) { detail = it } }
                    }
                    }
                }
            }
        }
        "fork" -> target?.let { node ->
            AlertDialog(onDismissRequest = { dismissPanel() }, icon = { Symbol("call_split", tint = c.primary) }, title = { Text("创建独立会话") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("新会话包含到「${node.title.take(48)}」为止的历史和相关附件、产物，有独立会话身份。")
                    HistoryHint("check_circle", "原会话保留，当前续写位置不变。")
                    HistoryHint("build", "原生会保留目标所在工具批次的完整结果，不截成半截上下文。")
                    HistoryHint("folder_off", "不会复制或回滚工作区文件。")
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let { HistoryBanner(it, error = true) }
                }
            }, confirmButton = { TextButton({
                operation {
                    // Keep a successfully created guest fork if host adoption needs a retry.
                    val file = forkFile ?: explorer.fork(node.id).also { forkFile = it }
                    val id = onFork(file)
                    forkAppId = id
                    forkFile = null
                    panel = "forkResult"
                }
            }, enabled = !busy && !session.isRunning) { Text(if (busy) "正在创建" else if (error != null) "重试" else "创建") } }, dismissButton = { TextButton({ dismissPanel() }, enabled = !busy) { Text("取消") } }, containerColor = c.surface, shape = RoundedCornerShape(28.dp))
        }
        "forkResult" -> AlertDialog(onDismissRequest = { dismissPanel() }, icon = { Symbol("check_circle", tint = c.tertiary) }, title = { Text("已创建独立会话") }, text = { SelectionContainer { Text("新会话已保存，有独立身份。\n${forkAppId.orEmpty()}\n原会话不受影响，可以随时返回。") } }, confirmButton = {
            TextButton({ forkAppId?.let(onOpenFork); panel = null; onDismiss() }) { Text("打开新会话") }
        }, dismissButton = { TextButton({ dismissPanel() }) { Text("留在这里") } }, containerColor = c.surface, shape = RoundedCornerShape(28.dp))
    }
    }
    if (panel == "reanswer" && pendingAnswers != null && target != null) {
        val node = target
        val answers = pendingAnswers!!
        AlertDialog(onDismissRequest = { if (!busy) pendingAnswers = null }, icon = { Symbol("play_arrow", tint = c.primary) }, title = { Text("提交并继续运行？") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("将一次提交全部 ${answers.size} 题答案。原答案保留，Agent 会按新答案继续，可能调用工具和修改文件。")
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { HistoryBanner(it, error = true) }
            }
        }, confirmButton = { TextButton({
            operation {
                explorer.reanswer(node.id, answers)
                pendingAnswers = null
                panel = null
                notice = "已提交新答案，Agent 继续运行"
            }
        }, enabled = !busy && !session.isRunning) { Text(if (busy) "正在提交" else "提交并继续") } }, dismissButton = { TextButton({ pendingAnswers = null }, enabled = !busy) { Text("继续编辑") } }, containerColor = c.surface, shape = RoundedCornerShape(28.dp))
    }
    openingLink?.let { link ->
        AlertDialog(onDismissRequest = { openingLink = null }, icon = { Symbol("open_in_new", tint = c.primary) }, title = { Text("打开链接") }, text = { SelectionContainer { Text(link) } }, confirmButton = {
            TextButton({
                try {
                    require(link.startsWith("https://", true) || link.startsWith("http://", true)) { "只允许 HTTP(S) 链接" }
                    uri.openUri(link)
                    openingLink = null
                } catch (failure: Exception) { error = "打开失败：${failure.message ?: "没有可用的浏览器"}"; openingLink = null }
            }) { Text("在浏览器打开") }
        }, dismissButton = { TextButton({ openingLink = null }) { Text("取消") } }, containerColor = c.surface, shape = RoundedCornerShape(28.dp))
    }
    detail?.let { request ->
        if (request.markdown) ThinkingDetailScreen(request.id, request.text, false) { detail = null }
        else CodeDetailScreen(request.title, request.text, request.language) { detail = null }
    }
}

private val historyScopes = listOf("default" to "默认", "notool" to "隐藏工具结果", "user" to "仅请求", "labeled" to "仅已标记", "all" to "全部")
private val historyPreviewTabs = listOf("body" to "正文", "raw" to "原始记录")
private val historySummaryModes = listOf("none" to "不摘要", "sum" to "生成摘要", "focus" to "指定重点")
private data class HistoryRail(val kind: String, val current: Boolean = false)
private data class HistoryRow(val node: SessionHistoryNode, val rails: List<HistoryRail>, val children: Int)
private val historyToolTypes = setOf("tool", "toolResult", "bash", "python", "bashExecution", "pythonExecution")
private val historyDefaultTypes = setOf("message", "user", "assistant", "tool", "toolResult", "bash", "python", "bashExecution", "pythonExecution", "ask", "custom_message", "custom", "compaction", "branch_summary")
private fun historyIsUser(node: SessionHistoryNode) = node.role == "user" || node.type == "user"
private fun historyIsAsk(node: SessionHistoryNode) = node.type == "ask"
private fun historyIsAssistant(node: SessionHistoryNode) = node.role == "assistant" || node.type == "assistant"
private fun historyIsTool(node: SessionHistoryNode) = node.role == "toolResult" || node.role == "tool" || node.type in historyToolTypes
private fun historyType(node: SessionHistoryNode): Pair<String, String> = when {
    historyIsAsk(node) -> "help" to "提问"
    historyIsUser(node) -> "person" to "你"
    historyIsAssistant(node) -> "smart_toy" to "助手"
    historyIsTool(node) -> "build" to "工具结果"
    else -> when (node.type) {
        "compaction" -> "compress" to "压缩摘要"
        "branch_summary" -> "alt_route" to "分支摘要"
        "model_change", "model" -> "swap_horiz" to "模型变更"
        "thinking_level_change" -> "psychology" to "思考档位"
        "service_tier_change" -> "speed" to "服务等级"
        "mode", "mode_change" -> "tune" to "模式变更"
        "session_info", "title" -> "title" to "标题"
        "label" -> "bookmark" to "标签"
        "context_reset", "context_clear" -> "restart_alt" to "上下文重置"
        "custom", "custom_message" -> "extension" to "扩展消息"
        else -> "description" to node.type
    }
}
private fun historyTitle(node: SessionHistoryNode): String = node.searchText.ifBlank {
    node.title.takeIf { it.isNotBlank() && it != node.type } ?: historyType(node).second
}.replace('\n', ' ')
private fun historyVisible(node: SessionHistoryNode, filter: String): Boolean {
    val pureToolCall = historyIsAssistant(node) && node.searchText.isBlank()
    if (pureToolCall) return false
    return when (filter) {
        "all" -> true
        "user" -> historyIsUser(node)
        "labeled" -> !node.label.isNullOrBlank()
        else -> node.type in historyDefaultTypes && (filter != "notool" || !historyIsTool(node))
    }
}
private fun historyRows(nodes: List<SessionHistoryNode>, filter: String, query: String): List<HistoryRow> {
    val children = nodes.groupBy { it.parentId }
    val ids = nodes.mapTo(hashSetOf()) { it.id }
    val visited = hashSetOf<String>()
    val result = mutableListOf<HistoryRow>()
    data class Pending(val node: SessionHistoryNode, val rails: List<HistoryRail>)
    val stack = ArrayDeque<Pending>()
    fun push(items: List<SessionHistoryNode>, rails: List<HistoryRail>) {
        val ordered = items.sortedByDescending { it.onCurrentPath }
        for (i in ordered.indices.reversed()) {
            val node = ordered[i]
            stack.addLast(Pending(node, if (ordered.size > 1) rails + HistoryRail(if (i == ordered.lastIndex) "elbow" else "tee", node.onCurrentPath) else rails))
        }
    }
    push(nodes.filter { it.parentId == null || it.parentId !in ids }, emptyList())
    while (stack.isNotEmpty()) {
        val (node, rails) = stack.removeLast()
        if (!visited.add(node.id)) continue
        val descendants = children[node.id].orEmpty()
        val matches = query.isBlank() || node.searchText.contains(query, true) || (node.searchText.isBlank() && node.title.take(200).contains(query, true)) || node.label.orEmpty().contains(query, true) || historyType(node).second.contains(query, true) || node.type.contains(query, true) || node.role.orEmpty().contains(query, true)
        if (historyVisible(node, filter) && matches) result += HistoryRow(node, rails, descendants.size)
        push(descendants, rails.map { if (it.kind == "tee") HistoryRail("line", it.current) else if (it.kind == "elbow") HistoryRail("blank") else it })
    }
    return result
}
private fun historyPath(nodes: List<SessionHistoryNode>, leafId: String?): List<SessionHistoryNode> {
    val byId = nodes.associateBy { it.id }
    val path = mutableListOf<SessionHistoryNode>()
    val seen = hashSetOf<String>()
    var node = byId[leafId]
    while (node != null && seen.add(node.id)) { path += node; node = byId[node.parentId] }
    return path.asReversed()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySurface(title: String, onDismiss: () -> Unit, full: Boolean = false, dismissible: Boolean = true, actions: @Composable RowScope.() -> Unit = {}, footer: (@Composable ColumnScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalAgentColors.current
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val allowDismiss by rememberUpdatedState(dismissible)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || allowDismiss }), modifier = Modifier.widthIn(max = AgentPageMaxWidth), containerColor = c.surface, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), dragHandle = null) {
        Column(Modifier.fillMaxWidth().then(if (full) Modifier.height(windowHeight * .9f) else Modifier.heightIn(max = windowHeight * .9f))) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp, bottom = 6.dp).size(32.dp, 4.dp).background(c.outline.copy(alpha = .5f), RoundedCornerShape(2.dp)))
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                actions(); AgentIconButton("close", "关闭$title", onDismiss)
            }
            Column(Modifier.fillMaxWidth().weight(1f, fill = full), content = content)
            if (footer != null) {
                HorizontalDivider(color = c.surfaceContainerHigh)
                Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp), content = footer)
            }
        }
    }
}

@Composable
private fun HistorySearch(value: String, onChange: (String) -> Unit, onClear: () -> Unit) {
    val c = LocalAgentColors.current
    var focused by remember { mutableStateOf(false) }
    TextField(value, onChange, Modifier.fillMaxWidth().heightIn(min = 56.dp).onFocusChanged { focused = it.isFocused }.border(2.dp, if (focused) c.primary else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(28.dp)), placeholder = { Text("搜索内容开头、类型或标签", fontSize = 18.sp) }, leadingIcon = { Symbol("search", tint = c.onSurfaceVariant) }, trailingIcon = { if (value.isNotEmpty()) AgentIconButton("close", "清除搜索", onClear) }, singleLine = true, shape = RoundedCornerShape(28.dp), colors = TextFieldDefaults.colors(focusedContainerColor = c.surface, unfocusedContainerColor = c.surfaceContainer, focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent), textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp))
}
@Composable
private fun historyTonalColors() = ButtonDefaults.filledTonalButtonColors(containerColor = LocalAgentColors.current.primaryContainer, contentColor = LocalAgentColors.current.onPrimaryContainer)
@Composable
private fun historyFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = LocalAgentColors.current.surfaceContainer,
    unfocusedContainerColor = LocalAgentColors.current.surfaceContainer,
    disabledContainerColor = LocalAgentColors.current.surfaceContainer,
    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
    disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
)
@Composable
private fun HistoryChip(title: String, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true, icon: String? = null) {
    val c = LocalAgentColors.current
    FilterChip(selected, onClick, label = { Text(title, fontSize = 16.sp) }, modifier = Modifier.height(40.dp), enabled = enabled, shape = RoundedCornerShape(12.dp), leadingIcon = if (selected || icon != null) ({ Symbol(if (selected) "check" else icon!!, size = 20.dp) }) else null, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.primaryContainer, selectedLabelColor = c.onPrimaryContainer, selectedLeadingIconColor = c.onPrimaryContainer))
}

@Composable
private fun HistorySegments(options: List<Pair<String, String>>, selected: String, enabled: Boolean = true, onSelect: (String) -> Unit) {
    val c = LocalAgentColors.current
    Row(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(24.dp)).border(1.dp, c.outline, RoundedCornerShape(24.dp))) {
        options.forEachIndexed { index, (key, title) ->
            if (index > 0) Box(Modifier.fillMaxHeight().width(1.dp).background(c.outline))
            val chosen = selected == key
            val background by animateColorAsState(if (chosen) c.primaryContainer else c.surface, tween(300, easing = EmphEasing))
            Row(Modifier.weight(1f).fillMaxHeight().background(background).clickable(enabled = enabled) { onSelect(key) }.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                AnimatedVisibility(chosen, enter = expandHorizontally(tween(350, easing = EmphEasing)), exit = shrinkHorizontally(tween(250, easing = EmphEasing))) {
                    Row { Symbol("check", size = 20.dp, tint = c.onPrimaryContainer); Spacer(Modifier.width(6.dp)) }
                }
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, color = if (enabled) c.onSurface else c.outline)
            }
        }
    }
}
@Composable
private fun HistoryNavigation(selected: String?, rows: List<HistoryRow>, leaf: String?, onNavigate: (String) -> Unit) {
    val c = LocalAgentColors.current
    val index = rows.indexOfFirst { it.node.id == selected }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 8.dp)) {
    val iconWidth = ((maxWidth - 140.dp) / 5).coerceAtMost(48.dp)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(Triple("previousUser", "keyboard_double_arrow_up", "上一个请求"), Triple("previous", "keyboard_arrow_up", "上一条消息"), Triple("next", "keyboard_arrow_down", "下一条消息"), Triple("nextUser", "keyboard_double_arrow_down", "下一个请求"), Triple("sibling", "alt_route", "切换兄弟分支")).forEach { (key, icon, description) ->
            val enabled = when (key) {
                "previousUser" -> rows.take(index.coerceAtLeast(0)).any { historyIsUser(it.node) }
                "nextUser" -> rows.drop(index + 1).any { historyIsUser(it.node) }
                "previous" -> index > 0
                "next" -> index < rows.lastIndex
                else -> selected != null
            }
            IconButton({ onNavigate(key) }, Modifier.width(iconWidth).height(44.dp).background(c.surfaceContainer, RoundedCornerShape(14.dp)), enabled = enabled) { Symbol(icon, tint = if (enabled) c.onSurfaceVariant else c.outline.copy(alpha = .38f), contentDescription = description) }
        }
        Spacer(Modifier.weight(1f))
        FilledTonalButton({ onNavigate("current") }, enabled = leaf != null, modifier = Modifier.width(116.dp).height(44.dp), contentPadding = PaddingValues(horizontal = 10.dp), colors = historyTonalColors()) {
            Symbol("my_location", size = 20.dp); Spacer(Modifier.width(4.dp)); Text("当前位置", fontSize = 16.sp, maxLines = 1)
        }
    }
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryTreeRow(row: HistoryRow, selected: Boolean, leaf: Boolean, query: String, onClick: () -> Unit) {
    val c = LocalAgentColors.current
    val node = row.node
    val color by animateColorAsState(if (selected) c.primaryContainer else c.surface, tween(250, easing = EmphEasing))
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(color).clickable(onClick = onClick).heightIn(min = 52.dp).padding(top = 10.dp, bottom = 10.dp, end = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (row.rails.isNotEmpty()) Canvas(Modifier.width((row.rails.size * 16).dp).height(52.dp)) {
            val unit = 16.dp.toPx(); val xOffset = 7.dp.toPx(); val stroke = 2.dp.toPx()
            row.rails.forEachIndexed { index, rail ->
                val x = index * unit + xOffset
                val tint = if (rail.current) c.primary else c.surfaceContainerHighest
                if (rail.kind == "line" || rail.kind == "tee") drawLine(tint, Offset(x, -10.dp.toPx()), Offset(x, size.height + 10.dp.toPx()), stroke)
                if (rail.kind == "elbow") drawLine(tint, Offset(x, -10.dp.toPx()), Offset(x, size.height / 2), stroke)
                if (rail.kind == "tee" || rail.kind == "elbow") drawLine(tint, Offset(x, size.height / 2), Offset(x + 9.dp.toPx(), size.height / 2), stroke)
            }
        }
        Box(Modifier.size(32.dp).background(if (node.onCurrentPath) c.primaryContainer else c.surfaceContainer, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Symbol(historyType(node).first, size = 20.dp, tint = if (node.onCurrentPath) c.onPrimaryContainer else c.onSurfaceVariant)
        }
        Column(Modifier.weight(1f)) {
            val title = historyTitle(node)
            val annotated = buildAnnotatedString {
                append(title)
                if (query.isNotBlank()) {
                    var start = title.indexOf(query, ignoreCase = true)
                    while (start >= 0) {
                        addStyle(SpanStyle(background = c.warnContainer), start, start + query.length)
                        start = title.indexOf(query, start + query.length, ignoreCase = true)
                    }
                }
            }
            Text(annotated, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = c.onSurface.copy(alpha = if (node.onCurrentPath || selected) 1f else .7f))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(top = 2.dp)) {
                Text(historyType(node).second, fontSize = 15.sp, color = c.onSurfaceVariant)
                if (node.timestamp.isNotBlank()) Text(explorerTimestamp(node.timestamp), fontSize = 15.sp, color = c.onSurfaceVariant)
                node.label?.takeIf { it.isNotBlank() }?.let { HistoryTag("bookmark", it) }
                if (row.children > 1) HistoryTag("call_split", "${row.children} 个分支", neutral = true)
                if (leaf) HistoryTag("edit", "续写位置", success = true)
            }
        }
    }
}
@Composable
private fun HistoryTag(icon: String, title: String, neutral: Boolean = false, success: Boolean = false) {
    val c = LocalAgentColors.current
    Row(Modifier.background(if (success) c.tertiaryContainer else if (neutral) c.surfaceContainerHigh else c.primaryContainer, RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Symbol(icon, size = 16.dp, tint = if (success) c.tertiary else c.onPrimaryContainer)
        Text(title, fontSize = 14.sp, color = if (success) c.tertiary else c.onSurfaceVariant)
    }
}
@Composable
private fun HistoryMenuItem(title: String, icon: String, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(title, fontSize = 17.sp) }, onClick = onClick, enabled = enabled, leadingIcon = { Symbol(icon, tint = LocalAgentColors.current.onSurfaceVariant) }, modifier = Modifier.heightIn(min = 56.dp))
}
@Composable
private fun HistoryHint(icon: String, text: String) {
    val c = LocalAgentColors.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Symbol(icon, size = 20.dp, tint = c.onSurfaceVariant)
        Text(text, color = c.onSurfaceVariant, fontSize = 16.sp, lineHeight = 24.sp)
    }
}
@Composable
private fun HistoryBanner(text: String, error: Boolean = false, warning: Boolean = false, action: String? = null, onAction: () -> Unit = {}) {
    val c = LocalAgentColors.current
    Surface(color = if (error) c.errorContainer else if (warning) c.warnContainer else c.surfaceContainer, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Symbol(if (error) "error" else if (warning) "warning" else "info", tint = if (error) c.error else if (warning) c.warn else c.primary)
            Column(Modifier.weight(1f)) {
                SelectionContainer { Text(text, fontSize = 16.sp) }
                if (action != null) TextButton(onAction) { Text(action) }
            }
        }
    }
}
@Composable
private fun HistoryEmpty(icon: String, text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Symbol(icon, size = 48.dp, tint = LocalAgentColors.current.outline)
        Text(text, color = LocalAgentColors.current.onSurfaceVariant)
        if (action != null) TextButton(onAction) { Text(action) }
    }
}

@Composable
private fun HistoryPreview(explorer: SessionExplorer, node: SessionHistoryNode, sessionId: String, controller: AgentSessionController, expanded: MutableMap<String, Boolean>, onDismiss: () -> Unit, onDetail: (FeedDetail) -> Unit) {
    var raw by rememberSaveable(sessionId, node.id) { mutableStateOf(false) }
    val c = LocalAgentColors.current
    HistorySurface(historyType(node).second, onDismiss, full = true) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HistorySegments(historyPreviewTabs, if (raw) "raw" else "body") { raw = it == "raw" }
            HistoryDetailContent(explorer, node, if (raw) HistoryDetailKind.RAW else HistoryDetailKind.PREVIEW) { loaded ->
                val ask = loaded.ask
                if (raw) {
                    if (loaded.rawRecord.isBlank()) HistoryBanner("原生没有返回这条节点的原始记录。", error = true)
                    else {
                        TextButton({ onDetail(FeedDetail(node.id, "原始记录", loaded.rawRecord, "json")) }) { Symbol("open_in_full", size = 20.dp); Spacer(Modifier.width(8.dp)); Text("全屏查看原始记录") }
                        SelectionContainer { HighlightedCode(loaded.rawRecord, "json", fontSize = 15.sp, lineHeight = 25.5.sp) }
                    }
                } else if (loaded.feed.isNotEmpty()) {
                    loaded.feed.forEach { item -> HistoryReadonlyFeed(item, controller, expanded, onDetail) }
                } else if (ask != null) {
                    HistoryReadonlyFeed(ask, controller, expanded, onDetail)
                } else if (loaded.text.isNotBlank()) {
                    if (historyIsUser(node)) SelectionContainer { Text(loaded.text, fontSize = 18.sp) }
                    else Markdown(loaded.text)
                } else HistoryEmpty("visibility_off", "这个节点没有可见正文，请查看原始记录")
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                listOf("节点" to node.id, "父节点" to (node.parentId ?: "根节点"), "时间" to node.timestamp.ifBlank { "未记录" }, "会话" to sessionId, "类型" to node.type, "状态" to if (node.onCurrentPath) "在当前路径上" else "在其他分支上").forEachIndexed { index, (key, value) ->
                    Row(Modifier.fillMaxWidth().background(c.surfaceContainerLow, RoundedCornerShape(topStart = if (index == 0) 20.dp else 6.dp, topEnd = if (index == 0) 20.dp else 6.dp, bottomStart = if (index == 5) 20.dp else 6.dp, bottomEnd = if (index == 5) 20.dp else 6.dp)).padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(key, Modifier.width(70.dp), color = c.onSurfaceVariant, fontSize = 16.sp)
                        SelectionContainer { Text(value, fontSize = 15.sp, fontFamily = if (key in listOf("节点", "父节点", "会话", "类型")) LocalAgentMono.current else MaterialTheme.typography.bodyMedium.fontFamily) }
                    }
                }
            }
        }
    }
}
@Composable
private fun HistoryDetailContent(explorer: SessionExplorer, node: SessionHistoryNode, kind: HistoryDetailKind, content: @Composable (SessionHistoryDetail) -> Unit) {
    var loaded by remember(explorer, node.id, node.version, kind) { mutableStateOf<SessionHistoryDetail?>(null) }
    var failure by remember(explorer, node.id, node.version, kind) { mutableStateOf<String?>(null) }
    var attempt by remember(explorer, node.id, node.version, kind) { mutableIntStateOf(0) }
    LaunchedEffect(explorer, node.id, node.version, kind, attempt) {
        loaded = null
        failure = null
        try {
            if (attempt > 0) explorer.refreshHistory()
            loaded = explorer.loadHistoryDetail(node.id, kind)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = error.message ?: "节点详情读取失败"
        }
    }
    val result = loaded
    when {
        failure != null -> HistoryBanner(failure!!, error = true, action = "重试", onAction = { attempt++ })
        result == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
        else -> content(result)
    }
}

@Composable
private fun HistoryReadonlyFeed(item: FeedItem, controller: AgentSessionController, expanded: MutableMap<String, Boolean>, onDetail: (FeedDetail) -> Unit) {
    when (item) {
        is ApprovalCardItem -> {
            Text(item.title, fontWeight = FontWeight.Medium)
            Text(item.description)
            SelectionContainer { HighlightedCode(item.commandPreview, "bash") }
            Text("历史审批：${item.resolution?.name ?: "没有已保存决定"}", color = LocalAgentColors.current.onSurfaceVariant)
        }
        is StepGroupNodeItem -> {
            Text(plainTitle(item.title), fontWeight = FontWeight.Medium)
            item.children.forEach { HistoryReadonlyFeed(it, controller, expanded, onDetail) }
        }
        is AskCardItem -> if (item.isResolved) FeedNode(item, controller, expanded, onDetail) else {
            Text(item.title, fontWeight = FontWeight.Medium)
            item.questions.forEach { question ->
                Text(question.question, fontSize = 18.sp)
                question.options.forEach { option ->
                    Text(option.label, fontWeight = FontWeight.Medium)
                    option.description?.let { Text(it, color = LocalAgentColors.current.onSurfaceVariant) }
                    option.preview?.let { AskRichPreview(it, Modifier.fillMaxWidth()) }
                }
            }
            HistoryHint("history", "这条历史记录没有已保存的回答。查看不会提交答案，重新回答请使用单独入口。")
        }
        else -> FeedNode(item, controller, expanded, onDetail)
    }
}

@Composable
private fun HistoryLabel(node: SessionHistoryNode, busy: Boolean, error: String?, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var value by rememberSaveable(node.id) { mutableStateOf(node.label.orEmpty()) }
    HistorySurface("节点标签", onDismiss, dismissible = !busy, footer = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (!node.label.isNullOrBlank()) TextButton({ onSave(null) }, enabled = !busy) { Text("清除", color = LocalAgentColors.current.error) }
            Spacer(Modifier.weight(1f))
            Button({ onSave(value.trim().ifEmpty { null }) }, enabled = !busy, modifier = Modifier.height(48.dp)) { Text(if (busy) "正在保存" else "保存") }
        }
    }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            TextField(value, { value = it }, Modifier.fillMaxWidth(), enabled = !busy, placeholder = { Text("例如 可用版本") }, shape = RoundedCornerShape(16.dp), singleLine = true, colors = historyFieldColors())
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { HistoryBanner(it, error = true) }
        }
    }
}
@Composable
private fun HistorySwitch(node: SessionHistoryNode, hasDraft: Boolean, preset: Boolean, busy: Boolean, error: String?, onDismiss: () -> Unit, onCancel: () -> Unit, onSwitch: (HistorySummary, Boolean) -> Unit) {
    var mode by rememberSaveable(node.id, preset) { mutableStateOf(if (preset) "sum" else "none") }
    var focus by rememberSaveable(node.id) { mutableStateOf("") }
    var replace by rememberSaveable(node.id) { mutableStateOf(!hasDraft) }
    val user = historyIsUser(node)
    HistorySurface(if (busy && mode != "none") "正在生成摘要" else if (user) "回到这条请求之前" else "切换到这里", { if (busy) onCancel() else onDismiss() }, dismissible = !busy, footer = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(if (busy) onCancel else onDismiss) { Text(if (busy) "取消导航" else "取消") }
            Spacer(Modifier.weight(1f))
            if (error != null && mode != "none" && !busy) TextButton({ mode = "none"; onSwitch(HistorySummary(), replace) }) { Text("不摘要切换") }
            Button({ onSwitch(HistorySummary(mode != "none", focus.takeIf { mode == "focus" && it.isNotBlank() }), replace) }, enabled = !busy && (mode != "focus" || focus.isNotBlank()), modifier = Modifier.height(48.dp)) { Text(if (busy) "处理中" else if (error != null) "重试" else "切换") }
        }
    }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            if (hasDraft && user) {
                HistoryBanner("输入框里有未发送的草稿。请明确选择保留还是替换。", warning = true)
                HistoryChoice("保留我的草稿，不填回原输入", !replace, !busy) { replace = false }
                HistoryChoice("用原输入和图片替换草稿", replace, !busy) { replace = true }
            }
            Text("离开的路径", Modifier.padding(top = 20.dp, bottom = 8.dp), color = LocalAgentColors.current.onSurfaceVariant, fontSize = 16.sp)
            HistorySegments(historySummaryModes, mode, enabled = !busy) { mode = it }
            AnimatedVisibility(mode == "focus") {
                TextField(focus, { focus = it }, Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 72.dp), enabled = !busy, minLines = 2, placeholder = { Text("例如 保留编译失败的原因") }, shape = RoundedCornerShape(16.dp), colors = historyFieldColors())
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 16.dp))
            error?.let { HistoryBanner(it, error = true) }
            HistoryBanner("不会回滚工作区文件，已执行的写入和命令不会撤销。", warning = true)
        }
    }
}
@Composable
private fun HistoryChoice(title: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = LocalAgentColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(if (selected) 28.dp else 20.dp)).background(if (selected) c.primaryContainer else c.surfaceContainerLow).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Symbol(if (selected) "radio_button_checked" else "radio_button_unchecked", tint = if (selected) c.primary else c.outline)
        Text(title, fontSize = 17.sp)
    }
}

@Composable
private fun HistoryRewind(nodes: List<SessionHistoryNode>, path: List<SessionHistoryNode>, leaf: String?, busy: Boolean, onDismiss: () -> Unit, onChoose: (SessionHistoryNode) -> Unit, onPreview: (SessionHistoryNode) -> Unit) {
    var shown by rememberSaveable { mutableIntStateOf(4) }
    var currentOnly by rememberSaveable { mutableStateOf(true) }
    var hideTools by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val source = if (currentOnly) path else nodes
    val candidates = source.filter { it.navigable && (!hideTools || !historyIsTool(it)) && (query.isBlank() || it.searchText.contains(query, true) || it.title.take(200).contains(query, true) || historyType(it).second.contains(query, true)) }
    var selected by rememberSaveable { mutableStateOf(leaf) }
    var positioned by rememberSaveable { mutableStateOf(false) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val selectedIndex = candidates.indexOfFirst { it.id == selected }
    fun select(index: Int) {
        candidates.getOrNull(index)?.let { node ->
            selected = node.id
            val needed = candidates.size - index
            if (needed > shown) shown = needed
            scope.launch { withFrameNanos { }; list.animateScrollToItem((index - (candidates.size - shown).coerceAtLeast(0)).coerceAtLeast(0) + if (shown < candidates.size) 1 else 0) }
        }
    }
    LaunchedEffect(candidates, leaf) {
        if (!positioned && candidates.isNotEmpty()) {
            positioned = true
            val index = candidates.indexOfFirst { it.id == selected }.takeIf { it >= 0 } ?: candidates.lastIndex
            list.scrollToItem((index - (candidates.size - shown).coerceAtLeast(0)).coerceAtLeast(0) + if (shown < candidates.size) 1 else 0)
        }
    }
    HistorySurface("回退到", onDismiss, full = true, footer = {
        val node = candidates.firstOrNull { it.id == selected }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AgentIconButton("visibility", "查看选中消息", { node?.let(onPreview) }, enabled = node != null)
            Spacer(Modifier.weight(1f))
            Button({ node?.let(onChoose) }, enabled = node != null && node.id != leaf && !busy) { Text(if (node?.id == leaf) "已在这里" else "回退到这里") }
        }
    }) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            HistorySearch(query, { query = it; shown = 4 }, { query = "" })
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryChip("当前路径", currentOnly, { currentOnly = !currentOnly }); HistoryChip("隐藏工具结果", hideTools, { hideTools = !hideTools })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AgentIconButton("keyboard_double_arrow_up", "上一个请求", { select((selectedIndex - 1 downTo 0).firstOrNull { historyIsUser(candidates[it]) } ?: selectedIndex) }, enabled = selectedIndex > 0)
                AgentIconButton("keyboard_arrow_up", "上一条", { select(selectedIndex - 1) }, enabled = selectedIndex > 0)
                AgentIconButton("keyboard_arrow_down", "下一条", { select(selectedIndex + 1) }, enabled = selectedIndex < candidates.lastIndex)
                AgentIconButton("keyboard_double_arrow_down", "下一个请求", { select((selectedIndex + 1..candidates.lastIndex).firstOrNull { historyIsUser(candidates[it]) } ?: selectedIndex) }, enabled = selectedIndex < candidates.lastIndex)
                Text("${candidates.size} 条", fontSize = 15.sp, color = LocalAgentColors.current.onSurfaceVariant)
            }
            LazyColumn(state = list, modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (shown < candidates.size) item { TextButton({ shown += 4 }, Modifier.fillMaxWidth()) { Symbol("expand_less", size = 20.dp); Text("加载更早的 ${minOf(4, candidates.size - shown)} 条") } }
                items(candidates.takeLast(shown), key = { it.id }) { node ->
                    HistoryListRow(node, node.id == selected, "${historyType(node).second} · ${explorerTimestamp(node.timestamp)}${if (node.id == leaf) " · 当前位置" else ""}", { selected = node.id }, {
                        val siblings = nodes.filter { it.parentId == node.parentId }
                        if (siblings.size > 1) AgentIconButton("alt_route", "查看兄弟延续", {
                            currentOnly = false
                            val next = siblings[(siblings.indexOf(node) + 1) % siblings.size]
                            selected = next.id
                            shown = nodes.size
                        })
                    })
                }
                if (candidates.isEmpty()) item { HistoryEmpty("search_off", "没有可回退的匹配记录") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryTurns(explorer: SessionExplorer, path: List<SessionHistoryNode>, onDismiss: () -> Unit, onChoose: (SessionHistoryNode) -> Unit, onCopy: (String, String) -> Unit, onOpen: (String) -> Unit, error: String?, notice: String?) {
    var shown by rememberSaveable { mutableIntStateOf(5) }
    val turns = remember(path) { path.filter { it.hasCopyContent }.asReversed() }
    val assistant = path.lastOrNull { historyIsAssistant(it) }
    HistorySurface("复制", onDismiss, full = true) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            if (assistant != null) HistoryDetailContent(explorer, assistant, HistoryDetailKind.COPY) { loaded ->
                HistoryQuickCopy(loaded.copyBlocks, onCopy, onOpen)
            } else HistoryQuickCopy(emptyList(), onCopy, onOpen)
            error?.let { HistoryBanner(it, error = true) }; notice?.let { HistoryBanner(it) }
            Text("选择一条消息", Modifier.padding(vertical = 12.dp), color = LocalAgentColors.current.primary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(turns.take(shown), key = { it.id }) { node -> HistoryListRow(node, false, "${historyType(node).second} · ${node.copyBlockCount?.let { "$it 项可复制" } ?: "可复制内容"}", { onChoose(node) }) }
                if (turns.isEmpty()) item { HistoryEmpty("content_paste_off", "当前路径没有可复制的消息") }
                if (shown < turns.size) item { TextButton({ shown += 5 }, Modifier.fillMaxWidth()) { Symbol("expand_more", size = 20.dp); Text("加载更早的消息") } }
            }
        }
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryQuickCopy(blocks: List<SessionCopyBlock>, onCopy: (String, String) -> Unit, onOpen: (String) -> Unit) {
    val code = blocks.lastOrNull { it.icon == "code" }?.text
    val command = blocks.lastOrNull { it.icon == "terminal" }?.text
    val link = blocks.lastOrNull { it.icon == "link" }?.text
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HistoryChip("最近的代码", false, { code?.let { onCopy(it, "代码，不含围栏") } }, code != null, "code")
        HistoryChip("最近的命令", false, { command?.let { onCopy(it, "命令") } }, command != null, "terminal")
        HistoryChip("最近的链接", false, { link?.let { onCopy(it, "完整链接") } }, link != null, "link")
        HistoryChip("打开链接", false, { link?.let(onOpen) }, link != null, "open_in_new")
    }
}

@Composable
private fun HistoryBlocks(explorer: SessionExplorer, node: SessionHistoryNode, onDismiss: () -> Unit, onCopy: (String, String) -> Unit, onOpen: (String) -> Unit, error: String?, notice: String?) {
    var selected by rememberSaveable(node.id) { mutableIntStateOf(-1) }
    HistorySurface("复制内容", onDismiss, full = true) {
        HistoryDetailContent(explorer, node, HistoryDetailKind.COPY) { loaded ->
        val blocks = loaded.copyBlocks
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                error?.let { HistoryBanner(it, error = true) }; notice?.let { HistoryBanner(it) }
            }
            if (blocks.isEmpty()) item { HistoryEmpty("content_paste_off", "这条消息没有可复制的文本") }
            items(blocks.size) { index ->
                val block = blocks[index]
                Surface(color = LocalAgentColors.current.surfaceContainerLow, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Symbol(block.icon, tint = LocalAgentColors.current.onSurfaceVariant)
                            Text(block.title, Modifier.weight(1f), fontSize = 18.sp)
                            AgentIconButton("visibility", "预览${block.title}", { selected = if (selected == index) -1 else index })
                            AgentIconButton("content_copy", "复制${block.title}", { onCopy(block.text, block.title) }, enabled = block.text.isNotEmpty())
                        }
                        if (selected == index) SelectionContainer { Text(block.text, fontFamily = LocalAgentMono.current, fontSize = 15.sp, lineHeight = 25.sp) }
                        else Text(block.text.lineSequence().firstOrNull().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = LocalAgentMono.current, fontSize = 14.sp, color = LocalAgentColors.current.onSurfaceVariant)
                        if (block.icon == "link" && (block.text.startsWith("https://", true) || block.text.startsWith("http://", true))) TextButton({ onOpen(block.text) }) { Symbol("open_in_new", size = 20.dp); Spacer(Modifier.width(6.dp)); Text("打开链接") }
                    }
                }
            }
        }
        }
    }
}
@Composable
private fun HistoryListRow(node: SessionHistoryNode, selected: Boolean, subtitle: String, onClick: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    val c = LocalAgentColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(20.dp)).background(if (selected) c.primaryContainer else c.surfaceContainerLow).clickable(onClick = onClick).heightIn(min = 72.dp).padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(44.dp).background(if (historyIsUser(node)) c.primaryContainer else c.surfaceContainerHigh, RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) { Symbol(historyType(node).first, tint = c.onSurfaceVariant) }
        Column(Modifier.weight(1f)) {
            Text(historyTitle(node), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 18.sp)
            Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, color = c.onSurfaceVariant)
        }
        trailing()
    }
}
