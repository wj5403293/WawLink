package dev.wawlink.app

import dev.wawlink.driver.OmpProcessPool
import dev.wawlink.runtime.ContainerConfig
import dev.wawlink.runtime.ContainerInfo
import dev.wawlink.runtime.ContainerRuntime
import dev.wawlink.runtime.LocalContainerRuntime
import dev.wawlink.runtime.RuntimePaths
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppCoordinatorInterruptionTest {
    private class Fixture(private val waitForInput: Boolean = false) : AutoCloseable {
        private val directory = Files.createTempDirectory("wawlink-interruption-").toFile()
        private val base = LocalContainerRuntime(RuntimePaths(directory.resolve("runtime")))
        val containers = MutableStateFlow(listOf(ContainerInfo(ContainerConfig("guest", "测试容器", directory.resolve("root").path))))
        val probes = AtomicInteger()
        val opened = Channel<Process>(Channel.UNLIMITED)
        private val children = mutableListOf<Process>()
        private val runtime = object : ContainerRuntime by base {
            override val containers = this@Fixture.containers
            override suspend fun openProcess(id: String, arguments: List<String>, cwd: String, environment: Map<String, String>): Process {
                check(arguments.last() == "--inspect")
                probes.incrementAndGet()
                val script = (if (waitForInput) "read release; " else "") + "printf 'WAWLINK_OMP_CHECK\\tMISSING\\tOMP is missing\\n'"
                return ProcessBuilder("/bin/sh", "-c", script).start().also { synchronized(children) { children += it }; opened.send(it) }
            }
        }
        val app = AppCoordinator(runtime, OmpProcessPool(runtime, directory.resolve("pool")), directory.resolve("app"))
        fun interrupt() { containers.value = containers.value.map { it.copy(stopGeneration = it.stopGeneration + 1) } }
        override fun close() {
            app.close()
            synchronized(children) { children.forEach { it.destroyForcibly(); it.waitFor() } }
            directory.deleteRecursively()
        }
    }

    @Test fun automaticRepairRequestExpiresAcrossStopAndFreshCheckStillOffersRepair() = runBlocking {
        Fixture().use { fixture ->
            fixture.app.checkOmpInstallation("guest")
            val first = withTimeout(5_000) { fixture.app.ompSetup.first { it != null } }!!
            assertEquals(0L, first.stopGeneration)
            fixture.interrupt()
            withTimeout(5_000) { fixture.app.ompSetup.first { it == null } }
            assertNull(fixture.app.confirmOmpSetup(first), "A stale dialog must not authorize installation")
            fixture.app.checkOmpInstallation("guest")
            val next = withTimeout(5_000) { fixture.app.ompSetup.first { it != null } }!!
            assertEquals(1L, next.stopGeneration)
            assertNotEquals(first.id, next.id)
            assertEquals(2, fixture.probes.get())
        }
    }

    @Test fun delayedInstallationCheckCannotOfferRepairAfterContainerStop() = runBlocking {
        Fixture(waitForInput = true).use { fixture ->
            val check = async { fixture.app.checkOmpInstallation("guest") }
            val process = withTimeout(5_000) { fixture.opened.receive() }
            fixture.interrupt()
            runCatching { process.outputStream.write("continue\n".toByteArray()); process.outputStream.flush() }
            withTimeout(5_000) { check.await() }
            assertNull(fixture.app.ompSetup.value)
            assertNull(fixture.app.notice.value)
            assertEquals(1, fixture.probes.get())
        }
    }

    @Test fun approvalFromPreviousContainerLifetimeCannotOpenInstallationTerminal() = runBlocking {
        Fixture().use { fixture ->
            fixture.app.requestOmpInstallation("guest")
            val request = withTimeout(5_000) { fixture.app.ompSetup.first { it != null } }!!
            val approval = fixture.app.confirmOmpSetup(request)
            assertTrue(approval != null)
            fixture.interrupt()
            assertFailsWith<CancellationException> { fixture.app.openTerminal("guest", approval) }
            assertEquals(0, fixture.probes.get())
            assertNull(fixture.app.notice.value)
        }
    }
}
