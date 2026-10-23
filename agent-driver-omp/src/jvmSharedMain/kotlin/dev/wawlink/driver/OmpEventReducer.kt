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
 * Native UI card ids deliberately retain request ids for responses. Public AskCardItem
 * supports a single answer/free text, so upstream multi-select questions cannot expose
 * a multi-selection widget. Blocked/abandoned todo distinctions and automatic effort have
 * no exact public enum equivalent; blocked stays pending, abandoned completes, auto preserves
 * the last effective fixed thinking level.
 */
class OmpEventReducer(initial: ChatSessionUiState) {
    var state: ChatSessionUiState = initial
        private set

    private var generation = 0L
    private var sequence = 0L
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
    private var planId: String? = null
    private var activeGroup: String? = null
    private var activeTitle: String? = null
    private var startedAt: Long? = null
    private var runError: String? = null
    private var aborted = false

    private data class ToolRecord(
        val id: String,
        var name: String,
        var args: JsonObject,
        val startedAt: Long,
        var output: String = "",
        var finished: Boolean = false,
        var finishedAt: Long? = null,
        var failed: Boolean = false,
        var intent: String? = null,
        var added: Int = 0,
        var removed: Int = 0,
    )

    fun update(transform: (ChatSessionUiState) -> ChatSessionUiState): ChatSessionUiState {
        state = transform(state)
        return state
    }
    internal fun hasConfirmedUser(id: String): Boolean = id in reconciledUsers

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
                planId = null
            }
            "agent_start" -> {
                if (startedAt == null) {
                    planId = null
                    activeGroup = null
                    activeTitle = null
                    startedAt = System.currentTimeMillis()
                    runError = null
                    aborted = false
                    state = state.copy(elapsedSec = 0)
                }
                state = state.copy(status = SessionStatus.RUNNING, actionText = "正在执行", error = null)
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
            if (message != null) snapshot(id, message, true)
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
                if (thinking && !narration) put(ThinkingNodeItem(nodeId, streaming,
                    elapsedSec = thinkingElapsed(nodeId, streaming), text = text,
                    isExpanded = (old as? ThinkingNodeItem)?.isExpanded ?: false))
                else put(AssistantMessageItem(nodeId, text, streaming))
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

    private fun snapshot(id: String, message: JsonObject, final: Boolean) {
        messages[id] = message
        when (message.string("role")) {
            "user" -> {
                val text = contentText(message["content"])
                if (item(id) == null) {
                    val existing = allItems(state.feed).filterIsInstance<UserMessageItem>().firstOrNull {
                        it.text == text && it.id !in reconciledUsers
                    }
                    if (existing != null) {
                        reconciledUsers.add(existing.id)
                        // Wire start/end share an id, but the pool owns the original attachments and timestamp.
                        reconciledUsers.add(id)
                        userFeedIds[id] = existing.id
                    } else if (id !in reconciledUsers) {
                        val index = consumedQueue.indexOfFirst { it.text == text }
                        val queued = if (index >= 0) consumedQueue.removeAt(index) else null
                        val feedId = queued?.id ?: id
                        put(UserMessageItem(feedId, text, attachments = queued?.attachments.orEmpty(),
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
                            put(AssistantMessageItem(nodeId, block.string("thinking").orEmpty(), streaming))
                        } else {
                            val old = item(nodeId) as? ThinkingNodeItem
                            put(ThinkingNodeItem(nodeId, streaming, thinkingElapsed(nodeId, streaming),
                                block.string("thinking").orEmpty(), old?.isExpanded ?: false))
                        }
                        "text" -> put(AssistantMessageItem(nodeId, block.string("text").orEmpty(), streaming))
                        "toolCall" -> block.string("id")?.let { tool(it, block.string("name").orEmpty(),
                            block.obj("arguments") ?: JsonObject(emptyMap()), block.string("intent")) }
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
                })
            }
            "compactionSummary", "branchSummary" -> message.string("summary")?.let {
                put(AssistantMessageItem(id, it))
            }
        }
    }

    private fun tool(callId: String, name: String, args: JsonObject, intent: String? = null): ToolRecord {
        val key = "omp:$generation:tool:$callId"
        val old = tools[key]
        if (old != null) {
            if (name.isNotEmpty()) old.name = name
            if (args.isNotEmpty()) old.args = args
            if (intent != null) old.intent = intent
            renderTool(old, intent = intent)
            return old
        }
        val record = ToolRecord(key, name, args, System.currentTimeMillis())
        record.intent = intent
        tools[key] = record
        if (name !in todoNames) {
            val node = toolNode(record, intent = intent)
            val group = activeGroup?.let { item(it) as? StepGroupNodeItem }
            if (group == null) put(node) else put(group.copy(children = group.children + node,
                stats = group.stats.copy(toolCount = group.stats.toolCount + 1,
                    editCount = group.stats.editCount + if (isEdit(name)) 1 else 0,
                    readCount = group.stats.readCount + if (name == "read") 1 else 0)))
        }
        if (name in todoNames && (args["todos"] != null || args["phases"] != null)) applyPlan(args)
        return record
    }

    private fun toolFrame(frame: JsonObject) {
        val callId = frame.string("toolCallId") ?: return
        val record = tool(callId, frame.string("toolName").orEmpty(), frame.obj("args") ?: JsonObject(emptyMap()), frame.string("intent"))
        val type = frame.string("type")
        val resultElement = frame["result"] ?: frame["partialResult"] ?: frame["update"]
        val result = resultElement.asObject()
        val details = result?.obj("details")
        val isError = frame.bool("isError") == true || result?.bool("isError") == true
        // Native toolResult and agent_end snapshots repeat already applied Todo results.
        if (record.name in todoNames && !isError && !record.finished) {
            if (details != null) applyPlan(details)
            if (result != null && (result["todos"] != null || result["phases"] != null)) applyPlan(result)
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
            if (record.finishedAt == null) record.finishedAt = System.currentTimeMillis()
        }
        val diff = details?.string("diff") ?: result?.string("diff")
        val elapsed = details?.number("durationMs") ?: result?.number("durationMs") ?: frame.number("elapsedMs")
        val old = item(record.id) as? ToolNodeItem
        var node = toolNode(record, isError, elapsed?.let { (it / 1000).toInt() }, frame.string("intent"))
        if (diff != null) {
            val lines = diff.lines().map { line ->
                DiffLine(line, when {
                    line.startsWith("+++") || line.startsWith("---") -> DiffLineKind.CONTEXT
                    line.startsWith("+") -> DiffLineKind.ADDED
                    line.startsWith("-") -> DiffLineKind.REMOVED
                    else -> DiffLineKind.CONTEXT
                })
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

    private fun toolNode(record: ToolRecord, error: Boolean = false, elapsed: Int? = null, intent: String? = null): ToolNodeItem {
        val command = record.args.string("command") ?: record.args.string("code") ?: record.args.toString()
        val path = record.args.string("path") ?: record.args.string("file_path")
        val shell = record.name == "bash" || record.name == "python"
        val output = record.output
        val failed = error || record.failed
        val detail = if (shell) ToolDetail.CommandAndOutput(command, output.lines().map { OutputLine(it, failed) })
            else ToolDetail.PlainText(listOf(record.args.toString(), output).filter { it.isNotEmpty() }.joinToString("\n\n"))
        return ToolNodeItem(record.id,
            icon = when { isEdit(record.name) -> "edit_document"; record.name == "read" -> "description"; shell -> "terminal"; else -> "build" },
            leadStyle = if (failed) LeadStyle.WARN else LeadStyle.SOFT,
            titleHtml = escape(intent ?: record.intent ?: record.name),
            state = if (!record.finished) NodeStatus.RUNNING else if (failed) NodeStatus.ERROR else NodeStatus.OK,
            elapsedSec = elapsed ?: seconds(record.startedAt, record.finishedAt ?: System.currentTimeMillis()),
            previewContent = if (path != null) pathPreview(path) else ToolPreview.CommandOneLine(if (shell) command else record.args.toString()),
            detailContent = detail, rawCopyText = if (output.isEmpty()) command else output,
            fullSheetText = if (shell) "$command\n\n$output" else listOf(record.args.toString(), output).joinToString("\n\n"))
    }

    private fun pathPreview(path: String, added: Int? = null, removed: Int? = null): ToolPreview.MidPath {
        val split = path.lastIndexOf('/')
        return ToolPreview.MidPath(if (split >= 0) path.substring(0, split + 1) else "", path.substring(split + 1), added, removed)
    }

    private fun applyPlan(value: JsonObject) {
        val phases = value.array("phases") ?: value.array("todoPhases")
        val tasks = if (phases != null) phases.flatMap { it.asObject()?.array("tasks").orEmpty() }
            else value.array("todos") ?: value.array("tasks") ?: return
        applyTasks(tasks.mapNotNull { it.asObject() })
    }

    private fun applyTasks(tasks: List<JsonObject>) {
        if (tasks.isEmpty() && planId == null) return
        val id = planId ?: generated("plan").also { planId = it }
        val steps = tasks.map { task ->
            val status = when (task.string("status")) {
                "in_progress" -> StepState.IN_PROGRESS
                "completed", "done", "abandoned" -> StepState.COMPLETED
                else -> StepState.PENDING
            }
            PlanStep(task.string("content") ?: task.string("title").orEmpty(),
                if (status == StepState.COMPLETED) "check_circle" else if (status == StepState.IN_PROGRESS) "pending" else "radio_button_unchecked", status)
        }
        put(PlanNodeItem(id, steps))
        val title = steps.firstOrNull { it.state == StepState.IN_PROGRESS }?.title
        if (title != activeTitle) {
            closeGroup(NodeStatus.OK)
            activeTitle = title
            if (title != null) {
                val group = StepGroupNodeItem(generated("group"), title)
                activeGroup = group.id
                put(group)
            }
        }
    }

    private fun closeGroup(status: NodeStatus) {
        activeGroup?.let { id -> (item(id) as? StepGroupNodeItem)?.let { put(it.copy(state = status, isExpanded = false,
            elapsedSec = it.children.filterIsInstance<ToolNodeItem>().sumOf { child -> child.elapsedSec })) } }
        activeGroup = null
        activeTitle = null
    }

    private fun extensionRequest(frame: JsonObject) {
        val id = frame.string("id") ?: return
        val title = frame.string("title").orEmpty()
        when (frame.string("method")) {
            "select" -> {
                val options = frame.array("options").orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                if (isApprovalRequest(frame)) {
                    put(ApprovalCardItem(id, title.lineSequence().first(), title, title.substringAfter('\n', "")))
                } else put(AskCardItem(id, title, title, options))
                waiting()
            }
            "confirm" -> {
                put(ApprovalCardItem(id, title, frame.string("message").orEmpty(), ""))
                waiting()
            }
            "input", "editor" -> {
                put(AskCardItem(id, title, frame.string("placeholder") ?: frame.string("prefill") ?: title, emptyList()))
                waiting()
            }
            "ask" -> askRequest(frame, id)
            "cancel" -> {
                val target = frame.string("targetId") ?: return
                state = state.copy(feed = mapFeed(state.feed) {
                    when {
                        it is ApprovalCardItem && it.id == target && it.resolution == null -> it.copy(resolution = ApprovalDecision.DENY)
                        it is AskCardItem && it.selectedAnswer == null && (it.id == target || it.id.startsWith("$target:q:")) -> it.copy(selectedAnswer = "已取消")
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
        val questions = frame.array("questions")
        if (questions == null) {
            put(AskCardItem(id, frame.string("title") ?: "需要你的回答", frame.string("question").orEmpty(),
                frame.array("options").orEmpty().mapNotNull { optionLabel(it) }))
        } else questions.forEachIndexed { index, question ->
            val value = question.asObject() ?: return@forEachIndexed
            put(AskCardItem("$id:q:$index", value.string("header") ?: "需要你的回答", value.string("question").orEmpty(),
                value.array("options").orEmpty().mapNotNull { optionLabel(it) }))
        }
        if (questions == null || questions.isNotEmpty()) waiting()
    }

    private fun optionLabel(value: JsonElement) = value.asObject()?.string("label") ?: (value as? JsonPrimitive)?.contentOrNull
    private fun waiting() { state = state.copy(status = SessionStatus.WAITING, actionText = "等待你的回复") }
    private fun hasPendingDialog(): Boolean = allItems(state.feed).any {
        it is ApprovalCardItem && it.resolution == null || it is AskCardItem && it.selectedAnswer == null
    }

    private fun queue(frame: JsonObject) {
        val texts = (frame.array("steering").orEmpty() + frame.array("followUp").orEmpty())
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val remaining = state.queuedPrompts.toMutableList()
        if (state.queuedPrompts.isNotEmpty()) withdrawnQueue = state.queuedPrompts
        state = state.copy(queuedPrompts = texts.map { text ->
            val index = remaining.indexOfFirst { it.text == text }
            if (index >= 0) remaining.removeAt(index) else QueuedPrompt(generated("queue"), text)
        })
        remaining.forEach { removed -> if (consumedQueue.none { it.id == removed.id }) consumedQueue.add(removed) }
    }

    private fun response(frame: JsonObject) {
        if (frame.bool("success") == false) {
            state = state.copy(error = frame.string("error") ?: "RPC 请求失败")
            return
        }
        val data = frame.obj("data") ?: return
        when (frame.string("command")) {
            "get_entries" -> {
                data.array("entries")?.forEach { entry -> entry.asObject()?.let { value ->
                    value.string("id")?.let { entries[it] = value }
                } }
                leafEntryId = data.string("leafId")
                markCompactedBoundary()
            }
            "abort_and_restore_queue" -> {
                val available = (state.queuedPrompts + withdrawnQueue + consumedQueue).distinctBy { it.id }.toMutableList()
                val restored = (data.array("steering").orEmpty() + data.array("followUp").orEmpty()).mapNotNull { value ->
                    val text = value.asObject()?.string("text") ?: return@mapNotNull null
                    val index = available.indexOfFirst { it.text == text }
                    if (index >= 0) available.removeAt(index) else QueuedPrompt(generated("queue"), text)
                }
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
            "get_messages", "get_branch_messages", "get_messages_page" -> data.array("messages")?.forEach { element ->
                element.asObject()?.let { snapshot(generated("message"), it, true) }
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
            modelId = value.string("id") ?: value.string("modelId") ?: state.modelId,
            usage = state.usage.copy(contextWindow = value.number("contextWindow") ?: state.usage.contextWindow))
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

    private fun thinkingElapsed(id: String, streaming: Boolean): Int {
        val old = item(id) as? ThinkingNodeItem
        if (!streaming && old != null && !old.isStreaming) return old.elapsedSec
        val start = blockStartedAt.getOrPut(id) { System.currentTimeMillis() }
        return seconds(start)
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
                    if (type == "text" || type == "thinking") compactedIds.add(blockId(id, index))
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
        closeGroup(status)
        state = state.copy(feed = mapFeed(state.feed) {
            when (it) {
                is ThinkingNodeItem -> it.copy(isStreaming = false)
                is AssistantMessageItem -> it.copy(isStreaming = false)
                is ToolNodeItem -> if (it.state == NodeStatus.RUNNING) it.copy(state = status) else it
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

    private fun item(id: String): FeedItem? = allItems(state.feed).firstOrNull { it.id == id }
    private fun put(value: FeedItem) {
        var found = false
        val feed = mapFeed(state.feed) { if (it.id == value.id) { found = true; value } else it }
        val group = if (!found && value is ThinkingNodeItem) activeGroup?.let { item(it) as? StepGroupNodeItem } else null
        state = state.copy(feed = when {
            found -> feed
            value is UserMessageItem -> {
                reserveUserPosition(value.id)
                placeUserMessage(state.feed, value, userPositions.getValue(value.id), userPositions)
            }
            group != null -> mapFeed(feed) {
                if (it is StepGroupNodeItem && it.id == group.id) it.copy(children = it.children + value) else it
            }
            else -> feed + value
        })
    }
    private fun mapFeed(feed: List<FeedItem>, transform: (FeedItem) -> FeedItem): List<FeedItem> = feed.map {
        transform(if (it is StepGroupNodeItem) it.copy(children = mapFeed(it.children, transform)) else it)
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
    private fun seconds(since: Long, until: Long = System.currentTimeMillis()) = ((until - since).coerceAtLeast(0) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    private fun isEdit(name: String) = name == "edit" || name == "write" || name == "apply_patch" || name == "ast_edit"
    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private val todoNames = setOf("todo", "todo_write", "todowrite")
}

private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject
private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
private fun JsonObject.decimal(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
