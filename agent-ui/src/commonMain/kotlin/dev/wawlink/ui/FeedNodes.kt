package dev.wawlink.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wawlink.protocol.*
import kotlinx.coroutines.delay

internal fun elapsed(seconds: Int) = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
internal fun plainTitle(html: String) = html.replace(Regex("<[^>]*>"), "").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replace("&quot;", "\"")

@Composable
private fun nodeTitle(html: String): AnnotatedString {
    val color = LocalAgentColors.current.onSurfaceVariant
    return remember(html, color) {
        buildAnnotatedString {
            var end = 0
            Regex("<span[^>]*>(.*?)</span>", RegexOption.DOT_MATCHES_ALL).findAll(html).forEach { span ->
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

@Composable
internal fun RotatingSymbol(name: String = "progress_activity", size: androidx.compose.ui.unit.Dp = 20.dp, tint: Color = LocalAgentColors.current.primary) {
    val transition = rememberInfiniteTransition()
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)))
    Symbol(name, Modifier.graphicsLayer { rotationZ = angle }, size, tint)
}

@Composable
internal fun NodeStatusLabel(status: NodeStatus, seconds: Int, showTime: Boolean = true) {
    val c = LocalAgentColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        when (status) {
            NodeStatus.RUNNING -> RotatingSymbol(tint = c.onSurfaceVariant)
            NodeStatus.OK -> Symbol("check_circle", size = 20.dp, tint = c.tertiary, filled = true)
            NodeStatus.ERROR -> Symbol("error", size = 20.dp, tint = c.error, filled = true)
            NodeStatus.STOPPED -> Symbol("stop_circle", size = 20.dp, tint = c.onSurfaceVariant)
        }
        if (showTime) Text(elapsed(seconds), fontFamily = LocalAgentMono.current, fontSize = 15.sp, color = c.onSurfaceVariant)
    }
}

@Composable
internal fun FeedNode(item: FeedItem, controller: AgentSessionController, expanded: MutableMap<String, Boolean>, onDetail: (String, String) -> Unit) {
    if (LocalAgentAppearance.current.hideToolCalls && item is ToolNodeItem) return
    when (item) {
        is UserMessageItem -> {
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
        }
        is AssistantMessageItem -> {
            Column(Modifier.padding(horizontal = 4.dp)) {
                Markdown(streamedText(item.markdownText, item.isStreaming))
                if (item.isStreaming) StreamingCaret()
            }
        }
        is ThinkingNodeItem -> ThinkingNode(item, expanded)
        is PlanNodeItem -> PlanNode(item, expanded)
        is StepGroupNodeItem -> GroupNode(item, controller, expanded, onDetail)
        is ToolNodeItem -> ToolNode(item, expanded, onDetail)
        is ApprovalCardItem -> ApprovalCard(item, controller)
        is AskCardItem -> AskCard(item, controller)
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
private fun NodeFrame(icon: String, lead: LeadStyle, open: Boolean, title: String,
    onToggle: (() -> Unit)?, status: @Composable RowScope.() -> Unit = {},
    rail: @Composable ColumnScope.() -> Unit = {}, titleContent: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit) {
    val c = LocalAgentColors.current
    val radius by animateDpAsState(if (open) 24.dp else 14.dp, tween(450, easing = EmphEasing))
    val colors = when (lead) {
        LeadStyle.DARK -> c.onSurface to c.surface
        LeadStyle.SOFT -> c.surfaceContainerHigh to c.onSurface
        LeadStyle.TONE -> c.primaryContainer to c.onPrimaryContainer
        LeadStyle.WARN -> c.warnContainer to if (LocalAgentAppearance.current.darkTheme) c.onSurface else Color(0xFF2E1500)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.width(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(radius)).background(colors.first)
                .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier), contentAlignment = Alignment.Center) {
                Symbol(icon, size = 28.dp, tint = colors.second)
            }
            rail()
        }
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 30.dp).clip(RoundedCornerShape(12.dp))
                .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier),
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
private fun Expand(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible,
        enter = expandVertically(tween(500, easing = EmphEasing)) + fadeIn(tween(400, 80, EmphDecelEasing)),
        exit = shrinkVertically(tween(500, easing = EmphEasing)) + fadeOut(tween(200, easing = EmphAccelEasing))) { content() }
}

@Composable
private fun ThinkingNode(node: ThinkingNodeItem, expanded: MutableMap<String, Boolean>) {
    val open = expanded[node.id] ?: node.isExpanded
    val c = LocalAgentColors.current
    val text = streamedText(node.text, node.isStreaming)
    NodeFrame("psychology", LeadStyle.SOFT, open, "思考", { expanded[node.id] = !open },
        status = { NodeStatusLabel(if (node.isStreaming) NodeStatus.RUNNING else NodeStatus.OK, node.elapsedSec) },
        titleContent = { if (node.isStreaming) ShimmerTitle("思考中") else Text("思考", fontSize = 19.sp, fontWeight = FontWeight.Bold) }) {
        Expand(!open) {
            if (node.isStreaming) ThinkingTail(text)
            else Text(text, color = c.onSurfaceVariant, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Expand(open) { SelectionContainer { Text(text, color = c.onSurfaceVariant, fontSize = 17.sp, lineHeight = 28.9.sp) } }
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
private fun ThinkingTail(text: String) {
    var overflow by remember { mutableStateOf(false) }
    var availableWidth by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxWidth().onSizeChanged { availableWidth = it.width }.graphicsLayer { clip = true; compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            if (overflow) drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), endX = 48.dp.toPx()), blendMode = BlendMode.DstIn)
        }) {
        Text(text.replace('\n', ' '), color = LocalAgentColors.current.onSurfaceVariant, fontSize = 17.sp,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
            modifier = Modifier.fillMaxWidth().wrapContentWidth(if (overflow) Alignment.End else Alignment.Start, unbounded = true),
            onTextLayout = { overflow = it.size.width > availableWidth })
    }
}

@Composable
private fun PlanNode(node: PlanNodeItem, expanded: MutableMap<String, Boolean>) {
    val open = expanded[node.id] ?: node.isExpanded
    val c = LocalAgentColors.current
    NodeFrame("checklist", LeadStyle.TONE, open, "计划", { expanded[node.id] = !open },
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
                            StepState.IN_PROGRESS -> RotatingSymbol(size = 24.dp)
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
private fun GroupNode(node: StepGroupNodeItem, controller: AgentSessionController, expanded: MutableMap<String, Boolean>, onDetail: (String, String) -> Unit) {
    val open = expanded[node.id] ?: node.isExpanded
    LaunchedEffect(node.state) { if (node.state != NodeStatus.RUNNING) expanded[node.id] = false }
    val c = LocalAgentColors.current
    NodeFrame(node.icon, LeadStyle.DARK, open, node.title, { expanded[node.id] = !open },
        status = { NodeStatusLabel(node.state, node.elapsedSec) }) {
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
private fun ToolNode(node: ToolNodeItem, expanded: MutableMap<String, Boolean>, onDetail: (String, String) -> Unit) {
    val open = expanded[node.id] ?: node.isExpanded
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1800); copied = false } }
    val copyText = node.rawCopyText.ifEmpty { node.fullSheetText.ifEmpty { toolDetailText(node.detailContent) } }
    NodeFrame(node.icon, node.leadStyle, open, node.titleHtml, { expanded[node.id] = !open },
        status = { NodeStatusLabel(node.state, node.elapsedSec) }, rail = {
            StagedRail(open, 0, "open_in_full", "详情") { onDetail(plainTitle(node.titleHtml), node.fullSheetText.ifEmpty { toolDetailText(node.detailContent) }) }
            StagedRail(open, 60, if (copied) "check" else "content_copy", if (copied) "已复制" else "复制") {
                clipboard.setText(AnnotatedString(copyText)); copied = true
            }
        }) {
        Expand(!open) { ToolPreviewContent(node.previewContent) }
        Expand(open) { Box(Modifier.heightIn(min = 150.dp)) { ToolDetailContent(node.detailContent) } }
    }
}

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
private fun ToolPreviewContent(preview: ToolPreview) {
    val c = LocalAgentColors.current
    when (preview) {
        is ToolPreview.CommandOneLine -> Text(preview.cmd, Modifier.padding(top = 2.dp), fontFamily = LocalAgentMono.current,
            color = if (LocalAgentAppearance.current.darkTheme) c.tertiary else Color(0xFF2D5016),
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
private fun ToolDetailContent(detail: ToolDetail) {
    val c = LocalAgentColors.current
    val mono = LocalAgentMono.current
    SelectionContainer {
        when (detail) {
            is ToolDetail.CommandAndOutput -> Column {
                Text(detail.fullCmd, fontFamily = mono, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = if (LocalAgentAppearance.current.darkTheme) c.tertiary else Color(0xFF2D5016), lineHeight = 22.5.sp)
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
                        Text((when (line.kind) { DiffLineKind.ADDED -> "+"; DiffLineKind.REMOVED -> "−"; DiffLineKind.CONTEXT -> " " }) + line.text,
                            Modifier.fillMaxWidth().background(pair.first).padding(horizontal = 12.dp), color = pair.second,
                            fontFamily = mono, fontSize = 14.sp, lineHeight = 22.4.sp, softWrap = false)
                    }
                }
            }
            is ToolDetail.PlainText -> Text(detail.text, Modifier.padding(top = 10.dp), fontFamily = mono, fontSize = 14.5.sp, lineHeight = 22.5.sp, color = c.onSurfaceVariant)
        }
    }
}

@Composable
private fun ApprovalCard(node: ApprovalCardItem, controller: AgentSessionController) {
    val c = LocalAgentColors.current
    Surface(color = c.surfaceContainer, shape = RoundedCornerShape(28.dp)) {
        Box(Modifier.padding(20.dp)) {
            NodeFrame("gpp_maybe", LeadStyle.WARN, false, node.title, null) {
                Expand(node.resolution == null) {
                    Column {
                        Text(node.description, Modifier.padding(top = 4.dp), fontSize = 18.sp)
                        Surface(color = c.surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(top = 12.dp)) {
                            Text(node.commandPreview, Modifier.padding(horizontal = 14.dp, vertical = 12.dp), fontFamily = LocalAgentMono.current,
                                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (LocalAgentAppearance.current.darkTheme) c.tertiary else Color(0xFF2D5016))
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
    var selection by remember(node.id) { mutableStateOf<String?>(null) }
    var custom by remember(node.id) { mutableStateOf("") }
    var isCustom by remember(node.id) { mutableStateOf(false) }
    Surface(color = c.surfaceContainer, shape = RoundedCornerShape(28.dp)) {
        Box(Modifier.padding(20.dp)) {
            NodeFrame("help", LeadStyle.TONE, false, node.title, null) {
                Expand(node.selectedAnswer == null) {
                    Column {
                        Text(node.question, Modifier.padding(top = 4.dp), fontSize = 18.sp)
                        Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            node.options.forEach { option ->
                                val chosen = !isCustom && selection == option
                                Surface(color = if (chosen) c.primaryContainer else c.surface, shape = RoundedCornerShape(if (chosen) 28.dp else 20.dp),
                                    modifier = Modifier.fillMaxWidth().clickable { selection = option; isCustom = false }) {
                                    Row(Modifier.heightIn(min = 60.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                        Symbol(if (chosen) "radio_button_checked" else "radio_button_unchecked", tint = if (chosen) c.primary else c.outline, filled = chosen)
                                        Text(option, fontSize = 17.sp)
                                    }
                                }
                            }
                            Surface(color = if (isCustom) c.primaryContainer else c.surface, shape = RoundedCornerShape(if (isCustom) 28.dp else 20.dp)) {
                                Row(Modifier.heightIn(min = 60.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Symbol("edit", tint = if (isCustom) c.primary else c.outline)
                                    BasicTextField(custom, { custom = it; isCustom = true }, Modifier.weight(1f).padding(vertical = 12.dp),
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.onSurface),
                                        decorationBox = { inner -> if (custom.isEmpty()) Text("其他想法", color = c.onSurfaceVariant); inner() })
                                }
                            }
                        }
                        val answer = if (isCustom) custom.trim() else selection.orEmpty()
                        Button({ controller.respondAsk(node.id, answer) }, Modifier.fillMaxWidth().padding(top = 16.dp).height(48.dp), enabled = answer.isNotEmpty()) {
                            Symbol("send", size = 20.dp); Spacer(Modifier.width(8.dp)); Text("确定")
                        }
                    }
                }
                Expand(node.selectedAnswer != null) {
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Symbol("reply", tint = c.primary); Text(node.selectedAnswer.orEmpty(), fontSize = 17.sp)
                    }
                }
            }
        }
    }
}
