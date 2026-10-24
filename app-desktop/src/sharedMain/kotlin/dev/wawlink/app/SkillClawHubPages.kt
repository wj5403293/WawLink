@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.wawlink.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wawlink.ui.Symbol
import dev.wawlink.ui.SkillContentPreview

@Composable internal fun SkillMarketPage(state: SkillUiState) {
    var sources by remember { mutableStateOf(false) }
    val sourcePages = rememberSaveableStateHolder()
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (state.marketSource == "clawhub") "ClawHub" else "官方 Skillshare", fontSize = 22.sp)
            Text(if (state.marketSource == "clawhub") "公开技能 · 独立目录安装" else "官方技能包 · 声明与锁文件", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            FilledTonalButton({ sources = true }, enabled = state.busy == null) { Symbol("storefront"); Text("切换来源"); Symbol("arrow_drop_down") }
            DropdownMenu(sources, { sources = false }, containerColor = Color.White, shape = RoundedCornerShape(24.dp)) {
                listOf("clawhub" to "ClawHub", "skillshare" to "官方 Skillshare").forEach { (key, label) ->
                    DropdownMenuItem({ Text(label, fontSize = 17.sp) }, { sources = false; state.selectMarket(key) }, leadingIcon = { Symbol(if (state.marketSource == key) "check" else "storefront") })
                }
            }
        }
    }
    sourcePages.SaveableStateProvider(state.marketSource) {
        if (state.marketSource == "clawhub") SkillClawHubMarketPage(state) else SkillOfficialMarketPage(state)
    }
}

@Composable private fun SkillClawHubMarketPage(state: SkillUiState) {
    val data = state.clawMarket
    SkillField("搜索 ClawHub 技能", state.clawQuery, { state.clawQuery = it })
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ state.searchClawHub() }, enabled = state.busy == null) { Symbol("search"); Text("搜索") }
        if (state.clawSearchQuery.isNotEmpty()) TextButton({ state.clawQuery = ""; state.browseClawHub() }, enabled = state.busy == null) { Text("返回浏览") }
        TextButton({ if (state.clawSearchQuery.isEmpty()) state.browseClawHub() else state.searchClawHub() }, enabled = state.busy == null) { Symbol("refresh"); Text("刷新") }
    }
    if (state.clawSearchQuery.isEmpty()) {
        SkillSegments(listOf("downloads" to "下载量", "updated" to "最新", "trending" to "趋势"), state.clawSort) { if (state.busy == null && it != state.clawSort) state.browseClawHub(it) }
    } else {
        SkillHeading("搜索：${state.clawSearchQuery}", "search")
        Text("搜索由 ClawHub 服务端返回；不附加不受支持的作者筛选、排序或分页。", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    SkillBanner("ClawHub 与官方 Skillshare 是不同来源。ClawHub 技能可能使用 OpenClaw 专属工具、运行环境或配置；安装不代表全部能力可在 OMP 中使用。", "info")
    if (data.isNotEmpty()) {
        val rows = data.skillObjects("items")
        rows.forEachIndexed { index, item ->
            SkillRow(item.skillText("name", item.skillText("slug")), "${item.skillText("owner")}/${item.skillText("slug")}\n${item.skillText("description")}\n${item.skillText("version")} · ${item.skillText("downloads", "0")} 次下载 · ${item.skillText("stars", "0")} 星", "school", first = index == 0, last = index == rows.lastIndex, onClick = { state.openClawHub(item) })
        }
        if (rows.isEmpty()) SkillEmpty(if (data["nextCursor"] != null) "当前页没有可显示的技能，可继续读取下一页" else if (state.clawSearchQuery.isNotEmpty()) "没有找到技能" else "ClawHub 当前没有可显示的公开技能", "search_off")
        if (data["nextCursor"] != null && state.clawSearchQuery.isEmpty()) OutlinedButton({ state.moreClawHub() }, Modifier.fillMaxWidth(), enabled = state.busy == null) { Text("继续读取") }
        else if (rows.isNotEmpty()) Text("已显示本次返回的全部结果", Modifier.padding(16.dp), fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else if (state.busy == null && state.error == null) {
        OutlinedButton({ state.browseClawHub() }) { Text("读取 ClawHub") }
    }
}

@Composable internal fun SkillClawHubDetailPage(state: SkillUiState) {
    val data = state.clawDetail
    if (data.isEmpty()) return
    val skill = data.skillObject("skill")
    val version = data.skillObject("version")
    val current = version.skillText("version")
    var installing by remember(skill.skillText("owner"), skill.skillText("slug"), current) { mutableStateOf(false) }
    var raw by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(20.dp)) { Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) { Symbol("school", size = 32.dp, tint = MaterialTheme.colorScheme.onPrimaryContainer) } }
        Column(Modifier.weight(1f)) { Text(skill.skillText("name", skill.skillText("slug")), fontSize = 22.sp); Text("${skill.skillText("owner")}/${skill.skillText("slug")}", fontSize = 16.sp) }
    }
    Text(skill.skillText("description"), fontSize = 17.sp)
    SkillValues(listOf("来源" to "ClawHub", "精确版本" to current, "发布者" to skill.skillText("owner"), "下载量" to skill.skillText("downloads"), "星标" to skill.skillText("stars"), "发布时间" to clawHubDate(version["createdAt"]), "许可证" to version.skillText("license", "未提供")))
    SkillClawHubLink(state, skill.skillText("url"))
    SkillHeading("审核与兼容性", "shield")
    SkillBanner("扫描结果不是安全保证，clean 也不代表没有警告。OpenClaw 与 OMP 的工具、权限、依赖和触发方式可能不同，请检查正文及全部资源。安装不会执行脚本、安装依赖或修改 SKILL.md；之后模型使用时仍可能运行技能中的命令。", "warning", warning = true)
    data.skillStrings("warnings").forEach { SkillBanner(it, "warning", warning = true) }
    val security = version.skillObject("security")
    if (security.isNotEmpty()) SkillValues(security.map { it.key to it.value.toString() })
    else if (version["security"] != null) SkillValues(listOf("服务端审核 / 扫描" to version.skillText("security")))
    else SkillBanner("服务端未提供此版本的审核 / 扫描详情，不表示已通过安全审核。", "info")
    SkillHeading("技能正文", "description")
    when {
        data.skillBool("previewTooLarge") -> SkillBanner("SKILL.md 超出安全预览上限，未截断冒充全文。请到原站检查完整内容。", "info")
        data["content"] == null -> SkillBanner("当前版本没有可用的 SKILL.md 文本预览；请检查资源清单和原站。", "info")
        else -> {
            SkillSegments(listOf("view" to "预览", "raw" to "原文"), if (raw) "raw" else "view") { raw = it == "raw" }
            SkillBanner("安全预览不执行脚本，不自动请求外部图片或打开链接。", "shield")
            if (raw) SelectionContainer { Text(data.skillText("content"), fontFamily = FontFamily.Monospace, fontSize = 16.sp) }
            else SkillContentPreview(data.skillText("content"))
        }
    }
    SkillHeading("版本", "history")
    val versions = data.skillObjects("versions")
    versions.forEachIndexed { index, item ->
        val target = item.skillText("version")
        SkillRow(target, listOf(clawHubDate(item["createdAt"]), item.skillText("changelog")).filter { it.isNotEmpty() }.joinToString("\n"), "history", first = index == 0, last = index == versions.lastIndex, onClick = if (target != current && state.busy == null) ({ state.loadClawHubVersion(target) }) else null, trailing = if (target == current) ({ Symbol("check", tint = MaterialTheme.colorScheme.primary) }) else null)
    }
    if (data["nextCursor"] != null) TextButton({ state.moreClawHubVersions() }, enabled = state.busy == null) { Text("继续读取版本") }
    if (version.skillText("changelog").isNotEmpty()) { SkillHeading("此版本更新说明", "notes"); Text(version.skillText("changelog"), fontSize = 17.sp) }
    val files = version.skillObjects("files")
    SkillHeading("完整资源清单 · ${files.size} 个文件", "folder_open")
    files.forEachIndexed { index, file ->
        val path = file.skillText("path")
        SkillRow(path, "${file.skillText("size")} 字节${if (file["contentType"] != null) " · ${file.skillText("contentType")}" else ""}\nSHA-256 ${file.skillText("sha256")}", if (path.startsWith("scripts/")) "terminal" else "draft", first = index == 0, last = index == files.lastIndex, onClick = { state.previewClawHub(path) })
    }
    Button({ installing = true }, Modifier.fillMaxWidth().padding(top = 16.dp), enabled = state.busy == null && current.isNotEmpty()) { Symbol("download"); Text("安装 $current 到${state.scopeLabel}") }
    SkillBanner("按上方精确版本保存全部资源并校验，不写入官方 Skillshare 的声明或锁文件。当前安装范围由此入口固定。", "info")
    if (installing) SkillClawHubInstallSheet(state, skill, version) { installing = false }
}

@Composable private fun SkillClawHubInstallSheet(state: SkillUiState, skill: SkillObject, version: SkillObject, close: () -> Unit) {
    var name by remember { mutableStateOf(skill.skillText("slug")) }
    var accepted by remember { mutableStateOf(false) }
    val current = version.skillText("version")
    ModalBottomSheet(onDismissRequest = { if (state.busy == null) close() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("确认安装 ClawHub 技能", fontSize = 22.sp)
            SkillValues(listOf("来源身份" to "${skill.skillText("owner")}/${skill.skillText("slug")}", "精确版本" to current, "安装范围" to state.scopeLabel, "完整资源" to "${version.skillObjects("files").size} 个文件"))
            SkillField("本地目录名", name, { name = it }, mono = true)
            SkillBanner("同名目录不会被覆盖。若提示目录冲突，请在此改名后重新确认安装；目录名不修改原始 SKILL.md 的技能名称，加载名冲突请在技能管理中检查别名。", "folder")
            SkillBanner("仅下载并校验全部资源，不执行脚本或安装依赖。技能可包含命令、网络访问或 OpenClaw 专用能力；模型之后使用时可能执行并产生费用，OMP 不保证完全兼容。", "warning", warning = true)
            state.clawDetail.skillStrings("warnings").forEach { SkillBanner(it, "warning", warning = true) }
            Row(Modifier.fillMaxWidth().clickable(enabled = state.busy == null) { accepted = !accepted }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(accepted, { accepted = it }, enabled = state.busy == null)
                Text("我已检查此版本的审核信息、正文与资源，理解风险并确认安装。", Modifier.weight(1f), fontSize = 17.sp)
            }
            state.busy?.let { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(it, fontSize = 16.sp) }
            state.error?.let { SkillBanner(it, "error", error = true) }
            Button({
                state.request("clawhub.install", mapOf("owner" to skill.skillText("owner"), "slug" to skill.skillText("slug"), "version" to current, "name" to name.trim(), "confirm" to true), "正在重新核对版本、下载并校验完整资源") { result ->
                    close()
                    state.notice = "已安装到 ${result.skillText("path")}\n来源 ClawHub · 原版版本 $current\n" + result.skillStrings("warnings").joinToString("\n")
                }
            }, Modifier.fillMaxWidth(), enabled = accepted && name.isNotBlank() && state.busy == null) { Text(if (state.error != null) "按此目录名重新确认安装" else "确认安装到${state.scopeLabel}") }
            TextButton({ close() }, enabled = state.busy == null) { Text("取消") }
        }
    }
}

@Composable internal fun SkillClawHubFileSheet(state: SkillUiState) {
    val path = state.clawFilePath ?: return
    val file = state.clawFile
    var raw by remember(path) { mutableStateOf(!path.endsWith(".md", true)) }
    val clipboard = LocalClipboardManager.current
    ModalBottomSheet(onDismissRequest = { state.clawFilePath = null; state.clawFile = null }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(path, fontSize = 22.sp)
            SkillBanner("有界安全预览：不执行脚本、不请求外部图片；二进制或超限文本不会截断冒充全文。", "shield")
            state.busy?.let { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(it, fontSize = 16.sp) }
            state.error?.let { SkillBanner(it, "error", error = true) { TextButton({ state.previewClawHub(path) }, enabled = state.busy == null) { Text("重试预览") } } }
            if (file != null) {
                Text("${file.skillText("size")} 字节", fontSize = 16.sp)
                when {
                    file.skillBool("binary") -> SkillEmpty("二进制资源，不提供文本预览", "draft")
                    file["content"] == null -> SkillBanner("文本超过预览上限，未返回全文。请到原站查看。")
                    else -> {
                        SkillSegments(listOf("view" to "预览", "raw" to "原文"), if (raw) "raw" else "view") { raw = it == "raw" }
                        TextButton({ clipboard.setText(AnnotatedString(file.skillText("content"))) }) { Symbol("content_copy"); Text("复制原文") }
                        if (raw) SelectionContainer { Text(file.skillText("content"), fontFamily = FontFamily.Monospace, fontSize = 16.sp) }
                        else SkillContentPreview(file.skillText("content"))
                    }
                }
            }
            SkillClawHubLink(state, state.clawDetail.skillObject("skill").skillText("url"))
            TextButton({ state.clawFilePath = null; state.clawFile = null }) { Text("关闭预览") }
        }
    }
}

@Composable internal fun SkillClawHubOrigin(state: SkillUiState, origin: SkillObject) {
    if (origin.skillText("provider") != "clawhub") return
    SkillHeading("安装来源", "download_done")
    SkillValues(listOf("市场" to "ClawHub", "原版身份" to "${origin.skillText("owner")}/${origin.skillText("slug")}", "原版版本" to origin.skillText("version"), "安装时间" to clawHubDate(origin["installedAt"])))
    SkillBanner("这是安装时的来源记录，不认证当前本地编辑内容仍与原版一致。", "info")
    SkillClawHubLink(state, origin.skillText("url"))
}

@Composable private fun SkillClawHubLink(state: SkillUiState, url: String) {
    if (url.isEmpty()) return
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton({ runCatching { uri.openUri(url) }.onFailure { state.error = "无法打开浏览器，请复制原站地址手动打开" } }) { Symbol("open_in_new"); Text("ClawHub 原站") }
        TextButton({ clipboard.setText(AnnotatedString(url)) }) { Symbol("link"); Text("复制原站链接") }
    }
}

@Composable private fun clawHubDate(value: Any?): String = remember(value) {
    runCatching {
        val instant = when (value) {
            is Number -> java.time.Instant.ofEpochMilli(value.toLong())
            is String -> java.time.Instant.parse(value)
            else -> null
        }
        instant?.atZone(java.time.ZoneId.systemDefault())
            ?.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }.getOrNull() ?: "未提供"
}
