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
    private val request: suspend (String, JsonObject) -> JsonObject,
    private val synchronize: suspend (JsonObject) -> Unit,
    private val identities: suspend (List<JsonObject>) -> Map<String, List<String>>,
    private val originalTexts: suspend (List<JsonObject>) -> Map<String, String>,
    private val messageForChatItem: suspend (String) -> JsonObject?,
    private val prepareAttachments: suspend (String, List<AttachmentFile>, String) -> Triple<String, List<AttachmentFile>, JsonArray>,
    private val files: (String, String) -> Flow<List<WorkspaceFileItem>>,
) : SessionExplorer {
    private val mutableHistory = MutableStateFlow(SessionHistoryState())
    override val history = mutableHistory.asStateFlow()
    private val mutableHub = MutableStateFlow(AgentHubState())
    override val hub = mutableHub.asStateFlow()
    private val historyMutex = Mutex()
    private val hubMutex = Mutex()
    private val hubRequestLock = Any()
    private data class HubRequest(val revision: Long, val limit: Int, val query: AgentActivityQuery)
    @Volatile private var desiredHub = HubRequest(0, 20, AgentActivityQuery())
    private val children = linkedMapOf<String, ChildController>()
    private val detailMutex = Mutex()
    private data class DetailKey(val sessionId: String, val sessionFile: String?, val entryId: String, val version: String, val kind: HistoryDetailKind)
    private val details = LinkedHashMap<DetailKey, SessionHistoryDetail>(8, .75f, true)

    private suspend fun call(op: String, body: JsonObject = buildJsonObject {}): JsonObject =
        withContext(Dispatchers.IO) { request(op, body) }

    override suspend fun refreshHistory(): Unit = historyMutex.withLock {
        mutableHistory.update { it.copy(loading = true, error = null) }
        try {
            val previous = history.value
            var page = call("explorer_history", buildJsonObject { previous.revision.takeIf { it.isNotEmpty() }?.let { put("since", it) } })
            val sessionId = page.string("sessionId")
            val revision = page.string("revision")
            val leaf = page.optional("leafId")
            val sessionFile = page.optional("sessionFile")
            val reset = page.bool("reset") == true
            check(reset || (sessionId == previous.sessionId && sessionFile == previous.sessionFile)) { "原生历史增量所属会话已变化" }
            if (revision == previous.revision && sessionId == previous.sessionId && sessionFile == previous.sessionFile && leaf == previous.leafId &&
                (page["nodes"] as? JsonArray).isNullOrEmpty() && (page["removedIds"] as? JsonArray).isNullOrEmpty() && page.optional("nextCursor") == null) return@withLock
            val nodes = if (reset) linkedMapOf() else previous.nodes.associateByTo(linkedMapOf()) { it.id }
            val cursors = hashSetOf<String>()
            while (true) {
                check(page.string("sessionId") == sessionId && page.string("revision") == revision && page.optional("leafId") == leaf && page.optional("sessionFile") == sessionFile && page.bool("reset") == reset) { "原生历史分页不是同一快照" }
                withContext(Dispatchers.Default) {
                    (page["removedIds"] as? JsonArray).orEmpty().forEach { nodes.remove(it.jsonPrimitive.content) }
                    page.objects("nodes").forEach { value ->
                        val old = nodes[value.string("id")]
                        val node = SessionHistoryNode(id = value.string("id"), parentId = value.optional("parentId"), type = value.string("type"),
                            role = value.optional("role"), title = value.string("title"), searchText = value.string("searchText"), timestamp = value.string("timestamp"),
                            label = value.optional("label"), onCurrentPath = old?.onCurrentPath == true, navigable = value.bool("navigable") != false, version = value.string("version"),
                            hasCopyContent = value.bool("hasCopyContent") == true, copyBlockCount = (value["copyBlockCount"] as? JsonPrimitive)?.intOrNull)
                        nodes[node.id] = old?.takeIf { it == node } ?: node
                    }
                }
                val cursor = page.optional("nextCursor") ?: break
                check(cursors.add(cursor)) { "原生历史分页游标重复" }
                page = call("explorer_history", buildJsonObject { put("cursor", cursor) })
            }
            val updated = withContext(Dispatchers.Default) {
                val path = hashSetOf<String>()
                var cursor = leaf
                while (cursor != null && path.add(cursor)) cursor = nodes[cursor]?.parentId
                nodes.values.map { node -> if (node.onCurrentPath == (node.id in path)) node else node.copy(onCurrentPath = node.id in path) }
            }
            mutableHistory.update { it.copy(sessionId = sessionId, sessionFile = sessionFile, revision = revision, leafId = leaf, nodes = updated, error = null) }
            detailMutex.withLock {
                val versions = updated.associate { it.id to it.version }
                details.keys.removeAll { it.sessionId != sessionId || it.sessionFile != sessionFile || versions[it.entryId] != it.version }
            }
        } catch (failure: Exception) {
            if (failure !is CancellationException) mutableHistory.update { it.copy(error = failure.message ?: "历史读取失败") }
            throw failure
        } finally { mutableHistory.update { it.copy(loading = false) } }
    }

    override suspend fun loadHistoryDetail(entryId: String, kind: HistoryDetailKind): SessionHistoryDetail = detailMutex.withLock {
        val state = history.value
        val node = state.nodes.firstOrNull { it.id == entryId } ?: error("历史节点已不存在，请重新读取")
        val key = DetailKey(state.sessionId, state.sessionFile, entryId, node.version, kind)
        details[key]?.let { return@withLock it }
        val response = call("explorer_history_detail", buildJsonObject { put("entryId", entryId); put("version", node.version); put("kind", kind.name.lowercase()) })
        check(response.string("sessionId") == state.sessionId && response.string("entryId") == entryId && response.string("version") == node.version) { "历史详情已变化，请重新读取" }
        val entry = response.obj("entry")
        val calls = response.objects("calls").associateBy { it.string("id") }
        val detail = withContext(Dispatchers.Default) {
            val message = entry?.obj("message") ?: entry?.takeIf { it.string("type") == "custom_message" }
            val sourceText = if (entry == null) "" else message?.let(::messageText) ?: entry.optional("text")
                ?: entry.optional("summary") ?: entry.optional("label") ?: entry.optional("name").orEmpty()
            val parsed = if (response.containsKey("userText") || message?.string("role") == "user")
                parseAttachmentPrompt(sourceText, bridgeAttachmentFiles(response)) else AttachmentPrompt(sourceText, emptyList())
            val text = if (entry == null) parsed.text else originalTexts(listOf(entry))[entryId] ?: response.optional("userText") ?: parsed.text
            val prompt = parsed.copy(text = text)
            SessionHistoryDetail(state.sessionId, entryId, node.version, text,
                feed = if (kind == HistoryDetailKind.PREVIEW && entry != null) renderEntry(entry, calls, prompt) else emptyList(),
                rawRecord = response.string("rawRecord"),
                copyBlocks = if (kind == HistoryDetailKind.COPY) copyBlocks(message, text) else emptyList(),
                ask = if (kind == HistoryDetailKind.ASK || kind == HistoryDetailKind.PREVIEW) entry?.let { nativeHistoricalAsk(it, calls) } else null)
        }
        check(history.value.sessionId == state.sessionId && history.value.sessionFile == state.sessionFile && history.value.nodes.any { it.id == entryId && it.version == node.version }) { "历史详情已过期，请重新读取" }
        details[key] = detail
        while (details.size > 8) details.remove(details.keys.first())
        detail
    }

    override suspend fun entryIdForChatItem(chatItemId: String): String? {
        val message = messageForChatItem(chatItemId) ?: return null
        val response = call("explorer_history_find", buildJsonObject { put("message", message) })
        check(response.string("sessionId") == history.value.sessionId) { "消息所属会话已变化" }
        return response.optional("entryId")
    }

    override suspend fun chatItemIdForEntry(entryId: String): String? {
        val sessionId = history.value.sessionId.ifBlank { hub.value.mainSessionId }
        val node = history.value.nodes.firstOrNull { it.id == entryId }
        val response = call("explorer_history_detail", buildJsonObject { put("entryId", entryId); node?.let { put("version", it.version) }; put("kind", "identity") })
        check(response.string("sessionId") == sessionId) { "消息所属会话已变化" }
        val entry = response.obj("entry") ?: return null
        return identities(listOf(entry))[entryId]?.firstOrNull()
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
        synchronize(call("explorer_history_branch"))
        refreshHistory()
        response.obj("prompt")?.let { prompt ->
            val restored = restoredPrompt(prompt)
            RestoredSessionPrompt(restored.text, restored.attachments)
        }
    }

    override suspend fun reanswer(entryId: String, answers: List<AskAnswer>) = historyOperation {
        val card = loadHistoryDetail(entryId, HistoryDetailKind.ASK).ask ?: error("历史问题元数据不可用")
        val ordered = validateAskAnswers(card.questions, answers)
        val response = call("explorer_reanswer", buildJsonObject { put("entryId", entryId); put("answers", answersJson(ordered)) })
        if (response.bool("cancelled") == true) throw SessionNavigationCancelledException()
        synchronize(call("explorer_history_branch"))
        refreshHistory()
    }

    override suspend fun cancelNavigation() { call("explorer_cancel_navigation") }
    override suspend fun fork(entryId: String): String = historyOperation {
        val response = call("explorer_fork", buildJsonObject { put("entryId", entryId) })
        if (response.bool("cancelled") == true) throw SessionNavigationCancelledException()
        response.optional("sessionFile") ?: error("原生分支未返回会话路径")
    }

    override suspend fun refreshHub(activityLimit: Int?, activityQuery: AgentActivityQuery?) {
        require(activityLimit == null || activityLimit > 0)
        val target = synchronized(hubRequestLock) {
            HubRequest(desiredHub.revision + 1, activityLimit ?: desiredHub.limit, activityQuery ?: desiredHub.query).also { next ->
                desiredHub = next
                mutableHub.update { it.copy(loading = true, error = null, activityQuery = next.query, activityLimit = next.limit,
                    activity = if (it.activityQuery == next.query) it.activity else emptyList(),
                    activityHasMore = it.activityQuery == next.query && it.activityHasMore) }
            }
        }
        try { hubMutex.withLock {
            if (desiredHub != target) return@withLock
            try {
                val response = call("explorer_hub", buildJsonObject {
                    put("activityLimit", target.limit)
                    put("activityQuery", buildJsonObject {
                        put("instanceKey", target.query.instanceKey); put("includeDescendants", target.query.includeDescendants)
                        put("kind", target.query.kind?.name?.lowercase()); put("search", target.query.search)
                    })
                })
                synchronized(hubRequestLock) {
                    if (desiredHub != target) return@synchronized
                    val mainId = response.string("mainSessionId")
                    if (hub.value.mainSessionId.isNotBlank() && hub.value.mainSessionId != mainId) {
                        synchronized(children) { children.values.forEach { it.invalidate() }; children.clear() }
                    }
                    val previous = hub.value
                    val oldAgents = previous.agents.associateBy { it.instanceKey }
                    val agents = response.objects("agents").map(::runtimeAgent).map { agent -> oldAgents[agent.instanceKey]?.takeIf { it == agent } ?: agent }
                    val oldActivities = previous.activity.associateBy { it.id }
                    val activities = response.objects("activity").map(::activity).map { event -> oldActivities[event.id]?.takeIf { it == event } ?: event }
                    mutableHub.value = AgentHubState(mainSessionId = mainId, agents = agents, activity = activities,
                        activityLimit = target.limit, activityQuery = target.query, activityHasMore = response.bool("activityHasMore") == true,
                        connected = response.bool("connected") == true, discovering = response.bool("discovering") == true,
                        discoveryError = response.optional("discoveryError"))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                synchronized(hubRequestLock) {
                    if (desiredHub == target) mutableHub.update { it.copy(connected = false, discovering = false, error = failure.message) }
                }
                throw failure
            }
        } } finally {
            synchronized(hubRequestLock) { if (desiredHub == target) mutableHub.update { it.copy(loading = false) } }
        }
    }

    override suspend fun openAgent(instanceKey: String): RuntimeAgentSessionController {
        if (hub.value.agents.none { it.instanceKey == instanceKey }) refreshHub()
        val agent = hub.value.agents.firstOrNull { it.instanceKey == instanceKey } ?: error("子代理实例已失效")
        val child = synchronized(children) { children.getOrPut(instanceKey) { ChildController(agent) } }
        child.refresh()
        return child
    }

    override suspend fun controlAgent(instanceKey: String, operation: RuntimeAgentOperation) {
        call("explorer_agent_control", buildJsonObject { put("instanceKey", instanceKey); put("operation", operation.name.lowercase()) })
        refreshHub()
        synchronized(children) { children[instanceKey] }?.refresh()
    }

    override suspend fun exportAgent(instanceKey: String): SessionExport {
        val result = call("explorer_agent_export", buildJsonObject { put("instanceKey", instanceKey) })
        return SessionExport(result.optional("path") ?: error("原生导出未返回路径"), result.optional("name") ?: error("原生导出未返回文件名"), result.string("mimeType", "text/html"))
    }

    private inner class ChildController(private val agent: RuntimeAgent) : RuntimeAgentSessionController {
        private val mutex = Mutex()
        private val mutable = MutableStateFlow(ChatSessionUiState(id = agent.instanceKey, title = agent.name,
            workspace = agent.workspace ?: workspace, providerId = agent.providerId.orEmpty(), modelId = agent.modelId.orEmpty(),
            availableModels = emptyList(), availableThinkingLevels = emptyList()))
        override val state = mutable.asStateFlow()
        private var reducer = OmpEventReducer(mutable.value)
        private var cursor: JsonElement? = null
        @Volatile private var entryFeedIds: Map<String, String> = emptyMap()
        private val nativeEntries = linkedMapOf<String, JsonObject>()
        override fun feedItemIdForEntry(entryId: String): String? = entryFeedIds[entryId.removePrefix("entry:")]
        private var valid = true
        private val uiRequests = linkedMapOf<String, JsonObject>()
        private var queueMetadata = emptyMap<String, JsonObject>()
        private val queuedInputs = mutableMapOf<String, RestoredSessionPrompt>()
        private val attachmentInputs = mutableMapOf<String, AttachmentPrompt>()
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
                attachmentInputs.entries.forEachIndexed { index, (wire, prompt) -> reducer.rememberAttachmentPrompt("attachment-cache:$index", prompt.text, prompt.attachments, wire) }
                uiRequests.clear()
                val messageIds = result.obj("messageIds")
                val entries = result.objects("entries").map { entry ->
                    rememberBridgePrompt(entry)
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
                rememberBridgePrompt(event)
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
            if (queuedInputs.isNotEmpty()) queuedInputs.keys.retainAll(queueMetadata.values.mapTo(mutableSetOf()) { it.string("wireText", it.string("text")) })
            reducer.update { current -> current.copy(workspace = result.optional("workspace") ?: current.workspace,
                providerId = result.optional("providerId") ?: current.providerId, modelId = result.optional("modelId") ?: current.modelId,
                thinkingLevel = parseThinking(result.optional("thinking"), current.thinkingLevel), modelLoading = false,
                availableModels = result.objects("availableModels").map { SessionModel(it.string("providerId"), it.string("modelId"), it.string("name", it.string("modelId"))) },
                availableThinkingLevels = (result["availableThinkingLevels"] as? JsonArray).orEmpty().mapNotNull { value ->
                    when (value.jsonPrimitive.content) {
                        "off", "none" -> ThinkingLevel.NONE
                        else -> enumValue<ThinkingLevel>(value.jsonPrimitive.content)
                    }
                }.distinct(),
                status = when {
                    current.feed.flattenFeed().any { it is AskCardItem && !it.isResolved || it is ApprovalCardItem && it.resolution == null } -> SessionStatus.WAITING
                    result["isRunning"]?.jsonPrimitive?.booleanOrNull == true -> SessionStatus.RUNNING
                    current.status == SessionStatus.RUNNING || current.status == SessionStatus.WAITING -> SessionStatus.DONE
                    else -> current.status
                }, error = result.optional("error") ?: current.error,
                queuedPrompts = if (result["queue"] is JsonArray) result.objects("queue").map { queued ->
                    val wire = queued.string("wireText", queued.string("text"))
                    val prompt = queuedInputs[wire]?.let { AttachmentPrompt(it.text, it.attachments) } ?: restoredPrompt(queued)
                    attachmentInputs[wire] = prompt
                    reducer.rememberAttachmentPrompt(queued.string("id"), prompt.text, prompt.attachments, wire)
                    QueuedPrompt(queued.string("id"), prompt.text, prompt.attachments,
                        if (queued.string("queue") == PromptQueue.STEERING.wireName) PromptQueue.STEERING else PromptQueue.FOLLOW_UP)
                } else current.queuedPrompts) }
            cursor = result["cursor"] ?: cursor
            entryFeedIds = reducer.nativeEntryFeedIds(nativeEntries.values.toList())
            mutable.value = reducer.state
        }
        private fun rememberBridgePrompt(value: JsonObject) {
            val message = value.obj("message") ?: return
            if (message.string("role") != "user") return
            val wire = messageText(message)
            val prompt = attachmentInputs[wire] ?: parseAttachmentPrompt(wire, bridgeAttachmentFiles(value)).let { parsed ->
                parsed.copy(text = value.optional("userText") ?: parsed.text)
            }
            reducer.rememberAttachmentPrompt(value.string("messageId", value.string("id")), prompt.text, prompt.attachments, wire)
        }
        private fun launchOperation(block: suspend () -> Unit) {
            scope.launch {
                try { block() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { mutex.withLock { reducer.update { it.copy(error = failure.message, modelLoading = false) }; mutable.value = reducer.state } }
            }
        }
        override fun sendPrompt(text: String, attachments: List<AttachmentFile>) = sendAgentPrompt(text, attachments, RuntimeAgentPromptBehavior.PROMPT)
        override fun enqueuePrompt(text: String, attachments: List<AttachmentFile>) = sendAgentPrompt(text, attachments, RuntimeAgentPromptBehavior.FOLLOW_UP)
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
                    mutex.withLock {
                        attachmentInputs[prepared.first] = AttachmentPrompt(text, prepared.second)
                        reducer.rememberAttachmentPrompt(pendingId, text, prepared.second, prepared.first)
                    }
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
                        if (response["queued"]?.jsonPrimitive?.booleanOrNull == true) {
                            queuedInputs[prepared.first] = RestoredSessionPrompt(text, prepared.second)
                            reducer.update { it.copy(queuedPrompts = it.queuedPrompts + QueuedPrompt(acceptedId, text, prepared.second,
                                if (behavior == RuntimeAgentPromptBehavior.FOLLOW_UP) PromptQueue.FOLLOW_UP else PromptQueue.STEERING)) }
                        }
                        else if (existingId == null || existingId == pendingId) reducer.addUserMessage(UserMessageItem(acceptedId, text, prepared.second, System.currentTimeMillis(), accepted = true))
                        reducer.rememberAttachmentPrompt(acceptedId, text, prepared.second, prepared.first)
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
            val (queued, item) = mutex.withLock {
                (queueMetadata[queueId] ?: error("排队消息已失效，请刷新后重试")) to
                    (reducer.state.queuedPrompts.firstOrNull { it.id == queueId } ?: error("排队消息已开始执行"))
            }
            check(text == null || text.isNotBlank() || item.attachments.isNotEmpty()) { "排队消息不能为空" }
            // Native inline images have no guest-file suffix. Keep their existing
            // image payload instead of advertising a host preview-cache path.
            val references = parseAttachmentPrompt(queued.string("wireText", queued.string("text"))).attachments
            val wire = text?.let { attachmentWireMessage(it, references) }
            childCall("explorer_agent_control", buildJsonObject {
                put("operation", if (text == null) "remove_queue" else "update_queue"); put("queueId", queueId)
                put("queue", queued.string("queue")); put("originalText", queued.string("wireText", queued.string("text"))); wire?.let { put("text", it) }
            })
            if (text != null && wire != null) mutex.withLock {
                queuedInputs[wire] = RestoredSessionPrompt(text, item.attachments)
                attachmentInputs[wire] = AttachmentPrompt(text, item.attachments)
                reducer.rememberAttachmentPrompt(queueId, text, item.attachments, wire)
            }
            refresh()
        }
        override fun updateQueuedPrompt(id: String, newText: String) { queueOperation(id, newText) }
        override fun removeQueuedPrompt(id: String) { queueOperation(id, null) }
        override suspend fun takeQueuedPrompt(id: String): RestoredSessionPrompt = withContext(NonCancellable) {
            mutex.withLock {
                val item = reducer.state.queuedPrompts.firstOrNull { it.id == id } ?: error("排队消息已开始执行，无法撤回")
                val queued = queueMetadata[id] ?: error("排队消息已变化，请稍后重试")
                val wire = queued.string("wireText", queued.string("text"))
                val input = queuedInputs[wire] ?: RestoredSessionPrompt(item.text, item.attachments)
                childCall("explorer_agent_control", buildJsonObject {
                    put("operation", "remove_queue"); put("queueId", id)
                    put("queue", queued.string("queue")); put("originalText", wire)
                })
                reducer.update { it.copy(queuedPrompts = it.queuedPrompts.filterNot { prompt -> prompt.id == id }) }
                queueMetadata = queueMetadata - id
                if (queueMetadata.values.none { it.string("wireText", it.string("text")) == wire }) queuedInputs.remove(wire)
                mutable.value = reducer.state
                input
            }
        }
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

    private fun renderEntry(entry: JsonObject, calls: Map<String, JsonObject>, prompt: AttachmentPrompt): List<FeedItem> {
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
        return renderer.state.feed.mapFeed { item -> if (item is UserMessageItem) item.copy(text = prompt.text,
            attachments = prompt.attachments.ifEmpty { item.attachments }) else item }
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

    private fun restoredPrompt(prompt: JsonObject): AttachmentPrompt {
        val parsed = parseAttachmentPrompt(prompt.string("wireText", prompt.string("text")), bridgeAttachmentFiles(prompt))
        return if (parsed.attachments.isNotEmpty()) parsed else parsed.copy(attachments = nativeImageAttachments(prompt))
    }

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
            dispatchEntryId = value.optional("dispatchEntryId"), definitionName = value.optional("definitionName"))
    }
    private fun activity(value: JsonObject) = AgentActivity(value.string("id"), value.string("agentId"), value.string("instanceKey"),
        enumValue<AgentActivityKind>(value.string("kind")) ?: error("原生子代理活动类别未知"), value.string("text"), value.string("timestamp"), value.optional("entryId"), value.optional("chatItemId"))
}

private fun JsonObject.optional(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.obj(key: String) = this[key] as? JsonObject
private fun JsonObject.objects(key: String) = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.longOrNull
private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
private fun bridgeAttachmentFiles(value: JsonObject): List<AttachmentFile> = value.objects("attachments").mapNotNull { file ->
    val path = file.optional("path") ?: return@mapNotNull null
    val name = file.optional("name") ?: return@mapNotNull null
    val restored = parseAttachmentPrompt("\n\n附件（容器内真实文件）：\n- $name: $path").attachments.singleOrNull() ?: return@mapNotNull null
    restored.copy(id = file.optional("id") ?: restored.id,
        mimeType = file.optional("mimeType") ?: restored.mimeType,
        sizeBytes = file.long("sizeBytes") ?: restored.sizeBytes,
        source = enumValue<AttachmentSource>(file.string("source")) ?: restored.source)
}
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
