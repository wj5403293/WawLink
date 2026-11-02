package dev.wawlink.runtime

import java.io.File

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

    fun update(
        root: File, requiredGroupIds: Set<Int>, config: ContainerConfig? = null,
    ) {
        if (requiredGroupIds.isEmpty()) return
        val active = config ?: ContainerConfig("identity-offline", "identity-offline", root.canonicalPath)
        val files = RootfsHostFiles(active)
        val file = File(root.canonicalFile, "etc/group")
        val previous = files.read(file)
        val existing = previous?.toString(Charsets.UTF_8).orEmpty()
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
        files.write(file, content.toByteArray(Charsets.UTF_8), RootfsHostFiles.revisionOrNull(previous),
            defaultMode = 0x1A4, preserveOwner = true)
    }
}
