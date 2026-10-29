package dev.wawlink.runtime

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LocalContainerRuntimeTest {
    private fun fixture(block: (File, File, ContainerConfig) -> Unit) {
        val directory = Files.createTempDirectory("wawlink-local-runtime-test").toFile()
        val data = File(directory, "data").apply { mkdirs() }
        val config = root(directory, "source", "original")
        try { block(directory, data, config) } finally { RootfsArchives.deleteTree(directory) }
    }

    private fun root(directory: File, id: String, payload: String): ContainerConfig {
        val root = File(directory, id).apply { mkdirs() }
        File(root, "etc").mkdirs()
        File(root, "bin").mkdirs()
        // Only root validation runs: no test launches this ELF header or an engine substitute.
        File(root, "bin/sh").apply {
            writeBytes(ByteArray(20).apply {
                this[0] = 0x7f; this[1] = 'E'.code.toByte(); this[2] = 'L'.code.toByte(); this[3] = 'F'.code.toByte()
                this[5] = 1; this[18] = 62
            })
            setExecutable(true)
        }
        File(root, "etc/passwd").writeText("root:x:0:0::/root:/bin/sh\n")
        File(root, "payload").writeText(payload)
        return ContainerConfig(id, id, root.canonicalPath, arch = "amd64", shell = "/bin/sh", link2symlink = false, isDefault = true)
    }

    private fun saved(data: File, config: ContainerConfig, cached: Long?) {
        File(data, "containers.json").writeText(buildJsonObject {
            put("containers", Json.encodeToJsonElement(listOf(config)))
            if (cached != null) put("sizes", buildJsonObject { put(config.id, cached) })
        }.toString())
    }

    private class SizeGate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val pending = AtomicBoolean(true)
        fun measure(root: File, excluded: Set<String>): Long {
            val actual = RootfsArchives.size(root, excluded)
            if (pending.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Size calculation was not released" }
            }
            return actual
        }
        suspend fun awaitEntry() = withContext(Dispatchers.IO) {
            check(entered.await(10, TimeUnit.SECONDS)) { "Background size calculation did not start" }
        }
    }

    // Synchronize completion only; assertions below concern public state and persisted data.
    private suspend fun awaitSizeWork(runtime: LocalContainerRuntime) = withTimeout(10_000) {
        val field = LocalContainerRuntime::class.java.getDeclaredField("scope").apply { isAccessible = true }
        val scope = field.get(runtime) as CoroutineScope
        while (true) {
            val jobs = scope.coroutineContext[Job]!!.children.toList()
            if (jobs.isEmpty()) break
            jobs.joinAll()
        }
    }

    private fun startup(cached: Long?) = fixture { _, data, config -> runBlocking {
        saved(data, config, cached)
        val gate = SizeGate()
        val runtime = LocalContainerRuntime(RuntimePaths(data, hostProot = "/missing-engine-must-not-be-launched"), gate::measure)
        try {
            gate.awaitEntry()
            val initial = runtime.containers.value.single()
            assertEquals(cached ?: 0L, initial.sizeBytes)
            assertFalse(initial.running)
            assertEquals(0L, initial.runGeneration)
            gate.release.countDown()
            val actual = RootfsArchives.size(File(config.rootfsPath))
            withTimeout(10_000) { runtime.containers.first { it.single().sizeBytes == actual } }
            val persisted = Json.parseToJsonElement(File(data, "containers.json").readText()).jsonObject
            assertEquals(actual, persisted.getValue("sizes").jsonObject.getValue(config.id).jsonPrimitive.long)
        } finally { gate.release.countDown(); runtime.close(); awaitSizeWork(runtime) }
    } }

    @Test fun cachedFirstScreenDoesNotWaitForRecursiveSizeCalculation() = startup(987_654L)

    @Test fun oldConfigurationWithoutASizeCacheLoadsAndRefreshesOffline() = startup(null)

    @Test fun lateSizeCannotReplaceADeletedAndReregisteredContainer() = fixture { directory, data, config -> runBlocking {
        saved(data, config, 111L)
        val gate = SizeGate()
        val runtime = LocalContainerRuntime(RuntimePaths(data, hostProot = "/missing-engine-must-not-be-launched"), gate::measure)
        try {
            gate.awaitEntry()
            runtime.delete(config.id)
            assertTrue(runtime.containers.value.isEmpty())
            val replacement = root(directory, "replacement", "a different rootfs with a different size").copy(id = config.id)
            runtime.register(replacement)
            val actual = RootfsArchives.size(File(replacement.rootfsPath))
            withTimeout(10_000) { runtime.containers.first { it.single().sizeBytes == actual } }
            gate.release.countDown()
            awaitSizeWork(runtime)
            assertEquals(replacement.rootfsPath, runtime.containers.value.single().config.rootfsPath)
            assertEquals(actual, runtime.containers.value.single().sizeBytes)
        } finally { gate.release.countDown(); runtime.close(); awaitSizeWork(runtime) }
    } }

    @Test fun changedMetadataInvalidatesThePendingConfigurationSnapshot() = fixture { _, data, config -> runBlocking {
        saved(data, config, 111L)
        val gate = SizeGate()
        val runtime = LocalContainerRuntime(RuntimePaths(data, hostProot = "/missing-engine-must-not-be-launched"), gate::measure)
        try {
            gate.awaitEntry()
            runtime.saveMetadata(config.id, ContainerMetadata("New portable title"), null)
            val actual = RootfsArchives.size(File(config.rootfsPath))
            gate.release.countDown()
            awaitSizeWork(runtime)
            assertEquals("New portable title", runtime.containers.value.single().config.displayName)
            assertEquals(actual, runtime.containers.value.single().sizeBytes)
        } finally { gate.release.countDown(); runtime.close(); awaitSizeWork(runtime) }
    } }

    @Test fun lateSizeCannotPublishAfterClose() = fixture { _, data, config -> runBlocking {
        saved(data, config, 111L)
        val gate = SizeGate()
        val runtime = LocalContainerRuntime(RuntimePaths(data, hostProot = "/missing-engine-must-not-be-launched"), gate::measure)
        try {
            gate.awaitEntry()
            withTimeout(10_000) { withContext(Dispatchers.IO) { runtime.close() } }
            val closed = runtime.containers.value
            gate.release.countDown()
            awaitSizeWork(runtime)
            assertEquals(closed, runtime.containers.value)
            assertEquals(111L, runtime.containers.value.single().sizeBytes)
        } finally { gate.release.countDown(); runtime.close(); awaitSizeWork(runtime) }
    } }

    @Test fun stoppedRootfsRejectsSameContainerWorkButDoesNotHoldTheGlobalMutex() = fixture { directory, data, config -> runBlocking {
        val runtime = LocalContainerRuntime(RuntimePaths(data, hostProot = "/missing-engine-must-not-be-launched"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            runtime.register(config.copy(isDefault = false))
            val other = root(directory, "other", "other").copy(isDefault = true)
            runtime.register(other)
            val operation = async {
                runtime.withStoppedRootfs(config.id, "reserved-clone") { entered.complete(Unit); release.await() }
            }
            entered.await()
            withTimeout(10_000) {
                runtime.stop(other.id)
                runtime.saveMetadata(other.id, ContainerMetadata("Other remains editable"), null)
                runtime.setDefault(other.id)
                val third = root(directory, "third", "third").copy(isDefault = false)
                runtime.register(third)
                runtime.delete(third.id)
            }
            val rejected = listOf<suspend () -> Unit>(
                { runtime.start(config.id) },
                { runtime.update(config) },
                { runtime.delete(config.id) },
                { runtime.setDefault(config.id) },
                { runtime.readMetadata(config.id) },
                { runtime.saveMetadata(config.id, ContainerMetadata("Forbidden"), null) },
                { runtime.clone(config.id, "Forbidden") },
                { runtime.exportRootfs(config.id, File(directory, "busy.tar.zst").path) },
                { runtime.openProcess(config.id, listOf("/bin/true")) },
                { runtime.openRunningProcess(config.id, listOf("/bin/true")) },
                { runtime.openTerminal(config.id) },
                { runtime.register(root(directory, "reserved-clone", "reserved").copy(isDefault = false)) },
            )
            rejected.forEach { call ->
                val failure = assertFailsWith<IllegalStateException> { withTimeout(10_000) { call() } }
                assertTrue(failure.message.orEmpty().contains("正在归档"))
            }
            val generation = runtime.containers.value.first { it.config.id == config.id }.stopGeneration
            runtime.stop(config.id)
            release.complete(Unit)
            operation.await()
            val stopped = runtime.containers.value.first { it.config.id == config.id }
            assertFalse(stopped.running)
            assertEquals(generation + 1, stopped.stopGeneration)
            assertEquals("Other remains editable", runtime.readMetadata(other.id).value.title)
            runtime.readMetadata(config.id) // The lease was released without restoring an old config.
        } finally { release.complete(Unit); runtime.close(); awaitSizeWork(runtime) }
    } }

    @Test fun cancellationAndFailureReleaseTheLeaseAndPreserveTheOriginalFailure() = fixture { _, data, config -> runBlocking {
        val runtime = LocalContainerRuntime(RuntimePaths(data, hostProot = "/missing-engine-must-not-be-launched"))
        try {
            runtime.register(config)
            val entered = CompletableDeferred<Unit>()
            val operation = async {
                runtime.withStoppedRootfs(config.id) { entered.complete(Unit); awaitCancellation() }
            }
            entered.await()
            operation.cancelAndJoin()
            runtime.readMetadata(config.id)
            val original = IllegalArgumentException("original archive failure")
            val failure = assertFails { runtime.withStoppedRootfs(config.id) { throw original } }
            assertSame(original, failure)
            runtime.readMetadata(config.id)
            assertFalse(runtime.containers.value.single().running)
            assertEquals(0L, runtime.containers.value.single().runGeneration)
        } finally { runtime.close(); awaitSizeWork(runtime) }
    } }

    @Test fun closeDoesNotWaitForTheArchiveActionOrReopenTheContainer() = fixture { _, data, config -> runBlocking {
        val runtime = LocalContainerRuntime(RuntimePaths(data, hostProot = "/missing-engine-must-not-be-launched"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            runtime.register(config)
            val operation = async {
                runtime.withStoppedRootfs(config.id) {
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                }
            }
            entered.await()
            withTimeout(10_000) { withContext(Dispatchers.IO) { runtime.close() } }
            val closed = runtime.containers.value
            release.complete(Unit)
            operation.await()
            assertEquals(closed, runtime.containers.value)
            assertFailsWith<IllegalStateException> { runtime.start(config.id) }
        } finally { release.complete(Unit); runtime.close(); awaitSizeWork(runtime) }
    } }

    private fun interruptedClone(close: Boolean) = fixture { directory, data, config -> runBlocking {
        val gate = SizeGate()
        val managedRoots = File(data, "rootfs").canonicalFile.toPath()
        val runtime = LocalContainerRuntime(RuntimePaths(data, hostProot = "/missing-engine-must-not-be-launched")) { root, excluded ->
            if (root.toPath().startsWith(managedRoots)) gate.measure(root, excluded) else RootfsArchives.size(root, excluded)
        }
        try {
            runtime.register(config)
            val other = root(directory, "other", "other").copy(isDefault = false)
            runtime.register(other)
            val cloning = async(Dispatchers.IO) { runCatching { runtime.clone(config.id, "Interrupted clone") } }
            gate.awaitEntry() // Actual export and extraction completed; clone size IO is still pending.
            withTimeout(10_000) {
                runtime.stop(other.id)
                runtime.saveMetadata(other.id, ContainerMetadata("Independent edit"), null)
            }
            assertFailsWith<IllegalStateException> { runtime.start(config.id) }
            if (close) {
                withTimeout(10_000) { withContext(Dispatchers.IO) { runtime.close() } }
                gate.release.countDown()
                assertTrue(cloning.await().isFailure)
            } else {
                val generation = runtime.containers.value.first { it.config.id == config.id }.stopGeneration
                runtime.stop(config.id)
                cloning.cancel()
                gate.release.countDown()
                cloning.cancelAndJoin()
                assertEquals(generation + 1, runtime.containers.value.first { it.config.id == config.id }.stopGeneration)
                assertEquals("Independent edit", runtime.readMetadata(other.id).value.title)
                runtime.readMetadata(config.id)
            }
            assertEquals(setOf(config.id, other.id), runtime.containers.value.map { it.config.id }.toSet())
            assertTrue(runtime.containers.value.all { !it.running && it.runGeneration == 0L })
            assertTrue(File(data, "rootfs").listFiles().orEmpty().isEmpty())
            assertTrue(File(data, "downloads").listFiles().orEmpty().isEmpty())
        } finally { gate.release.countDown(); runtime.close(); awaitSizeWork(runtime) }
    } }

    @Test fun cancellingAnActualCloneCleansItsFilesAndReleasesAdmission() = interruptedClone(close = false)

    @Test fun closingDuringAnActualCloneRejectsLateRegistrationAndCleansItsFiles() = interruptedClone(close = true)

    @Test fun cloneRegistrationFailureRollsBackConfigurationSizeAndPartialFiles() = fixture { _, data, config -> runBlocking {
        val runtime = LocalContainerRuntime(RuntimePaths(data, hostProot = "/missing-engine-must-not-be-launched"))
        try {
            runtime.register(config)
            awaitSizeWork(runtime)
            val original = runtime.containers.value.single()
            val state = File(data, "containers.json")
            assertTrue(state.delete())
            assertTrue(state.mkdir())
            val sentinel = File(state, "keep").apply { writeText("nonempty directories cannot be replaced") }
            assertFails { runtime.clone(config.id, "Uncommitted clone") }
            assertEquals(listOf(config.id), runtime.containers.value.map { it.config.id })
            assertEquals(original.sizeBytes, runtime.containers.value.single().sizeBytes)
            assertTrue(File(data, "rootfs").listFiles().orEmpty().isEmpty())
            assertTrue(File(data, "downloads").listFiles().orEmpty().isEmpty())
            assertTrue(sentinel.delete())
            assertTrue(state.delete())
            runtime.setDefault(config.id)
            val persisted = Json.parseToJsonElement(state.readText()).jsonObject
            assertEquals(setOf(config.id), persisted.getValue("sizes").jsonObject.keys)
            assertEquals(1, persisted.getValue("containers").let { Json.decodeFromString<List<ContainerConfig>>(it.toString()) }.size)
            runtime.readMetadata(config.id)
        } finally { runtime.close(); awaitSizeWork(runtime) }
    } }

    @Test fun exportFailurePreservesItsCauseAndRemovesTheTemporaryArchive() = fixture { directory, data, config -> runBlocking {
        val runtime = LocalContainerRuntime(RuntimePaths(data, hostProot = "/missing-engine-must-not-be-launched"))
        try {
            runtime.register(config)
            val target = File(config.rootfsPath, "forbidden.tar.zst")
            val failure = assertFailsWith<IllegalStateException> { runtime.exportRootfs(config.id, target.path) }
            // Coroutine stacktrace recovery can insert a copy of the export wrapper;
            // the original archive rejection must still survive in its cause chain.
            val cause = generateSequence(failure.cause) { it.cause }.firstOrNull { it is IllegalArgumentException }
            assertTrue(cause is IllegalArgumentException, failure.stackTraceToString())
            assertTrue(cause!!.message.orEmpty().contains("rootfs 内"), failure.stackTraceToString())
            assertFalse(target.exists())
            assertFalse(File(config.rootfsPath).listFiles().orEmpty().any { it.name.endsWith(".part") })
            runtime.readMetadata(config.id)
            val existing = File(directory, "existing.tar.zst").apply { writeText("keep existing bytes") }
            assertFails { runtime.exportRootfs(config.id, existing.path) }
            assertEquals("keep existing bytes", existing.readText())
        } finally { runtime.close(); awaitSizeWork(runtime) }
    } }
}
