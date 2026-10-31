package dev.wawlink.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import dev.wawlink.protocol.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

class ChatMessageActionsTest {
    private val attachment = AttachmentFile("file", "设计.txt", "/workspace/设计.txt", source = AttachmentSource.CONTAINER)
    private val user = SessionHistoryNode("native-user", type = "message", role = "user", title = "请求", parentId = "parent")
    private val assistant = SessionHistoryNode("native-answer", type = "message", role = "assistant", title = "回复")

    @Test fun fullMessageCopyPreservesMarkdownWhitespaceAndNeverAddsAttachmentPaths() {
        val source = "  # 标题\n\n```kotlin\n" + "val 中文 = 42\n".repeat(6000) + "```\n\n[链接](https://example.com)\n  "
        assertEquals(source, messageCopyText(AssistantMessageItem("a", source)))
        assertEquals("\n  原始正文  \n", messageCopyText(UserMessageItem("u", "\n  原始正文  \n", listOf(attachment))))
        assertEquals(" \n ", messageCopyText(UserMessageItem("blank", " \n ", listOf(attachment))))
        assertEquals("设计.txt\n图片.png", messageCopyText(UserMessageItem("files", "", listOf(attachment, attachment.copy(name = "图片.png")))))
        assertEquals("", messageCopyText(UserMessageItem("empty", "")))
    }

    @Test fun askCopyIncludesAllQuestionsOptionsPreviewsAndAnswersMatchedByNativeId() {
        val card = AskCardItem("ask", "请确认", listOf(
            AskQuestion("first", "第一题？", options = listOf(AskOption("方案 A", "保留内容", "**完整预览**")), recommended = 0),
            AskQuestion("second", "第二题？", multi = true, options = listOf(AskOption("甲"), AskOption("乙"))),
        ), listOf(AskAnswer("second", listOf("甲", "乙"), "补充"), AskAnswer("first", listOf("方案 A"))))
        val copied = messageCopyText(card)
        listOf("请确认", "第一题？", "方案 A", "保留内容", "**完整预览**", "第二题？", "甲", "乙", "补充").forEach { assertContains(copied, it) }
        assertTrue(copied.lastIndexOf("方案 A") < copied.indexOf("第二题？"))
        assertTrue(copied.lastIndexOf("甲") > copied.indexOf("第二题？"))
        assertTrue(copied.lastIndexOf("乙") > copied.indexOf("第二题？"))
    }

    @Test fun coldIndexIsLoadedBeforeNativeIdentityLookupAndDetailPanelDismissesToChat() = runBlocking {
        val explorer = Explorer()
        explorer.onRefresh = { explorer.history.value = explorer.history.value.copy(nodes = listOf(user)) }
        explorer.lookup = { check(explorer.history.value.nodes.isNotEmpty()); user.id }
        val actions = MessageHistoryActionsState(explorer) { false }
        actions.open("chat-user", FeedHistoryAction.COPY_BLOCKS)
        assertEquals(user, actions.node)
        assertEquals(FeedHistoryAction.COPY_BLOCKS, actions.action)
        assertEquals(1, explorer.refreshes)
        assertEquals(0, explorer.details)
        assertTrue(actions.dismiss())
        assertNull(actions.action)
        assertNull(actions.node)
        assertEquals("leaf", explorer.history.value.leafId)
    }

    @Test fun missingNodeRefreshesStaleIndexAndUnsavedMessageShowsAnActionableError() = runBlocking {
        val explorer = Explorer(listOf(assistant))
        explorer.lookup = { user.id }
        explorer.onRefresh = { explorer.history.value = explorer.history.value.copy(nodes = listOf(assistant, user)) }
        val actions = MessageHistoryActionsState(explorer) { false }
        actions.open("chat-user", FeedHistoryAction.SWITCH)
        assertEquals(user, actions.node)
        assertEquals(1, explorer.refreshes)
        explorer.lookup = { null }
        actions.open("unsaved", FeedHistoryAction.COPY_BLOCKS)
        assertNull(actions.node)
        assertFalse(actions.loading)
        assertNotNull(actions.error)
        assertTrue(actions.dismiss())
        assertNull(actions.action)
    }

    @Test fun lateLookupCannotReopenDismissedPanelOrReplaceNewMessageTarget() = runBlocking {
        val explorer = Explorer(listOf(user, assistant))
        val late = CompletableDeferred<String?>()
        explorer.lookup = { if (it == "old") late.await() else assistant.id }
        val actions = MessageHistoryActionsState(explorer) { false }
        val first = launch(start = CoroutineStart.UNDISPATCHED) { actions.open("old", FeedHistoryAction.SWITCH) }
        assertTrue(actions.loading)
        assertTrue(actions.dismiss())
        actions.open("new", FeedHistoryAction.COPY_BLOCKS)
        late.complete(user.id)
        first.join()
        assertEquals(FeedHistoryAction.COPY_BLOCKS, actions.action)
        assertEquals(assistant, actions.node)
        assertFalse(actions.loading)
    }

    @Test fun runningSessionAndInvalidTargetsCannotNavigateEvenAfterPanelWasOpened() = runBlocking {
        var running = false
        val explorer = Explorer(listOf(user))
        explorer.lookup = { user.id }
        val actions = MessageHistoryActionsState(explorer) { running }
        actions.open("chat", FeedHistoryAction.SWITCH)
        running = true
        actions.switch(HistorySummary(), true) { _, _ -> fail("运行中不能改草稿") }
        assertEquals(0, explorer.navigations)
        assertEquals(user, actions.node)
        assertNotNull(actions.error)
        actions.open("chat", FeedHistoryAction.SWITCH)
        assertNull(actions.node)
        running = false
        explorer.history.value = explorer.history.value.copy(nodes = listOf(user.copy(navigable = false)))
        actions.open("chat", FeedHistoryAction.SWITCH)
        assertNull(actions.node)
        assertNotNull(actions.error)
        explorer.history.value = explorer.history.value.copy(nodes = listOf(user), leafId = user.id)
        actions.open("chat", FeedHistoryAction.SWITCH)
        assertNull(actions.node)
        assertNotNull(actions.error)
        assertEquals(0, explorer.navigations)
    }

    @Test fun nativeFailureKeepsPanelAndDraftThenRetryRestoresOriginalTextAndAttachments() = runBlocking {
        val explorer = Explorer(listOf(user))
        explorer.lookup = { user.id }
        explorer.onNavigate = { _, _ -> error("摘要认证已过期") }
        val actions = MessageHistoryActionsState(explorer) { false }
        val draft = ComposerDraftState(mutableStateOf("我的草稿"), mutableStateListOf(attachment))
        actions.open("chat", FeedHistoryAction.SWITCH)
        actions.switch(HistorySummary(true), true, draft::restore)
        assertFalse(actions.busy)
        assertEquals(FeedHistoryAction.SWITCH, actions.action)
        assertEquals("摘要认证已过期", actions.error)
        assertEquals("我的草稿", draft.textState.value)
        assertEquals(listOf(attachment), draft.attachments.toList())
        val restored = attachment.copy(id = "original", name = "原始.png")
        explorer.onNavigate = { _, _ -> RestoredSessionPrompt("  原始输入\n", listOf(restored)) }
        actions.switch(HistorySummary(), true, draft::restore)
        assertNull(actions.action)
        assertEquals("  原始输入\n", draft.textState.value)
        assertEquals(listOf(restored), draft.attachments.toList())
    }

    @Test fun keepDraftAndEmptyDraftUseExistingRestoreSemantics() = runBlocking {
        val explorer = Explorer(listOf(user))
        explorer.lookup = { user.id }
        explorer.onNavigate = { _, _ -> RestoredSessionPrompt("原输入", listOf(attachment)) }
        val actions = MessageHistoryActionsState(explorer) { false }
        val localAttachment = attachment.copy(id = "draft")
        val draft = ComposerDraftState(mutableStateOf("保留草稿"), mutableStateListOf(localAttachment))
        actions.open("chat", FeedHistoryAction.SWITCH)
        actions.switch(HistorySummary(), false, draft::restore)
        assertEquals("保留草稿", draft.textState.value)
        assertEquals(listOf(localAttachment), draft.attachments.toList())
        draft.textState.value = ""
        draft.attachments.clear()
        actions.open("chat", FeedHistoryAction.SWITCH)
        actions.switch(HistorySummary(true, "保留错误原因"), false, draft::restore)
        assertEquals("原输入", draft.textState.value)
        assertEquals(listOf(attachment), draft.attachments.toList())
    }

    @Test fun duplicateConfirmationIsSuppressedAndCancellationNeverRestoresDraft() = runBlocking {
        val explorer = Explorer(listOf(user))
        explorer.lookup = { user.id }
        val result = CompletableDeferred<RestoredSessionPrompt?>()
        explorer.onNavigate = { _, _ -> result.await() }
        val actions = MessageHistoryActionsState(explorer) { false }
        val draft = ComposerDraftState(mutableStateOf("未发送"), mutableStateListOf(attachment))
        actions.open("chat", FeedHistoryAction.SWITCH)
        val first = async(start = CoroutineStart.UNDISPATCHED) { actions.switch(HistorySummary(true), true, draft::restore) }
        assertTrue(actions.busy)
        assertFalse(actions.dismiss())
        actions.switch(HistorySummary(), true, draft::restore)
        assertEquals(1, explorer.navigations)
        explorer.onCancel = { result.completeExceptionally(SessionNavigationCancelledException()) }
        actions.cancelNavigation()
        first.await()
        assertFalse(actions.busy)
        assertEquals(FeedHistoryAction.SWITCH, actions.action)
        assertEquals("未发送", draft.textState.value)
        assertEquals(listOf(attachment), draft.attachments.toList())
        assertNotNull(actions.error)
        assertTrue(actions.dismiss())
    }

    private class Explorer(nodes: List<SessionHistoryNode> = emptyList()) : SessionExplorer {
        override val history = MutableStateFlow(SessionHistoryState(sessionId = "session", leafId = "leaf", nodes = nodes))
        override val hub = MutableStateFlow(AgentHubState())
        var refreshes = 0
        var details = 0
        var navigations = 0
        var onRefresh: suspend () -> Unit = {}
        var lookup: suspend (String) -> String? = { null }
        var onNavigate: suspend (String, HistorySummary) -> RestoredSessionPrompt? = { _, _ -> null }
        var onCancel: suspend () -> Unit = {}
        override suspend fun refreshHistory() { refreshes++; onRefresh() }
        override suspend fun entryIdForChatItem(chatItemId: String) = lookup(chatItemId)
        override suspend fun navigate(entryId: String, summary: HistorySummary): RestoredSessionPrompt? { navigations++; return onNavigate(entryId, summary) }
        override suspend fun cancelNavigation() = onCancel()
        override suspend fun loadHistoryDetail(entryId: String, kind: HistoryDetailKind): SessionHistoryDetail { details++; error("详情只应由面板按需读取") }
        override suspend fun chatItemIdForEntry(entryId: String): String? = error("未使用")
        override suspend fun refreshHub(activityLimit: Int?, activityQuery: AgentActivityQuery?) = error("未使用")
        override suspend fun setLabel(entryId: String, label: String?) = error("未使用")
        override suspend fun reanswer(entryId: String, answers: List<AskAnswer>) = error("未使用")
        override suspend fun fork(entryId: String): String = error("不能创建独立会话")
        override suspend fun openAgent(instanceKey: String): RuntimeAgentSessionController = error("未使用")
        override suspend fun controlAgent(instanceKey: String, operation: RuntimeAgentOperation) = error("未使用")
        override suspend fun exportAgent(instanceKey: String): SessionExport = error("未使用")
    }
}
