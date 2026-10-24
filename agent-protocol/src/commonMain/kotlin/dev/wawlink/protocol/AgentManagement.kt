package dev.wawlink.protocol

import kotlinx.coroutines.flow.StateFlow

/** Configuration belongs to the selected container/profile and, when present, its project. */
enum class ConfigurationScope(val label: String) { GLOBAL("全局"), PROJECT("项目") }

data class AgentConfigurationContext(
    val containerId: String,
    val containerName: String,
    val profile: String = "default",
    val projectPath: String? = null,
    val sessionId: String? = null,
)

data class ConfigurationCapability(val name: String, val description: String = "", val source: String = "builtin")
data class AgentDefinitionSource(val source: String, val path: String, val description: String = "")
data class ManagedAgent(
    val name: String,
    val description: String,
    val source: String,
    val path: String,
    val readOnly: Boolean,
    val fields: Map<String, Any?> = emptyMap(),
    val systemPrompt: String = "",
    val content: String = "",
    val version: String = "",
    val shadows: List<AgentDefinitionSource> = emptyList(),
)

data class ManagedModel(
    val provider: String,
    val id: String,
    val name: String = id,
    val kind: String = "chat",
    val api: String = "",
    val input: List<String> = emptyList(),
    val available: Boolean = false,
    val unavailableReason: String? = null,
    val metadata: Map<String, Any?> = emptyMap(),
) { val key: String = "$provider/$id" }

data class ManagedRole(
    val id: String,
    val name: String,
    val kind: String = "chat",
    val model: String? = null,
    val thinking: String? = null,
    val error: String? = null,
    val source: String? = null,
    val automatic: Boolean = false,
    val diagnostics: List<String> = emptyList(),
)

data class ManagedAdvisor(
    val id: String,
    val name: String,
    val scope: ConfigurationScope,
    val path: String,
    val configuration: Map<String, Any?> = emptyMap(),
    val runtimeStatus: String? = null,
    val version: String = "",
)

data class ConfigurationFile(val id: String, val title: String, val path: String, val writable: Boolean = true)
data class ConfigurationDocument(val id: String, val path: String, val content: String, val version: String, val writable: Boolean = true, val values: Map<String, Any?> = emptyMap())
data class ManagedAccount(
    val provider: String,
    val id: String,
    val label: String,
    val type: String,
    val status: String,
    val quota: String? = null,
    val pinned: Boolean = false,
)
data class ManagedAuthProvider(val id: String, val name: String, val oauth: Boolean = false)
data class ManagedProvider(
    val id: String,
    val name: String = id,
    val builtin: Boolean = false,
    val enabled: Boolean = false,
    val oauth: Boolean = false,
    val canFetchModels: Boolean = false,
    val baseUrl: String? = null,
    val api: String? = null,
)
data class AuthenticationChallenge(val provider: String, val url: String? = null, val instructions: String? = null, val prompt: String? = null, val secret: Boolean = false)

data class AgentManagementState(
    val context: AgentConfigurationContext,
    val loading: Boolean = true,
    val error: String? = null,
    /** Nested native configuration objects. Absence differs from null and an explicit empty list. */
    val globalValues: Map<String, Any?> = emptyMap(),
    val projectValues: Map<String, Any?> = emptyMap(),
    val effectiveValues: Map<String, Any?> = emptyMap(),
    val defaultValues: Map<String, Any?> = emptyMap(),
    /** Native dotted setting IDs to their actual winning layer. */
    val provenance: Map<String, String> = emptyMap(),
    val categories: List<SettingCategory> = emptyList(),
    val agents: List<ManagedAgent> = emptyList(),
    val tools: List<ConfigurationCapability> = emptyList(),
    val skills: List<ConfigurationCapability> = emptyList(),
    val models: List<ManagedModel> = emptyList(),
    val modelsLoading: Boolean = true,
    val modelsError: String? = null,
    val roles: List<ManagedRole> = emptyList(),
    val advisors: List<ManagedAdvisor> = emptyList(),
    val advisorInstructions: String = "",
    val advisorMaxNotes: Int? = null,
    val advisorSharedByScope: Map<ConfigurationScope, Map<String, Any?>> = emptyMap(),
    val files: List<ConfigurationFile> = emptyList(),
    val accounts: List<ManagedAccount> = emptyList(),
    val authProviders: List<ManagedAuthProvider> = emptyList(),
    val providers: List<ManagedProvider> = emptyList(),
    val authentication: AuthenticationChallenge? = null,
)

/** Complete native management, separate from the existing framework-settings panel. */
interface AgentManagementRepository {
    suspend fun parseAgent(content: String): ManagedAgent
    suspend fun renderAgent(name: String, fields: Map<String, Any?>, systemPrompt: String): String
    suspend fun previewRole(role: String, scope: ConfigurationScope, selector: String?): ManagedRole
    val state: StateFlow<AgentManagementState>
    suspend fun reload(online: Boolean = false)
    /** Full model declarations are loaded only for the selected model, never for the whole catalogue. */
    suspend fun modelDetails(provider: String, modelId: String): ManagedModel
    /** Display-only defaults from the native model builder; never materializes configuration. */
    suspend fun modelDefaults(provider: String, modelId: String, api: String): Map<String, Any?>
    /** Force native online discovery for this provider only; never substitutes a static catalogue reload. */
    suspend fun fetchProviderModels(provider: String)
    /** Keys are native dotted IDs; null removes this layer's override, never writes a null sentinel. */
    suspend fun updateSettings(scope: ConfigurationScope, changes: Map<String, Any?>)
    suspend fun resetRuntimeSettings(scope: ConfigurationScope)
    suspend fun saveAgent(scope: ConfigurationScope, name: String, fields: Map<String, Any?>, systemPrompt: String, original: ManagedAgent? = null)
    suspend fun deleteAgent(agent: ManagedAgent)
    suspend fun generateAgent(description: String): String
    suspend fun saveAdvisor(scope: ConfigurationScope, configuration: Map<String, Any?>, original: ManagedAdvisor? = null)
    suspend fun deleteAdvisor(advisor: ManagedAdvisor)
    suspend fun updateAdvisorShared(scope: ConfigurationScope, instructions: String, maxNotes: Int?)
    suspend fun readDocument(id: String): ConfigurationDocument
    suspend fun saveDocument(document: ConfigurationDocument, content: String, overwrite: Boolean = false)
    suspend fun saveDocumentValues(document: ConfigurationDocument, values: Map<String, Any?>)
    suspend fun exportRedacted(): String
    suspend fun applyPreset(name: String)
    suspend fun savePreset(name: String)
    suspend fun deletePreset(name: String)
    suspend fun probeModel(provider: String, modelId: String): LatencyInfo
    suspend fun login(provider: String)
    suspend fun answerLogin(value: String)
    fun cancelLogin()
    suspend fun logout(provider: String, accountId: String? = null)
    suspend fun pinAccount(provider: String, accountId: String?)
}
