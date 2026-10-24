@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.wawlink.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wawlink.ui.EditableCodeScreen
import dev.wawlink.ui.SkillContentPreview
import dev.wawlink.ui.Symbol
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@Composable internal fun SkillEditorPage(state: SkillUiState, registerBack: ((() -> Boolean)?) -> Unit) {
    val original = state.detail
    var content by remember(original) { mutableStateOf(original.skillText("content").ifEmpty { "---\nname: \ndescription: \n---\n# 新技能\n\n" }) }
    var baseline by remember(original) { mutableStateOf(content) }
    var version by remember(original) { mutableStateOf(original.skillText("version")) }
    var fields by remember(original) { mutableStateOf(original.skillObject("fields")) }
    var body by remember(original) { mutableStateOf(original.skillText("body").ifEmpty { "# 新技能\n\n" }) }
    var strict by remember { mutableStateOf(false) }; var raw by remember { mutableStateOf(false) }; var preview by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf<String?>(null) }; var discard by remember { mutableStateOf(false) }; var conflicts by remember { mutableStateOf<SkillObject?>(null) }; var diff by remember { mutableStateOf(false) }; var validation by remember { mutableStateOf<SkillObject>(emptyMap()) }
    var fullscreenDismiss by remember { mutableStateOf<(() -> Unit)?>(null) }
    val dirty = content != baseline
    fun leave() { if (fullscreen != null) fullscreenDismiss?.invoke() else if (dirty) discard = true else { registerBack(null); state.back() } }
    SideEffect { registerBack { leave(); true } }
    DisposableEffect(Unit) { onDispose { registerBack(null) } }
    fun save(expected: String = version) {
        val new = original.isEmpty()
        state.request(if (new) "create" else "save", mapOf("id" to original.skillText("id"), "version" to expected, "content" to content, "strict" to strict), "正在原子保存") { result ->
            when { result.skillBool("conflict") -> conflicts = result
                result["validation"] != null -> validation = result.skillObject("validation")
                else -> { baseline = content; version = result.skillText("version", version); state.notice = "已保存；新会话或重启后生效"; registerBack(null); state.back() }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(if (raw) "原始 SKILL.md" else "技能字段与正文", fontSize = 22.sp); TextButton({ raw = !raw }) { Symbol(if (raw) "view_agenda" else "data_object"); Text(if (raw) "字段" else "原文") } }
            if (conflicts != null) SkillBanner("文件在外部被修改；你的草稿仍保留。", "sync_problem", error = true) {
                FlowRow { TextButton({ diff = true }) { Text("对比") }; TextButton({ val c = conflicts!!; content = c.skillText("external"); baseline = content; version = c.skillText("externalVersion"); conflicts = null; state.request("validate", mapOf("content" to content)) { fields = it.skillObject("fields"); body = it.skillText("body") } }) { Text("读取外部版本") }; TextButton({ val expected = conflicts!!.skillText("externalVersion"); conflicts = null; save(expected) }) { Text("确认覆盖外部版本") } }
            }
            SkillSegments(listOf("local" to "本地使用", "strict" to "可发布"), if (strict) "strict" else "local") { strict = it == "strict" }
            SkillBanner(if (strict) "按原生标准技能 schema 与 Skillshare 发布规则校验。" else "本地宽松校验。能加载不代表符合发布要求；未知 YAML 字段保留。")
            if (raw) {
                SkillField("原始 YAML + Markdown", content, { content = it }, false, true)
                TextButton({ fullscreen = "raw" }) { Symbol("open_in_full"); Text("原文全屏编辑") }
                TextButton({ state.request("validate", mapOf("content" to content, "strict" to strict)) { validation = it; if (it.skillStrings("errors").isEmpty()) { fields = it.skillObject("fields"); body = it.skillText("body") } } }) { Text("解析字段与校验") }
            } else {
                SkillHeading("基本信息", "badge")
                listOf("name" to "名称", "description" to "描述", "license" to "许可证", "compatibility" to "兼容性", "allowed-tools" to "声明的工具").forEach { (key, label) ->
                    SkillField(label, fields.skillText(key), { value -> fields = fields + (key to value); content = skillSetYamlString(content, key, value) }, singleLine = key != "description", mono = key != "description")
                }
                if (strict) {
                    val metadata = fields.skillObject("metadata")
                    listOf("version" to "版本（metadata.version）", "keywords" to "关键词（逗号分隔）", "repository" to "仓库 HTTPS URL", "homepage" to "主页 HTTPS URL").forEach { (key, label) ->
                        SkillField(label, metadata.skillText(key), { value -> val next = metadata + (key to value); fields = fields + ("metadata" to next); content = skillSetMetadata(content, next) }, mono = true)
                    }
                }
                SkillBanner("保存到${state.scopeLabel}技能目录。${original.skillText("path")}\nhide 只隐藏目录；allowed-tools 不强制限制工具权限；globs 不会自动触发技能。")
                val extras = fields.filterKeys { it !in listOf("name", "description", "license", "compatibility", "metadata", "allowed-tools") }
                if (extras.isNotEmpty()) { SkillHeading("其他字段（保留）", "help"); SkillValues(extras.map { it.key to it.value.toString() }) }
                SkillHeading("正文", "article") { TextButton({ fullscreen = "body" }) { Symbol("open_in_full"); Text("全屏") } }
                SkillSegments(listOf("edit" to "编辑", "view" to "预览"), if (preview) "view" else "edit") { preview = it == "view" }
                if (preview) SkillContentPreview(body) else {
                    SkillField("Markdown 正文", body, { next -> body = next; val m = Regex("^\\uFEFF?---[ \\t]*\\r?\\n[\\s\\S]*?\\r?\\n---[ \\t]*(?:\\r?\\n|$)").find(content); content = if (m != null) content.take(m.range.last + 1) + next else next }, false, true)
                }
            }
            Button({ state.request("validate", mapOf("content" to content, "strict" to strict, "directory" to original.skillText("path").substringAfterLast('/').ifEmpty { null })) { validation = it } }, enabled = state.busy == null) { Symbol("rule"); Text("校验当前草稿") }
            validation.skillStrings("errors").forEach { SkillBanner(it, "error", error = true) }
            validation.skillStrings("warnings").forEach { SkillBanner(it, "warning", warning = true) }
            if (validation.isNotEmpty() && validation.skillStrings("errors").isEmpty()) SkillBanner("校验通过", "check_circle")
            Spacer(Modifier.height(32.dp))
        }
        if (dirty) Surface(tonalElevation = 6.dp, shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(12.dp), color = MaterialTheme.colorScheme.inverseSurface) { Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text("有未保存的修改", Modifier.weight(1f), fontSize = 17.sp); TextButton({ discard = true }) { Text("放弃") }; Button({ save() }, enabled = state.busy == null) { Text("保存") } } }
    }
    fullscreen?.let { mode -> EditableCodeScreen(if (mode == "raw") "SKILL.md 原文" else "技能正文", if (mode == "raw") content else body, "markdown", { fullscreen = null }, registerDismiss = { fullscreenDismiss = it }, onSave = { next ->
        if (mode == "raw") { content = next; state.request("validate", mapOf("content" to next)) { if (it.skillStrings("errors").isEmpty()) { fields = it.skillObject("fields"); body = it.skillText("body") } } }
        else { body = next; val m = Regex("^\\uFEFF?---[ \\t]*\\r?\\n[\\s\\S]*?\\r?\\n---[ \\t]*(?:\\r?\\n|$)").find(content); content = if (m != null) content.take(m.range.last + 1) + next else next }; fullscreen = null
    }) }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("有未保存的修改") }, text = { Text("离开后这些修改会丢失。保存失败不会清空草稿。") }, confirmButton = { Button({ save(); discard = false }) { Text("保存") } }, dismissButton = { Row { TextButton({ content = baseline; discard = false; registerBack(null); state.back() }) { Text("放弃修改") }; TextButton({ discard = false }) { Text("继续编辑") } } })
    if (diff) ModalBottomSheet({ diff = false }) { Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) { Text("外部版本", fontSize = 22.sp); Text(conflicts?.skillText("external") ?: ""); HorizontalDivider(); Text("你的草稿", fontSize = 22.sp); Text(content) } }
}

@Composable internal fun SkillSettingsPage(state: SkillUiState) {
    var selected by remember { mutableStateOf<SkillObject?>(null) }; var draft by remember { mutableStateOf("") }
    SkillBanner("正在编辑${state.scopeLabel}层。清除本层会恢复继承，不会关闭或卸载技能。保存只影响后续加载，不擦除会话历史。")
    listOf("总开关" to listOf("skills.enabled", "skillful", "skills.enableSkillCommands"), "发现来源" to listOf("skills.enablePiUser", "skills.enablePiProject", "skills.enableAgentsUser", "skills.enableAgentsProject", "skills.enableClaudeUser", "skills.enableClaudeProject", "skills.enableCodexUser", "enabledProviders", "disabledProviders"), "目录与过滤" to listOf("skills.customDirectories", "skills.includeSkills", "skills.ignoredSkills", "skills.registryUrl")).forEach { (title, keys) ->
        SkillHeading(title, if (title == "发现来源") "travel_explore" else "tune")
        val rows = state.snapshot.skillObjects("settings").filter { it.skillText("key") in keys }
        rows.forEachIndexed { i, row -> SkillRow(row.skillText("title"), "${row["value"]} · 来源 ${row.skillText("provenance")}", "tune", i == 0, i == rows.lastIndex, onClick = { selected = row; draft = if (row.skillText("type") == "list") row.skillStrings("value").joinToString("\n") else row.skillText("value") }, trailing = if (row.skillText("type") == "boolean") ({ Switch(row.skillBool("value"), { state.request("setSetting", mapOf("key" to row.skillText("key"), "value" to it), "正在保存加载设置") }) }) else null) }
    }
    SkillBanner("自定义目录按顺序扫描直接技能子目录；附加工作区不会自动成为技能目录。include 匹配最终别名；ignore 同时匹配原始名与别名。不同市场共用同一内容 store，切换后可能遇到完整性冲突。")
    SkillBanner("OpenCode 用户来源使用 enabledProviders 中的 opencode opt-in；GitHub 项目来源默认启用，可通过 disabledProviders 中的 github 禁用。这两个原生键为共享发现设置，也影响该来源的其他能力，不是单独技能权限开关。")
    selected?.let { row -> ModalBottomSheet({ selected = null }) { Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text(row.skillText("title"), fontSize = 22.sp)
        SkillValues(listOf("默认值" to row.skillText("defaultValue"), "用户层" to row.skillText("user", "未设置"), "项目层" to row.skillText("project", "未设置"), "有效值" to row.skillText("value"), "来源" to row.skillText("provenance"), "配置键" to row.skillText("key")))
        if (row.skillText("type") == "boolean") Row { Switch(draft == "true", { draft = it.toString() }); Text(if (draft == "true") "开启" else "关闭") }
        else SkillField(if (row.skillText("type") == "list") "每行一个值，保留顺序" else "本层值", draft, { draft = it }, row.skillText("type") != "list", true)
        FlowRow { TextButton({ state.request("setSetting", mapOf("key" to row.skillText("key"), "value" to null), "正在清除本层") { selected = null } }) { Text("清除本层") }; Button({ state.request("setSetting", mapOf("key" to row.skillText("key"), "value" to when (row.skillText("type")) { "list" -> draft.lines().map(String::trim).filter(String::isNotEmpty); "boolean" -> (draft == "true"); else -> draft }), "正在保存设置") { selected = null } }) { Text("保存") } }
    } } }
}

@Composable internal fun SkillImportSheet(state: SkillUiState, files: HostFiles, close: () -> Unit) {
    var mode by remember { mutableStateOf("directory") }; var path by remember { mutableStateOf("") }; var name by remember { mutableStateOf("") }; var checked by remember { mutableStateOf<SkillObject?>(null) }; var upload by remember { mutableStateOf<Pair<String, String>?>(null) }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(close) { Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
        Text("导入技能", fontSize = 22.sp); SkillSegments(listOf("directory" to "本地目录", "skill" to ".skill 发布"), mode) { mode = it }
        if (mode == "directory") {
            SkillField("容器内目录（绝对路径）", path, { path = it; checked = null }, mono = true)
            SkillBanner("先列出真实资源并检查链接和越界路径，再复制到当前${state.scopeLabel}技能目录。不会执行脚本。")
            Button({ state.request("inspectImport", mapOf("path" to path), "正在检查导入目录") { checked = it; name = it.skillObject("fields").skillText("name") } }) { Text("检查目录") }
            checked?.let { data -> SkillField("新名称", name, { name = it }, mono = true); data.skillObjects("resources").forEach { SkillRow(it.skillText("path"), if (it.skillBool("skipped")) it.skillText("reason") else "${it.skillText("size")} 字节", if (it.skillBool("directory")) "folder" else "draft") }; Button({ state.request("importDirectory", mapOf("path" to data.skillText("path"), "name" to name), "正在复制全部安全资源") { close(); state.notice = "已导入 ${it.skillText("path")}" } }) { Text("确认导入到${state.scopeLabel}") } }
        } else {
            SkillBanner(".skill 不是本地安装：文件将上传到 Skillshare，由服务器转换并公开发布；需要 Stencil 账号。", "cloud_upload", warning = true)
            Button({ scope.launch { try { val file = files.pickImportFiles().singleOrNull() ?: return@launch; require(file.name.endsWith(".skill")) { "请选择一个 .skill 文件" }; val encoded = withContext(Dispatchers.IO) { file.openInput().use { Base64.getEncoder().encodeToString(it.readBytes()) } }; upload = file.name to encoded } catch (error: Exception) { state.error = error.message } } }) { Text("选择主机 .skill 文件") }
            TextButton({ close(); state.request("author") { state.author = it; state.navigate("author") } }) { Text("登录技能市场") }
        }
    } }
    upload?.let { file -> AlertDialog(onDismissRequest = { upload = null }, icon = { Symbol("cloud_upload") }, title = { Text("上传并公开发布 ${file.first}？") }, text = { Text("这是实际在线发布，不是本地导入或预检。服务器转换的结果与账号权限为准；文件不会自动改为私有。") }, confirmButton = { Button({ state.request("importSkill", mapOf("base64" to file.second, "confirm" to true), "正在上传并发布") { upload = null; close(); state.notice = "服务器确认发布：${it.skillObject("published")}" } }) { Text("确认上传并公开发布") } }, dismissButton = { TextButton({ upload = null }) { Text("取消") } }) }
}
