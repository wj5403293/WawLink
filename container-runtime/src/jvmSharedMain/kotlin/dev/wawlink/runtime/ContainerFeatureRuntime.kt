package dev.wawlink.runtime

import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

private const val MAX_CARD_HTML_BYTES = 256 * 1024

/** One admission epoch spans discovery, validation and every command they issue. */
internal class FeatureOperation(val containerId: String, val stopGeneration: Long, var runningOnly: Boolean) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<FeatureOperation>

    suspend fun ensureCurrent(runtime: ContainerRuntime) {
        currentCoroutineContext().ensureActive()
        if (runtime.wasInterrupted(containerId, stopGeneration)) throw CancellationException("容器已停止或重新启动")
    }
}

internal suspend fun <T> withFeatureOperation(runtime: ContainerRuntime, containerId: String, block: suspend FeatureOperation.() -> T): T {
    val inherited = currentCoroutineContext()[FeatureOperation]?.takeIf { it.containerId == containerId }
    val operation = inherited ?: runtime.containers.value.firstOrNull { it.config.id == containerId }?.let {
        FeatureOperation(containerId, it.stopGeneration, it.running)
    } ?: throw CancellationException("容器已移除")
    return withContext(operation) {
        operation.ensureCurrent(runtime)
        try { operation.block() }
        catch (failure: Throwable) {
            operation.ensureCurrent(runtime)
            throw failure
        }
    }
}

internal fun parseFeatureLifecycle(manifest: JsonObject): ContainerFeatureLifecycle = when (val value = manifest["lifecycle"]) {
    null -> ContainerFeatureLifecycle.PAGE
    else -> {
        require(value is JsonPrimitive && value.isString) { "lifecycle 必须为字符串" }
        when (value.content) {
            "page" -> ContainerFeatureLifecycle.PAGE
            "manual" -> ContainerFeatureLifecycle.MANUAL
            "container" -> ContainerFeatureLifecycle.CONTAINER
            else -> error("lifecycle 只能为 page、manual 或 container")
        }
    }
}

internal fun parseFeatureCardUrl(manifest: JsonObject): String? = manifest["cardUrl"]?.let {
    require(it is JsonPrimitive && it.isString && it.content.isNotBlank()) { "cardUrl 必须为非空字符串" }
    require("card" !in manifest) { "card 和 cardUrl 不能同时声明" }
    validateFeatureCardUrl(it.content)
    it.content
}

private fun decodeFeatureUrl(value: String): String {
    val bytes = ByteArrayOutputStream()
    var index = 0
    while (index < value.length) {
        if (value[index] == '%') {
            require(index + 2 < value.length) { "cardUrl 包含无效编码" }
            val high = value[index + 1].digitToIntOrNull(16)
            val low = value[index + 2].digitToIntOrNull(16)
            require(high != null && low != null) { "cardUrl 包含无效编码" }
            bytes.write(high * 16 + low)
            index += 3
        } else {
            val end = value.indexOf('%', index).takeIf { it >= 0 } ?: value.length
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .encode(java.nio.CharBuffer.wrap(value, index, end))
            while (encoded.hasRemaining()) bytes.write(encoded.get().toInt() and 255)
            index = end
        }
    }
    return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
}

internal fun validateFeatureCardUrl(value: String) {
    val uri = URI(value)
    val decoded = decodeFeatureUrl(value)
    require(decoded.none { it < ' ' || it == '\\' || it == '\u007f' }) { "cardUrl 包含无效字符" }
    if (value.startsWith('/')) {
        require(!value.startsWith("//") && !uri.isAbsolute && uri.rawAuthority == null && !uri.path.startsWith("//")) { "cardUrl 必须为单斜线开头的绝对路径" }
    } else {
        require(uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost") && uri.port in 1..65535 &&
            uri.rawUserInfo == null) { "cardUrl 只允许显式端口的 http://127.0.0.1 或 http://localhost 地址" }
    }
    require(uri.rawQuery.orEmpty().split('&').none { part ->
        decodeFeatureUrl(part.substringBefore('=')).replace('+', ' ') == "__wawlink_token"
    }) { "cardUrl 不能声明保留的 __wawlink_token 参数" }
}

/** Managed paths need a live gateway; explicit loopback URLs never receive its token. */
fun resolveFeatureCardUrl(feature: ContainerFeature, session: ContainerFeatureSession?): String? {
    val cardUrl = feature.cardUrl ?: return null
    validateFeatureCardUrl(cardUrl)
    if (!cardUrl.startsWith('/')) return cardUrl
    val live = session ?: return null
    val gateway = URI(live.url)
    val card = URI(cardUrl)
    val bootstrap = gateway.rawQuery.orEmpty().split('&').singleOrNull { it.substringBefore('=') == "__wawlink_token" }
        ?: error("功能会话缺少访问令牌")
    val query = card.rawQuery?.let { if (it.isEmpty()) bootstrap else "$it&$bootstrap" } ?: bootstrap
    return "${gateway.scheme}://${gateway.rawAuthority}${card.rawPath}?$query" + (card.rawFragment?.let { "#$it" } ?: "")
}

internal fun parseFeatureCardSpan(manifest: JsonObject): Int = manifest["cardSpan"]?.let {
    require(it is JsonPrimitive && !it.isString && it.intOrNull?.let { span -> span in 1..2 } == true) { "cardSpan 必须为整数 1 或 2" }
    it.int
} ?: 1

internal fun validateFeatureRelativeScript(script: String) {
    require(script.isNotBlank() && !script.startsWith('/') && '\u0000' !in script &&
        script.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "script 必须为功能目录内的相对文件路径" }
}

/** Parsing and discovery are data-only; neither shell nor Lua source is evaluated here. */
internal fun parseFeatureActions(manifest: JsonObject): List<ContainerFeatureAction> {
    val entries = manifest["actions"] ?: return emptyList()
    require(entries is JsonArray && entries.size <= 3) { "actions 必须为最多三个动作的数组" }
    val ids = HashSet<String>()
    return entries.map { entry ->
        require(entry is JsonObject) { "action 必须为对象" }
        fun string(name: String): String? = entry[name]?.let {
            require(it is JsonPrimitive && it.isString && it.content.isNotBlank()) { "action.$name 必须为非空字符串" }
            it.content
        }
        fun required(name: String): String = requireNotNull(string(name)) { "缺少 action.$name 字符串字段" }
        val id = required("id")
        require(id.matches(Regex("[a-z0-9][a-z0-9_-]{0,63}"))) { "action.id 必须为 1–64 位小写字母、数字、下划线或短横线，首位为字母或数字" }
        require(ids.add(id)) { "action.id 重复：$id" }
        val title = required("title")
        val icon = required("icon")
        require(icon.matches(Regex("[a-z][a-z0-9_]{0,79}"))) { "action.icon 必须是 Material Symbols 名称" }
        val type = when (required("type")) {
            "open" -> ContainerFeatureActionType.OPEN
            "start" -> ContainerFeatureActionType.START
            "stop" -> ContainerFeatureActionType.STOP
            "toggle" -> ContainerFeatureActionType.TOGGLE
            "logs" -> ContainerFeatureActionType.LOGS
            "script" -> ContainerFeatureActionType.SCRIPT
            "lua" -> ContainerFeatureActionType.LUA
            else -> throw IllegalArgumentException("action.type 只能为小写 open、start、stop、toggle、logs、script 或 lua")
        }
        val script = string("script")?.also(::validateFeatureRelativeScript)
        val lua = string("lua")
        require(type != ContainerFeatureActionType.SCRIPT || script != null) { "script 动作缺少相对 script 文件路径" }
        require(type != ContainerFeatureActionType.LUA || lua != null) { "lua 动作缺少非空内联 lua 源码" }
        ContainerFeatureAction(id, title, icon, type, script, lua)
    }
}

/** Reads data only, using the configured guest identity and never a host/rootfs pathname. */
internal suspend fun readFeatureCardHtml(runtime: ContainerRuntime, containerId: String, directory: String, requestedFile: String?): String? {
    val file = requestedFile ?: "card.html"
    require(!file.startsWith('/') && '\u0000' !in file && file.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
        "card 必须为功能目录内的相对路径"
    }
    val response = featureCommand(runtime, containerId, """
        set -eu
        dir=${shellQuote(directory)}
        [ "${'$'}(readlink -f -- "${'$'}dir")" = "${'$'}dir" ] || { echo '功能目录已变化' >&2; exit 1; }
        file="${'$'}dir"/${shellQuote(file)}
        if [ ! -e "${'$'}file" ] && [ ! -L "${'$'}file" ]; then
            ${if (requestedFile == null) "exit 0" else "echo '找不到 card 指定的 HTML 文件' >&2; exit 1"}
        fi
        resolved=${'$'}(readlink -f -- "${'$'}file")
        case "${'$'}resolved" in "${'$'}dir/"*) ;; *) echo '卡片 HTML 越出功能目录' >&2; exit 1;; esac
        [ -f "${'$'}resolved" ] || { echo '卡片 HTML 必须为普通文件' >&2; exit 1; }
        [ "${'$'}(stat -Lc %s -- "${'$'}resolved")" -le $MAX_CARD_HTML_BYTES ] || { echo '卡片 HTML 超过 256 KiB' >&2; exit 1; }
        printf 'H\n'
        head -c ${MAX_CARD_HTML_BYTES + 1} -- "${'$'}resolved" | base64 | tr -d '\n'
    """.trimIndent()).toString(Charsets.UTF_8)
    if (response.isEmpty()) return null
    require(response.startsWith("H\n")) { "卡片 HTML 读取返回无效数据" }
    val bytes = Base64.getDecoder().decode(response.substring(2))
    require(bytes.size <= MAX_CARD_HTML_BYTES) { "卡片 HTML 超过 256 KiB" }
    return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
}

/** Guest PID/start-time identity, not the host Process wrapper PID (which may be su/proot). */
internal data class FeatureProcessIdentity(val supervisorPid: Long, val supervisorStart: Long, val groupPid: Long, val groupStart: Long) {
    companion object {
        fun parse(line: String, marker: String): FeatureProcessIdentity? {
            check(line.startsWith("$marker ")) { "功能监管身份协议无效" }
            val fields = line.removePrefix("$marker ").split(' ')
            if (fields == listOf("unavailable")) return null
            require(fields.size == 4) { "功能监管身份协议无效" }
            val values = fields.map { it.toLong().also { value -> require(value > 0) } }
            return FeatureProcessIdentity(values[0], values[1], values[2], values[3])
        }
    }
}

/** A fixed-size preamble is emitted before start.sh can write stdout. */
internal fun readFeatureIdentity(input: InputStream, marker: String): FeatureProcessIdentity? {
    val line = StringBuilder()
    while (true) {
        val next = input.read()
        check(next >= 0) { "功能进程未提供监管身份" }
        if (next == 10) break
        check(line.length < 512) { "功能监管身份超过大小上限" }
        line.append(next.toChar())
    }
    return FeatureProcessIdentity.parse(line.toString(), marker)
}

/** Linux stat fields after the last ') ': state, ppid, pgrp, session, ... starttime. */
internal val FEATURE_PROC_IDENTITY_SH = """
    proc_identity() {
        IFS= read -r row < "/proc/${'$'}1/stat" 2>/dev/null || return 1
        fields=${'$'}{row##*) }
        set -f
        set -- ${'$'}fields
        [ "${'$'}#" -ge 22 ] || return 1
        group=${'$'}3; session=${'$'}4
        shift 19
        printf '%s %s %s' "${'$'}group" "${'$'}session" "${'$'}1"
    }
""".trimIndent()

internal suspend fun readFeatureMemoryBytes(runtime: ContainerRuntime, containerId: String, identity: FeatureProcessIdentity): Long? {
    val result = featureCommand(runtime, containerId, """
        set -eu
        $FEATURE_PROC_IDENTITY_SH
        valid_identity() {
            supervisor=${'$'}(proc_identity ${identity.supervisorPid}) || return 1
            set -- ${'$'}supervisor
            [ "${'$'}3" = ${identity.supervisorStart} ] || return 1
            leader=${'$'}(proc_identity ${identity.groupPid}) || return 1
            [ "${'$'}leader" = '${identity.groupPid} ${identity.groupPid} ${identity.groupStart}' ]
        }
        valid_identity || exit 0
        total=0
        found=0
        for stat in /proc/[0-9]*/stat; do
            pid=${'$'}{stat%/stat}; pid=${'$'}{pid##*/}
            entry=${'$'}(proc_identity "${'$'}pid") || continue
            set -- ${'$'}entry
            [ "${'$'}1" = ${identity.groupPid} ] && [ "${'$'}2" = ${identity.groupPid} ] || continue
            before=${'$'}entry
            rss=''
            if [ ! -r "/proc/${'$'}pid/status" ]; then exit 0; fi
            while read -r name value unit rest; do
                if [ "${'$'}name" = VmRSS: ] && [ "${'$'}unit" = kB ]; then rss=${'$'}value; break; fi
            done < "/proc/${'$'}pid/status" || exit 0
            after=${'$'}(proc_identity "${'$'}pid") || continue
            [ "${'$'}before" = "${'$'}after" ] || continue
            case "${'$'}rss" in ''|*[!0-9]*) exit 0;; esac
            total=${'$'}((total + rss * 1024))
            found=1
        done
        valid_identity || exit 0
        [ "${'$'}found" = 1 ] || exit 0
        printf '%s' "${'$'}total"
    """.trimIndent()).toString(Charsets.UTF_8)
    return result.toLongOrNull()?.takeIf { it >= 0 }
}

/** Repeat the file service mount preflight before running the optional stop hook. */
internal fun featureRemovalMountGuard(directory: String): String = """
    set -eu
    [ -r /proc/self/mountinfo ] || { echo '无法检查系统挂载，尚未卸载' >&2; exit 1; }
    target=${shellQuote(directory)}
    while IFS=' ' read -r mount_id parent_id device mount_root mount_path rest; do
        decoded=${'$'}(printf '%b_' "${'$'}mount_path")
        decoded=${'$'}{decoded%_}
        [ "${'$'}decoded" = / ] && continue
        case "${'$'}target/" in "${'$'}decoded/"*) echo '不能卸载挂载及其子目录中的功能' >&2; exit 1;; esac
        case "${'$'}decoded/" in "${'$'}target/"*) echo '功能目录包含系统挂载，尚未卸载' >&2; exit 1;; esac
    done < /proc/self/mountinfo
""".trimIndent()

/** Links inside a feature are unlinked, not traversed; the selected root and ancestors cannot be links. */
internal fun featureRemovalScript(directory: String): String = featureRemovalMountGuard(directory) + "\n" + """
    fail() { printf '%s\n' "${'$'}*" >&2; exit 1; }
    checked_dir() {
        if [ "${'$'}1" = / ] || [ -z "${'$'}1" ]; then return; fi
        checked_dir "${'$'}{1%/*}"
        [ ! -L "${'$'}1" ] && [ -d "${'$'}1" ] && [ -x "${'$'}1" ] || fail '功能路径包含链接或不可访问目录'
    }
    remove_tree() (
        parent=${'$'}{1%/*}
        [ -w "${'$'}parent" ] && [ -x "${'$'}parent" ] || fail "父目录不可写：${'$'}parent"
        if [ -k "${'$'}parent" ] && [ "${'$'}(id -u)" != 0 ]; then
            uid=${'$'}(id -u)
            [ "${'$'}(stat -c %u -- "${'$'}parent")" = "${'$'}uid" ] || [ "${'$'}(stat -c %u -- "${'$'}1")" = "${'$'}uid" ] || fail '粘滞目录中的条目不属于当前用户'
        fi
        if [ ! -L "${'$'}1" ] && [ -d "${'$'}1" ]; then
            [ -r "${'$'}1" ] && [ -w "${'$'}1" ] && [ -x "${'$'}1" ] || fail "目录不可读取或写入：${'$'}1"
            for entry in "${'$'}1"/* "${'$'}1"/.[!.]* "${'$'}1"/..?*; do
                [ -e "${'$'}entry" ] || [ -L "${'$'}entry" ] || continue
                remove_tree "${'$'}entry" "${'$'}2"
            done
            [ "${'$'}2" = preflight ] || rmdir -- "${'$'}1"
        else
            [ "${'$'}2" = preflight ] || rm -- "${'$'}1"
        fi
    )
    checked_dir "${'$'}target"
    [ "${'$'}(readlink -f -- "${'$'}target")" = "${'$'}target" ] || fail '功能目录已变化'
    remove_tree "${'$'}target" preflight
    remove_tree "${'$'}target" delete
""".trimIndent()
