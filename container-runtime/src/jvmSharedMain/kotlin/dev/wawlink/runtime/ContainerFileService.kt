package dev.wawlink.runtime

import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Keeps one write bound to its first process admission, including non-cancellable cleanup. */
internal class ContainerFileOperation(val containerId: String, val stopGeneration: Long, var runGeneration: Long?) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ContainerFileOperation>

    fun isCurrent(runtime: ContainerRuntime): Boolean = runtime.containers.value.any {
        it.config.id == containerId && it.stopGeneration == stopGeneration &&
            (runGeneration == null || it.running && it.runGeneration == runGeneration)
    }
}

/** Binds a multi-process guest operation to its first admission, including commit and cleanup. */
suspend fun <T> withContainerFileOperation(
    runtime: ContainerRuntime, containerId: String, expectedStopGeneration: Long? = null,
    runningOnly: Boolean = false, expectedRunGeneration: Long? = null, block: suspend () -> T,
): T {
    val current = runtime.containers.value.firstOrNull { it.config.id == containerId }
        ?: throw CancellationException("容器已移除")
    if (expectedStopGeneration != null && current.stopGeneration != expectedStopGeneration || runningOnly && !current.running ||
        expectedRunGeneration != null && (!current.running || current.runGeneration != expectedRunGeneration))
        throw CancellationException("容器已停止或重新启动")
    val inherited = currentCoroutineContext()[ContainerFileOperation]?.takeIf { it.containerId == containerId }
    val operation = inherited ?: ContainerFileOperation(containerId, current.stopGeneration, current.runGeneration.takeIf { current.running })
    if (!operation.isCurrent(runtime)) throw CancellationException("容器已停止或重新启动")
    return withContext(operation) {
        block().also { if (!operation.isCurrent(runtime)) throw CancellationException("容器已停止或重新启动") }
    }
}

data class ContainerFile(val name: String, val kind: String, val size: Long, val modified: Long, val mode: String, val owner: String, val group: String, val readable: Boolean) {
    val directory get() = kind == "directory"
    val regular get() = kind == "file"
    val supported get() = directory || regular
}
data class ContainerDirectory(val path: String, val entries: List<ContainerFile>)
data class ContainerFileContent(val bytes: ByteArray, val sizeBytes: Long, val truncated: Boolean)

/** Guest-only filesystem access. No rootfs paths, host shell, Python, Node or SSH are involved. */
class ContainerFileService(private val runtime: ContainerRuntime, private val config: ContainerConfig) {
    companion object {
        private const val MAX_TEXT_BYTES = 2 * 1024 * 1024
        private fun textBytes(text: String): ByteArray {
            require(text.length <= MAX_TEXT_BYTES && '\u0000' !in text) { "仅支持不含 NUL、最大 2 MiB 的 UTF-8 文本" }
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text))
            require(encoded.remaining() <= MAX_TEXT_BYTES) { "文件超过 2 MiB，请导出后编辑" }
            return ByteArray(encoded.remaining()).also { encoded.get(it) }
        }
        fun nameError(name: String): String? = when {
            name.isEmpty() || name == "." || name == ".." -> "名称不能为空、. 或 .."
            '/' in name || '\u0000' in name -> "名称不能包含 / 或 NUL"
            else -> null
        }
        fun path(value: String, base: String = "/"): String {
            require('\u0000' !in value) { "路径不能包含 NUL" }
            val segments = ArrayList<String>()
            (if (value.startsWith('/')) value else "$base/$value").split('/').forEach {
                when (it) { "", "." -> Unit; ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex); else -> segments.add(it) }
            }
            return "/" + segments.joinToString("/")
        }
        fun child(parent: String, name: String): String {
            require(nameError(name) == null) { nameError(name).orEmpty() }
            return parent.trimEnd('/') + "/" + name
        }
        private fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
        // Recursive parent checks use quoted strings, never line-based parsing or word splitting.
        private val guards = """
            set -eu
            fail() { printf '%s\n' "${'$'}*" >&2; exit 1; }
            checked_dir() {
                [ "${'$'}1" = / ] || [ -z "${'$'}1" ] && return
                checked_dir "${'$'}{1%/*}"
                [ ! -L "${'$'}1" ] && [ -d "${'$'}1" ] && [ -x "${'$'}1" ] || fail "路径包含链接、非目录或不可访问目录：${'$'}1"
            }
            checked() {
                checked_dir "${'$'}{1%/*}"
                [ ! -L "${'$'}1" ] || fail "不能操作符号链接：${'$'}1"
                [ -f "${'$'}1" ] || [ -d "${'$'}1" ] || fail "仅支持普通文件与目录：${'$'}1"
            }
            absent() { [ ! -e "${'$'}1" ] && [ ! -L "${'$'}1" ] || fail "目标已存在，未覆盖：${'$'}1"; }
            tree() (
                checked "${'$'}1"
                if [ -d "${'$'}1" ]; then
                    [ -r "${'$'}1" ] && [ -x "${'$'}1" ] || fail "目录不可读取：${'$'}1"
                    for entry in "${'$'}1"/* "${'$'}1"/.[!.]* "${'$'}1"/..?*; do
                        [ -e "${'$'}entry" ] || [ -L "${'$'}entry" ] || continue
                        tree "${'$'}entry"
                    done
                fi
            )
            describe() {
                kind=other
                if [ -L "${'$'}1" ]; then kind=link; elif [ -d "${'$'}1" ]; then kind=directory; elif [ -f "${'$'}1" ]; then kind=file; fi
                readable=0
                if [ -r "${'$'}1" ]; then readable=1; fi
                printf '%s\000%s\000' "${'$'}kind" "${'$'}readable"
                stat -c '%s %Y %a %u %g' -- "${'$'}1"
            }
        """.trimIndent()
    }
    private val virtualRoots = listOf("/proc", "/sys", "/dev", "/run")
    // resolv.conf is an implicit desktop PRoot bind, even when absent from config.mounts.
    private val hostBindings = config.mounts.map { path(it.target) } + "/etc/resolv.conf"
    private val boundaries = listOf("/", "/workspace") + virtualRoots + hostBindings

    private fun guardPath(value: String): String = path(value).also { require(it == value) { "路径必须规范化" } }
    private fun mutablePath(value: String) {
        guardPath(value)
        val prefix = "$value/"
        require(boundaries.none { it == value || it.startsWith(prefix) } && virtualRoots.none { value.startsWith("$it/") }) {
            "不能修改根目录、工作区根、挂载根及其父目录、系统虚拟目录：$value"
        }
    }
    private fun deletablePath(value: String) {
        mutablePath(value)
        require(hostBindings.none { value.startsWith("$it/") }) { "不能删除宿主挂载内的文件或目录：$value" }
    }

    private fun operation(): ContainerFileOperation {
        val container = runtime.containers.value.firstOrNull { it.config.id == config.id }
            ?: throw CancellationException("容器已移除")
        return ContainerFileOperation(config.id, container.stopGeneration, container.runGeneration.takeIf { container.running })
    }

    private suspend fun <T> process(script: String, input: (suspend (OutputStream) -> Unit)? = null, read: (InputStream) -> T): T = withContext(Dispatchers.IO) {
        val operation = currentCoroutineContext()[ContainerFileOperation]
        if (operation != null && !operation.isCurrent(runtime)) throw CancellationException("容器已停止或重新启动")
        val arguments = listOf("/bin/sh", "-c", guards + "\n" + script)
        val process = if (operation?.runGeneration != null) runtime.openRunningProcess(config.id, arguments, "/")
            else runtime.openProcess(config.id, arguments, "/")
        try {
            coroutineScope {
                val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { process.destroy() }
                }
                val errors = async { runInterruptible { process.errorStream.use { stream ->
                    val buffer = ByteArray(4096)
                    val text = java.io.ByteArrayOutputStream()
                    while (true) { val count = stream.read(buffer); if (count < 0) break; if (text.size() < 16384) text.write(buffer, 0, minOf(count, 16384 - text.size())) }
                    text.toString("UTF-8")
                } } }
                val writer = async {
                    try { process.outputStream.use { if (input != null) input(it) }; Result.success(Unit) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { Result.failure<Unit>(failure) }
                }
                try {
                    val result = runInterruptible { process.inputStream.use(read) }
                    val written = writer.await()
                    val status = runInterruptible { process.waitFor() }
                    val message = errors.await()
                    check(status == 0) { message.ifBlank { "容器文件操作失败（$status）" } }
                    written.getOrThrow()
                    result
                } finally { cancellation.cancel(); process.destroy() }
            }
        } finally { process.destroy(); process.inputStream.close(); process.errorStream.close(); process.outputStream.close() }
    }
    private suspend fun command(script: String) = process(script) { it.copyTo(DiscardOutput) }

    private suspend fun cleanup(operation: ContainerFileOperation, directory: String, temporary: String) = withContext(NonCancellable) {
        if (operation.runGeneration != null && operation.isCurrent(runtime)) try {
            command("checked_dir ${quote(directory)}; if [ -f ${quote(temporary)} ] && [ ! -L ${quote(temporary)} ]; then rm -- ${quote(temporary)}; fi")
        } catch (failure: Exception) { if (operation.isCurrent(runtime)) throw failure }
    }

    private fun field(input: InputStream, delimiter: Int): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next < 0) { check(bytes.size() == 0) { "文件数据不完整" }; return null }
            if (next == delimiter) return bytes.toString("UTF-8")
            bytes.write(next)
        }
    }
    private fun metadata(name: String, input: InputStream): ContainerFile {
        val kind = checkNotNull(field(input, 0))
        val readable = checkNotNull(field(input, 0)) == "1"
        val values = checkNotNull(field(input, 10)).split(' ')
        check(values.size == 5) { "无法读取文件属性" }
        return ContainerFile(name, kind, values[0].toLong(), values[1].toLong(), values[2], values[3], values[4], readable)
    }

    /** lstat-like metadata; final links are reported, ancestors are never deliberately traversed. */
    suspend fun stat(source: String): ContainerFile {
        guardPath(source)
        val parent = source.substringBeforeLast('/').ifEmpty { "/" }
        return process("checked_dir ${quote(parent)}; describe ${quote(source)}") {
            metadata(source.substringAfterLast('/').ifEmpty { "/" }, it.buffered())
        }
    }

    suspend fun list(directory: String): ContainerDirectory {
        val normalized = path(directory)
        val script = """
            checked_dir ${quote(normalized)}
            [ -r ${quote(normalized)} ] && [ -x ${quote(normalized)} ] || fail '目录不可读取'
            cd ${quote(normalized)}
            for entry in ./* ./.[!.]* ./..?*; do
                [ -e "${'$'}entry" ] || [ -L "${'$'}entry" ] || continue
                printf '%s\000' "${'$'}{entry#./}"
                describe "${'$'}entry"
            done
        """.trimIndent()
        val entries = process(script) { input ->
            val buffered = input.buffered()
            val result = ArrayList<ContainerFile>()
            while (true) {
                val name = field(buffered, 0) ?: break
                result.add(metadata(name, buffered))
            }
            result
        }
        return ContainerDirectory(normalized, entries)
    }
    suspend fun create(directory: String, name: String, folder: Boolean) {
        val target = child(guardPath(directory), name); mutablePath(target)
        command("checked_dir ${quote(directory)}; absent ${quote(target)}; " + if (folder) "mkdir -- ${quote(target)}" else "(set -C; : > ${quote(target)})")
    }
    suspend fun rename(source: String, name: String) {
        mutablePath(source)
        val destination = child(source.substringBeforeLast('/').ifEmpty { "/" }, name); mutablePath(destination)
        command("checked ${quote(source)}; absent ${quote(destination)}; mv -n -- ${quote(source)} ${quote(destination)}; [ ! -e ${quote(source)} ] || fail '重命名未完成'")
    }
    /** Preflight every subtree before the first unlink; never use rm -r or follow links. */
    suspend fun delete(entries: List<String>) {
        require(entries.isNotEmpty())
        entries.forEach(::deletablePath)
        // Selecting an ancestor already includes its descendants. Avoid a predictable second unlink.
        val roots = entries.distinct().filter { candidate -> entries.none { it != candidate && candidate.startsWith("$it/") } }
        val mountChecks = roots.joinToString("\n") { source -> "check_mounts ${quote(source)}" }
        command("""
            check_mounts() {
                [ -r /proc/self/mountinfo ] || fail '无法检查系统挂载，尚未开始删除'
                while IFS=' ' read -r mount_id parent_id device mount_root mount_path rest; do
                    # mountinfo escapes whitespace/backslashes. A sentinel preserves trailing newlines.
                    decoded=${'$'}(printf '%b_' "${'$'}mount_path")
                    decoded=${'$'}{decoded%_}
                    [ "${'$'}decoded" = / ] && continue
                    case "${'$'}1/" in "${'$'}decoded/"*) fail "不能删除挂载及其子目录：${'$'}1" ;; esac
                    case "${'$'}decoded/" in "${'$'}1/"*) fail "目录包含系统挂载：${'$'}1" ;; esac
                done < /proc/self/mountinfo
            }
            delete_tree() (
                checked "${'$'}1"
                parent=${'$'}{1%/*}; [ -n "${'$'}parent" ] || parent=/
                [ -w "${'$'}parent" ] && [ -x "${'$'}parent" ] || fail "父目录不可写：${'$'}parent"
                # Sticky directories may only unlink entries owned by the current guest user.
                if [ -k "${'$'}parent" ] && [ "${'$'}(id -u)" != 0 ]; then
                    uid=${'$'}(id -u)
                    [ "${'$'}(stat -c %u -- "${'$'}parent")" = "${'$'}uid" ] || [ "${'$'}(stat -c %u -- "${'$'}1")" = "${'$'}uid" ] || fail "粘滞目录中的条目不属于当前用户：${'$'}1"
                fi
                if [ -d "${'$'}1" ]; then
                    [ -r "${'$'}1" ] && [ -w "${'$'}1" ] && [ -x "${'$'}1" ] || fail "目录不可读取或写入：${'$'}1"
                    for entry in "${'$'}1"/* "${'$'}1"/.[!.]* "${'$'}1"/..?*; do
                        [ -e "${'$'}entry" ] || [ -L "${'$'}entry" ] || continue
                        delete_tree "${'$'}entry" "${'$'}2"
                    done
                    [ "${'$'}2" = preflight ] || rmdir -- "${'$'}1"
                else
                    [ "${'$'}2" = preflight ] || rm -- "${'$'}1"
                fi
            )
            $mountChecks
            ${roots.joinToString("\n") { "delete_tree ${quote(it)} preflight" }}
            ${roots.joinToString("\n") { "delete_tree ${quote(it)} delete" }}
        """.trimIndent())
    }
    suspend fun copy(entries: List<String>, directory: String) {
        guardPath(directory); require(entries.isNotEmpty())
        val preflight = entries.joinToString("\n") { source ->
            guardPath(source)
            require(source != "/" && directory != source && !directory.startsWith("$source/")) { "不能复制到自身或子目录" }
            val target = child(directory, source.substringAfterLast('/')); mutablePath(target)
            "tree ${quote(source)}; absent ${quote(target)}"
        }
        command("""
            checked_dir ${quote(directory)}
            $preflight
            copy_entry() (
                source=${'$'}1; target=${'$'}2
                checked "${'$'}source"; checked_dir "${'$'}{target%/*}"; absent "${'$'}target"
                if [ -d "${'$'}source" ]; then
                    mkdir -- "${'$'}target"
                    for entry in "${'$'}source"/* "${'$'}source"/.[!.]* "${'$'}source"/..?*; do
                        [ -e "${'$'}entry" ] || [ -L "${'$'}entry" ] || continue
                        copy_entry "${'$'}entry" "${'$'}target/${'$'}{entry##*/}"
                    done
                else
                    (set -C; cat -- "${'$'}source" > "${'$'}target")
                fi
            )
            ${entries.joinToString("\n") { "copy_entry ${quote(it)} ${quote(child(directory, it.substringAfterLast('/')))}" }}
        """.trimIndent())
    }
    suspend fun importFile(directory: String, name: String, input: InputStream) {
        val target = child(guardPath(directory), name); mutablePath(target)
        val temporary = child(directory, ".wawlink-import-${java.util.UUID.randomUUID()}.part")
        val operation = operation()
        withContext(operation) {
            try {
                process("checked_dir ${quote(directory)}; absent ${quote(target)}; (set -C; cat > ${quote(temporary)})",
                    { output -> runInterruptible { input.copyTo(output, 32768) } }) { it.copyTo(DiscardOutput) }
                // Commit only after the host stream completed successfully. mv -n refuses replacement.
                command("checked ${quote(temporary)}; absent ${quote(target)}; mv -n -- ${quote(temporary)} ${quote(target)}; [ ! -e ${quote(temporary)} ] || fail '导入目标已存在，未覆盖'")
            } finally { cleanup(operation, directory, temporary) }
        }
    }
    suspend fun exportFile(source: String, output: OutputStream) {
        guardPath(source)
        process("checked ${quote(source)}; [ -f ${quote(source)} ] || fail '不是普通文件'; cat -- ${quote(source)}") { it.copyTo(output, 32768) }
    }

    /** Reads a bounded prefix in one guest process; never downloads the remaining file or follows links. */
    suspend fun readPrefix(source: String, maxBytes: Int): ContainerFileContent {
        guardPath(source)
        require(maxBytes in 1..8 * 1024 * 1024) { "预览读取范围必须在 1 B 到 8 MiB 之间" }
        val operation = operation()
        return withContext(operation) {
            val result = process("""
                checked ${quote(source)}
                [ -f ${quote(source)} ] && [ -r ${quote(source)} ] || fail '只能预览可读普通文件'
                stat -c '%s' -- ${quote(source)}
                head -c ${maxBytes + 1} -- ${quote(source)}
            """.trimIndent()) { input ->
                val buffered = input.buffered()
                val size = checkNotNull(field(buffered, 10)).toLong()
                check(size >= 0) { "无法读取附件大小" }
                val output = java.io.ByteArrayOutputStream(minOf(size, maxBytes.toLong()).toInt())
                val buffer = ByteArray(minOf(maxBytes + 1, 8192))
                var count = 0
                var truncated = size > maxBytes
                while (count <= maxBytes) {
                    val read = buffered.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - count))
                    if (read < 0) break
                    val keep = minOf(read, maxBytes - count)
                    if (keep > 0) output.write(buffer, 0, keep)
                    count += read
                    if (count > maxBytes) { truncated = true; break }
                }
                ContainerFileContent(output.toByteArray(), maxOf(size, count.toLong()), truncated)
            }
            if (!operation.isCurrent(runtime)) throw CancellationException("容器已停止或重新启动")
            result
        }
    }
    suspend fun readText(source: String): String = withContext(Dispatchers.IO) {
        val bytes = java.io.ByteArrayOutputStream()
        exportFile(source, object : OutputStream() {
            override fun write(value: Int) { check(bytes.size() < MAX_TEXT_BYTES) { "文件超过 2 MiB，请导出后打开" }; bytes.write(value) }
            override fun write(buffer: ByteArray, offset: Int, count: Int) { check(bytes.size() + count <= MAX_TEXT_BYTES) { "文件超过 2 MiB，请导出后打开" }; bytes.write(buffer, offset, count) }
        })
        val content = bytes.toByteArray()
        check(content.none { it == 0.toByte() }) { "二进制文件请导出后打开" }
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(content)).toString()
    }

    /** Atomic guest-side replacement; a stale editor never silently overwrites newer contents. */
    suspend fun writeText(source: String, text: String, expectedText: String): Unit = withContext(Dispatchers.IO) {
        mutablePath(source)
        val bytes = textBytes(text)
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest(textBytes(expectedText))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val directory = source.substringBeforeLast('/').ifEmpty { "/" }
        val temporary = child(directory, ".wawlink-edit-${java.util.UUID.randomUUID()}.part")
        val script = """
            checked ${quote(source)}
            [ -f ${quote(source)} ] && [ -r ${quote(source)} ] && [ -w ${quote(source)} ] || fail '只能保存可读写的普通文件'
            [ -w ${quote(directory)} ] && [ -x ${quote(directory)} ] || fail '父目录不可写，无法安全保存'
            [ -r /proc/self/mountinfo ] || fail '无法检查系统挂载，尚未开始保存'
            while IFS=' ' read -r mount_id parent_id device mount_root mount_path rest; do
                decoded=${'$'}(printf '%b_' "${'$'}mount_path"); decoded=${'$'}{decoded%_}
                [ "${'$'}decoded" != ${quote(source)} ] || fail '不能替换挂载根文件'
            done < /proc/self/mountinfo
            identity=${'$'}(stat -c '%d:%i:%s:%Y:%Z:%a:%u:%g' -- ${quote(source)})
            umask 077
            (set -C; cat > ${quote(temporary)})
            checked ${quote(source)}; checked ${quote(temporary)}
            [ "${'$'}(stat -c %s -- ${quote(temporary)})" = '${bytes.size}' ] || fail '文本写入不完整，原文件未修改'
            [ -f ${quote(source)} ] || fail '文件类型已改变，尚未保存'
            [ "${'$'}(stat -c '%d:%i:%s:%Y:%Z:%a:%u:%g' -- ${quote(source)})" = "${'$'}identity" ] || fail '文件已改变，请保留草稿并重新打开后合并'
            actual=${'$'}(sha256sum < ${quote(source)})
            [ "${'$'}{actual%% *}" = '$expected' ] || fail '文件内容已改变，请保留草稿并重新打开后合并'
            chown --reference=${quote(source)} -- ${quote(temporary)}
            chmod --reference=${quote(source)} -- ${quote(temporary)}
            checked ${quote(source)}; checked_dir ${quote(directory)}
            mv -fT -- ${quote(temporary)} ${quote(source)}
        """.trimIndent()
        val operation = operation()
        withContext(operation) {
            try {
                process(script, { output -> runInterruptible { output.write(bytes) } }) { it.copyTo(DiscardOutput) }
            } finally { cleanup(operation, directory, temporary) }
        }
    }
    suspend fun exportZip(entries: List<String>, output: OutputStream) = withContext(Dispatchers.IO) {
        require(entries.isNotEmpty())
        val plan = ArrayList<Pair<String, String>>()
        suspend fun visit(source: String, relative: String, info: ContainerFile) {
            currentCoroutineContext().ensureActive()
            require(info.supported) { "不能导出链接或特殊文件：$source" }
            require('\\' !in info.name) { "ZIP 不支持含反斜杠的名称，请单独导出原文件：$source" }
            plan.add(source to if (info.directory) "$relative/" else relative)
            if (info.directory) list(source).entries.forEach { visit(child(source, it.name), "$relative/${it.name}", it) }
        }
        val names = HashSet<String>()
        entries.groupBy { guardPath(it); require(it != "/"); it.substringBeforeLast('/').ifEmpty { "/" } }.forEach { (parent, roots) ->
            val files = list(parent).entries.associateBy { it.name }
            roots.forEach { source ->
                val name = source.substringAfterLast('/')
                require(names.add(name)) { "导出来源名称冲突：$name" }
                visit(source, name, files[name] ?: error("来源已不存在：$source"))
            }
        }
        val zip = ZipOutputStream(object : OutputStream() {
            override fun write(value: Int) = output.write(value)
            override fun write(buffer: ByteArray, offset: Int, count: Int) = output.write(buffer, offset, count)
            override fun close() = output.flush()
        })
        zip.use {
            plan.forEach { (source, relative) ->
                it.putNextEntry(ZipEntry(relative))
                if (!relative.endsWith('/')) exportFile(source, it)
                it.closeEntry()
            }
        }
    }
}

private object DiscardOutput : OutputStream() {
    override fun write(value: Int) = Unit
    override fun write(buffer: ByteArray, offset: Int, count: Int) = Unit
}
