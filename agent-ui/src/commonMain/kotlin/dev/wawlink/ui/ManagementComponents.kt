@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.wawlink.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wawlink.protocol.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import kotlin.math.abs

/** Screen-local ordered writes; failed operations leave the editor and its draft in place. */
internal class ManagementActions(scope: CoroutineScope, private val notify: suspend (String) -> Unit) {
    private val operations = Channel<Pair<String?, suspend () -> Unit>>(Channel.UNLIMITED)
    private var pending by mutableIntStateOf(0)
    val busy: Boolean get() = pending > 0
    var error by mutableStateOf<String?>(null)
        private set
    init {
        scope.launch {
            for ((success, operation) in operations) {
                try {
                    operation()
                    error = null
                    if (success != null) scope.launch { notify(success) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    error = failure.message ?: "操作失败"
                    scope.launch { notify(error ?: "操作失败") }
                } finally { pending-- }
            }
        }
    }
    fun launch(success: String? = null, block: suspend () -> Unit) {
        pending++
        if (operations.trySend(success to block).isFailure) pending--
    }
}
internal val LocalManagementActions = staticCompositionLocalOf<ManagementActions> { error("Missing management actions") }
internal val LocalManagementSession = staticCompositionLocalOf<AgentSessionController?> { null }
internal val LocalManagementRepository = staticCompositionLocalOf<AgentManagementRepository?> { null }

internal fun configurationMap(value: Any?): Map<String, Any?> = (value as? Map<*, *>)?.entries?.mapNotNull { (key, item) -> (key as? String)?.let { it to item } }?.toMap().orEmpty()
internal fun configurationStrings(value: Any?): List<String> = when (value) { is String -> listOf(value); is List<*> -> value.filterIsInstance<String>(); else -> emptyList() }
internal fun configurationValue(values: Map<String, Any?>, path: String): Any? {
    if (values.containsKey(path)) return values[path]
    var current: Any? = values
    for (part in path.split('.')) current = (current as? Map<*, *>)?.get(part) ?: return null
    return current
}
internal fun configurationJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is String -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to configurationJson(it.value) })
    is Iterable<*> -> JsonArray(value.map(::configurationJson))
    else -> JsonPrimitive(value.toString())
}
internal fun configurationFromJson(value: JsonElement): Any? = when (value) {
    JsonNull -> null
    is JsonObject -> value.mapValues { configurationFromJson(it.value) }
    is JsonArray -> value.map(::configurationFromJson)
    is JsonPrimitive -> if (value.isString) value.content else value.booleanOrNull ?: value.longOrNull ?: value.doubleOrNull ?: value.content
}
internal fun configurationDisplay(value: Any?): String = when (value) {
    null -> "未设置"
    true -> "开启"
    false -> "关闭"
    is String -> value.ifEmpty { "空文本" }
    is List<*> -> if (value.isEmpty()) "空列表" else value.joinToString("，") { if (it is String) it else configurationJson(it).toString() }
    is Map<*, *> -> if (value.isEmpty()) "空映射" else configurationJson(value).toString()
    else -> value.toString()
}
internal fun managementSourceLabel(source: String): String = when (source) {
    "global", "user" -> "全局"
    "project" -> "项目"
    "builtin", "bundled" -> "内置"
    "package", "extension" -> "扩展包"
    "plugin" -> "兼容插件"
    "default" -> "默认值"
    "env" -> "环境变量"
    "runtime" -> "运行时"
    "overlay", "cli" -> "启动配置"
    else -> source
}
internal fun managementThinkingLabel(value: String?): String = when (value) {
    null, "inherit" -> "继承"
    "off", "none" -> "关闭"
    "minimal" -> "极低"
    "low" -> "低"
    "medium" -> "中"
    "high" -> "高"
    "xhigh" -> "超高"
    "max" -> "最大"
    "auto" -> "自动"
    else -> value
}

@Composable
internal fun ManagementPage(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}, fab: (@Composable () -> Unit)? = null, footer: (@Composable () -> Unit)? = null, scroll: ScrollState = rememberScrollState(), content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalAgentColors.current
    val bar by animateColorAsState(if (scroll.value > 4) colors.surfaceContainer else colors.surface, tween(300, easing = EmphEasing))
    Box(Modifier.fillMaxSize().background(colors.surface)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.background(bar)) { AgentTopBar(title, onBack, actions) }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 140.dp), content = content)
        }
        if (fab != null) Box(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 20.dp)) { fab() }
        if (footer != null) Box(Modifier.align(Alignment.BottomCenter).padding(12.dp)) { footer() }
    }
}
@Composable
internal fun ManagementGroup(title: String, action: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 24.dp, bottom = 10.dp).heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = LocalAgentColors.current.primary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        action?.invoke()
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp), content = content)
}
@Composable
internal fun ManagementRow(title: String, subtitle: String = "", icon: String = "tune", onClick: (() -> Unit)? = null, index: Int = 0, count: Int = 1, lead: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null, enabled: Boolean = true, error: Boolean = false, detail: String = "") {
    val c = LocalAgentColors.current
    val shape = RoundedCornerShape(topStart = if (index == 0) 24.dp else 6.dp, topEnd = if (index == 0) 24.dp else 6.dp,
        bottomStart = if (index == count - 1) 24.dp else 6.dp, bottomEnd = if (index == count - 1) 24.dp else 6.dp)
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(shape).background(c.surfaceContainerLow)
        .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
        .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (lead != null) lead() else if (icon.isNotEmpty()) Box(Modifier.size(44.dp).background(if (error) c.errorContainer else c.primaryContainer, CircleShape), contentAlignment = Alignment.Center) { Symbol(icon, tint = if (error) c.error else c.onPrimaryContainer) }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (enabled) c.onSurface else c.outline)
            if (subtitle.isNotEmpty()) Text(subtitle, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (error) c.error else c.onSurfaceVariant)
            if (detail.isNotEmpty()) Text(detail, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = c.onSurfaceVariant)
        }
        if (trailing != null) trailing() else if (onClick != null) Symbol("chevron_right", tint = c.onSurfaceVariant)
    }
}
@Composable
internal fun ManagementHint(text: String, icon: String = "info", error: Boolean = false) {
    val color = if (error) LocalAgentColors.current.error else LocalAgentColors.current.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Symbol(icon, Modifier.padding(top = 2.dp), size = 20.dp, tint = color)
        Text(text, fontSize = 16.sp, color = color)
    }
}
@Composable
internal fun ManagementBanner(text: String, icon: String = "info", error: Boolean = false, warning: Boolean = false) {
    val c = LocalAgentColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).background(when { error -> c.errorContainer; warning -> c.warnContainer; else -> c.surfaceContainer }, RoundedCornerShape(24.dp)).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Symbol(icon, tint = when { error -> c.error; warning -> c.warn; else -> c.primary })
        Text(text, fontSize = 17.sp, color = if (error) c.error else c.onSurface)
    }
}
@Composable
internal fun ManagementSegments(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, enabled: (String) -> Boolean = { true }) {
    val c = LocalAgentColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(24.dp)).border(1.dp, c.outline, RoundedCornerShape(24.dp))) {
        options.forEachIndexed { index, (id, label) ->
            val chosen = id == selected
            val bg by animateColorAsState(if (chosen) c.secondaryContainer else c.surface, tween(300, easing = EmphEasing))
            if (index > 0) Box(Modifier.width(1.dp).height(52.dp).background(c.outline))
            Row(Modifier.weight(1f).heightIn(min = 52.dp).background(bg).clickable(enabled(id)) { onSelect(id) }.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                AnimatedVisibility(chosen, enter = expandHorizontally(tween(350, easing = EmphEasing)) + fadeIn(), exit = shrinkHorizontally(tween(250)) + fadeOut()) { Symbol("check", Modifier.padding(end = 6.dp), size = 20.dp) }
                Text(label, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, color = if (enabled(id)) c.onSurface else c.outline)
            }
        }
    }
}
@Composable
internal fun ManagementScopeChip(scope: ConfigurationScope, onScope: (ConfigurationScope) -> Unit, hasProject: Boolean, globalOnly: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    val c = LocalAgentColors.current
    Box(Modifier.padding(end = 8.dp)) {
        Surface(onClick = { if (!globalOnly) open = true }, color = if (globalOnly) c.surface else if (scope == ConfigurationScope.PROJECT) c.secondaryContainer else c.surfaceContainerHigh,
            shape = RoundedCornerShape(20.dp), border = if (globalOnly) BorderStroke(1.dp, c.outlineVariant) else null) {
            Row(Modifier.height(40.dp).padding(start = 10.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Symbol(if (globalOnly) "lock" else if (scope == ConfigurationScope.PROJECT) "folder" else "public", size = 20.dp)
                Text(if (globalOnly) "仅全局" else scope.label, fontSize = 16.sp)
            }
        }
        ManagementMenu(open, { open = false }) {
            ConfigurationScope.entries.forEach { value ->
                ManagementMenuItem(text = { Text(value.label) }, selected = value == scope, leadingIcon = { Symbol(if (value == ConfigurationScope.GLOBAL) "public" else "folder") }, trailingIcon = { if (value == scope) Symbol("check") },
                    enabled = value != ConfigurationScope.PROJECT || hasProject, onClick = { open = false; onScope(value) })
            }
        }
    }
}
@Composable
internal fun ManagementField(label: String, value: String, onValue: (String) -> Unit, mono: Boolean = false, multiline: Boolean = false, readOnly: Boolean = false) {
    if (label.isNotEmpty()) SettingsLabel(label)
    if (!multiline) SettingsField(value, onValue, mono = mono, readOnly = readOnly)
    else {
        val c = LocalAgentColors.current
        BasicTextField(value, onValue, Modifier.fillMaxWidth().heightIn(min = 140.dp).background(c.surfaceContainer, RoundedCornerShape(16.dp)).padding(horizontal = 18.dp, vertical = 14.dp),
            readOnly = readOnly, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = if (mono) LocalAgentMono.current else MaterialTheme.typography.bodyMedium.fontFamily, color = c.onSurface, fontSize = if (mono) 16.sp else 17.sp), cursorBrush = SolidColor(c.primary))
    }
}
internal data class ManagementLayer(val label: String, val value: String, val icon: String = "layers", val selected: Boolean = false, val error: Boolean = false)
@Composable
internal fun ManagementLayers(layers: List<ManagementLayer>) {
    val c = LocalAgentColors.current
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        layers.forEachIndexed { index, layer ->
            val effective = layer.label in setOf("有效值", "最终解析", "当前解析")
            val shape = RoundedCornerShape(topStart = if (index == 0) 20.dp else 6.dp, topEnd = if (index == 0) 20.dp else 6.dp, bottomStart = if (index == layers.lastIndex) 20.dp else 6.dp, bottomEnd = if (index == layers.lastIndex) 20.dp else 6.dp)
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).background(if (effective) c.surface else if (layer.selected) c.primaryContainer else c.surfaceContainerLow, shape).then(if (effective) Modifier.border(1.dp, c.outlineVariant, shape) else Modifier).padding(vertical = 8.dp, horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Symbol(layer.icon, tint = if (layer.error) c.error else if (layer.selected) c.onPrimaryContainer else c.onSurfaceVariant)
                Text(layer.label, Modifier.width(96.dp), fontSize = 16.sp, color = c.onSurfaceVariant)
                Text(layer.value, Modifier.weight(1f), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (layer.error) c.error else c.onSurface)
                if (layer.selected) Symbol("check", size = 20.dp, tint = if (effective) c.primary else c.onPrimaryContainer)
            }
        }
    }
}
@Composable
internal fun ManagementFab(label: String, icon: String = "add", onClick: () -> Unit) {
    ExtendedFloatingActionButton(onClick = onClick, shape = RoundedCornerShape(16.dp), containerColor = LocalAgentColors.current.primaryContainer, contentColor = LocalAgentColors.current.onPrimaryContainer,
        icon = { Symbol(icon) }, text = { Text(label, fontSize = 17.sp, fontWeight = FontWeight.Medium) })
}
@Composable
internal fun ManagementChip(label: String, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    FilterChip(selected, onClick, { Text(label, fontSize = 16.sp) }, enabled = enabled, modifier = Modifier.heightIn(min = 40.dp), shape = RoundedCornerShape(12.dp), leadingIcon = if (selected) ({ Symbol("check", size = 20.dp) }) else null)
}
@Composable
internal fun ManagementSaveBar(dirty: Boolean, saving: Boolean, onSave: () -> Unit, onDiscard: () -> Unit, error: String? = null, canSave: Boolean = true) {
    AnimatedVisibility(dirty || saving || error != null, enter = slideInVertically(tween(550, easing = EmphDecelEasing)) { it } + fadeIn(), exit = slideOutVertically(tween(350, easing = EmphAccelEasing)) { it } + fadeOut()) {
        Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF303030), contentColor = Color(0xFFF2F2F2), shadowElevation = 6.dp) {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (saving) CircularProgressIndicator(Modifier.size(22.dp), color = Color(0xFFA8C7FA), strokeWidth = 2.dp) else Symbol(if (error == null) "edit" else "error", size = 22.dp)
                Text(if (saving) "正在保存" else if (error != null) "保存失败" else "有未保存的修改", Modifier.weight(1f), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onDiscard, enabled = !saving) { Text("放弃", color = Color(0xFFA8C7FA)) }
                Button(onSave, enabled = !saving && canSave, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA8C7FA), contentColor = Color(0xFF062E6F))) { Text(if (error != null) "重试" else "保存") }
            }
        }
    }
}

/** Strip only a recognised thought suffix and only after checking the complete model ID. */
internal fun managementSelectorParts(selector: String, state: AgentManagementState): Pair<String, String?> {
    if (state.models.any { it.key == selector || it.id == selector }) return selector to null
    val suffix = selector.substringAfterLast(':', "")
    return if (suffix in listOf("off", "minimal", "low", "medium", "high", "xhigh", "max", "auto")) selector.substringBeforeLast(':') to suffix else selector to null
}
@Composable
internal fun ManagementThinkingPicker(current: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit, inherit: Boolean = true, efforts: List<String>? = null, expanded: Boolean = true) {
    ManagementMenu(expanded, onDismiss) {
        Text("思考强度", Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = LocalAgentColors.current.onSurfaceVariant, fontSize = 16.sp)
        val values = (if (inherit) listOf<String?>(null) else emptyList()) + listOf("off", "minimal", "low", "medium", "high", "xhigh", "max", "auto").filter { efforts == null || it == "off" || it in efforts }
        values.forEach { value ->
            ManagementMenuItem(text = { Text(managementThinkingLabel(value)) }, selected = current == value || current == "inherit" && value == null,
                leadingIcon = { Symbol(if (value == null) "link" else if (value == "auto") "auto_mode" else "lightbulb") },
                onClick = { onPick(value); onDismiss() }, trailingIcon = { if (current == value) Symbol("check", tint = LocalAgentColors.current.primary) })
        }
    }
}
@Composable
internal fun ManagementModelPicker(state: AgentManagementState, current: String? = null, kind: String? = null, allowRole: Boolean = true, allowRaw: Boolean = true, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var tab by rememberSaveable { mutableStateOf("model") }
    var query by rememberSaveable { mutableStateOf("") }
    var provider by rememberSaveable { mutableStateOf<String?>(null) }
    var onlyAvailable by rememberSaveable { mutableStateOf(false) }
    var strict by rememberSaveable { mutableStateOf(kind != null) }
    val repository = LocalManagementRepository.current
    val actions = LocalManagementActions.current
    var raw by rememberSaveable { mutableStateOf(current.orEmpty()) }
    var detail by remember { mutableStateOf<ManagedModel?>(null) }
    AgentSheet("选择模型", onDismiss, actions = {}, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp)) {
            ManagementSegments(listOf("model" to "模型") + (if (allowRole) listOf("role" to "角色引用") else emptyList()) + (if (allowRaw) listOf("raw" to "高级") else emptyList()), tab, { tab = it })
            Spacer(Modifier.height(12.dp))
            when (tab) {
                "model" -> {
                    SettingsSearch(query, { query = it }, "搜索模型或服务")
                    if (state.modelsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.modelsError?.let { ManagementBanner(it, "sync_problem", error = true) }
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        var showProviders by remember { mutableStateOf(false) }
                        Box {
                            ManagementChip(provider ?: "全部服务", provider != null, { showProviders = true })
                            ManagementMenu(showProviders, { showProviders = false }) {
                                DropdownMenuItem(text = { Text("全部服务") }, onClick = { provider = null; showProviders = false })
                                state.models.map { it.provider }.distinct().forEach { id -> DropdownMenuItem(text = { Text(id) }, leadingIcon = { Symbol("hub") }, onClick = { provider = id; showProviders = false }) }
                            }
                        }
                        if (kind != null) ManagementChip("仅适用类型", strict, { strict = !strict })
                        ManagementChip("仅可用", onlyAvailable, { onlyAvailable = !onlyAvailable })
                    }
                    val requiredRole = kind?.removePrefix("role:")?.takeIf { kind.startsWith("role:") || state.roles.any { role -> role.id == kind } }
                    val models = state.models.filter { model -> (provider == null || model.provider == provider) && (!onlyAvailable || model.available) &&
                        (!strict || kind == null || if (requiredRole != null) (model.metadata["acceptedRoles"] as? List<*>)?.contains(requiredRole) == true else model.kind == kind) &&
                        (query.isBlank() || model.key.contains(query, true) || model.name.contains(query, true)) }
                    LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        itemsIndexed(models, key = { _, model -> model.key }) { index, model ->
                            ManagementRow(model.name, if (model.available) model.provider else model.unavailableReason ?: "未认证或不可用", index = index, count = models.size,
                                onClick = { onPick(model.key); onDismiss() }, lead = { ModelIcon(model.id, model.name, Modifier.size(44.dp)) },
                                trailing = { Row(verticalAlignment = Alignment.CenterVertically) { if (current == model.key) Symbol("check", tint = LocalAgentColors.current.primary); AgentIconButton("info", "模型详情", { actions.launch { detail = repository?.modelDetails(model.provider, model.id) ?: model } }) } })
                        }
                    }
                    if (models.isEmpty() && !state.modelsLoading) SettingsEmpty(if (state.modelsError == null) "没有符合条件的模型" else "模型目录读取失败", "search_off")
                }
                "role" -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    state.roles.forEachIndexed { index, role -> ManagementRow(role.name, role.model ?: role.error ?: "未配置", "label", { onPick("@${role.id}"); onDismiss() }, index, state.roles.size, trailing = { if (current == "@${role.id}") Symbol("check") }) }
                }
                "raw" -> {
                    ManagementField("选择器", raw, { raw = it }, mono = true)
                    ManagementHint("支持通配符与角色引用", "code")
                    Button({ onPick(raw.trim()); onDismiss() }, enabled = raw.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 20.dp).height(48.dp)) { Symbol("check"); Spacer(Modifier.width(8.dp)); Text("使用此选择器") }
                }
            }
        }
    }
    detail?.let { model ->
        val fields = configurationMap(model.metadata["baseline"]) + model.metadata
        val thinking = configurationMap(fields["thinking"])
        val cost = configurationMap(fields["cost"])
        AgentSheet(model.name, { detail = null }) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ModelIcon(model.id, model.name, Modifier.size(44.dp))
                ManagementHint(model.key, "tag")
                ManagementLayers(listOf(ManagementLayer("类型", model.kind, "category"), ManagementLayer("状态", if (model.available) "可用" else model.unavailableReason ?: "未认证或不可用", "info")))
                ManagementLayers(listOf(
                    ManagementLayer("输入", configurationStrings(fields["input"]).joinToString("，") { if (it == "text") "文本" else if (it == "image") "图片" else it }, "input"),
                    ManagementLayer("工具调用", if (fields["supportsTools"] == true) "支持" else "不支持", "construction"),
                    ManagementLayer("上下文", configurationDisplay(fields["contextWindow"]), "data_usage"),
                    ManagementLayer("输出上限", configurationDisplay(fields["maxTokens"]), "output"),
                    ManagementLayer("思考", if (fields["reasoning"] != true) "不支持" else configurationStrings(thinking["efforts"]).joinToString("，", transform = ::managementThinkingLabel), "lightbulb")))
                ManagementGroup("价格") {
                    ManagementLayers(listOf("input" to "输入", "output" to "输出", "cacheRead" to "缓存读取", "cacheWrite" to "缓存写入").map { (key, label) ->
                        val price = cost[key] as? Number
                        ManagementLayer(label, if (price == null) "未知" else if (price.toDouble() == 0.0) "免费" else "\$$price / 百万", "payments")
                    })
                }
            }
        }
    }
}
@Composable
internal fun ManagementCandidates(candidates: List<String>, state: AgentManagementState, onChange: (List<String>) -> Unit, kind: String? = null, readOnly: Boolean = false, maxCandidates: Int? = null) {
    var pickerIndex by remember { mutableStateOf<Int?>(null) }
    var picking by remember { mutableStateOf(false) }
    var thinkingIndex by remember { mutableStateOf<Int?>(null) }
    val latestCandidates by rememberUpdatedState(candidates)
    val latestChange by rememberUpdatedState(onChange)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        candidates.forEachIndexed { index, selector ->
            key(selector, candidates.take(index).count { it == selector }) {
                val (base, thinking) = managementSelectorParts(selector, state)
                val model = state.models.firstOrNull { it.key == base }
                val role = state.roles.firstOrNull { "@${it.id}" == base }
                var more by remember { mutableStateOf(false) }
                var rowHeight by remember { mutableIntStateOf(76) }
                var offset by remember { mutableFloatStateOf(0f) }
                val latestIndex by rememberUpdatedState(index)
                ManagementRow(model?.name ?: role?.let { "跟随${it.name}" } ?: base,
                    model?.let { if (it.available) it.provider else it.unavailableReason ?: "不可用" } ?: role?.model ?: role?.error ?: "高级选择器",
                    index = index, count = candidates.size, icon = "", lead = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!readOnly) Box(Modifier.size(28.dp, 44.dp).onSizeChanged { rowHeight = it.height.coerceAtLeast(1) * 76 / 44 }
                                .pointerInput(selector) {
                                    detectDragGestures(onDragEnd = { offset = 0f }, onDragCancel = { offset = 0f }, onDrag = { change, delta ->
                                        change.consume(); offset += delta.y
                                        val position = latestIndex
                                        val next = (position + if (offset > 0) 1 else -1).coerceIn(0, latestCandidates.lastIndex)
                                        if (abs(offset) >= rowHeight && next != position) {
                                            val moved = latestCandidates.toMutableList(); moved.add(next, moved.removeAt(position)); latestChange(moved); offset = 0f
                                        }
                                    })
                                }, contentAlignment = Alignment.Center) { Symbol("drag_indicator", tint = LocalAgentColors.current.onSurfaceVariant, size = 20.dp) }
                            Box(Modifier.size(30.dp).background(LocalAgentColors.current.surfaceContainerHigh, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Text("${index + 1}", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                            if (model != null) ModelIcon(model.id, model.name, Modifier.size(36.dp)) else Symbol(if (role != null) "label" else "code")
                        }
                    }, trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!readOnly) {
                                Box {
                                    AgentIconButton(if (thinking == null) "link" else "lightbulb", "思考强度：${managementThinkingLabel(thinking)}", { thinkingIndex = index })
                                    val efforts = model?.metadata?.get("thinking")?.let { configurationStrings(configurationMap(it)["efforts"]).takeIf(List<String>::isNotEmpty) }
                                    ManagementThinkingPicker(thinking, { thinkingIndex = null }, { value -> onChange(candidates.mapIndexed { i, old -> if (i == index) base + (value?.let { ":$it" } ?: "") else old }) }, efforts = efforts, expanded = thinkingIndex == index)
                                }
                                Box {
                                    AgentIconButton("more_vert", "候选操作", { more = true })
                                    ManagementMenu(more, { more = false }) {
                                        fun move(target: Int) { val next = candidates.toMutableList(); next.add(target, next.removeAt(index)); onChange(next); more = false }
                                        DropdownMenuItem(text = { Text("上移") }, leadingIcon = { Symbol("arrow_upward") }, enabled = index > 0, onClick = { move(index - 1) })
                                        DropdownMenuItem(text = { Text("下移") }, leadingIcon = { Symbol("arrow_downward") }, enabled = index < candidates.lastIndex, onClick = { move(index + 1) })
                                        DropdownMenuItem(text = { Text("替换") }, leadingIcon = { Symbol("swap_horiz") }, onClick = { more = false; pickerIndex = index; picking = true })
                                        DropdownMenuItem(text = { Text("删除", color = LocalAgentColors.current.error) }, leadingIcon = { Symbol("delete", tint = LocalAgentColors.current.error) }, onClick = { more = false; onChange(candidates.filterIndexed { i, _ -> i != index }) })
                                    }
                                }
                            } else Text(managementThinkingLabel(thinking), fontSize = 16.sp)
                        }
                    })
            }
        }
        if (candidates.isEmpty()) SettingsEmpty("没有候选", "playlist_add")
        if (!readOnly && (maxCandidates == null || candidates.size < maxCandidates)) TextButton({ pickerIndex = null; picking = true }) { Symbol("add", size = 20.dp); Spacer(Modifier.width(6.dp)); Text("添加候选", fontSize = 17.sp) }
    }
    if (picking) ManagementModelPicker(state, current = pickerIndex?.let { candidates.getOrNull(it) }, kind = kind, onDismiss = { picking = false }) { selection ->
        val index = pickerIndex
        if (index == null) onChange(candidates + selection) else onChange(candidates.mapIndexed { i, old -> if (i == index) selection else old })
    }
}

@Composable
internal fun ManagementSettingEditor(item: SettingItem, state: AgentManagementState, scope: ConfigurationScope, onDismiss: () -> Unit, onSave: (Any?) -> Unit) {
    val layer = if (scope == ConfigurationScope.PROJECT) state.projectValues else state.globalValues
    val canEdit = scope != ConfigurationScope.PROJECT || state.context.projectPath != null
    val layerValue = configurationValue(layer, item.id)
    var draft by remember(item.id, scope) { mutableStateOf(configurationJson(layerValue ?: item.value).let { if (it is JsonPrimitive && it.isString) it.content else it.toString() }) }
    var error by remember { mutableStateOf<String?>(null) }
    fun saveValue(value: Any?) { if (canEdit) onSave(value) }
    AgentSheet(item.title, onDismiss, actions = {}, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            ManagementBanner("正在编辑${scope.label}层，写入 ${if (scope == ConfigurationScope.PROJECT) ".omp/config.yml" else "~/.omp/agent/config.yml"}", if (scope == ConfigurationScope.PROJECT) "folder" else "public")
            SettingsLabel(if (layerValue == null) "本层未设置，下面是当前有效值" else "本层值")
            if (!canEdit) ManagementBanner("未打开项目，可查看来源，但不能修改项目设置。", "folder_off")
            when (item.type) {
                SettingType.BOOLEAN -> ManagementRow(item.title, if (draft == "true") "开启" else "关闭", "", trailing = { Switch(draft == "true", { draft = it.toString() }, enabled = canEdit) })
                SettingType.ENUM -> {
                    if (item.id == "defaultThinkingLevel") {
                        var thinking by remember { mutableStateOf(false) }
                        Box {
                            ManagementRow(managementThinkingLabel(draft), icon = "lightbulb", onClick = { thinking = true }, trailing = { Symbol("arrow_drop_down") })
                            ManagementThinkingPicker(draft, { thinking = false }, { draft = it ?: "off" }, inherit = false, efforts = item.options.map { it.value }, expanded = thinking)
                        }
                    } else Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        item.options.forEachIndexed { index, option -> ManagementRow(option.label, icon = "", onClick = if (canEdit) ({ draft = option.value }) else null, index = index, count = item.options.size, trailing = { if (draft == option.value) Symbol("check") }) }
                    }
                }
                SettingType.NUMBER -> {
                    ManagementField("数值", draft, { draft = it; error = null }, mono = true)
                    if (item.min != null || item.max != null) ManagementHint("范围：${item.min ?: "不限"} 至 ${item.max ?: "不限"}", "straighten")
                }
                SettingType.STRING -> ManagementField("值", draft, { draft = it; error = null }, multiline = draft.contains('\n') || item.id.contains("instructions", true) || item.id.contains("prompt", true))
                SettingType.ARRAY, SettingType.RECORD -> ManagementCollectionEditor(draft, item.type == SettingType.RECORD, canEdit, { draft = it; error = null })
            }
            ManagementGroup("来源与覆盖") {
            ManagementLayers(listOf(
                ManagementLayer("默认值", configurationDisplay(item.defaultValue), "tune"),
                ManagementLayer("全局", configurationDisplay(configurationValue(state.globalValues, item.id)), "public"),
                ManagementLayer("项目", if (state.context.projectPath == null) "未打开项目" else configurationDisplay(configurationValue(state.projectValues, item.id)), "folder"),
                ManagementLayer("有效值", configurationDisplay(item.value), "done_all", selected = true),
                ManagementLayer("来源", managementSourceLabel(state.provenance[item.id] ?: "default"), "layers")))
            }
            ManagementGroup("详情") { ManagementLayers(listOf(ManagementLayer("配置键", item.id, "tag"), ManagementLayer("类型", when (item.type) { SettingType.BOOLEAN -> "开关"; SettingType.ENUM -> "枚举"; SettingType.NUMBER -> "数值"; SettingType.STRING -> "文本"; SettingType.ARRAY -> "列表"; SettingType.RECORD -> "映射" }, "category"))) }
            error?.let { ManagementHint(it, "error", error = true) }
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton({ saveValue(null) }, enabled = canEdit) { Text("清除本层") }
                TextButton({ saveValue(item.defaultValue) }, enabled = canEdit) { Text("设为默认") }
                Spacer(Modifier.weight(1f))
                Button({
                    try {
                        val value: Any? = when (item.type) {
                            SettingType.ENUM, SettingType.STRING -> draft
                            SettingType.NUMBER -> (draft.toLongOrNull() ?: draft.toDoubleOrNull()?.takeIf { it.isFinite() })?.also { number ->
                                item.min?.let { require(number.toDouble() >= it) { "小于允许的最小值" } }; item.max?.let { require(number.toDouble() <= it) { "大于允许的最大值" } }
                            } ?: throw IllegalArgumentException("请输入有效数值")
                            else -> configurationFromJson(Json.parseToJsonElement(draft)).also { parsed ->
                                require(item.type != SettingType.ARRAY || parsed is List<*>) { "需要 JSON 列表" }; require(item.type != SettingType.RECORD || parsed is Map<*, *>) { "需要 JSON 对象" }
                            }
                        }
                        saveValue(value)
                    } catch (failure: Exception) { error = failure.message ?: "输入无效" }
                }, enabled = canEdit) { Text("完成") }
            }
        }
    }
}

/** Same shadow-safe animation layout as the composer popup, positioned at its trigger. */
@Composable
internal fun ManagementMenu(expanded: Boolean, onDismissRequest: () -> Unit, containerColor: Color = LocalAgentColors.current.surface, content: @Composable ColumnScope.() -> Unit) {
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = expanded
    val motion = rememberTransition(visible, label = "management-menu")
    val scale by motion.animateFloat(transitionSpec = { if (targetState) tween(450, easing = EmphDecelEasing) else tween(200, easing = EmphAccelEasing) }, label = "scale") { if (it) 1f else .7f }
    val opacity by motion.animateFloat(transitionSpec = { if (targetState) tween(250) else tween(150) }, label = "opacity") { if (it) 1f else 0f }
    val density = LocalDensity.current
    var pivot by remember { mutableStateOf(TransformOrigin(.5f, 0f)) }
    val position = remember(density) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val shadow = with(density) { 32.dp.roundToPx() }
                val inset = with(density) { 12.dp.roundToPx() }
                val gap = with(density) { 4.dp.roundToPx() }
                val width = popupContentSize.width - shadow * 2
                val height = popupContentSize.height - shadow * 2
                val x = (anchorBounds.right - width).coerceIn(inset, (windowSize.width - width - inset).coerceAtLeast(inset)) - shadow
                val below = anchorBounds.bottom + gap + height <= windowSize.height - inset
                val y = (if (below) anchorBounds.bottom + gap else (anchorBounds.top - height - gap).coerceAtLeast(inset)) - shadow
                pivot = TransformOrigin(((anchorBounds.left + anchorBounds.width / 2f - x) / popupContentSize.width.coerceAtLeast(1)).coerceIn(0f, 1f), if (below) 0f else 1f)
                return IntOffset(x, y)
            }
        }
    }
    if (visible.currentState || visible.targetState) Popup(position, onDismissRequest, properties = PopupProperties(focusable = true, clippingEnabled = false)) {
        Box(Modifier.graphicsLayer { scaleX = scale; scaleY = scale; alpha = opacity; transformOrigin = pivot; clip = false }.padding(32.dp)) {
            Surface(color = LocalAgentColors.current.surface, shape = RoundedCornerShape(24.dp), shadowElevation = 12.dp) {
                Column(Modifier.widthIn(min = 220.dp, max = 320.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(8.dp), content = content)
            }
        }
    }
}

@Composable
internal fun ManagementMenuItem(text: @Composable () -> Unit, onClick: () -> Unit, selected: Boolean = false, enabled: Boolean = true, leadingIcon: (@Composable () -> Unit)? = null, trailingIcon: (@Composable () -> Unit)? = null) {
    val c = LocalAgentColors.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (selected) c.secondaryContainer else Color.Transparent).clickable(enabled = enabled, onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        CompositionLocalProvider(LocalContentColor provides if (!enabled) c.outline else c.onSurface) {
            leadingIcon?.invoke()
            Box(Modifier.weight(1f)) { text() }
            trailingIcon?.invoke()
        }
    }
}

@Composable
internal fun ManagementStepper(steps: List<Pair<String, String>>, current: String) {
    val c = LocalAgentColors.current
    val selected = steps.indexOfFirst { it.first == current }.coerceAtLeast(0)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.forEachIndexed { index, (_, title) ->
            if (index > 0) Box(Modifier.weight(1f).height(2.dp).background(c.surfaceContainerHigh, CircleShape))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(32.dp).background(if (index == selected) c.primary else if (index < selected) c.primaryContainer else c.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
                    if (index < selected) Symbol("check", size = 20.dp, tint = c.onPrimaryContainer)
                    else Text("${index + 1}", color = if (index == selected) c.onPrimary else c.onSurface, fontSize = 16.sp)
                }
                Text(title, fontSize = 16.sp, color = if (index == selected) c.onSurface else c.onSurfaceVariant, fontWeight = if (index == selected) FontWeight.Medium else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun ManagementCollectionEditor(draft: String, record: Boolean, enabled: Boolean, onChange: (String) -> Unit) {
    val parsed = remember(draft) { runCatching { Json.parseToJsonElement(draft) }.getOrNull() }
    val entries = if (record) (parsed as? JsonObject)?.entries?.map { it.key to it.value }.orEmpty() else (parsed as? JsonArray)?.mapIndexed { index, value -> index.toString() to value }.orEmpty()
    var newKey by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<String?>(null) }
    var editedValue by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun replace(key: String, value: JsonElement?) {
        if (record) { val map = (parsed as? JsonObject).orEmpty().toMutableMap(); if (value == null) map.remove(key) else map[key] = value; onChange(JsonObject(map).toString()) }
        else { val list = (parsed as? JsonArray).orEmpty().toMutableList(); val index = key.toIntOrNull(); if (index != null && index in list.indices) { if (value == null) list.removeAt(index) else list[index] = value } else if (value != null) list.add(value); onChange(JsonArray(list).toString()) }
    }
    entries.forEachIndexed { index, (key, value) ->
        ManagementRow(if (record) key else configurationDisplay(configurationFromJson(value)), if (record) configurationDisplay(configurationFromJson(value)) else "", "", onClick = { editing = key; editedValue = if (value is JsonPrimitive && value.isString) value.content else value.toString() }, index = index, count = entries.size, trailing = { AgentIconButton("remove_circle", "移除", { replace(key, null) }, enabled = enabled) })
    }
    if (entries.isEmpty()) SettingsEmpty(if (record) "空映射" else "空列表", "playlist_add")
    ManagementField(if (record) "键" else "新条目", newKey, { newKey = it }, mono = true, readOnly = !enabled)
    if (record) ManagementField("值", newValue, { newValue = it }, mono = true, readOnly = !enabled)
    TextButton({ replace(if (record) newKey.trim() else "new", JsonPrimitive(if (record) newValue else newKey)); newKey = ""; newValue = "" }, enabled = enabled && newKey.isNotBlank()) { Symbol("add"); Text("添加") }
    editing?.let { key -> AgentSheet(if (record) key else "编辑条目", { editing = null }) {
        ManagementField("值", editedValue, { editedValue = it; error = null }, mono = true, multiline = entries.firstOrNull { it.first == key }?.second !is JsonPrimitive, readOnly = !enabled)
        error?.let { ManagementHint(it, "error", error = true) }
        Button({ try { val original = entries.firstOrNull { it.first == key }?.second; replace(key, if (original is JsonPrimitive && original.isString) JsonPrimitive(editedValue) else Json.parseToJsonElement(editedValue)); editing = null } catch (failure: Exception) { error = failure.message ?: "格式错误" } }, enabled = enabled) { Text("完成") }
    } }
}
