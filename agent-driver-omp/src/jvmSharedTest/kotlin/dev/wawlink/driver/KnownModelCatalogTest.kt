package dev.wawlink.driver

import dev.wawlink.protocol.ModelConfig
import dev.wawlink.protocol.ModelCost
import dev.wawlink.protocol.ThinkingLevel
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class KnownModelCatalogTest {
    @Test
    fun convertsAllFourPricesVisionLimitsAndIndependentHighestLevels() {
        val models = parseModelCatalog("""{
          "claude-test": {
            "mode":"chat", "supports_reasoning":true, "supports_vision":true,
            "context_window":1000000, "max_input_tokens":200000, "max_output_tokens":64000,
            "supports_max_reasoning_effort":true,
            "input_cost_per_token":0.000003, "output_cost_per_token":0.000015,
            "cache_read_input_token_cost":0.0000002, "cache_creation_input_token_cost":0.00000375
          },
          "gpt-test": {"supports_xhigh_reasoning_effort":true, "supports_max_reasoning_effort":true},
          "embedding": {"mode":"embedding"}
        }""")
        assertEquals(2, models.size)
        val claude = models.first()
        assertEquals(true, claude.reasoning)
        assertEquals(listOf("text", "image"), claude.input)
        assertEquals(1_000_000, claude.contextWindow)
        assertEquals(64_000, claude.maxTokens)
        assertEquals(ModelCost(3.0, 15.0, 0.2, 3.75), claude.cost)
        assertEquals(mapOf(ThinkingLevel.XHIGH to "max", ThinkingLevel.MAX to "max"), claude.thinkingLevelMap)
        assertEquals(mapOf(ThinkingLevel.XHIGH to "xhigh", ThinkingLevel.MAX to "max"), models.last().thinkingLevelMap)
    }

    @Test
    fun unknownFlagsRemainUnknownAndMissingPricesDoNotBecomeFree() {
        val models = parseModelCatalog("""{
          "unknown": {}, "disabled": {"supports_reasoning":false},
          "partial": {"input_cost_per_token":0.00000075},
          "free": {"input_cost_per_token":0},
          "invalid": {"input_cost_per_token":-1,"output_cost_per_token":"0.1","supports_reasoning":"true"}
        }""").associateBy { it.id }
        assertNull(models.getValue("unknown").reasoning)
        assertNull(models.getValue("unknown").cost)
        assertEquals(false, models.getValue("disabled").reasoning)
        assertEquals(ThinkingLevel.NONE, models.getValue("disabled").thinkingLevel)
        assertEquals(ModelCost(input = 0.75), models.getValue("partial").cost)
        assertEquals(ModelCost(), models.getValue("free").cost)
        assertNull(models.getValue("invalid").cost)
        assertNull(models.getValue("invalid").reasoning)
    }

    @Test
    fun integerLimitsAndPriceOverflowNeverWrapOrBecomeInfinity() {
        val models = parseModelCatalog("""{
          "boundary": {"max_input_tokens":2147483647,"max_output_tokens":1,"input_cost_per_token":1e290},
          "invalid": {"context_window":0,"max_input_tokens":2147483648,"max_tokens":-1,"output_cost_per_token":1e308},
          "fallback": {"context_window":-1,"max_input_tokens":123,"max_output_tokens":0,"max_tokens":456}
        }""").associateBy { it.id }
        val boundary = models.getValue("boundary")
        assertEquals(Int.MAX_VALUE, boundary.contextWindow)
        assertEquals(1, boundary.maxTokens)
        assertTrue(assertNotNull(boundary.cost).input.isFinite())
        assertEquals(200_000, models.getValue("invalid").contextWindow)
        assertEquals(16_384, models.getValue("invalid").maxTokens)
        assertNull(models.getValue("invalid").cost)
        assertEquals(123, models.getValue("fallback").contextWindow)
        assertEquals(456, models.getValue("fallback").maxTokens)
    }

    @Test
    fun fallbackReadsNamesModalitiesGeminiLimitsAndNativeMax() {
        val models = parseModelCatalog("""{"claude":[
          {"id":"claude-test","display_name":"Claude Test","context_length":200000,"max_completion_tokens":64000,
           "thinking":{"levels":["low","medium","high","max"]},"supportedInputModalities":["image","text"]},
          {"id":"gemini-test","inputTokenLimit":1048576,"outputTokenLimit":65536}
        ]}""", fallback = true)
        assertEquals("Claude Test", models.first().name)
        assertEquals(true, models.first().reasoning)
        assertEquals("max", models.first().thinkingLevelMap[ThinkingLevel.MAX])
        assertEquals("max", models.first().thinkingLevelMap[ThinkingLevel.XHIGH])
        assertEquals(listOf("text", "image"), models.first().input)
        assertEquals(1_048_576, models.last().contextWindow)
        assertEquals(65_536, models.last().maxTokens)
        assertNull(models.last().reasoning)
        assertNull(models.last().cost)
    }

    @Test
    fun exactIdsWinButNormalizedAndProxyCollisionsAreNeverGuessed() {
        val first = ModelConfig("vendor-a/model-4.6", name = "First", reasoning = true)
        val second = ModelConfig("vendor-b/model-4-6", name = "Second", reasoning = false)
        val index = ModelCatalogIndex(listOf(first, second, ModelConfig("single-model", reasoning = true)))
        assertEquals("First", index.match(first.id)?.name)
        assertEquals("Second", index.match(second.id)?.name)
        assertNull(index.match("proxy/model-4_6"))
        assertNull(index.match("model-4-6"))
        assertNull(index.match("single"))
        assertNull(index.match("single-model-20260101"))
        assertNull(index.match(""))
        assertEquals("proxy/SINGLE_MODEL", index.match("proxy/SINGLE_MODEL")?.id)
        assertEquals(true, index.match("proxy/SINGLE_MODEL")?.reasoning)
        val caseCollision = ModelCatalogIndex(listOf(ModelConfig("MODEL"), ModelConfig("model")))
        assertNotNull(caseCollision.match("MODEL"))
        assertNull(caseCollision.match("Model"))
        assertNull(ModelCatalogIndex(listOf(first, first)).match(first.id))
    }

    @Test
    fun concurrentMatchesShareOneLoadAndPrimaryOwnsDuplicateIds() = runBlocking {
        val requests = AtomicInteger()
        var clock = 1_000L
        val catalog = KnownModelCatalog(load = { url ->
            requests.incrementAndGet()
            delay(10)
            if (url == KnownModelCatalog.PRIMARY_URL) """{"shared":{"max_input_tokens":400000,"input_cost_per_token":0.000002}}"""
            else """{"group":[{"id":"shared","context_length":128000},{"id":"fallback-only","thinking":{}}]}"""
        }, now = { clock })
        coroutineScope { (1..12).map { async { catalog.index().match("shared") } }.awaitAll() }
        assertEquals(2, requests.get())
        assertEquals(400_000, catalog.index().match("shared")?.contextWindow)
        assertEquals(2.0, catalog.index().match("shared")?.cost?.input)
        assertEquals(true, catalog.index().match("fallback-only")?.reasoning)
        assertNull(catalog.index().match("missing"))
        assertEquals(2, requests.get())
        clock += 6 * 60 * 60 * 1_000
        catalog.index()
        assertEquals(4, requests.get())
    }

    @Test
    fun sourceFailureUsesFallbackAndCachesFailuresWithoutPerModelRetries() = runBlocking {
        val requests = AtomicInteger()
        val fallback = KnownModelCatalog(load = { url ->
            requests.incrementAndGet()
            if (url == KnownModelCatalog.PRIMARY_URL) error("offline")
            """{"group":[{"id":"only-fallback"}]}"""
        })
        assertNotNull(fallback.index().match("only-fallback"))
        assertEquals(2, requests.get())
        val offlineCalls = AtomicInteger()
        var clock = 0L
        val offline = KnownModelCatalog(load = { offlineCalls.incrementAndGet(); error("offline") }, now = { clock })
        repeat(3) { assertFailsWith<IllegalStateException> { offline.index() } }
        assertEquals(2, offlineCalls.get())
        clock = 60_000
        assertFailsWith<IllegalStateException> { offline.index() }
        assertEquals(4, offlineCalls.get())
    }

    @Test
    fun conflictingFallbackGroupsAreAmbiguousWhileIdenticalDuplicatesAreSafe() = runBlocking {
        val catalog = KnownModelCatalog(load = { url ->
            if (url == KnownModelCatalog.PRIMARY_URL) error("offline")
            """{
              "first":[{"id":"conflict","context_length":1000},{"id":"identical","context_length":2000}],
              "second":[{"id":"conflict","context_length":3000},{"id":"identical","context_length":2000}]
            }"""
        })
        assertNull(catalog.index().match("conflict"))
        assertNull(catalog.index().match("proxy/conflict"))
        assertEquals(2000, catalog.index().match("identical")?.contextWindow)
    }

    @Test
    fun failedRefreshKeepsUsableStaleCatalogAndInvalidDocumentsFail(): Unit = runBlocking {
        var clock = 0L
        var online = true
        val catalog = KnownModelCatalog(load = { _ ->
            if (!online) error("offline")
            """{"known":{}}"""
        }, now = { clock })
        assertNotNull(catalog.index().match("known"))
        online = false
        clock = 6 * 60 * 60 * 1_000L
        assertNotNull(catalog.index().match("known"))
        assertFalse(catalog.index().match("known")?.reasoning == true)
        assertFailsWith<IllegalArgumentException> { parseModelCatalog("{}") }
        assertFailsWith<IllegalStateException> { parseModelCatalog("[]") }
    }
}
