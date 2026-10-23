package dev.wawlink.runtime

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/** Love2Droid's supplementary-GID repair: PRoot exposes the host process's real groups. */
internal object RuntimeIdentity {
    fun hostSupplementaryGroupIds(procStatus: String): Set<Int> = procStatus.lineSequence()
        .firstOrNull { it.startsWith("Groups:") }
        ?.substringAfter(':')
        ?.splitToSequence(' ', '\t')
        ?.mapNotNull(String::toIntOrNull)
        ?.filter { it >= 0 }
        ?.toSortedSet()
        .orEmpty()

    fun update(root: File, requiredGroupIds: Set<Int>) {
        if (requiredGroupIds.isEmpty()) return
        val rootPath = root.canonicalFile.toPath()
        val etc = File(root, "etc").canonicalFile.toPath()
        require(etc.startsWith(rootPath) && etc != rootPath && Files.isDirectory(etc)) { "用户组配置目录越界或不存在" }
        val file = etc.resolve("group").toFile().canonicalFile.toPath()
        require(file.startsWith(rootPath) && file != rootPath) { "用户组数据库路径越界" }
        val existing = if (Files.exists(file)) file.toFile().readText(Charsets.UTF_8) else ""
        val entries = existing.lineSequence().map { it.split(':', limit = 4) }.toList()
        val configured = entries.mapNotNull { it.getOrNull(2)?.toIntOrNull() }.toSet()
        val missing = requiredGroupIds.filter { it >= 0 && it !in configured }.sorted()
        if (missing.isEmpty()) return
        val names = entries.mapNotNull { it.firstOrNull() }.toMutableSet()
        val content = buildString {
            append(existing)
            if (isNotEmpty() && last() != '\n') append('\n')
            missing.forEach { gid ->
                val base = "host_gid_$gid"
                var name = base
                var suffix = 1
                while (!names.add(name)) name = "${base}_${suffix++}"
                append("$name:x:$gid:\n")
            }
        }
        val temporary = Files.createTempFile(file.parent, ".group.", ".tmp")
        try {
            try {
                val permissions = if (Files.exists(file)) Files.getPosixFilePermissions(file)
                    else PosixFilePermissions.fromString("rw-r--r--")
                Files.setPosixFilePermissions(temporary, permissions)
            } catch (_: UnsupportedOperationException) {
                // Same non-POSIX filesystem support as RuntimeDns.
            }
            FileOutputStream(temporary.toFile()).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
