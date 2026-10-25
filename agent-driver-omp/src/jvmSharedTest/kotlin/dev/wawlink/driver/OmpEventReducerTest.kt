package dev.wawlink.driver

import dev.wawlink.protocol.*
import kotlinx.serialization.json.*
import kotlin.test.*

class OmpEventReducerTest {
    private fun reducer(clock: () -> Long = System::currentTimeMillis) = OmpEventReducer(ChatSessionUiState("session"), clock).also {
        it.accept("""{"type":"ready"}""")
        it.accept("""{"type":"agent_start"}""")
    }
    private fun OmpEventReducer.accept(json: String) = reduce(Json.parseToJsonElement(json).jsonObject)
    private fun OmpEventReducer.updateEvent(event: String, message: String = """{"role":"assistant"}""") = accept(
        """{"type":"message_update","messageId":"msg-1","message":$message,"assistantMessageEvent":$event}"""
    )
    private fun OmpEventReducer.startTool(id: String, name: String, args: String = "{}") = accept(
        """{"type":"tool_execution_start","toolCallId":"$id","toolName":"$name","args":$args}"""
    )
    private fun OmpEventReducer.endTool(id: String, name: String, result: String, error: Boolean = false) = accept(
        """{"type":"tool_execution_end","toolCallId":"$id","toolName":"$name","result":$result,"isError":$error}"""
    )
    private fun allItems(feed: List<FeedItem>): List<FeedItem> = feed.flatMap {
        listOf(it) + if (it is StepGroupNodeItem) allItems(it.children) else emptyList()
    }
    private fun OmpEventReducer.acceptAt(atMillis: Long, json: String) = reduce(JsonObject(
        Json.parseToJsonElement(json).jsonObject + ("_wawlinkAtMillis" to JsonPrimitive(atMillis))
    ))

    @Test fun groupDurationIsWallTimeIncludingThinkingRatherThanParallelToolSum() {
        var now = 1_000L
        val reducer = reducer { now }
        reducer.accept("""{"type":"todo_update","todos":[{"content":"Work","status":"in_progress"}]}""")
        reducer.updateEvent("""{"type":"thinking_start","contentIndex":0}""")
        now = 3_000L
        reducer.updateEvent("""{"type":"thinking_end","contentIndex":0,"content":"Ready"}""")
        reducer.startTool("parallel-a", "read")
        now = 4_000L
        reducer.startTool("parallel-b", "read")
        now = 11_000L
        reducer.endTool("parallel-a", "read", """{"content":[]}""")
        reducer.endTool("parallel-b", "read", """{"content":[]}""")
        now = 13_000L
        reducer.accept("""{"type":"agent_end","isTerminal":true,"messages":[]}""")
        val group = reducer.state.feed.filterIsInstance<StepGroupNodeItem>().single()
        assertEquals(1_000L, group.startedAtMillis)
        assertEquals(12, group.elapsedSec)
        assertEquals(2, group.children.filterIsInstance<ThinkingNodeItem>().single().elapsedSec)
        assertEquals(listOf(8, 7), group.children.filterIsInstance<ToolNodeItem>().map { it.elapsedSec })
        assertEquals(NodeStatus.OK, group.state)
        now = 90_000L
        reducer.accept("""{"type":"notice","message":"later"}""")
        reducer.accept("""{"type":"agent_end","isTerminal":true,"messages":[]}""")
        assertEquals(group, reducer.state.feed.filterIsInstance<StepGroupNodeItem>().single())
    }

    @Test fun everyTerminalOutcomeFreezesUnfinishedThinkingToolsAndGroups() {
        for ((outcome, status) in listOf("completed" to NodeStatus.OK, "error" to NodeStatus.ERROR, "aborted" to NodeStatus.STOPPED)) {
            var now = 1_000L
            val reducer = reducer { now }
            reducer.accept("""{"type":"todo_update","todos":[{"content":"Work","status":"in_progress"}]}""")
            reducer.updateEvent("""{"type":"thinking_delta","contentIndex":0,"delta":"Working"}""")
            reducer.startTool("unfinished", "bash", """{"command":"job"}""")
            now = 6_500L
            reducer.accept("""{"type":"prompt_result","status":"$outcome","agentInvoked":true,"sessionSettled":false}""")
            reducer.accept("""{"type":"agent_end","isTerminal":true,"messages":[]}""")
            val group = reducer.state.feed.filterIsInstance<StepGroupNodeItem>().single()
            assertEquals(status, group.state)
            assertEquals(5, group.elapsedSec)
            val thinking = group.children.filterIsInstance<ThinkingNodeItem>().single()
            assertFalse(thinking.isStreaming)
            assertEquals(1_000L, thinking.startedAtMillis)
            assertEquals(5, thinking.elapsedSec)
            val tool = group.children.filterIsInstance<ToolNodeItem>().single()
            assertEquals(status, tool.state)
            assertEquals(1_000L, tool.startedAtMillis)
            assertEquals(5, tool.elapsedSec)
            now = 60_000L
            reducer.endTool("unfinished", "bash", """{"content":[]}""")
            reducer.updateEvent("""{"type":"thinking_end","contentIndex":0,"content":"Working"}""")
            assertEquals(group, reducer.state.feed.filterIsInstance<StepGroupNodeItem>().single())
        }
    }

    @Test fun executionStartAndReportedFinalDurationSurviveLateSnapshots() {
        var now = 1_000L
        val reducer = reducer { now }
        val call = """{"type":"toolCall","id":"call","name":"bash","arguments":{"command":"job"}}"""
        reducer.updateEvent("""{"type":"toolcall_end","contentIndex":0,"toolCall":$call}""")
        now = 4_000L
        reducer.startTool("call", "bash", """{"command":"job"}""")
        assertEquals(4_000L, reducer.state.feed.filterIsInstance<ToolNodeItem>().single().startedAtMillis)
        now = 8_000L
        reducer.endTool("call", "bash", """{"content":[],"details":{"durationMs":3500}}""")
        now = 40_000L
        reducer.endTool("call", "bash", """{"content":[]}""")
        reducer.accept("""{"type":"message_end","messageId":"msg-1","message":{"role":"assistant","content":[$call]}}""")
        val tool = reducer.state.feed.filterIsInstance<ToolNodeItem>().single()
        assertEquals(3, tool.elapsedSec)
        assertEquals(4_000L, tool.startedAtMillis)
        assertEquals(NodeStatus.OK, tool.state)
    }

    @Test fun localStopAndFailureFreezeRunningNodesWithoutChangingCompletedDurations() {
        val done = ToolNodeItem("done", titleHtml = "done", state = NodeStatus.OK, elapsedSec = 2, startedAtMillis = 1_000L)
        val feed = listOf(StepGroupNodeItem("group", "Work", startedAtMillis = 500L, children = listOf(
            ThinkingNodeItem("thinking", isStreaming = true, startedAtMillis = 2_000L),
            ToolNodeItem("tool", titleHtml = "tool", startedAtMillis = 3_000L), done,
        )))
        for (status in listOf(NodeStatus.STOPPED, NodeStatus.ERROR)) {
            val stopped = stopFeed(feed, 6_500L, status)
            val group = stopped.single() as StepGroupNodeItem
            assertEquals(status, group.state)
            assertEquals(6, group.elapsedSec)
            assertEquals(4, group.children.filterIsInstance<ThinkingNodeItem>().single().elapsedSec)
            assertEquals(3, group.children.filterIsInstance<ToolNodeItem>().first().elapsedSec)
            assertEquals(done, group.children.last())
            assertEquals(stopped, stopFeed(stopped, 60_000L, status))
        }
    }

    @Test fun journalEventTimesRestoreFinalDurationsInsteadOfReplaySpeed() {
        fun replay(clock: Long): ChatSessionUiState {
            val reducer = OmpEventReducer(ChatSessionUiState("session")) { clock }
            reducer.acceptAt(1_000L, """{"type":"ready"}""")
            reducer.acceptAt(1_000L, """{"type":"agent_start"}""")
            reducer.acceptAt(2_000L, """{"type":"todo_update","todos":[{"content":"Work","status":"in_progress"}]}""")
            reducer.acceptAt(3_000L, """{"type":"thinking_delta","delta":"Working"}""")
            reducer.acceptAt(4_000L, """{"type":"tool_execution_start","toolCallId":"tool","toolName":"read","args":{}}""")
            reducer.acceptAt(9_000L, """{"type":"agent_end","isTerminal":true,"messages":[]}""")
            return reducer.state
        }
        val original = replay(10_000L)
        assertEquals(original, replay(900_000L))
        val group = original.feed.filterIsInstance<StepGroupNodeItem>().single()
        assertEquals(7, group.elapsedSec)
        assertEquals(6, group.children.filterIsInstance<ThinkingNodeItem>().single().elapsedSec)
        assertEquals(5, group.children.filterIsInstance<ToolNodeItem>().single().elapsedSec)
    }

    @Test fun nestedStreamingAndFinalizationPreservePriorSnapshotsAndMessageOrder() {
        val thinking = ThinkingNodeItem("omp:1:message:msg-1:block:0", isStreaming = true, text = "Before", startedAtMillis = 1_000L)
        val tool = ToolNodeItem("nested-tool", titleHtml = "Read", startedAtMillis = 1_000L)
        val narration = AssistantMessageItem("nested-narration", "Narration", isStreaming = true)
        val history = StepGroupNodeItem("history", "History", state = NodeStatus.OK, children = listOf(
            UserMessageItem("history-user", "Original question"), AssistantMessageItem("history-answer", "Original answer"),
        ))
        val nested = StepGroupNodeItem("nested", "Nested", children = listOf(thinking, narration, tool))
        val current = StepGroupNodeItem("current", "Current", children = listOf(UserMessageItem("anchored-user", "Continue"), nested))
        val captured = listOf(history, current, AssistantMessageItem("last-answer", "Unchanged tail"))
        val reducer = OmpEventReducer(ChatSessionUiState("session", feed = captured)) { 4_000L }
        reducer.accept("""{"type":"ready"}""")
        reducer.updateEvent("""{"type":"thinking_delta","contentIndex":0,"delta":" after"}""")
        val streaming = reducer.state.feed
        val streamingNodes = allItems(streaming)
        assertEquals("Before after", streamingNodes.filterIsInstance<ThinkingNodeItem>().single().text)
        assertEquals(allItems(captured).map { it.id }, streamingNodes.map { it.id })
        assertEquals(history, streaming.first())
        assertEquals("Before", allItems(captured).filterIsInstance<ThinkingNodeItem>().single().text)
        reducer.accept("""{"type":"agent_end","isTerminal":true,"messages":[]}""")
        val finished = allItems(reducer.state.feed)
        assertEquals(thinking.copy(text = "Before after", isStreaming = false, elapsedSec = 3), finished.filterIsInstance<ThinkingNodeItem>().single())
        assertEquals(narration.copy(isStreaming = false), finished.filterIsInstance<AssistantMessageItem>().single { it.id == narration.id })
        assertEquals(tool.copy(state = NodeStatus.OK, elapsedSec = 3), finished.filterIsInstance<ToolNodeItem>().single())
        assertTrue(streamingNodes.filterIsInstance<ThinkingNodeItem>().single().isStreaming)
        assertTrue(streamingNodes.filterIsInstance<AssistantMessageItem>().single { it.id == narration.id }.isStreaming)
        assertEquals(history, reducer.state.feed.first())
        assertEquals(allItems(captured).map { it.id }, finished.map { it.id })
    }

    @Test fun unchangedPlansSurviveNewUsersStartsRemindersResultsAndStateSnapshots() {
        val reducer = reducer()
        val tasks = """[{"content":"Read","status":"in_progress"},{"content":"Edit","status":"pending"}]"""
        val details = """{"phases":[{"name":"Work","tasks":$tasks}]}"""
        val result = """{"role":"toolResult","toolCallId":"todo-init","toolName":"todo","content":[],"details":$details}"""
        reducer.endTool("todo-init", "todo", result)
        reducer.startTool("first", "read")
        reducer.accept("""{"type":"agent_end","isTerminal":true,"messages":[$result]}""")
        val plan = reducer.state.feed.filterIsInstance<PlanNodeItem>().single()
        val oldGroup = reducer.state.feed.filterIsInstance<StepGroupNodeItem>().single()
        val user = UserMessageItem("next-user", "Continue")
        reducer.addUserMessage(user)
        reducer.accept("""{"type":"agent_start"}""")
        reducer.accept("""{"type":"todo_reminder","todos":$tasks}""")
        reducer.accept("""{"type":"response","command":"get_state","success":true,"data":{"todoPhases":[{"name":"Renamed metadata","tasks":$tasks}]}}""")
        reducer.accept("""{"type":"message_end","message":$result}""")
        reducer.accept("""{"type":"response","command":"get_messages","success":true,"data":{"messages":[$result]}}""")
        reducer.endTool("todo-query", "todo", """{"content":[],"details":$details}""")
        reducer.accept("""{"type":"message_update","messageId":"next-thinking","assistantMessageEvent":{"type":"thinking_delta","delta":"Continue reading"}}""")
        reducer.startTool("second", "read")
        assertEquals(listOf(plan), reducer.state.feed.filterIsInstance<PlanNodeItem>())
        val groups = reducer.state.feed.filterIsInstance<StepGroupNodeItem>()
        assertEquals(2, groups.size)
        assertEquals(oldGroup, groups.first())
        assertEquals(NodeStatus.RUNNING, groups.last().state)
        assertEquals("Continue reading", groups.last().children.filterIsInstance<ThinkingNodeItem>().single().text)
        assertEquals("omp:1:tool:second", groups.last().children.filterIsInstance<ToolNodeItem>().single().id)
        assertTrue(reducer.state.feed.indexOf(plan) < reducer.state.feed.indexOf(user))
        assertTrue(reducer.state.feed.indexOf(user) < reducer.state.feed.indexOf(groups.last()))
    }

    @Test fun changedTaskStatusAndTitlesCreateNewPlansOnlyAfterConversationBoundaries() {
        val reducer = reducer()
        reducer.accept("""{"type":"todo_update","todos":[{"content":"Read","status":"in_progress"}]}""")
        reducer.accept("""{"type":"agent_end","isTerminal":true,"messages":[]}""")
        val original = reducer.state.feed.filterIsInstance<PlanNodeItem>().single()
        val user = UserMessageItem("next-user", "Edit instead")
        reducer.addUserMessage(user)
        reducer.accept("""{"type":"agent_start"}""")
        reducer.accept("""{"type":"todo_update","todos":[{"content":"Read","status":"completed"}]}""")
        val second = reducer.state.feed.filterIsInstance<PlanNodeItem>().last()
        assertNotEquals(original.id, second.id)
        assertTrue(reducer.state.feed.indexOf(second) > reducer.state.feed.indexOf(user))
        reducer.accept("""{"type":"todo_update","todos":[{"content":"Edit","status":"in_progress"}]}""")
        assertEquals(listOf(original.id, second.id), reducer.state.feed.filterIsInstance<PlanNodeItem>().map { it.id })
        assertEquals("Edit", reducer.state.feed.filterIsInstance<PlanNodeItem>().last().steps.single().title)
        reducer.accept("""{"type":"agent_end","isTerminal":true,"messages":[]}""")
        reducer.accept("""{"type":"agent_start"}""")
        reducer.accept("""{"type":"todo_update","todos":[{"content":"Verify","status":"in_progress"}]}""")
        assertEquals(3, reducer.state.feed.filterIsInstance<PlanNodeItem>().size)
        assertEquals(original, reducer.state.feed.filterIsInstance<PlanNodeItem>().first())
    }

    @Test fun unchangedActivePlanStillGroupsNextTurnWithoutAnotherTodoFrame() {
        val reducer = reducer()
        reducer.accept("""{"type":"todo_update","todos":[{"content":"Read","status":"in_progress"}]}""")
        reducer.startTool("first", "read")
        reducer.accept("""{"type":"agent_end","isTerminal":true,"messages":[]}""")
        val oldGroup = reducer.state.feed.filterIsInstance<StepGroupNodeItem>().single()
        val user = UserMessageItem("next-user", "Continue")
        reducer.addUserMessage(user)
        reducer.accept("""{"type":"agent_start"}""")
        reducer.startTool("next", "read")
        val groups = reducer.state.feed.filterIsInstance<StepGroupNodeItem>()
        assertEquals(listOf("Read", "Read"), groups.map { it.title })
        assertEquals(oldGroup, groups.first())
        assertEquals("omp:1:tool:next", groups.last().children.single().id)
        assertEquals(1, reducer.state.feed.filterIsInstance<PlanNodeItem>().size)
        assertEquals(listOf(user), allItems(reducer.state.feed).filterIsInstance<UserMessageItem>())
    }

    @Test fun importedHistoryKeepsUnchangedPlansAndPlacesChangedPlansAfterTheirUsers() {
        val reducer = OmpEventReducer(ChatSessionUiState("session")) { 100_000L }
        reducer.accept("""{"type":"ready"}""")
        val messages = """[
            {"role":"user","content":"Start","timestamp":1},
            {"role":"toolResult","toolCallId":"todo-first","toolName":"todo","content":[],"details":{"tasks":[{"content":"Read","status":"in_progress"}]}},
            {"role":"assistant","timestamp":2,"content":[{"type":"thinking","thinking":"First turn"}]},
            {"role":"user","content":"Continue","timestamp":3},
            {"role":"toolResult","toolCallId":"todo-reminder","toolName":"todo","content":[],"details":{"tasks":[{"content":"Read","status":"in_progress"}]}},
            {"role":"assistant","timestamp":4,"content":[{"type":"thinking","thinking":"Second turn"}]},
            {"role":"user","content":"Change","timestamp":5},
            {"role":"toolResult","toolCallId":"todo-changed","toolName":"todo","content":[],"details":{"tasks":[{"content":"Read","status":"completed"},{"content":"Edit","status":"in_progress"}]}},
            {"role":"assistant","timestamp":6,"content":[{"type":"thinking","thinking":"Third turn"}]}
        ]"""
        val response = """{"type":"response","command":"get_messages","success":true,"data":{"messages":$messages}}"""
        reducer.accept(response)
        val feed = reducer.state.feed
        val plans = feed.filterIsInstance<PlanNodeItem>()
        assertEquals(2, plans.size)
        assertEquals(listOf("Read"), plans.first().steps.map { it.title })
        assertEquals(listOf("Read", "Edit"), plans.last().steps.map { it.title })
        val users = feed.filterIsInstance<UserMessageItem>()
        assertEquals(listOf("Start", "Continue", "Change"), users.map { it.text })
        assertTrue(feed.indexOf(plans.first()) < feed.indexOf(users[1]))
        assertTrue(feed.indexOf(plans.last()) > feed.indexOf(users[2]))
        val groups = feed.filterIsInstance<StepGroupNodeItem>()
        assertEquals(listOf("Read", "Read", "Edit"), groups.map { it.title })
        assertEquals(listOf("First turn", "Second turn", "Third turn"), groups.map {
            it.children.filterIsInstance<ThinkingNodeItem>().single().text
        })
        assertTrue(groups.all { it.state == NodeStatus.OK && it.startedAtMillis == null })
        reducer.accept(response)
        assertEquals(feed, reducer.state.feed)
    }

    @Test fun importedHistoryNeverStartsLiveClocksOrDuplicatesAnUnchangedPlan() {
        val reducer = OmpEventReducer(ChatSessionUiState("session")) { 100_000L }
        reducer.accept("""{"type":"ready"}""")
        val messages = """[
            {"role":"toolResult","toolCallId":"todo","toolName":"todo","content":[],"details":{"phases":[{"tasks":[{"content":"Read","status":"in_progress"}]}]}},
            {"role":"assistant","content":[{"type":"thinking","thinking":"History"},{"type":"toolCall","id":"read","name":"read","arguments":{"path":"Main.kt"}}]},
            {"role":"toolResult","toolCallId":"read","toolName":"read","content":[{"type":"text","text":"val old = true"}],"details":{"durationMs":9000}}
        ]"""
        val response = """{"type":"response","command":"get_messages","success":true,"data":{"messages":$messages}}"""
        reducer.accept(response)
        val feed = reducer.state.feed
        val group = feed.filterIsInstance<StepGroupNodeItem>().single()
        assertEquals(NodeStatus.OK, group.state)
        assertNull(group.startedAtMillis)
        val thinking = group.children.filterIsInstance<ThinkingNodeItem>().single()
        assertFalse(thinking.isStreaming)
        assertNull(thinking.startedAtMillis)
        val tool = group.children.filterIsInstance<ToolNodeItem>().single()
        assertEquals(NodeStatus.OK, tool.state)
        assertNull(tool.startedAtMillis)
        assertEquals(9, tool.elapsedSec)
        reducer.accept(response)
        reducer.accept("""{"type":"response","command":"get_state","success":true,"data":{"todoPhases":[{"tasks":[{"content":"Read","status":"in_progress"}]}],"isSettled":true}}""")
        assertEquals(feed, reducer.state.feed)
    }

    @Test fun toolSourceMetadataContainsOnlyActualPayloadAndPreservesReturnedLineNumbers() {
        val reducer = reducer()
        reducer.startTool("read", "read", """{"path":"Main.kt"}""")
        reducer.endTool("read", "read", """{"content":[{"type":"text","text":"1: val x = 1\n2: println(x)"}]}""")
        reducer.startTool("write", "write", """{"path":"out.py","content":"print('hello')","language":"python"}""")
        reducer.endTool("write", "write", """{"content":[{"type":"text","text":"Permission denied"}]}""", error = true)
        reducer.startTool("edit", "edit", """{"path":"Main.kt","newText":"val x = 2"}""")
        reducer.startTool("create", "create", """{"file_path":"out.js","text":"const x = 1;"}""")
        reducer.startTool("grep", "grep", """{"path":"Main.kt","pattern":"val"}""")
        reducer.endTool("grep", "grep", """{"content":[{"type":"text","text":"Main.kt:1:val x = 1"}]}""")
        reducer.startTool("find", "find", """{"pattern":"*.kt"}""")
        reducer.endTool("find", "find", """{"content":[{"type":"text","text":"src/Main.kt"}]}""")
        reducer.startTool("python", "python", """{"code":"print(1)"}""")
        reducer.startTool("eval", "eval", """{"language":"js","code":"console.log(1)"}""")
        reducer.endTool("eval", "eval", """{"content":[{"type":"text","text":"1"}]}""")
        reducer.startTool("unknown", "custom", """{"query":"not source"}""")
        val tools = reducer.state.feed.filterIsInstance<ToolNodeItem>().associateBy { it.id.substringAfterLast(':') }
        assertEquals("1: val x = 1\n2: println(x)", tools.getValue("read").codeText)
        assertEquals("Main.kt", tools.getValue("read").codePath)
        assertNull(tools.getValue("read").resultText)
        assertEquals("print('hello')", tools.getValue("write").codeText)
        assertEquals("python", tools.getValue("write").codeLanguage)
        assertEquals("Permission denied", tools.getValue("write").resultText)
        assertEquals(NodeStatus.ERROR, tools.getValue("write").state)
        assertEquals("val x = 2", tools.getValue("edit").codeText)
        assertEquals("const x = 1;", tools.getValue("create").codeText)
        assertEquals("out.js", tools.getValue("create").codePath)
        assertEquals("Main.kt:1:val x = 1", tools.getValue("grep").codeText)
        assertEquals("src/Main.kt", tools.getValue("find").codeText)
        assertEquals("print(1)", tools.getValue("python").codeText)
        assertEquals("python", tools.getValue("python").codeInterpreter)
        assertEquals("console.log(1)", tools.getValue("eval").codeText)
        assertEquals("js", tools.getValue("eval").codeLanguage)
        assertEquals("js", tools.getValue("eval").codeInterpreter)
        assertEquals("1", tools.getValue("eval").resultText)
        assertNull(tools.getValue("unknown").codeText)
        assertTrue(tools.getValue("read").fullSheetText.contains("\"path\""))
    }

    @Test fun userMessagesStayBetweenEarlierAndLaterTodoChildrenAcrossSnapshots() {
        val reducer = reducer()
        reducer.accept("""{"type":"todo_update","todos":[{"content":"Read","status":"in_progress"}]}""")
        reducer.startTool("before", "read", """{"path":"before.txt"}""")
        val before = reducer.state.feed.filterIsInstance<StepGroupNodeItem>().single().children.single()
        val message = """{"role":"user","content":"补充要求","timestamp":123}"""
        reducer.accept("""{"type":"message_start","messageId":"follow-up","message":$message}""")
        reducer.accept("""{"type":"message_end","messageId":"follow-up","message":$message}""")
        val user = allItems(reducer.state.feed).filterIsInstance<UserMessageItem>().single()
        reducer.updateEvent("""{"type":"thinking_delta","contentIndex":0,"delta":"遵循补充要求"}""")
        val thinking = allItems(reducer.state.feed).filterIsInstance<ThinkingNodeItem>().single()
        reducer.startTool("after", "read", """{"path":"after.txt"}""")
        val group = reducer.state.feed.filterIsInstance<StepGroupNodeItem>().single()
        val after = group.children.filterIsInstance<ToolNodeItem>().last()
        val order = listOf(before.id, user.id, thinking.id, after.id)
        assertEquals(order, group.children.map { it.id })
        assertTrue(reducer.state.feed.none { it is UserMessageItem })
        reducer.accept("""{"type":"todo_update","todos":[{"content":"Read","status":"completed"},{"content":"Edit","status":"in_progress"}]}""")
        reducer.accept("""{"type":"message_end","messageId":"follow-up","message":$message}""")
        reducer.accept("""{"type":"agent_end","isTerminal":true,"messages":[$message]}""")
        val groups = reducer.state.feed.filterIsInstance<StepGroupNodeItem>()
        assertEquals(order, groups.first().children.map { it.id })
        assertEquals(listOf(user), allItems(reducer.state.feed).filterIsInstance<UserMessageItem>())
        assertTrue(groups.last().children.isEmpty())
    }

    @Test fun queuedUsersKeepTheirSendPositionsWhenConsumedAfterToolsAndTaskSwitches() {
        val reducer = reducer()
        reducer.accept("""{"type":"todo_update","todos":[{"content":"Read","status":"in_progress"}]}""")
        reducer.startTool("before", "read")
        val originalGroup = reducer.state.feed.filterIsInstance<StepGroupNodeItem>().single()
        val first = QueuedPrompt("queued-first", "相同追加")
        val second = QueuedPrompt("queued-second", "相同追加")
        val position = reducer.currentUserPosition()
        reducer.reserveUserPosition(first.id, position)
        reducer.reserveUserPosition(second.id, position)
        reducer.update { it.copy(queuedPrompts = listOf(first, second)) }
        reducer.startTool("later", "read")
        reducer.accept("""{"type":"todo_update","todos":[{"content":"Read","status":"completed"},{"content":"Edit","status":"in_progress"}]}""")
        reducer.accept("""{"type":"queue_update","steering":[],"followUp":[]}""")
        reducer.accept("""{"type":"message_start","messageId":"first","message":{"role":"user","content":"相同追加"}}""")
        reducer.accept("""{"type":"message_end","messageId":"first","message":{"role":"user","content":"相同追加"}}""")
        reducer.accept("""{"type":"message_end","messageId":"second","message":{"role":"user","content":"相同追加"}}""")
        val groups = reducer.state.feed.filterIsInstance<StepGroupNodeItem>()
        assertEquals(listOf(originalGroup.children.single().id, first.id, second.id, "omp:1:tool:later"), groups.first().children.map { it.id })
        assertTrue(groups.last().children.isEmpty())
        assertEquals(listOf(first.id, second.id), allItems(reducer.state.feed).filterIsInstance<UserMessageItem>().map { it.id })
    }

    @Test fun localNestedUserKeepsIdentityAndAttachmentsAcrossRepeatedNativeSnapshots() {
        val reducer = reducer()
        reducer.accept("""{"type":"todo_update","todos":[{"content":"Read","status":"in_progress"}]}""")
        val attachment = AttachmentFile("attachment", "要求.txt", "/workspace/要求.txt")
        val user = UserMessageItem("local-user", "补充要求", listOf(attachment), 123, accepted = false)
        reducer.addUserMessage(user)
        val message = """{"role":"user","content":"补充要求","timestamp":123}"""
        reducer.accept("""{"type":"message_start","messageId":"native-user","message":$message}""")
        reducer.startTool("after-local", "read")
        reducer.accept("""{"type":"message_end","messageId":"native-user","message":$message}""")
        reducer.accept("""{"type":"agent_end","isTerminal":true,"messages":[$message]}""")
        val group = reducer.state.feed.filterIsInstance<StepGroupNodeItem>().single()
        assertEquals(listOf(user.id, "omp:1:tool:after-local"), group.children.map { it.id })
        assertEquals(listOf(user.copy(accepted = true)), allItems(reducer.state.feed).filterIsInstance<UserMessageItem>())
        assertTrue(reducer.hasConfirmedUser(user.id))
    }

    @Test fun thinkingSnapshotsBelongToActiveTodoWithoutGroupingOtherMessages() {
        val reducer = reducer()
        reducer.accept("""{"type":"todo_update","todos":[{"content":"Read","status":"in_progress"}]}""")
        val message = """{"role":"assistant","content":[{"type":"thinking","thinking":"分析"},{"type":"text","text":"说明"}]}"""
        reducer.accept("""{"type":"message_start","messageId":"msg-1","message":$message}""")
        val group = reducer.state.feed.filterIsInstance<StepGroupNodeItem>().single()
        val thinking = group.children.filterIsInstance<ThinkingNodeItem>().single()
        assertEquals("分析", thinking.text)
        assertTrue(thinking.isStreaming)
        assertEquals(GroupStats(), group.stats)
        assertTrue(reducer.state.feed.none { it is ThinkingNodeItem })
        assertEquals("说明", reducer.state.feed.filterIsInstance<AssistantMessageItem>().single().markdownText)
        reducer.accept("""{"type":"message_end","messageId":"msg-1","message":$message}""")
        reducer.accept("""{"type":"agent_end","isTerminal":true,"messages":[$message]}""")
        val final = allItems(reducer.state.feed).filterIsInstance<ThinkingNodeItem>().single()
        assertEquals(thinking.id, final.id)
        assertFalse(final.isStreaming)
        assertEquals(final, reducer.state.feed.filterIsInstance<StepGroupNodeItem>().single().children.single())
    }

    @Test fun streamingThinkingKeepsItsOriginalTodoAcrossTaskChangesAndEndReplay() {
        val reducer = reducer()
        reducer.accept("""{"type":"todo_update","todos":[{"content":"Read","status":"in_progress"}]}""")
        reducer.updateEvent("""{"type":"thinking_delta","contentIndex":0,"delta":"分析"}""")
        val original = allItems(reducer.state.feed).filterIsInstance<ThinkingNodeItem>().single()
        reducer.update { state -> state.copy(feed = state.feed.map {
            if (it is StepGroupNodeItem) it.copy(children = it.children.map { child ->
                if (child is ThinkingNodeItem) child.copy(isExpanded = true) else child
            }) else it
        }) }
        reducer.accept("""{"type":"todo_update","todos":[{"content":"Read","status":"completed"},{"content":"Edit","status":"in_progress"}]}""")
        reducer.updateEvent("""{"type":"thinking_delta","contentIndex":0,"delta":"继续"}""")
        val full = """{"role":"assistant","content":[{"type":"thinking","thinking":"分析继续完成"}]}"""
        reducer.updateEvent("""{"type":"thinking_delta","contentIndex":0,"delta":"完成","partial":$full}""", full)
        reducer.updateEvent("""{"type":"thinking_end","contentIndex":0,"content":"分析继续完成"}""")
        reducer.updateEvent("""{"type":"thinking_delta","contentIndex":1,"delta":"编辑思考"}""")
        val groups = reducer.state.feed.filterIsInstance<StepGroupNodeItem>()
        assertEquals(listOf("Read", "Edit"), groups.map { it.title })
        assertEquals(original.id, groups[0].children.filterIsInstance<ThinkingNodeItem>().single().id)
        assertEquals("编辑思考", groups[1].children.filterIsInstance<ThinkingNodeItem>().single().text)
        reducer.accept("""{"type":"todo_auto_clear"}""")
        reducer.accept("""{"type":"message_end","messageId":"msg-1","message":$full}""")
        reducer.accept("""{"type":"agent_end","isTerminal":true,"messages":[$full]}""")
        val finalGroups = reducer.state.feed.filterIsInstance<StepGroupNodeItem>()
        val final = finalGroups[0].children.filterIsInstance<ThinkingNodeItem>().single()
        assertEquals(original.id, final.id)
        assertEquals("分析继续完成", final.text)
        assertTrue(final.isExpanded)
        assertFalse(final.isStreaming)
        assertEquals(2, allItems(reducer.state.feed).filterIsInstance<ThinkingNodeItem>().size)
        assertTrue(reducer.state.feed.none { it is ThinkingNodeItem })
        assertEquals("编辑思考", finalGroups[1].children.filterIsInstance<ThinkingNodeItem>().single().text)
    }

    @Test fun flatThinkingDoesNotMoveIntoALaterTodoAndNewThinkingFallsBackAfterTodoClears() {
        val reducer = reducer()
        reducer.updateEvent("""{"type":"thinking_delta","contentIndex":0,"delta":"开始"}""")
        val original = reducer.state.feed.filterIsInstance<ThinkingNodeItem>().single()
        reducer.accept("""{"type":"todo_update","todos":[{"content":"Read","status":"in_progress"}]}""")
        val full = """{"role":"assistant","content":[{"type":"thinking","thinking":"开始完成"}]}"""
        reducer.updateEvent("""{"type":"done","message":$full}""", full)
        reducer.accept("""{"type":"message_end","messageId":"msg-1","message":$full}""")
        assertTrue(reducer.state.feed.filterIsInstance<StepGroupNodeItem>().single().children.isEmpty())
        assertEquals(original.id, reducer.state.feed.filterIsInstance<ThinkingNodeItem>().single().id)
        reducer.accept("""{"type":"todo_auto_clear"}""")
        reducer.updateEvent("""{"type":"thinking_delta","contentIndex":1,"delta":"收尾"}""")
        reducer.accept("""{"type":"agent_end","isTerminal":true,"messages":[$full]}""")
        val thinking = reducer.state.feed.filterIsInstance<ThinkingNodeItem>()
        assertEquals(listOf("开始完成", "收尾"), thinking.map { it.text })
        assertTrue(thinking.all { !it.isStreaming })
        assertEquals(2, allItems(reducer.state.feed).filterIsInstance<ThinkingNodeItem>().size)
    }

    @Test fun nestedDeltasAndFinalSnapshotsDoNotDuplicateTextOrUsage() {
        val reducer = reducer()
        reducer.accept("""{"type":"message_start","messageId":"msg-1","message":{"role":"assistant","content":[]}}""")
        reducer.updateEvent("""{"type":"thinking_start","contentIndex":0}""")
        reducer.updateEvent("""{"type":"thinking_delta","contentIndex":0,"delta":"分析"}""")
        reducer.updateEvent("""{"type":"thinking_end","contentIndex":0,"content":"分析完成"}""")
        reducer.updateEvent("""{"type":"text_start","contentIndex":1}""")
        reducer.updateEvent("""{"type":"text_delta","contentIndex":1,"delta":"Hello"}""")
        val full = """{"role":"assistant","content":[{"type":"thinking","thinking":"分析完成"},{"type":"text","text":"Hello world"}],"usage":{"input":10,"output":4,"cacheRead":8,"cacheWrite":2,"contextTokens":24,"cost":{"total":0.25}},"stopReason":"stop","timestamp":123}"""
        reducer.updateEvent("""{"type":"text_delta","contentIndex":1,"delta":" world","partial":$full}""", full)
        reducer.updateEvent("""{"type":"done","message":$full}""", full)
        reducer.accept("""{"type":"message_end","messageId":"msg-1","message":$full}""")
        assertEquals("Hello world", reducer.state.feed.filterIsInstance<AssistantMessageItem>().single().markdownText)
        assertEquals("分析完成", reducer.state.feed.filterIsInstance<ThinkingNodeItem>().single().text)
        assertFalse(reducer.state.feed.filterIsInstance<AssistantMessageItem>().single().isStreaming)
        assertEquals(10L, reducer.state.usage.inputTokens)
        assertEquals(8L, reducer.state.usage.cacheReadTokens)
        assertEquals(0.25, reducer.state.usage.cost)
        assertEquals(SessionStatus.RUNNING, reducer.state.status)
        reducer.accept("""{"type":"turn_end"}""")
        reducer.accept("""{"type":"agent_end","isTerminal":false,"messages":[]}""")
        assertEquals(SessionStatus.RUNNING, reducer.state.status)
        reducer.accept("""{"type":"agent_end","isTerminal":true,"messages":[]}""")
        assertEquals(SessionStatus.DONE, reducer.state.status)
    }

    @Test fun ordinaryFallbackPreservesSeparateTextBlocks() {
        val reducer = reducer()
        reducer.accept("""{"type":"thinking_delta","delta":"思考"}""")
        reducer.accept("""{"type":"thinking_end","content":"思考完成"}""")
        reducer.accept("""{"type":"text_delta","delta":"答案"}""")
        reducer.accept("""{"type":"text_end","content":"答案"}""")
        assertEquals("思考完成", reducer.state.feed.filterIsInstance<ThinkingNodeItem>().single().text)
        assertEquals("答案", reducer.state.feed.filterIsInstance<AssistantMessageItem>().single().markdownText)
    }

    @Test fun nestedToolArgumentsAndExecutionReuseOneNode() {
        val reducer = reducer()
        reducer.updateEvent("""{"type":"toolcall_start","contentIndex":0}""")
        reducer.updateEvent("""{"type":"toolcall_delta","contentIndex":0,"delta":"{\"command\":"}""")
        reducer.updateEvent("""{"type":"toolcall_delta","contentIndex":0,"delta":"\"pwd\"}"}""")
        reducer.updateEvent("""{"type":"toolcall_end","contentIndex":0,"toolCall":{"type":"toolCall","id":"call-1","name":"bash","arguments":{"command":"pwd"}}}""")
        reducer.startTool("call-1", "bash", """{"command":"pwd"}""")
        reducer.endTool("call-1", "bash", """{"content":[{"type":"text","text":"/workspace"}]}""")
        val node = reducer.state.feed.filterIsInstance<ToolNodeItem>().single()
        assertEquals(NodeStatus.OK, node.state)
        assertEquals("pwd", (node.detailContent as ToolDetail.CommandAndOutput).fullCmd)
        assertEquals("/workspace", node.rawCopyText)
    }

    @Test fun actualTodoPhasesCreatePlanAndCloseGroupsOnActiveChange() {
        val reducer = reducer()
        reducer.startTool("todo-1", "todo", """{"op":"init","items":["Read","Edit"]}""")
        reducer.endTool("todo-1", "todo", """{"content":[{"type":"text","text":"Updated"}],"details":{"op":"init","phases":[{"name":"Work","tasks":[{"content":"Read","status":"in_progress"},{"content":"Edit","status":"pending"}]}]}}""")
        reducer.startTool("read-1", "read", """{"path":"src/Main.kt"}""")
        reducer.endTool("read-1", "read", """{"content":[{"type":"text","text":"source"}]}""")
        reducer.endTool("todo-2", "todo", """{"content":[],"details":{"op":"done","phases":[{"name":"Work","tasks":[{"content":"Read","status":"completed"},{"content":"Edit","status":"in_progress"}]}]}}""")
        reducer.startTool("edit-1", "edit", """{"path":"src/Main.kt"}""")
        val plan = reducer.state.feed.filterIsInstance<PlanNodeItem>().single()
        assertEquals(listOf(StepState.COMPLETED, StepState.IN_PROGRESS), plan.steps.map { it.state })
        val groups = reducer.state.feed.filterIsInstance<StepGroupNodeItem>()
        assertEquals(2, groups.size)
        assertEquals(NodeStatus.OK, groups[0].state)
        assertFalse(groups[0].isExpanded)
        assertEquals(GroupStats(readCount = 1, toolCount = 1), groups[0].stats)
        assertEquals(GroupStats(editCount = 1, toolCount = 1), groups[1].stats)
        assertEquals("edit_document", (groups[1].children.single() as ToolNodeItem).icon)
        reducer.accept("""{"type":"todo_update","todos":[{"content":"Read","status":"completed"},{"content":"Edit","status":"completed"}]}""")
        reducer.startTool("flat", "bash", """{"command":"pwd"}""")
        assertTrue(reducer.state.feed.last() is ToolNodeItem)
        assertTrue(reducer.state.feed.filterIsInstance<StepGroupNodeItem>().all { it.state == NodeStatus.OK && !it.isExpanded })
    }

    @Test fun replayedTodoResultsCannotReopenStepsOrMisgroupLaterTools() {
        val reducer = reducer()
        val initial = """{"phases":[{"name":"Work","tasks":[{"content":"Read","status":"in_progress"},{"content":"Edit","status":"pending"}]}]}"""
        val editing = """{"phases":[{"name":"Work","tasks":[{"content":"Read","status":"completed"},{"content":"Edit","status":"in_progress"}]}]}"""
        val complete = """{"phases":[{"name":"Work","tasks":[{"content":"Read","status":"completed"},{"content":"Edit","status":"completed"}]}]}"""
        fun message(id: String, details: String) = """{"role":"toolResult","toolCallId":"$id","toolName":"todo","content":[],"details":$details}"""
        val initialMessage = message("todo-init", initial)
        val editingMessage = message("todo-next", editing)
        val completeMessage = message("todo-done", complete)
        reducer.endTool("todo-init", "todo", initialMessage)
        reducer.startTool("read", "read")
        reducer.endTool("read", "read", """{"content":[]}""")
        reducer.endTool("todo-next", "todo", editingMessage)
        reducer.accept("""{"type":"message_end","message":$initialMessage}""")
        assertEquals(listOf(StepState.COMPLETED, StepState.IN_PROGRESS),
            reducer.state.feed.filterIsInstance<PlanNodeItem>().single().steps.map { it.state })
        reducer.startTool("edit", "edit")
        reducer.endTool("edit", "edit", """{"content":[]}""")
        reducer.endTool("todo-done", "todo", completeMessage)
        reducer.accept("""{"type":"agent_end","isTerminal":true,"messages":[$initialMessage,$editingMessage,$completeMessage]}""")
        val groups = reducer.state.feed.filterIsInstance<StepGroupNodeItem>()
        assertEquals(listOf("Read", "Edit"), groups.map { it.title })
        assertEquals(listOf("description", "edit_document"), groups.map { (it.children.single() as ToolNodeItem).icon })
        assertTrue(groups.all { it.state == NodeStatus.OK && !it.isExpanded })
        assertTrue(reducer.state.feed.filterIsInstance<PlanNodeItem>().single().steps.all { it.state == StepState.COMPLETED })
    }

    @Test fun toolsRemainFlatWithoutTodoAndAccumulatedOutputReplacesRatherThanAppends() {
        val reducer = reducer()
        reducer.startTool("shell", "bash", """{"command":"build"}""")
        reducer.accept("""{"type":"tool_execution_update","toolCallId":"shell","toolName":"bash","partialResult":{"content":[{"type":"text","text":"first"}]}}""")
        reducer.accept("""{"type":"tool_execution_update","toolCallId":"shell","toolName":"bash","partialResult":{"content":[{"type":"text","text":"first\nsecond"}]}}""")
        reducer.endTool("shell", "bash", """{"content":[{"type":"text","text":"first\nsecond\nfailed"}],"details":{"durationMs":2500}}""", true)
        val node = reducer.state.feed.filterIsInstance<ToolNodeItem>().single()
        assertEquals(NodeStatus.ERROR, node.state)
        assertEquals(2, node.elapsedSec)
        assertEquals("first\nsecond\nfailed", node.rawCopyText)
        assertEquals(3, (node.detailContent as ToolDetail.CommandAndOutput).outputLines.size)
        assertTrue((node.detailContent as ToolDetail.CommandAndOutput).outputLines.all { it.isError })
        assertTrue(reducer.state.feed.none { it is StepGroupNodeItem })
    }

    @Test fun diffTotalsAreIdempotentAcrossToolResultMessageAndExecutionEnd() {
        val reducer = reducer()
        reducer.startTool("edit", "edit", """{"path":"src/A.kt"}""")
        val result = """{"content":[{"type":"text","text":"Edited"}],"details":{"path":"src/A.kt","diff":"--- a/src/A.kt\n+++ b/src/A.kt\n@@ -1 +1,2 @@\n-old\n+new\n+extra"}}"""
        reducer.endTool("edit", "edit", result)
        reducer.endTool("edit", "edit", result)
        assertEquals(2, reducer.state.addedLines)
        assertEquals(1, reducer.state.removedLines)
        val node = reducer.state.feed.filterIsInstance<ToolNodeItem>().single()
        assertEquals(2, (node.previewContent as ToolPreview.MidPath).plusLines)
        assertEquals(1, (node.previewContent as ToolPreview.MidPath).minusLines)
        assertEquals(2, (node.detailContent as ToolDetail.DiffBlock).lines.count { it.kind == DiffLineKind.ADDED })
    }

    @Test fun diffSourceTextSeparatesExactlyOneMarkerAndPreservesMetadataAndLiteralPrefixes() {
        val reducer = reducer()
        reducer.startTool("edit", "edit", """{"path":"src/A.kt"}""")
        val diff = "--- a/src/A.kt\n+++ b/src/A.kt\n@@ -1,3 +1,3 @@\n-old\n+new\n context\n++counter\n--counter\nplain metadata"
        reducer.endTool("edit", "edit", buildJsonObject {
            put("content", JsonArray(emptyList()))
            put("details", buildJsonObject { put("diff", diff) })
        }.toString())
        val node = reducer.state.feed.filterIsInstance<ToolNodeItem>().single()
        assertEquals(listOf(
            DiffLine("--- a/src/A.kt"),
            DiffLine("+++ b/src/A.kt"),
            DiffLine("@@ -1,3 +1,3 @@"),
            DiffLine("old", DiffLineKind.REMOVED),
            DiffLine("new", DiffLineKind.ADDED),
            DiffLine("context"),
            DiffLine("+counter", DiffLineKind.ADDED),
            DiffLine("-counter", DiffLineKind.REMOVED),
            DiffLine("plain metadata"),
        ), (node.detailContent as ToolDetail.DiffBlock).lines)
        assertEquals(diff, node.codeText)
        assertEquals(diff, node.fullSheetText)
        assertEquals(diff, node.rawCopyText)
    }

    @Test fun usageIsCumulativeButStatsAndContextAreAuthoritative() {
        val reducer = reducer()
        reducer.accept("""{"type":"message_end","messageId":"a","message":{"role":"assistant","content":[],"usage":{"input":10,"output":2,"cacheRead":3,"cacheWrite":1,"cost":{"total":0.1}}}}""")
        reducer.accept("""{"type":"message_end","messageId":"b","message":{"role":"assistant","content":[],"usage":{"input":20,"output":4,"cacheRead":5,"cacheWrite":2,"cost":{"total":0.2}}}}""")
        assertEquals(30L, reducer.state.usage.inputTokens)
        assertEquals(6L, reducer.state.usage.outputTokens)
        assertEquals(8L, reducer.state.usage.cacheReadTokens)
        reducer.accept("""{"type":"response","command":"get_session_stats","success":true,"data":{"tokens":{"input":100,"output":20,"cacheRead":30,"cacheWrite":10},"cost":1.5,"contextUsage":{"tokens":60,"contextWindow":1000}}}""")
        assertEquals(100L, reducer.state.usage.inputTokens)
        assertEquals(60L, reducer.state.usage.contextTokens)
        assertEquals(1000L, reducer.state.usage.contextWindow)
        assertEquals(1.5, reducer.state.usage.cost)
        reducer.accept("""{"type":"message_end","messageId":"b","message":{"role":"assistant","content":[],"usage":{"input":20,"output":4,"cacheRead":5,"cacheWrite":2,"cost":{"total":0.2}}}}""")
        assertEquals(100L, reducer.state.usage.inputTokens)
        assertEquals(1.5, reducer.state.usage.cost)
    }

    @Test fun nativeAskGroupsQuestionsAndCancellationResolvesTheWholeRequest() {
        val reducer = reducer()
        reducer.accept("""{"type":"extension_ui_request","id":"approval","method":"select","title":"Allow tool: bash\ncommand: rm file","options":["Approve","Approve bash for this session","Deny"]}""")
        assertEquals("approval", reducer.state.feed.filterIsInstance<ApprovalCardItem>().single().id)
        assertEquals(SessionStatus.WAITING, reducer.state.status)
        reducer.accept("""{"type":"response","command":"get_state","success":true,"data":{"isStreaming":true}}""")
        assertEquals(SessionStatus.WAITING, reducer.state.status)
        reducer.accept("""{"type":"extension_ui_request","id":"questions","method":"ask","questions":[{"id":"language","header":"语言","question":"使用什么语言？","recommended":0,"options":[{"label":"Kotlin","description":"JVM","preview":"**预览**"}]},{"id":"target","question":"目标平台？","multi":true,"options":[{"label":"Android"},{"label":"Linux"}]}]}""")
        val card = reducer.state.feed.filterIsInstance<AskCardItem>().single()
        assertEquals("questions", card.id)
        assertEquals(listOf("language", "target"), card.questions.map { it.id })
        assertEquals(AskOption("Kotlin", "JVM", "**预览**"), card.questions[0].options.single())
        assertEquals(0, card.questions[0].recommended)
        assertTrue(card.questions[1].multi)
        reducer.accept("""{"type":"extension_ui_request","id":"cancel","method":"cancel","targetId":"questions"}""")
        assertTrue(reducer.state.feed.filterIsInstance<AskCardItem>().single().cancelled)
        assertTrue(reducer.state.feed.filterIsInstance<AskCardItem>().single().isResolved)
    }

    @Test fun nativeInputsSelectsAndConfirmAreInteractive() {
        val reducer = reducer()
        reducer.accept("""{"type":"extension_ui_request","id":"select","method":"select","title":"Pick","options":["A","B"]}""")
        reducer.accept("""{"type":"extension_ui_request","id":"input","method":"input","title":"Name","placeholder":"Type name"}""")
        reducer.accept("""{"type":"extension_ui_request","id":"editor","method":"editor","title":"Edit","prefill":"existing"}""")
        reducer.accept("""{"type":"extension_ui_request","id":"confirm","method":"confirm","title":"Continue?","message":"Confirm change"}""")
        assertEquals(listOf("select", "input", "editor"), reducer.state.feed.filterIsInstance<AskCardItem>().map { it.id })
        assertEquals("confirm", reducer.state.feed.filterIsInstance<ApprovalCardItem>().single().id)
        assertEquals("existing", reducer.state.feed.filterIsInstance<AskCardItem>().last().questions.single().question)
    }

    @Test fun nativeCatalogUpdatesReplaceRemovedCommandsAndRestartClearsOldWorkspaceMetadata() {
        val reducer = reducer()
        reducer.accept("""{"type":"response","command":"get_available_commands","success":true,"data":{"commands":[{"name":"compact","source":"builtin"},{"name":"deploy","source":"extension","aliases":["ship"]}]}}""")
        assertEquals(listOf("compact", "deploy"), reducer.state.slashCommands.map { it.name })
        reducer.accept("""{"type":"available_commands_update","commands":[{"name":"ship","source":"custom"}]}""")
        assertEquals(listOf("ship"), reducer.state.slashCommands.map { it.name })
        reducer.accept("""{"type":"ready"}""")
        assertTrue(reducer.state.slashCommands.isEmpty())
        assertEquals(SessionStatus.RUNNING, reducer.state.status)
    }

    @Test fun readyGenerationsPreventProcessRestartMessageAndToolCollisions() {
        val reducer = reducer()
        fun emit() {
            reducer.accept("""{"type":"message_end","messageId":"msg-1","message":{"role":"assistant","content":[{"type":"text","text":"answer"}]}}""")
            reducer.startTool("call-1", "read", """{"path":"file"}""")
        }
        emit()
        val first = reducer.state.feed.map { it.id }.toSet()
        reducer.accept("""{"type":"ready"}""")
        emit()
        val ids = reducer.state.feed.map { it.id }
        assertEquals(4, ids.size)
        assertEquals(4, ids.toSet().size)
        assertTrue(ids.takeLast(2).none { it in first })
    }

    @Test fun wireUserEchoPreservesPoolAddedMessageAndAttachments() {
        val attachment = AttachmentFile("file", "image.png", "/workspace/image.png")
        val reducer = OmpEventReducer(ChatSessionUiState("session", feed = listOf(UserMessageItem("local", "hello", listOf(attachment)))))
        reducer.accept("""{"type":"message_start","messageId":"msg-1","message":{"role":"user","content":[{"type":"text","text":"hello"}],"timestamp":123}}""")
        reducer.accept("""{"type":"message_end","messageId":"msg-1","message":{"role":"user","content":[{"type":"text","text":"hello"}],"timestamp":123}}""")
        val user = reducer.state.feed.filterIsInstance<UserMessageItem>().single()
        assertEquals("local", user.id)
        assertEquals(listOf(attachment), user.attachments)
    }

    @Test fun queueAndStateSnapshotsReconcileExistingQueueIdsAndModel() {
        val reducer = reducer()
        reducer.update { it.copy(queuedPrompts = listOf(QueuedPrompt("local-queue", "next", queue = PromptQueue.STEERING))) }
        reducer.accept("""{"type":"queue_update","steering":["next"],"followUp":["later"],"liveSteered":0}""")
        assertEquals("local-queue", reducer.state.queuedPrompts.first().id)
        reducer.accept("""{"type":"response","command":"get_state","success":true,"data":{"model":{"provider":"anthropic","id":"claude","contextWindow":200000},"thinkingLevel":"high","isStreaming":false,"queuedMessages":{"steering":[],"followUp":["later"]},"todoPhases":[],"contextUsage":{"tokens":500,"contextWindow":200000}}}""")
        assertEquals("anthropic", reducer.state.providerId)
        assertEquals("claude", reducer.state.modelId)
        assertEquals(ThinkingLevel.HIGH, reducer.state.thinkingLevel)
        assertEquals(500L, reducer.state.usage.contextTokens)
        assertEquals(listOf("later"), reducer.state.queuedPrompts.map { it.text })
        assertEquals(SessionStatus.RUNNING, reducer.state.status)
    }

    @Test fun retriesCompactionAndAssistantErrorsDoNotPrematurelyFinalize() {
        val reducer = reducer()
        reducer.accept("""{"type":"auto_retry_start","attempt":1,"maxAttempts":3,"errorMessage":"rate limited"}""")
        assertEquals(SessionStatus.RUNNING, reducer.state.status)
        reducer.accept("""{"type":"auto_retry_end","success":true,"attempt":1}""")
        assertNull(reducer.state.error)
        reducer.accept("""{"type":"auto_compaction_start","reason":"threshold","action":"snapcompact"}""")
        reducer.accept("""{"type":"auto_compaction_end","aborted":false,"willRetry":true,"result":{"tokensAfter":50}}""")
        assertEquals(50L, reducer.state.usage.contextTokens)
        assertEquals(SessionStatus.RUNNING, reducer.state.status)
        reducer.accept("""{"type":"message_end","messageId":"failed","message":{"role":"assistant","content":[],"stopReason":"error","errorMessage":"provider failed"}}""")
        assertEquals(SessionStatus.RUNNING, reducer.state.status)
        reducer.accept("""{"type":"agent_end","isTerminal":true,"messages":[]}""")
        assertEquals(SessionStatus.ERROR, reducer.state.status)
        assertEquals("provider failed", reducer.state.error)
    }

    @Test fun compactionWaitsForDurableEntryMappingAndKeepsRetainedTailVisible() {
        val reducer = reducer()
        val old = """{"role":"assistant","timestamp":1,"content":[{"type":"text","text":"old"}]}"""
        val kept = """{"role":"assistant","timestamp":2,"content":[{"type":"text","text":"kept"}]}"""
        reducer.accept("""{"type":"message_end","messageId":"a","message":$old}""")
        reducer.accept("""{"type":"message_end","messageId":"b","message":$kept}""")
        val oldId = reducer.state.feed.first().id
        reducer.accept("""{"type":"auto_compaction_end","aborted":false,"willRetry":false,"result":{"firstKeptEntryId":"entry-b","tokensAfter":10}}""")
        assertNull(reducer.state.compactedThroughId)
        reducer.accept("""{"type":"response","command":"get_entries","success":true,"data":{"leafId":"entry-c","entries":[{"id":"entry-a","parentId":null,"type":"message","message":$old},{"id":"entry-b","parentId":"entry-a","type":"message","message":$kept},{"id":"entry-c","parentId":"entry-b","type":"compaction","firstKeptEntryId":"entry-b"}]}}""")
        assertEquals(oldId, reducer.state.compactedThroughId)
        assertEquals("kept", reducer.state.feed.filterIsInstance<AssistantMessageItem>().last().markdownText)
    }

    @Test fun agentEndSnapshotsReconcileWithoutDuplicateMessages() {
        val reducer = reducer()
        val message = """{"role":"assistant","timestamp":1,"content":[{"type":"text","text":"answer"}],"usage":{"input":10,"output":2,"cost":{"total":0.1}}}"""
        reducer.accept("""{"type":"message_end","messageId":"a","message":$message}""")
        reducer.accept("""{"type":"agent_end","messages":[$message],"isTerminal":true}""")
        assertEquals(1, reducer.state.feed.filterIsInstance<AssistantMessageItem>().size)
        assertEquals(10L, reducer.state.usage.inputTokens)
        assertEquals(0.1, reducer.state.usage.cost)
    }

    @Test fun withdrawnQueueRestoresDuplicateOccurrencesWithOriginalAttachments() {
        val reducer = reducer()
        val attachment = AttachmentFile("attachment", "file", "/workspace/file")
        reducer.update { it.copy(queuedPrompts = listOf(QueuedPrompt("a", "next", listOf(attachment), PromptQueue.STEERING), QueuedPrompt("b", "next"))) }
        reducer.accept("""{"type":"queue_update","steering":[],"followUp":[]}""")
        reducer.accept("""{"type":"response","command":"abort_and_restore_queue","success":true,"data":{"steering":[{"text":"next"}],"followUp":[{"text":"next"}]}}""")
        assertEquals(listOf("a", "b"), reducer.state.queuedPrompts.map { it.id })
        assertEquals(listOf(attachment), reducer.state.queuedPrompts.first().attachments)
    }

    @Test fun commandOutputIsShownAsRealFeedContent() {
        val reducer = reducer()
        reducer.accept("""{"type":"command_output","text":"Session exported to /workspace/session.html"}""")
        assertEquals("Session exported to /workspace/session.html", reducer.state.feed.filterIsInstance<AssistantMessageItem>().single().markdownText)
    }

    @Test fun consumedQueuedMessagesKeepAttachmentsForRepeatedTextOccurrences() {
        val reducer = reducer()
        val first = AttachmentFile("first", "a.png", "/workspace/a.png")
        val second = AttachmentFile("second", "b.png", "/workspace/b.png")
        reducer.update { it.copy(queuedPrompts = listOf(QueuedPrompt("a", "next", listOf(first)), QueuedPrompt("b", "next", listOf(second)))) }
        reducer.accept("""{"type":"queue_update","steering":[],"followUp":[]}""")
        reducer.accept("""{"type":"message_start","messageId":"user-a","message":{"role":"user","content":"next","timestamp":1}}""")
        reducer.accept("""{"type":"message_end","messageId":"user-a","message":{"role":"user","content":"next","timestamp":1}}""")
        reducer.accept("""{"type":"message_start","messageId":"user-b","message":{"role":"user","content":"next","timestamp":2}}""")
        reducer.accept("""{"type":"message_end","messageId":"user-b","message":{"role":"user","content":"next","timestamp":2}}""")
        assertEquals(listOf(listOf(first), listOf(second)), reducer.state.feed.filterIsInstance<UserMessageItem>().map { it.attachments })
    }

    @Test fun settledSessionChangesOnlyStatusAndNeverFinalizesPendingFeed() {
        val reducer = reducer()
        reducer.startTool("unfinished", "bash", """{"command":"job"}""")
        reducer.accept("""{"type":"agent_end","isTerminal":false,"awaitingAsyncWork":true,"messages":[]}""")
        reducer.accept("""{"type":"session_settled"}""")
        assertEquals(SessionStatus.DONE, reducer.state.status)
        assertEquals(NodeStatus.RUNNING, reducer.state.feed.filterIsInstance<ToolNodeItem>().single().state)
        reducer.accept("""{"type":"agent_end","isTerminal":true,"messages":[]}""")
        assertEquals(NodeStatus.OK, reducer.state.feed.filterIsInstance<ToolNodeItem>().single().state)
    }

    @Test fun settledEventDoesNotDismissAnUnresolvedDialog() {
        val reducer = reducer()
        reducer.accept("""{"type":"extension_ui_request","id":"pending","method":"input","title":"Input"}""")
        reducer.accept("""{"type":"session_settled"}""")
        assertEquals(SessionStatus.WAITING, reducer.state.status)
    }

    @Test fun localSlashPromptResultSettlesStatusWithoutFinalizingFeed() {
        val reducer = reducer()
        reducer.startTool("pending-tool", "bash", """{"command":"prior"}""")
        reducer.accept("""{"type":"command_output","text":"Exported session"}""")
        reducer.accept("""{"type":"prompt_result","agentInvoked":false,"status":"completed","sessionSettled":true}""")
        assertEquals(SessionStatus.DONE, reducer.state.status)
        assertEquals(NodeStatus.RUNNING, reducer.state.feed.filterIsInstance<ToolNodeItem>().single().state)
        assertEquals("Exported session", reducer.state.feed.filterIsInstance<AssistantMessageItem>().single().markdownText)
    }

    @Test fun rejectedPromptResultReportsAdmissionFailureWithoutAgentEvents() {
        val reducer = reducer()
        reducer.accept("""{"type":"prompt_result","agentInvoked":false,"status":"error","sessionSettled":true,"error":{"message":"Unknown slash command","retryable":false}}""")
        assertEquals(SessionStatus.ERROR, reducer.state.status)
        assertEquals("Unknown slash command", reducer.state.error)
    }

    @Test fun latePromptFailureWithoutDetailPreservesTheAdmittedModelError() {
        val reducer = reducer()
        reducer.accept("""{"type":"message_end","messageId":"failed","message":{"role":"assistant","content":[],"stopReason":"error","errorMessage":"Provider unavailable"}}""")
        reducer.accept("""{"type":"agent_end","isTerminal":true,"messages":[]}""")
        reducer.accept("""{"type":"prompt_result","agentInvoked":true,"status":"error","sessionSettled":true}""")
        assertEquals(SessionStatus.ERROR, reducer.state.status)
        assertEquals("Provider unavailable", reducer.state.error)
    }

    @Test fun localPromptResultCannotSettleUnrelatedLiveBackgroundWork() {
        val reducer = reducer()
        reducer.accept("""{"type":"prompt_result","agentInvoked":false,"status":"completed","sessionSettled":false}""")
        assertEquals(SessionStatus.RUNNING, reducer.state.status)
        reducer.accept("""{"type":"session_settled"}""")
        assertEquals(SessionStatus.DONE, reducer.state.status)
    }

    @Test fun authoritativeSnapshotKeepsBackgroundWorkAndQuestionsPending() {
        val reducer = reducer()
        reducer.accept("""{"type":"response","command":"get_state","success":true,"data":{"isStreaming":false,"isSettled":false}}""")
        assertEquals(SessionStatus.RUNNING, reducer.state.status)
        reducer.accept("""{"type":"extension_ui_request","id":"pending","method":"input","title":"Input"}""")
        reducer.accept("""{"type":"response","command":"get_state","success":true,"data":{"isStreaming":false,"isSettled":true}}""")
        assertEquals(SessionStatus.WAITING, reducer.state.status)
        assertFalse(reducer.state.feed.filterIsInstance<AskCardItem>().single().isResolved)
    }
}
