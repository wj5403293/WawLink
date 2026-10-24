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
import kotlin.test.assertFailsWith
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

/** Exercises the coordinator with real durable journals and an unavailable container engine. */
class AppCoordinatorSessionRestoreTest {
    private class Fixture(count: Int, titleSource: SessionTitleSource? = null, journalOverride: String? = null) : AutoCloseable {
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
        var app: AppCoordinator
            private set

        init {
            val metadata = Properties().apply {
                setProperty("ids", ids.joinToString(","))
                ids.forEachIndexed { index, id ->
                    setProperty("$id.title", "历史 $index")
                    titleSource?.let { setProperty("$id.titleSource", it.name) }
                    setProperty("$id.container", configuration.id)
                    setProperty("$id.workspace", "/workspace/$index")
                    setProperty("$id.updated", index.toString())
                    journal(id).apply {
                        parentFile.mkdirs()
                        writeText(journalOverride ?: """{"type":"wawlink_local","action":"auto","enabled":true}
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
        fun metadata() = Properties().apply { File(directory, "sessions.properties").inputStream().use(::load) }
        fun restart(beforeRestart: () -> Unit = {}) {
            app.close()
            beforeRestart()
            app = AppCoordinator(runtime, OmpProcessPool(runtime, directory), directory)
        }
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
        }
    }

    @Test fun pendingTitlesUseTheFirstGroupedAcceptedQuestionAndStayAlignedAfterRestart() = runBlocking {
        val prefix = "a".repeat(47) + "😀"
        val question = prefix + " should not fit"
        val journal = """{"type":"agent_start"}
{"type":"todo_update","todos":[{"content":"Work","status":"in_progress"}]}
{"type":"wawlink_local","action":"user","localId":"first","text":"  $question\n more","timestamp":1,"preserveRun":true}
{"type":"message_start","messageId":"native-first","message":{"role":"user","content":"  $question\n more","timestamp":1}}
{"type":"wawlink_local","action":"user","localId":"second","text":"Later question","timestamp":2,"preserveRun":true}
{"type":"message_start","messageId":"native-second","message":{"role":"user","content":"Later question","timestamp":2}}
"""
        Fixture(2, SessionTitleSource.PENDING, journal).use { fixture ->
            fixture.publishContainers()
            val id = fixture.ids.first()
            val beforeOpen = withTimeout(5_000) { fixture.app.sessions.first { it.size == 2 } }
            assertTrue(beforeOpen.all { it.state.feed.isEmpty() && it.record.titleSource == SessionTitleSource.PENDING })
            assertNull(fixture.app.openedSession(id))
            val controller = fixture.app.openSession(id)
            val home = withTimeout(5_000) { fixture.app.sessions.first { items -> items.any { it.record.id == id && it.record.titleSource == SessionTitleSource.FIRST_QUESTION } } }.first { it.record.id == id }
            assertEquals(prefix, home.record.title)
            assertEquals(prefix, home.state.title)
            assertTrue(home.state.feed.filterIsInstance<StepGroupNodeItem>().any { group -> group.children.any { it is UserMessageItem && it.id == "first" } })
            withTimeout(5_000) { controller.state.first { it.title == prefix } }
            withTimeout(5_000) { while (fixture.metadata().getProperty("$id.titleSource") != SessionTitleSource.FIRST_QUESTION.name) delay(10) }
            fixture.restart()
            val restored = withTimeout(5_000) { fixture.app.sessions.first { it.size == 2 } }.first { it.record.id == id }
            assertEquals(prefix, restored.record.title)
            assertEquals(SessionTitleSource.FIRST_QUESTION, restored.record.titleSource)
            assertTrue(restored.state.feed.isEmpty())
            fixture.ids.forEach { assertNull(fixture.app.openedSession(it)) }
            val reopened = fixture.app.openSession(id)
            withTimeout(5_000) { reopened.state.first { it.feed.isNotEmpty() && it.title == prefix } }
            assertNull(fixture.app.openedSession(fixture.ids.last()))
        }
    }

    @Test fun blankCreationRemainsPendingAcrossRestartButAnExplicitPlaceholderTitleDoesNot() = runBlocking {
        Fixture(0).use { fixture ->
            fixture.publishContainers()
            val pending = fixture.app.create("  ", "fixture", "/workspace")
            val explicit = fixture.app.create(" 新会话 ", "fixture", "/workspace")
            fixture.restart {
                listOf(pending, explicit).forEach { id ->
                    fixture.journal(id).writeText("""{"type":"message_start","messageId":"question","message":{"role":"user","content":" First\n question ","timestamp":1}}
""")
                }
            }
            val restored = withTimeout(5_000) { fixture.app.sessions.first { it.size == 2 } }
            assertEquals(SessionTitleSource.PENDING, restored.first { it.record.id == pending }.record.titleSource)
            assertEquals(SessionTitleSource.MANUAL, restored.first { it.record.id == explicit }.record.titleSource)
            assertTrue(restored.all { it.record.title == "新会话" && it.state.feed.isEmpty() })
            val pendingController = fixture.app.openSession(pending)
            withTimeout(5_000) { pendingController.state.first { it.title == "First question" } }
            val explicitController = fixture.app.openSession(explicit)
            withTimeout(5_000) { explicitController.state.first { it.feed.isNotEmpty() } }
            assertEquals("新会话", explicitController.state.value.title)
            fixture.app.editSession(pending, " 固定标题 ", "/workspace", "fixture")
            assertEquals("固定标题", fixture.metadata().getProperty("$pending.title"))
            assertEquals(SessionTitleSource.MANUAL.name, fixture.metadata().getProperty("$pending.titleSource"))
        }
    }

    @Test fun legacyAndExplicitTitlesNeverBecomeAutomaticWhenHistoryIsOpened() = runBlocking {
        Fixture(2, journalOverride = """{"type":"message_start","messageId":"accepted","message":{"role":"user","content":"A real historical question","timestamp":1}}
""").use { fixture ->
            fixture.publishContainers()
            val legacy = fixture.ids.first()
            val controller = fixture.app.openSession(legacy)
            withTimeout(5_000) { controller.state.first { it.feed.isNotEmpty() } }
            fixture.app.editSession(legacy, "新会话", "/workspace/0", "fixture")
            val home = withTimeout(5_000) { fixture.app.sessions.first { items -> items.any { it.record.id == legacy && it.record.title == "新会话" } } }.first { it.record.id == legacy }
            assertEquals(SessionTitleSource.MANUAL, home.record.titleSource)
            fixture.restart()
            val reopened = fixture.app.openSession(legacy)
            withTimeout(5_000) { reopened.state.first { it.feed.isNotEmpty() && it.title == "新会话" } }
            assertEquals(SessionTitleSource.MANUAL.name, fixture.metadata().getProperty("$legacy.titleSource"))
        }
    }

    @Test fun rejectedFirstSendStaysUntitledUntilAnAcceptedQuestionArrives() = runBlocking {
        Fixture(1, SessionTitleSource.PENDING, journalOverride = "").use { fixture ->
            fixture.publishContainers()
            val id = fixture.ids.single()
            val controller = fixture.app.openSession(id)
            controller.sendPrompt("This failed send must not become the title")
            withTimeout(5_000) { controller.state.first { it.error != null && it.feed.isEmpty() } }
            val failed = withTimeout(5_000) { fixture.app.sessions.first { items -> items.singleOrNull()?.state?.error != null } }.single()
            assertEquals(SessionTitleSource.PENDING, failed.record.titleSource)
            assertEquals("历史 0", failed.record.title)
            assertEquals(SessionTitleSource.PENDING.name, fixture.metadata().getProperty("$id.titleSource"))
            fixture.restart {
                fixture.journal(id).appendText("""{"type":"message_start","messageId":"accepted","message":{"role":"user","content":"Actually admitted question","timestamp":2}}
""")
            }
            val accepted = fixture.app.openSession(id)
            withTimeout(5_000) { accepted.state.first { it.title == "Actually admitted question" } }
            val titled = withTimeout(5_000) { fixture.app.sessions.first { items -> items.singleOrNull()?.record?.titleSource == SessionTitleSource.FIRST_QUESTION } }.single()
            assertEquals("Actually admitted question", titled.record.title)
        }
    }

    @Test fun forkingAnAutomaticallyNamedSessionKeepsItsExplicitBranchTitle() = runBlocking {
        val journal = """{"type":"message_start","messageId":"accepted","message":{"role":"user","content":"Original question","timestamp":1}}
"""
        Fixture(1, SessionTitleSource.PENDING, journal).use { fixture ->
            fixture.publishContainers()
            val source = fixture.ids.single()
            val controller = fixture.app.openSession(source)
            withTimeout(5_000) { controller.state.first { it.title == "Original question" } }
            val branch = fixture.app.fork(source)
            val home = withTimeout(5_000) { fixture.app.sessions.first { items -> items.any { it.record.id == branch && it.state.feed.isNotEmpty() } } }.first { it.record.id == branch }
            assertEquals("Original question 分支", home.record.title)
            assertEquals(SessionTitleSource.MANUAL, home.record.titleSource)
            withTimeout(5_000) { fixture.app.openSession(branch).state.first { it.title == "Original question 分支" } }
            fixture.restart()
            val restored = withTimeout(5_000) { fixture.app.sessions.first { it.size == 2 } }
            assertEquals(SessionTitleSource.FIRST_QUESTION, restored.first { it.record.id == source }.record.titleSource)
            assertEquals(SessionTitleSource.MANUAL, restored.first { it.record.id == branch }.record.titleSource)
            assertTrue(restored.all { it.state.feed.isEmpty() })
        }
    }

    @Test fun newContextConversationUsesOnlySourceMetadataAndCurrentApprovalDefault() = runBlocking {
        Fixture(2).use { fixture ->
            fixture.publishContainers()
            fixture.app.updateDefaultAutoApprove(false)
            val source = fixture.ids.first()
            val created = fixture.app.createInContext(source)
            val home = withTimeout(5_000) { fixture.app.sessions.first { items -> items.any { it.record.id == created } } }.first { it.record.id == created }
            assertEquals("fixture", home.record.containerId)
            assertEquals("/workspace/0", home.record.workspace)
            assertEquals("新会话", home.record.title)
            assertEquals(SessionTitleSource.PENDING, home.record.titleSource)
            assertFalse(fixture.app.openSession(created).state.value.autoApprove)
            assertTrue(fixture.app.openSession(created).state.value.feed.isEmpty())
            fixture.ids.forEach { assertNull(fixture.app.openedSession(it)) }
            assertFailsWith<IllegalStateException> { fixture.app.createInContext("missing") }
            fixture.containers.value = listOf(ContainerInfo(ContainerConfig("other", "其他容器", File(fixture.directory, "other").path)))
            assertFailsWith<IllegalArgumentException> { fixture.app.createInContext(source) }
            assertEquals(3, fixture.metadata().getProperty("ids").split(',').size)
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
