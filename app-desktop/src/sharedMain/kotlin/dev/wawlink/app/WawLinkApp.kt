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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wawlink.protocol.*
import dev.wawlink.runtime.*
import dev.wawlink.ui.*
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.roundToInt

class HostNavigation { var onBack: (() -> Boolean)? = null }

private data class ContainerAttachmentPick(val container: ContainerConfig, val initialPath: String, val continuation: CancellableContinuation<List<ContainerFileSelection>>)
private data class ContainerFileOpenRequest(val config: ContainerConfig, val source: String)

private data class HostRoute(
    val page: String,
    val sessionId: String? = null,
    val containerId: String? = null,
    val rootfs: Boolean = false,
    val installationId: String? = null,
    val returnPage: String = "home",
    val resourceWorkspace: String? = null,
    val initialResource: String? = null,
    val feature: ContainerFeature? = null,
) {
    val stateKey: String = when (page) {
        "chat", "hub" -> "$page:$sessionId"
        "providers", "management" -> "$page:$containerId:${sessionId ?: "global"}:${initialResource.orEmpty()}"
        "framework", "container", "files", "rules", "prompts" -> "$page:$containerId"
        "plugins", "skills" -> "$page:$containerId:${resourceWorkspace ?: "user"}:$sessionId:$initialResource"
        "extension" -> "extension:$containerId:${feature?.id}"
        "terminal" -> "terminal:$containerId:${installationId ?: "shell"}"
        "install" -> "install:$rootfs"
        else -> page
    }
}

@Composable
fun WawLinkApp(app: AppCoordinator, files: HostFiles, navigation: HostNavigation? = null) {
    val appearance by app.appearance.collectAsState()
    val notice by app.notice.collectAsState()
    val ompSetup by app.ompSetup.collectAsState()
    val busy by app.busy.collectAsState()
    val containers by app.runtime.containers.collectAsState()
    val sessions by app.sessions.collectAsState()
    val currentContainerId by app.selectedContainerId.collectAsState()
    val pageStates = rememberSaveableStateHolder()
    val saveImage: suspend (String, String, ByteArray) -> Boolean = remember(files) {
        { name, mimeType, bytes -> files.saveFile(name, mimeType) { output -> output.write(bytes) } }
    }
    var page by rememberSaveable { mutableStateOf("home") }
    var selectedSession by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedContainer by rememberSaveable { mutableStateOf<String?>(null) }
    var installRootfs by rememberSaveable { mutableStateOf(false) }
    var creatingInContext by remember { mutableStateOf(false) }
    var fileOpenRequest by remember { mutableStateOf<ContainerFileOpenRequest?>(null) }
    var linkedDirectory by remember { mutableStateOf<ContainerFileOpenRequest?>(null) }
    val linkedDirectoryNavigation = remember { HostNavigation() }
    var settingsReturn by rememberSaveable { mutableStateOf("home") }
    var terminalReturn by rememberSaveable { mutableStateOf("home") }
    var terminalInstallationId by rememberSaveable { mutableStateOf<String?>(null) }
    var filesReturn by rememberSaveable { mutableStateOf("container") }
    var resourcesReturn by rememberSaveable { mutableStateOf("home") }
    var resourceWorkspace by rememberSaveable { mutableStateOf<String?>(null) }
    var resourceSession by rememberSaveable { mutableStateOf<String?>(null) }
    var initialResource by rememberSaveable { mutableStateOf<String?>(null) }
    var activeFeature by remember { mutableStateOf<ContainerFeature?>(null) }
    val resourceNavigation = remember { HostNavigation() }
    val rulesNavigation = remember { HostNavigation() }
    val promptsNavigation = remember { HostNavigation() }
    val fileNavigation = remember { HostNavigation() }
    val settingsNavigation = remember { HostNavigation() }
    val hubNavigation = remember { HostNavigation() }
    val chatNavigations = remember { mutableMapOf<String, AgentChatNavigation>() }
    val homeNavigation = remember { HostNavigation() }
    var definitionName by rememberSaveable { mutableStateOf<String?>(null) }
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
    fun openTerminal(id: String) { selectedContainer = id; terminalInstallationId = null; terminalReturn = page; page = "terminal" }
    var savedSessionIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    fun leaveChat() { page = "home" }
    fun openResources(kind: String, containerId: String, workspace: String? = null, sessionId: String? = null, initial: String? = null) {
        if (page != "plugins" && page != "skills") resourcesReturn = page
        selectedContainer = containerId
        resourceWorkspace = workspace
        resourceSession = sessionId
        initialResource = initial
        page = kind
    }
    fun openFeature(kind: String) {
        val id = app.currentContainerId()
        if (id == null) { app.report("请先安装或导入一个容器"); return }
        when (kind) {
            "terminal" -> openTerminal(id)
            "files" -> { selectedContainer = id; filesReturn = "home"; page = "files" }
            "rules" -> { selectedContainer = id; page = "rules" }
            "prompts" -> { selectedContainer = id; page = "prompts" }
            "plugins", "skills" -> openResources(kind, id)
        }
    }
    fun goBack(): Boolean = when {
        linkedDirectory != null -> { if (linkedDirectoryNavigation.onBack?.invoke() != true) linkedDirectory = null; true }
        fileOpenRequest != null -> { fileOpenRequest = null; true }
        containerAttachmentPick != null -> { if (attachmentNavigation.onBack?.invoke() != true) finishAttachmentPick(emptyList()); true }
        page == "home" && homeNavigation.onBack?.invoke() == true -> true
        page == "terminal" -> { page = terminalReturn; true }
        page == "files" && fileNavigation.onBack?.invoke() == true -> true
        page == "files" -> { page = filesReturn; true }
        (page == "providers" || page == "management") && settingsNavigation.onBack?.invoke() == true -> true
        page == "providers" || page == "management" || page == "framework" -> { page = settingsReturn; true }
        (page == "plugins" || page == "skills") && resourceNavigation.onBack?.invoke() == true -> true
        page == "plugins" || page == "skills" -> { page = resourcesReturn; true }
        page == "rules" && rulesNavigation.onBack?.invoke() == true -> true
        page == "prompts" && promptsNavigation.onBack?.invoke() == true -> true
        page == "hub" && hubNavigation.onBack?.invoke() == true -> true
        page == "hub" -> { page = "chat"; true }
        page == "chat" && chatNavigations[selectedSession]?.back() == true -> true
        page == "chat" -> { leaveChat(); true }
        page == "about" || page == "keepalive" -> { page = "appearance"; true }
        page != "home" -> { page = "home"; true }
        else -> false
    }
    SideEffect { navigation?.onBack = ::goBack }
    DisposableEffect(navigation) { onDispose { navigation?.onBack = null } }
    fun openNew() {
        if (creatingInContext) return
        if (app.currentContainerId() == null) { installRootfs = false; page = "install"; app.report("请先安装或导入一个容器"); return }
        creatingInContext = true
        app.launch {
            try {
                val id = app.createInSelectedContainer()
                withContext(Dispatchers.Main.immediate) { selectedSession = id; page = "chat" }
            } finally { withContext(NonCancellable + Dispatchers.Main.immediate) { creatingInContext = false } }
        }
    }
    fun createInContext(id: String) {
        if (creatingInContext) return
        creatingInContext = true
        app.launch {
            try {
                val next = app.createInContext(id)
                withContext(Dispatchers.Main.immediate) { selectedSession = next; page = "chat" }
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) { creatingInContext = false }
            }
        }
    }
    fun openSessionFile(record: SessionRecord, source: String) {
        runCatching {
            val config = containers.firstOrNull { it.config.id == record.containerId }?.config ?: error("会话容器已不存在")
            ContainerFileOpenRequest(config, ContainerFileService.path(source, record.workspace))
        }.onSuccess { fileOpenRequest = it }.onFailure(app::showError)
    }
    val configurationContainer = containers.firstOrNull { it.config.id == (selectedContainer ?: currentContainerId) }
    val route = when (page) {
        "chat", "hub" -> HostRoute(page, sessionId = selectedSession, returnPage = if (page == "hub") "chat" else "home")
        "providers", "management" -> HostRoute(page, containerId = configurationContainer?.config?.id,
            sessionId = if (settingsReturn == "chat" || settingsReturn == "hub") selectedSession else resourceSession.takeIf { settingsReturn == "plugins" || settingsReturn == "skills" },
            returnPage = settingsReturn, resourceWorkspace = resourceWorkspace.takeIf { settingsReturn == "plugins" || settingsReturn == "skills" },
            initialResource = definitionName.takeIf { settingsReturn == "hub" })
        "framework" -> HostRoute(page, containerId = configurationContainer?.config?.id, returnPage = settingsReturn)
        "container", "rules", "prompts" -> HostRoute(page, containerId = selectedContainer)
        "plugins", "skills" -> HostRoute(page, containerId = selectedContainer, sessionId = resourceSession, returnPage = resourcesReturn, resourceWorkspace = resourceWorkspace, initialResource = initialResource)
        "extension" -> HostRoute(page, containerId = selectedContainer, feature = activeFeature)
        "terminal" -> HostRoute(page, containerId = selectedContainer, installationId = terminalInstallationId, returnPage = terminalReturn)
        "files" -> HostRoute(page, containerId = selectedContainer, returnPage = filesReturn)
        "install" -> HostRoute(page, rootfs = installRootfs)
        "about", "keepalive" -> HostRoute(page, returnPage = "appearance")
        else -> HostRoute(page)
    }
    LaunchedEffect(sessions, route) {
        val retained = savedSessionIds.filter { id ->
            app.hasSession(id).also { exists -> if (!exists) { pageStates.removeState("chat:$id"); pageStates.removeState("hub:$id"); chatNavigations.remove(id) } }
        }
        val currentId = route.sessionId?.takeIf(app::hasSession)
        savedSessionIds = if (currentId != null && currentId !in retained) retained + currentId else retained
    }
    AgentTheme(appearance) {
        val topInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top)
        Surface(Modifier.fillMaxSize().onPreviewKeyEvent { page != "terminal" && it.type == KeyEventType.KeyDown && it.key == Key.Escape && goBack() }) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = when (page) {
                    "terminal" -> 1200.dp / appearance.densityScale
                    "chat" -> Dp.Infinity
                    else -> AgentPageMaxWidth
                }).fillMaxSize()) {
                    AnimatedContent(route, transitionSpec = {
                        val back = targetState.page == "home" || targetState.page == initialState.returnPage
                        (fadeIn(tween(380, easing = EmphDecelEasing)) + slideInHorizontally(tween(520, easing = EmphDecelEasing)) { if (back) -it / 4 else it / 4 }) togetherWith
                            (fadeOut(tween(180, easing = EmphAccelEasing)) + slideOutHorizontally(tween(380, easing = EmphEasing)) { if (back) it / 4 else -it / 4 })
                    }, modifier = Modifier.weight(1f), contentKey = { it.stateKey }) { current ->
                        Box(Modifier.fillMaxSize().then(if (current.page == "chat") Modifier else Modifier.windowInsetsPadding(topInsets))) {
                        pageStates.SaveableStateProvider(current.stateKey) {
                        when (current.page) {
                            "home" -> HomeScreen(app, files, onSettings = { selectedContainer = app.currentContainerId(); page = "appearance" }, onNewSession = ::openNew,
                                onEnterSession = { selectedSession = it; page = "chat" },
                                onContainer = { selectedContainer = it; page = "container" },
                                onInstall = { installRootfs = it; page = "install" }, onFeature = ::openFeature,
                                onExtension = { feature -> selectedContainer = app.currentContainerId(); activeFeature = feature; page = "extension" }, navigation = homeNavigation)
                            "chat" -> {
                                DisposableEffect(current.sessionId) {
                                    onDispose { if (page == "home" || selectedSession != current.sessionId) current.sessionId?.let(app::leaveSession) }
                                }
                                val controller = remember(current.sessionId) { runCatching { app.openSession(requireNotNull(current.sessionId)) } }
                                controller.fold(onSuccess = {
                                    val record = sessions.firstOrNull { s -> s.record.id == current.sessionId }?.record
                                    if (record != null) CompositionLocalProvider(
                                        LocalAgentFileLinkHandler provides { source -> openSessionFile(record, source) },
                                        LocalAgentImageSaveHandler provides saveImage,
                                        LocalAgentAttachmentHandler provides remember(app.runtime, record.containerId) { SessionAttachmentReader(app.runtime, record.containerId) },
                                    ) {
                                        AgentChatScreen(it, app.pool.providers(record.containerId), app.pool.management(record.containerId, record.workspace), ::leaveChat, { createInContext(record.id) },
                                        { selectedContainer = record.containerId; settingsReturn = "chat"; page = "providers" },
                                        { selectedContainer = record.containerId; settingsReturn = "chat"; page = "management" },
                                        { selectedContainer = record.containerId; settingsReturn = "chat"; page = "framework" }, files::pickAttachments,
                                        onPickContainerFiles = {
                                            pickContainerAttachments(record.containerId, record.workspace).map { selected ->
                                                AttachmentFile(java.util.UUID.randomUUID().toString(), selected.file.name, selected.path,
                                                    mimeType = containerFileMimeType(selected.file.name), sizeBytes = selected.file.size, source = AttachmentSource.CONTAINER)
                                            }
                                        }, onOpenProjectPlugins = { openResources("plugins", record.containerId, record.workspace, record.id) },
                                        onOpenProjectSkills = { openResources("skills", record.containerId, record.workspace, record.id) },
                                        explorer = app.pool.explorer(record.id), onOpenAgentHub = { page = "hub" },
                                        onFork = { source -> app.registerFork(record.id, source) },
                                        onOpenFork = { id -> selectedSession = id; page = "chat" },
                                        navigation = chatNavigations.getOrPut(record.id) { AgentChatNavigation() }, appearance = appearance,
                                        onRequestOmpRepair = { app.requestOmpInstallation(record.containerId) })
                                    }
                                    else Box(Modifier.windowInsetsPadding(topInsets)) { EmptyHost("会话已删除") }
                                }, onFailure = { error -> Column(Modifier.windowInsetsPadding(topInsets)) { AgentTopBar("无法进入会话", { page = "home" }); EmptyHost(error.message ?: error.toString()) } })
                            }
                            "hub" -> {
                                val controller = remember(current.sessionId) { runCatching { app.openSession(requireNotNull(current.sessionId)) } }
                                val record = sessions.firstOrNull { it.record.id == current.sessionId }?.record
                                controller.fold(onSuccess = { main ->
                                    if (record == null) EmptyHost("会话已删除")
                                    else CompositionLocalProvider(
                                        LocalAgentImageSaveHandler provides saveImage,
                                        LocalAgentAttachmentHandler provides remember(app.runtime, record.containerId) { SessionAttachmentReader(app.runtime, record.containerId) },
                                    ) {
                                        AgentHubScreen(app.pool.explorer(record.id), main, app.pool.providers(record.containerId),
                                            app.pool.management(record.containerId, record.workspace),
                                            onBack = { page = "chat" }, onMain = { page = "chat" },
                                            onLocateMain = { id -> chatNavigations.getOrPut(record.id) { AgentChatNavigation() }.locate(id); page = "chat" },
                                            onOpenDefinition = { name -> selectedContainer = record.containerId; definitionName = name; settingsReturn = "hub"; page = "management" },
                                            onPickFiles = files::pickAttachments,
                                            onPickContainerFiles = { workspace -> pickContainerAttachments(record.containerId, workspace).map { selected ->
                                                AttachmentFile(java.util.UUID.randomUUID().toString(), selected.file.name, selected.path,
                                                    mimeType = containerFileMimeType(selected.file.name), sizeBytes = selected.file.size, source = AttachmentSource.CONTAINER)
                                            } },
                                            onOpenFile = { workspace, source -> openSessionFile(record.copy(workspace = workspace), source) },
                                            onExport = { export ->
                                                files.saveFile(hostFileName(export.name), export.mimeType) { output ->
                                                    val container = app.runtime.containers.value.firstOrNull { it.config.id == record.containerId }?.config ?: error("会话容器已不存在")
                                                    ContainerFileService(app.runtime, container).exportFile(export.path, output)
                                                }
                                            }, registerBack = { handler -> if (page == "hub") hubNavigation.onBack = handler })
                                    }
                                }, onFailure = { error -> Column { AgentTopBar("无法进入 Agent", { page = "chat" }); EmptyHost(error.message ?: error.toString()) } })
                            }
                            "providers", "management" -> if (current.containerId != null) {
                                val session = current.sessionId?.let(app::openedSession)
                                val record = sessions.firstOrNull { session != null && it.record.id == current.sessionId && it.record.containerId == current.containerId }?.record
                                val management = remember(current.containerId, record?.workspace, record?.id, current.resourceWorkspace) { app.pool.management(current.containerId, record?.workspace ?: current.resourceWorkspace, record?.id) }
                                AgentManagementScreen(management, app.pool.providers(current.containerId), { page = settingsReturn },
                                    session = session.takeIf { record != null }, initialSection = when { current.page == "providers" -> "providers"; current.returnPage == "hub" -> "agents"; else -> "home" },
                                    registerBack = { settingsNavigation.onBack = it }, initialAgent = current.initialResource)
                            } else MissingContainer { page = "home" }
                            "framework" -> if (current.containerId != null) AgentConfigScreen(app.pool.settings(current.containerId), { page = settingsReturn }) else MissingContainer { page = "home" }
                            "appearance" -> AppearanceScreen(app, { page = "home" },
                                { if (configurationContainer != null) { settingsReturn = "appearance"; page = "providers" } else openNew() },
                                { if (configurationContainer != null) { settingsReturn = "appearance"; page = "management" } else openNew() },
                                { if (configurationContainer != null) { settingsReturn = "appearance"; page = "framework" } else openNew() },
                                { page = "keepalive" }, { page = "about" })
                            "about" -> AboutScreen({ page = "appearance" }, app::showError)
                            "keepalive" -> KeepAliveScreen(app, files, { page = "appearance" })
                            "install" -> InstallContainerScreen(app, files, current.rootfs, { page = "home" })
                            "container" -> containers.firstOrNull { it.config.id == current.containerId }?.let { ContainerDetailScreen(app, files, it, { page = "home" }, ::openTerminal, { id -> selectedContainer = id; filesReturn = "container"; page = "files" }) } ?: MissingContainer { page = "home" }
                            "files" -> containers.firstOrNull { it.config.id == current.containerId }?.let { ContainerFilesScreen(app, files, it.config, { page = current.returnPage }, { fileNavigation.onBack = it }) } ?: MissingContainer { page = "home" }
                            "terminal" -> containers.firstOrNull { it.config.id == current.containerId }?.let { TerminalScreen(app, it.config, { page = terminalReturn }, current.installationId) } ?: MissingContainer { page = terminalReturn }
                            "rules" -> containers.firstOrNull { it.config.id == current.containerId }?.let { ContainerRulesScreen(app, it.config, { page = "home" }, { rulesNavigation.onBack = it }) } ?: MissingContainer { page = "home" }
                            "prompts" -> containers.firstOrNull { it.config.id == current.containerId }?.let { ContainerPromptsScreen(app, it.config, { page = "home" }, { promptsNavigation.onBack = it }) } ?: MissingContainer { page = "home" }
                            "extension" -> if (current.containerId != null && current.feature != null) ContainerExtensionScreen(app.features, current.containerId, current.feature, { page = "home" }) else MissingContainer { page = "home" }
                            "plugins", "skills" -> if (current.containerId != null && containers.any { it.config.id == current.containerId }) {
                                val repository = remember(current.containerId, current.resourceWorkspace, current.sessionId) { app.pool.resources(current.containerId, current.resourceWorkspace, current.sessionId) }
                                val openAgentSettings = { settingsReturn = current.page; page = "management" }
                                if (current.page == "plugins") PluginManagementScreen(repository, app, files, { page = current.returnPage }, { resourceNavigation.onBack = it },
                                    onSkills = { openResources("skills", current.containerId, current.resourceWorkspace, current.sessionId, it) }, onAgentSettings = openAgentSettings, initialPlugin = current.initialResource)
                                else SkillManagementScreen(repository, app, files, { page = current.returnPage }, { resourceNavigation.onBack = it },
                                    onPlugins = { openResources("plugins", current.containerId, current.resourceWorkspace, current.sessionId, it) }, onAgentSettings = openAgentSettings,
                                    onUseSkill = current.sessionId?.let { id -> { alias: String, instructions: String ->
                                        runCatching { requireNotNull(app.openedSession(id)) { "会话已关闭" }.sendPrompt("/skill:$alias" + instructions.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty()); page = "chat" }.onFailure(app::showError)
                                    } }, initialSkill = current.initialResource)
                            } else MissingContainer { page = current.returnPage }
                        }
                        }
                        }
                    }
                    if (busy > 0) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
                }
            }
        }
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
        fileOpenRequest?.let { request ->
            ContainerFileLinkSheet(app, files, request.config, request.source, onDismiss = { fileOpenRequest = null },
                onOpenDirectory = { directory ->
                    linkedDirectory = ContainerFileOpenRequest(request.config, directory)
                    fileOpenRequest = null
                })
        }
        linkedDirectory?.let { request ->
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { if (linkedDirectoryNavigation.onBack?.invoke() != true) linkedDirectory = null },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(Modifier.fillMaxSize()) {
                    ContainerFilesScreen(app, files, request.config, { linkedDirectory = null }, { linkedDirectoryNavigation.onBack = it },
                        initialPath = request.source)
                }
            }
        }
        val setup = ompSetup
        if (setup != null) {
            val containerName = containers.firstOrNull { it.config.id == setup.containerId }?.config?.displayName.orEmpty()
            AlertDialog(
                onDismissRequest = { app.dismissOmpSetup(setup) },
                title = { Text(if (setup.repair) "修复 OMP" else "安装 OMP") },
                text = { Text("容器「$containerName」：${setup.message}\n\n同意后将打开终端，自动${if (setup.repair) "重新" else ""}安装 OMP 及运行依赖，并暂停此容器的 OMP 进程。已有配置、认证和会话会保留。安装期间请勿离开终端。") },
                confirmButton = {
                    TextButton({
                        app.confirmOmpSetup(setup)?.let { installationId ->
                            selectedContainer = setup.containerId
                            terminalInstallationId = installationId
                            if (page != "terminal") terminalReturn = page
                            page = "terminal"
                        }
                    }) { Text(if (setup.repair) "打开终端并修复" else "打开终端并安装") }
                },
                dismissButton = { TextButton({ app.dismissOmpSetup(setup) }) { Text("取消") } },
            )
        } else notice?.let { item ->
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
@Composable private fun AppearanceScreen(app: AppCoordinator, onBack: () -> Unit, providers: () -> Unit, agent: () -> Unit, framework: () -> Unit, keepAlive: () -> Unit, about: () -> Unit) {
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
            AgentPaletteSettings(appearance) { app.updateAppearance(it) }
            HostRow("深色主题", icon = "dark_mode") { Switch(appearance.darkTheme, { app.updateAppearance(appearance.copy(darkTheme = it)) }) }
            if (app.isAndroid) {
                val keepScreenOn by app.keepScreenOn.collectAsState()
                HostRow("屏幕常亮", icon = "light_mode") {
                    Switch(keepScreenOn, app::updateKeepScreenOn, modifier = Modifier.semantics { contentDescription = "屏幕常亮" })
                }
            }
            HostRow("色觉辅助模式", icon = "visibility") { Switch(appearance.colorVisionAssist, { app.updateAppearance(appearance.copy(colorVisionAssist = it)) }) }
            HostRow("平滑流式输出", icon = "animation") { Switch(appearance.smoothStreaming, { app.updateAppearance(appearance.copy(smoothStreaming = it)) }) }
            HostRow("常驻显示词元用量", icon = "token") { Switch(appearance.alwaysShowUsage, { app.updateAppearance(appearance.copy(alwaysShowUsage = it)) }) }
            HostRow("隐藏工具调用过程", icon = "construction") { Switch(appearance.hideToolCalls, { app.updateAppearance(appearance.copy(hideToolCalls = it)) }) }
            HostRow("自动折叠已压缩记录", icon = "unfold_less") { Switch(appearance.collapseCompacted, { app.updateAppearance(appearance.copy(collapseCompacted = it)) }) }
            FeatureCardsSettings(app)
            SectionLabel("会话", "forum")
            HostRow("新会话默认免审批", icon = "bolt") { Switch(defaultAutoApprove, app::updateDefaultAutoApprove) }
            SectionLabel("当前容器 Agent", "smart_toy")
            HostRow("模型服务", icon = "deployed_code", onClick = providers) { Symbol("chevron_right") }
            HostRow("Agent 设置", icon = "smart_toy", onClick = agent) { Symbol("chevron_right") }
            HostRow("框架设置", icon = "tune", onClick = framework) { Symbol("chevron_right") }
            SectionLabel("应用", "apps")
            HostRow("后台保活", "电池与后台运行", "shield", onClick = keepAlive) { Symbol("chevron_right") }
            HostRow("关于", "项目与作者", "info", onClick = about) { Symbol("chevron_right") }
        }
    }
}

@Composable private fun AboutScreen(onBack: () -> Unit, onError: (Throwable) -> Unit) {
    val uriHandler = LocalUriHandler.current
    val githubUrl = "https://github.com/wsdx233/WawLink"
    Column {
        AgentTopBar("关于", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ListItem(
                headlineContent = { Text("GitHub") },
                supportingContent = { Text(githubUrl) },
                leadingContent = { Symbol("code") },
                modifier = Modifier.clickable { runCatching { uriHandler.openUri(githubUrl) }.onFailure(onError) },
            )
            ListItem(
                headlineContent = { Text("作者") },
                supportingContent = { Text("wsdx233") },
                leadingContent = { Symbol("person") },
            )
        }
    }
}
