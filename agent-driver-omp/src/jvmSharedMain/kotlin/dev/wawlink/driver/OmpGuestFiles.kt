package dev.wawlink.driver

import dev.wawlink.runtime.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

internal class OmpGuestFileException(val code: String, val guestPath: String) : IOException("容器文件操作失败：$code${guestPath.takeIf(String::isNotEmpty)?.let { "：$it" }.orEmpty()}")
internal data class OmpGuestEntry(val name: String, val directory: Boolean, val regular: Boolean, val size: Long, val modified: Long)

/** One guest-account file worker per running container; captured sessions never reconnect. */
internal class OmpGuestFiles(private val runtime: ContainerRuntime, private val containerId: String) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycle = Mutex()
    @Volatile private var current: OmpFileSession? = null
    @Volatile private var closed = false
    init {
        scope.launch {
            runtime.containers.collect { current?.let { session -> if (!session.isCurrent()) { session.close(); if (current === session) current = null } } }
        }
    }
    val stopGeneration: Long get() = runtime.containers.value.firstOrNull { it.config.id == containerId }?.stopGeneration ?: throw OmpProcessStoppedException()
    suspend fun session(expectedStopGeneration: Long? = null, runningOnly: Boolean = false): OmpFileSession {
        val expected = expectedStopGeneration ?: stopGeneration
        return withContext(Dispatchers.IO) {
        lifecycle.withLock {
            check(!closed) { "容器文件服务已关闭" }
            val info = runtime.containers.value.firstOrNull { it.config.id == containerId } ?: throw OmpProcessStoppedException()
            if (info.stopGeneration != expected || runningOnly && !info.running) throw OmpProcessStoppedException()
            current?.let { if (it.isCurrent() && it.alive) return@withLock it; it.close(); current = null }
            var child: Process? = null
            try {
                withContainerFileOperation(runtime, containerId, expected, runningOnly) {
                    val args = listOf("/usr/local/bin/bun", "-e", source)
                    val environment = mapOf("WAWLINK_FILE_HOME" to containerHome(info.config), "WAWLINK_FILE_MOUNTS" to JsonArray(info.config.mounts.map { mount -> buildJsonObject {
                        put("target", mount.target.trimEnd('/').ifEmpty { "/" }); put("readWrite", mount.readWrite)
                    } }).toString())
                    child = if (runningOnly) runtime.openRunningProcess(containerId, args, "/", environment) else runtime.openProcess(containerId, args, "/", environment)
                    val started = runtime.containers.value.firstOrNull { it.config.id == containerId } ?: throw OmpProcessStoppedException()
                    OmpFileSession(runtime, started, requireNotNull(child), scope)
                }.also { session ->
                    if (closed || !session.isCurrent()) { session.close(); throw OmpProcessStoppedException() }
                    current = session
                }
            } catch (failure: Throwable) { child?.destroy(); throw failure }
        }
        }
    }
    override fun close() { closed = true; current?.close(); current = null; scope.cancel() }
    companion object {
        private val source by lazy { requireNotNull(OmpGuestFiles::class.java.getResourceAsStream("/omp-management/guest-files.mjs")).bufferedReader().use { it.readText() } }
    }
}

internal class OmpFileSession(
    private val runtime: ContainerRuntime, private val info: ContainerInfo, private val child: Process, scope: CoroutineScope,
) : AutoCloseable {
    val stopGeneration = info.stopGeneration
    val runGeneration = info.runGeneration
    val agentDirectory: String = "${containerHome(info.config).trimEnd('/')}/.omp/agent"
    val alive: Boolean get() = !closed && child.isAlive
    private val sequence = AtomicLong()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    private val output = Mutex()
    @Volatile private var closed = false
    @Volatile private var processFailure: IOException? = null
    init {
        scope.launch {
            try {
                readManagementFrames(child.inputStream) { frame ->
                    val id = frame["id"]?.jsonPrimitive?.content ?: return@readManagementFrames
                    val result = pending.remove(id) ?: return@readManagementFrames
                    val error = frame["error"] as? JsonObject
                    if (error != null) result.completeExceptionally(OmpGuestFileException(error["code"]!!.jsonPrimitive.content, error["path"]?.jsonPrimitive?.content.orEmpty()))
                    else result.complete(frame["result"] ?: JsonNull)
                }
            } catch (failure: Exception) {
                if (!closed && isCurrent()) processFailure = IOException("容器文件通道读取失败", failure)
            } finally {
                if (!closed && isCurrent() && processFailure == null) processFailure = IOException("容器文件进程异常退出")
                failPending(processFailure ?: OmpProcessStoppedException())
                close()
            }
        }
        scope.launch {
            // Runtime stderr may include guest data. File protocol reports code/path instead.
            try { child.errorStream.use { input -> val bytes = ByteArray(4096); while (input.read(bytes) >= 0) { } } } catch (_: IOException) { }
        }
    }
    fun isCurrent(): Boolean = runtime.containers.value.any { current ->
        current.config.id == info.config.id && current.running && current.stopGeneration == stopGeneration && current.runGeneration == runGeneration &&
            current.config.rootfsPath == info.config.rootfsPath && current.config.user == info.config.user && current.config.environment == info.config.environment && current.config.mounts == info.config.mounts
    }
    private fun checkCurrent() {
        if (!isCurrent()) throw OmpProcessStoppedException()
        if (closed) throw processFailure ?: OmpProcessStoppedException()
    }
    private fun failPending(failure: Throwable) { pending.values.forEach { it.completeExceptionally(failure) }; pending.clear() }
    override fun close() {
        if (closed) return
        closed = true
        failPending(processFailure ?: OmpProcessStoppedException())
        runCatching { child.outputStream.close() }
        child.destroy()
    }
    suspend fun request(op: String, args: Map<String, Any?> = emptyMap()): JsonElement {
        checkCurrent()
        val id = sequence.incrementAndGet().toString()
        val result = CompletableDeferred<JsonElement>()
        pending[id] = result
        var sent = false
        try {
            withContext(Dispatchers.IO) {
                output.withLock {
                    checkCurrent()
                    val frame = buildJsonObject { put("id", id); put("op", op); put("args", args.element()) }
                    child.outputStream.write(frame.toString().toByteArray(Charsets.UTF_8)); child.outputStream.write(10); child.outputStream.flush()
                    sent = true
                }
            }
            val response = withTimeout(if (op == "awaitResponse") ((args["timeoutMs"] as? Number)?.toLong() ?: 180_000) + 5_000 else 180_000) { result.await() }
            checkCurrent()
            return response
        } catch (cancelled: CancellationException) {
            if (sent) withContext(NonCancellable + Dispatchers.IO) {
                try {
                    withTimeout(5_000) {
                        output.withLock {
                            checkCurrent()
                            val cancel = buildJsonObject { put("id", "cancel-$id"); put("op", "cancel"); put("args", buildJsonObject { put("id", id) }) }
                            child.outputStream.write(cancel.toString().toByteArray(Charsets.UTF_8)); child.outputStream.write(10); child.outputStream.flush()
                        }
                        val completed = try { result.await() } catch (_: OmpGuestFileException) { null }
                        val release = when (op) { "readOpen" -> "readClose"; "listOpen" -> "listClose"; "writeOpen" -> "writeAbort"; else -> null }
                        if (release != null && completed is JsonPrimitive && completed !is JsonNull) request(release, mapOf("token" to completed.content))
                    }
                } catch (failure: Exception) {
                    if (isCurrent() && processFailure == null) processFailure = IOException("容器文件操作取消清理失败", failure)
                    close()
                }
            }
            throw cancelled
        } catch (failure: Exception) {
            if (!isCurrent()) throw OmpProcessStoppedException()
            if (failure is IOException && failure !is OmpGuestFileException) { processFailure = failure; close() }
            throw failure
        } finally { pending.remove(id) }
    }
    suspend fun stat(path: String): OmpGuestEntry? = request("stat", mapOf("path" to path)).takeUnless { it is JsonNull }?.jsonObject?.let(::entry)
    suspend fun list(path: String): List<OmpGuestEntry> {
        val token = request("listOpen", mapOf("path" to path)).jsonPrimitive.content
        val entries = mutableListOf<OmpGuestEntry>()
        var failure: Throwable? = null
        try {
            while (true) {
                val page = request("listChunk", mapOf("token" to token)).jsonObject
                page["entries"]!!.jsonArray.forEach { entries += entry(it.jsonObject) }
                if (page["done"]!!.jsonPrimitive.boolean) return entries
            }
        } catch (caught: Throwable) { failure = caught; throw caught }
        finally { cleanup("listClose", token, failure) }
    }
    suspend fun mkdirs(path: String) { request("mkdirs", mapOf("path" to path)) }
    suspend fun version(path: String): String = request("version", mapOf("path" to path)).jsonPrimitive.content
    private fun entry(value: JsonObject) = OmpGuestEntry(value["name"]!!.jsonPrimitive.content, value["directory"]!!.jsonPrimitive.boolean,
        value["regular"]!!.jsonPrimitive.boolean, value["size"]!!.jsonPrimitive.long, value["modified"]!!.jsonPrimitive.long)
    private suspend fun read(path: String, consume: suspend (ByteArray) -> Unit): Boolean {
        val token = request("readOpen", mapOf("path" to path)).takeUnless { it is JsonNull }?.jsonPrimitive?.content ?: return false
        var failure: Throwable? = null
        try {
            while (true) {
                val part = request("readChunk", mapOf("token" to token)).takeUnless { it is JsonNull }?.jsonPrimitive?.content ?: break
                consume(Base64.getDecoder().decode(part))
            }
        } catch (caught: Throwable) { failure = caught; throw caught }
        finally { cleanup("readClose", token, failure) }
        return true
    }
    suspend fun readBytes(path: String): ByteArray? {
        val bytes = ByteArrayOutputStream()
        return if (read(path) { bytes.write(it) }) bytes.toByteArray() else null
    }
    suspend fun readText(path: String): String? = readBytes(path)?.let { bytes ->
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }
    suspend fun writeText(path: String, content: String, expectedVersion: String? = null, createOnly: Boolean = false) {
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(content))
        val bytes = ByteArray(encoded.remaining()); encoded.get(bytes)
        writeBytes(path, bytes, expectedVersion, createOnly)
    }
    suspend fun writeBytes(path: String, content: ByteArray, expectedVersion: String? = null, createOnly: Boolean = false) {
        content.inputStream().use { write(path, it, expectedVersion, createOnly) }
    }
    suspend fun importFile(path: String, input: InputStream, createOnly: Boolean = true) { write(path, input, null, createOnly) }
    private suspend fun write(path: String, input: InputStream, expectedVersion: String?, createOnly: Boolean) = withContext(Dispatchers.IO) {
        val token = request("writeOpen", mapOf("path" to path, "expectedVersion" to expectedVersion, "createOnly" to createOnly)).jsonPrimitive.content
        var failure: Throwable? = null
        try {
            val buffer = ByteArray(64 * 1024)
            var offset = 0L
            while (true) {
                val count = runInterruptible { input.read(buffer) }; if (count < 0) break; if (count == 0) continue
                val data = Base64.getEncoder().encodeToString(if (count == buffer.size) buffer else buffer.copyOf(count))
                request("writeChunk", mapOf("token" to token, "offset" to offset, "data" to data)); offset += count
            }
            request("writeCommit", mapOf("token" to token))
        } catch (caught: Throwable) { failure = caught; throw caught }
        finally { cleanup("writeAbort", token, failure) }
    }
    suspend fun copyFileTo(source: String, destination: OmpFileSession, target: String) {
        val token = destination.request("writeOpen", mapOf("path" to target, "createOnly" to true)).jsonPrimitive.content
        var failure: Throwable? = null
        try {
            var offset = 0L
            if (!read(source) { bytes ->
                destination.request("writeChunk", mapOf("token" to token, "offset" to offset, "data" to Base64.getEncoder().encodeToString(bytes))); offset += bytes.size
            }) throw OmpGuestFileException("ENOENT", source)
            checkCurrent()
            destination.request("writeCommit", mapOf("token" to token))
        } catch (caught: Throwable) { failure = caught; throw caught }
        finally { destination.cleanup("writeAbort", token, failure) }
    }
    private suspend fun cleanup(op: String, token: String, failure: Throwable?) = withContext(NonCancellable) {
        if (!closed && isCurrent()) try { request(op, mapOf("token" to token)) }
        catch (cleanup: Throwable) { if (failure != null) failure.addSuppressed(cleanup) else if (isCurrent()) throw cleanup }
    }
    suspend fun delete(path: String, recursive: Boolean = false) { request("delete", mapOf("path" to path, "recursive" to recursive)) }
    suspend fun rewriteHistory(path: String, workspace: String, title: String, newId: String? = null, parent: String? = null) {
        request("rewriteHistory", mapOf("path" to path, "workspace" to workspace, "title" to title, "newId" to newId, "parent" to parent))
    }
    suspend fun awaitResponse(directory: String, requestId: String, instance: String, timeoutMs: Long): JsonObject {
        request("awaitResponse", mapOf("directory" to directory, "requestId" to requestId, "instance" to instance, "timeoutMs" to timeoutMs))
        val value = readText("$directory/response-$requestId.json") ?: throw OmpGuestFileException("ENOENT", directory)
        return DriverJson.parseToJsonElement(value).jsonObject.also { check(it["instance"]?.jsonPrimitive?.content == instance) { "会话文件响应实例已过期" } }
    }
}

private fun Any?.element(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is Map<*, *> -> JsonObject(entries.associate { it.key.toString() to it.value.element() })
    is List<*> -> JsonArray(map { it.element() })
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    else -> JsonPrimitive(toString())
}
