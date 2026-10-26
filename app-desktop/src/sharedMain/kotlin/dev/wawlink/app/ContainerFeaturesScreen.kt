@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package dev.wawlink.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
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
import kotlinx.coroutines.launch
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

private val ContainerFeature.isHtmlCard: Boolean get() = cardHtml != null || cardUrl != null
private data class CardDrag(val key: String, val pointer: Offset, val grab: Offset, val itemOffset: Offset, val size: IntSize)

@Composable
internal fun ContainerFeaturesScreen(
    app: AppCoordinator,
    service: ContainerFeatureService,
    containerId: String?,
    onBuiltin: (String) -> Unit,
    onExtension: (ContainerFeature) -> Unit,
    onSettings: () -> Unit,
    navigation: HostNavigation? = null,
) {
    val containers by app.runtime.containers.collectAsState()
    val states by service.states.collectAsState()
    val lifecycleErrors by service.errors.collectAsState()
    val preferences by app.featureCards.state.collectAsState()
    val preferenceError by app.featureCards.error.collectAsState()
    val container = containers.firstOrNull { it.config.id == containerId }
    var catalog by remember(containerId) { mutableStateOf<ContainerFeatureCatalog?>(null) }
    var error by remember(containerId) { mutableStateOf<String?>(null) }
    var refresh by remember(containerId) { mutableStateOf(0) }
    var loading by remember(containerId) { mutableStateOf(false) }
    var selected by remember(containerId) { mutableStateOf(emptySet<String>()) }
    var editing by remember(containerId) { mutableStateOf(false) }
    var choosingContainer by remember { mutableStateOf(false) }
    var logFeature by remember(containerId) { mutableStateOf<ContainerFeature?>(null) }
    val actionScope = rememberCoroutineScope()
    val lua = rememberFeatureNativeLuaExecutor(containerId)
    var busyActions by remember(containerId) { mutableStateOf(emptySet<String>()) }
    val actionOutput = remember(containerId) { mutableStateMapOf<String, String>() }
    var colorDialog by remember(containerId) { mutableStateOf(false) }
    var uninstallDialog by remember(containerId) { mutableStateOf(false) }
    var working by remember(containerId) { mutableStateOf(false) }
    var drag by remember(containerId) { mutableStateOf<CardDrag?>(null) }
    var localOrder by remember(containerId) { mutableStateOf<List<String>?>(null) }
    val grid = rememberLazyGridState()
    val css = containerFeatureThemeCss()
    SideEffect { service.setTheme(css) }
    ContainerFeatureIcons(service)
    val cardThemeCss = containerFeatureThemeCss(forCard = true)
    val entries = remember(catalog) {
        featureBuiltins.map { CardEntry("builtin/${it.id}", builtin = it) } + catalog?.features.orEmpty().map { CardEntry("extension/${it.id}", feature = it) }
    }
    val byKey = remember(entries) { entries.associateBy { it.key } }
    val layout = preferences[containerId] ?: FeatureCardLayout()
    val order = (localOrder ?: layout.order).filter { it in byKey } + entries.map { it.key }.filterNot { it in (localOrder ?: layout.order) }
    val visible = order.filterNot { it in layout.hidden }
    val hidden = order.filter { it in layout.hidden }
    val displayed = if (editing) visible + hidden else visible
    val gridKeys = if (editing && hidden.isNotEmpty()) visible + "hidden-divider" + hidden else visible
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
        if (!editing) return false
        finishDrag(); editing = false; selected = emptySet(); colorDialog = false; uninstallDialog = false
        return true
    }
    SideEffect { navigation?.onBack = ::clearSelection }
    DisposableEffect(navigation) { onDispose { navigation?.onBack = null } }
    LaunchedEffect(containerId, refresh) {
        if (containerId == null) return@LaunchedEffect
        loading = true; error = null
        try {
            app.featureCards.load(containerId)
            catalog = service.catalog(containerId)
            app.featureCards.reconcile(containerId, featureCardTitles(catalog).keys.toList())
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { report(failure) }
        finally { loading = false }
    }
    LaunchedEffect(containerId, entries) {
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
            item.key in displayed && point.x >= item.offset.x && point.x <= item.offset.x + item.size.width &&
                point.y >= item.offset.y && point.y <= item.offset.y + item.size.height
        }?.let { (it.key as String) to (byKey[it.key]?.feature?.let { feature -> feature.isHtmlCard || feature.actions.isNotEmpty() } == true) }
    }
    val selectionMode by rememberUpdatedState(editing)
    val onTap by rememberUpdatedState<(String) -> Unit> { key ->
        if (!working && container != null) {
            if (editing) selected = if (key in selected) selected - key else selected + key
            else byKey[key]?.let { entry -> entry.builtin?.let { onBuiltin(it.id) } ?: entry.feature?.let(onExtension) }
        }
    }
    val onHold by rememberUpdatedState<(String, Offset, Boolean) -> Unit> { key, point, wasSelecting ->
        if (!working && container != null) {
            editing = true
            selected = selected + key
            if (wasSelecting && key !in layout.hidden) grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.let {
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
    fun runAction(feature: ContainerFeature, action: ContainerFeatureAction) {
        val id = containerId ?: return
        val key = "${feature.id}/${action.id}"
        if (editing || key in busyActions) return
        if (action.type == ContainerFeatureActionType.LOGS) { logFeature = feature; return }
        busyActions = busyActions + key
        actionScope.launch {
            try {
                when (action.type) {
                    ContainerFeatureActionType.OPEN -> onExtension(feature)
                    ContainerFeatureActionType.START -> service.start(id, feature)
                    ContainerFeatureActionType.STOP -> service.stop(id, feature.id)
                    ContainerFeatureActionType.TOGGLE -> {
                        if (service.states.value[service.stateKey(id, feature.id)]?.status == ContainerFeatureStatus.RUNNING) service.stop(id, feature.id)
                        else service.start(id, feature)
                    }
                    ContainerFeatureActionType.SCRIPT -> actionOutput[feature.id] = service.runScriptAction(id, feature, action)
                    ContainerFeatureActionType.LUA -> actionOutput[feature.id] = lua.execute(requireNotNull(action.lua)).let { if (it == JsonNull) "" else it.toString() }
                    ContainerFeatureActionType.LOGS -> Unit
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                actionOutput[feature.id] = failure.message ?: "操作失败"
                report(failure)
            } finally { busyActions = busyActions - key }
        }
    }
    CompositionLocalProvider(LocalFeatureCardCovered provides (editing || choosingContainer || colorDialog || uninstallDialog || logFeature != null)) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        val barColor by animateColorAsState(if (editing) MaterialTheme.colorScheme.surfaceContainerHigh else if (grid.canScrollBackward) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface, tween(300, easing = EmphEasing))
        Row(Modifier.fillMaxWidth().height(64.dp).background(barColor).padding(horizontal = if (editing) 4.dp else 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (editing) {
                AgentIconButton("close", "退出编辑", { clearSelection() }, enabled = !working)
                Text(if (selected.isEmpty()) "编辑" else "已选择 ${selected.size} 项", Modifier.weight(1f), fontSize = 20.sp, maxLines = 1)
                val allHidden = selected.isNotEmpty() && selected.all { it in layout.hidden }
                AgentIconButton(if (allHidden) "visibility" else "visibility_off", if (allHidden) "显示所选功能" else "隐藏所选功能", {
                    val keys = selected
                    persist {
                        if (allHidden) app.featureCards.restore(requireNotNull(containerId), keys) else app.featureCards.hide(requireNotNull(containerId), keys)
                        selected = emptySet()
                    }
                }, enabled = !working && selected.isNotEmpty())
                AgentIconButton("palette", "卡片颜色", { colorDialog = true }, enabled = !working && selected.isNotEmpty() && selected.none { byKey[it]?.feature?.isHtmlCard == true })
                AgentIconButton("delete", "卸载所选功能", { uninstallDialog = true }, enabled = !working && selected.isNotEmpty() && customSelection.size == selected.size)
                IconButton({ clearSelection() }, enabled = !working, modifier = Modifier.size(48.dp)) { Symbol("check", tint = MaterialTheme.colorScheme.primary, contentDescription = "完成编辑") }
            } else {
                Text("功能", Modifier.weight(1f), fontSize = 24.sp, fontWeight = FontWeight.Medium)
                AgentIconButton("edit", "编辑功能卡片", { editing = true }, enabled = container != null)
                AgentIconButton("settings", "应用设置", onSettings)
            }
        }
        if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyVerticalGrid(
            columns = GridCells.Fixed(2), state = grid,
            modifier = Modifier.fillMaxSize().featureCardGestures(containerId, { onHit(it) }, { selectionMode }, { onTap(it) }, { key, point, selecting -> onHold(key, point, selecting) }, { onMove(it) }, { onFinish() }),
            contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "container-selector", span = { GridItemSpan(2) }) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.weight(1f).height(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer).clickable(enabled = !working && !editing) { choosingContainer = true }.padding(start = 10.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.surface, CircleShape), contentAlignment = Alignment.Center) {
                            Symbol("deployed_code")
                            Box(Modifier.align(Alignment.BottomEnd).size(12.dp).background(if (container?.running == true) LocalAgentColors.current.tertiary else MaterialTheme.colorScheme.outline, CircleShape).border(2.dp, MaterialTheme.colorScheme.surface, CircleShape))
                        }
                        Text(container?.config?.name ?: "选择容器", Modifier.weight(1f), fontSize = 19.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Symbol("unfold_more")
                    }
                    Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceContainer, CircleShape), contentAlignment = Alignment.Center) {
                        if (loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        else AgentIconButton("refresh", "刷新功能", { refresh++ }, enabled = container != null && !editing)
                    }
                }
            }
            items(gridKeys, key = { it }, span = { GridItemSpan(if (it == "hidden-divider") 2 else byKey.getValue(it).feature?.cardSpan ?: 1) }) { key ->
                if (key == "hidden-divider") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Symbol("visibility_off", size = 20.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("已隐藏", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider(Modifier.weight(1f))
                    }
                } else {
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
                val htmlHeight = if (feature != null && feature.isHtmlCard) {
                    val cells = grid.layoutInfo.visibleItemsInfo
                    val cell = cells.firstOrNull { it.key == key }
                    val peer = cell?.let { current -> cells.firstOrNull { it.key != key && it.row == current.row && it.key in byKey } }
                    val peerFeature = byKey[peer?.key]?.feature
                    maxOf(feature.cardHeight, if (peerFeature?.isHtmlCard == true) peerFeature.cardHeight else peer?.size?.height?.let { (it / density).toInt() } ?: 0)
                } else 0
                val itemScale by animateFloatAsState(if (editing && key !in selected) .97f else 1f, tween(350, easing = EmphEasing))
                val itemAlpha by animateFloatAsState(if (key in layout.hidden) { if (key in selected) .75f else .45f } else 1f, tween(300, easing = EmphEasing))
                FeatureCardTile(
                    entry, state, service, containerId, container?.running == true, editing, key in selected, color, cardThemeCss,
                    Modifier.fillMaxWidth().zIndex(if (currentDrag != null) 2f else 0f)
                        .animateItem(placementSpec = if (currentDrag != null) null else tween(420, easing = EmphEasing))
                        .graphicsLayer {
                            if (currentDrag != null) {
                                val origin = itemOffset?.let { Offset(it.x.toFloat(), it.y.toFloat()) } ?: currentDrag.itemOffset
                                val delta = currentDrag.pointer - currentDrag.grab - origin
                                translationX = delta.x; translationY = delta.y; scaleX = 1.035f; scaleY = 1.035f; shadowElevation = 12.dp.toPx()
                            } else { scaleX = itemScale; scaleY = itemScale }
                            alpha = itemAlpha
                        },
                    onAction = { action ->
                        if (feature != null && containerId != null && !editing) when (action) {
                            "open" -> onExtension(feature)
                            "start" -> persist { service.start(containerId, feature) }
                            "stop" -> persist { service.stop(containerId, feature.id) }
                            else -> error = "未知卡片操作：$action"
                        }
                    }, onError = { message -> error = "${entry.title}：$message" }, onClick = { onTap(key) }, onSelect = { if (container != null && !working) { editing = true; selected = selected + key } },
                    busyActions = busyActions.filter { it.startsWith("${feature?.id}/") }.map { it.substringAfter('/') }.toSet(),
                    onNativeAction = { action -> feature?.let { runAction(it, action) } },
                    htmlHeight = htmlHeight,
                    nativeGestures = FeatureCardNativeGestures(
                        selectionMode = editing,
                        onTap = { if (editing) onTap(key) },
                        onHold = { normalized ->
                            grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.let { item ->
                                onHold(key, Offset(item.offset.x + normalized.x * item.size.width, item.offset.y + normalized.y * item.size.height), editing)
                            }
                        },
                        onDrag = { normalized ->
                            drag?.takeIf { it.key == key }?.let { current -> onMove(Offset(normalized.x * current.size.width, normalized.y * current.size.height)) }
                        },
                        onEnd = { onFinish() },
                    ),
                )
                }
            }
            (listOfNotNull(error, preferenceError) + catalog?.errors.orEmpty() + lifecycleErrors[containerId].orEmpty()).distinct().forEachIndexed { index, message ->
                item(key = "error/$index", span = { GridItemSpan(2) }) { FeatureCardError(message) }
            }
        }
    }
    }
    if (choosingContainer) FeatureContainerSheet(containers, containerId, { choosingContainer = false }) { id ->
        persist { app.selectContainer(id); choosingContainer = false }
    }
    val colorEntries = selected.mapNotNull(byKey::get)
    if (colorDialog && colorEntries.isNotEmpty()) FeatureCardColorSheet(
        title = colorEntries.first().title, icon = colorEntries.first().icon, count = colorEntries.size,
        initialColor = layout.colors[colorEntries.first().key] ?: colorEntries.first().feature?.color,
        mixed = colorEntries.map { layout.colors[it.key] ?: it.feature?.color }.distinct().size > 1,
        defaultColor = colorEntries.first().feature?.color,
        onDismiss = { colorDialog = false },
        onColor = { color -> val keys = selected; persist { app.featureCards.setColor(requireNotNull(containerId), keys, color); colorDialog = false } },
    )
    logFeature?.let { feature ->
        AgentSheet("${feature.title}日志", { logFeature = null }) {
            val output = listOf(states[service.stateKey(containerId.orEmpty(), feature.id)]?.output.orEmpty(), actionOutput[feature.id].orEmpty()).filter { it.isNotBlank() }.joinToString("\n")
            SelectionContainer(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState())) { Text(output.ifBlank { "暂无日志" }, fontFamily = LocalAgentMono.current) }
        }
    }
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
                    else if (!card.second) change.consume()
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
    busyActions: Set<String>, onNativeAction: (ContainerFeatureAction) -> Unit,
    htmlHeight: Int,
) {
    val feature = entry.feature
    var memory by remember(containerId, feature?.id) { mutableStateOf<Long?>(null) }
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
    val customBackground = remember(color) {
        color?.takeIf { FeatureCardPreferences.validColor(it) }?.let { Color(0xFF000000L or it.drop(1).toLong(16)) }
    }
    val background by animateColorAsState(customBackground ?: MaterialTheme.colorScheme.surfaceContainer, tween(350, easing = EmphEasing))
    val customForeground = remember(customBackground) { customBackground?.let(::agentContainerContentColor) }
    val foreground = customForeground ?: MaterialTheme.colorScheme.onSurface
    val selectionColor = customForeground ?: MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(28.dp)
    val html = feature?.cardHtml
    val htmlCard = feature?.isHtmlCard == true
    val url = if (containerRunning && state.status != ContainerFeatureStatus.STARTING && state.status != ContainerFeatureStatus.STOPPING) feature?.let { resolveFeatureCardUrl(it, state.session) } else null
    var htmlError by remember(containerId, html, url, state.session) { mutableStateOf<String?>(null) }
    val failure = htmlError ?: state.error
    val actions = feature?.actions.orEmpty()
    val wide = feature?.cardSpan == 2
    Box(modifier.heightIn(min = (if (htmlCard) htmlHeight else feature?.cardHeight ?: 172).dp).clip(shape).background(background)
        .clickable(enabled = !htmlCard && actions.isNotEmpty() && !selectionMode, onClick = onClick)
        .semantics(mergeDescendants = (!htmlCard && actions.isEmpty()) || selectionMode) {
            contentDescription = entry.title
            if (selectionMode) this.selected = selected
            onClick(label = if (selectionMode) "选择${entry.title}" else "打开${entry.title}") { onClick(); true }
            onLongClick(label = "选择${entry.title}") { onSelect(); true }
        }) {
        if (htmlCard) {
            val data = buildJsonObject {
                put("id", requireNotNull(feature).id); put("status", state.status.name.lowercase()); put("containerRunning", containerRunning)
                put("memoryBytes", memory?.let(::JsonPrimitive) ?: JsonNull)
            }.toString()
            val cardCss = remember(css, background, foreground) {
                fun Color.hex() = "#%06x".format(toArgb() and 0xffffff)
                "$css\n:root{--wawlink-card-color:${background.hex()};--wawlink-card-foreground:${foreground.hex()}}:where(html,body){margin:0;width:100%;height:100%;overflow:hidden;background:var(--wawlink-card-color);color:var(--wawlink-card-foreground)}:where(button,input,select,textarea){color:inherit}"
            }
            if (html != null || url != null) CompositionLocalProvider(LocalFeatureCardNativeGestures provides nativeGestures) {
                ContainerFeatureCardSurface(html, url, cardCss, data,
                    Modifier.fillMaxWidth().height(htmlHeight.dp), onAction, { message -> htmlError = message; onError(message) })
            } else Box(Modifier.fillMaxWidth().height(htmlHeight.dp), contentAlignment = Alignment.Center) {
                if (state.status == ContainerFeatureStatus.STARTING || state.status == ContainerFeatureStatus.STOPPING) CircularProgressIndicator()
                else AgentIconButton("play_arrow", "启动${entry.title}", { onAction("start") }, enabled = !selectionMode)
            }
            if (selectionMode) Box(Modifier.matchParentSize().pointerInput(Unit) {
                // Become the hit target instead of the embedded platform view; ancestors own gestures.
                awaitEachGesture { awaitFirstDown(requireUnconsumed = false); do { val event = awaitPointerEvent() } while (event.changes.any { it.pressed }) }
            })
        } else {
            Column(Modifier.fillMaxWidth().heightIn(min = (feature?.cardHeight ?: 172).dp).padding(16.dp)) {
                val iconBackground = if (customBackground == null) MaterialTheme.colorScheme.surface else foreground.copy(alpha = .1f)
                if (wide) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    FeatureCardIcon(entry.icon, iconBackground, foreground)
                    Column(Modifier.weight(1f)) {
                        Text(entry.title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (feature?.showStatus == true) FeatureCardStatus(state.status, foreground)
                        else Text(entry.description, fontSize = 15.sp, lineHeight = 22.sp, color = foreground.copy(alpha = .76f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    FeatureCardIcon(entry.icon, iconBackground, foreground)
                    Spacer(Modifier.height(14.dp))
                    Text(entry.title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(entry.description, Modifier.padding(top = 2.dp), fontSize = 15.sp, lineHeight = 22.sp, color = foreground.copy(alpha = .76f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (feature?.showStatus == true) FeatureCardStatus(state.status, foreground)
                }
                if (feature?.showMemory == true) Text("内存 · ${memory?.let(::bytesLabel) ?: "不可用"}", Modifier.padding(top = 4.dp), fontSize = 15.sp, color = foreground.copy(alpha = .76f))
                if (actions.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        actions.forEachIndexed { index, action ->
                            val running = state.status == ContainerFeatureStatus.RUNNING
                            val transitioning = state.status == ContainerFeatureStatus.STARTING || state.status == ContainerFeatureStatus.STOPPING
                            val busy = action.id in busyActions || (transitioning && action.type in setOf(ContainerFeatureActionType.START, ContainerFeatureActionType.STOP, ContainerFeatureActionType.TOGGLE))
                            val toggle = action.type == ContainerFeatureActionType.TOGGLE
                            val label = if (toggle) when (state.status) {
                                ContainerFeatureStatus.RUNNING -> "停止"
                                ContainerFeatureStatus.STARTING -> "启动中"
                                ContainerFeatureStatus.STOPPING -> "停止中"
                                ContainerFeatureStatus.FAILED -> "重试"
                                ContainerFeatureStatus.STOPPED -> action.title
                            } else action.title
                            val icon = if (toggle) when (state.status) { ContainerFeatureStatus.RUNNING -> "stop"; ContainerFeatureStatus.FAILED -> "refresh"; else -> action.icon } else action.icon
                            val filled = index == 0 && !(toggle && running)
                            Button({ onNativeAction(action) }, modifier = Modifier.weight(1f).height(44.dp).semantics { contentDescription = label },
                                enabled = !selectionMode && !busy && (containerRunning || action.type == ContainerFeatureActionType.LUA || action.type == ContainerFeatureActionType.LOGS) &&
                                    !(action.type == ContainerFeatureActionType.OPEN && !running) && !(action.type == ContainerFeatureActionType.START && running) && !(action.type == ContainerFeatureActionType.STOP && !running),
                                shape = CircleShape, contentPadding = PaddingValues(horizontal = 6.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = if (filled) foreground else MaterialTheme.colorScheme.surface.copy(alpha = .65f), contentColor = if (filled) background else foreground)) {
                                if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
                                else Symbol(icon, size = 20.dp)
                                if (wide || actions.size < 3) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(label, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                if (failure != null) Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(10.dp)) {
                    Text(failure, Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 16.sp)
                }
            }
        }
        if (selected) Box(Modifier.matchParentSize().border(3.dp, selectionColor, shape))
        val selectionScale by animateFloatAsState(if (selectionMode) 1f else .4f, tween(300, easing = EmphDecelEasing))
        val selectionAlpha by animateFloatAsState(if (selectionMode) 1f else 0f, tween(250, easing = EmphEasing))
        if (selectionAlpha > 0f) Box(Modifier.align(Alignment.TopEnd).padding(12.dp).size(32.dp).graphicsLayer { scaleX = selectionScale; scaleY = selectionScale; alpha = selectionAlpha }.background(if (selected) selectionColor else MaterialTheme.colorScheme.surface.copy(alpha = .85f), CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Symbol("check", size = 22.dp, tint = if (customBackground != null) background else MaterialTheme.colorScheme.onPrimary)
        }
        if (htmlCard && failure != null) Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer) {
            Text(failure, Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 16.sp)
        }
    }
}

@Composable
private fun FeatureCardIcon(icon: String, background: Color, foreground: Color) {
    Box(Modifier.size(52.dp).background(background, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
        Symbol(icon, size = 28.dp, tint = foreground)
    }
}

@Composable
private fun FeatureCardStatus(status: ContainerFeatureStatus, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (status == ContainerFeatureStatus.STARTING || status == ContainerFeatureStatus.STOPPING) CircularProgressIndicator(Modifier.size(18.dp), color = color, strokeWidth = 2.dp)
        else Box(Modifier.size(10.dp).background(when (status) { ContainerFeatureStatus.RUNNING -> LocalAgentColors.current.tertiary; ContainerFeatureStatus.FAILED -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.outline }, CircleShape))
        Text(when (status) { ContainerFeatureStatus.RUNNING -> "运行中"; ContainerFeatureStatus.STARTING -> "正在启动"; ContainerFeatureStatus.STOPPING -> "正在停止"; ContainerFeatureStatus.FAILED -> "运行异常"; ContainerFeatureStatus.STOPPED -> "未运行" }, fontSize = 15.sp, color = color.copy(alpha = .76f))
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

