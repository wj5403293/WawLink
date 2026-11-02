package dev.wawlink.driver

import dev.wawlink.protocol.ModelConfig
import dev.wawlink.protocol.ProviderApiType
import dev.wawlink.protocol.ProviderConfig
import dev.wawlink.protocol.ThinkingLevel
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

class ModelPayloadExtensionTest {
    @Test
    fun messageBudgetRoundTripRetainsHighestLevelsAndExactBoundary() = runBlocking<Unit> {
        TestOmpGuestFiles().use { fixture ->
            val session = fixture.files.session()
            val models = listOf(
                ModelConfig("xhigh", thinkingLevel = ThinkingLevel.XHIGH, thinkingBudget = 1024, maxTokens = 1025, reasoning = true),
                ModelConfig("max", thinkingLevel = ThinkingLevel.MAX, thinkingBudget = 2048, maxTokens = 4096, reasoning = true),
            )
            val provider = ProviderConfig("vendor", "Vendor", apiType = ProviderApiType.MESSAGE, models = models)
            ModelPayloadExtension.writeBudgets(session, fixture.guestDirectory, listOf(provider))
            assertEquals(mapOf("vendor/xhigh" to 1024, "vendor/max" to 2048), ModelPayloadExtension.readBudgets(session, fixture.guestDirectory))
            assertEquals(listOf(ThinkingLevel.XHIGH, ThinkingLevel.MAX), models.map { it.thinkingLevel })
            session.writeText("${fixture.guestDirectory}/wawlink-model-budgets.json", """{"vendor/invalid":1023}""")
            assertFailsWith<IllegalArgumentException> { ModelPayloadExtension.readBudgets(session, fixture.guestDirectory) }
        }
    }

    @Test fun extensionAndBudgetsRemainPrivateAcrossGuestAtomicReplacement() = runBlocking<Unit> {
        TestOmpGuestFiles("/home/operator").use { fixture ->
            val session = fixture.files.session()
            val guest = fixture.guestDirectory
            Files.setAttribute(fixture.directory.toPath(), "unix:mode", "700".toInt(8))
            val script = ModelPayloadExtension.install(session, guest)
            assertEquals("$guest/wawlink-model-payload.mjs", script)
            repeat(2) {
                session.writeText(script, "// native replacement\n")
                ModelPayloadExtension.install(session, guest)
                ModelPayloadExtension.writeBudgets(session, guest, emptyList())
                for (name in listOf("wawlink-model-payload.mjs", "wawlink-model-budgets.json")) {
                    val file = File(fixture.directory, name)
                    assertEquals("600".toInt(8), (Files.getAttribute(file.toPath(), "unix:mode") as Number).toInt() and 0x1ff)
                }
            }
            assertEquals("700".toInt(8), (Files.getAttribute(fixture.directory.toPath(), "unix:mode") as Number).toInt() and 0x1ff)
            assertEquals(emptyMap(), ModelPayloadExtension.readBudgets(session, guest))
        }
    }

    @Test fun stalePayloadOperationsNeverReconnectAfterContainerRestart() = runBlocking<Unit> {
        TestOmpGuestFiles().use { fixture ->
            val stale = fixture.files.session()
            val guest = fixture.guestDirectory
            ModelPayloadExtension.install(stale, guest)
            ModelPayloadExtension.writeBudgets(stale, guest, emptyList())
            fixture.stop()
            fixture.restart()
            assertFailsWith<OmpProcessStoppedException> { ModelPayloadExtension.install(stale, guest) }
            assertFailsWith<OmpProcessStoppedException> { ModelPayloadExtension.readBudgets(stale, guest) }
            assertFailsWith<OmpProcessStoppedException> { ModelPayloadExtension.writeBudgets(stale, guest, emptyList()) }
            assertEquals(0, fixture.starts)
            val active = fixture.files.session(runningOnly = true)
            assertEquals(emptyMap(), ModelPayloadExtension.readBudgets(active, guest))
        }
    }

    @Test
    fun rejectsDisabledThinkingUnsupportedApiAndExhaustedOutputBudget() = runBlocking<Unit> {
        TestOmpGuestFiles().use { fixture ->
            val session = fixture.files.session()
            val valid = ModelConfig("model", thinkingBudget = 1024, maxTokens = 1025)
            val provider = ProviderConfig("vendor", "Vendor", apiType = ProviderApiType.MESSAGE)
            for (model in listOf(valid.copy(thinkingLevel = ThinkingLevel.NONE), valid.copy(reasoning = false), valid.copy(maxTokens = 1024), valid.copy(thinkingBudget = 1023))) {
                assertFailsWith<IllegalArgumentException> { ModelPayloadExtension.writeBudgets(session, fixture.guestDirectory, listOf(provider.copy(models = listOf(model)))) }
            }
            assertFailsWith<IllegalArgumentException> {
                ModelPayloadExtension.writeBudgets(session, fixture.guestDirectory, listOf(provider.copy(apiType = ProviderApiType.RESPONSE, models = listOf(valid))))
            }
        }
    }
}
