package dev.wawlink.runtime

import java.io.File
import java.lang.reflect.Proxy
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ContainerFeatureDebugTest {
    private class Fixture {
        private val config = ContainerConfig("container", "Test", "/unused")
        val containers = MutableStateFlow(listOf(ContainerInfo(config, running = true, runGeneration = 4)))
        val catalogCalls = AtomicInteger()
        val runtime = Proxy.newProxyInstance(ContainerRuntime::class.java.classLoader, arrayOf(ContainerRuntime::class.java)) { _, method, _ ->
            when (method.name) {
                "getContainers" -> containers
                "toString" -> "DebuggerFixtureRuntime"
                else -> error("Debugger must not invoke runtime method ${method.name}")
            }
        } as ContainerRuntime
        val debugger = ContainerFeatureDebugger(runtime, {
            catalogCalls.incrementAndGet()
            ContainerFeatureCatalog("/home/test", listOf(ContainerFeature("demo", "Demo", "Description", "terminal", "/home/test/.wawlink/features/demo")), emptyList())
        }, { _, _ -> ContainerFeatureState(output = "real service output", error = "service failure") })
        val card = FeatureDebugTarget("container", "demo", "card")
        val page = card.copy(surface = "page")
        suspend fun request(operation: String, surface: String = "card", generation: Long = 4) =
            debugger.request("container", generation, operation, "demo", surface, if (operation == "eval") "value" else null)
    }

    @Test fun duplicateMountsFailAndClosingOwnHandlePreservesOtherMountsAndSurfaces() = runBlocking {
        val fixture = Fixture()
        fixture.debugger.use {
            val first = it.register(fixture.card) { _, _ -> JsonPrimitive("first") }
            val second = it.register(fixture.card) { _, _ -> JsonPrimitive("second") }
            val page = it.register(fixture.page) { _, _ -> JsonPrimitive("page") }
            assertTrue(runCatching { fixture.request("eval") }.exceptionOrNull() is IllegalStateException)
            assertEquals(JsonPrimitive("page"), fixture.request("eval", "page"))
            first.close()
            assertEquals(JsonPrimitive("second"), fixture.request("eval"))
            first.close()
            assertEquals(JsonPrimitive("second"), fixture.request("eval"))
            second.close()
            page.close()
            val inspection = fixture.request("inspect").jsonObject
            assertFalse(inspection.getValue("mounted").jsonPrimitive.boolean)
            assertEquals("real service output", fixture.request("logs").jsonObject.getValue("output").jsonPrimitive.content)
            assertEquals("service failure", inspection.getValue("error").jsonPrimitive.content)
            assertTrue(runCatching { fixture.request("screenshot") }.isFailure)
            assertTrue(runCatching { fixture.request("reload") }.isFailure)
        }
    }

    @Test fun stoppedDeletedAndConflatedRestartCannotUseStaleMountsOrStartRuntime() = runBlocking {
        val fixture = Fixture()
        fixture.debugger.use {
            val old = it.register(fixture.card) { _, _ -> JsonPrimitive("old") }
            fixture.containers.value = fixture.containers.value.map { container -> container.copy(running = false) }
            assertTrue(runCatching { fixture.request("eval") }.isFailure)
            assertEquals(0, fixture.catalogCalls.get(), "Stopped requests must fail before discovery")
            fixture.containers.value = fixture.containers.value.map { container -> container.copy(running = true, runGeneration = 5) }
            assertTrue(runCatching { fixture.request("eval") }.isFailure)
            assertFalse(fixture.request("inspect", generation = 5).jsonObject.getValue("mounted").jsonPrimitive.boolean)
            val replacement = it.register(fixture.card) { _, _ -> JsonPrimitive("new") }
            it.containersChanged()
            old.close()
            assertEquals(JsonPrimitive("new"), fixture.request("eval", generation = 5))
            fixture.containers.value = emptyList()
            assertTrue(runCatching { fixture.request("reload", generation = 5) }.isFailure)
            it.containersChanged()
            replacement.close()
        }
    }

    @Test fun disposingMountCancelsPendingRequestRatherThanReturningStaleValue() = runBlocking {
        val fixture = Fixture()
        fixture.debugger.use {
            val entered = CompletableDeferred<Unit>()
            val registration = it.register(fixture.card) { _, _ ->
                entered.complete(Unit)
                awaitCancellation()
            }
            val pending = async { runCatching { fixture.request("eval") } }
            withTimeout(5000) { entered.await() }
            registration.close()
            assertTrue(withTimeout(5000) { pending.await() }.isFailure)
            assertFalse(fixture.request("inspect").jsonObject.getValue("mounted").jsonPrimitive.boolean)
        }
    }

    @Test fun endpointRejectsMissingWrongCrossContainerAndBrowserCredentials() {
        val calls = AtomicInteger()
        FeatureDebugEndpoint("first", 7) { id, generation, _, _, _, _ ->
            calls.incrementAndGet()
            buildJsonObject { put("containerId", id); put("generation", generation) }
        }.use { first ->
            FeatureDebugEndpoint("second", 11) { _, _, _, _, _, _ -> JsonNull }.use { second ->
                val config = first.configuration()
                val firstToken = config.getValue("token").jsonPrimitive.content
                val secondToken = second.configuration().getValue("token").jsonPrimitive.content
                assertNotEquals(firstToken, secondToken)
                assertEquals(403, post(config, null).first)
                assertEquals(403, post(config, secondToken).first)
                assertEquals(403, post(config, firstToken, "Origin: http://127.0.0.1\r\n").first)
                assertEquals(403, post(config, firstToken, "Referer: http://127.0.0.1/\r\n").first)
                assertEquals(0, calls.get())
                val (status, response) = post(config, firstToken)
                assertEquals(200, status)
                assertFalse(response.contains("Access-Control-Allow", ignoreCase = true))
                assertFalse(response.contains(firstToken))
                val body = Json.parseToJsonElement(response.substringAfter("\r\n\r\n")).jsonObject
                assertEquals("first", body.getValue("result").jsonObject.getValue("containerId").jsonPrimitive.content)
                assertEquals(1, calls.get())
            }
        }
    }

    /** Runs production installer/client JS under Node; only UID/GID syscalls are simulated. */
    private class ClientFixture(uid: Int = 0, gid: Int = 0) : AutoCloseable {
        val root = Files.createTempDirectory("wawlink-debug-owner-").toFile()
        val home = File(root, "guest home '中文").apply { mkdir() }
        val directory = File(home, ".wawlink")
        val bin = File(directory, "bin")
        val privateDir = File(directory, "feature-debug")
        val executable = File(bin, "wawlink-feature")
        val marker = File(privateDir, "owner")
        val record = File(privateDir, "installation.json")
        val config = File(privateDir, "endpoint.json")
        private val identity = File(root, "identity.json").apply {
            writeText(buildJsonObject { put("uid", uid); put("gid", gid); put("owners", buildJsonObject {}) }.toString())
        }
        private val preload = File(root, "ownership.cjs").apply { writeText(OWNERSHIP_PRELOAD) }
        private val containers = MutableStateFlow(listOf(ContainerInfo(ContainerConfig("container", "Test", root.path), running = true, runGeneration = 4)))
        private val runtime = Proxy.newProxyInstance(ContainerRuntime::class.java.classLoader, arrayOf(ContainerRuntime::class.java)) { _, method, args ->
            if (method.name == "getContainers") return@newProxyInstance containers
            check(method.name == "openRunningProcess") { "Unexpected runtime API: ${method.name}" }
            check(args[0] == "container" && args[2] == "/")
            @Suppress("UNCHECKED_CAST")
            val command = args[1] as List<String>
            // Keep the exact production script/payload, substituting only the host JS executable.
            val actual = command.dropLast(1) + command.last().replaceFirst("/usr/local/bin/bun", "node --require " + shellQuote(preload.path))
            ProcessBuilder(actual).directory(root).apply {
                @Suppress("UNCHECKED_CAST")
                environment().putAll(args[3] as Map<String, String>)
                environment()["HOME"] = home.path
                environment()["WAWLINK_TEST_IDENTITY"] = identity.path
            }.start()
        } as ContainerRuntime
        private val debugger = ContainerFeatureDebugger(runtime, {
            ContainerFeatureCatalog(home.path, listOf(ContainerFeature("demo", "Demo", "Description", "terminal", File(directory, "features/demo").path)), emptyList())
        }, { _, _ -> ContainerFeatureState(output = "service output") })
        private val endpoint = FeatureDebugEndpoint("container", 4, debugger::request)
        suspend fun install() = installFeatureDebugClient(runtime, "container", endpoint.configuration())
        suspend fun client(vararg arguments: String): JsonObject = Json.parseToJsonElement(featureCommand(runtime, "container",
            "exec /usr/local/bin/bun " + shellQuote(executable.path) + arguments.joinToString("") { " " + shellQuote(it) },
            runningOnly = true).toString(Charsets.UTF_8)).jsonObject
        private fun key(file: File): String = listOf("dev", "ino").joinToString(":") {
            (Files.getAttribute(file.toPath(), "unix:$it", NOFOLLOW_LINKS) as Number).toLong().toString()
        }
        fun own(file: File, uid: Int, gid: Int) {
            val state = Json.parseToJsonElement(identity.readText()).jsonObject.toMutableMap()
            val owners = state.getValue("owners").jsonObject.toMutableMap()
            owners[key(file)] = buildJsonObject { put("uid", uid); put("gid", gid) }
            state["owners"] = JsonObject(owners)
            identity.writeText(JsonObject(state).toString())
        }
        fun owner(file: File): Pair<Int, Int> {
            val state = Json.parseToJsonElement(identity.readText()).jsonObject
            val owner = state.getValue("owners").jsonObject[key(file)]?.jsonObject ?: state
            return owner.getValue("uid").jsonPrimitive.int to owner.getValue("gid").jsonPrimitive.int
        }
        fun mode(file: File) = (Files.getAttribute(file.toPath(), "unix:mode", NOFOLLOW_LINKS) as Number).toInt() and 4095
        fun mode(file: File, mode: Int) { Files.setAttribute(file.toPath(), "unix:mode", mode, NOFOLLOW_LINKS) }
        override fun close() { endpoint.close(); debugger.close(); root.deleteRecursively() }
    }

    @Test fun productionInstallerAndClientReclaimForeignOwnersOnlyAsRoot() = runBlocking {
        ClientFixture().use { f ->
            f.install()
            val managed = listOf(f.directory, f.bin, f.privateDir, f.marker, f.record, f.executable, f.config)
            managed.forEach { f.own(it, 12001, 12002) }
            val originalClient = f.executable.readText()
            f.install()
            managed.forEach { assertEquals(0 to 0, f.owner(it), it.path) }
            assertEquals(originalClient, f.executable.readText())
            assertEquals(448, f.mode(f.privateDir))
            assertEquals(448, f.mode(f.executable))
            listOf(f.marker, f.record, f.config).forEach { assertEquals(384, f.mode(it)) }
            // Exercise the installed client, HTTP authentication, endpoint and real debugger dispatch.
            listOf(f.directory, f.bin, f.privateDir, f.config).forEach { f.own(it, 12001, 12002) }
            val response = f.client("list", "--surface", "page")
            assertEquals("container", response.getValue("containerId").jsonPrimitive.content)
            assertEquals("page", response.getValue("surface").jsonPrimitive.content)
            assertEquals("demo", response.getValue("features").jsonArray.single().jsonObject.getValue("featureId").jsonPrimitive.content)
            listOf(f.directory, f.bin, f.privateDir, f.config).forEach { assertEquals(0 to 0, f.owner(it)) }
            assertEquals("service output", f.client("logs", "demo").getValue("output").jsonPrimitive.content)
        }
    }

    @Test fun nonrootInstallerAndClientRejectForeignOwnersWithoutTakingThemOver() = runBlocking {
        ClientFixture(1000, 1001).use { f ->
            f.install()
            for (target in listOf(f.directory, f.bin, f.privateDir, f.config)) {
                val contents = if (target.isFile) target.readText() else null
                f.own(target, 12001, 12002)
                assertFailsWith<IllegalStateException> { f.install() }
                assertEquals(12001 to 12002, f.owner(target))
                assertFailsWith<IllegalStateException> { f.client("list") }
                assertEquals(12001 to 12002, f.owner(target))
                if (contents != null) assertEquals(contents, target.readText())
                f.own(target, 1000, 1001)
            }
            assertEquals("container", f.client("list").getValue("containerId").jsonPrimitive.content)
        }
    }

    @Test fun sharedSetgidParentSurvivesWhileManagedChildrenUseCurrentPrimaryGroup() = runBlocking {
        ClientFixture(1000, 1001).use { f ->
            f.directory.mkdir()
            f.mode(f.directory, 1528) // 02770: shared guest UID / host App GID.
            f.own(f.directory, 1000, 12002)
            f.install()
            assertEquals(1000 to 12002, f.owner(f.directory))
            assertEquals(1528, f.mode(f.directory))
            val children = listOf(f.bin, f.privateDir, f.marker, f.record, f.executable, f.config)
            children.forEach { assertEquals(1000 to 1001, f.owner(it), it.path) }
            // Existing own files may also retain a supplementary/inherited group from older installs.
            children.forEach { f.own(it, 1000, 12002) }
            f.install()
            children.forEach { assertEquals(1000 to 1001, f.owner(it), it.path) }
            f.own(f.config, 1000, 12002)
            f.client("list")
            assertEquals(1000 to 1001, f.owner(f.config))
            assertEquals(1000 to 12002, f.owner(f.directory))
            assertEquals(1528, f.mode(f.directory))
        }
    }

    @Test fun rootRepairRejectsDirectoryAndManagedFileSymlinksWithoutTouchingTargets() = runBlocking {
        ClientFixture().use { f ->
            f.install()
            val outside = File(f.root, "outside").apply { mkdir() }
            val kept = File(outside, "kept").apply { writeText("user data") }
            f.own(outside, 12001, 12002)
            f.own(kept, 12001, 12002)
            val outsideMode = f.mode(outside)
            val keptMode = f.mode(kept)
            for (target in listOf(f.directory, f.bin, f.privateDir, f.marker, f.record, f.executable, f.config)) {
                val backup = File(f.root, "backup")
                val wasDirectory = target.isDirectory
                Files.move(target.toPath(), backup.toPath())
                try {
                    Files.createSymbolicLink(target.toPath(), (if (wasDirectory) outside else kept).toPath())
                    assertFailsWith<IllegalStateException> { f.install() }
                    if (wasDirectory || target == f.config) assertFailsWith<IllegalStateException> { f.client("list") }
                    assertEquals("user data", kept.readText())
                    assertEquals(12001 to 12002, f.owner(outside))
                    assertEquals(12001 to 12002, f.owner(kept))
                    assertEquals(outsideMode, f.mode(outside))
                    assertEquals(keptMode, f.mode(kept))
                } finally {
                    Files.deleteIfExists(target.toPath())
                    Files.move(backup.toPath(), target.toPath())
                }
            }
        }
    }

    @Test fun rootRepairRejectsHardlinkedManagedFilesWithoutMutatingAliases() = runBlocking {
        ClientFixture().use { f ->
            f.install()
            for (target in listOf(f.marker, f.record, f.executable, f.config)) {
                val original = target.readText()
                val originalMode = f.mode(target)
                val alias = File(f.root, "outside-alias").apply { writeText(original) }
                f.mode(alias, 420) // 0644: would visibly change if a linked file were chmod'ed.
                f.own(alias, 12001, 12002)
                Files.delete(target.toPath())
                Files.createLink(target.toPath(), alias.toPath())
                try {
                    assertFailsWith<IllegalStateException> { f.install() }
                    if (target == f.config) assertFailsWith<IllegalStateException> { f.client("list") }
                    assertEquals(original, alias.readText())
                    assertEquals(420, f.mode(alias))
                    assertEquals(12001 to 12002, f.owner(alias))
                } finally {
                    Files.delete(target.toPath())
                    Files.delete(alias.toPath())
                    target.writeText(original)
                    f.mode(target, originalMode)
                    f.own(target, 0, 0)
                }
            }
        }
    }

    @Test fun rootTakeoverDoesNotAdoptUserModifiedExecutableOrUnmanagedPrivateData() = runBlocking {
        ClientFixture().use { f ->
            f.install()
            f.executable.writeText("user's executable\n")
            f.own(f.executable, 12001, 12002)
            val executableMode = f.mode(f.executable)
            assertFailsWith<IllegalStateException> { f.install() }
            assertEquals("user's executable\n", f.executable.readText())
            assertEquals(12001 to 12002, f.owner(f.executable))
            assertEquals(executableMode, f.mode(f.executable))
        }
        ClientFixture().use { f ->
            f.privateDir.mkdirs()
            f.own(f.privateDir, 12001, 12002)
            val directoryMode = f.mode(f.privateDir)
            val user = File(f.privateDir, "user-data").apply { writeText("kept") }
            f.own(user, 12001, 12002)
            val mode = f.mode(user)
            assertFailsWith<IllegalStateException> { f.install() }
            assertEquals("kept", user.readText())
            assertEquals(12001 to 12002, f.owner(user))
            assertEquals(mode, f.mode(user))
            assertEquals(12001 to 12002, f.owner(f.privateDir))
            assertEquals(directoryMode, f.mode(f.privateDir))
            assertFalse(f.marker.exists())
        }
    }

    @Test fun repairedClientStillRejectsPublicConfigInvalidTokensAndNonLoopbackEndpoints() = runBlocking {
        ClientFixture().use { f ->
            f.install()
            val original = f.config.readText()
            f.mode(f.config, 420)
            assertFailsWith<IllegalStateException> { f.client("list") }
            f.mode(f.config, 384)
            for ((name, value) in listOf("token" to "invalid", "url" to "http://192.0.2.1:1234/v1")) {
                val config = Json.parseToJsonElement(original).jsonObject.toMutableMap()
                config[name] = JsonPrimitive(value)
                f.config.writeText(JsonObject(config).toString())
                f.own(f.config, 12001, 12002)
                assertFailsWith<IllegalStateException> { f.client("list") }
            }
            f.config.writeText(original)
            assertEquals("container", f.client("list").getValue("containerId").jsonPrimitive.content)
        }
    }

    private fun post(config: JsonObject, token: String?, extraHeaders: String = ""): Pair<Int, String> {
        val url = URI(config.getValue("url").jsonPrimitive.content)
        val body = """{"operation":"list","surface":"card"}""".toByteArray()
        return Socket("127.0.0.1", url.port).use { socket ->
            socket.soTimeout = 5000
            val headers = buildString {
                append("POST /v1 HTTP/1.1\r\nHost: 127.0.0.1:${url.port}\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\n")
                if (token != null) append("Authorization: Bearer $token\r\n")
                append(extraHeaders); append("\r\n")
            }
            socket.getOutputStream().apply { write(headers.toByteArray()); write(body); flush() }
            val response = featureReadBounded(socket.getInputStream(), 1_048_576).toString(Charsets.UTF_8)
            response.substringBefore("\r\n").split(' ')[1].toInt() to response
        }
    }
    private companion object {
        // Ownership simulation, not a privileged/chroot test: real filesystem types, links, modes,
        // production JS, processes and authenticated HTTP remain untouched.
        const val OWNERSHIP_PRELOAD = """
const fs = require('node:fs');
const path = require('node:path');
const statePath = process.env.WAWLINK_TEST_IDENTITY;
const original = {};
for (const name of ['lstatSync', 'fstatSync', 'openSync', 'mkdirSync', 'writeFileSync']) original[name] = fs[name].bind(fs);
const state = JSON.parse(fs.readFileSync(statePath, 'utf8'));
process.getuid = () => state.uid;
process.getgid = () => state.gid;
const key = info => String(info.dev) + ':' + String(info.ino);
const owner = info => state.owners[key(info)] || { uid: state.uid, gid: state.gid };
function decorate(info) { return Object.assign(info, owner(info)); }
function save() { original.writeFileSync(statePath, JSON.stringify(state)); }
function created(location, info) {
  const parent = original.lstatSync(path.dirname(String(location)));
  state.owners[key(info)] = { uid: state.uid, gid: (parent.mode & 0o2000) ? owner(parent).gid : state.gid };
  save();
}
fs.lstatSync = (location, options) => decorate(original.lstatSync(location, options));
fs.fstatSync = (fd, options) => decorate(original.fstatSync(fd, options));
fs.mkdirSync = (location, options) => {
  const result = original.mkdirSync(location, options);
  created(location, original.lstatSync(location));
  return result;
};
fs.openSync = (location, flags, mode) => {
  let existed = true;
  try { original.lstatSync(location); } catch (error) { if (error.code !== 'ENOENT') throw error; existed = false; }
  const fd = original.openSync(location, flags, mode);
  if (!existed) created(location, original.fstatSync(fd));
  return fd;
};
fs.fchownSync = (fd, uid, gid) => {
  const info = original.fstatSync(fd), current = owner(info);
  if (state.uid !== 0 && (current.uid !== state.uid || uid !== state.uid || gid !== state.gid)) {
    throw Object.assign(Error('simulated fchown permission denied'), { code: 'EPERM' });
  }
  state.owners[key(info)] = { uid, gid };
  save();
};
"""
    }
}
