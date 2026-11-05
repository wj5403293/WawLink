package dev.wawlink.driver

import dev.wawlink.runtime.ContainerConfig
import dev.wawlink.runtime.ContainerInfo
import dev.wawlink.runtime.ContainerRuntime
import dev.wawlink.runtime.LocalContainerRuntime
import dev.wawlink.runtime.RuntimePaths
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking

class OmpInstallationTest {
    private class ResultProcess(output: String, private val status: Int = 0, diagnostic: String = "") : Process() {
        private val stdout = ByteArrayInputStream(output.toByteArray())
        private val stderr = ByteArrayInputStream(diagnostic.toByteArray())
        private val stdin = ByteArrayOutputStream()
        override fun getInputStream() = stdout
        override fun getErrorStream() = stderr
        override fun getOutputStream() = stdin
        override fun waitFor() = status
        override fun exitValue() = status
        override fun destroy() = Unit
        override fun isAlive() = false
    }

    private suspend fun fixture(block: suspend (ContainerRuntime) -> Unit) {
        val directory = Files.createTempDirectory("wawlink-install-check-").toFile()
        val base = LocalContainerRuntime(RuntimePaths(directory))
        val containerState = MutableStateFlow(listOf(ContainerInfo(ContainerConfig("guest", "验证", directory.path), running = true)))
        val runtime = object : ContainerRuntime by base { override val containers = containerState }
        try { block(runtime) } finally { base.close(); directory.deleteRecursively() }
    }

    @Test fun engineLaunchFailureIsNotMisreportedAsMissingOmp() = runBlocking {
        fixture { base ->
            val failure = IllegalStateException("Cannot launch container engine: missing executable")
            val runtime = object : ContainerRuntime by base {
                override suspend fun openProcess(id: String, arguments: List<String>, cwd: String, environment: Map<String, String>): Process = throw failure
            }
            assertFailsWith<IllegalStateException> { OmpInstallation.inspect(runtime, "guest") }
        }
    }

    @Test fun failedEngineExitCannotForgeAMissingInstallationResult() = runBlocking {
        fixture { base ->
            val runtime = object : ContainerRuntime by base {
                override suspend fun openProcess(id: String, arguments: List<String>, cwd: String, environment: Map<String, String>): Process =
                    ResultProcess("WAWLINK_OMP_CHECK\tMISSING\tmissing\n", 127, "guest interpreter failed")
            }
            val error = assertFailsWith<IllegalStateException> { OmpInstallation.inspect(runtime, "guest") }
            assertTrue(error.message.orEmpty().contains("127"))
            assertTrue(error.message.orEmpty().contains("guest interpreter failed"))
        }
    }

    @Test fun incompleteAmbiguousAndUnknownProbeFramesAreErrorsNotReinstallSuggestions() {
        for (output in listOf("", "bun not found", "WAWLINK_OMP_CHECK\tUNKNOWN\tmessage\n",
            "WAWLINK_OMP_CHECK\tREADY\n", "WAWLINK_OMP_CHECK\tREADY\t \n",
            "WAWLINK_OMP_CHECK\tREADY\tok\nWAWLINK_OMP_CHECK\tMISSING\tmissing\n")) {
            assertFailsWith<IllegalStateException>(output) { OmpInstallation.decodeCheck(output) }
        }
    }

    @Test fun bundledInstallerRepairsDpkgBeforeAptPrerequisites() {
        val installer = OmpInstallation.command()
        val repair = installer.indexOf("DEBIAN_FRONTEND=noninteractive dpkg --configure -a")
        val aptBranch = installer.indexOf("apt-get -o APT::Update::Error-Mode=any update")
        assertTrue(repair >= 0)
        assertTrue(aptBranch > repair)
        assertTrue(installer.indexOf("repair_dpkg", repair) < aptBranch)
    }

    @Test fun staleInspectionAndRunningOnlyInspectionNeverOpenStoppedContainer() = runBlocking {
        fixture { base ->
            val containerState = MutableStateFlow(base.containers.value.map { it.copy(running = false, stopGeneration = 1) })
            var starts = 0
            val runtime = object : ContainerRuntime by base {
                override val containers = containerState
                override suspend fun openProcess(id: String, arguments: List<String>, cwd: String, environment: Map<String, String>): Process {
                    starts++
                    return ResultProcess("WAWLINK_OMP_CHECK\tBROKEN\tunused\n")
                }
            }
            assertFailsWith<OmpProcessStoppedException> { OmpInstallation.inspect(runtime, "guest", stopGeneration = 0) }
            assertFailsWith<OmpProcessStoppedException> { OmpInstallation.inspect(runtime, "guest", runningOnly = true) }
            assertEquals(0, starts)
        }
    }

    @Test fun inspectionResultFromBeforeRapidRestartIsInterruptedInsteadOfSuggestingRepair() = runBlocking {
        fixture { base ->
            val containerState = MutableStateFlow(base.containers.value)
            val opened = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Process>()
            var starts = 0
            val runtime = object : ContainerRuntime by base {
                override val containers = containerState
                override suspend fun openRunningProcess(id: String, arguments: List<String>, cwd: String, environment: Map<String, String>): Process {
                    starts++
                    opened.complete(Unit)
                    return gate.await()
                }
            }
            val inspection = async { runCatching { OmpInstallation.inspect(runtime, "guest", runningOnly = true) } }
            withTimeout(5_000) { opened.await() }
            containerState.value = containerState.value.map { it.copy(running = false, stopGeneration = 1) }
            containerState.value = containerState.value.map { it.copy(running = true) }
            gate.complete(ResultProcess("WAWLINK_OMP_CHECK\tBROKEN\tstale result\n"))
            assertTrue(withTimeout(5_000) { inspection.await() }.exceptionOrNull() is OmpProcessStoppedException)
            assertEquals(1, starts)
        }
    }

}
