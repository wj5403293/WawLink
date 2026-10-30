package dev.wawlink.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Resolves homes within the guest without reading a passwd file outside its rootfs. */
internal fun sanitizedExportHomes(config: ContainerConfig): Set<String> {
    val root = File(config.rootfsPath).toPath().toRealPath()
    val passwd = root.resolve("etc/passwd").toRealPath()
    require(passwd.startsWith(root) && Files.isRegularFile(passwd, NOFOLLOW_LINKS)) { "用户数据库路径越界或不是普通文件" }

    val homes = linkedSetOf<String>()
    fun addHome(home: String) {
        require(home.startsWith('/') && !home.startsWith("//") && '\u0000' !in home) { "用户主目录必须是容器内绝对路径" }
        val parts = home.split('/').filter { it.isNotEmpty() }
        require(parts.none { it == "." || it == ".." }) { "用户主目录路径无效" }
        val relative = parts.joinToString("/")
        val actual = root.resolve(relative).toFile().canonicalFile.toPath()
        require(actual.startsWith(root)) { "用户主目录路径越界：$home" }
        homes += relative
        // The archive walker does not follow home symlinks, so also filter their in-root targets.
        homes += root.relativize(actual).toString().replace(File.separatorChar, '/')
    }

    addHome("/root")
    addHome(containerHome(config))
    passwd.toFile().useLines { lines ->
        lines.forEach { line ->
            val fields = line.split(':', limit = 7)
            if (fields.size == 7 && fields[2].toLongOrNull()?.let { it >= 0 } == true && fields[5].startsWith('/')) {
                addHome(fields[5])
            }
        }
    }
    return homes
}

/** Prepared once per export; matching is confined to complete path components. */
internal class RootfsSanitizer(userHomes: Set<String>, sanitization: RootfsExportSanitization) {
    private val targets = mutableListOf<String>()
    private val profileRoots = mutableListOf<String>()
    private val profileTargets = mutableListOf<String>()
    private val systemCacheRoots = mutableListOf<String>()

    init {
        if (sanitization.ompConfigAndCredentials) profileTargets += ompConfigFiles
        if (sanitization.ompSessionsAndLogs) profileTargets += ompSessionPaths
        for (home in userHomes) {
            fun underHome(path: String) = if (home.isEmpty()) path else "$home/$path"
            if (sanitization.ompConfigAndCredentials) {
                ompConfigFiles.forEach { targets += underHome(".omp/agent/$it") }
            }
            if (sanitization.ompSessionsAndLogs) {
                ompSessionPaths.forEach { targets += underHome(".omp/agent/$it") }
                targets += underHome(".wawlink/logs")
            }
            if (profileTargets.isNotEmpty()) profileRoots += underHome(".omp/profiles/")
            if (sanitization.shellHistory) shellHistoryPaths.forEach { targets += underHome(it) }
            if (sanitization.sshGitAndCaches) credentialAndCachePaths.forEach { targets += underHome(it) }
        }
        if (sanitization.ompSessionsAndLogs) targets += "tmp/wawlink"
        if (sanitization.sshGitAndCaches) systemCacheRoots += listOf(
            "var/cache/apt/archives", "var/lib/apt/lists", "var/cache/apk", "var/cache/pacman/pkg",
        )
    }

    fun shouldSkip(relativePath: String): Boolean {
        if (targets.any { matchesPath(relativePath, it) }) return true
        if (systemCacheRoots.any { relativePath.length > it.length && matchesPath(relativePath, it) }) return true
        for (prefix in profileRoots) {
            if (!relativePath.startsWith(prefix)) continue
            val profileEnd = relativePath.indexOf('/', prefix.length)
            if (profileEnd <= prefix.length || !relativePath.startsWith("agent/", profileEnd + 1)) continue
            val targetStart = profileEnd + 1 + "agent/".length
            if (profileTargets.any { matchesPath(relativePath, it, targetStart) }) return true
        }
        return false
    }

    private fun matchesPath(path: String, target: String, start: Int = 0): Boolean {
        val end = start + target.length
        return path.regionMatches(start, target, 0, target.length) && (path.length == end || path.getOrNull(end) == '/')
    }

    private companion object {
        val ompConfigFiles = listOf(
            "config.yml", "config.json", "models.yml", "models.json", "auth.json", "credentials.json",
            "oauth.json", "agent.db", "agent.db-wal", "agent.db-shm",
        )
        val ompSessionPaths = listOf("sessions", "logs", "wawlink")
        val shellHistoryPaths = listOf(
            ".bash_history", ".zsh_history", ".ash_history", ".sh_history", ".local/share/fish/fish_history",
            ".python_history", ".node_repl_history", ".lesshst", ".viminfo", ".wget-hsts",
        )
        val credentialAndCachePaths = listOf(
            ".ssh", ".gnupg", ".gitconfig", ".git-credentials", ".netrc", ".npmrc", ".pypirc",
            ".cargo/credentials", ".cargo/credentials.toml", ".config/gh", ".cache", ".npm", ".bun/install/cache",
        )
    }
}
