@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.wawlink.app

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.wawlink.protocol.AgentResourceRepository
import dev.wawlink.ui.EmphAccelEasing
import dev.wawlink.ui.EmphDecelEasing
import dev.wawlink.ui.EmphEasing
import dev.wawlink.ui.Symbol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun pluginMap(value: Any?): Map<String, Any?> = (value as? Map<*, *>)?.entries?.mapNotNull { (k, v) -> (k as? String)?.let { it to v } }?.toMap().orEmpty()
internal fun pluginMaps(value: Any?): List<Map<String, Any?>> = (value as? List<*>)?.map(::pluginMap).orEmpty()
internal fun pluginStrings(value: Any?): List<String> = when (value) { is String -> listOf(value); is List<*> -> value.filterIsInstance<String>(); else -> emptyList() }
internal fun Map<String, Any?>.pluginText(key: String, fallback: String = ""): String = this[key]?.toString() ?: fallback
internal fun pluginDisplay(value: Any?): String = when (value) { null -> "未设置"; true -> "开启"; false -> "关闭"; is String -> value.ifEmpty { "空字符串" }; is List<*> -> if (value.isEmpty()) "空列表" else value.joinToString("，"); else -> value.toString() }
internal fun pluginJson(value: Any?, level: Int = 0): String {
    val indent = "  ".repeat(level)
    fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
    return when (value) {
        null -> "null"
        is String -> quote(value)
        is Boolean, is Number -> value.toString()
        is Map<*, *> -> if (value.isEmpty()) "{}" else value.entries.joinToString(",\n", "{\n", "\n$indent}") { "$indent  ${quote(it.key.toString())}: ${pluginJson(it.value, level + 1)}" }
        is Iterable<*> -> value.joinToString(", ", "[", "]") { pluginJson(it, level + 1) }
        else -> quote(value.toString())
    }
}

internal class PluginActions(private val repository: AgentResourceRepository, private val scope: CoroutineScope) {
    var snapshot by mutableStateOf<Map<String, Any?>>(emptyMap()); private set
    var markets by mutableStateOf<List<Map<String, Any?>>>(emptyList()); private set
    var detail by mutableStateOf<Map<String, Any?>>(emptyMap()); private set
    var updates by mutableStateOf<Map<String, Any?>>(emptyMap()); private set
    var doctor by mutableStateOf<List<Map<String, Any?>>>(emptyList()); private set
    var result by mutableStateOf<Map<String, Any?>>(emptyMap()); private set
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf<String?>(null); private set
    var canCancel by mutableStateOf(false); private set
    var pendingRestart by mutableStateOf(false); private set
    var notice by mutableStateOf<String?>(null)
    private var selected: String? = null
    private var operationId: String? = null
    private val operationMutex = Mutex()
    val project get() = repository.context.projectPath != null
    val fixedScope get() = if (project) "project" else "user"
    val items get() = pluginMaps(snapshot["plugins"])
    suspend fun request(operation: String, args: Map<String, Any?> = emptyMap()): Map<String, Any?> = repository.request("plugins.$operation", args)
    private suspend fun refresh() {
        snapshot = request("snapshot")
        if (selected != null) detail = request("detail", mapOf("id" to selected))
    }
    fun load(initial: String? = null) = run("正在读取插件") { if(initial != null) selected = initial; refresh() }
    fun select(id: String) { selected = id; detail = emptyMap(); run("正在读取插件详情") { detail = request("detail", mapOf("id" to id)) } }
    fun loadMarkets() = run("正在读取市场缓存") { markets = pluginMaps(request("markets")["markets"]) }
    fun check(id: String? = null) = run("正在检查更新") { updates = request("checkUpdates", if (id == null) emptyMap() else mapOf("id" to id)); notice = updates.pluginText("note") }
    fun diagnose(fix: Boolean = false) = run(if (fix) "正在执行修复" else "正在诊断", reread = fix) {
        canCancel = true
        val response = request("doctor", mapOf("fix" to fix, "confirmed" to fix, "operationId" to operationId))
        doctor = pluginMaps(response["checks"]); if (fix) pendingRestart = true
    }
    fun mutate(operation: String, args: Map<String, Any?> = emptyMap(), complete: (Map<String, Any?>) -> Unit = {}) = run("正在${when(operation) { "install" -> "安装"; "upgrade", "upgradeMarket" -> "升级"; "uninstall" -> "卸载"; else -> "保存" }}", reread = true) {
        canCancel = operation in listOf("install", "uninstall", "upgrade", "upgradeMarket", "marketAdd", "marketUpdate", "marketRemove")
        val response = request(operation, args + mapOf("scope" to fixedScope, "operationId" to operationId))
        result = response
        if (operation == "uninstall" && selected == args["id"]) { selected = null; detail = emptyMap() }
        if (response["changed"] == true) pendingRestart = true
        if (operation.startsWith("market")) markets = pluginMaps(request("markets")["markets"])
        notice = if (response["changed"] == true) "已保存；运行中的会话未重载，新会话生效" else "操作完成"
        complete(response)
    }
    fun validate(id: String) = run("正在校验设置") { result = request("validate", mapOf("id" to id)); notice = if (result["valid"] == true) "已声明字段的类型和范围有效；不检查必填项和未知键" else pluginJson(result["errors"]) }
    fun cancel() { val id = operationId ?: return; scope.launch { try { val response = request("cancel", mapOf("operationId" to id)); notice = response.pluginText("note") } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.message } } }
    fun run(label: String, reread: Boolean = false, block: suspend () -> Unit) {
        if (busy == null) busy = label
        scope.launch {
            operationMutex.withLock {
                busy = label; error = null; operationId = "plugin-${System.nanoTime()}"
                var interrupted = false
                try { block() }
                catch (cancelled: CancellationException) { interrupted = true; throw cancelled }
                catch (failure: Exception) { error = failure.message ?: "原生插件操作失败" }
                finally {
                    try {
                        if (reread && !interrupted) {
                            // Failed native operations may already have changed files; cancelled owners must not restart a bridge.
                            try { snapshot = request("snapshot"); if (selected != null) detail = request("detail", mapOf("id" to selected)) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { if (error == null) error = failure.message }
                        }
                    } finally { operationId = null; canCancel = false; busy = null }
                }
            }
        }
    }
}

@Composable
internal fun PluginIcon(icon: String, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) { Symbol(icon, contentDescription = label) }
}

@Composable
internal fun PluginPage(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}, fab: (@Composable () -> Unit)? = null, footer: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    val bar by animateColorAsState(if (scroll.value > 4) Color(0xFFF0F4F9) else Color.White, tween(300, easing = EmphEasing))
    Box(Modifier.fillMaxSize().background(Color.White)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(64.dp).background(bar).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                PluginIcon("arrow_back", "返回", onClick = onBack)
                Text(title, Modifier.weight(1f).padding(start = 4.dp), fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                actions()
            }
            Column(Modifier.weight(1f).verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 140.dp)) { content() }
        }
        if (fab != null) Box(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 20.dp)) { fab() }
        if (footer != null) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)) { footer() }
    }
}

@Composable
internal fun PluginGroup(title: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 24.dp, bottom = 10.dp).heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = MaterialTheme.colorScheme.primary, fontSize = 16.sp, fontWeight = FontWeight.Medium); action?.invoke()
    }
}

@Composable
internal fun PluginRow(title: String, subtitle: String = "", icon: String = "extension", index: Int = 0, count: Int = 1, onClick: (() -> Unit)? = null, error: Boolean = false, enabled: Boolean = true, avatar: String? = null, trailing: (@Composable () -> Unit)? = null) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(if (index == 0) 24.dp else 6.dp, if (index == 0) 24.dp else 6.dp, if (index == count - 1) 24.dp else 6.dp, if (index == count - 1) 24.dp else 6.dp)
    Row(Modifier.fillMaxWidth().padding(bottom = 3.dp).clip(shape).background(Color(0xFFF8FAFD)).then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier).heightIn(min = 72.dp).padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(44.dp).background(if (avatar != null) c.primary else c.primaryContainer, if (avatar != null) RoundedCornerShape(14.dp) else CircleShape), contentAlignment = Alignment.Center) {
            if (avatar != null) Text(avatar.take(1).uppercase(), color = c.onPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold) else Symbol(icon, tint = if (error) c.error else c.onPrimaryContainer)
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (enabled) c.onSurface else c.onSurfaceVariant)
            if (subtitle.isNotEmpty()) Text(subtitle, fontSize = 16.sp, color = if (error) c.error else c.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) trailing() else if (onClick != null) Symbol("chevron_right", tint = c.onSurfaceVariant)
    }
}

@Composable
internal fun PluginHint(text: String, icon: String = "info", error: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Symbol(icon, size = 20.dp, tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, fontSize = 16.sp, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 24.sp)
    }
}

@Composable
internal fun PluginBanner(text: String, error: Boolean = false, warning: Boolean = false, icon: String = "info", content: (@Composable ColumnScope.() -> Unit)? = null) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).background(if (error) c.errorContainer else if (warning) Color(0xFFFFDCC1) else Color(0xFFF0F4F9), RoundedCornerShape(24.dp)).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Symbol(icon, tint = if (error) c.error else if (warning) Color(0xFF8A5000) else c.primary)
        Column(Modifier.weight(1f)) { Text(text, fontSize = 17.sp, lineHeight = 26.sp); content?.invoke(this) }
    }
}

@Composable
internal fun PluginSegments(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, enabled: (String) -> Boolean = { true }) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(24.dp)).border(1.dp, c.outline, RoundedCornerShape(24.dp))) {
        options.forEachIndexed { index, (id, label) ->
            Row(Modifier.weight(1f).heightIn(min = 52.dp).background(if (id == selected) c.primaryContainer else Color.Transparent).clickable(enabled(id)) { onSelect(id) }.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                AnimatedVisibility(id == selected, enter = expandHorizontally(tween(350, easing = EmphEasing)) + fadeIn(), exit = shrinkHorizontally() + fadeOut()) { Symbol("check", size = 20.dp) }
                Text(label, fontSize = 16.sp, color = if (enabled(id)) c.onSurface else c.outline, maxLines = 1)
            }
            if (index != options.lastIndex) Box(Modifier.width(1.dp).height(52.dp).background(c.outline))
        }
    }
}

@Composable
internal fun PluginSearch(value: String, onValue: (String) -> Unit, placeholder: String) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp).height(56.dp).background(Color(0xFFF0F4F9), RoundedCornerShape(28.dp)).padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Symbol("search", tint = c.onSurfaceVariant)
        BasicTextField(value, onValue, Modifier.weight(1f), singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 18.sp, color = c.onSurface), cursorBrush = SolidColor(c.primary), decorationBox = { inner -> if (value.isEmpty()) Text(placeholder, color = c.onSurfaceVariant, fontSize = 18.sp); inner() })
        if (value.isNotEmpty()) PluginIcon("close", "清除搜索") { onValue("") }
    }
}

@Composable
internal fun PluginField(label: String, value: String, onValue: (String) -> Unit, secret: Boolean = false, multiline: Boolean = false, trailing: (@Composable () -> Unit)? = null) {
    if (label.isNotEmpty()) Text(label, Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp), fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(value, onValue, Modifier.fillMaxWidth(), singleLine = !multiline, minLines = if (multiline) 4 else 1, shape = RoundedCornerShape(16.dp), textStyle = LocalTextStyle.current.copy(fontSize = 16.sp, fontFamily = FontFamily.Monospace), visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None, trailingIcon = trailing)
}

@Composable
internal fun PluginKeyValues(rows: List<Pair<String, Any?>>, effective: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        rows.forEachIndexed { index, (key, value) ->
            val shape = RoundedCornerShape(if(index == 0) 20.dp else 6.dp, if(index == 0) 20.dp else 6.dp, if(index == rows.lastIndex) 20.dp else 6.dp, if(index == rows.lastIndex) 20.dp else 6.dp)
            Row(Modifier.fillMaxWidth().background(if(effective && index == rows.lastIndex) MaterialTheme.colorScheme.primaryContainer else Color(0xFFF8FAFD), shape).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(key, Modifier.widthIn(min = 84.dp), fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(pluginDisplay(value), Modifier.weight(1f), fontSize = 16.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
internal fun PluginEmpty(text: String, icon: String = "extension_off", action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) { Symbol(icon, size = 48.dp, tint = MaterialTheme.colorScheme.outline); Text(text, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); action?.invoke() }
}

@Composable
internal fun PluginSheet(title: String, full: Boolean = false, onClose: () -> Unit, footer: (@Composable RowScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val transition = remember { MutableTransitionState(false) }
    val coroutine = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val latestClose by rememberUpdatedState(onClose)
    val scrimAlpha by animateFloatAsState(if(transition.targetState) .32f else 0f, tween(350, easing = EmphEasing))
    fun close() {
        if(closing) return
        closing = true; transition.targetState = false
        coroutine.launch { kotlinx.coroutines.delay(350); latestClose() }
    }
    LaunchedEffect(Unit) { transition.targetState = true }
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrimAlpha)).clickable(onClick = ::close))
            AnimatedVisibility(transition, modifier = Modifier.align(Alignment.BottomCenter), enter = slideInVertically(tween(550, easing = EmphDecelEasing)) { it } + fadeIn(tween(350)), exit = slideOutVertically(tween(350, easing = EmphAccelEasing)) { it }) {
                Surface(Modifier.fillMaxWidth().heightIn(max = maxHeight * .9f).then(if (full) Modifier.height(maxHeight * .9f) else Modifier), shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = Color.White) {
                    Column {
                        Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp, bottom = 6.dp).size(32.dp, 4.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = .5f), RoundedCornerShape(2.dp)))
                        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) { Text(title, Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Medium); PluginIcon("close", "关闭", onClick = ::close) }
                        Column(Modifier.weight(1f, fill = full).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp)) { content() }
                        if (footer != null) { HorizontalDivider(color = Color(0xFFE9EEF6)); Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { footer() } }
                    }
                }
            }
        }
    }
}
