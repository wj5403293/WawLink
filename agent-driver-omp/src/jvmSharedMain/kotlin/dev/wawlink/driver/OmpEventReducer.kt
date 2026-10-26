package dev.wawlink.driver

import dev.wawlink.protocol.*
import kotlinx.serialization.json.*

internal data class UserMessagePosition(val groupId: String?, val afterId: String?)

internal fun placeUserMessage(feed: List<FeedItem>, user: UserMessageItem, position: UserMessagePosition,
    positions: Map<String, UserMessagePosition> = emptyMap()): List<FeedItem> {
    fun insert(items: List<FeedItem>): List<FeedItem> {
        var after = position.afterId
        while (after != null) {
            val index = items.indexOfFirst { it.id == after }
            if (index >= 0) return items.toMutableList().apply { add(index + 1, user) }
            val previous = positions[after] ?: return items + user
            after = previous.afterId
        }
        return listOf(user) + items
    }
    if (position.groupId == null) return insert(feed)
    val index = feed.indexOfFirst { it.id == position.groupId && it is StepGroupNodeItem }
    if (index < 0) return feed + user
    val group = feed[index] as StepGroupNodeItem
    return feed.toMutableList().apply { set(index, group.copy(children = insert(group.children))) }
}

/**
 * OMP 18.12.6 RPC reducer; the pool serializes all access, including [update].
 *
 * Modern updates nest `assistantMessageEvent` and may carry two accumulated snapshots;
 * snapshots replace blocks, while delta-only frames append. Tool callback content is also
 * accumulated, not a delta. Successful todo results expose `details.phases[].tasks`.
 * Only terminal `agent_end` finalizes feed nodes; `session_settled` only settles session status.
 * Durable compaction boundaries require `get_entries` ancestry; partially retained groups
 * remain visible rather than losing content.
 *
 * Native UI card ids retain request ids; ask questions retain their own ids and option metadata.
 * Blocked/abandoned todo distinctions and automatic effort have no exact public enum equivalent;
 * blocked stays pending, abandoned completes, auto preserves the last effective fixed level.
 */
class OmpEventReducer(initial: ChatSessionUiState, private val clock: () -> Long = System::currentTimeMillis) {
    private class FeedLocation(val index: Int, val parent: FeedLocation?, var node: FeedItem)
    private val feedLocations = HashMap<String, FeedLocation>()
    private var indexedFeed: List<FeedItem>? = null

    var state: ChatSessionUiState = initial
        private set(value) {
            if (indexedFeed !== value.feed) {
                feedLocations.clear()
                indexedFeed = null
            }
            field = value
        }

    private var generation = 0L
    private var sequence = 0L
    private var eventTimeMillis = clock()
    private var currentMessage: String? = null
    private val messageStack = mutableListOf<String>()
    private val messages = mutableMapOf<String, JsonObject>()
    private val completedBlocks = mutableSetOf<String>()
    private val blockStartedAt = mutableMapOf<String, Long>()
    private val usageByMessage = mutableMapOf<String, UsageInfo>()
    private val reconciledUsers = mutableSetOf<String>()
    private val userFeedIds = mutableMapOf<String, String>()
    private val userPositions = linkedMapOf<String, UserMessagePosition>()
    private val positionTails = mutableMapOf<UserMessagePosition, String>()
    private val entries = linkedMapOf<String, JsonObject>()
    private var leafEntryId: String? = null
    private var firstKeptEntryId: String? = null
    private var withdrawnQueue = emptyList<QueuedPrompt>()
    private val consumedQueue = mutableListOf<QueuedPrompt>()
    private val tools = mutableMapOf<String, ToolRecord>()
    private val argumentDeltas = mutableMapOf<String, String>()
    private val askFeedByTool = mutableMapOf<String, String>()
    private var planId: String? = initial.feed.filterIsInstance<PlanNodeItem>().lastOrNull()?.id
    private var planSteps = initial.feed.filterIsInstance<PlanNodeItem>().lastOrNull()?.steps.orEmpty()
    private var planAfterUser = false
    private var activeGroup: String? = null
    private var activeTitle: String? = null
    private var startedAt: Long? = null
    private var runError: String? = null
    private var aborted = false

    private data class ToolRecord(
        val id: String,
        var name: String,
        var args: JsonObject,
        var startedAt: Long?,
        var output: String = "",
        var finished: Boolean = false,
        var finishedAt: Long? = null,
        var elapsedSec: Int? = null,
        var executionStarted: Boolean = false,
        var terminalStatus: NodeStatus? = null,
        var failed: Boolean = false,
        var intent: String? = null,
        var details: JsonObject? = null,
        var diff: String? = null,
        var added: Int = 0,
        var removed: Int = 0,
    )

    fun update(transform: (ChatSessionUiState) -> ChatSessionUiState): ChatSessionUiState {
        val previous = state
        state = transform(state)
        if (previous.isRunning && (state.status == SessionStatus.STOPPED || state.status == SessionStatus.ERROR)) {
            startedAt = null
            activeGroup = null
            activeTitle = null
            tools.values.forEach { record ->
                val node = item(record.id) as? ToolNodeItem
                if (!record.finished && node != null && node.state != NodeStatus.RUNNING) {
                    record.finished = true
                    record.elapsedSec = node.elapsedSec
                    record.terminalStatus = node.state
                }
            }
        }
        return state
    }
    internal fun hasConfirmedUser(id: String): Boolean = id in reconciledUsers

    /** Native entry identity is retained independently of RPC generations and presentation groups. */
    internal fun nativeEntryFeedIds(records: List<JsonObject>): Map<String, String> =
        nativeEntryFeedAliases(records).mapValues { it.value.first() }

    internal fun nativeEntryFeedAliases(records: List<JsonObject>): Map<String, List<String>> {
        val unused = messages.entries.toMutableList()
        val feed = allItems(state.feed).associateBy { it.id }
        return buildMap {
            records.forEach { entry ->
                val message = entry["message"] as? JsonObject ?: return@forEach
                val entryId = entry.string("id") ?: return@forEach
                val explicit = entry.string("messageId")?.let(::wireMessage)
                val match = unused.firstOrNull { it.key == explicit } ?: unused.firstOrNull { it.value == message }
                if (match != null) {
                    unused.remove(match)
                    val targets = nativeFeedTargets(match.key, message, feed)
                    if (targets.isNotEmpty()) put(entryId, targets)
                }
            }
        }
    }

    /** Resolve the one explicitly selected chat item without serializing any other message. */
    internal fun nativeMessageForFeedItem(feedItemId: String): JsonObject? {
        val feed = allItems(state.feed).associateBy { it.id }
        return messages.entries.firstOrNull { (id, message) -> feedItemId in nativeFeedTargets(id, message, feed) }?.value
    }


    private fun nativeFeedTargets(messageId: String, message: JsonObject, feed: Map<String, FeedItem>): List<String> {
        val id = userFeedIds[messageId] ?: messageId
        fun toolIds(callId: String): List<String> = listOfNotNull(askFeedByTool[callId],
            "history:ask:$callId".takeIf { it in feed }, feed.keys.firstOrNull { it.endsWith(":tool:$callId") })
        return when (message.string("role")) {
            "assistant" -> message.array("content").orEmpty().flatMapIndexed { index, value ->
                val block = value as? JsonObject
                if (block?.string("type") == "toolCall") block.string("id")?.let(::toolIds).orEmpty()
                else listOf(blockId(id, index))
            }.sortedBy { if (feed[it] is AssistantMessageItem) 0 else if (feed[it] is AskCardItem) 1 else 2 }
            "toolResult" -> message.string("toolCallId")?.let(::toolIds).orEmpty()
            else -> listOf(id)
        }.filter { it in feed }.distinct()
    }

    private val originalUserText = mutableMapOf<String, String>()
    internal fun rememberUserText(feedId: String, text: String) { originalUserText[feedId] = text }
    internal fun originalUserText(feedId: String): String? = originalUserText[feedId]
    internal fun nativeUserText(records: List<JsonObject>): Map<String, String> = buildMap {
        nativeEntryFeedIds(records).forEach { (entryId, feedId) -> originalUserText[feedId]?.let { put(entryId, it) } }
    }

    /** Rebuild only the selected ancestry; the caller journals the complete tree before applying. */
    internal fun restoreNativeBranch(records: List<JsonObject>, leafId: String?) {
        val previousIds = nativeEntryFeedIds(records)
        val previousUsers = allItems(state.feed).filterIsInstance<UserMessageItem>().associateBy { it.id }
        val byId = records.associateBy { it.string("id") }
        val nativeCalls = records.asSequence().mapNotNull { it.obj("message") }
            .flatMap { it.array("content").orEmpty().asSequence() }.mapNotNull { it as? JsonObject }
            .filter { it.string("type") == "toolCall" }.associateBy { it.string("id").orEmpty() }
        val branch = mutableListOf<JsonObject>()
        val visited = hashSetOf<String>()
        var cursor = leafId
        while (cursor != null && visited.add(cursor)) {
            val entry = byId[cursor] ?: break
            branch.add(entry)
            cursor = entry.string("parentId")
        }
        currentMessage = null; messageStack.clear(); messages.clear(); completedBlocks.clear()
        blockStartedAt.clear(); usageByMessage.clear(); reconciledUsers.clear(); userFeedIds.clear()
        userPositions.clear(); positionTails.clear(); tools.clear(); argumentDeltas.clear()
        askFeedByTool.clear()
        entries.clear(); records.forEach { record -> record.string("id")?.let { entries[it] = record } }
        activeGroup = null; activeTitle = null; startedAt = null; runError = null; aborted = false
        planId = null; planSteps = emptyList(); planAfterUser = false
        state = state.copy(feed = emptyList(), compactedThroughId = null, status = SessionStatus.IDLE,
            error = null, usage = UsageInfo(), addedLines = 0, removedLines = 0, actionText = "准备就绪")
        branch.asReversed().forEach { entry ->
            val message = entry.obj("message")
            if (message != null) {
                val entryId = entry.string("id").orEmpty()
                val old = previousIds[entryId]
                val messageId = when {
                    entry.string("messageId") != null -> wireMessage(entry.string("messageId").orEmpty())
                    message.string("role") == "user" && old != null -> old
                    message.string("role") == "assistant" && old?.contains(":block:") == true -> old.substringBeforeLast(":block:")
                    else -> "native:$entryId"
                }
                snapshot(messageId, message, true, historical = true)
                if (message.string("role") == "user") {
                    val user = item(messageId) as? UserMessageItem
                    val previous = previousUsers[old]
                    if (user != null) put(user.copy(attachments = previous?.attachments ?: nativeImageAttachments(message),
                        timestamp = previous?.timestamp ?: user.timestamp))
                }
                nativeHistoricalAsk(entry, nativeCalls)?.let(::put)
            } else when (entry.string("type")) {
                "compaction", "branch_summary" -> entry.string("summary")?.let {
                    put(AssistantMessageItem("native:${entry.string("id")}", it))
                }
            }
        }
        closeGroup(NodeStatus.OK)
        leafEntryId = leafId
        markCompactedBoundary()
        state = state.copy(status = SessionStatus.IDLE, actionText = "准备就绪")
    }

    internal fun currentUserPosition(): UserMessagePosition {
        val group = activeGroup?.let { item(it) as? StepGroupNodeItem }?.takeIf { state.isRunning && it.state == NodeStatus.RUNNING }
        return UserMessagePosition(group?.id, (group?.children ?: state.feed).lastOrNull()?.id)
    }

    internal fun reserveUserPosition(id: String, position: UserMessagePosition = currentUserPosition()) {
        if (id in userPositions) return
        // Pending users form an anchor chain, including multiple identical queued prompts.
        userPositions[id] = positionTails[position]?.let { position.copy(afterId = it) } ?: position
        positionTails[position] = id
    }

    internal fun addUserMessage(user: UserMessageItem, position: UserMessagePosition = currentUserPosition()) {
        reserveUserPosition(user.id, position)
        put(user)
    }


    fun reduce(frame: JsonObject): ChatSessionUiState {
        eventTimeMillis = frame.number("_wawlinkAtMillis") ?: clock()
        when (frame.string("type")) {
            "ready" -> {
                generation++
                sequence = 0
                currentMessage = null
                messageStack.clear()
                completedBlocks.clear()
                blockStartedAt.clear()
                usageByMessage.clear()
                tools.clear()
                argumentDeltas.clear()
                consumedQueue.clear()
                activeGroup = null
                activeTitle = null
                planAfterUser = true
                startedAt = null
                state = state.copy(slashCommands = emptyList(), slashCommandsError = null)
            }
            "agent_start" -> {
                if (startedAt == null) {
                    planAfterUser = true
                    activeGroup = null
                    activeTitle = null
                    startedAt = eventTimeMillis
                    runError = null
                    aborted = false
                    state = state.copy(elapsedSec = 0)
                }
                state = state.copy(status = SessionStatus.RUNNING, actionText = "正在执行", error = null)
                ensureActiveGroup()
            }
            "turn_start" -> state = state.copy(actionText = "正在生成")
            "message_start", "message_update", "message_end" -> messageFrame(frame)
            "thinking_start", "thinking_delta", "thinking_end", "text_start", "text_delta", "text_end",
            "toolcall_start", "toolcall_delta", "toolcall_end" -> {
                val id = currentMessage ?: generated("message").also { currentMessage = it }
                assistantEvent(id, frame)
            }
            "tool_execution_start", "tool_execution_update", "tool_stream_update", "tool_execution_end" -> toolFrame(frame)
            "todo", "todo_update", "plan_update", "todo_reminder" -> applyPlan(frame)
            "todo_auto_clear" -> applyTasks(emptyList())
            "extension_ui_request" -> extensionRequest(frame)
            "tool_approval_request" -> {
                val id = frame.string("id") ?: frame.string("requestId") ?: generated("approval")
                put(ApprovalCardItem(id, frame.string("title") ?: "工具执行审批", frame.string("description") ?: "",
                    frame.string("command") ?: frame.obj("args")?.toString().orEmpty()))
                waiting()
            }
            "command_output" -> frame.string("text")?.let { put(AssistantMessageItem(generated("command"), it)) }
            "available_commands_update" -> availableCommands(frame)
            "session_info_update" -> frame.string("title")?.let { state = state.copy(title = it) }
            "ask" -> askRequest(frame, frame.string("id") ?: generated("ask"))
            "queue_update" -> queue(frame)
            "response" -> response(frame)
            "thinking_level_changed" -> thinking(frame.string("configured")?.takeUnless { it == "auto" }
                ?: frame.string("thinkingLevel")?.takeUnless { it == "auto" } ?: frame.string("resolved"))
            "model_changed" -> frame.obj("model")?.let(::model)
            "auto_retry_start" -> state = state.copy(status = SessionStatus.RUNNING,
                actionText = "正在重试 ${frame.number("attempt") ?: 1}/${frame.number("maxAttempts") ?: 1}",
                error = frame.string("errorMessage"))
            "auto_retry_end" -> {
                runError = if (frame.bool("success") == true) null else frame.string("finalError")
                state = state.copy(error = runError, actionText = if (runError == null) "正在执行" else "重试失败")
            }
            "auto_compaction_start" -> state = state.copy(actionText = "正在压缩上下文")
            "auto_compaction_end" -> {
                state = state.copy(actionText = if (frame.bool("willRetry") == true) "压缩后重试" else "上下文压缩完成",
                    error = frame.string("errorMessage"))
                frame.obj("result")?.number("tokensAfter")?.let { count ->
                    state = state.copy(usage = state.usage.copy(contextTokens = count))
                }
                if (frame.bool("aborted") != true && frame.bool("skipped") != true) frame.obj("result")?.let(::compactionResult)
            }
            "cache_warming_end" -> frame.obj("usage")?.let { accumulateUsage(generated("warming"), it) }
            "notice" -> state = state.copy(actionText = frame.string("message") ?: state.actionText,
                error = if (frame.string("level") == "error") frame.string("message") else state.error)
            "agent_end" -> {
                reconcileEndMessages(frame)
                if (frame.bool("isTerminal") != false) finish() else {
                    state = state.copy(status = SessionStatus.RUNNING, actionText = "等待后续任务")
                }
            }
            "session_settled" -> if (state.isRunning && !hasPendingDialog()) settleStatus()
            "prompt_result" -> promptResult(frame)
        }
        if (state.isRunning) startedAt?.let { state = state.copy(elapsedSec = seconds(it)) }
        return state
    }

    private fun generated(kind: String) = "omp:$generation:$kind:${++sequence}"
    private fun wireMessage(id: String) = "omp:$generation:message:$id"
    private fun blockId(id: String, index: Int) = "$id:block:$index"

    private fun messageFrame(frame: JsonObject) {
        val type = frame.string("type")
        val supplied = frame.string("messageId")?.let(::wireMessage)
        val id = supplied ?: when (type) {
            "message_start" -> generated("message")
            else -> messageStack.lastOrNull() ?: currentMessage ?: generated("message")
        }
        if (type == "message_start") messageStack.add(id)
        currentMessage = id
        val message = frame.obj("message")
        val event = frame.obj("assistantMessageEvent")
        if (event != null) assistantEvent(id, event, message)
        else if (message != null) snapshot(id, message, type == "message_end")
        if (type == "message_end") {
            if (event != null && message != null) snapshot(id, message, true)
            messageStack.remove(id)
            currentMessage = messageStack.lastOrNull()
        }
    }

    private fun assistantEvent(id: String, event: JsonObject, outer: JsonObject? = null) {
        val type = event.string("type").orEmpty()
        val terminal = type == "done" || type == "error"
        val full = event.obj("message") ?: event.obj("error") ?: event.obj("partial")
            ?: outer?.takeIf { it["content"] != null }
        if (full != null) snapshot(id, full, terminal)
        val index = (event.number("contentIndex") ?: if (type.startsWith("text_")) 1 else if (type.startsWith("toolcall_")) 2 else 0).toInt()
        val nodeId = blockId(id, index)
        if (type.endsWith("_end")) completedBlocks.add(nodeId)
        when {
            type.startsWith("thinking_") || type.startsWith("text_") -> {
                val thinking = type.startsWith("thinking_")
                val old = item(nodeId)
                val previous = when (old) {
                    is ThinkingNodeItem -> old.text
                    is AssistantMessageItem -> old.markdownText
                    else -> ""
                }
                val text = event.string("content") ?: if (full == null && type.endsWith("_delta")) {
                    previous + event.string("delta").orEmpty()
                } else previous
                val streaming = nodeId !in completedBlocks
                val narration = full?.array("content")?.getOrNull(index)?.asObject()?.bool("narration") == true
                if (thinking && !narration) put(thinkingNode(nodeId, streaming, text))
                else put(AssistantMessageItem(nodeId, text, streaming), groupWithTodo = true)
            }
            type.startsWith("toolcall_") -> {
                val key = "$id:$index"
                if (type == "toolcall_delta") argumentDeltas[key] = argumentDeltas[key].orEmpty() + event.string("delta").orEmpty()
                val call = event.obj("toolCall") ?: full?.array("content")?.getOrNull(index)?.asObject()
                if (call?.string("type") == "toolCall" || event.obj("toolCall") != null) {
                    val args = call?.obj("arguments") ?: argumentDeltas[key]?.let { raw ->
                        runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                    } ?: JsonObject(emptyMap())
                    call?.let { block -> block.string("id")?.let { tool(it, block.string("name").orEmpty(), args, block.string("intent")) } }
                    if (type == "toolcall_end") argumentDeltas.remove(key)
                }
            }
        }
    }

    private fun snapshot(id: String, message: JsonObject, final: Boolean, historical: Boolean = false) {
        messages[id] = message
        when (message.string("role")) {
            "user" -> {
                if (historical) closeGroup(NodeStatus.OK)
                val text = contentText(message["content"])
                val direct = item(id) as? UserMessageItem
                if (direct != null) {
                    if (!direct.accepted) put(direct.copy(accepted = true))
                    reconciledUsers.add(id)
                } else {
                    val existing = allItems(state.feed).filterIsInstance<UserMessageItem>().firstOrNull {
                        it.text == text && it.id !in reconciledUsers
                    }
                    if (existing != null) {
                        reconciledUsers.add(existing.id)
                        if (!existing.accepted) put(existing.copy(accepted = true))
                        // Wire start/end share an id, but the pool owns the original attachments and timestamp.
                        reconciledUsers.add(id)
                        userFeedIds[id] = existing.id
                    } else if (id !in reconciledUsers) {
                        val index = consumedQueue.indexOfFirst { it.text == text }
                        val queued = if (index >= 0) consumedQueue.removeAt(index) else null
                        val feedId = queued?.id ?: id
                        put(UserMessageItem(feedId, text, attachments = queued?.attachments?.takeIf { it.isNotEmpty() } ?: nativeImageAttachments(message),
                            timestamp = message.number("timestamp") ?: 0))
                        reconciledUsers.add(feedId)
                        userFeedIds[id] = feedId
                        reconciledUsers.add(id)
                    }
                }
            }
            "assistant" -> {
                message.array("content")?.forEachIndexed { index, element ->
                    val block = element.asObject() ?: return@forEachIndexed
                    val nodeId = blockId(id, index)
                    if (final) completedBlocks.add(nodeId)
                    val streaming = nodeId !in completedBlocks
                    when (block.string("type")) {
                        "thinking" -> if (block.bool("narration") == true) {
                            put(AssistantMessageItem(nodeId, block.string("thinking").orEmpty(), streaming),
                                groupWithTodo = true, historical = historical)
                        } else {
                            put(thinkingNode(nodeId, streaming, block.string("thinking").orEmpty()), historical = historical)
                        }
                        "text" -> put(AssistantMessageItem(nodeId, block.string("text").orEmpty(), streaming),
                            groupWithTodo = true, historical = historical)
                        "image" -> {
                            val mime = block.string("mimeType")
                            val data = block.string("data")
                            if (mime in imageMimeTypes && !data.isNullOrBlank())
                                put(AssistantMessageItem(nodeId, "![图像](data:$mime;base64,$data)", streaming),
                                    groupWithTodo = true, historical = historical)
                        }
                        "toolCall" -> block.string("id")?.let { tool(it, block.string("name").orEmpty(),
                            block.obj("arguments") ?: JsonObject(emptyMap()), block.string("intent"), historical) }
                    }
                }
                message.obj("usage")?.let { accumulateUsage(id, it) }
                message.string("provider")?.let { state = state.copy(providerId = it) }
                message.string("model")?.let { state = state.copy(modelId = it) }
                if (final) {
                    if (message.string("stopReason") == "aborted") aborted = true
                    if (message.string("stopReason") == "error") {
                        runError = message.string("errorMessage") ?: "模型请求失败"
                        state = state.copy(error = runError)
                    } else if (message.string("stopReason") != "aborted") {
                        runError = null
                        aborted = false
                    }
                }
            }
            "toolResult" -> {
                val callId = message.string("toolCallId") ?: return
                toolFrame(buildJsonObject {
                    put("type", "tool_execution_end")
                    put("toolCallId", callId)
                    put("toolName", message.string("toolName").orEmpty())
                    put("result", message)
                    put("isError", message.bool("isError") ?: false)
                }, historical)
            }
            "compactionSummary", "branchSummary" -> message.string("summary")?.let {
                put(AssistantMessageItem(id, it))
            }
        }
    }

    private fun tool(callId: String, name: String, args: JsonObject, intent: String? = null, historical: Boolean = false,
        renderExisting: Boolean = true): ToolRecord {
        val key = "omp:$generation:tool:$callId"
        val old = tools[key]
        if (old != null) {
            if (name.isNotEmpty()) old.name = name
            if (args.isNotEmpty()) old.args = args
            if (intent != null) old.intent = intent
            if (renderExisting) renderTool(old, intent = intent)
            return old
        }
        val record = ToolRecord(key, name, args, if (historical) null else eventTimeMillis)
        record.intent = intent
        tools[key] = record
        if (name !in todoNames) {
            ensureActiveGroup(historical)
            val node = toolNode(record, intent = intent)
            val group = activeGroup?.let { item(it) as? StepGroupNodeItem }
            if (group == null) put(node) else put(group.copy(children = group.children + node,
                stats = group.stats.copy(toolCount = group.stats.toolCount + 1,
                    editCount = group.stats.editCount + if (isEdit(name)) 1 else 0,
                    readCount = group.stats.readCount + if (name == "read") 1 else 0)))
        }
        if (name in todoNames && !record.finished && (args["todos"] != null || args["phases"] != null)) applyPlan(args, historical)
        return record
    }

    private fun toolFrame(frame: JsonObject, historical: Boolean = false) {
        val callId = frame.string("toolCallId") ?: return
        val record = tool(callId, frame.string("toolName").orEmpty(), frame.obj("args") ?: JsonObject(emptyMap()),
            frame.string("intent"), historical, renderExisting = false)
        val type = frame.string("type")
        if (type == "tool_execution_start" && !record.executionStarted && !record.finished) {
            record.startedAt = eventTimeMillis
            record.executionStarted = true
        }
        val resultElement = frame["result"] ?: frame["partialResult"] ?: frame["update"]
        val result = resultElement.asObject()
        val details = result?.obj("details")
        if (details != null) record.details = details
        val isError = frame.bool("isError") == true || result?.bool("isError") == true
        // Native toolResult and agent_end snapshots repeat already applied Todo results.
        if (record.name in todoNames && !isError && !record.finished) {
            if (details != null) applyPlan(details, historical)
            if (result != null && (result["todos"] != null || result["phases"] != null)) applyPlan(result, historical)
        }
        if (resultElement != null) {
            val output = when {
                result?.containsKey("content") == true -> contentText(result["content"])
                result?.string("output") != null -> result.string("output").orEmpty()
                result?.string("text") != null -> result.string("text").orEmpty()
                resultElement is JsonPrimitive -> resultElement.contentOrNull.orEmpty()
                else -> ""
            }
            // AgentToolUpdateCallback carries accumulated previews; only explicit delta streams append.
            record.output = if (type == "tool_stream_update" && result?.string("delta") != null) {
                record.output + result.string("delta").orEmpty()
            } else if (output.isNotEmpty() || result?.containsKey("content") == true) output else record.output
        }
        if (type == "tool_execution_end") {
            record.finished = true
            record.failed = record.failed || isError
            if (record.finishedAt == null) record.finishedAt = eventTimeMillis
        }
        val diff = details?.string("diff") ?: result?.string("diff")
        if (diff != null) record.diff = diff
        val elapsed = details?.number("durationMs") ?: result?.number("durationMs") ?: frame.number("elapsedMs")
        if (type == "tool_execution_end" && record.elapsedSec == null) {
            record.elapsedSec = elapsed?.let { (it.coerceAtLeast(0) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
                ?: record.startedAt?.let { seconds(it, record.finishedAt ?: eventTimeMillis) } ?: 0
        }
        val old = item(record.id) as? ToolNodeItem
        var node = toolNode(record, isError, frame.string("intent"))
        if (diff != null) {
            val lines = diff.lines().map { line ->
                when {
                    line.startsWith("+++ ") || line.startsWith("--- ") || line == "+++" || line == "---" -> DiffLine(line)
                    line.startsWith("+") -> DiffLine(line.substring(1), DiffLineKind.ADDED)
                    line.startsWith("-") -> DiffLine(line.substring(1), DiffLineKind.REMOVED)
                    line.startsWith(" ") -> DiffLine(line.substring(1))
                    else -> DiffLine(line)
                }
            }
            val added = lines.count { it.kind == DiffLineKind.ADDED }
            val removed = lines.count { it.kind == DiffLineKind.REMOVED }
            state = state.copy(addedLines = state.addedLines + added - record.added,
                removedLines = state.removedLines + removed - record.removed)
            record.added = added
            record.removed = removed
            val path = details?.string("path") ?: record.args.string("path").orEmpty()
            node = node.copy(detailContent = ToolDetail.DiffBlock(lines),
                previewContent = pathPreview(path, added, removed), fullSheetText = diff, rawCopyText = diff)
        } else if (old?.detailContent is ToolDetail.DiffBlock) {
            node = node.copy(detailContent = old.detailContent, previewContent = old.previewContent,
                fullSheetText = old.fullSheetText, rawCopyText = old.rawCopyText)
        }
        if (record.name !in todoNames) put(node.copy(isExpanded = old?.isExpanded ?: false))
        if (isError) state = state.copy(actionText = "工具执行失败")
    }

    private fun renderTool(record: ToolRecord, intent: String? = null) {
        val old = item(record.id) as? ToolNodeItem ?: return
        val fresh = toolNode(record, old.state == NodeStatus.ERROR, intent = intent)
        put(fresh.copy(state = old.state, elapsedSec = old.elapsedSec, detailContent = old.detailContent,
            rawCopyText = old.rawCopyText, fullSheetText = old.fullSheetText, isExpanded = old.isExpanded,
            previewContent = if (old.detailContent is ToolDetail.DiffBlock) old.previewContent else fresh.previewContent))
    }

    private fun toolNode(record: ToolRecord, error: Boolean = false, intent: String? = null): ToolNodeItem {
        val shell = record.name == "bash" || record.name == "python"
        val suppliedCommand = record.args.string("command") ?: record.args.string("code")
        val argumentsText = if (shell && suppliedCommand != null) "" else record.args.toString()
        val command = suppliedCommand ?: argumentsText
        val path = record.args.string("path") ?: record.args.string("file_path")
        val output = record.output
        val failed = error || record.failed
        val detail = if (shell) ToolDetail.CommandAndOutput(command, output.lines().map { OutputLine(it, failed) })
            else ToolDetail.PlainText(listOf(argumentsText, output).filter { it.isNotEmpty() }.joinToString("\n\n"))
        val details = record.details
        val codePath = details?.string("path") ?: path
        val interpreter = record.args.string("interpreter") ?: when (record.name) {
            "bash", "python" -> record.name
            else -> if (record.args.string("code") != null) record.args.string("language") else null
        }
        val diff = record.diff ?: record.args.string("patch")
        val codeText = when {
            diff != null -> diff
            shell -> record.args.string("code") ?: record.args.string("command")
            record.name in sourceOutputTools -> output.takeIf { it.isNotEmpty() }
            record.name in sourceWriteTools -> record.args.string("content") ?: record.args.string("text") ?: record.args.string("code")
            record.name == "edit" -> record.args.string("newText") ?: record.args.string("new_string") ?: record.args.string("replacement")
            interpreter != null -> record.args.string("code") ?: record.args.string("command")
            else -> record.args.string("code")
        }
        return ToolNodeItem(record.id,
            icon = when { isEdit(record.name) -> "edit_document"; record.name == "read" -> "description"; shell -> "terminal"; else -> "build" },
            leadStyle = if (failed) LeadStyle.WARN else LeadStyle.SOFT,
            titleHtml = escape(intent ?: record.intent ?: record.name),
            state = record.terminalStatus ?: if (!record.finished && record.startedAt != null) NodeStatus.RUNNING else if (failed) NodeStatus.ERROR else NodeStatus.OK,
            elapsedSec = record.elapsedSec ?: record.startedAt?.let { seconds(it, record.finishedAt ?: eventTimeMillis) } ?: 0,
            previewContent = if (path != null) pathPreview(path) else ToolPreview.CommandOneLine(if (shell) command else argumentsText),
            detailContent = detail, rawCopyText = if (output.isEmpty()) command else output,
            fullSheetText = if (shell) "$command\n\n$output" else listOf(argumentsText, output).joinToString("\n\n"),
            startedAtMillis = record.startedAt,
            codeLanguage = if (diff != null) "diff" else details?.string("language") ?: record.args.string("language"),
            codePath = codePath, codeInterpreter = interpreter, codeText = codeText,
            resultText = output.takeIf { it.isNotEmpty() && it != codeText })
    }

    private fun pathPreview(path: String, added: Int? = null, removed: Int? = null): ToolPreview.MidPath {
        val split = path.lastIndexOf('/')
        return ToolPreview.MidPath(if (split >= 0) path.substring(0, split + 1) else "", path.substring(split + 1), added, removed)
    }

    private fun applyPlan(value: JsonObject, historical: Boolean = false) {
        val phases = value.array("phases") ?: value.array("todoPhases")
        val tasks = if (phases != null) phases.flatMap { it.asObject()?.array("tasks").orEmpty() }
            else value.array("todos") ?: value.array("tasks") ?: return
        applyTasks(tasks.mapNotNull { it.asObject() }, historical)
    }

    private fun applyTasks(tasks: List<JsonObject>, historical: Boolean = false) {
        if (tasks.isEmpty() && planId == null) return
        val steps = tasks.map { task ->
            val status = when (task.string("status")) {
                "in_progress" -> StepState.IN_PROGRESS
                "completed", "done", "abandoned" -> StepState.COMPLETED
                else -> StepState.PENDING
            }
            PlanStep(task.string("content") ?: task.string("title").orEmpty(),
                if (status == StepState.COMPLETED) "check_circle" else if (status == StepState.IN_PROGRESS) "pending" else "radio_button_unchecked", status)
        }
        if (steps != planSteps || planId == null) {
            val id = planId?.takeUnless { planAfterUser } ?: generated("plan")
            planId = id
            planSteps = steps
            planAfterUser = false
            val old = item(id) as? PlanNodeItem
            put(PlanNodeItem(id, steps, old?.isExpanded ?: true))
        }
        val title = steps.firstOrNull { it.state == StepState.IN_PROGRESS }?.title
        if (title != activeTitle) closeGroup(NodeStatus.OK)
        ensureActiveGroup(historical)
    }

    private fun ensureActiveGroup(historical: Boolean = false) {
        if (activeGroup != null || !historical && !state.isRunning) return
        val title = planSteps.firstOrNull { it.state == StepState.IN_PROGRESS }?.title ?: return
        val group = StepGroupNodeItem(generated("group"), title,
            state = if (historical) NodeStatus.OK else NodeStatus.RUNNING,
            isExpanded = !historical, startedAtMillis = if (historical) null else eventTimeMillis)
        activeGroup = group.id
        activeTitle = title
        put(group)
    }

    private fun closeGroup(status: NodeStatus) {
        activeGroup?.let { id -> (item(id) as? StepGroupNodeItem)?.let {
            if (it.state == NodeStatus.RUNNING) put(it.copy(state = status, isExpanded = false,
                elapsedSec = it.startedAtMillis?.let { start -> seconds(start) } ?: it.elapsedSec))
        } }
        activeGroup = null
        activeTitle = null
    }

    private fun extensionRequest(frame: JsonObject) {
        val id = frame.string("id") ?: return
        val title = frame.string("title").orEmpty()
        when (frame.string("method")) {
            "select" -> {
                val options = frame.array("options").orEmpty().mapIndexedNotNull { index, option ->
                    askOption(option)?.let { value ->
                        value.copy(description = value.description ?: frame.array("optionDetails")?.getOrNull(index)?.asObject()?.string("description"))
                    }
                }
                if (isApprovalRequest(frame)) {
                    put(ApprovalCardItem(id, title.lineSequence().first(), title, title.substringAfter('\n', "")))
                } else put(AskCardItem(id, title, listOf(AskQuestion(id, title, options = options))))
                waiting()
            }
            "confirm" -> {
                put(ApprovalCardItem(id, title, frame.string("message").orEmpty(), ""))
                waiting()
            }
            "input", "editor" -> {
                put(AskCardItem(id, title, listOf(AskQuestion(id, frame.string("placeholder") ?: frame.string("prefill") ?: title))))
                waiting()
            }
            "ask" -> askRequest(frame, id)
            "cancel" -> {
                val target = frame.string("targetId") ?: return
                state = state.copy(feed = mapFeed(state.feed) {
                    when {
                        it is ApprovalCardItem && it.id == target && it.resolution == null -> it.copy(resolution = ApprovalDecision.DENY)
                        it is AskCardItem && it.id == target && !it.isResolved -> it.copy(cancelled = true)
                        else -> it
                    }
                })
                if (!hasPendingDialog()) state = state.copy(status = SessionStatus.RUNNING, actionText = "正在执行")
            }
            "notify" -> state = state.copy(actionText = frame.string("message") ?: state.actionText)
            "setStatus" -> frame.string("statusText")?.let { state = state.copy(actionText = it) }
            "setTitle" -> state = state.copy(title = title)
        }
    }

    private fun askRequest(frame: JsonObject, id: String) {
        val waitingTool = tools.values.singleOrNull { it.name.equals("ask", ignoreCase = true) && !it.finished }
        if (waitingTool != null) askFeedByTool[waitingTool.id.substringAfterLast(":tool:")] = id
        val questions = frame.array("questions")?.mapNotNull { question ->
            val value = question.asObject() ?: return@mapNotNull null
            val questionId = value.string("id") ?: return@mapNotNull null
            AskQuestion(questionId, value.string("question").orEmpty(), value.string("header"),
                value.array("options").orEmpty().mapNotNull(::askOption), value.bool("multi") == true,
                value.number("recommended")?.toInt())
        } ?: listOf(AskQuestion(id, frame.string("question").orEmpty(),
            options = frame.array("options").orEmpty().mapNotNull(::askOption)))
        if (questions.isEmpty()) return
        val previous = item(id) as? AskCardItem
        put(AskCardItem(id, frame.string("title") ?: "需要你的回答", questions, previous?.answers, previous?.cancelled == true))
        if (previous?.isResolved != true) waiting()
    }

    private fun askOption(value: JsonElement): AskOption? {
        val option = value.asObject()
        val label = option?.string("label") ?: (value as? JsonPrimitive)?.contentOrNull ?: return null
        return AskOption(label, option?.string("description"), option?.string("preview"))
    }

    private fun availableCommands(frame: JsonObject) {
        val commands = frame.array("commands") ?: return
        state = state.copy(slashCommands = commands.mapNotNull { element ->
            val command = element.asObject() ?: return@mapNotNull null
            val name = command.string("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            AgentSlashCommand(name, command.string("source").orEmpty(),
                command.array("aliases").orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
        }, slashCommandsError = null)
    }
    private fun waiting() { state = state.copy(status = SessionStatus.WAITING, actionText = "等待你的回复") }
    private fun hasPendingDialog(): Boolean = allItems(state.feed).any {
        it is ApprovalCardItem && it.resolution == null || it is AskCardItem && !it.isResolved
    }

    private fun queue(frame: JsonObject) {
        val remaining = state.queuedPrompts.toMutableList()
        if (state.queuedPrompts.isNotEmpty()) withdrawnQueue = state.queuedPrompts
        state = state.copy(queuedPrompts = reconcileQueuedPrompts(frame, remaining))
        remaining.forEach { removed -> if (consumedQueue.none { it.id == removed.id }) consumedQueue.add(removed) }
    }

    private fun reconcileQueuedPrompts(frame: JsonObject, available: MutableList<QueuedPrompt>): List<QueuedPrompt> = buildList {
        for (kind in PromptQueue.entries) {
            for (value in frame.array(kind.wireName).orEmpty()) {
                val text = (value as? JsonPrimitive)?.contentOrNull ?: value.asObject()?.string("text") ?: continue
                val index = available.indexOfFirst { it.queue == kind && it.text == text }
                add(if (index >= 0) available.removeAt(index) else QueuedPrompt(generated("queue"), text, queue = kind))
            }
        }
    }

    private fun response(frame: JsonObject) {
        if (frame.bool("success") == false) {
            val error = frame.string("error") ?: "RPC 请求失败"
            state = if (frame.string("command") == "get_available_commands") state.copy(slashCommandsError = error)
                else state.copy(error = error)
            return
        }
        val data = frame.obj("data") ?: return
        when (frame.string("command")) {
            "get_available_commands" -> availableCommands(data)
            "get_entries" -> {
                data.array("entries")?.forEach { entry -> entry.asObject()?.let { value ->
                    value.string("id")?.let { entries[it] = value }
                } }
                leafEntryId = data.string("leafId")
                markCompactedBoundary()
            }
            "abort_and_restore_queue" -> {
                val available = (state.queuedPrompts + withdrawnQueue + consumedQueue).distinctBy { it.id }.toMutableList()
                val restored = reconcileQueuedPrompts(data, available)
                state = state.copy(queuedPrompts = restored)
                withdrawnQueue = restored
                val restoredIds = restored.mapTo(mutableSetOf()) { it.id }
                consumedQueue.removeAll { it.id in restoredIds }
            }
            "get_state" -> {
                data.obj("model")?.let(::model)
                thinking(data.string("thinkingLevel"))
                data.string("sessionName")?.let { state = state.copy(title = it) }
                data.obj("queuedMessages")?.let(::queue)
                if (data["todoPhases"] != null) applyPlan(data)
                data.obj("contextUsage")?.let(::context)
                if (data.bool("isStreaming") == true && !hasPendingDialog()) state = state.copy(status = SessionStatus.RUNNING)
                if (data.bool("isCompacting") == true) state = state.copy(actionText = "正在压缩上下文")
                if (data.bool("isSettled") == true && state.isRunning && !hasPendingDialog()) settleStatus()
            }
            "get_session_stats" -> {
                val tokens = data.obj("tokens")
                if (tokens != null) state = state.copy(usage = state.usage.copy(
                    inputTokens = tokens.number("input") ?: 0, outputTokens = tokens.number("output") ?: 0,
                    cacheReadTokens = tokens.number("cacheRead") ?: 0, cacheWriteTokens = tokens.number("cacheWrite") ?: 0,
                    cost = data.decimal("cost") ?: state.usage.cost))
                data.obj("contextUsage")?.let(::context)
            }
            "set_model", "cycle_model" -> model(data.obj("model") ?: data)
            "set_thinking_level", "cycle_thinking_level" -> thinking(data.string("level") ?: data.string("thinkingLevel"))
            "set_todos" -> applyPlan(data)
            "get_messages", "get_branch_messages", "get_messages_page" -> {
                data.array("messages")?.forEach { element -> element.asObject()?.let { message ->
                    val matching = messages.entries.lastOrNull { sameMessage(it.value, message) }?.key
                    if (matching == null) snapshot(generated("message"), message, true, historical = true)
                } }
                if (!state.isRunning) {
                    activeGroup = null
                    activeTitle = null
                }
            }
            "compact" -> {
                state = state.copy(actionText = "上下文压缩完成")
                data.number("tokensAfter")?.let { state = state.copy(usage = state.usage.copy(contextTokens = it)) }
                compactionResult(data)
            }
        }
    }

    private fun model(value: JsonObject) {
        state = state.copy(providerId = value.string("provider") ?: state.providerId,
            modelId = value.string("id") ?: value.string("modelId") ?: state.modelId, modelLoading = false,
            usage = state.usage.copy(contextWindow = value.number("contextWindow") ?: state.usage.contextWindow))
    }

    internal fun previewModel(providerId: String, modelId: String, thinkingLevel: String?) {
        state = state.copy(providerId = providerId, modelId = modelId, modelLoading = false)
        thinking(thinkingLevel)
    }

    private fun thinking(value: String?) {
        val level = when (value?.lowercase()) {
            "off", "none" -> ThinkingLevel.NONE
            "minimal", "low" -> ThinkingLevel.LOW
            "medium" -> ThinkingLevel.MEDIUM
            "high" -> ThinkingLevel.HIGH
            "xhigh" -> ThinkingLevel.XHIGH
            "max" -> ThinkingLevel.MAX
            else -> return // `auto` is a selector, not one of the public UI's six fixed levels.
        }
        state = state.copy(thinkingLevel = level)
    }

    private fun context(value: JsonObject) {
        state = state.copy(usage = state.usage.copy(contextTokens = value.number("tokens") ?: state.usage.contextTokens,
            contextWindow = value.number("contextWindow") ?: state.usage.contextWindow))
    }

    private fun accumulateUsage(id: String, value: JsonObject) {
        val next = UsageInfo(value.number("input") ?: 0, value.number("output") ?: 0,
            value.number("cacheRead") ?: 0, value.number("cacheWrite") ?: 0,
            value.number("contextTokens") ?: ((value.number("input") ?: 0) + (value.number("output") ?: 0) +
                (value.number("cacheRead") ?: 0) + (value.number("cacheWrite") ?: 0)),
            cost = value.obj("cost")?.decimal("total") ?: value.decimal("cost") ?: 0.0)
        val previous = usageByMessage.put(id, next) ?: UsageInfo()
        if (next == previous) return
        val usage = state.usage
        state = state.copy(usage = usage.copy(inputTokens = usage.inputTokens + next.inputTokens - previous.inputTokens,
            outputTokens = usage.outputTokens + next.outputTokens - previous.outputTokens,
            cacheReadTokens = usage.cacheReadTokens + next.cacheReadTokens - previous.cacheReadTokens,
            cacheWriteTokens = usage.cacheWriteTokens + next.cacheWriteTokens - previous.cacheWriteTokens,
            contextTokens = next.contextTokens, cost = usage.cost + next.cost - previous.cost))
    }

    private fun thinkingNode(id: String, streaming: Boolean, text: String): ThinkingNodeItem {
        val old = item(id) as? ThinkingNodeItem
        val start = old?.startedAtMillis ?: blockStartedAt[id] ?: if (streaming) {
            eventTimeMillis.also { blockStartedAt[id] = it }
        } else null
        val elapsed = if (old != null && !old.isStreaming) old.elapsedSec else start?.let { seconds(it) } ?: 0
        return ThinkingNodeItem(id, streaming, elapsed, text, old?.isExpanded ?: false, start)
    }

    private fun reconcileEndMessages(frame: JsonObject) {
        frame.array("messages")?.forEach { element ->
            val message = element.asObject() ?: return@forEach
            val matching = messages.entries.lastOrNull { sameMessage(it.value, message) }?.key
            snapshot(matching ?: generated("message"), message, true)
        }
    }

    private fun compactionResult(result: JsonObject) {
        firstKeptEntryId = result.string("firstKeptEntryId") ?: return
        markCompactedBoundary()
    }

    private fun markCompactedBoundary() {
        val firstKept = firstKeptEntryId ?: return
        val branch = mutableListOf<JsonObject>()
        val visited = mutableSetOf<String>()
        var cursor = leafEntryId
        while (cursor != null && visited.add(cursor)) {
            val entry = entries[cursor] ?: return
            branch.add(entry)
            cursor = entry.string("parentId")
        }
        branch.reverse()
        val keptIndex = branch.indexOfFirst { it.string("id") == firstKept }
        if (keptIndex < 0) return
        val compactedIds = mutableSetOf<String>()
        branch.take(keptIndex).forEach { entry ->
            val message = entry.obj("message") ?: return@forEach
            val match = messages.entries.lastOrNull { sameMessage(it.value, message) } ?: return@forEach
            val id = match.key
            when (message.string("role")) {
                "user" -> compactedIds.add(userFeedIds[id] ?: id)
                "assistant" -> message.array("content")?.forEachIndexed { index, element ->
                    val type = element.asObject()?.string("type")
                    if (type == "text" || type == "thinking" || type == "image") compactedIds.add(blockId(id, index))
                    element.asObject()?.takeIf { it.string("type") == "toolCall" }?.string("id")?.let { callId ->
                        if (branch.none { it.obj("message")?.string("toolCallId") == callId }) {
                            compactedIds.add("${id.substringBefore(":message:")}:tool:$callId")
                        }
                    }
                }
                "toolResult" -> message.string("toolCallId")?.let { compactedIds.add("${id.substringBefore(":message:")}:tool:$it") }
                else -> compactedIds.add(id)
            }
        }
        // A group containing a retained child stays visible in full; never hide retained content.
        var boundary: String? = null
        for (node in state.feed) {
            if (node is PlanNodeItem) continue
            val compacted = node.id in compactedIds || node is StepGroupNodeItem && node.children.isNotEmpty() &&
                allItems(node.children).filterNot { it is StepGroupNodeItem }.all { it.id in compactedIds }
            if (!compacted) break
            boundary = node.id
        }
        if (boundary != null) state = state.copy(compactedThroughId = boundary)
    }

    private fun sameMessage(prior: JsonObject, message: JsonObject): Boolean =
        prior == message || prior.string("role") == message.string("role") && (
            message.string("responseId") != null && prior.string("responseId") == message.string("responseId") ||
                message.number("timestamp") != null && prior.number("timestamp") == message.number("timestamp") && prior["content"] == message["content"])

    private fun finish() {
        val status = when { aborted -> NodeStatus.STOPPED; runError != null -> NodeStatus.ERROR; else -> NodeStatus.OK }
        tools.values.forEach { record ->
            if (record.finished) return@forEach
            record.finished = true
            record.finishedAt = eventTimeMillis
            record.elapsedSec = record.startedAt?.let { seconds(it) } ?: 0
            record.terminalStatus = status
        }
        closeGroup(status)
        state = state.copy(feed = mapFeed(state.feed) {
            if (it is ThinkingNodeItem || it is AssistantMessageItem) completedBlocks.add(it.id)
            when (it) {
                is ThinkingNodeItem -> if (it.isStreaming) it.copy(isStreaming = false,
                    elapsedSec = it.startedAtMillis?.let { start -> seconds(start) } ?: it.elapsedSec) else it
                is AssistantMessageItem -> if (it.isStreaming) it.copy(isStreaming = false) else it
                is ToolNodeItem -> if (it.state == NodeStatus.RUNNING) it.copy(state = status,
                    elapsedSec = it.startedAtMillis?.let { start -> seconds(start) } ?: it.elapsedSec) else it
                else -> it
            }
        })
        settleStatus()
        currentMessage = null
        messageStack.clear()
        consumedQueue.clear()
    }

    private fun settleStatus() {
        state = state.copy(status = when { aborted -> SessionStatus.STOPPED; runError != null -> SessionStatus.ERROR; else -> SessionStatus.DONE },
            elapsedSec = startedAt?.let { seconds(it) } ?: state.elapsedSec,
            actionText = when { aborted -> "已停止"; runError != null -> "执行失败"; else -> "已完成" }, error = runError)
        startedAt = null
    }

    private fun promptResult(frame: JsonObject) {
        val invoked = frame.bool("agentInvoked") != false
        when (frame.string("status")) {
            "error" -> {
                runError = frame.obj("error")?.string("message") ?: runError ?: "请求失败"
                state = state.copy(error = runError)
            }
            "aborted" -> if (!invoked || state.isRunning) aborted = true
            "completed" -> if (!invoked) {
                runError = null
                aborted = false
                state = state.copy(error = null)
            }
        }
        // A local slash command can complete while unrelated agent/background work is live.
        // Only the server's settled predicate proves it is safe to stop the session indicator.
        if (frame.bool("sessionSettled") == true && (!invoked || state.isRunning) && !hasPendingDialog()) settleStatus()
    }

    private fun ensureFeedLocations() {
        if (indexedFeed === state.feed) return
        feedLocations.clear()
        indexFeedLocations(state.feed, null)
        indexedFeed = state.feed
    }

    private fun indexFeedLocations(feed: List<FeedItem>, parent: FeedLocation?) {
        for (index in feed.indices) {
            val node = feed[index]
            val location = FeedLocation(index, parent, node)
            feedLocations[node.id] = location
            if (node is StepGroupNodeItem) indexFeedLocations(node.children, location)
        }
    }

    private fun removeFeedLocations(node: FeedItem) {
        feedLocations.remove(node.id)
        if (node is StepGroupNodeItem) node.children.forEach(::removeFeedLocations)
    }

    private fun item(id: String): FeedItem? {
        ensureFeedLocations()
        return feedLocations[id]?.node
    }

    /** Copies only the changed node's ancestor lists; location records never escape the reducer. */
    private fun replaceFeedItem(location: FeedLocation, value: FeedItem) {
        val original = location.node
        if (original == value) return
        val previousChildren = (original as? StepGroupNodeItem)?.children.orEmpty()
        val children = (value as? StepGroupNodeItem)?.children.orEmpty()
        if (previousChildren !== children) {
            // Remove all changed positions before adding them, so a reorder cannot remove a new entry.
            for (index in previousChildren.indices) {
                if (previousChildren[index] !== children.getOrNull(index)) removeFeedLocations(previousChildren[index])
            }
            for (index in children.indices) {
                if (children[index] !== previousChildren.getOrNull(index)) {
                    val child = children[index]
                    val childLocation = FeedLocation(index, location, child)
                    feedLocations[child.id] = childLocation
                    if (child is StepGroupNodeItem) indexFeedLocations(child.children, childLocation)
                }
            }
        }
        location.node = value
        var current = location
        var replacement = value
        while (true) {
            val parent = current.parent
            val siblings = (parent?.node as? StepGroupNodeItem)?.children ?: state.feed
            val feed = siblings.toMutableList().apply { set(current.index, replacement) }
            if (parent == null) {
                indexedFeed = feed
                state = state.copy(feed = feed)
                return
            }
            replacement = (parent.node as StepGroupNodeItem).copy(children = feed)
            parent.node = replacement
            current = parent
        }
    }

    private fun put(value: FeedItem, groupWithTodo: Boolean = value is ThinkingNodeItem, historical: Boolean = false) {
        ensureFeedLocations()
        // Existing blocks keep their first location across task changes and full snapshots.
        feedLocations[value.id]?.let { replaceFeedItem(it, value); return }
        if (groupWithTodo) ensureActiveGroup(historical)
        if (value is UserMessageItem) {
            planAfterUser = true
            reserveUserPosition(value.id)
            // Anchored insertion can shift existing positions; the state setter invalidates the index.
            state = state.copy(feed = placeUserMessage(state.feed, value, userPositions.getValue(value.id), userPositions))
            return
        }
        val groupLocation = if (groupWithTodo) activeGroup?.let { feedLocations[it] } else null
        val group = groupLocation?.node as? StepGroupNodeItem
        if (groupLocation != null && group != null) {
            replaceFeedItem(groupLocation, group.copy(children = group.children + value))
        } else {
            val feed = state.feed + value
            val location = FeedLocation(state.feed.size, null, value)
            feedLocations[value.id] = location
            if (value is StepGroupNodeItem) indexFeedLocations(value.children, location)
            indexedFeed = feed
            state = state.copy(feed = feed)
        }
    }
    private fun mapFeed(feed: List<FeedItem>, transform: (FeedItem) -> FeedItem): List<FeedItem> {
        var changed: MutableList<FeedItem>? = null
        for (index in feed.indices) {
            val original = feed[index]
            val node = if (original is StepGroupNodeItem) {
                val children = mapFeed(original.children, transform)
                if (children === original.children) original else original.copy(children = children)
            } else original
            val mapped = transform(node)
            if (mapped !== original) {
                if (changed == null) changed = feed.toMutableList()
                changed[index] = mapped
            }
        }
        return changed ?: feed
    }
    private fun allItems(feed: List<FeedItem>): Sequence<FeedItem> = sequence {
        for (item in feed) {
            yield(item)
            if (item is StepGroupNodeItem) yieldAll(allItems(item.children))
        }
    }
    private fun contentText(value: JsonElement?): String = when (value) {
        is JsonPrimitive -> value.contentOrNull.orEmpty()
        is JsonArray -> value.mapNotNull { it.asObject()?.takeIf { block -> block.string("type") == "text" }?.string("text") }.joinToString("\n")
        else -> ""
    }
    private fun seconds(since: Long, until: Long = eventTimeMillis) = ((until - since).coerceAtLeast(0) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    private fun isEdit(name: String) = name == "edit" || name == "write" || name == "apply_patch" || name == "ast_edit"
    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private val todoNames = setOf("todo", "todo_write", "todowrite")
    private val sourceOutputTools = setOf("read", "grep", "find")
    private val sourceWriteTools = setOf("write", "create")
    private val imageMimeTypes = setOf("image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp", "image/svg+xml")
}

private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject
private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
private fun JsonObject.decimal(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
