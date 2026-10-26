package dev.wawlink.runtime

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContainerMetadataActionTest {
    @Test fun metadataEditsPreserveActionsButRunOrStopEpochChangesCancelThem() = runBlocking {
        val directory = Files.createTempDirectory("wawlink-metadata-action-test").toFile()
        val home = File(directory, "home").apply { mkdirs() }
        val featureDirectory = File(home, ".wawlink/features/demo").apply { mkdirs() }
        File(featureDirectory, "run.sh").writeText("printf 'action-ran'\n")
        val config = ContainerConfig("guest", "Guest", File(directory, "unused-rootfs").path)
        val state = MutableStateFlow(listOf(ContainerInfo(config, running = true, runGeneration = 1)))
        val base = LocalContainerRuntime(RuntimePaths(File(directory, "runtime")))
        var launches = 0
        val runtime = object : ContainerRuntime by base {
            override val containers = state
            override suspend fun openProcess(id: String, arguments: List<String>, cwd: String, environment: Map<String, String>): Process =
                error("Actions must not start a stopped container")
            override suspend fun openRunningProcess(id: String, arguments: List<String>, cwd: String, environment: Map<String, String>): Process {
                launches++
                return ProcessBuilder(arguments).apply {
                    environment().putAll(environment)
                    environment()["HOME"] = home.path
                }.start()
            }
        }
        val action = ContainerFeatureAction("run", "运行", "terminal", ContainerFeatureActionType.SCRIPT, script = "run.sh")
        val feature = ContainerFeature("demo", "演示", "", "terminal", featureDirectory.canonicalPath, actions = listOf(action))
        val actions = ContainerFeatureScriptActions(runtime)
        try {
            val output = actions.run(config.id, feature, action) {
                val current = state.value.single()
                state.value = listOf(current.copy(config = current.config.copy(metadata = ContainerMetadata("新标题", icon = "home"))))
            }
            assertEquals("action-ran", output)
            assertEquals(1, launches)
            assertFailsWith<CancellationException> {
                actions.run(config.id, feature, action) {
                    state.value = state.value.map { it.copy(runGeneration = it.runGeneration + 1) }
                }
            }
            assertEquals(1, launches)
            assertFailsWith<CancellationException> {
                actions.run(config.id, feature, action) {
                    state.value = state.value.map { it.copy(stopGeneration = it.stopGeneration + 1) }
                }
            }
            assertEquals(1, launches, "A stop epoch must cancel validation even if the container is already running again")
            File(featureDirectory, "run.sh").writeText("printf 'real-action-failure' >&2; exit 7\n")
            val failure = assertFailsWith<IllegalStateException> { actions.run(config.id, feature, action) {} }
            assertTrue(failure.message.orEmpty().contains("real-action-failure"))
            assertEquals(2, launches)
        } finally {
            actions.close()
            base.close()
            RootfsArchives.deleteTree(directory)
        }
    }
}
