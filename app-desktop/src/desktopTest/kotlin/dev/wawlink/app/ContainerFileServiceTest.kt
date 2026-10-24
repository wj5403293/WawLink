package dev.wawlink.app

import dev.wawlink.runtime.ContainerConfig
import dev.wawlink.runtime.ContainerRuntime
import dev.wawlink.runtime.ContainerFileService
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlin.test.*
import kotlinx.coroutines.runBlocking

/** Shell-backed contract fixture; never opens the configured rootfs path. Not a container/engine test. */
class ContainerFileServiceTest {
    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("wawlink-files-").toFile()
        var mountInfo: File? = null
        val runtime = Proxy.newProxyInstance(ContainerRuntime::class.java.classLoader, arrayOf(ContainerRuntime::class.java)) { _, method, arguments ->
            check(method.name == "openProcess") { "Unexpected runtime API: ${method.name}" }
            check(arguments[0] == "guest-id")
            check(arguments[2] == "/")
            @Suppress("UNCHECKED_CAST")
            val command = arguments[1] as List<String>
            val actual = mountInfo?.let { table -> command.dropLast(1) + command.last().replace("/proc/self/mountinfo", table.path) } ?: command
            ProcessBuilder(actual).directory(root).start()
        } as ContainerRuntime
        val service = ContainerFileService(runtime, ContainerConfig("guest-id", "fixture", "/must-not-be-read", mounts = listOf(dev.wawlink.runtime.ContainerMount("/host", File(root, "mount").path))))
        fun path(name: String) = ContainerFileService.child(root.path, name)
        override fun close() { root.deleteRecursively() }
    }

    private class RecordingHost : HostFiles {
        val opened = ArrayList<Triple<String, String, ByteArray>>()
        val saved = ArrayList<Triple<String, String, ByteArray>>()
        override suspend fun pickAttachments(): List<dev.wawlink.protocol.AttachmentFile> = error("Unexpected picker")
        override suspend fun pickRootfs(): String? = error("Unexpected rootfs picker")
        override suspend fun exportRootfs(runtime: ContainerRuntime, container: ContainerConfig): Boolean = error("Unexpected rootfs export")
        override suspend fun pickImportFiles(): List<HostImportFile> = error("Unexpected import picker")
        override suspend fun openFile(name: String, mimeType: String, write: suspend (java.io.OutputStream) -> Unit) {
            val output = ByteArrayOutputStream(); write(output)
            opened.add(Triple(name, mimeType, output.toByteArray()))
        }
        override suspend fun saveFile(name: String, mimeType: String, write: suspend (java.io.OutputStream) -> Unit): Boolean {
            val output = ByteArrayOutputStream(); write(output)
            saved.add(Triple(name, mimeType, output.toByteArray()))
            return true
        }
    }

    @Test fun preservesUnusualNamesAndStreamsBytes() = runBlocking {
        Fixture().use { fixture ->
            val names = listOf("with spaces", "quote'\"", "中文", "line\nbreak", "-leading", "\n")
            names.forEach { name -> fixture.service.importFile(fixture.root.path, name, "payload:$name".byteInputStream()) }
            assertEquals(names.toSet(), fixture.service.list(fixture.root.path).entries.map { it.name }.toSet())
            names.forEach { name ->
                val info = fixture.service.stat(fixture.path(name))
                assertTrue(info.regular && info.readable)
                assertEquals("payload:$name".toByteArray().size.toLong(), info.size)
                val output = ByteArrayOutputStream()
                fixture.service.exportFile(fixture.path(name), output)
                assertEquals("payload:$name", output.toString("UTF-8"))
            }
        }
    }

    @Test fun refusesOverwriteAndRollsBackFailedImport() = runBlocking {
        Fixture().use { fixture ->
            fixture.service.importFile(fixture.root.path, "existing", "original".byteInputStream())
            assertFailsWith<IllegalStateException> { fixture.service.importFile(fixture.root.path, "existing", "replacement".byteInputStream()) }
            assertEquals("original", File(fixture.path("existing")).readText())
            val failing = object : InputStream() { override fun read(): Int = throw java.io.IOException("source failed") }
            assertFails { fixture.service.importFile(fixture.root.path, "failed", failing) }
            assertFalse(File(fixture.path("failed")).exists())
            assertEquals(setOf("existing"), fixture.service.list(fixture.root.path).entries.map { it.name }.toSet())
        }
    }

    @Test fun createsRenamesCopiesAndRecursivelyDeletesDirectories() = runBlocking {
        Fixture().use { fixture ->
            val service = fixture.service
            service.create(fixture.root.path, "source", true)
            service.create(fixture.path("source"), "nested", true)
            assertFailsWith<IllegalArgumentException> { service.importFile(fixture.path("source"), "nested/file", "invalid".byteInputStream()) }
            service.importFile(fixture.path("source") + "/nested", "old", "content".byteInputStream())
            service.rename(fixture.path("source") + "/nested/old", "new '\n中文")
            service.create(fixture.root.path, "destination", true)
            service.copy(listOf(fixture.path("source")), fixture.path("destination"))
            assertEquals("content", File(fixture.path("destination") + "/source/nested/new '\n中文").readText())
            service.delete(listOf(fixture.path("source"), fixture.path("source") + "/nested", fixture.path("source")))
            assertFalse(File(fixture.path("source")).exists())
        }
    }

    @Test fun rejectsLinksAndPreflightsDeleteAndCopy() = runBlocking {
        Fixture().use { fixture ->
            val service = fixture.service
            service.create(fixture.root.path, "folder", true)
            service.create(fixture.path("folder"), "child", false)
            service.create(fixture.root.path, "kept", false)
            Files.createSymbolicLink(File(fixture.root, "link").toPath(), File(fixture.root, "folder").toPath())
            assertEquals("link", service.list(fixture.root.path).entries.single { it.name == "link" }.kind)
            assertFailsWith<IllegalStateException> { service.list(fixture.path("link")) }
            assertFailsWith<IllegalStateException> { service.delete(listOf(fixture.path("kept"), fixture.path("link"))) }
            assertTrue(File(fixture.path("kept")).exists())
            Files.createSymbolicLink(File(fixture.path("folder"), "nested-link").toPath(), File(fixture.path("kept")).toPath())
            assertFailsWith<IllegalStateException> { service.delete(listOf(fixture.path("kept"), fixture.path("folder"))) }
            assertTrue(File(fixture.path("kept")).exists())
            assertTrue(File(fixture.path("folder"), "child").exists())
            assertFailsWith<IllegalArgumentException> { service.delete(listOf("/workspace")) }
            assertFailsWith<IllegalArgumentException> { service.copy(listOf(fixture.path("folder")), fixture.path("folder")) }
            service.create(fixture.root.path, "destination", true)
            assertFailsWith<IllegalStateException> { service.copy(listOf(fixture.path("link")), fixture.path("destination")) }
            assertTrue(service.list(fixture.path("destination")).entries.isEmpty())
        }
    }

    @Test fun protectsBindRootsDescendantsAndAncestorsBeforeStartingDelete() = runBlocking<Unit> {
        Fixture().use { fixture ->
            val bound = File(fixture.root, "mount").apply { mkdir() }
            val nested = File(bound, "nested").apply { mkdir() }
            val file = File(nested, "host-data").apply { writeText("must stay") }
            File(fixture.root, "kept").writeText("kept")
            listOf(bound.path, nested.path, file.path, fixture.root.path, "/", "/workspace", "/proc/1", "/sys/kernel", "/dev/null", "/run/user", "/etc", "/etc/resolv.conf").forEach { protected ->
                assertFailsWith<IllegalArgumentException>(protected) { fixture.service.delete(listOf(fixture.path("kept"), protected)) }
                assertEquals("kept", File(fixture.path("kept")).readText())
                assertEquals("must stay", file.readText())
            }
            fixture.service.create(nested.path, "new", false)
            fixture.service.importFile(nested.path, "imported", "explicit import".byteInputStream())
            fixture.service.rename(file.path, "renamed")
            assertEquals("explicit import", File(nested, "imported").readText())
            assertEquals("must stay", File(nested, "renamed").readText())
            assertFailsWith<IllegalArgumentException> { fixture.service.delete(listOf(File(nested, "renamed").path)) }
        }
    }

    @Test fun preflightsSystemMountsWithEscapedNamesAcrossEverySelectedRoot() = runBlocking {
        Fixture().use { fixture ->
            val folder = File(fixture.root, "folder").apply { mkdir() }
            val bound = File(folder, "mounted space\nback\\slash").apply { mkdir() }
            File(folder, "ordinary").writeText("keep")
            File(bound, "host-file").writeText("keep")
            val kept = File(fixture.root, "kept").apply { writeText("keep") }
            fun escaped(value: String) = value.replace("\\", "\\134").replace(" ", "\\040").replace("\t", "\\011").replace("\n", "\\012")
            fixture.mountInfo = File(fixture.root, "mountinfo").apply {
                writeText("1 0 0:1 / / rw - ext4 /dev/root rw\n2 1 0:2 / ${escaped(bound.path)} rw - tmpfs tmpfs rw\n")
            }
            assertFailsWith<IllegalStateException> { fixture.service.delete(listOf(kept.path, folder.path)) }
            assertTrue(kept.exists())
            assertTrue(File(folder, "ordinary").exists())
            assertFailsWith<IllegalStateException> { fixture.service.delete(listOf(File(bound, "host-file").path)) }
            assertEquals("keep", File(bound, "host-file").readText())
        }
    }

    @Test fun preflightsSpecialFilesBeforeDeletingEarlierSafeRoots() = runBlocking {
        Fixture().use { fixture ->
            val folder = File(fixture.root, "folder").apply { mkdir() }
            val kept = File(fixture.root, "kept").apply { writeText("keep") }
            File(folder, "first").writeText("keep")
            assertEquals(0, ProcessBuilder("mkfifo", File(folder, "pipe").path).start().waitFor())
            assertFailsWith<IllegalStateException> { fixture.service.delete(listOf(kept.path, folder.path)) }
            assertTrue(kept.exists())
            assertTrue(File(folder, "first").exists())
        }
    }

    @Test fun recursivelyDeletesHiddenAndUnusualNamesWithoutFollowingLinks() = runBlocking {
        Fixture().use { fixture ->
            val source = File(fixture.root, "tree '\n中文").apply { mkdir() }
            val nested = File(source, ".hidden").apply { mkdir() }
            File(nested, "..dots").writeText("data")
            File(nested, "line\nbreak").writeText("data")
            File(source, "-leading").writeText("data")
            fixture.service.delete(listOf(source.path))
            assertFalse(source.exists())
        }
    }

    @Test fun rejectsNonNormalizedAndNulPathsBeforeMutating() = runBlocking {
        Fixture().use { fixture ->
            val kept = File(fixture.root, "kept").apply { writeText("keep") }
            assertEquals("/workspace/b.apk", ContainerFileService.path("./a/../b.apk", "/workspace"))
            assertEquals("/", ContainerFileService.path("/../../"))
            assertFailsWith<IllegalArgumentException> { ContainerFileService.path("/nul\u0000") }
            assertFailsWith<IllegalArgumentException> { ContainerFileService.child("/workspace", "../outside") }
            assertFailsWith<IllegalArgumentException> { fixture.service.delete(listOf(fixture.root.path + "/./kept")) }
            assertFailsWith<IllegalArgumentException> { fixture.service.delete(listOf(fixture.root.path + "//kept")) }
            assertEquals("keep", kept.readText())
        }
    }

    @Test fun nativeHandoffRejectsMissingLinksAndDirectoryBeforeCallingHost() = runBlocking<Unit> {
        Fixture().use { fixture ->
            val host = RecordingHost()
            val actions = ContainerFileActions(fixture.service, host)
            val directory = File(fixture.root, "directory").apply { mkdir() }
            val file = File(fixture.root, "file").apply { writeText("data") }
            val link = File(fixture.root, "link")
            Files.createSymbolicLink(link.toPath(), file.toPath())
            assertFailsWith<IllegalStateException> { actions.open(directory.path) }
            assertFailsWith<IllegalStateException> { actions.open(link.path) }
            assertFails { actions.open(fixture.path("missing")) }
            assertFailsWith<IllegalStateException> { actions.download(listOf(link.path)) }
            assertTrue(host.opened.isEmpty())
            assertTrue(host.saved.isEmpty())
            assertFails { fixture.service.list(fixture.path("deleted-directory")) }
        }
    }

    @Test fun mimeFilterAndHostBasenameBoundaries() {
        assertEquals("application/vnd.android.package-archive", containerFileMimeType("应用.APK"))
        assertEquals("image/svg+xml", containerFileMimeType("drawing.svg"))
        assertEquals("application/octet-stream", containerFileMimeType("unknown.file-extension"))
        assertEquals("image.png", hostFileName("/guest/../../image.png"))
        assertEquals("image.png", hostFileName("C:\\guest\\image.png"))
        assertEquals("line_break", hostFileName("line\nbreak"))
        assertEquals("document", hostFileName(".."))
        val names = listOf("alpha.txt", " beta.TXT", ".hidden").map { dev.wawlink.runtime.ContainerFile(it, "file", 0, 0, "600", "1", "1", true) }
        assertEquals(listOf(" beta.TXT"), filterContainerFiles(names, "  BETA ").map { it.name })
        assertEquals(names, filterContainerFiles(names, "   "))
    }

    @Test fun exportsDirectoryZipAndRejectsBinaryText() = runBlocking<Unit> {
        Fixture().use { fixture ->
            val service = fixture.service
            service.create(fixture.root.path, "directory", true)
            service.importFile(fixture.path("directory"), "文件\n'", "text".byteInputStream())
            val archive = ByteArrayOutputStream()
            service.exportZip(listOf(fixture.path("directory")), archive)
            ZipInputStream(archive.toByteArray().inputStream()).use { zip ->
                assertEquals("directory/", zip.nextEntry.name)
                zip.closeEntry()
                assertEquals("directory/文件\n'", zip.nextEntry.name)
                assertEquals("text", zip.readBytes().toString(Charsets.UTF_8))
            }
            service.importFile(fixture.root.path, "binary", byteArrayOf(0, 1, 2).inputStream())
            assertFailsWith<IllegalStateException> { service.readText(fixture.path("binary")) }
        }
    }
}
