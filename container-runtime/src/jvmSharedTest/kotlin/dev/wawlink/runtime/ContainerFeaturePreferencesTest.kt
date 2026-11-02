package dev.wawlink.runtime

import java.io.File
import java.lang.reflect.Proxy
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

/** Real shell processes with isolated guest homes; no configured rootfs is inspected. */
class ContainerFeaturePreferencesTest {
    private class Fixture(engine: ContainerEngineType = ContainerEngineType.PROOT) : AutoCloseable {
        val root = Files.createTempDirectory("wawlink-card-preferences-").toFile()
        val home = File(root, "guest home '中文").apply { mkdir() }
        val directory = File(home, ".wawlink")
        val file = File(directory, "feature-cards.json")
        val containers = MutableStateFlow(listOf(ContainerInfo(ContainerConfig("guest", "Guest", root.path, engine = engine))))
        val runtime = Proxy.newProxyInstance(ContainerRuntime::class.java.classLoader, arrayOf(ContainerRuntime::class.java)) { _, method, args ->
            if (method.name == "getContainers") return@newProxyInstance containers
            check(method.name == "openProcess") { "Unexpected runtime API: ${method.name}" }
            check(args[0] == "guest")
            check(args[2] == "/")
            @Suppress("UNCHECKED_CAST")
            val command = args[1] as List<String>
            check(command.sumOf { it.length } < 16_384) { "Document leaked into argv" }
            ProcessBuilder(command).directory(root).apply {
                @Suppress("UNCHECKED_CAST")
                environment().putAll(args[3] as Map<String, String>)
                environment()["HOME"] = home.path
            }.start()
        } as ContainerRuntime
        val service = ContainerFeaturePreferences(runtime)
        fun assertNoStaging() = assertTrue(!directory.exists() || directory.listFiles().orEmpty().none { it.name.startsWith(".feature-cards-") })
        override fun close() { root.deleteRecursively() }
    }

    @Test fun absentWriteReadReopenAndExactReplacement() = runBlocking {
        Fixture().use { f ->
            assertNull(f.service.read("guest"))
            assertFalse(f.directory.exists())
            val original = "{\"order\":[\"中文\"],\"hidden\":[],\"colors\":{}}\n"
            f.service.write("guest", original, null)
            assertEquals(original, f.file.readText())
            assertEquals(original, ContainerFeaturePreferences(f.runtime).read("guest"))
            val next = "{\"order\":[],\"hidden\":[\"demo\"],\"colors\":{\"demo\":\"red\"}}"
            f.service.write("guest", next, original)
            assertEquals(next, f.service.read("guest"))
            f.assertNoStaging()
        }
    }


    @Test fun allEnginesKeepOwnerAccessAndPrivateReplacementWithoutChangingHardlinkAliases() = runBlocking {
        for (engine in listOf(ContainerEngineType.PROOT, ContainerEngineType.PROROOT, ContainerEngineType.CHROOT)) Fixture(engine).use { f ->
            f.directory.mkdir()
            f.file.writeText("old")
            Files.setPosixFilePermissions(f.file.toPath(), PosixFilePermissions.fromString("rw-------"))
            val alias = File(f.home, "private-alias")
            Files.createLink(alias.toPath(), f.file.toPath())
            Files.setPosixFilePermissions(f.directory.toPath(), PosixFilePermissions.fromString("r-x------"))
            f.service.write("guest", "new", "old")
            assertEquals("new", f.service.read("guest"))
            assertEquals("old", alias.readText())
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(alias.toPath()))
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(f.file.toPath()))
            assertTrue(Files.getPosixFilePermissions(f.directory.toPath()).containsAll(PosixFilePermissions.fromString("rwx------")))
            f.assertNoStaging()
        }
    }

    @Test fun absentAndStaleConflictsRetainExternalDocument() = runBlocking {
        Fixture().use { f ->
            f.service.write("guest", "original", null)
            assertFailsWith<IllegalStateException> { f.service.write("guest", "overwrite", null) }
            val snapshot = f.service.read("guest")
            f.file.writeText("external")
            assertFailsWith<IllegalStateException> { f.service.write("guest", "draft", snapshot) }
            assertEquals("external", f.file.readText())
            f.file.delete()
            assertFailsWith<IllegalStateException> { f.service.write("guest", "draft", "external") }
            assertFalse(f.file.exists())
            f.assertNoStaging()
        }
    }

    @Test fun rejectsDirectoryAndFileSymlinksAndSpecialFiles() = runBlocking {
        for (engine in ContainerEngineType.entries) Fixture(engine).use { f ->
            val outside = File(f.root, "outside").apply { mkdir() }
            val kept = File(outside, "feature-cards.json").apply { writeText("kept") }
            Files.createSymbolicLink(f.directory.toPath(), outside.toPath())
            assertFailsWith<IllegalStateException> { f.service.read("guest") }
            assertFailsWith<IllegalStateException> { f.service.write("guest", "draft", "kept") }
            Files.delete(f.directory.toPath())
            f.directory.mkdir()
            Files.createSymbolicLink(f.file.toPath(), kept.toPath())
            assertFailsWith<IllegalStateException> { f.service.read("guest") }
            assertFailsWith<IllegalStateException> { f.service.write("guest", "draft", "kept") }
            assertEquals("kept", kept.readText())
            Files.delete(f.file.toPath())
            f.file.mkdir()
            assertFailsWith<IllegalStateException> { f.service.read("guest") }
            assertFailsWith<IllegalStateException> { f.service.write("guest", "draft", null) }
            f.file.delete()
            assertEquals(0, ProcessBuilder("mkfifo", f.file.path).start().waitFor())
            assertFailsWith<IllegalStateException> { f.service.read("guest") }
            assertFailsWith<IllegalStateException> { f.service.write("guest", "draft", null) }
            f.assertNoStaging()
        }
    }

    @Test fun boundedUtf8StdinAndFailedSaveRetention() = runBlocking {
        Fixture().use { f ->
            val boundary = "é".repeat(1024 * 1024)
            f.service.write("guest", boundary, null)
            assertEquals(boundary, f.service.read("guest"))
            assertFailsWith<IllegalArgumentException> { f.service.write("guest", boundary + "a", boundary) }
            assertFailsWith<IllegalArgumentException> { f.service.write("guest", "a\u0000b", boundary) }
            assertFailsWith<CharacterCodingException> { f.service.write("guest", "\uD800", boundary) }
            assertEquals(boundary, f.file.readText())
            f.service.write("guest", "kept", boundary)
            f.file.writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
            assertFailsWith<CharacterCodingException> { f.service.read("guest") }
            f.file.writeBytes(byteArrayOf(0, 65))
            assertFailsWith<IllegalStateException> { f.service.read("guest") }
            f.file.writeBytes(ByteArray(2 * 1024 * 1024 + 1) { 65 })
            assertFailsWith<IllegalStateException> { f.service.read("guest") }
            f.assertNoStaging()
        }
    }
}
