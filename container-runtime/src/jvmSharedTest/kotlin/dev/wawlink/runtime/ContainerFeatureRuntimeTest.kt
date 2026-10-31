package dev.wawlink.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs

class ContainerFeatureRuntimeTest {
    private fun feature(url: String? = null) = ContainerFeature("demo", "Demo", "Description", "terminal", "/home/test/.wawlink/features/demo", cardUrl = url)
    private fun manifest(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test fun closingGatewayClosesUpstreamWhileAwaitingResponseHeaders() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5000
            ContainerFeatureGateway(server.localPort, "token", "", { null }).use { gateway ->
                val url = URI(gateway.url)
                Socket("127.0.0.1", url.port).use { client ->
                    client.soTimeout = 5000
                    client.getOutputStream().apply {
                        write("GET ${url.rawPath}?${url.rawQuery} HTTP/1.1\r\nHost: 127.0.0.1:${url.port}\r\n\r\n".toByteArray())
                        flush()
                    }
                    server.accept().use { upstream ->
                        upstream.soTimeout = 5000
                        val request = upstream.getInputStream().bufferedReader()
                        while (true) {
                            val line = request.readLine() ?: error("Gateway closed before sending the request")
                            if (line.isEmpty()) break
                        }
                        gateway.close()
                        assertEquals(-1, request.read(), "Shutdown must close upstream I/O before response headers arrive")
                        assertEquals(-1, client.getInputStream().read(), "Shutdown must also close the client")
                    }
                }
            }
        }
    }

    @Test fun gatewayRejectsUnscheduledClientsWithFailureAndClosesTheirSockets() {
        ContainerFeatureGateway(1, "token", "", { null }).use { gateway ->
            val pool = ContainerFeatureGateway::class.java.getDeclaredField("pool").apply { isAccessible = true }.get(gateway) as ExecutorService
            pool.shutdown() // Keep the acceptor running while deterministically rejecting its next client task.
            val url = URI(gateway.url)
            Socket("127.0.0.1", url.port).use { client ->
                client.soTimeout = 5000
                val response = featureReadBounded(client.getInputStream(), 4096).toString(Charsets.UTF_8)
                assertTrue(response.startsWith("HTTP/1.1 502 "), response)
                assertTrue(response.contains("功能服务连接失败"), response)
            }
        }
    }

    @Test fun lifecycleAndDynamicCardSchemaRemainExplicit() {
        assertEquals(ContainerFeatureLifecycle.PAGE, parseFeatureLifecycle(manifest("{}")))
        assertEquals(ContainerFeatureLifecycle.CONTAINER, parseFeatureLifecycle(manifest("""{"lifecycle":"container"}""")))
        for (invalid in listOf("null", "false", "1", "\"Container\"")) {
            assertFails { parseFeatureLifecycle(manifest("{\"lifecycle\":$invalid}")) }
        }
        assertEquals("/card", parseFeatureCardUrl(manifest("""{"cardUrl":"/card"}""")))
        assertFails { parseFeatureCardUrl(manifest("""{"card":"card.html","cardUrl":"/card"}""")) }
        for (invalid in listOf("null", "false", "1", "\"\"")) {
            assertFails { parseFeatureCardUrl(manifest("{\"cardUrl\":$invalid}")) }
        }
    }

    @Test fun managedCardUrlsPreserveRawQueryFragmentAndBootstrapBoundaries() {
        val session = ContainerFeatureSession("container", "demo", "http://127.0.0.1:4567/?__wawlink_token=secret", "secret")
        assertNull(resolveFeatureCardUrl(feature(), session))
        assertNull(resolveFeatureCardUrl(feature("/card"), null))
        assertEquals("http://127.0.0.1:4567/card?__wawlink_token=secret", resolveFeatureCardUrl(feature("/card"), session))
        assertEquals("http://127.0.0.1:4567/card/a%20b?mode=a%26b&empty=&__wawlink_token=secret#section%23two",
            resolveFeatureCardUrl(feature("/card/a%20b?mode=a%26b&empty=#section%23two"), session))
        assertEquals("http://127.0.0.1:4567/card?__wawlink_token=secret#top", resolveFeatureCardUrl(feature("/card?#top"), session))
        for (url in listOf("http://localhost:8080/card?q=a%26b#top", "http://127.0.0.1:1/", "http://localhost:65535")) {
            assertEquals(url, resolveFeatureCardUrl(feature(url), null))
            assertEquals(url, resolveFeatureCardUrl(feature(url), session))
        }
    }

    @Test fun cardUrlsRejectUntrustedOriginsEncodingAndReservedTokenNames() {
        for (url in listOf(
            "card", "//localhost:8080/card", "/%2fhost/card", "https://localhost:8080/card", "file:///card", "content://card",
            "http://127.0.0.1/card", "http://localhost:0/card", "http://localhost:65536/card", "http://127.0.0.2:8080/card",
            "http://localhost.example:8080/card", "http://user@localhost:8080/card", "http://localhost:8080@evil.test/card",
            "/card?__wawlink_token=other", "/card?x=1&%5f%5fwawlink_token=other", "http://localhost:8080/card?__wawlink_token=other",
            "/card%", "/card%GG", "/card%ff", "/card%00", "/card%0a", "/card%5cother", "/card\\other",
        )) assertFails(url) { validateFeatureCardUrl(url) }
        validateFeatureCardUrl("/card?value=__wawlink_token%3Ddata#outside?__wawlink_token=data")
    }

    @Test fun runningEpochStartsOnlyContainerFeaturesAndDoesNotRetryOnMetadata() = runBlocking {
        Fixture(mapOf("auto" to "container", "broken" to "container", "page" to "page", "manual" to "manual"), failed = setOf("broken")).use { fixture ->
            ContainerFeatureService(fixture.runtime).use { service ->
                val first = fixture.next("auto")
                state(service, "auto", ContainerFeatureStatus.RUNNING)
                state(service, "broken", ContainerFeatureStatus.FAILED)
                assertEquals(1, fixture.scans.get())
                assertEquals(0, fixture.cardReads.get(), "cardUrl discovery must not snapshot card.html")
                assertEquals(setOf("auto", "broken"), fixture.started.keys)
                withTimeout(5000) { service.errors.first { it["container"].orEmpty().any { error -> error.startsWith("broken：") } } }
                service.leave("container", "auto")
                assertTrue(first.isAlive, "leaving Functions must retain container lifecycle")
                val token = service.states.value.getValue("container/auto").session!!.token
                service.stop("container", "auto")
                state(service, "auto", ContainerFeatureStatus.STOPPED)
                assertEquals(1, first.controlStops.get())
                fixture.metadataBarrier()
                assertEquals(1, fixture.started.getValue("auto").get())
                assertEquals(1, fixture.started.getValue("broken").get())
                assertEquals(1, fixture.scans.get())

                // A different active feature makes the stopped transition observable without sleeps.
                val page = service.catalog("container").features.single { it.id == "page" }
                service.start("container", page)
                fixture.running(false)
                state(service, "page", ContainerFeatureStatus.STOPPED)
                fixture.running(true)
                val second = fixture.next("auto")
                state(service, "auto", ContainerFeatureStatus.RUNNING)
                assertTrue(first !== second)
                assertNotEquals(token, service.states.value.getValue("container/auto").session!!.token)
                assertEquals(2, fixture.started.getValue("auto").get())
                service.close()
                assertFalse(second.isAlive)
                assertEquals(1, second.controlStops.get())
                assertEquals(0, second.destroyCalls.get(), "normal teardown must use guest cleanup, not destroy the wrapper")
            }
        }
    }

    @Test fun conflatedRunningGenerationStartsANewInstanceAndRetiresTheOldOne() = runBlocking {
        Fixture(mapOf("auto" to "container")).use { fixture ->
            ContainerFeatureService(fixture.runtime).use { service ->
                val first = fixture.next("auto")
                val firstSession = state(service, "auto", ContainerFeatureStatus.RUNNING).getValue("container/auto").session!!
                // No false snapshot is delivered: this is the actual restart/conflation boundary.
                fixture.containers.value = fixture.containers.value.map { it.copy(runGeneration = it.runGeneration + 1) }
                val second = fixture.next("auto")
                withTimeout(5000) { service.states.first { it["container/auto"]?.session?.token?.let { token -> token != firstSession.token } == true } }
                assertTrue(first !== second)
                assertEquals(2, fixture.scans.get())
                assertEquals(2, fixture.started.getValue("auto").get())
                service.close()
                assertFalse(first.isAlive)
                assertFalse(second.isAlive)
                assertEquals(1, first.controlStops.get())
                assertEquals(1, second.controlStops.get())
            }
        }
    }

    @Test fun stoppingDuringReadinessReapsTheStartingGroupAndDoesNotBlockOtherFeatures() = runBlocking {
        Fixture(mapOf("waiting" to "container", "ready" to "container"), unready = setOf("waiting")).use { fixture ->
            ContainerFeatureService(fixture.runtime).use { service ->
                val waiting = fixture.next("waiting")
                val ready = fixture.next("ready")
                state(service, "ready", ContainerFeatureStatus.RUNNING)
                service.stop("container", "waiting")
                state(service, "waiting", ContainerFeatureStatus.STOPPED)
                assertFalse(waiting.isAlive)
                assertEquals(1, waiting.controlStops.get())
                assertEquals(0, waiting.destroyCalls.get())
                assertTrue(ready.isAlive)
                fixture.metadataBarrier()
                assertEquals(1, fixture.started.getValue("waiting").get())
                fixture.containers.value = emptyList()
                state(service, "ready", ContainerFeatureStatus.STOPPED)
                assertFalse(ready.isAlive, "container deletion must reap active groups")
            }
        }
    }

    @Test fun leavingOnlyStopsPageLifecycleFeatures(): Unit = runBlocking {
        Fixture(mapOf("page" to "page", "manual" to "manual")).use { fixture ->
            ContainerFeatureService(fixture.runtime).use { service ->
                val catalog = service.catalog("container")
                val page = catalog.features.single { it.id == "page" }
                val manual = catalog.features.single { it.id == "manual" }
                service.start("container", page)
                service.start("container", manual)
                val pageProcess = fixture.next("page")
                val manualProcess = fixture.next("manual")
                service.leave("container", "page")
                service.leave("container", "manual")
                assertFalse(pageProcess.isAlive)
                assertTrue(manualProcess.isAlive)
                state(service, "page", ContainerFeatureStatus.STOPPED)
                state(service, "manual", ContainerFeatureStatus.RUNNING)
            }
        }
    }

    @Test fun closeReapsReadinessAndActiveGroupsAndRunsStopHooksWithTheirToken() = runBlocking {
        Fixture(mapOf("waiting" to "container", "ready" to "container"), unready = setOf("waiting")).use { fixture ->
            val service = ContainerFeatureService(fixture.runtime)
            try {
                val waiting = fixture.next("waiting")
                val ready = fixture.next("ready")
                state(service, "ready", ContainerFeatureStatus.RUNNING)
                val token = service.states.value.getValue("container/ready").session!!.token
                service.close()
                assertFalse(waiting.isAlive)
                assertFalse(ready.isAlive)
                assertEquals(1, waiting.controlStops.get())
                assertEquals(1, ready.controlStops.get())
                assertEquals(token, fixture.stopTokens["ready"])
                assertTrue(fixture.stopTokens.getValue("waiting").isNotEmpty())
                assertEquals(0, fixture.autoOpenCalls.get(), "automatic discovery, launch and cleanup must use running-only admission")
            } finally { service.close() }
        }
    }

    @Test fun stopBetweenAutostartCheckAndAtomicLaunchCannotReopenContainer() = runBlocking {
        Fixture(mapOf("auto" to "container"), pauseServiceLaunch = true).use { fixture ->
            ContainerFeatureService(fixture.runtime).use { service ->
                try {
                    withTimeout(5000) { fixture.admission.receive() }
                    fixture.running(false)
                } finally { fixture.releaseAdmission.countDown() }
                state(service, "auto", ContainerFeatureStatus.STOPPED)
                assertTrue(fixture.started.isEmpty())
                assertEquals(0, fixture.autoOpenCalls.get())
                assertFalse(fixture.containers.value.single().running)
                assertNull(service.states.value.getValue("container/auto").session)
            }
        }
    }

    @Test fun discoveryFailuresAreVisibleAndNeverRetriedByMetadata() = runBlocking {
        Fixture(emptyMap(), discoveryFailure = true).use { fixture ->
            ContainerFeatureService(fixture.runtime).use { service ->
                withTimeout(5000) { service.errors.first { it["container"].orEmpty().any { error -> error.contains("discovery denied") } } }
                fixture.metadataBarrier()
                assertEquals(1, fixture.scans.get())
                assertTrue(fixture.started.isEmpty())
            }
        }
    }

    @Test fun cancelledCatalogDoesNotPublishErrorsOrReopenContainer() = runBlocking {
        Fixture(emptyMap()).use { fixture ->
            ContainerFeatureService(fixture.runtime).use { service ->
                withTimeout(5000) { service.errors.first { "container" in it } }
                fixture.nextScan()
                fixture.pauseNextScan.set(true)
                fixture.failNextScan.set(true)
                val catalog = async(Dispatchers.IO) { service.catalog("container") }
                val scan = fixture.nextScan()
                catalog.cancelAndJoin()
                assertFalse(scan.isAlive)
                assertTrue(service.errors.value["container"].orEmpty().isEmpty())
                assertEquals(0, fixture.autoOpenCalls.get())
            }
        }
    }

    @Test fun cancellingPageStartDuringReadinessReapsWithoutFailedState() = runBlocking {
        Fixture(mapOf("waiting" to "page"), unready = setOf("waiting")).use { fixture ->
            ContainerFeatureService(fixture.runtime).use { service ->
                val feature = service.catalog("container").features.single()
                val starting = async(Dispatchers.IO) { service.start("container", feature) }
                val process = fixture.next("waiting")
                starting.cancelAndJoin()
                val stopped = state(service, "waiting", ContainerFeatureStatus.STOPPED).getValue("container/waiting")
                assertNull(stopped.error)
                assertNull(stopped.session)
                assertFalse(process.isAlive)
                assertEquals(1, process.controlStops.get())
                assertTrue(service.errors.value["container"].orEmpty().isEmpty())
            }
        }
    }

    @Test fun rapidRestartInvalidatesInFlightCatalogAndReadinessWithoutErrors() = runBlocking {
        Fixture(mapOf("waiting" to "page"), unready = setOf("waiting")).use { fixture ->
            ContainerFeatureService(fixture.runtime).use { service ->
                withTimeout(5000) { service.errors.first { "container" in it } }
                fixture.nextScan()
                val feature = service.catalog("container").features.single()
                fixture.nextScan()
                val starting = async(Dispatchers.IO) { runCatching { service.start("container", feature) } }
                val process = fixture.next("waiting")
                fixture.nextScan()
                fixture.pauseNextScan.set(true)
                fixture.failNextScan.set(true)
                val catalog = async(Dispatchers.IO) { runCatching { service.catalog("container") } }
                val scan = fixture.nextScan()
                fixture.restart()
                scan.stop()
                assertIs<CancellationException>(catalog.await().exceptionOrNull())
                assertIs<CancellationException>(starting.await().exceptionOrNull())
                state(service, "waiting", ContainerFeatureStatus.STOPPED)
                fixture.metadataBarrier()
                assertFalse(process.isAlive)
                assertNull(service.states.value.getValue("container/waiting").error)
                assertTrue(service.errors.value["container"].orEmpty().isEmpty())
                assertEquals(0, fixture.autoOpenCalls.get())
                assertTrue(fixture.containers.value.first { it.config.id == "container" }.running)
            }
        }
    }

    @Test fun realCatalogAndReadinessFailuresRemainVisible() = runBlocking {
        Fixture(mapOf("broken" to "page"), failed = setOf("broken")).use { fixture ->
            ContainerFeatureService(fixture.runtime).use { service ->
                val feature = service.catalog("container").features.single()
                val failure = runCatching { service.start("container", feature) }.exceptionOrNull()
                assertIs<IllegalStateException>(failure)
                val failed = state(service, "broken", ContainerFeatureStatus.FAILED).getValue("container/broken")
                assertTrue(failed.error.orEmpty().contains("启动进程已退出"))
                fixture.failNextScan.set(true)
                val catalogFailure = runCatching { service.catalog("container") }.exceptionOrNull()
                assertIs<IllegalStateException>(catalogFailure)
                assertTrue(service.errors.value["container"].orEmpty().any { it.contains("discovery denied") })
            }
        }
    }

    @Test fun containerStopDuringStopHookDoesNotPublishFailedState() = runBlocking {
        Fixture(mapOf("ready" to "container")).use { fixture ->
            ContainerFeatureService(fixture.runtime).use { service ->
                fixture.next("ready")
                state(service, "ready", ContainerFeatureStatus.RUNNING)
                fixture.pauseNextStopHook.set(true)
                val stopping = async(Dispatchers.IO) { service.stop("container", "ready") }
                val hook = fixture.nextStopHook()
                fixture.running(false)
                hook.stop()
                stopping.await()
                val stopped = state(service, "ready", ContainerFeatureStatus.STOPPED).getValue("container/ready")
                assertNull(stopped.error)
                assertTrue(service.errors.value["container"].orEmpty().isEmpty())
                assertEquals(0, fixture.autoOpenCalls.get())
            }
        }
    }

    @Test fun genuineStopHookFailureRemainsFailed() = runBlocking {
        Fixture(mapOf("ready" to "container")).use { fixture ->
            ContainerFeatureService(fixture.runtime).use { service ->
                fixture.next("ready")
                state(service, "ready", ContainerFeatureStatus.RUNNING)
                fixture.failNextStopHook.set(true)
                service.stop("container", "ready")
                val failed = state(service, "ready", ContainerFeatureStatus.FAILED).getValue("container/ready")
                assertTrue(failed.error.orEmpty().contains("stop denied"))
                assertTrue(service.errors.value["container"].orEmpty().any { it.contains("stop denied") })
            }
        }
    }

    @Test fun unexpectedContainerExitRemainsFailedEvenWhenRunGenerationIsCleared() = runBlocking {
        Fixture(mapOf("ready" to "container")).use { fixture ->
            ContainerFeatureService(fixture.runtime).use { service ->
                fixture.next("ready")
                state(service, "ready", ContainerFeatureStatus.RUNNING)
                fixture.containers.value = fixture.containers.value.map { it.copy(running = false, runGeneration = 0) }
                val failed = state(service, "ready", ContainerFeatureStatus.FAILED).getValue("container/ready")
                assertTrue(failed.error.orEmpty().contains("容器运行进程已退出"))
                assertTrue(service.errors.value["container"].orEmpty().any { it.contains("容器运行进程已退出") })
            }
        }
    }

    private suspend fun state(service: ContainerFeatureService, featureId: String, status: ContainerFeatureStatus) =
        withTimeout(5000) { service.states.first { it["container/$featureId"]?.status == status } }

    /** Commands return immutable discovery data; service children retain an explicit stdin cleanup pipe. */
    private class Fixture(
        private val features: Map<String, String>,
        private val unready: Set<String> = emptySet(),
        private val failed: Set<String> = emptySet(),
        private val discoveryFailure: Boolean = false,
        private val pauseServiceLaunch: Boolean = false,
    ) : AutoCloseable {
        private val config = ContainerConfig("container", "Test", "/unused")
        val containers = MutableStateFlow(listOf(ContainerInfo(config, running = true, runGeneration = 1)))
        val scans = AtomicInteger()
        val cardReads = AtomicInteger()
        val autoOpenCalls = AtomicInteger()
        val pauseNextScan = AtomicBoolean()
        val failNextScan = AtomicBoolean()
        private val scanProcesses = Channel<ControlledProcess>(Channel.UNLIMITED)
        val pauseNextStopHook = AtomicBoolean()
        val failNextStopHook = AtomicBoolean()
        private val stopHooks = Channel<ControlledProcess>(Channel.UNLIMITED)
        val started = ConcurrentHashMap<String, AtomicInteger>()
        val stopTokens = ConcurrentHashMap<String, String>()
        private val created = ConcurrentHashMap<String, Channel<ControlledProcess>>()
        private val processes = ConcurrentHashMap.newKeySet<ControlledProcess>()
        private val barriers = Channel<Unit>(Channel.UNLIMITED)
        val admission = Channel<Unit>(Channel.UNLIMITED)
        val releaseAdmission = CountDownLatch(1)
        private var barrierId = 0
        val runtime: ContainerRuntime = Proxy.newProxyInstance(ContainerRuntime::class.java.classLoader, arrayOf(ContainerRuntime::class.java)) { _, method, args ->
            when (method.name) {
                "getContainers" -> containers
                "getDeployments" -> MutableStateFlow(emptyList<DeploymentProgress>())
                "getCatalog" -> emptyList<DistroSpec>()
                "openProcess", "openRunningProcess" -> {
                    val id = args[0] as String
                    if (method.name == "openProcess") autoOpenCalls.incrementAndGet()
                    @Suppress("UNCHECKED_CAST") val arguments = args[1] as List<String>
                    @Suppress("UNCHECKED_CAST") val environment = args[3] as Map<String, String>
                    if (pauseServiceLaunch && arguments.getOrNull(3) == "wawlink-feature") {
                        admission.trySend(Unit).getOrThrow()
                        check(releaseAdmission.await(5, TimeUnit.SECONDS)) { "admission gate was not released" }
                    }
                    check(containers.value.any { it.config.id == id && it.running }) { "container stopped" }
                    command(id, arguments, environment)
                }
                "toString" -> "FeatureFixtureRuntime"
                else -> error("Unexpected runtime call: ${method.name}")
            }
        } as ContainerRuntime

        private fun command(id: String, arguments: List<String>, environment: Map<String, String>): Process {
            val script = arguments[2]
            if (arguments.getOrNull(3) == "wawlink-feature") {
                val featureId = environment.getValue("WAWLINK_FEATURE_ID")
                val marker = Regex("WAWLINK_PROCESS_[a-fA-F0-9-]+").find(script)!!.value
                val process = ControlledProcess("$marker unavailable\n", alive = featureId !in failed,
                    port = environment.getValue("WAWLINK_PORT").toInt().takeIf { featureId !in unready && featureId !in failed })
                processes += process
                started.computeIfAbsent(featureId) { AtomicInteger() }.incrementAndGet()
                created.computeIfAbsent(featureId) { Channel(Channel.UNLIMITED) }.trySend(process).getOrThrow()
                return process
            }
            if (script.contains("root=\"${'$'}home/.wawlink/features\"")) {
                if (id != "container") {
                    barriers.trySend(Unit).getOrThrow()
                    return ControlledProcess("/home/test\u0000", alive = false)
                }
                scans.incrementAndGet()
                val data = buildString {
                    append("/home/test\u0000")
                    features.forEach { (featureId, lifecycle) ->
                        val json = """{"title":"$featureId","description":"Description","icon":"terminal","lifecycle":"$lifecycle","cardUrl":"/card"}"""
                        append("F\u0000$featureId\u0000/home/test/.wawlink/features/$featureId\u0000")
                        append(Base64.getEncoder().encodeToString(json.toByteArray()))
                        append('\u0000')
                    }
                }
                val failure = discoveryFailure || failNextScan.getAndSet(false)
                val process = ControlledProcess(if (failure) "" else data, alive = pauseNextScan.getAndSet(false),
                    error = if (failure) "discovery denied" else "", exit = if (failure) 1 else 0)
                processes += process
                scanProcesses.trySend(process).getOrThrow()
                return process
            }
            if (script.contains("卡片 HTML")) cardReads.incrementAndGet()
            if (script.contains("timeout -k 1 15 /bin/sh ./stop.sh")) {
                stopTokens[environment.getValue("WAWLINK_FEATURE_ID")] = environment.getValue("WAWLINK_TOKEN")
                val failure = failNextStopHook.getAndSet(false)
                val process = ControlledProcess("", alive = pauseNextStopHook.getAndSet(false),
                    error = if (failure) "stop denied" else "", exit = if (failure) 1 else 0)
                processes += process
                stopHooks.trySend(process).getOrThrow()
                return process
            }
            return ControlledProcess("", alive = false)
        }

        suspend fun next(featureId: String): ControlledProcess = withTimeout(5000) {
            created.computeIfAbsent(featureId) { Channel(Channel.UNLIMITED) }.receive()
        }

        suspend fun nextScan(): ControlledProcess = withTimeout(5000) { scanProcesses.receive() }
        suspend fun nextStopHook(): ControlledProcess = withTimeout(5000) { stopHooks.receive() }

        fun running(value: Boolean) {
            containers.value = containers.value.filter { it.config.id == "container" }.map {
                it.copy(running = value, stopGeneration = it.stopGeneration + if (!value && it.running) 1 else 0,
                    runGeneration = it.runGeneration + if (value && !it.running) 1 else 0)
            }
        }

        fun restart() {
            containers.value = containers.value.map { it.copy(runGeneration = it.runGeneration + 1, stopGeneration = it.stopGeneration + 1) }
        }

        suspend fun metadataBarrier() {
            val id = "barrier${++barrierId}"
            val metadata = containers.value.map { it.copy(sizeBytes = it.sizeBytes + 1) }
            containers.value = metadata + ContainerInfo(config.copy(id = id), running = true)
            withTimeout(5000) { barriers.receive() }
        }

        override fun close() { releaseAdmission.countDown(); processes.forEach { it.stop() } }
    }

    private class ControlledProcess(
        output: String,
        alive: Boolean,
        error: String = "",
        private val exit: Int = 0,
        port: Int? = null,
    ) : Process() {
        private val terminated = CountDownLatch(if (alive) 1 else 0)
        private val stdout = ByteArrayInputStream(output.toByteArray())
        private val stderr = ByteArrayInputStream(error.toByteArray())
        val controlStops = AtomicInteger()
        val destroyCalls = AtomicInteger()
        private val server = port?.let { ServerSocket(it, 4, InetAddress.getByName("127.0.0.1")) }
        private val stdin = object : ByteArrayOutputStream() {
            override fun flush() {
                if (toString("UTF-8").contains("stop\n")) {
                    controlStops.incrementAndGet()
                    reset()
                    stop()
                }
            }
        }

        init {
            if (server != null) Thread({
                while (!server.isClosed) {
                    try {
                        server.accept().use { socket ->
                            val input = socket.getInputStream().bufferedReader()
                            while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break }
                            socket.getOutputStream().apply {
                                write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                                flush()
                            }
                        }
                    } catch (_: java.io.IOException) { break }
                }
            }, "feature-readiness-fixture").apply { isDaemon = true; start() }
        }

        fun stop() { server?.close(); terminated.countDown() }
        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream() = stdout
        override fun getErrorStream() = stderr
        override fun waitFor(): Int { terminated.await(); return exit }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = terminated.await(timeout, unit)
        override fun exitValue(): Int { check(!isAlive); return exit }
        override fun isAlive(): Boolean = terminated.count > 0
        override fun destroy() { destroyCalls.incrementAndGet(); stop() }
        override fun destroyForcibly(): Process { destroy(); return this }
    }
}
