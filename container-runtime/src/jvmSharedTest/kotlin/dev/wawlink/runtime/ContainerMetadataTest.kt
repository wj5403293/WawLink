package dev.wawlink.runtime

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContainerMetadataTest {
    private fun fixture(block: (File, File, ContainerConfig, ContainerMetadataStore) -> Unit) {
        val directory = Files.createTempDirectory("wawlink-metadata-test").toFile()
        val data = File(directory, "data").apply { mkdirs() }
        val root = File(directory, "root").apply { mkdirs() }
        File(root, "etc").mkdirs()
        File(root, "bin").mkdirs()
        // A valid ELF architecture header is sufficient: these offline tests never execute it.
        File(root, "bin/sh").apply {
            writeBytes(ByteArray(20).apply {
                this[0] = 0x7f; this[1] = 'E'.code.toByte(); this[2] = 'L'.code.toByte(); this[3] = 'F'.code.toByte()
                this[5] = 1; this[18] = 62
            })
            setExecutable(true)
        }
        File(root, "etc/passwd").writeText("root:x:0:0::/root:/bin/sh\nalice:x:1001:1002::/home/alice:/bin/sh\n")
        val config = ContainerConfig("guest", "旧名称", root.path, shell = "/bin/sh", link2symlink = false)
        val store = ContainerMetadataStore(RuntimeEngines(RuntimePaths(data, hostProot = "/missing-engine-must-not-be-launched")))
        try { block(directory, data, config, store) } finally { RootfsArchives.deleteTree(directory) }
    }

    private fun document(config: ContainerConfig, home: String = "root"): File = File(config.rootfsPath, "$home/.wawlink/container.json")
    private fun writeDocument(config: ContainerConfig, content: String, home: String = "root"): File = document(config, home).apply {
        parentFile.mkdirs()
        writeText(content)
    }

    @Test fun roundTripPreservesUnknownFieldsPermissionsAndExactContentRevision() = fixture { _, _, config, store ->
        assertEquals(ContainerMetadataSnapshot(ContainerMetadata(), null), store.read(config))
        assertFalse(File(config.rootfsPath, "root").exists())
        val original = "{ \"title\":\"初始\", \"future\":{\"nested\":[1,true,null]}, \"version\":\"1.0\" }\n"
        val file = writeDocument(config, original)
        val permissions = PosixFilePermissions.fromString("rw-------")
        Files.setPosixFilePermissions(file.toPath(), permissions)
        val before = store.read(config)
        val digest = MessageDigest.getInstance("SHA-256").digest(original.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(digest, before.revision)
        val metadata = ContainerMetadata("便携标题", "第一行\n第二行", "2026.10", "terminal")
        val saved = store.save(config, metadata, before.revision)
        assertEquals(metadata, saved.value)
        assertEquals(saved, store.read(config))
        assertNotEquals(before.revision, saved.revision)
        assertEquals(Json.parseToJsonElement("{\"nested\":[1,true,null]}"), Json.parseToJsonElement(file.readText()).jsonObject["future"])
        assertEquals(permissions, Files.getPosixFilePermissions(file.toPath()))
        assertEquals(listOf("container.json"), file.parentFile.listFiles().orEmpty().map { it.name })
        assertFalse(Json.parseToJsonElement(Json.encodeToString(config.copy(metadata = metadata))).jsonObject.containsKey("metadata"))
        assertEquals("便携标题", config.copy(metadata = metadata).displayName)
        assertEquals("旧名称", config.displayName)
    }


    @Test fun staleRevisionAndInvalidSaveLeaveOriginalBytesUntouched() = fixture { _, _, config, store ->
        val first = store.save(config, ContainerMetadata("原始"), null)
        val file = document(config)
        file.appendText("\n")
        val external = file.readBytes()
        assertFailsWith<IllegalStateException> { store.save(config, ContainerMetadata("覆盖"), first.revision) }
        assertContentEquals(external, file.readBytes())
        val revision = store.read(config).revision
        for (invalid in listOf(
            ContainerMetadata("   "), ContainerMetadata("x".repeat(121)),
            ContainerMetadata("标题", description = "x".repeat(4001)),
            ContainerMetadata("标题", version = "x".repeat(65)),
            ContainerMetadata("标题", icon = "Bad-Icon"),
            ContainerMetadata("标题", icon = "data:image/svg+xml;base64,PHN2Zz4="),
            ContainerMetadata("\uD800"),
        )) {
            assertFails { store.save(config, invalid, revision) }
            assertContentEquals(external, file.readBytes())
        }
    }

    @Test fun invalidUtf8JsonSchemaAndOversizedDocumentsAreNotReplaced() = fixture { _, _, config, store ->
        val file = writeDocument(config, "{}")
        val invalid = listOf(
            "[]".toByteArray(), "{\"title\":null}".toByteArray(), "{\"description\":7}".toByteArray(),
            "{title:\"a\"}".toByteArray(), "{\"title\":\"a\",}".toByteArray(),
            "{\"title\":\"a\"} trailing".toByteArray(), byteArrayOf(0xc3.toByte(), 0x28),
            ("{\"description\":\"" + "x".repeat(4001) + "\"}").toByteArray(),
            ByteArray(1024 * 1024 + 1) { ' '.code.toByte() },
        )
        for (bytes in invalid) {
            file.writeBytes(bytes)
            assertFails { store.read(config) }
            assertFails { store.save(config, ContainerMetadata("修复不能覆盖原件"), null) }
            assertContentEquals(bytes, file.readBytes())
        }
    }

    @Test fun homeDirectoryAndDocumentLinksCannotEscapeOrRedirectWrites() = fixture { directory, _, config, store ->
        val outside = File(directory, "outside").apply { mkdirs() }
        val sentinel = File(outside, "container.json").apply { writeText("{\"title\":\"不可修改\"}") }
        val rootHome = File(config.rootfsPath, "root")
        Files.createSymbolicLink(rootHome.toPath(), outside.toPath())
        assertFails { store.read(config) }
        assertFails { store.save(config, ContainerMetadata("写入"), null) }
        Files.delete(rootHome.toPath())
        rootHome.mkdirs()
        val wawlink = File(rootHome, ".wawlink")
        Files.createSymbolicLink(wawlink.toPath(), outside.toPath())
        assertFails { store.read(config) }
        assertFails { store.save(config, ContainerMetadata("写入"), null) }
        Files.delete(wawlink.toPath())
        wawlink.mkdirs()
        val file = document(config)
        Files.createSymbolicLink(file.toPath(), sentinel.toPath())
        assertFails { store.read(config) }
        assertFails { store.save(config, ContainerMetadata("写入"), null) }
        Files.delete(file.toPath())
        Files.createLink(file.toPath(), sentinel.toPath())
        assertFails { store.read(config) }
        assertFails { store.save(config, ContainerMetadata("写入"), null) }
        Files.delete(file.toPath())
        file.mkdirs()
        assertFails { store.read(config) }
        assertEquals("{\"title\":\"不可修改\"}", sentinel.readText())
    }

    @Test fun nonRootAndExplicitHomeResolveWithoutAnEngineAndMountsRemainExcluded() = fixture { directory, _, config, store ->
        val alice = config.copy(user = "1001")
        val metadata = ContainerMetadata("Alice", "描述", "1", "home")
        store.save(alice, metadata, null)
        assertEquals(metadata, store.read(alice).value)
        assertTrue(document(config, "home/alice").isFile)
        assertNull(store.read(config).revision)
        val override = alice.copy(environment = mapOf("HOME" to "/custom/home"))
        store.save(override, metadata.copy(title = "Override"), null)
        assertEquals("Override", store.read(override).value.title)
        assertTrue(document(config, "custom/home").isFile)
        val source = File(directory, "host-mount").apply { mkdirs() }
        for (target in listOf("/custom", "/custom/home", "/custom/home/.wawlink", "/custom/home/.wawlink/container.json")) {
            assertFails { store.read(override.copy(mounts = listOf(ContainerMount(source.path, target)))) }
        }
        for (home in listOf("/", "/proc/self", "/sys", "/dev", "/host-rootfs", "/home/../root", "relative")) {
            assertFails { store.read(config.copy(environment = mapOf("HOME" to home))) }
        }
    }

    @Test fun customPngChecksContentDimensionsEncodingAndDecodedSize() = fixture { _, _, config, store ->
        fun uri(bytes: ByteArray) = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)
        val icon = uri(png(2, 3))
        val saved = store.save(config, ContainerMetadata("图片", icon = icon), null)
        assertEquals(icon, store.read(config).value.icon)
        val file = document(config)
        val bytes = file.readBytes()
        for (invalid in listOf(
            uri(png(513, 1)), uri(byteArrayOf(1, 2, 3)), uri(png(1, 1).apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }),
            icon + "\n", "data:image/png;base64," + "A".repeat(700_000), uri(ByteArray(512 * 1024 + 1)),
        )) {
            assertFails { store.save(config, ContainerMetadata("图片", icon = invalid), saved.revision) }
            assertContentEquals(bytes, file.readBytes())
        }
    }

    private fun png(width: Int, height: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val data = DataOutputStream(output)
        data.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        fun chunk(type: String, bytes: ByteArray) {
            val name = type.toByteArray(Charsets.US_ASCII)
            data.writeInt(bytes.size); data.write(name); data.write(bytes)
            data.writeInt(CRC32().apply { update(name); update(bytes) }.value.toInt())
        }
        val header = ByteArrayOutputStream()
        DataOutputStream(header).apply { writeInt(width); writeInt(height); write(byteArrayOf(8, 6, 0, 0, 0)) }
        chunk("IHDR", header.toByteArray())
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { stream -> repeat(height) { stream.write(0); stream.write(ByteArray(width * 4)) } }
        chunk("IDAT", compressed.toByteArray())
        chunk("IEND", byteArrayOf())
        return output.toByteArray()
    }

    @Test fun runtimeReloadPublishesGuestMetadataWithoutPersistingAHostCopy() = fixture { _, data, config, _ -> runBlocking {
        val paths = RuntimePaths(data, hostProot = "/missing-engine-must-not-be-launched")
        val initial = ContainerMetadata("存于客体", "描述", "release-2", "terminal")
        val runtime = LocalContainerRuntime(paths)
        runtime.register(config)
        assertEquals(config.name, runtime.containers.value.single().config.displayName)
        val saved = runtime.saveMetadata(config.id, initial, null)
        assertEquals(initial, runtime.containers.value.single().config.metadata)
        assertEquals(0L, runtime.containers.value.single().runGeneration)
        assertFalse(runtime.containers.value.single().running)
        assertFalse(File(data, "containers.json").readText().contains("存于客体"))
        document(config).writeText("{\"title\":\"客体外部编辑\",\"icon\":\"home\"}")
        val reloaded = runtime.readMetadata(config.id)
        assertNotEquals(saved.revision, reloaded.revision)
        assertEquals("客体外部编辑", runtime.containers.value.single().config.displayName)
        runtime.close()
        val restored = LocalContainerRuntime(paths)
        try {
            assertEquals("客体外部编辑", restored.containers.value.single().config.displayName)
            assertNull(restored.containers.value.single().metadataError)
            assertNull(restored.containers.value.single().error)
        } finally { restored.close() }
    } }

    @Test fun initialMetadataErrorsDoNotBecomeEngineErrorsOrBlockConfigRepair() = fixture { _, data, config, _ -> runBlocking {
        writeDocument(config, "{\"title\":false}")
        val runtime = LocalContainerRuntime(RuntimePaths(data, hostProot = "/missing-engine-must-not-be-launched"))
        try {
            runtime.register(config)
            assertTrue(runtime.containers.value.single().metadataError != null)
            assertNull(runtime.containers.value.single().error)
            assertEquals(config.name, runtime.containers.value.single().config.displayName)
            // A new configured HOME recovers independently of the malformed old document.
            runtime.close()
            // Only the host capability probe runs during config validation; no guest is started.
            val repair = LocalContainerRuntime(RuntimePaths(data, hostProot = "/bin/echo"))
            try {
                val changed = config.copy(arch = System.getProperty("os.arch"), environment = mapOf("HOME" to "/new-home"))
                repair.update(changed)
                assertNull(repair.containers.value.single().metadataError)
                assertEquals("{\"title\":false}", document(config).readText())
                repair.saveMetadata(config.id, ContainerMetadata("已修复"), null)
                repair.update(changed.copy(name = "过期快照"))
                assertEquals("已修复", repair.containers.value.single().config.displayName)
                val alice = changed.copy(user = "alice", environment = emptyMap())
                repair.update(alice)
                assertEquals(ContainerMetadata(), repair.containers.value.single().config.metadata)
                repair.saveMetadata(config.id, ContainerMetadata("Alice"), null)
                assertTrue(document(config, "home/alice").isFile)
                repair.update(changed)
                assertEquals("已修复", repair.containers.value.single().config.displayName)
            } finally { repair.close() }
        } finally { runtime.close() }
    } }

    @Test fun archiveImportAndCloneRetainPortableFieldsAndUseExplicitCloneTitle() = fixture { directory, data, config, store -> runBlocking {
        val metadata = ContainerMetadata("归档标题", "保留描述", "v3", "terminal")
        store.save(config, metadata, null)
        val archive = File(directory, "portable.tar.zst")
        RootfsArchives.export(File(config.rootfsPath), archive)
        val runtime = LocalContainerRuntime(RuntimePaths(data, hostProot = "/missing-engine-must-not-be-launched"))
        try {
            val inspection = runtime.inspectRootfsArchive(archive.path, "临时导入名称")
            assertEquals(metadata, inspection.metadata)
            val imported = runtime.importRootfs(inspection.id, "临时导入名称")
            assertEquals(metadata, imported.metadata)
            assertEquals("归档标题", imported.displayName)
            val cloned = runtime.clone(imported.id, "明确克隆名称")
            assertEquals(metadata.copy(title = "明确克隆名称"), cloned.metadata)
            assertEquals(metadata, runtime.readMetadata(imported.id).value)
            assertEquals(cloned.metadata, runtime.readMetadata(cloned.id).value)
            assertTrue(runtime.containers.value.all { !it.running && it.runGeneration == 0L })
            // A distribution archive with no document gets its requested initial title.
            document(config).delete()
            val bare = File(directory, "bare.tar.zst")
            RootfsArchives.export(File(config.rootfsPath), bare)
            val bareInspection = runtime.inspectRootfsArchive(bare.path, "全新标题")
            val created = runtime.importRootfs(bareInspection.id, "全新标题")
            assertEquals(ContainerMetadata("全新标题"), runtime.readMetadata(created.id).value)
            val invalid = "{\"title\":false}"
            writeDocument(config, invalid)
            val malformed = File(directory, "malformed.tar.zst")
            RootfsArchives.export(File(config.rootfsPath), malformed)
            val malformedInspection = runtime.inspectRootfsArchive(malformed.path, "可修复导入")
            val repairable = runtime.importRootfs(malformedInspection.id, "可修复导入")
            val info = runtime.containers.value.single { it.config.id == repairable.id }
            assertTrue(info.metadataError != null)
            assertNull(info.error)
            assertEquals(invalid, document(repairable).readText())
            assertFails { runtime.clone(repairable.id, "不能覆盖损坏元数据") }
            assertEquals(invalid, document(repairable).readText())
        } finally { runtime.close() }
    } }
}
