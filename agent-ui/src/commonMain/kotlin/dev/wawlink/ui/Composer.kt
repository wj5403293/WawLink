package dev.wawlink.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wawlink.protocol.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.math.*

@Composable
fun ThinkingBulb(level: ThinkingLevel, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    val fillValues = floatArrayOf(0f, .18f, .34f, .52f, .72f, 1f)
    val fill by animateFloatAsState(fillValues[level.rays], tween(350, easing = EmphEasing))
    val rayCount = level.rays
    val angles = when (rayCount) { 1 -> listOf(0f); 2 -> listOf(-30f, 30f); 3 -> listOf(-45f, 0f, 45f); 4 -> listOf(-60f, -20f, 20f, 60f); 5 -> listOf(-64f, -32f, 0f, 32f, 64f); else -> emptyList() }
    val rayProgress = (0 until 5).map { index ->
        animateFloatAsState(if (index < rayCount) 1f else 0f, tween(450, index * 40, EmphDecelEasing)).value
    }
    var popped by remember { mutableStateOf(false) }
    LaunchedEffect(level) { popped = false; kotlinx.coroutines.delay(16); popped = true }
    val scale by animateFloatAsState(if (popped) 1f else .8f, agentSpring())
    Canvas(modifier.size(24.dp).semantics { contentDescription = "思考强度：${level.label}" }.graphicsLayer { scaleX = scale; scaleY = scale }) {
        val s = size.minDimension / 24f
        val path = Path().apply {
            moveTo(12f*s,6.2f*s)
            cubicTo(7.48f*s,6.2f*s,5.95f*s,11.94f*s,9.3f*s,14.53f*s)
            lineTo(9.3f*s,16.4f*s); lineTo(14.7f*s,16.4f*s); lineTo(14.7f*s,14.53f*s)
            cubicTo(18.05f*s,11.94f*s,16.52f*s,6.2f*s,12f*s,6.2f*s); close()
        }
        drawPath(path, tint.copy(alpha = fill)); drawPath(path, tint, style = Stroke(1.8f*s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        fun line(x1: Float, y1: Float, x2: Float, y2: Float, alpha: Float = 1f) = drawLine(tint.copy(alpha = alpha),
            androidx.compose.ui.geometry.Offset(x1*s,y1*s), androidx.compose.ui.geometry.Offset(x2*s,y2*s), 1.8f*s, StrokeCap.Round)
        line(9.6f,19f,14.4f,19f); line(10.6f,21.4f,13.4f,21.4f)
        angles.forEachIndexed { index, angle ->
            val a = angle * PI.toFloat() / 180f
            val p = rayProgress[index]
            line(12f+sin(a)*7.6f, 10.5f-cos(a)*7.6f, 12f+sin(a)*(7.6f+2.4f*p), 10.5f-cos(a)*(7.6f+2.4f*p), p)
        }
        if (rayCount == 0) line(5.5f,4.5f,18.5f,19.5f)
    }
}

internal fun decimal(value: Double, digits: Int): String {
    val factor = 10.0.pow(digits)
    val scaled = round(value.coerceAtLeast(0.0) * factor).toLong()
    return if (digits == 0) scaled.toString() else "${scaled / factor.toLong()}.${(scaled % factor.toLong()).toString().padStart(digits, '0')}"
}
internal fun tokens(value: Long): String = when { value >= 1_000_000 -> decimal(value / 1_000_000.0, 1) + "M"; value >= 1_000 -> decimal(value / 1000.0, 1) + "k"; else -> value.toString() }

private data class SlashCommand(val name: String, val icon: String, val description: String)
private val slashCommands = listOf(
    SlashCommand("init", "description", "生成项目说明"), SlashCommand("plan", "checklist", "先规划再执行"),
    SlashCommand("review", "rate_review", "审查当前改动"), SlashCommand("test", "science", "运行测试"),
    SlashCommand("compact", "compress", "压缩上下文"), SlashCommand("clear", "delete_sweep", "清空会话"),
    SlashCommand("model", "neurology", "切换模型"), SlashCommand("cost", "monitoring", "查看用量"),
)

@OptIn(ExperimentalAnimationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun ComposerArea(controller: AgentSessionController, state: ChatSessionUiState, providerRepository: ProviderConfigRepository,
    onOpenProviderSettings: () -> Unit, onPickFiles: suspend () -> List<AttachmentFile>, modifier: Modifier = Modifier) {
    val c = LocalAgentColors.current
    val appearance = LocalAgentAppearance.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val providers by providerRepository.providers.collectAsState()
    var text by remember(state.id) { mutableStateOf("") }
    val attachments = remember(state.id) { mutableStateListOf<AttachmentFile>() }
    var menu by remember { mutableStateOf<String?>(null) }
    var workspacePicker by remember { mutableStateOf(false) }
    var usageSheet by remember { mutableStateOf(false) }
    var pickerError by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var slashIndex by remember { mutableStateOf(0) }
    var inputFocused by remember { mutableStateOf(false) }
    val suggestions = if (Regex("^/\\S*$").matches(text)) slashCommands.filter { it.name.startsWith(text.drop(1), ignoreCase = true) } else emptyList()
    val selectedIndex = slashIndex.coerceIn(0, (suggestions.size - 1).coerceAtLeast(0))
    fun pickSlash(command: SlashCommand) {
        when (command.name) { "model" -> { text = ""; menu = "model" }; "cost" -> { text = ""; usageSheet = true }; else -> text = "/${command.name} " }
        slashIndex = 0
    }
    fun submit() {
        val prompt = text.trim()
        if (prompt.isEmpty() && attachments.isEmpty()) { if (state.isRunning) controller.stopExecution(); return }
        if (prompt.startsWith("/") && attachments.isEmpty()) {
            val command = prompt.drop(1).substringBefore(' ')
            val args = prompt.drop(1).substringAfter(' ', "")
            when (command) { "model" -> menu = "model"; "cost" -> usageSheet = true; else -> controller.executeSlashCommand(command, args) }
        } else if (state.isRunning && attachments.isEmpty()) controller.enqueuePrompt(prompt)
        else controller.sendPrompt(prompt, attachments.toList())
        text = ""; attachments.clear(); focusManager.clearFocus()
    }
    Column(modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(c.surface.copy(alpha = 0f), c.surface, c.surface), endY = 90f))
        .padding(horizontal = 12.dp).padding(top = 24.dp, bottom = 12.dp)) {
        PromptQueueList(state.queuedPrompts, controller)
        AnimatedVisibility(suggestions.isNotEmpty(), enter = expandVertically(tween(450, easing = EmphEasing)) + fadeIn(tween(300, easing = EmphDecelEasing)) + slideInVertically(tween(500, easing = EmphDecelEasing)) { 10 },
            exit = shrinkVertically(tween(400, easing = EmphEasing)) + fadeOut(tween(200, easing = EmphAccelEasing))) {
            Surface(color = c.surface, shape = RoundedCornerShape(24.dp), shadowElevation = 6.dp, modifier = Modifier.padding(bottom = 8.dp)) {
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(6.dp)) {
                    suggestions.forEachIndexed { index, command ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (index == selectedIndex) c.surfaceContainer else Color.Transparent)
                            .clickable { pickSlash(command) }.heightIn(min = 60.dp).padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Box(Modifier.size(44.dp).background(if (index == selectedIndex) c.primaryContainer else c.surfaceContainer, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Symbol(command.icon, tint = c.onSurfaceVariant) }
                            Text("/${command.name}", color = c.primary, fontSize = 17.sp, fontFamily = LocalAgentMono.current, fontWeight = FontWeight.SemiBold)
                            Text(command.description, Modifier.weight(1f), fontSize = 16.sp, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        Surface(modifier = if (inputFocused) Modifier.border(2.dp, c.primary, RoundedCornerShape(28.dp)) else Modifier,
            shape = RoundedCornerShape(28.dp), color = c.surfaceContainerHigh) {
            Column(Modifier.padding(8.dp)) {
                AnimatedVisibility(state.isRunning || appearance.alwaysShowUsage,
                    enter = expandVertically(tween(450, easing = EmphEasing)) + fadeIn(tween(350, 100, EmphDecelEasing)),
                    exit = shrinkVertically(tween(450, easing = EmphEasing)) + fadeOut(tween(250, easing = EmphAccelEasing))) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (state.isRunning) RotatingSymbol()
                        AnimatedContent(if (state.isRunning) state.actionText else "用量", Modifier.weight(1f),
                            transitionSpec = { (fadeIn(tween(350, easing = EmphDecelEasing)) + slideInVertically(tween(350, easing = EmphDecelEasing)) { 10 }) togetherWith fadeOut(tween(150, easing = EmphAccelEasing)) }) { action ->
                            Text(action, fontSize = 16.sp, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        UsageCapsule(state.usage) { usageSheet = true }
                    }
                }
                if (attachments.isNotEmpty()) FlowRow(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    attachments.forEach { file -> InputChip(selected = true, onClick = { attachments.remove(file) },
                        label = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Symbol("attach_file", size = 18.dp) }, trailingIcon = { Symbol("close", size = 18.dp) }) }
                }
                pickerError?.let { error -> Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(error, Modifier.weight(1f), color = c.error, fontSize = 14.sp)
                    AgentIconButton("close", "关闭错误", { pickerError = null })
                } }
                BasicTextField(text, { text = it; slashIndex = 0 }, Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 160.dp)
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp)
                    .onFocusChanged { inputFocused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) false else when {
                            suggestions.isNotEmpty() && event.key == Key.DirectionDown -> { slashIndex = (selectedIndex + 1) % suggestions.size; true }
                            suggestions.isNotEmpty() && event.key == Key.DirectionUp -> { slashIndex = (selectedIndex - 1 + suggestions.size) % suggestions.size; true }
                            suggestions.isNotEmpty() && (event.key == Key.Enter || event.key == Key.Tab) -> { pickSlash(suggestions[selectedIndex]); true }
                            suggestions.isNotEmpty() && event.key == Key.Escape -> { text = ""; true }
                            event.key == Key.Enter && !event.isShiftPressed -> { submit(); true }
                            else -> false
                        }
                    }, textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onSurface, fontSize = 18.sp, lineHeight = 27.sp),
                    cursorBrush = SolidColor(c.primary), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { submit() }),
                    decorationBox = { inner -> Box { if (text.isEmpty()) Text("描述你的任务", color = c.onSurfaceVariant, fontSize = 18.sp); inner() } })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box {
                        ComposerButton("更多附件", menu == "more", { menu = if (menu == "more") null else "more" }) { if (picking) RotatingSymbol() else Symbol("add") }
                        ComposerMenu(menu == "more", { menu = null }) {
                            PopupRow("文件", "attach_file") {
                                menu = null; picking = true
                                scope.launch {
                                    try { attachments.addAll(onPickFiles().filter { incoming -> attachments.none { it.path == incoming.path } }) }
                                    catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; pickerError = e.message ?: "无法选择文件" }
                                    finally { picking = false }
                                }
                            }
                            PopupRow("项目文件", "folder_open") { menu = null; workspacePicker = true }
                        }
                    }
                    Box {
                        ComposerButton("模型 ${state.modelId}", menu == "model", { menu = if (menu == "model") null else "model" }) { Symbol("neurology") }
                        ComposerMenu(menu == "model", { menu = null }) {
                            providers.filter { it.enabled && it.models.isNotEmpty() }.forEach { provider ->
                                Text(provider.name, Modifier.padding(start = 14.dp, top = 10.dp, bottom = 6.dp), fontSize = 16.sp, color = c.onSurfaceVariant, fontWeight = FontWeight.Medium)
                                provider.models.forEach { model ->
                                    PopupRow(model.name, "neurology", state.providerId == provider.id && state.modelId == model.id,
                                        customIcon = {
                                            val palette = listOf(Color(0xFF0B57D0), Color(0xFFC5551A), Color(0xFF146C2E), Color(0xFF8E24AA), Color(0xFF00796B), Color(0xFFB3261E), Color(0xFF5F6368))
                                            Box(Modifier.size(52.dp).background(palette[(provider.id.hashCode() and Int.MAX_VALUE) % palette.size], CircleShape), contentAlignment = Alignment.Center) {
                                                Text(provider.name.take(1).uppercase(), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }) {
                                        controller.selectModel(provider.id, model.id); controller.selectThinkingLevel(model.thinkingLevel); menu = null
                                    }
                                }
                            }
                            HorizontalDivider(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), color = c.outlineVariant.copy(alpha = .6f))
                            PopupRow("管理模型服务", "tune") { menu = null; onOpenProviderSettings() }
                        }
                    }
                    Box {
                        ComposerButton("思考强度 ${state.thinkingLevel.label}", menu == "think", { menu = if (menu == "think") null else "think" }, lit = state.thinkingLevel != ThinkingLevel.NONE) { ThinkingBulb(state.thinkingLevel) }
                        ComposerMenu(menu == "think", { menu = null }) {
                            Text("思考强度", Modifier.padding(start = 14.dp, top = 10.dp, bottom = 6.dp), fontSize = 16.sp, color = c.onSurfaceVariant)
                            val api = providers.firstOrNull { it.id == state.providerId }?.apiType
                            val levels = when (api) {
                                ProviderApiType.CHAT -> listOf(ThinkingLevel.NONE, ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH)
                                ProviderApiType.MESSAGE -> ThinkingLevel.entries.filter { it != ThinkingLevel.XHIGH }
                                else -> ThinkingLevel.entries
                            }
                            levels.forEach { level -> PopupRow(level.label, selected = state.thinkingLevel == level,
                                customIcon = { ThinkingBulb(level) }) { controller.selectThinkingLevel(level); menu = null } }
                        }
                    }
                    ComposerButton(if (state.autoApprove) "自动执行" else "需批准", false, { controller.toggleExecutionMode(!state.autoApprove) }, lit = state.autoApprove) {
                        Symbol(if (state.autoApprove) "bolt" else "shield", filled = state.autoApprove)
                    }
                    Spacer(Modifier.weight(1f))
                    val hasInput = text.isNotBlank() || attachments.isNotEmpty()
                    val stopping = state.isRunning && !hasInput
                    val radius by animateDpAsState(if (stopping) 24.dp else 16.dp, agentSpring())
                    val color by animateColorAsState(if (stopping) c.onSurface else c.primary, tween(300, easing = EmphEasing))
                    val mode = if (stopping) "stop" else if (state.isRunning) "playlist_add" else "arrow_upward"
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(radius)).background(color).clickable(enabled = state.isRunning || hasInput) { submit() }
                        .semantics { contentDescription = if (stopping) "停止" else if (state.isRunning) "加入队列" else "发送" }, contentAlignment = Alignment.Center) {
                        AnimatedContent(mode, transitionSpec = { (fadeIn(tween(300, easing = EmphDecelEasing)) + scaleIn(agentSpring())) togetherWith fadeOut(tween(150)) }) { Symbol(it, tint = if (stopping) c.surface else c.onPrimary, filled = true) }
                    }
                }
            }
        }
    }
    if (workspacePicker) WorkspacePicker(controller, state.workspace, { workspacePicker = false }) { files ->
        attachments.addAll(files.filter { incoming -> attachments.none { it.path == incoming.path } }); workspacePicker = false
    }
    if (usageSheet) UsageSheet(state) { usageSheet = false }
}

@Composable
private fun ComposerButton(description: String, active: Boolean, onClick: () -> Unit, lit: Boolean = false, content: @Composable () -> Unit) {
    val c = LocalAgentColors.current
    val radius by animateDpAsState(if (active) 14.dp else 22.dp, agentSpring())
    val background by animateColorAsState(if (active) c.primaryContainer else Color.Transparent, tween(300, easing = EmphEasing))
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(radius)).background(background).clickable(onClick = onClick).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        CompositionLocalProvider(LocalContentColor provides if (active) c.onPrimaryContainer else if (lit) c.primary else c.onSurfaceVariant) { content() }
    }
}

@Composable
private fun ComposerMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val transition = remember { MutableTransitionState(false) }
    transition.targetState = expanded
    val density = LocalDensity.current
    var pivot by remember { mutableFloatStateOf(.15f) }
    val provider = remember(density) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val inset = with(density) { 12.dp.roundToPx() }
                val x = (anchorBounds.left - with(density) { 6.dp.roundToPx() }).coerceIn(inset, (windowSize.width - popupContentSize.width - inset).coerceAtLeast(inset))
                val y = (anchorBounds.top - popupContentSize.height - with(density) { 10.dp.roundToPx() }).coerceAtLeast(inset)
                pivot = ((anchorBounds.left + anchorBounds.width / 2f - x) / popupContentSize.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                return IntOffset(x, y)
            }
        }
    }
    if (transition.currentState || transition.targetState) Popup(popupPositionProvider = provider, onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)) {
        AnimatedVisibility(transition,
            enter = scaleIn(tween(500, easing = EmphDecelEasing), .6f, TransformOrigin(pivot, 1f)) + fadeIn(tween(300, easing = EmphDecelEasing)),
            exit = scaleOut(tween(220, easing = EmphAccelEasing), .6f, TransformOrigin(pivot, 1f)) + fadeOut(tween(180, easing = EmphAccelEasing))) {
            Surface(color = LocalAgentColors.current.surface, shape = RoundedCornerShape(28.dp), shadowElevation = 12.dp) {
                Column(Modifier.widthIn(min = 240.dp, max = 320.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(vertical = 10.dp), content = content)
            }
        }
    }
}

@Composable
private fun PopupRow(label: String, icon: String = "", selected: Boolean = false, customIcon: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val c = LocalAgentColors.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp).clip(RoundedCornerShape(22.dp)).clickable(onClick = onClick)
        .heightIn(min = 64.dp).padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(52.dp).background(if (selected) c.primaryContainer else c.surfaceContainer, RoundedCornerShape(if (selected) 16.dp else 26.dp)), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalContentColor provides if (selected) c.onPrimaryContainer else c.onSurface) { if (customIcon != null) customIcon() else Symbol(icon) }
        }
        Text(label, Modifier.weight(1f), fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
        if (selected) Symbol("check", tint = c.primary)
    }
}

@Composable
private fun PromptQueueList(prompts: List<QueuedPrompt>, controller: AgentSessionController) {
    val displayed = remember(controller) { mutableStateListOf<QueuedPrompt>() }
    val present = remember(controller) { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(prompts) {
        val ids = prompts.mapTo(mutableSetOf()) { it.id }
        displayed.forEach { present[it.id] = it.id in ids }
        prompts.forEach { prompt ->
            val index = displayed.indexOfFirst { it.id == prompt.id }
            if (index < 0) displayed.add(prompt) else displayed[index] = prompt
            present[prompt.id] = true
        }
        kotlinx.coroutines.delay(500)
        displayed.removeAll { it.id !in ids }
        present.keys.retainAll(ids)
    }
    Column(Modifier.heightIn(max = 210.dp).verticalScroll(rememberScrollState())) {
        displayed.forEach { prompt -> key(prompt.id) {
            val visible = remember { MutableTransitionState(false) }
            visible.targetState = present[prompt.id] == true
            AnimatedVisibility(visible,
                enter = expandVertically(tween(450, easing = EmphEasing)) + fadeIn(tween(350, 80, EmphDecelEasing)) + slideInVertically(tween(500, easing = EmphDecelEasing)) { 12 },
                exit = shrinkVertically(tween(450, easing = EmphEasing)) + fadeOut(tween(250, easing = EmphAccelEasing))) {
                QueueRow(prompt, controller)
            }
        } }
    }
}

@Composable
private fun QueueRow(prompt: QueuedPrompt, controller: AgentSessionController) {
    var editing by remember(prompt.id) { mutableStateOf(false) }
    var draft by remember(prompt.id, prompt.text) { mutableStateOf(prompt.text) }
    val c = LocalAgentColors.current
    val focus = remember { FocusRequester() }
    fun save() { if (draft.isBlank()) controller.removeQueuedPrompt(prompt.id) else controller.updateQueuedPrompt(prompt.id, draft.trim()); editing = false }
    LaunchedEffect(editing) { if (editing) focus.requestFocus() }
    val radius by animateDpAsState(if (editing) 28.dp else 20.dp, tween(400, easing = EmphEasing))
    val background by animateColorAsState(if (editing) c.primaryContainer else c.surfaceContainer, tween(300, easing = EmphEasing))
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(radius)).background(background).heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Symbol("schedule", size = 22.dp, tint = c.onSurfaceVariant)
        if (editing) BasicTextField(draft, { draft = it }, Modifier.weight(1f).focusRequester(focus).padding(vertical = 8.dp).onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.key == Key.Enter && !it.isShiftPressed) { save(); true } else false
        }, textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.onSurface), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { save() }))
        else Text(prompt.text.ifBlank { "${prompt.attachments.size} 个附件" }, Modifier.weight(1f), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        AgentIconButton(if (editing) "check" else "edit", if (editing) "保存" else "编辑", { if (editing) save() else editing = true }, Modifier.size(44.dp))
        AgentIconButton("close", "移除排队消息", { controller.removeQueuedPrompt(prompt.id) }, Modifier.size(44.dp))
    }
}

@Composable
private fun UsageCapsule(usage: UsageInfo, onClick: () -> Unit) {
    val c = LocalAgentColors.current
    val progress by animateFloatAsState(usage.contextFraction, tween(600, easing = EmphEasing))
    Row(Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { Symbol("token", size = 18.dp, tint = c.onSurfaceVariant); Text(tokens(usage.totalTokens), fontSize = 16.sp, color = c.onSurfaceVariant) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { Symbol("payments", size = 18.dp, tint = c.onSurfaceVariant); Text(decimal(usage.cost, if (usage.cost < 1) 3 else 2), fontSize = 16.sp, color = c.onSurfaceVariant) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Canvas(Modifier.size(20.dp)) {
                val stroke = 3.2.dp.toPx()
                drawCircle(c.outlineVariant, radius = size.minDimension / 2 - stroke / 2, style = Stroke(stroke))
                drawArc(c.primary, -90f, progress * 360f, false, topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                    size = androidx.compose.ui.geometry.Size(size.width-stroke,size.height-stroke), style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Text("${(usage.contextFraction * 100).roundToInt()}%", fontSize = 16.sp, color = c.onSurfaceVariant)
        }
    }
}

@Composable
private fun UsageSheet(state: ChatSessionUiState, onDismiss: () -> Unit) {
    val usage = state.usage
    AgentSheet("用量", onDismiss) {
        val rows = listOf(Triple("input", "输入", usage.inputTokens.toString()), Triple("output", "输出", usage.outputTokens.toString()),
            Triple("cached", "缓存命中", usage.cacheReadTokens.toString()), Triple("save", "缓存写入", usage.cacheWriteTokens.toString()),
            Triple("data_usage", "上下文", "${tokens(usage.contextTokens)} / ${tokens(usage.contextWindow)}"),
            Triple("payments", "费用", "$${decimal(usage.cost, 4)}"), Triple("neurology", "模型", state.modelId.ifEmpty { "未选择" }))
        rows.forEachIndexed { index, (icon, label, value) ->
            Row(Modifier.heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Symbol(icon, tint = LocalAgentColors.current.onSurfaceVariant)
                Text(label, Modifier.weight(1f), fontSize = 18.sp)
                Text(value, Modifier.widthIn(max = 225.dp), fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }
            if (index != rows.lastIndex) HorizontalDivider(color = LocalAgentColors.current.surfaceContainerHigh)
        }
    }
}

@Composable
private fun WorkspacePicker(controller: AgentSessionController, workspace: String, onDismiss: () -> Unit, onPick: (List<AttachmentFile>) -> Unit) {
    val selected = remember { mutableStateMapOf<String, WorkspaceFileItem>() }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    AgentSheet("项目文件", onDismiss) {
        Text(workspace, fontFamily = LocalAgentMono.current, fontSize = 14.sp, color = LocalAgentColors.current.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
        Column(Modifier.weight(1f, fill = false).heightIn(min = 180.dp, max = 480.dp).verticalScroll(rememberScrollState())) {
            WorkspaceBranch(controller, "", 0, expanded, selected)
        }
        Button({ onPick(selected.values.map { file -> AttachmentFile("workspace:${file.relativePath}", file.name,
            if (file.relativePath.startsWith('/')) file.relativePath else workspace.trimEnd('/') + "/" + file.relativePath,
            sizeBytes = file.sizeBytes, isWorkspaceFile = true) }) }, Modifier.fillMaxWidth().padding(top = 16.dp).height(48.dp), enabled = selected.isNotEmpty()) {
            Symbol("attach_file", size = 20.dp); Spacer(Modifier.width(8.dp)); Text("添加 ${selected.size} 个文件")
        }
    }
}

@Composable
private fun WorkspaceBranch(controller: AgentSessionController, path: String, depth: Int,
    expanded: MutableMap<String, Boolean>, selected: MutableMap<String, WorkspaceFileItem>) {
    var error by remember(path) { mutableStateOf<String?>(null) }
    var loaded by remember(path) { mutableStateOf(false) }
    val flow = remember(controller, path) { controller.listWorkspaceFiles(path).catch { e ->
        if (e is kotlinx.coroutines.CancellationException) throw e
        error = e.message ?: "无法读取目录"; loaded = true; emit(emptyList())
    }.onEach { loaded = true } }
    val files by flow.collectAsState(initial = emptyList())
    val c = LocalAgentColors.current
    if (error != null) Text(error.orEmpty(), color = c.error, modifier = Modifier.padding(12.dp))
    else if (!loaded) Row(Modifier.padding(12.dp)) { RotatingSymbol(); Text("正在读取文件…") }
    else if (files.isEmpty()) Text("此目录为空", color = c.onSurfaceVariant, modifier = Modifier.padding(start = (depth * 16 + 12).dp, top = 12.dp, bottom = 12.dp))
    files.sortedWith(compareByDescending<WorkspaceFileItem> { it.isDirectory }.thenBy { it.name.lowercase() }).forEach { file ->
        val isOpen = expanded[file.relativePath] == true
        val chosen = selected.containsKey(file.relativePath)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (chosen) c.primaryContainer else Color.Transparent)
            .clickable { if (file.isDirectory) expanded[file.relativePath] = !isOpen else if (chosen) selected.remove(file.relativePath) else selected[file.relativePath] = file }
            .heightIn(min = 52.dp).padding(start = (depth * 16 + 8).dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Symbol(if (file.isDirectory) if (isOpen) "folder_open" else "folder" else "description", tint = if (chosen) c.primary else c.onSurfaceVariant)
            Text(file.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 17.sp)
            if (file.isDirectory) Symbol(if (isOpen) "expand_more" else "chevron_right", tint = c.onSurfaceVariant)
            else Symbol(if (chosen) "check_circle" else "radio_button_unchecked", tint = if (chosen) c.primary else c.outline, filled = chosen)
        }
        AnimatedVisibility(file.isDirectory && isOpen, enter = expandVertically(tween(400, easing = EmphEasing)) + fadeIn(), exit = shrinkVertically(tween(400, easing = EmphEasing)) + fadeOut()) {
            WorkspaceBranch(controller, file.relativePath, depth + 1, expanded, selected)
        }
    }
}
