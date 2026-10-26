package dev.wawlink.driver

import dev.wawlink.protocol.AgentConfigurationContext
import dev.wawlink.runtime.ContainerConfig
import dev.wawlink.runtime.ContainerInfo
import dev.wawlink.runtime.ContainerRuntime
import dev.wawlink.runtime.LocalContainerRuntime
import dev.wawlink.runtime.RuntimePaths
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*

class OmpAgentManagementRepositoryTest {
    @Test fun configurationIsUsableBeforeModelsAndInvalidModelsRemainRecoverable() = runBlocking {
        fixture {
            val transport = connect()
            transport.respond(transport.request("snapshot"), configuration)
            val catalogue = transport.request("modelsSnapshot")
            val basic = awaitState { !it.loading && it.modelsLoading }
            assertEquals("global", basic.files.single().id)
            assertEquals("review", basic.agents.single().name)
            transport.respond(catalogue, error = "UNAVAILABLE: Invalid native model provider configuration")
            val failed = awaitState { !it.modelsLoading && it.modelsError != null }
            assertNull(failed.error)
            assertEquals("review", failed.agents.single().name)
            assertTrue(failed.modelsError!!.contains("模型服务配置无效"))
            assertTrue(runtimeFailures.isEmpty())

            val reload = async { repository.reload() }
            transport.respond(transport.request("snapshot"), configuration)
            transport.respond(transport.request("modelsSnapshot"), catalogueResult)
            reload.await()
            val repaired = repository.state.value
            assertNull(repaired.modelsError)
            assertEquals("local/chat", repaired.roles.single().model)
            assertEquals("chat", repaired.models.single().id)
            assertEquals("global", repaired.files.single().id)
            assertTrue(uncaught.isEmpty(), uncaught.toString())
        }
    }

    @Test fun processExitCompletesLoadingWithoutLosingConfigurationAndReloadStartsFreshProcess() = runBlocking {
        fixture {
            val first = connect()
            first.respond(first.request("snapshot"), configuration)
            first.request("modelsSnapshot")
            awaitState { !it.loading }
            first.destroy()
            val failed = awaitState { !it.loading && !it.modelsLoading && it.error != null }
            assertEquals("review", failed.agents.single().name)
            assertEquals(1, runtimeFailures.size)
            val reload = async { repository.reload() }
            val replacement = connect()
            replacement.respond(replacement.request("snapshot"), configuration)
            replacement.respond(replacement.request("modelsSnapshot"), catalogueResult)
            reload.await()
            assertNull(repository.state.value.error)
            assertNull(repository.state.value.modelsError)
            assertEquals("local/chat", repository.state.value.roles.single().model)
            assertEquals(1, runtimeFailures.size)
            assertTrue(uncaught.isEmpty(), uncaught.toString())
        }
    }

    @Test fun failedBasicSnapshotTerminatesLoadingAndCanRetryOnSameProcess() = runBlocking {
        fixture {
            val transport = connect()
            transport.respond(transport.request("snapshot"), error = "UNAVAILABLE: Configuration must be a mapping")
            val failed = awaitState { !it.loading && it.error != null }
            assertFalse(failed.modelsLoading)
            val reload = async { repository.reload() }
            transport.respond(transport.request("snapshot"), configuration)
            transport.respond(transport.request("modelsSnapshot"), catalogueResult)
            reload.await()
            assertNull(repository.state.value.error)
            assertEquals("review", repository.state.value.agents.single().name)
            assertTrue(runtimeFailures.isEmpty())
            assertTrue(uncaught.isEmpty(), uncaught.toString())
        }
    }

    @Test fun incompleteCatalogueDoesNotReplacePreviouslyLoadedModels() = runBlocking {
        fixture {
            val transport = connect()
            transport.respond(transport.request("snapshot"), configuration)
            transport.respond(transport.request("modelsSnapshot"), catalogueResult)
            awaitState { !it.modelsLoading }
            val reload = async { repository.reload() }
            transport.respond(transport.request("snapshot"), configuration)
            transport.respond(transport.request("modelsSnapshot"), buildJsonObject { put("modelsCount", 1) })
            reload.await()
            val incomplete = repository.state.value
            assertFalse(incomplete.modelsLoading)
            assertTrue(incomplete.modelsError!!.contains("传输不完整"))
            assertEquals("local/chat", incomplete.models.single().key)
            assertEquals("review", incomplete.agents.single().name)
            assertEquals(1, runtimeFailures.size)
            assertTrue(uncaught.isEmpty(), uncaught.toString())
        }
    }

    @Test fun providerStateUsesNativeFlagsAndRetainsThemOnCatalogueFailure() = runBlocking {
        fixture {
            val transport = connect()
            transport.respond(transport.request("snapshot"), configuration)
            val providers = Json.parseToJsonElement("""[{"id":"local","builtin":true,"enabled":true,"canFetchModels":true,"baseUrl":"http://127.0.0.1:8080/v1","api":"openai-responses"},{"id":"openai","builtin":true,"enabled":false,"oauth":false},{"id":"unconfigured"}]""")
            transport.respond(transport.request("modelsSnapshot"), JsonObject(catalogueResult.jsonObject + ("providers" to providers)))
            val loaded = awaitState { !it.modelsLoading }
            assertTrue(loaded.providers.first { it.id == "local" }.enabled)
            assertEquals("http://127.0.0.1:8080/v1", loaded.providers.first().baseUrl)
            assertFalse(loaded.providers.first { it.id == "openai" }.enabled)
            assertFalse(loaded.providers.first { it.id == "openai" }.oauth)
            assertFalse(loaded.providers.first { it.id == "unconfigured" }.enabled)
            val reload = async { repository.reload() }
            transport.respond(transport.request("snapshot"), configuration)
            transport.respond(transport.request("modelsSnapshot"), error = "UNAVAILABLE: models.yml: providers.vendor.api: expected native API")
            reload.await()
            assertEquals(loaded.providers, repository.state.value.providers)
            assertTrue(repository.state.value.modelsError!!.contains("providers.vendor.api"))
        }
    }

    @Test fun providerFetchIsDirectedAndFailureDoesNotReloadOrReplaceCatalogue() = runBlocking {
        fixture {
            val transport = connect()
            transport.respond(transport.request("snapshot"), configuration)
            transport.respond(transport.request("modelsSnapshot"), catalogueResult)
            val initial = awaitState { !it.modelsLoading }
            val fetch = async { repository.fetchProviderModels("local") }
            val request = transport.request("fetchProviderModels")
            assertEquals("local", request["args"]!!.jsonObject["provider"]!!.jsonPrimitive.content)
            transport.respond(request, JsonPrimitive(true))
            transport.respond(transport.request("snapshot"), configuration)
            transport.respond(transport.request("modelsSnapshot"), catalogueResult)
            fetch.await()
            val failed = async { runCatching { repository.fetchProviderModels("local") } }
            transport.respond(transport.request("fetchProviderModels"), error = "UNAVAILABLE: Provider local: model discovery unauthenticated; HTTP 401")
            assertTrue(failed.await().exceptionOrNull()!!.message!!.contains("HTTP 401"))
            assertEquals(initial.models, repository.state.value.models)
            assertTrue(runtimeFailures.isEmpty())
            transport.assertNoRequest()
        }
    }

    @Test fun rejectedDocumentKeepsOriginalBytesAndNativeFieldDiagnostic() = runBlocking {
        fixture {
            val transport = connect()
            transport.respond(transport.request("snapshot"), configurationWithModels)
            transport.respond(transport.request("modelsSnapshot"), catalogueResult)
            awaitState { !it.modelsLoading }
            val file = file("models.yml").apply { writeText("# retained document\nproviders: {}\n") }
            val original = file.readText()
            val source = repository.readDocument("models")
            val save = async { runCatching { repository.saveDocument(source, "providers:\n  vendor:\n    api: not-a-native-api\n") } }
            val validation = transport.request("validateDocument")
            assertEquals("/root/.omp/agent/models.yml", validation["args"]!!.jsonObject["path"]!!.jsonPrimitive.content)
            val message = "UNAVAILABLE: /root/.omp/agent/models.yml: providers.vendor.api: expected native API"
            transport.respond(validation, error = message)
            assertEquals(message, save.await().exceptionOrNull()!!.message)
            assertEquals(original, file.readText())
            transport.assertNoRequest()
            assertEquals("review", repository.state.value.agents.single().name)
            assertTrue(runtimeFailures.isEmpty())
        }
    }

    @Test fun legacyMigrationValidatesBeforeReplacingOriginalFile() = runBlocking {
        fixture {
            val legacy = "# legacy model\nproviders: {\n  vendor: {\n    api: openai-responses,\n    models: [\n      {id: legacy-model}\n    ]\n  }\n}\n"
            val file = file("models.yml").apply { writeText(legacy) }
            val transport = connect()
            transport.respond(transport.request("snapshot"), configurationWithModels)
            val validation = transport.request("validateDocument")
            assertEquals("models", validation["args"]!!.jsonObject["id"]!!.jsonPrimitive.content)
            assertEquals(legacy, file.readText())
            transport.respond(validation, error = "UNAVAILABLE: models.yml: providers.vendor: baseUrl is required")
            val failed = awaitState { !it.modelsLoading && it.modelsError != null }
            assertTrue(failed.modelsError!!.contains("baseUrl"))
            assertEquals(legacy, file.readText())
            transport.assertNoRequest()
        }
    }

    @Test fun nativeExitBeforeInitReportsOnceAndReloadCanRecover() = runBlocking {
        fixture {
            val first = launchTransport()
            first.request("init")
            first.destroy()
            awaitState { !it.loading && it.error != null }
            assertEquals(1, runtimeFailures.size)
            val reload = async { repository.reload() }
            val replacement = connect()
            replacement.respond(replacement.request("snapshot"), configuration)
            replacement.respond(replacement.request("modelsSnapshot"), catalogueResult)
            reload.await()
            assertNull(repository.state.value.error)
            assertEquals(1, runtimeFailures.size)
        }
    }

    @Test fun malformedNativeOutputReportsWithoutLeakingPayloadAndRetainsDefinitions() = runBlocking {
        fixture {
            val transport = connect()
            transport.respond(transport.request("snapshot"), configuration)
            transport.request("modelsSnapshot")
            transport.emitRaw("not-json sk-secret-provider-body")
            val failed = awaitState { !it.modelsLoading && it.error != null }
            assertEquals("review", failed.agents.single().name)
            assertEquals(1, runtimeFailures.size)
            assertFalse(runtimeFailures.single().contains("sk-secret"))
            assertFalse(failed.error!!.contains("sk-secret"))
        }
    }

    @Test fun brokenRpcWriteReportsOnceAndReloadUsesAnotherNativeProcess() = runBlocking {
        fixture {
            val transport = connect()
            transport.respond(transport.request("snapshot"), configuration)
            transport.respond(transport.request("modelsSnapshot"), catalogueResult)
            awaitState { !it.modelsLoading }
            transport.failWrites = true
            assertTrue(runCatching { repository.generateAgent("描述") }.isFailure)
            assertEquals(1, runtimeFailures.size)
            assertEquals("review", repository.state.value.agents.single().name)
            val reload = async { repository.reload() }
            val replacement = connect()
            replacement.respond(replacement.request("snapshot"), configuration)
            replacement.respond(replacement.request("modelsSnapshot"), catalogueResult)
            reload.await()
            assertEquals("chat", repository.state.value.models.single().id)
            assertEquals(1, runtimeFailures.size)
        }
    }

    @Test fun repairParkStopsPendingCatalogueWithoutFaultAndKeepsRepositoryReloadable() = runBlocking {
        fixture {
            val transport = connect()
            transport.respond(transport.request("snapshot"), configuration)
            transport.request("modelsSnapshot")
            awaitState { !it.loading && it.modelsLoading }
            repository.park()
            assertFalse(transport.isAlive)
            assertTrue(runtimeFailures.isEmpty())
            assertEquals("review", repository.state.value.agents.single().name)
            assertNull(repository.state.value.error)
            val reload = async { repository.reload() }
            val replacement = connect()
            replacement.respond(replacement.request("snapshot"), configuration)
            replacement.respond(replacement.request("modelsSnapshot"), catalogueResult)
            reload.await()
            assertNull(repository.state.value.error)
            assertTrue(runtimeFailures.isEmpty())
        }
    }

    @Test fun cancellingNativeOperationAndClosingPendingRequestsDoNotReportInstallationFaults() = runBlocking {
        fixture {
            val transport = connect()
            transport.respond(transport.request("snapshot"), configuration)
            transport.respond(transport.request("modelsSnapshot"), catalogueResult)
            awaitState { !it.modelsLoading }
            val generate = async { repository.generateAgent("取消生成") }
            transport.request("generateAgent")
            generate.cancelAndJoin()
            assertFalse(transport.isAlive)
            assertTrue(runtimeFailures.isEmpty())
            val reload = async { repository.reload() }
            val replacement = connect()
            replacement.request("snapshot")
            repository.close()
            reload.join()
            assertFalse(replacement.isAlive)
            assertTrue(runtimeFailures.isEmpty())
        }
    }

    @Test fun callerTimeoutCancelsNativeWorkWithoutReportingAnRpcTimeout() = runBlocking {
        fixture {
            val transport = connect()
            transport.respond(transport.request("snapshot"), configuration)
            transport.respond(transport.request("modelsSnapshot"), catalogueResult)
            awaitState { !it.modelsLoading }
            val generate = async { withTimeout(250) { repository.generateAgent("限时取消") } }
            transport.request("generateAgent")
            assertFailsWith<TimeoutCancellationException> { generate.await() }
            assertFalse(transport.isAlive)
            assertTrue(runtimeFailures.isEmpty())
            assertEquals("review", repository.state.value.agents.single().name)
        }
    }

    @Test fun containerStopDuringNativeLaunchDiscardsLateChildAndReloadCanRetryAfterRestart() = runBlocking {
        fixture {
            opening.receive()
            stopContainer()
            restartContainer()
            val stale = launchTransport()
            withTimeout(5_000) { stale.destroyed.await() }
            awaitState { !it.loading && !it.modelsLoading }
            assertTrue(runtimeFailures.isEmpty())
            assertNull(repository.state.value.error)

            val retry = async { repository.reload() }
            val replacement = connect()
            replacement.respond(replacement.request("snapshot"), configuration)
            replacement.respond(replacement.request("modelsSnapshot"), catalogueResult)
            retry.await()
            assertEquals("chat", repository.state.value.models.single().id)
            assertNull(repository.state.value.error)
            replacement.destroy()
            awaitState { it.error != null }
            assertEquals(1, runtimeFailures.size)
            assertEquals(listOf(1L), failureGenerations)
            assertTrue(uncaught.isEmpty(), uncaught.toString())
        }
    }

    @Test fun containerStopBeforeInitCompletesPendingRequestsWithoutAnError() = runBlocking {
        fixture {
            val transport = launchTransport()
            transport.request("init")
            stopContainer()
            val stopped = awaitState { !it.loading && !it.modelsLoading }
            withTimeout(5_000) { transport.destroyed.await() }
            assertNull(stopped.error)
            assertNull(stopped.modelsError)
            assertTrue(runtimeFailures.isEmpty())
            assertTrue(uncaught.isEmpty(), uncaught.toString())
        }
    }

    @Test fun containerStopDuringCatalogueKeepsDefinitionsAndRetryRemainsAvailable() = runBlocking {
        fixture {
            val transport = connect()
            transport.respond(transport.request("snapshot"), configuration)
            transport.request("modelsSnapshot")
            awaitState { !it.loading }
            stopContainer()
            restartContainer()
            val stopped = awaitState { !it.modelsLoading }
            withTimeout(5_000) { transport.destroyed.await() }
            assertEquals("review", stopped.agents.single().name)
            assertNull(stopped.error)
            assertNull(stopped.modelsError)
            assertTrue(runtimeFailures.isEmpty())
            val retry = async { repository.reload() }
            val replacement = connect()
            replacement.respond(replacement.request("snapshot"), configuration)
            replacement.respond(replacement.request("modelsSnapshot"), catalogueResult)
            retry.await()
            assertEquals("chat", repository.state.value.models.single().id)
            assertTrue(runtimeFailures.isEmpty())
            assertTrue(uncaught.isEmpty(), uncaught.toString())
        }
    }

    @Test fun containerStopCancelsExternalManagementAndResourceRequestsWithoutRepairErrors() = runBlocking {
        fixture {
            val transport = connect()
            transport.respond(transport.request("snapshot"), configuration)
            transport.respond(transport.request("modelsSnapshot"), catalogueResult)
            awaitState { !it.modelsLoading }
            val generate = async { runCatching { repository.generateAgent("停止生成") } }
            transport.request("generateAgent")
            val resource = async { runCatching { repository.resources.request("skills.snapshot", emptyMap()) } }
            transport.request("skills.snapshot")
            stopContainer()
            assertTrue(withTimeout(5_000) { generate.await() }.exceptionOrNull() is CancellationException)
            assertTrue(withTimeout(5_000) { resource.await() }.exceptionOrNull() is CancellationException)
            assertTrue(runtimeFailures.isEmpty())
            assertNull(repository.state.value.error)
            assertNull(repository.state.value.modelsError)

            restartContainer()
            val retry = async { repository.reload() }
            val replacement = connect()
            replacement.respond(replacement.request("snapshot"), configuration)
            replacement.respond(replacement.request("modelsSnapshot"), catalogueResult)
            retry.await()
            assertEquals("chat", repository.state.value.models.single().id)
            assertTrue(runtimeFailures.isEmpty())
            assertTrue(uncaught.isEmpty(), uncaught.toString())
        }
    }

    @Test fun managementFramesRespectLimitsAcrossReadsAndPreserveUtf8AndFinalFrame() {
        val text = "汉字🙂".repeat(1500)
        val source = "{\"text\":${JsonPrimitive(text)}}\n{\"id\":2}"
        val frames = mutableListOf<JsonObject>()
        readManagementFrames(ByteArrayInputStream(source.toByteArray()), consume = frames::add)
        assertEquals(text, frames[0]["text"]!!.jsonPrimitive.content)
        assertEquals(2, frames[1]["id"]!!.jsonPrimitive.int)
        val atLimit = "{\"id\":1}"
        var delivered: JsonObject? = null
        readManagementFrames(ByteArrayInputStream((atLimit + "\n").toByteArray()), atLimit.length) { delivered = it }
        assertEquals(1, delivered!!["id"]!!.jsonPrimitive.int)
        assertFailsWith<IOException> {
            readManagementFrames(ByteArrayInputStream((atLimit + " ").toByteArray()), atLimit.length) { fail("Oversize frame was delivered") }
        }
    }

    @Test fun malformedManagementOutputFailsInsteadOfLeavingRequestsWaiting() {
        assertFailsWith<IOException> {
            readManagementFrames(ByteArrayInputStream("not-json\n".toByteArray())) { fail("Malformed frame was delivered") }
        }
    }

    private suspend fun fixture(block: suspend Fixture.() -> Unit) {
        val fixture = Fixture()
        try { fixture.block() } finally { fixture.close() }
    }

    private class Fixture : AutoCloseable {
        private val directory = Files.createTempDirectory("wawlink-management-").toFile()
        private val local = LocalContainerRuntime(RuntimePaths(directory.resolve("runtime")))
        private val processes = Channel<Transport>(Channel.UNLIMITED)
        private val transports = CopyOnWriteArrayList<Transport>()
        val opening = Channel<Unit>(Channel.UNLIMITED)
        private val containerState = MutableStateFlow(listOf(ContainerInfo(ContainerConfig("fixture", "验证", directory.path), running = true)))
        val failureGenerations = CopyOnWriteArrayList<Long>()
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val runtimeFailures = CopyOnWriteArrayList<String>()
        private val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, failure -> uncaught += failure })
        private val runtime = object : ContainerRuntime by local {
            override val containers = containerState
            override suspend fun openProcess(id: String, arguments: List<String>, cwd: String, environment: Map<String, String>): Process {
                opening.send(Unit)
                return processes.receive()
            }
        }
        val repository = OmpAgentManagementRepository(
            runtime, AgentConfigurationContext("fixture", "验证"),
            { directory.resolve("agent").apply { mkdirs() } },
            { guest, _ -> directory.resolve("agent").resolve(guest.removePrefix("/root/.omp/agent/")) },
            owner, { _, _ -> }, { _, _ -> }, { emptyMap() }, {}, { generation, message -> failureGenerations += generation; runtimeFailures += message },
        )
        fun stopContainer() {
            containerState.value = containerState.value.map { it.copy(running = false, stopGeneration = it.stopGeneration + 1) }
        }
        fun restartContainer() {
            containerState.value = containerState.value.map { it.copy(running = true) }
        }
        suspend fun launchTransport(): Transport = Transport().also { transport ->
            transports += transport
            processes.send(transport)
        }
        suspend fun connect(): Transport {
            val transport = launchTransport()
            transport.respond(transport.request("init"), JsonPrimitive(true))
            return transport
        }
        fun file(name: String) = directory.resolve("agent").apply { mkdirs() }.resolve(name)
        suspend fun awaitState(predicate: (dev.wawlink.protocol.AgentManagementState) -> Boolean) = withTimeout(5_000) { repository.state.first(predicate) }
        override fun close() {
            repository.close()
            owner.cancel()
            transports.forEach(Transport::destroy)
            processes.close()
            local.close()
            directory.deleteRecursively()
        }
    }

    private class Transport : Process() {
        private val stdout = PipedInputStream(65_536)
        private val events = PipedOutputStream(stdout)
        private val stderr = PipedInputStream(4096)
        private val errors = PipedOutputStream(stderr)
        private val exited = CountDownLatch(1)
        val destroyed = CompletableDeferred<Unit>()
        private val requests = Channel<JsonObject>(Channel.UNLIMITED)
        @Volatile var failWrites = false
        private val input = object : OutputStream() {
            private val buffer = ByteArrayOutputStream()
            override fun write(value: Int) {
                if (failWrites) throw IOException("Native pipe is unavailable")
                if (value != '\n'.code) { buffer.write(value); return }
                val frame = Json.parseToJsonElement(buffer.toString(Charsets.UTF_8)).jsonObject
                buffer.reset()
                check(requests.trySend(frame).isSuccess)
            }
        }
        suspend fun request(operation: String): JsonObject = withTimeout(5_000) {
            requests.receive().also { assertEquals(operation, it["op"]!!.jsonPrimitive.content) }
        }
        fun assertNoRequest() { assertTrue(requests.tryReceive().isFailure, "Unexpected management request after failure") }
        @Synchronized fun respond(request: JsonObject, result: JsonElement = JsonNull, error: String? = null) {
            val models = (result as? JsonObject)?.get("models") as? JsonArray
            val body = if (request["op"]!!.jsonPrimitive.content == "modelsSnapshot" && error == null && models != null) {
                emit(buildJsonObject { put("event", "models_page"); put("id", request["id"]!!); put("models", models) })
                JsonObject(result.jsonObject - "models" + ("modelsCount" to JsonPrimitive(models.size)))
            } else result
            emit(buildJsonObject {
                put("id", request["id"]!!)
                if (error == null) put("result", body) else put("error", error)
            })
        }
        @Synchronized fun emitRaw(value: String) {
            events.write((value + "\n").toByteArray())
            events.flush()
        }
        private fun emit(frame: JsonObject) {
            events.write((frame.toString() + "\n").toByteArray())
            events.flush()
        }
        override fun getOutputStream(): OutputStream = input
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = stderr
        override fun waitFor(): Int { exited.await(); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = exited.await(timeout, unit)
        override fun exitValue(): Int { check(!isAlive); return 0 }
        override fun isAlive(): Boolean = exited.count > 0
        @Synchronized override fun destroy() {
            if (!isAlive) return
            exited.countDown()
            destroyed.complete(Unit)
            events.close()
            errors.close()
        }
    }

    companion object {
        private val configuration = Json.parseToJsonElement("""{"files":[{"id":"global","path":"/root/.omp/agent/config.yml"}],"agents":[{"name":"review","description":"审查","source":"user","path":"/root/.omp/agent/agents/review.md"}]}""")
        private val configurationWithModels = JsonObject(configuration.jsonObject + ("files" to Json.parseToJsonElement("""[{"id":"global","path":"/root/.omp/agent/config.yml"},{"id":"models","path":"/root/.omp/agent/models.yml"}]""")))
        private val catalogueResult = Json.parseToJsonElement("""{"models":[{"provider":"local","id":"chat","available":true}],"roles":[{"id":"default","name":"Default","model":"local/chat","providerId":"local","modelId":"chat"}]}""")
    }
}
