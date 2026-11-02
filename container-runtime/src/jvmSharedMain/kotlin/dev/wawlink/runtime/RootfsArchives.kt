package dev.wawlink.runtime

import com.github.luben.zstd.ZstdInputStream
import com.github.luben.zstd.ZstdOutputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.FileSystemException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.nio.file.attribute.PosixFilePermission
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

internal const val ROOTFS_SIZE_SCAN_MARKER = "# WAWLINK_ROOTFS_SIZE_SCAN"

/** Extraction never follows an archive-created link while writing. Absolute guest links are
 * rebased to relative links, so inspecting the extracted rootfs on the host is safe as well. */
internal object RootfsArchives {
    // PRoot/QEMU exposes its host view through a mode-000 transient mountpoint.
    private val runtimeMounts = setOf("proc", "sys", "dev", "host-rootfs")

    private fun member(root: Path, name: String): Path {
        require('\u0000' !in name && !name.startsWith('/') && (File.separatorChar != '\\' || '\\' !in name)) { "归档包含非法绝对路径：$name" }
        val path = root.resolve(name).normalize()
        require(path.startsWith(root)) { "归档路径越界：$name" }
        return path
    }

    private fun noLinkParents(root: Path, path: Path) {
        var current = root
        for (part in root.relativize(path)) {
            current = current.resolve(part)
            require(!Files.isSymbolicLink(current)) { "归档尝试通过符号链接写入：$current" }
        }
    }

    private fun unpackStream(file: File): InputStream {
        val input = BufferedInputStream(file.inputStream())
        input.mark(8)
        val magic = ByteArray(6)
        val count = input.read(magic)
        input.reset()
        return try {
            when {
                count >= 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte() -> GzipCompressorInputStream(input)
                count >= 6 && magic.contentEquals(byteArrayOf(0xfd.toByte(), 0x37, 0x7a, 0x58, 0x5a, 0)) -> XZCompressorInputStream(input)
                count >= 4 && magic.take(4) == listOf(0x28.toByte(), 0xb5.toByte(), 0x2f.toByte(), 0xfd.toByte()) -> ZstdInputStream(input)
                else -> error("不支持的归档格式，请选择 tar.gz、tar.xz 或 tar.zst")
            }
        } catch (failure: Throwable) { input.close(); throw failure }
    }

    fun extract(
        archive: File,
        destination: File,
        createHardLink: (Path, Path) -> Path = Files::createLink,
        progress: (Long) -> Unit = {},
    ) {
        val requestedRoot = destination.toPath().toAbsolutePath().normalize()
        Files.createDirectories(requestedRoot)
        require(!Files.isSymbolicLink(requestedRoot)) { "解压目录不能是符号链接" }
        // Android app-data paths can have aliased parents; compare all links against
        // the same real root without permitting the destination itself to be a link.
        val root = requestedRoot.toRealPath()
        val hardlinks = mutableListOf<Pair<Path, Path>>()
        val symlinks = mutableListOf<Pair<Path, String>>()
        val directoryModes = mutableListOf<Pair<Path, Int>>()
        val owners = linkedMapOf<Path, LogicalOwner>()
        var bytes = 0L
        val buffer = ByteArray(128 * 1024)
        TarArchiveInputStream(unpackStream(archive)).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                val target = member(root, entry.name)
                require(tar.canReadEntryData(entry)) { "归档包含不支持的文件格式：${entry.name}" }
                val owner = LogicalOwner(entry.longUserId, entry.longGroupId, entry.mode and 0xFFF)
                require(owner.uid >= 0 && owner.gid >= 0) { "归档包含非法 UID/GID：${entry.name}" }
                if (target == root) {
                    require(entry.isDirectory) { "归档根路径不是目录" }
                    owners[root] = owner
                    directoryModes += root to owner.mode
                    continue
                }
                noLinkParents(root, target.parent)
                Files.createDirectories(target.parent)
                require(!Files.exists(target, NOFOLLOW_LINKS) || (entry.isDirectory && Files.isDirectory(target, NOFOLLOW_LINKS))) {
                    "归档包含重复或冲突路径：${entry.name}"
                }
                when {
                    entry.isDirectory -> {
                        Files.createDirectories(target)
                        directoryModes += target to entry.mode
                        owners[target] = owner
                    }
                    entry.isSymbolicLink -> {
                        require('\u0000' !in entry.linkName && entry.linkName.isNotEmpty() && (File.separatorChar != '\\' || '\\' !in entry.linkName)) { "非法符号链接：${entry.name}" }
                        symlinks += target to entry.linkName
                        owners[target] = owner
                    }
                    entry.isLink -> hardlinks += target to member(root, entry.linkName.removePrefix("./"))
                    entry.isFile && !entry.isFIFO && !entry.isCharacterDevice && !entry.isBlockDevice -> {
                        Files.newOutputStream(target).use { output ->
                            while (true) {
                                val read = tar.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                bytes += read
                                progress(bytes)
                            }
                        }
                        setMode(target, entry.mode)
                        if (entry.lastModifiedDate.time > 0) target.toFile().setLastModified(entry.lastModifiedDate.time)
                        owners[target] = owner
                    }
                    // /dev is provided by the engine; never create privileged device nodes on the host.
                    (entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO) && root.relativize(target).firstOrNull()?.toString() == "dev" -> Unit
                    else -> error("归档含不支持的特殊文件：${entry.name}")
                }
            }
        }
        var hardlinkFailure: Exception? = null
        while (hardlinks.isNotEmpty()) {
            var resolved = false
            val iterator = hardlinks.iterator()
            while (iterator.hasNext()) {
                val (target, linked) = iterator.next()
                noLinkParents(root, target.parent)
                noLinkParents(root, linked)
                if (!Files.exists(linked, NOFOLLOW_LINKS)) continue
                require(Files.isRegularFile(linked, NOFOLLOW_LINKS)) { "硬链接目标不是普通文件：$linked" }
                require(!Files.exists(target, NOFOLLOW_LINKS)) { "归档包含重复硬链接：$target" }
                if (hardlinkFailure == null) {
                    try { createHardLink(target, linked) }
                    catch (failure: FileSystemException) { hardlinkFailure = failure }
                    catch (failure: UnsupportedOperationException) { hardlinkFailure = failure }
                }
                // App-private Android storage can reject link(2); materialize real files,
                // not symlinks, and avoid repeating the rejected syscall for this import.
                hardlinkFailure?.let { linkFailure ->
                    try { Files.copy(linked, target, NOFOLLOW_LINKS, COPY_ATTRIBUTES) }
                    catch (copyFailure: Exception) { copyFailure.addSuppressed(linkFailure); throw copyFailure }
                }
                val owner = requireNotNull(owners[linked]) { "硬链接缺少目标元数据：$linked" }
                owners[target] = owner
                if (hardlinkFailure != null) setMode(target, owner.mode)
                iterator.remove()
                resolved = true
            }
            require(resolved) { "归档包含循环或缺失目标的硬链接" }
        }
        // Flatten the distributor wrapper BEFORE rebasing guest-absolute symlinks.
        var wrapperRoot: Path? = null
        val extracted = root.toFile()
        if (!File(extracted, "etc").isDirectory) {
            val children = extracted.listFiles().orEmpty()
            if (children.size == 1 && children[0].isDirectory && File(children[0], "etc").isDirectory) {
                val wrapper = children[0]
                wrapperRoot = wrapper.toPath().toAbsolutePath().normalize()
                wrapper.listFiles().orEmpty().forEach { Files.move(it.toPath(), root.resolve(it.name)) }
                Files.delete(wrapper.toPath())
            }
        }
        fun relocated(path: Path): Path = wrapperRoot?.let { wrapper ->
            require(path.startsWith(wrapper)) { "归档链接位于 rootfs 包装目录之外" }
            root.resolve(wrapper.relativize(path))
        } ?: path
        symlinks.forEach { (original, linkName) ->
            val target = relocated(original)
            val linked = if (linkName.startsWith('/')) root.resolve(linkName.trimStart('/'))
            else target.parent.resolve(linkName)
            require(linked.normalize().startsWith(root)) { "符号链接逃逸 rootfs：$original" }
            noLinkParents(root, target.parent)
            require(!Files.exists(target, NOFOLLOW_LINKS)) { "归档包含冲突符号链接：$original" }
            val relative = if (linkName.startsWith('/')) target.parent.relativize(root).resolve(linkName.trimStart('/')) else java.nio.file.Paths.get(linkName)
            Files.createSymbolicLink(target, relative)
        }
        symlinks.forEach { (original, _) ->
            require(relocated(original).toFile().canonicalFile.toPath().startsWith(root)) { "符号链接链逃逸 rootfs：$original" }
        }
        directoryModes.asReversed().distinctBy { it.first }.forEach { (path, mode) ->
            if (wrapperRoot != null && path == root) return@forEach
            val target = relocated(path)
            setMode(target, mode)
        }
        val entries = owners.entries.filterNot { wrapperRoot != null && it.key == root }.associate { (path, owner) ->
            val target = relocated(path)
            val name = root.relativize(target).toString().replace(File.separatorChar, '/')
            (if (name.isEmpty()) "/" else "/$name") to owner
        }
        RootfsOwnership.save(destination, OwnershipManifest(entries))
    }

    private fun setMode(path: Path, mode: Int) {
        Files.setAttribute(path, "unix:mode", mode and 0xFFF, NOFOLLOW_LINKS)
    }

    private fun outputTar(archive: File): TarArchiveOutputStream {
        require(!Files.isSymbolicLink(archive.toPath())) { "导出目标不能是符号链接" }
        archive.parentFile?.mkdirs()
        return TarArchiveOutputStream(ZstdOutputStream(archive.outputStream(), 6)).apply {
            setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
        }
    }

    private fun omitted(name: String, exclude: (String) -> Boolean): Boolean {
        if (name.substringBefore('/') in runtimeMounts) return true
        var end = name.indexOf('/')
        while (end >= 0) {
            if (exclude(name.substring(0, end))) return true
            end = name.indexOf('/', end + 1)
        }
        return exclude(name)
    }

    private fun TarArchiveEntry.ownership(owner: LogicalOwner) {
        setUserId(owner.uid)
        setGroupId(owner.gid)
        userName = ""
        groupName = ""
        mode = owner.mode and 0xFFF
    }

    /** Guest ownership comes from the sidecar; physical modes remain authoritative after guest chmod. */
    fun export(
        rootfs: File, archive: File,
        configuredUser: String = "root", configuredHome: String? = null,
        exclude: (String) -> Boolean = { false },
    ) {
        val root = rootfs.toPath().toRealPath()
        require(!archive.canonicalFile.toPath().startsWith(root)) { "导出文件不能位于容器 rootfs 内（包括符号链接目录）" }
        val manifest = RootfsOwnership.load(rootfs)
        val passwd = root.resolve("etc/passwd")
        val rules = RootfsOwnershipRules(if (runCatching {
            noLinkParents(root, passwd)
            Files.isRegularFile(passwd, NOFOLLOW_LINKS)
        }.getOrDefault(false)) passwd.toFile().readText() else "", configuredUser, configuredHome)
        val hardlinks = mutableMapOf<Any, Pair<String, LogicalOwner>>()
        val buffer = ByteArray(128 * 1024)
        outputTar(archive).use { tar ->
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = root.relativize(dir).toString().replace(File.separatorChar, '/')
                    if (dir != root && omitted(name, exclude)) return FileVisitResult.SKIP_SUBTREE
                    writeEntry(dir, if (dir == root) "./" else "$name/", attrs)
                    return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = root.relativize(file).toString().replace(File.separatorChar, '/')
                    if (!omitted(name, exclude)) writeEntry(file, name, attrs)
                    return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, failure: java.io.IOException): FileVisitResult {
                    val name = root.relativize(file).toString().replace(File.separatorChar, '/')
                    if (omitted(name, exclude)) return FileVisitResult.SKIP_SUBTREE
                    throw failure
                }
                private fun writeEntry(path: Path, name: String, attrs: BasicFileAttributes) {
                    if (!attrs.isSymbolicLink && !attrs.isRegularFile && !attrs.isDirectory) return
                    val guestPath = if (path == root) "/" else "/${name.trimEnd('/')}"
                    val actual = Files.readAttributes(path, "unix:uid,gid,mode", NOFOLLOW_LINKS)
                    val mode = (actual.getValue("mode") as Number).toInt() and 0xFFF
                    val uid = (actual.getValue("uid") as Number).toLong() and 0xFFFFFFFFL
                    val gid = (actual.getValue("gid") as Number).toLong() and 0xFFFFFFFFL
                    val recorded = manifest?.entries?.get(guestPath)
                    var owner = (recorded ?: if (uid != ChrootHostIdentity.uid.toLong()) {
                        LogicalOwner(uid, gid, mode)
                    } else rules.infer(guestPath, mode, attrs.isDirectory, attrs.isSymbolicLink)).copy(
                        mode = if (attrs.isSymbolicLink) recorded?.mode ?: mode else mode,
                    )
                    val previous = if (attrs.isRegularFile) attrs.fileKey()?.let(hardlinks::get) else null
                    val entry = when {
                        attrs.isSymbolicLink -> TarArchiveEntry(name, TarConstants.LF_SYMLINK).apply {
                            linkName = Files.readSymbolicLink(path).toString()
                        }
                        previous != null -> TarArchiveEntry(name, TarConstants.LF_LINK).apply {
                            linkName = previous.first
                            owner = previous.second
                        }
                        else -> TarArchiveEntry(name).apply { if (attrs.isRegularFile) size = attrs.size() }
                    }
                    entry.lastModifiedTime = attrs.lastModifiedTime()
                    entry.ownership(owner)
                    tar.putArchiveEntry(entry)
                    if (attrs.isRegularFile && previous == null) {
                        Files.newInputStream(path).use { input ->
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                tar.write(buffer, 0, count)
                            }
                        }
                        attrs.fileKey()?.let { hardlinks[it] = name to owner }
                    }
                    tar.closeArchiveEntry()
                }
            })
            tar.finish()
        }
    }

    /** Transcodes the privileged producer's tar without copying or reading private rootfs paths as App. */
    fun exportFromTar(archive: File, input: InputStream, exclude: (String) -> Boolean = { false }) {
        val root = java.nio.file.Paths.get("/wawlink-archive")
        val entries = linkedMapOf<Path, TarArchiveEntry>()
        val owners = mutableMapOf<Path, LogicalOwner>()
        val deferred = linkedMapOf<Path, Path>()
        val skipped = mutableSetOf<Path>()
        val symlinks = mutableMapOf<Path, String>()
        val buffer = ByteArray(128 * 1024)
        fun name(path: Path): String = root.relativize(path).toString().replace(File.separatorChar, '/')
        outputTar(archive).use { output ->
            TarArchiveInputStream(input).use { tar ->
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    require(tar.canReadEntryData(entry)) { "归档包含不支持的文件格式：${entry.name}" }
                    val path = member(root, entry.name)
                    require(path != root || entry.isDirectory) { "归档根路径不是目录" }
                    val previous = entries.put(path, entry)
                    require(previous == null || previous.isDirectory && entry.isDirectory) { "归档包含重复或冲突路径：${entry.name}" }
                    val normalized = name(path)
                    val owner = LogicalOwner(entry.longUserId, entry.longGroupId, entry.mode and 0xFFF)
                    require(owner.uid >= 0 && owner.gid >= 0) { "归档包含非法 UID/GID：${entry.name}" }
                    if (entry.isSymbolicLink) {
                        val link = entry.linkName
                        require('\u0000' !in link && link.isNotEmpty() && (File.separatorChar != '\\' || '\\' !in link)) { "非法符号链接：${entry.name}" }
                        val target = if (link.startsWith('/')) root.resolve(link.trimStart('/')) else path.parent.resolve(link)
                        require(target.normalize().startsWith(root)) { "符号链接逃逸 rootfs：${entry.name}" }
                        symlinks[path] = link
                    }
                    val linked = if (entry.isLink) member(root, entry.linkName.removePrefix("./")) else null
                    val regular = entry.isFile && !entry.isFIFO && !entry.isCharacterDevice && !entry.isBlockDevice
                    val supported = entry.isDirectory || regular || entry.isSymbolicLink || entry.isLink
                    if (omitted(normalized, exclude) || !supported) {
                        skipped.add(path)
                        continue
                    }
                    if (entry.isLink) {
                        deferred[path] = requireNotNull(linked)
                        continue
                    }
                    val outputName = if (path == root) "./" else normalized
                    val outputEntry = when {
                        entry.isSymbolicLink -> TarArchiveEntry(outputName, TarConstants.LF_SYMLINK).apply { linkName = entry.linkName }
                        entry.isDirectory -> TarArchiveEntry(outputName.trimEnd('/') + "/")
                        else -> TarArchiveEntry(outputName).apply { size = entry.realSize }
                    }
                    outputEntry.lastModifiedTime = entry.lastModifiedTime
                    outputEntry.ownership(owner)
                    owners[path] = owner
                    output.putArchiveEntry(outputEntry)
                    if (regular) {
                        while (true) {
                            val count = tar.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                    output.closeArchiveEntry()
                }
            }
            // Validate the virtual tree too: no member may be nested under an archive-created link/file.
            entries.keys.forEach { path ->
                var parent = path.parent
                while (parent != null && parent.startsWith(root)) {
                    require(entries[parent]?.isDirectory != false) { "归档尝试通过非目录写入：$path" }
                    parent = parent.parent
                }
            }
            symlinks.keys.forEach { path ->
                val pending = java.util.ArrayDeque<String>()
                root.relativize(path).forEach { pending.addLast(it.toString()) }
                var target = root
                var followed = 0
                while (pending.isNotEmpty()) {
                    when (val part = pending.removeFirst()) {
                        "", "." -> Unit
                        ".." -> {
                            require(target != root) { "符号链接链逃逸 rootfs：$path" }
                            target = target.parent
                        }
                        else -> {
                            target = target.resolve(part)
                            val linked = symlinks[target] ?: continue
                            require(++followed <= 40) { "归档包含循环或过深符号链接：$path" }
                            target = if (linked.startsWith('/')) root else target.parent
                            linked.split('/').asReversed().forEach(pending::addFirst)
                        }
                    }
                }
            }
            while (deferred.isNotEmpty()) {
                var resolved = false
                val iterator = deferred.iterator()
                while (iterator.hasNext()) {
                    val (path, linked) = iterator.next()
                    if (linked in skipped || omitted(name(linked), exclude)) {
                        skipped.add(path)
                    } else {
                        val target = entries[linked]
                        require(target == null || target.isFile || target.isLink) { "硬链接目标不是普通文件：$linked" }
                        val owner = owners[linked] ?: continue
                        val entry = TarArchiveEntry(name(path), TarConstants.LF_LINK).apply {
                            linkName = name(linked)
                            lastModifiedTime = entries.getValue(path).lastModifiedTime
                        }
                        entry.ownership(owner)
                        owners[path] = owner
                        output.putArchiveEntry(entry)
                        output.closeArchiveEntry()
                    }
                    iterator.remove()
                    resolved = true
                }
                require(resolved) { "归档包含循环或缺失目标的硬链接" }
            }
            output.finish()
        }
    }

    fun deleteTree(file: File) {
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) {
            RootfsOwnership.deleteManifest(file)
            return
        }
        Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val permissions = Files.getPosixFilePermissions(dir, NOFOLLOW_LINKS)
                Files.setPosixFilePermissions(dir, permissions + setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult { Files.delete(path); return FileVisitResult.CONTINUE }
            override fun postVisitDirectory(dir: Path, failure: java.io.IOException?): FileVisitResult {
                if (failure != null) throw failure
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
        RootfsOwnership.deleteManifest(file)
    }

    /** Count logical bytes per regular-file path, including each hard link but never a symlink. */
    fun privilegedSize(config: ContainerConfig, rootCommand: (String) -> String): Long {
        val excluded = RootfsOwnership.exclusions(config, rootCommand)
        val prune = excluded.joinToString(" -o ") {
            "-path " + shellQuote("." + it.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?").replace("[", "\\["))
        }
        val producerComplete = "WAWLINK_SIZE_PRODUCER_COMPLETE"
        val workersComplete = "WAWLINK_SIZE_WORKERS_COMPLETE"
        // Keep each stat invocation bounded. Android mksh uses 32-bit arithmetic, so only
        // numeric metadata crosses the shell; Kotlin performs checked Long aggregation.
        val worker = """
            set -efu
            complete=0
            for path do
                shift
                if [ "${'$'}path" = '$producerComplete' ]; then complete=1; else set -- "${'$'}@" "${'$'}path"; fi
            done
            # Buffer this batch so a failed stat cannot publish its partial numeric output.
            if [ "${'$'}#" -gt 0 ]; then
                values=${'$'}(stat -c '%f:%s' -- "${'$'}@") || exit
                printf '%s\n' "${'$'}values"
            fi
            if [ "${'$'}complete" -eq 1 ]; then printf '%s\n' '$producerComplete'; fi
        """.trimIndent()
        val output = rootCommand("""
            $ROOTFS_SIZE_SCAN_MARKER
            set -eu
            cd ${shellQuote(File(config.rootfsPath).canonicalPath)}
            worker_shell=${'$'}(command -v sh)
            {
                { find . -xdev \( $prune \) -prune -o -type f -print0 || exit; printf '%s\000' '$producerComplete'; } |
                    xargs -0 -r -n 128 -s 16384 "${'$'}worker_shell" -c ${shellQuote(worker)} size || exit
                printf '%s\n' '$workersComplete'
            }
        """.trimIndent())
        var total = 0L
        var producer = false
        var workers = false
        for (line in output.lineSequence()) {
            when (line) {
                producerComplete -> {
                    require(!producer && !workers) { "Rootfs 体积扫描完成标记无效" }
                    producer = true
                }
                workersComplete -> {
                    require(producer && !workers) { "Rootfs 体积扫描未完整完成" }
                    workers = true
                }
                "" -> require(workers) { "Rootfs 体积扫描包含空元数据" }
                else -> {
                    require(!producer && !workers) { "Rootfs 体积扫描完成后仍有元数据" }
                    val separator = line.indexOf(':')
                    require(separator > 0) { "Rootfs 体积元数据无效" }
                    val mode = line.substring(0, separator).toIntOrNull(16)
                    val size = line.substring(separator + 1).toLongOrNull()
                    require(mode != null && mode >= 0 && size != null && size >= 0) { "Rootfs 体积元数据无效" }
                    if ((mode and 0xF000) == 0x8000) total = Math.addExact(total, size)
                }
            }
        }
        // Neither a failed find nor an earlier failed xargs worker may publish a partial sum.
        require(producer && workers) { "Rootfs 体积扫描协议不完整" }
        return total
    }

    fun size(rootfs: File, excluded: Set<String> = emptySet()): Long {
        if (!rootfs.isDirectory) return 0
        val root = rootfs.toPath()
        val omitted = excluded + runtimeMounts
        var size = 0L
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir != root && root.relativize(dir).toString() in omitted) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile && root.relativize(file).toString() !in omitted) size += attrs.size()
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, failure: java.io.IOException): FileVisitResult {
                if (root.relativize(file).toString() in omitted) return FileVisitResult.SKIP_SUBTREE
                throw failure
            }
        })
        return size
    }
}
