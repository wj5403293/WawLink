package dev.wawlink.driver

import dev.wawlink.protocol.ModelConfig
import dev.wawlink.protocol.ProviderApiType
import dev.wawlink.protocol.ProviderConfig
import dev.wawlink.protocol.ThinkingLevel
import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.yaml.snakeyaml.Yaml

class OmpConfigRepositoriesTest {
    @Test
    fun operationalSettingsRemainAvailableWithoutTerminalAppearanceControls() {
        val ids = OmpSettingsSchema.items.keys
        assertTrue(ids.containsAll(setOf("modelRoleStorage", "temperature", "compaction.enabled", "hindsight.scoping", "edit.mode", "shellPath")))
        assertFalse(OmpSettingsSchema.categories.any { it.id == "appearance" })
        val excluded = setOf("composer.shape", "showHardwareCursor", "symbolPreset", "images.blockImages", "input.bareExitOnEmptySession", "doubleEscapeAction")
        assertFalse(ids.any { it in excluded || it.startsWith("tui.") || it.startsWith("statusLine.") || it.startsWith("terminal.") })
    }

    @Test
    fun settingEnumRoundTripResetAndCommentsUseRealWireValues() = runBlocking {
        withContainer { dir, scope ->
            val file = File(dir, "config.yml")
            file.writeText("""
                # operator comment
                future:
                  keep: 'unchanged' # unknown inline comment
                tier:
                  openai: none # tier comment
                tui:
                  mouse: true
                theme:
                  dark: nord
            """.trimIndent() + "\n")
            val calls = Collections.synchronizedList(mutableListOf<Pair<String?, Any?>>())
            val repo = OmpSettingsRepository({ dir }, scope) { id, value -> calls.add(id to value) }
            try {
                repo.updateSetting("tier.openai", "ultrafast")
                val updated = load(file)
                assertEquals("ultrafast", (updated["tier"] as Map<*, *>)["openai"])
                assertEquals("ultrafast", repo.categories.value.flatMap { it.groups }.flatMap { it.items }.single { it.id == "tier.openai" }.value)
                assertTrue(file.readText().contains("# operator comment"))
                assertTrue(file.readText().contains("# unknown inline comment"))
                assertTrue(file.readText().contains("# tier comment"))
                // A disk edit made after the last state publication must not be overwritten.
                file.appendText("anotherFuture: 17\n")
                repo.resetSetting("tier.openai")
                assertEquals("none", calls.last { it.first == "tier.openai" }.second)
                assertEquals(17, load(file)["anotherFuture"])
                repo.resetAll()
                val reset = load(file)
                assertEquals(true, (reset["tui"] as Map<*, *>)["mouse"])
                assertEquals("nord", (reset["theme"] as Map<*, *>)["dark"])
                assertEquals("unchanged", (reset["future"] as Map<*, *>)["keep"])
                assertTrue(file.readText().contains("# unknown inline comment"))
            } finally { repo.close() }
        }
    }

    @Test
    fun providerSaveUsesActualFormatsAndPreservesOAuthUnknownFieldsAndOtherBudgets() = runBlocking {
        withContainer { dir, scope ->
            val file = File(dir, "models.yml")
            file.writeText("""
                # model document comment
                futureRoot: preserve
                providers:
                  vendor:
                    api: anthropic-messages
                    baseUrl: https://example.invalid
                    auth: oauth
                    futureProvider: keep
                    models:
                      - id: model-a
                        name: Existing
                        contextWindow: 200000
                        maxTokens: 8192
                        futureModel: keep
                        thinking:
                          mode: anthropic-budget-effort
                          efforts: [low, high]
                          defaultLevel: high
                          futureThinking: keep
                          effortMap:
                            high: upstream-high
                  unsupported:
                    api: google-generative-ai
                    future: keep
            """.trimIndent() + "\n")
            val oauth = File(dir, "auth.json").apply { writeText("{\"vendor\":{\"type\":\"oauth\",\"access\":\"test-only\",\"future\":true}}\n") }
            val originalAuth = oauth.readText()
            val budgetsFile = File(dir, "wawlink-model-budgets.json")
            budgetsFile.writeText("{\"unsupported/model-z\":2048,\"vendor/old-model\":1024}\n")
            val repo = OmpProviderRepository({ dir }, scope) { _, _ -> }
            try {
                val provider = ProviderConfig(
                    id = "vendor", name = "自定义服务", apiType = ProviderApiType.MESSAGE,
                    baseUrl = "https://example.invalid", apiKey = "explicit-test-key", enabled = false,
                    models = listOf(ModelConfig("model-a", "Existing", ThinkingLevel.HIGH, "wire-high", 2048, 200_000, 8192)),
                )
                repo.saveProvider(provider)
                val all = load(file)
                assertEquals("preserve", all["futureRoot"])
                val providers = all["providers"] as Map<*, *>
                val saved = providers["vendor"] as Map<*, *>
                assertEquals("anthropic-messages", saved["api"])
                assertEquals("oauth", saved["auth"])
                assertEquals("keep", saved["futureProvider"])
                assertEquals("explicit-test-key", saved["apiKey"])
                assertFalse(saved.containsKey("name"))
                assertFalse(saved.containsKey("enabled"))
                val model = (saved["models"] as List<*>).single() as Map<*, *>
                assertFalse(model.containsKey("thinkingBudget"))
                assertEquals("keep", model["futureModel"])
                val thinking = model["thinking"] as Map<*, *>
                assertEquals("anthropic-budget-effort", thinking["mode"])
                assertEquals("high", thinking["defaultLevel"])
                assertEquals("keep", thinking["futureThinking"])
                assertEquals("wire-high", (thinking["effortMap"] as Map<*, *>)["high"])
                assertTrue(file.readText().contains("# model document comment"))
                assertEquals(originalAuth, oauth.readText())
                val budgets = Json.parseToJsonElement(budgetsFile.readText()).jsonObject
                assertEquals("2048", budgets.getValue("unsupported/model-z").jsonPrimitive.content)
                assertEquals("2048", budgets.getValue("vendor/model-a").jsonPrimitive.content)
                assertFalse(budgets.containsKey("vendor/old-model"))
                val loaded = repo.providers.value.single()
                assertEquals("自定义服务", loaded.name)
                assertEquals(2048, loaded.models.single().thinkingBudget)
                assertFalse(loaded.enabled)
                repo.deleteProvider("vendor")
                assertTrue((load(file)["providers"] as Map<*, *>).containsKey("unsupported"))
                assertEquals(originalAuth, oauth.readText())
                val remaining = Json.parseToJsonElement(budgetsFile.readText()).jsonObject
                assertEquals(setOf("unsupported/model-z"), remaining.keys)
            } finally { repo.close() }
        }
    }

    @Test
    fun providerApiTypesUseTheThreeRealWireNames() = runBlocking {
        withContainer { dir, scope ->
            val repo = OmpProviderRepository({ dir }, scope) { _, _ -> }
            try {
                val expected = mapOf(ProviderApiType.CHAT to "openai-completions", ProviderApiType.RESPONSE to "openai-responses", ProviderApiType.MESSAGE to "anthropic-messages")
                for ((api, wire) in expected) {
                    repo.saveProvider(ProviderConfig(api.name, api.name, api, "https://example.invalid/v1", models = listOf(ModelConfig("model"))))
                    val saved = (load(File(dir, "models.yml"))["providers"] as Map<*, *>)[api.name] as Map<*, *>
                    assertEquals(wire, saved["api"])
                }
            } finally { repo.close() }
        }
    }

    @Test
    fun settingsObserveExternalEditsAndNotifyWithEffectiveValues() = runBlocking {
        withContainer { dir, scope ->
            val file = File(dir, "config.yml").apply { writeText("temperature: 0.2\n") }
            val calls = Collections.synchronizedList(mutableListOf<Pair<String?, Any?>>())
            val repo = OmpSettingsRepository({ dir }, scope) { id, value -> calls.add(id to value) }
            try {
                withTimeout(4_000) { repo.categories.first { categories -> categories.flatMap { it.groups }.flatMap { it.items }.single { it.id == "temperature" }.value == 0.2 } }
                file.writeText("temperature: 0.7\n")
                withTimeout(4_000) { repo.categories.first { categories -> categories.flatMap { it.groups }.flatMap { it.items }.single { it.id == "temperature" }.value == 0.7 } }
                repo.resetSetting("temperature")
                assertEquals(-1L, calls.last { it.first == "temperature" }.second)
            } finally { repo.close() }
        }
    }

    private suspend fun withContainer(block: suspend (File, CoroutineScope) -> Unit) {
        val temporary = Files.createTempDirectory("wawlink-config-test").toFile()
        val dir = File(temporary, "rootfs/root/.omp/agent").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try { block(dir, scope) } finally { scope.cancel(); temporary.deleteRecursively() }
    }

    private fun load(file: File): Map<String, Any?> = assertNotNull(Yaml().load(file.readText()))
}
