package dev.wawlink.driver

import dev.wawlink.protocol.ModelConfig
import dev.wawlink.protocol.ModelCost
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import dev.wawlink.protocol.ProviderApiType
import dev.wawlink.protocol.ProviderConfig
import dev.wawlink.protocol.ThinkingLevel
import dev.wawlink.runtime.ContainerConfig
import dev.wawlink.runtime.ContainerEngineType
import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun startupRepairPreservesConfigValuesAndLeavesStableFilesUntouched() = runBlocking {
        withContainer { rootfs, dir, _ ->
            val models = File(dir, "models.yml").apply { writeText("""
                # user model comment
                providers: {
                  custom: {
                    api: anthropic-messages,
                    apiKey: 'key: literal',
                    headers: {X-Literal: 'true'},
                    models: [
                      {id: '001', maxTokens: 8192, futureModel: {keep: null}}
                    ]
                    ,
                    futureProvider: [true, 'false', 17]
                  }
                }
            """.trimIndent() + "\n") }
            val config = File(dir, "config.yml").apply { writeText("""
                # user setting comment
                temperature: 0.2
                futureSetting: {items: [
                    'true', '001'
                  ]
                  , next: 'false'}
            """.trimIndent() + "\n") }
            val expected = listOf(load(models), load(config))
            val auth = File(dir, "auth.json").apply { writeText("{\"external\":\"untouched\"}\n") }
            val originalAuth = auth.readText()
            prepareOmpConfiguration({ rootfs }) { dir }
            assertEquals(expected, listOf(load(models), load(config)))
            assertTrue(models.readText().contains("# user model comment"))
            assertTrue(config.readText().contains("# user setting comment"))
            assertEquals(originalAuth, auth.readText())
            val timestamp = java.nio.file.attribute.FileTime.fromMillis(1_600_000_000_000)
            listOf(models, config).forEach { Files.setLastModifiedTime(it.toPath(), timestamp) }
            prepareOmpConfiguration({ rootfs }) { dir }
            listOf(models, config).forEach { assertEquals(timestamp, Files.getLastModifiedTime(it.toPath())) }
        }
    }

    @Test
    fun settingEnumRoundTripResetAndCommentsUseRealWireValues() = runBlocking {
        withContainer { rootfs, dir, scope ->
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
            val repo = OmpSettingsRepository({ rootfs }, { dir }, scope) { id, value -> calls.add(id to value) }
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
        withContainer { rootfs, dir, scope ->
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
            val repo = OmpProviderRepository({ rootfs }, { dir }, scope) { _, _ -> }
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
                val loaded = repo.providers.value.single { it.id == "vendor" }
                assertEquals("自定义服务", loaded.name)
                assertEquals(2048, loaded.models.single().thinkingBudget)
                assertNull(loaded.models.single().reasoning)
                assertEquals(listOf("text"), loaded.models.single().input)
                assertEquals(mapOf(ThinkingLevel.HIGH to "wire-high"), loaded.models.single().thinkingLevelMap)
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
        withContainer { rootfs, dir, scope ->
            val repo = OmpProviderRepository({ rootfs }, { dir }, scope) { _, _ -> }
            try {
                val expected = mapOf(ProviderApiType.CHAT to "openai-completions", ProviderApiType.RESPONSE to "openai-responses", ProviderApiType.MESSAGE to "anthropic-messages")
                for ((api, wire) in expected) {
                    repo.saveProvider(ProviderConfig(api.name, api.name, api, "https://example.invalid/v1", apiKey = "fixture-key", models = listOf(ModelConfig("model"))))
                    val saved = (load(File(dir, "models.yml"))["providers"] as Map<*, *>)[api.name] as Map<*, *>
                    assertEquals(wire, saved["api"])
                }
            } finally { repo.close() }
        }
    }

    @Test
    fun settingsObserveExternalEditsAndNotifyWithEffectiveValues() = runBlocking {
        withContainer { rootfs, dir, scope ->
            val file = File(dir, "config.yml").apply { writeText("temperature: 0.2\n") }
            val calls = Collections.synchronizedList(mutableListOf<Pair<String?, Any?>>())
            val repo = OmpSettingsRepository({ rootfs }, { dir }, scope) { id, value -> calls.add(id to value) }
            try {
                withTimeout(4_000) { repo.categories.first { categories -> categories.flatMap { it.groups }.flatMap { it.items }.single { it.id == "temperature" }.value == 0.2 } }
                file.writeText("temperature: 0.7\n")
                withTimeout(4_000) { repo.categories.first { categories -> categories.flatMap { it.groups }.flatMap { it.items }.single { it.id == "temperature" }.value == 0.7 } }
                repo.resetSetting("temperature")
                assertEquals(-1L, calls.last { it.first == "temperature" }.second)
            } finally { repo.close() }
        }
    }

    @Test
    fun capabilitiesPricesAndHighestDefaultsRoundTripWithoutErasingUnknownYaml() = runBlocking {
        withContainer { rootfs, dir, scope ->
            val file = File(dir, "models.yml")
            file.writeText("""
                providers:
                  vendor:
                    api: openai-responses
                    baseUrl: https://example.invalid/v1
                    auth: none
                    models:
                      - id: known
                        name: Original
                        reasoning: true # capability comment
                        input: [text, image]
                        cost:
                          input: 1.0 # price comment
                          output: 2.0
                          cacheRead: 0.1
                          cacheWrite: 0.2
                          futurePrice: preserve
                        thinking:
                          mode: effort
                          efforts: [low, medium, high, xhigh, max]
                          defaultLevel: xhigh
                          futureThinking: preserve
                          effortMap:
                            xhigh: max # map comment
                            max: native-max
                            futureEffort: preserve
                        futureModel: preserve
            """.trimIndent() + "\n")
            val repo = OmpProviderRepository({ rootfs }, { dir }, scope) { _, _ -> }
            try {
                val provider = withTimeout(4_000) { repo.providers.first { it.isNotEmpty() } }.single()
                val original = provider.models.single()
                assertEquals(true, original.reasoning)
                assertEquals(ModelCost(1.0, 2.0, 0.1, 0.2), original.cost)
                assertEquals("max", original.thinkingLevelMap[ThinkingLevel.XHIGH])
                assertEquals("native-max", original.thinkingLevelMap[ThinkingLevel.MAX])
                val updated = original.copy(name = "中文显示名", thinkingLevel = ThinkingLevel.MAX,
                    reasoningEffort = "stale-override", contextWindow = Int.MAX_VALUE, maxTokens = 1,
                    cost = ModelCost(0.75, 4.5, 0.075, 0.0),
                    thinkingLevelMap = mapOf(ThinkingLevel.XHIGH to "xhigh", ThinkingLevel.MAX to "max"))
                repo.saveProvider(provider.copy(models = listOf(updated)))
                val loaded = repo.providers.value.single().models.single()
                assertEquals(updated.name, loaded.name)
                assertEquals(updated.reasoning, loaded.reasoning)
                assertEquals(updated.input, loaded.input)
                assertEquals(updated.cost, loaded.cost)
                assertEquals(updated.contextWindow, loaded.contextWindow)
                assertEquals(updated.maxTokens, loaded.maxTokens)
                assertEquals(ThinkingLevel.MAX, loaded.thinkingLevel)
                assertEquals("max", loaded.reasoningEffort)
                assertEquals(updated.thinkingLevelMap, loaded.thinkingLevelMap)
                val saved = (((load(file)["providers"] as Map<*, *>)["vendor"] as Map<*, *>)["models"] as List<*>).single() as Map<*, *>
                assertFalse(saved.containsKey("thinkingLevelMap"))
                assertEquals("preserve", saved["futureModel"])
                assertEquals("preserve", (saved["cost"] as Map<*, *>)["futurePrice"])
                val thinking = saved["thinking"] as Map<*, *>
                assertEquals("max", thinking["defaultLevel"])
                assertEquals("preserve", thinking["futureThinking"])
                assertEquals("preserve", (thinking["effortMap"] as Map<*, *>)["futureEffort"])
                listOf("# capability comment", "# price comment", "# map comment").forEach { assertTrue(file.readText().contains(it)) }
                // Disabling the default is not a declaration that a reasoning-capable model lost its capability.
                repo.saveProvider(provider.copy(models = listOf(loaded.copy(thinkingLevel = ThinkingLevel.NONE, reasoningEffort = null))))
                assertEquals(ThinkingLevel.NONE, repo.providers.value.single().models.single().thinkingLevel)
                assertEquals(true, repo.providers.value.single().models.single().reasoning)
                assertFalse(file.readText().contains("defaultLevel: none"))
                assertTrue(file.readText().contains("futureThinking: preserve"))
            } finally { repo.close() }
        }
    }

    @Test
    fun allSixDefaultsAndNullableCapabilitiesStayIndependent() = runBlocking {
        withContainer { rootfs, dir, scope ->
            val repo = OmpProviderRepository({ rootfs }, { dir }, scope) { _, _ -> }
            try {
                val models = ThinkingLevel.entries.map { level -> ModelConfig(level.name, thinkingLevel = level, reasoning = true) } +
                    listOf(ModelConfig("unknown"), ModelConfig("explicitly-disabled", thinkingLevel = ThinkingLevel.NONE, reasoning = false))
                repo.saveProvider(ProviderConfig("vendor", "Vendor", baseUrl = "https://example.invalid/v1", apiKey = "fixture-key", models = models))
                val loaded = repo.providers.value.single().models.associateBy { it.id }
                ThinkingLevel.entries.forEach { assertEquals(it, loaded.getValue(it.name).thinkingLevel) }
                assertNull(loaded.getValue("unknown").reasoning)
                assertNull(loaded.getValue("unknown").cost)
                assertEquals(false, loaded.getValue("explicitly-disabled").reasoning)
                val saved = (((load(File(dir, "models.yml"))["providers"] as Map<*, *>)["vendor"] as Map<*, *>)["models"] as List<*>).map { it as Map<*, *> }
                assertFalse(saved.single { it["id"] == "unknown" }.containsKey("reasoning"))
                assertFalse(saved.single { it["id"] == "unknown" }.containsKey("thinking"))
                assertTrue(saved.all { it["input"] == listOf("text") })
                assertFailsWith<IllegalArgumentException> { repo.saveProvider(ProviderConfig("bad", "Bad", models = listOf(ModelConfig("invalid", cost = ModelCost(input = Double.NaN))))) }
                assertFailsWith<IllegalArgumentException> { repo.saveProvider(ProviderConfig("bad", "Bad", models = listOf(ModelConfig("invalid", contextWindow = 0)))) }
                assertFailsWith<IllegalArgumentException> { repo.saveProvider(ProviderConfig("bad", "Bad", models = listOf(ModelConfig("invalid", input = listOf("audio"))))) }
            } finally { repo.close() }
        }
    }

    @Test
    fun remoteDiscoveryMatchesOncePreservesManualModelsAndDoesNotWriteConfiguration() = runBlocking {
        withContainer { rootfs, dir, scope ->
            val catalogRequests = AtomicInteger()
            val catalog = KnownModelCatalog(load = { url ->
                catalogRequests.incrementAndGet()
                if (url == KnownModelCatalog.PRIMARY_URL) """{"known":{"supports_reasoning":true,"supports_vision":true,"input_cost_per_token":0.000002}}"""
                else """{"group":[{"id":"fallback"}]}"""
            })
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/v1/models") { exchange ->
                val body = """{"data":[{"id":"known"},{"id":"manual"},{"id":"unknown"},{"id":"known"}]}""".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            val repo = OmpProviderRepository({ rootfs }, { dir }, scope, catalog) { _, _ -> }
            try {
                val manual = ModelConfig("manual", "手工资料", reasoning = false, cost = ModelCost(input = 9.0))
                val provider = ProviderConfig("vendor", "Vendor", baseUrl = "http://127.0.0.1:${server.address.port}/v1", models = listOf(manual))
                val models = repo.fetchRemoteModels(provider).getOrThrow()
                assertEquals(listOf("known", "manual", "unknown"), models.map { it.id })
                assertEquals(true, models.first().reasoning)
                assertEquals(listOf("text", "image"), models.first().input)
                assertEquals(2.0, models.first().cost?.input)
                assertEquals(manual, models[1])
                assertEquals(ModelConfig("unknown"), models.last())
                assertNull(repo.matchModel("not-in-catalog").getOrThrow())
                assertEquals(true, repo.matchModel("proxy/KNOWN").getOrThrow()?.reasoning)
                assertEquals(2, catalogRequests.get())
                assertFalse(File(dir, "models.yml").exists())
                assertEquals(emptyList(), repo.providers.value)
            } finally { repo.close(); server.stop(0) }
        }
    }

    @Test
    fun remoteDiscoveryUsesAuthenticatedGetAndNeverExposesErrorBody() = runBlocking {
        withContainer { rootfs, dir, scope ->
            val requests = Collections.synchronizedList(mutableListOf<List<String?>>())
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/v1/models") { exchange ->
                requests.add(listOf(exchange.requestMethod, exchange.requestHeaders.getFirst("Authorization"), exchange.requestHeaders.getFirst("X-Private")))
                val body = """{"error":"fixture-key fixture-header providers: entire-document-secret"}""".toByteArray()
                exchange.sendResponseHeaders(401, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            val repo = OmpProviderRepository({ rootfs }, { dir }, scope) { _, _ -> }
            try {
                val provider = ProviderConfig("vendor", "Vendor", baseUrl = "http://127.0.0.1:${server.address.port}/v1", apiKey = "fixture-key", headers = mapOf("X-Private" to "fixture-header"))
                val message = repo.fetchRemoteModels(provider).exceptionOrNull()!!.message!!
                assertEquals(listOf(listOf("GET", "Bearer fixture-key", "fixture-header")), requests.toList())
                assertTrue(message.contains("HTTP 401"))
                assertFalse(message.contains("fixture-key"))
                assertFalse(message.contains("fixture-header"))
                assertFalse(message.contains("entire-document-secret"))
                assertFalse(File(dir, "models.yml").exists())
            } finally { repo.close(); server.stop(0) }
        }
    }

    @Test
    fun yamlSyntaxDiagnosticsKeepLocationWithoutSourceExcerpts() {
        val secret = "fixture-do-not-display"
        val failure = assertFailsWith<IllegalArgumentException> {
            OmpYaml.parse("providers:\n  vendor:\n    apiKey: $secret\n    models: [\n", "models.yml")
        }
        assertTrue(failure.message!!.contains("models.yml: line"))
        assertFalse(failure.message!!.contains(secret))
        assertFalse(failure.message!!.contains("apiKey:"))
    }

    @Test
    fun rejectedProviderSavePreservesAllDocumentsAndReportsNativeField() = runBlocking {
        withContainer { rootfs, dir, scope ->
            val models = File(dir, "models.yaml").apply { writeText("# retained fallback\nproviders: {}\n") }
            val config = File(dir, "config.yml").apply { writeText("disabledProviders: [retained]\n") }
            val metadata = File(dir, "wawlink-provider-ui.json").apply { writeText("{}\n") }
            val budgets = File(dir, "wawlink-model-budgets.json").apply { writeText("{}\n") }
            val originals = listOf(models, config, metadata, budgets).associateWith { it.readText() }
            val repo = OmpProviderRepository({ rootfs }, { dir }, scope) { _, _ -> }
            try {
                val failure = assertFailsWith<IllegalArgumentException> {
                    repo.saveProvider(ProviderConfig("vendor", "Vendor", baseUrl = "", models = listOf(ModelConfig("chat"))))
                }
                assertTrue(failure.message!!.contains("providers.vendor.baseUrl"))
                originals.forEach { (file, content) -> assertEquals(content, file.readText()) }
                assertFalse(File(dir, "models.yml").exists())
                budgets.writeText("not-json")
                val invalidBudgets = budgets.readText()
                assertFailsWith<Exception> {
                    repo.saveProvider(ProviderConfig("vendor", "Vendor", baseUrl = "https://example.invalid/v1", apiKey = "fixture-key", models = listOf(ModelConfig("chat"))))
                }
                originals.filterKeys { it != budgets }.forEach { (file, content) -> assertEquals(content, file.readText()) }
                assertEquals(invalidBudgets, budgets.readText())
                assertFalse(File(dir, "models.yml").exists())
            } finally { repo.close() }
        }
    }

    @Test
    fun fallbackModelsYamlRemainsTheNativeWriteTarget() = runBlocking {
        withContainer { rootfs, dir, scope ->
            val file = File(dir, "models.yaml").apply { writeText("providers: {}\n") }
            val repo = OmpProviderRepository({ rootfs }, { dir }, scope) { _, _ -> }
            try {
                repo.saveProvider(ProviderConfig("vendor", "Vendor", baseUrl = "https://example.invalid/v1", apiKey = "fixture-key", models = listOf(ModelConfig("chat"))))
                assertTrue((load(file)["providers"] as Map<*, *>).containsKey("vendor"))
                assertFalse(File(dir, "models.yml").exists())
                repo.deleteProvider("vendor")
                assertFalse((load(file)["providers"] as Map<*, *>).containsKey("vendor"))
            } finally { repo.close() }
        }
    }

    @Test fun chrootOfflineSettingsProvidersAndBootstrapKeepSharedModesAcrossReplacement() = runBlocking {
        for (engine in listOf(ContainerEngineType.CHROOT, ContainerEngineType.PROROOT, ContainerEngineType.PROOT)) {
            withContainer("/srv/custom-home") { rootfs, dir, scope ->
                val configuration = ContainerConfig("offline", "离线权限", rootfs.path, engine = engine)
                Files.setAttribute(dir.parentFile.toPath(), "unix:mode", 0x140)
                Files.setAttribute(dir.toPath(), "unix:mode", 0x140)
                val settings = OmpSettingsRepository({ rootfs }, { dir }, scope, configuration = { configuration }) { _, _ -> }
                val providers = OmpProviderRepository({ rootfs }, { dir }, scope, configuration = { configuration }) { _, _ -> }
                try {
                    repeat(2) { revision ->
                        settings.updateSetting("temperature", 0.2 + revision * 0.1)
                        providers.saveProvider(ProviderConfig("vendor", "服务", baseUrl = "https://example.invalid/v1", apiKey = "private-key", models = listOf(ModelConfig("chat"))))
                        prepareOmpConfiguration({ rootfs }, configuration) { dir }
                        for (name in listOf("config.yml", "models.yml", "wawlink-provider-ui.json", "wawlink-model-budgets.json")) {
                            val file = File(dir, name)
                            assertTrue(file.canRead() && file.canWrite())
                            val mode = (Files.getAttribute(file.toPath(), "unix:mode") as Number).toInt() and 0x1ff
                            assertEquals(if (engine == ContainerEngineType.CHROOT) "660".toInt(8) else "600".toInt(8), mode)
                        }
                    }
                    assertEquals("private-key", ((load(File(dir, "models.yml"))["providers"] as Map<*, *>)["vendor"] as Map<*, *>)["apiKey"])
                } finally { settings.close(); providers.close() }
            }
        }
    }

    @Test
    fun configurationEditsStayInExplicitRootfsForAnyHomeDepth() = runBlocking {
        for (home in listOf("/root", "/home/operator", "/srv/profiles/team/operator/custom-home")) {
            withContainer(home) { rootfs, dir, scope ->
                val other = File(rootfs, "untouched/.omp/agent/config.yml").apply { parentFile.mkdirs(); writeText("temperature: 0.9\n") }
                val settings = OmpSettingsRepository({ rootfs }, { dir }, scope) { _, _ -> }
                val providers = OmpProviderRepository({ rootfs }, { dir }, scope) { _, _ -> }
                try {
                    settings.updateSetting("temperature", 0.4)
                    providers.saveProvider(ProviderConfig("vendor", "Vendor", baseUrl = "https://example.invalid/v1", apiKey = "key", models = listOf(ModelConfig("chat"))))
                    prepareOmpConfiguration({ rootfs }) { dir }
                    assertEquals(0.4, load(File(dir, "config.yml"))["temperature"])
                    assertTrue((load(File(dir, "models.yml"))["providers"] as Map<*, *>).containsKey("vendor"))
                    assertEquals("temperature: 0.9\n", other.readText())
                } finally { settings.close(); providers.close() }
            }
        }
    }

    @Test
    fun configurationRejectsUnrelatedRootfsAndHostHome() = runBlocking {
        withContainer("/home/operator") { rootfs, dir, _ ->
            val unrelated = File(rootfs.parentFile, "rootfs-other").apply { mkdirs() }
            val hostHome = File(rootfs.parentFile, "host/.omp/agent").apply { mkdirs() }
            for (outside in listOf(File(unrelated, "home/operator/.omp/agent"), hostHome)) {
                assertFailsWith<IllegalArgumentException> { prepareOmpConfiguration({ rootfs }) { outside } }
            }
            assertFailsWith<IllegalArgumentException> { prepareOmpConfiguration({ File("/") }) { dir } }
            assertFailsWith<IllegalArgumentException> { prepareOmpConfiguration({ rootfs }) { File(dir, "child") } }
        }
    }

    @Test
    fun configurationRejectsEscapingLinksAtEveryHomeAncestor() = runBlocking {
        for (linked in listOf("home", "home/operator", "home/operator/.omp", "home/operator/.omp/agent")) {
            val temporary = Files.createTempDirectory("wawlink-config-link-").toFile()
            try {
                val rootfs = File(temporary, "rootfs").apply { mkdirs() }
                val outside = File(temporary, "outside").apply { mkdirs() }
                val link = File(rootfs, linked).apply { parentFile.mkdirs() }
                Files.createSymbolicLink(link.toPath(), outside.toPath())
                val dir = File(rootfs, "home/operator/.omp/agent")
                assertFailsWith<IllegalArgumentException> { prepareOmpConfiguration({ rootfs }) { dir } }
                assertTrue(outside.listFiles().orEmpty().isEmpty())
            } finally { temporary.deleteRecursively() }
        }
    }

    @Test
    fun configurationRejectsChildLinksEvenToAnotherUserInsideRootfs() = runBlocking {
        for (name in listOf("config.yml", "models.yml", "wawlink-provider-ui.json", "wawlink-model-budgets.json")) {
            withContainer("/srv/custom-home") { rootfs, dir, scope ->
                val target = File(rootfs, "home/other/.omp/agent/$name").apply { parentFile.mkdirs(); writeText("{}\n") }
                val original = target.readBytes()
                Files.createSymbolicLink(File(dir, name).toPath(), target.toPath())
                val providers = OmpProviderRepository({ rootfs }, { dir }, scope) { _, _ -> }
                try {
                    assertFailsWith<IllegalArgumentException> {
                        providers.saveProvider(ProviderConfig("vendor", "Vendor", baseUrl = "https://example.invalid/v1", apiKey = "key", models = listOf(ModelConfig("chat"))))
                    }
                    assertEquals(original.toList(), target.readBytes().toList())
                } finally { providers.close() }
            }
        }
    }

    private suspend fun withContainer(home: String = "/root", block: suspend (File, File, CoroutineScope) -> Unit) {
        val temporary = Files.createTempDirectory("wawlink-config-test").toFile()
        val rootfs = File(temporary, "rootfs").apply { mkdirs() }
        val dir = File(rootfs, "${home.trimStart('/')}/.omp/agent").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try { block(rootfs, dir, scope) } finally { scope.cancel(); temporary.deleteRecursively() }
    }

    private fun load(file: File): Map<String, Any?> = assertNotNull(Yaml().load(file.readText()))
}
