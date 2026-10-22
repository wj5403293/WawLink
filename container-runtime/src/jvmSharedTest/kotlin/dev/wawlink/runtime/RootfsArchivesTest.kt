package dev.wawlink.runtime

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream

class RootfsArchivesTest {
    private fun fixture(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("wawlink-archive-test").toFile()
        try { block(directory) } finally { RootfsArchives.deleteTree(directory) }
    }
    private fun archive(directory: File, entries: List<Pair<TarArchiveEntry, String>>): File {
        val file = File(directory, "image.tar.gz")
        TarArchiveOutputStream(GzipCompressorOutputStream(file.outputStream())).use { tar ->
            entries.forEach { (entry, text) ->
                val bytes = text.toByteArray()
                if (entry.isFile) entry.size = bytes.size.toLong()
                tar.putArchiveEntry(entry)
                if (entry.isFile) tar.write(bytes)
                tar.closeArchiveEntry()
            }
            tar.finish()
        }
        return file
    }
    private fun link(name: String, target: String) = TarArchiveEntry(name, TarConstants.LF_SYMLINK).apply { linkName = target } to ""

    @Test fun rejectsParentTraversalWithoutWritingOutside() = fixture { directory ->
        val image = archive(directory, listOf(TarArchiveEntry("../escaped", true) to "bad"))
        assertFailsWith<IllegalArgumentException> { RootfsArchives.extract(image, File(directory, "root")) }
        assertFalse(File(directory, "escaped").exists())
    }

    @Test fun rejectsAbsoluteArchiveMembers() = fixture { directory ->
        val escaped = File(directory, "escaped")
        val image = archive(directory, listOf(TarArchiveEntry(escaped.absolutePath, true) to "bad"))
        assertFailsWith<IllegalArgumentException> { RootfsArchives.extract(image, File(directory, "root")) }
        assertFalse(escaped.exists())
    }

    @Test fun rejectsSymlinkTraversalAndSymlinkWrites() = fixture { directory ->
        val image = archive(directory, listOf(link("escape", "../../outside")))
        assertFailsWith<IllegalArgumentException> { RootfsArchives.extract(image, File(directory, "root")) }
        val image2 = archive(directory, listOf(link("alias", "safe"), TarArchiveEntry("alias/file") to "bad"))
        assertFailsWith<IllegalArgumentException> { RootfsArchives.extract(image2, File(directory, "root2")) }
    }

    @Test fun rebasesAbsoluteGuestLinksAfterWrapperFlattening() = fixture { directory ->
        val image = archive(directory, listOf(
            TarArchiveEntry("wrapped/etc/") to "",
            TarArchiveEntry("wrapped/usr/bin/sh") to "shell-content",
            link("wrapped/bin", "/usr/bin"),
            link("wrapped/usr/bin/self", "."),
        ))
        val root = File(directory, "root")
        RootfsArchives.extract(image, root)
        assertFalse(File(root, "wrapped").exists())
        assertEquals("shell-content", File(root, "bin/sh").readText())
        assertTrue(File(root, "bin").canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
        assertEquals(File(root, "usr/bin").canonicalPath, File(root, "usr/bin/self").canonicalPath)
    }

    @Test fun refusesExportThroughDirectorySymlinkIntoRootfs() = fixture { directory ->
        val root = File(directory, "root").apply { mkdirs() }
        val alias = File(directory, "alias")
        Files.createSymbolicLink(alias.toPath(), root.toPath())
        assertFailsWith<IllegalArgumentException> { RootfsArchives.export(root, File(alias, "image.tar.zst")) }
        assertFalse(File(root, "image.tar.zst").exists())
    }

    @Test fun sizeExcludesUnreadableRuntimeAndConfiguredBindMounts() = fixture { directory ->
        val root = File(directory, "root").apply { mkdirs() }
        File(root, "workspace.txt").writeText("abc")
        val mounts = listOf(File(root, "host-rootfs"), File(root, "mounted"))
        mounts.forEach { mount ->
            mount.mkdirs()
            File(mount, "not-guest-data").writeText("excluded")
            Files.setPosixFilePermissions(mount.toPath(), emptySet())
        }
        try {
            assertEquals(3L, RootfsArchives.size(root, setOf("mounted")))
        } finally {
            mounts.forEach { Files.setPosixFilePermissions(it.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")) }
        }
    }

    @Test fun persistsDefaultsCloneAndExternalRootfsOwnership() = fixture { directory -> runBlocking {
        val root = File(directory, "external-root").apply { mkdirs() }
        File(root, "etc").mkdirs()
        File(root, "bin").mkdirs()
        // Real bytes; this test exercises metadata/archives and never executes a guest.
        Files.copy(File("/bin/sh").canonicalFile.toPath(), File(root, "bin/sh").toPath())
        val shellPermissions = java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-x---")
        Files.setPosixFilePermissions(File(root, "bin/sh").toPath(), shellPermissions)
        val data = File(directory, "runtime")
        val original = ContainerConfig("first", "测试容器", root.canonicalPath, arch = "amd64", link2symlink = false, environment = mapOf("FOO" to "bar"), autoSleep = false)
        val runtime = LocalContainerRuntime(RuntimePaths(data))
        runtime.register(original)
        val copied = runtime.clone("first", "克隆容器")
        assertEquals(shellPermissions, Files.getPosixFilePermissions(File(copied.rootfsPath, "bin/sh").toPath()))
        assertEquals(original.environment, copied.environment)
        assertFalse(copied.autoSleep)
        assertFalse(copied.isDefault)
        runtime.setDefault(copied.id)
        runtime.close()
        val restored = LocalContainerRuntime(RuntimePaths(data))
        assertEquals(copied.id, restored.containers.value.single { it.config.isDefault }.config.id)
        assertEquals("bar", restored.containers.value.first { it.config.id == "first" }.config.environment["FOO"])
        restored.delete("first")
        assertTrue(File(root, "bin/sh").isFile)
        restored.delete(copied.id)
        assertFalse(File(copied.rootfsPath).exists())
        restored.close()
    } }
}
