package dev.wawlink.driver

import dev.wawlink.protocol.*
import java.io.File
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** A side channel on the owning main RPC; observations never create a second native session. */
internal class OmpSessionExplorer(
    private val id: String,
    private val containerId: String,
    private val workspace: String,
    private val scope: CoroutineScope,
    private val mainState: StateFlow<ChatSessionUiState>,
    private val request: suspend (String, JsonObject) -> JsonObject,
    private val synchronize: suspend (JsonObject) -> Unit,
    private val identities: suspend (List<JsonObject>) -> Map<String, List<String>>,
    private val originalTexts: suspend (List<JsonObject>) -> Map<String, String>,
    private val prepareAttachments: suspend (String, List<AttachmentFile>, String) -> Triple<String, List<AttachmentFile>, JsonArray>,
    private val files: (String, String) -> Flow<List<WorkspaceFileItem>>,
) : SessionExplorer {
    private val mutableHistory = MutableStateFlow(SessionHistoryState())
    override val history = mutableHistory.asStateFlow()
    private val mutableHub = MutableStateFlow(AgentHubState())
    override val hub = mutableHub.asStateFlow()
    private val historyMutex = Mutex()
    private val hubMutex = Mutex()
    private val children = linkedMapOf<String, ChildController>()
    private var records = emptyList<JsonObject>()

    private suspend fun call(op: String, body: JsonObject = buildJsonObject {}): JsonObject = request(op, body)

    override suspend fun refreshHistory() = historyMutex.withLock {
        mutableHistory.update { it.copy(loading = true, error = null) }
        try { applyHistory(call("explorer_history")) }
        catch (failure: Exception) { mutableHistory.update { it.copy(error = failure.message) }; throw failure }
        finally { mutableHistory.update { it.copy(loading = false) } }
    }

    private suspend fun applyHistory(response: JsonObject) {
        records = response.objects("entries")
        if (records.isNotEmpty() && mainState.value.feed.isEmpty()) synchronize(response)
        val leaf = response.optional("leafId")
        val byId = records.associateBy { it.string("id") }
        val path = hashSetOf<String>()
        var cursor = leaf
        while (cursor != null && path.add(cursor)) cursor = byId[cursor]?.optional("parentId")
        val chatIds = identities(records)
        val originalInputs = response.obj("userInputs")?.mapNotNull { (key, value) ->
            (value as? JsonPrimitive)?.contentOrNull?.let { key to it }
        }.orEmpty().toMap() + originalTexts(records)
        val labels = linkedMapOf<String, String>()
        records.filter { it.string("type") == "label" }.forEach { entry ->
            val target = entry.optional("targetId") ?: entry.optional("entryId") ?: return@forEach
            val label = entry.optional("label")
            if (label == null) labels.remove(target) else labels[target] = label
        }
        response.obj("labels")?.forEach { (key, value) -> value.jsonPrimitive.contentOrNull?.let { labels[key] = it } }
        val calls = linkedMapOf<String, JsonObject>()
        records.forEach { entry -> entry.obj("message")?.objects("content")?.filter { it.string("type") == "toolCall" }?.forEach { calls[it.string("id")] = it } }
        val nodes = records.filterNot { it.string("type") == "session" }.map { entry ->
            val entryId = entry.string("id")
            val message = entry.obj("message")
            val role = message?.optional("role")
            val text = if (role == "user" || originalInputs.containsKey(entryId)) originalInputs[entryId]
                ?: message?.optional("rawText") ?: entry.optional("rawText") ?: message?.let(::messageText).orEmpty()
            else message?.let(::messageText) ?: entry.optional("text")
                ?: entry.optional("summary") ?: entry.optional("label") ?: entry.optional("name") ?: ""
            val feed = renderEntry(entry, calls)
            val ask = nativeHistoricalAsk(entry, calls)
            val isAskResult = role == "toolResult" && (message?.string("toolName")?.lowercase() == "ask" || calls[message?.string("toolCallId")]?.string("name")?.lowercase() == "ask")
            SessionHistoryNode(entryId, entry.optional("parentId"), if (isAskResult) "ask" else entry.string("type"), role,
                entry.optional("title") ?: title(entry.string("type"), role, text), text,
                entry.optional("timestamp") ?: message?.optional("timestamp").orEmpty(),
                labels[entryId] ?: entry.optional("label"), entryId in path,
                entry["navigable"]?.jsonPrimitive?.booleanOrNull ?: (entry.string("type") != "label"),
                feed, entry.toString(), copyBlocks(message, text), ask, chatIds[entryId]?.firstOrNull(), chatIds[entryId].orEmpty())
        }
        mutableHistory.update { it.copy(sessionId = response.string("sessionId"), sessionFile = response.optional("sessionFile"), leafId = leaf, nodes = nodes, error = null) }
    }

    private suspend fun <T> historyOperation(block: suspend () -> T): T {
        mutableHistory.update { it.copy(busy = true, error = null) }
        try { return block() }
        catch (failure: Exception) {
            if (failure !is SessionNavigationCancelledException && failure !is CancellationException)
                mutableHistory.update { it.copy(error = failure.message ?: "会话操作失败") }
            throw failure
        } finally { mutableHistory.update { it.copy(busy = false) } }
    }

    override suspend fun setLabel(entryId: String, label: String?) = historyOperation {
        call("explorer_label", buildJsonObject { put("entryId", entryId); put("label", label?.let(::JsonPrimitive) ?: JsonNull) })
        refreshHistory()
    }

    override suspend fun navigate(entryId: String, summary: HistorySummary): RestoredSessionPrompt? = historyOperation {
        val response = call("explorer_navigate", buildJsonObject {
            put("entryId", entryId); put("summary", buildJsonObject { put("enabled", summary.enabled); put("instructions", summary.instructions) })
        })
        if (response["cancelled"]?.jsonPrimitive?.booleanOrNull == true) throw SessionNavigationCancelledException()
        val snapshot = call("explorer_history")
        synchronize(snapshot)
        applyHistory(snapshot)
        response.obj("prompt")?.let { prompt ->
            RestoredSessionPrompt(prompt.string("text"), restoredImages(prompt))
        }
    }

    override suspend fun reanswer(entryId: String, answers: List<AskAnswer>) = historyOperation {
        val card = history.value.nodes.firstOrNull { it.id == entryId }?.ask ?: error("历史问题元数据不可用")
        val ordered = validateAskAnswers(card.questions, answers)
        val response = call("explorer_reanswer", buildJsonObject { put("entryId", entryId); put("answers", answersJson(ordered)) })
        if (response.bool("cancelled") == true) throw SessionNavigationCancelledException()
        val snapshot = call("explorer_history")
        synchronize(snapshot)
        applyHistory(snapshot)
    }

    override suspend fun cancelNavigation() { call("explorer_cancel_navigation") }
    override suspend fun fork(entryId: String): String = historyOperation {
        val response = call("explorer_fork", buildJsonObject { put("entryId", entryId) })
        if (response.bool("cancelled") == true) throw SessionNavigationCancelledException()
        response.optional("sessionFile") ?: error("原生分支未返回会话路径")
    }

    override suspend fun refreshHub(activityLimit: Int) = hubMutex.withLock {
        require(activityLimit == 20 || activityLimit == 2000)
        mutableHub.update { it.copy(loading = true, error = null) }
        try {
            val response = call("explorer_hub", buildJsonObject { put("activityLimit", activityLimit) })
            val mainId = response.string("mainSessionId")
            if (hub.value.mainSessionId.isNotBlank() && hub.value.mainSessionId != mainId) {
                synchronized(children) { children.values.forEach { it.invalidate() }; children.clear() }
            }
            val nativeHistory = call("explorer_history")
            val chatIds = identities(nativeHistory.objects("entries"))
            val agents = response.objects("agents").map(::runtimeAgent).map { agent ->
                agent.copy(dispatchChatItemId = if (agent.parentId == null) chatIds[agent.dispatchEntryId]?.firstOrNull() else null)
            }
            val activities = response.objects("activity").map(::activity).map { event ->
                event.copy(chatItemId = null)
            }
            mutableHub.value = AgentHubState(mainId, agents, activities,
                response["activityLimit"]?.jsonPrimitive?.intOrNull ?: activityLimit, connected = response["connected"]?.jsonPrimitive?.booleanOrNull == true)
        } catch (failure: Exception) {
            mutableHub.update { it.copy(connected = false, error = failure.message) }; throw failure
        } finally { mutableHub.update { it.copy(loading = false) } }
    }

    override suspend fun openAgent(instanceKey: String): RuntimeAgentSessionController {
        if (hub.value.agents.none { it.instanceKey == instanceKey }) refreshHub(hub.value.activityLimit)
        val agent = hub.value.agents.firstOrNull { it.instanceKey == instanceKey } ?: error("子代理实例已失效")
        val child = synchronized(children) { children.getOrPut(instanceKey) { ChildController(agent) } }
        child.refresh()
        return child
    }

    override suspend fun controlAgent(instanceKey: String, operation: RuntimeAgentOperation) {
        call("explorer_agent_control", buildJsonObject { put("instanceKey", instanceKey); put("operation", operation.name.lowercase()) })
        refreshHub(hub.value.activityLimit)
        synchronized(children) { children[instanceKey] }?.refresh()
    }

    override suspend fun exportAgent(instanceKey: String): SessionExport {
        val result = call("explorer_agent_export", buildJsonObject { put("instanceKey", instanceKey) })
        return SessionExport(result.optional("path") ?: error("原生导出未返回路径"), result.optional("name") ?: error("原生导出未返回文件名"), result.string("mimeType", "text/html"))
    }

    private inner class ChildController(private val agent: RuntimeAgent) : RuntimeAgentSessionController {
        private val mutex = Mutex()
        private val mutable = MutableStateFlow(ChatSessionUiState(id = agent.instanceKey, title = agent.name,
            workspace = agent.workspace ?: workspace, providerId = agent.providerId.orEmpty(), modelId = agent.modelId.orEmpty()))
        override val state = mutable.asStateFlow()
        private var reducer = OmpEventReducer(mutable.value)
        private var cursor: JsonElement? = null
        @Volatile private var entryFeedIds: Map<String, String> = emptyMap()
        private val nativeEntries = linkedMapOf<String, JsonObject>()
        override fun feedItemIdForEntry(entryId: String): String? = entryFeedIds[entryId.removePrefix("entry:")]
        private var valid = true
        private val uiRequests = linkedMapOf<String, JsonObject>()
        private var queueMetadata = emptyMap<String, JsonObject>()
        init {
            scope.launch {
                mutable.subscriptionCount.collectLatest { count ->
                    if (count > 0) while (isActive && valid) {
                        try { refresh() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { mutable.update { it.copy(error = failure.message, modelLoading = false) } }
                        delay(350)
                    }
                }
            }
        }
        fun invalidate() { valid = false; mutable.update { it.copy(status = SessionStatus.STOPPED, error = "所属主会话已切换，子代理实例已失效") } }
        private suspend fun childCall(op: String, body: JsonObject = buildJsonObject {}): JsonObject {
            check(valid) { "子代理实例已失效" }
            return call(op, JsonObject(body + ("instanceKey" to JsonPrimitive(agent.instanceKey))))
        }
        suspend fun refresh() = mutex.withLock {
            val result = childCall("explorer_agent_snapshot", buildJsonObject { cursor?.let { put("cursor", it) } })
            if (result["reset"]?.jsonPrimitive?.booleanOrNull == true || cursor == null) {
                reducer = OmpEventReducer(mutable.value.copy(feed = emptyList(), queuedPrompts = emptyList(), error = null, usage = UsageInfo()))
                uiRequests.clear()
                val messageIds = result.obj("messageIds")
                val entries = result.objects("entries").map { entry ->
                    val messageId = messageIds?.get(entry.string("id"))
                    if (messageId == null) entry else JsonObject(entry + ("messageId" to messageId))
                }
                nativeEntries.clear()
                entries.forEach { nativeEntries[it.string("id")] = it }
                if (entries.isNotEmpty()) reducer.restoreNativeBranch(entries, result.optional("leafId") ?: entries.lastOrNull()?.optional("id"))
                else result.objects("messages").forEachIndexed { index, message ->
                    reducer.reduce(buildJsonObject { put("type", "message_end"); put("messageId", message.optional("id") ?: "history:$index"); put("message", message) })
                }
            }
            result.objects("events").forEach { event ->
                if (event.string("type") == "extension_ui_request") {
                    if (event.string("method") == "cancel") uiRequests.remove(event.string("targetId"))
                    else uiRequests[event.string("id")] = event
                }
                reducer.reduce(event)
                val entryId = event.optional("entryId")
                val message = event.obj("message")
                if (entryId != null && message != null) nativeEntries[entryId] = buildJsonObject {
                    put("id", entryId); put("message", message); put("messageId", event["messageId"] ?: JsonNull)
                }
            }
            result.objects("requests").forEach { event -> uiRequests[event.string("id")] = event; reducer.reduce(event) }
            queueMetadata = result.objects("queue").associateBy { it.string("id") }
            reducer.update { current -> current.copy(workspace = result.optional("workspace") ?: current.workspace,
                providerId = result.optional("providerId") ?: current.providerId, modelId = result.optional("modelId") ?: current.modelId,
                thinkingLevel = parseThinking(result.optional("thinking"), current.thinkingLevel), modelLoading = false,
                status = when {
                    current.feed.flattenFeed().any { it is AskCardItem && !it.isResolved || it is ApprovalCardItem && it.resolution == null } -> SessionStatus.WAITING
                    result["isRunning"]?.jsonPrimitive?.booleanOrNull == true -> SessionStatus.RUNNING
                    current.status == SessionStatus.RUNNING || current.status == SessionStatus.WAITING -> SessionStatus.DONE
                    else -> current.status
                }, error = result.optional("error") ?: current.error,
                queuedPrompts = if (result["queue"] is JsonArray) result.objects("queue").map { queued ->
                    QueuedPrompt(queued.string("id"), queued.string("text"), nativeImageAttachments(queued))
                } else current.queuedPrompts) }
            cursor = result["cursor"] ?: cursor
            entryFeedIds = reducer.nativeEntryFeedIds(nativeEntries.values.toList())
            mutable.value = reducer.state
        }
        private fun launchOperation(block: suspend () -> Unit) {
            scope.launch {
                try { block() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { mutex.withLock { reducer.update { it.copy(error = failure.message, modelLoading = false) }; mutable.value = reducer.state } }
            }
        }
        override fun sendPrompt(text: String, attachments: List<AttachmentFile>) = sendAgentPrompt(text, attachments, RuntimeAgentPromptBehavior.PROMPT)
        override fun enqueuePrompt(text: String) = sendAgentPrompt(text, emptyList(), RuntimeAgentPromptBehavior.FOLLOW_UP)
        override fun sendAgentPrompt(text: String, attachments: List<AttachmentFile>, behavior: RuntimeAgentPromptBehavior) {
            if (text.isBlank() && attachments.isEmpty()) return
            if (text.trimStart().startsWith("/") || text.trimStart().startsWith("!")) {
                launchOperation { error("请通过子代理指令菜单执行安全指令；不允许将指令当作模型提示发送") }; return
            }
            launchOperation {
                val pendingId = "pending:${UUID.randomUUID()}"
                var admitted = false
                mutex.withLock {
                    reducer.addUserMessage(UserMessageItem(pendingId, text, attachments, System.currentTimeMillis(), accepted = false))
                    reducer.update { it.copy(error = null, actionText = "正在发送") }; mutable.value = reducer.state
                }
                try {
                    val prepared = prepareAttachments(text, attachments, state.value.workspace)
                    val response = childCall("explorer_agent_prompt", buildJsonObject {
                        put("text", prepared.first); if (prepared.third.isNotEmpty()) put("images", prepared.third)
                        put("behavior", when (behavior) { RuntimeAgentPromptBehavior.PROMPT -> "prompt"; RuntimeAgentPromptBehavior.STEER -> "steer"; RuntimeAgentPromptBehavior.FOLLOW_UP -> "followUp" })
                    })
                    check(response["accepted"]?.jsonPrimitive?.booleanOrNull == true) { "原生子代理未确认接收消息" }
                    admitted = true
                    // Admission is independent of eventual execution. Keep this message even if
                    // the next native snapshot or the subsequent model request fails.
                    mutex.withLock {
                        val existingId = response.optional("entryId")?.let { entryFeedIds[it] }
                        val acceptedId = existingId ?: response.optional("messageId") ?: response.optional("entryId") ?: pendingId
                        reducer.update { it.copy(feed = it.feed.withoutFeed(pendingId), error = null) }
                        if (response["queued"]?.jsonPrimitive?.booleanOrNull == true) reducer.update { it.copy(queuedPrompts = it.queuedPrompts + QueuedPrompt(acceptedId, prepared.first, prepared.second)) }
                        else if (existingId == null) reducer.addUserMessage(UserMessageItem(acceptedId, prepared.first, prepared.second, System.currentTimeMillis(), accepted = true))
                        mutable.value = reducer.state
                    }
                    refresh()
                } finally {
                    if (!admitted) mutex.withLock {
                        reducer.update { it.copy(feed = it.feed.withoutFeed(pendingId)) }; mutable.value = reducer.state
                    }
                }
            }
        }
        override fun stopExecution() { launchOperation { controlAgent(agent.instanceKey, RuntimeAgentOperation.STOP) } }
        private fun queueOperation(queueId: String, text: String?) = launchOperation {
            val queued = queueMetadata[queueId] ?: error("排队消息已失效，请刷新后重试")
            childCall("explorer_agent_control", buildJsonObject {
                put("operation", if (text == null) "remove_queue" else "update_queue"); put("queueId", queueId)
                put("queue", queued.string("queue")); put("originalText", queued.string("text")); text?.let { put("text", it) }
            }); refresh()
        }
        override fun updateQueuedPrompt(id: String, newText: String) { if (newText.isNotBlank()) queueOperation(id, newText) }
        override fun removeQueuedPrompt(id: String) { queueOperation(id, null) }
        override fun selectModel(providerId: String, modelId: String) { launchOperation {
            childCall("explorer_agent_model", buildJsonObject { put("providerId", providerId); put("modelId", modelId) }); refresh()
        } }
        override fun selectThinkingLevel(level: ThinkingLevel) { launchOperation {
            childCall("explorer_agent_thinking", buildJsonObject { put("level", thinkingWire(level)) }); refresh()
        } }
        override fun toggleExecutionMode(autoApprove: Boolean) { launchOperation {
            childCall("explorer_agent_control", buildJsonObject { put("operation", "approval_mode"); put("autoApprove", autoApprove) }); refresh()
        } }
        override fun respondApproval(nodeId: String, decision: ApprovalDecision) { launchOperation {
            val native = mutex.withLock { uiRequests[nodeId] } ?: error("子代理审批请求已失效")
            val body = buildJsonObject {
                put("requestId", nodeId)
                when (native.string("method")) {
                    "confirm" -> put("confirmed", decision != ApprovalDecision.DENY)
                    "select" -> {
                        val options = (native["options"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
                        val option = when (decision) {
                            ApprovalDecision.DENY -> options.firstOrNull { it == "Deny" }
                            ApprovalDecision.ALLOW_ONCE -> options.firstOrNull { it == "Approve Once" } ?: options.firstOrNull { it == "Approve" }
                            ApprovalDecision.ALLOW_ALWAYS_IN_SESSION -> options.firstOrNull { it.startsWith("Approve ") && it.endsWith(" for this session") }
                        } ?: error("原生子代理未提供该审批选项")
                        put("value", option)
                    }
                    else -> error("不支持的子代理审批方法")
                }
            }
            childCall("explorer_agent_respond", body)
            mutex.withLock { reducer.update { it.copy(feed = it.feed.mapFeed { item -> if (item is ApprovalCardItem && item.id == nodeId) item.copy(resolution = decision) else item }) }; mutable.value = reducer.state; uiRequests.remove(nodeId) }
            refresh()
        } }
        override fun respondAsk(nodeId: String, answers: List<AskAnswer>) { launchOperation {
            val native = mutex.withLock { uiRequests[nodeId] } ?: error("子代理提问请求已失效")
            val card = state.value.feed.flattenFeed().filterIsInstance<AskCardItem>().firstOrNull { it.id == nodeId } ?: error("子代理问题元数据不可用")
            val ordered = validateAskAnswers(card.questions, answers)
            childCall("explorer_agent_respond", buildJsonObject {
                put("requestId", nodeId)
                if (native.string("method") == "ask") put("answers", answersJson(ordered))
                else put("value", ordered.single().let { it.customInput ?: it.selectedOptions.single() })
            })
            mutex.withLock { reducer.update { it.copy(feed = it.feed.mapFeed { item -> if (item is AskCardItem && item.id == nodeId) item.copy(answers = ordered, cancelled = false) else item }) }; mutable.value = reducer.state; uiRequests.remove(nodeId) }
            refresh()
        } }
        override fun executeSlashCommand(command: String, args: String) { launchOperation {
            val name = command.trim().removePrefix("/")
            require(name in setOf("btw", "model", "think", "export", "jobs", "cost")) { "子代理不支持该指令" }
            val result = childCall("explorer_agent_command", buildJsonObject { put("command", name); put("args", args) })
            result.optional("text")?.let { output -> mutex.withLock {
                reducer.update { it.copy(feed = it.feed + AssistantMessageItem("command:${UUID.randomUUID()}", output)) }; mutable.value = reducer.state
            } }
            refresh()
        } }
        override fun loadSlashCommands() { launchOperation {
            mutex.withLock { reducer.update { it.copy(slashCommandsLoading = true, slashCommandsError = null) }; mutable.value = reducer.state }
            try {
                val result = childCall("explorer_agent_commands")
                mutex.withLock {
                    val commands = (result["commands"] as? JsonArray).orEmpty().mapNotNull { value ->
                        val name = (value as? JsonObject)?.optional("name") ?: (value as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                        val normalized = name.removePrefix("/")
                        if (normalized !in setOf("btw", "model", "think", "export", "jobs", "cost")) null
                        else buildJsonObject { put("name", normalized); put("source", "native child") }
                    }
                    reducer.reduce(buildJsonObject { put("type", "available_commands_update"); put("commands", JsonArray(commands)) })
                    mutable.value = reducer.state
                }
            } catch (failure: Exception) {
                mutex.withLock { reducer.update { it.copy(slashCommandsError = failure.message) }; mutable.value = reducer.state }; throw failure
            } finally { mutex.withLock { reducer.update { it.copy(slashCommandsLoading = false) }; mutable.value = reducer.state } }
        } }
        override fun listWorkspaceFiles(relativePath: String) = files(state.value.workspace, relativePath)
    }

    private fun renderEntry(entry: JsonObject, calls: Map<String, JsonObject>): List<FeedItem> {
        val renderer = OmpEventReducer(ChatSessionUiState(id = "history:${entry.string("id")}", workspace = workspace))
        val message = entry.obj("message")
        val renderEntries = mutableListOf<JsonObject>()
        var rendered = entry
        if (message?.string("role") == "toolResult") {
            calls[message.string("toolCallId")]?.let { call ->
                val callId = "call:${call.string("id")}"
                renderEntries += buildJsonObject {
                    put("id", callId); put("type", "message")
                    put("message", buildJsonObject { put("role", "assistant"); put("content", buildJsonArray { add(call) }) })
                }
                rendered = JsonObject(entry + ("parentId" to JsonPrimitive(callId)))
            }
        }
        renderEntries += rendered
        renderer.restoreNativeBranch(renderEntries, entry.optional("id"))
        return renderer.state.feed
    }


    private fun copyBlocks(message: JsonObject?, text: String): List<SessionCopyBlock> = buildList {
        if (text.isNotBlank()) add(SessionCopyBlock("${if (message?.string("role") == "user") "原始输入" else "文本"}", text))
        if (message?.string("role") == "assistant") message.objects("content").filter { it.string("type") == "toolCall" }.forEach { call ->
            val args = call.obj("arguments") ?: return@forEach
            val name = call.string("name").lowercase()
            val command = when (name) { "bash" -> args.optional("command"); "eval", "python", "javascript" -> args.optional("code"); else -> null }
            if (!command.isNullOrBlank()) add(SessionCopyBlock(call.string("name"), command, "terminal"))
        }
        val codeRanges = historyCodeFences.findAll(text).toList()
        codeRanges.forEach { add(SessionCopyBlock("代码", it.groupValues[2], "code")) }
        val prose = StringBuilder(text).apply {
            codeRanges.asReversed().forEach { match -> replace(match.range.first, match.range.last + 1, "\n") }
        }.toString()
        val quote = mutableListOf<String>()
        fun flushQuote() {
            if (quote.isNotEmpty()) { add(SessionCopyBlock("引用", quote.joinToString("\n"), "format_quote")); quote.clear() }
        }
        prose.lineSequence().forEach { line ->
            val prefix = historyQuotePrefix.find(line)
            if (prefix == null) flushQuote() else quote.add(line.substring(prefix.range.last + 1))
        }
        flushQuote()
        historyHttpTarget.findAll(prose).map { match ->
            var target = match.value.trimEnd('*', '.', ',', ';', '!', '，', '。', '；', '！', '）')
            while (target.endsWith(')') && target.count { it == ')' } > target.count { it == '(' }) target = target.dropLast(1)
            target
        }.distinct().forEach { add(SessionCopyBlock("链接", it, "link")) }
    }

    private fun restoredImages(prompt: JsonObject): List<AttachmentFile> = nativeImageAttachments(prompt)

    private fun runtimeAgent(value: JsonObject): RuntimeAgent {
        val stats = value.obj("stats") ?: buildJsonObject {}
        return RuntimeAgent(
            instanceKey = value.string("instanceKey"), id = value.string("id"), name = value.string("name"), parentId = value.optional("parentId"),
            lifecycle = enumValue<RuntimeAgentLifecycle>(value.string("lifecycle")) ?: error("原生子代理生命周期未知"), advisor = value.bool("advisor") == true,
            result = value.optional("result"), task = value.string("task"), activity = value.string("activity"), error = value.optional("error"),
            modelId = value.optional("modelId"), providerId = value.optional("providerId"), modelRole = value.optional("modelRole"), thinking = value.optional("thinking"),
            fallback = value.optional("fallback"), workspace = value.optional("workspace"), containerName = value.string("containerName", containerId), sessionFile = value.optional("sessionFile"),
            createdAtMillis = value.long("createdAtMillis"), lastActivityMillis = value.long("lastActivityMillis"), unread = value["unread"]?.jsonPrimitive?.intOrNull,
            stats = RuntimeAgentStats(stats.long("elapsedMillis"), stats.long("requests"), stats.long("tools"), stats.long("inputTokens"), stats.long("outputTokens"), stats.long("cacheReadTokens"), stats.long("cacheWriteTokens"), stats["cost"]?.jsonPrimitive?.doubleOrNull, stats.long("contextTokens"), stats.long("contextWindow")),
            artifacts = value.objects("artifacts").map { AgentArtifact(it.string("title"), it.string("path"), it.string("icon", "draft"), it.string("description")) },
            jobs = value.objects("jobs").map { AgentBackgroundJob(it.string("id"), it.string("command"), it.optional("cwd"), it.long("pid"), it.string("status"), it["exitCode"]?.jsonPrimitive?.intOrNull, it.string("output"), it.optional("outputPath")) },
            canSend = value.bool("canSend") == true, canStop = value.bool("canStop") == true, canKill = value.bool("canKill") == true,
            canRevive = value.bool("canRevive") == true, canSetModel = value.bool("canSetModel") == true, unavailableReason = value.optional("unavailableReason"),
            dispatchEntryId = value.optional("dispatchEntryId"), dispatchChatItemId = value.optional("dispatchChatItemId"), definitionName = value.optional("definitionName"))
    }
    private fun activity(value: JsonObject) = AgentActivity(value.string("id"), value.string("agentId"), value.string("instanceKey"),
        enumValue<AgentActivityKind>(value.string("kind")) ?: error("原生子代理活动类别未知"), value.string("text"), value.string("timestamp"), value.optional("entryId"), value.optional("chatItemId"))
}

private fun JsonObject.optional(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.obj(key: String) = this[key] as? JsonObject
private fun JsonObject.objects(key: String) = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.longOrNull
private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
private val historyCodeFences = Regex("(?m)^ {0,3}(`{3,}|~{3,})[^\\r\\n]*\\r?\\n([\\s\\S]*?)^ {0,3}\\1[ \\t]*(?:\\r?\\n|$)")
private val historyQuotePrefix = Regex("^[ \\t]*(?:>[ \\t]?)+")
private val historyHttpTarget = Regex("https?://[^\\s<>\\\"\\]`]+")
private inline fun <reified T : Enum<T>> enumValue(value: String): T? = enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) }
private fun parseThinking(value: String?, fallback: ThinkingLevel) = when (value?.lowercase()) { "off", "none" -> ThinkingLevel.NONE; else -> enumValue<ThinkingLevel>(value.orEmpty()) ?: fallback }
private fun messageText(message: JsonObject): String = when (val content = message["content"]) {
    is JsonPrimitive -> content.contentOrNull.orEmpty()
    is JsonArray -> content.mapNotNull { (it as? JsonObject)?.takeIf { block -> block.string("type") == "text" }?.optional("text") }.joinToString("\n")
    else -> message.optional("summary").orEmpty()
}
private fun title(type: String, role: String?, text: String): String {
    val prefix = when (role) { "user" -> "用户"; "assistant" -> "助手"; "toolResult" -> "工具结果"; else -> when(type) { "compaction" -> "压缩摘要"; "branch_summary" -> "分支摘要"; "label" -> "标签"; "model_change" -> "模型切换"; "thinking_level_change" -> "思考等级"; else -> type } }
    return text.lineSequence().firstOrNull { it.isNotBlank() }?.take(120)?.let { "$prefix · $it" } ?: prefix
}
private fun answersJson(answers: List<AskAnswer>) = buildJsonArray { answers.forEach { answer -> add(buildJsonObject {
    put("id", answer.questionId); put("selectedOptions", buildJsonArray { answer.selectedOptions.forEach { add(it) } }); answer.customInput?.let { put("customInput", it) }
}) } }
private fun List<FeedItem>.flattenFeed(): List<FeedItem> = flatMap { if (it is StepGroupNodeItem) listOf(it) + it.children.flattenFeed() else listOf(it) }
private fun List<FeedItem>.mapFeed(transform: (FeedItem) -> FeedItem): List<FeedItem> = map { transform(if (it is StepGroupNodeItem) it.copy(children = it.children.mapFeed(transform)) else it) }
private fun List<FeedItem>.withoutFeed(id: String): List<FeedItem> = mapNotNull {
    when { it.id == id -> null; it is StepGroupNodeItem -> it.copy(children = it.children.withoutFeed(id)); else -> it }
}

/** Native inline images become real local files, usable again by the normal attachment transfer. */
internal fun nativeImageAttachments(message: JsonObject): List<AttachmentFile> {
    val images = message.objects("images").ifEmpty { message.objects("content").filter { it.string("type") == "image" } }
    return images.mapIndexedNotNull { index, image ->
        val mime = image.string("mimeType", "image/png")
        val path = image.optional("path")
        if (path != null) return@mapIndexedNotNull AttachmentFile("native-image:$index:$path", image.string("name", File(path).name), path, mime, source = AttachmentSource.CONTAINER)
        val data = image.optional("data") ?: return@mapIndexedNotNull null
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(data.toByteArray()).joinToString("") { "%02x".format(it) }
        val suffix = when (mime) { "image/jpeg" -> ".jpg"; "image/webp" -> ".webp"; "image/gif" -> ".gif"; else -> ".png" }
        val directory = File(System.getProperty("java.io.tmpdir"), "wawlink-native-images")
        check(directory.isDirectory || directory.mkdirs()) { "无法创建历史图片缓存" }
        val local = File(directory, "$hash$suffix")
        synchronized(nativeImageLock) {
            if (!local.isFile) {
                val staging = File.createTempFile("image-", ".tmp", directory)
                try {
                    staging.writeBytes(Base64.getDecoder().decode(data))
                    java.nio.file.Files.move(staging.toPath(), local.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                } finally { staging.delete() }
            }
        }
        AttachmentFile("native-image:$hash", "历史图像-${index + 1}$suffix", local.path, mime, local.length(), AttachmentSource.LOCAL)
    }
}
private val nativeImageLock = Any()
internal fun nativeHistoricalAsk(entry: JsonObject, calls: Map<String, JsonObject>): AskCardItem? {
        val message = entry.obj("message") ?: return null
        val call = if (message.string("role") == "toolResult") calls[message.string("toolCallId")]
            else message.objects("content").firstOrNull { it.string("type") == "toolCall" && it.string("name").lowercase() == "ask" }
        if (call?.string("name")?.lowercase() != "ask") return null
        val args = call.obj("arguments") ?: return null
        val questions = args.objects("questions").mapNotNull { question ->
            val questionId = question.optional("id") ?: return@mapNotNull null
            AskQuestion(questionId, question.string("question"), question.optional("header"), (question["options"] as? JsonArray).orEmpty().mapNotNull { value ->
                if (value is JsonObject) value.optional("label")?.let { AskOption(it, value.optional("description"), value.optional("preview")) }
                else value.jsonPrimitive.contentOrNull?.let(::AskOption)
            }, question["multi"]?.jsonPrimitive?.booleanOrNull == true, question["recommended"]?.jsonPrimitive?.intOrNull)
        }
        if (questions.isEmpty()) return null
        val details = message.obj("details")
        val savedAnswers = details?.objects("results").orEmpty().ifEmpty {
            if (questions.size == 1 && details?.containsKey("selectedOptions") == true) listOf(details) else emptyList()
        }
        val answers = if (savedAnswers.size == questions.size) savedAnswers.mapIndexed { index, answer ->
            AskAnswer(answer.optional("id") ?: questions[index].id,
                (answer["selectedOptions"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }, answer.optional("customInput"))
        } else null
        return AskCardItem("history:ask:${call.string("id")}", "历史提问", questions, answers,
            cancelled = answers == null || message.bool("isError") == true || details?.bool("cancelled") == true)
    }
