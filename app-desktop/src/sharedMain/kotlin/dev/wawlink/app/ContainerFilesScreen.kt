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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
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

/** WawSSH FileWorkspace layout and actions, with guest-process rather than SSH access. */
@Composable
internal fun ContainerFilesScreen(app: AppCoordinator, host: HostFiles, config: ContainerConfig, onBack: () -> Unit, backHandler: ((() -> Boolean)?) -> Unit, initialPath: String = "/workspace", onChooseDirectory: ((String) -> Unit)? = null, onChooseFiles: ((List<ContainerFileSelection>) -> Unit)? = null) {
    val service = remember(config) { ContainerFileService(app.runtime, config) }
    val actions = remember(service, host) { ContainerFileActions(service, host) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var path by rememberSaveable(config.id) { mutableStateOf(runCatching { ContainerFileService.path(initialPath.ifEmpty { "/workspace" }, "/workspace") }.getOrDefault(initialPath)) }
    var history by rememberSaveable(config.id) { mutableStateOf(emptyList<String>()) }
    var selection by rememberSaveable(config.id) { mutableStateOf(emptyList<String>()) }
    var selecting by rememberSaveable(config.id) { mutableStateOf(false) }
    var pickedPaths by rememberSaveable(config.id) { mutableStateOf(emptyList<String>()) }
    var scrollPositions by rememberSaveable(config.id) { mutableStateOf(emptyMap<String, Pair<Int, Int>>()) }
    var hidden by rememberSaveable(config.id) { mutableStateOf(false) }
    var sortSize by rememberSaveable(config.id) { mutableStateOf(false) }
    var filterOpen by rememberSaveable(config.id) { mutableStateOf(false) }
    var filter by rememberSaveable(config.id) { mutableStateOf("") }
    var copied by rememberSaveable(config.id) { mutableStateOf(emptyList<String>()) }
    var entries by remember { mutableStateOf(emptyList<ContainerFile>()) }
    var operation by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var more by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var bulkMore by remember { mutableStateOf(false) }
    var downloadOptions by remember { mutableStateOf<List<String>?>(null) }
    var create by remember { mutableStateOf(false) }
    var pathSheet by remember { mutableStateOf(false) }
    var pathEdit by remember { mutableStateOf(false) }
    var pathDraft by remember { mutableStateOf("") }
    var action by remember { mutableStateOf<ContainerFile?>(null) }
    var nameAction by remember { mutableStateOf<String?>(null) }
    var renameSource by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var delete by remember { mutableStateOf<List<String>?>(null) }
    var properties by remember { mutableStateOf<List<ContainerFileSelection>?>(null) }
    var code by remember { mutableStateOf<Pair<String, String>?>(null) }
    var bookmarksOpen by remember { mutableStateOf(false) }
    var bookmarkMenu by remember { mutableStateOf<String?>(null) }
    var bookmarks by remember { mutableStateOf(emptyMap<String, String>()) }
    var bookmarkPath by remember { mutableStateOf<String?>(null) }
    var bookmarkName by remember { mutableStateOf("") }
    val bookmarkFile = remember(config.id) { File(app.dataDir, "file-bookmarks-${config.id}.properties") }
    val listState = rememberLazyListState()
    val busy = operation != null
    val visible = entries.filter { hidden || !it.name.startsWith('.') }.sortedWith(
        compareByDescending<ContainerFile> { it.directory }.thenByDescending { if (sortSize) it.size else 0L }.thenBy { it.name.lowercase(Locale.ROOT) }.thenBy { it.name })
    val filtered = filterContainerFiles(visible, filter)
    val selectedEntries = entries.filter { it.name in selection }.map { ContainerFileSelection(ContainerFileService.child(path, it.name), it) }
    fun selectedPaths() = selectedEntries.map { it.path }
    fun clearSelection() { selection = emptyList(); selecting = false }
    fun closeFilter() { filterOpen = false; filter = "" }
    fun run(label: String, reload: Boolean = true, work: suspend () -> Unit) {
        if (operation != null) return
        operation = label; failure = null
        scope.launch {
            try { work() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure = error.message ?: error.toString() }
            finally { if (reload) refresh++; operation = null }
        }
    }
    fun navigate(requested: String, back: Boolean = false) {
        if (operation != null) return
        val target = try { ContainerFileService.path(requested, path) } catch (error: IllegalArgumentException) { failure = error.message; return }
        if (target == path) return
        run("正在读取目录", false) {
            val directory = service.list(target)
            scrollPositions = scrollPositions + (path to (listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset))
            if (!back) history = history + path
            path = directory.path; entries = directory.entries
            clearSelection(); closeFilter()
            val position = scrollPositions[path]
            listState.scrollToItem(position?.first ?: 0, position?.second ?: 0)
        }
    }
    fun browserBack(): Boolean = when {
        code != null -> { code = null; true }
        operation != null -> true
        selecting -> { clearSelection(); true }
        filterOpen -> { closeFilter(); true }
        history.isNotEmpty() -> {
            // Consume before attempting I/O: deleted/unreadable history must not trap Back forever.
            val target = history.last(); history = history.dropLast(1)
            navigate(target, true); true
        }
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
        catch (error: Exception) { entries = emptyList(); clearSelection(); failure = error.message ?: error.toString() }
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
            val temporary = File.createTempFile("file-bookmarks-", ".part", bookmarkFile.parentFile)
            try {
                temporary.outputStream().use { values.store(it, "Container file bookmarks") }
                java.nio.file.Files.move(temporary.toPath(), bookmarkFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally { temporary.delete() }
        }
        bookmarks = next
    }
    fun copy(paths: List<String>) { if (paths.isNotEmpty()) { copied = paths; clearSelection(); action = null } }
    fun download(paths: List<String>, zip: Boolean = false) {
        if (paths.isEmpty()) return
        action = null; downloadOptions = null
        run(if (zip) "正在导出 ZIP" else "正在下载到主机", false) { if (actions.download(paths, zip)) clearSelection() }
    }
    fun openExternal(file: ContainerFile) {
        action = null
        val source = ContainerFileService.child(path, file.name)
        run(if (containerFileMimeType(file.name) == "application/vnd.android.package-archive") "正在准备系统安装器" else "正在打开文件", false) { actions.open(source) }
    }
    fun openText(file: ContainerFile) {
        action = null
        val source = ContainerFileService.child(path, file.name)
        run("正在读取文本", false) { code = file.name to service.readText(source) }
    }
    fun showProperties(files: List<ContainerFileSelection>) { properties = files; action = null }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (selecting) {
                Row(Modifier.fillMaxWidth().height(64.dp).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    FileIconButton("close", "取消选择", ::clearSelection, !busy)
                    Text("已选 ${selection.size} 项", Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.headlineSmall)
                    FileIconButton("select_all", "全选", { selection = filtered.filter { it.supported }.map { it.name } }, !busy)
                }
            } else Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                FileIconButton("arrow_back", "返回", { if (!browserBack()) onBack() })
                Row(Modifier.weight(1f).fillMaxHeight().clickable(enabled = !busy, onClickLabel = "打开路径菜单") { pathSheet = true }.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (onChooseFiles != null) "容器文件 · ${pickedPaths.size} 项" else if (onChooseDirectory != null) "选择工作区 · ${config.displayName}" else config.displayName, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(path, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Symbol("expand_more")
                }
                if (onChooseFiles != null) FileIconButton("select_all", "选择可见普通文件", { pickedPaths = (pickedPaths + filtered.filter { it.regular && it.readable }.map { ContainerFileService.child(path, it.name) }).distinct() }, !busy)
                FileIconButton("bookmarks", "书签", { bookmarksOpen = true }, !busy)
                FileIconButton("create_new_folder", "新建目录", { name = ""; nameAction = "folder" }, !busy)
                Box {
                    FileIconButton("more_vert", "更多文件操作", { more = true })
                    DropdownMenu(more, { more = false }) {
                        DropdownMenuItem({ Text("筛选") }, { more = false; filterOpen = true }, leadingIcon = { Symbol("filter_alt") })
                        DropdownMenuItem({ Text(if (hidden) "隐藏文件" else "显示隐藏文件") }, { hidden = !hidden; more = false }, leadingIcon = { Symbol(if (hidden) "visibility_off" else "visibility") })
                        DropdownMenuItem({ Text("刷新") }, { failure = null; refresh++; more = false }, enabled = !busy, leadingIcon = { Symbol("refresh") })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ navigate("/") }, enabled = !busy, colors = ButtonDefaults.textButtonColors(containerColor = if (path == "/") MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)) { Symbol("home", size = 22.dp); Text("根目录", Modifier.padding(start = 6.dp)) }
                val segments = path.removePrefix("/").split('/').filter { it.isNotEmpty() }
                segments.forEachIndexed { index, segment ->
                    Symbol("chevron_right", size = 20.dp)
                    TextButton({ navigate("/" + segments.take(index + 1).joinToString("/")) }, enabled = !busy, colors = ButtonDefaults.textButtonColors(containerColor = if (index == segments.lastIndex) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)) { Text(segment, maxLines = 1) }
                }
            }
            if (copied.isNotEmpty()) Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Symbol("content_paste")
                    Text("剪贴板中有 ${copied.size} 项", Modifier.weight(1f).padding(horizontal = 10.dp))
                    TextButton({ val sources = copied; val target = path; run("正在粘贴；失败可能保留已复制部分") { service.copy(sources, target); copied = emptyList() } }, enabled = !busy) { Text("粘贴到此处") }
                    FileIconButton("close", "清空剪贴板", { copied = emptyList() }, !busy)
                }
            }
            Row(Modifier.padding(start = 20.dp, end = 8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (filter.isBlank()) "${visible.count { it.directory }} 个文件夹 · ${visible.count { !it.directory }} 个文件" else "显示 ${filtered.size} / ${visible.size} 项", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    TextButton({ sortMenu = true }, enabled = !busy) { Symbol("sort", size = 22.dp); Text(if (sortSize) "大小" else "名称", Modifier.padding(start = 8.dp)) }
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        DropdownMenuItem({ Text("名称") }, { sortSize = false; sortMenu = false }, leadingIcon = { Symbol("sort_by_alpha") })
                        DropdownMenuItem({ Text("大小") }, { sortSize = true; sortMenu = false }, leadingIcon = { Symbol("straighten") })
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            operation?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyLarge) }
            Box(Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = if (selecting) 12.dp else if (filterOpen) 164.dp else 96.dp)) {
                    failure?.let { reason -> item {
                        Column(Modifier.padding(16.dp)) {
                            Text(reason, color = MaterialTheme.colorScheme.error)
                            TextButton({ failure = null; refresh++ }, enabled = !busy) { Text("重试") }
                        }
                    } }
                    if (filtered.isEmpty() && !busy && failure == null) item { EmptyHost(if (entries.isEmpty()) "目录为空" else if (filter.isNotBlank()) "没有匹配条目" else "没有可见条目", if (filter.isNotBlank()) "search_off" else "folder_open") }
                    items(filtered, key = { it.name }) { file ->
                        val source = ContainerFileService.child(path, file.name)
                        val rowSelected = file.name in selection || source in pickedPaths
                        fun togglePicked() { pickedPaths = if (source in pickedPaths) pickedPaths - source else pickedPaths + source }
                        Row(Modifier.fillMaxWidth().background(if (rowSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .combinedClickable(enabled = !busy, onClick = {
                                when {
                                    onChooseFiles != null && file.regular && file.readable -> togglePicked()
                                    selecting && file.supported -> selection = if (file.name in selection) selection - file.name else selection + file.name
                                    selecting -> failure = "符号链接和特殊文件不能批量操作"
                                    file.directory -> navigate(source)
                                    else -> action = file
                                }
                            }, onLongClick = {
                                if (onChooseFiles != null && file.regular && file.readable) togglePicked()
                                else if (onChooseFiles == null && file.supported) { closeFilter(); selecting = true; if (file.name !in selection) selection = selection + file.name }
                            }, onLongClickLabel = "选择条目")
                            .heightIn(min = 76.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(12.dp), color = if (rowSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.size(44.dp)) {
                                Box(contentAlignment = Alignment.Center) { Symbol(if (rowSelected) "check" else containerFileIcon(file), size = 26.dp) }
                            }
                            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                                Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                                Text(containerFileSubtitle(file), maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                            }
                            if (!selecting) FileIconButton("more_vert", "${file.name} 文件操作", { action = file }, !busy)
                        }
                    }
                }
                if (!selecting && !filterOpen) FloatingActionButton({ if (!busy) create = true }, Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Symbol("add", contentDescription = "新建 / 导入") }
                if (filterOpen) ContainerFileFilterBar(filter, { filter = it }, ::closeFilter, Modifier.align(Alignment.BottomCenter))
            }
            if (selecting) Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 8.dp)) {
                    ContainerFileBulkAction("content_copy", "复制", !busy && selection.isNotEmpty(), Modifier.weight(1f)) { copy(selectedPaths()) }
                    ContainerFileBulkAction("download", "下载", !busy && selection.isNotEmpty(), Modifier.weight(1f)) { downloadOptions = selectedPaths() }
                    ContainerFileBulkAction("drive_file_rename_outline", "重命名", !busy && selection.size == 1, Modifier.weight(1f)) { renameSource = selectedPaths().single(); name = selectedEntries.single().file.name; nameAction = "rename" }
                    ContainerFileBulkAction("delete", "删除", !busy && selection.isNotEmpty(), Modifier.weight(1f), dangerous = true) { delete = selectedPaths() }
                    ContainerFileBulkAction("more_horiz", "更多", !busy && selection.isNotEmpty(), Modifier.weight(1f)) { bulkMore = true }
                }
            }
            if (onChooseDirectory != null) HostAction("选择此目录作为工作区", "folder_open", {
                run("正在检查工作区目录", false) { onChooseDirectory(service.list(path).path) }
            }, Modifier.fillMaxWidth().padding(12.dp), enabled = !busy && failure == null)
            if (onChooseFiles != null) HostAction("添加 ${pickedPaths.size} 个容器文件", "attach_file", {
                val requested = pickedPaths
                run("正在检查所选文件", false) {
                    val picked = requested.map { source ->
                        val file = service.stat(source)
                        check(file.regular && file.readable) { "只能添加可读普通文件，不能添加链接：$source" }
                        ContainerFileSelection(source, file)
                    }
                    onChooseFiles(picked)
                }
            }, Modifier.fillMaxWidth().padding(12.dp), enabled = !busy && pickedPaths.isNotEmpty())
        }
        code?.let { (title, text) -> Surface(Modifier.fillMaxSize()) { CodeDetailScreen(title, text, title.substringAfterLast('.', "").ifEmpty { null }, { code = null }) } }
    }
    if (pathSheet) AgentActionSheet({ pathSheet = false }) {
        AgentActionRow("当前目录", path, "folder_open")
        AgentActionRow("复制当前路径", icon = "content_copy", onClick = { clipboard.setText(AnnotatedString(path)); pathSheet = false })
        AgentActionRow("编辑路径", icon = "edit", onClick = { pathDraft = path; pathEdit = true; pathSheet = false })
        if (path != "/") AgentActionRow("上一级目录", icon = "arrow_upward", onClick = { pathSheet = false; navigate(path.substringBeforeLast('/').ifEmpty { "/" }) })
    }
    if (create) AgentActionSheet({ create = false }, title = "新建 / 导入") {
        AgentActionRow("导入文件", "流式复制；不会覆盖已有条目", "upload", {
            create = false
            val destination = path
            run("正在从主机导入；失败可能保留已导入文件") {
                val sources = host.pickImportFiles()
                sources.forEach { source -> withContext(Dispatchers.IO) { source.openInput().use { service.importFile(destination, source.name, it) } } }
            }
        })
        AgentActionRow("新建目录", icon = "create_new_folder", onClick = { create = false; name = ""; nameAction = "folder" })
        AgentActionRow("新建文件", icon = "note_add", onClick = { create = false; name = ""; nameAction = "file" })
    }
    downloadOptions?.let { sources -> AgentActionSheet({ downloadOptions = null }) {
        AgentActionRow("导出", "打包为 ZIP 文件", "folder_zip", { download(sources, true) })
        AgentActionRow("下载", "普通文件保持原样；目录以 ZIP 保留结构", "download", { download(sources) })
    } }
    if (bulkMore) AgentActionSheet({ bulkMore = false }) {
        AgentActionRow("复制所选路径", icon = "content_copy", onClick = { clipboard.setText(AnnotatedString(selectedPaths().joinToString("\n"))); bulkMore = false })
        AgentActionRow("查看所选条目", icon = "info", onClick = { showProperties(selectedEntries); bulkMore = false })
        if (selectedEntries.size == 1) AgentActionRow("文件操作", icon = "more_vert", onClick = { action = selectedEntries.single().file; bulkMore = false })
    }
    action?.let { file ->
        AgentActionSheet({ action = null }) {
            val source = ContainerFileService.child(path, file.name)
            AgentActionRow(file.name, containerFileSubtitle(file), containerFileIcon(file))
            if (file.supported) {
                AgentActionRow("复制", icon = "content_copy", onClick = { copy(listOf(source)) })
                if (file.directory) AgentActionRow("进入目录", icon = "folder_open", onClick = { action = null; navigate(source) })
                else ContainerFileOpenRows(file, { openExternal(file) }, { openText(file) })
                AgentActionRow("下载", if (file.directory) "保留目录结构，保存为 ZIP" else "保存原文件", "download", { action = null; downloadOptions = listOf(source) })
                AgentActionRow("重命名", icon = "drive_file_rename_outline", onClick = { action = null; renameSource = source; name = file.name; nameAction = "rename" })
                AgentActionRow(if (file.directory) "删除目录" else "删除文件", icon = "delete", dangerous = true, onClick = { action = null; delete = listOf(source) })
            }
            AgentActionRow("复制路径", icon = "content_copy", onClick = { clipboard.setText(AnnotatedString(source)); action = null })
            AgentActionRow("属性", icon = "info", onClick = { showProperties(listOf(ContainerFileSelection(source, file))) })
        }
    }
    nameAction?.let { type ->
        val invalid = ContainerFileService.nameError(name)
        AlertDialog(onDismissRequest = { nameAction = null }, title = { Text(when (type) { "rename" -> "重命名"; "folder" -> "新建目录"; else -> "新建文件" }) }, text = {
            Column { Text("目标目录：$path"); OutlinedTextField(name, { name = it }, label = { Text("名称（不是路径）") }, singleLine = true, isError = invalid != null, supportingText = { Text(invalid ?: "不会覆盖已有条目") }) }
        }, confirmButton = { TextButton({
            val target = name; val source = renameSource; val directory = path; nameAction = null
            run("正在保存文件操作") {
                if (type == "rename") { service.rename(requireNotNull(source), target); clearSelection() }
                else service.create(directory, target, type == "folder")
            }
        }, enabled = invalid == null && !busy && (type != "rename" || name != renameSource?.substringAfterLast('/'))) { Text("确定") } }, dismissButton = { TextButton({ nameAction = null }) { Text("取消") } })
    }
    if (pathEdit) AlertDialog(onDismissRequest = { pathEdit = false }, title = { Text("前往目录") }, text = { OutlinedTextField(pathDraft, { pathDraft = it }, label = { Text("目录路径") }, supportingText = { Text("相对路径以当前目录为起点") }, singleLine = true) }, confirmButton = { TextButton({ pathEdit = false; navigate(pathDraft) }, enabled = !busy && pathDraft.isNotBlank()) { Text("前往") } }, dismissButton = { TextButton({ pathEdit = false }) { Text("取消") } })
    delete?.let { sources -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("确认删除 ${sources.size} 项？") }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            Text("目录及其全部内容将递归删除，不能撤销。开始前会检查所有所选目录；发现链接、特殊文件、不可访问目录或受保护挂载时，不会开始删除。执行期间外部修改仍可能导致部分完成。")
            sources.forEach { Text(it, Modifier.padding(top = 8.dp)) }
        }
    }, confirmButton = { TextButton({ delete = null; run("正在递归删除；失败时请检查已删除的部分") { service.delete(sources); clearSelection() } }, enabled = !busy) { Text("删除", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton({ delete = null }) { Text("取消") } }) }
    properties?.let { files -> AlertDialog(onDismissRequest = { properties = null }, title = { Text("属性 · ${files.size} 项") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { files.forEach { entry -> ContainerFileProperties(entry.path, entry.file) } }
    }, confirmButton = { TextButton({ properties = null }) { Text("关闭") } }) }
    if (bookmarksOpen) AgentActionSheet({ bookmarksOpen = false; bookmarkMenu = null }, title = "书签") {
        AgentActionRow("添加当前目录书签", path, "bookmark_add", { bookmarkPath = path; bookmarkName = bookmarks[path] ?: path })
        if (bookmarks.isEmpty()) AgentActionRow("暂无书签", "添加当前目录即可保存", "bookmark_border")
        else bookmarks.entries.sortedBy { it.value.lowercase(Locale.ROOT) }.forEach { (target, title) ->
            AgentActionRow(title, target, "folder_open", { bookmarksOpen = false; bookmarkMenu = null; navigate(target) }) {
                Box {
                    FileIconButton("more_vert", "书签操作", { bookmarkMenu = target })
                    DropdownMenu(bookmarkMenu == target, { bookmarkMenu = null }) {
                        DropdownMenuItem({ Text("重命名") }, { bookmarkMenu = null; bookmarkPath = target; bookmarkName = title }, leadingIcon = { Symbol("edit") })
                        DropdownMenuItem({ Text("删除") }, { bookmarkMenu = null; run("正在删除书签", false) { saveBookmarks(bookmarks - target) } }, enabled = !busy, leadingIcon = { Symbol("delete") })
                    }
                }
            }
        }
    }
    bookmarkPath?.let { target ->
        val invalid = when { bookmarkName.isBlank() -> "标题不能为空"; bookmarkName.length > 80 -> "标题不能超过 80 个字符"; bookmarkName.any(Char::isISOControl) -> "标题不能包含控制字符"; else -> null }
        AlertDialog(onDismissRequest = { bookmarkPath = null }, title = { Text(if (target in bookmarks) "重命名书签" else "添加书签") }, text = {
            Column { Text(target); OutlinedTextField(bookmarkName, { bookmarkName = it }, label = { Text("标题") }, singleLine = true, isError = invalid != null, supportingText = { invalid?.let { Text(it) } }) }
        }, confirmButton = { TextButton({ val title = bookmarkName.trim(); bookmarkPath = null; run("正在保存书签", false) { saveBookmarks(bookmarks + (target to title)) } }, enabled = !busy && invalid == null) { Text("保存") } }, dismissButton = { TextButton({ bookmarkPath = null }) { Text("取消") } })
    }
}

/** Message links use exactly the same guest stat, viewer/installer and download operations as the browser. */
@Composable
internal fun ContainerFileLinkSheet(app: AppCoordinator, host: HostFiles, config: ContainerConfig, source: String,
    onOpenDirectory: (String) -> Unit, onDismiss: () -> Unit) {
    val service = remember(config) { ContainerFileService(app.runtime, config) }
    val actions = remember(service, host) { ContainerFileActions(service, host) }
    val scope = rememberCoroutineScope()
    var file by remember(source) { mutableStateOf<ContainerFile?>(null) }
    var failure by remember(source) { mutableStateOf<String?>(null) }
    var operation by remember(source) { mutableStateOf<String?>(null) }
    var refresh by remember(source) { mutableIntStateOf(0) }
    var text by remember(source) { mutableStateOf<String?>(null) }
    var retry by remember(source) { mutableStateOf<(() -> Unit)?>(null) }
    LaunchedEffect(service, source, refresh) {
        operation = "正在读取文件属性"; failure = null; file = null
        try {
            require(source.startsWith('/') && ContainerFileService.path(source) == source) { "需要规范化的容器绝对路径" }
            file = service.stat(source)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure = error.message ?: error.toString() }
        finally { operation = null }
    }
    fun run(label: String, work: suspend () -> Unit) {
        if (operation != null) return
        operation = label; failure = null
        retry = { run(label, work) }
        scope.launch {
            try { work(); retry = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure = error.message ?: error.toString() }
            finally { operation = null }
        }
    }
    if (text == null) AgentActionSheet({ if (operation == null) onDismiss() }) {
        val info = file
        AgentActionRow(info?.name ?: "容器文件", source, info?.let(::containerFileIcon) ?: "description")
        operation?.let { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(it, Modifier.padding(16.dp)) }
        failure?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        if (info?.directory == true) {
            AgentActionRow("打开", icon = "folder_open", enabled = operation == null, onClick = { onOpenDirectory(source) })
            AgentActionRow("打包下载", icon = "folder_zip", enabled = operation == null && info.readable, onClick = {
                run("正在打包下载") { actions.download(listOf(source), forceZip = true) }
            })
        } else if (info?.regular == true) {
            AgentActionRow("打开", icon = "open_in_new", enabled = operation == null && info.readable, onClick = {
                run(if (containerFileMimeType(info.name) == "application/vnd.android.package-archive") "正在准备系统安装器" else "正在打开文件") { actions.open(source) }
            })
            AgentActionRow("打开所在文件夹", icon = "folder_open", enabled = operation == null, onClick = {
                onOpenDirectory(source.substringBeforeLast('/').ifEmpty { "/" })
            })
            AgentActionRow("编辑", icon = "edit", enabled = operation == null && info.readable, onClick = {
                run("正在读取文本") { text = service.readText(source) }
            })
            AgentActionRow("下载", icon = "download", enabled = operation == null && info.readable, onClick = {
                run("正在下载原文件") { actions.download(listOf(source)) }
            })
        }
        if (info != null && !info.supported) Text("不支持打开符号链接或特殊文件", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        else if (info != null && !info.readable) Text("文件不可读取，请检查当前容器用户权限", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        if (failure != null && operation == null) AgentActionRow("重试", icon = "refresh", onClick = { val failed = retry; if (failed != null) failed() else refresh++ })
    }
    text?.let { content ->
        EditableCodeScreen(file?.name ?: source, content, source.substringAfterLast('.', "").ifEmpty { null },
            onDismiss = { text = null; failure = null; retry = null }, error = failure, saving = operation != null) { draft ->
            run("正在保存文本") { service.writeText(source, draft, content); text = null; refresh++ }
        }
    }
}

@Composable
private fun ContainerFileOpenRows(file: ContainerFile, onOpen: () -> Unit, onText: () -> Unit) {
    val apk = containerFileMimeType(file.name) == "application/vnd.android.package-archive"
    AgentActionRow(if (apk) "安装 APK" else "打开", if (apk) "Android 系统安装器将要求确认；桌面不支持安装" else containerFileMimeType(file.name), if (apk) "android" else "open_in_new", onOpen)
    if (!apk) AgentActionRow("查看文本", "UTF-8，最大 2 MiB；二进制文件请使用打开", "code", onText)
}

@Composable
private fun ContainerFileProperties(source: String, file: ContainerFile) {
    Text(source, style = MaterialTheme.typography.titleMedium)
    Text("类型：${file.kind}\n大小：${bytesLabel(file.size)}\n权限：${file.mode}\nUID / GID：${file.owner} / ${file.group}\n修改：${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(file.modified * 1000))}", Modifier.padding(bottom = 16.dp))
}

@Composable
private fun ContainerFileFilterBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Surface(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).navigationBarsPadding().imePadding(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, onQueryChange, Modifier.weight(1f).focusRequester(focusRequester), singleLine = true, placeholder = { Text("筛选当前目录") }, leadingIcon = { Symbol("search", size = 22.dp) }, trailingIcon = if (query.isNotEmpty()) { { IconButton({ onQueryChange("") }) { Symbol("close", size = 20.dp) } } } else null, shape = RoundedCornerShape(16.dp))
            FileIconButton("close", "关闭筛选", onClose)
        }
    }
}

@Composable
private fun ContainerFileBulkAction(icon: String, label: String, enabled: Boolean, modifier: Modifier, dangerous: Boolean = false, onClick: () -> Unit) {
    val color = when { !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f); dangerous -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurfaceVariant }
    Column(modifier.heightIn(min = 56.dp).clickable(enabled = enabled, onClickLabel = label, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
        Symbol(icon, size = 24.dp, tint = color, contentDescription = label)
        Text(label, color = color, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun FileIconButton(icon: String, description: String, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) { Symbol(icon, contentDescription = description) }
}
