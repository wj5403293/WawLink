@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.wawlink.app

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wawlink.protocol.AgentResourceRepository
import dev.wawlink.ui.EmphAccelEasing
import dev.wawlink.ui.EmphDecelEasing
import dev.wawlink.ui.Symbol

private data class PluginDestination(val page: String, val id: String = "", val depth: Int = 0)
private data class PluginConfirmation(val title: String, val text: String, val action: String, val dangerous: Boolean = false, val execute: () -> Unit)

@Composable
internal fun PluginManagementScreen(repository: AgentResourceRepository, app: AppCoordinator, files: HostFiles, onBack: () -> Unit, registerBack: ((() -> Boolean)?) -> Unit, onSkills: (String?) -> Unit, onAgentSettings: () -> Unit, initialPlugin: String? = null) {
    val coroutine = rememberCoroutineScope()
    val actions = remember(repository) { PluginActions(repository, coroutine) }
    var destinations by remember(repository) { mutableStateOf(listOf(PluginDestination("home")) + if(initialPlugin == null) emptyList() else listOf(PluginDestination("plugin", initialPlugin, 1))) }
    val current = destinations.last()
    val pageStates = rememberSaveableStateHolder()
    var sheet by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<PluginConfirmation?>(null) }
    var featureLeave by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("all") }
    var marketQuery by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("全部") }
    var marketFilter by rememberSaveable { mutableStateOf("全部市场") }
    var tagFilter by rememberSaveable { mutableStateOf("全部标签") }
    var capTab by rememberSaveable { mutableStateOf("session") }
    var more by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var locate by remember { mutableStateOf<String?>(null) }
    var selectedEntry by remember { mutableStateOf<Pair<Map<String, Any?>, Map<String, Any?>>?>(null) }
    var settingKey by remember { mutableStateOf<String?>(null) }
    var featureDraft by remember { mutableStateOf<List<String>?>(null) }
    var featureMode by remember { mutableStateOf("default") }
    var featureDirty by remember { mutableStateOf(false) }
    val install = remember(repository) { PluginInstallDraft(if (repository.context.projectPath == null) "npm" else "market") }
    var marketSourceDraft by remember(repository) { mutableStateOf("") }
    val containers by app.runtime.containers.collectAsState()
    val config = containers.firstOrNull { it.config.id == repository.context.containerId }?.config
    val detail = actions.detail
    val id = detail.pluginText("id", current.id)
    val project = repository.context.projectPath != null
    val busy = actions.busy != null
    fun navigate(page: String, identity: String = "") {
        destinations = destinations + PluginDestination(page, identity, destinations.size)
        more = false
        if (page == "plugin") actions.select(identity)
        if (page == "markets" || page == "market") actions.loadMarkets()
        if (page == "features") {
            featureDraft = (if(project && detail["kind"] != "market") detail["projectFeatures"] else detail["enabledFeatures"])?.let(::pluginStrings)
            featureMode = if (featureDraft == null) "default" else if(featureDraft!!.isEmpty()) "none" else "custom"
            featureDirty = false
        }
    }
    fun pop() {
        when {
            featureDirty && current.page == "features" -> featureLeave = true
            destinations.size > 1 -> { destinations = destinations.dropLast(1); if(destinations.last().page == "plugin") actions.select(destinations.last().id) }
            else -> onBack()
        }
    }
    fun back(): Boolean = when {
        featureLeave -> { featureLeave = false; true }
        confirm != null -> { confirm = null; true }
        sheet != null -> { sheet = null; true }
        settingKey != null -> { settingKey = null; true }
        selectedEntry != null -> { selectedEntry = null; true }
        more -> { more = false; true }
        destinations.size > 1 -> { pop(); true }
        else -> false
    }
    if (!picker && locate == null) SideEffect { registerBack(::back) }
    DisposableEffect(repository) { onDispose { registerBack(null) } }
    LaunchedEffect(repository) { actions.load(initialPlugin) }
    val colors = MaterialTheme.colorScheme
    AnimatedContent(current, transitionSpec = {
        val forward = targetState.depth > initialState.depth
        (fadeIn(tween(400, easing = EmphDecelEasing)) + slideInHorizontally(tween(550, easing = EmphDecelEasing)) { if(forward) it else -it * 22 / 100 }) togetherWith
            (fadeOut(tween(360)) + slideOutHorizontally(tween(360, easing = EmphAccelEasing)) { if(forward) -it * 22 / 100 else it })
    }, label = "plugin-pages") { destination ->
        pageStates.SaveableStateProvider("${destination.depth}:${destination.page}:${destination.id}") {
        PluginPage(
            title = when(destination.page) { "home" -> "插件管理"; "plugin" -> detail.pluginText("name", destination.id); "features" -> "可选功能"; "settings" -> "插件设置"; "markets" -> "市场"; "market" -> destination.id; "updates" -> "更新"; "doctor" -> "诊断"; else -> "插件管理" },
            onBack = { pop() },
            actions = {
                Surface(color = colors.primaryContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.padding(end = 8.dp)) {
                    Row(Modifier.height(40.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Symbol(if(project) "folder" else "person", size = 20.dp); Text(if(project) "项目" else "用户", fontSize = 16.sp) }
                }
                if(destination.page == "home" || destination.page == "plugin") Box {
                    PluginIcon("more_vert", "更多操作") { more = true }
                    DropdownMenu(more, { more = false }, containerColor = Color.White, shape = RoundedCornerShape(24.dp)) {
                        val options = if(destination.page == "home") listOf("本地开发链接" to "link", "检查更新" to "check", "全部升级市场插件" to "updates", "生效与重载说明" to "reload", "技能管理" to "skills") else listOf("查看清单原文" to "raw", "定位安装目录" to "dir", "生效与重载说明" to "reload")
                        options.forEach { (label, action) -> DropdownMenuItem(text = { Text(label, fontSize = 17.sp) }, enabled = action != "link" || !project, onClick = {
                            more = false
                            when(action) { "link" -> { install.kind = "link"; sheet = "install" }; "check" -> { actions.check(); navigate("updates") }; "updates" -> { navigate("updates"); actions.check() }; "reload" -> sheet = "reload"; "skills" -> onSkills(null); "raw" -> sheet = "raw"; "dir" -> locate = detail.pluginText("path").takeIf { it.isNotEmpty() } }
                        }) }
                    }
                }
                if(destination.page == "markets") PluginIcon("add", "添加市场源", !busy && !project) { sheet = "addMarket" }
                if(destination.page == "market") PluginIcon("delete", "移除市场源", !busy && !project) {
                    confirm = PluginConfirmation("移除市场源", "移除登记和目录缓存，不卸载已安装插件；之后不能再从此源更新。", "移除", true) { actions.mutate("marketRemove", mapOf("name" to destination.id, "confirmed" to true)) { destinations = destinations.dropLast(1) } }
                }
            },
            fab = if(destination.page == "home") ({ ExtendedFloatingActionButton(onClick = { sheet = "install"; if(actions.markets.isEmpty()) actions.loadMarkets() }, shape = RoundedCornerShape(16.dp), containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer, icon = { Symbol("add") }, text = { Text("安装", fontSize = 17.sp) }) }) else null,
            footer = if(destination.page == "features" && featureDirty) ({
                Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF303030), contentColor = Color(0xFFF2F2F2), shadowElevation = 6.dp) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Symbol("edit", Modifier.padding(horizontal = 10.dp)); Text("有未保存的修改", Modifier.weight(1f), fontSize = 17.sp)
                        TextButton(onClick = { featureDraft = (if(project && detail["kind"] != "market") detail["projectFeatures"] else detail["enabledFeatures"])?.let(::pluginStrings); featureMode = if(featureDraft == null) "default" else if(featureDraft!!.isEmpty()) "none" else "custom"; featureDirty = false }, enabled = !busy) { Text("放弃") }
                        Button(onClick = { actions.mutate("features", mapOf("id" to id, "value" to featureDraft)) { featureDirty = false } }, enabled = !busy) { Text("保存") }
                    }
                }
            }) else null
        ) {
            if (actions.busy != null) { LinearProgressIndicator(Modifier.fillMaxWidth()); PluginHint(actions.busy ?: ""); if(actions.canCancel) TextButton(onClick = actions::cancel) { Symbol("cancel"); Text("请求取消原生操作") } }
            actions.error?.let { PluginBanner(it, error = true, icon = "error") { TextButton(onClick = { actions.error = null }) { Text("知道了") } } }
            if(actions.pendingRestart) PluginBanner("已保存，运行中的会话尚未重载。可执行扩展、工具和钩子在新会话生效。", warning = true, icon = "pending") { TextButton(onClick = { sheet = "reload" }) { Text("生效说明") } }
            when(destination.page) {
                "home" -> {
                    Surface(shape = RoundedCornerShape(28.dp), color = Color(0xFFF0F4F9), modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
                        Column(Modifier.padding(18.dp)) {
                            PluginContextRow("dns", "容器 ${repository.context.containerName}，OMP ${actions.snapshot.pluginText("ompVersion", "版本尚未读取")}")
                            PluginContextRow("account_circle", "配置档 ${repository.context.profile}")
                            PluginContextRow(if(project) "folder" else "person", repository.context.projectPath ?: "用户级插件；不读取项目配置")
                            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable { sheet = "paths" }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) { Symbol("route"); Text("实际读写的目录", Modifier.weight(1f), fontSize = 17.sp); Symbol("chevron_right") }
                        }
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PluginTile("市场", "${actions.snapshot.pluginText("marketCount", "尚未读取")} 个市场源", "storefront", Modifier.weight(1f)) { navigate("markets") }
                        PluginTile("诊断", if(actions.doctor.isEmpty()) "尚未运行" else "${actions.doctor.count { it["status"] != "ok" }} 个检查问题", "health_and_safety", Modifier.weight(1f)) { navigate("doctor") }
                    }
                    if(actions.items.isEmpty() && !busy && actions.error == null && actions.snapshot.isNotEmpty()) PluginEmpty("还没有安装插件") { FilledTonalButton(onClick = { sheet = "install"; actions.loadMarkets() }) { Symbol("add"); Text("安装插件") }; TextButton(onClick = { navigate("markets") }) { Text("浏览市场") } }
                    val updateIds = pluginMaps(actions.updates["updates"]).map { it.pluginText("pluginId") }.toSet()
                    if(updateIds.isNotEmpty()) PluginRow("检查到 ${updateIds.size} 个更新", "查看来源、范围与逐项升级结果", "system_update", onClick = { navigate("updates") })
                    PluginSearch(query, { query = it }, "搜索包名、描述或功能")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("all" to "全部", "npm" to "npm", "git" to "Git", "link" to "本地链接", "market" to "市场", "off" to "已停用", "shadow" to "有遮蔽", "update" to "可更新", "bad" to "异常").forEach { (key, label) -> FilterChip(filter == key, { filter = key }, { Text(label, fontSize = 16.sp) }, modifier = Modifier.heightIn(min = 40.dp), shape = RoundedCornerShape(12.dp), leadingIcon = if(filter == key) ({ Symbol("check", size = 20.dp) }) else null) }
                    }
                    val visible = actions.items.filter { item ->
                        val manifest = pluginMap(item["manifest"])
                        val matches = query.isBlank() || listOf(item.pluginText("id"), item.pluginText("name"), manifest.pluginText("name"), item.pluginText("description"), pluginJson(manifest["features"])).any { it.contains(query, ignoreCase = true) }
                        matches && when(filter) { "all" -> true; "off" -> item["enabled"] == false; "shadow" -> item["shadowedBy"] != null; "update" -> item.pluginText("id") in updateIds; "bad" -> item["broken"] == true; else -> item["kind"] == filter }
                    }
                    if(visible.isEmpty() && actions.items.isNotEmpty()) PluginEmpty("没有符合条件的插件", "search_off")
                    listOf("直接安装" to visible.filter { it["kind"] != "market" }, "市场插件" to visible.filter { it["kind"] == "market" }).forEach { (title, items) ->
                        if (items.isNotEmpty()) {
                            PluginGroup(title)
                            items.forEachIndexed { index, item ->
                                PluginInstalledRow(item, index, items.size, !busy && (!project || item["kind"] == "market" || item["userEnabled"] != false),
                                    { navigate("plugin", item.pluginText("id")) }) { enabled ->
                                    actions.mutate("enable", mapOf("id" to item.pluginText("id"), "enabled" to enabled))
                                }
                            }
                        }
                    }
                    Button(onClick = { actions.load() }, enabled = !busy, modifier = Modifier.padding(top = 16.dp)) { Symbol("refresh"); Text("重新读取") }
                }
                "plugin" -> if(detail.isNotEmpty()) {
                    Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Surface(shape = RoundedCornerShape(20.dp), color = colors.primary, modifier = Modifier.size(64.dp)) { Box(contentAlignment = Alignment.Center) { Text(detail.pluginText("name", id).substringAfterLast('/').take(1).uppercase(), color = colors.onPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold) } }
                        Column(Modifier.weight(1f)) { Text(id, fontSize = 22.sp); Text("${pluginKindLabel(detail.pluginText("kind"))} · ${if(detail["enabled"] == false) "已停用" else "配置已启用"}", fontSize = 16.sp, color = colors.onSurfaceVariant) }
                    }
                    if(detail.pluginText("description").isNotEmpty()) Text(detail.pluginText("description"), Modifier.padding(4.dp), fontSize = 17.sp, color = colors.onSurfaceVariant)
                    if(detail["broken"] == true) PluginBanner("链接目标或 package.json 缺失。卸载只移除登记，不删除链接源目录。", error = true, icon = "link_off") { TextButton(onClick = { install.kind = "link"; install.path = detail.pluginText("path"); sheet = "install" }) { Text("重新选择目录") } }
                    PluginGroup("启用")
                    if(detail["kind"] == "market") {
                        PluginRow(if(project) "项目安装" else "用户安装", "${detail.pluginText("version")} · ${if(detail["shadowedBy"] != null) "被项目安装遮蔽" else if(detail["enabled"] == false) "已停用" else "配置已启用"}", if(project) "folder" else "person", trailing = { Switch(detail["enabled"] != false, { actions.mutate("enable", mapOf("id" to id, "enabled" to it)) }, enabled = !busy) })
                        PluginHint("启用的项目安装优先于用户安装；停用项目副本可能让用户副本重新生效。缓存可能跨范围共享。", "layers")
                    } else {
                        PluginKeyValues(listOf("用户" to (detail["userEnabled"] != false), "项目覆盖" to if(project) if(detail["projectDisabled"] == true) "停用" else "未覆盖" else "无项目上下文", "有效" to (detail["enabled"] != false)), effective = true)
                        PluginRow(if(project) "在本项目停用" else "用户层启用", if(project) "项目不能重新启用用户层已停用的插件" else "修改用户运行状态；不会重载会话", "power_settings_new", trailing = { Switch(if(project) detail["projectDisabled"] == true else detail["userEnabled"] != false, { actions.mutate("enable", mapOf("id" to id, "enabled" to if(project) !it else it)) }, enabled = !busy && (!project || detail["userEnabled"] != false)) })
                    }
                    PluginGroup("能力")
                    PluginSegments(listOf("manifest" to "声明", "loaded" to "已加载", "session" to "当前会话"), capTab, { capTab = it })
                    Spacer(Modifier.height(12.dp))
                    val manifest = pluginMap(detail["manifest"])
                    if(capTab == "manifest") {
                        val entries = listOf("tools", "extensions", "commands", "hooks", "skills", "agents", "mcp", "lsp", "dap").mapNotNull { key -> manifest[key]?.let { key to it } }
                        if(entries.isEmpty()) PluginEmpty("没有读取到能力声明", "data_object") else PluginKeyValues(entries)
                        PluginHint("清单只说明声明了什么，不代表已加载、当前可调用或平台兼容。终端自定义组件不能等同于 Compose 界面能力。")
                    } else PluginBanner(pluginMap(detail[capTab]).pluginText("reason", "没有可核验的会话能力快照，不能把清单当作已加载结果"), icon = "visibility_off")
                    PluginGroup("配置")
                    PluginRow("可选功能", "${pluginMap(manifest["features"]).size} 项 · ${if(detail["enabledFeatures"] == null) "按默认" else if(pluginStrings(detail["enabledFeatures"]).isEmpty()) "都不选" else "自选 ${pluginStrings(detail["enabledFeatures"]).size} 项"}", "toggle_on", index = 0, count = 2, onClick = { navigate("features", id) })
                    PluginRow("插件设置", "${pluginMap(manifest["settings"]).size} 项", "settings", index = 1, count = 2, onClick = { navigate("settings", id) })
                    val resources = pluginMap(detail["resources"])
                    if(resources.values.any { pluginStrings(it).isNotEmpty() }) {
                        PluginGroup("提供的资源")
                        pluginStrings(resources["skills"]).forEach { skill -> PluginRow(skill, "技能，在技能管理中查看", "school", onClick = { onSkills(skill) }) }
                        pluginStrings(resources["agents"]).forEach { agent -> PluginRow(agent, "Agent，打开 Agent 设置", "smart_toy", onClick = onAgentSettings) }
                        pluginStrings(resources["mcp"]).forEach { mcp -> PluginRow(mcp, "仅发现配置文件；连接状态未知", "hub") }
                        PluginHint("资源名称来自安装目录清单，不是发现/加载成功证明；技能、Agent 和 MCP 不随单个 manifest feature 自动关闭。")
                    }
                    PluginGroup("来源")
                    PluginKeyValues(listOf("管理身份" to id, "安装范围" to if(detail["kind"] == "market" && project) "项目" else "用户", "来源" to detail["spec"], "版本" to detail["version"], "路径" to detail["path"]) + listOf("installedAt", "updatedAt", "gitCommitSha").mapNotNull { key -> detail[key]?.let { key to it } })
                    if(detail["kind"] == "link") PluginHint("本地链接不复制文件、不安装依赖、不验证入口；修改源目录后需要新会话。", "link")
                    FlowRow(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if(detail["kind"] != "link") OutlinedButton(onClick = { actions.check(id); navigate("updates") }, enabled = !busy) { Symbol("update"); Text("检查更新") }
                        if(!project || detail["kind"] == "market") {
                            if(detail["kind"] != "link") FilledTonalButton(onClick = { confirm = PluginConfirmation("升级 $id", "升级会保留启停与功能选择。npm 按包名安装最新，原 range/pin 不保留；Git 重新解析记录来源/ref；市场强制重装可能影响共享缓存。没有版本历史回退。", "升级") { actions.mutate("upgrade", mapOf("id" to id, "confirmed" to true)) { sheet = "results" } } }, enabled = !busy) { Symbol("upgrade"); Text("升级") }
                            TextButton(onClick = { confirm = PluginConfirmation("卸载 $id", "移除当前范围的安装登记和运行状态。链接源目录会保留；共享缓存仍被其他范围引用时保留。插件技能与 Agent 可能消失；已发给模型的内容和先前外部副作用不会撤回。", "卸载", true) { actions.mutate("uninstall", mapOf("id" to id, "confirmed" to true)) { destinations = listOf(PluginDestination("home")) } } }, enabled = !busy) { Symbol("delete", tint = colors.error); Text("卸载", color = colors.error) }
                        } else PluginHint("直接安装属于用户层。此项目入口仅编辑项目覆盖，不能卸载或升级用户插件。")
                    }
                }
                "features" -> {
                    val known = pluginMap(pluginMap(detail["manifest"])["features"])
                    val inheritsUser = project && detail["kind"] != "market"
                    val inherited = if(inheritsUser && detail["userFeatures"] != null) pluginStrings(detail["userFeatures"]) else known.filter { pluginMap(it.value)["default"] == true }.keys.toList()
                    PluginSegments(listOf("default" to if(inheritsUser) "继承用户" else "按默认", "none" to "都不选", "custom" to "自选"), featureMode, { mode -> featureMode = mode; featureDraft = when(mode) { "default" -> null; "none" -> emptyList(); else -> featureDraft?.takeIf { it.isNotEmpty() } ?: inherited }; featureDirty = true })
                    PluginHint(when(featureMode) { "default" -> if(inheritsUser) "清除项目 features 覆盖，继承用户选择；用户若为默认态才跟随 manifest default:true" else "跟随清单 default:true；以后新增默认功能会跟随启用"; "none" -> "只保留基础入口，不加载任何可选功能"; else -> "仅加载显式选中的集合，新增功能不会自动加入" })
                    val unknown = featureDraft.orEmpty().filter { it !in known }
                    if(unknown.isNotEmpty()) PluginBanner("选中了清单里不存在的功能：${unknown.joinToString("，")}", error = true) { TextButton(onClick = { featureDraft = featureDraft.orEmpty().filter { it in known }; featureDirty = true }) { Text("移除未知功能") } }
                    Spacer(Modifier.height(12.dp))
                    known.entries.forEachIndexed { index, (key, value) -> val feature = pluginMap(value); PluginRow(feature.pluginText("description", key), "$key${if(feature["default"] == true) " · 默认开启" else ""} · ${listOf("tools", "extensions", "hooks", "commands").filter { feature[it] != null }.joinToString()}", "toggle_on", index, known.size, trailing = { Switch(if(featureDraft == null) key in inherited else key in featureDraft.orEmpty(), { checked -> featureDraft = if(checked) featureDraft.orEmpty() + key else featureDraft.orEmpty() - key; featureDirty = true }, enabled = featureMode == "custom" && !busy) }) }
                    if(known.isEmpty()) PluginEmpty("没有可选功能", "toggle_off")
                    PluginHint("可选功能不是权限控制；基础入口始终保留，技能/Agent/MCP 可能按整个目录发现。", "shield")
                }
                "settings" -> {
                    val settings = pluginMap(detail["settings"]); val schema = pluginMap(settings["schema"]); val effective = pluginMap(settings["effective"])
                    PluginBanner(if(project) "项目层写入 ${detail.pluginText("overridePath")}，与用户设置浅合并" else "编辑用户运行状态；未知设置原样保留", icon = if(project) "folder" else "person")
                    schema.entries.forEachIndexed { index, (key, value) -> val field = pluginMap(value); PluginRow(field.pluginText("description", key), pluginDisplay(effective[key]), if(field["secret"] == true) "lock" else "tune", index, schema.size, onClick = { settingKey = key }) }
                    if(schema.isEmpty()) PluginEmpty("清单没有声明设置项", "settings")
                    FlowRow(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilledTonalButton(onClick = { actions.validate(id) }, enabled = !busy) { Symbol("rule"); Text("校验设置") }; OutlinedButton(onClick = { sheet = "rawSettings" }) { Symbol("data_object"); Text("原始值") } }
                    val unknown = pluginStrings(settings["unknown"])
                    if(unknown.isNotEmpty()) PluginHint("还有 ${unknown.size} 个清单未声明的设置，会原样保留：${unknown.joinToString()}。高级显示已脱敏。", "help")
                }
                "markets", "market" -> {
                    val markets = if(destination.page == "market") actions.markets.filter { it["name"] == destination.id } else actions.markets
                    if(destination.page == "markets") { PluginGroup("市场源") { TextButton(onClick = { sheet = "addMarket" }, enabled = !project) { Symbol("add"); Text("添加") } }; markets.forEachIndexed { index, market -> PluginRow(market.pluginText("name"), "${market.pluginText("sourceType")} · ${pluginMaps(market["catalog"]).size} 个插件 · ${market.pluginText("updatedAt")}", "storefront", index, markets.size, onClick = { navigate("market", market.pluginText("name")) }, error = market["error"] != null) } }
                    if(destination.page == "market") markets.firstOrNull()?.let { market ->
                        market["error"]?.let { PluginBanner(it.toString(), error = true) }
                        PluginKeyValues(listOf("类型" to market["sourceType"], "地址" to market["sourceUri"], "添加时间" to market["addedAt"], "目录更新" to market["updatedAt"]))
                        FilledTonalButton(onClick = { actions.mutate("marketUpdate", mapOf("name" to destination.id)) }, enabled = !busy && !project, modifier = Modifier.padding(top = 16.dp)) { Symbol("refresh"); Text("刷新目录") }
                        PluginHint("刷新只重取目录，不升级已安装插件。无效条目可能由原生解析跳过；当前 API 未提供跳过计数，不编造数字。")
                    }
                    if(markets.isEmpty() && !busy && actions.error == null) PluginEmpty("还没有市场源", "storefront") { FilledTonalButton(onClick = { sheet = "addMarket" }, enabled = !project) { Text("添加市场源") } }
                    PluginGroup("浏览")
                    PluginSearch(marketQuery, { marketQuery = it }, "在已取得的目录中搜索")
                    val entries = markets.flatMap { market -> pluginMaps(market["catalog"]).map { market to it } }
                    PluginFilterChips(listOf("全部") + entries.map { it.second.pluginText("category", "未分类") }.distinct(), category, { category = it })
                    if(destination.page == "markets") PluginFilterChips(listOf("全部市场") + markets.map { it.pluginText("name") }, marketFilter, { marketFilter = it })
                    PluginFilterChips(listOf("全部标签") + entries.flatMap { pluginStrings(it.second["tags"]) + pluginStrings(it.second["keywords"]) }.distinct(), tagFilter, { tagFilter = it })
                    val hits = entries.filter { (market, entry) -> (category == "全部" || entry.pluginText("category", "未分类") == category) && (marketFilter == "全部市场" || destination.page == "market" || market.pluginText("name") == marketFilter) && (tagFilter == "全部标签" || tagFilter in (pluginStrings(entry["tags"]) + pluginStrings(entry["keywords"]))) && (marketQuery.isBlank() || listOf(entry.pluginText("name"), entry.pluginText("description"), pluginJson(entry["tags"]), pluginJson(entry["keywords"])).any { it.contains(marketQuery, true) }) }
                    hits.forEachIndexed { index, (market, entry) -> val installed = actions.items.any { it["id"] == "${entry.pluginText("name")}@${market.pluginText("name")}" }; PluginRow(entry.pluginText("name"), "${entry.pluginText("description")}\n${market.pluginText("name")}${if(installed) " · 已装到${if(project) "项目" else "用户"}" else ""}${if(entry["supported"] == false) " · 暂不支持安装" else ""}", index = index, count = hits.size, onClick = { selectedEntry = market to entry }, avatar = entry.pluginText("name")) }
                    if(hits.isEmpty() && markets.isNotEmpty()) PluginEmpty("没有匹配的插件", "search_off")
                    PluginHint("只浏览已添加市场的缓存目录，不是跨 npm 搜索，没有评分、下载排行或兼容性保证。")
                }
                "updates" -> {
                    val updates = pluginMaps(actions.updates["updates"])
                    PluginGroup("市场插件")
                    val marketUpdates = updates.filter { it.pluginText("pluginId").contains('@') && actions.items.any { p -> p["id"] == it["pluginId"] && p["kind"] == "market" } }
                    marketUpdates.forEachIndexed { index, update -> PluginRow(update.pluginText("pluginId"), "${update.pluginText("from")} → ${update.pluginText("to")} · ${update.pluginText("scope")}", "system_update", index, marketUpdates.size, onClick = { navigate("plugin", update.pluginText("pluginId")) }) }
                    if(marketUpdates.isEmpty()) PluginEmpty("市场插件没有报告更新", "check_circle")
                    FilledTonalButton(onClick = { confirm = PluginConfirmation("全部升级市场插件", "仅升级当前范围已报告更新的市场插件，不处理 npm/Git。每项独立执行，可能部分成功；共享缓存会影响其他范围。", "升级") { actions.mutate("upgradeMarket", mapOf("confirmed" to true)) { sheet = "results"; actions.check() } } }, enabled = !busy && marketUpdates.isNotEmpty()) { Symbol("upgrade"); Text("全部升级市场插件") }
                    PluginHint("远端没有可比较版本时会跳过，不代表一定最新。先刷新市场目录再检查以取得最新目录版本。")
                    PluginGroup("直接安装")
                    pluginMaps(actions.updates["results"]).forEach { update -> PluginRow(update.pluginText("id"), "${update.pluginText("status")} · ${update.pluginText("from")} → ${update.pluginText("to")}\n${update.pluginText("message")}", "update", onClick = { navigate("plugin", update.pluginText("id")) }, error = update["status"] == "error") }
                    PluginHint("npm/Git 逐个升级，本地链接不能升级。检查只读取 registry/ref，不调用 upgrade --dry-run。")
                    OutlinedButton(onClick = { actions.check() }, enabled = !busy) { Symbol("refresh"); Text("检查更新") }
                    PluginGroup("市场自动更新")
                    PluginSegments(listOf("off" to "关闭", "notify" to "只提醒", "auto" to "自动升级"), actions.snapshot.pluginText("autoUpdate", "notify"), { value -> actions.mutate("autoUpdate", mapOf("value" to value)) }, enabled = { !busy && !project })
                    PluginHint("用户设置，只作用于市场插件。启动时约每 24 小时刷新；提醒目前写入日志，不承诺系统通知或所有 npm/Git 自动升级。")
                }
                "doctor" -> {
                    PluginHint("检查用户插件根下的 npm、Git、本地链接。不检查所有市场范围、MCP 连接、漏洞或权限。")
                    Button(onClick = { actions.diagnose() }, enabled = !busy, modifier = Modifier.padding(top = 16.dp)) { Symbol("health_and_safety"); Text(if(actions.doctor.isEmpty()) "开始诊断" else "重新诊断") }
                    actions.doctor.forEachIndexed { index, check -> PluginRow(check.pluginText("name"), check.pluginText("message") + if(check["fixed"] == true) " · 已修复" else "", if(check["fixed"] == true) "build_circle" else when(check["status"]) { "ok" -> "check_circle"; "warning" -> "warning"; else -> "error" }, index, actions.doctor.size, error = check["status"] == "error") }
                    if(actions.doctor.any { it["fixable"] == true && it["fixed"] != true }) FilledTonalButton(onClick = { confirm = PluginConfirmation("修复计划", "下面是当前原生检查可修复的操作；会写入文件，部分需要联网。修复后的 status/fixed 按原生结果展示。\n" + actions.doctor.filter { it["fixable"] == true && it["fixed"] != true }.joinToString("\n") { it.pluginText("name") + "：" + it.pluginText("repair") }, "执行修复", true) { actions.diagnose(true) } }, enabled = !busy && !project) { Symbol("build"); Text("修复问题") }
                    if(project) PluginHint("doctor --fix 修改用户根；项目入口不提供跨层修复。", "lock")
                }
            }
        }
        }
    }
    if(sheet == "install" && !picker && locate == null) PluginInstallSheet(install, actions, onClose = { sheet = null }, onPick = { picker = true }, onMarkets = { sheet = null; navigate("markets") }, onInstall = { spec, kind, force ->
        confirm = PluginConfirmation("确认安装", "插件是可信本地代码，可执行命令、读写文件、访问网络并注册工具和模型服务。\n来源：$spec\n范围：${if(project) "项目" else "用户"}\n${if(kind == "link") "仅链接，不复制文件、不安装依赖、不验证入口；同名链接/目录可能被替换。" else "安装可能运行包管理器和扩展 factory。失败可能回滚管理文件，但无法撤回网络、文件和其他外部副作用。"}\n${if(force) "强制重装会覆盖同名安装/共享缓存，可能影响其他范围。" else ""}", "安装") {
            actions.mutate("install", mapOf("kind" to kind, "spec" to spec, "confirmed" to true, "force" to force)) { sheet = null }
        }
    })
    if(sheet == "paths") PluginSheet("实际读写的目录", onClose = { sheet = null }) { PluginKeyValues(pluginMap(actions.snapshot["paths"]).entries.map { it.key to it.value }); PluginHint("由当前容器 OMP 原生目录 API 解析，不是 Android 主机路径。") }
    if(sheet == "reload") PluginSheet("生效与重载", onClose = { sheet = null }) { PluginBanner("保存成功不等于当前会话已重载。此管理入口不会中断运行中的会话。", warning = true); PluginHint("可执行扩展、工具和钩子需要新会话或显式重启。原生 RPC /reload-plugins 仅刷新适用的技能与文件命令，不能当成完整 JS factory 热替换。此处不伪造运行重载。历史内容和已发生副作用不会撤回。") }
    if(sheet == "raw") PluginSheet("清单原文（脱敏只读）", full = true, onClose = { sheet = null }) { PluginCode(pluginJson(detail["rawManifest"])); listOf("standardManifest" to "plugin.json", "compatibleManifest" to ".claude-plugin/plugin.json").forEach { (key, label) -> if(pluginMap(detail[key]).isNotEmpty()) { PluginGroup(label); PluginCode(pluginJson(detail[key])) } }; PluginHint("未执行脚本；已按秘密键和 schema 规则遮蔽敏感值。") }
    if(sheet == "rawSettings") PluginSheet("原始设置", full = true, onClose = { sheet = null }) { PluginBanner("可能包含密钥的值已遮蔽。secret 是显示提示，不是加密保存。", warning = true, icon = "visibility_off"); val values = pluginMap(detail["settings"]); listOf("defaults" to "默认值", "user" to "用户", "project" to "项目", "effective" to "有效值").forEach { (key, label) -> PluginGroup(label); PluginCode(pluginJson(values[key])) } }
    if(sheet == "results") PluginSheet("逐项操作结果", full = true, onClose = { sheet = null }) { val results = pluginMaps(actions.result["results"]); if(results.isEmpty()) PluginCode(pluginJson(actions.result)) else results.forEachIndexed { index, item -> PluginRow(item.pluginText("pluginId"), if(item["ok"] == true) "已升级：${item.pluginText("version")}" else item.pluginText("error", "未执行"), if(item["ok"] == true) "check_circle" else "error", index, results.size, error = item["ok"] != true) }; PluginHint("按项报告真实结果；部分成功不会标记成全部成功，已重新读取持久状态。") }
    if(sheet == "addMarket") PluginAddMarketSheet(actions, marketSourceDraft, { marketSourceDraft = it }, onClose = { sheet = null }, onAdded = { sheet = null; marketSourceDraft = "" })
    settingKey?.let { key -> PluginSettingSheet(key, detail, project, busy, actions.error, onClose = { settingKey = null }, onSave = { value, clear -> actions.mutate("setting", mapOf("id" to id, "key" to key, "value" to value, "clear" to clear)) { settingKey = null } }) }
    selectedEntry?.let { (market, entry) ->
        val entryId = "${entry.pluginText("name")}@${market.pluginText("name")}"
        val installed = actions.items.any { it["id"] == entryId }
        PluginSheet(entry.pluginText("name"), onClose = { selectedEntry = null }, footer = {
            if(installed) TextButton(onClick = { selectedEntry = null; navigate("plugin", entryId) }) { Text("查看已安装") }
            Spacer(Modifier.weight(1f))
            Button(onClick = { install.kind = "market"; install.market = market.pluginText("name"); install.entry = entry.pluginText("name"); install.force = installed; selectedEntry = null; sheet = "install" }, enabled = entry["supported"] != false && !busy) { Symbol("download"); Text(if(installed) "重新安装" else "安装") }
        }) {
            Text(entry.pluginText("description"), fontSize = 17.sp)
            PluginFilterChips(pluginStrings(entry["tags"]) + pluginStrings(entry["keywords"]), "", {})
            if(entry["supported"] == false) PluginBanner(entry.pluginText("unsupportedReason"), error = true, icon = "block")
            PluginKeyValues(listOf("身份" to entryId, "来源" to pluginJson(entry["source"]), "版本" to entry.pluginText("version", "目录未提供"), "已安装" to if(installed) if(project) "项目" else "用户" else "未安装"))
            PluginHint("目录能读取不代表插件全部能力可在当前平台使用。npm 市场来源未实现；JSON-only 市场不能安装相对路径来源。")
        }
    }
    if(featureLeave) AlertDialog(onDismissRequest = { featureLeave = false }, icon = { Symbol("edit_note") }, title = { Text("有未保存的修改", fontSize = 24.sp) }, text = { Text("离开后这些修改会丢失。保存失败会保留草稿。", fontSize = 17.sp) }, confirmButton = { Button(onClick = { actions.mutate("features", mapOf("id" to id, "value" to featureDraft)) { featureDirty = false; featureLeave = false; destinations = destinations.dropLast(1) } }, enabled = !busy) { Text("保存") } }, dismissButton = { Row { TextButton(onClick = { featureDirty = false; featureLeave = false; destinations = destinations.dropLast(1) }, enabled = !busy) { Text("放弃修改", color = colors.error) }; TextButton(onClick = { featureLeave = false }) { Text("继续编辑") } } }, shape = RoundedCornerShape(28.dp), containerColor = Color.White)
    confirm?.let { value -> AlertDialog(onDismissRequest = { confirm = null }, icon = { Symbol(if(value.dangerous) "warning" else "gpp_maybe", tint = if(value.dangerous) colors.error else colors.primary) }, title = { Text(value.title, fontSize = 24.sp) }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(value.text, fontSize = 17.sp, lineHeight = 26.sp) } }, confirmButton = { Button(onClick = { confirm = null; value.execute() }, colors = if(value.dangerous) ButtonDefaults.buttonColors(containerColor = colors.error) else ButtonDefaults.buttonColors()) { Text(value.action) } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } }, shape = RoundedCornerShape(28.dp), containerColor = Color.White) }
    actions.notice?.let { message ->
        LaunchedEffect(message) { kotlinx.coroutines.delay(4000); if(actions.notice == message) actions.notice = null }
        Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 24.dp), contentAlignment = Alignment.BottomCenter) {
            Snackbar(shape = RoundedCornerShape(14.dp), action = { TextButton(onClick = { actions.notice = null }) { Text("关闭") } }) { Text(message, fontSize = 16.sp) }
        }
    }
    if(picker && config != null) Surface(Modifier.fillMaxSize()) { ContainerFilesScreen(app, files, config, onBack = { picker = false }, backHandler = registerBack, initialPath = install.path.takeIf { it.startsWith('/') } ?: repository.context.projectPath ?: "/workspace", onChooseDirectory = { chosen -> install.path = chosen; picker = false }) }
    if(locate != null && config != null) Surface(Modifier.fillMaxSize()) { ContainerFilesScreen(app, files, config, onBack = { locate = null }, backHandler = registerBack, initialPath = locate!!) }
    if((picker || locate != null) && config == null) { picker = false; locate = null; actions.error = "所选容器已不存在，无法打开容器目录" }
}

@Composable
private fun PluginContextRow(icon: String, text: String) { Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) { Symbol(icon); Text(text, fontSize = 17.sp) } }

@Composable
private fun PluginTile(label: String, value: String, icon: String, modifier: Modifier, onClick: () -> Unit) { Surface(onClick = onClick, shape = RoundedCornerShape(24.dp), color = Color(0xFFF8FAFD), modifier = modifier.heightIn(min = 112.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Symbol(icon, tint = MaterialTheme.colorScheme.primary); Text(label, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, fontSize = 18.sp, fontWeight = FontWeight.Medium) } } }

@Composable
private fun PluginInstalledRow(item: Map<String, Any?>, index: Int, count: Int, enabled: Boolean, onClick: () -> Unit, onEnabled: (Boolean) -> Unit) {
    val state = if(item["broken"] == true) "链接或清单缺失" else if(item["enabled"] == false) "已停用" else if(item["shadowedBy"] != null) "被项目安装遮蔽" else "配置已启用"
    PluginRow(item.pluginText("name", item.pluginText("id")), "${pluginKindLabel(item.pluginText("kind"))} · ${item.pluginText("version", "未知版本")} · $state", index = index, count = count, onClick = onClick, error = item["broken"] == true, avatar = item.pluginText("name", item.pluginText("id")).substringAfterLast('/'), trailing = { Switch(item["enabled"] != false, onEnabled, enabled = enabled && item["broken"] != true) })
}

internal fun pluginKindLabel(kind: String): String = when(kind) { "npm" -> "npm"; "git" -> "Git"; "link" -> "本地链接"; "market" -> "市场"; else -> kind }

@Composable
internal fun PluginFilterChips(values: List<String>, selected: String, onSelect: (String) -> Unit) { Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { values.forEach { value -> FilterChip(value == selected, { onSelect(value) }, { Text(value, fontSize = 16.sp) }, modifier = Modifier.heightIn(min = 40.dp), shape = RoundedCornerShape(12.dp)) } } }

@Composable
internal fun PluginCode(text: String) { androidx.compose.foundation.text.selection.SelectionContainer { Text(text, Modifier.fillMaxWidth().background(Color(0xFFF8FAFD), RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 14.dp), fontFamily = FontFamily.Monospace, fontSize = 15.sp, lineHeight = 25.sp) } }
