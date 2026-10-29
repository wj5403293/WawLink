package dev.wawlink.runtime

import java.io.File
import java.lang.reflect.Proxy
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

/** Guest-command contract fixture. The configured rootfs is deliberately never opened. */
class ContainerFileTextTest {
    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("wawlink-text-").toFile()
        var mountInfo: File? = null
        var processCount = 0
        val containers = MutableStateFlow(listOf(ContainerInfo(ContainerConfig("text-guest", "fixture", "/must-not-be-read"), running = true, runGeneration = 1)))
        var afterProcess: (() -> Unit)? = null
        val runtime = Proxy.newProxyInstance(ContainerRuntime::class.java.classLoader, arrayOf(ContainerRuntime::class.java)) { _, method, arguments ->
            if (method.name == "getContainers") return@newProxyInstance containers
            check(method.name == "openProcess" || method.name == "openRunningProcess") { "Unexpected runtime API: ${method.name}" }
            check(arguments[0] == "text-guest")
            check(arguments[2] == "/")
            processCount++
            @Suppress("UNCHECKED_CAST")
            val command = arguments[1] as List<String>
            val actual = mountInfo?.let { table -> command.dropLast(1) + command.last().replace("/proc/self/mountinfo", table.path) } ?: command
            val child = ProcessBuilder(actual).directory(root).start()
            val finished = afterProcess ?: return@newProxyInstance child
            afterProcess = null
            object : Process() {
                override fun getInputStream() = child.inputStream
                override fun getOutputStream() = child.outputStream
                override fun getErrorStream() = child.errorStream
                override fun waitFor() = child.waitFor().also { finished() }
                override fun exitValue() = child.exitValue()
                override fun destroy() = child.destroy()
            }
        } as ContainerRuntime
        val service = ContainerFileService(runtime, ContainerConfig("text-guest", "fixture", "/must-not-be-read",
            mounts = listOf(ContainerMount("/host", File(root, "mount").path))))
        fun file(name: String, text: String) = File(root, name).apply { writeText(text) }
        fun noStagingFiles() = assertTrue(root.walkTopDown().none { it.name.startsWith(".wawlink-edit-") })
        override fun close() { root.deleteRecursively() }
    }

    @Test fun savesExactUtf8AndEmptyTextWithoutChangingMode() = runBlocking {
        Fixture().use { fixture ->
            val file = fixture.file("quote'\n中文.kt", "before\r\n")
            val mode = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
            Files.setPosixFilePermissions(file.toPath(), mode)
            val original = fixture.service.readText(file.path)
            val draft = "\uFEFF中文 😀\r\nsecond\n"
            fixture.service.writeText(file.path, draft, original)
            assertContentEquals(draft.toByteArray(Charsets.UTF_8), file.readBytes())
            assertEquals(draft, fixture.service.readText(file.path))
            assertEquals(mode, Files.getPosixFilePermissions(file.toPath()))
            fixture.service.writeText(file.path, "", draft)
            assertEquals(0L, file.length())
            fixture.noStagingFiles()
        }
    }

    @Test fun refusesStaleDraftAndLeavesNewerFileAndNoTemporaryFile() = runBlocking {
        Fixture().use { fixture ->
            val file = fixture.file("edited.txt", "original")
            val snapshot = fixture.service.readText(file.path)
            file.writeText("external replacement")
            assertFailsWith<IllegalStateException> { fixture.service.writeText(file.path, "my draft", snapshot) }
            assertEquals("external replacement", file.readText())
            fixture.noStagingFiles()
            fixture.service.writeText(file.path, "merged", "external replacement")
            assertEquals("merged", file.readText())
        }
    }

    @Test fun enforcesUtf8ByteLimitAndRejectsBinaryAndMalformedContent() = runBlocking {
        Fixture().use { fixture ->
            val file = fixture.file("text", "keep")
            val service = fixture.service
            val limit = 2 * 1024 * 1024
            val boundary = "é".repeat(limit / 2)
            service.writeText(file.path, boundary, "keep")
            assertEquals(limit.toLong(), file.length())
            assertEquals(boundary, service.readText(file.path))
            assertFailsWith<IllegalArgumentException> { service.writeText(file.path, boundary + "a", boundary) }
            assertFailsWith<IllegalArgumentException> { service.writeText(file.path, "a\u0000b", boundary) }
            assertFailsWith<CharacterCodingException> { service.writeText(file.path, "\uD800", boundary) }
            assertEquals(boundary, file.readText())
            file.writeBytes(ByteArray(limit + 1) { 65 })
            assertFailsWith<IllegalStateException> { service.readText(file.path) }
            file.writeBytes(byteArrayOf(0, 65))
            assertFailsWith<IllegalStateException> { service.readText(file.path) }
            file.writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
            assertFailsWith<CharacterCodingException> { service.readText(file.path) }
            fixture.noStagingFiles()
        }
    }

    @Test fun refusesFinalAndAncestorLinksAndNonFiles() = runBlocking {
        Fixture().use { fixture ->
            val file = fixture.file("kept", "keep")
            val link = File(fixture.root, "link")
            Files.createSymbolicLink(link.toPath(), file.toPath())
            assertFailsWith<IllegalStateException> { fixture.service.writeText(link.path, "draft", "keep") }
            assertFailsWith<IllegalStateException> { fixture.service.readText(link.path) }
            val directory = File(fixture.root, "directory").apply { mkdir() }
            File(directory, "child").writeText("child")
            val ancestor = File(fixture.root, "ancestor")
            Files.createSymbolicLink(ancestor.toPath(), directory.toPath())
            assertFailsWith<IllegalStateException> { fixture.service.writeText(File(ancestor, "child").path, "draft", "child") }
            assertFailsWith<IllegalStateException> { fixture.service.writeText(directory.path, "draft", "") }
            assertFailsWith<IllegalStateException> { fixture.service.writeText(File(fixture.root, "missing").path, "draft", "") }
            assertEquals("keep", file.readText())
            assertEquals("child", File(directory, "child").readText())
            fixture.noStagingFiles()
        }
    }

    @Test fun rejectsProtectedAndNonNormalizedSavePathsBeforeOpeningGuest() = runBlocking {
        Fixture().use { fixture ->
            val protected = listOf("/", "/workspace", "/proc/version", "/sys/kernel", "/dev/null", "/run/user", "/etc", "/etc/resolv.conf",
                File(fixture.root, "mount").path, fixture.root.path, File(fixture.root, "./file").path, "/nul\u0000")
            protected.forEach { source ->
                assertFailsWith<IllegalArgumentException>(source) { fixture.service.writeText(source, "draft", "") }
            }
            assertEquals(0, fixture.processCount)
        }
    }

    @Test fun refusesUnconfiguredFileMountRootsFromGuestMountTable() = runBlocking {
        Fixture().use { fixture ->
            val file = fixture.file("mounted space\nback\\slash", "keep")
            fun escaped(value: String) = value.replace("\\", "\\134").replace(" ", "\\040").replace("\t", "\\011").replace("\n", "\\012")
            fixture.mountInfo = fixture.file("mountinfo", "1 0 0:1 / / rw - ext4 /dev/root rw\n2 1 0:2 / ${escaped(file.path)} rw - tmpfs tmpfs rw\n")
            assertFailsWith<IllegalStateException> { fixture.service.writeText(file.path, "draft", "keep") }
            assertEquals("keep", file.readText())
            fixture.noStagingFiles()
        }
    }

    @Test fun stoppingAfterTextCommitSkipsCleanupWithoutReopeningTheContainer() = runBlocking {
        Fixture().use { fixture ->
            val file = fixture.file("saved.txt", "before")
            fixture.afterProcess = {
                fixture.containers.value = fixture.containers.value.map { it.copy(running = false, stopGeneration = it.stopGeneration + 1) }
            }
            fixture.service.writeText(file.path, "saved", "before")
            assertEquals("saved", file.readText())
            assertEquals(1, fixture.processCount)
            assertFalse(fixture.containers.value.single().running)
        }
    }

    @Test fun restartedInstanceCannotCommitOrCleanAnOlderImport() = runBlocking {
        Fixture().use { fixture ->
            fixture.afterProcess = {
                fixture.containers.value = fixture.containers.value.map { it.copy(runGeneration = it.runGeneration + 1, stopGeneration = it.stopGeneration + 1) }
            }
            assertFailsWith<CancellationException> { fixture.service.importFile(fixture.root.path, "imported", "payload".byteInputStream()) }
            assertFalse(File(fixture.root, "imported").exists())
            assertEquals(1, fixture.processCount)
            assertEquals("payload", fixture.root.listFiles()!!.single { it.name.startsWith(".wawlink-import-") }.readText())
        }
    }

    @Test fun stoppedCleanupKeepsTheOriginalImportReadFailure() = runBlocking {
        Fixture().use { fixture ->
            val input = object : java.io.InputStream() {
                override fun read(): Int {
                    fixture.containers.value = fixture.containers.value.map { it.copy(running = false, stopGeneration = it.stopGeneration + 1) }
                    throw java.io.IOException("source interrupted")
                }
            }
            val failure = assertFailsWith<java.io.IOException> { fixture.service.importFile(fixture.root.path, "failed", input) }
            assertEquals("source interrupted", failure.message)
            assertEquals(1, fixture.processCount)
            assertFalse(File(fixture.root, "failed").exists())
        }
    }
}
