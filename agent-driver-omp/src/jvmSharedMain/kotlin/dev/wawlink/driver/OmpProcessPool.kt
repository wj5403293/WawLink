package dev.wawlink.driver

import dev.wawlink.protocol.*
import dev.wawlink.runtime.ContainerConfig
import dev.wawlink.runtime.ContainerRuntime
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Owns only the RPC children it starts. Creating a controller never starts a container/process. */
class OmpProcessPool(val runtime: ContainerRuntime, val dataDir: File, private val resolvConf: StateFlow<String?>? = null) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = LinkedHashMap<String, OmpSession>()
    private val providerRepositories = LinkedHashMap<String, OmpProviderRepository>()
    private val settingsRepositories = LinkedHashMap<String, OmpSettingsRepository>()
    private var closed = false

    @Synchronized
    fun session(id: String, containerId: String, workspace: String, title: String = "新会话"): AgentSessionController {
        check(!closed) { "进程池已关闭" }
        require(id.isNotBlank()) { "会话 ID 不能为空" }
        val existing = sessions[id]
        if (existing != null) {
            require(existing.containerId == containerId && existing.workspace == normalizeGuest(workspace)) {
                "更改容器或工作区请使用 relocateSession"
            }
            existing.rename(title)
            return existing
        }
        return OmpSession(id, containerId, normalizeGuest(workspace), title).also { sessions[id] = it }
    }

    suspend fun fork(sourceId: String, newId: String, containerId: String, workspace: String, title: String): AgentSessionController = withContext(Dispatchers.IO) {
        require(sourceId != newId) { "分支必须使用新的会话 ID" }
        val source = synchronized(this@OmpProcessPool) { sessions[sourceId] } ?: loadExisting(sourceId)
        require(!metadataFile(newId).exists()) { "分支会话已存在" }
        source.exclusive {
            // An orderly EOF flushes the upstream writer before copying history/artifacts.
            source.park()
            val destinationWorkspace = normalizeGuest(workspace)
            val destination = ownedSessionDirectory(containerId, newId)
            require(!destination.exists()) { "分支目标目录已存在" }
            val sourceDirectory = ownedSessionDirectory(source.containerId, sourceId)
            if (sourceDirectory.exists()) copyOwnedTree(sourceDirectory, destination)
            val history = newestHistory(destination)
            if (history != null) rewriteSessionHeader(history, destinationWorkspace, title, UUID.randomUUID().toString(), source.sessionFile)
            val sourceJournal = journalFile(sourceId)
            if (sourceJournal.exists()) sourceJournal.copyTo(journalFile(newId).also { it.parentFile.mkdirs() }, overwrite = false)
            writeMetadata(newId, containerId, destinationWorkspace, title, history?.let { guestSessionDirectory(newId) + "/" + it.name })
            session(newId, containerId, destinationWorkspace, title)
        }
    }

    /** Relocation retains the transcript, artifact tree and GUI feed; it never copies credentials. */
    suspend fun relocateSession(id: String, containerId: String, workspace: String, title: String): AgentSessionController = withContext(Dispatchers.IO) {
        val source = synchronized(this@OmpProcessPool) { sessions[id] } ?: loadExisting(id)
        val nextWorkspace = normalizeGuest(workspace)
        if (source.containerId == containerId && source.workspace == nextWorkspace) {
            source.rename(title)
            return@withContext source
        }
        source.stopAccepting()
        source.park()
        val oldDirectory = ownedSessionDirectory(source.containerId, id)
        val newDirectory = ownedSessionDirectory(containerId, id)
        if (oldDirectory.canonicalFile != newDirectory.canonicalFile && oldDirectory.exists()) {
            require(!newDirectory.exists()) { "目标容器已有同名会话历史" }
            copyOwnedTree(oldDirectory, newDirectory)
        }
        newestHistory(newDirectory)?.let { rewriteSessionHeader(it, nextWorkspace, title) }
        val newPath = newestHistory(newDirectory)?.let { guestSessionDirectory(id) + "/" + it.name }
        writeMetadata(id, containerId, nextWorkspace, title, newPath)
        synchronized(this@OmpProcessPool) { sessions.remove(id) }
        source.dispose()
        if (oldDirectory.canonicalFile != newDirectory.canonicalFile && oldDirectory.exists()) deleteOwnedTree(oldDirectory)
        session(id, containerId, nextWorkspace, title)
    }

    suspend fun deleteSession(id: String) = withContext(Dispatchers.IO) {
        val source = synchronized(this@OmpProcessPool) { sessions.remove(id) }
            ?: if (metadataFile(id).exists()) loadExisting(id) else null
        if (source != null) {
            source.stopAccepting()
            source.park()
            source.dispose()
            synchronized(this@OmpProcessPool) { sessions.remove(id) }
            val owned = ownedSessionDirectory(source.containerId, id)
            if (owned.exists()) deleteOwnedTree(owned)
        }
        for (file in listOf(journalFile(id), metadataFile(id))) check(!file.exists() || file.delete()) { "无法删除会话记录：${file.name}" }
    }

    /** Releases a child but intentionally preserves its durable session for later resume. */
    @Synchronized
    fun closeSession(id: String) {
        sessions[id]?.requestPark()
    }

    @Synchronized
    fun providers(containerId: String): ProviderConfigRepository {
        check(!closed)
        return providerRepositories.getOrPut(containerId) {
            OmpProviderRepository({ agentDirectory(containerId) }, scope) { _, _ -> configurationChanged(containerId, null, null) }
        }
    }

    @Synchronized
    fun settings(containerId: String): AgentSettingsRepository {
        check(!closed)
        return settingsRepositories.getOrPut(containerId) {
            OmpSettingsRepository({ agentDirectory(containerId) }, scope) { key, value -> configurationChanged(containerId, key, value) }
        }
    }

    private suspend fun configurationChanged(containerId: String, key: String?, value: Any?) {
        val affected = synchronized(this) { sessions.values.filter { it.containerId == containerId } }
        affected.forEach { it.applyConfiguration(key, value) }
    }

    private fun loadExisting(id: String): OmpSession {
        val metadata = DriverJson.parseToJsonElement(metadataFile(id).readText()).jsonObject
        return session(id, metadata.string("containerId"), metadata.string("workspace"), metadata.string("title", "新会话")) as OmpSession
    }

    private fun config(id: String): ContainerConfig = runtime.containers.value.firstOrNull { it.config.id == id }?.config
        ?: error("找不到容器：$id")

    private fun agentDirectory(containerId: String): File = confined(File(config(containerId).rootfsPath), "root/.omp/agent")
    private fun ownedSessionDirectory(containerId: String, id: String): File = confined(agentDirectory(containerId), "wawlink/sessions/${safeId(id)}")
    private fun guestSessionDirectory(id: String) = "/root/.omp/agent/wawlink/sessions/${safeId(id)}"
    private fun metadataFile(id: String) = File(dataDir, "omp-sessions/${safeId(id)}.json")
    private fun journalFile(id: String) = File(dataDir, "omp-sessions/${safeId(id)}.jsonl")

    private fun writeMetadata(id: String, containerId: String, workspace: String, title: String, sessionFile: String?) {
        atomicWrite(metadataFile(id), buildJsonObject {
            put("id", id); put("containerId", containerId); put("workspace", workspace); put("title", title)
            sessionFile?.let { put("sessionFile", it) }
        }.toString())
    }

    private fun resolveWorkspace(containerId: String, workspace: String, write: Boolean = false): File {
        val configuration = config(containerId)
        val guest = normalizeGuest(workspace)
        val mount = configuration.mounts.filter { guest == it.target.trimEnd('/') || guest.startsWith(it.target.trimEnd('/') + "/") }
            .maxByOrNull { it.target.length }
        if (mount != null) {
            require(!write || mount.readWrite) { "工作区挂载为只读" }
            val relative = guest.removePrefix(mount.target.trimEnd('/')).trimStart('/')
            return confined(File(mount.source), relative)
        }
        return confined(File(configuration.rootfsPath), guest.trimStart('/'))
    }

    override fun close() {
        val active = synchronized(this) {
            if (closed) return
            closed = true
            sessions.values.toList().also { sessions.clear() }
        }
        runBlocking(Dispatchers.IO) { active.map { async { it.stopAccepting(); it.park(); it.dispose() } }.awaitAll() }
        synchronized(this) {
            providerRepositories.values.forEach { it.close() }
            settingsRepositories.values.forEach { it.close() }
        }
        scope.cancel()
    }

    private inner class OmpSession(val id: String, val containerId: String, val workspace: String, title: String) : AgentSessionController {
        private val ownerJob = SupervisorJob(scope.coroutineContext[Job])
        private val sessionScope = CoroutineScope(scope.coroutineContext + ownerJob)
        private val stateMutex = Mutex()
        private val lifecycleMutex = Mutex()
        private val writeMutex = Mutex()
        private val actions = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        private lateinit var actionJob: Job
        private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
        private val sequence = AtomicLong()
        private val stopGeneration = AtomicLong()
        private val requests = LinkedHashMap<String, JsonObject>()
        private val askAnswers = LinkedHashMap<String, MutableMap<Int, String>>()
        private val eventJournal = journalFile(id)
        private val reducer = OmpEventReducer(ChatSessionUiState(id = id, title = title, workspace = workspace))
        private val mutableState: MutableStateFlow<ChatSessionUiState>
        override val state: StateFlow<ChatSessionUiState>
        @Volatile private var process: Process? = null
        private var startedResolvConf: String? = null
        @Volatile private var backendSettled = true
        private var processJob: Job? = null
        private var heartbeat: Job? = null
        private var ready: CompletableDeferred<JsonObject>? = null
        @Volatile private var stopping: CompletableDeferred<Unit>? = null
        @Volatile private var disposed = false
        private var restartRequired = false
        private var hasThinkingPreference = false
        private var lastHeartbeatNanos = System.nanoTime()
        private var startedNanos = System.nanoTime()
        private val stderr = StringBuilder()
        @Volatile var sessionFile: String? = null
            private set

        init {
            mutableState = MutableStateFlow(reducer.state)
            state = mutableState.asStateFlow()
            actionJob = sessionScope.launch {
                try {
                    stateMutex.withLock {
                        replayJournal()
                        // No child from a previous host lifetime is owned by this controller.
                        reducer.update { it.copy(id = id, title = title, workspace = workspace,
                            feed = if (it.isRunning) stopFeed(it.feed) else it.feed,
                            status = if (it.isRunning) SessionStatus.STOPPED else it.status, actionText = "准备就绪") }
                        mutableState.value = reducer.state
                    }
                    val metadata = metadataFile(id)
                    if (metadata.exists()) sessionFile = DriverJson.parseToJsonElement(metadata.readText()).jsonObject["sessionFile"]?.jsonPrimitive?.contentOrNull
                    saveMetadata()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { fail(failure) }
                for (action in actions) {
                    try { stopping?.await(); action() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { fail(failure) }
                }
            }
        }

        suspend fun <T> exclusive(block: suspend () -> T): T {
            check(!disposed) { "会话已关闭" }
            val result = CompletableDeferred<T>()
            actions.send {
                try { result.complete(block()) }
                catch (failure: Throwable) { result.completeExceptionally(failure) }
            }
            return result.await()
        }

        fun requestPark() = submit { park() }

        suspend fun stopAccepting() {
            disposed = true
            actions.close()
            actionJob.cancelAndJoin()
        }

        private fun submit(action: suspend () -> Unit) {
            if (!disposed) actions.trySend(action)
        }

        override fun sendPrompt(text: String, attachments: List<AttachmentFile>) {
            if (text.isBlank() && attachments.isEmpty()) return
            submit {
                val promptGeneration = stopGeneration.get()
                val prepared = prepareAttachments(text, attachments)
                if (state.value.isRunning || !backendSettled) enqueue(prepared.first, prepared.second, prepared.third)
                else {
                    if (restartRequired || (process != null && startedResolvConf != resolvConf?.value)) park()
                    ensureStarted()
                    if (promptGeneration != stopGeneration.get()) return@submit
                    if (state.value.isRunning || !backendSettled) {
                        enqueue(prepared.first, prepared.second, prepared.third)
                        return@submit
                    }
                    local(buildJsonObject {
                        put("action", "user"); put("text", prepared.first); put("timestamp", System.currentTimeMillis())
                        put("attachments", attachmentsJson(prepared.second))
                    })
                    mutate { it.copy(status = SessionStatus.RUNNING, error = null, actionText = "正在处理", elapsedSec = 0) }
                    startedNanos = System.nanoTime()
                    command("prompt", buildJsonObject { put("message", prepared.first); if (prepared.third.isNotEmpty()) put("images", prepared.third) }, timeoutMs = 180_000)
                }
            }
        }

        override fun enqueuePrompt(text: String) {
            if (text.isBlank()) return
            submit {
                val promptGeneration = stopGeneration.get()
                if (!state.value.isRunning && backendSettled) {
                    if (restartRequired || (process != null && startedResolvConf != resolvConf?.value)) park()
                    ensureStarted()
                    if (promptGeneration != stopGeneration.get()) return@submit
                    if (state.value.isRunning || !backendSettled) {
                        enqueue(text, emptyList(), JsonArray(emptyList()))
                        return@submit
                    }
                    local(buildJsonObject { put("action", "user"); put("text", text); put("timestamp", System.currentTimeMillis()) })
                    mutate { it.copy(status = SessionStatus.RUNNING, error = null, actionText = "正在处理", elapsedSec = 0) }
                    startedNanos = System.nanoTime()
                    command("prompt", buildJsonObject { put("message", text) }, timeoutMs = 180_000)
                } else enqueue(text, emptyList(), JsonArray(emptyList()))
            }
        }

        private suspend fun enqueue(text: String, attachments: List<AttachmentFile>, images: JsonArray) {
            ensureStarted()
            val queuedId = UUID.randomUUID().toString()
            local(buildJsonObject { put("action", "queue_add"); put("queueId", queuedId); put("text", text); put("attachments", attachmentsJson(attachments)) })
            try {
                command("follow_up", buildJsonObject { put("message", text); if (images.isNotEmpty()) put("images", images) }, timeoutMs = 180_000)
            } catch (failure: Exception) {
                local(buildJsonObject { put("action", "queue_remove"); put("queueId", queuedId) })
                throw failure
            }
        }

        override fun updateQueuedPrompt(id: String, newText: String) {
            if (newText.isBlank()) return
            submit {
                val item = state.value.queuedPrompts.firstOrNull { it.id == id } ?: return@submit
                if (process == null) {
                    val prepared = prepareAttachments(newText, item.attachments)
                    local(buildJsonObject {
                        put("action", "queue_edit"); put("queueId", id); put("text", prepared.first)
                        put("attachments", attachmentsJson(prepared.second))
                    })
                    return@submit
                }
                val removed = removeNativeQueued(item.text)
                check(removed) { "该消息已开始执行，无法编辑" }
                local(buildJsonObject { put("action", "queue_remove"); put("queueId", id) })
                val prepared = prepareAttachments(newText, item.attachments)
                enqueue(prepared.first, prepared.second, prepared.third)
            }
        }

        override fun removeQueuedPrompt(id: String) {
            submit {
                val item = state.value.queuedPrompts.firstOrNull { it.id == id } ?: return@submit
                if (process == null || removeNativeQueued(item.text)) local(buildJsonObject { put("action", "queue_remove"); put("queueId", id) })
                else error("该消息已开始执行，无法移除")
            }
        }

        private suspend fun removeNativeQueued(text: String): Boolean {
            for (queue in listOf("followUp", "steering")) {
                val result = command("remove_queued_message", buildJsonObject { put("message", text); put("queue", queue) })
                if (result["data"]?.jsonObject?.get("removed")?.jsonPrimitive?.booleanOrNull == true) return true
            }
            return false
        }

        override fun stopExecution() {
            stopGeneration.incrementAndGet()
            val barrier = CompletableDeferred<Unit>()
            stopping = barrier
            sessionScope.launch {
                try {
                    if (process != null) command("abort_and_restore_queue", timeoutMs = 20_000)
                    val withdrawn = state.value.queuedPrompts.toList()
                    park()
                    for (queued in withdrawn) local(buildJsonObject {
                        put("action", "queue_add"); put("queueId", queued.id); put("text", queued.text)
                        put("attachments", attachmentsJson(queued.attachments))
                    })
                    local(buildJsonObject { put("action", "stopped") })
                } catch (failure: Exception) {
                    if (failure !is CancellationException) { park(); fail(failure) }
                }
                finally {
                    barrier.complete(Unit)
                    if (stopping === barrier) stopping = null
                }
            }
        }

        override fun selectModel(providerId: String, modelId: String) {
            submit {
                if (process == null) {
                    // Selection is durable without spawning; the next prompt starts with this identity.
                    local(buildJsonObject { put("action", "model"); put("provider", providerId); put("model", modelId) })
                } else {
                    command("set_model", buildJsonObject { put("provider", providerId); put("modelId", modelId) })
                    local(buildJsonObject { put("action", "model"); put("provider", providerId); put("model", modelId) })
                    command("get_state")
                }
            }
        }

        override fun selectThinkingLevel(level: ThinkingLevel) {
            submit {
                if (process != null) command("set_thinking_level", buildJsonObject { put("level", thinkingWire(level)) })
                local(buildJsonObject { put("action", "thinking"); put("level", level.name) })
            }
        }

        override fun toggleExecutionMode(autoApprove: Boolean) {
            submit {
                local(buildJsonObject { put("action", "auto"); put("enabled", autoApprove) })
                // --approval-mode is a session-local runtime override, not a globally persisted setting.
                // There is no set_approval_mode RPC: keep the current run safe, apply at its next idle start.
                restartRequired = true
                if (!state.value.isRunning && backendSettled) park()
                else mutate { it.copy(actionText = "执行模式将在本轮结束后生效") }
            }
        }

        override fun respondApproval(nodeId: String, decision: ApprovalDecision) {
            sessionScope.launch {
                try {
                    val request = stateMutex.withLock { requests[nodeId] } ?: error("审批请求已失效")
                    val response = buildJsonObject {
                        put("type", "extension_ui_response"); put("id", nodeId)
                        when (request.string("method")) {
                            "confirm" -> put("confirmed", decision != ApprovalDecision.DENY)
                            "select" -> {
                                val options = request["options"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
                                val value = when (decision) {
                                    ApprovalDecision.DENY -> options.firstOrNull { it == "Deny" }
                                    ApprovalDecision.ALLOW_ONCE -> options.firstOrNull { it == "Approve Once" } ?: options.firstOrNull { it == "Approve" }
                                    ApprovalDecision.ALLOW_ALWAYS_IN_SESSION -> options.firstOrNull { it.startsWith("Approve ") && it.endsWith(" for this session") }
                                } ?: error(if (decision == ApprovalDecision.ALLOW_ALWAYS_IN_SESSION) "此安全审批不允许会话级授权，请选择仅允许一次" else "上游未提供对应审批选项")
                                put("value", value)
                            }
                            else -> error("不支持的审批方法")
                        }
                    }
                    writeFrame(response)
                    local(buildJsonObject { put("action", "approval"); put("nodeId", nodeId); put("decision", decision.name) })
                    stateMutex.withLock { requests.remove(nodeId) }
                } catch (failure: Exception) { if (failure !is CancellationException) fail(failure) }
            }
        }

        override fun respondAsk(nodeId: String, selectedOption: String) {
            sessionScope.launch {
                try {
                    val requestId = nodeId.substringBefore(":q:")
                    val request = stateMutex.withLock { requests[requestId] } ?: error("提问请求已失效")
                    if (request.string("method") == "ask") {
                        val questions = request["questions"]!!.jsonArray
                        val index = nodeId.substringAfter(":q:", "0").toInt()
                        require(index in questions.indices)
                        val answers = stateMutex.withLock {
                            askAnswers.getOrPut(requestId) { LinkedHashMap() }.also { it[index] = selectedOption }.toMap()
                        }
                        local(buildJsonObject { put("action", "ask"); put("nodeId", nodeId); put("answer", selectedOption) })
                        if (answers.size == questions.size) {
                            writeFrame(buildJsonObject {
                                put("type", "extension_ui_response"); put("id", requestId)
                                put("answers", buildJsonArray {
                                    questions.forEachIndexed { questionIndex, element ->
                                        val question = element.jsonObject
                                        val answer = answers.getValue(questionIndex)
                                        val choices = question["options"]?.jsonArray.orEmpty().map { it.jsonObject.string("label") }
                                        add(buildJsonObject {
                                            put("id", question.string("id"))
                                            put("selectedOptions", if (answer in choices) JsonArray(listOf(JsonPrimitive(answer))) else JsonArray(emptyList()))
                                            if (answer !in choices) put("customInput", answer)
                                        })
                                    }
                                })
                            })
                            stateMutex.withLock { requests.remove(requestId); askAnswers.remove(requestId) }
                        }
                    } else {
                        writeFrame(buildJsonObject { put("type", "extension_ui_response"); put("id", requestId); put("value", selectedOption) })
                        local(buildJsonObject { put("action", "ask"); put("nodeId", nodeId); put("answer", selectedOption) })
                        stateMutex.withLock { requests.remove(requestId) }
                    }
                } catch (failure: Exception) { if (failure !is CancellationException) fail(failure) }
            }
        }

        override fun executeSlashCommand(command: String, args: String) {
            val name = command.trim().removePrefix("/")
            require(name.isNotBlank() && !name.contains('\n'))
            sendPrompt("/$name${if (args.isBlank()) "" else " $args"}")
        }

        override fun listWorkspaceFiles(relativePath: String): Flow<List<WorkspaceFileItem>> = flow {
            require(!File(relativePath).isAbsolute && relativePath.split('/').none { it == ".." }) { "目录不能越过工作区" }
            val root = resolveWorkspace(containerId, workspace)
            val directory = confined(root, relativePath)
            require(directory.isDirectory) { "目录不存在" }
            emit(directory.listFiles().orEmpty().mapNotNull { file ->
                val canonical = runCatching { confined(root, file.relativeTo(root).path) }.getOrNull() ?: return@mapNotNull null
                WorkspaceFileItem(file.name, canonical.relativeTo(root.canonicalFile).invariantSeparatorsPath, canonical.isDirectory, if (canonical.isFile) canonical.length() else 0)
            }.sortedWith(compareByDescending<WorkspaceFileItem> { it.isDirectory }.thenBy { it.name.lowercase() }))
        }.flowOn(Dispatchers.IO)

        fun rename(title: String) {
            if (title == state.value.title) return
            submit {
                local(buildJsonObject { put("action", "title"); put("title", title) })
                if (process != null) command("set_session_name", buildJsonObject { put("name", title) })
                saveMetadata()
            }
        }

        suspend fun applyConfiguration(key: String?, value: Any?) {
            submit {
                val live = when (key) {
                    "defaultThinkingLevel" -> if (value.toString() in setOf("inherit", "off", "minimal", "low", "medium", "high", "xhigh", "max"))
                        "set_thinking_level" to buildJsonObject { put("level", value.toString()) } else null
                    "steeringMode" -> "set_steering_mode" to buildJsonObject { put("mode", value.toString()) }
                    "followUpMode" -> "set_follow_up_mode" to buildJsonObject { put("mode", value.toString()) }
                    "interruptMode" -> "set_interrupt_mode" to buildJsonObject { put("mode", value.toString()) }
                    "compaction.enabled" -> "set_auto_compaction" to buildJsonObject { put("enabled", value as Boolean) }
                    "retry.enabled" -> "set_auto_retry" to buildJsonObject { put("enabled", value as Boolean) }
                    "providers.cacheWarming" -> "set_cache_warming" to buildJsonObject { put("mode", value.toString()) }
                    else -> null
                }
                if (process != null && live != null) command(live.first, live.second)
                else {
                    // Config's native watcher handles dynamic fields; a restart additionally covers
                    // setup-only model registry/tools without interrupting an in-flight run.
                    restartRequired = true
                    if (!state.value.isRunning && backendSettled) park()
                }
            }
        }

        private suspend fun ensureStarted() = lifecycleMutex.withLock {
            check(!disposed) { "会话已关闭" }
            if (process?.isAlive == true) return@withLock
            process?.let { stopProcess(it) }
            val directory = ownedSessionDirectory(containerId, id)
            directory.mkdirs()
            val workspaceDirectory = resolveWorkspace(containerId, workspace)
            if (!workspaceDirectory.exists()) check(resolveWorkspace(containerId, workspace, write = true).mkdirs()) { "无法创建工作区" }
            val history = newestHistory(directory)
            sessionFile = history?.let { guestSessionDirectory(id) + "/" + it.name }
            val args = mutableListOf("omp", "--mode", "rpc-ui", "--cwd", workspace, "--session-dir", guestSessionDirectory(id), "--approval-mode", if (state.value.autoApprove) "yolo" else "always-ask")
            sessionFile?.let { args += listOf("--resume", it) }
            // Pass only model identity already selected by the user; credentials stay in the container.
            if (state.value.providerId.isNotBlank() && state.value.modelId.isNotBlank()) args += listOf("--provider", state.value.providerId, "--model", state.value.modelId)
            prepareOmpConfiguration { agentDirectory(containerId) }
            confined(agentDirectory(containerId), "wawlink-model-payload.mjs")
            args += listOf("--extension", ModelPayloadExtension.install(agentDirectory(containerId)))
            if (hasThinkingPreference) args += listOf("--thinking", thinkingWire(state.value.thinkingLevel))
            val restoredQueue = state.value.queuedPrompts.toList()
            val startupGeneration = stopGeneration.get()
            val startReady = CompletableDeferred<JsonObject>()
            ready = startReady
            synchronized(stderr) { stderr.clear() }
            // Bun snapshots DNS servers; resume a fresh process on the next idle send after a network change.
            startedResolvConf = resolvConf?.value
            val child = runtime.openProcess(containerId, args, workspace, mapOf("PI_NO_PTY" to "1", "PI_NO_TITLE" to "1"))
            process = child
            restartRequired = false
            val childJob = SupervisorJob(ownerJob)
            processJob = childJob
            val childScope = CoroutineScope(sessionScope.coroutineContext + childJob)
            childScope.launch { drainStderr(child) }
            childScope.launch { readOutput(child) }
            try {
                val greeting = withTimeout(180_000) { startReady.await() }
                if (greeting["supportedProtocolVersions"]?.jsonArray?.any { it.jsonPrimitive.intOrNull == 2 } == true) command("negotiate_protocol", buildJsonObject { put("protocolVersion", 2) })
                command("set_event_filter", buildJsonObject { put("events", JsonNull); put("messageUpdates", "delta") })
                command("set_ask_dialog", buildJsonObject { put("enabled", true) })
                command("set_session_name", buildJsonObject { put("name", state.value.title) })
                command("get_state")
                command("get_session_stats")
                for ((index, queued) in restoredQueue.withIndex()) {
                    if (startupGeneration != stopGeneration.get()) {
                        for (unsubmitted in restoredQueue.drop(index)) local(buildJsonObject {
                            put("action", "queue_add"); put("queueId", unsubmitted.id); put("text", unsubmitted.text)
                            put("attachments", attachmentsJson(unsubmitted.attachments))
                        })
                        break
                    }
                    val prepared = prepareAttachments(queued.text, queued.attachments)
                    local(buildJsonObject {
                        put("action", "queue_add"); put("queueId", queued.id); put("text", prepared.first)
                        put("attachments", attachmentsJson(prepared.second))
                    })
                    command("follow_up", buildJsonObject { put("message", prepared.first); if (prepared.third.isNotEmpty()) put("images", prepared.third) }, timeoutMs = 180_000)
                }
                lastHeartbeatNanos = System.nanoTime()
                heartbeat = childScope.launch {
                    try {
                        while (isActive && child.isAlive) {
                            delay(1_000)
                            if (state.value.isRunning) mutate { it.copy(elapsedSec = ((System.nanoTime() - startedNanos) / 1_000_000_000).toInt()) }
                            if (System.nanoTime() - lastHeartbeatNanos >= 5_000_000_000L) {
                                command("get_state", timeoutMs = 25_000, heartbeat = true)
                                lastHeartbeatNanos = System.nanoTime()
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        sessionScope.launch { park(); fail(failure) }
                    }
                }
                saveMetadata()
            } catch (failure: Exception) {
                process = null
                childJob.cancel()
                stopProcess(child)
                throw IllegalStateException("OMP 启动失败：${failure.message}${stderrText()}", failure)
            }
        }

        private suspend fun command(type: String, parameters: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 45_000, heartbeat: Boolean = false): JsonObject {
            val requestId = if (heartbeat) "hb-${sequence.incrementAndGet()}" else "req-${sequence.incrementAndGet()}"
            val completion = CompletableDeferred<JsonObject>()
            pending[requestId] = completion
            try {
                writeFrame(buildJsonObject { put("id", requestId); put("type", type); parameters.forEach { (key, value) -> put(key, value) } })
                val response = try { withTimeout(timeoutMs) { completion.await() } }
                catch (failure: TimeoutCancellationException) { throw IllegalStateException("OMP 请求超时：$type", failure) }
                check(response["success"]?.jsonPrimitive?.booleanOrNull != false) { response.string("error", "OMP 请求失败：$type") }
                return response
            } finally { pending.remove(requestId) }
        }

        private suspend fun writeFrame(frame: JsonObject) = writeMutex.withLock {
            val child = process ?: error("OMP 进程未启动")
            check(child.isAlive) { "OMP 进程已退出${stderrText()}" }
            val encoded = frame.toString().toByteArray(Charsets.UTF_8)
            child.outputStream.write(encoded)
            child.outputStream.write('\n'.code)
            child.outputStream.flush()
        }

        private suspend fun readOutput(child: Process) {
            val decoder = OmpFrameDecoder()
            var failure: Throwable? = null
            try {
                child.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (currentCoroutineContext().isActive) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        val physical = DriverJson.parseToJsonElement(line) as? JsonObject ?: error("OMP 输出不是 JSON 对象")
                        val frame = decoder.push(physical) ?: continue
                        receive(frame)
                    }
                }
                if (process === child) {
                    val status = if (child.waitFor(250, TimeUnit.MILLISECONDS)) "，退出码 ${child.exitValue()}" else ""
                    failure = IllegalStateException("OMP 输出流已关闭$status${stderrText()}")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (problem: Exception) { failure = problem }
            finally {
                if (process === child && failure != null) {
                    process = null
                    backendSettled = true
                    ready?.completeExceptionally(failure)
                    pending.values.forEach { it.completeExceptionally(failure) }
                    heartbeat?.cancel()
                    stopProcess(child)
                    fail(failure)
                }
            }
        }

        private suspend fun receive(frame: JsonObject) {
            val type = frame.string("type")
            if (type == "agent_start") { startedNanos = System.nanoTime(); backendSettled = false }
            if (type == "session_settled") backendSettled = true
            if (type == "prompt_result") frame["sessionSettled"]?.jsonPrimitive?.booleanOrNull?.let { backendSettled = it }
            if (type == "extension_ui_request") {
                stateMutex.withLock {
                    when (frame.string("method")) {
                        "select", "confirm", "ask", "input", "editor" -> requests[frame.string("id")] = frame
                        "cancel" -> { requests.remove(frame.string("targetId")); askAnswers.remove(frame.string("targetId")) }
                    }
                }
            }
            if (type == "response" && frame.string("command") == "get_state") {
                frame["data"]?.jsonObject?.get("isSettled")?.jsonPrimitive?.booleanOrNull?.let { backendSettled = it }
                frame["data"]?.jsonObject?.get("sessionFile")?.jsonPrimitive?.contentOrNull?.let { path ->
                    require(path.startsWith(guestSessionDirectory(id) + "/")) { "OMP 会话离开受管目录，拒绝覆盖其他会话" }
                    if (sessionFile != path) { sessionFile = path; saveMetadata() }
                }
            }
            stateMutex.withLock {
                // Heartbeat snapshots still reduce state but need not inflate the durable feed journal.
                if (!(type == "response" && frame.string("id").startsWith("hb-"))) appendJournal(frame)
                mutableState.value = reducer.reduce(frame)
            }
            if (type == "agent_end" || type == "session_settled") local(buildJsonObject {
                put("action", "elapsed"); put("seconds", ((System.nanoTime() - startedNanos) / 1_000_000_000).toInt())
            })
            if (type == "ready") ready?.complete(frame)
            if (type == "response") pending[frame.string("id")]?.complete(frame)
            if (type == "session_info_update") saveMetadata()
            if (type == "model_changed" || type == "config_update") sessionScope.launch {
                runCatching { command("get_state") }.onFailure { if (it !is CancellationException && process != null) fail(it) }
            }
            if (type == "auto_compaction_start" || type == "auto_compaction_end" || (type == "response" && frame.string("command") == "compact")) {
                sessionScope.launch {
                    runCatching { command("get_entries") }.onFailure { if (it !is CancellationException && process != null) fail(it) }
                }
            }
            if (type == "session_settled" || (type == "agent_end" && frame["isTerminal"]?.jsonPrimitive?.booleanOrNull != false)) {
                sessionScope.launch {
                    runCatching { command("get_session_stats") }.onFailure { if (it !is CancellationException && process != null) fail(it) }
                }
            }
        }

        private suspend fun drainStderr(child: Process) {
            try {
                child.errorStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val buffer = CharArray(2048)
                    while (currentCoroutineContext().isActive) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        synchronized(stderr) {
                            stderr.append(buffer, 0, count)
                            if (stderr.length > 16_384) stderr.delete(0, stderr.length - 16_384)
                        }
                    }
                }
            } catch (_: java.io.IOException) { /* Closing this owned child closes its diagnostic stream. */ }
        }

        private fun stderrText(): String = synchronized(stderr) { stderr.toString().trim().let { if (it.isEmpty()) "" else "\n$it" } }

        suspend fun park() = lifecycleMutex.withLock {
            val child = process ?: return@withLock
            process = null
            backendSettled = true
            heartbeat?.cancel(); heartbeat = null
            // stdin EOF is the actual RPC shutdown protocol; do not invent a shutdown command.
            writeMutex.withLock { runCatching { child.outputStream.close() } }
            if (!child.waitFor(8, TimeUnit.SECONDS)) stopProcess(child)
            processJob?.cancel(); processJob = null
            val closedFailure = IllegalStateException("OMP 会话进程已关闭")
            pending.values.forEach { it.completeExceptionally(closedFailure) }
            pending.clear()
            stateMutex.withLock { requests.clear(); askAnswers.clear() }
            if (state.value.isRunning) local(buildJsonObject { put("action", "stopped") })
            saveMetadata()
        }

        fun dispose() {
            disposed = true
            actions.close()
            ownerJob.cancel()
        }

        private suspend fun fail(problem: Throwable) {
            local(buildJsonObject {
                put("action", if (process?.isAlive == true) "operation_error" else "error")
                put("message", problem.message ?: problem.javaClass.simpleName)
            })
        }

        private suspend fun mutate(transform: (ChatSessionUiState) -> ChatSessionUiState) = stateMutex.withLock {
            mutableState.value = reducer.update(transform)
        }

        private suspend fun local(payload: JsonObject) = stateMutex.withLock {
            val frame = buildJsonObject {
                put("type", "wawlink_local"); put("localId", UUID.randomUUID().toString()); payload.forEach { (key, value) -> put(key, value) }
            }
            appendJournal(frame)
            applyLocal(frame)
            mutableState.value = reducer.state
        }

        private fun applyLocal(frame: JsonObject) {
            when (frame.string("action")) {
                "user" -> reducer.update { it.copy(feed = it.feed + UserMessageItem(frame.string("localId"), frame.string("text"), parseAttachments(frame["attachments"]), frame["timestamp"]?.jsonPrimitive?.longOrNull ?: 0)) }
                "queue_add" -> reducer.update { current -> current.copy(queuedPrompts =
                    current.queuedPrompts.filterNot { it.id == frame.string("queueId") } +
                        QueuedPrompt(frame.string("queueId"), frame.string("text"), parseAttachments(frame["attachments"]))) }
                "queue_edit" -> reducer.update { current -> current.copy(queuedPrompts = current.queuedPrompts.map { queued ->
                    if (queued.id == frame.string("queueId")) queued.copy(text = frame.string("text"), attachments = parseAttachments(frame["attachments"])) else queued
                }) }
                "queue_remove" -> reducer.update { it.copy(queuedPrompts = it.queuedPrompts.filterNot { queued -> queued.id == frame.string("queueId") }) }
                "title" -> reducer.update { it.copy(title = frame.string("title")) }
                "model" -> reducer.update { it.copy(providerId = frame.string("provider"), modelId = frame.string("model")) }
                "thinking" -> {
                    hasThinkingPreference = true
                    reducer.update { it.copy(thinkingLevel = ThinkingLevel.valueOf(frame.string("level"))) }
                }
                "auto" -> reducer.update { it.copy(autoApprove = frame["enabled"]!!.jsonPrimitive.boolean) }
                "stopped" -> reducer.update { it.copy(status = SessionStatus.STOPPED, actionText = "已停止", feed = stopFeed(it.feed)) }
                "error" -> reducer.update { it.copy(status = SessionStatus.ERROR, error = frame.string("message"), actionText = "执行失败", feed = stopFeed(it.feed)) }
                "operation_error" -> reducer.update { it.copy(error = frame.string("message")) }
                "elapsed" -> reducer.update { it.copy(elapsedSec = frame["seconds"]!!.jsonPrimitive.int) }
                "approval" -> reducer.update { current ->
                    val feed = mapFeed(current.feed) { node -> if (node is ApprovalCardItem && node.id == frame.string("nodeId")) node.copy(resolution = ApprovalDecision.valueOf(frame.string("decision"))) else node }
                    current.copy(feed = feed, status = if (hasUnresolvedDialog(feed)) SessionStatus.WAITING else SessionStatus.RUNNING, error = null, actionText = "继续执行")
                }
                "ask" -> reducer.update { current ->
                    val feed = mapFeed(current.feed) { node -> if (node is AskCardItem && node.id == frame.string("nodeId")) node.copy(selectedAnswer = frame.string("answer")) else node }
                    current.copy(feed = feed, status = if (hasUnresolvedDialog(feed)) SessionStatus.WAITING else SessionStatus.RUNNING, error = null)
                }
            }
        }

        private fun replayJournal() {
            val file = eventJournal
            if (!file.exists()) return
            file.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) {
                        try {
                            val frame = DriverJson.parseToJsonElement(line).jsonObject
                            if (frame.string("type") == "wawlink_local") applyLocal(frame) else reducer.reduce(frame)
                        } catch (failure: Exception) {
                            // An interrupted final append must not erase the readable transcript prefix.
                            reducer.update { it.copy(error = "历史记录部分损坏：${failure.message}", status = SessionStatus.ERROR) }
                        }
                    }
                }
            }
        }

        private fun appendJournal(frame: JsonObject) {
            val file = eventJournal
            file.parentFile.mkdirs()
            file.appendText(frame.toString() + "\n", Charsets.UTF_8)
        }

        private fun saveMetadata() = writeMetadata(id, containerId, workspace, reducer.state.title, sessionFile)

        private fun prepareAttachments(text: String, attachments: List<AttachmentFile>): Triple<String, List<AttachmentFile>, JsonArray> {
            if (attachments.isEmpty()) return Triple(text, emptyList(), JsonArray(emptyList()))
            val workspaceRoot = resolveWorkspace(containerId, workspace, write = true)
            workspaceRoot.mkdirs()
            val transferred = ArrayList<AttachmentFile>(attachments.size)
            val images = ArrayList<JsonElement>()
            for (attachment in attachments) {
                val file: File
                val guestPath: String
                if (attachment.isWorkspaceFile) {
                    val relative = if (attachment.path.startsWith(workspace.trimEnd('/') + "/")) attachment.path.removePrefix(workspace.trimEnd('/') + "/") else attachment.path
                    require(!File(relative).isAbsolute) { "附件必须位于当前工作区" }
                    file = confined(workspaceRoot, relative)
                    guestPath = workspace.trimEnd('/') + "/" + file.relativeTo(workspaceRoot.canonicalFile).invariantSeparatorsPath
                } else {
                    val source = File(attachment.path)
                    require(source.isFile && source.canRead()) { "无法读取附件：${attachment.name}" }
                    val safeName = attachment.name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").ifBlank { "attachment" }
                    file = confined(workspaceRoot, ".wawlink/attachments/${safeId(attachment.id)}/$safeName")
                    file.parentFile.mkdirs()
                    if (source.canonicalFile != file.canonicalFile) source.inputStream().use { input -> file.outputStream().use { input.copyTo(it) } }
                    guestPath = workspace.trimEnd('/') + "/" + file.relativeTo(workspaceRoot.canonicalFile).invariantSeparatorsPath
                }
                require(file.isFile) { "附件不存在：${attachment.name}" }
                transferred += attachment.copy(path = guestPath, sizeBytes = file.length(), isWorkspaceFile = true)
                if (attachment.mimeType in setOf("image/png", "image/jpeg", "image/webp", "image/gif")) {
                    images += buildJsonObject { put("type", "image"); put("mimeType", attachment.mimeType); put("data", Base64.getEncoder().encodeToString(file.readBytes())) }
                }
            }
            val references = transferred.joinToString("\n") { "- ${it.name}: ${it.path}" }
            val marker = "\n\n附件（工作区内真实文件）：\n$references"
            return Triple(if (text.endsWith(marker)) text else text + marker, transferred, JsonArray(images))
        }
    }
}

internal val DriverJson = Json { ignoreUnknownKeys = true }
internal fun JsonObject.string(key: String, fallback: String = "") = (get(key) as? JsonPrimitive)?.contentOrNull ?: fallback
internal fun thinkingWire(level: ThinkingLevel): String = when (level) {
    ThinkingLevel.NONE -> "off"
    ThinkingLevel.LOW -> "low"
    ThinkingLevel.MEDIUM -> "medium"
    ThinkingLevel.HIGH -> "high"
    ThinkingLevel.XHIGH -> "xhigh"
    ThinkingLevel.MAX -> "max"
}

private fun safeId(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
private fun normalizeGuest(value: String): String {
    require(value.startsWith('/') && !value.contains('\u0000')) { "工作区必须是容器内绝对路径" }
    val parts = value.split('/').filter { it.isNotEmpty() && it != "." }
    require(parts.none { it == ".." }) { "路径不能包含上级目录" }
    return "/" + parts.joinToString("/")
}
private fun confined(root: File, relative: String): File {
    require(!File(relative).isAbsolute && relative.split('/', '\\').none { it == ".." }) { "路径不能越界" }
    val base = root.canonicalFile
    val target = File(base, relative).canonicalFile
    require(target == base || target.toPath().startsWith(base.toPath())) { "符号链接越过允许目录" }
    return target
}
private fun newestHistory(directory: File): File? = directory.listFiles()?.filter { it.isFile && it.extension == "jsonl" }?.maxByOrNull { it.lastModified() }
internal fun atomicWrite(file: File, contents: String) {
    file.parentFile.mkdirs()
    val temporary = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
    try {
        temporary.outputStream().use { output -> output.write(contents.toByteArray(Charsets.UTF_8)); output.fd.sync() }
        try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    } finally { temporary.delete() }
}
private fun copyOwnedTree(source: File, destination: File) {
    val root = source.canonicalFile
    source.walkTopDown().forEach { file ->
        require(!Files.isSymbolicLink(file.toPath())) { "会话目录包含符号链接，拒绝跨目录复制" }
        val relative = file.relativeTo(source).path
        val canonical = confined(root, relative)
        val target = confined(destination, relative)
        if (canonical.isDirectory) target.mkdirs() else canonical.copyTo(target.also { it.parentFile.mkdirs() }, overwrite = false)
    }
}
private fun deleteOwnedTree(directory: File) {
    require(!Files.isSymbolicLink(directory.toPath())) { "拒绝删除符号链接会话目录" }
    // walkFileTree does not follow links; deleting a malicious child link never touches its target.
    Files.walkFileTree(directory.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
        override fun visitFile(file: java.nio.file.Path, attributes: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
            Files.delete(file)
            return java.nio.file.FileVisitResult.CONTINUE
        }
        override fun postVisitDirectory(dir: java.nio.file.Path, failure: java.io.IOException?): java.nio.file.FileVisitResult {
            if (failure != null) throw failure
            Files.delete(dir)
            return java.nio.file.FileVisitResult.CONTINUE
        }
    })
}
private const val NATIVE_TITLE_SLOT_BYTES = 256

private fun historyRecord(input: java.io.InputStream): JsonObject {
    val line = ByteArrayOutputStream(NATIVE_TITLE_SLOT_BYTES)
    var byte = input.read()
    while (byte >= 0 && byte != '\n'.code) {
        line.write(byte)
        byte = input.read()
    }
    check(line.size() > 0) { "会话历史为空" }
    return DriverJson.parseToJsonElement(line.toString(Charsets.UTF_8.name())).jsonObject
}

/** OMP updates this prefix in place; width includes the newline and counts UTF-8 bytes. */
private fun nativeTitleSlot(title: String): ByteArray {
    val updatedAt = java.time.Instant.now().toString()
    fun encode(end: Int) = buildJsonObject {
        put("type", "title"); put("v", 1); put("title", title.substring(0, end))
        put("source", "user"); put("updatedAt", updatedAt); put("pad", "")
    }.toString().toByteArray(Charsets.UTF_8)
    var low = 0
    var high = minOf(title.length, NATIVE_TITLE_SLOT_BYTES - 1)
    var best = encode(0)
    check(best.size < NATIVE_TITLE_SLOT_BYTES) { "会话标题元数据超过固定槽位" }
    while (low <= high) {
        val middle = (low + high) ushr 1
        val end = if (middle > 0 && middle < title.length && title[middle - 1].isHighSurrogate() && title[middle].isLowSurrogate()) middle - 1 else middle
        val candidate = encode(end)
        if (candidate.size < NATIVE_TITLE_SLOT_BYTES) {
            best = candidate
            low = middle + 1
        } else high = middle - 1
    }
    return ByteArray(NATIVE_TITLE_SLOT_BYTES) { ' '.code.toByte() }.apply {
        best.copyInto(this, endIndex = best.size - 2)
        this[size - 3] = '"'.code.toByte()
        this[size - 2] = '}'.code.toByte()
        this[size - 1] = '\n'.code.toByte()
    }
}

private fun rewriteSessionHeader(file: File, workspace: String, title: String, newId: String? = null, parent: String? = null) {
    val temporary = File(file.parentFile, ".${file.name}.relocate-${UUID.randomUUID()}")
    try {
        file.inputStream().buffered().use { input -> temporary.outputStream().buffered().use { output ->
            var header = historyRecord(input)
            if (header.string("type") == "title") {
                require(header["v"]?.jsonPrimitive?.intOrNull == 1) { "不支持的 OMP 会话标题格式" }
                header = historyRecord(input)
            }
            require(header.string("type") == "session") { "不是 OMP 会话历史" }
            val rewritten = buildJsonObject {
                header.forEach { (key, value) -> put(key, value) }
                put("cwd", workspace); put("title", title); put("titleSource", "user")
                newId?.let { put("id", it) }; parent?.let { put("parentSession", it) }
            }
            output.write(nativeTitleSlot(title))
            output.write(rewritten.toString().toByteArray(Charsets.UTF_8))
            output.write('\n'.code)
            input.copyTo(output)
        } }
        try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    } finally { temporary.delete() }
}
private fun stopProcess(process: Process) {
    process.destroy()
    if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS) }
    runCatching { process.inputStream.close() }; runCatching { process.errorStream.close() }; runCatching { process.outputStream.close() }
}
private fun attachmentsJson(files: List<AttachmentFile>): JsonArray = JsonArray(files.map { file -> buildJsonObject {
    put("id", file.id); put("name", file.name); put("path", file.path); put("mimeType", file.mimeType); put("sizeBytes", file.sizeBytes); put("isWorkspaceFile", file.isWorkspaceFile)
} })
private fun parseAttachments(element: JsonElement?): List<AttachmentFile> = (element as? JsonArray).orEmpty().map { value ->
    val file = value.jsonObject
    AttachmentFile(file.string("id"), file.string("name"), file.string("path"), file.string("mimeType", "application/octet-stream"), file["sizeBytes"]?.jsonPrimitive?.longOrNull ?: 0, file["isWorkspaceFile"]?.jsonPrimitive?.booleanOrNull ?: false)
}
private fun mapFeed(items: List<FeedItem>, transform: (FeedItem) -> FeedItem): List<FeedItem> = items.map { item -> transform(if (item is StepGroupNodeItem) item.copy(children = mapFeed(item.children, transform)) else item) }
private fun hasUnresolvedDialog(items: List<FeedItem>): Boolean = items.any { item -> when (item) {
    is ApprovalCardItem -> item.resolution == null
    is AskCardItem -> item.selectedAnswer == null
    is StepGroupNodeItem -> hasUnresolvedDialog(item.children)
    else -> false
} }
private fun stopFeed(items: List<FeedItem>): List<FeedItem> = mapFeed(items) { item -> when (item) {
    is ToolNodeItem -> if (item.state == NodeStatus.RUNNING) item.copy(state = NodeStatus.STOPPED) else item
    is StepGroupNodeItem -> if (item.state == NodeStatus.RUNNING) item.copy(state = NodeStatus.STOPPED) else item
    is ThinkingNodeItem -> item.copy(isStreaming = false)
    is AssistantMessageItem -> item.copy(isStreaming = false)
    is ApprovalCardItem -> if (item.resolution == null) item.copy(resolution = ApprovalDecision.DENY) else item
    is AskCardItem -> if (item.selectedAnswer == null) item.copy(selectedAnswer = "已取消") else item
    else -> item
} }

/** Source-compatible protocol-v2 reassembly, including strict byte/order/UTF-8 validation. */
internal class OmpFrameDecoder {
    private data class Pending(val id: String, val count: Int, val length: Int, val bytes: ByteArrayOutputStream, var next: Int = 0)
    private var pending: Pending? = null
    fun push(frame: JsonObject): JsonObject? {
        if (frame.string("type") != "rpc_chunk") {
            check(pending == null) { "RPC 分块传输中断" }
            return frame
        }
        val id = frame.string("chunkId")
        val index = frame["index"]?.jsonPrimitive?.intOrNull ?: error("无效分块索引")
        val count = frame["count"]?.jsonPrimitive?.intOrNull ?: error("无效分块数量")
        val length = frame["byteLength"]?.jsonPrimitive?.intOrNull ?: error("无效分块长度")
        require(id.isNotEmpty() && id.length <= 128 && count in 2..256 && index in 0 until count && length in 1_048_576..67_108_864) { "无效 RPC 分块元数据" }
        val data = frame.string("data")
        val bytes = Base64.getDecoder().decode(data)
        require(bytes.isNotEmpty() && bytes.size <= 262_144 && Base64.getEncoder().encodeToString(bytes) == data) { "无效 RPC 分块内容" }
        val active = pending ?: Pending(id, count, length, ByteArrayOutputStream(minOf(length, 1_048_576))).also { require(index == 0); pending = it }
        require(active.id == id && active.count == count && active.length == length && active.next == index) { "RPC 分块顺序不匹配" }
        require(active.bytes.size() + bytes.size <= length) { "RPC 分块超过声明长度" }
        active.bytes.write(bytes); active.next++
        if (active.next < count) return null
        require(active.bytes.size() == length) { "RPC 分块长度不匹配" }
        pending = null
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return DriverJson.parseToJsonElement(decoder.decode(java.nio.ByteBuffer.wrap(active.bytes.toByteArray())).toString()).jsonObject
    }
}
