package dev.wawlink.driver

import dev.wawlink.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlin.test.*

class OmpSessionExplorerHistoryTest {
    @Test fun selectedDetailsPreserveOriginalTextAndCopyOnlyVisibleContent() = runBlocking {
        val visible = "Visible answer\n> quoted first\n> quoted second\n\n```kotlin\n  val n = 1\n\n```\n[reference](https://example.test/path_(part)?a=1&b=2)."
        val entries = listOf(
            entry("user", "user", JsonPrimitive("expanded prompt with injected context")),
            entry("skill", "custom", JsonPrimitive("expanded private skill instructions")),
            entry("assistant", "assistant", buildJsonArray {
                add(buildJsonObject { put("type", "thinking"); put("thinking", "private thought must stay out of copy") })
                add(buildJsonObject { put("type", "text"); put("text", visible) })
                add(buildJsonObject { put("type", "image"); put("mimeType", "image/png"); put("data", "aW1hZ2U=") })
                add(buildJsonObject {
                    put("type", "toolCall"); put("id", "bash-call"); put("name", "bash")
                    put("arguments", buildJsonObject { put("command", "printf '%s' original-command") })
                })
            }),
        ).associateBy { it["id"]!!.jsonPrimitive.content }
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val explorer = explorer(owner) { op, body ->
                when (op) {
                    "explorer_history" -> page("r1", "assistant", entries.keys.map { node(it) })
                    "explorer_history_detail" -> {
                        val id = body["entryId"]!!.jsonPrimitive.content
                        buildJsonObject {
                            put("sessionId", "native-main"); put("entryId", id); put("version", "v1")
                            if (body["kind"]!!.jsonPrimitive.content == "raw") put("rawRecord", entries.getValue(id).toString())
                            else {
                                put("entry", entries.getValue(id))
                                if (id == "user") put("userText", "  original user input\nuntouched  ")
                                if (id == "skill") put("userText", "/skill:review src/Main.kt")
                            }
                        }
                    }
                    else -> error("Unexpected native operation: $op")
                }
            }
            explorer.refreshHistory()
            val assistant = explorer.loadHistoryDetail("assistant", HistoryDetailKind.COPY)
            assertEquals(visible, assistant.text)
            assertEquals(visible, assistant.copyBlocks.first().text)
            assertTrue(assistant.copyBlocks.none { "private thought" in it.text || "aW1hZ2U=" in it.text })
            assertEquals("quoted first\nquoted second", assistant.copyBlocks.single { it.icon == "format_quote" }.text)
            assertEquals("  val n = 1\n\n", assistant.copyBlocks.single { it.icon == "code" }.text)
            assertEquals("https://example.test/path_(part)?a=1&b=2", assistant.copyBlocks.single { it.icon == "link" }.text)
            assertEquals("printf '%s' original-command", assistant.copyBlocks.single { it.icon == "terminal" }.text)
            fun all(feed: List<FeedItem>): List<FeedItem> = feed.flatMap { listOf(it) + if (it is StepGroupNodeItem) all(it.children) else emptyList() }
            val preview = explorer.loadHistoryDetail("assistant", HistoryDetailKind.PREVIEW)
            assertTrue(all(preview.feed).filterIsInstance<ThinkingNodeItem>().any { "private thought" in it.text })
            assertEquals(entries.getValue("assistant").toString(), explorer.loadHistoryDetail("assistant", HistoryDetailKind.RAW).rawRecord)
            val user = explorer.loadHistoryDetail("user", HistoryDetailKind.COPY)
            assertEquals("  original user input\nuntouched  ", user.text)
            assertEquals(user.text, user.copyBlocks.first().text)
            val skill = explorer.loadHistoryDetail("skill", HistoryDetailKind.COPY)
            assertEquals("/skill:review src/Main.kt", skill.text)
            assertEquals(skill.text, skill.copyBlocks.first().text)
        } finally { owner.cancel() }
    }

    @Test fun historyPublishesOnlyCompleteMatchingSnapshots() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val secondPage = CompletableDeferred<Unit>()
        val releasePage = CompletableDeferred<Unit>()
        var mode = "initial"
        try {
            val explorer = explorer(owner) { _, body ->
                when {
                    mode == "initial" -> page("r0", "old", listOf(node("old")))
                    body["cursor"] == null -> page(if (mode == "paged") "r1" else "r2", "b", listOf(node("a")), cursor = "next")
                    mode == "paged" -> {
                        secondPage.complete(Unit); releasePage.await()
                        page("r1", "b", listOf(node("b", "a")))
                    }
                    else -> page("wrong-revision", "b", listOf(node("b", "a")))
                }
            }
            explorer.refreshHistory()
            mode = "paged"
            val reading = async { explorer.refreshHistory() }
            withTimeout(5000) { secondPage.await() }
            assertEquals(listOf("old"), explorer.history.value.nodes.map { it.id })
            releasePage.complete(Unit); reading.await()
            assertEquals(listOf("a", "b"), explorer.history.value.nodes.map { it.id })
            assertTrue(explorer.history.value.nodes.all { it.onCurrentPath })
            mode = "mismatched"
            assertFailsWith<IllegalStateException> { explorer.refreshHistory() }
            assertEquals("r1", explorer.history.value.revision)
            assertEquals(listOf("a", "b"), explorer.history.value.nodes.map { it.id })
        } finally { releasePage.complete(Unit); owner.cancel() }
    }

    @Test fun versionChangesInvalidateSelectedDetailsAndRejectOldResponses() = runBlocking<Unit> {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var version = "v1"
        var stale = false
        try {
            val explorer = explorer(owner) { op, _ ->
                if (op == "explorer_history") page(version, "a", listOf(node("a", version = version)))
                else buildJsonObject {
                    put("sessionId", "native-main"); put("entryId", "a"); put("version", if (stale) "v1" else version)
                    put("entry", entry("a", "assistant", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", version) }) }))
                }
            }
            explorer.refreshHistory()
            assertEquals("v1", explorer.loadHistoryDetail("a", HistoryDetailKind.COPY).text)
            version = "v2"; explorer.refreshHistory()
            assertEquals("v2", explorer.loadHistoryDetail("a", HistoryDetailKind.COPY).text)
            stale = true
            assertFailsWith<IllegalStateException> { explorer.loadHistoryDetail("a", HistoryDetailKind.PREVIEW) }
        } finally { owner.cancel() }
    }

    @Test fun hubRosterRemainsAvailableWithoutHistoryOrTranscriptDetails() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val explorer = explorer(owner) { op, _ ->
                check(op == "explorer_hub") { "History and transcript details are unavailable" }
                buildJsonObject {
                    put("mainSessionId", "native-main"); put("connected", true); put("discovering", true)
                    put("agents", buildJsonArray { add(buildJsonObject {
                        put("instanceKey", "child-instance"); put("id", "child"); put("name", "运行实例"); put("lifecycle", "running")
                    }) })
                }
            }
            explorer.refreshHub()
            assertTrue(explorer.hub.value.connected)
            assertTrue(explorer.hub.value.discovering)
            assertEquals("child-instance", explorer.hub.value.agents.single().instanceKey)
            assertEquals(RuntimeAgentLifecycle.RUNNING, explorer.hub.value.agents.single().lifecycle)
            assertNull(explorer.hub.value.error)
        } finally { owner.cancel() }
    }


    @Test fun olderHubResponseCannotPublishOverNewerQuery() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val newStarted = CompletableDeferred<Unit>()
        val releaseNew = CompletableDeferred<Unit>()
        try {
            val explorer = explorer(owner) { _, body ->
                val search = body["activityQuery"]!!.jsonObject["search"]!!.jsonPrimitive.content
                if (search == "old") { oldStarted.complete(Unit); releaseOld.await() }
                else { newStarted.complete(Unit); releaseNew.await() }
                buildJsonObject {
                    put("mainSessionId", "native-main"); put("connected", true)
                    put("activity", buildJsonArray { add(buildJsonObject {
                        put("id", search); put("agentId", "child"); put("instanceKey", "child-instance"); put("kind", "tool"); put("text", search)
                    }) })
                }
            }
            val older = async(start = CoroutineStart.UNDISPATCHED) { explorer.refreshHub(20, AgentActivityQuery(search = "old")) }
            withTimeout(5000) { oldStarted.await() }
            val newer = async(start = CoroutineStart.UNDISPATCHED) { explorer.refreshHub(20, AgentActivityQuery(search = "new")) }
            assertEquals("new", explorer.hub.value.activityQuery.search)
            releaseOld.complete(Unit)
            withTimeout(5000) { newStarted.await() }
            assertTrue(explorer.hub.value.activity.isEmpty())
            assertTrue(explorer.hub.value.loading)
            releaseNew.complete(Unit)
            older.await(); newer.await()
            assertEquals(listOf("new"), explorer.hub.value.activity.map { it.id })
        } finally { releaseOld.complete(Unit); releaseNew.complete(Unit); owner.cancel() }
    }

    @Test fun childCandidatesAreNativeAndNeverReplaceBoundModelOrFallBackToMain() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var offerCandidates = true
        try {
            val explorer = explorer(owner) { op, _ ->
                when (op) {
                    "explorer_hub" -> buildJsonObject {
                        put("mainSessionId", "native-main"); put("connected", true)
                        put("agents", buildJsonArray { add(buildJsonObject {
                            put("instanceKey", "child-instance"); put("id", "child"); put("name", "child"); put("lifecycle", "idle")
                        }) })
                    }
                    "explorer_agent_snapshot" -> buildJsonObject {
                        put("providerId", "bound-provider"); put("modelId", "bound-not-in-candidates"); put("thinking", "high"); put("cursor", "native:1")
                        put("availableModels", buildJsonArray { if (offerCandidates) add(buildJsonObject {
                            put("providerId", "supported-provider"); put("modelId", "supported-model"); put("name", "Supported model")
                        }) })
                        put("availableThinkingLevels", buildJsonArray { if (offerCandidates) { add("off"); add("high"); add("unknown-native-selector") } })
                    }
                    else -> error("Candidate loading must not mutate a child: $op")
                }
            }
            explorer.refreshHub()
            val child = explorer.openAgent("child-instance")
            assertEquals(listOf(SessionModel("supported-provider", "supported-model", "Supported model")), child.state.value.availableModels)
            assertEquals(listOf(ThinkingLevel.NONE, ThinkingLevel.HIGH), child.state.value.availableThinkingLevels)
            assertEquals("bound-provider", child.state.value.providerId)
            assertEquals("bound-not-in-candidates", child.state.value.modelId)
            offerCandidates = false
            explorer.openAgent("child-instance")
            assertEquals(emptyList(), child.state.value.availableModels)
            assertEquals(emptyList(), child.state.value.availableThinkingLevels)
            assertEquals("bound-not-in-candidates", child.state.value.modelId)
        } finally { owner.cancel() }
    }

    @Test fun legacyAttachmentOnlyHistoryRestoresPreviewCopyAndNavigationWithoutCache() = runBlocking {
        val file = AttachmentFile("original", "图像.svg", "/workspace/图像.svg", "image/svg+xml", source = AttachmentSource.CONTAINER)
        val wire = attachmentWireMessage("", listOf(file))
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val explorer = explorer(owner) { op, _ ->
                when (op) {
                    "explorer_history" -> page("r1", "attachment", listOf(node("attachment")))
                    "explorer_history_detail" -> buildJsonObject {
                        put("sessionId", "native-main"); put("entryId", "attachment"); put("version", "v1")
                        put("entry", entry("attachment", "user", JsonPrimitive(wire)))
                    }
                    "explorer_navigate" -> buildJsonObject { put("prompt", buildJsonObject { put("text", wire) }) }
                    "explorer_history_branch" -> buildJsonObject {}
                    else -> error("Unexpected native operation: $op")
                }
            }
            explorer.refreshHistory()
            val preview = explorer.loadHistoryDetail("attachment", HistoryDetailKind.PREVIEW)
            val user = preview.feed.filterIsInstance<UserMessageItem>().single()
            assertEquals("", preview.text)
            assertEquals("", user.text)
            assertEquals(file.path, user.attachments.single().path)
            assertEquals(AttachmentSource.CONTAINER, user.attachments.single().source)
            assertEquals("image/svg+xml", user.attachments.single().mimeType)
            val copy = explorer.loadHistoryDetail("attachment", HistoryDetailKind.COPY)
            assertEquals("", copy.text)
            assertTrue(copy.copyBlocks.isEmpty())
            val restored = explorer.navigate("attachment", HistorySummary())!!
            assertEquals("", restored.text)
            assertEquals(user.attachments, restored.attachments)
        } finally { owner.cancel() }
    }

    @Test fun cleanBridgeTextAndMetadataDoNotReparseAnOriginalMarkerExample() = runBlocking {
        val example = "  原始例子\n\n附件（容器内真实文件）：\n- example.txt: /examples/example.txt"
        val file = AttachmentFile("stable-workspace-id", "real.kt", "/workspace/real.kt", "text/plain", 17, AttachmentSource.WORKSPACE)
        val wire = attachmentWireMessage(example, listOf(file))
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val explorer = explorer(owner) { op, _ ->
                when (op) {
                    "explorer_history" -> page("r1", "user", listOf(node("user")))
                    "explorer_history_detail" -> buildJsonObject {
                        put("sessionId", "native-main"); put("entryId", "user"); put("version", "v1")
                        put("entry", entry("user", "user", JsonPrimitive(wire))); put("userText", example)
                        put("attachments", buildJsonArray { add(attachmentJson(file)) })
                    }
                    "explorer_navigate" -> buildJsonObject { put("prompt", buildJsonObject {
                        put("text", example); put("wireText", wire); put("attachments", buildJsonArray { add(attachmentJson(file)) })
                    }) }
                    "explorer_history_branch" -> buildJsonObject {}
                    else -> error("Unexpected native operation: $op")
                }
            }
            explorer.refreshHistory()
            val copy = explorer.loadHistoryDetail("user", HistoryDetailKind.COPY)
            assertEquals(example, copy.text)
            assertEquals(example, copy.copyBlocks.first().text)
            val user = explorer.loadHistoryDetail("user", HistoryDetailKind.PREVIEW).feed.filterIsInstance<UserMessageItem>().single()
            assertEquals(example, user.text)
            assertEquals(listOf(file), user.attachments)
            assertEquals(RestoredSessionPrompt(example, listOf(file)), explorer.navigate("user", HistorySummary()))
        } finally { owner.cancel() }
    }

    @Test fun childAdmissionAndNativeResetKeepRawTextAndAttachmentIdentity() = runBlocking {
        val raw = "  \n保留原始空白\n  "
        val file = AttachmentFile("stable", "note.txt", "/workspace/note.txt", "text/plain", 7, AttachmentSource.WORKSPACE)
        val wire = attachmentWireMessage(raw, listOf(file))
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val admitted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var sent = false
        try {
            val explorer = explorer(owner) { op, body ->
                when (op) {
                    "explorer_hub" -> childHub()
                    "explorer_agent_prompt" -> {
                        assertEquals(wire, body["text"]!!.jsonPrimitive.content)
                        admitted.complete(Unit); release.await(); sent = true
                        buildJsonObject { put("accepted", true); put("entryId", "accepted") }
                    }
                    "explorer_agent_snapshot" -> buildJsonObject {
                        put("reset", true); put("cursor", "generation:1"); put("queue", buildJsonArray {})
                        put("entries", buildJsonArray { if (sent) add(entry("accepted", "user", JsonPrimitive(wire))) })
                        put("leafId", if (sent) JsonPrimitive("accepted") else JsonNull)
                    }
                    else -> error("Unexpected child operation: $op")
                }
            }
            explorer.refreshHub()
            val child = explorer.openAgent("child-instance")
            child.sendPrompt(raw, listOf(file))
            withTimeout(5000) { admitted.await() }
            val pending = child.state.value.feed.filterIsInstance<UserMessageItem>().single()
            assertEquals(raw, pending.text)
            assertEquals(listOf(file), pending.attachments)
            assertFalse(pending.accepted)
            release.complete(Unit)
            withTimeout(5000) { child.state.first { it.feed.filterIsInstance<UserMessageItem>().singleOrNull()?.accepted == true } }
            val accepted = child.state.value.feed.filterIsInstance<UserMessageItem>().single()
            assertEquals(raw, accepted.text)
            assertEquals(listOf(file), accepted.attachments)
        } finally { release.complete(Unit); owner.cancel() }
    }

    @Test fun childQueueBlankEditAndTakeUseWireIdentityAndPreserveAttachments() = runBlocking {
        val file = AttachmentFile("stable", "config.json", "/workspace/config.json", "application/json", 10, AttachmentSource.WORKSPACE)
        var wire = attachmentWireMessage("same", listOf(file))
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var removed = false
        var removalFails = false
        try {
            val explorer = explorer(owner) { op, body ->
                when (op) {
                    "explorer_hub" -> childHub()
                    "explorer_agent_snapshot" -> buildJsonObject {
                        put("cursor", "generation:1"); put("queue", buildJsonArray { if (!removed) add(buildJsonObject {
                            put("id", "queue-id"); put("queue", "steering"); put("wireText", wire)
                            put("text", parseAttachmentPrompt(wire).text); put("attachments", buildJsonArray { add(attachmentJson(file)) })
                        }) })
                    }
                    "explorer_agent_control" -> {
                        assertEquals("queue-id", body["queueId"]!!.jsonPrimitive.content)
                        assertEquals("steering", body["queue"]!!.jsonPrimitive.content)
                        assertEquals(wire, body["originalText"]!!.jsonPrimitive.content)
                        if (body["operation"]!!.jsonPrimitive.content == "update_queue") wire = body["text"]!!.jsonPrimitive.content
                        else { check(!removalFails) { "native removal failed" }; removed = true }
                        buildJsonObject {}
                    }
                    else -> error("Unexpected child operation: $op")
                }
            }
            explorer.refreshHub()
            val child = explorer.openAgent("child-instance")
            assertEquals("same", child.state.value.queuedPrompts.single().text)
            child.updateQueuedPrompt("queue-id", "")
            withTimeout(5000) { child.state.first { it.queuedPrompts.singleOrNull()?.text == "" } }
            assertEquals(attachmentWireMessage("", listOf(file)), wire)
            assertEquals(QueuedPrompt("queue-id", "", listOf(file), PromptQueue.STEERING), child.state.value.queuedPrompts.single())
            removalFails = true
            assertFailsWith<IllegalStateException> { child.takeQueuedPrompt("queue-id") }
            assertEquals(1, child.state.value.queuedPrompts.size)
            removalFails = false
            assertEquals(RestoredSessionPrompt("", listOf(file)), child.takeQueuedPrompt("queue-id"))
            assertTrue(child.state.value.queuedPrompts.isEmpty())
        } finally { owner.cancel() }
    }

    @Test fun nativeInlineImageQueueEditNeverAdvertisesHostPreviewCachePaths() = runBlocking {
        val image = AttachmentFile("native-image", "历史图像.png", "/tmp/native-image.png", "image/png", 5, AttachmentSource.LOCAL)
        var wire = "native image"
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val explorer = explorer(owner) { op, body ->
                when (op) {
                    "explorer_hub" -> childHub()
                    "explorer_agent_snapshot" -> buildJsonObject {
                        put("cursor", "native:1"); put("queue", buildJsonArray { add(buildJsonObject {
                            put("id", "image-queue"); put("text", wire); put("wireText", wire); put("queue", "followUp")
                            put("attachments", buildJsonArray { add(attachmentJson(image)) })
                        }) })
                    }
                    "explorer_agent_control" -> {
                        assertEquals(wire, body["originalText"]!!.jsonPrimitive.content)
                        wire = body["text"]!!.jsonPrimitive.content
                        buildJsonObject {}
                    }
                    else -> error("Unexpected native operation: $op")
                }
            }
            explorer.refreshHub()
            val child = explorer.openAgent("child-instance")
            child.updateQueuedPrompt("image-queue", "\n  ")
            withTimeout(5000) { child.state.first { it.queuedPrompts.singleOrNull()?.text == "\n  " } }
            assertEquals("\n  ", wire)
            assertEquals(listOf(image), child.state.value.queuedPrompts.single().attachments)
            assertEquals("\n  ", child.state.value.queuedPrompts.single().text)
        } finally { owner.cancel() }
    }

    private fun attachmentJson(file: AttachmentFile) = buildJsonObject {
        put("id", file.id); put("name", file.name); put("path", file.path); put("mimeType", file.mimeType)
        put("sizeBytes", file.sizeBytes); put("source", file.source.name)
    }
    private fun childHub() = buildJsonObject {
        put("mainSessionId", "native-main"); put("connected", true)
        put("agents", buildJsonArray { add(buildJsonObject {
            put("instanceKey", "child-instance"); put("id", "child"); put("name", "child"); put("lifecycle", "idle")
        }) })
    }

    private fun explorer(owner: CoroutineScope, request: suspend (String, JsonObject) -> JsonObject) =
        OmpSessionExplorer("app", "container", "/workspace", owner, request = request, synchronize = {},
            identities = { emptyMap() }, originalTexts = { emptyMap() }, messageForChatItem = { null },
            prepareAttachments = { text, attachments, _ -> Triple(attachmentWireMessage(text, attachments), attachments, JsonArray(emptyList())) },
            files = { _, _ -> flowOf(emptyList()) })

    private fun entry(id: String, role: String, content: JsonElement) = buildJsonObject {
        put("id", id); put("type", "message"); put("parentId", JsonNull)
        put("message", buildJsonObject { put("role", role); put("content", content); put("timestamp", 1) })
    }
    private fun node(id: String, parent: String? = null, version: String = "v1") = buildJsonObject {
        put("id", id); put("parentId", parent); put("type", "message"); put("title", id); put("version", version)
        put("hasCopyContent", true)
    }
    private fun page(revision: String, leaf: String, nodes: List<JsonObject>, cursor: String? = null) = buildJsonObject {
        put("sessionId", "native-main"); put("sessionFile", "/session/main.jsonl"); put("revision", revision); put("leafId", leaf)
        put("reset", true); put("nodes", JsonArray(nodes)); put("nextCursor", cursor)
    }
}
