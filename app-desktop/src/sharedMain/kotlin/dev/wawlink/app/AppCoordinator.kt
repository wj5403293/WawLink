package dev.wawlink.app

import dev.wawlink.driver.OmpProcessPool
import dev.wawlink.protocol.*
import dev.wawlink.runtime.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class HostImportFile(val name: String, val openInput: () -> java.io.InputStream)

interface HostFiles {
    suspend fun pickAttachments(): List<AttachmentFile>
    suspend fun pickRootfs(): String?
    suspend fun exportRootfs(runtime: ContainerRuntime, container: ContainerConfig): Boolean
    suspend fun pickImportFiles(): List<HostImportFile>
    suspend fun saveFile(name: String, mimeType: String, write: suspend (java.io.OutputStream) -> Unit): Boolean
}

data class SessionRecord(
    val id: String,
    val title: String,
    val containerId: String,
    val workspace: String,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)
data class HomeSession(val record: SessionRecord, val state: ChatSessionUiState)
data class HostNotice(val id: Long = System.nanoTime(), val text: String, val error: Boolean = false)

class AppCoordinator(val runtime: ContainerRuntime, val pool: OmpProcessPool, val dataDir: File, val isAndroid: Boolean = false) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val persistenceLock = Any()
    private val records = MutableStateFlow<List<SessionRecord>>(emptyList())
    private val sessionStates = MutableStateFlow<Map<String, ChatSessionUiState>>(emptyMap())
    private val controllers = mutableMapOf<String, AgentSessionController>()
    private val observers = mutableMapOf<String, Job>()
    private val _appearance = MutableStateFlow(AgentAppearance())
    val appearance: StateFlow<AgentAppearance> = _appearance.asStateFlow()
    private val _defaultAutoApprove = MutableStateFlow(true)
    val defaultAutoApprove: StateFlow<Boolean> = _defaultAutoApprove.asStateFlow()
    private val notices = MutableStateFlow<List<HostNotice>>(emptyList())
    val notice: StateFlow<HostNotice?> = notices.map { it.firstOrNull() }.stateIn(scope, SharingStarted.Eagerly, null)
    private val _busy = MutableStateFlow(0)
    val busy: StateFlow<Int> = _busy.asStateFlow()
    val sessions = combine(records, sessionStates) { items, states ->
        items.sortedByDescending { it.updatedAt }.map { item -> HomeSession(item, (states[item.id] ?: ChatSessionUiState(item.id)).copy(title = item.title, workspace = item.workspace)) }
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
            )
            _defaultAutoApprove.value = flag("defaultAutoApprove", true)
            val metadata = Properties()
            File(dataDir, "sessions.properties").takeIf { it.exists() }?.inputStream()?.use(metadata::load)
            records.value = metadata.getProperty("ids", "").split(',').filter { it.isNotBlank() }.map { id ->
                SessionRecord(id, metadata.getProperty("$id.title", "新会话"), metadata.getProperty("$id.container", ""), metadata.getProperty("$id.workspace", "/workspace"), metadata.getProperty("$id.pinned").toBoolean(), metadata.getProperty("$id.archived").toBoolean(), metadata.getProperty("$id.updated", "0").toLong())
            }
        } catch (e: Exception) { showError(e) }
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
    fun hasSession(id: String): Boolean = records.value.any { it.id == id }

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

    private fun observe(id: String, controller: AgentSessionController) {
        observers.remove(id)?.cancel()
        var lastUser = latestUserId(controller.state.value.feed)
        observers[id] = scope.launch {
            controller.state.collect { state ->
                sessionStates.update { it + (id to state) }
                val latest = latestUserId(state.feed)
                if (latest != null && latest != lastUser) {
                    lastUser = latest
                    records.update { all -> all.map { if (it.id == id) it.copy(updatedAt = System.currentTimeMillis()) else it } }
                    runCatching { withContext(Dispatchers.IO) { persistSessions() } }.onFailure(::showError)
                }
            }
        }
    }
    suspend fun create(title: String, containerId: String, workspace: String): String {
        require(workspace.startsWith('/')) { "工作区必须是容器内绝对路径" }
        require(runtime.containers.value.any { it.config.id == containerId }) { "请先安装或导入一个容器" }
        val id = UUID.randomUUID().toString()
        val record = SessionRecord(id, title.trim().ifBlank { "新会话" }, containerId, workspace)
        synchronized(controllers) {
            val controller = pool.session(record.id, record.containerId, record.workspace, record.title, autoApprove = _defaultAutoApprove.value)
            controllers[id] = controller
            records.update { it + record }
            observe(id, controller)
        }
        persistSessions()
        return id
    }
    suspend fun fork(id: String): String {
        val r = records.value.first { it.id == id }
        val next = r.copy(id = UUID.randomUUID().toString(), title = "${r.title} 分支", pinned = false, archived = false, updatedAt = System.currentTimeMillis())
        val controller = pool.fork(r.id, next.id, r.containerId, r.workspace, next.title)
        records.update { it + next }
        synchronized(controllers) { controllers[next.id] = controller; observe(next.id, controller) }
        persistSessions()
        return next.id
    }
    suspend fun editSession(id: String, title: String, workspace: String, containerId: String) {
        require(title.isNotBlank()) { "会话标题不能为空" }
        require(workspace.startsWith('/')) { "工作区必须是容器内绝对路径" }
        val controller = pool.relocateSession(id, containerId, workspace, title)
        synchronized(controllers) { controllers[id] = controller; observe(id, controller) }
        records.update { all -> all.map { if (it.id == id) it.copy(title = title.trim(), workspace = workspace, containerId = containerId, updatedAt = System.currentTimeMillis()) else it } }
        persistSessions()
    }
    suspend fun togglePin(ids: Set<String>) { records.update { all -> all.map { if (it.id in ids) it.copy(pinned = !it.pinned) else it } }; persistSessions() }
    suspend fun toggleArchive(ids: Set<String>) { records.update { all -> all.map { if (it.id in ids) it.copy(archived = !it.archived) else it } }; persistSessions() }
    suspend fun deleteSessions(ids: Set<String>) {
        for (id in ids) {
            pool.deleteSession(id)
            synchronized(controllers) { controllers.remove(id); observers.remove(id)?.cancel() }
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
    private fun persistPreferences() = synchronized(persistenceLock) {
        val current = _appearance.value
        val p = Properties()
        p["dark"] = current.darkTheme.toString(); p["vision"] = current.colorVisionAssist.toString()
        p["smooth"] = current.smoothStreaming.toString(); p["usage"] = current.alwaysShowUsage.toString()
        p["hideTools"] = current.hideToolCalls.toString(); p["collapse"] = current.collapseCompacted.toString()
        p["defaultAutoApprove"] = _defaultAutoApprove.value.toString()
        p["uiScalePercent"] = current.effectiveUiScalePercent.toString()
        p["uiScaleBaseline"] = AgentAppearance.UI_SCALE_BASELINE_PERCENT.toString()
        saveProperties(File(dataDir, "appearance.properties"), p)
    }
    private fun persistSessions() = synchronized(persistenceLock) {
        val p = Properties()
        val current = records.value
        p["ids"] = current.joinToString(",") { it.id }
        current.forEach { r ->
            p["${r.id}.title"] = r.title; p["${r.id}.container"] = r.containerId; p["${r.id}.workspace"] = r.workspace
            p["${r.id}.pinned"] = r.pinned.toString(); p["${r.id}.archived"] = r.archived.toString(); p["${r.id}.updated"] = r.updatedAt.toString()
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
    override fun close() { scope.cancel(); pool.close(); runtime.close() }
}
