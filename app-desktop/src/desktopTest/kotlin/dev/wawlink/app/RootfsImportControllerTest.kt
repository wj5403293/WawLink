package dev.wawlink.app

import dev.wawlink.runtime.*
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.test.*

class RootfsImportControllerTest {
    private data class Commit(val inspectionId: String, val name: String, val mode: RootfsImportMode, val targetId: String?)

    private class Fixture(scope: CoroutineScope, configs: List<ContainerConfig>, title: String) {
        private val directory = Files.createTempDirectory("wawlink-import-ui-").toFile()
        private val base = LocalContainerRuntime(RuntimePaths(directory.resolve("runtime")))
        val containers = MutableStateFlow(configs.map { ContainerInfo(it, stopGeneration = 7) })
        val inspectCalls = AtomicInteger()
        val commitCalls = mutableListOf<Commit>()
        val commitStarted = CompletableDeferred<Unit>()
        val updates = mutableListOf<ContainerConfig>()
        val discarded = mutableListOf<String>()
        val completed = mutableListOf<Pair<ContainerConfig, Long?>>()
        val liveHandles = mutableSetOf<String>()
        var inspectGate: CompletableDeferred<Unit>? = null
        var commitGate: CompletableDeferred<Unit>? = null
        var failCommits = 0
        var failUpdates = 0
        var failInspection = false
        var cleanupWasActive = false
        val runtime = object : ContainerRuntime by base {
            override val containers = this@Fixture.containers
            override suspend fun inspectRootfsArchive(source: String, name: String, engine: ContainerEngineType, expectedSha256: String?): RootfsImportInspection {
                val attempt = inspectCalls.incrementAndGet()
                inspectGate?.await()
                if (failInspection) error("归档校验失败")
                val id = "inspection-$attempt"
                liveHandles += id
                return RootfsImportInspection(id, name, ContainerMetadata(title = title, version = "archive-v2"))
            }
            override suspend fun importRootfs(inspectionId: String, name: String, mode: RootfsImportMode, targetId: String?): ContainerConfig {
                assertTrue(inspectionId in liveHandles)
                commitCalls += Commit(inspectionId, name, mode, targetId)
                commitStarted.complete(Unit)
                commitGate?.await()
                if (failCommits > 0) { failCommits--; error("目标暂时不可写") }
                val metadata = ContainerMetadata(title = if (mode == RootfsImportMode.RENAME) name else title, version = "archive-v2")
                val config = if (mode == RootfsImportMode.UPDATE) containers.value.single { it.config.id == targetId }.config.copy(metadata = metadata)
                    else ContainerConfig("new-container", name, directory.resolve("root").path, metadata = metadata)
                liveHandles -= inspectionId
                containers.value = containers.value.filterNot { it.config.id == config.id } + ContainerInfo(config, stopGeneration = 7)
                return config
            }
            override suspend fun discardRootfsImport(inspectionId: String) {
                cleanupWasActive = currentCoroutineContext().isActive
                yield()
                discarded += inspectionId
                liveHandles -= inspectionId
            }
            override suspend fun update(config: ContainerConfig) {
                if (failUpdates > 0) { failUpdates--; error("配置暂时不可写") }
                updates += config
                containers.value = containers.value.map { if (it.config.id == config.id) it.copy(config = config) else it }
            }
        }
        val controller = RootfsImportController(runtime, scope) { config, generation -> completed += config to generation }
        init { controller.edit { it.copy(source = "/archive.tar.gz", name = "incoming") } }
        suspend fun idle(): RootfsImportUiState = withTimeout(5_000) { controller.state.first { !it.busy } }
        suspend fun close() {
            controller.close()
            base.close()
            directory.deleteRecursively()
        }
    }

    private suspend fun CoroutineScope.withFixture(
        configs: List<ContainerConfig> = emptyList(),
        title: String = "archive-title",
        block: suspend Fixture.() -> Unit,
    ) {
        val fixture = Fixture(this, configs, title)
        try { fixture.block() } finally { fixture.close() }
    }

    private fun existing(id: String, name: String, title: String = name) = ContainerConfig(
        id, name, "/rootfs/$id", distro = "debian", version = "12", engine = ContainerEngineType.CHROOT,
        user = "alice", shell = "/bin/zsh", mounts = listOf(ContainerMount("/host/work", "/workspace")),
        environment = mapOf("KEEP" to "value"), metadata = ContainerMetadata(title = title, version = "existing-v1"),
    )

    @Test fun nameFocusLossShowsDuplicateWithoutSourceAndGuardsBothPrepareActions() = runBlocking {
        withFixture(listOf(existing("old", "internal-name", "incoming"))) {
            controller.edit { it.copy(source = "", name = " incoming ") }
            assertEquals(RootfsImportPhase.FORM, controller.state.value.phase, "Typing must not open the conflict prompt")
            controller.checkNameConflict()
            val focused = controller.state.value
            assertEquals(RootfsImportPhase.DECISION, focused.phase)
            assertEquals(listOf("old"), focused.conflicts.map { it.id })
            assertNull(focused.inspection)
            assertEquals(0, inspectCalls.get())
            controller.updateExisting()
            assertNotNull(controller.state.value.error)
            controller.rename("new-name")
            controller.importRenamed()
            assertNotNull(controller.state.value.error)
            assertEquals(RootfsImportPhase.DECISION, controller.state.value.phase)
            assertEquals(0, inspectCalls.get())
            assertTrue(commitCalls.isEmpty())
            controller.cancel()
            assertEquals(" incoming ", idle().draft.name)
            assertEquals("", controller.state.value.draft.source)
        }
    }

    @Test fun nameFocusLossDoesNotPrepareOrInterruptNonconflictingAndBlankNames() = runBlocking {
        withFixture(listOf(existing("old", "incoming"))) {
            controller.edit { it.copy(name = "unique-name") }
            controller.checkNameConflict()
            assertEquals(RootfsImportPhase.FORM, controller.state.value.phase)
            controller.edit { it.copy(name = "   ") }
            controller.checkNameConflict()
            assertEquals(RootfsImportPhase.FORM, controller.state.value.phase)
            assertEquals(0, inspectCalls.get())
            assertTrue(commitCalls.isEmpty())
        }
    }

    @Test fun focusDetectedDuplicateCanProceedAfterSourceIsChosenAndArchiveConfirmed() = runBlocking {
        withFixture(listOf(existing("old", "incoming"))) {
            controller.edit { it.copy(source = "") }
            controller.checkNameConflict()
            controller.cancel()
            idle()
            controller.edit { it.copy(source = "/chosen.tar.gz") }
            controller.checkNameConflict()
            controller.updateExisting()
            assertEquals(RootfsImportPhase.DECISION, idle().phase)
            assertEquals(1, inspectCalls.get())
            assertTrue(commitCalls.isEmpty())
            controller.updateExisting()
            assertEquals(RootfsImportPhase.COMPLETE, idle().phase)
            assertEquals("old", commitCalls.single().targetId)
        }
    }

    @Test fun focusDecisionDiscardsInspectionWhenFailureDraftChangesArchive() = runBlocking {
        withFixture(listOf(existing("old", "incoming"))) {
            controller.edit { it.copy(name = "unique-name") }
            failCommits = 1
            controller.submit()
            assertNotNull(idle().inspection)
            controller.edit { it.copy(name = "incoming", source = "/replacement.tar.gz") }
            controller.checkNameConflict()
            val conflict = idle()
            assertEquals(RootfsImportPhase.DECISION, conflict.phase)
            assertNull(conflict.inspection)
            assertEquals(listOf("inspection-1"), discarded)
            assertEquals(1, inspectCalls.get(), "Focus loss must not inspect the replacement archive")
            controller.updateExisting()
            assertEquals("inspection-2", idle().inspection?.id)
            assertEquals(2, inspectCalls.get())
            controller.updateExisting()
            assertEquals(RootfsImportPhase.COMPLETE, idle().phase)
            assertEquals("inspection-2", commitCalls.last().inspectionId)
        }
    }

    @Test fun inputDuplicateRequiresDecisionBeforeReadingArchive() = runBlocking {
        withFixture(listOf(existing("old", "internal-name", "incoming"))) {
            controller.edit { it.copy(name = " incoming ") }
            controller.submit()
            val state = idle()
            assertEquals(RootfsImportPhase.DECISION, state.phase)
            assertEquals(listOf("old"), state.conflicts.map { it.id })
            assertEquals("old", state.selectedTargetId)
            assertEquals("existing-v1", state.conflicts.single().metadata.version)
            assertNull(state.inspection)
            assertEquals(0, inspectCalls.get())
            assertTrue(commitCalls.isEmpty())
            assertTrue(completed.isEmpty())
        }
    }

    @Test fun archiveTitleIntroducesSecondPhaseAndUpdatePreservesConfiguration() = runBlocking {
        val target = existing("old", "internal-name", "archive-title")
        withFixture(listOf(target)) {
            controller.edit { it.copy(family = "Alpine", shell = "/bin/sh", engine = ContainerEngineType.PROOT) }
            controller.submit()
            val state = idle()
            assertEquals(RootfsImportPhase.DECISION, state.phase)
            assertEquals("archive-v2", state.inspection?.metadata?.version)
            assertEquals(listOf(target), state.conflicts)
            assertTrue(commitCalls.isEmpty())
            controller.updateExisting()
            assertEquals(RootfsImportPhase.COMPLETE, idle().phase)
            assertEquals(1, inspectCalls.get())
            assertEquals(RootfsImportMode.UPDATE, commitCalls.single().mode)
            assertEquals(target.id, commitCalls.single().targetId)
            assertTrue(updates.isEmpty(), "UPDATE must not apply family, shell or engine from the form")
            assertEquals(target.copy(metadata = ContainerMetadata(title = "archive-title", version = "archive-v2")), completed.single().first)
            assertEquals(7L, completed.single().second)
        }
    }

    @Test fun separateInputAndArchiveMatchesRequireExplicitMultiTargetChoice() = runBlocking {
        val input = existing("input", "incoming")
        val archive = existing("archive", "other", "archive-title")
        withFixture(listOf(input, archive)) {
            controller.submit()
            assertEquals(listOf("input"), idle().conflicts.map { it.id })
            controller.updateExisting()
            val inspected = idle()
            assertEquals(setOf("input", "archive"), inspected.conflicts.map { it.id }.toSet())
            assertNull(inspected.selectedTargetId, "A single input match must not implicitly select among archive conflicts")
            controller.updateExisting()
            assertTrue(commitCalls.isEmpty())
            controller.selectTarget("not-a-conflict")
            assertNull(controller.state.value.selectedTargetId)
            controller.selectTarget("archive")
            controller.updateExisting()
            assertEquals(RootfsImportPhase.COMPLETE, idle().phase)
            assertEquals("archive", commitCalls.single().targetId)
            assertEquals(1, inspectCalls.get())
        }
    }

    @Test fun changedConflictsCannotSilentlySubstituteAnUpdateTarget() = runBlocking {
        withFixture(listOf(existing("old", "elsewhere", "archive-title"))) {
            controller.submit()
            assertEquals("old", idle().selectedTargetId)
            containers.value = listOf(ContainerInfo(existing("replacement", "another", "archive-title")))
            controller.updateExisting()
            val changed = idle()
            assertEquals(RootfsImportPhase.DECISION, changed.phase)
            assertNotNull(changed.error)
            assertTrue(commitCalls.isEmpty())
            controller.updateExisting()
            assertEquals(RootfsImportPhase.COMPLETE, idle().phase)
            assertEquals("replacement", commitCalls.single().targetId)
        }
    }

    @Test fun renameValidatesBothExistingNamesAndCommitsWithNewFormChoices() = runBlocking {
        withFixture(listOf(existing("old", "incoming", "pretty-title"))) {
            controller.edit { it.copy(family = "Alpine", shell = "/bin/sh") }
            controller.submit()
            idle()
            controller.importRenamed()
            assertNotNull(controller.state.value.error)
            controller.rename(" pretty-title ")
            assertNotNull(controller.renameError())
            controller.importRenamed()
            assertEquals(0, inspectCalls.get())
            controller.rename(" incoming ")
            assertNotNull(controller.renameError())
            controller.rename(" renamed ")
            assertNull(controller.renameError())
            controller.importRenamed()
            assertEquals(RootfsImportPhase.DECISION, idle().phase)
            assertTrue(commitCalls.isEmpty(), "Inspected archive must still be confirmed")
            controller.importRenamed()
            assertEquals(RootfsImportPhase.COMPLETE, idle().phase)
            assertEquals(Commit("inspection-1", "renamed", RootfsImportMode.RENAME, null), commitCalls.single())
            assertEquals("renamed", completed.single().first.name)
            assertEquals("renamed", completed.single().first.metadata.title)
            assertEquals("alpine", updates.single().distro)
            assertEquals("/bin/sh", updates.single().shell)
        }
    }

    @Test fun cancelDiscardsInspectionButPreservesAllFormAndDecisionDrafts() = runBlocking {
        withFixture(listOf(existing("old", "incoming"))) {
            controller.edit { it.copy(sourceType = "网络地址", url = "https://example.test/root.tar.gz", checksum = "abc", family = "Fedora", shell = "/bin/zsh") }
            val draft = controller.state.value.draft
            controller.submit()
            idle()
            controller.rename("my-copy")
            controller.importRenamed()
            idle()
            controller.cancel()
            val cancelled = idle()
            assertEquals(RootfsImportPhase.FORM, cancelled.phase)
            assertEquals(draft, cancelled.draft)
            assertEquals("my-copy", cancelled.rename)
            assertEquals("old", cancelled.selectedTargetId)
            assertNull(cancelled.inspection)
            assertEquals(listOf("inspection-1"), discarded)
            assertTrue(liveHandles.isEmpty())
            assertTrue(cleanupWasActive)
            assertTrue(completed.isEmpty())
            controller.submit()
            assertEquals("my-copy", idle().rename)
        }
    }

    @Test fun failedCommitRetainsInspectionAndDecisionForRetryWithoutRepreparing() = runBlocking {
        withFixture(listOf(existing("old", "elsewhere", "archive-title"))) {
            failCommits = 1
            controller.submit()
            idle()
            controller.rename("repairable-copy")
            controller.importRenamed()
            val failed = idle()
            assertEquals(RootfsImportPhase.DECISION, failed.phase)
            assertNotNull(failed.error)
            assertEquals("repairable-copy", failed.rename)
            assertEquals("inspection-1", failed.inspection?.id)
            assertTrue(completed.isEmpty())
            assertTrue(discarded.isEmpty())
            controller.rename("repaired-copy")
            controller.importRenamed()
            assertEquals(RootfsImportPhase.COMPLETE, idle().phase)
            assertEquals(1, inspectCalls.get())
            assertEquals(listOf("repairable-copy", "repaired-copy"), commitCalls.map { it.name })
            assertTrue(liveHandles.isEmpty())
        }
    }

    @Test fun newImportFailureRetriesSameInspectionAndFinishesOnlyOnce() = runBlocking {
        withFixture {
            failCommits = 1
            controller.submit()
            val failed = idle()
            assertEquals(RootfsImportPhase.FORM, failed.phase)
            assertNotNull(failed.inspection)
            assertTrue(completed.isEmpty())
            controller.submit()
            assertEquals(RootfsImportPhase.COMPLETE, idle().phase)
            assertEquals(1, inspectCalls.get())
            assertEquals(2, commitCalls.size)
            assertEquals(1, completed.size)
            assertEquals(RootfsImportMode.NEW, commitCalls.last().mode)
        }
    }

    @Test fun successfulCommitIsNotRepeatedWhenConfigurationFinalizationNeedsRetry() = runBlocking {
        withFixture {
            failUpdates = 1
            controller.submit()
            val failed = idle()
            assertNotNull(failed.error)
            assertTrue(completed.isEmpty())
            assertNull(failed.inspection)
            assertTrue(failed.committed)
            controller.edit { it.copy(source = "/must-not-reimport.tar.gz", family = "Alpine", shell = "/bin/sh") }
            assertEquals(failed.draft.source, controller.state.value.draft.source)
            controller.submit()
            assertEquals(RootfsImportPhase.COMPLETE, idle().phase)
            assertEquals(1, inspectCalls.get())
            assertEquals(1, commitCalls.size)
            assertEquals(1, updates.size)
            assertEquals(1, completed.size)
            assertEquals("alpine", updates.single().distro)
            assertEquals("/bin/sh", updates.single().shell)
        }
    }

    @Test fun duplicateLaunchesAreBlockedAndCancellationCleansPreparedArchive() = runBlocking {
        withFixture {
            commitGate = CompletableDeferred()
            controller.submit()
            withTimeout(5_000) { commitStarted.await() }
            controller.submit()
            controller.submit()
            controller.cancel()
            assertEquals(RootfsImportPhase.FORM, idle().phase)
            assertEquals(1, inspectCalls.get())
            assertEquals(1, commitCalls.size)
            assertEquals(listOf("inspection-1"), discarded)
            assertTrue(liveHandles.isEmpty())
            assertTrue(cleanupWasActive)
            assertTrue(completed.isEmpty())
        }
    }

    @Test fun leavingDecisionCleansInspectionEvenFromCancelledLifecycle() = runBlocking {
        withFixture(listOf(existing("old", "incoming"))) {
            controller.submit()
            idle()
            controller.updateExisting()
            idle()
            val leaving = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { controller.close() } }
            }
            leaving.cancelAndJoin()
            assertEquals(listOf("inspection-1"), discarded)
            assertTrue(liveHandles.isEmpty())
            assertTrue(cleanupWasActive)
            assertTrue(completed.isEmpty())
        }
    }

    @Test fun archiveValidationFailureRetainsDraftAndAllowsRepair() = runBlocking {
        withFixture {
            failInspection = true
            val draft = controller.state.value.draft
            controller.submit()
            assertNotNull(idle().error)
            assertEquals(draft, controller.state.value.draft)
            assertTrue(commitCalls.isEmpty())
            failInspection = false
            controller.edit { it.copy(source = "/fixed.tar.gz") }
            controller.submit()
            assertEquals(RootfsImportPhase.COMPLETE, idle().phase)
            assertEquals(2, inspectCalls.get())
            assertEquals(1, commitCalls.size)
        }
    }
}
