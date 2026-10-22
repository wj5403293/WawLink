package dev.wawlink.runtime

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/** The runtime validates root first; only the final resolver symlink may be replaced. */
internal object RuntimeDns {
    fun update(root: File, content: String) {
        val rootPath = root.canonicalFile.toPath()
        val etc = File(root, "etc").canonicalFile.toPath()
        require(etc.startsWith(rootPath) && etc != rootPath && Files.isDirectory(etc)) { "DNS 配置目录越界或不存在" }
        val file = etc.resolve("resolv.conf")
        if (Files.isRegularFile(file, NOFOLLOW_LINKS) && file.toFile().readText(Charsets.UTF_8) == content) return
        val temporary = Files.createTempFile(etc, ".resolv.", ".tmp")
        try {
            try {
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-r--r--"))
            } catch (_: UnsupportedOperationException) {
                // Match the existing runtime's support for filesystems without POSIX attributes.
            }
            FileOutputStream(temporary.toFile()).use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
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
