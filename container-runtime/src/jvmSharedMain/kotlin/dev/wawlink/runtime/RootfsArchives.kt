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
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

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
        var bytes = 0L
        val buffer = ByteArray(128 * 1024)
        TarArchiveInputStream(unpackStream(archive)).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                val target = member(root, entry.name)
                if (target == root) continue
                noLinkParents(root, target.parent)
                Files.createDirectories(target.parent)
                require(!Files.exists(target, NOFOLLOW_LINKS) || (entry.isDirectory && Files.isDirectory(target, NOFOLLOW_LINKS))) {
                    "归档包含重复或冲突路径：${entry.name}"
                }
                when {
                    entry.isDirectory -> {
                        Files.createDirectories(target)
                        directoryModes += target to entry.mode
                    }
                    entry.isSymbolicLink -> {
                        require('\u0000' !in entry.linkName && entry.linkName.isNotEmpty() && (File.separatorChar != '\\' || '\\' !in entry.linkName)) { "非法符号链接：${entry.name}" }
                        symlinks += target to entry.linkName
                    }
                    entry.isLink -> hardlinks += target to member(root, entry.linkName.removePrefix("./"))
                    entry.isFile -> {
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
        directoryModes.asReversed().forEach { (path, mode) ->
            val target = relocated(path)
            if (target != root) setMode(target, mode)
        }
    }

    private val permissionBits = PosixFilePermission.values()

    private fun setMode(path: Path, mode: Int) {
        val permissions = java.util.EnumSet.noneOf(PosixFilePermission::class.java)
        permissionBits.forEachIndexed { index, permission -> if (mode and (1 shl (8 - index)) != 0) permissions.add(permission) }
        Files.setPosixFilePermissions(path, permissions)
    }

    fun export(rootfs: File, archive: File) {
        val root = rootfs.toPath().toRealPath()
        require(!archive.canonicalFile.toPath().startsWith(root)) { "导出文件不能位于容器 rootfs 内（包括符号链接目录）" }
        require(!Files.isSymbolicLink(archive.toPath())) { "导出目标不能是符号链接" }
        val buffer = ByteArray(128 * 1024)
        archive.parentFile?.mkdirs()
        TarArchiveOutputStream(ZstdOutputStream(archive.outputStream(), 6)).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir == root) return FileVisitResult.CONTINUE
                    val name = root.relativize(dir).toString().replace(File.separatorChar, '/')
                    writeEntry(dir, "$name/", attrs)
                    return if (name in runtimeMounts) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    writeEntry(file, root.relativize(file).toString().replace(File.separatorChar, '/'), attrs)
                    return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, failure: java.io.IOException): FileVisitResult {
                    if (root.relativize(file).toString() in runtimeMounts) return FileVisitResult.SKIP_SUBTREE
                    throw failure
                }
                private fun writeEntry(path: Path, name: String, attrs: BasicFileAttributes) {
                    val entry = when {
                        attrs.isSymbolicLink -> TarArchiveEntry(name, org.apache.commons.compress.archivers.tar.TarConstants.LF_SYMLINK).apply {
                            linkName = Files.readSymbolicLink(path).toString()
                        }
                        attrs.isRegularFile || attrs.isDirectory -> TarArchiveEntry(path.toFile(), name)
                        else -> return // Sockets/FIFOs are live process state, not a rootfs image.
                    }
                    if (!attrs.isSymbolicLink) {
                        val permissions = Files.getPosixFilePermissions(path, NOFOLLOW_LINKS)
                        var mode = 0
                        for (index in permissionBits.indices) {
                            if (permissionBits[index] in permissions) mode = mode or (1 shl (8 - index))
                        }
                        entry.mode = mode
                    }
                    entry.userId = 0
                    entry.groupId = 0
                    tar.putArchiveEntry(entry)
                    if (attrs.isRegularFile) Files.newInputStream(path).use { input ->
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            tar.write(buffer, 0, count)
                        }
                    }
                    tar.closeArchiveEntry()
                }
            })
            tar.finish()
        }
    }

    fun deleteTree(file: File) {
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return
        Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult { Files.delete(path); return FileVisitResult.CONTINUE }
            override fun postVisitDirectory(dir: Path, failure: java.io.IOException?): FileVisitResult {
                if (failure != null) throw failure
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
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
