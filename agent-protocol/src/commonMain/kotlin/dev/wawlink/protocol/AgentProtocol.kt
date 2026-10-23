package dev.wawlink.protocol

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Host-neutral contracts. The only runtime dependency is Kotlin coroutines for observable state. */
interface AgentSessionController {
    val state: StateFlow<ChatSessionUiState>
    fun sendPrompt(text: String, attachments: List<AttachmentFile> = emptyList())
    fun stopExecution()
    fun enqueuePrompt(text: String)
    fun updateQueuedPrompt(id: String, newText: String)
    fun removeQueuedPrompt(id: String)
    fun selectModel(providerId: String, modelId: String)
    fun selectThinkingLevel(level: ThinkingLevel)
    fun toggleExecutionMode(autoApprove: Boolean)
    fun respondApproval(nodeId: String, decision: ApprovalDecision)
    fun respondAsk(nodeId: String, selectedOption: String)
    fun executeSlashCommand(command: String, args: String)
    fun listWorkspaceFiles(relativePath: String): Flow<List<WorkspaceFileItem>>
}

interface ProviderConfigRepository {
    val providers: StateFlow<List<ProviderConfig>>
    val error: StateFlow<String?>
    suspend fun saveProvider(provider: ProviderConfig)
    suspend fun deleteProvider(providerId: String)
    suspend fun testConnection(provider: ProviderConfig): Result<LatencyInfo>
    suspend fun fetchRemoteModels(provider: ProviderConfig): Result<List<String>>
}

interface AgentSettingsRepository {
    val categories: StateFlow<List<SettingCategory>>
    val error: StateFlow<String?>
    suspend fun updateSetting(id: String, value: Any?)
    suspend fun resetSetting(id: String)
    suspend fun resetAll()
}

enum class SessionStatus { IDLE, RUNNING, WAITING, DONE, ERROR, STOPPED }
enum class ThinkingLevel(val label: String, val rays: Int) {
    NONE("无", 0), LOW("低", 1), MEDIUM("中", 2), HIGH("高", 3), XHIGH("超高", 4), MAX("最大", 5)
}
enum class ApprovalDecision { DENY, ALLOW_ONCE, ALLOW_ALWAYS_IN_SESSION }
enum class NodeStatus { RUNNING, OK, ERROR, STOPPED }
enum class StepState { PENDING, IN_PROGRESS, COMPLETED }
enum class LeadStyle { DARK, SOFT, TONE, WARN }
enum class DiffLineKind { CONTEXT, ADDED, REMOVED }

data class AttachmentFile(
    val id: String,
    val name: String,
    val path: String,
    val mimeType: String = "application/octet-stream",
    val sizeBytes: Long = 0,
    val isWorkspaceFile: Boolean = false,
)
data class WorkspaceFileItem(val name: String, val relativePath: String, val isDirectory: Boolean, val sizeBytes: Long = 0)
data class QueuedPrompt(val id: String, val text: String, val attachments: List<AttachmentFile> = emptyList())
data class UsageInfo(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val contextTokens: Long = 0,
    val contextWindow: Long = 0,
    val cost: Double = 0.0,
) {
    val totalTokens: Long get() = inputTokens + outputTokens
    val contextFraction: Float get() = if (contextWindow > 0) (contextTokens.toFloat() / contextWindow).coerceIn(0f, 1f) else 0f
}
data class ChatSessionUiState(
    val id: String,
    val title: String = "新会话",
    val workspace: String = "/workspace",
    val status: SessionStatus = SessionStatus.IDLE,
    val feed: List<FeedItem> = emptyList(),
    val compactedThroughId: String? = null,
    val queuedPrompts: List<QueuedPrompt> = emptyList(),
    val providerId: String = "",
    val modelId: String = "",
    val thinkingLevel: ThinkingLevel = ThinkingLevel.MEDIUM,
    val autoApprove: Boolean = true,
    val elapsedSec: Int = 0,
    val actionText: String = "准备就绪",
    val usage: UsageInfo = UsageInfo(),
    val addedLines: Int = 0,
    val removedLines: Int = 0,
    val error: String? = null,
) {
    val isRunning: Boolean get() = status == SessionStatus.RUNNING || status == SessionStatus.WAITING
}

sealed interface FeedItem { val id: String }
data class UserMessageItem(override val id: String, val text: String, val attachments: List<AttachmentFile> = emptyList(), val timestamp: Long = 0) : FeedItem
data class ThinkingNodeItem(override val id: String, val isStreaming: Boolean = false, val elapsedSec: Int = 0, val text: String = "", val isExpanded: Boolean = false) : FeedItem
data class PlanStep(val title: String, val icon: String = "radio_button_unchecked", val state: StepState = StepState.PENDING)
data class PlanNodeItem(override val id: String, val steps: List<PlanStep>, val isExpanded: Boolean = true) : FeedItem
data class GroupStats(val editCount: Int = 0, val readCount: Int = 0, val toolCount: Int = 0)
data class StepGroupNodeItem(
    override val id: String,
    val title: String,
    val icon: String = "deployed_code",
    val state: NodeStatus = NodeStatus.RUNNING,
    val elapsedSec: Int = 0,
    val stats: GroupStats = GroupStats(),
    val children: List<FeedItem> = emptyList(),
    val isExpanded: Boolean = true,
) : FeedItem
data class ToolNodeItem(
    override val id: String,
    val icon: String = "terminal",
    val leadStyle: LeadStyle = LeadStyle.SOFT,
    val titleHtml: String,
    val state: NodeStatus = NodeStatus.RUNNING,
    val elapsedSec: Int = 0,
    val previewContent: ToolPreview = ToolPreview.CommandOneLine(""),
    val detailContent: ToolDetail = ToolDetail.PlainText(""),
    val rawCopyText: String = "",
    val fullSheetText: String = "",
    val isExpanded: Boolean = false,
) : FeedItem
sealed interface ToolPreview {
    data class CommandOneLine(val cmd: String) : ToolPreview
    data class MidPath(val dirPrefix: String, val fileName: String, val plusLines: Int? = null, val minusLines: Int? = null) : ToolPreview
    data class MetaCount(val icon: String, val label: String, val count: Int) : ToolPreview
}
data class OutputLine(val text: String, val isError: Boolean = false)
data class DiffLine(val text: String, val kind: DiffLineKind = DiffLineKind.CONTEXT)
sealed interface ToolDetail {
    data class CommandAndOutput(val fullCmd: String, val outputLines: List<OutputLine>) : ToolDetail
    data class DiffBlock(val lines: List<DiffLine>) : ToolDetail
    data class PlainText(val text: String) : ToolDetail
}
data class ApprovalCardItem(override val id: String, val title: String, val description: String, val commandPreview: String, val resolution: ApprovalDecision? = null) : FeedItem
data class AskCardItem(override val id: String, val title: String, val question: String, val options: List<String>, val selectedAnswer: String? = null) : FeedItem
data class AssistantMessageItem(override val id: String, val markdownText: String, val isStreaming: Boolean = false) : FeedItem

enum class ProviderApiType(val label: String) { CHAT("Chat"), RESPONSE("Response"), MESSAGE("Message") }
data class ModelConfig(
    val id: String,
    val name: String = id,
    val thinkingLevel: ThinkingLevel = ThinkingLevel.MEDIUM,
    val reasoningEffort: String? = null,
    val thinkingBudget: Int? = null,
    val contextWindow: Int = 200_000,
    val maxTokens: Int = 16_384,
)
data class ProviderConfig(
    val id: String,
    val name: String,
    val apiType: ProviderApiType = ProviderApiType.CHAT,
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val enabled: Boolean = true,
    val models: List<ModelConfig> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
)
data class LatencyInfo(val latencyMs: Long, val statusCode: Int, val message: String = "连接成功")

enum class SettingType { BOOLEAN, ENUM, NUMBER, STRING, ARRAY, RECORD }
data class SettingOption(val value: String, val label: String = value)
data class SettingItem(
    val id: String,
    val title: String,
    val icon: String = "tune",
    val type: SettingType,
    val value: Any?,
    val defaultValue: Any?,
    val options: List<SettingOption> = emptyList(),
    val min: Double? = null,
    val max: Double? = null,
    val step: Double = 1.0,
) {
    val isModified: Boolean get() = value != defaultValue
}
data class SettingGroup(val id: String, val title: String, val icon: String, val items: List<SettingItem>)
data class SettingCategory(val id: String, val title: String, val icon: String, val groups: List<SettingGroup>)

data class AgentAppearance(
    val darkTheme: Boolean = false,
    val colorVisionAssist: Boolean = false,
    val smoothStreaming: Boolean = true,
    val alwaysShowUsage: Boolean = false,
    val hideToolCalls: Boolean = false,
    val collapseCompacted: Boolean = true,
)
