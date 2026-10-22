package dev.wawlink.driver

import dev.wawlink.runtime.ContainerConfig
import dev.wawlink.runtime.LocalContainerRuntime
import dev.wawlink.runtime.RuntimePaths
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

class OmpSessionHistoryTest {
    @Test fun forksNativeTitleSlotWithoutChangingSourceOrMessageBytes() = runBlocking {
        withHistory(includeSlot = true) { pool, root, source, body ->
            val original = source.readBytes()
            val title = "独立分支"
            pool.fork("source", "branch", "fixture", "/branch-workspace", title)
            val branch = File(historyDirectory(root, "branch"), source.name)
            val (slot, header) = inspect(branch, body)
            assertEquals(title, slot.getValue("title").jsonPrimitive.content)
            assertEquals(title, header.getValue("title").jsonPrimitive.content)
            assertEquals("/branch-workspace", header.getValue("cwd").jsonPrimitive.content)
            assertNotEquals("11111111-1111-4111-8111-111111111111", header.getValue("id").jsonPrimitive.content)
            assertEquals("/root/.omp/agent/wawlink/sessions/${hash("source")}/${source.name}", header.getValue("parentSession").jsonPrimitive.content)
            assertEquals("preserved-cache-identity", header.getValue("providerPromptCacheKey").jsonPrimitive.content)
            assertContentEquals(original, source.readBytes())
        }
    }

    @Test fun relocationPreservesIdentityAndFitsEscapedUnicodeTitleToNativeSlot() = runBlocking {
        withHistory(includeSlot = false) { pool, _, source, body ->
            val title = "迁移😀\\\"".repeat(80)
            pool.relocateSession("source", "fixture", "/moved-workspace", title)
            val (slot, header) = inspect(source, body)
            assertEquals("11111111-1111-4111-8111-111111111111", header.getValue("id").jsonPrimitive.content)
            assertEquals("/moved-workspace", header.getValue("cwd").jsonPrimitive.content)
            assertEquals(title, header.getValue("title").jsonPrimitive.content)
            val visibleTitle = slot.getValue("title").jsonPrimitive.content
            assertTrue(title.startsWith(visibleTitle))
            val nextEnd = title.offsetByCodePoints(visibleTitle.length, 1)
            val next = JsonObject(slot + mapOf("title" to JsonPrimitive(title.substring(0, nextEnd)), "pad" to JsonPrimitive("")))
            assertTrue(next.toString().toByteArray(Charsets.UTF_8).size + 1 > 256, "槽位必须保留能容纳的最长完整 Unicode 前缀")
        }
    }

    private fun inspect(file: File, expectedBody: ByteArray): Pair<JsonObject, JsonObject> {
        val bytes = file.readBytes()
        assertEquals(255, bytes.indexOf('\n'.code.toByte()), "原生标题原位更新占据 256 字节，包含换行")
        val slot = Json.parseToJsonElement(String(bytes, 0, 255, Charsets.UTF_8)).jsonObject
        assertEquals("title", slot.getValue("type").jsonPrimitive.content)
        assertEquals("user", slot.getValue("source").jsonPrimitive.content)
        var headerEnd = 256
        while (bytes[headerEnd] != '\n'.code.toByte()) headerEnd++
        val header = Json.parseToJsonElement(String(bytes, 256, headerEnd - 256, Charsets.UTF_8)).jsonObject
        assertEquals("session", header.getValue("type").jsonPrimitive.content)
        assertContentEquals(expectedBody, bytes.copyOfRange(headerEnd + 1, bytes.size))
        return slot to header
    }

    private suspend fun withHistory(includeSlot: Boolean, test: suspend (OmpProcessPool, File, File, ByteArray) -> Unit) {
        val directory = Files.createTempDirectory("wawlink-history-").toFile()
        val runtime = LocalContainerRuntime(RuntimePaths(File(directory, "runtime")))
        var pool: OmpProcessPool? = null
        try {
            val root = File(directory, "root").apply { mkdirs() }
            File(root, "etc").mkdirs()
            val shell = File(root, "bin/sh").apply { parentFile.mkdirs() }
            Files.copy(File("/bin/sh").canonicalFile.toPath(), shell.toPath())
            check(shell.setExecutable(true))
            val arch = if (System.getProperty("os.arch") in listOf("aarch64", "arm64")) "arm64" else "amd64"
            runtime.register(ContainerConfig("fixture", "历史验证", root.canonicalPath, arch = arch, link2symlink = false))
            val header = buildJsonObject {
                put("type", "session"); put("version", 3)
                put("id", "11111111-1111-4111-8111-111111111111")
                put("timestamp", "2026-10-22T00:00:00.000Z"); put("cwd", "/workspace")
                put("title", "原始会话"); put("providerPromptCacheKey", "preserved-cache-identity")
            }
            val body = ("""{"type":"message","id":"message-1","parentId":null,"timestamp":"2026-10-22T00:00:01.000Z","message":{"role":"user","content":"保留中文与 😀","timestamp":1}}""" + "\r\n").toByteArray(Charsets.UTF_8)
            val source = File(historyDirectory(root, "source").apply { mkdirs() }, "source.jsonl")
            source.outputStream().use { output ->
                if (includeSlot) {
                    val slot = """{"type":"title","v":1,"title":"原始会话","source":"user","updatedAt":"2026-10-22T00:00:00.000Z","pad":""}"""
                    output.write((slot.dropLast(2) + " ".repeat(255 - slot.toByteArray(Charsets.UTF_8).size) + "\"}\n").toByteArray(Charsets.UTF_8))
                }
                output.write((header.toString() + "\n").toByteArray(Charsets.UTF_8))
                output.write(body)
            }
            val data = File(directory, "gui")
            File(data, "omp-sessions/${hash("source")}.json").apply {
                parentFile.mkdirs()
                writeText(buildJsonObject {
                    put("id", "source"); put("containerId", "fixture"); put("workspace", "/workspace"); put("title", "原始会话")
                    put("sessionFile", "/root/.omp/agent/wawlink/sessions/${hash("source")}/${source.name}")
                }.toString())
            }
            pool = OmpProcessPool(runtime, data)
            pool.session("source", "fixture", "/workspace", "原始会话")
            test(pool, root, source, body)
        } finally {
            pool?.close()
            runtime.close()
            directory.deleteRecursively()
        }
    }

    private fun historyDirectory(root: File, id: String) = File(root, "root/.omp/agent/wawlink/sessions/${hash(id)}")
    private fun hash(id: String) = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
}
