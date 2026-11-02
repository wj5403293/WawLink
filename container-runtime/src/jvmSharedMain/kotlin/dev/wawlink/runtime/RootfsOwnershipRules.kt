package dev.wawlink.runtime

internal data class OwnershipAccount(val name: String, val uid: Long, val gid: Long, val home: String)

/** Conservative reconstruction for metadata lost to application-owned extraction. */
internal class RootfsOwnershipRules(
    passwd: String,
    configuredUser: String = "root",
    configuredHome: String? = null,
) {
    val accounts: List<OwnershipAccount>
    private val homes: List<OwnershipAccount>
    private val namedAccounts: Map<String, OwnershipAccount>

    init {
        val parsed = passwd.lineSequence().mapNotNull { line ->
            val fields = line.removeSuffix("\r").split(':')
            if (fields.size != 7 || !accountName.matches(fields[0])) return@mapNotNull null
            val uid = fields[2].toLongOrNull()?.takeIf { it in 0..maxId } ?: return@mapNotNull null
            val gid = fields[3].toLongOrNull()?.takeIf { it in 0..maxId } ?: return@mapNotNull null
            OwnershipAccount(fields[0], uid, gid, fields[5])
        }.distinct().groupBy { it.name }.values.mapNotNull { it.singleOrNull() }.toMutableList()
        if (parsed.none { it.name == "root" }) {
            // Never invent an identity for an unknown configured non-root user.
            parsed += OwnershipAccount("root", 0, 0, "/root")
        }
        val configuredUid = configuredUser.toLongOrNull()
        val configuredAccount = parsed.firstOrNull { it.name == configuredUser }
            ?: parsed.filter { it.uid == configuredUid }.takeIf { matches ->
                matches.map { it.gid }.distinct().size == 1
            }?.firstOrNull()
        accounts = parsed.map { account ->
            val override = configuredHome?.let { safeHome(it, account.uid) }
            if (account == configuredAccount && override != null) account.copy(home = override) else account
        }
        namedAccounts = accounts.associateBy { it.name }
        homes = accounts.mapNotNull { account ->
            safeHome(account.home, account.uid)?.let { account.copy(home = it) }
        }.groupBy { it.home }.values.mapNotNull { candidates ->
            // Aliases for the same identity are harmless; conflicting identities are not.
            candidates.first().takeIf { candidates.all { other -> other.uid == it.uid && other.gid == it.gid } }
        }.sortedByDescending { it.home.length }
    }

    fun infer(path: String, mode: Int, directory: Boolean, symbolicLink: Boolean = false): LogicalOwner {
        val originalMode = mode and 0xfff
        val guestPath = normalizedPath(path) ?: return LogicalOwner(0, 0, originalMode)
        val home = homes.firstOrNull { within(guestPath, it.home) }
        val account = home ?: serviceAccount(guestPath)
        var inferredMode = originalMode
        // lchmod is not portable: callers must never apply target permissions to a link.
        if (!symbolicLink) {
            inferredMode = when {
                directory && (guestPath == "/tmp" || guestPath == "/var/tmp") -> 0x3ff // 01777
                !directory && guestPath in sudoPaths -> 0x9ed // 04755
                home != null -> privateMode(guestPath.removePrefix(home.home).removePrefix("/"), originalMode, directory)
                else -> originalMode
            }
        }
        return LogicalOwner(account?.uid ?: 0, account?.gid ?: 0, inferredMode)
    }

    private fun serviceAccount(path: String): OwnershipAccount? {
        for (prefix in servicePrefixes) {
            if (!path.startsWith(prefix)) continue
            val name = path.substring(prefix.length).substringBefore('/')
            return namedAccounts[name]
        }
        return null
    }

    private fun privateMode(relative: String, mode: Int, directory: Boolean): Int {
        val ssh = within(relative, ".ssh")
        val vnc = within(relative, ".vnc")
        if (directory) return if (ssh || vnc) 0x1c0 else mode // 0700
        val name = relative.substringAfterLast('/')
        if ((ssh && (name in sshSecrets || (name.startsWith("id_") && !name.endsWith(".pub")))) ||
            (vnc && name == "passwd") || relative in homeSecrets
        ) return 0x180 // 0600
        return mode
    }

    private companion object {
        const val maxId = 4294967294L
        val accountName = Regex("[a-zA-Z_][a-zA-Z0-9_.-]*[$]?")
        val servicePrefixes = listOf("/var/lib/", "/var/log/", "/run/")
        val sudoPaths = setOf("/usr/bin/sudo", "/bin/sudo")
        val sshSecrets = setOf("config", "authorized_keys", "authorized_keys2", "known_hosts", "known_hosts2")
        val homeSecrets = setOf(
            ".Xauthority", ".ICEauthority", ".netrc", ".git-credentials", ".npmrc", ".pypirc",
            ".cargo/credentials", ".cargo/credentials.toml",
        )
        val reservedHomes = setOf(
            "/", "/home", "/rootfs", "/usr", "/usr/local", "/var", "/var/lib", "/var/log",
            "/var/cache", "/opt", "/srv", "/mnt", "/media", "/boot", "/nonexistent",
        )
        val unsafeTrees = listOf("/bin", "/sbin", "/lib", "/lib32", "/lib64", "/etc", "/usr", "/proc", "/sys", "/dev", "/run", "/tmp", "/var/tmp")

        fun within(path: String, parent: String): Boolean = path == parent || path.startsWith("$parent/")

        fun normalizedPath(path: String): String? {
            if (!path.startsWith('/') || '\u0000' in path || '\n' in path || '\r' in path || "//" in path) return null
            val normalized = if (path == "/") path else path.trimEnd('/')
            if (normalized.split('/').any { it == "." || it == ".." }) return null
            return normalized
        }

        fun safeHome(path: String, uid: Long): String? {
            val home = normalizedPath(path) ?: return null
            if (home in reservedHomes || unsafeTrees.any { within(home, it) }) return null
            if (within(home, "/root") && uid != 0L) return null
            return home
        }
    }
}
