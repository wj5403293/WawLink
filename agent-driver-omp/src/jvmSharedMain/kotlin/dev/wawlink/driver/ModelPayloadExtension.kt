package dev.wawlink.driver

import dev.wawlink.protocol.ProviderConfig
import dev.wawlink.protocol.ProviderApiType
import dev.wawlink.protocol.ThinkingLevel
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Bridges the native provider-payload hook for per-model Message budgets, not a fictional YAML field. */
internal object ModelPayloadExtension {
    private const val SCRIPT_NAME = "wawlink-model-payload.mjs"
    private const val BUDGET_NAME = "wawlink-model-budgets.json"

    fun install(agentDir: File, guestAgentDir: String): String {
        require(guestAgentDir.startsWith('/') && !guestAgentDir.split('/').contains("..")) { "Agent 配置目录必须是容器内绝对路径" }
        val source = checkNotNull(javaClass.getResourceAsStream("/$SCRIPT_NAME")) { "缺少模型预算扩展资源" }
            .use { it.readBytes() }
        val target = File(agentDir, SCRIPT_NAME)
        if (!target.isFile || !target.readBytes().contentEquals(source)) atomicWrite(target, source)
        return "${guestAgentDir.trimEnd('/')}/$SCRIPT_NAME"
    }

    fun readBudgets(agentDir: File): Map<String, Int> {
        val file = File(agentDir, BUDGET_NAME)
        if (!file.exists()) return emptyMap()
        val values = Json.parseToJsonElement(file.readText()) as? JsonObject
            ?: error("模型思考预算文件必须是键值表")
        return values.mapValues { (key, value) ->
            val budget = value.jsonPrimitive.intOrNull
            require(key.contains('/') && budget != null && budget >= 1024) { "无效的模型思考预算：$key" }
            budget
        }
    }

    fun writeBudgets(agentDir: File, providers: List<ProviderConfig>) {
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
        atomicWrite(File(agentDir, BUDGET_NAME), JsonObject(budgets).toString().toByteArray(Charsets.UTF_8))
    }

    private fun atomicWrite(file: File, bytes: ByteArray) {
        check(file.parentFile.isDirectory || file.parentFile.mkdirs()) { "无法创建 Agent 配置目录" }
        require(!Files.isSymbolicLink(file.toPath())) { "拒绝覆盖符号链接配置文件" }
        val temporary = File.createTempFile(".${file.name}.", ".tmp", file.parentFile)
        try {
            temporary.outputStream().use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }
}
