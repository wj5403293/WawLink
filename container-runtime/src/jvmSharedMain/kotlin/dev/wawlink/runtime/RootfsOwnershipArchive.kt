package dev.wawlink.runtime

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible

/** Root tar opens private guest files once; App streams/re-encodes it without widening permissions. */
internal suspend fun exportOwnedRootfs(
    config: ContainerConfig,
    archive: File,
    rootCommand: (String) -> String,
    exclude: (String) -> Boolean = { false },
    sanitization: RootfsExportSanitization = RootfsExportSanitization(),
    startRootProcess: (String) -> Process = { ProcessBuilder("su", "-c", it).start() },
) {
    RootfsOwnership.requireStable(config)
    val root = File(config.rootfsPath).canonicalFile
    require(!archive.canonicalFile.toPath().startsWith(root.toPath())) { "导出文件不能位于容器 Rootfs 内" }
    val privileged = RootfsOwnership.physical(config) == ContainerEngineType.CHROOT || RootfsAccess.requiresRoot(config)
    if (privileged) RootfsOwnership.preflight(rootCommand)
    val entries = RootfsOwnership.snapshot(config, rootCommand.takeIf { privileged })
    val protected = RootfsOwnership.exclusions(config, rootCommand.takeIf { privileged })
    val sanitizer = if (sanitization.isSanitized) {
        val homes = if (privileged) RootfsOwnership.exportHomes(config, entries, rootCommand) else sanitizedExportHomes(config)
        RootfsSanitizer(homes, sanitization)
    } else null
    fun filtered(path: String): Boolean {
        var current = path
        while (current.isNotEmpty()) {
            if (exclude(current) || sanitizer?.shouldSkip(current) == true) return true
            current = current.substringBeforeLast('/', "")
        }
        return false
    }
    val privateInodes = entries.asSequence().filter { !it.directory && it.links > 1 && filtered(it.path.trimStart('/')) }.map { it.identity }.toHashSet()
    val aliases = entries.asSequence().filter { !it.directory && it.identity in privateInodes }.map { it.path.trimStart('/') }.toHashSet()
    fun skip(path: String): Boolean = path in aliases || protected.any { path == it.trimStart('/') || path.startsWith(it.trimStart('/') + "/") } || filtered(path)
    if (!privileged) {
        RootfsArchives.export(root, archive, config.user, config.environment["HOME"], ::skip)
        return
    }
    val arguments = listOf("tar", "-cf", "-") + protected.map { "--exclude=.$it" } + listOf("-C", root.path, ".")
    val process = startRootProcess("set -eu\ncommand -v tar >/dev/null || { echo 'Root 环境缺少 tar，无法读取容器私有文件' >&2; exit 1; }\nexec " + arguments.joinToString(" ", transform = ::shellQuote))
    fun release() {
        process.destroy()
        if (process.isAlive) process.destroyForcibly()
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.outputStream.close() }
    }
    try {
        coroutineScope {
            val errors = async(Dispatchers.IO) {
                runInterruptible { process.errorStream.bufferedReader().use { reader ->
                    val result = StringBuilder()
                    val buffer = CharArray(4096)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        if (result.length < 65536) result.append(buffer, 0, minOf(count, 65536 - result.length))
                    }
                    result.toString()
                } }
            }
            try {
                val manifest = RootfsOwnership.load(root)
                val logicalOwners = if (manifest?.physical == ContainerEngineType.PROOT) {
                    RootfsOwnership.refresh(config, rootCommand, entries).entries
                } else emptyMap()
                runInterruptible(Dispatchers.IO) { process.inputStream.use { RootfsArchives.exportFromTar(archive, it, logicalOwners, ::skip) } }
                check(runInterruptible(Dispatchers.IO) { process.waitFor(30, TimeUnit.SECONDS) }) { "容器归档进程未按期退出" }
                val error = errors.await()
                check(process.exitValue() == 0) { "容器私有文件归档失败：${error.trim()}" }
            } finally {
                // Close native pipes before coroutineScope waits for its stderr reader to finish.
                release()
            }
        }
    } finally {
        release()
    }
}
