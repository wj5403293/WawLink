package dev.wawlink.runtime

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Preferences belong to the configured guest user's HOME, never to host application storage. */
class ContainerFeaturePreferences(private val runtime: ContainerRuntime) {
    suspend fun read(containerId: String): String? {
        val response = featureCommand(runtime, containerId, guard + """
            if [ ! -e "${'$'}dir" ]; then exit 0; fi
            checked_directory
            checked_file
            if [ ! -e "${'$'}file" ]; then exit 0; fi
            printf 'D'
            head -c ${MAX_BYTES + 1} -- "${'$'}file"
        """.trimIndent())
        if (response.isEmpty()) return null
        check(response[0] == 'D'.code.toByte()) { "容器返回了无效的功能卡片设置" }
        check(response.size - 1 <= MAX_BYTES && response.none { it == 0.toByte() }) { "功能卡片设置包含 NUL 或超过 2 MiB" }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response, 1, response.size - 1)).toString()
    }

    /** Compare the exact prior document before atomically replacing it. Null requires absence. */
    suspend fun write(containerId: String, document: String, expectedDocument: String?) {
        val next = textBytes(document)
        val expected = expectedDocument?.let { previous ->
            MessageDigest.getInstance("SHA-256").digest(textBytes(previous)).joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        val shared = runtime.containers.value.firstOrNull { it.config.id == containerId }?.config?.engine == ContainerEngineType.CHROOT
        featureCommand(runtime, containerId, guard + """
            umask ${if (shared) "0007" else "0077"}
            if [ ! -e "${'$'}dir" ]; then mkdir -- "${'$'}dir"; fi
            checked_directory
            cd -P -- "${'$'}dir"
            dir=${'$'}PWD
            file="${'$'}dir/feature-cards.json"
            # Keep the inherited App shared group; change only this management directory.
            ${if (shared) "chmod 2770 ." else "mode=\$(stat -c %a .); [ \"\$((0\$mode & 0700))\" = 448 ] || chmod u+rwx ."}
            temporary=${'$'}(mktemp "${'$'}dir/.feature-cards-XXXXXXXX")
            trap 'rm -f -- "${'$'}temporary"' 0
            trap 'exit 1' HUP INT TERM
            cat > "${'$'}temporary"
            [ "${'$'}(stat -c %s -- "${'$'}temporary")" = ${next.size} ] || fail '功能卡片设置写入不完整'
            checked_file
            ${if (expected == null) "[ ! -e \"\$file\" ] || fail '功能卡片设置已存在，请刷新后重试'" else """
                [ -f "${'$'}file" ] || fail '功能卡片设置已移除，请刷新后重试'
                actual=${'$'}(sha256sum < "${'$'}file")
                [ "${'$'}{actual%% *}" = '$expected' ] || fail '功能卡片设置已改变，请刷新后重试'
            """.trimIndent()}
            # Never chmod the old document: atomic replacement must leave hardlink aliases intact.
            [ ! -L "${'$'}temporary" ] && [ -f "${'$'}temporary" ] && [ "${'$'}(stat -c %h -- "${'$'}temporary")" = 1 ] || fail '功能卡片设置暂存文件无效'
            chmod ${if (shared) "660" else "600"} -- "${'$'}temporary"
            mv -fT -- "${'$'}temporary" "${'$'}file"
        """.trimIndent(), stdinBytes = next)
    }

    private fun textBytes(text: String): ByteArray {
        require('\u0000' !in text) { "功能卡片设置不能包含 NUL" }
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
        require(encoded.remaining() <= MAX_BYTES) { "功能卡片设置超过 2 MiB" }
        return ByteArray(encoded.remaining()).also { encoded.get(it) }
    }

    private companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
        val guard = """
            set -eu
            fail() { printf '%s\n' "${'$'}*" >&2; exit 1; }
            [ -n "${'$'}{HOME:-}" ] && [ "${'$'}{HOME#/}" != "${'$'}HOME" ] || fail '容器 HOME 无效'
            home=${'$'}(readlink -f -- "${'$'}HOME")
            dir="${'$'}home/.wawlink"
            file="${'$'}dir/feature-cards.json"
            [ ! -L "${'$'}dir" ] || fail '功能卡片设置目录不能是符号链接'
            checked_directory() {
                [ ! -L "${'$'}dir" ] && [ -d "${'$'}dir" ] || fail '功能卡片设置路径不是目录'
            }
            checked_file() {
                [ ! -L "${'$'}file" ] || fail '功能卡片设置文件不能是符号链接'
                if [ -e "${'$'}file" ]; then
                    [ -f "${'$'}file" ] || fail '功能卡片设置不是普通文件'
                    [ "${'$'}(stat -c %s -- "${'$'}file")" -le $MAX_BYTES ] || fail '功能卡片设置超过 2 MiB'
                fi
            }

        """.trimIndent() + "\n"
    }
}
