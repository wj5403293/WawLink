package dev.wawlink.runtime

import java.io.File
import java.lang.reflect.Proxy
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.*
import kotlinx.coroutines.runBlocking

/** Guest-command contract fixture. The configured rootfs is deliberately never opened. */
class ContainerFileTextTest {
    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("wawlink-text-").toFile()
        var mountInfo: File? = null
        var processCount = 0
        val runtime = Proxy.newProxyInstance(ContainerRuntime::class.java.classLoader, arrayOf(ContainerRuntime::class.java)) { _, method, arguments ->
            check(method.name == "openProcess") { "Unexpected runtime API: ${method.name}" }
            check(arguments[0] == "text-guest")
            check(arguments[2] == "/")
            processCount++
            @Suppress("UNCHECKED_CAST")
            val command = arguments[1] as List<String>
            val actual = mountInfo?.let { table -> command.dropLast(1) + command.last().replace("/proc/self/mountinfo", table.path) } ?: command
            ProcessBuilder(actual).directory(root).start()
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
}
