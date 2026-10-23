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
        val runtime = Proxy.newProxyInstance(ContainerRuntime::class.java.classLoader, arrayOf(ContainerRuntime::class.java)) { _, method, arguments ->
            check(method.name == "openProcess") { "Unexpected runtime API: ${method.name}" }
            check(arguments[0] == "guest-id")
            check(arguments[2] == "/")
            @Suppress("UNCHECKED_CAST")
            val command = arguments[1] as List<String>
            ProcessBuilder(command).directory(root).start()
        } as ContainerRuntime
        val service = ContainerFileService(runtime, ContainerConfig("guest-id", "fixture", "/must-not-be-read", mounts = listOf(dev.wawlink.runtime.ContainerMount("/host", File(root, "mount").path))))
        fun path(name: String) = ContainerFileService.child(root.path, name)
        override fun close() { root.deleteRecursively() }
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

    @Test fun createsRenamesCopiesAndDeletesOnlyEmptyDirectories() = runBlocking {
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
            service.delete(listOf(fixture.path("source") + "/nested/new '\n中文"))
            service.delete(listOf(fixture.path("source") + "/nested"))
            service.delete(listOf(fixture.path("source")))
            assertFalse(File(fixture.path("source")).exists())
        }
    }

    @Test fun rejectsLinksAndPreflightsDeleteAndCopy() = runBlocking {
        Fixture().use { fixture ->
            val service = fixture.service
            service.create(fixture.root.path, "folder", true)
            service.create(fixture.path("folder"), "child", false)
            service.create(fixture.root.path, "kept", false)
            assertFailsWith<IllegalStateException> { service.delete(listOf(fixture.path("kept"), fixture.path("folder"))) }
            assertTrue(File(fixture.path("kept")).exists())
            Files.createSymbolicLink(File(fixture.root, "link").toPath(), File(fixture.root, "folder").toPath())
            assertEquals("link", service.list(fixture.root.path).entries.single { it.name == "link" }.kind)
            assertFailsWith<IllegalStateException> { service.list(fixture.path("link")) }
            assertFailsWith<IllegalStateException> { service.delete(listOf(fixture.path("link"))) }
            assertFailsWith<IllegalArgumentException> { service.delete(listOf("/workspace")) }
            assertFailsWith<IllegalArgumentException> { service.copy(listOf(fixture.path("folder")), fixture.path("folder")) }
            service.create(fixture.root.path, "destination", true)
            assertFailsWith<IllegalStateException> { service.copy(listOf(fixture.path("link")), fixture.path("destination")) }
            assertTrue(service.list(fixture.path("destination")).entries.isEmpty())
        }
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
