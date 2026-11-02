package dev.wawlink.driver

import dev.wawlink.protocol.ProviderConfig
import dev.wawlink.protocol.ProviderApiType
import dev.wawlink.protocol.ThinkingLevel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Bridges the native provider-payload hook for per-model Message budgets, not a fictional YAML field. */
internal object ModelPayloadExtension {
    private const val SCRIPT_NAME = "wawlink-model-payload.mjs"
    private const val BUDGET_NAME = "wawlink-model-budgets.json"


    suspend fun install(files: OmpFileSession, guestAgentDir: String): String {
        val directory = ompAgentDirectory(files, guestAgentDir)
        val source = checkNotNull(javaClass.getResourceAsStream("/$SCRIPT_NAME")) { "缺少模型预算扩展资源" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val target = "$directory/$SCRIPT_NAME"
        val existing = files.readText(target)
        if (existing != source) files.writeText(target, source, expectedVersion = contentVersion(existing), createOnly = existing == null)
        return target
    }

    suspend fun readBudgets(files: OmpFileSession, guestAgentDir: String): Map<String, Int> =
        parseBudgets(files.readText("${ompAgentDirectory(files, guestAgentDir)}/$BUDGET_NAME"))

    fun parseBudgets(content: String?): Map<String, Int> {
        if (content == null) return emptyMap()
        val values = Json.parseToJsonElement(content) as? JsonObject
            ?: error("模型思考预算文件必须是键值表")
        return values.mapValues { (key, value) ->
            val budget = value.jsonPrimitive.intOrNull
            require(key.contains('/') && budget != null && budget >= 1024) { "无效的模型思考预算：$key" }
            budget
        }
    }

    suspend fun writeBudgets(files: OmpFileSession, guestAgentDir: String, providers: List<ProviderConfig>) {
        val target = "${ompAgentDirectory(files, guestAgentDir)}/$BUDGET_NAME"
        val existing = files.readText(target)
        val budgets = buildMap<String, JsonPrimitive> {
            providers.forEach { provider ->
                provider.models.forEach { model ->
                    model.thinkingBudget?.let { budget ->
                        require(provider.apiType == ProviderApiType.MESSAGE) { "数值思考预算仅适用于 Message 接口" }
                        require(budget >= 1024 && budget < model.maxTokens && model.thinkingLevel != ThinkingLevel.NONE && model.reasoning != false) { "思考预算必须至少为 1024、小于模型输出上限，且启用思考" }
                        put("${provider.id}/${model.id}", JsonPrimitive(budget))
                    }
                }
            }
        }
        files.writeText(target, JsonObject(budgets).toString() + "\n", expectedVersion = contentVersion(existing), createOnly = existing == null)
    }

}
