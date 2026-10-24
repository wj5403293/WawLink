package dev.wawlink.protocol

/** Native resource operations share the container/profile context, without loading model SDKs. */
interface AgentResourceRepository {
    val context: AgentConfigurationContext
    suspend fun request(operation: String, arguments: Map<String, Any?> = emptyMap()): Map<String, Any?>
}
