package dev.wawlink.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wawlink.protocol.*
import kotlinx.coroutines.delay

private val titleTagPattern = Regex("<[^>]*>")
private val titleSpanPattern = Regex("<span[^>]*>(.*?)</span>", RegexOption.DOT_MATCHES_ALL)

internal fun plainTitle(html: String) = html.replace(titleTagPattern, "").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replace("&quot;", "\"")

@Composable
private fun nodeTitle(html: String): AnnotatedString {
    val color = LocalAgentColors.current.onSurfaceVariant
    return remember(html, color) {
        buildAnnotatedString {
            var end = 0
            titleSpanPattern.findAll(html).forEach { span ->
                append(plainTitle(html.substring(end, span.range.first)))
                withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) { append(plainTitle(span.groupValues[1])) }
                end = span.range.last + 1
            }
            append(plainTitle(html.substring(end)))
        }
    }
}
internal fun PlanNodeItem.progress(): Float = if (steps.isEmpty()) 0f else steps.count { it.state == StepState.COMPLETED }.toFloat() / steps.size
internal fun PlanNodeItem.progressLabel(): String {
    val current = steps.indexOfFirst { it.state == StepState.IN_PROGRESS }
    return when {
        current >= 0 -> "第 ${current + 1} 项，共 ${steps.size} 项"
        steps.isNotEmpty() && steps.all { it.state == StepState.COMPLETED } -> "全部完成"
        else -> "共 ${steps.size} 项"
    }
}
/** Observe the whole feed, not recycled rows, so completion is consumed exactly once. */
internal fun updateGroupExpansion(
    feed: List<FeedItem>,
    expanded: MutableMap<String, Boolean>,
    previouslyRunning: MutableMap<String, Boolean>,
) {
    for (item in feed) if (item is StepGroupNodeItem) {
        val running = item.state == NodeStatus.RUNNING
        val previous = previouslyRunning[item.id]
        if (previous != running) {
            previouslyRunning[item.id] = running
            if (previous == true && !running) expanded[item.id] = false
        }
        updateGroupExpansion(item.children, expanded, previouslyRunning)
    }
}



@Composable
internal fun AgentProgressIndicator(size: androidx.compose.ui.unit.Dp = 20.dp, tint: Color = LocalAgentColors.current.primary) {
    CircularProgressIndicator(Modifier.size(size), color = tint, strokeWidth = 2.dp)
}

@Composable
internal fun NodeStatusLabel(status: NodeStatus, seconds: Int, showTime: Boolean = true, startedAtMillis: Long? = null) {
    val c = LocalAgentColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        when (status) {
            NodeStatus.RUNNING -> AgentProgressIndicator(tint = c.onSurfaceVariant)
            NodeStatus.OK -> Symbol("check_circle", size = 20.dp, tint = c.tertiary, filled = true)
            NodeStatus.ERROR -> Symbol("error", size = 20.dp, tint = c.error, filled = true)
            NodeStatus.STOPPED -> Symbol("stop_circle", size = 20.dp, tint = c.onSurfaceVariant)
        }
        if (showTime) NodeElapsedLabel(seconds, startedAtMillis, status == NodeStatus.RUNNING)
    }
}

@Composable
private fun NodeElapsedLabel(seconds: Int, startedAtMillis: Long?, running: Boolean) {
    val time = if (running && startedAtMillis != null) {
        val clock = LocalFeedClock.current
        if (clock != null) {
            val value by remember(seconds, startedAtMillis, clock) {
                derivedStateOf { elapsed(runningElapsed(seconds, startedAtMillis, true, clock.value)) }
            }
            value
        } else elapsed(runningElapsed(seconds, startedAtMillis, true, feedEpochMillis()))
    } else elapsed(seconds.coerceAtLeast(0))
    if (time.isNotEmpty()) Text(time, fontFamily = LocalAgentMono.current, fontSize = 15.sp, color = LocalAgentColors.current.onSurfaceVariant)
}

@Composable
internal fun FeedNode(item: FeedItem, controller: AgentSessionController, expanded: MutableMap<String, Boolean>, onDetail: (FeedDetail) -> Unit) {
    if (LocalAgentAppearance.current.hideToolCalls && item is ToolNodeItem) return
    when (item) {
        is UserMessageItem -> Column {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Surface(shape = RoundedCornerShape(24.dp, 24.dp, 6.dp, 24.dp), color = LocalAgentColors.current.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(.84f)) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                        SelectionContainer { Text(item.text, fontSize = 18.sp) }
                        item.attachments.forEach { file ->
                            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Symbol("attach_file", size = 18.dp); Text(file.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            if (item.accepted) FeedHistoryActions(item.id, alignEnd = true)
        }
        is AssistantMessageItem -> {
            Column(Modifier.padding(horizontal = 4.dp)) {
                Markdown(streamedText(item.markdownText, item.isStreaming))
                if (item.isStreaming) StreamingCaret()
                if (!item.isStreaming) FeedHistoryActions(item.id)
            }
        }
        is ThinkingNodeItem -> ThinkingNode(item, expanded, onDetail)
        is PlanNodeItem -> PlanNode(item, expanded)
        is StepGroupNodeItem -> GroupNode(item, controller, expanded, onDetail)
        is ToolNodeItem -> ToolNode(item, expanded, onDetail)
        is ApprovalCardItem -> ApprovalCard(item, controller)
        is AskCardItem -> Column {
            AskCard(item, controller)
            FeedHistoryActions(item.id)
        }
    }
}

@Composable
private fun StreamingCaret() {
    val t = rememberInfiniteTransition()
    val opacity by t.animateFloat(1f, 0f, infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse))
    Box(Modifier.padding(start = 2.dp).size(9.dp, 20.dp).graphicsLayer { alpha = opacity }
        .background(LocalAgentColors.current.primary, RoundedCornerShape(2.dp)))
}

@Composable
private fun NodeFrame(nodeId: String, icon: String, lead: LeadStyle, open: Boolean, title: String,
    onToggle: (() -> Unit)?, status: @Composable RowScope.() -> Unit = {},
    rail: @Composable ColumnScope.() -> Unit = {}, titleContent: (@Composable () -> Unit)? = null, onIconClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit) {
    val c = LocalAgentColors.current
    val bounds = LocalFeedBounds.current
    val root = LocalFeedRootCoordinates.current
    val onInteract = LocalFeedInteraction.current
    val toggle = onToggle?.let { action -> { onInteract(); action() } }
    val iconClick = onIconClick?.let { action -> { onInteract(); action() } } ?: toggle
    DisposableEffect(nodeId, bounds, root) { onDispose { bounds?.remove(nodeId) } }
    val radius by animateDpAsState(if (open) 24.dp else 14.dp, tween(450, easing = EmphEasing))
    val colors = when (lead) {
        LeadStyle.DARK -> c.onSurface to c.surface
        LeadStyle.SOFT -> c.surfaceContainerHigh to c.onSurface
        LeadStyle.TONE -> c.primaryContainer to c.onPrimaryContainer
        LeadStyle.WARN -> c.warnContainer to if (LocalAgentAppearance.current.darkTheme) c.onSurface else Color(0xFF2E1500)
    }
    Row(Modifier.fillMaxWidth().onGloballyPositioned { coordinates ->
        val owner = root?.coordinates
        if (bounds != null && root != null && owner != null && owner.isAttached && coordinates.isAttached) {
            val top = owner.localPositionOf(coordinates, Offset.Zero).y
            val bottom = top + coordinates.size.height
            val previous = bounds[nodeId]
            if (previous == null || previous.ownerId != root.ownerId || previous.top != top || previous.bottom != bottom) {
                bounds[nodeId] = FeedNodeBounds(root.ownerId, top, bottom)
            }
        }
    }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.width(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(radius)).background(colors.first)
                .then(if (iconClick != null) Modifier.clickable(
                    interactionSource = remember(nodeId) { MutableInteractionSource() },
                    indication = ripple(color = colors.second), role = Role.Button,
                    onClickLabel = if (onIconClick != null) "全屏查看" else if (open) "收起" else "展开",
                    onClick = iconClick) else Modifier), contentAlignment = Alignment.Center) {
                Symbol(icon, size = 28.dp, tint = colors.second,
                    contentDescription = if (onIconClick != null) "全屏查看${plainTitle(title)}" else null)
            }
            rail()
        }
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 30.dp).clip(RoundedCornerShape(12.dp))
                .then(if (toggle != null) Modifier.clickable(onClick = toggle) else Modifier),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    if (titleContent != null) titleContent() else Text(nodeTitle(title), fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                status()
                if (onToggle != null) {
                    val rotation by animateFloatAsState(if (open) 180f else 0f, tween(500, easing = EmphEasing))
                    Symbol("expand_more", Modifier.graphicsLayer { rotationZ = rotation }, tint = c.onSurfaceVariant)
                }
            }
            content()
        }
    }
}

@Composable
private fun Expand(visible: Boolean, transition: MutableTransitionState<Boolean> = remember { MutableTransitionState(visible) }, content: @Composable () -> Unit) {
    transition.targetState = visible
    AnimatedVisibility(transition,
        enter = expandVertically(tween(500, easing = EmphEasing)) + fadeIn(tween(400, 80, EmphDecelEasing)),
        exit = shrinkVertically(tween(500, easing = EmphEasing)) + fadeOut(tween(200, easing = EmphAccelEasing))) {
        CompositionLocalProvider(LocalFeedExpansionInProgress provides (!transition.isIdle || LocalFeedExpansionInProgress.current)) { content() }
    }
}

@Composable
private fun ThinkingNode(node: ThinkingNodeItem, expanded: MutableMap<String, Boolean>, onDetail: (FeedDetail) -> Unit) {
    val open = expanded[node.id] ?: node.isExpanded
    val expansion = remember(node.id) { MutableTransitionState(open) }
    expansion.targetState = open
    val text = streamedText(node.text, node.isStreaming, paused = !expansion.isIdle || LocalFeedExpansionInProgress.current)
    val showDetail = { onDetail(FeedDetail(node.id, "思考", node.text, markdown = true)) }
    NodeFrame(node.id, "psychology", LeadStyle.SOFT, open, "思考", { expanded[node.id] = !open },
        onIconClick = showDetail,
        status = { NodeStatusLabel(if (node.isStreaming) NodeStatus.RUNNING else NodeStatus.OK, node.elapsedSec, startedAtMillis = node.startedAtMillis) },
        rail = { StagedRail(open, 0, "open_in_full", "全屏", showDetail) },
        titleContent = { if (node.isStreaming) ShimmerTitle("思考中") else Text("思考", fontSize = 19.sp, fontWeight = FontWeight.Bold) }) {
        Expand(!open) {
            ThoughtPreview(text, tail = node.isStreaming)
        }
        Expand(open, expansion) { ThinkingMarkdown(node.id, text) }
    }
}

@Composable
private fun ShimmerTitle(text: String) {
    val c = LocalAgentColors.current
    val transition = rememberInfiniteTransition()
    val shift by transition.animateFloat(1f, -1f, infiniteRepeatable(tween(1800, easing = LinearEasing)))
    var width by remember { mutableStateOf(160f) }
    Text(text, fontSize = 19.sp, fontWeight = FontWeight.Bold,
        style = TextStyle(brush = Brush.linearGradient(
            0f to c.onSurface, .35f to c.onSurface, .5f to Color(0xFFA8C7FA), .65f to c.onSurface, 1f to c.onSurface,
            start = androidx.compose.ui.geometry.Offset(shift * width * 3, 0f), end = androidx.compose.ui.geometry.Offset((shift + 1) * width * 3, 0f))),
        onTextLayout = { width = it.size.width.toFloat() })
}


@Composable
private fun PlanNode(node: PlanNodeItem, expanded: MutableMap<String, Boolean>) {
    val open = expanded[node.id] ?: node.isExpanded
    val c = LocalAgentColors.current
    NodeFrame(node.id, "checklist", LeadStyle.TONE, open, "计划", { expanded[node.id] = !open },
        status = { Text(node.progressLabel(), fontSize = 16.sp, color = c.onSurfaceVariant, maxLines = 1) }) {
        val progress by animateFloatAsState(node.progress(), tween(800, easing = EmphEasing))
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp).height(4.dp),
            color = c.primary, trackColor = c.primaryContainer)
        Expand(open) {
            Column(Modifier.padding(top = 6.dp)) {
                node.steps.forEach { step ->
                    Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (step.state) {
                            StepState.COMPLETED -> Symbol("check_circle", tint = c.primary, filled = true)
                            StepState.IN_PROGRESS -> AgentProgressIndicator(size = 24.dp)
                            StepState.PENDING -> Symbol("radio_button_unchecked", tint = c.outline)
                        }
                        Text(step.title, fontSize = 17.sp, fontWeight = if (step.state == StepState.IN_PROGRESS) FontWeight.Bold else FontWeight.Normal,
                            color = if (step.state == StepState.COMPLETED) c.onSurfaceVariant else c.onSurface)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupNode(node: StepGroupNodeItem, controller: AgentSessionController, expanded: MutableMap<String, Boolean>, onDetail: (FeedDetail) -> Unit) {
    val open = expanded[node.id] ?: node.isExpanded
    val c = LocalAgentColors.current
    NodeFrame(node.id, node.icon, LeadStyle.DARK, open, node.title, { expanded[node.id] = !open },
        status = { NodeStatusLabel(node.state, node.elapsedSec, startedAtMillis = node.startedAtMillis) }) {
        FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (node.stats.editCount > 0) Stat("edit", "修改了", node.stats.editCount, "个文件")
            if (node.stats.readCount > 0) Stat("description", "读取了", node.stats.readCount, "个文件")
            Stat("construction", "调用了", node.stats.toolCount, "次工具")
        }
        Expand(open) {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                node.children.forEach { child -> key(child.id) { FeedNode(child, controller, expanded, onDetail) } }
            }
        }
    }
}

@Composable
private fun Stat(icon: String, prefix: String, count: Int, suffix: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Symbol(icon, size = 22.dp, tint = LocalAgentColors.current.onSurfaceVariant)
        Text(prefix, fontSize = 17.sp, color = LocalAgentColors.current.onSurfaceVariant)
        Text(count.toString(), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(suffix, fontSize = 17.sp, color = LocalAgentColors.current.onSurfaceVariant)
    }
}

@Composable
private fun ToolNode(node: ToolNodeItem, expanded: MutableMap<String, Boolean>, onDetail: (FeedDetail) -> Unit) {
    val open = expanded[node.id] ?: node.isExpanded
    val showDetail = {
        val text = node.codeText ?: node.fullSheetText.ifEmpty { toolDetailText(node.detailContent) }
        onDetail(FeedDetail(node.id, plainTitle(node.titleHtml), text, inferCodeLanguage(node.codeLanguage?.takeUnless { it == "diff" }, node.codePath, text, node.codeInterpreter)))
    }
    NodeFrame(node.id, node.icon, node.leadStyle, open, node.titleHtml, { expanded[node.id] = !open },
        onIconClick = showDetail,
        status = { NodeStatusLabel(node.state, node.elapsedSec, startedAtMillis = node.startedAtMillis) }, rail = {
            StagedRail(open, 0, "open_in_full", "详情", showDetail)
            ToolCopyRail(node, open)
        }) {
        Expand(!open) { ToolPreviewContent(node) }
        Expand(open) { Box(Modifier.heightIn(min = 150.dp)) { ToolDetailContent(node) } }
    }
}

@Composable
private fun ToolCopyRail(node: ToolNodeItem, open: Boolean) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1800); copied = false } }
    StagedRail(open, 60, if (copied) "check" else "content_copy", if (copied) "已复制" else "复制") {
        clipboard.setText(AnnotatedString(node.copyText())); copied = true
    }
}

internal fun ToolNodeItem.copyText(): String =
    rawCopyText.ifEmpty { fullSheetText.ifEmpty { toolDetailText(detailContent) } }

@Composable
private fun StagedRail(open: Boolean, delayMs: Int, icon: String, text: String, onClick: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(open) { if (open) { delay(delayMs.toLong()); shown = true } else shown = false }
    AnimatedVisibility(shown, enter = fadeIn(tween(350, easing = EmphDecelEasing)) + slideInVertically(agentSpring()) { -8 } + scaleIn(agentSpring(), .9f),
        exit = fadeOut(tween(200, easing = EmphAccelEasing)) + shrinkVertically(tween(300, easing = EmphEasing))) {
        Column(Modifier.padding(top = 6.dp).width(48.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Symbol(icon, size = 22.dp, tint = LocalAgentColors.current.onSurfaceVariant)
            Text(text, fontSize = 13.sp, color = LocalAgentColors.current.onSurfaceVariant)
        }
    }
}

@Composable
internal fun MidTruncatedPath(prefix: String, file: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 2.dp)) {
        Text(prefix, Modifier.weight(1f, fill = false), fontFamily = LocalAgentMono.current, fontSize = 15.sp,
            color = LocalAgentColors.current.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(file, fontFamily = LocalAgentMono.current, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun ToolPreviewContent(node: ToolNodeItem) {
    val preview = node.previewContent
    val c = LocalAgentColors.current
    when (preview) {
        is ToolPreview.CommandOneLine -> Text(rememberHighlightedCode(preview.cmd,
            inferCodeLanguage(node.codeLanguage, node.codePath, preview.cmd, node.codeInterpreter) ?: "bash"),
            Modifier.padding(top = 2.dp), fontFamily = LocalAgentMono.current, color = c.onSurfaceVariant,
            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        is ToolPreview.MidPath -> Column {
            MidTruncatedPath(preview.dirPrefix, preview.fileName)
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                preview.plusLines?.let { Text("+$it", color = c.tertiary, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                preview.minusLines?.let { Text("−$it", color = c.error, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
            }
        }
        is ToolPreview.MetaCount -> Stat(preview.icon, preview.label, preview.count, "")
    }
}

internal fun toolDetailText(detail: ToolDetail): String = when (detail) {
    is ToolDetail.CommandAndOutput -> detail.fullCmd + "\n\n" + detail.outputLines.joinToString("\n") { it.text }
    is ToolDetail.DiffBlock -> detail.lines.joinToString("\n") { (when (it.kind) { DiffLineKind.ADDED -> "+"; DiffLineKind.REMOVED -> "-"; DiffLineKind.CONTEXT -> " " }) + it.text }
    is ToolDetail.PlainText -> detail.text
}

@Composable
private fun ToolDetailContent(node: ToolNodeItem) {
    val c = LocalAgentColors.current
    val mono = LocalAgentMono.current
    val detail = node.detailContent
    val language = inferCodeLanguage(node.codeLanguage?.takeUnless { it == "diff" }, node.codePath, node.codeText.orEmpty(), node.codeInterpreter)
    SelectionContainer {
        when (detail) {
            is ToolDetail.CommandAndOutput -> Column {
                HighlightedCode(detail.fullCmd, inferCodeLanguage(node.codeLanguage, text = detail.fullCmd, interpreter = node.codeInterpreter),
                    fontSize = 15.sp, lineHeight = 22.5.sp)
                Column(Modifier.padding(top = 10.dp).drawWithContent {
                    drawContent(); drawLine(c.outlineVariant, androidx.compose.ui.geometry.Offset.Zero,
                        androidx.compose.ui.geometry.Offset(0f, size.height), 2.dp.toPx())
                }.padding(start = 12.dp)) {
                    detail.outputLines.forEachIndexed { index, line -> key(index) {
                        var enter by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) { enter = true }
                        AnimatedVisibility(enter, enter = fadeIn(tween(350, easing = EmphDecelEasing)) + slideInVertically(tween(350, easing = EmphDecelEasing)) { 12 }) {
                            Text(line.text, fontFamily = mono, fontSize = 14.5.sp, lineHeight = 22.5.sp, color = if (line.isError) c.error else c.onSurfaceVariant)
                        }
                    } }
                }
            }
            is ToolDetail.DiffBlock -> {
                val dark = LocalAgentAppearance.current.darkTheme
                val accessible = LocalAgentAppearance.current.colorVisionAssist
                Column(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(14.dp)).background(c.surfaceContainerLow)
                    .horizontalScroll(rememberScrollState()).width(IntrinsicSize.Max)) {
                    detail.lines.forEach { line ->
                        val pair = when (line.kind) {
                            DiffLineKind.ADDED -> if (accessible) c.tertiary.copy(alpha = .12f) to c.tertiary else if (dark) Color(0xFF153B25) to Color(0xFFA4E4B7) else Color(0xFFE6F4EA) to Color(0xFF0D652D)
                            DiffLineKind.REMOVED -> if (accessible) c.error.copy(alpha = .12f) to c.error else if (dark) Color(0xFF4E2221) to Color(0xFFFFB4AB) else Color(0xFFFCE8E6) to Color(0xFFA50E0E)
                            DiffLineKind.CONTEXT -> c.surfaceContainerLow to c.onSurfaceVariant
                        }
                        val highlighted = rememberHighlightedCode(line.text, language)
                        val rendered = remember(highlighted, line.kind) { buildAnnotatedString {
                            append(when (line.kind) { DiffLineKind.ADDED -> "+"; DiffLineKind.REMOVED -> "−"; DiffLineKind.CONTEXT -> " " })
                            append(highlighted)
                        } }
                        Text(rendered,
                            Modifier.fillMaxWidth().background(pair.first).padding(horizontal = 12.dp), color = pair.second,
                            fontFamily = mono, fontSize = 14.sp, lineHeight = 22.4.sp, softWrap = false)
                    }
                }
            }
            is ToolDetail.PlainText -> Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HighlightedCode(node.codeText ?: detail.text, language, fontSize = 14.5.sp, lineHeight = 22.5.sp)
                node.resultText?.let { result -> Text(result, fontFamily = mono, fontSize = 14.5.sp, lineHeight = 22.5.sp,
                    color = if (node.state == NodeStatus.ERROR) c.error else c.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun ApprovalCard(node: ApprovalCardItem, controller: AgentSessionController) {
    val c = LocalAgentColors.current
    Surface(color = c.surfaceContainer, shape = RoundedCornerShape(28.dp)) {
        Box(Modifier.padding(20.dp)) {
            NodeFrame(node.id, "gpp_maybe", LeadStyle.WARN, false, node.title, null) {
                Expand(node.resolution == null) {
                    Column {
                        Text(node.description, Modifier.padding(top = 4.dp), fontSize = 18.sp)
                        Surface(color = c.surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(top = 12.dp)) {
                            Text(rememberHighlightedCode(node.commandPreview, "bash"), Modifier.padding(horizontal = 14.dp, vertical = 12.dp), fontFamily = LocalAgentMono.current,
                                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.onSurfaceVariant)
                        }
                        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ controller.respondApproval(node.id, ApprovalDecision.DENY) }, Modifier.height(48.dp)) {
                                Symbol("close", size = 20.dp); Spacer(Modifier.width(8.dp)); Text("拒绝")
                            }
                            Button({ controller.respondApproval(node.id, ApprovalDecision.ALLOW_ONCE) }, Modifier.weight(1f).height(48.dp)) {
                                Symbol("check", size = 20.dp); Spacer(Modifier.width(8.dp)); Text("允许")
                            }
                        }
                        TextButton({ controller.respondApproval(node.id, ApprovalDecision.ALLOW_ALWAYS_IN_SESSION) }, Modifier.heightIn(min = 48.dp)) {
                            Symbol("done_all", size = 20.dp); Spacer(Modifier.width(8.dp)); Text("本次会话始终允许", fontSize = 17.sp)
                        }
                    }
                }
                Expand(node.resolution != null) {
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val denied = node.resolution == ApprovalDecision.DENY
                        Symbol(if (denied) "block" else "verified", tint = if (denied) c.error else c.tertiary, filled = true)
                        Text(when (node.resolution) { ApprovalDecision.DENY -> "已拒绝"; ApprovalDecision.ALLOW_ALWAYS_IN_SESSION -> "已始终允许"; else -> "已允许" }, fontSize = 17.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun AskCard(node: AskCardItem, controller: AgentSessionController) {
    val c = LocalAgentColors.current
    var draft by rememberSaveable(node.id, stateSaver = AskRequestDraftSaver) { mutableStateOf(AskRequestDraft()) }
    val focusManager = LocalFocusManager.current
    val onNavigate = LocalFeedItemNavigation.current
    fun navigate(next: AskRequestDraft) {
        focusManager.clearFocus(force = true)
        onNavigate(node.id)
        draft = next
    }
    val page = draft.page(node.questions)
    val active = node.questions.getOrNull(page)
    fun answer(question: AskQuestion): AskAnswer? = if (node.answers != null) {
        node.answers?.firstOrNull { it.questionId == question.id }
    } else draft.question(question.id).answer(question)

    Surface(color = c.surfaceContainer, shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.padding(20.dp)) {
            NodeFrame(node.id, "help", LeadStyle.TONE, false, node.title, null) {
                if (node.isResolved) {
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Symbol(if (node.cancelled) "block" else "check_circle", size = 20.dp, tint = c.onSurfaceVariant)
                        Text(if (node.cancelled) "已取消 · 可查看答案" else "已提交 · 可查看答案", color = c.onSurfaceVariant, fontSize = 14.sp)
                    }
                }
                if (node.questions.isEmpty()) {
                    Text("没有可回答的问题", Modifier.padding(top = 12.dp), color = c.onSurfaceVariant)
                } else if (draft.reviewing) {
                    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (node.isResolved) "全部答案" else "检查全部答案", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        node.questions.forEachIndexed { index, question ->
                            Surface(color = c.surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp)) {
                                    Text("${index + 1}. ${question.question}", fontWeight = FontWeight.Medium)
                                    val response = answer(question)
                                    Text(response?.let { (it.selectedOptions + listOfNotNull(it.customInput)).joinToString("\n").ifEmpty { "不选择任何选项" } } ?: "尚未作答",
                                        Modifier.padding(top = 8.dp), color = if (response == null && !node.isResolved) c.error else c.onSurfaceVariant)
                                    TextButton({ navigate(draft.show(question.id)) }) {
                                        Text(if (node.isResolved) "查看问题" else "编辑答案")
                                    }
                                }
                            }
                        }
                    }
                } else if (active != null) {
                    // Clip both incoming and outgoing pages; movement follows question order, not tap timing.
                    AnimatedContent(
                        targetState = page,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)),
                        transitionSpec = {
                            val direction = if (targetState > initialState) AnimatedContentTransitionScope.SlideDirection.Left else AnimatedContentTransitionScope.SlideDirection.Right
                            (slideIntoContainer(direction, tween(450, easing = EmphEasing)) + fadeIn(tween(250))) togetherWith
                                (slideOutOfContainer(direction, tween(450, easing = EmphEasing)) + fadeOut(tween(180)))
                        },
                        label = "ask-question",
                    ) { index ->
                        node.questions.getOrNull(index)?.let { question ->
                            AskQuestionPage(
                                question = question,
                                draft = draft.question(question.id),
                                answer = answer(question),
                                resolved = node.isResolved,
                                unanswered = draft.unansweredQuestionId == question.id && answer(question) == null,
                                onChange = { if (!node.isResolved) draft = draft.edit(question.id, it) },
                                onSelect = { label ->
                                    if (!node.isResolved) {
                                        val next = draft.selectOption(node.questions, question.id, label)
                                        if (!question.multi && next != draft) navigate(next) else draft = next
                                    }
                                },
                                onPreview = { label, selected -> draft = draft.edit(question.id, draft.question(question.id).togglePreview(label, selected)) },
                            )
                        }
                    }
                }
            }
            if (node.questions.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { navigate(draft.show(node.questions[page - 1].id)) },
                        enabled = !draft.reviewing && page > 0,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Symbol("chevron_left", tint = c.onSurfaceVariant.copy(alpha = if (!draft.reviewing && page > 0) 1f else .38f), contentDescription = "上一题")
                    }
                    IconButton(
                        onClick = { navigate(draft.show(node.questions[page + 1].id)) },
                        enabled = !draft.reviewing && page < node.questions.lastIndex,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Symbol("chevron_right", tint = c.onSurfaceVariant.copy(alpha = if (!draft.reviewing && page < node.questions.lastIndex) 1f else .38f), contentDescription = "下一题")
                    }
                    Text(if (draft.reviewing) "检查" else "${page + 1}/${node.questions.size}", color = c.onSurfaceVariant, fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    if (draft.reviewing && !node.isResolved) {
                        Button({
                            val missing = draft.firstUnanswered(node.questions)
                            if (missing != null) {
                                navigate(draft.show(missing).copy(unansweredQuestionId = missing))
                            } else {
                                draft.answers(node.questions)?.let { controller.respondAsk(node.id, it) }
                            }
                        }, Modifier.heightIn(min = 48.dp)) {
                            Text("提交全部")
                        }
                    } else if (draft.reviewing) {
                        TextButton({ navigate(draft.copy(reviewing = false)) }, Modifier.heightIn(min = 48.dp)) { Text("返回") }
                    } else {
                        TextButton({ navigate(draft.copy(reviewing = true)) }, Modifier.heightIn(min = 48.dp)) {
                            Text(if (node.isResolved) "查看全部" else "检查答案")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AskQuestionPage(
    question: AskQuestion,
    draft: AskQuestionDraft,
    answer: AskAnswer?,
    resolved: Boolean,
    unanswered: Boolean,
    onChange: (AskQuestionDraft) -> Unit,
    onSelect: (String) -> Unit,
    onPreview: (String, Boolean) -> Unit,
) {
    val c = LocalAgentColors.current
    val selected = if (resolved) answer?.selectedOptions.orEmpty() else if (!question.multi && draft.customActive) emptyList() else draft.selectedOptions
    val custom = if (resolved) answer?.customInput.orEmpty() else draft.customInput
    val customChosen = if (resolved) !answer?.customInput.isNullOrBlank() else question.multi && custom.isNotBlank() || draft.customActive || question.options.isEmpty()
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        question.header?.takeIf { it.isNotBlank() }?.let {
            Text(it, Modifier.padding(bottom = 6.dp), color = c.onSurfaceVariant, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Text(question.question, fontSize = 18.sp)
        if (question.multi) Text("可多选，也可补充其他想法", Modifier.padding(top = 6.dp), color = c.onSurfaceVariant, fontSize = 14.sp)
        Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            question.options.forEachIndexed { index, option ->
                val chosen = option.label in selected
                Surface(color = if (chosen) c.primaryContainer else c.surface, shape = RoundedCornerShape(if (chosen) 28.dp else 20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = chosen, enabled = !resolved,
                                role = if (question.multi) Role.Checkbox else Role.RadioButton,
                                onClick = { onSelect(option.label) })
                                .heightIn(min = 60.dp).padding(horizontal = 18.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Symbol(if (question.multi) { if (chosen) "check_box" else "check_box_outline_blank" } else {
                                if (chosen) "radio_button_checked" else "radio_button_unchecked"
                            }, tint = if (chosen) c.primary else c.outline, filled = chosen)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(option.label, fontSize = 17.sp)
                                option.description?.takeIf { it.isNotBlank() }?.let { Text(it, color = c.onSurfaceVariant, fontSize = 14.sp) }
                                if (question.recommended == index) Text("推荐", color = c.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        option.preview?.takeIf { it.isNotBlank() }?.let { preview ->
                            val open = draft.previewOpen(option.label, chosen)
                            TextButton({ onPreview(option.label, chosen) }, Modifier.padding(horizontal = 8.dp)
                                .semantics { contentDescription = "${if (open) "收起" else "展开"}${option.label}预览" }) {
                                Text(if (open) "收起预览" else "展开预览")
                            }
                            if (open) AskRichPreview(preview, Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp))
                        }
                    }
                }
            }
            Surface(color = if (customChosen) c.primaryContainer else c.surface, shape = RoundedCornerShape(if (customChosen) 28.dp else 20.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Symbol("edit", tint = if (customChosen) c.primary else c.outline)
                    if (resolved) {
                        SelectionContainer {
                            Text(custom.ifBlank { if (question.options.isEmpty()) "未作答" else "未填写其他想法" }, Modifier.padding(vertical = 12.dp), color = if (custom.isBlank()) c.onSurfaceVariant else c.onSurface)
                        }
                    } else {
                        BasicTextField(custom, { onChange(draft.editCustom(it)) }, Modifier.weight(1f).padding(vertical = 12.dp)
                            .onFocusChanged { if (it.isFocused && !question.multi && !draft.customActive) onChange(draft.editCustom(custom)) }
                            .semantics { contentDescription = if (question.options.isEmpty()) "填写答案" else "其他想法" },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.onSurface),
                            decorationBox = { inner ->
                                if (custom.isEmpty()) Text(if (question.options.isEmpty()) "填写答案" else "其他想法", color = c.onSurfaceVariant)
                                inner()
                            })
                    }
                }
            }
            if (question.multi && selected.isEmpty() && custom.isBlank()) {
                if (resolved) {
                    if (answer != null) Text("本题不选择任何选项", Modifier.padding(top = 4.dp), color = c.onSurfaceVariant, fontSize = 14.sp)
                } else {
                    TextButton({ onChange(draft.confirmEmpty()) }) {
                        Text(if (draft.confirmedEmpty) "已确认本题不选择" else "本题不选择")
                    }
                }
            }
        }
        if (unanswered) Text("请先回答这一题，再提交全部答案", Modifier.padding(top = 10.dp), color = c.error, fontSize = 14.sp)
    }
}
