package dev.wawlink.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wawlink.protocol.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.random.Random

@Composable
fun ProviderSettingsScreen(repository: ProviderConfigRepository, onBack: () -> Unit) {
    val persisted by repository.providers.collectAsState()
    val synchronizationError by repository.error.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val notify: (String) -> Unit = remember(scope) { { text -> scope.launch { snackbar.showSnackbar(text) }; Unit } }
    val queue = remember(repository) { SettingsWriteQueue(scope, notify) }
    val drafts = remember(repository) { mutableStateMapOf<String, ProviderConfig>() }
    val removed = remember(repository) { mutableStateMapOf<String, Boolean>() }
    var selectedId by remember(repository) { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
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
    val all = (persisted.map { drafts[it.id] ?: it } + drafts.values.filter { draft -> persisted.none { it.id == draft.id } })
        .filter { removed[it.id] != true }
    val selected = selectedId?.let { id -> all.firstOrNull { it.id == id } }
    val colors = LocalAgentColors.current

    Box(Modifier.fillMaxSize().background(colors.surface), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 460.dp).fillMaxSize()) {
            AnimatedContent(selected?.id, transitionSpec = {
                (slideInHorizontally(tween(500, easing = EmphDecelEasing)) { if (targetState != null) it else -it / 4 } + fadeIn(tween(300))) togetherWith
                    (slideOutHorizontally(tween(350, easing = EmphAccelEasing)) { if (targetState != null) -it / 4 else it } + fadeOut(tween(250)))
            }, modifier = Modifier.weight(1f)) { target ->
                val provider = target?.let { current(it) }
                if (provider == null) {
                    Box(Modifier.fillMaxSize()) {
                        Column {
                            AgentTopBar("设置", ::back)
                            SettingsFailure(queue) { queue.failures.keys.toList().forEach(::persist) }
                            SettingsSyncError(synchronizationError)
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp)) {
                                SettingsSearch(search, { search = it }, "搜索服务")
                                Row(Modifier.padding(start = 4.dp, top = 20.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Symbol("hub", size = 20.dp, tint = colors.primary)
                                    Text("模型服务", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = colors.primary)
                                }
                                val visible = all.filter { search.isBlank() || it.name.contains(search.trim(), true) }
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    visible.forEachIndexed { index, item ->
                                        Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).background(colors.surfaceContainerLow, segmentedSettingsShape(index, visible.size)).clickable { selectedId = item.id }.padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                            ProviderAvatar(item)
                                            Column(Modifier.weight(1f)) {
                                                Text(item.name.ifBlank { "未命名" }, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = if (item.enabled) colors.onSurface else colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text("${item.apiType.label} · ${item.models.size} 个模型", fontSize = 16.sp, color = colors.onSurfaceVariant)
                                            }
                                            Switch(item.enabled, { update((current(item.id) ?: item).copy(enabled = it)) })
                                        }
                                    }
                                    if (visible.isEmpty()) SettingsEmpty(if (all.isEmpty()) "还没有模型服务，点下方添加" else "没有匹配的服务", "hub")
                                }
                            }
                        }
                        ExtendedFloatingActionButton(onClick = {
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
                    }, { queue.failures.keys.toList().forEach(::persist) }, repository, notify)
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).widthIn(max = 460.dp).padding(16.dp))
    }
}

private val providerAvatarPalette = listOf(Color(0xFF0B57D0), Color(0xFFC5551A), Color(0xFF146C2E), Color(0xFF8E24AA), Color(0xFF00796B), Color(0xFFB3261E), Color(0xFF5F6368))
private val chatThinkingLevels = listOf(ThinkingLevel.NONE, ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH)
private val messageThinkingLevels = listOf(ThinkingLevel.NONE, ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH, ThinkingLevel.MAX)

@Composable
private fun ProviderAvatar(provider: ProviderConfig) {
    val color = providerAvatarPalette[(provider.id.hashCode().toLong().let { if (it < 0) -it else it } % providerAvatarPalette.size).toInt()]
    Box(Modifier.size(44.dp).alpha(if (provider.enabled) 1f else .5f).background(if (provider.enabled) color else Color(0xFF747775), CircleShape), contentAlignment = Alignment.Center) {
        Text(provider.name.firstOrNull()?.uppercase() ?: "?", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

private fun ProviderApiType.levels(): List<ThinkingLevel> = when (this) {
    ProviderApiType.CHAT -> chatThinkingLevels
    ProviderApiType.RESPONSE -> ThinkingLevel.entries
    ProviderApiType.MESSAGE -> messageThinkingLevels
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
                    val selected = type == provider.apiType
                    val segmentShape = when (index) { 0 -> RoundedCornerShape(topStart = 26.dp, bottomStart = 26.dp); 2 -> RoundedCornerShape(topEnd = 26.dp, bottomEnd = 26.dp); else -> RoundedCornerShape(0.dp) }
                    val bg by animateColorAsState(if (selected) colors.secondaryContainer else colors.surface, tween(300, easing = EmphEasing))
                    Row(Modifier.weight(1f).fillMaxHeight().background(bg, segmentShape).clickable {
                        modifyLatest { current -> current.copy(apiType = type, models = current.models.map { model -> if (model.thinkingLevel !in type.levels() && model.reasoningEffort == null && model.thinkingBudget == null) model.copy(thinkingLevel = ThinkingLevel.HIGH) else model }) }
                    }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        AnimatedVisibility(selected, enter = expandHorizontally(tween(350, easing = EmphEasing)) + fadeIn(), exit = shrinkHorizontally() + fadeOut()) { Symbol("check", Modifier.padding(end = 6.dp), size = 20.dp) }
                        Text(type.label, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    }
                    if (index < ProviderApiType.entries.lastIndex) Box(Modifier.width(1.dp).fillMaxHeight().background(colors.outline))
                }
            }
            Row(Modifier.padding(start = 4.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Symbol("info", size = 20.dp, tint = colors.onSurfaceVariant)
                Text(when (provider.apiType) { ProviderApiType.CHAT -> "经典 OpenAI 接口"; ProviderApiType.RESPONSE -> "新版 OpenAI 接口"; ProviderApiType.MESSAGE -> "Claude 接口" }, color = colors.onSurfaceVariant, fontSize = 16.sp)
            }
            SettingsLabel("接口地址")
            SettingsField(provider.baseUrl, { base -> modifyLatest { it.copy(baseUrl = base) } }, placeholder = "https://", mono = true, keyboardType = KeyboardType.Uri)
            SettingsLabel("请求路径")
            SettingsField(when (provider.apiType) { ProviderApiType.CHAT -> "/chat/completions"; ProviderApiType.RESPONSE -> "/responses"; ProviderApiType.MESSAGE -> "/messages" }, {}, readOnly = true, mono = true)
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
                else AgentIconButton("sync", "获取远端模型", {
                    syncing = true
                    val snapshot = latest
                    scope.launch {
                        try {
                            repository.fetchRemoteModels(snapshot).fold({ ids ->
                                if (snapshot.apiType != latest.apiType || snapshot.baseUrl != latest.baseUrl || snapshot.apiKey != latest.apiKey) notify("配置已变更，请重新同步")
                                else {
                                    var added = 0
                                    modifyLatest { current ->
                                        val known = current.models.map { it.id }.toSet()
                                        val new = ids.map { it.trim() }.filter { it.isNotEmpty() && it !in known }.distinct().map { ModelConfig(id = it, thinkingLevel = ThinkingLevel.NONE) }
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
                })
                AgentIconButton("add", "添加模型", { editModel = null; modelSheet = true })
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                provider.models.forEachIndexed { index, model ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).background(colors.surfaceContainerLow, segmentedSettingsShape(index, provider.models.size)).clickable { editModel = model; modelSheet = true }.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Symbol("neurology", tint = colors.onSurfaceVariant)
                        Text(model.id, Modifier.weight(1f), fontFamily = LocalAgentMono.current, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Surface(shape = RoundedCornerShape(12.dp), color = colors.surface, border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant)) {
                            Row(Modifier.heightIn(min = 36.dp).padding(start = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (model.reasoningEffort != null || model.thinkingBudget != null) Symbol("tune", size = 20.dp, tint = colors.primary) else ThinkingBulb(model.thinkingLevel, Modifier.size(20.dp), if (model.thinkingLevel == ThinkingLevel.NONE) colors.onSurfaceVariant else colors.primary)
                                Text(model.thinkingBudget?.toString() ?: model.reasoningEffort ?: model.thinkingLevel.label, fontSize = 16.sp, maxLines = 1, modifier = Modifier.widthIn(max = 96.dp), overflow = TextOverflow.Ellipsis, color = if (model.thinkingLevel == ThinkingLevel.NONE && model.reasoningEffort == null && model.thinkingBudget == null) colors.onSurfaceVariant else colors.primary)
                            }
                        }
                        Symbol("chevron_right", tint = colors.onSurfaceVariant)
                    }
                }
                if (provider.models.isEmpty()) SettingsEmpty("还没有模型，点加号添加或同步获取", "neurology")
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("删除服务？") }, text = { Text("将删除「${provider.name.ifBlank { "未命名" }}」及其模型配置。此操作无法撤销。") }, confirmButton = { TextButton({ confirmDelete = false; onDelete() }) { Text("删除", color = colors.error) } }, dismissButton = { TextButton({ confirmDelete = false }) { Text("取消") } })
    if (modelSheet) ModelConfigSheet(provider.apiType, editModel, provider.models.map { it.id }.toSet(), { modelSheet = false }, { model ->
        val originalId = editModel?.id
        modifyLatest { current -> current.copy(models = if (originalId == null) current.models + model else current.models.map { if (it.id == originalId) model else it }) }
        modelSheet = false
    }, if (editModel != null) ({ val id = editModel!!.id; modifyLatest { current -> current.copy(models = current.models.filterNot { it.id == id }) }; modelSheet = false }) else null)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelConfigSheet(type: ProviderApiType, model: ModelConfig?, existingIds: Set<String>, onDismiss: () -> Unit, onSave: (ModelConfig) -> Unit, onDelete: (() -> Unit)?) {
    val initial = remember(model) { model ?: ModelConfig(id = "") }
    var id by remember { mutableStateOf(model?.id.orEmpty()) }
    var level by remember { mutableStateOf(model?.thinkingLevel ?: ThinkingLevel.NONE) }
    var custom by remember { mutableStateOf(model?.reasoningEffort != null || model?.thinkingBudget != null) }
    var customValue by remember { mutableStateOf(model?.thinkingBudget?.toString() ?: model?.reasoningEffort.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    AgentSheet(if (model == null) "添加模型" else "编辑模型", onDismiss, actions = {}, contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            SettingsLabel("模型 ID")
            SettingsField(id, { id = it; error = null }, mono = true, placeholder = "例如 gpt-5")
            SettingsLabel("思考强度")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                type.levels().forEach { value ->
                    FilterChip(selected = !custom && level == value, onClick = { level = value; custom = false }, label = { Text(value.label, fontSize = 17.sp) }, leadingIcon = { ThinkingBulb(value, Modifier.size(22.dp)) }, shape = RoundedCornerShape(12.dp), modifier = Modifier.height(44.dp))
                }
                FilterChip(custom, { custom = true; if (type == ProviderApiType.MESSAGE && level == ThinkingLevel.NONE) level = ThinkingLevel.HIGH }, label = { Text("自定义", fontSize = 17.sp) }, leadingIcon = { Symbol("tune", size = 22.dp) }, shape = RoundedCornerShape(12.dp), modifier = Modifier.height(44.dp))
            }
            AnimatedVisibility(custom, enter = expandVertically(tween(500, easing = EmphEasing)) + fadeIn(tween(380, 60, EmphDecelEasing)), exit = shrinkVertically(tween(480, easing = EmphEasing)) + fadeOut(tween(240))) {
                Column {
                    SettingsLabel(if (type == ProviderApiType.MESSAGE) "思考预算" else if (type == ProviderApiType.RESPONSE) "reasoning.effort" else "reasoning_effort")
                    SettingsField(customValue, { customValue = it; error = null }, mono = true, placeholder = if (type == ProviderApiType.MESSAGE) "例如 16000" else "例如 high", keyboardType = if (type == ProviderApiType.MESSAGE) KeyboardType.Number else KeyboardType.Text)
                    if (type == ProviderApiType.MESSAGE) Text("预算须为整数，范围 1024 ～ ${initial.maxTokens - 1}，且必须小于实际请求的输出上限。", Modifier.padding(start = 4.dp, top = 8.dp), fontSize = 14.sp, color = LocalAgentColors.current.onSurfaceVariant)
                }
            }
            error?.let { Text(it, Modifier.padding(top = 12.dp), color = LocalAgentColors.current.error, fontSize = 16.sp) }
            Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                onDelete?.let { OutlinedButton(it, modifier = Modifier.height(54.dp)) { Symbol("delete"); Spacer(Modifier.width(8.dp)); Text("删除", fontSize = 18.sp) } }
                SettingsSaveButton({
                    val cleanId = id.trim()
                    val budget = if (custom && type == ProviderApiType.MESSAGE) customValue.trim().toIntOrNull() else null
                    error = when {
                        cleanId.isEmpty() -> "请填写模型 ID"
                        cleanId != model?.id && cleanId in existingIds -> "模型 ID 已存在"
                        custom && type == ProviderApiType.MESSAGE && (budget == null || budget < 1024 || budget >= initial.maxTokens) -> "思考预算须为 1024 ～ ${initial.maxTokens - 1} 的整数"
                        custom && type != ProviderApiType.MESSAGE && customValue.isBlank() -> "请填写自定义值"
                        else -> null
                    }
                    if (error == null) onSave(initial.copy(id = cleanId, name = if (model == null || model.name == model.id) cleanId else model.name, thinkingLevel = if (budget != null && level == ThinkingLevel.NONE) ThinkingLevel.HIGH else level, reasoningEffort = if (custom && type != ProviderApiType.MESSAGE) customValue.trim() else null, thinkingBudget = budget))
                }, modifier = Modifier.weight(1f))
            }
        }
    }
}
