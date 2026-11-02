package dev.wawlink.runtime

import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Validates the entire overlay before writing. IO failures during commit are not a transaction. */
internal class RootfsOverlay(
    private val source: ContainerConfig,
    private val target: ContainerConfig,
    targetSnapshot: List<RootfsOwnership.Entry>? = null,
    targetHomes: Set<String>? = null,
    targetHome: String? = null,
) {
    private val sourceRoot = File(source.rootfsPath).toPath().toRealPath()
    private val root = File(target.rootfsPath).toPath().toRealPath()
    private var snapshot = targetSnapshot?.associateBy { it.path }
    private val sourceOwners = RootfsOwnership.load(File(source.rootfsPath))?.entries.orEmpty()
    private val retainedIdentity = target.copy(rootfsPath = source.rootfsPath)
    private val homes = sanitizedExportHomes(source, requirePasswd = false) + (targetHomes ?: sanitizedExportHomes(target, requirePasswd = false)) +
        sanitizedExportHomes(retainedIdentity, requirePasswd = false)
    private val sanitizer = RootfsSanitizer(homes, RootfsExportSanitization(true, true, true, true), includeCaches = false)
    private val boundaries = (listOf("/workspace") + target.mounts.map { it.target }).flatMap { name ->
        require(name.startsWith('/') && !name.startsWith("//") && '\u0000' !in name && name.split('/').none { it == "." || it == ".." }) { "受保护目录路径无效：$name" }
        val relative = name.trim('/'); val actual = snapshot?.let { root.resolve(RootfsOwnership.resolveGuest("/$relative", it).trimStart('/')) }
            ?: root.resolve(relative).toFile().canonicalFile.toPath()
        require(actual.startsWith(root)) { "受保护目录路径越界：$name" }
        listOf(relative, root.relativize(actual).toString())
    }.toSet()
    private data class Entry(val source: Path, val destination: Path, val directory: Boolean, val preserveAccess: Boolean)
    private val entries = mutableListOf<Entry>()

    private fun relative(path: Path) = root.relativize(path).toString().replace(File.separatorChar, '/')
    private fun metadata(path: Path) = snapshot?.get(if (path == root) "/" else "/" + relative(path))
    private fun exists(path: Path) = if (snapshot != null) metadata(path) != null else Files.exists(path, NOFOLLOW_LINKS)
    private fun isLink(path: Path) = if (snapshot != null) metadata(path)?.symbolicLink == true else Files.isSymbolicLink(path)
    private fun isDirectory(path: Path) = if (snapshot != null) metadata(path)?.directory == true else Files.isDirectory(path, NOFOLLOW_LINKS)
    private fun protected(name: String) = sanitizer.shouldSkip(name) || boundaries.any { it.isEmpty() || name == it || name.startsWith("$it/") }
    private fun ancestor(name: String) = sanitizer.protectsDescendant(name) || boundaries.any { it.startsWith("$name/") }

    /** Only conventional merged-/usr links may be traversed, never arbitrary local aliases. */
    private fun mergedLink(path: Path): Path? {
        val name = relative(path)
        when (name) { "bin", "sbin", "lib", "lib32", "lib64", "libx32" -> Unit; else -> return null }
        val link = snapshot?.let { Path.of(requireNotNull(metadata(path)?.linkTarget)) } ?: Files.readSymbolicLink(path)
        if (link.isAbsolute) return null
        val resolved = path.parent.resolve(link).normalize()
        if (resolved != root.resolve("usr/$name")) return null
        var parent = root
        for (part in root.relativize(resolved)) {
            parent = parent.resolve(part)
            require(!isLink(parent)) { "合并 usr 目录包含不安全链接：$parent" }
            require(!exists(parent) || isDirectory(parent)) { "合并 usr 目标不是目录：$parent" }
        }
        return resolved
    }

    private fun destination(name: Path, directory: Boolean): Path {
        var current = root
        name.forEachIndexed { index, part ->
            current = current.resolve(part)
            val parent = index < name.nameCount - 1
            if (isLink(current) && (parent || directory)) {
                current = mergedLink(current) ?: error("更新不能通过目标符号链接写入：$current")
            } else if (parent && exists(current) && !isDirectory(current)) {
                // A source directory can replace a non-directory parent, but it must itself be planned.
                require(entries.any { it.destination == current && it.directory }) { "更新目标父路径不是目录：$current" }
            }
        }
        return current
    }

    init {
        require(!Files.isSymbolicLink(File(target.rootfsPath).toPath())) { "更新 rootfs 不能是符号链接" }
        require(containerHome(retainedIdentity) == (targetHome ?: containerHome(target))) { "更新不能改变当前用户的有效 HOME，请保留归档中的原用户主目录或先显式配置 HOME" }
        require(target.shell.startsWith('/') && '\u0000' !in target.shell && target.shell.split('/').none { it == "." || it == ".." }) { "容器 Shell 路径无效" }
        val requestedShell = sourceRoot.resolve(target.shell.trimStart('/'))
        val shellRoot = if (!protected(target.shell.trimStart('/')) && Files.exists(requestedShell, NOFOLLOW_LINKS)) sourceRoot else root
        if (shellRoot == root && snapshot != null) {
            val shell = metadata(root.resolve(RootfsOwnership.resolveGuest(target.shell, requireNotNull(snapshot)).trimStart('/')))
            require(shell?.kind == 0x8000 && shell.mode and 0x49 != 0) { "更新后选定 Shell 不存在或不可执行：${target.shell}" }
        } else {
            val shell = shellRoot.resolve(target.shell.trimStart('/')).toFile().canonicalFile.toPath()
            require(shell.startsWith(shellRoot) && Files.isRegularFile(shell, NOFOLLOW_LINKS) && Files.isExecutable(shell)) { "更新后选定 Shell 不存在或不可执行：${target.shell}" }
        }
        Files.walkFileTree(sourceRoot, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir == sourceRoot) return FileVisitResult.CONTINUE
                val name = sourceRoot.relativize(dir)
                if (protected(name.toString().replace(File.separatorChar, '/'))) return FileVisitResult.SKIP_SUBTREE
                val destination = destination(name, directory = true)
                if (protected(relative(destination))) return FileVisitResult.SKIP_SUBTREE
                entries += Entry(dir, destination, true, ancestor(relative(destination)))
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val name = sourceRoot.relativize(file)
                val logical = name.toString().replace(File.separatorChar, '/')
                if (protected(logical)) return FileVisitResult.CONTINUE
                val destination = destination(name, directory = false)
                val actual = relative(destination)
                if (protected(actual)) return FileVisitResult.CONTINUE
                require(!ancestor(logical) && !ancestor(actual)) { "更新不能替换受保护目录的祖先：$logical" }
                require(attrs.isRegularFile || attrs.isSymbolicLink) { "更新包含不支持的文件：$logical" }
                if (isDirectory(destination)) {
                    val existing = snapshot
                    val nonempty = if (existing != null) {
                        val prefix = "/" + actual + "/"
                        existing.keys.any { it.startsWith(prefix) }
                    } else Files.newDirectoryStream(destination).use { it.iterator().hasNext() }
                    require(!nonempty) { "更新不能删除本地目录内容：$logical" }
                }
                entries += Entry(file, destination, false, false)
                return FileVisitResult.CONTINUE
            }
        })
        // Directory aliases must not cause two archive entries to replace the same inode path.
        val destinations = HashSet<Path>(entries.size)
        require(entries.all { destinations.add(it.destination) }) { "更新归档包含合并 usr 路径冲突" }
    }

    suspend fun apply() {
        // Privileged preflight used metadata only; commit now runs on the stopped App-owned tree.
        snapshot = null
        val original = RootfsOwnership.load(root.toFile()) ?: OwnershipManifest(emptyMap(), origin = "heuristic-v1")
        val owners = LinkedHashMap(original.entries)
        val permissions = mutableMapOf<Path, Int>()
        var changed = false
        var failure: Throwable? = null
        try {
            for (entry in entries) {
                currentCoroutineContext().ensureActive()
                val destination = entry.destination
                require(this.destination(sourceRoot.relativize(entry.source), entry.directory) == destination) { "更新期间目标路径已变更" }
                val sourceName = "/" + sourceRoot.relativize(entry.source).toString().replace(File.separatorChar, '/')
                val sourceMode = (Files.getAttribute(entry.source, "unix:mode", NOFOLLOW_LINKS) as Number).toInt() and 0xFFF
                val sourceOwner = sourceOwners[sourceName]?.copy(mode = sourceMode)
                    ?: RootfsOwnership.managedOwner(source, sourceName, sourceMode, entry.directory)
                val name = "/" + relative(destination)
                if (entry.directory) {
                    val existed = Files.isDirectory(destination, NOFOLLOW_LINKS)
                    if (!existed) {
                        Files.deleteIfExists(destination)
                        Files.createDirectory(destination)
                    }
                    val previous = (Files.getAttribute(destination, "unix:mode", NOFOLLOW_LINKS) as Number).toInt() and 0xFFF
                    permissions[destination] = previous
                    Files.setAttribute(destination, "unix:mode", previous or 0x1C0, NOFOLLOW_LINKS)
                    owners[name] = if (entry.preserveAccess && existed) {
                        (owners[name] ?: RootfsOwnership.managedOwner(target, name, previous, true)).copy(mode = previous)
                    } else sourceOwner
                } else {
                    val temporary = destination.parent.resolve(".wawlink-import-${UUID.randomUUID()}")
                    try {
                        if (Files.isSymbolicLink(entry.source)) {
                            Files.createSymbolicLink(temporary, Files.readSymbolicLink(entry.source))
                            Files.getFileAttributeView(temporary, java.nio.file.attribute.BasicFileAttributeView::class.java, NOFOLLOW_LINKS)
                                .setTimes(Files.getLastModifiedTime(entry.source, NOFOLLOW_LINKS), null, null)
                        } else {
                            Files.copy(entry.source, temporary, StandardCopyOption.COPY_ATTRIBUTES, NOFOLLOW_LINKS)
                            Files.setAttribute(temporary, "unix:mode", sourceMode, NOFOLLOW_LINKS)
                        }
                        if (Files.isDirectory(destination, NOFOLLOW_LINKS)) Files.delete(destination)
                        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                        owners[name] = sourceOwner
                    } finally { Files.deleteIfExists(temporary) }
                }
                changed = true
            }
            for (entry in entries.asReversed()) {
                if (!entry.directory || entry.preserveAccess) continue
                Files.setAttribute(entry.destination, "unix:mode", owners.getValue("/" + relative(entry.destination)).mode, NOFOLLOW_LINKS)
                Files.setLastModifiedTime(entry.destination, Files.getLastModifiedTime(entry.source, NOFOLLOW_LINKS))
                permissions.remove(entry.destination)
            }
        } catch (caught: Throwable) { failure = caught; throw caught }
        finally {
            var cleanupFailure: Throwable? = null
            permissions.forEach { (path, mode) ->
                try { Files.setAttribute(path, "unix:mode", mode, NOFOLLOW_LINKS) }
                catch (caught: Throwable) { if (cleanupFailure == null) cleanupFailure = caught else cleanupFailure!!.addSuppressed(caught) }
            }
            if (changed) try { RootfsOwnership.save(root.toFile(), original.copy(entries = owners)) }
            catch (caught: Throwable) { if (cleanupFailure == null) cleanupFailure = caught else cleanupFailure!!.addSuppressed(caught) }
            cleanupFailure?.let { if (failure != null) failure.addSuppressed(it) else throw it }
        }
    }
}
