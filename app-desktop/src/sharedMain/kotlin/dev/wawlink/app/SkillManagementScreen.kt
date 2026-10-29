@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.wawlink.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wawlink.protocol.AgentResourceRepository
import dev.wawlink.ui.*
import java.util.Base64
import kotlinx.coroutines.launch

@Composable
internal fun SkillManagementScreen(repository: AgentResourceRepository, app: AppCoordinator, files: HostFiles, onBack: () -> Unit, registerBack: ((() -> Boolean)?) -> Unit, onPlugins: (String?) -> Unit, onAgentSettings: () -> Unit, onUseSkill: ((String, String) -> Unit)? = null, initialSkill: String? = null) {
    val scope = rememberCoroutineScope()
    val state = remember(repository) { SkillUiState(repository, scope) }
    val savedPages = rememberSaveableStateHolder()
    val clipboard = LocalClipboardManager.current
    var pageScroll by remember { mutableStateOf<ScrollState?>(null) }
    val barColor by animateColorAsState(if ((pageScroll?.value ?: 0) > 4) Color(0xFFF0F4F9) else Color.White, tween(300, easing = EmphEasing))
    var more by remember { mutableStateOf(false) }
    var paths by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var initialOpened by remember(repository, initialSkill) { mutableStateOf(false) }
    var editorBack by remember { mutableStateOf<(() -> Boolean)?>(null) }
    var preview by remember { mutableStateOf<SkillObject?>(null) }
    var previewRaw by remember { mutableStateOf(false) }
    fun back(): Boolean = when { state.clawFilePath != null -> { state.clawFilePath = null; state.clawFile = null; true }; preview != null -> { preview = null; true }; editorBack != null -> editorBack!!.invoke(); else -> state.back() }
    SideEffect { registerBack(::back) }
    DisposableEffect(Unit) { onDispose { registerBack(null) } }
    LaunchedEffect(repository) {
        state.refresh()
    }
    LaunchedEffect(state.snapshot, initialSkill) {
        if (!initialOpened && initialSkill != null && state.page == "home" && state.history.isEmpty()) state.snapshot.skillObjects("skills").firstOrNull { it.skillText("alias") == initialSkill || it.skillText("name") == initialSkill || it.skillText("id") == initialSkill }?.let { initialOpened = true; state.openSkill(it.skillText("id")) }
    }
    Surface(Modifier.fillMaxSize(), color = Color.White) {
        Scaffold(containerColor = Color.White, topBar = {
            Row(Modifier.fillMaxWidth().height(64.dp).background(barColor).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                SkillIconButton("arrow_back", "返回") { if (!back()) onBack() }
                Text(when (state.page) { "home" -> "技能管理"; "detail" -> state.detail.skillText("alias"); "editor" -> if (state.detail.isEmpty()) "新建技能" else "编辑技能"; "settings" -> "加载设置"; "market" -> "技能市场"; "clawhubDetail" -> state.clawIdentity.skillText("slug"); "package" -> "@${state.packageData.skillObject("packument").skillText("scope")}/${state.packageData.skillObject("packument").skillText("name")}"; "author" -> "作者与发布"; "publish" -> "发布预检与发布"; else -> "技能管理" }, Modifier.weight(1f).padding(start = 4.dp), fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.padding(end = 8.dp)) {
                    Row(Modifier.height(40.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Symbol(if (state.project) "folder" else "person", size = 20.dp); Text(state.scopeLabel, fontSize = 16.sp) }
                }
                Box {
                    SkillIconButton("more_vert", "更多") { more = true }
                    DropdownMenu(more, { more = false }, containerColor = Color.White, shape = RoundedCornerShape(24.dp)) {
                        DropdownMenuItem({ Text("作者与发布") }, { more = false; state.request("author") { state.author = it; state.navigate("author") } }, leadingIcon = { Symbol("publish") })
                        DropdownMenuItem({ Text("导入技能目录 / .skill 发布") }, { more = false; importing = true }, leadingIcon = { Symbol("drive_folder_upload") })
                        DropdownMenuItem({ Text("重新读取技能") }, { more = false; state.refresh() }, leadingIcon = { Symbol("refresh") })
                        DropdownMenuItem({ Text("插件管理") }, { more = false; onPlugins(null) }, leadingIcon = { Symbol("extension") })
                    }
                }
            }
        }, floatingActionButton = {
            if (state.page == "home") ExtendedFloatingActionButton(onClick = { state.detail = emptyMap(); state.navigate("editor") }, icon = { Symbol("add") }, text = { Text("新建", fontSize = 17.sp) }, containerColor = MaterialTheme.colorScheme.primaryContainer)
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                state.busy?.let { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(it, Modifier.padding(horizontal = 20.dp), fontSize = 16.sp) }
                state.error?.let { SkillBanner(it, "error", error = true) { Row { if (state.canRetry) TextButton({ state.retry() }) { Text("重试") }; TextButton({ state.error = null }) { Text("关闭") } } } }
                state.notice?.let { SkillBanner(it, "check_circle") { TextButton({ state.notice = null }) { Text("知道了") } } }
                AnimatedContent(state.page to state.history.size, transitionSpec = {
                    val forward = targetState.second > initialState.second
                    (slideInHorizontally(tween(550, easing = CubicBezierEasing(.05f, .7f, .1f, 1f))) { if (forward) it else -it / 5 } + fadeIn(tween(400))).togetherWith(
                        slideOutHorizontally(tween(360, easing = CubicBezierEasing(.3f, 0f, .8f, .15f))) { if (forward) -it / 5 else it } + fadeOut(tween(300)))
                }, label = "技能页面导航") { destination ->
                    val page = destination.first
                    savedPages.SaveableStateProvider(page) {
                    if (page == "editor") {
                        SideEffect { pageScroll = null }
                        SkillEditorPage(state, { editorBack = it })
                    } else {
                    val scroll = rememberScrollState()
                    SideEffect { if (page == state.page) pageScroll = scroll }
                    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 140.dp)) {
                        when (page) {
                            "home" -> SkillHomePage(state, { paths = true })
                            "detail" -> SkillDetailPage(state, onPlugins, onAgentSettings, onUseSkill) { file ->
                                state.request("resource", mapOf("id" to state.detail.skillText("id"), "file" to file)) { preview = it + ("name" to file) + if (file == "SKILL.md") mapOf("body" to state.detail.skillText("body")) else emptyMap(); previewRaw = !file.endsWith(".md") }
                            }
                            "settings" -> SkillSettingsPage(state)
                            "market" -> SkillMarketPage(state)
                            "clawhubDetail" -> SkillClawHubDetailPage(state)
                            "package" -> SkillPackagePage(state) { file ->
                                val pack = state.packageData.skillObject("packument")
                                val id = "@${pack.skillText("scope")}/${pack.skillText("name")}"
                                val version = state.packageData.skillObject("version").skillText("version")
                                state.request("packageFile", mapOf("id" to id, "version" to version, "path" to file), "正在读取技能包文件") {
                                    if (state.page == "package" && state.packageData.skillObject("packument") == pack && state.packageData.skillObject("version").skillText("version") == version) {
                                        preview = it + ("name" to file); previewRaw = !file.endsWith(".md", true)
                                    }
                                }
                            }
                            "author" -> SkillAuthorPage(state)
                            "publish" -> SkillPublishPage(state)
                        }
                    }
                    }
                    }
                }
            }
        }
        if (paths) ModalBottomSheet({ paths = false }) { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("实际读写的目录", fontSize = 22.sp)
            SkillValues(listOf("容器" to repository.context.containerName, "配置档" to repository.context.profile, "范围" to state.scopeLabel, "工作区" to (repository.context.projectPath ?: "无项目 · 仅用户级")) + state.snapshot.skillObject("paths").map { it.key to it.value.toString() } + state.snapshot.skillObject("packagePaths").map { it.key to it.value.toString() } + listOf("内容 store" to state.snapshot.skillText("store")))
            SkillBanner("目录来自当前容器与原生配置解析；不是宿主机目录。当前入口固定为${state.scopeLabel}范围。")
        } }
        if (importing) SkillImportSheet(state, files, { importing = false })
        if (state.clawFilePath != null) SkillClawHubFileSheet(state)
        preview?.let { resource -> Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) { SkillIconButton("arrow_back", "关闭预览") { preview = null }; Text(resource.skillText("name"), Modifier.weight(1f), fontSize = 22.sp); SkillIconButton("content_copy", "复制原文") { clipboard.setText(AnnotatedString(resource.skillText("content"))) } }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                SkillBanner("安全预览：不会执行脚本，不自动打开链接或请求外部图片。", "shield")
                when {
                    resource["base64"] != null -> {
                        val bitmap = remember(resource) { runCatching { rasterImage(Base64.getDecoder().decode(resource.skillText("base64")), resource.skillText("name").substringAfterLast('.')) }.getOrNull() }
                        if (bitmap != null) Image(bitmap, resource.skillText("name"), Modifier.fillMaxWidth().heightIn(max = 560.dp)) else SkillBanner("图片无法解码", error = true)
                    }
                    resource.skillBool("binary") -> SkillEmpty("二进制资源 · ${resource.skillText("size")} 字节", "draft")
                    else -> {
                        SkillSegments(listOf("view" to "预览", "raw" to "原文"), if (previewRaw) "raw" else "view") { previewRaw = it == "raw" }
                        if (previewRaw) androidx.compose.foundation.text.selection.SelectionContainer { Text(resource.skillText("content"), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 15.sp) }
                        else SkillContentPreview(resource.skillText("body", resource.skillText("content")))
                    }
                }
            }
        } } }
        state.scriptRequest?.let { approval -> AlertDialog(onDismissRequest = { state.scriptRequest = null; state.pendingOperation = null }, icon = { Symbol("terminal") }, title = { Text("技能包包含脚本") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text("${approval.skillText("id")}@${approval.skillText("version")} 安装时不会执行脚本，但模型使用时可能运行，且可能产生费用。仅批准这一个精确版本。"); approval.skillObjects("files").forEach { Text(it.skillText("path"), fontSize = 16.sp) } } }, confirmButton = { Button({ state.approveScripts() }) { Text("继续安装") } }, dismissButton = { TextButton({ state.scriptRequest = null; state.pendingOperation = null }) { Text("取消") } }) }
    }
}

@Composable private fun SkillHomePage(state: SkillUiState, showPaths: () -> Unit) {
    var view by rememberSaveable { mutableStateOf("found") }; var query by rememberSaveable { mutableStateOf("") }; var filter by rememberSaveable { mutableStateOf("all") }; var warnings by remember { mutableStateOf(false) }
    val snapshot = state.snapshot
    val omp = snapshot.skillText("ompVersion")
    Surface(color = Color(0xFFF0F4F9), shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) { Column(Modifier.padding(18.dp)) {
        SkillContextLine("dns", "容器 ${state.repository.context.containerName}" + if (omp.isNotEmpty()) "，OMP $omp" else "")
        SkillContextLine("account_circle", "配置档 ${state.repository.context.profile}")
        SkillContextLine(if (state.project) "folder" else "person", state.repository.context.projectPath ?: "用户级技能；不读取项目配置")
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable(onClick = showPaths), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) { Symbol("route", tint = MaterialTheme.colorScheme.onSurfaceVariant); Text("实际读写的目录", Modifier.weight(1f), fontSize = 17.sp); Symbol("chevron_right", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    } }
    if (state.changed) SkillBanner("已保存；运行中的会话尚未重载。新会话或重启后生效，已有历史内容不会被撤回。", "pending", warning = true)
    val settings = snapshot.skillObjects("settings")
    val loading = when {
        snapshot.isEmpty() -> "读取中"
        settings.firstOrNull { it.skillText("key") == "skills.enabled" }?.skillBool("value") == false -> "已关闭"
        settings.firstOrNull { it.skillText("key") == "skillful" }?.skillBool("value") == false -> "目录已隐藏"
        else -> "全部开启"
    }
    Row(Modifier.padding(top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(Triple("storefront", "技能市场", if (state.marketSource == "clawhub") "ClawHub" else "官方 Skillshare"), Triple("tune", "加载设置", loading)).forEachIndexed { i, (icon, label, value) ->
            Surface(Modifier.weight(1f).heightIn(min = 112.dp).clickable { if (i == 0) state.openMarket() else state.navigate("settings") }, color = Color(0xFFF8FAFD), shape = RoundedCornerShape(24.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Symbol(icon, tint = MaterialTheme.colorScheme.primary); Text(label, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
        }
    }
    SkillSegments(listOf("found" to "已发现", "packages" to "已安装的技能包"), view) { view = it }
    if (view == "packages") SkillInstalledPage(state)
    else {
        PluginSearch(query, { query = it }, "搜索名称、别名或描述")
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 2.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all" to "全部", "native" to state.scopeLabel, "skillshare" to "技能包", "plugins" to "插件", "compat" to "兼容目录", "managed" to "自动学习", "flag" to "隐藏与过滤", "conflict" to "冲突").forEach { (key, title) -> FilterChip(filter == key, { filter = key }, { Text(title, fontSize = 16.sp) }, shape = RoundedCornerShape(12.dp), leadingIcon = if (filter == key) ({ Symbol("check", size = 20.dp) }) else null, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer, selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer)) }
        }
        if (snapshot.skillObjects("warnings").isNotEmpty()) SkillBanner("${snapshot.skillObjects("warnings").size} 条加载警告", "warning", warning = true) { TextButton({ warnings = true }) { Text("查看") } }
        val entries = snapshot.skillObjects("skills").filter { s -> val source = s.skillText("source"); val flags = s.skillStrings("flags");
            (query.isEmpty() || listOf(s.skillText("name"), s.skillText("alias"), s.skillText("description")).any { it.contains(query, true) }) &&
                (filter == "all" || source == filter || filter == "plugins" && source.contains("plugins") || filter == "compat" && source in listOf("claude", "codex", "agents", "opencode", "github", "custom") || filter == "flag" && flags.isNotEmpty() || filter == "conflict" && ("conflict" in flags || s.skillObjects("shadows").isNotEmpty())) }
        Spacer(Modifier.height(4.dp))
        entries.forEachIndexed { i, s ->
            val source = s.skillText("source")
            val flags = s.skillStrings("flags")
            val icon = when (source) { "native" -> if (state.project) "folder" else "person"; "skillshare" -> "package_2"; "managed", "managed-skills" -> "auto_awesome"; else -> if (source.contains("plugins")) "extension" else "folder_special" }
            SkillRow(s.skillText("alias"), s.skillText("description"), icon, first = i == 0, last = i == entries.lastIndex, muted = flags.any { it in listOf("ignored", "disabled", "inactive", "excluded") }, onClick = { state.openSkill(s.skillText("id")) }, metadata = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val origin = s.skillObject("origin")
                    Text(if (origin.skillText("provider") == "clawhub") "ClawHub · ${state.scopeLabel}" else if (source == "native") state.scopeLabel else if (source == "skillshare") "技能包" else skillSource(source), Modifier.background(Color(0xFFE9EEF6), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 3.dp), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    val status = flags.firstOrNull()?.let(::skillFlag) ?: if (origin.skillText("provider") == "clawhub") "原版 ${origin.skillText("version")}" else s.skillText("packageId").let { if (it.isEmpty()) "" else "$it ${s.skillText("packageVersion")}" }
                    if (status.isNotEmpty()) Text(status, Modifier.weight(1f), fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            })
        }
        if (entries.isEmpty() && state.busy == null && snapshot.isNotEmpty()) SkillEmpty(if (query.isEmpty()) "还没有发现技能" else "没有符合条件的技能", if (query.isEmpty()) "school" else "search_off")
        PluginHint("按来源优先级和名称排序。隐藏、过滤和被遮蔽都不是卸载。", "sort")
    }
    if (warnings) PluginSheet("未能加载与冲突警告", onClose = { warnings = false }) { snapshot.skillObjects("warnings").forEach { SkillRow(it.skillText("skillPath"), it.skillText("message"), "warning") } }
}
@Composable private fun SkillContextLine(icon: String, text: String) { Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) { Symbol(icon, tint = MaterialTheme.colorScheme.onSurfaceVariant); Text(text, Modifier.weight(1f), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) } }

@Composable private fun SkillDetailPage(state: SkillUiState, onPlugins: (String?) -> Unit, onAgents: () -> Unit, onUse: ((String, String) -> Unit)?, onPreview: (String) -> Unit) {
    val s = state.detail; var copy by remember { mutableStateOf(false) }; var name by remember(s) { mutableStateOf(s.skillText("name")) }; var delete by remember { mutableStateOf(false) }; var use by remember { mutableStateOf(false) }; var args by remember { mutableStateOf("") }; var more by remember { mutableStateOf(false) }; val clipboard = LocalClipboardManager.current
    Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) { Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) { Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) { Symbol("school", size = 32.dp, tint = MaterialTheme.colorScheme.onPrimaryContainer) } }; Column(Modifier.weight(1f)) { Text(s.skillText("alias"), fontSize = 22.sp); Text(skillSource(s.skillText("source")), fontSize = 16.sp) }; Box { SkillIconButton("more_vert", "技能操作") { more = true }; DropdownMenu(more, { more = false }) {
        DropdownMenuItem({ Text("复制读取地址") }, { more = false; clipboard.setText(AnnotatedString("skill://${s.skillText("alias")}")) }, leadingIcon = { Symbol("link") })
        DropdownMenuItem({ Text("复制容器目录地址") }, { more = false; clipboard.setText(AnnotatedString(s.skillText("path"))) }, leadingIcon = { Symbol("folder_open") })
        DropdownMenuItem({ Text(if ("disabled" in s.skillStrings("flags")) "取消禁用" else "禁用这个技能") }, { more = false; state.request("disable", mapOf("id" to s.skillText("id"), "disabled" to ("disabled" !in s.skillStrings("flags"))), "正在保存禁用列表") { state.request("detail", mapOf("id" to s.skillText("id"))) { state.detail = it } } }, leadingIcon = { Symbol("block") })
    } } }
    Text(s.skillText("description"), fontSize = 17.sp)
    SkillClawHubOrigin(state, s.skillObject("origin"))
    s.skillStrings("flags").forEach { SkillBanner(skillFlag(it), if (it == "conflict") "call_split" else "visibility_off", warning = it == "conflict") }
    if (!s.skillBool("writable")) SkillBanner(if (s.skillText("source").contains("plugin")) "技能属于插件，升级和卸载请到插件管理；直接改缓存不是正确做法。" else if (s.skillText("source").contains("managed")) "自动学习技能保留专用写入边界，不在此直接编辑。" else "该来源只读；要修改请复制为独立技能，所有相对资源会一起复制。") { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { if (s.skillText("source").contains("plugin")) TextButton({ onPlugins(s.skillObject("sourceMeta").skillText("pluginName").ifEmpty { null }) }) { Text("转到所属插件") }; TextButton({ copy = true }) { Text("复制为独立技能") } } }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilledTonalButton({ onPreview("SKILL.md") }) { Symbol("visibility"); Text("查看内容") }; if (onUse != null) OutlinedButton({ use = true }) { Symbol("play_arrow"); Text("在会话中使用") } }
    SkillHeading("身份", "badge")
    SkillValues(listOf("原始名" to s.skillText("name"), "加载名" to s.skillText("alias"), "来源优先级" to s.skillText("priority"), "路径" to s.skillText("path"), "原生来源" to s.skillText("source"), "来源元数据" to s.skillObject("sourceMeta").toString()))
    if (s["packageId"] != null) {
        SkillValues(listOf("技能包" to s.skillText("packageId"), "版本" to s.skillText("packageVersion"), "安装范围" to state.scopeLabel))
        TextButton({ state.openPackage(s.skillText("packageId"), s.skillText("packageVersion")) }) { Symbol("package_2"); Text("查看技能包") }
    }
    if (s.skillObjects("shadows").isNotEmpty()) { SkillHeading("同名来源", "layers"); s.skillObjects("shadows").forEach { SkillRow(skillSource(it.skillText("source")), it.skillText("path"), "layers") } }
    SkillHeading("模型目录", "visibility"); SkillValues(listOf("完整描述" to s.skillText("description"), "目录状态" to if (s.skillStrings("flags").isEmpty()) "已选用" else s.skillStrings("flags").joinToString(" · ", transform = ::skillFlag)))
    SkillBanner("隐藏仅影响模型目录，不是访问控制。allowed-tools 只是声明；globs 不会使技能按文件自动触发。")
    SkillHeading("资源", "folder_open")
    s.skillObjects("resources").forEachIndexed { index, file -> SkillRow(file.skillText("path"), if (file.skillBool("skipped")) file.skillText("reason") else if (file.skillBool("executable")) "可执行脚本 · ${file.skillText("size")} 字节" else if (file.skillBool("directory")) "目录" else "${file.skillText("size")} 字节", if (file.skillBool("directory")) "folder" else if (file.skillBool("executable")) "terminal" else "draft", first = index == 0, last = index == s.skillObjects("resources").lastIndex, onClick = if (!file.skillBool("directory") && !file.skillBool("skipped")) ({ onPreview(file.skillText("path")) }) else null) }
    SkillHeading("引用它的 Agent", "smart_toy"); s.skillStrings("agentRefs").forEach { SkillRow(it, "启动前自动加载 · 查看 Agent 定义", "smart_toy", onClick = onAgents) }; if (s.skillStrings("agentRefs").isEmpty()) SkillEmpty("没有 Agent 自动加载它", "link_off")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { if (s.skillBool("writable")) FilledTonalButton({ state.navigate("editor") }) { Symbol("edit"); Text("编辑") }; OutlinedButton({ copy = true }) { Symbol("content_copy"); Text("复制") }; if (s.skillBool("writable")) TextButton({ delete = true }) { Text("删除", color = MaterialTheme.colorScheme.error) } }
    if (copy) AlertDialog(onDismissRequest = { copy = false }, title = { Text("复制为独立技能") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { SkillField("新名称", name, { name = it }); Text("保存到${state.scopeLabel}技能目录，复制全部普通资源并保留权限；跳过链接和越界文件。"); s.skillObjects("resources").forEach { Text(it.skillText("path"), fontSize = 16.sp) } } }, confirmButton = { Button({ state.request("copy", mapOf("id" to s.skillText("id"), "name" to name), "正在安全复制") { copy = false; state.notice = "已复制到 ${it.skillText("path")}" } }) { Text("复制") } }, dismissButton = { TextButton({ copy = false }) { Text("取消") } })
    if (delete) AlertDialog(onDismissRequest = { delete = false }, icon = { Symbol("delete") }, title = { Text("删除 ${s.skillText("alias")}？") }, text = { Text("将删除整个目录 ${s.skillText("path")}，包含 ${s.skillObjects("resources").count { !it.skillBool("directory") }} 个文件。\nAgent 引用不会自动修改：${s.skillStrings("agentRefs").joinToString()}\n低优先级同名技能可能重新生效。") }, confirmButton = { Button({ state.request("delete", mapOf("id" to s.skillText("id"), "version" to s.skillText("version")), "正在删除技能") { if (it.skillBool("conflict")) state.error = "文件已被外部修改，请重新读取后再删除" else { delete = false; state.back() } } }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("删除整个目录") } }, dismissButton = { TextButton({ delete = false }) { Text("取消") } })
    if (use) AlertDialog(onDismissRequest = { use = false }, title = { Text("在会话中使用") }, text = { Column { Text("会发送 /skill:${s.skillText("alias")} 并开始模型任务，可能调用工具、运行脚本和产生费用。仅查看请使用预览。"); SkillField("附加说明（可选）", args, { args = it }, false) } }, confirmButton = { Button({ use = false; onUse?.invoke(s.skillText("alias"), args) }) { Text("发送到会话") } }, dismissButton = { TextButton({ use = false }) { Text("取消") } })
}
