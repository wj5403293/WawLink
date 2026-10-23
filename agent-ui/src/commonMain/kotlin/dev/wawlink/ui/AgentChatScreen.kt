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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onSizeChanged
import dev.wawlink.protocol.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Host-neutral chat surface: all effects are routed through the supplied controller or host callbacks. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AgentChatScreen(
    controller: AgentSessionController,
    providers: ProviderConfigRepository,
    onNavigateToSessionList: () -> Unit,
    onNewSession: () -> Unit,
    onOpenProviderSettings: () -> Unit,
    onOpenAgentSettings: () -> Unit,
    onPickFiles: suspend () -> List<AttachmentFile>,
    appearance: AgentAppearance = AgentAppearance(),
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
        var detail by remember { mutableStateOf<Pair<String, String>?>(null) }
        var composerHeight by remember { mutableStateOf(156.dp) }
        var followTail by rememberSaveable(state.id) { mutableStateOf(true) }
        val density = LocalDensity.current
        val compactIndex = state.feed.indexOfFirst { it.id == state.compactedThroughId }
        var showCompacted by rememberSaveable(state.id, state.compactedThroughId, appearance.collapseCompacted) { mutableStateOf(!appearance.collapseCompacted) }
        val hasCompacted = compactIndex >= 0
        val visibleFeed = remember(state.feed, appearance.hideToolCalls, showCompacted, compactIndex) {
            state.feed.drop(if (hasCompacted && !showCompacted) compactIndex + 1 else 0)
                .filterNot { appearance.hideToolCalls && it is ToolNodeItem }
        }
        val feedIndex = remember(visibleFeed, hasCompacted) { visibleFeed.mapIndexed { index, item -> item.id to index + 1 + if (hasCompacted) 1 else 0 }.toMap() }
        val itemsById = remember(visibleFeed) { visibleFeed.associateBy { it.id } }
        val plan = visibleFeed.lastOrNull { it is PlanNodeItem } as? PlanNodeItem
        val globalPin by remember(plan, feedIndex, list) {
            derivedStateOf {
                plan?.let { node ->
                    val position = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == node.id }
                    val index = feedIndex[node.id] ?: return@let null
                    if ((position != null && position.offset + with(density) { 30.dp.roundToPx() } < 4) || (position == null && list.firstVisibleItemIndex > index)) node else null
                }
            }
        }
        val parentPin by remember(itemsById, globalPin, list) {
            derivedStateOf {
                val line = if (globalPin != null) with(density) { 56.dp.roundToPx() } else 0
                list.layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { position ->
                    val node = itemsById[position.key as? String ?: ""] as? StepGroupNodeItem
                    if (node != null && position.offset < line - with(density) { 8.dp.roundToPx() } && position.offset + position.size > line + with(density) { 72.dp.roundToPx() }) node else null
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
        val lastFeed = visibleFeed.lastOrNull()
        LaunchedEffect(lastFeed, composerHeight, visibleFeed.size, state.error, state.queuedPrompts.size) {
            if (followTail && !list.isScrollInProgress && (visibleFeed.isNotEmpty() || state.error != null)) {
                val target = if (state.error != null) visibleFeed.size + 1 + if (hasCompacted) 1 else 0
                    else feedIndex[visibleFeed.last().id] ?: return@LaunchedEffect
                list.scrollToItem(target)
                val itemHeight = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }?.size ?: 0
                list.scrollBy(itemHeight + with(density) { composerHeight.toPx() })
            }
        }
        fun scrollTo(id: String) {
            val index = feedIndex[id] ?: return
            followTail = false
            scope.launch {
                val headerHeight = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "chat-pins" }?.size ?: 0
                list.animateScrollToItem(index, -headerHeight - with(density) { 8.dp.roundToPx() })
            }
        }
        Surface(color = c.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 460.dp).fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                    val scrolled by remember(list) { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 4 } }
                    val barColor by animateColorAsState(if (scrolled) c.surfaceContainer else c.surface, tween(350, easing = EmphEasing))
                    Row(Modifier.fillMaxWidth().height(64.dp).background(barColor).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        AgentIconButton("forum", "会话列表", onNavigateToSessionList)
                        Text(state.title, Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        AgentIconButton("edit_square", "新建会话", onNewSession)
                        AgentIconButton("settings", "设置", { settingsMenu = true })
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        LazyColumn(state = list, modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 8.dp, bottom = composerHeight + 20.dp)) {
                            stickyHeader(key = "chat-pins") {
                                Column(Modifier.fillMaxWidth().background(barColor)) {
                                    PinSlot(globalPin != null) {
                                        globalPin?.let { node ->
                                            PinRow("checklist", node.progressLabel(), true, progress = node.progress(),
                                                status = { NodeStatusLabel(if (node.steps.all { it.state == StepState.COMPLETED }) NodeStatus.OK else if (state.isRunning) NodeStatus.RUNNING else NodeStatus.STOPPED, 0, false) }) { scrollTo(node.id) }
                                        }
                                    }
                                    PinSlot(parentPin != null) {
                                        parentPin?.let { node ->
                                            PinRow(node.icon, node.title, false, stats = node.stats,
                                                status = { NodeStatusLabel(node.state, node.elapsedSec) }) { scrollTo(node.id) }
                                        }
                                    }
                                }
                            }
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
                                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 20.dp).animateItem(
                                    fadeInSpec = if (item is UserMessageItem) null else tween(600, easing = EmphDecelEasing), placementSpec = agentSpring(), fadeOutSpec = tween(200, easing = EmphAccelEasing)).graphicsLayer {
                                        translationY = (1f - enter) * with(density) { 24.dp.toPx() }; scaleX = .98f + .02f * enter; scaleY = scaleX
                                    }) {
                                    FeedNode(item, controller, expanded) { title, body -> detail = title to body }
                                }
                            }
                            if (state.error != null) item(key = "session-error") {
                                Surface(color = c.errorContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Symbol("error", tint = c.error); SelectionContainer { Text(state.error.orEmpty(), color = c.onSurface) }
                                    }
                                }
                            }
                        }
                        if (visibleFeed.isEmpty() && !hasCompacted && state.error == null) Column(Modifier.align(Alignment.Center).padding(bottom = composerHeight), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Symbol("neurology", size = 40.dp, tint = c.primary)
                            Text("开始新的任务", style = MaterialTheme.typography.titleLarge)
                            Text(state.workspace, fontFamily = LocalAgentMono.current, color = c.onSurfaceVariant, fontSize = 14.sp)
                        }
                        androidx.compose.animation.AnimatedVisibility(!followTail && list.canScrollForward, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = composerHeight + 12.dp),
                            enter = fadeIn(tween(300, easing = EmphDecelEasing)) + scaleIn(agentSpring()), exit = fadeOut(tween(150, easing = EmphAccelEasing))) {
                            FilledTonalIconButton({ followTail = true; scope.launch {
                                val last = visibleFeed.lastOrNull()?.id?.let { feedIndex[it] }
                                if (last != null) {
                                    list.animateScrollToItem(last)
                                    val itemHeight = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == last }?.size ?: 0
                                    list.animateScrollBy(itemHeight + with(density) { composerHeight.toPx() })
                                }
                            } }) { Symbol("arrow_downward", contentDescription = "回到最新消息") }
                        }
                        ComposerArea(controller, state, providers, onOpenProviderSettings, onPickFiles, { followTail = true },
                            Modifier.align(Alignment.BottomCenter).onSizeChanged { size -> composerHeight = with(density) { size.height.toDp() } })
                    }
                }
            }
        }
        if (settingsMenu) AgentSheet("设置", { settingsMenu = false }) {
            SettingsDestination("hub", "模型服务") { settingsMenu = false; onOpenProviderSettings() }
            SettingsDestination("tune", "Agent 设置") { settingsMenu = false; onOpenAgentSettings() }
        }
        detail?.let { (title, body) ->
            val clipboard = LocalClipboardManager.current
            var copied by remember(body) { mutableStateOf(false) }
            AgentSheet(title, { detail = null }) {
                TextButton({ clipboard.setText(AnnotatedString(body)); copied = true }, Modifier.align(Alignment.End)) {
                    Symbol(if (copied) "check" else "content_copy", size = 20.dp); Spacer(Modifier.width(8.dp)); Text(if (copied) "已复制" else "复制全部")
                }
                SelectionContainer(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Text(body, fontFamily = LocalAgentMono.current, fontSize = 15.sp, lineHeight = 23.25.sp, color = c.onSurfaceVariant)
                }
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
    status: @Composable RowScope.() -> Unit, onClick: () -> Unit) {
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
            Symbol("vertical_align_top", size = 22.dp, tint = c.onSurfaceVariant)
        }
        progress?.let { value ->
            val animated by androidx.compose.animation.core.animateFloatAsState(value, tween(800, easing = EmphEasing))
            LinearProgressIndicator(progress = { animated }, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 4.dp).height(4.dp),
                color = c.primary, trackColor = c.primaryContainer)
        }
    }
}

@Composable
private fun SettingsDestination(icon: String, title: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Symbol(icon, tint = LocalAgentColors.current.onSurfaceVariant); Text(title, Modifier.weight(1f), fontSize = 18.sp)
        Symbol("chevron_right", tint = LocalAgentColors.current.onSurfaceVariant)
    }
}
