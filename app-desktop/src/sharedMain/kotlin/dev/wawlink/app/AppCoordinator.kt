package dev.wawlink.app

import dev.wawlink.driver.OmpProcessPool
import dev.wawlink.driver.OmpInstallation
import dev.wawlink.driver.OmpInstallationState
import dev.wawlink.protocol.*
import dev.wawlink.runtime.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class HostImportFile(val name: String, val openInput: () -> java.io.InputStream)

interface HostFiles {
    suspend fun pickAttachments(): List<AttachmentFile>
    suspend fun pickRootfs(): String?
    suspend fun pickMountDirectory(): String?
    suspend fun prepareMountSource(source: String, readWrite: Boolean): String
    suspend fun exportRootfs(runtime: ContainerRuntime, container: ContainerConfig): Boolean
    suspend fun pickImportFiles(): List<HostImportFile>
    suspend fun saveFile(name: String, mimeType: String, write: suspend (java.io.OutputStream) -> Unit): Boolean
    suspend fun openFile(name: String, mimeType: String, write: suspend (java.io.OutputStream) -> Unit)
}

enum class SessionTitleSource { MANUAL, PENDING, FIRST_QUESTION }

data class SessionRecord(
    val id: String,
    val title: String,
    val containerId: String,
    val workspace: String,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    val titleSource: SessionTitleSource = SessionTitleSource.MANUAL,
)
data class HomeSession(val record: SessionRecord, val state: ChatSessionUiState)
data class HostNotice(val id: Long = System.nanoTime(), val text: String, val error: Boolean = false)

internal data class OmpSetupRequest(
    val id: String = UUID.randomUUID().toString(),
    val containerId: String,
    val message: String,
    val repair: Boolean,
    val runtimeIssueId: Long? = null,
)

class AppCoordinator(val runtime: ContainerRuntime, val pool: OmpProcessPool, val dataDir: File, val isAndroid: Boolean = false) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val persistenceLock = Any()
    private val records = MutableStateFlow<List<SessionRecord>>(emptyList())
    private val sessionStates = MutableStateFlow<Map<String, ChatSessionUiState>>(emptyMap())
    private val controllers = mutableMapOf<String, AgentSessionController>()
    private val observers = mutableMapOf<String, Job>()
    private val provisionalSessions = ConcurrentHashMap.newKeySet<String>()
    private val backgroundDrafts = ConcurrentHashMap.newKeySet<String>()
    val features = ContainerFeatureService(runtime)
    val featureCards = FeatureCardPreferences(runtime)
    val selectedContainerId: StateFlow<String?> = runtime.containers.map { items ->
        (items.firstOrNull { it.config.isDefault } ?: items.firstOrNull())?.config?.id
    }.stateIn(scope, SharingStarted.Eagerly, currentContainerId())
    private val _appearance = MutableStateFlow(AgentAppearance())
    val appearance: StateFlow<AgentAppearance> = _appearance.asStateFlow()
    private val _defaultAutoApprove = MutableStateFlow(true)
    val defaultAutoApprove: StateFlow<Boolean> = _defaultAutoApprove.asStateFlow()
    private val _keepScreenOn = MutableStateFlow(false)
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()
    private val notices = MutableStateFlow<List<HostNotice>>(emptyList())
    val notice: StateFlow<HostNotice?> = notices.map { it.firstOrNull() }.stateIn(scope, SharingStarted.Eagerly, null)
    private val ompSetupLock = Any()
    private val ompSetups = MutableStateFlow<List<OmpSetupRequest>>(emptyList())
    private val approvedOmpInstallations = mutableMapOf<String, String>()
    private val activeOmpInstallations = mutableMapOf<String, String>()
    private val handledOmpIssues = mutableMapOf<String, Long>()
    internal val ompSetup = combine(ompSetups, runtime.containers) { requests, containers ->
        requests.firstOrNull { request -> containers.any { it.config.id == request.containerId } }
    }.stateIn(scope, SharingStarted.Eagerly, null)
    private val _busy = MutableStateFlow(0)
    val busy: StateFlow<Int> = _busy.asStateFlow()
    val sessions = combine(records.map { items -> items.sortedByDescending { it.updatedAt } }, sessionStates) { items, states ->
        items.map { item -> HomeSession(item, (states[item.id] ?: ChatSessionUiState(item.id)).copy(title = item.title, workspace = item.workspace)) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    val active = combine(runtime.containers, runtime.deployments, sessions, busy) { containers, deployments, sessions, busy ->
        containers.any { it.running } || deployments.any { it.error == null && it.progress < 1f } || sessions.any { it.state.isRunning } || busy > 0
    }.stateIn(scope, SharingStarted.Eagerly, false)

    init {
        dataDir.mkdirs()
        try {
            val p = Properties()
            File(dataDir, "appearance.properties").takeIf { it.exists() }?.inputStream()?.use(p::load)
            fun flag(key: String, default: Boolean) = p.getProperty(key)?.toBooleanStrictOrNull() ?: default
            val savedScale = p.getProperty("uiScalePercent")?.toIntOrNull()
                ?.coerceIn(AgentAppearance.MIN_UI_SCALE_PERCENT, AgentAppearance.MAX_UI_SCALE_PERCENT)
            val uiScale = when {
                savedScale == null -> AgentAppearance.DEFAULT_UI_SCALE_PERCENT
                p.getProperty("uiScaleBaseline") == AgentAppearance.UI_SCALE_BASELINE_PERCENT.toString() -> savedScale
                savedScale == 100 -> AgentAppearance.DEFAULT_UI_SCALE_PERCENT
                else -> (savedScale * 100 + AgentAppearance.UI_SCALE_BASELINE_PERCENT / 2) / AgentAppearance.UI_SCALE_BASELINE_PERCENT
            }.coerceIn(AgentAppearance.MIN_UI_SCALE_PERCENT, AgentAppearance.MAX_UI_SCALE_PERCENT)
            _appearance.value = AgentAppearance(
                darkTheme = flag("dark", false), colorVisionAssist = flag("vision", false),
                smoothStreaming = flag("smooth", true), alwaysShowUsage = flag("usage", false),
                hideToolCalls = flag("hideTools", false), collapseCompacted = flag("collapse", true),
                uiScalePercent = uiScale,
                colorSource = runCatching { AgentColorSource.valueOf(p.getProperty("colorSource", "BLUE")) }.getOrDefault(AgentColorSource.BLUE),
                customColor = p.getProperty("customColor")?.toLongOrNull()?.takeIf { it in 0xFF000000L..0xFFFFFFFFL } ?: 0xFF0B57D0L,
            )
            _defaultAutoApprove.value = flag("defaultAutoApprove", true)
            _keepScreenOn.value = flag("keepScreenOn", false)
            val metadata = Properties()
            File(dataDir, "sessions.properties").takeIf { it.exists() }?.inputStream()?.use(metadata::load)
            records.value = metadata.getProperty("ids", "").split(',').filter { it.isNotBlank() }.map { id ->
                SessionRecord(
                    id = id,
                    title = metadata.getProperty("$id.title", "新会话"),
                    containerId = metadata.getProperty("$id.container", ""),
                    workspace = metadata.getProperty("$id.workspace", "/workspace"),
                    pinned = metadata.getProperty("$id.pinned").toBoolean(),
                    archived = metadata.getProperty("$id.archived").toBoolean(),
                    updatedAt = metadata.getProperty("$id.updated", "0").toLong(),
                    // Older records have no provenance; their saved titles must remain explicit.
                    titleSource = SessionTitleSource.entries.firstOrNull { it.name == metadata.getProperty("$id.titleSource") }
                        ?: SessionTitleSource.MANUAL,
                )
            }
        } catch (e: Exception) { showError(e) }
        scope.launch {
            pool.runtimeIssues.collect { issues ->
                issues.values.forEach { issue ->
                    offerOmpSetup(OmpSetupRequest(containerId = issue.containerId, message = issue.message,
                        repair = true, runtimeIssueId = issue.id))
                }
            }
        }
        scope.launch {
            var previous = emptyMap<String, List<String>>()
            features.errors.collect { failures ->
                failures.forEach { (id, messages) ->
                    val name = runtime.containers.value.firstOrNull { it.config.id == id }?.config?.name ?: id
                    messages.filterNot { it in previous[id].orEmpty() }.forEach { message ->
                        showError(IllegalStateException("$name：$message"))
                    }
                }
                previous = failures
            }
        }
    }
    fun report(text: String) { notices.update { it + HostNotice(text = text) } }
    fun showError(error: Throwable) { notices.update { it + HostNotice(text = error.message ?: error.toString(), error = true) } }
    fun dismissNotice() { notices.update { it.drop(1) } }
    fun launch(action: suspend () -> Unit): Job = scope.launch {
        _busy.update { it + 1 }
        try { withContext(Dispatchers.IO) { action() } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { showError(e) }
        finally { _busy.update { it - 1 } }
    }

    suspend fun installDistro(distro: DistroSpec, name: String, mirror: MirrorSource, engine: ContainerEngineType): ContainerConfig {
        val installed = runtime.installDistro(distro, name, mirror, engine)
        checkOmpInstallation(installed.id)
        return installed
    }

    internal suspend fun checkOmpInstallation(containerId: String) {
        val check = OmpInstallation.inspect(runtime, containerId)
        if (check.state != OmpInstallationState.READY) {
            offerOmpSetup(OmpSetupRequest(containerId = containerId, message = check.message,
                repair = check.state == OmpInstallationState.BROKEN))
        }
    }

    internal fun requestOmpInstallation(containerId: String) {
        offerOmpSetup(OmpSetupRequest(containerId = containerId, message = "可重新安装 OMP 及其运行依赖。", repair = true))
    }

    private fun offerOmpSetup(request: OmpSetupRequest) = synchronized(ompSetupLock) {
        if (runtime.containers.value.none { it.config.id == request.containerId }) return@synchronized
        if (request.runtimeIssueId != null && handledOmpIssues[request.containerId] == request.runtimeIssueId) return@synchronized
        if (request.containerId in activeOmpInstallations) {
            acknowledgeOmpIssue(request)
            return@synchronized
        }
        val existing = ompSetups.value.firstOrNull { it.containerId == request.containerId }
        if (existing == null) ompSetups.value += request
        else if (request.runtimeIssueId != null) {
            ompSetups.value = ompSetups.value.map {
                if (it.id == existing.id) request.copy(id = existing.id) else it
            }
        }
    }

    private fun acknowledgeOmpIssue(request: OmpSetupRequest) {
        request.runtimeIssueId?.let {
            handledOmpIssues[request.containerId] = it
            pool.dismissRuntimeIssue(request.containerId, it)
        }
    }

    internal fun dismissOmpSetup(request: OmpSetupRequest) = synchronized(ompSetupLock) {
        val current = ompSetups.value.firstOrNull { it.id == request.id } ?: return@synchronized
        acknowledgeOmpIssue(current)
        ompSetups.value = ompSetups.value.filterNot { it.id == current.id }
    }

    internal fun confirmOmpSetup(request: OmpSetupRequest): String? = synchronized(ompSetupLock) {
        val current = ompSetups.value.firstOrNull { it.id == request.id } ?: return@synchronized null
        if (runtime.containers.value.none { it.config.id == current.containerId }) return@synchronized null
        approvedOmpInstallations[current.id] = current.containerId
        activeOmpInstallations[current.containerId] = current.id
        acknowledgeOmpIssue(current)
        ompSetups.value = ompSetups.value.filterNot { it.id == current.id }
        current.id
    }

    internal suspend fun openTerminal(containerId: String, installationId: String?): TerminalSession {
        val install = synchronized(ompSetupLock) {
            if (installationId != null && approvedOmpInstallations[installationId] == containerId) {
                approvedOmpInstallations.remove(installationId)
                true
            } else false
        }
        if (!install) return runtime.openTerminal(containerId)
        try {
            pool.prepareOmpRepair(containerId)
            return runtime.openTerminal(containerId, OmpInstallation.command())
        } catch (failure: Throwable) {
            synchronized(ompSetupLock) { activeOmpInstallations.remove(containerId) }
            throw failure
        }
    }

    internal fun finishOmpInstallation(containerId: String, installationId: String?, terminal: TerminalSession?) {
        val finished = synchronized(ompSetupLock) {
            if (installationId == null || activeOmpInstallations[containerId] != installationId) false
            else {
                approvedOmpInstallations.remove(installationId)
                activeOmpInstallations.remove(containerId)
                true
            }
        }
        if (finished) launch {
            if (terminal != null) withTimeout(15_000) { terminal.isAlive.first { !it } }
            if (OmpInstallation.inspect(runtime, containerId).state == OmpInstallationState.READY) {
                pool.refreshAfterOmpRepair(containerId)
            }
        }
    }
    fun hasSession(id: String): Boolean = records.value.any { it.id == id }

    internal fun isEmptyDraft(session: HomeSession): Boolean = session.record.id in provisionalSessions &&
        latestUserId(session.state.feed) == null && session.state.queuedPrompts.isEmpty()

    fun currentContainerId(): String? = runtime.containers.value.let { items ->
        (items.firstOrNull { it.config.isDefault } ?: items.firstOrNull())?.config?.id
    }

    suspend fun selectContainer(id: String) { runtime.setDefault(id) }

    suspend fun createInSelectedContainer(): String = create("", requireNotNull(currentContainerId()) { "请先安装或导入一个容器" }, "/workspace")

    /** Only new, never-accepted conversations are disposable; existing history is never inspected here. */
    fun leaveSession(id: String) {
        if (id !in provisionalSessions) return
        backgroundDrafts.add(id)
        discardEmptySession(id)
    }

    private fun hasAcceptedMessage(feed: List<FeedItem>): Boolean = feed.any {
        it is UserMessageItem && it.accepted || it is StepGroupNodeItem && hasAcceptedMessage(it.children)
    }

    private fun discardEmptySession(id: String) {
        synchronized(controllers) {
            if (id !in provisionalSessions || id !in backgroundDrafts) return
            val state = controllers[id]?.state?.value ?: sessionStates.value[id]
            if (state != null && (latestUserId(state.feed) != null || state.queuedPrompts.isNotEmpty() || state.isRunning)) return
            provisionalSessions.remove(id)
            backgroundDrafts.remove(id)
            observers.remove(id)?.cancel()
            controllers.remove(id)
            records.update { all -> all.filterNot { it.id == id } }
            sessionStates.update { it - id }
        }
        launch { pool.deleteSession(id); persistSessions() }
    }

    fun openedSession(id: String): AgentSessionController? = synchronized(controllers) { controllers[id] }

    fun openSession(id: String): AgentSessionController = synchronized(controllers) {
        controllers.getOrPut(id) {
            val r = records.value.firstOrNull { it.id == id } ?: error("找不到会话 $id")
            require(runtime.containers.value.any { it.config.id == r.containerId }) { "会话绑定的容器已不存在，请切换运行容器" }
            pool.session(r.id, r.containerId, r.workspace, r.title).also { observe(id, it) }
        }
    }
    private fun latestUserId(feed: List<FeedItem>): String? {
        for (index in feed.indices.reversed()) {
            when (val item = feed[index]) {
                is UserMessageItem -> return item.id
                is StepGroupNodeItem -> latestUserId(item.children)?.let { return it }
                else -> Unit
            }
        }
        return null
    }

    private fun firstQuestion(feed: List<FeedItem>): String? {
        for (item in feed) {
            when (item) {
                is UserMessageItem -> if (item.accepted && item.text.isNotBlank()) return item.text
                is StepGroupNodeItem -> firstQuestion(item.children)?.let { return it }
                else -> Unit
            }
        }
        return null
    }

    private fun questionTitle(question: String): String = buildString(48) {
        var offset = 0
        var codePoints = 0
        var separator = false
        while (offset < question.length && codePoints < 48) {
            val point = question.codePointAt(offset)
            offset += Character.charCount(point)
            if (Character.isWhitespace(point) || Character.isSpaceChar(point)) {
                separator = isNotEmpty()
                continue
            }
            if (separator) {
                if (codePoints == 47) break
                append(' ')
                codePoints++
                separator = false
            }
            appendCodePoint(point)
            codePoints++
        }
    }

    private fun observe(id: String, controller: AgentSessionController) {
        observers.remove(id)?.cancel()
        var lastUser = latestUserId(controller.state.value.feed)
        observers[id] = scope.launch {
            controller.state.collect { state ->
                sessionStates.update { it + (id to state) }
                val becameDurable = id in provisionalSessions && hasAcceptedMessage(state.feed) && provisionalSessions.remove(id)
                if (becameDurable) backgroundDrafts.remove(id)
                val latest = latestUserId(state.feed)
                val userChanged = latest != null && latest != lastUser
                if (userChanged) lastUser = latest
                val titleChanged = synchronized(controllers) {
                    val record = records.value.firstOrNull { it.id == id }
                    val title = if (record?.titleSource == SessionTitleSource.PENDING) firstQuestion(state.feed)?.let(::questionTitle) else null
                    if (record != null && (userChanged || title != null)) {
                        records.update { all -> all.map {
                            if (it.id != id) it else it.copy(
                                title = title ?: it.title,
                                titleSource = if (title != null) SessionTitleSource.FIRST_QUESTION else it.titleSource,
                                updatedAt = if (userChanged) System.currentTimeMillis() else it.updatedAt,
                            )
                        } }
                        if (title != null) pool.session(id, record.containerId, record.workspace, title)
                    }
                    title != null
                }
                if (userChanged || titleChanged || becameDurable) {
                    runCatching { withContext(Dispatchers.IO) { persistSessions() } }.onFailure(::showError)
                }
                if (id in backgroundDrafts) discardEmptySession(id)
            }
        }
    }
    suspend fun create(title: String, containerId: String, workspace: String): String {
        require(workspace.startsWith('/')) { "工作区必须是容器内绝对路径" }
        require(runtime.containers.value.any { it.config.id == containerId }) { "请先安装或导入一个容器" }
        val id = UUID.randomUUID().toString()
        val record = SessionRecord(
            id, title.trim().ifBlank { "新会话" }, containerId, workspace,
            titleSource = if (title.isBlank()) SessionTitleSource.PENDING else SessionTitleSource.MANUAL,
        )
        synchronized(controllers) {
            val controller = pool.session(record.id, record.containerId, record.workspace, record.title, autoApprove = _defaultAutoApprove.value)
            controllers[id] = controller
            provisionalSessions.add(id)
            records.update { it + record }
            observe(id, controller)
        }
        persistSessions()
        return id
    }
    suspend fun createInContext(sessionId: String): String {
        val source = records.value.firstOrNull { it.id == sessionId } ?: error("找不到会话 $sessionId")
        require(runtime.containers.value.any { it.config.id == source.containerId }) { "会话绑定的容器已不存在" }
        return create("", source.containerId, source.workspace)
    }
    suspend fun fork(id: String): String {
        val r = records.value.first { it.id == id }
        val next = r.copy(id = UUID.randomUUID().toString(), title = "${r.title} 分支", titleSource = SessionTitleSource.MANUAL, pinned = false, archived = false, updatedAt = System.currentTimeMillis())
        val controller = pool.fork(r.id, next.id, r.containerId, r.workspace, next.title)
        records.update { it + next }
        synchronized(controllers) { controllers[next.id] = controller; observe(next.id, controller) }
        persistSessions()
        return next.id
    }
    suspend fun registerFork(sourceId: String, guestSessionFile: String): String {
        val source = records.value.firstOrNull { it.id == sourceId } ?: error("来源会话已不存在")
        val next = source.copy(id = UUID.randomUUID().toString(), title = "${source.title} 分支",
            titleSource = SessionTitleSource.MANUAL, pinned = false, archived = false, updatedAt = System.currentTimeMillis())
        val controller = pool.adoptFork(sourceId, next.id, guestSessionFile, next.title)
        records.update { it + next }
        synchronized(controllers) { controllers[next.id] = controller; observe(next.id, controller) }
        persistSessions()
        return next.id
    }
    suspend fun editSession(id: String, title: String, workspace: String, containerId: String, manualTitle: Boolean = true) {
        require(title.isNotBlank()) { "会话标题不能为空" }
        require(workspace.startsWith('/')) { "工作区必须是容器内绝对路径" }
        val controller = pool.relocateSession(id, containerId, workspace, title.trim())
        synchronized(controllers) {
            records.update { all -> all.map { if (it.id == id) it.copy(title = title.trim(), titleSource = if (manualTitle) SessionTitleSource.MANUAL else it.titleSource, workspace = workspace, containerId = containerId, updatedAt = System.currentTimeMillis()) else it } }
            controllers[id] = controller
            observe(id, controller)
        }
        persistSessions()
    }
    suspend fun togglePin(ids: Set<String>) { records.update { all -> all.map { if (it.id in ids) it.copy(pinned = !it.pinned) else it } }; persistSessions() }
    suspend fun toggleArchive(ids: Set<String>) { records.update { all -> all.map { if (it.id in ids) it.copy(archived = !it.archived) else it } }; persistSessions() }
    suspend fun deleteSessions(ids: Set<String>) {
        for (id in ids) {
            pool.deleteSession(id)
            synchronized(controllers) { controllers.remove(id); observers.remove(id)?.cancel() }
            provisionalSessions.remove(id)
            backgroundDrafts.remove(id)
            records.update { all -> all.filterNot { it.id == id } }
            sessionStates.update { it - id }
            persistSessions()
        }
    }
    suspend fun deleteContainer(id: String) {
        require(records.value.none { it.containerId == id }) { "此容器仍有关联会话，请先切换会话容器或删除会话" }
        runtime.delete(id)
    }
    fun updateAppearance(next: AgentAppearance, persist: Boolean = true) {
        _appearance.value = next.copy(uiScalePercent = next.effectiveUiScalePercent)
        if (persist) launch { persistPreferences() }
    }
    fun updateDefaultAutoApprove(enabled: Boolean) {
        _defaultAutoApprove.value = enabled
        launch { persistPreferences() }
    }
    fun updateKeepScreenOn(enabled: Boolean) {
        _keepScreenOn.value = enabled
        launch { persistPreferences() }
    }
    private fun persistPreferences() = synchronized(persistenceLock) {
        val current = _appearance.value
        val p = Properties()
        p["dark"] = current.darkTheme.toString(); p["vision"] = current.colorVisionAssist.toString()
        p["smooth"] = current.smoothStreaming.toString(); p["usage"] = current.alwaysShowUsage.toString()
        p["hideTools"] = current.hideToolCalls.toString(); p["collapse"] = current.collapseCompacted.toString()
        p["defaultAutoApprove"] = _defaultAutoApprove.value.toString()
        p["keepScreenOn"] = _keepScreenOn.value.toString()
        p["uiScalePercent"] = current.effectiveUiScalePercent.toString()
        p["uiScaleBaseline"] = AgentAppearance.UI_SCALE_BASELINE_PERCENT.toString()
        p["colorSource"] = current.colorSource.name
        p["customColor"] = current.customColor.toString()
        saveProperties(File(dataDir, "appearance.properties"), p)
    }
    private fun persistSessions() = synchronized(persistenceLock) {
        val p = Properties()
        val current = records.value.filterNot { it.id in provisionalSessions }
        p["ids"] = current.joinToString(",") { it.id }
        current.forEach { r ->
            p["${r.id}.title"] = r.title; p["${r.id}.container"] = r.containerId; p["${r.id}.workspace"] = r.workspace
            p["${r.id}.pinned"] = r.pinned.toString(); p["${r.id}.archived"] = r.archived.toString(); p["${r.id}.updated"] = r.updatedAt.toString()
            p["${r.id}.titleSource"] = r.titleSource.name
        }
        saveProperties(File(dataDir, "sessions.properties"), p)
    }
    private fun saveProperties(file: File, properties: Properties) = synchronized(persistenceLock) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.outputStream().use { properties.store(it, "WawLink") }
        try { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        Unit
    }
    override fun close() { scope.cancel(); features.close(); pool.close(); runtime.close() }
}
