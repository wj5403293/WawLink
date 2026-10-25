package dev.wawlink.runtime

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

private const val MAX_CARD_HTML_BYTES = 256 * 1024

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
