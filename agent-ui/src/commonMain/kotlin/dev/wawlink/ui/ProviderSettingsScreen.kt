package dev.wawlink.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wawlink.protocol.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.random.Random

internal data class ProviderListEntry(
    val id: String,
    val name: String,
    val builtin: Boolean,
    val enabled: Boolean,
    val apiLabel: String?,
    val modelCount: Int,
)

internal fun providerListEntries(
    configured: List<ProviderConfig>,
    native: List<ManagedProvider>,
    modelCounts: Map<String, Int>,
): List<ProviderListEntry> {
    val configuredById = configured.associateBy { it.id }
    val nativeIds = HashSet<String>(native.size)
    val entries = ArrayList<ProviderListEntry>(configured.size + native.size)
    for (provider in native) {
        nativeIds.add(provider.id)
        val local = configuredById[provider.id]
        entries.add(ProviderListEntry(
            id = provider.id,
            name = if (provider.builtin) provider.name else local?.name ?: provider.name,
            builtin = provider.builtin,
            enabled = provider.enabled,
            apiLabel = if (provider.builtin) null else local?.let { it.nativeApi ?: it.apiType.label } ?: provider.api,
            modelCount = modelCounts[provider.id] ?: local?.models?.size ?: 0,
        ))
    }
    for (provider in configured) {
        if (provider.id !in nativeIds) entries.add(ProviderListEntry(
            provider.id, provider.name, false, provider.enabled,
            provider.nativeApi ?: provider.apiType.label, provider.models.size,
        ))
    }
    entries.sortWith(compareBy<ProviderListEntry> { it.builtin }.thenByDescending { it.enabled })
    return entries
}

internal fun providerEnabledSettings(document: ConfigurationDocument, provider: String, enabled: Boolean): Map<String, Any?> {
    val rules = (document.values["disabledProviders"] as? List<*>).orEmpty()
    val updated = if (enabled) rules.filterNot { it == provider }
        else if (provider in rules) rules else rules + provider
    return mapOf("disabledProviders" to updated)
}

@Composable
fun ProviderSettingsScreen(repository: ProviderConfigRepository, onBack: () -> Unit, onAdvanced: ((String) -> Unit)? = null, registerBack: ((() -> Boolean)?) -> Unit = {}) {
    val persisted by repository.providers.collectAsState()
    val managementRepository = LocalManagementRepository.current
    val managementState = managementRepository?.state?.collectAsState()?.value
    val synchronizationError by repository.error.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val notify: (String) -> Unit = remember(scope) { { text -> scope.launch { snackbar.showSnackbar(text) }; Unit } }
    val queue = remember(repository) { SettingsWriteQueue(scope, notify) }
    val drafts = remember(repository) { mutableStateMapOf<String, ProviderConfig>() }
    val removed = remember(repository) { mutableStateMapOf<String, Boolean>() }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    val navigationState = rememberSaveableStateHolder()
    // Optimistic drafts must not permanently mask changes made by the running agent or another editor.
    LaunchedEffect(persisted, queue.pending) {
        if (queue.pending == 0) {
            drafts.keys.toList().filterNot { it in queue.failures }.forEach(drafts::remove)
            removed.keys.toList().filter { it !in queue.failures && repository.providers.value.none { provider -> provider.id == it } }.forEach(removed::remove)
        }
    }
    fun current(id: String): ProviderConfig? = drafts[id] ?: repository.providers.value.firstOrNull { it.id == id }
    fun persist(id: String) {
        queue.submit(id, if (removed[id] == true) "删除服务失败" else "保存服务失败") {
            if (removed[id] == true) repository.deleteProvider(id)
            else current(id)?.let { repository.saveProvider(it) }
        }
    }
    fun update(provider: ProviderConfig) { drafts[provider.id] = provider; persist(provider.id) }
    fun back() { queue.afterSaved { if (selectedId != null) selectedId = null else onBack() } }
    val latestBack by rememberUpdatedState(::back)
    val currentRegistration by rememberUpdatedState(registerBack)
    DisposableEffect(repository) { currentRegistration { latestBack(); true }; onDispose { currentRegistration(null) } }
    val all = (persisted.map { drafts[it.id] ?: it } + drafts.values.filter { draft -> persisted.none { it.id == draft.id } })
        .filter { removed[it.id] != true }
    val selected = selectedId?.let { id -> all.firstOrNull { it.id == id } }
    val colors = LocalAgentColors.current

    Box(Modifier.fillMaxSize().background(colors.surface), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = AgentPageMaxWidth).fillMaxSize()) {
            AnimatedContent(selected?.id, transitionSpec = {
                (slideInHorizontally(tween(500, easing = EmphDecelEasing)) { if (targetState != null) it else -it / 4 } + fadeIn(tween(300))) togetherWith
                    (slideOutHorizontally(tween(350, easing = EmphAccelEasing)) { if (targetState != null) -it / 4 else it } + fadeOut(tween(250)))
            }, modifier = Modifier.weight(1f)) { target ->
                navigationState.SaveableStateProvider(target?.let { "provider:$it" } ?: "provider-list") {
                val provider = target?.let { current(it) }
                if (provider == null) {
                    Box(Modifier.fillMaxSize()) {
                        Column {
                            AgentTopBar("模型服务", ::back) {
                                if (onAdvanced != null) ManagementScopeChip(ConfigurationScope.GLOBAL, {}, managementState?.context?.projectPath != null, globalOnly = true)
                            }
                            SettingsFailure(queue) { queue.failures.keys.toList().forEach(::persist) }
                            SettingsSyncError(synchronizationError)
                            if (managementState?.modelsLoading == true) LinearProgressIndicator(Modifier.fillMaxWidth())
                            managementState?.modelsError?.let { message ->
                                ManagementBanner(message, "sync_problem", error = true)
                                TextButton({ scope.launch { try { managementRepository?.reload(online = true) } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { notify(failure.message ?: "读取失败") } } }) { Text("重新读取") }
                            }
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp)) {
                                SettingsSearch(search, { search = it }, "搜索服务")
                                Spacer(Modifier.height(16.dp))
                                val nativeModels = managementState?.models.orEmpty()
                                val modelCounts = remember(nativeModels) { nativeModels.groupingBy { it.provider }.eachCount() }
                                val nativeProviders = if (onAdvanced != null) managementState?.providers.orEmpty() else emptyList()
                                val entries = remember(all, nativeProviders, modelCounts) { providerListEntries(all, nativeProviders, modelCounts) }
                                val query = search.trim()
                                val visible = entries.filter { query.isEmpty() || it.name.contains(query, true) || it.id.contains(query, true) }
                                val customCount = visible.count { !it.builtin }
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    visible.forEachIndexed { index, item ->
                                        if (index == customCount && customCount > 0) HorizontalDivider(Modifier.padding(vertical = 16.dp), color = colors.outline)
                                        val position = if (item.builtin) index - customCount else index
                                        val groupSize = if (item.builtin) visible.size - customCount else customCount
                                        Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).background(colors.surfaceContainerLow, segmentedSettingsShape(position, groupSize)).clickable { if (onAdvanced != null) queue.afterSaved { onAdvanced(item.id) } else selectedId = item.id }.padding(start = 16.dp, end = if (item.builtin) 16.dp else 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                            ProviderInitialIcon(item.name.ifBlank { item.id }, Modifier.size(44.dp).alpha(if (item.enabled) 1f else .45f))
                                            Column(Modifier.weight(1f).alpha(if (item.enabled) 1f else .5f)) {
                                                Text(item.name.ifBlank { "未命名" }, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(if (item.apiLabel == null) "${item.modelCount} 个模型" else "${item.apiLabel} · ${item.modelCount} 个模型", fontSize = 16.sp, color = colors.onSurfaceVariant)
                                            }
                                            Switch(item.enabled, { checked ->
                                                if (managementRepository != null) scope.launch {
                                                    try {
                                                        val document = managementRepository.readDocument("global")
                                                        managementRepository.updateSettings(ConfigurationScope.GLOBAL, providerEnabledSettings(document, item.id, checked))
                                                    } catch (cancelled: CancellationException) { throw cancelled }
                                                    catch (failure: Exception) { notify(failure.message ?: "保存失败") }
                                                } else current(item.id)?.let { update(it.copy(enabled = checked)) }
                                            })
                                        }
                                    }
                                    if (visible.isEmpty() && managementState?.modelsLoading != true) SettingsEmpty(if (entries.isEmpty()) "还没有模型服务" else "没有匹配的服务", "deployed_code")
                                }
                            }
                        }
                        ExtendedFloatingActionButton(onClick = {
                            if (onAdvanced != null) { queue.afterSaved { onAdvanced("") }; return@ExtendedFloatingActionButton }
                            val id = generateSequence { "provider-" + Random.nextLong().toULong().toString(16) }.first { current(it) == null }
                            val new = ProviderConfig(id = id, name = "新服务", baseUrl = "")
                            update(new)
                            selectedId = id
                        }, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 20.dp), shape = RoundedCornerShape(16.dp), containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer, icon = { Symbol("add") }, text = { Text("添加服务", fontSize = 17.sp) })
                    }
                } else {
                    ProviderDetailScreen(provider, queue, ::back, { transform -> current(provider.id)?.let { update(transform(it)) } }, {
                        removed[provider.id] = true
                        persist(provider.id)
                        queue.afterSaved { selectedId = null; notify("已删除服务") }
                    }, { queue.failures.keys.toList().forEach(::persist) }, repository, notify, onAdvanced?.let { open -> { queue.afterSaved { open(provider.id) } } })
                }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).widthIn(max = AgentPageMaxWidth).padding(16.dp))
    }
}



@Composable
private fun ProviderDetailScreen(
    provider: ProviderConfig,
    queue: SettingsWriteQueue,
    onBack: () -> Unit,
    modifyLatest: ((ProviderConfig) -> ProviderConfig) -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    repository: ProviderConfigRepository,
    notify: (String) -> Unit,
    onAdvanced: (() -> Unit)?,
) {
    val colors = LocalAgentColors.current
    val synchronizationError by repository.error.collectAsState()
    val scope = rememberCoroutineScope()
    var showKey by remember(provider.id) { mutableStateOf(false) }
    var testing by remember(provider.id) { mutableStateOf(false) }
    var syncing by remember(provider.id) { mutableStateOf(false) }
    var testResult by remember(provider.id) { mutableStateOf<String?>(null) }
    var testFailed by remember(provider.id) { mutableStateOf(false) }
    var modelSheet by remember(provider.id) { mutableStateOf(false) }
    var editModel by remember(provider.id) { mutableStateOf<ModelConfig?>(null) }
    var confirmDelete by remember(provider.id) { mutableStateOf(false) }
    val latest by rememberUpdatedState(provider)
    LaunchedEffect(provider.apiType, provider.baseUrl, provider.apiKey) { testResult = null }
    Column(Modifier.fillMaxSize()) {
        AgentTopBar(provider.name.ifBlank { "未命名" }, onBack) {
            if (onAdvanced != null) AgentIconButton("tune", "原生协议与完整模型配置", onAdvanced)
            AgentIconButton("delete", "删除服务", { confirmDelete = true })
            Switch(provider.enabled, { enabled -> modifyLatest { it.copy(enabled = enabled) } })
            Spacer(Modifier.width(12.dp))
        }
        SettingsFailure(queue, onRetry)
        SettingsSyncError(synchronizationError)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 120.dp)) {
            SettingsLabel("名称")
            SettingsField(provider.name, { name -> modifyLatest { it.copy(name = name) } })
            SettingsLabel("接口类型")
            Row(Modifier.fillMaxWidth().height(52.dp).border(1.dp, colors.outline, RoundedCornerShape(26.dp))) {
                ProviderApiType.entries.forEachIndexed { index, type ->
                    val selected = provider.nativeApi == null && type == provider.apiType
                    val segmentShape = when (index) { 0 -> RoundedCornerShape(topStart = 26.dp, bottomStart = 26.dp); 2 -> RoundedCornerShape(topEnd = 26.dp, bottomEnd = 26.dp); else -> RoundedCornerShape(0.dp) }
                    val bg by animateColorAsState(if (selected) colors.secondaryContainer else colors.surface, tween(300, easing = EmphEasing))
                    Row(Modifier.weight(1f).fillMaxHeight().background(bg, segmentShape).clickable {
                        modifyLatest { it.copy(apiType = type, nativeApi = null) }
                    }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        AnimatedVisibility(selected, enter = expandHorizontally(tween(350, easing = EmphEasing)) + fadeIn(), exit = shrinkHorizontally() + fadeOut()) { Symbol("check", Modifier.padding(end = 6.dp), size = 20.dp) }
                        Text(type.label, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    }
                    if (index < ProviderApiType.entries.lastIndex) Box(Modifier.width(1.dp).fillMaxHeight().background(colors.outline))
                }
            }
            Row(Modifier.padding(start = 4.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Symbol("info", size = 20.dp, tint = colors.onSurfaceVariant)
                Text(provider.nativeApi ?: when (provider.apiType) { ProviderApiType.CHAT -> "经典 OpenAI 接口"; ProviderApiType.RESPONSE -> "新版 OpenAI 接口"; ProviderApiType.MESSAGE -> "Claude 接口" }, modifier = Modifier.weight(1f), color = colors.onSurfaceVariant, fontSize = 16.sp)
                if (onAdvanced != null) TextButton(onAdvanced) { Text("更多协议") }
            }
            SettingsLabel("接口地址")
            SettingsField(provider.baseUrl, { base -> modifyLatest { it.copy(baseUrl = base) } }, placeholder = "https://", mono = true, keyboardType = KeyboardType.Uri)
            SettingsLabel("请求路径")
            SettingsField(if (provider.nativeApi != null) "由原生协议确定" else when (provider.apiType) { ProviderApiType.CHAT -> "/chat/completions"; ProviderApiType.RESPONSE -> "/responses"; ProviderApiType.MESSAGE -> "/messages" }, {}, readOnly = true, mono = true)
            SettingsLabel("API Key") {
                TextButton(enabled = !testing, onClick = {
                    val snapshot = latest
                    testing = true
                    testResult = null
                    scope.launch {
                        try {
                            val result = repository.testConnection(snapshot)
                            if (snapshot.baseUrl == latest.baseUrl && snapshot.apiType == latest.apiType && snapshot.apiKey == latest.apiKey) {
                                result.fold({ info -> testFailed = false; testResult = "${info.message} · ${info.latencyMs} ms · HTTP ${info.statusCode}" }, { error -> testFailed = true; testResult = "连接失败：${error.message ?: "未知错误"}" })
                                notify(testResult ?: "测试完成")
                            } else notify("配置已变更，请重新测试")
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { testFailed = true; testResult = "连接失败：${error.message ?: "未知错误"}"; notify(testResult!!) }
                        finally { testing = false }
                    }
                }) {
                    if (testing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Symbol(if (testResult == null) "network_check" else if (testFailed) "error" else "check_circle", size = 20.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(if (testing) "测试中" else if (testResult == null) "测试" else if (testFailed) "连接失败" else "连接正常", fontSize = 16.sp)
                }
            }
            SettingsField(provider.apiKey, { key -> modifyLatest { it.copy(apiKey = key) } }, placeholder = "sk-", mono = true, secret = !showKey, keyboardType = KeyboardType.Password, trailing = { AgentIconButton(if (showKey) "visibility_off" else "visibility", if (showKey) "隐藏 API Key" else "显示 API Key", { showKey = !showKey }) })
            AnimatedVisibility(testResult != null, enter = expandVertically(tween(400, easing = EmphEasing)) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Text(testResult.orEmpty(), Modifier.padding(8.dp), color = if (testFailed) colors.error else colors.tertiary, fontSize = 15.sp)
            }
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 28.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("模型", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(10.dp))
                Surface(shape = CircleShape, color = colors.surfaceContainerHigh) { Text("${provider.models.size}", Modifier.padding(horizontal = 8.dp, vertical = 3.dp), fontSize = 15.sp) }
                Spacer(Modifier.weight(1f))
                if (syncing) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
                else TextButton(onClick = {
                    syncing = true
                    val snapshot = latest
                    scope.launch {
                        try {
                            repository.fetchRemoteModels(snapshot).fold({ models ->
                                if (snapshot.apiType != latest.apiType || snapshot.baseUrl != latest.baseUrl || snapshot.apiKey != latest.apiKey) notify("配置已变更，请重新同步")
                                else {
                                    var added = 0
                                    modifyLatest { current ->
                                        val known = current.models.map { it.id }.toSet()
                                        val new = models.filter { it.id.isNotBlank() && it.id !in known }.distinctBy { it.id }
                                        added = new.size
                                        current.copy(models = current.models + new)
                                    }
                                    notify(if (added > 0) "获取到 $added 个新模型" else "没有新模型")
                                }
                            }, { error -> notify("获取模型失败：${error.message ?: "未知错误"}") })
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { notify("获取模型失败：${error.message ?: "未知错误"}") }
                        finally { syncing = false }
                    }
                }) { Symbol("sync", size = 20.dp); Spacer(Modifier.width(6.dp)); Text("获取模型") }
                AgentIconButton("add", "添加模型", { editModel = null; modelSheet = true })
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 480.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                itemsIndexed(provider.models, key = { _, model -> model.id }) { index, model ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).background(colors.surfaceContainerLow, segmentedSettingsShape(index, provider.models.size)).clickable { editModel = model; modelSheet = true }.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        ModelIcon(model.id, model.name)
                        Column(Modifier.weight(1f)) {
                            Text(model.name.ifBlank { model.id }, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (model.name != model.id) Text(model.id, fontFamily = LocalAgentMono.current, fontSize = 14.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Surface(shape = RoundedCornerShape(12.dp), color = colors.surface, border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant)) {
                            Row(Modifier.heightIn(min = 36.dp).padding(start = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                val customThinking = model.hasCustomThinking()
                                if (customThinking) Symbol("tune", size = 20.dp, tint = colors.primary) else ThinkingBulb(model.thinkingLevel, Modifier.size(20.dp), if (model.thinkingLevel == ThinkingLevel.NONE) colors.onSurfaceVariant else colors.primary)
                                Text(if (customThinking) model.thinkingBudget?.toString() ?: model.reasoningEffort.orEmpty() else model.thinkingLevel.label, fontSize = 16.sp, maxLines = 1, modifier = Modifier.widthIn(max = 96.dp), overflow = TextOverflow.Ellipsis, color = if (model.thinkingLevel == ThinkingLevel.NONE && !customThinking) colors.onSurfaceVariant else colors.primary)
                            }
                        }
                        Symbol("chevron_right", tint = colors.onSurfaceVariant)
                    }
                }
                if (provider.models.isEmpty()) item { SettingsEmpty("还没有模型，读取目录或手工添加", "playlist_add") }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("删除服务？") }, text = { Text("将删除「${provider.name.ifBlank { "未命名" }}」及其模型配置。此操作无法撤销。") }, confirmButton = { TextButton({ confirmDelete = false; onDelete() }) { Text("删除", color = colors.error) } }, dismissButton = { TextButton({ confirmDelete = false }) { Text("取消") } })
    if (modelSheet) ModelConfigSheet(provider.apiType, editModel, provider.models.map { it.id }.toSet(), repository, { modelSheet = false }, { model ->
        val originalId = editModel?.id
        modifyLatest { current -> current.copy(models = if (originalId == null) current.models + model else current.models.map { if (it.id == originalId) model else it }) }
        modelSheet = false
    }, if (editModel != null) ({ val id = editModel!!.id; modifyLatest { current -> current.copy(models = current.models.filterNot { it.id == id }) }; modelSheet = false }) else null)
}

private fun ModelConfig.hasCustomThinking(): Boolean = thinkingBudget != null ||
    (reasoningEffort?.let { it != thinkingLevel.name.lowercase() && it != thinkingLevelMap[thinkingLevel] } == true)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelConfigSheet(
    type: ProviderApiType,
    model: ModelConfig?,
    existingIds: Set<String>,
    repository: ProviderConfigRepository,
    onDismiss: () -> Unit,
    onSave: (ModelConfig) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val initial = remember(model) { model ?: ModelConfig(id = "", thinkingLevel = ThinkingLevel.NONE) }
    val colors = LocalAgentColors.current
    var id by rememberSaveable { mutableStateOf(initial.id) }
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var reasoning by rememberSaveable { mutableStateOf(initial.reasoning) }
    var imageInput by rememberSaveable { mutableStateOf("image" in initial.input) }
    var context by rememberSaveable { mutableStateOf(initial.contextWindow.toString()) }
    var maxTokens by rememberSaveable { mutableStateOf(initial.maxTokens.toString()) }
    var inputPrice by rememberSaveable { mutableStateOf(initial.cost?.input?.toString().orEmpty()) }
    var outputPrice by rememberSaveable { mutableStateOf(initial.cost?.output?.toString().orEmpty()) }
    var cacheReadPrice by rememberSaveable { mutableStateOf(initial.cost?.cacheRead?.toString().orEmpty()) }
    var cacheWritePrice by rememberSaveable { mutableStateOf(initial.cost?.cacheWrite?.toString().orEmpty()) }
    var level by rememberSaveable { mutableStateOf(initial.thinkingLevel) }
    var custom by rememberSaveable { mutableStateOf(initial.hasCustomThinking()) }
    var customValue by rememberSaveable { mutableStateOf(initial.thinkingBudget?.toString() ?: initial.reasoningEffort.orEmpty()) }
    var dirty by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var suggestedThinkingMap by remember { mutableStateOf(initial.thinkingLevelMap) }
    var suggestedInput by remember { mutableStateOf(initial.input) }
    var matchStatus by remember { mutableStateOf("填写模型 ID 后自动匹配能力与价格") }
    var matchFailed by remember { mutableStateOf(false) }
    var matching by remember { mutableStateOf(false) }
    var retryMatch by remember { mutableIntStateOf(0) }
    var idRevision by remember { mutableIntStateOf(0) }
    var matchedModel by remember { mutableStateOf<ModelConfig?>(null) }
    var matchedQuery by remember { mutableStateOf("") }
    var matchedRevision by remember { mutableIntStateOf(-1) }
    var error by remember { mutableStateOf<String?>(null) }
    fun changed(field: String) { if (field !in dirty) dirty = dirty + field; error = null }
    // Explicit application may update persisted metadata; automatic suggestions never do.
    // Both paths protect fields edited while matching was in flight.
    fun applySuggestion(value: ModelConfig) {
        if ("name" !in dirty) name = value.name
        if ("reasoning" !in dirty) reasoning = value.reasoning
        if ("image" !in dirty) { imageInput = "image" in value.input; suggestedInput = value.input }
        if ("context" !in dirty) context = value.contextWindow.toString()
        if ("maxTokens" !in dirty) maxTokens = value.maxTokens.toString()
        if ("inputPrice" !in dirty) inputPrice = value.cost?.input?.toString().orEmpty()
        if ("outputPrice" !in dirty) outputPrice = value.cost?.output?.toString().orEmpty()
        if ("cacheReadPrice" !in dirty) cacheReadPrice = value.cost?.cacheRead?.toString().orEmpty()
        if ("cacheWritePrice" !in dirty) cacheWritePrice = value.cost?.cacheWrite?.toString().orEmpty()
        if ("thinking" !in dirty) {
            level = if (value.reasoning == false) ThinkingLevel.NONE else value.thinkingLevel
            suggestedThinkingMap = value.thinkingLevelMap
            custom = value.hasCustomThinking()
            customValue = value.thinkingBudget?.toString() ?: value.reasoningEffort.orEmpty()
        }
    }
    LaunchedEffect(id.trim(), idRevision, retryMatch) {
        val query = id.trim()
        val revision = idRevision
        matching = false
        matchFailed = false
        matchedModel = null
        matchedQuery = ""
        matchedRevision = -1
        if (query.isEmpty()) {
            matchStatus = "填写模型 ID 后自动匹配能力与价格"
            return@LaunchedEffect
        }
        if (model == null) applySuggestion(ModelConfig(id = query, thinkingLevel = ThinkingLevel.NONE))
        matching = true
        matchStatus = "正在匹配模型资料…"
        delay(350)
        try {
            val result = repository.matchModel(query)
            currentCoroutineContext().ensureActive()
            if (query != id.trim() || revision != idRevision) return@LaunchedEffect
            result.fold({ matched ->
                if (matched == null) matchStatus = "资料库暂无此模型；保留当前填写内容，不推测能力与价格"
                else {
                    matchedModel = matched
                    matchedQuery = query
                    matchedRevision = revision
                    if (model == null) applySuggestion(matched)
                    matchStatus = if (model == null) "已匹配模型资料，仅补充未手动修改的字段" else "已匹配模型资料；点「应用匹配」补充未手动修改的字段"
                }
            }, { failure ->
                matchFailed = true
                matchStatus = "匹配失败：${failure.message ?: "未知错误"}；可重试或手动填写"
            })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (query == id.trim() && revision == idRevision) {
                matchFailed = true
                matchStatus = "匹配失败：${failure.message ?: "未知错误"}；可重试或手动填写"
            }
        } finally {
            if (query == id.trim() && revision == idRevision) matching = false
        }
    }
    AgentSheet(if (model == null) "添加模型" else "编辑模型", onDismiss, actions = {}, contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ModelIcon(id, name, Modifier.size(32.dp))
                Text("模型资料", fontSize = 20.sp, fontWeight = FontWeight.Medium)
            }
            SettingsLabel("模型 ID")
            SettingsField(id, { id = it; idRevision++; matchedModel = null; error = null }, mono = true, placeholder = "例如 gpt-5")
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (matching) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(matchStatus, Modifier.weight(1f), color = if (matchFailed) colors.error else colors.onSurfaceVariant, fontSize = 14.sp)
                TextButton({ matchedModel = null; retryMatch++ }, enabled = id.isNotBlank() && !matching) { Text("重新匹配") }
            }
            if (model != null) {
                OutlinedButton(onClick = {
                    matchedModel?.takeIf { matchedQuery == id.trim() && matchedRevision == idRevision }?.let {
                        applySuggestion(it)
                        matchStatus = "已应用匹配资料，保留手动修改的字段；保存后生效"
                    }
                }, enabled = !matching && matchedModel != null && matchedQuery == id.trim() && matchedRevision == idRevision, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Symbol("auto_fix_high", size = 20.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("应用匹配", fontSize = 17.sp)
                }
            }
            SettingsLabel("显示名称")
            SettingsField(name, { name = it; changed("name") }, placeholder = "留空使用模型 ID")
            SettingsLabel("推理能力")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf<Pair<Boolean?, String>>(null to "未知", false to "不支持", true to "支持").forEach { (value, label) ->
                    FilterChip(reasoning == value, { reasoning = value; changed("reasoning") }, label = { Text(label, fontSize = 17.sp) }, modifier = Modifier.height(44.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("图片输入", fontSize = 18.sp)
                    Text("开启后允许向此模型发送图片", fontSize = 14.sp, color = colors.onSurfaceVariant)
                }
                Switch(imageInput, { imageInput = it; changed("image") })
            }
            SettingsLabel("上下文窗口 · token")
            SettingsField(context, { context = it; changed("context") }, mono = true, keyboardType = KeyboardType.Number)
            SettingsLabel("最大输出 · token")
            SettingsField(maxTokens, { maxTokens = it; changed("maxTokens") }, mono = true, keyboardType = KeyboardType.Number)
            SettingsLabel("价格 · 美元 / 百万 token")
            Text("全部留空表示未知；填写任一项后，其余空项按 0 保存。", fontSize = 14.sp, color = colors.onSurfaceVariant)
            SettingsLabel("输入价格")
            SettingsField(inputPrice, { inputPrice = it; changed("inputPrice") }, mono = true, placeholder = "未知", keyboardType = KeyboardType.Decimal)
            SettingsLabel("输出价格")
            SettingsField(outputPrice, { outputPrice = it; changed("outputPrice") }, mono = true, placeholder = "未知", keyboardType = KeyboardType.Decimal)
            SettingsLabel("缓存读取价格")
            SettingsField(cacheReadPrice, { cacheReadPrice = it; changed("cacheReadPrice") }, mono = true, placeholder = "未知", keyboardType = KeyboardType.Decimal)
            SettingsLabel("缓存写入价格")
            SettingsField(cacheWritePrice, { cacheWritePrice = it; changed("cacheWritePrice") }, mono = true, placeholder = "未知", keyboardType = KeyboardType.Decimal)
            SettingsLabel("思考强度")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ThinkingLevel.entries.forEach { value ->
                    FilterChip(selected = !custom && level == value, onClick = { level = value; custom = false; changed("thinking") }, enabled = reasoning != false || value == ThinkingLevel.NONE, label = { Text(value.label, fontSize = 17.sp) }, leadingIcon = { ThinkingBulb(value, Modifier.size(22.dp)) }, shape = RoundedCornerShape(12.dp), modifier = Modifier.height(44.dp))
                }
                FilterChip(custom, { custom = true; changed("thinking"); if (level == ThinkingLevel.NONE) level = ThinkingLevel.HIGH }, enabled = reasoning != false, label = { Text("自定义", fontSize = 17.sp) }, leadingIcon = { Symbol("tune", size = 22.dp) }, shape = RoundedCornerShape(12.dp), modifier = Modifier.height(44.dp))
            }
            AnimatedVisibility(custom, enter = expandVertically(tween(500, easing = EmphEasing)) + fadeIn(tween(380, 60, EmphDecelEasing)), exit = shrinkVertically(tween(480, easing = EmphEasing)) + fadeOut(tween(240))) {
                Column {
                    SettingsLabel(if (type == ProviderApiType.MESSAGE) "思考预算" else if (type == ProviderApiType.RESPONSE) "reasoning.effort" else "reasoning_effort")
                    SettingsField(customValue, { customValue = it; changed("thinking") }, mono = true, placeholder = if (type == ProviderApiType.MESSAGE) "例如 16000" else "例如 high", keyboardType = if (type == ProviderApiType.MESSAGE) KeyboardType.Number else KeyboardType.Text)
                    if (type == ProviderApiType.MESSAGE) Text("预算须为至少 1024 的整数，且小于上方编辑的最大输出 token。", Modifier.padding(start = 4.dp, top = 8.dp), fontSize = 14.sp, color = colors.onSurfaceVariant)
                }
            }
            error?.let { Text(it, Modifier.padding(top = 12.dp), color = colors.error, fontSize = 16.sp) }
            Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                onDelete?.let { OutlinedButton(it, modifier = Modifier.height(54.dp)) { Symbol("delete"); Spacer(Modifier.width(8.dp)); Text("删除", fontSize = 18.sp) } }
                SettingsSaveButton({
                    val cleanId = id.trim()
                    val contextLimit = context.trim().toIntOrNull()
                    val outputLimit = maxTokens.trim().toIntOrNull()
                    val priceTexts = listOf(inputPrice, outputPrice, cacheReadPrice, cacheWritePrice)
                    val prices = priceTexts.map { if (it.isBlank()) 0.0 else it.trim().toDoubleOrNull() }
                    val budget = if (custom && type == ProviderApiType.MESSAGE) customValue.trim().toIntOrNull() else null
                    error = when {
                        cleanId.isEmpty() -> "请填写模型 ID"
                        cleanId != model?.id && cleanId in existingIds -> "模型 ID 已存在"
                        contextLimit == null || contextLimit <= 0 -> "上下文窗口须为正整数"
                        outputLimit == null || outputLimit <= 0 -> "最大输出须为正整数"
                        prices.any { it == null || !it.isFinite() || it < 0.0 } -> "价格须为有限的非负数字"
                        reasoning == false && (level != ThinkingLevel.NONE || custom) -> "此模型不支持思考，请选择无或修改推理能力"
                        custom && type == ProviderApiType.MESSAGE && (budget == null || budget < 1024 || budget >= outputLimit) -> "思考预算须至少为 1024，且小于最大输出"
                        custom && type != ProviderApiType.MESSAGE && customValue.isBlank() -> "请填写自定义值"
                        else -> null
                    }
                    if (error == null) onSave(initial.copy(
                        id = cleanId,
                        name = name.trim().ifBlank { cleanId },
                        reasoning = reasoning,
                        input = (suggestedInput.filterNot { it == "image" } + if (imageInput) listOf("image") else emptyList()).distinct(),
                        contextWindow = contextLimit!!,
                        maxTokens = outputLimit!!,
                        cost = if (priceTexts.all { it.isBlank() }) null else ModelCost(prices[0]!!, prices[1]!!, prices[2]!!, prices[3]!!),
                        thinkingLevel = if (budget != null && level == ThinkingLevel.NONE) ThinkingLevel.HIGH else level,
                        reasoningEffort = if (custom && type != ProviderApiType.MESSAGE) customValue.trim() else null,
                        thinkingBudget = budget,
                        thinkingLevelMap = if (custom && type != ProviderApiType.MESSAGE) suggestedThinkingMap + (level to customValue.trim()) else suggestedThinkingMap,
                    ))
                }, modifier = Modifier.weight(1f))
            }
        }
    }
}
