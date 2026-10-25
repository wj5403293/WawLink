package dev.wawlink.protocol

import kotlinx.coroutines.flow.StateFlow

/** Native history and runtime instances, scoped to one container and one main session. */
interface SessionExplorer {
    val history: StateFlow<SessionHistoryState>
    val hub: StateFlow<AgentHubState>
    suspend fun refreshHistory()
    suspend fun refreshHub(activityLimit: Int = 20)
    suspend fun setLabel(entryId: String, label: String?)
    suspend fun navigate(entryId: String, summary: HistorySummary = HistorySummary()): RestoredSessionPrompt?
    suspend fun reanswer(entryId: String, answers: List<AskAnswer>)
    suspend fun cancelNavigation()
    /** Creates a real native fork without moving the source session. Returns its guest session file. */
    suspend fun fork(entryId: String): String
    /** Pure observation: opening a parked agent must never revive it. */
    suspend fun openAgent(instanceKey: String): RuntimeAgentSessionController
    suspend fun controlAgent(instanceKey: String, operation: RuntimeAgentOperation)
    suspend fun exportAgent(instanceKey: String): SessionExport
}

data class HistorySummary(val enabled: Boolean = false, val instructions: String? = null)
class SessionNavigationCancelledException : IllegalStateException("已取消，续写位置没有变化")
enum class RuntimeAgentPromptBehavior { PROMPT, STEER, FOLLOW_UP }
interface RuntimeAgentSessionController : AgentSessionController {
    fun sendAgentPrompt(text: String, attachments: List<AttachmentFile>, behavior: RuntimeAgentPromptBehavior)
    /** Resolves a native entry through this child's reducer, never through the main session. */
    fun feedItemIdForEntry(entryId: String): String?
}
data class RestoredSessionPrompt(val text: String, val attachments: List<AttachmentFile> = emptyList())
data class SessionExport(val path: String, val name: String, val mimeType: String = "text/html")

data class SessionHistoryState(
    val sessionId: String = "",
    val sessionFile: String? = null,
    val leafId: String? = null,
    val nodes: List<SessionHistoryNode> = emptyList(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

data class SessionHistoryNode(
    val id: String,
    val parentId: String? = null,
    val type: String,
    val role: String? = null,
    val title: String,
    val text: String = "",
    val timestamp: String = "",
    val label: String? = null,
    val onCurrentPath: Boolean = false,
    val navigable: Boolean = true,
    val feed: List<FeedItem> = emptyList(),
    val rawRecord: String = "",
    val copyBlocks: List<SessionCopyBlock> = emptyList(),
    val ask: AskCardItem? = null,
    /** Exact application feed identity, not a text-based guess. */
    val chatItemId: String? = null,
    /** A native message may project to separate text, thinking, Ask and tool nodes. */
    val chatItemIds: List<String> = emptyList(),
)

data class SessionCopyBlock(val title: String, val text: String, val icon: String = "notes")

enum class RuntimeAgentLifecycle(val label: String) {
    RUNNING("运行中"), IDLE("空闲"), PARKED("已停放"), ABORTED("已终止")
}
enum class RuntimeAgentOperation { STOP, KILL, REVIVE }
enum class AgentActivityKind(val label: String, val icon: String) {
    RESPONSE("回复", "chat"), TOOL("工具", "build"), COMMUNICATION("通信", "forum"),
    LIFECYCLE("生命周期", "change_circle"), ERROR("错误", "error")
}

data class AgentHubState(
    val mainSessionId: String = "",
    val agents: List<RuntimeAgent> = emptyList(),
    val activity: List<AgentActivity> = emptyList(),
    val activityLimit: Int = 20,
    val loading: Boolean = false,
    val connected: Boolean = false,
    val error: String? = null,
)

data class RuntimeAgent(
    /** Includes owning main-session identity and transcript identity, not just a local agent name. */
    val instanceKey: String,
    val id: String,
    val name: String,
    val parentId: String? = null,
    val lifecycle: RuntimeAgentLifecycle,
    val advisor: Boolean = false,
    val result: String? = null,
    val task: String = "",
    val activity: String = "",
    val error: String? = null,
    val modelId: String? = null,
    val providerId: String? = null,
    val modelRole: String? = null,
    val thinking: String? = null,
    val fallback: String? = null,
    val workspace: String? = null,
    val containerName: String = "",
    val sessionFile: String? = null,
    val createdAtMillis: Long? = null,
    val lastActivityMillis: Long? = null,
    val unread: Int? = null,
    val stats: RuntimeAgentStats = RuntimeAgentStats(),
    val artifacts: List<AgentArtifact> = emptyList(),
    val jobs: List<AgentBackgroundJob> = emptyList(),
    val canSend: Boolean = false,
    val canStop: Boolean = false,
    val canKill: Boolean = false,
    val canRevive: Boolean = false,
    val canSetModel: Boolean = false,
    val unavailableReason: String? = null,
    val dispatchEntryId: String? = null,
    val dispatchChatItemId: String? = null,
    val definitionName: String? = null,
)

data class RuntimeAgentStats(
    val elapsedMillis: Long? = null,
    val requests: Long? = null,
    val tools: Long? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val cacheReadTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
    val cost: Double? = null,
    val contextTokens: Long? = null,
    val contextWindow: Long? = null,
)

data class AgentActivity(
    val id: String,
    val agentId: String,
    val instanceKey: String,
    val kind: AgentActivityKind,
    val text: String,
    val timestamp: String = "",
    val entryId: String? = null,
    val chatItemId: String? = null,
)

data class AgentArtifact(val title: String, val path: String, val icon: String = "draft", val description: String = "")
data class AgentBackgroundJob(
    val id: String,
    val command: String,
    val cwd: String? = null,
    val pid: Long? = null,
    val status: String,
    val exitCode: Int? = null,
    val output: String = "",
    val outputPath: String? = null,
)
