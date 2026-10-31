package dev.wawlink.driver

import dev.wawlink.runtime.ContainerConfig
import dev.wawlink.runtime.ContainerEngineType
import dev.wawlink.runtime.ContainerMount
import dev.wawlink.runtime.LocalContainerRuntime
import dev.wawlink.runtime.ContainerRuntime
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

    @Test fun forksAndAdoptsHistoryInUsernameUidAndOverriddenHomes() = runBlocking {
        for ((user, homeOverride, home) in listOf(
            Triple("operator", null, "/home/operator"),
            Triple("1000", null, "/home/operator"),
            Triple("operator", "/srv/team/profiles/custom-home", "/srv/team/profiles/custom-home"),
        )) {
            withHistory(includeSlot = true, user = user, homeOverride = homeOverride, guestHome = home) { pool, root, source, body ->
                assertEquals("$home/.omp/agent", pool.guestAgentDirectory("fixture"))
                val original = source.readBytes()
                pool.fork("source", "branch", "fixture", "/branch-workspace", "用户分支")
                val branch = File(historyDirectory(root, "branch", home), source.name)
                val (_, header) = inspect(branch, body)
                assertEquals("$home/.omp/agent/wawlink/sessions/${hash("source")}/${source.name}", header.getValue("parentSession").jsonPrimitive.content)
                pool.adoptFork("source", "adopted", "$home/.omp/agent/wawlink/sessions/${hash("branch")}/${source.name}", "接管分支")
                assertTrue(File(historyDirectory(root, "adopted", home), source.name).isFile)
                assertContentEquals(original, source.readBytes())
                assertTrue(!File(root, "root/.omp/agent/wawlink/sessions").exists())
            }
        }
    }

    @Test fun chrootForkAdoptionAndRelocationKeepCopiedHistoryAndArtifactsShared() = runBlocking {
        withHistory(includeSlot = true, user = "operator", guestHome = "/home/operator", engine = ContainerEngineType.CHROOT) { pool, root, source, body ->
            val artifact = File(source.parentFile, "${source.nameWithoutExtension}/nested/result.txt").apply { parentFile.mkdirs(); writeText("原生附件") }
            pool.fork("source", "branch", "fixture", "/branch", "分支")
            val branch = historyDirectory(root, "branch", "/home/operator")
            val copied = File(branch, artifact.relativeTo(source.parentFile).path)
            assertEquals("原生附件", copied.readText())
            pool.adoptFork("source", "adopted", "/home/operator/.omp/agent/wawlink/sessions/${hash("branch")}/${source.name}", "接管")
            pool.relocateSession("branch", "fixture", "/moved", "迁移")
            for (id in listOf("branch", "adopted")) {
                val directory = historyDirectory(root, id, "/home/operator")
                inspect(File(directory, source.name), body)
                directory.walkTopDown().forEach { file ->
                    val expected = if (file.isDirectory) "2770" else "660"
                    assertEquals(expected.toInt(8), (Files.getAttribute(file.toPath(), "unix:mode") as Number).toInt() and 0xfff)
                    assertTrue(file.canRead() && file.canWrite())
                }
            }
        }
    }

    @Test fun crossContainerForkAndRelocationUseDestinationUsersHomeAndPreserveOtherUsersData() = runBlocking {
        withHistory(includeSlot = true, user = "operator", guestHome = "/home/operator") { pool, root, source, body ->
            val config = pool.runtime.containers.value.single().config
            val target = File(root.parentFile, "target-root").apply { mkdirs() }
            File(target, "etc").mkdirs()
            File(root, "etc/passwd").copyTo(File(target, "etc/passwd"))
            File(root, "bin/sh").copyTo(File(target, "bin/sh").apply { parentFile.mkdirs() }).setExecutable(true)
            val targetHome = "/srv/custom-home"
            pool.runtime.register(config.copy(id = "target", rootfsPath = target.canonicalPath, user = "1000", environment = mapOf("HOME" to targetHome)))
            val untouched = File(target, "root/.omp/agent/config.yml").apply { parentFile.mkdirs(); writeText("temperature: 0.9\n") }
            pool.fork("source", "branch", "target", "/branch", "跨容器分支")
            val branch = File(historyDirectory(target, "branch", targetHome), source.name)
            assertEquals("/branch", inspect(branch, body).second.getValue("cwd").jsonPrimitive.content)
            assertTrue(source.isFile)
            pool.relocateSession("source", "target", "/moved", "迁移会话")
            val relocated = File(historyDirectory(target, "source", targetHome), source.name)
            assertEquals("/moved", inspect(relocated, body).second.getValue("cwd").jsonPrimitive.content)
            assertTrue(!source.exists())
            assertEquals("temperature: 0.9\n", untouched.readText())
            val data = File(root.parentFile, "gui/omp-sessions")
            for (id in listOf("source", "branch")) {
                val metadata = Json.parseToJsonElement(File(data, "${hash(id)}.json").readText()).jsonObject
                assertEquals("$targetHome/.omp/agent/wawlink/sessions/${hash(id)}/${source.name}", metadata.getValue("sessionFile").jsonPrimitive.content)
            }
        }
    }

    @Test fun homeUnderRuntimeSystemMountsCannotOwnOmpConfiguration() = runBlocking {
        for (home in listOf("/dev/profile", "/proc/profile", "/sys/profile")) {
            withHistory(includeSlot = true, user = "1000", homeOverride = home, guestHome = home) { pool, _, source, _ ->
                kotlin.test.assertFailsWith<IllegalArgumentException> { pool.settings("fixture").updateSetting("temperature", 0.4) }
                assertTrue(source.isFile)
            }
        }
    }

    @Test fun overriddenHomeRejectsAncestorAndChildMountsWithoutTouchingHostConfiguration() = runBlocking {
        val home = "/srv/team/custom-home"
        for (target in listOf("/srv/team", home, "$home/.omp/agent", "$home/.omp/agent/skills")) {
            withHistory(includeSlot = true, user = "1000", homeOverride = home, guestHome = home, mountTarget = target) { pool, root, source, _ ->
                val hostConfig = File(root.parentFile, "host-home/.omp/agent/config.yml").apply { parentFile.mkdirs(); writeText("temperature: 0.9\n") }
                val original = source.readBytes()
                kotlin.test.assertFailsWith<IllegalArgumentException> { pool.fork("source", "blocked", "fixture", "/workspace", "不可写入宿主") }
                kotlin.test.assertFailsWith<IllegalArgumentException> { pool.settings("fixture").updateSetting("temperature", 0.4) }
                assertEquals("temperature: 0.9\n", hostConfig.readText())
                assertContentEquals(original, source.readBytes())
                assertTrue(!historyDirectory(root, "blocked", home).exists())
            }
        }
    }

    @Test fun mountedAliasOfAgentHomeIsRejectedByCanonicalBoundary() = runBlocking {
        val home = "/srv/team/custom-home"
        withHistory(includeSlot = true, user = "1000", homeOverride = home, guestHome = home, mountTarget = "/srv/mounted-profile") { pool, root, source, _ ->
            val mount = File(root, "srv/mounted-profile")
            // Registration records mounts without creating their targets; no guest process is started here.
            val homeDirectory = File(root, home.trimStart('/'))
            Files.createSymbolicLink(mount.toPath(), homeDirectory.toPath())
            assertTrue(Files.isSameFile(mount.toPath(), homeDirectory.toPath()), "挂载别名必须真实解析到容器 HOME")
            val original = source.readBytes()
            kotlin.test.assertFailsWith<IllegalArgumentException> { pool.fork("source", "blocked", "fixture", "/workspace", "符号链接挂载") }
            kotlin.test.assertFailsWith<IllegalArgumentException> { pool.settings("fixture").updateSetting("temperature", 0.4) }
            assertContentEquals(original, source.readBytes())
            assertTrue(!historyDirectory(root, "blocked", home).exists())
        }
    }

    @Test fun unrelatedWorkspaceMountDoesNotBlockRootfsOwnedHistory() = runBlocking {
        withHistory(includeSlot = true, user = "operator", guestHome = "/home/operator", mountTarget = "/workspace") { pool, root, source, _ ->
            pool.fork("source", "branch", "fixture", "/workspace", "普通挂载")
            assertTrue(File(historyDirectory(root, "branch", "/home/operator"), source.name).isFile)
            assertTrue(source.isFile)
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

    private suspend fun withHistory(includeSlot: Boolean, user: String = "root", homeOverride: String? = null, guestHome: String = "/root", mountTarget: String? = null, engine: ContainerEngineType = ContainerEngineType.PROROOT, test: suspend (OmpProcessPool, File, File, ByteArray) -> Unit) {
        val directory = Files.createTempDirectory("wawlink-history-").toFile()
        val runtime = LocalContainerRuntime(RuntimePaths(File(directory, "runtime")))
        var pool: OmpProcessPool? = null
        try {
            val root = File(directory, "root").apply { mkdirs() }
            File(root, "etc").mkdirs()
            File(root, "etc/passwd").writeText("root:x:0:0:root:/root:/bin/sh\noperator:x:1000:1000:Operator:/home/operator:/bin/sh\n")
            val shell = File(root, "bin/sh").apply { parentFile.mkdirs() }
            Files.copy(File("/bin/sh").canonicalFile.toPath(), shell.toPath())
            check(shell.setExecutable(true))
            val arch = if (System.getProperty("os.arch") in listOf("aarch64", "arm64")) "arm64" else "amd64"
            val mounts = mountTarget?.let { listOf(ContainerMount(File(directory, "host-home").apply { mkdirs() }.canonicalPath, it)) }.orEmpty()
            runtime.register(ContainerConfig("fixture", "历史验证", root.canonicalPath, arch = arch, user = user, mounts = mounts, environment = homeOverride?.let { mapOf("HOME" to it) }.orEmpty(), link2symlink = false))
            val header = buildJsonObject {
                put("type", "session"); put("version", 3)
                put("id", "11111111-1111-4111-8111-111111111111")
                put("timestamp", "2026-10-22T00:00:00.000Z"); put("cwd", "/workspace")
                put("title", "原始会话"); put("providerPromptCacheKey", "preserved-cache-identity")
            }
            val body = ("""{"type":"message","id":"message-1","parentId":null,"timestamp":"2026-10-22T00:00:01.000Z","message":{"role":"user","content":"保留中文与 😀","timestamp":1}}""" + "\r\n").toByteArray(Charsets.UTF_8)
            val source = File(historyDirectory(root, "source", guestHome).apply { mkdirs() }, "source.jsonl")
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
                    put("sessionFile", "$guestHome/.omp/agent/wawlink/sessions/${hash("source")}/${source.name}")
                }.toString())
            }
            pool = OmpProcessPool(object : ContainerRuntime by runtime {
                override val containers = if (engine == ContainerEngineType.CHROOT) kotlinx.coroutines.flow.MutableStateFlow(runtime.containers.value.map { it.copy(config = it.config.copy(engine = engine)) }) else runtime.containers
                override suspend fun openProcess(id: String, arguments: List<String>, cwd: String, environment: Map<String, String>): Process = error("History operations must not start a container process")
            }, data)
            pool.session("source", "fixture", "/workspace", "原始会话")
            test(pool, root, source, body)
        } finally {
            pool?.close()
            runtime.close()
            directory.deleteRecursively()
        }
    }

    private fun historyDirectory(root: File, id: String, home: String = "/root") = File(root, "${home.trimStart('/')}/.omp/agent/wawlink/sessions/${hash(id)}")
    private fun hash(id: String) = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
}
