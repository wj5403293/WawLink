@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package dev.wawlink.app

import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.wawlink.runtime.*
import dev.wawlink.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.math.pow

internal data class FeatureBuiltin(val id: String, val title: String, val description: String, val icon: String)
internal val featureBuiltins = listOf(
    FeatureBuiltin("terminal", "终端", "进入容器的交互式终端", "terminal"),
    FeatureBuiltin("files", "文件", "浏览和管理容器文件", "folder"),
    FeatureBuiltin("rules", "规则", "查看与编辑容器用户规则", "rule"),
    FeatureBuiltin("plugins", "插件", "安装、配置与管理用户插件", "extension"),
    FeatureBuiltin("skills", "技能", "管理 Skill 与原生技能包", "psychology"),
)
internal fun featureCardTitles(catalog: ContainerFeatureCatalog?): Map<String, String> =
    featureBuiltins.associate { "builtin/${it.id}" to it.title } + catalog?.features.orEmpty().associate { "extension/${it.id}" to it.title }

private data class CardEntry(val key: String, val builtin: FeatureBuiltin? = null, val feature: ContainerFeature? = null) {
    val title get() = builtin?.title ?: feature!!.title
    val description get() = builtin?.description ?: feature!!.description
    val icon get() = builtin?.icon ?: feature!!.icon
}
private data class CardDrag(val key: String, val pointer: Offset, val grab: Offset, val itemOffset: Offset, val size: IntSize)

@Composable
internal fun ContainerFeaturesScreen(
    app: AppCoordinator,
    service: ContainerFeatureService,
    containerId: String?,
    onBuiltin: (String) -> Unit,
    onExtension: (ContainerFeature) -> Unit,
    navigation: HostNavigation? = null,
) {
    val containers by app.runtime.containers.collectAsState()
    val states by service.states.collectAsState()
    val preferences by app.featureCards.state.collectAsState()
    val preferenceError by app.featureCards.error.collectAsState()
    val container = containers.firstOrNull { it.config.id == containerId }
    var catalog by remember(containerId) { mutableStateOf<ContainerFeatureCatalog?>(null) }
    var error by remember(containerId) { mutableStateOf<String?>(null) }
    var refresh by remember(containerId) { mutableStateOf(0) }
    var loading by remember(containerId) { mutableStateOf(false) }
    var selected by remember(containerId) { mutableStateOf(emptySet<String>()) }
    var colorDialog by remember(containerId) { mutableStateOf(false) }
    var uninstallDialog by remember(containerId) { mutableStateOf(false) }
    var working by remember(containerId) { mutableStateOf(false) }
    var drag by remember(containerId) { mutableStateOf<CardDrag?>(null) }
    var localOrder by remember(containerId) { mutableStateOf<List<String>?>(null) }
    val grid = rememberLazyGridState()
    val css = containerFeatureThemeCss()
    SideEffect { service.setTheme(css) }
    ContainerFeatureIcons(service)
    val entries = remember(catalog) {
        featureBuiltins.map { CardEntry("builtin/${it.id}", builtin = it) } + catalog?.features.orEmpty().map { CardEntry("extension/${it.id}", feature = it) }
    }
    val byKey = remember(entries) { entries.associateBy { it.key } }
    val layout = preferences[containerId] ?: FeatureCardLayout()
    val order = (localOrder ?: layout.order).filter { it in byKey } + entries.map { it.key }.filterNot { it in (localOrder ?: layout.order) }
    val visible = order.filterNot { it in layout.hidden }
    val customSelection = selected.mapNotNull { byKey[it]?.feature }
    fun report(failure: Exception) { error = failure.message ?: "功能操作失败" }
    fun persist(action: suspend () -> Unit) {
        app.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { report(failure); app.showError(failure) }
        }
    }
    fun finishDrag() {
        if (drag == null) return
        drag = null
        val saved = localOrder
        if (containerId != null && saved != null) persist {
            try { app.featureCards.reorder(containerId, saved) }
            finally { if (drag == null && localOrder == saved) localOrder = null }
        }
    }
    fun clearSelection(): Boolean {
        if (selected.isEmpty()) return false
        finishDrag(); selected = emptySet(); colorDialog = false; uninstallDialog = false
        return true
    }
    SideEffect { navigation?.onBack = ::clearSelection }
    DisposableEffect(navigation) { onDispose { navigation?.onBack = null } }
    LaunchedEffect(containerId, refresh) {
        if (containerId == null) return@LaunchedEffect
        loading = true; error = null
        try { catalog = service.catalog(containerId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { report(failure) }
        finally { loading = false }
    }
    LaunchedEffect(containerId, entries) {
        if (containerId != null) {
            try { app.featureCards.reconcile(containerId, entries.map { it.key }) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { report(failure) }
        }
        selected = selected.intersect(byKey.keys)
    }
    fun moveOver(pointer: Offset) {
        val current = drag ?: return
        val target = grid.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            val key = item.key as? String
            key != current.key && key in visible &&
                pointer.x in (item.offset.x + item.size.width * .16f)..(item.offset.x + item.size.width * .84f) &&
                pointer.y in (item.offset.y + item.size.height * .16f)..(item.offset.y + item.size.height * .84f)
        }?.key as? String ?: return
        val next = order.toMutableList()
        val from = next.indexOf(current.key); val to = next.indexOf(target)
        if (from >= 0 && to >= 0 && from != to) { next.add(to, next.removeAt(from)); localOrder = next }
    }
    val onMove by rememberUpdatedState<(Offset) -> Unit> { delta ->
        drag = drag?.let { it.copy(pointer = it.pointer + delta) }
        drag?.let { moveOver(it.pointer) }
    }
    val onFinish by rememberUpdatedState<() -> Unit> { finishDrag() }
    // The same grid-level gesture owner survives item moves and consumes HTML input before WebView.
    val onHit by rememberUpdatedState<(Offset) -> Pair<String, Boolean>?> { point ->
        grid.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            item.key in visible && point.x >= item.offset.x && point.x <= item.offset.x + item.size.width &&
                point.y >= item.offset.y && point.y <= item.offset.y + item.size.height
        }?.let { (it.key as String) to (byKey[it.key]?.feature?.cardHtml != null) }
    }
    val selectionMode by rememberUpdatedState(selected.isNotEmpty())
    val onTap by rememberUpdatedState<(String) -> Unit> { key ->
        if (!working && container != null) {
            if (selected.isNotEmpty()) selected = if (key in selected) selected - key else selected + key
            else byKey[key]?.let { entry -> entry.builtin?.let { onBuiltin(it.id) } ?: entry.feature?.let(onExtension) }
        }
    }
    val onHold by rememberUpdatedState<(String, Offset, Boolean) -> Unit> { key, point, wasSelecting ->
        if (!working && container != null) {
            selected = selected + key
            if (wasSelecting) grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.let {
                val origin = Offset(it.offset.x.toFloat(), it.offset.y.toFloat())
                localOrder = order
                drag = CardDrag(key, point, point - origin, origin, it.size)
            }
        }
    }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val onAutoMove by rememberUpdatedState<(Offset) -> Unit> { moveOver(it) }
    LaunchedEffect(drag?.key) {
        if (drag == null) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (drag != null) {
            val now = withFrameNanos { it }
            val seconds = ((now - previous) / 1_000_000_000f).coerceAtMost(.05f)
            previous = now
            val current = drag ?: break
            val info = grid.layoutInfo
            val edge = 88 * density
            val top = info.viewportStartOffset.toFloat().coerceAtLeast(0f)
            val bottom = info.viewportEndOffset.toFloat()
            val penetration = when {
                current.pointer.y < top + edge -> -((top + edge - current.pointer.y) / edge).coerceIn(0f, 1f)
                current.pointer.y > bottom - edge -> ((current.pointer.y - bottom + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (penetration != 0f) {
                val amount = grid.scrollBy((if (penetration < 0) -1 else 1) * abs(penetration).pow(2) * 1000 * density * seconds)
                drag = drag?.let { it.copy(itemOffset = it.itemOffset - Offset(0f, amount)) }
                drag?.let { onAutoMove(it.pointer) }
            }
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        if (selected.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("已选择 ${selected.size} 项", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        TextButton({ clearSelection() }, enabled = !working) { Text("完成") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton({ val keys = selected; persist { app.featureCards.hide(requireNotNull(containerId), keys); selected = emptySet() } }, enabled = !working) { Symbol("visibility_off", size = 20.dp); Text("隐藏") }
                        TextButton({ colorDialog = true }, enabled = !working) { Symbol("palette", size = 20.dp); Text("颜色") }
                        TextButton({ uninstallDialog = true }, enabled = !working && customSelection.isNotEmpty()) { Symbol("delete", size = 20.dp); Text("卸载") }
                    }
                    if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(container?.config?.name ?: "尚未选择容器", Modifier.weight(1f), fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                AgentIconButton("refresh", "刷新功能", { refresh++ }, enabled = !loading && container != null)
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2), state = grid,
            modifier = Modifier.fillMaxSize().featureCardGestures(containerId, { onHit(it) }, { selectionMode }, { onTap(it) }, { key, point, selecting -> onHold(key, point, selecting) }, { onMove(it) }, { onFinish() }),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 28.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(visible, key = { it }) { key ->
                val entry = byKey.getValue(key)
                val feature = entry.feature
                val state = feature?.let { states[service.stateKey(containerId.orEmpty(), it.id)] } ?: ContainerFeatureState()
                val currentDrag = drag?.takeIf { it.key == key }
                val itemOffset = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.offset
                SideEffect {
                    if (currentDrag != null && itemOffset != null) {
                        val origin = Offset(itemOffset.x.toFloat(), itemOffset.y.toFloat())
                        if (drag?.key == key && drag?.itemOffset != origin) drag = drag?.copy(itemOffset = origin)
                    }
                }
                val pin = LocalPinnableContainer.current
                DisposableEffect(currentDrag != null, pin) {
                    val handle = if (currentDrag != null) pin?.pin() else null
                    onDispose { handle?.release() }
                }
                val color = layout.colors[key] ?: feature?.color
                FeatureCardTile(
                    entry, state, service, containerId, container?.running == true, selected.isNotEmpty(), key in selected, color, css,
                    Modifier.fillMaxWidth().zIndex(if (currentDrag != null) 2f else 0f)
                        .animateItem(placementSpec = if (currentDrag != null) null else tween(420, easing = EmphEasing))
                        .graphicsLayer {
                            if (currentDrag != null) {
                                val origin = itemOffset?.let { Offset(it.x.toFloat(), it.y.toFloat()) } ?: currentDrag.itemOffset
                                val delta = currentDrag.pointer - currentDrag.grab - origin
                                translationX = delta.x; translationY = delta.y; scaleX = 1.035f; scaleY = 1.035f; shadowElevation = 12.dp.toPx()
                            }
                        },
                    onAction = { action ->
                        if (feature != null && containerId != null && selected.isEmpty()) when (action) {
                            "open" -> onExtension(feature)
                            "start" -> persist { service.start(containerId, feature) }
                            "stop" -> persist { service.stop(containerId, feature.id) }
                            else -> error = "未知卡片操作：$action"
                        }
                    }, onError = { message -> error = "${entry.title}：$message" }, onClick = { onTap(key) }, onSelect = { if (container != null && !working) selected = selected + key },
                    nativeGestures = FeatureCardNativeGestures(
                        selectionMode = selected.isNotEmpty(),
                        onTap = { if (selected.isNotEmpty()) onTap(key) },
                        onHold = { normalized ->
                            grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.let { item ->
                                onHold(key, Offset(item.offset.x + normalized.x * item.size.width, item.offset.y + normalized.y * item.size.height), selected.isNotEmpty())
                            }
                        },
                        onDrag = { normalized ->
                            drag?.takeIf { it.key == key }?.let { current -> onMove(Offset(normalized.x * current.size.width, normalized.y * current.size.height)) }
                        },
                        onEnd = { onFinish() },
                    ),
                )
            }
            (listOfNotNull(error, preferenceError) + catalog?.errors.orEmpty()).distinct().forEachIndexed { index, message ->
                item(key = "error/$index", span = { GridItemSpan(2) }) { FeatureCardError(message) }
            }
        }
    }
    if (colorDialog) FeatureCardColorDialog(
        onDismiss = { colorDialog = false },
        onColor = { color -> val keys = selected; persist { app.featureCards.setColor(requireNotNull(containerId), keys, color); colorDialog = false } },
    )
    if (uninstallDialog) AlertDialog(
        onDismissRequest = { if (!working) uninstallDialog = false },
        title = { Text("卸载 ${customSelection.size} 个自定义功能？") },
        text = { Text("将停止功能并删除其目录及内容，无法撤销。内置功能不会被卸载。\n\n" + customSelection.joinToString("\n") { it.title }) },
        confirmButton = { TextButton({
            val features = customSelection.toList()
            working = true
            persist {
                try {
                    for (feature in features) {
                        service.uninstall(requireNotNull(containerId), feature)
                        app.featureCards.remove(containerId, "extension/${feature.id}")
                        catalog = catalog?.let { it.copy(features = it.features.filterNot { item -> item.id == feature.id }) }
                        selected = selected - "extension/${feature.id}"
                    }
                    uninstallDialog = false
                } finally { working = false }
            }
        }, enabled = !working && customSelection.isNotEmpty()) { Text("卸载", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ uninstallDialog = false }, enabled = !working) { Text("取消") } },
    )
}

/** Observe at Initial so a trusted HTML card cannot steal selection/reorder gestures. */
private fun Modifier.featureCardGestures(
    containerId: String?, hit: (Offset) -> Pair<String, Boolean>?, selecting: () -> Boolean,
    tap: (String) -> Unit, hold: (String, Offset, Boolean) -> Unit, move: (Offset) -> Unit, finish: () -> Unit,
) = pointerInput(containerId) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val card = hit(down.position) ?: return@awaitEachGesture
        val wasSelecting = selecting()
        var cancelled = false
        var released = false
        var latest = down.position
        val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                latest = change.position
                if ((latest - down.position).getDistance() > viewConfiguration.touchSlop || event.changes.size > 1) { cancelled = true; break }
                if (!change.pressed) {
                    released = true
                    if (wasSelecting) change.consume()
                    else if (!card.second) {
                        // Let the native start/stop button handle its own tap before card-open.
                        val final = awaitPointerEvent(PointerEventPass.Final).changes.firstOrNull { it.id == down.id }
                        if (final == null || final.isConsumed) cancelled = true else final.consume()
                    }
                    break
                }
            }
            true
        }
        if (ended == true) {
            if (released && !cancelled && (wasSelecting || !card.second)) tap(card.first)
        } else {
            hold(card.first, latest, wasSelecting)
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    if (!change.pressed) break
                    if (wasSelecting) move(change.position - latest)
                    latest = change.position
                }
            } finally { finish() }
        }
    }
}

@Composable
private fun FeatureCardTile(
    entry: CardEntry, state: ContainerFeatureState, service: ContainerFeatureService, containerId: String?, containerRunning: Boolean,
    selectionMode: Boolean, selected: Boolean, color: String?, css: String, modifier: Modifier,
    onAction: (String) -> Unit, onError: (String) -> Unit, onClick: () -> Unit, onSelect: () -> Unit,
    nativeGestures: FeatureCardNativeGestures,
) {
    val feature = entry.feature
    var memory by remember(containerId, feature?.id) { mutableStateOf<Long?>(null) }
    var htmlError by remember(feature?.cardHtml) { mutableStateOf<String?>(null) }
    LaunchedEffect(containerId, feature?.id, feature?.showMemory, state.status, containerRunning) {
        memory = null
        if (containerId != null && feature?.showMemory == true && containerRunning && state.status == ContainerFeatureStatus.RUNNING) {
            while (true) {
                memory = try { service.memoryBytes(containerId, feature.id) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                delay(2000)
            }
        }
    }
    val background = color?.takeIf { FeatureCardPreferences.validColor(it) }?.let { Color(0xFF000000L or it.drop(1).toLong(16)) } ?: MaterialTheme.colorScheme.surfaceContainerLow
    val foreground = if (color == null) MaterialTheme.colorScheme.onSurface else if (background.luminance() > .45f) Color(0xFF1F1F1F) else Color.White
    val shape = RoundedCornerShape(24.dp)
    val html = feature?.cardHtml
    val failure = htmlError ?: state.error
    Box(modifier.heightIn(min = (if (html != null) feature?.cardHeight ?: 120 else 120).dp).clip(shape).background(background)
        .border(if (selected) 3.dp else 0.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
        .semantics(mergeDescendants = html == null || selectionMode) {
            contentDescription = entry.title
            if (selectionMode) this.selected = selected
            onClick(label = if (selectionMode) "选择${entry.title}" else "打开${entry.title}") { onClick(); true }
            onLongClick(label = "选择${entry.title}") { onSelect(); true }
        }) {
        if (html != null) {
            val data = buildJsonObject {
                put("id", feature.id); put("status", state.status.name.lowercase()); put("containerRunning", containerRunning)
                put("memoryBytes", memory?.let(::JsonPrimitive) ?: JsonNull)
            }.toString()
            val cardColor = color ?: "var(--md-sys-color-surface)"
            CompositionLocalProvider(LocalFeatureCardNativeGestures provides nativeGestures) {
                ContainerFeatureCardSurface(html, "$css\n:root{--wawlink-card-color:$cardColor}html,body{margin:0;background:var(--wawlink-card-color)!important;min-height:100%;}", data,
                    Modifier.fillMaxWidth().height(feature.cardHeight.dp), onAction, { message -> htmlError = message; onError(message) })
            }
            if (selectionMode) Box(Modifier.matchParentSize().pointerInput(Unit) {
                // Become the hit target instead of the embedded platform view; ancestors own gestures.
                awaitEachGesture { awaitFirstDown(requireUnconsumed = false); do { val event = awaitPointerEvent() } while (event.changes.any { it.pressed }) }
            })
        } else {
            Row(Modifier.fillMaxWidth().heightIn(min = 120.dp).padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(44.dp).background(foreground.copy(alpha = .09f), RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                    Symbol(entry.icon, size = 28.dp, tint = foreground)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(entry.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = foreground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(entry.description, fontSize = 18.sp, lineHeight = 25.sp, color = foreground.copy(alpha = .76f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (feature?.showStatus == true) FeatureCardStatus(state.status, foreground)
                    if (feature?.showMemory == true) Text("内存 · ${memory?.let(::bytesLabel) ?: "不可用"}", fontSize = 16.sp, color = foreground.copy(alpha = .76f))
                    if (feature != null && !selectionMode) {
                        val running = state.status == ContainerFeatureStatus.RUNNING
                        val transitioning = state.status == ContainerFeatureStatus.STARTING || state.status == ContainerFeatureStatus.STOPPING
                        TextButton(
                            onClick = { onAction(if (running) "stop" else "start") },
                            enabled = containerId != null && !transitioning,
                            modifier = Modifier.heightIn(min = 48.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = foreground),
                        ) {
                            if (transitioning) CircularProgressIndicator(Modifier.size(20.dp), color = foreground, strokeWidth = 2.dp)
                            else Symbol(if (running) "stop_circle" else "play_circle", size = 20.dp, tint = foreground)
                            Spacer(Modifier.width(5.dp))
                            Text(if (running) "停止" else if (transitioning) "处理中" else "启动", fontSize = 16.sp)
                        }
                    }
                    if (failure != null) Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(10.dp)) {
                        Text(failure, Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 16.sp)
                    }
                }
            }
        }
        if (selected) Symbol("check_circle", Modifier.align(Alignment.TopEnd).padding(8.dp), size = 22.dp, tint = MaterialTheme.colorScheme.primary)
        if (html != null && failure != null) Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer) {
            Text(failure, Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 16.sp)
        }
    }
}

@Composable
private fun FeatureCardStatus(status: ContainerFeatureStatus, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (status == ContainerFeatureStatus.STARTING || status == ContainerFeatureStatus.STOPPING) CircularProgressIndicator(Modifier.size(18.dp), color = color, strokeWidth = 2.dp)
        else Symbol(when (status) { ContainerFeatureStatus.RUNNING -> "play_circle"; ContainerFeatureStatus.FAILED -> "error"; else -> "stop_circle" }, size = 20.dp, tint = color)
        Text(when (status) { ContainerFeatureStatus.RUNNING -> "运行中"; ContainerFeatureStatus.STARTING -> "正在启动"; ContainerFeatureStatus.STOPPING -> "正在停止"; ContainerFeatureStatus.FAILED -> "运行异常"; ContainerFeatureStatus.STOPPED -> "已停止" }, fontSize = 16.sp, color = color)
    }
}

@Composable
internal fun FeatureCardError(message: String) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Symbol("error", tint = MaterialTheme.colorScheme.onErrorContainer)
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun FeatureCardColorDialog(onDismiss: () -> Unit, onColor: (String?) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("卡片颜色") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("覆盖所选卡片的颜色；恢复默认将使用清单颜色或应用主题。")
            listOf("#FFFFFF", "#D3E3FD", "#C4EED0", "#FFF2CC", "#FAD2CF", "#EADDFF").chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { color ->
                        Box(Modifier.size(48.dp).semantics { contentDescription = "卡片颜色 $color"; selected = value == color }
                            .clickable { value = color }.padding(6.dp)
                            .background(Color(0xFF000000L or color.drop(1).toLong(16)), RoundedCornerShape(10.dp))
                            .border(if (value == color) 3.dp else 1.dp, if (value == color) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)))
                    }
                }
            }
            OutlinedTextField(value, { value = it }, label = { Text("#RRGGBB") }, singleLine = true, isError = value.isNotEmpty() && !FeatureCardPreferences.validColor(value))
            TextButton({ onColor(null) }) { Text("恢复默认颜色") }
        }
    }, confirmButton = { TextButton({ onColor(value) }, enabled = FeatureCardPreferences.validColor(value)) { Text("应用") } }, dismissButton = { TextButton(onDismiss) { Text("取消") } })
}
