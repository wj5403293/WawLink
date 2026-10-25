package dev.wawlink.driver

import dev.wawlink.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlin.test.*

class OmpSessionExplorerHistoryTest {
    @Test fun historyConsumerCopiesOnlyVisibleTextAndKeepsExactTargets() = runBlocking {
        val visible = "Visible answer\n> quoted first\n> quoted second\n\n```kotlin\n  val n = 1\n\n```\n[reference](https://example.test/path_(part)?a=1&b=2)."
        val response = buildJsonObject {
            put("sessionId", "native-main"); put("leafId", "assistant")
            put("entries", buildJsonArray {
                add(buildJsonObject {
                    put("id", "user"); put("type", "message"); put("parentId", JsonNull)
                    put("message", buildJsonObject { put("role", "user"); put("content", "expanded prompt with injected context"); put("timestamp", 1) })
                })
                add(buildJsonObject {
                    put("id", "skill"); put("type", "message"); put("parentId", "user")
                    put("message", buildJsonObject { put("role", "custom"); put("content", "expanded private skill instructions"); put("timestamp", 2) })
                })
                add(buildJsonObject {
                    put("id", "assistant"); put("type", "message"); put("parentId", "skill")
                    put("message", buildJsonObject {
                        put("role", "assistant"); put("timestamp", 3)
                        put("content", buildJsonArray {
                            add(buildJsonObject { put("type", "thinking"); put("thinking", "private thought must stay out of copy") })
                            add(buildJsonObject { put("type", "text"); put("text", visible) })
                            add(buildJsonObject { put("type", "image"); put("mimeType", "image/png"); put("data", "aW1hZ2U=") })
                            add(buildJsonObject {
                                put("type", "toolCall"); put("id", "bash-call"); put("name", "bash")
                                put("arguments", buildJsonObject { put("command", "printf '%s' original-command") })
                            })
                        })
                    })
                })
            })
            put("userInputs", buildJsonObject { put("skill", "/skill:review src/Main.kt") })
        }
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val explorer = OmpSessionExplorer("app", "container", "/workspace", owner,
                MutableStateFlow(ChatSessionUiState("app", feed = listOf(AssistantMessageItem("existing", "existing")))),
                request = { _, _ -> response }, synchronize = {},
                identities = { mapOf("assistant" to listOf("answer-block", "thought-block", "tool-block")) },
                originalTexts = { mapOf("user" to "  original user input\nuntouched  ") },
                prepareAttachments = { text, attachments, _ -> Triple(text, attachments, JsonArray(emptyList())) },
                files = { _, _ -> flowOf(emptyList()) })
            explorer.refreshHistory()
            val assistant = explorer.history.value.nodes.single { it.id == "assistant" }
            assertEquals(visible, assistant.text)
            assertEquals(visible, assistant.copyBlocks.first().text)
            assertTrue(assistant.copyBlocks.none { "private thought" in it.text || "aW1hZ2U=" in it.text })
            assertEquals("quoted first\nquoted second", assistant.copyBlocks.single { it.icon == "format_quote" }.text)
            assertEquals("  val n = 1\n\n", assistant.copyBlocks.single { it.icon == "code" }.text)
            assertEquals("https://example.test/path_(part)?a=1&b=2", assistant.copyBlocks.single { it.icon == "link" }.text)
            assertEquals("printf '%s' original-command", assistant.copyBlocks.single { it.icon == "terminal" }.text)
            assertEquals("answer-block", assistant.chatItemId)
            assertEquals(listOf("answer-block", "thought-block", "tool-block"), assistant.chatItemIds)
            fun all(feed: List<FeedItem>): List<FeedItem> = feed.flatMap { listOf(it) + if (it is StepGroupNodeItem) all(it.children) else emptyList() }
            assertTrue(all(assistant.feed).filterIsInstance<ThinkingNodeItem>().any { "private thought" in it.text })
            val rawUser = explorer.history.value.nodes.single { it.id == "user" }
            assertEquals("  original user input\nuntouched  ", rawUser.text)
            assertEquals(rawUser.text, rawUser.copyBlocks.first().text)
            val skill = explorer.history.value.nodes.single { it.id == "skill" }
            assertEquals("/skill:review src/Main.kt", skill.text)
            assertEquals(skill.text, skill.copyBlocks.first().text)
        } finally { owner.cancel() }
    }
}
