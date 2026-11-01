package dev.wawlink.runtime

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.IdentityHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

data class FeatureDebugTarget(val containerId: String, val featureId: String, val surface: String)

fun interface FeatureDebugPage {
    suspend fun request(operation: String, expression: String?): JsonElement
}

/** Real mounted renderer handles, scoped to the container's current running generation. */
class ContainerFeatureDebugger internal constructor(
    private val runtime: ContainerRuntime,
    private val catalog: suspend (String) -> ContainerFeatureCatalog,
    private val state: (String, String) -> ContainerFeatureState,
) : AutoCloseable {
    private class Registration(val generation: Long, val page: FeatureDebugPage, parent: Job) {
        val job = SupervisorJob(parent)
    }
    private val job = SupervisorJob()
    private val gate = Any()
    private val installLock = Mutex()
    private val serial = AtomicLong()
    private val registrations = mutableMapOf<FeatureDebugTarget, MutableMap<Long, Registration>>()
    private val endpoints = mutableMapOf<String, FeatureDebugEndpoint>()
    private var closed = false

    fun register(target: FeatureDebugTarget, page: FeatureDebugPage): AutoCloseable {
        validateTarget(target.featureId, target.surface)
        val id = serial.incrementAndGet()
        val registration = synchronized(gate) {
            check(!closed) { "Feature debugger is closed" }
            val container = runtime.containers.value.firstOrNull { it.config.id == target.containerId }
                ?: error("Container does not exist")
            Registration(container.runGeneration, page, job).also {
                registrations.getOrPut(target) { linkedMapOf() }[id] = it
            }
        }
        return AutoCloseable {
            synchronized(gate) {
                registrations[target]?.let { entries ->
                    if (entries[id] === registration) entries.remove(id)
                    if (entries.isEmpty()) registrations.remove(target)
                }
            }
            registration.job.cancel(CancellationException("Feature renderer was disposed"))
        }
    }

    internal fun containersChanged() {
        val running = runtime.containers.value.filter { it.running }.associateBy { it.config.id }
        val retired = synchronized(gate) {
            registrations.entries.removeAll { (target, entries) ->
                entries.entries.removeAll { (_, registration) ->
                    (running[target.containerId]?.runGeneration != registration.generation).also { stale ->
                        if (stale) registration.job.cancel(CancellationException("Container running generation changed"))
                    }
                }
                entries.isEmpty()
            }
            endpoints.values.filter { running[it.containerId]?.runGeneration != it.generation }.also { values ->
                values.forEach { endpoints.remove(it.containerId) }
            }
        }
        retired.forEach { it.close() }
    }

    private fun requireCurrent(containerId: String, generation: Long) {
        synchronized(gate) { check(!closed) { "Feature debugger is closed" } }
        check(runtime.containers.value.any { it.config.id == containerId && it.running && it.runGeneration == generation }) {
            "Container is stopped, deleted, or its running generation changed"
        }
    }

    /** Called by the existing pre-agent installation hook; never starts a container. */
    internal suspend fun ensureInstalled(containerId: String) = installLock.withLock {
        val container = runtime.containers.value.firstOrNull { it.config.id == containerId && it.running }
            ?: error("Feature debugger requires a running container")
        requireCurrent(containerId, container.runGeneration)
        containersChanged()
        val endpoint = synchronized(gate) {
            endpoints[containerId]?.takeIf { it.generation == container.runGeneration }?.let { return@withLock }
            FeatureDebugEndpoint(containerId, container.runGeneration, ::request).also { endpoints[containerId] = it }
        }
        try {
            installFeatureDebugClient(runtime, containerId, endpoint.configuration())
            requireCurrent(containerId, container.runGeneration)
        } catch (failure: Throwable) {
            synchronized(gate) { if (endpoints[containerId] === endpoint) endpoints.remove(containerId) }
            endpoint.close()
            throw failure
        }
    }

    private fun mounted(target: FeatureDebugTarget, generation: Long): Registration? = synchronized(gate) {
        val current = registrations[target].orEmpty().values.filter { it.generation == generation && it.job.isActive }
        check(current.size <= 1) { "Multiple mounted ${target.surface} renderers match ${target.featureId}; close duplicate mounts before debugging" }
        current.singleOrNull()
    }

    /** Refreshes static card source from the guest; captured Compose HTML is not a reload source. */
    suspend fun readCardHtml(target: FeatureDebugTarget): String {
        validateTarget(target.featureId, target.surface)
        val container = runtime.containers.value.firstOrNull { it.config.id == target.containerId && it.running }
            ?: error("Feature debugger requires a running container")
        requireCurrent(target.containerId, container.runGeneration)
        val currentCatalog = catalog(target.containerId)
        requireCurrent(target.containerId, container.runGeneration)
        return currentCatalog.features.firstOrNull { it.id == target.featureId }?.takeIf { it.cardUrl == null }?.cardHtml
            ?: error("Feature has no current static HTML card")
    }

    internal suspend fun request(containerId: String, generation: Long, operation: String, featureId: String?, surface: String, expression: String?): JsonElement {
        require(operation in setOf("list", "inspect", "logs", "eval", "screenshot", "reload")) { "Unknown debugger operation" }
        require(surface == "card" || surface == "page") { "Surface must be card or page" }
        if (operation != "list") validateTarget(featureId ?: error("Feature ID is required"), surface)
        require(operation == "eval" || expression == null) { "Only eval accepts an expression" }
        if (operation == "eval") require(expression != null && expression.toByteArray().size <= 65_536) { "Eval expression must be at most 64 KiB" }
        requireCurrent(containerId, generation)
        val currentCatalog = catalog(containerId)
        requireCurrent(containerId, generation)
        if (operation == "list") return buildJsonObject {
            put("containerId", containerId); put("generation", generation.toString()); put("running", true)
            put("surface", surface)
            put("features", JsonArray(currentCatalog.features.map { feature ->
                metadata(containerId, feature, surface, mounted(FeatureDebugTarget(containerId, feature.id, surface), generation) != null)
            }))
            put("errors", JsonArray(currentCatalog.errors.map(::JsonPrimitive)))
        }
        val feature = currentCatalog.features.firstOrNull { it.id == featureId } ?: error("Feature is absent or its current manifest is invalid")
        val target = FeatureDebugTarget(containerId, feature.id, surface)
        val registration = mounted(target, generation)
        val detail = if (registration != null) {
            withTimeout(25_000) {
                coroutineScope {
                    val pending = async {
                        requireCurrent(containerId, generation)
                        registration.page.request(operation, expression)
                    }
                    val invalidation = registration.job.invokeOnCompletion {
                        pending.cancel(CancellationException("Feature renderer was disposed or invalidated"))
                    }
                    try { pending.await() } finally { invalidation.dispose() }
                }
            }.also {
                requireCurrent(containerId, generation)
                check(mounted(target, generation) === registration && registration.job.isActive) { "Feature renderer changed during the request" }
            }
        } else {
            check(operation == "inspect" || operation == "logs") { "Feature ${feature.id} $surface is not mounted; open that surface in WawLink first" }
            null
        }
        if (operation != "inspect" && operation != "logs") return detail ?: error("Feature renderer is not mounted")
        return buildJsonObject {
            if (detail != null) {
                require(detail is JsonObject) { "Renderer returned invalid $operation metadata" }
                detail.forEach { (key, value) -> put(key, value) }
            }
            metadata(containerId, feature, surface, registration != null).forEach { (key, value) -> put(key, value) }
            put("generation", generation.toString())
            put("catalogErrors", JsonArray(currentCatalog.errors.map(::JsonPrimitive)))
        }
    }

    private fun metadata(containerId: String, feature: ContainerFeature, surface: String, isMounted: Boolean): JsonObject {
        val currentState = state(containerId, feature.id)
        return buildJsonObject {
            put("containerId", containerId); put("featureId", feature.id); put("surface", surface)
            put("running", true); put("mounted", isMounted)
            put("status", currentState.status.name.lowercase())
            put("error", currentState.error?.let(::JsonPrimitive) ?: JsonNull)
            put("output", currentState.output)
            put("feature", buildJsonObject {
                put("id", feature.id); put("title", feature.title); put("description", feature.description)
                put("lifecycle", feature.lifecycle.name.lowercase()); put("icon", feature.icon)
                put("cardHeight", feature.cardHeight); put("cardSpan", feature.cardSpan)
                put("cardUrl", feature.cardUrl?.let(::JsonPrimitive) ?: JsonNull)
                put("hasStaticCard", feature.cardHtml != null)
            })
        }
    }

    override fun close() {
        val retired = synchronized(gate) {
            if (closed) return
            closed = true
            registrations.clear()
            endpoints.values.toList().also { endpoints.clear() }
        }
        job.cancel(CancellationException("Feature debugger is closed"))
        retired.forEach { it.close() }
        FeatureDebugServices.detach(runtime, this)
    }

    private fun validateTarget(featureId: String, surface: String) {
        require(featureId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))) { "Invalid feature ID" }
        require(surface == "card" || surface == "page") { "Surface must be card or page" }
    }
}

/** The runtime service owns this binding, including when no Functions UI has been opened. */
internal object FeatureDebugServices {
    private class Binding(val debugger: ContainerFeatureDebugger, val reportErrors: suspend (String, List<String>, Boolean) -> Unit)
    private val services = IdentityHashMap<ContainerRuntime, Binding>()
    @Synchronized fun attach(runtime: ContainerRuntime, debugger: ContainerFeatureDebugger, reportErrors: suspend (String, List<String>, Boolean) -> Unit) {
        check(services[runtime] == null) { "A feature service is already attached to this runtime" }
        services[runtime] = Binding(debugger, reportErrors)
    }
    @Synchronized fun detach(runtime: ContainerRuntime, debugger: ContainerFeatureDebugger) {
        if (services[runtime]?.debugger === debugger) services.remove(runtime)
    }
    suspend fun install(runtime: ContainerRuntime, containerId: String) {
        val debugger = synchronized(this) { services[runtime]?.debugger } ?: return
        debugger.ensureInstalled(containerId)
    }
    suspend fun publishInstallationErrors(runtime: ContainerRuntime, containerId: String, errors: List<String>, publish: Boolean) {
        val binding = synchronized(this) { services[runtime] } ?: return
        binding.reportErrors(containerId, errors, publish)
    }
}

/** No CORS, no query/cookie authentication, no redirects, and no non-loopback listener. */
internal class FeatureDebugEndpoint(
    val containerId: String,
    val generation: Long,
    private val request: suspend (String, Long, String, String?, String, String?) -> JsonElement,
) : AutoCloseable {
    private val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
    private val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val closed = AtomicBoolean()
    private val job = SupervisorJob()
    private val pool = ThreadPoolExecutor(8, 8, 30, TimeUnit.SECONDS, ArrayBlockingQueue(32)) { task ->
        Thread(task, "wawlink-feature-debug-client").apply { isDaemon = true }
    }
    private val acceptor = Thread({
        try {
            while (!closed.get()) {
                val socket = server.accept()
                sockets.add(socket)
                try {
                    if (closed.get()) {
                        sockets.remove(socket)
                        runCatching { socket.close() }
                        break
                    }
                    pool.execute {
                        try { if (!closed.get()) serve(socket) }
                        catch (_: Throwable) {
                            if (!closed.get()) runCatching { respond(socket, 400, errorJson("Invalid debugger request")) }
                        } finally { sockets.remove(socket); runCatching { socket.close() } }
                    }
                } catch (_: Throwable) {
                    if (!closed.get()) runCatching { respond(socket, 503, errorJson("Debugger is unavailable")) }
                    sockets.remove(socket)
                    runCatching { socket.close() }
                }
            }
        } catch (failure: Throwable) {
            if (!closed.get()) runCatching { failure.printStackTrace() }
        } finally {
            close()
        }
    }, "wawlink-feature-debug").apply { isDaemon = true; start() }

    fun configuration(): JsonObject = buildJsonObject {
        put("version", 1); put("containerId", containerId); put("generation", generation.toString())
        put("url", "http://127.0.0.1:${server.localPort}/v1"); put("token", token)
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 35_000
        val input = BufferedInputStream(socket.getInputStream())
        require(line(input) == "POST /v1 HTTP/1.1")
        val headers = linkedMapOf<String, String>()
        var bytes = 0
        while (true) {
            val row = line(input)
            bytes += row.length + 2
            require(bytes <= 16_384)
            if (row.isEmpty()) break
            val split = row.indexOf(':'); require(split > 0)
            val name = row.substring(0, split).lowercase()
            require(name !in headers)
            headers[name] = row.substring(split + 1).trim()
        }
        val supplied = headers["authorization"].orEmpty().toByteArray(Charsets.UTF_8)
        val expected = "Bearer $token".toByteArray(Charsets.UTF_8)
        if (headers["host"] != "127.0.0.1:${server.localPort}" || headers.containsKey("origin") ||
            headers.containsKey("referer") || !MessageDigest.isEqual(supplied, expected)) {
            respond(socket, 403, errorJson("Debugger authentication failed")); return
        }
        require(headers["transfer-encoding"] == null)
        require(headers["content-type"]?.substringBefore(';')?.trim() == "application/json")
        val size = headers["content-length"]?.toIntOrNull() ?: error("Content-Length is required")
        require(size in 1..524_288)
        val body = ByteArray(size)
        var offset = 0
        while (offset < size) { val count = input.read(body, offset, size - offset); require(count > 0); offset += count }
        val value = Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
        require(value.keys.all { it in setOf("operation", "featureId", "surface", "expression") })
        fun string(name: String): String? = value[name]?.let { require(it is JsonPrimitive && it.isString); it.content }
        val operation = string("operation") ?: error("Operation is required")
        val featureId = string("featureId")
        val surface = string("surface") ?: "card"
        val expression = string("expression")
        val result = try {
            runBlocking(job) { withTimeout(30_000) { request(containerId, generation, operation, featureId, surface, expression) } }
        } catch (failure: Throwable) {
            val message = if (failure is TimeoutCancellationException) "Debugger operation timed out" else failure.message ?: "Debugger operation failed"
            respond(socket, 409, errorJson(message.replace(token, "[redacted]").take(8192))); return
        }
        respond(socket, 200, buildJsonObject { put("ok", true); put("result", result) })
    }

    private fun respond(socket: Socket, status: Int, value: JsonObject) {
        var body = value.toString().replace(token, "[redacted]").toByteArray(Charsets.UTF_8)
        var code = status
        if (body.size > 32 * 1024 * 1024) {
            code = 413
            body = errorJson("Debugger response exceeds 32 MiB").toString().toByteArray()
        }
        socket.getOutputStream().apply {
            write("HTTP/1.1 $code WawLink\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            write(body); flush()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        job.cancel(CancellationException("Container debugger endpoint was revoked"))
        runCatching { server.close() }
        sockets.forEach { runCatching { it.close() } }
        runCatching { pool.shutdownNow() }
    }

    private fun errorJson(message: String) = buildJsonObject { put("ok", false); put("error", message) }
    private fun line(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val byte = input.read(); require(byte >= 0)
            if (byte == 10) break
            require(bytes.size() < 8192)
            if (byte != 13) bytes.write(byte)
        }
        return bytes.toString("ISO-8859-1")
    }
}
