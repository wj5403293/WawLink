@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.wawlink.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wawlink.runtime.ContainerConfig
import dev.wawlink.runtime.ContainerFile
import dev.wawlink.runtime.ContainerFileService
import dev.wawlink.ui.*
import java.io.File
import java.util.Locale
import java.util.Properties
import kotlinx.coroutines.*
internal data class ContainerFileSelection(val path: String, val file: ContainerFile)


/** WawSSH FileWorkspace/FileBrowserState layout, adapted to a local guest rather than an SSH session. */
@Composable
internal fun ContainerFilesScreen(app: AppCoordinator, host: HostFiles, config: ContainerConfig, onBack: () -> Unit, backHandler: ((() -> Boolean)?) -> Unit, initialPath: String = "/workspace", onChooseDirectory: ((String) -> Unit)? = null, onChooseFiles: ((List<ContainerFileSelection>) -> Unit)? = null) {
    val service = remember(config) { ContainerFileService(app.runtime, config) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var path by rememberSaveable { mutableStateOf(runCatching { ContainerFileService.path(initialPath.ifEmpty { "/workspace" }, "/workspace") }.getOrDefault(initialPath)) }
    var history by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var selection by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var pickedPaths by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var scrollPositions by rememberSaveable { mutableStateOf(emptyMap<String, Pair<Int, Int>>()) }
    var hidden by rememberSaveable { mutableStateOf(false) }
    var sortSize by rememberSaveable { mutableStateOf(false) }
    var filterOpen by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("") }
    var copied by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var entries by remember { mutableStateOf(emptyList<ContainerFile>()) }
    var operation by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var more by remember { mutableStateOf(false) }
    var create by remember { mutableStateOf(false) }
    var pathEdit by remember { mutableStateOf(false) }
    var pathDraft by remember { mutableStateOf("") }
    var action by remember { mutableStateOf<ContainerFile?>(null) }
    var nameAction by remember { mutableStateOf<String?>(null) }
    var renameSource by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var delete by remember { mutableStateOf<List<String>?>(null) }
    var properties by remember { mutableStateOf<List<ContainerFile>?>(null) }
    var code by remember { mutableStateOf<Pair<String, String>?>(null) }
    var bookmarksOpen by remember { mutableStateOf(false) }
    var bookmarks by remember { mutableStateOf(emptyMap<String, String>()) }
    var bookmarkPath by remember { mutableStateOf<String?>(null) }
    var bookmarkName by remember { mutableStateOf("") }
    val bookmarkFile = remember(config.id) { File(app.dataDir, "file-bookmarks-${config.id}.properties") }
    val listState = rememberLazyListState()
    val busy = operation != null
    val visible = entries.filter { (hidden || !it.name.startsWith('.')) && it.name.contains(filter, true) }.sortedWith(
        compareByDescending<ContainerFile> { it.directory }.thenByDescending { if (sortSize) it.size else 0L }.thenBy { it.name.lowercase(Locale.ROOT) }.thenBy { it.name })
    val selectedEntries = entries.filter { it.name in selection }
    fun selectedPaths() = selectedEntries.map { ContainerFileService.child(path, it.name) }
    fun clearSelection() { selection = emptyList(); selecting = false }
    fun run(label: String, reload: Boolean = true, work: suspend () -> Unit) {
        if (busy) return
        operation = label; failure = null
        scope.launch {
            try { work() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure = error.message ?: error.toString() }
            finally { if (reload) refresh++; operation = null }
        }
    }
    fun navigate(requested: String, back: Boolean = false) {
        if (busy) return
        val target = try { ContainerFileService.path(requested, path) } catch (error: IllegalArgumentException) { failure = error.message; return }
        if (target == path) return
        run("正在读取目录", false) {
            val directory = service.list(target)
            scrollPositions = scrollPositions + (path to (listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset))
            if (!back) history = history + path else history = history.dropLast(1)
            path = directory.path; entries = directory.entries
            clearSelection(); filter = ""; filterOpen = false
            val position = scrollPositions[path]
            listState.scrollToItem(position?.first ?: 0, position?.second ?: 0)
        }
    }
    fun browserBack(): Boolean = when {
        code != null -> { code = null; true }
        busy -> true
        selecting -> { clearSelection(); true }
        filterOpen -> { filterOpen = false; filter = ""; true }
        history.isNotEmpty() -> { navigate(history.last(), true); true }
        else -> false
    }
    SideEffect { backHandler(::browserBack) }
    DisposableEffect(Unit) { onDispose { backHandler(null) } }
    LaunchedEffect(service, refresh) {
        operation = "正在读取目录"
        try {
            val result = service.list(path); entries = result.entries
            selection = selection.filter { name -> entries.any { it.name == name && it.supported } }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure = error.message ?: error.toString() }
        finally { operation = null }
    }
    LaunchedEffect(bookmarkFile) {
        try { bookmarks = withContext(Dispatchers.IO) {
            val values = Properties(); if (bookmarkFile.exists()) bookmarkFile.inputStream().use(values::load)
            values.stringPropertyNames().associateWith { values.getProperty(it) }
        } } catch (error: Exception) { failure = error.message ?: error.toString() }
    }
    suspend fun saveBookmarks(next: Map<String, String>) {
        withContext(Dispatchers.IO) {
            val values = Properties(); next.forEach { (key, value) -> values.setProperty(key, value) }
            val temporary = File(bookmarkFile.parentFile, "${bookmarkFile.name}.part")
            try {
                temporary.outputStream().use { values.store(it, "Container file bookmarks") }
                java.nio.file.Files.move(temporary.toPath(), bookmarkFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally { temporary.delete() }
        }
        bookmarks = next
    }
    fun copy(paths: List<String>) { copied = paths; clearSelection(); action = null }
    fun export(paths: List<String>) {
        if (paths.isEmpty()) return
        action = null
        val singleFile = paths.size == 1 && entries.any { !it.directory && it.regular && ContainerFileService.child(path, it.name) == paths.single() }
        run("正在导出到主机", false) {
            val filename = if (singleFile) paths.single().substringAfterLast('/') else if (paths.size == 1) paths.single().substringAfterLast('/') + ".zip" else "container-files.zip"
            host.saveFile(filename, if (singleFile) "application/octet-stream" else "application/zip") { output ->
                if (singleFile) service.exportFile(paths.single(), output) else service.exportZip(paths, output)
            }
        }
    }
    fun open(file: ContainerFile) {
        val source = ContainerFileService.child(path, file.name)
        if (file.directory) navigate(source)
        else if (file.regular) run("正在打开文本", false) { code = file.name to service.readText(source) }
        else properties = listOf(file)
    }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        if (selecting) AgentTopBar("已选 ${selection.size} 项", ::clearSelection, actions = {
            FileIconButton("select_all", "全选可见文件", { selection = visible.filter { it.supported }.map { it.name } })
        }) else AgentTopBar(if (onChooseFiles != null) "容器文件 · ${pickedPaths.size} 项" else if (onChooseDirectory != null) "选择工作区 · ${config.name}" else config.name, { if (!browserBack()) onBack() }, actions = {
            if (onChooseFiles != null) FileIconButton("select_all", "选择可见普通文件", { pickedPaths = (pickedPaths + visible.filter { it.regular }.map { ContainerFileService.child(path, it.name) }).distinct() }, enabled = !busy)
            FileIconButton("bookmarks", "书签", { if (!busy) bookmarksOpen = true })
            FileIconButton("create_new_folder", "新建目录", { if (!busy) { name = ""; nameAction = "folder" } })
            Box {
                FileIconButton("more_vert", "更多文件操作", { more = true })
                DropdownMenu(more, { more = false }) {
                    DropdownMenuItem({ Text("筛选") }, { more = false; filterOpen = true })
                    DropdownMenuItem({ Text(if (hidden) "隐藏点文件" else "显示隐藏文件") }, { hidden = !hidden; more = false })
                    DropdownMenuItem({ Text("编辑路径") }, { pathDraft = path; pathEdit = true; more = false }, enabled = !busy)
                    DropdownMenuItem({ Text("复制当前路径") }, { clipboard.setText(AnnotatedString(path)); more = false })
                    DropdownMenuItem({ Text("刷新") }, { failure = null; refresh++; more = false }, enabled = !busy)
                }
            }
        })
        Text(path, Modifier.fillMaxWidth().clickable(enabled = !busy) { pathDraft = path; pathEdit = true }.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            FileIconButton("arrow_upward", "上一级目录", { navigate(path.substringBeforeLast('/').ifEmpty { "/" }) }, enabled = !busy && path != "/")
            TextButton({ navigate("/") }, enabled = !busy) { Text("根目录") }
            val segments = path.removePrefix("/").split('/').filter { it.isNotEmpty() }
            segments.forEachIndexed { index, segment ->
                Symbol("chevron_right", size = 18.dp)
                TextButton({ navigate("/" + segments.take(index + 1).joinToString("/")) }, enabled = !busy) { Text(segment, maxLines = 1) }
            }
        }
        if (copied.isNotEmpty()) Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("已复制 ${copied.size} 项", Modifier.weight(1f))
                TextButton({ val sources = copied; val target = path; run("正在粘贴；失败可能保留已复制部分") { service.copy(sources, target); copied = emptyList() } }, enabled = !busy) { Text("粘贴到此处") }
                FileIconButton("close", "清空剪贴板", { copied = emptyList() }, enabled = !busy)
            }
        }
        if (filterOpen) Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(filter, { filter = it }, Modifier.weight(1f), label = { Text("筛选文件名") }, singleLine = true)
            FileIconButton("close", "关闭筛选", { filterOpen = false; filter = "" })
        }
        Row(Modifier.padding(start = 16.dp, end = 8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${visible.size} / ${entries.size} 项", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton({ sortSize = !sortSize }) { Symbol("sort"); Text(if (sortSize) "大小" else "名称") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        operation?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        failure?.let { Text(it, Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.error) }
        Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 88.dp)) {
                if (visible.isEmpty() && !busy) item { EmptyHost(if (entries.isEmpty()) "目录为空" else "没有匹配条目", "folder_open") }
                items(visible, key = { it.name }) { file ->
                    val source = ContainerFileService.child(path, file.name)
                    val rowSelected = file.name in selection || source in pickedPaths
                    fun togglePicked() { pickedPaths = if (source in pickedPaths) pickedPaths - source else pickedPaths + source }
                    Row(Modifier.fillMaxWidth().background(if (rowSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                        .combinedClickable(enabled = !busy, onClick = {
                            if (onChooseFiles != null && file.regular) togglePicked()
                            else if (selecting && file.supported) selection = if (file.name in selection) selection - file.name else selection + file.name
                            else if (!selecting) open(file)
                        }, onLongClick = {
                            if (onChooseFiles != null && file.regular) togglePicked()
                            else if (onChooseFiles == null && file.supported) { selecting = true; if (file.name !in selection) selection = selection + file.name }
                        })
                        .padding(start = 20.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Symbol(if (rowSelected) "check_circle" else if (file.directory) "folder" else if (file.kind == "link") "link" else "description", tint = if (file.directory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            Text("${if (file.directory) "目录" else if (file.kind == "link") "符号链接（只显示属性）" else if (file.regular) bytesLabel(file.size) else "特殊文件"} · ${file.mode}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (!selecting) FileIconButton("more_vert", "${file.name} 文件操作", { action = file }, enabled = !busy)
                    }
                }
            }
            if (!selecting) FloatingActionButton({ if (!busy) create = true }, Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Symbol("add") }
        }
        if (selecting) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton({ copy(selectedPaths()) }, enabled = !busy && selection.isNotEmpty()) { Text("复制") }
            TextButton({ export(selectedPaths()) }, enabled = !busy && selection.isNotEmpty()) { Text("导出") }
            TextButton({ renameSource = selectedPaths().single(); name = selectedEntries.single().name; nameAction = "rename" }, enabled = !busy && selection.size == 1) { Text("重命名") }
            TextButton({ delete = selectedPaths() }, enabled = !busy && selection.isNotEmpty()) { Text("删除", color = MaterialTheme.colorScheme.error) }
            TextButton({ properties = selectedEntries }, enabled = selection.isNotEmpty()) { Text("属性") }
        }
        if (onChooseDirectory != null) HostAction("选择此目录作为工作区", "folder_open", {
            run("正在检查工作区目录", false) { onChooseDirectory(service.list(path).path) }
        }, Modifier.fillMaxWidth().padding(12.dp), enabled = !busy && failure == null)
        if (onChooseFiles != null) HostAction("添加 ${pickedPaths.size} 个容器文件", "attach_file", {
            val requested = pickedPaths
            run("正在检查所选文件", false) {
                val picked = ArrayList<ContainerFileSelection>()
                requested.groupBy { it.substringBeforeLast('/').ifEmpty { "/" } }.forEach { (parent, paths) ->
                    val files = service.list(parent).entries.associateBy { it.name }
                    paths.forEach { source ->
                        val file = files[source.substringAfterLast('/')] ?: error("文件已不存在：$source")
                        check(file.regular && file.readable) { "只能添加可读普通文件，不能添加链接：$source" }
                        picked.add(ContainerFileSelection(source, file))
                    }
                }
                onChooseFiles(picked)
            }
        }, Modifier.fillMaxWidth().padding(12.dp), enabled = !busy && pickedPaths.isNotEmpty())
    }
    code?.let { (title, text) -> Surface(Modifier.fillMaxSize()) { CodeDetailScreen(title, text, title.substringAfterLast('.', "").ifEmpty { null }, { code = null }) } }
    }
    if (create) AgentSheet("新建 / 导入", { create = false }) {
        HostRow("新建文件", icon = "note_add", onClick = { create = false; name = ""; nameAction = "file" })
        HostRow("新建目录", icon = "create_new_folder", onClick = { create = false; name = ""; nameAction = "folder" })
        HostRow("从主机导入文件", "流式复制；不会覆盖已有条目", "upload", {
            create = false
            val destination = path
            run("正在从主机导入；失败可能保留已导入文件") {
                val sources = host.pickImportFiles()
                sources.forEach { source -> withContext(Dispatchers.IO) { source.openInput().use { service.importFile(destination, source.name, it) } } }
            }
        })
    }
    action?.let { file ->
        AgentSheet(file.name, { action = null }) {
            val source = ContainerFileService.child(path, file.name)
            if (file.supported) {
                HostRow(if (file.directory) "进入目录" else "打开文本", icon = if (file.directory) "folder_open" else "code", onClick = { action = null; open(file) })
                HostRow("复制", icon = "content_copy", onClick = { copy(listOf(source)) })
                HostRow("导出到主机", if (file.directory) "ZIP，拒绝链接与特殊文件" else "保存原文件", "download", { export(listOf(source)) })
                HostRow("重命名", icon = "drive_file_rename_outline", onClick = { action = null; renameSource = source; name = file.name; nameAction = "rename" })
                HostRow(if (file.directory) "删除空目录" else "删除文件", icon = "delete", onClick = { action = null; delete = listOf(source) })
            }
            HostRow("复制路径", icon = "content_copy", onClick = { clipboard.setText(AnnotatedString(source)); action = null })
            HostRow("属性", icon = "info", onClick = { properties = listOf(file); action = null })
        }
    }
    nameAction?.let { type ->
        val invalid = ContainerFileService.nameError(name)
        AlertDialog(onDismissRequest = { nameAction = null }, title = { Text(when (type) { "rename" -> "重命名"; "folder" -> "新建目录"; else -> "新建文件" }) }, text = {
            Column { Text("目标目录：$path"); HostField("名称（不是路径）", name, { name = it }, singleLine = false); Text(invalid ?: "不会覆盖已有条目") }
        }, confirmButton = { TextButton({
            val target = name; val source = renameSource; val directory = path; nameAction = null
            run("正在保存文件操作") {
                if (type == "rename") { service.rename(requireNotNull(source), target); clearSelection() }
                else service.create(directory, target, type == "folder")
            }
        }, enabled = invalid == null && !busy && (type != "rename" || name != renameSource?.substringAfterLast('/'))) { Text("确定") } }, dismissButton = { TextButton({ nameAction = null }) { Text("取消") } })
    }
    if (pathEdit) AlertDialog(onDismissRequest = { pathEdit = false }, title = { Text("前往目录") }, text = { HostField("路径（相对当前目录）", pathDraft, { pathDraft = it }, singleLine = false) }, confirmButton = { TextButton({ pathEdit = false; navigate(pathDraft) }, enabled = !busy && pathDraft.isNotEmpty()) { Text("前往") } }, dismissButton = { TextButton({ pathEdit = false }) { Text("取消") } })
    delete?.let { sources -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("确认删除 ${sources.size} 项？") }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) { Text("只删除普通文件与空目录，不递归删除。任一目录非空时不会开始删除。不能撤销；执行期间外部修改可能导致部分完成。"); sources.forEach { Text(it, Modifier.padding(top = 8.dp)) } }
    }, confirmButton = { TextButton({ delete = null; run("正在删除；失败时请检查已删除的部分") { service.delete(sources); clearSelection() } }, enabled = !busy) { Text("删除", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton({ delete = null }) { Text("取消") } }) }
    properties?.let { files -> AlertDialog(onDismissRequest = { properties = null }, title = { Text("属性 · ${files.size} 项") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { files.forEach { file ->
            Text(ContainerFileService.child(path, file.name), style = MaterialTheme.typography.titleMedium)
            Text("类型：${file.kind}\n大小：${bytesLabel(file.size)}\n权限：${file.mode}\nUID / GID：${file.owner} / ${file.group}\n修改：${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(file.modified * 1000))}", Modifier.padding(bottom = 16.dp))
        } }
    }, confirmButton = { TextButton({ properties = null }) { Text("关闭") } }) }
    if (bookmarksOpen) AgentSheet("书签", { bookmarksOpen = false }) {
        HostRow("添加当前目录", path, "bookmark_add", { bookmarkPath = path; bookmarkName = bookmarks[path] ?: path })
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            bookmarks.entries.sortedBy { it.value.lowercase(Locale.ROOT) }.forEach { (target, title) ->
                HostRow(title, target, "folder", { bookmarksOpen = false; navigate(target) }) {
                    FileIconButton("edit", "重命名书签", { bookmarkPath = target; bookmarkName = title })
                    FileIconButton("delete", "删除书签", { run("正在删除书签", false) { saveBookmarks(bookmarks - target) } }, enabled = !busy)
                }
            }
        }
    }
    bookmarkPath?.let { target -> AlertDialog(onDismissRequest = { bookmarkPath = null }, title = { Text("保存书签") }, text = { Column { Text(target); HostField("标题", bookmarkName, { bookmarkName = it }) } }, confirmButton = { TextButton({
        val title = bookmarkName; bookmarkPath = null
        run("正在保存书签", false) { saveBookmarks(bookmarks + (target to title)) }
    }, enabled = !busy && bookmarkName.isNotBlank()) { Text("保存") } }, dismissButton = { TextButton({ bookmarkPath = null }) { Text("取消") } }) }
}

@Composable
private fun FileIconButton(icon: String, description: String, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Symbol(icon, contentDescription = description)
    }
}
