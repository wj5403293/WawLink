package dev.wawlink.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

private val mappedDependencyDirectories = setOf("node_modules", ".git", ".bun", ".cache", ".venv")
/** PRoot changes reported IDs, not the App's kernel rights. Restore owner access, never world access. */
internal fun prepareMappedHostDirectory(config: ContainerConfig, directory: File) {
    val (root, target) = containerHostPath(config, directory)
    var current = root
    for (part in root.relativize(target)) {
        current = current.resolve(part)
        if (!Files.exists(current, NOFOLLOW_LINKS)) {
            try { Files.createDirectory(current) }
            catch (_: java.nio.file.FileAlreadyExistsException) { /* Another host management writer won. */ }
        }
        mappedAccess(current, directory = true)
    }
}

internal fun prepareMappedHostFile(config: ContainerConfig, file: File) {
    val (_, target) = containerHostPath(config, file)
    mappedAccess(target, directory = false)
}

private fun mappedAccess(path: Path, directory: Boolean) {
    val before = Files.readAttributes(path, "unix:dev,ino,uid,mode,nlink", NOFOLLOW_LINKS)
    require(!Files.isSymbolicLink(path) && if (directory) Files.isDirectory(path, NOFOLLOW_LINKS)
        else Files.isRegularFile(path, NOFOLLOW_LINKS)) { "容器管理路径类型无效：$path" }
    if (!directory) require((before.getValue("nlink") as Number).toLong() == 1L) { "容器管理文件不能是硬链接：$path" }
    val owner = (before.getValue("uid") as Number).toInt()
    val mode = (before.getValue("mode") as Number).toInt()
    val required = if (directory) 0x1C0 else 0x180 // owner rwx / rw; preserve all other bits
    if (owner == ChrootHostIdentity.uid && mode and required != required) {
        // NOFOLLOW mode updates open O_RDONLY on the JVM and cannot repair an unreadable directory.
        // This path chmod has identity checks, not an atomic guarantee against concurrent replacement.
        Files.setAttribute(path, "unix:mode", mode or required)
        val after = Files.readAttributes(path, "unix:dev,ino", NOFOLLOW_LINKS)
        require(before["dev"] == after["dev"] && before["ino"] == after["ino"] && !Files.isSymbolicLink(path)) {
            "容器管理路径在权限准备期间发生变化：$path"
        }
    }
    require(Files.isReadable(path) && Files.isWritable(path) && (!directory || Files.isExecutable(path))) {
        "容器管理路径无法由当前 App 读写：$path；实际 UID=$owner，App UID=${ChrootHostIdentity.uid}，请用原 chroot/Root 身份修复所有权"
    }
}

/** Only explicit management data: no recursive HOME, workspace, plugin, or dependency walk. */
internal fun prepareMappedOwnership(config: ContainerConfig, root: File, homeName: String) {
    require(File(homeName).toPath().normalize().toString() !in reservedContainerHomes) { "容器 HOME 不能使用系统目录根" }
    val home = File(root, homeName.trimStart('/'))
    prepareMappedHostDirectory(config, home)
    val excluded = (listOf("/dev", "/proc", "/sys", "/host-rootfs") + config.mounts.map { it.target })
        .map { root.toPath().resolve(it.trimStart('/')).normalize() }
    fun directory(location: File): Boolean {
        if (excluded.any { location.toPath().startsWith(it) }) return false
        val (_, target) = containerHostPath(config, location)
        if (!Files.exists(target, NOFOLLOW_LINKS)) return false
        if ((Files.getAttribute(target, "unix:uid", NOFOLLOW_LINKS) as Number).toInt() != ChrootHostIdentity.uid) {
            return Files.isDirectory(target, NOFOLLOW_LINKS) && Files.isReadable(target) && Files.isWritable(target) && Files.isExecutable(target)
        }
        mappedAccess(target, directory = true)
        return true
    }
    fun files(location: File) {
        if (!directory(location)) return
        Files.newDirectoryStream(location.toPath()).use { entries ->
            for (entry in entries) {
                if (Files.isRegularFile(entry, NOFOLLOW_LINKS) &&
                    (Files.getAttribute(entry, "unix:nlink", NOFOLLOW_LINKS) as Number).toLong() == 1L &&
                    (Files.getAttribute(entry, "unix:uid", NOFOLLOW_LINKS) as Number).toInt() == ChrootHostIdentity.uid) {
                    mappedAccess(entry, directory = false)
                }
            }
        }
    }
    val wawlink = File(home, ".wawlink")
    if (directory(wawlink)) {
        files(wawlink)
        val features = File(wawlink, "features")
        if (!Files.isSymbolicLink(features.toPath()) && Files.isDirectory(features.toPath(), NOFOLLOW_LINKS)) directory(features)
        // Debugger private paths are validated by its optional installer, not required by OMP.
        for (name in listOf("bin", "feature-debug")) {
            val location = File(wawlink, name)
            val target = location.toPath()
            if (!Files.isSymbolicLink(target) && Files.isDirectory(target, NOFOLLOW_LINKS) &&
                (Files.getAttribute(target, "unix:uid", NOFOLLOW_LINKS) as Number).toInt() == ChrootHostIdentity.uid) files(location)
        }
    }
    val omp = File(home, ".omp")
    if (directory(omp)) {
        files(omp)
        val agent = File(omp, "agent")
        if (directory(agent)) {
            files(agent)
            for (name in listOf("wawlink", "sessions", "rules", "prompts", "agents", "advisors")) {
                val location = File(agent, name)
                if (!directory(location)) continue
                Files.walkFileTree(location.toPath(), object : java.nio.file.SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                        if (dir != location.toPath() && dir.fileName.toString() in mappedDependencyDirectories) {
                            return java.nio.file.FileVisitResult.SKIP_SUBTREE
                        }
                        return if (directory(dir.toFile())) java.nio.file.FileVisitResult.CONTINUE
                            else java.nio.file.FileVisitResult.SKIP_SUBTREE
                    }
                    override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                        if (attrs.isRegularFile && (Files.getAttribute(file, "unix:nlink", NOFOLLOW_LINKS) as Number).toLong() == 1L &&
                            (Files.getAttribute(file, "unix:uid", NOFOLLOW_LINKS) as Number).toInt() == ChrootHostIdentity.uid &&
                            excluded.none { file.startsWith(it) }) {
                            containerHostPath(config, file.toFile())
                            mappedAccess(file, directory = false)
                        }
                        return java.nio.file.FileVisitResult.CONTINUE
                    }
                })
            }
            val skills = File(agent, "skills")
            val builtin = File(skills, "wawlink-functions")
            if (!Files.isSymbolicLink(skills.toPath()) && Files.isDirectory(skills.toPath(), NOFOLLOW_LINKS) &&
                directory(skills) && !Files.isSymbolicLink(builtin.toPath()) && Files.isDirectory(builtin.toPath(), NOFOLLOW_LINKS)) files(builtin)
        }
    }
    val workspace = File(root, "workspace")
    if (config.mounts.none { workspace.toPath().startsWith(root.toPath().resolve(it.target.trimStart('/'))) }) {
        prepareMappedHostDirectory(config, workspace)
    }
}
