package dev.wawlink.driver

import dev.wawlink.protocol.*
import kotlinx.serialization.json.*
import kotlin.test.*

class OmpNativeHistoryTest {
    private fun entry(json: String) = DriverJson.parseToJsonElement(json).jsonObject
    private fun items(feed: List<FeedItem>): List<FeedItem> = feed.flatMap { listOf(it) + if (it is StepGroupNodeItem) items(it.children) else emptyList() }

    @Test fun switchingAncestryPreservesAbandonedBranchesForReturnNavigation() {
        val root = entry("""{"id":"root","parentId":null,"type":"message","message":{"role":"user","content":"root","timestamp":1}}""")
        val left = entry("""{"id":"left","parentId":"root","type":"message","message":{"role":"assistant","content":[{"type":"text","text":"left branch"}],"timestamp":2}}""")
        val right = entry("""{"id":"right","parentId":"root","type":"message","message":{"role":"assistant","content":[{"type":"text","text":"right branch"}],"timestamp":3}}""")
        val records = listOf(root, left, right)
        val reducer = OmpEventReducer(ChatSessionUiState("main"))
        reducer.restoreNativeBranch(records, "left")
        assertEquals(listOf("left branch"), items(reducer.state.feed).filterIsInstance<AssistantMessageItem>().map { it.markdownText })
        reducer.restoreNativeBranch(records, "right")
        assertEquals(listOf("right branch"), items(reducer.state.feed).filterIsInstance<AssistantMessageItem>().map { it.markdownText })
        reducer.restoreNativeBranch(records, "left")
        assertEquals(listOf("left branch"), items(reducer.state.feed).filterIsInstance<AssistantMessageItem>().map { it.markdownText })
        assertFalse(reducer.state.isRunning)
    }

    @Test fun exactMessageIdentityDoesNotLocateAnotherIdenticalTextTurn() {
        val reducer = OmpEventReducer(ChatSessionUiState("main"))
        reducer.reduce(entry("""{"type":"message_end","messageId":"first","message":{"role":"user","content":"same","timestamp":1}}"""))
        reducer.reduce(entry("""{"type":"message_end","messageId":"second","message":{"role":"user","content":"same","timestamp":2}}"""))
        val first = entry("""{"id":"entry-first","type":"message","message":{"role":"user","content":"same","timestamp":1}}""")
        val second = entry("""{"id":"entry-second","type":"message","message":{"role":"user","content":"same","timestamp":2}}""")
        val absent = entry("""{"id":"absent","type":"message","message":{"role":"user","content":"same","timestamp":3}}""")
        val mapping = reducer.nativeEntryFeedIds(listOf(first, second, absent))
        assertEquals("omp:0:message:first", mapping["entry-first"])
        assertEquals("omp:0:message:second", mapping["entry-second"])
        assertFalse(mapping.containsKey("absent"))
    }

    @Test fun nativeSnapshotAndLaterLiveMessageUseTheSameIdentity() {
        val reducer = OmpEventReducer(ChatSessionUiState("child"))
        val record = entry("""{"id":"a","parentId":null,"type":"message","messageId":"entry:a","message":{"role":"assistant","content":[{"type":"text","text":"answer"}],"timestamp":1}}""")
        reducer.restoreNativeBranch(listOf(record), "a")
        reducer.reduce(entry("""{"type":"message_end","messageId":"entry:a","message":{"role":"assistant","content":[{"type":"text","text":"answer"}],"timestamp":1}}"""))
        assertEquals(1, items(reducer.state.feed).filterIsInstance<AssistantMessageItem>().size)
        assertEquals("omp:0:message:entry:a:block:0", reducer.nativeEntryFeedIds(listOf(record))["a"])
    }

    @Test fun everyDisplayedBlockLocatesItsNativeMessageWithoutChoosingThoughtAsCanonical() {
        val reducer = OmpEventReducer(ChatSessionUiState("main"))
        val record = entry("""{"id":"multi","parentId":null,"type":"message","messageId":"entry:multi","message":{"role":"assistant","content":[{"type":"thinking","thinking":"thought"},{"type":"text","text":"first"},{"type":"text","text":"second"},{"type":"toolCall","id":"tool-a","name":"bash","arguments":{"command":"pwd"}}],"timestamp":1}}""")
        reducer.restoreNativeBranch(listOf(record), "multi")
        val aliases = reducer.nativeEntryFeedAliases(listOf(record)).getValue("multi")
        assertEquals("omp:0:message:entry:multi:block:1", aliases.first())
        assertTrue("omp:0:message:entry:multi:block:0" in aliases)
        assertTrue("omp:0:message:entry:multi:block:2" in aliases)
        assertTrue("omp:0:tool:tool-a" in aliases)
        assertEquals(4, aliases.size)
        assertEquals(aliases.first(), reducer.nativeEntryFeedIds(listOf(record))["multi"])
    }

    @Test fun acceptedMessageSurvivesExecutionError() {
        val reducer = OmpEventReducer(ChatSessionUiState("child"))
        reducer.addUserMessage(UserMessageItem("admitted", "accepted input", accepted = true))
        reducer.reduce(entry("""{"type":"agent_start"}"""))
        reducer.reduce(entry("""{"type":"message_end","messageId":"failed-model","message":{"role":"assistant","content":[],"stopReason":"error","errorMessage":"provider unavailable"}}"""))
        reducer.reduce(entry("""{"type":"agent_end","isTerminal":true,"messages":[]}"""))
        assertEquals("accepted input", items(reducer.state.feed).filterIsInstance<UserMessageItem>().single().text)
        assertTrue(items(reducer.state.feed).filterIsInstance<UserMessageItem>().single().accepted)
        assertEquals(SessionStatus.ERROR, reducer.state.status)
    }
}
