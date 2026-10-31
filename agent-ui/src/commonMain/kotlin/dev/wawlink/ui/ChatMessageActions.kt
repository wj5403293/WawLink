package dev.wawlink.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wawlink.protocol.*
import kotlinx.coroutines.CancellationException

internal enum class FeedHistoryAction { COPY_BLOCKS, SWITCH, LOCATE }

/** Copies source text, never the streamed display buffer or an RPC attachment suffix. */
internal fun messageCopyText(item: FeedItem): String = when (item) {
    is UserMessageItem -> item.text.ifEmpty { item.attachments.joinToString("\n") { it.name } }
    is AssistantMessageItem -> item.markdownText
    is AskCardItem -> buildString {
        append(item.title)
        for (question in item.questions) {
            append("\n\n").append(question.question)
            for ((index, option) in question.options.withIndex()) {
                append("\n- ").append(option.label)
                if (question.recommended == index) append("（推荐）")
                option.description?.takeIf { it.isNotEmpty() }?.let { append("：").append(it) }
                option.preview?.takeIf { it.isNotEmpty() }?.let { append('\n').append(it) }
            }
            val answer = item.answers?.firstOrNull { it.questionId == question.id }
            append("\n回答：")
            when {
                answer != null -> append((answer.selectedOptions + listOfNotNull(answer.customInput)).joinToString("；").ifEmpty { "不选择任何选项" })
                item.cancelled -> append("已取消")
                else -> append("尚未回答")
            }
        }
    }
    else -> ""
}

/** Native identity must be resolved after the first lightweight index, and retried after a miss. */
internal suspend fun resolveMessageHistoryNode(explorer: SessionExplorer, chatItemId: String): SessionHistoryNode {
    var refreshed = false
    if (explorer.history.value.nodes.isEmpty()) {
        explorer.refreshHistory()
        refreshed = true
    }
    var entryId = explorer.entryIdForChatItem(chatItemId)
    var node = explorer.history.value.nodes.firstOrNull { it.id == entryId }
    if (node == null && !refreshed) {
        explorer.refreshHistory()
        entryId = explorer.entryIdForChatItem(chatItemId)
        node = explorer.history.value.nodes.firstOrNull { it.id == entryId }
    }
    return node ?: error(explorer.history.value.error ?: "这条消息尚未写入原生历史，请稍后重试")
}

/** A message opens one native detail surface, not a history tree hidden underneath it. */
internal class MessageHistoryActionsState(
    val explorer: SessionExplorer,
    private val isRunning: () -> Boolean,
) {
    var action by mutableStateOf<FeedHistoryAction?>(null)
        private set
    var chatItemId by mutableStateOf<String?>(null)
        private set
    var node by mutableStateOf<SessionHistoryNode?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var generation = 0L

    suspend fun open(id: String, requested: FeedHistoryAction) {
        require(requested != FeedHistoryAction.LOCATE)
        if (busy) return
        val current = ++generation
        action = requested
        chatItemId = id
        node = null
        loading = true
        error = null
        try {
            val resolved = resolveMessageHistoryNode(explorer, id)
            if (generation != current) return
            if (requested == FeedHistoryAction.SWITCH) {
                check(!isRunning()) { "会话正在运行，请停止后再创建分支" }
                check(resolved.navigable) { "这个节点不能作为续写位置" }
                check(resolved.id != explorer.history.value.leafId) { "当前已在这个节点" }
            }
            node = resolved
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (generation == current) error = failure.message ?: "消息历史读取失败"
        } finally {
            if (generation == current) loading = false
        }
    }

    fun dismiss(): Boolean {
        if (busy) return false
        generation++
        action = null
        chatItemId = null
        node = null
        error = null
        loading = false
        return true
    }

    suspend fun switch(summary: HistorySummary, replaceDraft: Boolean, restore: (RestoredSessionPrompt, Boolean) -> Unit) {
        val target = node ?: return
        if (action != FeedHistoryAction.SWITCH || busy || loading) return
        if (isRunning() || explorer.history.value.busy) {
            error = "会话正在运行，请停止后再创建分支"
            return
        }
        busy = true
        error = null
        try {
            check(target.navigable && target.id != explorer.history.value.leafId) { "续写位置已变化，请重新选择消息" }
            val prompt = explorer.navigate(target.id, summary)
            explorer.history.value.error?.let { kotlin.error(it) }
            if (prompt != null) restore(prompt, replaceDraft)
            busy = false
            dismiss()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cancelled: SessionNavigationCancelledException) {
            error = cancelled.message
        } catch (failure: Exception) {
            error = failure.message ?: "分支切换失败"
        } finally {
            busy = false
        }
    }

    suspend fun cancelNavigation() {
        try {
            explorer.cancelNavigation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "取消导航失败"
        }
    }
}
