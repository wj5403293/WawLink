package dev.wawlink.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wawlink.runtime.*
import dev.wawlink.ui.*
import kotlinx.coroutines.*
import java.util.Base64

private const val ICON_PREFIX = "data:image/png;base64,"
private val avatarImages = object : LinkedHashMap<String, ImageBitmap>(16, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 24
}

@Composable
internal fun ContainerAvatar(config: ContainerConfig, round: Boolean = false, size: Dp = 48.dp) {
    val icon = config.metadata.icon
    val image by produceState<ImageBitmap?>(null, icon) {
        value = null
        if (icon.startsWith(ICON_PREFIX)) {
            value = withContext(Dispatchers.Default) {
                synchronized(avatarImages) { avatarImages[icon] } ?: runCatching {
                    require(icon.length <= 700_000)
                    decodeContainerIcon(Base64.getDecoder().decode(icon.removePrefix(ICON_PREFIX))).also {
                        synchronized(avatarImages) { avatarImages[icon] = it }
                    }
                }.getOrNull()
            }
        }
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(if (round) size / 2 else size / 3))
        .background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        when {
            image != null && icon.startsWith(ICON_PREFIX) -> Image(image!!, "${config.displayName}的图标", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            icon.isNotBlank() && !icon.startsWith(ICON_PREFIX) -> Symbol(icon, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            else -> DistroAvatar(config.distro, round, size)
        }
    }
}

private val metadataSymbols = listOf(
    "terminal" to "终端", "dns" to "服务器", "code" to "代码", "deployed_code" to "容器",
    "cloud" to "云端", "storage" to "存储", "rocket_launch" to "火箭", "science" to "实验室",
    "smart_toy" to "机器人", "memory" to "芯片", "database" to "数据库", "language" to "网络",
    "school" to "学习", "work" to "工作", "home" to "家", "favorite" to "喜爱",
    "security" to "安全", "build" to "工具", "palette" to "创作", "sports_esports" to "游戏"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContainerMetadataSheet(app: AppCoordinator, files: HostFiles, config: ContainerConfig, dismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var baseline by remember(config.id) { mutableStateOf<ContainerMetadataSnapshot?>(null) }
    var draft by remember(config.id) { mutableStateOf(ContainerMetadata()) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    var confirmReload by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var choosingSymbol by remember { mutableStateOf(false) }
    val busy = loading || saving || picking
    val dirty = baseline != null && draft != baseline!!.value.let { it.copy(title = it.title.ifBlank { config.name }) }
    fun close() { if (!busy) { if (dirty) discard = true else dismiss() } }
    val currentBusy by rememberUpdatedState(busy)
    val currentDirty by rememberUpdatedState(dirty)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { target ->
        if (target == SheetValue.Hidden && (currentBusy || currentDirty)) {
            if (!currentBusy) discard = true
            false
        } else true
    })
    LaunchedEffect(config.id, reload) {
        loading = true
        loadError = null
        try {
            val latest = app.runtime.readMetadata(config.id)
            if (baseline == null) draft = latest.value.copy(title = latest.value.title.ifBlank { config.name })
            baseline = latest
            actionError = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { loadError = failure.message ?: "读取容器信息失败" }
        finally { loading = false }
    }
    ModalBottomSheet(onDismissRequest = ::close, sheetState = sheetState,
        modifier = Modifier.widthIn(max = AgentPageMaxWidth), containerColor = LocalAgentColors.current.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("编辑容器信息", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            AgentIconButton("close", "关闭", ::close, enabled = !busy)
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).weight(1f, fill = false).imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在读取容器中的最新信息…") }
            loadError?.let {
                Text("读取失败：$it", color = MaterialTheme.colorScheme.error)
                TextButton({ if (!busy) reload++ }, enabled = !busy) { Text("重试读取") }
            }
            if (baseline != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ContainerAvatar(config.copy(metadata = draft), size = 64.dp)
                    Column(Modifier.weight(1f)) {
                        Text("容器图标", style = MaterialTheme.typography.titleMedium)
                        Row {
                            TextButton({ choosingSymbol = !choosingSymbol }, enabled = !busy) { Text("选择图标") }
                            TextButton({ draft = draft.copy(icon = ""); actionError = null }, enabled = !busy) { Text("恢复默认") }
                        }
                    }
                }
                AnimatedVisibility(choosingSymbol, enter = expandVertically(tween(260, easing = EmphDecelEasing)),
                    exit = shrinkVertically(tween(220, easing = EmphAccelEasing))) {
                    Column {
                        metadataSymbols.chunked(4).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                row.forEach { (symbol, label) ->
                                    AgentIconButton(symbol, label, {
                                        draft = draft.copy(icon = symbol); choosingSymbol = false; actionError = null
                                    }, filled = draft.icon == symbol, enabled = !busy)
                                }
                            }
                        }
                    }
                }
                OutlinedButton({
                    if (!busy) {
                        picking = true
                        scope.launch {
                            try {
                                val selected = files.pickContainerIcon()
                                if (selected != null) {
                                    val uri = withContext(Dispatchers.IO) {
                                        val bytes = selected.openInput().use { input ->
                                            val output = java.io.ByteArrayOutputStream()
                                            val buffer = ByteArray(8192)
                                            var total = 0
                                            while (true) {
                                                val count = input.read(buffer, 0, minOf(buffer.size, 10 * 1024 * 1024 + 1 - total))
                                                if (count < 0) break
                                                if (count == 0) continue
                                                output.write(buffer, 0, count)
                                                total += count
                                                require(total <= 10 * 1024 * 1024) { "图片文件不能超过 10 MiB" }
                                            }
                                            output.toByteArray()
                                        }
                                        require(bytes.size <= 10 * 1024 * 1024) { "图片文件不能超过 10 MiB" }
                                        ICON_PREFIX + Base64.getEncoder().encodeToString(normalizeContainerIcon(bytes))
                                    }
                                    draft = draft.copy(icon = uri)
                                    actionError = null
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { actionError = "导入失败：${failure.message ?: "图片无法解码"}" }
                            finally { picking = false }
                        }
                    }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Symbol("image"); Spacer(Modifier.width(8.dp)); Text(if (picking) "正在处理图片…" else "从文件选择图片") }
                OutlinedTextField(draft.title, { draft = draft.copy(title = it) }, label = { Text("标题") }, singleLine = true, enabled = !busy, isError = draft.title.isBlank() || draft.title.length > 120, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                OutlinedTextField(draft.description, { draft = draft.copy(description = it) }, label = { Text("描述") }, minLines = 3, maxLines = 6, enabled = !busy, isError = draft.description.length > 4000, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                OutlinedTextField(draft.version, { draft = draft.copy(version = it) }, label = { Text("分发版本号") }, singleLine = true, enabled = !busy, isError = draft.version.length > 64, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                if (draft.title.length > 120 || draft.description.length > 4000 || draft.version.length > 64) Text("标题最多 120 字、描述最多 4000 字、版本号最多 64 字。", color = MaterialTheme.colorScheme.error)
                if (picking || saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                actionError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    TextButton({ if (!busy) confirmReload = true }, enabled = !busy) { Text("读取最新信息并保留草稿") }
                }
                Button({
                    val snapshot = baseline
                    if (!busy && snapshot != null && loadError == null) {
                        saving = true
                        scope.launch {
                            try { app.runtime.saveMetadata(config.id, draft, snapshot.revision); dismiss() }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { actionError = "保存失败，草稿已保留：${failure.message ?: "请重试"}" }
                            finally { saving = false }
                        }
                    }
                }, enabled = !busy && loadError == null && draft.title.isNotBlank() && draft.title.length <= 120 && draft.description.length <= 4000 && draft.version.length <= 64, modifier = Modifier.fillMaxWidth()) { Text(if (saving) "正在保存…" else "保存") }
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃未保存的修改？") }, text = { Text("草稿尚未写入容器。") }, confirmButton = { TextButton(dismiss) { Text("放弃修改") } }, dismissButton = { TextButton({ discard = false }) { Text("继续编辑") } })
    if (confirmReload) AlertDialog(
        onDismissRequest = { confirmReload = false }, title = { Text("保留草稿并重新读取？") },
        text = { Text("当前草稿不会改变。再次保存时，将用草稿替换容器文件中的最新信息。") },
        confirmButton = { TextButton({ confirmReload = false; reload++ }) { Text("重新读取") } },
        dismissButton = { TextButton({ confirmReload = false }) { Text("取消") } },
    )
}
