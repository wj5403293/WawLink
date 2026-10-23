package dev.wawlink.driver

import dev.wawlink.protocol.ModelConfig
import dev.wawlink.protocol.ModelCost
import dev.wawlink.protocol.ThinkingLevel
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import kotlin.math.round
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Catalog conversion adapted from https://github.com/wsdx233/ipmt/blob/master/src/known_models.rs.
 * MIT License — Copyright (c) 2026 IPMT contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
internal class KnownModelCatalog(
    private val load: suspend (String) -> String = ::downloadModelCatalog,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var cached: ModelCatalogIndex? = null
    private var failure: Exception? = null
    private var retryAt = Long.MIN_VALUE

    /** One application-wide load, including concurrent syncs; failures are cached briefly too. */
    suspend fun index(): ModelCatalogIndex = mutex.withLock {
        if (now() < retryAt) return@withLock cached ?: throw checkNotNull(failure)
        fun parse(text: String, fallback: Boolean): List<ModelConfig> = parseModelCatalog(text, fallback)
        suspend fun source(url: String, fallback: Boolean): Result<List<ModelConfig>> = try {
            Result.success(parse(load(url), fallback))
        } catch (error: CancellationException) { throw error } catch (error: Exception) { Result.failure(error) }
        val primary = source(PRIMARY_URL, false)
        val fallback = source(FALLBACK_URL, true)
        if (primary.isFailure && fallback.isFailure) {
            failure = primary.exceptionOrNull() as Exception
            retryAt = now() + 60_000
            return@withLock cached ?: throw checkNotNull(failure)
        }
        val models = primary.getOrDefault(emptyList()).toMutableList()
        val primaryIds = models.mapTo(mutableSetOf()) { it.id }
        // Repeated fallback groups may disagree about one ID; keep conflicts ambiguous.
        models.addAll(fallback.getOrDefault(emptyList()).filter { it.id !in primaryIds }.distinct())
        ModelCatalogIndex(models).also {
            cached = it
            failure = null
            retryAt = now() + 6 * 60 * 60 * 1_000
        }
    }

    companion object {
        const val PRIMARY_URL = "https://raw.githubusercontent.com/Wei-Shaw/sub2api/main/backend/resources/model-pricing/model_prices_and_context_window.json"
        const val FALLBACK_URL = "https://raw.githubusercontent.com/router-for-me/models/main/models.json"
        val shared = KnownModelCatalog()
    }
}

/** No substring/fuzzy/date stripping: a normalized alias must identify exactly one catalog ID. */
internal class ModelCatalogIndex(models: List<ModelConfig>) {
    private val exact = HashMap<String, ModelConfig?>()
    private val normalized = HashMap<String, ModelConfig?>()
    private val suffixes = HashMap<String, ModelConfig?>()

    init {
        fun HashMap<String, ModelConfig?>.addUnique(key: String, model: ModelConfig) {
            if (containsKey(key)) this[key] = null else this[key] = model
        }
        models.forEach { model ->
            exact.addUnique(model.id, model)
            normalized.addUnique(normalizeModelId(model.id), model)
            suffixes.addUnique(normalizeModelId(model.id.substringAfterLast('/')), model)
        }
    }

    fun match(id: String): ModelConfig? {
        val requested = id.trim()
        if (requested.isEmpty()) return null
        val canonical = normalizeModelId(requested)
        val match = when {
            exact.containsKey(requested) -> exact[requested]
            normalized.containsKey(canonical) -> normalized[canonical]
            else -> suffixes[normalizeModelId(requested.substringAfterLast('/'))]
        } ?: return null
        return match.copy(id = id, name = if (match.name == match.id) id else match.name)
    }
}

private fun normalizeModelId(id: String): String = id.lowercase(Locale.ROOT).replace('.', '-').replace('_', '-')

internal fun parseModelCatalog(text: String, fallback: Boolean = false): List<ModelConfig> {
    val root = Json.parseToJsonElement(text) as? JsonObject ?: error("模型目录必须是 JSON 对象")
    val models = if (fallback) root.values.flatMap { group ->
        (group as? JsonArray).orEmpty().mapNotNull { value ->
            val source = value as? JsonObject ?: return@mapNotNull null
            val id = source.text("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            catalogModel(id, source, true)
        }
    } else root.mapNotNull { (id, value) ->
        val source = value as? JsonObject ?: return@mapNotNull null
        if (id.isBlank() || source.text("mode")?.let { it !in setOf("chat", "responses", "completion") } == true) null
        else catalogModel(id, source, false)
    }
    require(models.isNotEmpty()) { "模型目录没有文本生成模型" }
    return models
}

private fun catalogModel(id: String, source: JsonObject, fallback: Boolean): ModelConfig {
    val thinking = source["thinking"] as? JsonObject
    val levels = (thinking?.get("levels") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
    val supportsMax = source.flag("supports_max_reasoning_effort") == true || "max" in levels
    val supportsXhigh = source.flag("supports_xhigh_reasoning_effort") == true || "xhigh" in levels
    val reasoning = if (supportsMax || supportsXhigh || thinking != null) true else source.flag("supports_reasoning")
    val modalities = source[if (fallback) "supportedInputModalities" else "supported_modalities"] as? JsonArray
    val image = source.flag("supports_vision") == true || modalities.orEmpty().any { (it as? JsonPrimitive)?.content == "image" }
    val prices = listOf("input_cost_per_token", "output_cost_per_token", "cache_read_input_token_cost", "cache_creation_input_token_cost")
        .map { key -> source.number(key)?.takeIf { it >= 0 }?.let { value ->
            (value * 1_000_000).takeIf { it.isFinite() }?.let { if (it <= Double.MAX_VALUE / 1e12) round(it * 1e12) / 1e12 else it }
        } }
    val mappings = buildMap {
        // Unlike Pi's historic five-level UI, OMP supports both native levels independently.
        if (supportsXhigh) put(ThinkingLevel.XHIGH, "xhigh")
        if (supportsMax) put(ThinkingLevel.MAX, "max")
        if (supportsMax && !supportsXhigh) put(ThinkingLevel.XHIGH, "max")
    }
    return ModelConfig(
        id = id,
        name = source.text("display_name") ?: source.text("displayName") ?: source.text("name") ?: id,
        reasoning = reasoning,
        thinkingLevel = if (reasoning == false) ThinkingLevel.NONE else ThinkingLevel.MEDIUM,
        input = if (image) listOf("text", "image") else listOf("text"),
        contextWindow = source.positiveInt("context_window", "context_length", "inputTokenLimit", "max_input_tokens") ?: 200_000,
        maxTokens = source.positiveInt("max_output_tokens", "max_completion_tokens", "outputTokenLimit", "max_tokens") ?: 16_384,
        cost = if (prices.any { it != null }) ModelCost(prices[0] ?: 0.0, prices[1] ?: 0.0, prices[2] ?: 0.0, prices[3] ?: 0.0) else null,
        thinkingLevelMap = mappings,
    )
}

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.flag(key: String): Boolean? = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
private fun JsonObject.number(key: String): Double? = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
private fun JsonObject.positiveInt(vararg keys: String): Int? = keys.firstNotNullOfOrNull { key ->
    (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
}

private suspend fun downloadModelCatalog(url: String): String = withContext(Dispatchers.IO) {
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 8_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "WawLink-model-catalog")
        check(connection.responseCode in 200..299) { "模型目录 HTTP ${connection.responseCode}" }
        val maximum = 10 * 1024 * 1024
        require(connection.contentLengthLong <= maximum) { "模型目录超过 10 MiB" }
        connection.inputStream.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= maximum) { "模型目录超过 10 MiB" }
                output.write(buffer, 0, count)
            }
            output.toString("UTF-8")
        }
    } finally { connection.disconnect() }
}
