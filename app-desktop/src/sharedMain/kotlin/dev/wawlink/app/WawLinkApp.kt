@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.wawlink.app

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wawlink.protocol.*
import dev.wawlink.runtime.*
import dev.wawlink.ui.*
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.roundToInt

class HostNavigation { var onBack: (() -> Boolean)? = null }

private data class ContainerAttachmentPick(val container: ContainerConfig, val initialPath: String, val continuation: CancellableContinuation<List<ContainerFileSelection>>)

private data class HostRoute(
    val page: String,
    val sessionId: String? = null,
    val containerId: String? = null,
    val rootfs: Boolean = false,
) {
    val stateKey: String = when (page) {
        "chat" -> "chat:$sessionId"
        "providers", "management" -> "$page:$containerId:${sessionId ?: "global"}"
        "framework", "container", "terminal", "files" -> "$page:$containerId"
        "install" -> "install:$rootfs"
        else -> page
    }
}

@Composable
fun WawLinkApp(app: AppCoordinator, files: HostFiles, navigation: HostNavigation? = null) {
    val appearance by app.appearance.collectAsState()
    val notice by app.notice.collectAsState()
    val busy by app.busy.collectAsState()
    val containers by app.runtime.containers.collectAsState()
    val sessions by app.sessions.collectAsState()
    val pageStates = rememberSaveableStateHolder()
    var page by rememberSaveable { mutableStateOf("home") }
    var selectedSession by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedContainer by rememberSaveable { mutableStateOf<String?>(null) }
    var newSession by remember { mutableStateOf(false) }
    var installRootfs by rememberSaveable { mutableStateOf(false) }
    var settingsReturn by rememberSaveable { mutableStateOf("home") }
    var terminalReturn by rememberSaveable { mutableStateOf("home") }
    val fileNavigation = remember { HostNavigation() }
    val settingsNavigation = remember { HostNavigation() }
    var containerAttachmentPick by remember { mutableStateOf<ContainerAttachmentPick?>(null) }
    val attachmentNavigation = remember { HostNavigation() }
    fun finishAttachmentPick(selection: List<ContainerFileSelection>) {
        val request = containerAttachmentPick
        containerAttachmentPick = null
        if (request?.continuation?.isActive == true) request.continuation.resume(selection)
    }
    suspend fun pickContainerAttachments(containerId: String, workspace: String): List<ContainerFileSelection> = withContext(Dispatchers.Main.immediate) {
        check(containerAttachmentPick == null) { "容器文件选择器已打开" }
        val container = app.runtime.containers.value.firstOrNull { it.config.id == containerId }?.config ?: error("会话容器已不存在")
        suspendCancellableCoroutine { continuation ->
            val request = ContainerAttachmentPick(container, workspace, continuation)
            containerAttachmentPick = request
            continuation.invokeOnCancellation { if (containerAttachmentPick === request) containerAttachmentPick = null }
        }
    }
    DisposableEffect(Unit) { onDispose { containerAttachmentPick?.continuation?.cancel() } }
    fun openTerminal(id: String) { selectedContainer = id; terminalReturn = page; page = "terminal" }
    var savedSessionIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    fun goBack(): Boolean = when {
        containerAttachmentPick != null -> { if (attachmentNavigation.onBack?.invoke() != true) finishAttachmentPick(emptyList()); true }
        newSession -> { newSession = false; true }
        page == "terminal" -> { page = terminalReturn; true }
        page == "files" && fileNavigation.onBack?.invoke() == true -> true
        page == "files" -> { page = "container"; true }
        (page == "providers" || page == "management") && settingsNavigation.onBack?.invoke() == true -> true
        page == "providers" || page == "management" || page == "framework" -> { page = settingsReturn; true }
        page != "home" -> { page = "home"; true }
        else -> false
    }
    SideEffect { navigation?.onBack = ::goBack }
    DisposableEffect(navigation) { onDispose { navigation?.onBack = null } }
    fun openNew() { if (containers.isEmpty()) { installRootfs = false; page = "install"; app.report("请先安装或导入一个容器") } else newSession = true }
    val configurationContainer = containers.firstOrNull { it.config.id == selectedContainer } ?: containers.firstOrNull { it.config.isDefault } ?: containers.firstOrNull()
    val route = when (page) {
        "chat" -> HostRoute(page, sessionId = selectedSession)
        "providers", "management" -> HostRoute(page, containerId = configurationContainer?.config?.id, sessionId = selectedSession.takeIf { settingsReturn == "chat" })
        "framework", "container" -> HostRoute(page, containerId = configurationContainer?.config?.id)
        "terminal", "files" -> HostRoute(page, containerId = selectedContainer)
        "install" -> HostRoute(page, rootfs = installRootfs)
        else -> HostRoute(page)
    }
    LaunchedEffect(sessions, route) {
        val retained = savedSessionIds.filter { id ->
            app.hasSession(id).also { exists -> if (!exists) pageStates.removeState("chat:$id") }
        }
        val currentId = route.sessionId?.takeIf(app::hasSession)
        savedSessionIds = if (currentId != null && currentId !in retained) retained + currentId else retained
    }
    AgentTheme(appearance) {
        Surface(Modifier.fillMaxSize().onPreviewKeyEvent { page != "terminal" && it.type == KeyEventType.KeyDown && it.key == Key.Escape && goBack() }) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = if (page == "terminal") 1200.dp / appearance.densityScale else AgentPageMaxWidth).fillMaxSize()) {
                    AnimatedContent(route, transitionSpec = {
                        (fadeIn(tween(380, easing = EmphDecelEasing)) + slideInHorizontally(tween(520, easing = EmphDecelEasing)) { it / 4 }) togetherWith
                            (fadeOut(tween(180, easing = EmphAccelEasing)) + slideOutHorizontally(tween(380, easing = EmphEasing)) { -it / 4 })
                    }, modifier = Modifier.weight(1f)) { current ->
                        pageStates.SaveableStateProvider(current.stateKey) {
                        when (current.page) {
                            "home" -> HomeScreen(app, files, onSettings = { page = "appearance" }, onNewSession = ::openNew,
                                onEnterSession = { selectedSession = it; page = "chat" },
                                onContainer = { selectedContainer = it; page = "container" },
                                onInstall = { installRootfs = it; page = "install" }, onTerminal = ::openTerminal)
                            "chat" -> {
                                val controller = remember(current.sessionId) { runCatching { app.openSession(requireNotNull(current.sessionId)) } }
                                controller.fold(onSuccess = {
                                    val record = sessions.firstOrNull { s -> s.record.id == current.sessionId }?.record
                                    if (record != null) AgentChatScreen(it, app.pool.providers(record.containerId), app.pool.management(record.containerId, record.workspace), { page = "home" }, ::openNew,
                                        { selectedContainer = record.containerId; settingsReturn = "chat"; page = "providers" },
                                        { selectedContainer = record.containerId; settingsReturn = "chat"; page = "management" },
                                        { selectedContainer = record.containerId; settingsReturn = "chat"; page = "framework" }, files::pickAttachments,
                                        onPickContainerFiles = {
                                            pickContainerAttachments(record.containerId, record.workspace).map { selected ->
                                                AttachmentFile(java.util.UUID.randomUUID().toString(), selected.file.name, selected.path,
                                                    sizeBytes = selected.file.size, source = AttachmentSource.CONTAINER)
                                            }
                                        }, appearance = appearance)
                                    else EmptyHost("会话已删除")
                                }, onFailure = { error -> Column { AgentTopBar("无法进入会话", { page = "home" }); EmptyHost(error.message ?: error.toString()) } })
                            }
                            "providers", "management" -> if (current.containerId != null) {
                                val session = current.sessionId?.let(app::openedSession)
                                val record = sessions.firstOrNull { session != null && it.record.id == current.sessionId && it.record.containerId == current.containerId }?.record
                                val management = remember(current.containerId, record?.workspace, record?.id) { app.pool.management(current.containerId, record?.workspace, record?.id) }
                                AgentManagementScreen(management, app.pool.providers(current.containerId), { page = settingsReturn },
                                    session = session.takeIf { record != null }, initialSection = if (current.page == "providers") "providers" else "home",
                                    registerBack = { settingsNavigation.onBack = it })
                            } else MissingContainer { page = "home" }
                            "framework" -> if (current.containerId != null) AgentConfigScreen(app.pool.settings(current.containerId), { page = settingsReturn }) else MissingContainer { page = "home" }
                            "appearance" -> AppearanceScreen(app, { page = "home" },
                                { if (configurationContainer != null) { settingsReturn = "appearance"; page = "providers" } else openNew() },
                                { if (configurationContainer != null) { settingsReturn = "appearance"; page = "management" } else openNew() },
                                { if (configurationContainer != null) { settingsReturn = "appearance"; page = "framework" } else openNew() })
                            "install" -> InstallContainerScreen(app, files, current.rootfs, { page = "home" })
                            "container" -> containers.firstOrNull { it.config.id == current.containerId }?.let { ContainerDetailScreen(app, files, it, { page = "home" }, ::openTerminal, { id -> selectedContainer = id; page = "files" }) } ?: MissingContainer { page = "home" }
                            "files" -> containers.firstOrNull { it.config.id == current.containerId }?.let { ContainerFilesScreen(app, files, it.config, { page = "container" }, { fileNavigation.onBack = it }) } ?: MissingContainer { page = "home" }
                            "terminal" -> containers.firstOrNull { it.config.id == current.containerId }?.let { TerminalScreen(app, it.config, { page = terminalReturn }) } ?: MissingContainer { page = terminalReturn }
                        }
                        }
                    }
                    if (busy > 0) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
                }
            }
        }
        if (newSession) SessionEditor(app, files, null, { newSession = false }) { id -> selectedSession = id; newSession = false; page = "chat" }
        containerAttachmentPick?.let { request ->
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { if (attachmentNavigation.onBack?.invoke() != true) finishAttachmentPick(emptyList()) },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(Modifier.fillMaxSize()) {
                    ContainerFilesScreen(app, files, request.container, { finishAttachmentPick(emptyList()) }, { attachmentNavigation.onBack = it },
                        initialPath = request.initialPath, onChooseFiles = ::finishAttachmentPick)
                }
            }
        }
        notice?.let { item ->
            AlertDialog(onDismissRequest = app::dismissNotice, title = { Text(if (item.error) "操作失败" else "提示") }, text = { Text(item.text, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) }, confirmButton = { TextButton(onClick = app::dismissNotice) { Text("知道了") } })
        }
    }
}

@Composable private fun MissingContainer(onBack: () -> Unit) { Column { AgentTopBar("容器不存在", onBack); EmptyHost("请先安装或导入容器") } }

@Composable
internal fun EmptyHost(text: String, icon: String = "forum") {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Symbol(icon, size = 48.dp, tint = MaterialTheme.colorScheme.outline)
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp)
    }
}
@Composable internal fun SectionLabel(title: String, icon: String = "tune", count: String? = null, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 20.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Symbol(icon, size = 20.dp, tint = MaterialTheme.colorScheme.primary)
        Text(title, color = MaterialTheme.colorScheme.primary, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        count?.let { Text(it, fontSize = 16.sp) }; action?.invoke()
    }
}
@Composable internal fun HostField(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier, singleLine: Boolean = true) {
    OutlinedTextField(value, onValue, modifier.fillMaxWidth().padding(vertical = 6.dp), label = { Text(label) }, singleLine = singleLine, shape = RoundedCornerShape(16.dp))
}
@Composable internal fun HostAction(label: String, icon: String, onClick: () -> Unit, modifier: Modifier = Modifier, tonal: Boolean = false, enabled: Boolean = true) {
    if (tonal) FilledTonalButton(onClick, modifier.heightIn(min = 48.dp), enabled = enabled) { Symbol(icon, size = 21.dp); Spacer(Modifier.width(8.dp)); Text(label, fontSize = 17.sp) }
    else Button(onClick, modifier.heightIn(min = 48.dp), enabled = enabled) { Symbol(icon, size = 21.dp); Spacer(Modifier.width(8.dp)); Text(label, fontSize = 17.sp) }
}
@Composable internal fun <T> HostChips(items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, icon: String = "check", enabled: (T) -> Boolean = { true }) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item -> FilterChip(selected == item, { onSelect(item) }, { Text(label(item), fontSize = 17.sp) }, enabled = enabled(item), leadingIcon = { Symbol(if (selected == item) "check" else icon, size = 20.dp) }, modifier = Modifier.heightIn(min = 44.dp), shape = RoundedCornerShape(if (selected == item) 22.dp else 14.dp), colors = FilterChipDefaults.filterChipColors(iconColor = MaterialTheme.colorScheme.onSurfaceVariant, selectedContainerColor = MaterialTheme.colorScheme.primaryContainer, selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer, selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer), border = FilterChipDefaults.filterChipBorder(enabled = enabled(item), selected = selected == item, borderColor = MaterialTheme.colorScheme.outlineVariant)) }
    }
}
@Composable internal fun HostRow(title: String, subtitle: String = "", icon: String = "tune", onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Symbol(icon, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) { Text(title, fontSize = 18.sp, fontWeight = FontWeight.Medium); if (subtitle.isNotEmpty()) Text(subtitle, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        trailing()
    }
}
@Composable private fun AppearanceScreen(app: AppCoordinator, onBack: () -> Unit, providers: () -> Unit, agent: () -> Unit, framework: () -> Unit) {
    val appearance by app.appearance.collectAsState()
    val defaultAutoApprove by app.defaultAutoApprove.collectAsState()
    Column {
        AgentTopBar("应用设置", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            SectionLabel("外观与显示", "palette")
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp))) {
                HostRow("整体缩放", "${appearance.effectiveUiScalePercent}%${if (appearance.effectiveUiScalePercent == AgentAppearance.DEFAULT_UI_SCALE_PERCENT) "（默认）" else ""}", "zoom_in") {
                    TextButton({ app.updateAppearance(app.appearance.value.copy(uiScalePercent = AgentAppearance.DEFAULT_UI_SCALE_PERCENT)) },
                        enabled = appearance.effectiveUiScalePercent != AgentAppearance.DEFAULT_UI_SCALE_PERCENT) { Text("恢复默认") }
                }
                Slider(
                    value = appearance.effectiveUiScalePercent.toFloat(),
                    onValueChange = { app.updateAppearance(app.appearance.value.copy(uiScalePercent = it.roundToInt()), persist = false) },
                    onValueChangeFinished = { app.updateAppearance(app.appearance.value) },
                    valueRange = AgentAppearance.MIN_UI_SCALE_PERCENT.toFloat()..AgentAppearance.MAX_UI_SCALE_PERCENT.toFloat(),
                    steps = 8,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics {
                        contentDescription = "整体缩放"
                        stateDescription = "${appearance.effectiveUiScalePercent}%"
                    },
                )
            }
            HostRow("深色主题", icon = "dark_mode") { Switch(appearance.darkTheme, { app.updateAppearance(appearance.copy(darkTheme = it)) }) }
            HostRow("色觉辅助模式", icon = "visibility") { Switch(appearance.colorVisionAssist, { app.updateAppearance(appearance.copy(colorVisionAssist = it)) }) }
            HostRow("平滑流式输出", icon = "animation") { Switch(appearance.smoothStreaming, { app.updateAppearance(appearance.copy(smoothStreaming = it)) }) }
            HostRow("常驻显示词元用量", icon = "token") { Switch(appearance.alwaysShowUsage, { app.updateAppearance(appearance.copy(alwaysShowUsage = it)) }) }
            HostRow("隐藏工具调用过程", icon = "construction") { Switch(appearance.hideToolCalls, { app.updateAppearance(appearance.copy(hideToolCalls = it)) }) }
            HostRow("自动折叠已压缩记录", icon = "unfold_less") { Switch(appearance.collapseCompacted, { app.updateAppearance(appearance.copy(collapseCompacted = it)) }) }
            SectionLabel("会话", "forum")
            HostRow("新会话默认免审批", icon = "bolt") { Switch(defaultAutoApprove, app::updateDefaultAutoApprove) }
            SectionLabel("当前容器 Agent", "smart_toy")
            HostRow("模型服务", icon = "cloud", onClick = providers) { Symbol("chevron_right") }
            HostRow("Agent 设置", icon = "smart_toy", onClick = agent) { Symbol("chevron_right") }
            HostRow("框架设置", icon = "tune", onClick = framework) { Symbol("chevron_right") }
        }
    }
}
