@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.wawlink.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wawlink.ui.Symbol

@Composable internal fun SkillInstalledPage(state: SkillUiState) {
    var selected by remember { mutableStateOf<SkillObject?>(null) }; var remove by remember { mutableStateOf<SkillObject?>(null) }; var spec by remember { mutableStateOf("") }; var changeRange by remember { mutableStateOf(false) }; var repair by remember { mutableStateOf<SkillObject?>(null) }; var repairContent by remember { mutableStateOf("") }
    state.snapshot.skillObjects("packageErrors").forEach { failure -> SkillBanner("${failure.skillText("path")}\n${failure.skillText("message")}\n原文件已保留，不会当作空清单覆盖。", "error", error = true) { TextButton({ repair = failure; repairContent = failure.skillText("content") }) { Text("查看并修复文件") } } }
    SkillHeading("${state.scopeLabel}技能包", if (state.project) "folder" else "person")
    val installed = state.snapshot.skillObjects("installed")
    installed.forEachIndexed { i, item -> SkillRow(item.skillText("id"), "声明 ${item.skillText("range", "未声明")} · 锁定 ${item.skillText("version", "未锁定")}\n${if (item.skillBool("stored")) "已下载 · marker 与锁匹配" else if (item["version"] != null) "已锁定，内容缺失" else "已声明，未锁定"}", "package_2", i == 0, i == installed.lastIndex, onClick = { selected = item }) }
    if (installed.isEmpty() && state.snapshot.isNotEmpty() && state.snapshot.skillObjects("packageErrors").isEmpty()) SkillEmpty("还没有安装官方 Skillshare 技能包", "package_2")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton({ state.request("restore", label = "正在恢复锁定版本") { state.notice = "恢复完成：${it["changes"]}\n${it.skillStrings("warnings").joinToString("\n")}" } }, enabled = state.busy == null) { Symbol("restore"); Text("按锁文件恢复") }
        OutlinedButton({ state.request("update", label = "正在按声明范围更新") { state.notice = "范围内更新结果：${it["changes"]}\n${it.skillStrings("warnings").joinToString("\n")}" } }, enabled = state.busy == null) { Symbol("update"); Text("范围内更新") }
        Button({ state.openMarket("skillshare") }, enabled = state.busy == null) { Symbol("add"); Text("安装技能包") }
    }
    SkillBanner("恢复只补齐锁定版本，不会升级；范围内更新会重新解析声明。内容 store 可跨项目共享，marker 不等于每次全量重哈希。失败后按实际清单重读，两个文件不是跨文件事务。")
    selected?.let { item -> ModalBottomSheet({ selected = null }) { Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
        Text(item.skillText("id"), fontSize = 22.sp)
        SkillValues(listOf("声明" to item.skillText("range", "未声明"), "锁定" to item.skillText("version", "未锁定"), "本地内容" to if (item.skillBool("stored")) "marker 与锁匹配" else "缺失", "完整性" to item.skillText("integrity"), "安装范围" to state.scopeLabel))
        SkillBanner("精确版本不自动升级；范围遵守 SemVer；latest/自定义标签每次更新重新解析。被 authored 技能遮蔽、过滤或隐藏都不等于卸载。")
        FlowRow { TextButton({ remove = item }) { Text("卸载", color = MaterialTheme.colorScheme.error) }; TextButton({ spec = item.skillText("range"); changeRange = true }) { Text("改范围") }; FilledTonalButton({ selected = null; state.request(if (item.skillBool("stored")) "update" else "restore", mapOf("id" to item.skillText("id")), "正在恢复或更新") { state.notice = "原生结果：${it["changes"]}" } }) { Text(if (item.skillBool("stored")) "更新" else "恢复锁定版本") }; TextButton({ selected = null; state.openPackage(item.skillText("id")) }) { Text("查看市场详情") } }
    } } }
    if (changeRange) AlertDialog(onDismissRequest = { changeRange = false }, title = { Text("修改声明范围") }, text = { Column { SkillField("范围、标签或精确版本", spec, { spec = it }, mono = true); Text("将重新解析并安装 ${selected?.skillText("id")}@$spec。跨大版本需要明确改范围。") } }, confirmButton = { Button({ val item = selected ?: return@Button; state.request("install", mapOf("spec" to "${item.skillText("id")}@$spec"), "正在保存并更新范围") { changeRange = false; selected = null; state.notice = "已更新声明：${it["changes"]}" } }) { Text("保存并更新") } }, dismissButton = { TextButton({ changeRange = false }) { Text("取消") } })
    remove?.let { item -> AlertDialog(onDismissRequest = { remove = null }, title = { Text("卸载 ${item.skillText("id")}？") }, text = { Text("从当前${state.scopeLabel}声明和锁移除。不会删除独立技能。仅按当前项目与用户锁清理内容，不扫描其他项目；其他项目可能需要重新恢复。") }, confirmButton = { Button({ state.request("uninstall", mapOf("id" to item.skillText("id")), "正在卸载") { remove = null; selected = null; state.notice = "已从当前范围卸载" } }) { Text("卸载") } }, dismissButton = { TextButton({ remove = null }) { Text("取消") } }) }
    repair?.let { failure -> dev.wawlink.ui.EditableCodeScreen(failure.skillText("path"), repairContent, "json", { repair = null }, error = state.error, saving = state.busy != null, onSave = { text -> repairContent = text; state.request("repairPackageFile", mapOf("path" to failure.skillText("path"), "version" to failure.skillText("version"), "content" to text), "正在校验并保存清单") { repair = null } }) }
}

@Composable internal fun SkillOfficialMarketPage(state: SkillUiState) {
    var query by rememberSaveable { mutableStateOf("") }; var owner by rememberSaveable { mutableStateOf("") }; var keyword by rememberSaveable { mutableStateOf("") }; var sort by rememberSaveable { mutableStateOf("relevance") }; var page by rememberSaveable { mutableIntStateOf(1) }; var filters by remember { mutableStateOf(false) }; var results by rememberSaveable { mutableStateOf(false) }; var address by remember { mutableStateOf(false) }
    fun search(next: Int = 1) { state.request("search", mapOf("query" to query, "owner" to owner, "keyword" to keyword, "sort" to sort, "page" to next), "正在搜索注册表") { state.market = it; page = next; results = true } }
    SkillField("搜索技能包", query, { query = it })
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(owner.isNotEmpty() || keyword.isNotEmpty(), { filters = true }, { Text(listOf(owner.takeIf { it.isNotEmpty() }?.let { "作者 $it" }, keyword.takeIf { it.isNotEmpty() }?.let { "关键词 $it" }).filterNotNull().joinToString(" · ").ifEmpty { "作者 / 关键词" }) }); Button({ search() }, enabled = state.busy == null) { Symbol("search"); Text("搜索") }; TextButton({ address = true }) { Symbol("dns"); Text("市场地址") } }
    SkillSegments(listOf("relevance" to "相关", "downloads" to "下载量", "recent" to "最新"), sort) { sort = it; search() }
    if (!results) {
        listOf("热门" to "popular", "最近更新" to "recent", "趋势" to "trending").forEach { (title, key) -> val rows = state.market.skillObjects(key); if (rows.isNotEmpty()) { SkillHeading(title); rows.forEach { SkillPackageRow(state, it) } } }
        val keywords = state.market["keywords"] as? List<*> ?: emptyList<Any?>()
        if (keywords.isNotEmpty()) { SkillHeading("关键词", "sell"); FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { keywords.forEach { item -> val label = if (item is List<*>) item.firstOrNull()?.toString().orEmpty() else if (item is Map<*, *>) item["keyword"]?.toString() ?: item["name"]?.toString().orEmpty() else item.toString(); FilterChip(keyword == label, { keyword = label; search() }, { Text(label) }) } } }
        if (state.market.isNotEmpty() && listOf("popular", "recent", "trending").all { state.market.skillObjects(it).isEmpty() }) SkillEmpty("当前官方 Skillshare 内容为空。可搜索包名、作者或关键词，或切换 ClawHub 浏览公开技能；这不代表其他市场没有技能。", "storefront")
    } else {
        SkillHeading("${state.market.skillText("total", "0")} 个结果")
        state.market.skillObjects("hits").forEach { SkillPackageRow(state, it) }
        if (state.market.isNotEmpty() && state.busy == null && state.market.skillObjects("hits").isEmpty()) SkillEmpty("当前官方 Skillshare 没有符合查询的技能包", "search_off")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { TextButton({ search(page - 1) }, enabled = page > 1 && state.busy == null) { Text("上一页") }; Text("第 $page 页", Modifier.padding(12.dp)); TextButton({ search(page + 1) }, enabled = state.busy == null && page * state.market.skillText("perPage", "20").toIntOrNull().let { it ?: 20 } < state.market.skillText("total", "0").toIntOrNull().let { it ?: 0 }) { Text("下一页") } }
    }
    if (filters) AlertDialog(onDismissRequest = { filters = false }, title = { Text("作者与关键词") }, text = { Column { SkillField("作者 scope（不含 @）", owner, { owner = it }); SkillField("关键词", keyword, { keyword = it }); Text("发送真实 owner: 与 keyword: 查询，由注册表服务端解释。") } }, confirmButton = { Button({ filters = false; search() }) { Text("应用") } }, dismissButton = { TextButton({ owner = ""; keyword = ""; filters = false; search() }) { Text("清除筛选") } })
    if (address) AlertDialog(onDismissRequest = { address = false }, title = { Text("技能市场地址") }, text = { Column { Text(state.market.skillText("registryUrl", state.snapshot.skillText("registryUrl"))); SkillBanner("来自 skills.registryUrl。搜索、安装、更新和作者操作使用同一真实地址。不同市场的同名同版本共用内容 store，可能出现完整性冲突。") } }, confirmButton = { TextButton({ address = false }) { Text("知道了") } })
}
@Composable private fun SkillPackageRow(state: SkillUiState, item: SkillObject) {
    val id = "@${item.skillText("scope")}/${item.skillText("name")}";
    SkillRow(id, "${item.skillText("description")}\n${item.skillText("version")} · 每周 ${item.skillText("weeklyDownloads", "0")} 次下载${if (item["deprecated"] != null) " · 已弃用" else ""}", "package_2", onClick = { state.openPackage(id) })
}

@Composable internal fun SkillPackagePage(state: SkillUiState, onPreview: (String) -> Unit) {
    val data = state.packageData; val pack = data.skillObject("packument"); val version = data.skillObject("version"); val id = "@${pack.skillText("scope")}/${pack.skillText("name")}"; val current = version.skillText("version")
    var install by remember { mutableStateOf(false) }; var method by remember { mutableStateOf("range") }; var spec by remember(current) { mutableStateOf("^$current") }; var manage by remember { mutableStateOf(false) }; var action by remember { mutableStateOf<String?>(null) }; var value by remember { mutableStateOf("") }; var target by remember(current) { mutableStateOf(current) }
    Text(pack.skillText("description"), Modifier.padding(vertical = 12.dp), fontSize = 17.sp)
    data.skillStrings("warnings").forEach { SkillBanner(it, "warning", warning = true) }
    SkillValues(listOf("发布者" to version.skillObject("publisher").skillText("username"), "许可证" to pack.skillText("license"), "仓库" to pack.skillText("repository"), "每周下载" to pack.skillObject("downloads").skillText("weekly"), "更新" to pack.skillText("updatedAt"), "已安装" to state.snapshot.skillObjects("installed").filter { it.skillText("id") == id }.joinToString { "${state.scopeLabel} ${it.skillText("version", "未锁定")}" }.ifEmpty { "未安装" }))
    SkillHeading("来源证明", "verified")
    val provenance = version.skillObject("provenance")
    if (provenance.skillObject("verified").isNotEmpty()) SkillBanner("服务端已验证 GitHub Actions 来源", "verified")
    else SkillBanner(if (provenance.skillObject("reported").isNotEmpty()) "作者自行报告来源，未经服务端验证" else "注册表未返回来源证明；不表示可信或已验证。")
    if (provenance.isNotEmpty()) SkillValues(provenance.map { it.key to it.value.toString() })
    SkillHeading("版本与标签", "history")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { pack.skillObject("distTags").forEach { (tag, v) -> AssistChip({ state.request("package", mapOf("id" to id, "version" to v)) { state.packageData = it } }, { Text("$tag：$v") }, leadingIcon = { Symbol("sell", size = 18.dp) }) } }
    pack.skillObject("versions").forEach { (v, raw) -> val item = (raw as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap(); SkillRow(v, listOfNotNull(if (item.skillBool("yanked")) "已撤回：范围安装跳过，精确版本仍可安装" else null, item["deprecated"]?.let { "已弃用：$it" }).joinToString("\n"), "deployed_code", onClick = { state.request("package", mapOf("id" to id, "version" to v)) { state.packageData = it } }, trailing = if (v == current) ({ Symbol("check", tint = MaterialTheme.colorScheme.primary) }) else null) }
    SkillHeading("文件", "folder_open")
    version.skillObjects("files").forEachIndexed { i, file -> SkillRow(file.skillText("path"), "${file.skillText("size")} 字节${if (file.skillBool("executable")) " · 可执行" else ""}\n${file.skillText("sha256", file.skillText("hash"))}", if (file.skillBool("executable") || file.skillText("path").startsWith("scripts/")) "terminal" else "draft", first = i == 0, last = i == version.skillObjects("files").lastIndex, onClick = if (state.busy == null) ({ onPreview(file.skillText("path")) }) else null) }
    if (version.skillBool("hasScripts")) SkillBanner("包含脚本。安装仅下载并校验，不会执行；模型使用时可能运行这些文件。", "terminal", warning = true)
    SkillValues(listOf("压缩后" to "${version.skillText("size")} 字节", "解包后" to "${version.skillText("unpackedSize")} 字节", "完整性" to version.skillText("integrity"), "版本" to current))
    Button({ spec = "^$current"; install = true }, Modifier.fillMaxWidth()) { Symbol("download"); Text("安装到${state.scopeLabel}") }
    TextButton({ manage = !manage }) { Symbol("manage_accounts"); Text("作者管理 · 权限由服务器校验") }
    if (manage) {
        SkillHeading("标签", "sell")
        pack.skillObject("distTags").forEach { (tag, v) -> SkillRow(tag, "指向 $v", "sell", onClick = { action = "setTag"; value = tag; target = v.toString() }, trailing = if (tag != "latest") ({ SkillIconButton("delete", "删除标签") { action = "removeTag"; value = tag } }) else ({ Text("只能改指向") })) }
        TextButton({ action = "setTag"; value = ""; target = current }) { Text("添加标签") }
        SkillHeading("版本维护", "history")
        SkillRow(current, if (version.skillBool("yanked")) "已撤回" else "未撤回", "undo", onClick = { action = "yank" })
        SkillRow("弃用说明", version.skillText("deprecated", "未弃用"), "warning", onClick = { action = "deprecate"; value = version.skillText("deprecated") })
        TextButton({ action = "setTag"; value = "latest"; target = current }) { Text("设为 latest") }
        SkillHeading("维护者", "group")
        pack.skillObjects("owners").forEach { owner -> SkillRow(owner.skillText("username"), "整包维护权限", "person", trailing = { SkillIconButton("person_remove", "移除维护者") { action = "removeOwner"; value = owner.skillText("username") } }) }
        TextButton({ action = "addOwner"; value = "" }) { Text("添加维护者") }
        SkillBanner("撤回不是删除：范围安装跳过，但精确版本或标签仍可能安装；已经下载的内容和会话历史不会被撤回。维护者对整个包有权限。")
    }
    if (install) ModalBottomSheet({ install = false }) { Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
        Text("安装 $id", fontSize = 22.sp); SkillSegments(listOf("range" to "兼容更新", "exact" to "固定版本", "tag" to "跟随标签"), method) { method = it; spec = when (it) { "exact" -> current; "tag" -> "latest"; else -> "^$current" } }
        SkillField("范围 / 标签 / 精确版本", spec, { spec = it }, mono = true)
        SkillBanner("保存为 $id@$spec\n本地安装范围：${state.scopeLabel}。安装只下载、校验完整性并解包；含脚本版本会单独确认。", "save")
        Button({ state.approvals = emptyList(); state.request("install", mapOf("spec" to "$id@$spec"), "正在解析、下载并校验") { install = false; state.notice = "原生安装结果：${it["changes"]}\n${it.skillStrings("warnings").joinToString("\n")}" } }, enabled = state.busy == null) { Text("安装到${state.scopeLabel}") }
    } }
    action?.let { op -> AlertDialog(onDismissRequest = { action = null }, title = { Text(when (op) { "setTag" -> "设置标签"; "removeTag" -> "删除标签"; "yank" -> if (version.skillBool("yanked")) "取消撤回" else "撤回版本"; "deprecate" -> "弃用说明"; "addOwner" -> "添加维护者"; else -> "移除维护者" }) }, text = { Column { Text("$id@$current\n实际写入市场，必须拥有对应权限。"); if (op in listOf("setTag", "deprecate", "addOwner")) SkillField(if (op == "setTag") "标签名" else if (op == "deprecate") "说明（空表示取消弃用，最多 500 字）" else "用户名", value, { value = it }, op != "deprecate"); if (op == "setTag") SkillField("精确目标版本", target, { target = it }, mono = true); if (op == "yank") Text("范围安装会跳过撤回版本，不删除已下载内容。"); if (op == "removeOwner") Text("$value 将失去整个包的维护权限。"); if (op == "removeTag") Text("删除 $value，latest 不允许删除。") } }, confirmButton = { Button({ state.request(op, mapOf("id" to id, "version" to if (op == "setTag") target else current, "tag" to value, "message" to value.ifEmpty { null }, "username" to value, "yanked" to !version.skillBool("yanked")), "正在提交市场维护操作") { action = null; state.request("package", mapOf("id" to id, "version" to current)) { state.packageData = it } } }) { Text("确认提交") } }, dismissButton = { TextButton({ action = null }) { Text("取消") } }) }
}
