package dev.wawlink.driver

import dev.wawlink.runtime.ContainerRuntime
import dev.wawlink.runtime.LocalContainerRuntime
import dev.wawlink.runtime.RuntimePaths
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
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
        try { block(base) } finally { base.close(); directory.deleteRecursively() }
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

}
