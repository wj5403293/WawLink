package dev.wawlink.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onPlaced
import dev.wawlink.protocol.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Host-neutral chat surface: all effects are routed through the supplied controller or host callbacks. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AgentChatScreen(
    controller: AgentSessionController,
    providers: ProviderConfigRepository,
    modelRepository: AgentManagementRepository,
    onNavigateToSessionList: () -> Unit,
    onNewSession: () -> Unit,
    onOpenProviderSettings: () -> Unit,
    onOpenAgentSettings: () -> Unit,
    onOpenFrameworkSettings: () -> Unit,
    onPickFiles: suspend () -> List<AttachmentFile>,
    onPickContainerFiles: suspend () -> List<AttachmentFile>,
    onOpenProjectPlugins: () -> Unit,
    onOpenProjectSkills: () -> Unit,
    explorer: SessionExplorer,
    onOpenAgentHub: () -> Unit,
    onFork: suspend (String) -> String,
    onOpenFork: (String) -> Unit,
    navigation: AgentChatNavigation,
    appearance: AgentAppearance = AgentAppearance(),
    onRequestOmpRepair: (() -> Unit)? = null,
) {
    val inheritedColors = if (LocalAgentThemeProvided.current && LocalAgentAppearance.current == appearance) LocalAgentColors.current else null
    AgentTheme(appearance, colors = inheritedColors) {
        val state by controller.state.collectAsState()
        val c = LocalAgentColors.current
        val scope = rememberCoroutineScope()
        val list = key(state.id) { rememberLazyListState() }
        val expanded = rememberSaveable(state.id, saver = BooleanStateMapSaver) { mutableStateMapOf<String, Boolean>() }
        val previouslyRunning = rememberSaveable(state.id, saver = BooleanStateMapSaver) { mutableStateMapOf<String, Boolean>() }
        LaunchedEffect(state.id, state.feed) { updateGroupExpansion(state.feed, expanded, previouslyRunning) }
        var settingsMenu by remember { mutableStateOf(false) }
        var detail by remember(state.id) { mutableStateOf<FeedDetail?>(null) }
        var historyOpen by rememberSaveable(state.id) { mutableStateOf(false) }
        var historyFocus by rememberSaveable(state.id) { mutableStateOf<String?>(null) }
        val messageActions = remember(explorer, state.id) { MessageHistoryActionsState(explorer) { controller.state.value.isRunning } }
        val messageScope = key(explorer, state.id) { rememberCoroutineScope() }
        val historyStates = rememberSaveableStateHolder()
        val draft = rememberComposerDraft(state.id)
        SideEffect {
            navigation.backHandler = {
                when {
                    messageActions.action != null -> {
                        if (!messageActions.dismiss()) messageScope.launch { messageActions.cancelNavigation() }
                        true
                    }
                    historyOpen -> { historyOpen = false; true }
                    detail != null -> { detail = null; true }
                    settingsMenu -> { settingsMenu = false; true }
                    else -> false
                }
            }
        }
        DisposableEffect(navigation) { onDispose { navigation.backHandler = null } }
        var composerHeight by remember { mutableStateOf(156.dp) }
        var followTail by rememberSaveable(state.id) { mutableStateOf(true) }
        val density = LocalDensity.current
        val nodeBounds = remember(state.id) { mutableStateMapOf<String, FeedNodeBounds>() }
        var showCompacted by rememberSaveable(state.id, state.compactedThroughId, appearance.collapseCompacted) { mutableStateOf(!appearance.collapseCompacted) }
        val compactIndex = state.feed.indexOfFirst { it.id == state.compactedThroughId }
        val hasCompacted = compactIndex >= 0
        val visibleFeed = remember(state.feed, appearance.hideToolCalls, showCompacted, compactIndex) {
            state.feed.drop(if (hasCompacted && !showCompacted) compactIndex + 1 else 0)
                .filterNot { appearance.hideToolCalls && it is ToolNodeItem }
        }
        val feedIndex = remember(visibleFeed, hasCompacted) {
            buildMap { visibleFeed.forEachIndexed { index, item ->
                flattenedFeed(listOf(item)).forEach { put(it.id, index + if (hasCompacted) 1 else 0) }
            } }
        }
        val allNodes = remember(visibleFeed) { flattenedFeed(visibleFeed) }
        val itemsById = remember(allNodes) { allNodes.associateBy { it.id } }
        val clock = rememberFeedClock(state.isRunning || allNodes.any {
            it is ThinkingNodeItem && it.isStreaming || it is ToolNodeItem && it.state == NodeStatus.RUNNING || it is StepGroupNodeItem && it.state == NodeStatus.RUNNING
        })
        val plans = remember(visibleFeed) { visibleFeed.filterIsInstance<PlanNodeItem>() }
        val visibleRootRange = remember(list) {
            derivedStateOf {
                val rows = list.layoutInfo.visibleItemsInfo
                if (rows.isEmpty()) IntRange.EMPTY else rows.first().index..rows.last().index
            }
        }
        val pinCandidates = remember(visibleFeed, expanded, appearance.hideToolCalls, hasCompacted, visibleRootRange) {
            derivedStateOf {
                val range = visibleRootRange.value
                val offset = if (hasCompacted) 1 else 0
                val start = (range.first - offset).coerceIn(0, visibleFeed.size)
                val end = (range.last + 1 - offset).coerceIn(start, visibleFeed.size)
                val nodes = visibleFeedNodes(visibleFeed.subList(start, end), expanded, appearance.hideToolCalls)
                nodes.filterIsInstance<StepGroupNodeItem>().filter { it.isExpanded(expanded) } to
                    nodes.filter { (it is ThinkingNodeItem || it is ToolNodeItem) && it.isExpanded(expanded) }
            }
        }
        val pinHeight = with(density) { 56.dp.toPx() }
        val pinThreshold = with(density) { 8.dp.toPx() }
        val globalPinState = remember(plans, feedIndex, list, pinThreshold) {
            derivedStateOf {
                val layout = list.layoutInfo
                var low = 0
                var high = plans.size
                while (low < high) {
                    val middle = (low + high).ushr(1)
                    val node = plans[middle]
                    val position = layout.visibleItemsInfo.firstOrNull { it.key == node.id }
                    val index = feedIndex[node.id] ?: Int.MAX_VALUE
                    val passed = position?.let { it.offset + pinThreshold < 0 } ?: (list.firstVisibleItemIndex > index)
                    if (passed) low = middle + 1 else high = middle
                }
                plans.getOrNull(low - 1)
            }
        }
        val globalPin by globalPinState
        val parentPinState = remember(pinCandidates, nodeBounds, globalPinState, pinHeight, pinThreshold) {
            derivedStateOf {
                val line = if (globalPin != null) pinHeight else 0f
                val rows = list.layoutInfo.visibleItemsInfo
                pinCandidates.value.first.lastOrNull { node ->
                    nodeBounds[node.id]?.crossesPin(rows, line, pinHeight, pinThreshold) == true
                }
            }
        }
        val parentPin by parentPinState
        val contentPinState = remember(pinCandidates, nodeBounds, globalPinState, parentPinState, pinHeight, pinThreshold) {
            derivedStateOf {
                val line = (if (globalPin != null) pinHeight else 0f) + (if (parentPin != null) pinHeight else 0f)
                val rows = list.layoutInfo.visibleItemsInfo
                pinCandidates.value.second.lastOrNull { node ->
                    nodeBounds[node.id]?.crossesPin(rows, line, pinHeight, pinThreshold) == true
                }
            }
        }
        LaunchedEffect(list) {
            list.interactionSource.interactions.collect { interaction -> if (interaction is DragInteraction.Start) followTail = false }
        }
        LaunchedEffect(list) {
            snapshotFlow { list.isScrollInProgress }.distinctUntilChanged().drop(1).collect { scrolling ->
                if (!scrolling) followTail = !list.canScrollForward
            }
        }
        // Follow layout growth, not token events. Never reset a tall row to its beginning on each delta.
        LaunchedEffect(list) {
            snapshotFlow {
                if (followTail && !list.isScrollInProgress && list.canScrollForward) {
                    list.layoutInfo.totalItemsCount to list.layoutInfo.visibleItemsInfo.lastOrNull()?.let { it.index to it.size }
                } else null
            }.collect { target ->
                if (target != null && target.first > 0) {
                    val last = target.first - 1
                    if (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index != last) list.scrollToItem(last)
                    val layout = list.layoutInfo
                    val tail = layout.visibleItemsInfo.lastOrNull()
                    if (tail?.index == last) {
                        // A MAX_VALUE scroll can overflow LazyColumn's integer offsets and
                        // remeasure/dispose the entire history. Only consume actual growth.
                        val remaining = tail.offset.toFloat() + tail.size - layout.viewportEndOffset + layout.afterContentPadding
                        if (remaining > 0f) list.scrollBy(remaining)
                    }
                }
            }
        }
        fun scrollTo(id: String) {
            val index = feedIndex[id] ?: return
            followTail = false
            scope.launch {
                if (nodeBounds[id] == null || list.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                    list.animateScrollToItem(index)
                    withFrameNanos { }
                }
                val ancestorPins = (if (globalPin != null && globalPin?.id != id) 1 else 0) +
                    (if (parentPin != null && parentPin?.id != id && itemsById[id] !is PlanNodeItem) 1 else 0)
                nodeBounds[id]?.let { bounds ->
                    val owner = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == bounds.ownerId }
                    if (owner != null) list.animateScrollBy(owner.offset + bounds.top - ancestorPins * pinHeight - with(density) { 8.dp.toPx() })
                }
            }
        }
        fun anchorFeedItem(id: String) {
            followTail = false
            val index = feedIndex[id] ?: return
            val owner = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
            val bounds = nodeBounds[id]
            if (owner != null && bounds != null) {
                val pinned = (if (globalPin != null) pinHeight else 0f) + (if (parentPin != null) pinHeight else 0f)
                val top = owner.offset + bounds.top
                val inOwner = bounds.top
                val target = top.coerceAtLeast(pinned + with(density) { 8.dp.toPx() })
                // Apply with the new page's measurement, before a shorter page can clamp the old offset.
                list.requestScrollToItem(index, (inOwner - target).roundToInt())
            } else {
                list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
            }
        }
        LaunchedEffect(navigation.location, feedIndex) {
            val request = navigation.location ?: return@LaunchedEffect
            if (hasCompacted && !showCompacted) {
                showCompacted = true
                return@LaunchedEffect
            }
            if (request.id !in feedIndex) return@LaunchedEffect
            fun expandPath(nodes: List<FeedItem>): Boolean {
                for (node in nodes) {
                    if (node.id == request.id) return true
                    if (node is StepGroupNodeItem && expandPath(node.children)) {
                        expanded[node.id] = true
                        return true
                    }
                }
                return false
            }
            expandPath(state.feed)
            withFrameNanos { }
            scrollTo(request.id)
            navigation.located(request)
        }
        CompositionLocalProvider(LocalFeedClock provides clock, LocalFeedBounds provides nodeBounds,
            LocalFeedInteraction provides { followTail = false }, LocalFeedItemNavigation provides { id -> anchorFeedItem(id) },
            LocalFeedHistoryRunning provides state.isRunning,
            LocalFeedHistoryRequest provides { id, action ->
                if (action == FeedHistoryAction.LOCATE) { historyFocus = id; historyOpen = true }
                else messageScope.launch { messageActions.open(id, action) }
            }) {
        val scrolled by remember(list) { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 4 } }
        val barColor by animateColorAsState(if (scrolled) c.surfaceContainer else c.surface, tween(350, easing = EmphEasing))
        val topInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top)
        Surface(color = c.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(topInsets).background(barColor))
                Column(Modifier.widthIn(max = AgentPageMaxWidth).fillMaxSize().windowInsetsPadding(topInsets).navigationBarsPadding().imePadding()) {
                    Row(Modifier.fillMaxWidth().height(64.dp).background(barColor).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        AgentIconButton("forum", "会话列表", onNavigateToSessionList)
                        Text(state.title, Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        AgentIconButton("edit_square", "新建会话", onNewSession)
                        AgentIconButton("account_tree", "历史树", { historyFocus = null; historyOpen = true })
                        AgentIconButton("groups", "Agent 运行总览", onOpenAgentHub)
                        Box {
                            AgentIconButton("settings", "设置", { settingsMenu = true })
                            ManagementMenu(settingsMenu, { settingsMenu = false }) {
                                ManagementMenuItem(text = { Text("模型服务") }, leadingIcon = { Symbol("deployed_code") }, onClick = { settingsMenu = false; onOpenProviderSettings() })
                                ManagementMenuItem(text = { Text("Agent 设置") }, leadingIcon = { Symbol("smart_toy") }, onClick = { settingsMenu = false; onOpenAgentSettings() })
                                ManagementMenuItem(text = { Text("框架设置") }, leadingIcon = { Symbol("tune") }, onClick = { settingsMenu = false; onOpenFrameworkSettings() })
                                HorizontalDivider()
                                ManagementMenuItem(text = { Text("项目插件管理") }, leadingIcon = { Symbol("extension") }, onClick = { settingsMenu = false; onOpenProjectPlugins() })
                                ManagementMenuItem(text = { Text("项目技能管理") }, leadingIcon = { Symbol("school") }, onClick = { settingsMenu = false; onOpenProjectSkills() })
                            }
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        LazyColumn(state = list, modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 8.dp, bottom = composerHeight + 20.dp)) {
                            if (hasCompacted) item(key = "compacted-history") {
                                Row(Modifier.padding(horizontal = 16.dp).padding(bottom = 20.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surfaceContainerLow)
                                    .clickable { showCompacted = !showCompacted }.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Symbol("compress", tint = c.onSurfaceVariant)
                                    Text("已压缩的记录 · ${compactIndex + 1} 条", Modifier.weight(1f), fontSize = 16.sp, color = c.onSurfaceVariant)
                                    Symbol(if (showCompacted) "expand_less" else "expand_more", tint = c.onSurfaceVariant)
                                }
                            }
                            items(visibleFeed, key = { it.id }, contentType = { it::class.simpleName }) { item ->
                                var entered by rememberSaveable(item.id) { mutableStateOf(item is UserMessageItem) }
                                LaunchedEffect(item.id) { entered = true }
                                val enter by animateFloatAsState(if (entered) 1f else 0f, tween(600, easing = EmphDecelEasing))
                                val root = remember(item.id) { FeedRootCoordinates(item.id) }
                                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 20.dp).animateItem(
                                    fadeInSpec = if (item is UserMessageItem) null else tween(600, easing = EmphDecelEasing), placementSpec = null, fadeOutSpec = tween(200, easing = EmphAccelEasing))
                                    .onPlaced { root.coordinates = it }.graphicsLayer {
                                        translationY = (1f - enter) * with(density) { 24.dp.toPx() }; scaleX = .98f + .02f * enter; scaleY = scaleX
                                    }) {
                                    CompositionLocalProvider(LocalFeedRootCoordinates provides root) {
                                        FeedNode(item, controller, expanded) { request -> followTail = false; detail = request }
                                    }
                                }
                            }
                        }
                        // Geometry invalidates this small overlay, never the whole chat/composer scope.
                        FeedPins(globalPinState, parentPinState, contentPinState, state.isRunning,
                            Modifier.fillMaxWidth().align(Alignment.TopCenter).background(barColor),
                            onScrollTo = { scrollTo(it) },
                            onCollapse = { id -> followTail = false; expanded[id] = false; scrollTo(id) })
                        if (visibleFeed.isEmpty() && !hasCompacted && state.error == null) Column(Modifier.align(Alignment.Center).padding(bottom = composerHeight), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Symbol("neurology", size = 40.dp, tint = c.primary)
                            Text("开始新的任务", style = MaterialTheme.typography.titleLarge)
                            Text(state.workspace, fontFamily = LocalAgentMono.current, color = c.onSurfaceVariant, fontSize = 14.sp)
                        }
                        androidx.compose.animation.AnimatedVisibility(!followTail && list.canScrollForward, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = composerHeight + 12.dp),
                            enter = fadeIn(tween(300, easing = EmphDecelEasing)) + scaleIn(agentSpring()), exit = fadeOut(tween(150, easing = EmphAccelEasing))) {
                            FilledTonalIconButton({ followTail = true }) { Symbol("arrow_downward", contentDescription = "回到最新消息") }
                        }
                        var lastError by remember(state.id) { mutableStateOf(state.error) }
                        LaunchedEffect(state.error) { state.error?.let { lastError = it } }
                        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { size -> composerHeight = with(density) { size.height.toDp() } }) {
                            AnimatedVisibility(state.error != null,
                                enter = expandVertically(tween(320, easing = EmphDecelEasing)) + fadeIn(tween(220)),
                                exit = shrinkVertically(tween(240, easing = EmphAccelEasing)) + fadeOut(tween(150))) {
                                ChatErrorBanner(state.error ?: lastError.orEmpty(), state.modelId, controller::dismissError,
                                    onOpenProviderSettings, onOpenAgentSettings, onRequestOmpRepair)
                            }
                            ComposerArea(controller, state, providers, modelRepository, onOpenProviderSettings, onPickFiles, onPickContainerFiles, { followTail = true }, draft = draft)
                        }
                    }
                }
            }
        }
        }
        detail?.let { request ->
            if (request.markdown) {
                val thinking = itemsById[request.id] as? ThinkingNodeItem
                ThinkingDetailScreen(request.id, thinking?.text ?: request.text, thinking?.isStreaming == true, { detail = null })
            } else {
                val tool = itemsById[request.id] as? ToolNodeItem
                CodeDetailScreen(request.title, tool?.codeText ?: request.text, request.language, { detail = null })
            }
        }
        if (historyOpen) historyStates.SaveableStateProvider("history:${state.id}") {
            SessionHistorySheet(explorer, controller, onDismiss = { historyOpen = false },
                onLocate = { id -> historyOpen = false; navigation.locate(id) },
                onRestorePrompt = { prompt, replace -> draft.restore(prompt, replace) }, hasDraft = draft.hasContent,
                onFork = onFork, onOpenFork = { id -> historyOpen = false; onOpenFork(id) },
                focusChatItemId = historyFocus)
        }
        MessageHistoryActionSheet(messageActions, draft.hasContent, state.isRunning) { prompt, replace -> draft.restore(prompt, replace) }
    }
}

internal enum class ChatErrorAction(val icon: String, val title: String) {
    OPEN_OMP_REPAIR("build", "修复 OMP"),
    OPEN_PROVIDER_SETTINGS("deployed_code", "配置模型服务"),
    OPEN_AGENT_SETTINGS("smart_toy", "Agent 设置"),
    DISMISS("close", "关闭报错"),
}

/** Explicit diagnoses precede the missing-model fallback when several problems overlap. */
internal fun diagnoseChatError(error: String, modelId: String): List<ChatErrorAction> =
    chatErrorCandidates(error, modelId).take(2)

private val chatErrorStatusCode = Regex("(?<![0-9])(?:401|403|404|429)(?![0-9])")

private fun chatErrorCandidates(error: String, modelId: String): List<ChatErrorAction> {
    val text = error.lowercase()
    val ompFailure = listOf("omp 缺失", "安装损坏", "omp 输出流已关闭", "omp 通信响应损坏",
        "omp 进程未启动", "omp 进程异常", "omp 通信失效", "cannot find module").any(text::contains) ||
        ("enoent" in text && "omp" in text)
    val sessionFailure = listOf("会话管理", "历史树", "agent hub", "session management").any(text::contains)
    val defaultRole = "默认模型" in text || ("default" in text && listOf("model", "role", "模型", "角色").any(text::contains))
    val modelFailure = defaultRole || listOf("缺少服务标识", "缺少模型标识", "没有可用模型", "模型不可用", "未配置模型",
        "模型未配置", "no available model", "model not found", "model unavailable", "model is unavailable", "api key",
        "apikey", "api_key", "unauthorized", "authentication", "凭证", "认证", "配额", "余额", "rate limit",
        "model_not_found", "未找到模型", "模型服务").any(text::contains) || chatErrorStatusCode.containsMatchIn(text)
    return buildList {
        if (ompFailure) add(ChatErrorAction.OPEN_OMP_REPAIR)
        if (sessionFailure) add(ChatErrorAction.DISMISS)
        if (modelFailure || modelId.isBlank()) {
            add(ChatErrorAction.OPEN_PROVIDER_SETTINGS)
            if (defaultRole || modelId.isBlank()) add(ChatErrorAction.OPEN_AGENT_SETTINGS)
        }
    }
}

internal fun visibleChatErrorActions(error: String, modelId: String, canRepairOmp: Boolean): List<ChatErrorAction> =
    chatErrorCandidates(error, modelId).filter {
        // The always-visible close icon already performs DISMISS.
        it != ChatErrorAction.DISMISS && (it != ChatErrorAction.OPEN_OMP_REPAIR || canRepairOmp)
    }.take(2)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatErrorBanner(errorText: String, modelId: String, onDismiss: () -> Unit,
    onOpenProviderSettings: () -> Unit, onOpenAgentSettings: () -> Unit, onRequestOmpRepair: (() -> Unit)?) {
    val c = LocalAgentColors.current
    val actions = remember(errorText, modelId, onRequestOmpRepair != null) {
        visibleChatErrorActions(errorText, modelId, onRequestOmpRepair != null)
    }
    Surface(color = c.errorContainer, contentColor = c.material.onErrorContainer,
        shape = RoundedCornerShape(22.dp), tonalElevation = 2.dp, shadowElevation = 2.dp,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Symbol("error", modifier = Modifier.padding(top = 12.dp), tint = c.error, size = 22.dp)
                SelectionContainer(Modifier.weight(1f).padding(top = 12.dp, bottom = 8.dp)) {
                    Text(errorText, color = c.material.onErrorContainer, fontSize = 15.sp, lineHeight = 21.sp,
                        maxLines = 5, overflow = TextOverflow.Ellipsis)
                }
                AgentIconButton("close", "关闭报错", onDismiss)
            }
            if (actions.isNotEmpty()) FlowRow(Modifier.fillMaxWidth().padding(start = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEach { action ->
                    FilledTonalButton(onClick = {
                        when (action) {
                            ChatErrorAction.OPEN_OMP_REPAIR -> onRequestOmpRepair?.invoke()
                            ChatErrorAction.OPEN_PROVIDER_SETTINGS -> onOpenProviderSettings()
                            ChatErrorAction.OPEN_AGENT_SETTINGS -> onOpenAgentSettings()
                            ChatErrorAction.DISMISS -> onDismiss()
                        }
                    }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Symbol(action.icon, size = 18.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(action.title, maxLines = 1)
                    }
                }
            }
        }
    }
}

private fun FeedNodeBounds.crossesPin(rows: List<LazyListItemInfo>, line: Float, height: Float, threshold: Float): Boolean {
    val offset = rows.firstOrNull { it.key == ownerId }?.offset ?: return false
    return offset + top + threshold < line && offset + bottom > line + height
}

@Composable
private fun FeedPins(
    global: State<PlanNodeItem?>,
    parent: State<StepGroupNodeItem?>,
    content: State<FeedItem?>,
    running: Boolean,
    modifier: Modifier,
    onScrollTo: (String) -> Unit,
    onCollapse: (String) -> Unit,
) {
    val globalPin by global
    val parentPin by parent
    val contentPin by content
    Column(modifier) {
        PinSlot(globalPin != null) {
            globalPin?.let { node ->
                PinRow("checklist", node.progressLabel(), true, progress = node.progress(),
                    status = { NodeStatusLabel(if (node.steps.all { it.state == StepState.COMPLETED }) NodeStatus.OK else if (running) NodeStatus.RUNNING else NodeStatus.STOPPED, 0, false) }) { onScrollTo(node.id) }
            }
        }
        PinSlot(parentPin != null) {
            parentPin?.let { node ->
                PinRow(node.icon, node.title, false, stats = node.stats,
                    status = { NodeStatusLabel(node.state, node.elapsedSec, startedAtMillis = node.startedAtMillis) }) { onScrollTo(node.id) }
            }
        }
        PinSlot(contentPin != null) {
            contentPin?.let { node ->
                val thinking = node as? ThinkingNodeItem
                val tool = node as? ToolNodeItem
                PinRow(if (thinking != null) "psychology" else tool?.icon ?: "code", if (thinking != null) "思考" else plainTitle(tool?.titleHtml.orEmpty()), false,
                    onCollapse = { onCollapse(node.id) },
                    status = {
                        if (thinking != null) NodeStatusLabel(if (thinking.isStreaming) NodeStatus.RUNNING else NodeStatus.OK, thinking.elapsedSec, startedAtMillis = thinking.startedAtMillis)
                        else if (tool != null) NodeStatusLabel(tool.state, tool.elapsedSec, startedAtMillis = tool.startedAtMillis)
                    }) { onScrollTo(node.id) }
            }
        }
    }
}

@Composable
private fun PinSlot(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible,
        enter = expandVertically(tween(500, easing = EmphEasing)) + fadeIn(tween(350, 120, EmphDecelEasing)) + slideInVertically(tween(500, 50, EmphDecelEasing)) { -10 },
        exit = shrinkVertically(tween(500, easing = EmphEasing)) + fadeOut(tween(180, easing = EmphAccelEasing))) { content() }
}

@Composable
private fun PinRow(icon: String, title: String, tone: Boolean, progress: Float? = null, stats: GroupStats? = null,
    onCollapse: (() -> Unit)? = null, status: @Composable RowScope.() -> Unit, onClick: () -> Unit) {
    val c = LocalAgentColors.current
    Box(Modifier.fillMaxWidth().height(56.dp).clickable(onClick = onClick)) {
        Row(Modifier.fillMaxSize().padding(start = 16.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).background(if (tone) c.primaryContainer else c.onSurface, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Symbol(icon, size = 22.dp, tint = if (tone) c.onPrimaryContainer else c.surface)
            }
            Text(title, Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (stats != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Symbol("edit", size = 20.dp, tint = c.onSurfaceVariant); Text(stats.editCount.toString(), fontSize = 17.sp, color = c.onSurfaceVariant)
                Symbol("description", size = 20.dp, tint = c.onSurfaceVariant); Text(stats.readCount.toString(), fontSize = 17.sp, color = c.onSurfaceVariant)
            }
            status()
            if (onCollapse != null) AgentIconButton("unfold_less", "收起内容", onCollapse)
            Symbol("vertical_align_top", size = 22.dp, tint = c.onSurfaceVariant)
        }
        progress?.let { value ->
            val animated by androidx.compose.animation.core.animateFloatAsState(value, tween(800, easing = EmphEasing))
            LinearProgressIndicator(progress = { animated }, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 4.dp).height(4.dp),
                color = c.primary, trackColor = c.primaryContainer)
        }
    }
}

