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

/** User configuration is always read and written as the configured guest user. */
@Composable
internal fun ContainerRulesScreen(app: AppCoordinator, config: ContainerConfig, onBack: () -> Unit,
    registerBack: ((() -> Boolean)?) -> Unit) {
    key(config) { RulesBrowser(app, config, GuestConfigDirectory.RULES, onBack, registerBack) }
}

@Composable
internal fun ContainerPromptsScreen(app: AppCoordinator, config: ContainerConfig, onBack: () -> Unit,
    registerBack: ((() -> Boolean)?) -> Unit) {
    key(config) { RulesBrowser(app, config, GuestConfigDirectory.PROMPTS, onBack, registerBack) }
}

private enum class GuestConfigDirectory(val segment: String, val label: String, val icon: String, val example: String) {
    RULES("rules", "规则", "rule_folder", "instructions.md"),
    PROMPTS("prompts", "提示词", "chat_paste_go", "review.md"),
}

private data class PromptContextFile(val name: String, val description: String)
private val PromptContextFiles = listOf(
    PromptContextFile("AGENTS.md", "全局 Agent 上下文说明，主会话与顾问自动注入"),
    PromptContextFile("RULES.md", "全局常驻硬性规则，每次请求始终携带"),
    PromptContextFile("SYSTEM.md", "自定义系统提示词，替换默认主提示词模板"),
    PromptContextFile("SYSTEM_TEMPLATE.md", "Handlebars 系统提示词模板"),
    PromptContextFile("APPEND_SYSTEM.md", "追加系统提示词，附加在默认提示词末尾"),
    PromptContextFile("PERSONALITY.md", "自定义人格说明，替换默认 personality 预设"),
    PromptContextFile("TITLE_SYSTEM.md", "自动会话标题生成提示词"),
)

private data class RulesEditor(val path: String, val original: String, val newContextFile: Boolean = false)
private data class RulesNameAction(val directory: String, val folder: Boolean = false, val source: ContainerFile? = null)
private val RulesEasing = CubicBezierEasing(.2f, 0f, 0f, 1f)

@Composable
private fun RulesBrowser(app: AppCoordinator, config: ContainerConfig, kind: GuestConfigDirectory, onBack: () -> Unit,
    registerBack: ((() -> Boolean)?) -> Unit) {
    val files = remember(app.runtime, config) { ContainerFileService(app.runtime, config) }
    val scope = rememberCoroutineScope()
    var root by remember { mutableStateOf<String?>(null) }
    var path by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf(emptyList<ContainerFile>()) }
    var contextEntries by remember { mutableStateOf<Map<String, ContainerFile>?>(null) }
    var search by remember { mutableStateOf("") }
    var operation by remember { mutableStateOf<String?>("正在定位用户${kind.label}目录") }
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

    fun contextDirectory(): String {
        check(kind == GuestConfigDirectory.PROMPTS) { "仅提示词管理支持上下文文件" }
        return requireNotNull(root).substringBeforeLast('/')
    }
    suspend fun refreshContext() {
        if (kind == GuestConfigDirectory.PROMPTS) {
            contextEntries = files.list(contextDirectory()).entries.associateBy { it.name }
        }
    }

    suspend fun load(target: String) {
        val boundary = requireNotNull(root)
        require(target == boundary || target.startsWith("$boundary/")) { "只能浏览当前用户的${kind.label}目录" }
        val result = files.list(target)
        refreshContext()
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
        for (segment in listOf(".omp", "agent", kind.segment)) {
            val listing = files.list(directory)
            val existing = listing.entries.firstOrNull { it.name == segment }
            if (existing == null) files.create(directory, segment, folder = true)
            else require(existing.directory) { "${kind.label}路径包含符号链接或非目录：${ContainerFileService.child(directory, segment)}" }
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
            retry = { run("正在定位用户${kind.label}目录") { initialize() } }
        } finally { operation = null }
    }
    fun navigate(target: String) {
        if (target == path) return
        run("正在读取${kind.label}目录") { load(target); search = "" }
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
            file.regular -> run("正在读取 UTF-8 ${kind.label}") {
                editor = RulesEditor(target, files.readText(target))
            }
            else -> failure = "符号链接和特殊文件仅显示，不会跟随、编辑或删除。"
        }
    }
    fun openContext(file: PromptContextFile) {
        run("正在读取 ${file.name}") {
            refreshContext()
            val existing = requireNotNull(contextEntries)[file.name]
            val target = ContainerFileService.child(contextDirectory(), file.name)
            if (existing == null) editor = RulesEditor(target, "", newContextFile = true)
            else {
                require(existing.regular) { "不能编辑符号链接、目录或特殊文件：$target" }
                editor = RulesEditor(target, files.readText(target))
            }
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
    val groups = listOf("文件夹" to sorted.filter { it.directory }, "${kind.label}文件" to sorted.filter { it.regular },
        "链接与特殊文件" to sorted.filter { !it.supported })

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                AgentIconButton("arrow_back", "返回", { if (!browserBack()) onBack() })
                Text("${kind.label}管理", Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.titleLarge)
                AgentIconButton("refresh", "刷新${kind.label}", { path?.let { current -> run("正在刷新${kind.label}") { load(current) } } }, enabled = !busy && path != null)
                Box {
                    AgentIconButton("add", "新建${kind.label}或文件夹", { createMenu = true }, enabled = !busy && path != null)
                    DropdownMenu(createMenu, { createMenu = false }) {
                        DropdownMenuItem({ Text("新建${kind.label}文件") }, { path?.let { startName(RulesNameAction(it)) } }, leadingIcon = { Symbol("note_add") })
                        DropdownMenuItem({ Text("新建文件夹") }, { path?.let { startName(RulesNameAction(it, folder = true)) } }, leadingIcon = { Symbol("create_new_folder") })
                    }
                }
            }
            Column(Modifier.widthIn(max = AgentPageMaxWidth).fillMaxWidth().align(Alignment.CenterHorizontally).padding(horizontal = 20.dp)) {
                Text("用户${kind.label}", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                Text("${config.displayName} · ${config.user}", style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                Text(root ?: "正在读取容器内 HOME…", style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp),
                    enabled = path != null && !busy, singleLine = true, shape = RoundedCornerShape(28.dp),
                    placeholder = { Text("搜索当前目录的${kind.label}") }, leadingIcon = { Symbol("search") },
                    trailingIcon = { if (search.isNotEmpty()) AgentIconButton("close", "清除搜索", { search = "" }) })
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    val boundary = root
                    val current = path
                    TextButton({ boundary?.let(::navigate) }, enabled = !busy && boundary != null) {
                        Symbol(kind.icon, size = 21.dp)
                        Text(kind.label, Modifier.padding(start = 6.dp))
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
                            ErrorMessage(message, color = MaterialTheme.colorScheme.onErrorContainer)
                            if (retry != null) TextButton({ retry?.invoke() }, enabled = !busy) { Text("重试") }
                        }
                    }
                } }
                if (kind == GuestConfigDirectory.PROMPTS) {
                    item("context-heading") {
                        Text("内置上下文与系统提示词", color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, start = 4.dp))
                    }
                    itemsIndexed(PromptContextFiles, key = { _, file -> "context-${file.name}" }) { index, file ->
                        val existing = contextEntries?.get(file.name)
                        val status = when {
                            contextEntries == null -> "未读取"
                            existing == null -> "未创建"
                            existing.regular -> "已创建"
                            else -> "不可编辑"
                        }
                        val shape = RoundedCornerShape(topStart = if (index == 0) 22.dp else 5.dp,
                            topEnd = if (index == 0) 22.dp else 5.dp,
                            bottomStart = if (index == PromptContextFiles.lastIndex) 22.dp else 5.dp,
                            bottomEnd = if (index == PromptContextFiles.lastIndex) 22.dp else 5.dp)
                        Row(Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .clickable(enabled = !busy && contextEntries != null) { openContext(file) }
                            .heightIn(min = 88.dp).padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                                Symbol("description", size = 26.dp, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(file.name, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(file.description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                            }
                            Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                        }
                    }
                }
                if (path != null && sorted.isEmpty() && !busy && failure == null) item("empty") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Symbol(if (search.isNotBlank()) "search_off" else kind.icon, size = 48.dp, tint = MaterialTheme.colorScheme.primary)
                        Text(if (search.isNotBlank()) "没有匹配的条目" else "此目录暂无${kind.label}", style = MaterialTheme.typography.titleLarge)
                        Text(if (search.isNotBlank()) "试试其他名称" else "点击右上角 + 新建${kind.label}文件或文件夹", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                .combinedClickable(enabled = !busy, onClick = { open(file) }, onLongClick = { entryMenu = file.name }, onLongClickLabel = "${kind.label}操作")
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
                                            DropdownMenuItem({ Text(if (file.directory) "进入文件夹" else "编辑${kind.label}") }, { entryMenu = null; open(file) }, leadingIcon = { Symbol(if (file.directory) "folder_open" else "edit") })
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
                    run("正在安全保存${kind.label}") {
                        if (document.newContextFile) {
                            val directory = contextDirectory()
                            val name = document.path.substringAfterLast('/')
                            require(PromptContextFiles.any { it.name == name } && document.path == ContainerFileService.child(directory, name)) {
                                "只能创建当前用户的上下文文件"
                            }
                            RulesCreateContextFile(app.runtime, config, directory, name, draft)
                        } else files.writeText(document.path, draft, expectedText = document.original)
                        editor = null
                        editorFailure = null
                        path?.let { load(it) }
                    }
                })
        } }
    }
    nameAction?.let { action ->
        val invalid = ContainerFileService.nameError(name)
        val title = if (action.source != null) "重命名" else if (action.folder) "新建文件夹" else "新建${kind.label}文件"
        AgentSheet(title, { if (!busy) { nameAction = null; failure = null } }) {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(action.directory, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("名称") }, singleLine = true,
                    enabled = !busy, shape = RoundedCornerShape(16.dp), isError = invalid != null && name.isNotEmpty(),
                    supportingText = { Text(invalid?.takeIf { name.isNotEmpty() } ?: if (action.folder || action.source != null) "不会覆盖同名条目" else "例如 ${kind.example}；不会覆盖同名条目") })
                failure?.let { ErrorMessage(it) }
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
            title = { Text(if (file.directory) "删除此文件夹及全部内容？" else "删除此${kind.label}文件？") },
            text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(source.orEmpty())
                Text("此操作无法撤销。删除前会检查符号链接、特殊文件、受保护挂载及权限。外部并发修改仍可能造成部分删除；失败后请刷新目录检查。")
                failure?.let { ErrorMessage(it) }
            } },
            confirmButton = { TextButton({
                if (source != null) run("正在删除${kind.label}") {
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

/** A single guest process publishes a new file without replacing an existing entry. */
private suspend fun RulesCreateContextFile(runtime: ContainerRuntime, config: ContainerConfig, directory: String, name: String, text: String) = withContext(Dispatchers.IO) {
    require(text.length <= 2 * 1024 * 1024 && '\u0000' !in text) { "仅支持不含 NUL、最大 2 MiB 的 UTF-8 文本" }
    val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text))
    require(encoded.remaining() <= 2 * 1024 * 1024) { "文件超过 2 MiB，请导出后编辑" }
    val bytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
    val temporary = ".wawlink-context-${java.util.UUID.randomUUID()}.part"
    val script = """
        set -eu
        directory=${'$'}1; target=${'$'}directory/${'$'}2; temporary=${'$'}directory/${'$'}3; expected_size=${'$'}4
        created=0; identity=
        fail() { printf '%s\n' "${'$'}*" >&2; exit 1; }
        checked_dir() {
            [ "${'$'}1" = / ] && return 0
            [ -z "${'$'}1" ] && return 0
            checked_dir "${'$'}{1%/*}" && [ ! -L "${'$'}1" ] && [ -d "${'$'}1" ] && [ -x "${'$'}1" ]
        }
        cleanup() {
            if [ "${'$'}created" = 1 ] && checked_dir "${'$'}directory" && [ -f "${'$'}temporary" ] && [ ! -L "${'$'}temporary" ] &&
                [ "${'$'}(stat -c '%d:%i' -- "${'$'}temporary" 2>/dev/null || true)" = "${'$'}identity" ]; then
                rm -f -- "${'$'}temporary" || :
            fi
        }
        trap cleanup EXIT
        trap 'exit 1' HUP INT TERM
        checked_dir "${'$'}directory" || fail '上下文目录包含符号链接、非目录或不可访问目录'
        [ ! -e "${'$'}target" ] && [ ! -L "${'$'}target" ] || fail '文件已存在，请保留草稿并重新打开'
        umask 077
        set -C
        exec 3> "${'$'}temporary"
        set +C
        created=1
        identity=${'$'}(stat -c '%d:%i' -- "${'$'}temporary")
        cat >&3
        exec 3>&-
        checked_dir "${'$'}directory" || fail '上下文目录已改变，尚未保存'
        [ -f "${'$'}temporary" ] && [ ! -L "${'$'}temporary" ] &&
            [ "${'$'}(stat -c '%d:%i' -- "${'$'}temporary")" = "${'$'}identity" ] || fail '暂存文件已改变，尚未保存'
        [ "${'$'}(stat -c %s -- "${'$'}temporary")" = "${'$'}expected_size" ] || fail '文本写入不完整，尚未保存'
        [ ! -e "${'$'}target" ] && [ ! -L "${'$'}target" ] || fail '文件已存在，请保留草稿并重新打开'
        ln -T -- "${'$'}temporary" "${'$'}target" || fail '无法安全创建文件；未覆盖已有条目'
    """.trimIndent()
    val process = runtime.openProcess(config.id, listOf("/bin/sh", "-c", script, "wawlink-context", directory, name, temporary, bytes.size.toString()), "/")
    try {
        coroutineScope {
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { process.destroy() }
            }
            try {
                val errors = async { runInterruptible { process.errorStream.use { RulesReadOutput(it) } } }
                val writer = async {
                    try { runInterruptible { process.outputStream.use { it.write(bytes) } }; Result.success(Unit) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { Result.failure<Unit>(failure) }
                }
                runInterruptible { process.inputStream.use { RulesReadOutput(it) } }
                val status = runInterruptible { process.waitFor() }
                val message = errors.await()
                val written = writer.await()
                check(status == 0) { message.ifBlank { "无法创建上下文文件（$status）" } }
                written.getOrThrow()
            } finally { cancellation.cancel() }
        }
    } finally {
        process.destroy()
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.outputStream.close() }
    }
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
