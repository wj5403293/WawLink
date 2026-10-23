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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wawlink.protocol.*
import dev.wawlink.runtime.*
import dev.wawlink.ui.*

class HostNavigation { var onBack: (() -> Boolean)? = null }

private data class HostRoute(
    val page: String,
    val sessionId: String? = null,
    val containerId: String? = null,
    val rootfs: Boolean = false,
) {
    val stateKey: String = when (page) {
        "chat" -> "chat:$sessionId"
        "providers", "agent", "container" -> "$page:$containerId"
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
    var savedSessionIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    fun goBack(): Boolean = when {
        newSession -> { newSession = false; true }
        page == "providers" || page == "agent" -> { page = settingsReturn; true }
        page != "home" -> { page = "home"; true }
        else -> false
    }
    SideEffect { navigation?.onBack = ::goBack }
    DisposableEffect(navigation) { onDispose { navigation?.onBack = null } }
    fun openNew() { if (containers.isEmpty()) { installRootfs = false; page = "install"; app.report("请先安装或导入一个容器") } else newSession = true }
    val configurationContainer = containers.firstOrNull { it.config.id == selectedContainer } ?: containers.firstOrNull { it.config.isDefault } ?: containers.firstOrNull()
    val route = when (page) {
        "chat" -> HostRoute(page, sessionId = selectedSession)
        "providers", "agent", "container" -> HostRoute(page, containerId = configurationContainer?.config?.id)
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
        Surface(Modifier.fillMaxSize().onPreviewKeyEvent { it.type == KeyEventType.KeyDown && it.key == Key.Escape && goBack() }) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 460.dp).fillMaxSize()) {
                    AnimatedContent(route, transitionSpec = {
                        (fadeIn(tween(380, easing = EmphDecelEasing)) + slideInHorizontally(tween(520, easing = EmphDecelEasing)) { it / 4 }) togetherWith
                            (fadeOut(tween(180, easing = EmphAccelEasing)) + slideOutHorizontally(tween(380, easing = EmphEasing)) { -it / 4 })
                    }, modifier = Modifier.weight(1f)) { current ->
                        pageStates.SaveableStateProvider(current.stateKey) {
                        when (current.page) {
                            "home" -> HomeScreen(app, onSettings = { page = "appearance" }, onNewSession = ::openNew,
                                onEnterSession = { selectedSession = it; page = "chat" },
                                onContainer = { selectedContainer = it; page = "container" },
                                onInstall = { installRootfs = it; page = "install" })
                            "chat" -> {
                                val controller = remember(current.sessionId) { runCatching { app.controller(requireNotNull(current.sessionId)) } }
                                controller.fold(onSuccess = {
                                    val record = sessions.firstOrNull { s -> s.record.id == current.sessionId }?.record
                                    if (record != null) AgentChatScreen(it, app.pool.providers(record.containerId), { page = "home" }, ::openNew,
                                        { selectedContainer = record.containerId; settingsReturn = "chat"; page = "providers" }, { selectedContainer = record.containerId; settingsReturn = "chat"; page = "agent" }, files::pickAttachments, appearance)
                                    else EmptyHost("会话已删除")
                                }, onFailure = { error -> Column { AgentTopBar("无法进入会话", { page = "home" }); EmptyHost(error.message ?: error.toString()) } })
                            }
                            "providers" -> if (current.containerId != null) ProviderSettingsScreen(app.pool.providers(current.containerId), { page = settingsReturn }) else MissingContainer { page = "home" }
                            "agent" -> if (current.containerId != null) AgentConfigScreen(app.pool.settings(current.containerId), { page = settingsReturn }) else MissingContainer { page = "home" }
                            "appearance" -> AppearanceScreen(app, { page = "home" }, { if (configurationContainer != null) { settingsReturn = "appearance"; page = "providers" } else openNew() }, { if (configurationContainer != null) { settingsReturn = "appearance"; page = "agent" } else openNew() })
                            "install" -> InstallContainerScreen(app, files, current.rootfs, { page = "home" })
                            "container" -> containers.firstOrNull { it.config.id == current.containerId }?.let { ContainerDetailScreen(app, files, it, { page = "home" }) } ?: MissingContainer { page = "home" }
                        }
                        }
                    }
                    if (busy > 0) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
                }
            }
        }
        if (newSession) SessionEditor(app, null, { newSession = false }) { id -> selectedSession = id; newSession = false; page = "chat" }
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
@Composable private fun AppearanceScreen(app: AppCoordinator, onBack: () -> Unit, providers: () -> Unit, agent: () -> Unit) {
    val appearance by app.appearance.collectAsState()
    val defaultAutoApprove by app.defaultAutoApprove.collectAsState()
    Column {
        AgentTopBar("应用设置", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            SectionLabel("外观与显示", "palette")
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
            HostRow("Agent 设置", icon = "tune", onClick = agent) { Symbol("chevron_right") }
        }
    }
}
