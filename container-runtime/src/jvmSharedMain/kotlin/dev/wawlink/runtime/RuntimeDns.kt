package dev.wawlink.runtime

import java.io.File

/** Replace only the final resolver symlink; ancestors, bindings and hard links remain forbidden. */
internal object RuntimeDns {
    fun update(
        root: File, content: String, config: ContainerConfig? = null,
        rootCommand: (String) -> String = ::runHostRootCommand,
    ) {
        val active = config ?: ContainerConfig("dns-offline", "dns-offline", root.canonicalPath)
        val files = RootfsHostFiles(active, rootCommand)
        val file = File(root.canonicalFile, "etc/resolv.conf")
        val previous = files.read(file, replaceSymlink = true)
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (previous != null && previous.contentEquals(bytes)) return
        files.write(file, bytes, RootfsHostFiles.revisionOrNull(previous), defaultMode = 0x1A4,
            preserveOwner = true, replaceSymlink = true)
    }
}
