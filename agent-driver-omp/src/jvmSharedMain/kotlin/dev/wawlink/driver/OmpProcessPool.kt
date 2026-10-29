package dev.wawlink.driver

import dev.wawlink.protocol.*
import dev.wawlink.runtime.ContainerConfig
import dev.wawlink.runtime.ContainerRuntime
import dev.wawlink.runtime.ContainerFileService
import dev.wawlink.runtime.installContainerFeatureSkill
import dev.wawlink.runtime.wasInterrupted
import dev.wawlink.runtime.containerHome
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

internal fun isApprovalRequest(frame: JsonObject): Boolean {
    if (frame["method"]?.jsonPrimitive?.contentOrNull == "confirm") return true
    if (frame["method"]?.jsonPrimitive?.contentOrNull != "select" ||
        frame["title"]?.jsonPrimitive?.contentOrNull?.startsWith("Allow tool:") != true) return false
    val options = frame["options"]?.jsonArray.orEmpty()
    return options.any { it.jsonPrimitive.contentOrNull == "Deny" } &&
        options.any { it.jsonPrimitive.contentOrNull == "Approve" || it.jsonPrimitive.contentOrNull == "Approve Once" }
}

data class OmpRuntimeIssue(val id: Long, val containerId: String, val message: String, val stopGeneration: Long)

internal class OmpProcessStoppedException : IllegalStateException("OMP 进程已主动暂停，请重试")

/** Owns its RPC children; idle model previews share a native management bridge, not a chat process. */
class OmpProcessPool(val runtime: ContainerRuntime, val dataDir: File, private val resolvConf: StateFlow<String?>? = null) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = LinkedHashMap<String, OmpSession>()
    private val providerRepositories = LinkedHashMap<String, OmpProviderRepository>()
    private val settingsRepositories = LinkedHashMap<String, OmpSettingsRepository>()
    private val managementRepositories = LinkedHashMap<Triple<String, String?, String?>, OmpAgentManagementRepository>()
    private val resourceRepositories = LinkedHashMap<Triple<String, String?, String?>, OmpAgentManagementRepository>()
    private var closed = false
    private val issueSequence = AtomicLong()
    private val mutableRuntimeIssues = MutableStateFlow<Map<String, OmpRuntimeIssue>>(emptyMap())
    val runtimeIssues: StateFlow<Map<String, OmpRuntimeIssue>> = mutableRuntimeIssues.asStateFlow()

    fun dismissRuntimeIssue(containerId: String, issueId: Long) {
        mutableRuntimeIssues.update { issues ->
            if (issues[containerId]?.id == issueId) issues - containerId else issues
        }
    }

    private fun reportRuntimeIssue(containerId: String, stopGeneration: Long, message: String) {
        synchronized(this) {
            if (closed || runtime.wasInterrupted(containerId, stopGeneration)) return
            val previous = mutableRuntimeIssues.value[containerId]
            if (previous?.stopGeneration == stopGeneration) return
            val issue = OmpRuntimeIssue(issueSequence.incrementAndGet(), containerId, message, stopGeneration)
            mutableRuntimeIssues.update { issues -> issues + (containerId to issue) }
        }
    }

    /** Stops owned children without removing controllers, configuration, or durable history. */
    suspend fun prepareOmpRepair(containerId: String): Unit = coroutineScope {
        val (chat, management) = synchronized(this@OmpProcessPool) {
            check(!closed) { "进程池已关闭" }
            sessions.values.filter { it.containerId == containerId } to
                (managementRepositories.filterKeys { it.first == containerId }.values + resourceRepositories.filterKeys { it.first == containerId }.values).toList()
        }
        (chat.map { async { it.parkForRepair() } } + management.map { async { it.park() } }).awaitAll()
    }
    /** Re-resolves native metadata only; no prompt or model inference is started. */
    suspend fun refreshAfterOmpRepair(containerId: String) {
        val (chat, management) = synchronized(this) {
            check(!closed) { "进程池已关闭" }
            sessions.values.filter { it.containerId == containerId } to
                managementRepositories.filterKeys { it.first == containerId }.values.toList()
        }
        management.forEach { it.reload() }
        chat.forEach { it.refreshModelPreview() }
    }


    @Synchronized
    fun session(id: String, containerId: String, workspace: String, title: String = "新会话", autoApprove: Boolean = true): AgentSessionController {
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
        return OmpSession(id, containerId, normalizeGuest(workspace), title, autoApprove).also { sessions[id] = it }
    }

    @Synchronized
    fun explorer(id: String): SessionExplorer {
        check(!closed) { "进程池已关闭" }
        return requireNotNull(sessions[id]) { "会话尚未打开：$id" }.explorer
    }

    /** Adopt a native independent fork into this application's owned session directory. */
    suspend fun adoptFork(sourceId: String, newId: String, guestSessionFile: String, title: String): AgentSessionController = withContext(Dispatchers.IO) {
        require(sourceId != newId) { "分支必须使用新的会话 ID" }
        val source = synchronized(this@OmpProcessPool) { sessions[sourceId] } ?: loadExisting(sourceId)
        require(!metadataFile(newId).exists()) { "分支会话已存在" }
        val native = resolveWorkspace(source.containerId, guestSessionFile)
        require(native.isFile && native.extension == "jsonl") { "原生分支文件不存在" }
        val destination = ownedSessionDirectory(source.containerId, newId)
        require(!destination.exists()) { "分支目标目录已存在" }
        destination.mkdirs()
        try {
            val target = File(destination, native.name)
            native.copyTo(target)
            // Native fork writes sibling artifact directories beside its transcript.
            val stem = native.nameWithoutExtension
            native.parentFile.listFiles().orEmpty().filter { it.isDirectory && it.name == stem }
                .forEach { copyOwnedTree(it, File(destination, it.name)) }
            rewriteSessionHeader(target, source.workspace, title)
            writeMetadata(newId, source.containerId, source.workspace, title, guestSessionDirectory(source.containerId, newId) + "/" + target.name)
            session(newId, source.containerId, source.workspace, title, source.state.value.autoApprove)
        } catch (failure: Throwable) {
            deleteOwnedTree(destination)
            throw failure
        }
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
            writeMetadata(newId, containerId, destinationWorkspace, title, history?.let { guestSessionDirectory(containerId, newId) + "/" + it.name })
            session(newId, containerId, destinationWorkspace, title, autoApprove = source.state.value.autoApprove)
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
        val newPath = newestHistory(newDirectory)?.let { guestSessionDirectory(containerId, id) + "/" + it.name }
        writeMetadata(id, containerId, nextWorkspace, title, newPath)
        synchronized(this@OmpProcessPool) { sessions.remove(id) }
        source.dispose()
        if (oldDirectory.canonicalFile != newDirectory.canonicalFile && oldDirectory.exists()) deleteOwnedTree(oldDirectory)
        session(id, containerId, nextWorkspace, title, autoApprove = source.state.value.autoApprove)
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
        val ownedBridges = synchronized(this@OmpProcessPool) {
            val removed = managementRepositories.filterKeys { it.third == id }.values.toList() +
                resourceRepositories.filterKeys { it.third == id }.values.toList()
            managementRepositories.keys.removeAll { it.third == id }
            resourceRepositories.keys.removeAll { it.third == id }
            removed
        }
        ownedBridges.forEach { it.close() }
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
            OmpProviderRepository({ File(config(containerId).rootfsPath) }, { agentDirectory(containerId) }, scope) { _, _ -> configurationChanged(containerId, null, null) }
        }
    }

    @Synchronized
    fun settings(containerId: String): AgentSettingsRepository {
        check(!closed)
        return settingsRepositories.getOrPut(containerId) {
            OmpSettingsRepository({ File(config(containerId).rootfsPath) }, { agentDirectory(containerId) }, scope) { key, value -> configurationChanged(containerId, key, value) }
        }
    }

    @Synchronized
    fun management(containerId: String, workspace: String? = null, sessionId: String? = null): AgentManagementRepository {
        check(!closed)
        val project = workspace?.let(::normalizeGuest)
        val configuration = config(containerId)
        val selected = sessionId?.let { id -> requireNotNull(sessions[id]) { "Active session is unavailable" }.also {
            require(it.containerId == containerId && (project == null || it.workspace == project)) { "Session belongs to another configuration context" }
        } }
        return managementRepositories.getOrPut(Triple(containerId, project, sessionId)) {
            OmpAgentManagementRepository(runtime, AgentConfigurationContext(containerId, configuration.name, projectPath = project, sessionId = sessionId),
                { agentDirectory(containerId) }, { guestAgentDirectory(containerId) }, { guest, write -> resolveWorkspace(containerId, guest, write) }, scope,
                changed = { changedScope, keys ->
                    val roleOnly = keys.isNotEmpty() && keys.all { it.startsWith("modelRoles") || it.startsWith("modelPresets") || it in setOf("defaultThinkingLevel", "modelTags", "modelRoleStorage", "cycleOrder") }
                    val affected = synchronized(this) { sessions.values.filter { it.containerId == containerId && (changedScope == ConfigurationScope.GLOBAL || it.workspace == project) && (!roleOnly || it === selected) } }
                    affected.forEach { it.markConfigurationChanged() }
                },
                pin = { provider, account -> requireNotNull(selected) { "Runtime account pinning requires an active session" }.managementControl("pin", provider, account) },
                pins = { if (selected?.managementAvailable() == true) selected.managementControl("pins")["pins"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty() else emptyMap() },
                applyDefault = { role -> selected?.applyManagementDefault(role) },
                runtimeFailure = { generation, message -> reportRuntimeIssue(containerId, generation, message) },
            )
        }
    }

    /** Resource discovery owns a separate bridge so model/OAuth SDK work cannot block its first screen. */
    @Synchronized
    fun resources(containerId: String, workspace: String? = null, sessionId: String? = null): AgentResourceRepository {
        check(!closed)
        val project = workspace?.let(::normalizeGuest)
        val configuration = config(containerId)
        if (sessionId != null) require(sessions[sessionId]?.let { it.containerId == containerId && it.workspace == project } == true) { "会话不属于当前资源作用域" }
        return resourceRepositories.getOrPut(Triple(containerId, project, sessionId)) {
            OmpAgentManagementRepository(runtime, AgentConfigurationContext(containerId, configuration.name, projectPath = project, sessionId = sessionId),
                { agentDirectory(containerId) }, { guestAgentDirectory(containerId) }, { guest, write -> resolveWorkspace(containerId, guest, write) }, scope,
                changed = { changedScope, _ ->
                    val affected = synchronized(this) { sessions.values.filter { it.containerId == containerId && (changedScope == ConfigurationScope.GLOBAL || it.workspace == project) } }
                    affected.forEach { it.markConfigurationChanged() }
                },
                pin = { _, _ -> error("资源管理不提供模型账户操作") }, pins = { emptyMap() }, applyDefault = {},
                runtimeFailure = { generation, message -> reportRuntimeIssue(containerId, generation, message) }, loadInitially = false,
            )
        }.resources
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

    fun guestAgentDirectory(containerId: String): String = guestAgentDirectory(config(containerId))
    private fun guestAgentDirectory(configuration: ContainerConfig): String = normalizeGuest(containerHome(configuration).trimEnd('/') + "/.omp/agent")
    private fun agentDirectory(containerId: String): File {
        val configuration = config(containerId)
        val guest = guestAgentDirectory(configuration)
        val rootfs = File(configuration.rootfsPath)
        val directory = ompAgentDirectory(rootfs, File(rootfs, guest.trimStart('/')))
        val canonical = directory.canonicalFile.toPath()
        fun overlapsMount(target: String): Boolean {
            val mounted = File(rootfs, target.trimStart('/')).canonicalFile.toPath()
            return guest == target || guest.startsWith(target.trimEnd('/') + "/") || target.startsWith(guest + "/") ||
                canonical.startsWith(mounted) || mounted.startsWith(canonical)
        }
        // These system paths are bound by the runtime in addition to custom mounts.
        require(!overlapsMount("/dev") && !overlapsMount("/proc") && !overlapsMount("/sys") &&
            configuration.mounts.none { overlapsMount(normalizeGuest(it.target)) }) {
            "OMP 配置和会话目录必须位于容器 Rootfs 内，不能被宿主挂载覆盖"
        }
        return directory
    }
    private fun ownedSessionDirectory(containerId: String, id: String): File = confined(agentDirectory(containerId), "wawlink/sessions/${safeId(id)}")
    private fun guestSessionDirectory(containerId: String, id: String) = "${guestAgentDirectory(containerId)}/wawlink/sessions/${safeId(id)}"
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
            managementRepositories.values.forEach { it.close() }
            resourceRepositories.values.forEach { it.close() }
        }
        scope.cancel()
    }

    private data class OutgoingPrompt(
        val id: String,
        var text: String,
        val attachments: List<AttachmentFile>,
        val timestamp: Long,
        val generation: Long,
        val containerStopGeneration: Long,
        val queued: Boolean,
        val queue: PromptQueue,
        var position: UserMessagePosition,
        val awaitingHistory: Boolean,
        var preview: Boolean = true,
        var dispatching: Boolean = false,
    )

    private class ChatAttempt(val generation: Long, val containerStopGeneration: Long) {
        val reported = AtomicBoolean()
        val managementInstance = UUID.randomUUID().toString()
        val ready = CompletableDeferred<JsonObject>()
        val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
        @Volatile var processJob: Job? = null
        @Volatile var heartbeat: Job? = null
        @Volatile var managementFailure: String? = null
        @Volatile var intentional = false
        @Volatile var nativeReady = false
    }

    private inner class OmpSession(val id: String, val containerId: String, val workspace: String, title: String, initialAutoApprove: Boolean) : AgentSessionController {
        private val ownerJob = SupervisorJob(scope.coroutineContext[Job])
        private val sessionScope = CoroutineScope(scope.coroutineContext + ownerJob)
        private val stateMutex = Mutex()
        private val lifecycleMutex = Mutex()
        private val writeMutex = Mutex()
        private val actions = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        private lateinit var actionJob: Job
        private val sequence = AtomicLong()
        private val stopGeneration = AtomicLong()
        private val requests = LinkedHashMap<String, JsonObject>()
        private val answeringApprovals = HashSet<String>()
        private val loadingSlashCommands = AtomicBoolean()
        private val eventJournal = journalFile(id)
        private val reducer = OmpEventReducer(ChatSessionUiState(id = id, title = title, workspace = workspace, autoApprove = initialAutoApprove, modelLoading = true))
        private val presentationLock = Any()
        private val outgoingPrompts = LinkedHashMap<String, OutgoingPrompt>()
        @Volatile private var backendState = reducer.state
        private var backendUserPosition = reducer.currentUserPosition()
        private var historyReady = false
        private val mutableState: MutableStateFlow<ChatSessionUiState>
        override val state: StateFlow<ChatSessionUiState>
        @Volatile private var process: Process? = null
        @Volatile private var activeAttempt: ChatAttempt? = null
        @Volatile private var repairing = false
        @Volatile private var parkRequested = false
        @Volatile private var hasStartedRpc = false
        private var previewError: String? = null
        private var dismissedPreviewHash: String? = null
        private var dismissedRunError: String? = null
        private var startedResolvConf: String? = null
        @Volatile private var backendSettled = true
        @Volatile private var stopping: CompletableDeferred<Unit>? = null
        @Volatile private var disposed = false
        @Volatile private var restartRequired = false
        private var hasThinkingPreference = false
        private var hasModelPreference = false
        private var managementDefault: ManagedRole? = null
        private var hasExecutionPreference = false
        @Volatile private var deferredExplorerQueue: List<QueuedPrompt> = emptyList()
        private var lastHeartbeatNanos = System.nanoTime()
        private var startedNanos = System.nanoTime()
        @Volatile var sessionFile: String? = null
            private set

        val explorer: OmpSessionExplorer by lazy {
            OmpSessionExplorer(id, containerId, workspace, sessionScope,
                request = { op, parameters -> explorerRequest(op, parameters) },
                synchronize = { history -> synchronizeNativeHistory(history) },
                identities = { records -> stateMutex.withLock { reducer.nativeEntryFeedAliases(records) } },
                originalTexts = { records -> stateMutex.withLock { reducer.nativeUserText(records) } },
                messageForChatItem = { chatId -> stateMutex.withLock { reducer.nativeMessageForFeedItem(chatId) } },
                prepareAttachments = { text, attachments, childWorkspace -> prepareAttachments(text, attachments, childWorkspace) },
                files = { childWorkspace, path -> workspaceFiles(childWorkspace, path) })
        }

        private fun currentContainerStopGeneration(): Long = runtime.containers.value.firstOrNull { it.config.id == containerId }?.stopGeneration
            ?: throw OmpProcessStoppedException()

        private fun interrupted(attempt: ChatAttempt): Boolean = attempt.intentional || runtime.wasInterrupted(containerId, attempt.containerStopGeneration)

        init {
            mutableState = MutableStateFlow(reducer.state)
            state = mutableState.asStateFlow()
            val initialGeneration = runtime.containers.value.firstOrNull { it.config.id == containerId }?.stopGeneration
            sessionScope.launch(start = CoroutineStart.UNDISPATCHED) {
                var observed = initialGeneration
                runtime.containers.map { values -> values.firstOrNull { it.config.id == containerId }?.stopGeneration }
                    .distinctUntilChanged().collect { generation ->
                        if (generation != observed) {
                            observed = generation
                            val attempt = activeAttempt
                            synchronized(presentationLock) {
                                outgoingPrompts.values.removeAll { it.preview && !it.queued && runtime.wasInterrupted(containerId, it.containerStopGeneration) }
                                presentState()
                            }
                            if (attempt != null && runtime.wasInterrupted(containerId, attempt.containerStopGeneration)) {
                                attempt.intentional = true
                                attempt.ready.completeExceptionally(OmpProcessStoppedException())
                                attempt.pending.values.forEach { it.completeExceptionally(OmpProcessStoppedException()) }
                                process?.let(::stopProcess)
                                park(attempt)
                            }
                            stateMutex.withLock {
                                if (activeAttempt === attempt && (attempt == null || interrupted(attempt))) {
                                    reducer.update { it.copy(modelLoading = false) }
                                    recordLocal(buildJsonObject { put("action", "stopped") })
                                }
                            }
                        }
                    }
            }
            actionJob = sessionScope.launch {
                try {
                    stateMutex.withLock {
                        val lastRecordedAt = replayJournal()
                        if (!hasExecutionPreference) recordLocal(buildJsonObject { put("action", "auto"); put("enabled", initialAutoApprove) })
                        // No child from a previous host lifetime is owned by this controller.
                        reducer.update { it.copy(id = id, title = title, workspace = workspace,
                            feed = stopFeed(it.feed, lastRecordedAt ?: System.currentTimeMillis(),
                                if (it.status == SessionStatus.ERROR) NodeStatus.ERROR else NodeStatus.STOPPED),
                            status = if (it.isRunning) SessionStatus.STOPPED else it.status, actionText = "准备就绪",
                            slashCommands = emptyList(), slashCommandsLoading = false, slashCommandsError = null) }
                        synchronized(presentationLock) {
                            historyReady = true
                            var position = reducer.currentUserPosition()
                            for (prompt in outgoingPrompts.values) if (prompt.awaitingHistory) {
                                prompt.position = position
                                position = position.copy(afterId = prompt.id)
                            }
                        }
                        publishState()
                    }
                    val metadata = metadataFile(id)
                    if (metadata.exists()) sessionFile = DriverJson.parseToJsonElement(metadata.readText()).jsonObject["sessionFile"]?.jsonPrimitive?.contentOrNull
                    saveMetadata()
                    sessionScope.launch { initializeModel(expectedStopGeneration = initialGeneration) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { fail(failure) }
                for (action in actions) {
                    try { stopping?.await(); action() }
                    catch (cancelled: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        if (activeAttempt?.let { runtime.wasInterrupted(containerId, it.containerStopGeneration) } != true) throw cancelled
                        local(buildJsonObject { put("action", "stopped") })
                    }
                    catch (failure: Exception) { fail(failure) }
                }
            }
        }

        suspend fun refreshModelPreview() {
            repairing = false
            if (!disposed && !hasModelPreference && !hasStartedRpc && sessionFile == null && process == null) initializeModel(refresh = true)
        }

        private suspend fun initializeModel(refresh: Boolean = false, expectedStopGeneration: Long? = runtime.containers.value.firstOrNull { it.config.id == containerId }?.stopGeneration) {
            try {
                if (expectedStopGeneration == null || runtime.wasInterrupted(containerId, expectedStopGeneration)) throw OmpProcessStoppedException()
                if (hasModelPreference || hasStartedRpc || sessionFile != null || process != null || (!refresh && state.value.modelId.isNotBlank())) {
                    mutate { it.copy(modelLoading = false) }
                    return
                }
                val snapshot = management(containerId, workspace).state.first { !it.modelsLoading }
                if (runtime.wasInterrupted(containerId, expectedStopGeneration)) throw OmpProcessStoppedException()
                check(snapshot.modelsError == null && snapshot.error == null) { snapshot.modelsError ?: snapshot.error.orEmpty() }
                val role = checkNotNull(snapshot.roles.firstOrNull { it.id == "default" }) { "原生默认模型响应缺失" }
                check(role.error == null) { role.error.orEmpty() }
                val provider = checkNotNull(role.providerId?.takeIf { it.isNotBlank() }) { "原生默认模型缺少服务标识" }
                val model = checkNotNull(role.modelId?.takeIf { it.isNotBlank() }) { "原生默认模型缺少模型标识" }
                stateMutex.withLock {
                    // A late preview must never replace an explicit selection, history or live RPC state.
                    if (!runtime.wasInterrupted(containerId, expectedStopGeneration) && !hasModelPreference && !hasStartedRpc && (refresh || reducer.state.modelId.isBlank()) && process == null && sessionFile == null) {
                        reducer.previewModel(provider, model, if (hasThinkingPreference) null else role.thinking)
                        reducer.update { it.copy(error = if (it.error == previewError) null else it.error) }
                        previewError = null
                        if (dismissedPreviewHash != null) recordLocal(buildJsonObject { put("action", "preview_recovered") })
                    } else if (reducer.state.modelLoading) reducer.update { it.copy(modelLoading = false) }
                    publishState()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (failure is OmpProcessStoppedException || repairing || expectedStopGeneration == null || runtime.wasInterrupted(containerId, expectedStopGeneration)) {
                    mutate { it.copy(modelLoading = false) }
                    return
                }
                stateMutex.withLock {
                    val show = !hasModelPreference && !hasStartedRpc && !reducer.state.isRunning && sessionFile == null && process == null
                    val message = failure.message ?: "无法读取默认模型"
                    val suppressed = show && dismissedPreviewHash?.let { it == safeId(message) } == true
                    if (show && !suppressed && dismissedPreviewHash != null) recordLocal(buildJsonObject { put("action", "preview_recovered") })
                    if (show) previewError = message.takeUnless { suppressed }
                    reducer.update { it.copy(modelLoading = false, error = if (show && !suppressed) message else it.error) }
                    publishState()
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

        fun requestPark() {
            beginIntentionalStop()
            submit { park() }
        }

        suspend fun stopAccepting() {
            disposed = true
            actions.close()
            actionJob.cancelAndJoin()
        }

        private fun submit(action: suspend () -> Unit) {
            if (!disposed) actions.trySend(action)
        }

        override fun sendPrompt(text: String, attachments: List<AttachmentFile>) = admitPrompt(text, attachments, PromptQueue.STEERING)

        override fun dismissError() {
            val displayedError = state.value.error ?: return
            sessionScope.launch {
                stateMutex.withLock {
                    if (reducer.state.error != displayedError) return@withLock
                    val previewHash = previewError?.takeIf { it == displayedError }?.let(::safeId)
                    recordLocal(buildJsonObject {
                        put("action", "clear_error")
                        previewHash?.let { put("previewHash", it) }
                    })
                }
            }
        }

        override fun enqueuePrompt(text: String, attachments: List<AttachmentFile>) = admitPrompt(text, attachments, PromptQueue.FOLLOW_UP)

        private fun admitPrompt(text: String, attachments: List<AttachmentFile>, queue: PromptQueue) {
            if (text.isBlank() && attachments.isEmpty()) return
            synchronized(presentationLock) {
                if (disposed) return
                val position = outgoingPrompts.values.lastOrNull {
                    it.preview && !it.queued && it.position.groupId == backendUserPosition.groupId
                }?.let { backendUserPosition.copy(afterId = it.id) } ?: backendUserPosition
                val prompt = OutgoingPrompt(UUID.randomUUID().toString(), text, attachments.toList(),
                    System.currentTimeMillis(), stopGeneration.get(), runtime.containers.value.firstOrNull { it.config.id == containerId }?.stopGeneration ?: -1,
                    mutableState.value.isRunning || !backendSettled, queue, position, !historyReady)
                outgoingPrompts[prompt.id] = prompt
                presentState()
                if (actions.trySend { deliverPrompt(prompt) }.isFailure) {
                    outgoingPrompts.remove(prompt.id)
                    presentState()
                }
            }
        }


        private suspend fun deliverPrompt(prompt: OutgoingPrompt) {
            suspend fun cancelled(): Boolean {
                if (synchronized(presentationLock) { prompt.id !in outgoingPrompts }) return true
                if (prompt.generation == stopGeneration.get() && !runtime.wasInterrupted(containerId, prompt.containerStopGeneration)) return false
                // A stop preserves queued input for the next run, but never sends a cancelled first prompt.
                if (prompt.queued) local(buildJsonObject {
                    put("action", "queue_add"); put("queueId", prompt.id); put("text", prompt.text)
                    put("queue", prompt.queue.wireName)
                    put("attachments", attachmentsJson(prompt.attachments))
                    put("position", positionJson(prompt.position))
                })
                completePrompt(prompt.id)
                return true
            }
            try {
                if (cancelled()) return
                val originalText = synchronized(presentationLock) { prompt.text }
                val prepared = prepareAttachments(originalText, prompt.attachments)
                if (cancelled()) return
                if (!backendState.isRunning && backendSettled &&
                    (restartRequired || (process != null && startedResolvConf != resolvConf?.value))) park()
                ensureStarted()
                restoreExplorerQueue()
                if (cancelled()) return
                val dispatchText = synchronized(presentationLock) {
                    if (prompt.id !in outgoingPrompts) return
                    prompt.dispatching = true
                    prompt.text
                }
                val message = if (dispatchText == originalText) prepared.first else attachmentPrompt(dispatchText, prepared.second)
                val running = backendState.isRunning || !backendSettled
                local(buildJsonObject { put("action", "raw_input"); put("promptId", prompt.id); put("text", dispatchText) })
                val nativeSlash = message.startsWith("/") && prompt.attachments.isEmpty()
                if (running && !nativeSlash) {
                    enqueue(message, prepared.second, prepared.third, prompt.id, prompt.position, prompt.queue)
                } else {
                    if (!running) startedNanos = System.nanoTime()
                    local(buildJsonObject {
                        put("action", "user"); put("localId", prompt.id); put("text", message)
                        put("timestamp", prompt.timestamp); put("attachments", attachmentsJson(prepared.second))
                        put("position", positionJson(prompt.position))
                        if (running) put("preserveRun", true)
                    })
                    command("prompt", buildJsonObject {
                        put("message", message); if (prepared.third.isNotEmpty()) put("images", prepared.third)
                        put("streamingBehavior", if (prompt.queue == PromptQueue.STEERING) "steer" else "followUp")
                    }, timeoutMs = 180_000, requestId = prompt.id)
                }
            } catch (cancellation: CancellationException) {
                currentCoroutineContext().ensureActive()
                if (!runtime.wasInterrupted(containerId, prompt.containerStopGeneration) && prompt.generation == stopGeneration.get()) throw cancellation
                if (!cancelled()) completePrompt(prompt.id)
                if (!prompt.queued) local(buildJsonObject { put("action", "prompt_cancelled"); put("promptId", prompt.id) })
                local(buildJsonObject { put("action", "stopped") })
            }
            catch (failure: Exception) {
                if (failure is OmpProcessStoppedException || prompt.generation != stopGeneration.get() || runtime.wasInterrupted(containerId, prompt.containerStopGeneration)) {
                    if (!cancelled()) completePrompt(prompt.id)
                    if (!prompt.queued) local(buildJsonObject { put("action", "prompt_cancelled"); put("promptId", prompt.id) })
                } else rejectPrompt(prompt.id, failure.message ?: failure.javaClass.simpleName)
            }
        }

        private suspend fun enqueue(text: String, attachments: List<AttachmentFile>, images: JsonArray,
            queuedId: String = UUID.randomUUID().toString(), position: UserMessagePosition? = null,
            queue: PromptQueue = PromptQueue.FOLLOW_UP) {
            ensureStarted()
            local(buildJsonObject {
                put("action", "queue_add"); put("queueId", queuedId); put("text", text); put("attachments", attachmentsJson(attachments))
                put("queue", queue.wireName)
                position?.let { put("position", positionJson(it)) }
            })
            try {
                command(if (queue == PromptQueue.STEERING) "steer" else "follow_up", buildJsonObject { put("message", text); if (images.isNotEmpty()) put("images", images) }, timeoutMs = 180_000, requestId = queuedId)
            } catch (failure: Exception) {
                if (failure !is OmpProcessStoppedException) local(buildJsonObject { put("action", "queue_remove"); put("queueId", queuedId) })
                throw failure
            }
        }

        override fun updateQueuedPrompt(id: String, newText: String) {
            if (newText.isBlank()) return
            synchronized(presentationLock) {
                val prompt = outgoingPrompts[id]
                if (prompt != null && prompt.queued && prompt.preview && !prompt.dispatching) {
                    prompt.text = newText
                    presentState()
                    return
                }
            }
            submit {
                val item = state.value.queuedPrompts.firstOrNull { it.id == id } ?: return@submit
                local(buildJsonObject { put("action", "raw_input"); put("promptId", id); put("text", newText) })
                if (process == null || deferredExplorerQueue.any { it.id == id }) {
                    val prepared = prepareAttachments(newText, item.attachments)
                    deferredExplorerQueue = deferredExplorerQueue.map { if (it.id == id) it.copy(text = prepared.first, attachments = prepared.second) else it }
                    local(buildJsonObject {
                        put("action", "queue_edit"); put("queueId", id); put("text", prepared.first)
                        put("attachments", attachmentsJson(prepared.second))
                    })
                    return@submit
                }
                val removed = removeNativeQueued(item)
                check(removed) { "该消息已开始执行，无法编辑" }
                local(buildJsonObject { put("action", "queue_remove"); put("queueId", id) })
                val prepared = prepareAttachments(newText, item.attachments)
                enqueue(prepared.first, prepared.second, prepared.third, id, queue = item.queue)
            }
        }

        override fun removeQueuedPrompt(id: String) {
            synchronized(presentationLock) {
                val prompt = outgoingPrompts[id]
                if (prompt != null && prompt.queued && prompt.preview && !prompt.dispatching) {
                    outgoingPrompts.remove(id)
                    presentState()
                    return
                }
            }
            submit { takeAdmittedPrompt(id) }
        }

        override suspend fun takeQueuedPrompt(id: String): RestoredSessionPrompt {
            synchronized(presentationLock) {
                val prompt = outgoingPrompts[id]
                if (prompt != null && prompt.queued && prompt.preview && !prompt.dispatching) {
                    outgoingPrompts.remove(id)
                    presentState()
                    return RestoredSessionPrompt(prompt.text, prompt.attachments)
                }
            }
            return exclusive { takeAdmittedPrompt(id) }
        }

        private suspend fun takeAdmittedPrompt(id: String): RestoredSessionPrompt {
            val item = state.value.queuedPrompts.firstOrNull { it.id == id }
                ?: error("该消息已开始执行，无法撤回编辑")
            val restored = stateMutex.withLock {
                RestoredSessionPrompt(reducer.originalUserText(id) ?: item.text, item.attachments)
            }
            val deferred = deferredExplorerQueue.any { it.id == id }
            check(process == null || deferred || removeNativeQueued(item)) { "该消息已开始执行，无法撤回编辑" }
            if (deferred) deferredExplorerQueue = deferredExplorerQueue.filterNot { it.id == id }
            local(buildJsonObject { put("action", "queue_remove"); put("queueId", id) })
            completePrompt(id)
            return restored
        }

        private suspend fun removeNativeQueued(item: QueuedPrompt): Boolean {
            val result = command("remove_queued_message", buildJsonObject {
                put("message", item.text); put("queue", item.queue.wireName)
            })
            return result["data"]?.jsonObject?.get("removed")?.jsonPrimitive?.booleanOrNull == true
        }

        override fun stopExecution() {
            val barrier = CompletableDeferred<Unit>()
            stopping = barrier
            val starting = activeAttempt?.nativeReady == false
            if (starting) beginIntentionalStop() else stopGeneration.incrementAndGet()
            synchronized(presentationLock) {
                outgoingPrompts.values.removeAll { it.preview && !it.queued }
                presentState()
            }
            sessionScope.launch {
                try {
                    if (process != null && !starting) command("abort_and_restore_queue", timeoutMs = 20_000)
                    val withdrawn = state.value.queuedPrompts.toList()
                    park()
                    for (queued in withdrawn) local(buildJsonObject {
                        put("action", "queue_add"); put("queueId", queued.id); put("text", queued.text)
                        put("queue", queued.queue.wireName)
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
            // UI responses cannot wait behind prompt admission: that admission may itself need approval.
            sessionScope.launch {
                try {
                    local(buildJsonObject { put("action", "auto"); put("enabled", autoApprove) })
                    restartRequired = true
                    if (autoApprove) {
                        val waiting = stateMutex.withLock { requests.mapNotNull { (nodeId, request) -> nodeId.takeIf { isApprovalRequest(request) } } }
                        for (nodeId in waiting) answerApproval(nodeId, ApprovalDecision.ALLOW_ONCE)
                    }
                    if (!state.value.isRunning && backendSettled) park()
                    else if (!autoApprove) mutate { it.copy(actionText = "审批模式将在本轮结束后生效") }
                } catch (failure: Exception) { if (failure !is CancellationException) fail(failure) }
            }
        }

        override fun respondApproval(nodeId: String, decision: ApprovalDecision) {
            sessionScope.launch {
                try { answerApproval(nodeId, decision) }
                catch (failure: Exception) { if (failure !is CancellationException) fail(failure) }
            }
        }

        private suspend fun answerApproval(nodeId: String, decision: ApprovalDecision) {
            val request = stateMutex.withLock {
                val request = requests[nodeId] ?: return
                if (!answeringApprovals.add(nodeId)) return
                request
            }
            try {
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
                stateMutex.withLock {
                    if (requests.remove(nodeId) != null) recordLocal(buildJsonObject {
                        put("action", "approval"); put("nodeId", nodeId); put("decision", decision.name)
                    })
                }
            } finally {
                stateMutex.withLock { answeringApprovals.remove(nodeId) }
            }
        }

        override fun respondAsk(nodeId: String, answers: List<AskAnswer>) {
            sessionScope.launch {
                try {
                    stateMutex.withLock {
                        val request = requests[nodeId] ?: error("提问请求已失效")
                        val card = findAskCard(reducer.state.feed, nodeId) ?: error("提问卡片已失效")
                        check(!card.isResolved) { "提问已回答或取消" }
                        val ordered = validateAskAnswers(card.questions, answers)
                        writeFrame(buildJsonObject {
                            put("type", "extension_ui_response"); put("id", nodeId)
                            if (request.string("method") == "ask") put("answers", askAnswersJson(ordered))
                            else {
                                val answer = ordered.single()
                                put("value", answer.customInput ?: answer.selectedOptions.single())
                            }
                        })
                        recordLocal(buildJsonObject { put("action", "ask"); put("nodeId", nodeId); put("answers", askAnswersJson(ordered)) })
                        requests.remove(nodeId)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { fail(failure) }
            }
        }

        override fun executeSlashCommand(command: String, args: String) {
            val name = command.trim().removePrefix("/")
            require(name.isNotBlank() && !name.contains('\n'))
            sendPrompt("/$name${if (args.isBlank()) "" else " $args"}")
        }

        override fun loadSlashCommands() {
            if (disposed || !loadingSlashCommands.compareAndSet(false, true)) return
            val containerGeneration = runtime.containers.value.firstOrNull { it.config.id == containerId }?.stopGeneration ?: run {
                loadingSlashCommands.set(false)
                return
            }
            submit {
                mutate { it.copy(slashCommandsLoading = true, slashCommandsError = null) }
                try {
                    if (runtime.wasInterrupted(containerId, containerGeneration)) throw OmpProcessStoppedException()
                    if (process?.isAlive == true) command("get_available_commands") else ensureStarted()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    if (failure !is OmpProcessStoppedException && !runtime.wasInterrupted(containerId, containerGeneration))
                        mutate { it.copy(slashCommandsError = failure.message ?: "无法加载 OMP 指令") }
                } finally {
                    loadingSlashCommands.set(false)
                    mutate { it.copy(slashCommandsLoading = false) }
                }
            }
        }

        override fun listWorkspaceFiles(relativePath: String): Flow<List<WorkspaceFileItem>> = workspaceFiles(workspace, relativePath)

        private fun workspaceFiles(project: String, relativePath: String): Flow<List<WorkspaceFileItem>> = flow {
            require(!File(relativePath).isAbsolute && relativePath.split('/').none { it == ".." }) { "目录不能越过工作区" }
            val root = resolveWorkspace(containerId, project)
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

        fun markConfigurationChanged() { restartRequired = true }
        fun managementAvailable(): Boolean = process?.isAlive == true

        suspend fun applyManagementDefault(role: ManagedRole) = exclusive {
            require(role.error == null && role.providerId != null && role.modelId != null) { "Default role has no available model" }
            managementDefault = role
            if (state.value.isRunning || !backendSettled) { restartRequired = true; return@exclusive }
            val provider = requireNotNull(role.providerId)
            val modelId = requireNotNull(role.modelId)
            if (process != null) {
                command("set_model", buildJsonObject { put("provider", provider); put("modelId", modelId) })
                role.thinking?.let { command("set_thinking_level", buildJsonObject { put("level", it) }) }
                command("get_state")
            } else {
                reducer.update { it.copy(providerId = provider, modelId = modelId, modelLoading = false) }
                presentState()
            }
        }

        private suspend fun awaitManagementResponse(attempt: ChatAttempt, directory: File, responseFile: File, timeoutMs: Long) {
            val channel = File(directory, "channel.json")
            var channelStamp = 0L
            var channelSize = 0L
            var channelReady = false
            val received = withTimeoutOrNull(timeoutMs) {
                while (true) {
                    if (activeAttempt !== attempt || interrupted(attempt)) throw OmpProcessStoppedException()
                    check(process?.isAlive == true) { "OMP 会话已退出" }
                    attempt.managementFailure?.let { error(it) }
                    if (responseFile.exists()) break
                    val stamp = channel.lastModified()
                    val size = channel.length()
                    if (stamp != 0L && (stamp != channelStamp || size != channelSize)) {
                        val status = DriverJson.parseToJsonElement(channel.readText()).jsonObject
                        if (status.string("instance") == attempt.managementInstance) {
                            check(status["ready"]?.jsonPrimitive?.booleanOrNull == true) {
                                status.string("error", "OMP 会话管理通道初始化失败")
                            }
                            channelReady = true
                        }
                        channelStamp = stamp
                        channelSize = size
                    }
                    delay(25)
                }
                true
            } ?: false
            check(received) {
                if (channelReady) "OMP 会话管理请求超时，请重试"
                else "OMP 会话管理扩展未就绪，历史树与 Agent Hub 暂不可用"
            }
        }

        suspend fun managementControl(op: String, provider: String? = null, accountId: String? = null, expectedAttempt: ChatAttempt? = activeAttempt): JsonObject = exclusive {
            val attempt = expectedAttempt ?: error("OMP 进程未启动")
            if (activeAttempt !== attempt || interrupted(attempt)) throw OmpProcessStoppedException()
            check(process?.isAlive == true) { "Runtime account management requires a running session" }
            require(op == "pins" || (!state.value.isRunning && backendSettled)) { "Session is busy" }
            val requestId = UUID.randomUUID().toString()
            val directory = confined(ownedSessionDirectory(containerId, id), "management")
            check(directory.isDirectory || directory.mkdirs())
            val requestFile = File(directory, "request-$requestId.json")
            val responseFile = File(directory, "response-$requestId.json")
            try {
                atomicWrite(requestFile, buildJsonObject { put("id", requestId); put("instance", attempt.managementInstance); put("op", op); provider?.let { put("provider", it) }; put("accountId", accountId?.let(::JsonPrimitive) ?: JsonNull) }.toString())
                awaitManagementResponse(attempt, directory, responseFile, 15_000)
                val response = DriverJson.parseToJsonElement(responseFile.readText()).jsonObject
                if (activeAttempt !== attempt || interrupted(attempt)) throw OmpProcessStoppedException()
                check(response.string("instance") == attempt.managementInstance) { "OMP 会话管理响应实例不匹配" }
                check(response["success"]?.jsonPrimitive?.booleanOrNull == true) { "Session is busy or account capability is unavailable" }
                response
            } finally { requestFile.delete(); responseFile.delete() }
        }

        private suspend fun explorerRequest(op: String, parameters: JsonObject): JsonObject {
            val containerGeneration = currentContainerStopGeneration()
            // Startup alone is serialized with prompt admission; side-channel responses must not
            // wait behind a running prompt which may itself be waiting for an explorer UI answer.
            if (process?.isAlive != true) {
                val emptyHistory = exclusive {
                    if (runtime.wasInterrupted(containerId, containerGeneration)) throw OmpProcessStoppedException()
                    if (op == "explorer_history" && newestHistory(ownedSessionDirectory(containerId, id)) == null && backendState.feed.isEmpty()) true
                    else { ensureStarted(restoreQueuedPrompts = false); false }
                }
                if (emptyHistory) return buildJsonObject {
                    put("success", true); put("sessionId", ""); put("nodes", JsonArray(emptyList())); put("leafId", JsonNull)
                    put("sessionFile", JsonNull); put("revision", ""); put("reset", true); put("removedIds", JsonArray(emptyList())); put("nextCursor", JsonNull)
                }
            }
            val attempt = activeAttempt ?: error("OMP 进程未启动")
            if (interrupted(attempt) || runtime.wasInterrupted(containerId, containerGeneration)) throw OmpProcessStoppedException()
            val requestId = UUID.randomUUID().toString()
            val directory = confined(ownedSessionDirectory(containerId, id), "management")
            check(directory.isDirectory || directory.mkdirs())
            val requestFile = File(directory, "request-$requestId.json")
            val responseFile = File(directory, "response-$requestId.json")
            try {
                atomicWrite(requestFile, buildJsonObject {
                    parameters.forEach { (key, value) -> put(key, value) }
                    put("id", requestId); put("op", op)
                    put("instance", attempt.managementInstance)
                    put("sessionId", (explorer.history.value.sessionId.takeIf { it.isNotBlank() }
                        ?: explorer.hub.value.mainSessionId.takeIf { it.isNotBlank() })?.let(::JsonPrimitive) ?: JsonNull)
                }.toString())
                awaitManagementResponse(attempt, directory, responseFile, if (op == "explorer_navigate") 600_000 else 180_000)
                val response = DriverJson.parseToJsonElement(responseFile.readText()).jsonObject
                if (activeAttempt !== attempt || interrupted(attempt)) throw OmpProcessStoppedException()
                check(response.string("instance") == attempt.managementInstance) { "OMP 会话管理响应实例不匹配" }
                check(response["success"]?.jsonPrimitive?.booleanOrNull == true) { response.string("error", "原生会话操作失败") }
                return response
            } finally { requestFile.delete(); responseFile.delete() }
        }

        private suspend fun synchronizeNativeHistory(history: JsonObject) {
            stateMutex.withLock {
                recordLocal(buildJsonObject {
                    put("action", "native_branch"); put("entries", history["entries"] ?: JsonArray(emptyList()))
                    put("leafId", history["leafId"] ?: JsonNull)
                })
                requests.clear()
            }
            command("get_messages")
            command("get_state")
            command("get_session_stats")
        }

        private suspend fun restoreExplorerQueue() {
            val queued = deferredExplorerQueue
            for ((index, item) in queued.withIndex()) {
                try {
                    val prepared = prepareAttachments(item.text, item.attachments)
                    enqueue(prepared.first, prepared.second, prepared.third, item.id, queue = item.queue)
                    deferredExplorerQueue = queued.drop(index + 1)
                } catch (failure: Throwable) {
                    deferredExplorerQueue = queued.drop(index)
                    mutate { it }
                    throw failure
                }
            }
        }

        private suspend fun ensureStarted(restoreQueuedPrompts: Boolean = true) = lifecycleMutex.withLock {
            check(!disposed) { "会话已关闭" }
            if (process?.isAlive == true && activeAttempt?.let { !interrupted(it) } == true) return@withLock
            val startupGeneration = stopGeneration.get()
            val containerGeneration = currentContainerStopGeneration()
            repairing = false
            activeAttempt?.intentional = true
            process?.let { stopProcess(it) }
            process = null
            activeAttempt?.processJob?.cancel()
            activeAttempt?.heartbeat?.cancel()
            activeAttempt?.pending?.values?.forEach { it.completeExceptionally(OmpProcessStoppedException()) }
            val attempt = ChatAttempt(startupGeneration, containerGeneration)
            val startReady = attempt.ready
            activeAttempt = attempt
            if (parkRequested) { attempt.intentional = true; throw OmpProcessStoppedException() }
            val directory = ownedSessionDirectory(containerId, id)
            directory.mkdirs()
            val workspaceDirectory = resolveWorkspace(containerId, workspace)
            if (!workspaceDirectory.exists()) check(resolveWorkspace(containerId, workspace, write = true).mkdirs()) { "无法创建工作区" }
            installContainerFeatureSkill(runtime, containerId)
            val history = newestHistory(directory)
            sessionFile = history?.let { guestSessionDirectory(containerId, id) + "/" + it.name }
            val args = mutableListOf("omp", "--mode", "rpc-ui", "--cwd", workspace, "--session-dir", guestSessionDirectory(containerId, id), "--approval-mode", if (state.value.autoApprove) "yolo" else "always-ask")
            sessionFile?.let { args += listOf("--resume", it) }
            // Display-only defaults are resolved again by native startup; only pin explicit selections.
            if (hasModelPreference && state.value.providerId.isNotBlank() && state.value.modelId.isNotBlank()) args += listOf("--provider", state.value.providerId, "--model", state.value.modelId)
            prepareOmpConfiguration({ File(config(containerId).rootfsPath) }) { agentDirectory(containerId) }
            confined(agentDirectory(containerId), "wawlink-model-payload.mjs")
            args += listOf("--extension", ModelPayloadExtension.install(agentDirectory(containerId), guestAgentDirectory(containerId)))
            for (module in listOf("session.mjs", "diagnostics.mjs", "explorer.mjs", "explorer-runtime.mjs", "explorer-history.mjs")) {
                val destination = confined(agentDirectory(containerId), "wawlink/management/$module")
                val source = requireNotNull(javaClass.getResourceAsStream("/omp-management/$module")).bufferedReader().use { it.readText() }
                if (!destination.exists() || destination.readText() != source) repoAtomicWrite(destination, source)
            }
            args += listOf("--extension", "${guestAgentDirectory(containerId)}/wawlink/management/session.mjs")
            managementDefault?.let { role ->
                val provider = requireNotNull(role.providerId)
                val modelId = requireNotNull(role.modelId)
                val providerIndex = args.indexOf("--provider")
                if (providerIndex >= 0) { args[providerIndex + 1] = provider; args[args.indexOf("--model") + 1] = modelId }
                else args += listOf("--provider", provider, "--model", modelId)
            }
            if (managementDefault?.thinking != null) args += listOf("--thinking", requireNotNull(managementDefault?.thinking))
            else if (hasThinkingPreference) args += listOf("--thinking", thinkingWire(state.value.thinkingLevel))
            val restoredQueue = if (restoreQueuedPrompts) backendState.queuedPrompts.toList() else emptyList()
            if (!restoreQueuedPrompts) deferredExplorerQueue = backendState.queuedPrompts.toList()
            else deferredExplorerQueue = emptyList()
            if (interrupted(attempt) || startupGeneration != stopGeneration.get()) throw OmpProcessStoppedException()
            // Bun snapshots DNS servers; resume a fresh process on the next idle send after a network change.
            startedResolvConf = resolvConf?.value
            val child = try {
                runtime.openProcess(containerId, args, workspace, mapOf("PI_NO_PTY" to "1", "PI_NO_TITLE" to "1",
                    "WAWLINK_MANAGEMENT_DIRECTORY" to "${guestSessionDirectory(containerId, id)}/management",
                    "WAWLINK_MANAGEMENT_INSTANCE" to attempt.managementInstance))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (interrupted(attempt) || startupGeneration != stopGeneration.get()) throw OmpProcessStoppedException()
                reportChatFailure(attempt, inspectInstallation = true)
                throw failure
            }
            if (interrupted(attempt) || startupGeneration != stopGeneration.get()) {
                attempt.intentional = true
                stopProcess(child)
                throw OmpProcessStoppedException()
            }
            process = child
            restartRequired = false
            val childJob = SupervisorJob(ownerJob)
            attempt.processJob = childJob
            val childScope = CoroutineScope(sessionScope.coroutineContext + childJob)
            childScope.launch { drainStderr(child) }
            childScope.launch { readOutput(child, attempt) }
            try {
                val greeting = withTimeoutOrNull(180_000) { startReady.await() } ?: run {
                    reportChatFailure(attempt)
                    throw IllegalStateException("OMP 启动通信超时，请重试")
                }
                if (greeting["supportedProtocolVersions"]?.jsonArray?.any { it.jsonPrimitive.intOrNull == 2 } == true) command("negotiate_protocol", buildJsonObject { put("protocolVersion", 2) })
                command("set_event_filter", buildJsonObject { put("events", JsonNull); put("messageUpdates", "delta") })
                command("set_ask_dialog", buildJsonObject { put("enabled", true) })
                if (restoreQueuedPrompts) command("set_session_name", buildJsonObject { put("name", state.value.title) })
                command("get_state")
                command("get_available_commands")
                command("get_session_stats")
                if (backendState.feed.isEmpty() && history != null) {
                    val native = command("get_entries")["data"] as? JsonObject
                    if (native != null) {
                        local(buildJsonObject {
                            put("action", "native_branch"); put("entries", native["entries"] ?: JsonArray(emptyList()))
                            put("leafId", native["leafId"] ?: JsonNull)
                        })
                        command("get_state")
                    }
                }
                for ((index, queued) in restoredQueue.withIndex()) {
                    if (interrupted(attempt) || startupGeneration != stopGeneration.get()) {
                        for (unsubmitted in restoredQueue.drop(index)) local(buildJsonObject {
                            put("action", "queue_add"); put("queueId", unsubmitted.id); put("text", unsubmitted.text)
                            put("queue", unsubmitted.queue.wireName)
                            put("attachments", attachmentsJson(unsubmitted.attachments))
                        })
                        break
                    }
                    val prepared = prepareAttachments(queued.text, queued.attachments)
                    local(buildJsonObject {
                        put("action", "queue_add"); put("queueId", queued.id); put("text", prepared.first)
                        put("queue", queued.queue.wireName)
                        put("attachments", attachmentsJson(prepared.second))
                    })
                    command(if (queued.queue == PromptQueue.STEERING) "steer" else "follow_up", buildJsonObject { put("message", prepared.first); if (prepared.third.isNotEmpty()) put("images", prepared.third) }, timeoutMs = 180_000)
                }
                lastHeartbeatNanos = System.nanoTime()
                attempt.heartbeat = childScope.launch {
                    try {
                        while (isActive && child.isAlive) {
                            delay(1_000)
                            if (state.value.isRunning) mutate { it.copy(elapsedSec = ((System.nanoTime() - startedNanos) / 1_000_000_000).toInt()) }
                            if (System.nanoTime() - lastHeartbeatNanos >= 5_000_000_000L) {
                                command("get_state", timeoutMs = 25_000, heartbeat = true, expectedAttempt = attempt)
                                lastHeartbeatNanos = System.nanoTime()
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        sessionScope.launch {
                            if (activeAttempt !== attempt) return@launch
                            val stopped = interrupted(attempt) || failure is OmpProcessStoppedException
                            park(attempt)
                            if (!stopped) fail(failure, attempt)
                        }
                    }
                }
                saveMetadata()
            } catch (failure: Exception) {
                val stopped = interrupted(attempt) || repairing || startupGeneration != stopGeneration.get()
                if (stopped) for (queued in restoredQueue) {
                    if (stateMutex.withLock { !reducer.hasConfirmedUser(queued.id) } && backendState.queuedPrompts.none { it.id == queued.id }) local(buildJsonObject {
                        put("action", "queue_add"); put("queueId", queued.id); put("text", queued.text)
                        put("queue", queued.queue.wireName); put("attachments", attachmentsJson(queued.attachments))
                    })
                }
                if (process === child) process = null
                attempt.intentional = true
                childJob.cancel()
                stopProcess(child)
                if (failure is CancellationException) throw failure
                if (stopped) throw OmpProcessStoppedException()
                throw IllegalStateException("OMP 启动失败：${failure.message}", failure)
            }
        }

        private suspend fun command(type: String, parameters: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 45_000, heartbeat: Boolean = false,
            requestId: String = if (heartbeat) "hb-${sequence.incrementAndGet()}" else "req-${sequence.incrementAndGet()}",
            expectedAttempt: ChatAttempt? = activeAttempt): JsonObject {
            val attempt = expectedAttempt ?: error("OMP 进程未启动")
            if (activeAttempt !== attempt || interrupted(attempt)) throw OmpProcessStoppedException()
            val completion = CompletableDeferred<JsonObject>()
            attempt.pending[requestId] = completion
            try {
                writeFrame(buildJsonObject { put("id", requestId); put("type", type); parameters.forEach { (key, value) -> put(key, value) } }, attempt)
                val response = withTimeoutOrNull(timeoutMs) { completion.await() } ?: run {
                    reportChatFailure(attempt)
                    throw IllegalStateException("OMP 请求超时：$type")
                }
                if (activeAttempt !== attempt || interrupted(attempt)) throw OmpProcessStoppedException()
                check(response["success"]?.jsonPrimitive?.booleanOrNull != false) { response.string("error", "OMP 请求失败：$type") }
                return response
            } catch (failure: Exception) {
                if (failure !is CancellationException && (activeAttempt !== attempt || interrupted(attempt))) throw OmpProcessStoppedException()
                throw failure
            } finally { attempt.pending.remove(requestId) }
        }

        private suspend fun writeFrame(frame: JsonObject, expectedAttempt: ChatAttempt? = activeAttempt) = writeMutex.withLock {
            val attempt = expectedAttempt ?: error("OMP 进程未启动")
            if (activeAttempt !== attempt || interrupted(attempt)) throw OmpProcessStoppedException()
            val child = process ?: error("OMP 进程未启动")
            try {
                check(child.isAlive) { "OMP 进程已退出" }
                val encoded = frame.toString().toByteArray(Charsets.UTF_8)
                child.outputStream.write(encoded)
                child.outputStream.write('\n'.code)
                child.outputStream.flush()
            } catch (failure: Exception) {
                if (failure !is CancellationException) reportChatFailure(attempt, inspectInstallation = !attempt.nativeReady)
                if (activeAttempt !== attempt || interrupted(attempt)) throw OmpProcessStoppedException()
                throw failure
            }
        }

        private suspend fun readOutput(child: Process, attempt: ChatAttempt) {
            val decoder = OmpFrameDecoder()
            var failure: Throwable? = null
            var protocolFailure = false
            try {
                child.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (currentCoroutineContext().isActive) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        val physical = DriverJson.parseToJsonElement(line) as? JsonObject ?: error("OMP 输出不是 JSON 对象")
                        val frame = decoder.push(physical) ?: continue
                        if (activeAttempt !== attempt || interrupted(attempt)) break
                        receive(frame, attempt)
                    }
                }
                if (process === child) {
                    val status = if (child.waitFor(250, TimeUnit.MILLISECONDS)) "，退出码 ${child.exitValue()}" else ""
                    failure = IllegalStateException("OMP 输出流已关闭$status")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { protocolFailure = true; failure = IllegalStateException("OMP 通信响应损坏，请修复后重试") }
            finally {
                if (process === child && failure != null) {
                    val stopped = interrupted(attempt)
                    if (!stopped) reportChatFailure(attempt, inspectInstallation = !attempt.nativeReady && !protocolFailure)
                    process = null
                    backendSettled = true
                    val reason = if (stopped) OmpProcessStoppedException() else failure
                    attempt.ready.completeExceptionally(reason)
                    attempt.pending.values.forEach { it.completeExceptionally(reason) }
                    attempt.heartbeat?.cancel()
                    stopProcess(child)
                    if (stopped) {
                        stateMutex.withLock {
                            if (activeAttempt === attempt) {
                                requests.clear()
                                recordLocal(buildJsonObject { put("action", "stopped") })
                            }
                        }
                    } else fail(reason, attempt)
                }
            }
        }

        private suspend fun receive(frame: JsonObject, attempt: ChatAttempt) {
            val type = frame.string("type")
            if (type == "extension_error" && frame.string("extensionPath") == "${guestAgentDirectory(containerId)}/wawlink/management/session.mjs") {
                attempt.managementFailure = "OMP 会话管理扩展加载或初始化失败，历史树与 Agent Hub 暂不可用"
            }
            if (type == "agent_start") { startedNanos = System.nanoTime(); backendSettled = false }
            if (type == "session_settled") backendSettled = true
            if (type == "prompt_result") frame["sessionSettled"]?.jsonPrimitive?.booleanOrNull?.let { backendSettled = it }
            if (type == "response" && frame.string("command") == "get_state") {
                frame["data"]?.jsonObject?.get("isSettled")?.jsonPrimitive?.booleanOrNull?.let { backendSettled = it }
                frame["data"]?.jsonObject?.get("sessionFile")?.jsonPrimitive?.contentOrNull?.let { path ->
                    require(path.startsWith(guestSessionDirectory(containerId, id) + "/")) { "OMP 会话离开受管目录，拒绝覆盖其他会话" }
                    if (sessionFile != path) { sessionFile = path; saveMetadata() }
                }
            }
            val autoApproval = stateMutex.withLock {
                if (activeAttempt !== attempt || interrupted(attempt)) return
                if (type == "extension_ui_request") {
                    when (frame.string("method")) {
                        "select", "confirm", "ask", "input", "editor" -> requests[frame.string("id")] = frame
                        "cancel" -> requests.remove(frame.string("targetId"))
                    }
                }
                // Command metadata is process-local; it must not replay stale plugins after restart.
                val recorded = if (type != "available_commands_update" && !(type == "response" &&
                    (frame.string("id").startsWith("hb-") || frame.string("command") == "get_available_commands"))) appendJournal(frame) else frame
                reduceNative(recorded)
                val automatic = type == "extension_ui_request" && reducer.state.autoApprove && isApprovalRequest(frame)
                if (!automatic) publishState()
                automatic
            }
            if (autoApproval) answerApproval(frame.string("id"), ApprovalDecision.ALLOW_ONCE)
            if (type == "agent_end" || type == "session_settled") local(buildJsonObject {
                put("action", "elapsed"); put("seconds", ((System.nanoTime() - startedNanos) / 1_000_000_000).toInt())
            })
            if (type == "response") {
                if (frame["success"]?.jsonPrimitive?.booleanOrNull == false) rejectPrompt(frame.string("id"), frame.string("error", "OMP 请求失败"))
                else if (frame.string("command") == "follow_up" || frame.string("command") == "steer") completePrompt(frame.string("id"))
                else if (frame.string("command") == "prompt" && frame["data"]?.jsonObject?.get("agentInvoked")?.jsonPrimitive?.booleanOrNull == false) {
                    completePrompt(frame.string("id"))
                    sessionScope.launch {
                        runCatching { command("get_state", expectedAttempt = attempt) }.onFailure { if (it !is CancellationException && activeAttempt === attempt && !interrupted(attempt) && process != null) fail(it) }
                    }
                }
            }
            if (type == "prompt_result") {
                if (frame["agentInvoked"]?.jsonPrimitive?.booleanOrNull == false && frame.string("status") == "error") {
                    rejectPrompt(frame.string("id"), frame["error"]?.jsonObject?.string("message", "消息发送失败") ?: "消息发送失败")
                } else completePrompt(frame.string("id"))
            }
            if (type == "ready") { hasStartedRpc = true; attempt.nativeReady = true; attempt.ready.complete(frame) }
            if (type == "response") attempt.pending[frame.string("id")]?.complete(frame)
            if (type == "session_info_update") saveMetadata()
            if (type == "model_changed" || type == "config_update") sessionScope.launch {
                runCatching { command("get_state", expectedAttempt = attempt) }.onFailure { if (it !is CancellationException && activeAttempt === attempt && !interrupted(attempt) && process != null) fail(it) }
            }
            if (type == "auto_compaction_start" || type == "auto_compaction_end" || (type == "response" && frame.string("command") == "compact")) {
                sessionScope.launch {
                    runCatching { command("get_entries", expectedAttempt = attempt) }.onFailure { if (it !is CancellationException && activeAttempt === attempt && !interrupted(attempt) && process != null) fail(it) }
                }
            }
            if (type == "session_settled" || (type == "agent_end" && frame["isTerminal"]?.jsonPrimitive?.booleanOrNull != false)) {
                sessionScope.launch {
                    runCatching { command("get_session_stats", expectedAttempt = attempt) }.onFailure { if (it !is CancellationException && activeAttempt === attempt && !interrupted(attempt) && process != null) fail(it) }
                }
            }
        }

        private suspend fun drainStderr(child: Process) {
            // Native provider errors can contain credentials; never retain or forward stderr.
            try {
                child.errorStream.use { stream ->
                    val buffer = ByteArray(4096)
                    while (currentCoroutineContext().isActive && stream.read(buffer) >= 0) { }
                }
            } catch (_: java.io.IOException) { }
        }


        private fun reportChatFailure(attempt: ChatAttempt, inspectInstallation: Boolean = false) {
            if (interrupted(attempt) || repairing || disposed || attempt.generation != stopGeneration.get() || !attempt.reported.compareAndSet(false, true)) return
            sessionScope.launch {
                if (repairing || disposed || attempt.generation != stopGeneration.get() || runtime.wasInterrupted(containerId, attempt.containerStopGeneration)) return@launch
                val message = if (inspectInstallation) {
                    val check = try { OmpInstallation.inspect(runtime, containerId, attempt.containerStopGeneration, runningOnly = true) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { return@launch }
                    if (check.state == OmpInstallationState.READY) return@launch
                    "容器中的 OMP 缺失或安装损坏，请在终端中安装修复"
                } else "容器中的 OMP 进程异常或通信失效，请在终端中修复"
                if (!repairing && !disposed && attempt.generation == stopGeneration.get()) reportRuntimeIssue(containerId, attempt.containerStopGeneration, message)
            }
        }

        private fun beginIntentionalStop() {
            stopGeneration.incrementAndGet()
            parkRequested = true
            activeAttempt?.intentional = true
            activeAttempt?.ready?.completeExceptionally(OmpProcessStoppedException())
            activeAttempt?.pending?.values?.forEach { it.completeExceptionally(OmpProcessStoppedException()) }
        }

        suspend fun parkForRepair() {
            repairing = true
            beginIntentionalStop()
            process?.let { stopProcess(it) }
            park()
            mutate { it.copy(modelLoading = false) }
        }

        suspend fun park(expectedAttempt: ChatAttempt? = activeAttempt) = lifecycleMutex.withLock {
            if (activeAttempt !== expectedAttempt) return@withLock
            activeAttempt?.intentional = true
            val child = process
            process = null
            backendSettled = true
            expectedAttempt?.heartbeat?.cancel(); expectedAttempt?.heartbeat = null
            if (child != null) {
                // stdin EOF is the actual RPC shutdown protocol; do not invent a shutdown command.
                writeMutex.withLock { runCatching { child.outputStream.close() } }
                if (!child.waitFor(8, TimeUnit.SECONDS)) stopProcess(child)
            }
            expectedAttempt?.processJob?.cancel(); expectedAttempt?.processJob = null
            val closedFailure = OmpProcessStoppedException()
            expectedAttempt?.pending?.values?.forEach { it.completeExceptionally(closedFailure) }
            expectedAttempt?.pending?.clear()
            stateMutex.withLock { requests.clear() }
            if (backendState.isRunning) local(buildJsonObject { put("action", "stopped") })
            saveMetadata()
            parkRequested = false
        }

        fun dispose() {
            disposed = true
            actions.close()
            ownerJob.cancel()
        }

        private suspend fun fail(problem: Throwable, expectedAttempt: ChatAttempt? = null) = stateMutex.withLock {
            if (problem is OmpProcessStoppedException || repairing || (expectedAttempt != null && activeAttempt !== expectedAttempt)) return@withLock
            recordLocal(buildJsonObject {
                put("action", if (process?.isAlive == true) "operation_error" else "error")
                put("message", problem.message ?: problem.javaClass.simpleName)
            })
        }

        private fun publishState() = synchronized(presentationLock) {
            if (deferredExplorerQueue.isNotEmpty()) reducer.update {
                it.copy(queuedPrompts = (it.queuedPrompts + deferredExplorerQueue).distinctBy { queued -> queued.id })
            }
            backendState = reducer.state
            backendUserPosition = reducer.currentUserPosition()
            presentState()
        }

        /** Called only under presentationLock; never waits for disk, attachments, or the RPC actor. */
        private fun presentState() {
            var feed: List<FeedItem>? = null
            var queue: MutableList<QueuedPrompt>? = null
            for (prompt in outgoingPrompts.values) {
                if (!prompt.preview) continue
                if (prompt.queued) {
                    if (queue == null) queue = backendState.queuedPrompts.toMutableList()
                    queue.add(QueuedPrompt(prompt.id, prompt.text, prompt.attachments, prompt.queue))
                } else {
                    feed = placeUserMessage(feed ?: backendState.feed,
                        UserMessageItem(prompt.id, prompt.text, prompt.attachments, prompt.timestamp, accepted = false), prompt.position)
                }
            }
            mutableState.value = if (feed == null && queue == null) backendState else backendState.copy(
                feed = feed ?: backendState.feed, queuedPrompts = queue ?: backendState.queuedPrompts,
                status = if (feed != null) SessionStatus.RUNNING else backendState.status,
                actionText = if (feed != null) "正在发送" else backendState.actionText,
                error = if (feed != null) null else backendState.error,
                elapsedSec = if (feed != null) 0 else backendState.elapsedSec)
        }

        private fun completePrompt(id: String) = synchronized(presentationLock) {
            if (outgoingPrompts.remove(id) != null) presentState()
        }

        private suspend fun rejectPrompt(id: String, message: String) = stateMutex.withLock {
            synchronized(presentationLock) { outgoingPrompts.remove(id) } ?: return@withLock
            recordLocal(buildJsonObject {
                put("action", if (reducer.hasConfirmedUser(id)) "operation_error" else "prompt_failed")
                put("promptId", id); put("message", message); put("settled", backendSettled)
            })
        }

        private suspend fun mutate(transform: (ChatSessionUiState) -> ChatSessionUiState) = stateMutex.withLock {
            reducer.update(transform)
            publishState()
        }

        private suspend fun local(payload: JsonObject) = stateMutex.withLock { recordLocal(payload) }

        private fun recordLocal(payload: JsonObject) {
            val frame = appendJournal(buildJsonObject {
                put("type", "wawlink_local"); put("localId", UUID.randomUUID().toString()); payload.forEach { (key, value) -> put(key, value) }
            })
            applyLocal(frame)
            synchronized(presentationLock) {
                val promptId = when (frame.string("action")) {
                    "user" -> frame.string("localId")
                    "queue_add" -> frame.string("queueId")
                    else -> null
                }
                outgoingPrompts[promptId]?.preview = false
                backendState = reducer.state
                backendUserPosition = reducer.currentUserPosition()
                presentState()
            }
        }

        private fun reduceNative(frame: JsonObject) {
            val type = frame.string("type")
            if (type == "agent_start") dismissedRunError = null
            reducer.reduce(frame)
            // A run's terminal frames can repeat the error already dismissed mid-run.
            val repeatsRunError = type == "agent_end" || type == "session_settled" ||
                (type == "prompt_result" && frame["agentInvoked"]?.jsonPrimitive?.booleanOrNull != false)
            if (repeatsRunError && dismissedRunError != null && reducer.state.error == dismissedRunError) {
                reducer.update { it.copy(error = null) }
            } else if (reducer.state.error != null) dismissedRunError = null
        }

        private fun applyLocal(frame: JsonObject) {
            val action = frame.string("action")
            if (action == "error" || action == "operation_error" || action == "prompt_failed") dismissedRunError = null
            when (action) {
                "raw_input" -> reducer.rememberUserText(frame.string("promptId"), frame.string("text"))
                "native_branch" -> reducer.restoreNativeBranch((frame["entries"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }, frame["leafId"]?.jsonPrimitive?.contentOrNull)
                "user" -> {
                    val position = frame["position"] as? JsonObject
                    reducer.addUserMessage(UserMessageItem(frame.string("localId"), frame.string("text"), parseAttachments(frame["attachments"]),
                        frame["timestamp"]?.jsonPrimitive?.longOrNull ?: 0, accepted = false), position?.let(::parsePosition) ?: reducer.currentUserPosition())
                    if (frame["preserveRun"]?.jsonPrimitive?.booleanOrNull != true)
                        reducer.update { it.copy(status = SessionStatus.RUNNING, error = null, actionText = "正在处理", elapsedSec = 0) }
                }
                "prompt_cancelled" -> if (!reducer.hasConfirmedUser(frame.string("promptId"))) reducer.update {
                    it.copy(feed = removeFeedItem(it.feed, frame.string("promptId")))
                }
                "prompt_failed" -> reducer.update { it.copy(
                    feed = removeFeedItem(it.feed, frame.string("promptId")),
                    queuedPrompts = it.queuedPrompts.filterNot { queued -> queued.id == frame.string("promptId") },
                    status = if (frame["settled"]?.jsonPrimitive?.booleanOrNull == true) SessionStatus.ERROR else it.status,
                    actionText = if (frame["settled"]?.jsonPrimitive?.booleanOrNull == true) "发送失败" else it.actionText,
                    error = frame.string("message")) }
                "queue_add" -> {
                    val position = frame["position"] as? JsonObject
                    reducer.reserveUserPosition(frame.string("queueId"), position?.let(::parsePosition) ?: reducer.currentUserPosition())
                    reducer.update { current -> current.copy(queuedPrompts =
                        current.queuedPrompts.filterNot { it.id == frame.string("queueId") } +
                            QueuedPrompt(frame.string("queueId"), frame.string("text"), parseAttachments(frame["attachments"]),
                                PromptQueue.entries.firstOrNull { it.wireName == frame.string("queue") } ?: PromptQueue.FOLLOW_UP)) }
                }
                "queue_edit" -> reducer.update { current -> current.copy(queuedPrompts = current.queuedPrompts.map { queued ->
                    if (queued.id == frame.string("queueId")) queued.copy(text = frame.string("text"), attachments = parseAttachments(frame["attachments"])) else queued
                }) }
                "queue_remove" -> reducer.update { it.copy(queuedPrompts = it.queuedPrompts.filterNot { queued -> queued.id == frame.string("queueId") }) }
                "title" -> reducer.update { it.copy(title = frame.string("title")) }
                "model" -> {
                    hasModelPreference = true
                    reducer.update { it.copy(providerId = frame.string("provider"), modelId = frame.string("model")) }
                }
                "thinking" -> {
                    hasThinkingPreference = true
                    reducer.update { it.copy(thinkingLevel = ThinkingLevel.valueOf(frame.string("level"))) }
                }
                "auto" -> {
                    hasExecutionPreference = true
                    reducer.update { it.copy(autoApprove = frame["enabled"]!!.jsonPrimitive.boolean) }
                }
                "stopped" -> reducer.update { it.copy(status = SessionStatus.STOPPED, actionText = "已停止",
                    feed = stopFeed(it.feed, frame["_wawlinkAtMillis"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis())) }
                "error" -> reducer.update { it.copy(status = SessionStatus.ERROR, error = frame.string("message"), actionText = "执行失败",
                    feed = stopFeed(it.feed, frame["_wawlinkAtMillis"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(), NodeStatus.ERROR)) }
                "operation_error" -> reducer.update { it.copy(error = frame.string("message")) }
                "clear_error" -> {
                    dismissedRunError = reducer.state.error.takeUnless { it == previewError }
                    dismissedPreviewHash = frame["previewHash"]?.jsonPrimitive?.contentOrNull
                    previewError = null
                    reducer.update { it.copy(error = null) }
                }
                "preview_recovered" -> dismissedPreviewHash = null
                "elapsed" -> reducer.update { it.copy(elapsedSec = frame["seconds"]!!.jsonPrimitive.int) }
                "approval" -> reducer.update { current ->
                    val feed = mapFeed(current.feed) { node -> if (node is ApprovalCardItem && node.id == frame.string("nodeId")) node.copy(resolution = ApprovalDecision.valueOf(frame.string("decision"))) else node }
                    current.copy(feed = feed, status = if (hasUnresolvedDialog(feed)) SessionStatus.WAITING else SessionStatus.RUNNING, error = null, actionText = "继续执行")
                }
                "ask" -> reducer.update { current ->
                    val answers = parseAskAnswers(frame["answers"])
                    val feed = mapFeed(current.feed) { node ->
                        if (node is AskCardItem && node.id == frame.string("nodeId")) node.copy(answers = answers, cancelled = false) else node
                    }
                    current.copy(feed = feed, status = if (hasUnresolvedDialog(feed)) SessionStatus.WAITING else SessionStatus.RUNNING, error = null)
                }
            }
        }

        private fun positionJson(position: UserMessagePosition) = buildJsonObject {
            put("groupId", position.groupId); put("afterId", position.afterId)
        }

        private fun parsePosition(value: JsonObject) = UserMessagePosition(
            value["groupId"]?.jsonPrimitive?.contentOrNull, value["afterId"]?.jsonPrimitive?.contentOrNull)

        private fun removeFeedItem(feed: List<FeedItem>, id: String): List<FeedItem> = feed.mapNotNull { item ->
            when {
                item.id == id -> null
                item is StepGroupNodeItem -> item.copy(children = removeFeedItem(item.children, id))
                else -> item
            }
        }

        private fun replayJournal(): Long? {
            val file = eventJournal
            if (!file.exists()) return null
            var lastRecordedAt: Long? = null
            file.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) {
                        try {
                            val frame = DriverJson.parseToJsonElement(line).jsonObject
                            frame["_wawlinkAtMillis"]?.jsonPrimitive?.longOrNull?.let { lastRecordedAt = it }
                            if (frame.string("type") == "wawlink_local") {
                                if (frame.string("action") == "ask" && frame["answers"] == null) {
                                    // Read old per-question journal answers into the grouped native card.
                                    val oldId = frame.string("nodeId")
                                    val requestId = oldId.substringBeforeLast(":q:", oldId)
                                    val card = findAskCard(reducer.state.feed, requestId)
                                    val index = oldId.substringAfterLast(":q:", "0").toIntOrNull() ?: 0
                                    val question = card?.questions?.getOrNull(index)
                                    if (question != null) {
                                        val value = frame.string("answer")
                                        val answer = if (question.options.any { it.label == value }) AskAnswer(question.id, listOf(value))
                                            else AskAnswer(question.id, customInput = value)
                                        val restored = card.answers.orEmpty().filterNot { it.questionId == question.id } + answer
                                        reducer.update { state -> state.copy(feed = mapFeed(state.feed) { node ->
                                            if (node is AskCardItem && node.id == requestId) node.copy(answers = restored) else node
                                        }) }
                                    }
                                } else applyLocal(frame)
                            } else reduceNative(frame)
                        } catch (failure: Exception) {
                            // An interrupted final append must not erase the readable transcript prefix.
                            reducer.update { it.copy(error = "历史记录部分损坏：${failure.message}", status = SessionStatus.ERROR) }
                        }
                    }
                }
            }
            return lastRecordedAt
        }

        private fun appendJournal(frame: JsonObject): JsonObject {
            val recorded = JsonObject(frame + ("_wawlinkAtMillis" to JsonPrimitive(System.currentTimeMillis())))
            val file = eventJournal
            file.parentFile.mkdirs()
            FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(recorded.toString())
                writer.write('\n'.code)
            }
            return recorded
        }

        private fun saveMetadata() = writeMetadata(id, containerId, workspace, reducer.state.title, sessionFile)

        private suspend fun prepareAttachments(text: String, attachments: List<AttachmentFile>, workspace: String = this.workspace): Triple<String, List<AttachmentFile>, JsonArray> {
            if (attachments.isEmpty()) return Triple(text, emptyList(), JsonArray(emptyList()))
            var workspaceRoot: File? = null
            var containerFiles: ContainerFileService? = null
            val transferred = ArrayList<AttachmentFile>(attachments.size)
            val images = ArrayList<JsonElement>()
            for (attachment in attachments) {
                val image = when (attachment.mimeType) { "image/png", "image/jpeg", "image/webp", "image/gif" -> true; else -> false }
                val guestPath: String
                val sizeBytes: Long
                val imageBytes: ByteArray?
                val source: AttachmentSource
                if (attachment.source == AttachmentSource.CONTAINER) {
                    require(attachment.path.startsWith('/')) { "容器附件必须使用绝对路径" }
                    val files = containerFiles ?: ContainerFileService(runtime, config(containerId)).also { containerFiles = it }
                    guestPath = ContainerFileService.path(attachment.path)
                    val file = files.stat(guestPath)
                    require(file.regular && file.readable) { "无法读取容器附件：${attachment.name}" }
                    sizeBytes = file.size
                    imageBytes = if (image) ByteArrayOutputStream().use { output -> files.exportFile(guestPath, output); output.toByteArray() } else null
                    source = AttachmentSource.CONTAINER
                } else {
                    val root = workspaceRoot ?: resolveWorkspace(containerId, workspace, write = true).also { it.mkdirs(); workspaceRoot = it }
                    val file: File
                    if (attachment.source == AttachmentSource.WORKSPACE) {
                        val relative = if (attachment.path.startsWith(workspace.trimEnd('/') + "/")) attachment.path.removePrefix(workspace.trimEnd('/') + "/") else attachment.path
                        require(!File(relative).isAbsolute) { "附件必须位于当前工作区" }
                        file = confined(root, relative)
                    } else {
                        val local = File(attachment.path)
                        require(local.isFile && local.canRead()) { "无法读取附件：${attachment.name}" }
                        val safeName = attachment.name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").ifBlank { "attachment" }
                        file = confined(root, ".wawlink/attachments/${safeId(attachment.id)}/$safeName")
                        file.parentFile.mkdirs()
                        if (local.canonicalFile != file.canonicalFile) local.inputStream().use { input -> file.outputStream().use { input.copyTo(it) } }
                    }
                    require(file.isFile && file.canRead()) { "无法读取附件：${attachment.name}" }
                    guestPath = workspace.trimEnd('/') + "/" + file.relativeTo(root.canonicalFile).invariantSeparatorsPath
                    sizeBytes = file.length()
                    imageBytes = if (image) file.readBytes() else null
                    source = AttachmentSource.WORKSPACE
                }
                transferred += attachment.copy(path = guestPath, sizeBytes = sizeBytes, source = source)
                if (imageBytes != null) images += buildJsonObject {
                    put("type", "image"); put("mimeType", attachment.mimeType); put("data", Base64.getEncoder().encodeToString(imageBytes))
                }
            }
            return Triple(attachmentPrompt(text, transferred), transferred, JsonArray(images))
        }

        private fun attachmentPrompt(text: String, attachments: List<AttachmentFile>): String {
            if (attachments.isEmpty()) return text
            val references = attachments.joinToString("\n") { "- ${it.name}: ${it.path}" }
            val marker = "\n\n附件（容器内真实文件）：\n$references"
            return if (text.endsWith(marker)) text else text + marker
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
    put("id", file.id); put("name", file.name); put("path", file.path); put("mimeType", file.mimeType); put("sizeBytes", file.sizeBytes); put("source", file.source.name)
} })
private fun parseAttachments(element: JsonElement?): List<AttachmentFile> = (element as? JsonArray).orEmpty().map { value ->
    val file = value.jsonObject
    val source = file["source"]?.jsonPrimitive?.contentOrNull?.let(AttachmentSource::valueOf)
        ?: if (file["isWorkspaceFile"]?.jsonPrimitive?.booleanOrNull == true) AttachmentSource.WORKSPACE else AttachmentSource.LOCAL
    AttachmentFile(file.string("id"), file.string("name"), file.string("path"), file.string("mimeType", "application/octet-stream"), file["sizeBytes"]?.jsonPrimitive?.longOrNull ?: 0, source)
}
private fun mapFeed(items: List<FeedItem>, transform: (FeedItem) -> FeedItem): List<FeedItem> = items.map { item -> transform(if (item is StepGroupNodeItem) item.copy(children = mapFeed(item.children, transform)) else item) }
private fun hasUnresolvedDialog(items: List<FeedItem>): Boolean = items.any { item -> when (item) {
    is ApprovalCardItem -> item.resolution == null
    is AskCardItem -> !item.isResolved
    is StepGroupNodeItem -> hasUnresolvedDialog(item.children)
    else -> false
} }
internal fun stopFeed(items: List<FeedItem>, atMillis: Long = System.currentTimeMillis(), status: NodeStatus = NodeStatus.STOPPED): List<FeedItem> {
    fun elapsed(start: Long?, previous: Int): Int = start?.let {
        ((atMillis - it).coerceAtLeast(0) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(previous)
    } ?: previous
    return mapFeed(items) { item -> when (item) {
        is ToolNodeItem -> if (item.state == NodeStatus.RUNNING) item.copy(state = status,
            elapsedSec = elapsed(item.startedAtMillis, item.elapsedSec)) else item
        is StepGroupNodeItem -> if (item.state == NodeStatus.RUNNING) item.copy(state = status,
            elapsedSec = elapsed(item.startedAtMillis, item.elapsedSec), isExpanded = false) else item
        is ThinkingNodeItem -> if (item.isStreaming) item.copy(isStreaming = false,
            elapsedSec = elapsed(item.startedAtMillis, item.elapsedSec)) else item
        is AssistantMessageItem -> item.copy(isStreaming = false)
        is ApprovalCardItem -> if (item.resolution == null) item.copy(resolution = ApprovalDecision.DENY) else item
        is AskCardItem -> if (!item.isResolved) item.copy(cancelled = true) else item
        else -> item
    } }
}

private fun findAskCard(items: List<FeedItem>, id: String): AskCardItem? {
    for (item in items) {
        if (item is AskCardItem && item.id == id) return item
        if (item is StepGroupNodeItem) findAskCard(item.children, id)?.let { return it }
    }
    return null
}

internal fun validateAskAnswers(questions: List<AskQuestion>, answers: List<AskAnswer>): List<AskAnswer> {
    require(answers.size == questions.size && answers.map { it.questionId }.toSet().size == answers.size) { "请完整回答所有问题" }
    return questions.map { question ->
        val answer = answers.firstOrNull { it.questionId == question.id } ?: error("缺少问题的回答：${question.id}")
        require(answer.selectedOptions.distinct().size == answer.selectedOptions.size &&
            answer.selectedOptions.all { label -> question.options.any { it.label == label } }) { "回答包含无效选项" }
        val custom = answer.customInput?.takeIf { it.isNotBlank() }
        require(question.multi || (answer.selectedOptions.size <= 1 && (custom == null || answer.selectedOptions.isEmpty()))) { "此问题只能选择一个回答" }
        require(question.multi || answer.selectedOptions.isNotEmpty() || custom != null) { "请先选择选项或填写回答" }
        answer.copy(customInput = custom)
    }
}

private fun askAnswersJson(answers: List<AskAnswer>): JsonArray = buildJsonArray {
    for (answer in answers) add(buildJsonObject {
        put("id", answer.questionId)
        put("selectedOptions", JsonArray(answer.selectedOptions.map(::JsonPrimitive)))
        answer.customInput?.let { put("customInput", it) }
    })
}

private fun parseAskAnswers(value: JsonElement?): List<AskAnswer> = (value as? JsonArray).orEmpty().map { element ->
    val answer = element.jsonObject
    AskAnswer(answer.string("id"), answer["selectedOptions"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content },
        answer["customInput"]?.jsonPrimitive?.contentOrNull)
}

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
