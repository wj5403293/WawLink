@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.wawlink.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wawlink.ui.Symbol
import kotlinx.coroutines.delay

@Composable internal fun SkillAuthorPage(state: SkillUiState) {
    var answer by remember { mutableStateOf("") }; var createToken by remember { mutableStateOf(false) }; var tokenName by remember { mutableStateOf("") }; var tokenDays by remember { mutableStateOf("90") }; var packages by remember { mutableStateOf("") }; var oneTime by remember { mutableStateOf<SkillObject?>(null) }; var revoke by remember { mutableStateOf<SkillObject?>(null) }; var authorFilter by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current; val uri = LocalUriHandler.current
    LaunchedEffect(state.author.skillText("status")) { while (state.author.skillText("status") == "waiting") { delay(800); if (state.busy == null) state.request("loginStatus", label = "等待 Stencil 登录") { state.author = it } } }
    SkillBanner("技能市场使用独立的 Stencil 账号，不是模型服务 API Key。浏览与安装无需登录；发布、维护和令牌操作由服务器校验权限。", "key")
    if (!state.author.skillBool("signedIn")) {
        Button({ state.request("login", label = "正在启动原生 Stencil 登录") { state.author = state.author + it } }, Modifier.fillMaxWidth(), enabled = state.author.skillText("status") != "waiting") { Symbol("login"); Text("登录技能市场") }
        if (state.author.skillText("status") == "waiting") {
            SkillBanner(state.author.skillText("instructions", "等待完成原生认证"), "open_in_browser") {
                state.author["url"]?.let { raw -> TextButton({ runCatching { uri.openUri(raw.toString()) }.onFailure { state.error = "无法打开浏览器，请复制登录地址手动打开" } }) { Text("打开登录页面") }; TextButton({ clipboard.setText(AnnotatedString(raw.toString())) }) { Text("复制登录地址") } }
                if (state.author["prompt"] != null) { SkillField(state.author.skillText("prompt"), answer, { answer = it }); Button({ state.request("answerLogin", mapOf("value" to answer), "正在提交登录回答") { answer = ""; state.author = state.author + it } }) { Text("继续") } }
                TextButton({ state.request("cancel", label = "正在取消登录") { state.author = emptyMap() } }) { Text("取消登录") }
            }
        }
        state.author["error"]?.let { SkillBanner(it.toString(), "error", error = true) }
        SkillHeading("不登录也能做")
        SkillRow("发布预检", "本地确定性打包、格式与大小检查、密钥扫描；不上传", "fact_check", onClick = { state.navigate("publish") })
        SkillBanner("登录不可用时，可在当前容器运行 omp，使用 /login → Stencil；也可由受信任的容器环境设置 STENCIL_API_KEY。发布专用 SKILLS_TOKEN 仅能发布，不能代替维护权限。")
    } else {
        SkillRow(state.author.skillText("username", "已登录 Stencil"), "身份声明不代表最终权限；权限由市场服务器校验", "account_circle", trailing = { TextButton({ state.request("logout", label = "正在退出 Stencil") { state.author = emptyMap(); state.tokens = emptyList(); state.mine = emptyList(); if (it.skillBool("environmentCredential")) state.notice = "已删除存储账号，但 STENCIL_API_KEY 环境变量仍提供认证；请在容器环境中移除" } }) { Text("退出") } })
        Button({ state.navigate("publish") }, Modifier.fillMaxWidth()) { Symbol("publish"); Text("发布技能") }
        SkillHeading("我的技能包", "package_2")
        SkillField("作者 scope", authorFilter.ifEmpty { state.author.skillText("username") }, { authorFilter = it }, mono = true)
        TextButton({ val owner = authorFilter.ifEmpty { state.author.skillText("username") }; state.request("myPackages", mapOf("owner" to owner, "page" to 1), "正在读取作者技能包") { state.mine = it.skillObjects("hits") } }) { Text("读取作者技能包") }
        state.mine.forEach { item -> SkillRow("@${item.skillText("scope")}/${item.skillText("name")}", "${item.skillText("description")} · ${item.skillText("version")}", "package_2", onClick = { state.openPackage("@${item.skillText("scope")}/${item.skillText("name")}") }) }
        SkillHeading("发布令牌", "key") { TextButton({ createToken = true }) { Text("创建") } }
        TextButton({ state.request("tokens", label = "正在读取发布令牌元数据") { state.tokens = it.skillObjects("tokens") } }) { Text("读取令牌列表") }
        state.tokens.forEach { token -> SkillRow(token.skillText("name"), "${token.skillStrings("packages").joinToString().ifEmpty { "我管理的全部技能包" }}\n到期 ${token.skillText("expiresAt", "未指定")}", "key", trailing = { SkillIconButton("delete", "吊销令牌") { revoke = token } }) }
        SkillBanner("令牌仅用于发布，不可管理标签、撤回或维护者；secret 仅创建时显示一次，不写入项目、日志或导出。组织、举报、管理员下架不属于当前原生客户端可用界面。")
    }
    if (createToken) ModalBottomSheet({ createToken = false }) { Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
        Text("创建发布令牌", fontSize = 22.sp); SkillField("名称", tokenName, { tokenName = it }); SkillField("限定技能包（每行 @scope/name；空表示所管理全部包）", packages, { packages = it }, false, true); SkillField("有效天数", tokenDays, { tokenDays = it }); Button({ state.request("createToken", mapOf("name" to tokenName, "days" to tokenDays.toDoubleOrNull(), "packages" to packages.lines().map(String::trim).filter(String::isNotEmpty)), "正在创建发布令牌") { oneTime = it.skillObject("created"); createToken = false } }) { Text("创建") }
    } }
    oneTime?.let { response -> AlertDialog(onDismissRequest = {}, icon = { Symbol("visibility") }, title = { Text("令牌只显示这一次") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text("关闭后无法再次查看。请保存到安全位置；不会写入项目或日志。"); val secret = response.skillText("token", response.skillText("secret")); androidx.compose.foundation.text.selection.SelectionContainer { Text(secret, fontFamily = FontFamily.Monospace, fontSize = 16.sp) }; TextButton({ clipboard.setText(AnnotatedString(secret)) }) { Symbol("content_copy"); Text("复制令牌") } } }, confirmButton = { Button({ oneTime = null; state.request("tokens") { state.tokens = it.skillObjects("tokens") } }) { Text("我已安全保存") } }) }
    revoke?.let { token -> AlertDialog(onDismissRequest = { revoke = null }, title = { Text("吊销 ${token.skillText("name")}？") }, text = { Text("使用此令牌的自动发布会立即失败，无法恢复原 secret。") }, confirmButton = { Button({ state.request("revokeToken", mapOf("tokenId" to token.skillText("id")), "正在吊销令牌") { revoke = null; state.request("tokens") { state.tokens = it.skillObjects("tokens") } } }) { Text("吊销") } }, dismissButton = { TextButton({ revoke = null }) { Text("取消") } }) }
}

@Composable internal fun SkillPublishPage(state: SkillUiState) {
    val candidates = state.snapshot.skillObjects("skills").filter { it.skillBool("writable") }
    var path by remember { mutableStateOf(candidates.firstOrNull()?.skillText("path") ?: "") }; var publisher by remember { mutableStateOf(state.author.skillText("username")) }; var tag by remember { mutableStateOf("latest") }; var report by remember { mutableStateOf<SkillObject?>(null) }; var confirm by remember { mutableStateOf(false) }; var versionKind by remember { mutableStateOf("patch") }
    SkillBanner("1 选择技能 → 2 本地预检 → 3 明确确认发布。预检不会上传；正式发布会公开上传，版本不可覆盖。", "fact_check")
    SkillHeading("技能目录", "folder_open")
    candidates.forEach { s -> SkillRow(s.skillText("name"), s.skillText("path"), "school", onClick = { path = s.skillText("path"); report = null }, trailing = { RadioButton(path == s.skillText("path"), { path = s.skillText("path"); report = null }) }) }
    SkillField("容器内技能目录", path, { path = it; report = null }, mono = true)
    SkillField("发布者 scope（不含 @，由服务器验证）", publisher, { publisher = it }, mono = true)
    SkillField("发布标签（如 latest、beta）", tag, { tag = it }, mono = true)
    Button({ state.request("preflight", mapOf("path" to path), "正在本地打包、校验并扫描") { report = it } }, enabled = state.busy == null && path.isNotBlank()) { Symbol("fact_check"); Text(if (report == null) "开始预检（不上传）" else "重新预检") }
    report?.let { pack ->
        SkillHeading("预检结果", "rule")
        SkillValues(listOf("名称" to pack.skillText("name"), "版本" to pack.skillText("version"), "文件" to pack.skillObjects("files").size.toString(), "压缩后" to "${pack.skillText("packedSize")} 字节 / 5 MiB", "解包后" to "${pack.skillText("unpackedSize")} 字节 / 20 MiB", "完整性" to pack.skillText("integrity")))
        pack.skillObjects("files").forEach { SkillRow(it.skillText("path"), "${it.skillText("size")} 字节${if (it.skillBool("executable")) " · 可执行" else ""}", if (it.skillBool("executable")) "terminal" else "draft") }
        if (pack.skillObjects("secrets").isEmpty()) SkillBanner("未发现凭据形状的文本；不是病毒、提示注入或恶意脚本审计。", "check_circle")
        else { SkillBanner("检测到疑似密钥，禁止上传。只显示位置与类型，不显示密钥。请移除或通过 .skillignore 排除后重试。", "key", error = true); pack.skillObjects("secrets").forEach { SkillRow("${it.skillText("path")}:${it.skillText("line")}", it.skillText("kind"), "key") } }
        if (pack.skillBool("hasScripts")) SkillBanner("包含脚本。发布不会执行，其他用户使用此技能时模型可能运行。", "terminal", warning = true)
        Button({ confirm = true }, Modifier.fillMaxWidth(), enabled = pack.skillBool("publishable") && publisher.isNotBlank() && state.busy == null) { Symbol("publish"); Text("发布 @$publisher/${pack.skillText("name")}@${pack.skillText("version")}") }
    }
    SkillHeading("只修改 metadata.version", "history")
    SkillField("patch / minor / major / 精确新版本", versionKind, { versionKind = it }, mono = true)
    TextButton({ state.request("bumpVersion", mapOf("path" to path, "kind" to versionKind), "正在保留其他字节并修改版本") { report = null; state.notice = "已修改版本：${it["result"]}。请重新预检。" } }) { Text("修改本地版本") }
    SkillBanner("原生打包遵守 .skillignore，检查 1000 文件 / 路径 / 符号链接 / 大小与严格 schema。扫描不等于安全认证；不存在任意作者来源“已验证”的默认徽标。")
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, icon = { Symbol("publish") }, title = { Text("确认公开发布？") }, text = { Text("将上传 @$publisher/${report?.skillText("name")}@${report?.skillText("version")}，标签 $tag。版本不可变；预检后文件若变化会拒绝发布。线上服务器 401/403/409 为最终结果。") }, confirmButton = { Button({ state.request("publish", mapOf("path" to path, "publisher" to publisher, "tag" to tag, "integrity" to report?.skillText("integrity"), "confirm" to true), "正在上传并等待服务器确认") { confirm = false; report = null; state.notice = "服务器确认发布：${it.skillObject("published")}" } }) { Text("确认上传并公开发布") } }, dismissButton = { TextButton({ confirm = false }) { Text("取消") } })
}
