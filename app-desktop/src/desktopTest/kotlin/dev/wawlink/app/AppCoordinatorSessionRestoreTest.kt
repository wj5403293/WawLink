package dev.wawlink.app

import dev.wawlink.driver.OmpProcessPool
import dev.wawlink.protocol.FeedItem
import dev.wawlink.protocol.StepGroupNodeItem
import dev.wawlink.protocol.UserMessageItem
import dev.wawlink.runtime.ContainerConfig
import dev.wawlink.runtime.ContainerInfo
import dev.wawlink.runtime.ContainerRuntime
import dev.wawlink.runtime.DeploymentProgress
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Exercises the coordinator with the real process pool and durable journals, without an engine or model. */
class AppCoordinatorSessionRestoreTest {
    private class Fixture(count: Int) : AutoCloseable {
        val directory = Files.createTempDirectory("wawlink-lazy-sessions-").toFile()
        val ids = List(count) { "history-$it" }
        val containers = MutableStateFlow<List<ContainerInfo>>(emptyList())
        private val deployments = MutableStateFlow<List<DeploymentProgress>>(emptyList())
        private val engineCalls = CopyOnWriteArrayList<String>()
        private val configuration = ContainerConfig("fixture", "历史容器", File(directory, "rootfs").path)
        private val runtime = Proxy.newProxyInstance(ContainerRuntime::class.java.classLoader, arrayOf(ContainerRuntime::class.java)) { _, method, _ ->
            when (method.name) {
                "getContainers" -> containers
                "getDeployments" -> deployments
                "close" -> Unit
                else -> {
                    engineCalls += method.name
                    error("Unexpected engine API: ${method.name}")
                }
            }
        } as ContainerRuntime
        val app: AppCoordinator

        init {
            val metadata = Properties().apply {
                setProperty("ids", ids.joinToString(","))
                ids.forEachIndexed { index, id ->
                    setProperty("$id.title", "历史 $index")
                    setProperty("$id.container", configuration.id)
                    setProperty("$id.workspace", "/workspace/$index")
                    setProperty("$id.updated", index.toString())
                    journal(id).apply {
                        parentFile.mkdirs()
                        writeText("""{"type":"wawlink_local","action":"auto","enabled":true}
{"type":"wawlink_local","action":"user","localId":"message-$id","text":"saved-$id","timestamp":1}
""")
                    }
                }
            }
            File(directory, "sessions.properties").outputStream().use { metadata.store(it, null) }
            app = AppCoordinator(runtime, OmpProcessPool(runtime, directory), directory)
        }

        fun publishContainers(running: Boolean = false) { containers.value = listOf(ContainerInfo(configuration, running = running)) }
        fun driverMetadata(id: String) = File(directory, "omp-sessions/${hash(id)}.json")
        fun journal(id: String) = File(directory, "omp-sessions/${hash(id)}.jsonl")
        fun assertNoEngineCalls() { assertTrue(engineCalls.isEmpty(), "Engine calls: $engineCalls") }
        override fun close() { app.close(); directory.deleteRecursively() }
        private fun hash(id: String) = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    @Test fun manyHistoricalSessionsStayMetadataOnlyAcrossContainerUpdates() = runBlocking {
        Fixture(64).use { fixture ->
            val originalJournals = fixture.ids.associateWith { fixture.journal(it).readText() }
            fixture.publishContainers()
            val sessions = withTimeout(5_000) { fixture.app.sessions.first { it.size == fixture.ids.size } }
            assertEquals("历史 63", sessions.first().record.title)
            assertTrue(sessions.all { it.state.feed.isEmpty() && it.state.workspace == it.record.workspace })
            fixture.publishContainers(running = true)
            withTimeout(5_000) { fixture.app.active.first { it } }
            fixture.publishContainers()
            withTimeout(5_000) { fixture.app.active.first { !it } }
            assertNull(withTimeoutOrNull(200) { fixture.app.sessions.first { items -> items.any { it.state.feed.isNotEmpty() } } })
            fixture.ids.forEach { id ->
                assertNull(fixture.app.openedSession(id))
                assertFalse(fixture.driverMetadata(id).exists(), "Unopened session acquired driver metadata: $id")
                assertEquals(originalJournals.getValue(id), fixture.journal(id).readText())
            }
            fixture.assertNoEngineCalls()
        }
    }

    @Test fun openingOneSessionRestoresOnlyItsFeedAndKeepsObservingIt() = runBlocking {
        Fixture(32).use { fixture ->
            fixture.publishContainers()
            val selected = "history-7"
            val controller = fixture.app.openSession(selected)
            val sessions = withTimeout(5_000) {
                fixture.app.sessions.first { items -> items.any { it.record.id == selected && containsSavedMessage(it.state.feed, selected) } }
            }
            assertTrue(containsSavedMessage(controller.state.value.feed, selected))
            assertTrue(sessions.filterNot { it.record.id == selected }.all { it.state.feed.isEmpty() })
            withTimeout(5_000) { while (!fixture.driverMetadata(selected).exists()) delay(10) }
            fixture.ids.filterNot { it == selected }.forEach { id ->
                assertNull(fixture.app.openedSession(id))
                assertFalse(fixture.driverMetadata(id).exists())
            }
            // No chat composition is retained here: home state must still observe the opened controller.
            controller.toggleExecutionMode(false)
            withTimeout(5_000) { fixture.app.sessions.first { items -> items.any { it.record.id == selected && !it.state.autoApprove } } }
            assertSame(controller, fixture.app.openedSession(selected))
            fixture.assertNoEngineCalls()
        }
    }

    @Test fun repeatedAndConcurrentOpensReuseTheSameRestoredController() = runBlocking {
        Fixture(8).use { fixture ->
            fixture.publishContainers()
            val selected = "history-3"
            val first = fixture.app.openSession(selected)
            val opened = coroutineScope { List(24) { async(Dispatchers.Default) { fixture.app.openSession(selected) } }.awaitAll() }
            opened.forEach { assertSame(first, it) }
            withTimeout(5_000) { first.state.first { containsSavedMessage(it.feed, selected) } }
            assertEquals(1, countSavedMessages(first.state.value.feed, selected))
            fixture.ids.filterNot { it == selected }.forEach { assertNull(fixture.app.openedSession(it)) }
            fixture.assertNoEngineCalls()
        }
    }

    @Test fun explicitCreationForkAndMigrationRetainHistoryAndMetadata() = runBlocking {
        Fixture(4).use { fixture ->
            fixture.publishContainers()
            val source = "history-1"
            fixture.app.openSession(source)
            withTimeout(5_000) { fixture.app.sessions.first { items -> items.any { it.record.id == source && containsSavedMessage(it.state.feed, source) } } }
            withTimeout(5_000) { while (!fixture.driverMetadata(source).exists()) delay(10) }
            val created = fixture.app.create("新建", "fixture", "/new-workspace")
            assertSame(fixture.app.openedSession(created), fixture.app.openSession(created))
            val branch = fixture.app.fork(source)
            withTimeout(5_000) { fixture.app.sessions.first { items -> items.any { it.record.id == branch && containsSavedMessage(it.state.feed, source) } } }
            fixture.app.editSession(branch, "迁移分支", "/moved-workspace", "fixture")
            val migrated = withTimeout(5_000) {
                fixture.app.sessions.first { items -> items.any { it.record.id == branch && it.record.workspace == "/moved-workspace" && containsSavedMessage(it.state.feed, source) } }
            }.first { it.record.id == branch }
            assertEquals("迁移分支", migrated.record.title)
            val metadata = Properties().apply { File(fixture.directory, "sessions.properties").inputStream().use(::load) }
            assertEquals((fixture.ids + created + branch).toSet(), metadata.getProperty("ids").split(',').toSet())
            assertEquals("迁移分支", metadata.getProperty("$branch.title"))
            assertEquals("/moved-workspace", metadata.getProperty("$branch.workspace"))
            fixture.ids.filterNot { it == source }.forEach { assertNull(fixture.app.openedSession(it)) }
            fixture.assertNoEngineCalls()
        }
    }

    private fun containsSavedMessage(feed: List<FeedItem>, id: String): Boolean = countSavedMessages(feed, id) > 0
    private fun countSavedMessages(feed: List<FeedItem>, id: String): Int = feed.sumOf { item ->
        when (item) {
            is UserMessageItem -> if (item.id == "message-$id" && item.text == "saved-$id") 1 else 0
            is StepGroupNodeItem -> countSavedMessages(item.children, id)
            else -> 0
        }
    }
}
