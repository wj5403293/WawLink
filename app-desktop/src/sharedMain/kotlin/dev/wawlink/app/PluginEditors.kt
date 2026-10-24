@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.wawlink.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wawlink.ui.EmphDecelEasing
import dev.wawlink.ui.EmphEasing
import dev.wawlink.ui.Symbol

internal class PluginInstallDraft(kind: String) {
    var kind by mutableStateOf(kind)
    var name by mutableStateOf("")
    var version by mutableStateOf("")
    var features by mutableStateOf("default")
    var featureNames by mutableStateOf("")
    var git by mutableStateOf("")
    var ref by mutableStateOf("")
    var market by mutableStateOf("")
    var entry by mutableStateOf("")
    var path by mutableStateOf("")
    var force by mutableStateOf(false)
}

@Composable
internal fun PluginInstallSheet(draft: PluginInstallDraft, actions: PluginActions, onClose: () -> Unit, onPick: () -> Unit, onMarkets: () -> Unit, onInstall: (String, String, Boolean) -> Unit) {
    val busy = actions.busy != null
    var localError by remember { mutableStateOf<String?>(null) }
    fun submit() {
        val spec = when(draft.kind) {
            "npm" -> draft.name.trim() + draft.version.trim().takeIf { it.isNotEmpty() }?.let { "@$it" }.orEmpty() + when(draft.features) { "all" -> "[*]"; "none" -> "[]"; "list" -> "[${draft.featureNames.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")}]"; else -> "" }
            "git" -> draft.git.trim() + draft.ref.trim().takeIf { it.isNotEmpty() }?.let { "#$it" }.orEmpty()
            "market" -> if(draft.entry.isNotBlank() && draft.market.isNotBlank()) "${draft.entry}@${draft.market}" else ""
            else -> draft.path.trim()
        }
        localError = when {
            spec.isBlank() || (draft.kind == "npm" && draft.name.isBlank()) -> "请先填写来源"
            draft.kind == "git" && Regex("^[\\w.-]+/[\\w.-]+$").matches(draft.git.trim()) -> "直接安装不识别 owner/repo，请使用 github:owner/repo"
            Regex("https?://[^/\\s@]+(?::[^/\\s@]*)?@", RegexOption.IGNORE_CASE).containsMatchIn(spec) || Regex("[?&](token|key|password|secret)=", RegexOption.IGNORE_CASE).containsMatchIn(spec) -> "地址含凭据，请使用 SSH 或凭据助手，不能写入插件登记"
            draft.kind == "link" && !spec.startsWith('/') -> "请选择容器内绝对目录，链接不会展开 ~"
            else -> null
        }
        if(localError == null) onInstall(spec, draft.kind, draft.force)
    }
    PluginSheet("安装插件", full = true, onClose = onClose, footer = {
        if(busy) OutlinedButton(onClick = actions::cancel, modifier = Modifier.fillMaxWidth()) { Symbol("cancel"); Text("请求取消原生操作") }
        else Button(onClick = ::submit, modifier = Modifier.fillMaxWidth()) { Symbol("download"); Text("继续", fontSize = 17.sp) }
    }) {
        PluginSegments(listOf("npm" to "npm", "git" to "Git", "market" to "市场", "link" to "本地链接"), draft.kind, { draft.kind = it; localError = null }, enabled = { !busy && (!actions.project || it == "market") })
        when(draft.kind) {
            "npm" -> {
                PluginField("包名", draft.name, { draft.name = it })
                PluginField("版本或 dist-tag（空白为最新）", draft.version, { draft.version = it })
                PluginHint("支持 @scope/name；版本可填 1.2.3、^2.0、next。直接 npm 安装不等于市场里的 npm 来源。")
                PluginGroup("可选功能")
                PluginSegments(listOf("default" to "按默认", "all" to "当前全部", "none" to "都不选", "list" to "指定"), draft.features, { draft.features = it }, enabled = { !busy })
                AnimatedVisibility(draft.features == "list", enter = expandVertically(tween(500, easing = EmphEasing)) + fadeIn(tween(400, easing = EmphDecelEasing)), exit = shrinkVertically(tween(500, easing = EmphEasing)) + fadeOut()) { PluginField("功能名，逗号分隔", draft.featureNames, { draft.featureNames = it }) }
                if(draft.features == "all") PluginHint("[*] 仅展开安装时已有的功能，以后新增功能不会自动加入。")
            }
            "git" -> {
                PluginField("仓库", draft.git, { draft.git = it })
                if(Regex("^[\\w.-]+/[\\w.-]+$").matches(draft.git.trim())) PluginHint("请加 github: 前缀；裸 owner/repo 仅是市场源添加的简写。", error = true)
                PluginField("分支、标签或提交（空白为默认分支）", draft.ref, { draft.ref = it })
                PluginHint("支持 github:、gitlab:、bitbucket:、codeberg:、sourcehut:/srht: 以及 HTTPS、SSH、SCP 风格 Git 地址。认证与 ref 可达性由原生 Git/Bun 决定。")
            }
            "market" -> {
                if(actions.markets.isEmpty()) { PluginEmpty("还没有读取到市场源", "storefront") { FilledTonalButton(onClick = onMarkets) { Text(if(actions.project) "浏览市场源" else "添加市场源") } } }
                else {
                    PluginGroup("市场")
                    PluginFilterChips(actions.markets.map { it.pluginText("name") }, draft.market, { draft.market = it; draft.entry = ""; draft.force = false })
                    val market = actions.markets.firstOrNull { it["name"] == draft.market } ?: actions.markets.firstOrNull()
                    if(draft.market.isEmpty() && market != null) LaunchedEffect(market) { draft.market = market.pluginText("name") }
                    market?.get("error")?.let { PluginBanner(it.toString(), error = true) }
                    PluginGroup("插件")
                    val entries = pluginMaps(market?.get("catalog"))
                    entries.forEachIndexed { index, entry ->
                        val supported = entry["supported"] != false
                        PluginRow(entry.pluginText("name"), if(supported) entry.pluginText("description") else entry.pluginText("unsupportedReason"), if(draft.entry == entry["name"]) "radio_button_checked" else "radio_button_unchecked", index, entries.size, enabled = supported && !busy, onClick = { draft.entry = entry.pluginText("name"); draft.force = actions.items.any { it["id"] == "${draft.entry}@${draft.market}" } })
                    }
                    if(entries.isEmpty()) PluginEmpty("此缓存目录没有可用插件", "storefront")
                    PluginGroup("安装范围")
                    PluginSegments(listOf("user" to "用户", "project" to "项目"), actions.fixedScope, {}, enabled = { false })
                    PluginHint("范围由入口的会话工作区固定，不能切到其他项目；用户与项目安装可能共享缓存。")
                }
            }
            "link" -> {
                PluginField("容器内目录", draft.path, { draft.path = it }, trailing = { PluginIcon("folder_open", "选择容器目录", !busy, onPick) })
                if(draft.path.startsWith('~')) PluginHint("链接不会展开 ~，请通过容器文件选择器选择绝对目录。", error = true)
                PluginHint("只建立链接，不复制文件、不安装依赖、不验证 factory。目标目录必须有带 name 的 package.json；覆盖同名链接或目录有风险。", "link")
                OutlinedButton(onClick = onPick, enabled = !busy, modifier = Modifier.padding(top = 12.dp)) { Symbol("folder_open"); Text("选择容器内目录") }
            }
        }
        if(draft.kind != "market") PluginHint("直接 npm/Git 安装与本地链接写入用户插件根，OMP 不支持将它们安装到项目。", "person")
        PluginRow("覆盖 / 强制重装", "已有同名安装可能被替换；市场缓存可能被另一范围共用", "warning", trailing = { Switch(draft.force, { draft.force = it }, enabled = !busy) })
        localError?.let { PluginHint(it, "error", error = true) }
        actions.error?.let { PluginBanner(it, error = true) { PluginHint("草稿和选择均保留。失败/取消并不保证没有写入，已重新读取实际持久状态。") } }
        if(busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); PluginHint("原生操作正在执行。当前 API 不提供可靠的逐阶段进度，不显示虚构百分比。取消只终止管理进程，不能撤销已执行的外部副作用。") }
    }
}

@Composable
internal fun PluginSettingSheet(key: String, detail: Map<String, Any?>, project: Boolean, busy: Boolean, error: String?, onClose: () -> Unit, onSave: (Any?, Boolean) -> Unit) {
    val settings = pluginMap(detail["settings"])
    val schema = pluginMap(pluginMap(settings["schema"])[key])
    val effective = pluginMap(settings["effective"])[key]
    val currentLayer = pluginMap(settings[if(project) "project" else "user"])
    val original = currentLayer[key] ?: effective
    val secret = schema["secret"] == true || original == "••••••••"
    var text by remember(key) { mutableStateOf(if(secret) "" else original?.toString().orEmpty()) }
    var boolean by remember(key) { mutableStateOf(original == true) }
    var enum by remember(key) { mutableStateOf(original?.toString().orEmpty()) }
    var showSecret by remember(key) { mutableStateOf(false) }
    var touched by remember(key) { mutableStateOf(false) }
    var validation by remember(key) { mutableStateOf<String?>(null) }
    fun save() {
        val value: Any? = when(schema["type"]) { "boolean" -> boolean; "enum" -> enum; "number" -> text.toDoubleOrNull(); else -> text }
        val number = value as? Double
        validation = when {
            secret && !touched -> "秘密值不会预填或覆盖；输入新值或清除本层"
            schema["type"] == "number" && (number == null || !number.isFinite()) -> "请输入有限数值"
            number != null && (schema["min"] as? Number)?.toDouble()?.let { number < it } == true -> "不能小于 ${schema["min"]}"
            number != null && (schema["max"] as? Number)?.toDouble()?.let { number > it } == true -> "不能大于 ${schema["max"]}"
            schema["type"] == "enum" && enum !in pluginStrings(schema["values"]) -> "请选择有效枚举值"
            else -> null
        }
        if(validation == null) onSave(value, false)
    }
    PluginSheet(schema.pluginText("description", key), onClose = onClose, footer = {
        TextButton(onClick = { onSave(null, true) }, enabled = !busy) { Text("清除本层") }
        Spacer(Modifier.weight(1f))
        Button(onClick = ::save, enabled = !busy && schema["type"] in listOf("string", "number", "boolean", "enum")) { Text(if(busy) "正在保存" else "完成") }
    }) {
        PluginBanner("正在编辑${if(project) "项目" else "用户"}层；${if(project) "写入真实项目覆盖文件，与用户值浅合并" else "写入插件运行状态文件"}", icon = if(project) "folder" else "person")
        PluginHint(schema.pluginText("description", key))
        when(schema["type"]) {
            "boolean" -> PluginRow(key, pluginDisplay(boolean), "toggle_on", trailing = { Switch(boolean, { boolean = it; touched = true }, enabled = !busy) })
            "enum" -> pluginStrings(schema["values"]).forEach { value -> PluginRow(value, icon = if(value == enum) "radio_button_checked" else "radio_button_unchecked", onClick = { enum = value; touched = true }) }
            "string", "number" -> PluginField(if(secret) "输入新值（现有值不回传）" else "本层值", text, { text = it; touched = true }, secret = secret && !showSecret, trailing = if(secret) ({ PluginIcon(if(showSecret) "visibility_off" else "visibility", "切换新输入值的遮罩") { showSecret = !showSecret } }) else null)
            else -> PluginHint("此字段类型不是 OMP 支持的 string/number/boolean/enum，不能创建伪表单。", error = true)
        }
        if(secret) PluginHint("遮罩不是加密保险库。原值不会进入此表单、日志或导出；输入新值前，保存按钮不会用遮罩覆盖秘密。", "lock")
        schema["env"]?.let { PluginHint("环境变量：$it。schema 声明不代表每个插件组件都统一消费；实际以插件实现为准。", "terminal") }
        PluginGroup("来源与覆盖")
        PluginKeyValues(listOf("默认值" to pluginMap(settings["defaults"])[key], "用户" to pluginMap(settings["user"])[key]) + if(project) listOf("项目" to pluginMap(settings["project"])[key], "有效值" to effective) else listOf("有效值" to effective), effective = true)
        PluginHint("有效值是 schema 默认 → 用户 → 项目的静态浅合并预览，不包含环境变量，也不是正在运行的插件消费值证明。原生运行时可能按组件自行处理默认值与 env。")
        PluginGroup("详情")
        PluginKeyValues(listOf("配置键" to key, "类型" to schema["type"], "生效时机" to "新会话 / 插件重新消费配置后") + listOf("min", "max", "step").mapNotNull { field -> schema[field]?.let { field to it } })
        PluginHint("清除本层是恢复继承，不是删除其他层。未知设置保持原样；设置是浅合并，不是深合并。")
        validation?.let { PluginHint(it, "error", true) }
        error?.let { PluginBanner(it, error = true) }
    }
}

@Composable
internal fun PluginAddMarketSheet(actions: PluginActions, source: String, onSource: (String) -> Unit, onClose: () -> Unit, onAdded: () -> Unit) {
    val hasCredentials = Regex("https?://[^/\\s@]+(?::[^/\\s@]*)?@", RegexOption.IGNORE_CASE).containsMatchIn(source) || Regex("[?&](token|key|password|secret)=", RegexOption.IGNORE_CASE).containsMatchIn(source)
    val type = when { Regex("^[\\w.-]+/[\\w.-]+$").matches(source.trim()) -> "GitHub 仓库"; source.startsWith("http") && source.substringBefore('?').endsWith(".json") -> "目录 JSON"; source.startsWith('/') || source.startsWith("~/") || source.startsWith("./") -> "本地目录"; source.startsWith("https://") || source.startsWith("http://") || source.startsWith("ssh://") || source.startsWith("git@") || source.startsWith("git:") -> "Git 仓库"; else -> null }
    var confirm by remember { mutableStateOf(false) }
    PluginSheet("添加市场源", onClose = onClose, footer = { Button(onClick = { confirm = true }, enabled = type != null && !hasCredentials && actions.busy == null && !actions.project, modifier = Modifier.fillMaxWidth()) { Text("添加") } }) {
        PluginField("owner/repo、Git 地址、.json 地址或本地目录", source, onSource)
        if(hasCredentials) PluginHint("地址含凭据；请改用 SSH 或凭据助手，不能将密钥写入市场登记。", error = true)
        if(source.isNotEmpty()) PluginHint(if(type == null) "无法识别这个地址" else "识别为$type${if(type == "目录 JSON") "，无法安装相对路径条目" else if(type == "本地目录") "，原生会展开 ~" else "，需要克隆，私有仓库需要凭据"}", if(type == null) "error" else "info", type == null)
        PluginHint("先读 .omp-plugin/marketplace.json，再读 .claude-plugin/marketplace.json。市场源属于用户级，没有通用项目源登记；同名/大小写冲突和目录改名由原生校验。")
        if(actions.project) PluginBanner("项目入口只读市场源；请从当前容器的 Functions 用户页管理市场源。", warning = true)
        actions.error?.let { PluginBanner(it, error = true) }
        if(actions.busy != null) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = actions::cancel) { Text("请求取消") } }
    }
    if(confirm) AlertDialog(onDismissRequest = { confirm = false }, icon = { Symbol("storefront") }, title = { Text("添加市场源") }, text = { Text("原生将访问此来源并缓存目录，仓库源需要 Git 克隆。不会自动安装目录里的插件。带凭据 URL 会被拒绝。\n$source", fontSize = 17.sp) }, confirmButton = { Button(onClick = { confirm = false; actions.mutate("marketAdd", mapOf("source" to source.trim(), "confirmed" to true)) { onAdded() } }) { Text("添加") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } }, shape = RoundedCornerShape(28.dp))
}
