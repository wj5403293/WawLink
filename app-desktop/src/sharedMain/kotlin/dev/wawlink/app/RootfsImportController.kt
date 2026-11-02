package dev.wawlink.app

import dev.wawlink.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class RootfsImportDraft(
    val sourceType: String = "本地文件",
    val source: String = "",
    val url: String = "",
    val name: String = "custom-rootfs",
    val checksum: String = "",
    val engine: ContainerEngineType = ContainerEngineType.PROOT,
    val family: String = "Ubuntu",
    val shell: String = "/bin/bash",
) {
    val archiveSource get() = if (sourceType == "本地文件") source else url.trim()
    val canSubmit get() = name.isNotBlank() && archiveSource.isNotBlank()
    fun sameArchive(other: RootfsImportDraft) = archiveSource == other.archiveSource && engine == other.engine && checksum.trim() == other.checksum.trim()
}

internal enum class RootfsImportPhase { FORM, PREPARING, DECISION, COMMITTING, FINALIZING, CANCELLING, COMPLETE }

internal data class RootfsImportUiState(
    val draft: RootfsImportDraft = RootfsImportDraft(),
    val phase: RootfsImportPhase = RootfsImportPhase.FORM,
    val inspection: RootfsImportInspection? = null,
    val committed: Boolean = false,
    val conflicts: List<ContainerConfig> = emptyList(),
    val selectedTargetId: String? = null,
    val rename: String = "",
    val error: String? = null,
    val progress: DeploymentProgress? = null,
) {
    val busy get() = when (phase) {
        RootfsImportPhase.PREPARING, RootfsImportPhase.COMMITTING, RootfsImportPhase.FINALIZING, RootfsImportPhase.CANCELLING -> true
        else -> false
    }
}

/** Owns one prepared archive until commit, cancellation, or leaving the form. */
internal class RootfsImportController(
    private val runtime: ContainerRuntime,
    private val scope: CoroutineScope,
    private val onCommitted: suspend (ContainerConfig, Long?) -> Unit,
) {
    private val mutableState = MutableStateFlow(RootfsImportUiState())
    val state = mutableState.asStateFlow()
    private var operation: Job? = null
    private var inspectedDraft: RootfsImportDraft? = null
    private var committed: ContainerConfig? = null
    private var committedMode = RootfsImportMode.NEW
    private var committedGeneration: Long? = null
    private var closed = false

    fun edit(change: (RootfsImportDraft) -> RootfsImportDraft) {
        if (state.value.busy) return
        val current = state.value.draft
        val next = change(current)
        val draft = if (committed == null) next else current.copy(family = next.family, shell = next.shell)
        mutableState.value = state.value.copy(draft = draft, error = null)
    }

    fun selectTarget(id: String) {
        if (!state.value.busy && state.value.conflicts.any { it.id == id }) {
            mutableState.value = state.value.copy(selectedTargetId = id, error = null)
        }
    }

    fun rename(value: String) {
        if (!state.value.busy) mutableState.value = state.value.copy(rename = value, error = null)
    }

    fun renameError(): String? = when {
        state.value.rename.isBlank() -> "请输入新容器名称"
        rootfsImportConflicts(state.value.rename, "", configs()).isNotEmpty() -> "新名称已被现有容器使用"
        else -> null
    }

    fun checkNameConflict() {
        val current = state.value
        if (closed || current.phase != RootfsImportPhase.FORM || current.committed || current.draft.name.isBlank()) return
        val conflicts = rootfsImportConflicts(current.draft.name, "", configs())
        if (conflicts.isNotEmpty()) {
            fun showDecision() {
                mutableState.value = state.value.copy(
                    phase = RootfsImportPhase.DECISION,
                    conflicts = conflicts,
                    selectedTargetId = conflicts.singleOrNull()?.id,
                    error = null,
                )
            }
            if (inspectedDraft?.sameArchive(current.draft) == false) {
                launchOperation(RootfsImportPhase.PREPARING) {
                    discardInspection()
                    showDecision()
                }
            } else showDecision()
        }
    }

    private fun sourceReady(): Boolean {
        if (state.value.inspection != null || state.value.draft.archiveSource.isNotBlank()) return true
        mutableState.value = state.value.copy(error = "请先选择 rootfs 归档或填写下载链接")
        return false
    }

    fun submit() {
        if (!state.value.draft.canSubmit) return
        launchOperation(RootfsImportPhase.PREPARING) {
            if (committed != null) { finishCommit(); return@launchOperation }
            if (inspectedDraft?.sameArchive(state.value.draft) == false) discardInspection()
            refreshConflicts()
            if (state.value.conflicts.isNotEmpty()) {
                mutableState.value = state.value.copy(phase = RootfsImportPhase.DECISION)
            } else {
                prepare()
                refreshConflicts()
                if (state.value.conflicts.isNotEmpty()) mutableState.value = state.value.copy(phase = RootfsImportPhase.DECISION)
                else commit(RootfsImportMode.NEW)
            }
        }
    }

    fun updateExisting() {
        if (state.value.phase != RootfsImportPhase.DECISION || state.value.selectedTargetId == null) return
        if (!sourceReady()) return
        launchOperation(RootfsImportPhase.PREPARING) {
            val requestedTarget = state.value.selectedTargetId
            refreshConflicts()
            val target = state.value.selectedTargetId?.takeIf { it == requestedTarget }
            if (target == null || state.value.conflicts.none { it.id == target }) {
                mutableState.value = state.value.copy(phase = RootfsImportPhase.DECISION, error = "请选择要更新的现有容器")
            } else if (state.value.inspection == null) {
                prepare()
                refreshConflicts()
                // The archive may introduce another conflicting title. Confirm the inspected archive, not just the input name.
                mutableState.value = state.value.copy(phase = RootfsImportPhase.DECISION)
            } else commit(RootfsImportMode.UPDATE, target)
        }
    }

    fun importRenamed() {
        if (state.value.phase != RootfsImportPhase.DECISION) return
        val invalid = renameError()
        if (invalid != null) { mutableState.value = state.value.copy(error = invalid); return }
        if (!sourceReady()) return
        launchOperation(RootfsImportPhase.PREPARING) {
            if (state.value.inspection == null) {
                prepare()
                refreshConflicts()
                mutableState.value = state.value.copy(phase = RootfsImportPhase.DECISION)
            } else {
                require(renameError() == null) { renameError() ?: "新名称不可用" }
                commit(RootfsImportMode.RENAME)
            }
        }
    }

    fun cancel() {
        if (closed || state.value.phase == RootfsImportPhase.CANCELLING || committed != null) return
        val active = operation
        mutableState.value = state.value.copy(phase = RootfsImportPhase.CANCELLING)
        operation = scope.launch {
            active?.cancelAndJoin()
            try { withContext(NonCancellable + Dispatchers.IO) { discardInspection() } }
            catch (failure: Exception) { mutableState.value = state.value.copy(error = failure.message ?: "清理导入缓存失败") }
            finally { mutableState.value = state.value.copy(phase = RootfsImportPhase.FORM) }
        }
    }

    suspend fun close() {
        closed = true
        operation?.cancelAndJoin()
        withContext(NonCancellable + Dispatchers.IO) { discardInspection() }
    }

    private fun configs() = runtime.containers.value.map { it.config }

    private fun refreshConflicts() {
        val current = state.value
        val conflicts = rootfsImportConflicts(current.draft.name, current.inspection?.metadata?.title.orEmpty(), configs())
        val changed = conflicts.map { it.id }.toSet() != current.conflicts.map { it.id }.toSet()
        val selected = when {
            conflicts.size == 1 -> conflicts.single().id
            conflicts.size > 1 && !changed -> current.selectedTargetId?.takeIf { id -> conflicts.any { it.id == id } }
            else -> null
        }
        mutableState.value = current.copy(conflicts = conflicts, selectedTargetId = selected)
    }
    private suspend fun progressCallback(): (DeploymentProgress) -> Unit {
        val owner = currentCoroutineContext()[Job]
        return { progress ->
            if (!closed && owner?.isActive == true) mutableState.update { current ->
                if (current.phase == RootfsImportPhase.PREPARING || current.phase == RootfsImportPhase.COMMITTING) current.copy(progress = progress)
                else current
            }
        }
    }


    private suspend fun prepare() {
        if (state.value.inspection != null) return
        val draft = state.value.draft
        withContext(Dispatchers.IO) {
            val inspection = runtime.inspectRootfsArchive(draft.archiveSource, draft.name.trim(), draft.engine, draft.checksum.trim().ifBlank { null }, progressCallback())
            inspectedDraft = draft
            mutableState.value = state.value.copy(inspection = inspection)
        }
    }

    private suspend fun discardInspection() {
        val inspection = state.value.inspection ?: return
        withContext(Dispatchers.IO) { runtime.discardRootfsImport(inspection.id) }
        inspectedDraft = null
        mutableState.value = state.value.copy(inspection = null)
    }

    private suspend fun commit(mode: RootfsImportMode, target: String? = null) {
        val current = state.value
        val inspection = requireNotNull(current.inspection)
        mutableState.value = current.copy(phase = RootfsImportPhase.COMMITTING, progress = null)
        committedMode = mode
        val name = if (mode == RootfsImportMode.RENAME) current.rename.trim() else current.draft.name.trim()
        withContext(Dispatchers.IO) {
            committed = runtime.importRootfs(inspection.id, name, mode, target, progressCallback())
            committedGeneration = runtime.containers.value.firstOrNull { it.config.id == committed?.id }?.stopGeneration
            // Save consumption before returning across the cancellable dispatcher boundary.
            mutableState.value = state.value.copy(inspection = null, committed = true)
            inspectedDraft = null
        }
        finishCommit()
    }

    private suspend fun finishCommit() {
        var config = requireNotNull(committed)
        mutableState.value = state.value.copy(phase = RootfsImportPhase.FINALIZING)
        if (committedMode != RootfsImportMode.UPDATE) {
            config = config.copy(distro = state.value.draft.family.lowercase(), shell = state.value.draft.shell)
            withContext(Dispatchers.IO) { runtime.update(config) }
            committed = config
        }
        onCommitted(config, committedGeneration)
        mutableState.value = state.value.copy(phase = RootfsImportPhase.COMPLETE)
    }

    private fun launchOperation(phase: RootfsImportPhase, action: suspend () -> Unit) {
        if (closed || state.value.busy || state.value.phase == RootfsImportPhase.COMPLETE) return
        mutableState.value = state.value.copy(phase = phase, error = null, progress = null)
        operation = scope.launch {
            try { action() }
            catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { discardInspection() }
                mutableState.value = state.value.copy(phase = RootfsImportPhase.FORM)
                throw cancelled
            } catch (failure: Exception) {
                val decision = state.value.conflicts.isNotEmpty() && committed == null
                mutableState.value = state.value.copy(phase = if (decision) RootfsImportPhase.DECISION else RootfsImportPhase.FORM, error = failure.message ?: "导入失败，请修正后重试")
            }
        }
    }
}
