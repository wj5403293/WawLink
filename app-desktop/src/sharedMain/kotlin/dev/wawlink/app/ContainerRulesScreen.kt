@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package dev.wawlink.app

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wawlink.runtime.ContainerConfig
import dev.wawlink.runtime.ContainerFile
import dev.wawlink.runtime.ContainerFileService
import dev.wawlink.runtime.ContainerRuntime
import dev.wawlink.ui.*
import java.io.InputStream
import java.util.Locale
import kotlinx.coroutines.*

/** User rules only: all paths and contents are obtained through the configured guest user. */
@Composable
internal fun ContainerRulesScreen(app: AppCoordinator, config: ContainerConfig, onBack: () -> Unit,
    registerBack: ((() -> Boolean)?) -> Unit) {
    key(config) { RulesBrowser(app, config, onBack, registerBack) }
}

private data class RulesEditor(val path: String, val original: String)
private data class RulesNameAction(val directory: String, val folder: Boolean = false, val source: ContainerFile? = null)
private val RulesEasing = CubicBezierEasing(.2f, 0f, 0f, 1f)

@Composable
private fun RulesBrowser(app: AppCoordinator, config: ContainerConfig, onBack: () -> Unit,
    registerBack: ((() -> Boolean)?) -> Unit) {
    val files = remember(app.runtime, config) { ContainerFileService(app.runtime, config) }
    val scope = rememberCoroutineScope()
    var root by remember { mutableStateOf<String?>(null) }
    var path by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf(emptyList<ContainerFile>()) }
    var search by remember { mutableStateOf("") }
    var operation by remember { mutableStateOf<String?>("正在定位用户规则目录") }
    var failure by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf<(() -> Unit)?>(null) }
    var createMenu by remember { mutableStateOf(false) }
    var entryMenu by remember { mutableStateOf<String?>(null) }
    var nameAction by remember { mutableStateOf<RulesNameAction?>(null) }
    var name by remember { mutableStateOf("") }
    var deletion by remember { mutableStateOf<ContainerFile?>(null) }
    var editor by remember { mutableStateOf<RulesEditor?>(null) }
    var editorFailure by remember { mutableStateOf<String?>(null) }
    var editorDismiss by remember { mutableStateOf<(() -> Unit)?>(null) }
    val busy = operation != null

    suspend fun load(target: String) {
        val boundary = requireNotNull(root)
        require(target == boundary || target.startsWith("$boundary/")) { "只能浏览当前用户的规则目录" }
        val result = files.list(target)
        entries = result.entries
        path = result.path
        entryMenu = null
    }
    fun run(label: String, work: suspend () -> Unit) {
        if (operation != null) return
        operation = label
        failure = null
        editorFailure = null
        retry = { run(label, work) }
        scope.launch {
            try { work(); retry = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val message = error.message ?: error.toString()
                if (editor != null) {
                    editorFailure = "$message\n草稿已保留，可点击“保存”重试。若文件已在外部修改，请先复制草稿，再取消并重新打开文件合并；不会强制覆盖。"
                } else failure = message
            } finally { operation = null }
        }
    }
    suspend fun initialize() {
        val home = RulesGuestHome(app.runtime, config)
        // Inspect each parent with ContainerFileService; never mkdir -p through a link.
        var directory = home
        for (segment in listOf(".omp", "agent", "rules")) {
            val listing = files.list(directory)
            val existing = listing.entries.firstOrNull { it.name == segment }
            if (existing == null) files.create(directory, segment, folder = true)
            else require(existing.directory) { "规则路径包含符号链接或非目录：${ContainerFileService.child(directory, segment)}" }
            directory = ContainerFileService.child(directory, segment)
        }
        root = directory
        load(directory)
    }
    LaunchedEffect(files) {
        try { initialize() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            failure = error.message ?: error.toString()
            retry = { run("正在定位用户规则目录") { initialize() } }
        } finally { operation = null }
    }
    fun navigate(target: String) {
        if (target == path) return
        run("正在读取规则目录") { load(target); search = "" }
    }
    fun browserBack(): Boolean = when {
        editor != null -> { editorDismiss?.invoke(); true }
        busy -> true
        nameAction != null -> { nameAction = null; failure = null; true }
        deletion != null -> { deletion = null; failure = null; true }
        entryMenu != null || createMenu -> { entryMenu = null; createMenu = false; true }
        search.isNotEmpty() -> { search = ""; true }
        path != null && path != root -> { navigate(requireNotNull(path).substringBeforeLast('/')); true }
        else -> false
    }
    SideEffect { registerBack(::browserBack) }
    DisposableEffect(Unit) { onDispose { registerBack(null) } }
    fun open(file: ContainerFile) {
        val current = path ?: return
        val target = ContainerFileService.child(current, file.name)
        when {
            file.directory -> navigate(target)
            file.regular -> run("正在读取 UTF-8 规则") {
                editor = RulesEditor(target, files.readText(target))
            }
            else -> failure = "符号链接和特殊文件仅显示，不会跟随、编辑或删除。"
        }
    }
    fun startName(action: RulesNameAction) {
        failure = null
        retry = null
        name = action.source?.name.orEmpty()
        nameAction = action
        createMenu = false
        entryMenu = null
    }
    val sorted = entries.filter { search.isBlank() || it.name.contains(search.trim(), ignoreCase = true) }
        .sortedWith(compareBy<ContainerFile> { it.name.lowercase(Locale.ROOT) }.thenBy { it.name })
    val groups = listOf("文件夹" to sorted.filter { it.directory }, "规则文件" to sorted.filter { it.regular },
        "链接与特殊文件" to sorted.filter { !it.supported })

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                AgentIconButton("arrow_back", "返回", { if (!browserBack()) onBack() })
                Text("规则管理", Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.titleLarge)
                AgentIconButton("refresh", "刷新规则", { path?.let { current -> run("正在刷新规则") { load(current) } } }, enabled = !busy && path != null)
                Box {
                    AgentIconButton("add", "新建规则或文件夹", { createMenu = true }, enabled = !busy && path != null)
                    DropdownMenu(createMenu, { createMenu = false }) {
                        DropdownMenuItem({ Text("新建规则文件") }, { path?.let { startName(RulesNameAction(it)) } }, leadingIcon = { Symbol("note_add") })
                        DropdownMenuItem({ Text("新建文件夹") }, { path?.let { startName(RulesNameAction(it, folder = true)) } }, leadingIcon = { Symbol("create_new_folder") })
                    }
                }
            }
            Column(Modifier.widthIn(max = AgentPageMaxWidth).fillMaxWidth().align(Alignment.CenterHorizontally).padding(horizontal = 20.dp)) {
                Text("用户规则", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                Text("${config.displayName} · ${config.user}", style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                Text(root ?: "正在读取容器内 HOME…", style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp),
                    enabled = path != null && !busy, singleLine = true, shape = RoundedCornerShape(28.dp),
                    placeholder = { Text("搜索当前目录的规则") }, leadingIcon = { Symbol("search") },
                    trailingIcon = { if (search.isNotEmpty()) AgentIconButton("close", "清除搜索", { search = "" }) })
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    val boundary = root
                    val current = path
                    TextButton({ boundary?.let(::navigate) }, enabled = !busy && boundary != null) {
                        Symbol("rule_folder", size = 21.dp)
                        Text("规则", Modifier.padding(start = 6.dp))
                    }
                    if (boundary != null && current != null && current != boundary) {
                        val segments = current.removePrefix("$boundary/").split('/')
                        segments.forEachIndexed { index, segment ->
                            Symbol("chevron_right", size = 18.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton({ navigate(boundary + "/" + segments.take(index + 1).joinToString("/")) }, enabled = !busy) {
                                Text(segment, maxLines = 1)
                            }
                        }
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f).widthIn(max = AgentPageMaxWidth).fillMaxWidth().align(Alignment.CenterHorizontally),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                operation?.let { label -> item("operation") { Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                failure?.let { message -> item("failure") {
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.errorContainer) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                            if (retry != null) TextButton({ retry?.invoke() }, enabled = !busy) { Text("重试") }
                        }
                    }
                } }
                if (path != null && sorted.isEmpty() && !busy && failure == null) item("empty") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Symbol(if (search.isNotBlank()) "search_off" else "rule_folder", size = 48.dp, tint = MaterialTheme.colorScheme.primary)
                        Text(if (search.isNotBlank()) "没有匹配的条目" else "此目录暂无规则", style = MaterialTheme.typography.titleLarge)
                        Text(if (search.isNotBlank()) "试试其他名称" else "点击右上角 + 新建规则文件或文件夹", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                groups.forEach { (title, group) ->
                    if (group.isNotEmpty()) {
                        item("heading-$title") {
                            Text("$title · ${group.size}", color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, start = 4.dp))
                        }
                        itemsIndexed(group, key = { _, file -> "entry-${file.name}" }) { index, file ->
                            val shape = RoundedCornerShape(topStart = if (index == 0) 22.dp else 5.dp,
                                topEnd = if (index == 0) 22.dp else 5.dp,
                                bottomStart = if (index == group.lastIndex) 22.dp else 5.dp,
                                bottomEnd = if (index == group.lastIndex) 22.dp else 5.dp)
                            Row(Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .animateContentSize(tween(400, easing = RulesEasing))
                                .combinedClickable(enabled = !busy, onClick = { open(file) }, onLongClick = { entryMenu = file.name }, onLongClickLabel = "规则操作")
                                .heightIn(min = 88.dp).padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                                    Symbol(RulesFileIcon(file), size = 26.dp, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(file.name, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(RulesFileSubtitle(file), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                Box {
                                    AgentIconButton("more_vert", "${file.name} 的操作", { entryMenu = file.name }, enabled = !busy)
                                    DropdownMenu(entryMenu == file.name, { entryMenu = null }) {
                                        if (file.supported) {
                                            DropdownMenuItem({ Text(if (file.directory) "进入文件夹" else "编辑规则") }, { entryMenu = null; open(file) }, leadingIcon = { Symbol(if (file.directory) "folder_open" else "edit") })
                                            DropdownMenuItem({ Text("重命名") }, { path?.let { startName(RulesNameAction(it, source = file)) } }, leadingIcon = { Symbol("drive_file_rename_outline") })
                                            DropdownMenuItem({ Text("删除", color = MaterialTheme.colorScheme.error) }, { entryMenu = null; failure = null; deletion = file }, leadingIcon = { Symbol("delete", tint = MaterialTheme.colorScheme.error) })
                                        } else DropdownMenuItem({ Text("不支持操作链接或特殊文件") }, { entryMenu = null }, enabled = false, leadingIcon = { Symbol("lock") })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        editor?.let { document -> key(document.path) {
            EditableCodeScreen(title = document.path.substringAfterLast('/'), text = document.original,
                language = document.path.substringAfterLast('.', "").ifEmpty { null },
                onDismiss = { if (!busy) { editor = null; editorFailure = null; retry = null } },
                registerDismiss = { editorDismiss = it }, error = editorFailure, saving = busy,
                onSave = { draft ->
                    run("正在安全保存规则") {
                        files.writeText(document.path, draft, expectedText = document.original)
                        editor = null
                        editorFailure = null
                        path?.let { load(it) }
                    }
                })
        } }
    }
    nameAction?.let { action ->
        val invalid = ContainerFileService.nameError(name)
        val title = if (action.source != null) "重命名" else if (action.folder) "新建文件夹" else "新建规则文件"
        AgentSheet(title, { if (!busy) { nameAction = null; failure = null } }) {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(action.directory, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("名称") }, singleLine = true,
                    enabled = !busy, shape = RoundedCornerShape(16.dp), isError = invalid != null && name.isNotEmpty(),
                    supportingText = { Text(invalid ?: if (action.folder || action.source != null) "不会覆盖同名条目" else "例如 instructions.md；不会覆盖同名条目") })
                failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button({
                    val target = name
                    run(if (action.source != null) "正在重命名" else "正在创建") {
                        if (action.source != null) files.rename(ContainerFileService.child(action.directory, action.source.name), target)
                        else files.create(action.directory, target, folder = action.folder)
                        nameAction = null
                        load(action.directory)
                    }
                }, Modifier.fillMaxWidth(), enabled = !busy && invalid == null && name != action.source?.name) {
                    Text(if (busy) "正在保存…" else if (action.source != null) "保存名称" else "创建")
                }
                TextButton({ nameAction = null; failure = null }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("取消") }
            }
        }
    }
    deletion?.let { file ->
        val source = path?.let { ContainerFileService.child(it, file.name) }
        AlertDialog(onDismissRequest = { if (!busy) { deletion = null; failure = null } },
            icon = { Symbol("delete", tint = MaterialTheme.colorScheme.error) },
            title = { Text(if (file.directory) "删除此文件夹及全部内容？" else "删除此规则文件？") },
            text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(source.orEmpty())
                Text("此操作无法撤销。删除前会检查符号链接、特殊文件、受保护挂载及权限。外部并发修改仍可能造成部分删除；失败后请刷新目录检查。")
                failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton({
                if (source != null) run("正在删除规则") {
                    files.delete(listOf(source))
                    deletion = null
                    path?.let { load(it) }
                }
            }, enabled = !busy && source != null) { Text(if (busy) "正在删除…" else "永久删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ deletion = null; failure = null }, enabled = !busy) { Text("取消") } })
    }
}

private fun RulesFileIcon(file: ContainerFile): String = when {
    file.directory -> "folder"
    file.kind == "link" -> "link"
    !file.regular -> "lock"
    else -> "description"
}

private fun RulesFileSubtitle(file: ContainerFile): String {
    if (!file.supported) return if (file.kind == "link") "符号链接 · 为安全起见不会跟随" else "特殊文件 · 不支持操作"
    val type = if (file.directory) "文件夹" else when {
        file.size < 1024 -> "${file.size} B"
        file.size < 1024 * 1024 -> "${(file.size + 1023) / 1024} KiB"
        else -> String.format(Locale.ROOT, "%.1f MiB", file.size / (1024.0 * 1024.0))
    }
    val date = java.time.Instant.ofEpochSecond(file.modified).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    return "$type · $date" + if (!file.readable) " · 不可读取" else ""
}

/** Read HOME from a non-login process: login shell startup files must not replace configured HOME. */
private suspend fun RulesGuestHome(runtime: ContainerRuntime, config: ContainerConfig): String = withContext(Dispatchers.IO) {
    val process = runtime.openProcess(config.id,
        listOf("/bin/sh", "-c", "[ -n \"\${HOME:-}\" ] || { printf '%s' '容器未配置 HOME' >&2; exit 1; }; printf '%s' \"\$HOME\""), "/")
    try {
        coroutineScope {
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { process.destroy() }
            }
            try {
                process.outputStream.close()
                val errors = async { runInterruptible { process.errorStream.use { RulesReadOutput(it) } } }
                val home = runInterruptible { process.inputStream.use { RulesReadOutput(it) } }
                val status = runInterruptible { process.waitFor() }
                val message = errors.await()
                check(status == 0) { message.ifBlank { "无法读取容器用户 HOME（$status）" } }
                require(home.startsWith('/') && '\u0000' !in home && ContainerFileService.path(home) == home) {
                    "容器 HOME 必须为规范化的绝对路径"
                }
                home
            } finally { cancellation.cancel() }
        }
    } finally {
        process.destroy()
        process.inputStream.close()
        process.errorStream.close()
        process.outputStream.close()
    }
}

private fun RulesReadOutput(input: InputStream): String {
    val result = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        check(result.size() + count <= 65536) { "容器返回的 HOME 或错误信息过长" }
        result.write(buffer, 0, count)
    }
    return result.toString("UTF-8")
}
