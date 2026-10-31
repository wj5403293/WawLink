package dev.wawlink.driver

import dev.wawlink.protocol.ModelConfig
import dev.wawlink.protocol.ProviderApiType
import dev.wawlink.protocol.ProviderConfig
import dev.wawlink.protocol.ThinkingLevel
import dev.wawlink.runtime.ContainerConfig
import dev.wawlink.runtime.ContainerEngineType
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModelPayloadExtensionTest {
    @Test
    fun messageBudgetRoundTripRetainsHighestLevelsAndExactBoundary() {
        val dir = Files.createTempDirectory("wawlink-budget-test").toFile()
        try {
            val models = listOf(
                ModelConfig("xhigh", thinkingLevel = ThinkingLevel.XHIGH, thinkingBudget = 1024, maxTokens = 1025, reasoning = true),
                ModelConfig("max", thinkingLevel = ThinkingLevel.MAX, thinkingBudget = 2048, maxTokens = 4096, reasoning = true),
            )
            val provider = ProviderConfig("vendor", "Vendor", apiType = ProviderApiType.MESSAGE, models = models)
            ModelPayloadExtension.writeBudgets(dir, listOf(provider))
            assertEquals(mapOf("vendor/xhigh" to 1024, "vendor/max" to 2048), ModelPayloadExtension.readBudgets(dir))
            assertEquals(listOf(ThinkingLevel.XHIGH, ThinkingLevel.MAX), models.map { it.thinkingLevel })
            val file = File(dir, "wawlink-model-budgets.json")
            file.writeText("""{"vendor/invalid":1023}""")
            assertFailsWith<IllegalArgumentException> { ModelPayloadExtension.readBudgets(dir) }
        } finally { dir.deleteRecursively() }
    }

    @Test fun chrootExtensionAndBudgetsRemainHostAccessibleAndGroupSharedAfterUpdates() {
        val root = Files.createTempDirectory("wawlink-extension-sharing-").toFile()
        try {
            val configuration = ContainerConfig("fixture", "权限", root.path, engine = ContainerEngineType.CHROOT)
            val dir = File(root, "home/operator/.omp/agent")
            val guest = "/home/operator/.omp/agent"
            val script = ModelPayloadExtension.install(dir, guest, configuration)
            assertEquals("$guest/wawlink-model-payload.mjs", script)
            repeat(2) { ModelPayloadExtension.writeBudgets(dir, emptyList(), configuration) }
            for (file in listOf(File(dir, "wawlink-model-payload.mjs"), File(dir, "wawlink-model-budgets.json"))) {
                assertEquals("660".toInt(8), (Files.getAttribute(file.toPath(), "unix:mode") as Number).toInt() and 0x1ff)
                assertEquals(true, file.canRead() && file.canWrite())
            }
            assertEquals(emptyMap(), ModelPayloadExtension.readBudgets(dir))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun rejectsDisabledThinkingUnsupportedApiAndExhaustedOutputBudget() {
        val dir = Files.createTempDirectory("wawlink-budget-test").toFile()
        try {
            val valid = ModelConfig("model", thinkingBudget = 1024, maxTokens = 1025)
            val provider = ProviderConfig("vendor", "Vendor", apiType = ProviderApiType.MESSAGE)
            for (model in listOf(valid.copy(thinkingLevel = ThinkingLevel.NONE), valid.copy(reasoning = false), valid.copy(maxTokens = 1024), valid.copy(thinkingBudget = 1023))) {
                assertFailsWith<IllegalArgumentException> { ModelPayloadExtension.writeBudgets(dir, listOf(provider.copy(models = listOf(model)))) }
            }
            assertFailsWith<IllegalArgumentException> {
                ModelPayloadExtension.writeBudgets(dir, listOf(provider.copy(apiType = ProviderApiType.RESPONSE, models = listOf(valid))))
            }
        } finally { dir.deleteRecursively() }
    }
}
