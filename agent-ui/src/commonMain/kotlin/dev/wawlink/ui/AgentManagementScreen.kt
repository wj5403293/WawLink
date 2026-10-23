package dev.wawlink.ui

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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wawlink.protocol.*

/** Native implementation of ref-plan/agent-settings.html. Framework settings stay independent. */
@Composable
fun AgentManagementScreen(
    repository: AgentManagementRepository,
    providers: ProviderConfigRepository,
    onBack: () -> Unit,
    session: AgentSessionController? = null,
    initialSection: String = "home",
    registerBack: ((() -> Boolean)?) -> Unit = {},
) {
    val state by repository.state.collectAsState()
    val persistedProviders by providers.providers.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val actions = remember(repository) { ManagementActions(coroutineScope) { snackbar.showSnackbar(it) } }
    var page by rememberSaveable { mutableStateOf(initialSection) }
    var targetProvider by rememberSaveable { mutableStateOf("") }
    var configurationScope by rememberSaveable { mutableStateOf(ConfigurationScope.GLOBAL) }
    var childBack by remember { mutableStateOf<(() -> Boolean)?>(null) }
    val pageStates = rememberSaveableStateHolder()
    val rootBack by rememberUpdatedState(onBack)
    fun back(): Boolean {
        if (childBack?.invoke() == true) return true
        if (page != initialSection) { childBack = null; page = initialSection } else rootBack()
        return true
    }
    val currentBack by rememberUpdatedState(::back)
    val currentRegistration by rememberUpdatedState(registerBack)
    DisposableEffect(repository) { currentRegistration { currentBack() }; onDispose { currentRegistration(null) } }
    LaunchedEffect(state.context.projectPath) { if (state.context.projectPath == null) configurationScope = ConfigurationScope.GLOBAL }
    fun navigate(destination: String) {
        if (destination == "providers" && page != "native-provider") pageStates.removeState("providers")
        childBack = null
        page = destination
    }
    CompositionLocalProvider(LocalManagementActions provides actions, LocalManagementSession provides session) {
        CompositionLocalProvider(LocalManagementRepository provides repository) {
        Box(Modifier.fillMaxSize().background(LocalAgentColors.current.surface)) {
            AnimatedContent(page, transitionSpec = {
                val forward = targetState != initialSection
                (slideInHorizontally(tween(550, easing = EmphDecelEasing)) { if (forward) it else -it / 5 } + fadeIn(tween(400))) togetherWith
                    (slideOutHorizontally(tween(360, easing = EmphAccelEasing)) { if (forward) -it / 5 else it } + fadeOut(tween(300)))
            }) { current ->
                pageStates.SaveableStateProvider(if (current == "native-provider") "native-provider:$targetProvider" else current) {
                    val setBack = remember(current) { { handler: (() -> Boolean)? -> if (page == current) childBack = handler } }
                    val goHome: () -> Unit = { if (current == initialSection) rootBack() else navigate(initialSection) }
                    when (current) {
                        "home" -> ManagementHome(repository, state, configurationScope, { configurationScope = it }, persistedProviders, goHome, ::navigate)
                        "agents" -> ManagementAgentsScreen(repository, configurationScope, { configurationScope = it }, goHome, setBack)
                        "advisors" -> ManagementAdvisorsScreen(repository, configurationScope, { configurationScope = it }, goHome, setBack)
                        "roles" -> ManagementRolesScreen(repository, goHome, setBack)
                        "runtime" -> ManagementRuntimeScreen(repository, configurationScope, { configurationScope = it }, goHome, setBack)
                        "presets", "allow", "fallback" -> ManagementPoliciesScreen(repository, configurationScope, { configurationScope = it }, goHome, setBack, current)
                        "providers" -> ProviderSettingsScreen(providers, goHome, onAdvanced = { id -> targetProvider = id; navigate("native-provider") }, registerBack = setBack)
                        "native-provider" -> ManagementNativeProviderScreen(repository, targetProvider, { navigate("providers") }, setBack, { navigate("auth") }, providers)
                        "auth" -> ManagementAuthenticationScreen(repository, state, { id -> targetProvider = id; navigate("native-provider") }, goHome)
                        "advanced" -> ManagementConfigurationFiles(repository, state, goHome, setBack)
                    }
                }
            }
            if (state.loading || actions.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp).align(Alignment.TopCenter))
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
        }
    }
}

@Composable
private fun ManagementHome(repository: AgentManagementRepository, state: AgentManagementState, configurationScope: ConfigurationScope, onScopeChange: (ConfigurationScope) -> Unit, providers: List<ProviderConfig>, onBack: () -> Unit, navigate: (String) -> Unit) {
    val c = LocalAgentColors.current
    val actions = LocalManagementActions.current
    var query by rememberSaveable { mutableStateOf("") }
    var onlyModified by rememberSaveable { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }
    var sessionSheet by remember { mutableStateOf(false) }
    var selectedSetting by remember { mutableStateOf<SettingItem?>(null) }
    val session = LocalManagementSession.current
    val sessionState = session?.state?.collectAsState()?.value
    val values = if (configurationScope == ConfigurationScope.PROJECT) state.projectValues else state.globalValues
    val settings = state.categories.flatMap { it.groups }.flatMap { it.items }.filter { managementRuntimeSetting(it.id) }
    val modified = settings.count { configurationValue(values, it.id) != null }
    val defaultRole = state.roles.firstOrNull { it.id == "default" }
    val defaultModel = state.models.firstOrNull { it.key == defaultRole?.model }
    ManagementPage("Agent 设置", onBack, actions = { AgentIconButton("refresh", "重新读取配置", { actions.launch { repository.reload(online = true) } }) }) {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp).background(c.surfaceContainer, RoundedCornerShape(28.dp)).padding(horizontal = 18.dp, vertical = 16.dp)) {
            ContextRow("dns", "容器 ${state.context.containerName}")
            ContextRow("account_circle", "配置档 ${state.context.profile}")
            ContextRow(if (state.context.projectPath == null) "folder_off" else "folder", state.context.projectPath ?: "未打开项目")
            Spacer(Modifier.height(12.dp))
            ManagementSegments(listOf("global" to "保存到全局", "project" to "保存到项目"), if (configurationScope == ConfigurationScope.PROJECT) "project" else "global", { onScopeChange(if (it == "project") ConfigurationScope.PROJECT else ConfigurationScope.GLOBAL) }, { it != "project" || state.context.projectPath != null })
            ManagementHint("写入${if (configurationScope == ConfigurationScope.PROJECT) "项目配置" else "全局配置"}，凭据始终保存在独立存储", "save")
        }
        state.error?.let { ManagementBanner(it, "error", error = true) }
        state.modelsError?.let { ManagementBanner(it, "sync_problem", error = true) }
        actions.error?.let { ManagementBanner(it, "error", error = true) }
        SettingsSearch(query, { query = it }, "搜索名称、配置键或当前值", Modifier.padding(top = 8.dp, bottom = 12.dp))
        ManagementChip("仅看已修改", onlyModified, { onlyModified = !onlyModified })
        if (query.isNotBlank() || onlyModified) {
            val matchedSettings = settings.filter { item -> (!onlyModified || configurationValue(values, item.id) != null) &&
                (query.isBlank() || item.title.contains(query, true) || item.id.contains(query, true) || configurationDisplay(item.value).contains(query, true)) }
            if (matchedSettings.isNotEmpty()) ManagementGroup("运行设置") {
                matchedSettings.forEachIndexed { index, item -> ManagementRow(item.title, configurationDisplay(item.value), item.icon, { selectedSetting = item }, index, matchedSettings.size) }
            }
            if (query.isNotBlank()) {
                val agents = state.agents.filter { it.name.contains(query, true) || it.description.contains(query, true) }
                if (agents.isNotEmpty()) ManagementGroup("Agent") { agents.forEachIndexed { index, agent -> ManagementRow(agent.name, agent.description, "smart_toy", { navigate("agents") }, index, agents.size) } }
                val roles = state.roles.filter { it.id.contains(query, true) || it.name.contains(query, true) || it.model?.contains(query, true) == true }
                if (roles.isNotEmpty()) ManagementGroup("角色") { roles.forEachIndexed { index, role -> ManagementRow(role.name, role.model ?: "未配置", "label", { navigate("roles") }, index, roles.size) } }
                val matches = providers.filter { it.id.contains(query, true) || it.name.contains(query, true) || it.baseUrl.contains(query, true) }
                if (matches.isNotEmpty()) ManagementGroup("模型服务") { matches.forEachIndexed { index, provider -> ManagementRow(provider.name, provider.baseUrl, "hub", { navigate("providers") }, index, matches.size) } }
            }
            if (matchedSettings.isEmpty() && (query.isBlank() || state.agents.none { it.name.contains(query, true) || it.description.contains(query, true) } && state.roles.none { it.id.contains(query, true) || it.name.contains(query, true) } && providers.none { it.name.contains(query, true) })) SettingsEmpty("没有找到相关设置", "search_off")
        } else {
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryTile("默认模型", defaultModel?.name ?: defaultRole?.model ?: "未配置", "star", Modifier.weight(1f)) { navigate("roles") }
                SummaryTile("当前会话", sessionState?.modelId?.ifEmpty { "未选择模型" } ?: "未选择会话", "bolt", Modifier.weight(1f)) { sessionSheet = true }
            }
            val disabled = configurationStrings(configurationValue(state.effectiveValues, "task.disabledAgents"))
            ManagementGroup("Agent") {
                ManagementRow("Agent 管理", "${state.agents.size} 个定义，${state.agents.count { it.name in disabled }} 个已停用", "smart_toy", { navigate("agents") }, 0, 2)
                val running = state.advisors.count { it.runtimeStatus == "running" }
                ManagementRow("顾问", if (state.advisors.any { it.runtimeStatus != null }) "$running 个运行中" else "${state.advisors.size} 个顾问配置", "support_agent", { navigate("advisors") }, 1, 2)
            }
            ManagementGroup("模型") {
                val destinations = listOf(
                    Triple("默认模型与角色", "${state.roles.size} 个角色，保存到${if (configurationValue(state.effectiveValues, "modelRoleStorage") == "project") "项目" else "全局"}", "star"),
                    Triple("模型服务", if (state.modelsLoading) "正在读取模型目录" else "${state.providers.count { it.enabled }} 个启用，${state.models.size} 个模型", "hub"),
                    Triple("账号与认证", if (state.modelsLoading) "正在读取认证记录" else "${state.accounts.size} 个认证记录", "key"),
                    Triple("模型预设", "${configurationMap(configurationValue(state.effectiveValues, "modelPresets")).size} 个预设", "bookmarks"),
                    Triple("允许范围与快速切换", if (configurationStrings(configurationValue(state.effectiveValues, "enabledModels")).isEmpty()) "不额外限制" else "已设置允许规则", "filter_alt"),
                    Triple("故障转移", "${configurationMap(configurationValue(state.effectiveValues, "retry.fallbackChains")).size} 条规则", "alt_route"))
                val pages = listOf("roles", "providers", "auth", "presets", "allow", "fallback")
                destinations.forEachIndexed { index, (title, subtitle, icon) -> ManagementRow(title, subtitle, icon, { navigate(pages[index]) }, index, destinations.size) }
            }
            ManagementGroup("运行") { ManagementRow("运行设置", "$modified 项在当前层已修改", "settings", { navigate("runtime") }) }
            ManagementGroup("高级") {
                ManagementRow("配置文件与来源", "原始编辑与校验", "data_object", { navigate("advanced") }, 0, 2)
                ManagementRow("重置运行设置", "仅清除${configurationScope.label}层的运行设置", "restart_alt", { reset = true }, 1, 2, error = true, trailing = {})
            }
        }
    }
    selectedSetting?.let { item -> ManagementSettingEditor(item, state, configurationScope, { selectedSetting = null }) { value -> actions.launch("已保存") { repository.updateSettings(configurationScope, mapOf(item.id to value)); selectedSetting = null } } }
    if (sessionSheet) ManagementCurrentSession(state, repository, configurationScope, { sessionSheet = false })
    if (reset) AlertDialog(onDismissRequest = { reset = false }, icon = { Symbol("restart_alt", tint = c.error) }, title = { Text("重置运行设置") }, text = { Text("将清除${configurationScope.label}层的运行设置，恢复继承。账号与凭据、模型服务、自定义模型、角色绑定与 Agent 定义保留。") },
        confirmButton = { TextButton({ actions.launch("已重置运行设置") { repository.resetRuntimeSettings(configurationScope); reset = false } }) { Text("重置", color = c.error) } }, dismissButton = { TextButton({ reset = false }) { Text("取消") } })
}
@Composable
private fun ContextRow(icon: String, text: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Symbol(icon, tint = LocalAgentColors.current.onSurfaceVariant)
        Text(text, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
@Composable
private fun SummaryTile(label: String, value: String, icon: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick, modifier = modifier, color = LocalAgentColors.current.surfaceContainerLow, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.heightIn(min = 128.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Symbol(icon, tint = LocalAgentColors.current.primary)
            Text(label, fontSize = 16.sp, color = LocalAgentColors.current.onSurfaceVariant)
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
@Composable
private fun ManagementCurrentSession(state: AgentManagementState, repository: AgentManagementRepository, scope: ConfigurationScope, onDismiss: () -> Unit) {
    val session = LocalManagementSession.current
    val current = session?.state?.collectAsState()?.value
    val actions = LocalManagementActions.current
    var pickModel by remember { mutableStateOf(false) }
    var pickThinking by remember { mutableStateOf(false) }
    AgentSheet("当前会话模型", onDismiss) {
        if (current == null || session == null) ManagementBanner("未选择会话。从会话进入 Agent 设置后，可在这里临时切换模型。", "forum")
        else {
            ManagementBanner("只影响当前会话，不会改动保存的默认模型", "bolt")
            ManagementRow(current.modelId.ifEmpty { "未选择模型" }, current.providerId, "", { pickModel = true }, lead = { ModelIcon(current.modelId, current.modelId, Modifier.size(44.dp)) })
            Spacer(Modifier.height(3.dp))
            Box {
                ManagementRow("思考强度", current.thinkingLevel.label, "lightbulb", { pickThinking = true })
                ManagementThinkingPicker(current.thinkingLevel.name.lowercase().replace("none", "off"), { pickThinking = false }, { effort -> ThinkingLevel.entries.firstOrNull { it.name.lowercase() == effort || it == ThinkingLevel.NONE && effort == "off" }?.let { session.selectThinkingLevel(it) } }, inherit = false, efforts = configurationStrings(configurationMap(state.models.firstOrNull { it.provider == current.providerId && it.id == current.modelId }?.metadata?.get("thinking"))["efforts"]).takeIf { it.isNotEmpty() }, expanded = pickThinking)
            }
            ManagementGroup("对照") { ManagementLayers(listOf(ManagementLayer("当前会话", "${current.modelId}，${current.thinkingLevel.label}", "bolt"), ManagementLayer("保存的默认", state.roles.firstOrNull { it.id == "default" }?.model ?: "未配置", "star"), ManagementLayer("默认思考", managementThinkingLabel(configurationValue(state.effectiveValues, "defaultThinkingLevel") as? String), "lightbulb"))) }
            OutlinedButton({ actions.launch("已保存为默认角色") {
                val targetScope = if (configurationValue(state.effectiveValues, "modelRoleStorage") == "project") ConfigurationScope.PROJECT else ConfigurationScope.GLOBAL
                require(targetScope != ConfigurationScope.PROJECT || state.context.projectPath != null) { "角色保存到项目，但未打开项目" }
                repository.updateSettings(targetScope, mapOf("modelRoles.default" to "${current.providerId}/${current.modelId}:${current.thinkingLevel.name.lowercase().replace("none", "off")}")); onDismiss()
            } }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(48.dp)) { Symbol("star"); Spacer(Modifier.width(8.dp)); Text("保存为默认") }
        }
    }
    if (pickModel) ManagementModelPicker(state, kind = "chat", allowRole = false, allowRaw = false, onDismiss = { pickModel = false }) { selector -> state.models.firstOrNull { it.key == selector }?.let { session?.selectModel(it.provider, it.id) } }
}

@Composable
private fun ManagementAuthenticationScreen(repository: AgentManagementRepository, state: AgentManagementState, openProvider: (String) -> Unit, onBack: () -> Unit) {
    val actions = LocalManagementActions.current
    var logout by remember { mutableStateOf<ManagedAccount?>(null) }
    var selectedPolicy by remember { mutableStateOf<SettingItem?>(null) }
    val authSettings = state.categories.flatMap { it.groups }.flatMap { it.items }.filter { it.id.startsWith("auth.") }
    ManagementPage("账号与认证", onBack, actions = { ManagementScopeChip(ConfigurationScope.GLOBAL, {}, state.context.projectPath != null, globalOnly = true) }) {
        if (state.modelsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.modelsError?.let { ManagementBanner(it, "sync_problem", error = true) }
        val providerIds = (state.authProviders.map { it.id } + state.accounts.map { it.provider }).distinct()
        providerIds.forEach { provider ->
            val descriptor = state.authProviders.firstOrNull { it.id == provider }
            val accounts = state.accounts.filter { it.provider == provider }
            val oauth = state.providers.firstOrNull { it.id == provider }?.oauth == true
            ManagementGroup(descriptor?.name ?: provider) {
                accounts.forEachIndexed { index, account ->
                    var menu by remember { mutableStateOf(false) }
                    val expired = account.status.contains("expired", true)
                    ManagementRow(account.label, if (expired) "登录已过期" else account.status, if (account.type == "oauth") "account_circle" else "key", index = index, count = accounts.size + if (oauth) 1 else 0,
                        detail = listOfNotNull(if (account.pinned) "当前会话" else null, account.quota).joinToString("，"), trailing = {
                        if (expired && account.type == "oauth" && oauth) FilledTonalButton({ actions.launch { repository.login(provider) } }) { Text("重新登录") }
                        else Box {
                            AgentIconButton("more_vert", "账号操作", { menu = true })
                            ManagementMenu(menu, { menu = false }) {
                                if (state.context.sessionId != null && account.type == "oauth") ManagementMenuItem(text = { Text(if (account.pinned) "解除固定" else "固定到当前会话") }, leadingIcon = { Symbol("push_pin") }, onClick = { menu = false; actions.launch { repository.pinAccount(provider, if (account.pinned) null else account.id) } })
                                ManagementMenuItem(text = { Text(if (account.type == "oauth") "退出登录" else "清除认证", color = LocalAgentColors.current.error) }, leadingIcon = { Symbol("logout", tint = LocalAgentColors.current.error) }, onClick = { menu = false; logout = account })
                            }
                        }
                    })
                }
                if (oauth) ManagementRow("添加账号", icon = "add", onClick = { actions.launch { repository.login(provider) } }, index = accounts.size, count = accounts.size + 1, trailing = {})
                else {
                    if (accounts.isEmpty()) ManagementRow("API Key", "未配置", "key_off", { openProvider(provider) }, 0, 2)
                    accounts.firstOrNull()?.quota?.let { quota -> ManagementRow("额度", quota, "data_usage", index = 1, count = 2) }
                    TextButton({ openProvider(provider) }) { Text("管理 API Key") }
                }
            }
        }
        if (providerIds.isEmpty() && !state.modelsLoading) SettingsEmpty("还没有认证记录", "key_off")
        ManagementGroup("账号策略") {
            authSettings.filterNot { it.id.contains("proxy", true) }.forEachIndexed { index, item -> ManagementRow(item.title, configurationDisplay(item.value), "rule", { selectedPolicy = item }, index, authSettings.count { !it.id.contains("proxy", true) }) }
        }
        ManagementGroup("认证代理") {
            authSettings.filter { it.id.contains("proxy", true) }.forEach { item ->
                ManagementRow(item.title, "仅全局，项目不能重定向凭据", "vpn_lock", { selectedPolicy = item }, trailing = if (item.type == SettingType.BOOLEAN) ({ Switch(item.value == true, { checked -> actions.launch { repository.updateSettings(ConfigurationScope.GLOBAL, mapOf(item.id to checked)) } }) }) else null)
            }
        }
    }
    ManagementAuthenticationChallenge(repository, state)
    logout?.let { account -> AlertDialog(onDismissRequest = { logout = null }, title = { Text(if (account.type == "oauth") "退出账号" else "清除认证") }, text = { Text("将移除 ${account.label} 的认证记录，不删除模型服务和角色绑定。") }, confirmButton = { TextButton({ actions.launch("已清除认证") { repository.logout(account.provider, account.id); logout = null } }) { Text("确认退出") } }, dismissButton = { TextButton({ logout = null }) { Text("取消") } }) }
    selectedPolicy?.let { item -> ManagementSettingEditor(item, state, ConfigurationScope.GLOBAL, { selectedPolicy = null }) { value -> actions.launch("已保存") { repository.updateSettings(ConfigurationScope.GLOBAL, mapOf(item.id to value)); selectedPolicy = null } } }
}

@Composable
internal fun ManagementAuthenticationChallenge(repository: AgentManagementRepository, state: AgentManagementState) {
    val actions = LocalManagementActions.current
    val uriHandler = LocalUriHandler.current
    state.authentication?.let { challenge ->
        var answer by rememberSaveable(challenge.provider, challenge.prompt) { mutableStateOf("") }
        AgentSheet("登录 ${challenge.provider}", { repository.cancelLogin() }) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                challenge.instructions?.let { Text(it, fontSize = 17.sp) }
                challenge.url?.let { url -> Button({ uriHandler.openUri(url) }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 48.dp)) { Symbol("open_in_new"); Spacer(Modifier.width(8.dp)); Text("在浏览器完成登录") } }
                challenge.prompt?.let { prompt ->
                    SettingsLabel(prompt)
                    SettingsField(answer, { answer = it }, mono = true, secret = challenge.secret)
                    Button({ val submitted = answer; actions.launch { repository.answerLogin(submitted); if (answer == submitted) answer = "" } }, enabled = answer.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("继续") }
                }
                if (challenge.prompt == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 20.dp))
                TextButton({ repository.cancelLogin() }) { Text("取消登录") }
            }
        }
    }
}

@Composable
private fun ManagementConfigurationFiles(repository: AgentManagementRepository, state: AgentManagementState, onBack: () -> Unit, registerBack: ((() -> Boolean)?) -> Unit) {
    val actions = LocalManagementActions.current
    val clipboard = LocalClipboardManager.current
    var document by remember { mutableStateOf<ConfigurationDocument?>(null) }
    var editing by remember { mutableStateOf(false) }
    var export by remember { mutableStateOf<String?>(null) }
    ManagementPage("配置文件与来源", onBack) {
        state.error?.let { ManagementBanner(it, "error", error = true) }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            state.files.forEachIndexed { index, file -> ManagementRow(file.title, file.path, when (file.id) { "project" -> "folder"; "models" -> "hub"; "watchdog" -> "support_agent"; else -> "description" }, { actions.launch { document = repository.readDocument(file.id); editing = true } }, index, state.files.size) }
        }
        ManagementGroup("导出") { ManagementRow("导出配置预览", "不包含 API Key、OAuth 数据和请求头值", "ios_share", { actions.launch { export = repository.exportRedacted() } }) }
        ManagementGroup("生效时机") { ManagementLayers(listOf(ManagementLayer("审批、循环保护", "立即生效", "bolt"), ManagementLayer("模型与提示", "下一轮生效", "forum"), ManagementLayer("子任务设置", "后续子任务生效", "account_tree"), ManagementLayer("启动与更新", "重新加载后生效", "refresh"))) }
    }
    val current = document
    if (editing && current != null) ManagementConfigurationDocumentScreen(repository, current, { editing = false; document = null }, registerBack)
    export?.let { text -> AgentSheet("配置预览", { export = null }) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            HighlightedCode(text, "yaml")
            Button({ clipboard.setText(AnnotatedString(text)); export = null }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Symbol("content_copy"); Spacer(Modifier.width(8.dp)); Text("复制脱敏配置") }
        }
    } }
}

@Composable
internal fun ManagementConfigurationDocumentScreen(repository: AgentManagementRepository, initial: ConfigurationDocument, onBack: () -> Unit, registerBack: ((() -> Boolean)?) -> Unit) {
    val actions = LocalManagementActions.current
    var source by remember(initial.id) { mutableStateOf(initial) }
    var text by remember(initial.id) { mutableStateOf(initial.content) }
    var edit by rememberSaveable(initial.id) { mutableStateOf(false) }
    var external by remember { mutableStateOf<ConfigurationDocument?>(null) }
    var discard by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    val dirty = text != source.content
    fun back(): Boolean { if (dirty) discard = true else onBack(); return true }
    fun save(after: () -> Unit = {}) { actions.launch("已保存，配置已重新读取") { repository.saveDocument(source, text); source = repository.readDocument(source.id); text = source.content; saved = true; after() } }
    AgentManagementBack(registerBack, ::back)
    ManagementPage(initial.path.substringAfterLast('/'), { back() }, actions = { AgentIconButton(if (edit) "visibility" else "edit", "切换编辑与预览", { edit = !edit }, enabled = source.writable) },
        footer = { ManagementSaveBar(dirty, actions.busy, { save() }, { text = source.content; external = null }, actions.error, canSave = source.writable && external == null) }) {
        ManagementHint(source.path, "description")
        external?.let { latest ->
            ManagementBanner("文件已在外部修改", "sync_problem", warning = true)
            Row {
                TextButton({ source = latest; text = latest.content; external = null }) { Text("使用外部版本") }
                TextButton({ source = latest; external = null }) { Text("保留我的修改") }
            }
        }
        actions.error?.let { ManagementBanner(it, "error", error = true) }
        if (saved && !dirty) ManagementBanner("已保存，配置已重新读取", "check_circle")
        if (edit) ManagementField("", text, { text = it; saved = false }, mono = true, multiline = true, readOnly = !source.writable)
        else HighlightedCode(text, if (source.path.endsWith(".md")) "markdown" else "yaml")
        OutlinedButton({ actions.launch {
            val latest = repository.readDocument(source.id)
            if (dirty && latest.version != source.version) external = latest else { source = latest; if (!dirty) text = latest.content }
        } }, modifier = Modifier.padding(top = 16.dp)) { Symbol("refresh"); Spacer(Modifier.width(8.dp)); Text("重新读取") }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, icon = { Symbol("edit_note") }, title = { Text("有未保存的修改") }, text = { Text("离开后这些修改会丢失。") }, confirmButton = { Button({ save { discard = false; onBack() } }) { Text("保存") } }, dismissButton = { Row { TextButton({ discard = false; onBack() }) { Text("放弃修改", color = LocalAgentColors.current.error) }; TextButton({ discard = false }) { Text("继续编辑") } } })
}
