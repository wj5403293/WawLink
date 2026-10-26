package dev.wawlink.runtime

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.net.InetSocketAddress
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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
    private fun hardlink(name: String, target: String) = TarArchiveEntry(name, TarConstants.LF_LINK).apply { linkName = target } to ""

    @Test fun concurrentDeploymentUpdatesPreserveOtherRowsAndInsertionOrder() = fixture { directory ->
        val releases = listOf(CountDownLatch(1), CountDownLatch(1))
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        releases.forEachIndexed { index, release ->
            server.createContext("/import$index") { exchange ->
                exchange.use {
                    val body = ByteArray(32 * 1024)
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.write(body, 0, 1024)
                    exchange.responseBody.flush()
                    check(release.await(10, TimeUnit.SECONDS)) { "Deployment was not released" }
                    exchange.responseBody.write(body, 1024, body.size - 1024)
                }
            }
        }
        server.start()
        val runtime = LocalContainerRuntime(RuntimePaths(File(directory, "data")))
        try {
            runBlocking {
                withTimeout(10_000) {
                    val first = async(Dispatchers.IO) { runCatching { runtime.importRootfs("http://127.0.0.1:${server.address.port}/import0", "first") } }
                    runtime.deployments.first { it.size == 1 }
                    val second = async(Dispatchers.IO) { runCatching { runtime.importRootfs("http://127.0.0.1:${server.address.port}/import1", "second") } }
                    val initial = runtime.deployments.first { it.size == 2 }
                    releases[0].countDown()
                    val firstResult = first.await()
                    val updated = runtime.deployments.value
                    releases[1].countDown()
                    val secondResult = second.await()
                    assertTrue(firstResult.isFailure)
                    assertTrue(secondResult.isFailure)
                    assertEquals(initial.map { it.id }, updated.map { it.id })
                    assertTrue(updated[0].error != null)
                    assertEquals(initial[1], updated[1])
                    assertEquals(initial.map { it.id }, runtime.deployments.value.map { it.id })
                }
            }
        } finally {
            releases.forEach { it.countDown() }
            server.stop(0)
            executor.shutdownNow()
            runtime.close()
        }
    }

    @Test fun packageBinLinksResolveThroughAliasedParentsWithAndWithoutWrapper() = fixture { directory ->
        val storage = File(directory, "app-storage").apply { mkdirs() }
        val alias = File(directory, "data-user-0")
        Files.createSymbolicLink(alias.toPath(), storage.toPath())
        for (wrapped in listOf(false, true)) {
            val prefix = if (wrapped) "wrapped/" else ""
            val image = archive(directory, listOf(
                TarArchiveEntry("${prefix}etc/") to "",
                TarArchiveEntry("${prefix}opt/omp/node_modules/@oh-my-pi/pi-mnemopi/src/cli.ts") to "console.log('mnemopi');",
                link("${prefix}opt/omp/node_modules/.bin/mnemopi", "../@oh-my-pi/pi-mnemopi/src/cli.ts"),
                link("${prefix}usr/bin/mnemopi", "/opt/omp/node_modules/.bin/mnemopi"),
            ))
            val root = File(alias, "wawlink/rootfs/$wrapped")
            RootfsArchives.extract(image, root)
            assertTrue(Files.isSymbolicLink(File(root, "opt/omp/node_modules/.bin/mnemopi").toPath()))
            assertEquals("console.log('mnemopi');", File(root, "usr/bin/mnemopi").readText())
            assertEquals(File(root, "opt/omp/node_modules/@oh-my-pi/pi-mnemopi/src/cli.ts").canonicalPath, File(root, "usr/bin/mnemopi").canonicalPath)
            assertFalse(File(root, "wrapped").exists())
        }
    }

    @Test fun rejectsSymlinkDestinationAndChainsThroughExistingExternalLinks() = fixture { directory ->
        val outside = File(directory, "outside").apply { mkdirs() }
        File(outside, "sentinel").writeText("unchanged")
        val destinationLink = File(directory, "linked-root")
        Files.createSymbolicLink(destinationLink.toPath(), outside.toPath())
        val image = archive(directory, listOf(link("mnemopi", "existing/sentinel")))
        assertFailsWith<IllegalArgumentException> { RootfsArchives.extract(image, destinationLink) }
        val root = File(directory, "root").apply { mkdirs() }
        Files.createSymbolicLink(File(root, "existing").toPath(), outside.toPath())
        assertFailsWith<IllegalArgumentException> { RootfsArchives.extract(image, root) }
        assertEquals("unchanged", File(outside, "sentinel").readText())
        assertFalse(File(outside, "mnemopi").exists())
    }

    @Test fun resolvesForwardHardlinkChainsWithoutCopyingWhenSupported() = fixture { directory ->
        val image = archive(directory, listOf(
            hardlink("first", "second"),
            hardlink("second", "original"),
            TarArchiveEntry("original") to "shared-content",
        ))
        val root = File(directory, "root")
        RootfsArchives.extract(image, root)
        assertEquals("shared-content", File(root, "first").readText())
        assertTrue(Files.isSameFile(File(root, "first").toPath(), File(root, "original").toPath()))
        assertTrue(Files.isSameFile(File(root, "second").toPath(), File(root, "original").toPath()))
    }

    @Test fun rejectedHardlinksBecomeRealFilesWithSourcePermissionsAndTimestamp() = fixture { directory ->
        val timestamp = FileTime.fromMillis(1_700_000_000_000L)
        val original = "opt/omp/node_modules/cliui/node_modules/string-width/index.js"
        val alias = "opt/omp/node_modules/wrap-ansi/node_modules/string-width/index.js"
        val image = archive(directory, listOf(
            hardlink("chain.js", alias),
            hardlink(alias, original),
            TarArchiveEntry(original).apply { mode = 0b111101000; modTime = java.util.Date(timestamp.toMillis()) } to "export default 'width';",
        ))
        for (unsupported in listOf(false, true)) {
            val root = File(directory, if (unsupported) "unsupported" else "denied")
            RootfsArchives.extract(image, root, createHardLink = { target, linked ->
                if (unsupported) throw UnsupportedOperationException("Hardlinks unsupported")
                throw FileSystemException(target.toString(), linked.toString(), "Operation not permitted")
            })
            for (name in listOf(alias, "chain.js")) {
                val path = File(root, name).toPath()
                assertFalse(Files.isSymbolicLink(path))
                assertFalse(Files.isSameFile(path, File(root, original).toPath()))
                assertEquals("export default 'width';", path.toFile().readText())
                assertEquals(PosixFilePermissions.fromString("rwxr-x---"), Files.getPosixFilePermissions(path))
                assertEquals(timestamp, Files.getLastModifiedTime(path))
            }
        }
    }

    @Test fun hardlinkCopyNeverOverwritesAnExistingTarget() = fixture { directory ->
        val image = archive(directory, listOf(TarArchiveEntry("original") to "source", hardlink("alias", "original")))
        val root = File(directory, "root")
        val denied = FileSystemException("alias", "original", "Operation not permitted")
        val failure = assertFailsWith<FileAlreadyExistsException> {
            RootfsArchives.extract(image, root, createHardLink = { target, _ ->
                target.toFile().writeText("concurrent-file")
                throw denied
            })
        }
        assertEquals("concurrent-file", File(root, "alias").readText())
        assertEquals("source", File(root, "original").readText())
        assertTrue(failure.suppressed.contains(denied))
    }

    @Test fun rejectsUnsafeAndUnresolvedHardlinkTargets() = fixture { directory ->
        val entries = listOf(
            listOf(hardlink("alias", "../outside")),
            listOf(hardlink("alias", "/outside")),
            listOf(TarArchiveEntry("directory/") to "", hardlink("alias", "directory")),
            listOf(hardlink("a", "b"), hardlink("b", "a")),
            listOf(hardlink("alias", "missing")),
            listOf(TarArchiveEntry("original") to "safe", hardlink("alias", "original"), TarArchiveEntry("alias") to "conflict"),
            listOf(TarArchiveEntry("original") to "safe", link("symbol", "original"), hardlink("alias", "symbol")),
        )
        entries.forEachIndexed { index, members ->
            val image = archive(directory, members)
            assertFailsWith<IllegalArgumentException> { RootfsArchives.extract(image, File(directory, "root$index")) }
        }
        assertFalse(File(directory, "outside").exists())
    }

    @Test fun preservesSystemdEscapesInLinuxFilenamesAndLinkTargets() = fixture { directory ->
        val unit = "usr/lib/systemd/system/system-systemd\\x2dcryptsetup.slice"
        val image = archive(directory, listOf(
            TarArchiveEntry(unit) to "[Unit]\nDescription=Cryptsetup slice\n",
            link("etc/systemd/system/cryptsetup.slice", "/$unit"),
            hardlink("usr/lib/systemd/system/copy.slice", unit),
        ))
        val root = File(directory, "root")
        if (File.separatorChar == '\\') {
            assertFailsWith<IllegalArgumentException> { RootfsArchives.extract(image, root) }
        } else {
            RootfsArchives.extract(image, root)
            assertEquals("[Unit]\nDescription=Cryptsetup slice\n", File(root, unit).readText())
            assertEquals(File(root, unit).readText(), File(root, "etc/systemd/system/cryptsetup.slice").readText())
            assertTrue(Files.isSameFile(File(root, unit).toPath(), File(root, "usr/lib/systemd/system/copy.slice").toPath()))
        }
    }

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
