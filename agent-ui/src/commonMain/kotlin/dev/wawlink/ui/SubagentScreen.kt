@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package dev.wawlink.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wawlink.protocol.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
internal fun SubagentScreen(
    agent: RuntimeAgent, hub: AgentHubState, explorer: SessionExplorer,
    providers: ProviderConfigRepository, modelRepository: AgentManagementRepository, busy: Boolean,
    onBack: () -> Unit, onMain: () -> Unit, onOpen: (RuntimeAgent, String?) -> Unit,
    onDefinition: (String?) -> Unit, onControl: (RuntimeAgent, RuntimeAgentOperation) -> Unit,
    onExport: (RuntimeAgent) -> Unit, onPickFiles: suspend () -> List<AttachmentFile>,
    onPickContainerFiles: suspend (String) -> List<AttachmentFile>, onOpenFile: (String, String) -> Unit,
    onDispatch: (RuntimeAgent) -> Unit, locateMessage: String?, onLocated: () -> Unit,
) {
    var controller by remember(agent.instanceKey) { mutableStateOf<RuntimeAgentSessionController?>(null) }
    var error by remember(agent.instanceKey) { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var tab by rememberSaveable { mutableStateOf("chat") }
    var modelMenu by remember { mutableStateOf(false) }
    val holder = rememberSaveableStateHolder()
    val c = LocalAgentColors.current
    LaunchedEffect(agent.instanceKey, refresh) {
        loading = true
        try { controller = explorer.openAgent(agent.instanceKey); error = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "无法读取记录" }
        finally { loading = false }
    }
    LaunchedEffect(locateMessage) { if (locateMessage != null) tab = "chat" }
    val childState = controller?.state?.collectAsState()?.value
    val readonly = agent.advisor || !agent.canSend || !hub.connected
    val currentAgent by rememberUpdatedState(agent)
    val activeController = controller
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            AgentIconButton("arrow_back", "返回", onBack)
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(agent.name, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(agent.id, fontFamily = LocalAgentMono.current, fontSize = 14.sp, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (agent.canSetModel && !agent.advisor && hub.connected) Box {
                AgentIconButton("deployed_code", "临时模型和思考", { modelMenu = true })
                ManagementMenu(modelMenu, { modelMenu = false }) {
                    ManagementMenuItem(text = { Text("只对这个子代理临时生效") }, enabled = false, onClick = {})
                    childState?.availableModels.orEmpty().forEach { model ->
                        ManagementMenuItem(text = { Text("${model.providerId} / ${model.name}") }, selected = childState?.providerId == model.providerId && childState?.modelId == model.modelId, enabled = !busy && activeController != null,
                            leadingIcon = { ModelIcon(model.modelId, model.name) }, onClick = { modelMenu = false; activeController?.selectModel(model.providerId, model.modelId) })
                    }
                    HorizontalDivider()
                    childState?.availableThinkingLevels.orEmpty().forEach { level -> ManagementMenuItem(text = { Text("思考 ${level.label}") }, selected = childState?.thinkingLevel == level,
                        leadingIcon = { ThinkingBulb(level) }, enabled = !busy && activeController != null, onClick = { modelMenu = false; activeController?.selectThinkingLevel(level) }) }
                }
            }
            if (agent.canStop && !agent.advisor && hub.connected) AgentIconButton("stop_circle", "停止当前执行", { if (!busy) onControl(agent, RuntimeAgentOperation.STOP) })
            HubAgentMenu(agent, busy || !hub.connected, { tab = "chat" }, onDefinition, onControl, { onExport(agent) }, { onDispatch(agent) })
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HubTag(if (agent.advisor) "顾问" else "子代理", if (agent.advisor) "shield_person" else "smart_toy", warning = agent.advisor)
            HubLife(agent)
            if (readonly) HubTag("只读", "lock")
            Text(childState?.modelId?.ifBlank { null } ?: agent.modelId ?: "模型未知", fontFamily = LocalAgentMono.current, color = c.onSurfaceVariant, fontSize = 14.sp)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onMain) { Symbol("home", size = 18.dp); Spacer(Modifier.width(4.dp)); Text("主会话") }
            hubLineage(hub.agents, agent).forEach { ancestor ->
                Symbol("chevron_right", size = 18.dp, tint = c.outline)
                Surface(onClick = { if (ancestor.instanceKey != agent.instanceKey) onOpen(ancestor, null) }, shape = RoundedCornerShape(18.dp), color = if (ancestor.instanceKey == agent.instanceKey) c.primaryContainer else c.surface) {
                    Text(ancestor.name, Modifier.padding(horizontal = 10.dp, vertical = 8.dp), fontSize = 16.sp)
                }
            }
        }
        Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) { HubSegments(listOf("chat" to "会话", "info" to "详情", "activity" to "活动", "jobs" to "后台任务"), tab) { tab = it } }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { message -> Column(Modifier.padding(horizontal = 16.dp)) {
            ManagementBanner(message, "error", error = true)
            TextButton({ refresh++ }, enabled = !loading) { Text("重新读取；已显示的内容保留") }
        } }
        holder.SaveableStateProvider(tab) {
            when (tab) {
                "chat" -> {
                    if (activeController != null && childState != null) CompositionLocalProvider(LocalAgentFileLinkHandler provides { path -> onOpenFile(currentAgent.workspace ?: childState.workspace, path) }) {
                        SubagentChat(agent, childState, activeController, providers, modelRepository, readonly, busy,
                            onPickFiles, { onPickContainerFiles(currentAgent.workspace ?: childState.workspace) },
                            { onControl(agent, RuntimeAgentOperation.REVIVE) }, { onControl(agent, RuntimeAgentOperation.STOP) }, { refresh++ }, locateMessage, onLocated,
                            onMain, { tab = "jobs" }, { onExport(agent) })
                    } else if (!loading && error == null) HubEmpty("chat_bubble_outline", "没有可读取的会话记录")
                }
                "info" -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
                    SubagentDetails(agent, hub, busy || !hub.connected, onOpen, onDefinition, onControl, onDispatch, onOpenFile)
                }
                "activity" -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    HubActivity(hub, explorer, agent, onOpen, busy = busy)
                }
                "jobs" -> SubagentJobs(agent, hub, onOpenFile)
            }
        }
    }
}

/** Controller delegation keeps Composer's queue, Ask and approval identity confined to this child. */
private class FocusedComposerController(
    private val child: RuntimeAgentSessionController,
    private val mode: () -> RuntimeAgentPromptBehavior,
    private val canSend: () -> Boolean,
    private val onBlocked: () -> Unit,
    private val onJobs: () -> Unit,
    private val onExport: () -> Unit,
    private val onMainRequired: (String) -> Unit,
    private val onAttempt: (RestoredSessionPrompt) -> Unit,
    private val onStop: () -> Unit,
    private val canSetModel: () -> Boolean,
    private val onUnsupported: (String) -> Unit,
) : AgentSessionController by child {
    override fun sendPrompt(text: String, attachments: List<AttachmentFile>) = submitPrompt(text, attachments, mode())
    override fun enqueuePrompt(text: String, attachments: List<AttachmentFile>) = submitPrompt(text, attachments, RuntimeAgentPromptBehavior.FOLLOW_UP)
    private fun submitPrompt(text: String, attachments: List<AttachmentFile>, behavior: RuntimeAgentPromptBehavior) {
        onAttempt(RestoredSessionPrompt(text, attachments))
        if (text.trimStart().startsWith("!")) { onMainRequired("Shell / Python 执行需要回到主会话；没有执行这段输入"); return }
        if (canSend()) child.sendAgentPrompt(text, attachments, behavior) else onBlocked()
    }
    override suspend fun takeQueuedPrompt(id: String): RestoredSessionPrompt {
        check(canSend()) { "当前子代理只读，不能撤回消息" }
        return child.takeQueuedPrompt(id)
    }
    override fun stopExecution() { if (canSend()) onStop() else onBlocked() }
    override fun respondAsk(nodeId: String, answers: List<AskAnswer>) { if (canSend()) child.respondAsk(nodeId, answers) else onBlocked() }
    override fun respondApproval(nodeId: String, decision: ApprovalDecision) { if (canSend()) child.respondApproval(nodeId, decision) else onBlocked() }
    override fun toggleExecutionMode(autoApprove: Boolean) { onUnsupported("子代理不支持独立切换审批模式") }
    override fun selectModel(providerId: String, modelId: String) {
        if (canSend() && canSetModel() && child.state.value.availableModels.orEmpty().any { it.providerId == providerId && it.modelId == modelId }) child.selectModel(providerId, modelId)
        else onUnsupported("这个子代理当前不支持所选模型")
    }
    override fun selectThinkingLevel(level: ThinkingLevel) {
        if (canSend() && canSetModel() && level in child.state.value.availableThinkingLevels.orEmpty()) child.selectThinkingLevel(level)
        else onUnsupported("这个子代理当前不支持所选思考档位")
    }
    override fun executeSlashCommand(command: String, args: String) {
        when (command.lowercase()) {
            "jobs" -> onJobs()
            "export" -> onExport()
            "model", "think" -> if (canSend() && canSetModel()) child.executeSlashCommand(command, args) else onUnsupported("这个子代理当前不能切换模型或思考档位")
            "btw", "cost" -> if (canSend()) child.executeSlashCommand(command, args) else onBlocked()
            else -> onMainRequired("/$command 需要回到主会话；没有向主会话或模型转发")
        }
    }
}

@Composable
private fun SubagentChat(agent: RuntimeAgent, state: ChatSessionUiState, child: RuntimeAgentSessionController,
    providers: ProviderConfigRepository, modelRepository: AgentManagementRepository, readonly: Boolean, busy: Boolean,
    onPickFiles: suspend () -> List<AttachmentFile>, onPickContainerFiles: suspend () -> List<AttachmentFile>,
    onRevive: () -> Unit, onStop: () -> Unit, onReload: () -> Unit, locateMessage: String?, onLocated: () -> Unit,
    onMain: () -> Unit, onJobs: () -> Unit, onExport: () -> Unit) {
    val c = LocalAgentColors.current; val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val bounds = remember { mutableStateMapOf<String, FeedNodeBounds>() }
    val allNodes = remember(state.feed) { flattenedFeed(state.feed) }
    val expanded = rememberSaveable(saver = BooleanStateMapSaver) { mutableStateMapOf<String, Boolean>() }
    val wasRunning = rememberSaveable(saver = BooleanStateMapSaver) { mutableStateMapOf<String, Boolean>() }
    var follow by rememberSaveable { mutableStateOf(true) }
    var mode by rememberSaveable { mutableStateOf(RuntimeAgentPromptBehavior.STEER.name) }
    var detail by remember { mutableStateOf<FeedDetail?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    var mainRequired by remember { mutableStateOf<String?>(null) }
    val draft = rememberComposerDraft(state.id)
    val submittedDraft = rememberComposerDraft("${state.id}:last-submission")
    var admissionBaseline by rememberSaveable { mutableStateOf(listOf<String>()) }
    val latestState by rememberUpdatedState(state)
    var replaceDraft by remember { mutableStateOf(false) }
    val currentReadonly by rememberUpdatedState(readonly)
    val currentCanSetModel by rememberUpdatedState(agent.canSetModel)
    val currentMode by rememberUpdatedState(if (state.isRunning) RuntimeAgentPromptBehavior.valueOf(mode) else RuntimeAgentPromptBehavior.PROMPT)
    val wrapper = remember(child) { FocusedComposerController(child, { currentMode }, { !currentReadonly }, { localError = "当前子代理只读，不能发送或响应请求" }, onJobs, onExport, { mainRequired = it }, {
        admissionBaseline = flattenedFeed(latestState.feed).filterIsInstance<UserMessageItem>().map { message -> message.id } + latestState.queuedPrompts.map { queued -> queued.id }
        submittedDraft.restore(it, true)
    }, onStop, { currentCanSetModel }, { localError = it }) }
    val clock = rememberFeedClock(state.isRunning)
    LaunchedEffect(state.feed) { updateGroupExpansion(state.feed, expanded, wasRunning) }
    LaunchedEffect(list) { list.interactionSource.interactions.collect { if (it is DragInteraction.Start) follow = false } }
    LaunchedEffect(list, follow) {
        if (follow) snapshotFlow { list.layoutInfo.let { it.totalItemsCount to it.visibleItemsInfo.lastOrNull()?.size } }.collectLatest {
            if (!list.isScrollInProgress && list.layoutInfo.totalItemsCount > 0) {
                val last = list.layoutInfo.totalItemsCount - 1
                if (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index != last) list.scrollToItem(last)
                val layout = list.layoutInfo; val tail = layout.visibleItemsInfo.lastOrNull()
                if (tail != null) { val remaining = tail.offset + tail.size - layout.viewportEndOffset + layout.afterContentPadding; if (remaining > 0) list.scrollBy(remaining.toFloat()) }
            }
        }
    }
    LaunchedEffect(locateMessage, state.feed) {
        if (locateMessage != null) {
            val targetId = if (allNodes.any { it.id == locateMessage }) locateMessage
                else child.feedItemIdForEntry(locateMessage.removePrefix("entry:"))
            val index = state.feed.indexOfFirst { root -> flattenedFeed(listOf(root)).any { it.id == targetId } }
            if (index >= 0) {
                follow = false
                fun expandPath(item: FeedItem): Boolean {
                    if (item.id == targetId) { expanded[item.id] = true; return true }
                    if (item is StepGroupNodeItem && item.children.any { expandPath(it) }) { expanded[item.id] = true; return true }
                    return false
                }
                expandPath(state.feed[index]); list.animateScrollToItem(index)
                kotlinx.coroutines.delay(550)
                val owner = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == state.feed[index].id }
                val target = targetId?.let { bounds[it] }
                if (owner != null && target != null) list.scrollBy(owner.offset + target.top)
                onLocated()
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (readonly) Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(if (agent.lifecycle == RuntimeAgentLifecycle.ABORTED) c.errorContainer else c.surfaceContainer, RoundedCornerShape(24.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Symbol(if (agent.advisor) "shield_person" else "lock", tint = c.onSurfaceVariant)
            Text(agent.unavailableReason ?: when { agent.advisor -> "顾问记录只读"; agent.lifecycle == RuntimeAgentLifecycle.PARKED -> "已停放，打开记录不会恢复执行"; agent.lifecycle == RuntimeAgentLifecycle.ABORTED -> "已终止，会话记录保留"; else -> "运行时不可交互" }, Modifier.weight(1f).padding(horizontal = 10.dp), fontSize = 17.sp)
            if (agent.canRevive && !agent.advisor) TextButton(onRevive, enabled = !busy) { Text("恢复") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            CompositionLocalProvider(LocalFeedClock provides clock, LocalFeedBounds provides bounds, LocalFeedInteraction provides { follow = false }, LocalFeedItemNavigation provides { id ->
                follow = false
                val ownerId = bounds[id]?.ownerId ?: id
                val rootIndex = state.feed.indexOfFirst { it.id == ownerId }
                if (rootIndex >= 0) scope.launch { list.animateScrollToItem(rootIndex) }
            }) {
                LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    items(state.feed, key = { it.id }, contentType = { it::class.simpleName }) { item ->
                        val coordinates = remember(item.id) { FeedRootCoordinates(item.id) }
                        CompositionLocalProvider(LocalFeedRootCoordinates provides coordinates) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).onGloballyPositioned { coordinates.coordinates = it }.animateItem(fadeInSpec = tween(550, easing = EmphDecelEasing), placementSpec = null, fadeOutSpec = tween(200))) {
                                if (readonly) ReadonlyFeedNode(item, wrapper, expanded) { detail = it }
                                else FeedNode(item, wrapper, expanded) { detail = it; follow = false }
                                if (item is UserMessageItem || item is AssistantMessageItem) SubagentMessageActions(item)
                            }
                        }
                    }
                    state.error?.let { error -> item("session-error") { Column(Modifier.padding(horizontal = 16.dp)) { ManagementBanner(error, "error", error = true); TextButton(onReload) { Text("重新读取，保留已显示内容") } } } }
                }
            }
            if (state.feed.isEmpty() && state.error == null) HubEmpty("forum", "尚无会话消息；只显示真实的子会话内容")
            if (!follow && list.canScrollForward) FilledTonalButton({ follow = true }, Modifier.align(Alignment.BottomCenter).padding(8.dp)) { Symbol("arrow_downward", size = 20.dp); Text("回到最新消息") }
        }
        if (localError != null) ManagementBanner(localError.orEmpty(), "error", error = true)
        val submittedText = submittedDraft.textState.value
        val submittedFiles = submittedDraft.attachments.toList()
        val rejectedSubmission = state.error != null && submittedDraft.hasContent && allNodes.filterIsInstance<UserMessageItem>().none { it.accepted && it.id !in admissionBaseline && it.text == submittedText && it.attachments == submittedFiles } && state.queuedPrompts.none { it.id !in admissionBaseline && it.text == submittedText && it.attachments == submittedFiles }
        if (rejectedSubmission) TextButton({ if (draft.hasContent) replaceDraft = true else draft.restore(RestoredSessionPrompt(submittedText, submittedFiles), true) }) { Text("取回未接收的发送内容") }
        if (allNodes.filterIsInstance<UserMessageItem>().any { !it.accepted }) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!readonly) {
            if (state.isRunning) Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HubChip("干预当前执行", mode == RuntimeAgentPromptBehavior.STEER.name) { mode = RuntimeAgentPromptBehavior.STEER.name }
                HubChip("后续消息", mode == RuntimeAgentPromptBehavior.FOLLOW_UP.name) { mode = RuntimeAgentPromptBehavior.FOLLOW_UP.name }
            }
            ComposerArea(wrapper, state, providers, modelRepository, { mainRequired = "模型服务配置需要回到主会话" }, onPickFiles, onPickContainerFiles, { follow = true }, draft = draft, showExecutionMode = false)
        } else Row(Modifier.fillMaxWidth().padding(12.dp).background(c.surfaceContainerLow, RoundedCornerShape(24.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { Symbol("lock"); Text("只读；查看与返回不会启动或停止子代理", fontSize = 16.sp) }
    }
    detail?.let { request ->
        if (request.markdown) ThinkingDetailScreen(request.id, request.text, false) { detail = null }
        else CodeDetailScreen(request.title, request.text, request.language, { detail = null })
    }
    mainRequired?.let { message -> AlertDialog(onDismissRequest = { mainRequired = null }, title = { Text("主会话操作") }, text = { Text(message) }, confirmButton = { TextButton({ mainRequired = null; onMain() }) { Text("回到主会话") } }, dismissButton = { TextButton({ mainRequired = null }) { Text("留在这里") } }) }
    if (replaceDraft) AlertDialog(onDismissRequest = { replaceDraft = false }, title = { Text("替换当前草稿？") }, text = { Text("当前草稿不会自动覆盖。取回未接收消息会替换当前文字和附件。") }, confirmButton = { TextButton({ draft.restore(RestoredSessionPrompt(submittedDraft.textState.value, submittedDraft.attachments.toList()), true); replaceDraft = false }) { Text("替换草稿") } }, dismissButton = { TextButton({ replaceDraft = false }) { Text("保留当前草稿") } })
}

@Composable
private fun ReadonlyFeedNode(item: FeedItem, controller: AgentSessionController, expanded: MutableMap<String, Boolean>, onDetail: (FeedDetail) -> Unit) {
    when {
        item is ApprovalCardItem && item.resolution == null -> Surface(color = LocalAgentColors.current.surfaceContainerLow, shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(item.title, fontSize = 18.sp); Text(item.description); SelectionContainer { Text(item.commandPreview, fontFamily = LocalAgentMono.current) }; ManagementHint("历史审批未决，只读记录不能代答", "lock") }
        }
        item is AskCardItem && !item.isResolved -> Surface(color = LocalAgentColors.current.surfaceContainerLow, shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(item.title, fontSize = 18.sp); item.questions.forEach { q -> Text(q.question); q.options.forEach { option -> Text("• ${option.label}"); option.description?.let { Text(it) }; option.preview?.let { Markdown(it) } } }; ManagementHint("历史问题未回答，只读记录不能提交", "lock") }
        }
        item is StepGroupNodeItem -> {
            // Preserve unresolved-request read-only semantics even inside a nested native tool group.
            val safeChildren = flattenedFeed(item.children).any { it is AskCardItem && !it.isResolved || it is ApprovalCardItem && it.resolution == null }
            if (safeChildren) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(LocalAgentColors.current.surfaceContainerLow).clickable { expanded[item.id] = !(expanded[item.id] ?: item.isExpanded) }.padding(16.dp)) { Symbol(item.icon); Text(item.title, Modifier.weight(1f).padding(start = 8.dp)); Symbol("expand_more") }
                AnimatedVisibility(expanded[item.id] ?: item.isExpanded, enter = expandVertically(tween(500, easing = EmphEasing)) + fadeIn(), exit = shrinkVertically(tween(500, easing = EmphEasing)) + fadeOut()) { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { item.children.forEach { ReadonlyFeedNode(it, controller, expanded, onDetail) } } }
            } else FeedNode(item, controller, expanded, onDetail)
        }
        else -> FeedNode(item, controller, expanded, onDetail)
    }
}

@Composable
private fun SubagentMessageActions(item: FeedItem) {
    val clipboard = LocalClipboardManager.current
    var copying by remember { mutableStateOf(false) }; var copyError by remember { mutableStateOf<String?>(null) }
    var full by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1800); copied = false } }
    val text = when (item) { is UserMessageItem -> item.text; is AssistantMessageItem -> item.markdownText; else -> "" }
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        AgentIconButton("content_copy", "复制这条消息", { copying = true })
        AgentIconButton("open_in_full", "查看全文", { full = true })
        if (copied) Text("已复制", Modifier.padding(vertical = 12.dp), color = LocalAgentColors.current.primary, fontSize = 14.sp)
    }
    if (copying) AgentSheet("复制这条子代理消息", { copying = false }) {
        val blocks = remember(text) {
            buildList {
                if (text.isNotEmpty()) add("正文" to text)
                if (item is AssistantMessageItem) {
                    Regex("```[^\\n]*\\n([\\s\\S]*?)```").findAll(text).forEachIndexed { index, match -> add("代码块 ${index + 1}" to match.groupValues[1].removeSuffix("\n")) }
                    Regex("(?m)^> ?(.*)$").findAll(text).forEach { add("引用" to it.groupValues[1]) }
                    Regex("https?://[^\\s<>\\)\\]]+").findAll(text).forEach { add("链接" to it.value) }
                }
            }
        }
        if (blocks.isEmpty()) HubEmpty("content_paste_off", "这条消息没有可复制文本")
        blocks.forEachIndexed { index, (label, value) -> ManagementRow(label, value.lineSequence().firstOrNull().orEmpty(), "content_copy", onClick = {
            try { clipboard.setText(AnnotatedString(value)); copying = false; copyError = null; copied = true }
            catch (failure: Exception) { copyError = failure.message ?: "复制失败" }
        }, index = index, count = blocks.size) }
        copyError?.let { ManagementBanner(it, "error", error = true) }
    }
    if (full) Dialog(onDismissRequest = { full = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = LocalAgentColors.current.surface, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                AgentTopBar("消息全文", { full = false }, actions = { AgentIconButton("content_copy", "复制全文", { try { clipboard.setText(AnnotatedString(text)); copyError = null } catch (failure: Exception) { copyError = failure.message ?: "复制失败" } }) })
                copyError?.let { ManagementBanner(it, "error", error = true) }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp)) {
                    SelectionContainer { if (item is AssistantMessageItem) Markdown(text) else Text(text, fontSize = 17.sp) }
                }
            }
        }
    }
}

@Composable
private fun SubagentDetails(agent: RuntimeAgent, hub: AgentHubState, busy: Boolean, onOpen: (RuntimeAgent, String?) -> Unit,
    onDefinition: (String?) -> Unit, onControl: (RuntimeAgent, RuntimeAgentOperation) -> Unit, onDispatch: (RuntimeAgent) -> Unit,
    onOpenFile: (String, String) -> Unit) {
    val s = agent.stats
    val contextTokens = s.contextTokens
    val contextWindow = s.contextWindow
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { HubLife(agent); agent.result?.let { HubTag("任务${hubResult(it)}", error = it == "failed" || it == "merge_failed") } }
    ManagementGroup("任务") { SelectionContainer { Text(agent.task, Modifier.fillMaxWidth().background(LocalAgentColors.current.surfaceContainerLow, RoundedCornerShape(20.dp)).padding(16.dp), fontSize = 17.sp) }; agent.error?.let { ManagementBanner(it, "error", error = true) } }
    ManagementGroup("位置") { HubKeyValues(listOf(
        "主会话" to hub.mainSessionId, "上级" to (hub.agents.firstOrNull { it.id == agent.parentId }?.let { "${it.name} ${it.id}" } ?: "主会话"),
        "层级" to hubLineage(hub.agents, agent).size.toString(), "下级" to hub.agents.count { it.parentId == agent.id }.toString(),
        "工作区" to (agent.workspace ?: "未提供"), "容器" to agent.containerName.ifBlank { "未提供" }, "Session 文件" to (agent.sessionFile ?: "尚无持久化文件"),
        "注册时间" to (agent.createdAtMillis?.let(::hubTime) ?: "未提供"), "最近活动" to (agent.lastActivityMillis?.let(::hubTime) ?: "未提供"), "未读通信" to (agent.unread?.toString() ?: "未测量"))) }
    ManagementGroup("模型") { HubKeyValues(listOf("模型" to (agent.modelId ?: "未提供"), "服务" to (agent.providerId ?: "未提供"), "角色" to (agent.modelRole ?: "未提供"), "思考" to managementThinkingLabel(agent.thinking), "故障转移" to (agent.fallback ?: "未提供")))
        ManagementHint("本页模型与思考切换只对当前子代理临时生效，不修改 Agent 定义或主会话") }
    ManagementGroup("用量") {
        HubKeyValues(listOf("运行时间" to (s.elapsedMillis?.let { "${it / 1000 / 60} 分 ${it / 1000 % 60} 秒" } ?: "未测量"), "请求" to hubCount(s.requests), "工具调用" to hubCount(s.tools), "输入" to hubCount(s.inputTokens), "输出" to hubCount(s.outputTokens), "缓存读取" to hubCount(s.cacheReadTokens), "缓存写入" to hubCount(s.cacheWriteTokens), "费用" to (s.cost?.let { "$${decimal(it, 4)}" } ?: "未测量"), "上下文" to (if (contextTokens != null && contextWindow != null && contextWindow > 0) "${decimal(contextTokens.toDouble() / contextWindow * 100, 1)}% ($contextTokens / $contextWindow)" else "未测量")))
        if (contextTokens != null && contextWindow != null && contextWindow > 0) LinearProgressIndicator(progress = { (contextTokens.toFloat() / contextWindow).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)))
    }
    ManagementGroup("产物") {
        if (agent.artifacts.isEmpty()) HubEmpty("inventory_2", "还没有产物")
        agent.artifacts.forEachIndexed { index, artifact -> ManagementRow(artifact.title, artifact.description, artifact.icon, onClick = { onOpenFile(agent.workspace.orEmpty(), artifact.path) }, index = index, count = agent.artifacts.size, detail = artifact.path) }
    }
    val children = hub.agents.filter { it.parentId == agent.id }
    if (children.isNotEmpty()) ManagementGroup("下级") { children.forEachIndexed { index, child -> HubAgentRow(child, hub.agents, index = index, count = children.size, busy = busy, onOpen = { onOpen(child, null) }, onDefinition = onDefinition, onControl = onControl) } }
    Row(Modifier.padding(top = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ onDispatch(agent) }, enabled = agent.dispatchEntryId != null) { Symbol("reply", size = 20.dp); Text("在上级中定位派发") }
        TextButton({ onDefinition(agent.definitionName ?: agent.name) }, enabled = !agent.advisor) { Symbol("description", size = 20.dp); Text("Agent 定义") }
    }
}
private fun hubCount(value: Long?): String = value?.let(::tokens) ?: "未测量"
private fun hubTime(value: Long): String = explorerTimestamp(value.toString(), full = true)

@Composable
private fun SubagentJobs(agent: RuntimeAgent, hub: AgentHubState, onOpenFile: (String, String) -> Unit) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val scoped = hubSubtree(hub.agents, agent.id)
    val jobs = scoped.flatMap { owner -> owner.jobs.map { owner to it } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
        if (jobs.isEmpty()) HubEmpty("work_off", "没有后台任务")
        jobs.forEachIndexed { index, (owner, job) ->
            ManagementRow(job.command, "${owner.name} ${owner.id} · PID ${job.pid ?: "未提供"} · ${job.exitCode?.let { "退出码 $it" } ?: job.status}", "terminal", onClick = { selected = "${owner.instanceKey}:${job.id}" }, index = index, count = jobs.size, error = job.exitCode?.let { it != 0 } == true)
        }
    }
    val target = jobs.firstOrNull { (owner, job) -> "${owner.instanceKey}:${job.id}" == selected }
    if (target != null) {
        val (owner, job) = target
        AgentSheet("后台任务（只读）", { selected = null }) {
            HubKeyValues(listOf("对象" to "${owner.name} ${owner.id}", "命令" to job.command, "目录" to (job.cwd ?: "未提供"), "PID" to (job.pid?.toString() ?: "未提供"), "状态" to (job.exitCode?.let { "退出码 $it" } ?: job.status)))
            ManagementGroup(if (job.exitCode == null) "实时输出" else "输出") {
                SelectionContainer { Text(job.output.ifEmpty { "尚无输出" }, Modifier.fillMaxWidth().background(LocalAgentColors.current.surfaceContainerLow, RoundedCornerShape(20.dp)).padding(16.dp), fontFamily = LocalAgentMono.current, fontSize = 15.sp) }
                job.outputPath?.let { path -> TextButton({ onOpenFile(owner.workspace.orEmpty(), path) }) { Symbol("open_in_full"); Text("打开完整输出文件") } }
            }
        }
    }
}
