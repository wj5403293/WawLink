package dev.wawlink.driver

import dev.wawlink.protocol.*
import dev.wawlink.runtime.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import kotlin.test.*

class OmpPromptDeliveryTest {
    @Test fun nativeDefaultModelIsVisibleBeforeTheFirstPrompt() = runBlocking {
        fixture(withManagement = true) {
            val catalogue = managementCatalogue()
            managementTransport.respondManagement(catalogue, nativeDefault)
            val initial = awaitState { !it.modelLoading && it.modelId == "resolved" }
            assertEquals("local", initial.providerId)
            assertEquals(ThinkingLevel.MAX, initial.thinkingLevel)
            assertEquals(SessionStatus.IDLE, initial.status)
            assertTrue(initial.feed.isEmpty())
            assertFalse(opened.isCompleted)
        }
    }

    @Test fun delayedDefaultDoesNotReplaceManualSelectionAndReplayRetainsIt() = runBlocking {
        fixture(withManagement = true) {
            val catalogue = managementCatalogue()
            controller.selectModel("manual", "picked")
            controller.selectThinkingLevel(ThinkingLevel.HIGH)
            awaitState { it.modelId == "picked" && it.thinkingLevel == ThinkingLevel.HIGH }
            managementTransport.respondManagement(catalogue, nativeDefault)
            val selected = awaitState { !it.modelLoading }
            assertEquals("manual", selected.providerId)
            assertEquals("picked", selected.modelId)
            assertEquals(ThinkingLevel.HIGH, selected.thinkingLevel)
            assertNull(selected.error)

            pool.close()
            pool = OmpProcessPool(runtime, data)
            val restored = withTimeout(5_000) { pool.session("session", "fixture", "/workspace").state.first { !it.modelLoading } }
            assertEquals("manual", restored.providerId)
            assertEquals("picked", restored.modelId)
            assertEquals(ThinkingLevel.HIGH, restored.thinkingLevel)
        }
    }

    @Test fun nativeDefaultFailureStopsLoadingAndPreservesAnEditableIdleSession() = runBlocking {
        fixture(withManagement = true) {
            val catalogue = managementCatalogue()
            managementTransport.respondManagement(catalogue, error = "UNAVAILABLE: Invalid native model provider configuration")
            val failed = awaitState { !it.modelLoading }
            assertEquals("", failed.providerId)
            assertEquals("", failed.modelId)
            assertEquals(SessionStatus.IDLE, failed.status)
            assertTrue(failed.error!!.contains("模型服务配置无效"))
            assertFalse(opened.isCompleted)
            assertTrue(pool.runtimeIssues.value.isEmpty())
        }
    }

    @Test fun firstMessageIsVisibleBeforeStartupAndKeepsItsIdentityAfterAcceptance() = runBlocking {
        fixture {
            controller.sendPrompt("立即显示")
            val optimistic = controller.state.value.feed.filterIsInstance<UserMessageItem>().single()
            assertEquals("立即显示", optimistic.text)
            assertFalse(optimistic.accepted)
            assertEquals(SessionStatus.RUNNING, controller.state.value.status)
            opened.await()
            gate.complete(transport)
            val request = transport.request()
            assertFalse(controller.state.value.feed.filterIsInstance<UserMessageItem>().single().accepted)
            transport.acceptUser(request)
            val accepted = awaitState { it.feed.filterIsInstance<UserMessageItem>().singleOrNull()?.accepted == true }
            assertEquals(optimistic.copy(accepted = true), accepted.feed.filterIsInstance<UserMessageItem>().single())
            transport.respond(request)
            transport.finish(request)
            val finished = awaitState { it.status == SessionStatus.DONE }
            assertEquals(listOf(optimistic.copy(accepted = true)), finished.feed.filterIsInstance<UserMessageItem>())
        }
    }

    @Test fun startupFailureWithdrawsTheMessageAndPersistsTheReasonWithoutPhantomHistory() = runBlocking {
        fixture {
            controller.sendPrompt("无法启动的消息")
            val id = controller.state.value.feed.single().id
            assertFalse(controller.state.value.feed.filterIsInstance<UserMessageItem>().single().accepted)
            opened.await()
            gate.completeExceptionally(IllegalStateException("容器启动被拒绝"))
            val failed = awaitState { it.error?.contains("容器启动被拒绝") == true }
            assertTrue(failed.feed.none { it.id == id })
            assertEquals(SessionStatus.ERROR, failed.status)
            pool.close()
            pool = OmpProcessPool(runtime, data)
            val restored = withTimeout(5_000) {
                pool.session("session", "fixture", "/workspace").state.first { it.error?.contains("容器启动被拒绝") == true }
            }
            assertTrue(restored.feed.none { it.id == id })
        }
    }

    @Test fun rejectedIdenticalResendDoesNotRemoveTheEarlierAcceptedMessage() = runBlocking {
        fixture {
            gate.complete(transport)
            controller.sendPrompt("相同文本")
            val first = transport.request()
            transport.acceptUser(first)
            transport.respond(first)
            transport.finish(first)
            val accepted = awaitState { it.status == SessionStatus.DONE }.feed.filterIsInstance<UserMessageItem>().single()
            controller.sendPrompt("相同文本")
            val second = transport.request()
            transport.respond(second, "消息被拒绝")
            val failed = awaitState { it.error == "消息被拒绝" && it.status == SessionStatus.ERROR }
            assertEquals(listOf(accepted), failed.feed.filterIsInstance<UserMessageItem>())
            assertTrue(pool.runtimeIssues.value.isEmpty())
        }
    }

    @Test fun acknowledgedButNotAdmittedPromptIsWithdrawnOnItsLateFailureResult() = runBlocking {
        fixture {
            gate.complete(transport)
            controller.sendPrompt("延迟拒绝")
            val id = controller.state.value.feed.single().id
            val request = transport.request()
            assertFalse(controller.state.value.feed.filterIsInstance<UserMessageItem>().single().accepted)
            transport.respond(request)
            transport.emit(buildJsonObject {
                put("type", "prompt_result"); put("id", request.string("id")); put("agentInvoked", false)
                put("status", "error"); put("sessionSettled", true)
                put("error", buildJsonObject { put("message", "发送钩子拒绝") })
            })
            val failed = awaitState { it.error == "发送钩子拒绝" && it.feed.none { item -> item.id == id } }
            assertEquals(SessionStatus.ERROR, failed.status)
        }
    }

    @Test fun queuePreviewsSurviveOldSnapshotsAndCanBeEditedOrRemovedBeforeDispatch() = runBlocking {
        fixture {
            controller.sendPrompt("首条消息")
            opened.await()
            controller.enqueuePrompt("原始追加")
            val queued = controller.state.value.queuedPrompts.single()
            controller.updateQueuedPrompt(queued.id, "编辑后的追加")
            assertEquals(listOf(queued.copy(text = "编辑后的追加")), controller.state.value.queuedPrompts)
            controller.enqueuePrompt("不应发送")
            val removed = controller.state.value.queuedPrompts.last()
            controller.removeQueuedPrompt(removed.id)
            assertEquals(listOf(queued.id), controller.state.value.queuedPrompts.map { it.id })
            gate.complete(transport)
            val first = transport.request()
            transport.acceptUser(first)
            transport.emit("""{"type":"queue_update","steering":[],"followUp":[]}""")
            assertEquals(listOf(queued.id), controller.state.value.queuedPrompts.map { it.id })
            transport.respond(first)
            val followUp = transport.request()
            assertEquals("follow_up", followUp.string("type"))
            assertEquals("编辑后的追加", followUp.string("message"))
            transport.emit("""{"type":"queue_update","steering":[],"followUp":["编辑后的追加"]}""")
            transport.respond(followUp)
            transport.emit("""{"type":"queue_update","steering":[],"followUp":[]}""")
            transport.acceptUser(followUp, startAgent = false)
            transport.finish(first)
            val finished = awaitState { it.status == SessionStatus.DONE && it.feed.filterIsInstance<UserMessageItem>().size == 2 }
            assertEquals(listOf("首条消息", "编辑后的追加"), finished.feed.filterIsInstance<UserMessageItem>().map { it.text })
            assertEquals(queued.id, finished.feed.filterIsInstance<UserMessageItem>().last().id)
            assertTrue(finished.queuedPrompts.isEmpty())
        }
    }

    @Test fun providerFailureKeepsTheMessageThatAlreadyReachedTheAgent() = runBlocking {
        fixture {
            gate.complete(transport)
            controller.sendPrompt("已接收的消息")
            val optimistic = controller.state.value.feed.filterIsInstance<UserMessageItem>().single()
            val request = transport.request()
            transport.acceptUser(request)
            transport.respond(request)
            transport.emit("""{"type":"message_end","messageId":"answer","message":{"role":"assistant","content":[],"stopReason":"error","errorMessage":"模型服务不可用"}}""")
            transport.finish(request, failed = true)
            val failed = awaitState { it.status == SessionStatus.ERROR }
            assertEquals("模型服务不可用", failed.error)
            assertEquals(listOf(optimistic.copy(accepted = true)), failed.feed.filterIsInstance<UserMessageItem>())
            assertTrue(pool.runtimeIssues.value.isEmpty())
        }
    }

    @Test fun unreadableAttachmentWithdrawsOnlyItsOwnMessageAndExplainsWhy() = runBlocking {
        fixture {
            val missing = AttachmentFile("missing", "缺失文件.txt", File(data, "does-not-exist").absolutePath)
            controller.sendPrompt("附件消息", listOf(missing))
            val failed = awaitState { it.error?.contains("无法读取附件：缺失文件.txt") == true }
            assertEquals(SessionStatus.ERROR, failed.status)
            assertTrue(failed.feed.filterIsInstance<UserMessageItem>().isEmpty())
            assertFalse(opened.isCompleted)
        }
    }

    @Test fun stopDuringStartupDoesNotResurrectTheCancelledFirstMessage() = runBlocking {
        fixture {
            controller.sendPrompt("被停止的消息")
            opened.await()
            controller.enqueuePrompt("保留追加")
            val queued = controller.state.value.queuedPrompts.single()
            controller.stopExecution()
            assertTrue(controller.state.value.feed.filterIsInstance<UserMessageItem>().isEmpty())
            gate.complete(transport)
            val stopped = awaitState { it.status == SessionStatus.STOPPED }
            assertEquals(listOf(queued), stopped.queuedPrompts)
            assertTrue(stopped.feed.filterIsInstance<UserMessageItem>().isEmpty())
            assertTrue(pool.runtimeIssues.value.isEmpty())
        }
    }

    @Test fun automaticModeAnswersNativePermissionPromptsButNotRealQuestions() = runBlocking {
        fixture {
            gate.complete(transport)
            controller.sendPrompt("自动执行")
            val request = transport.request()
            transport.acceptUser(request)
            transport.emit("""{"type":"extension_ui_request","id":"safety","method":"confirm","title":"确认执行","message":"允许修改文件"}""")
            assertEquals(buildJsonObject { put("type", "extension_ui_response"); put("id", "safety"); put("confirmed", true) }, transport.uiResponse())
            transport.emit("""{"type":"extension_ui_request","id":"tool","method":"select","title":"Allow tool: bash","options":["Approve Directory: /workspace","Always Approve","Approve Once","Deny"]}""")
            assertEquals(buildJsonObject { put("type", "extension_ui_response"); put("id", "tool"); put("value", "Approve Once") }, transport.uiResponse())
            val approved = awaitState { it.feed.filterIsInstance<ApprovalCardItem>().all { card -> card.resolution != null } && it.feed.any { item -> item.id == "tool" } }
            assertEquals(listOf(ApprovalDecision.ALLOW_ONCE, ApprovalDecision.ALLOW_ONCE), approved.feed.filterIsInstance<ApprovalCardItem>().map { it.resolution })
            transport.respond(request)
            transport.emit("""{"type":"extension_ui_request","id":"question","method":"select","title":"选择下一步","options":["Approve","Deny"]}""")
            val waiting = awaitState { it.feed.any { item -> item.id == "question" } }
            assertFalse(waiting.feed.filterIsInstance<AskCardItem>().single().isResolved)
            assertEquals(SessionStatus.WAITING, waiting.status)
            assertFalse(transport.hasUiResponse())
        }
    }

    @Test fun enablingAutomaticModeReleasesPendingApprovalBeforePromptAdmission() = runBlocking {
        fixture(autoApprove = false) {
            gate.complete(transport)
            controller.sendPrompt("等待审批后接收")
            val request = transport.request()
            transport.emit("""{"type":"extension_ui_request","id":"pending","method":"select","title":"Allow tool: write","options":["Approve","Deny"]}""")
            val waiting = awaitState { it.status == SessionStatus.WAITING }
            assertFalse(waiting.autoApprove)
            assertNull(waiting.feed.filterIsInstance<ApprovalCardItem>().single().resolution)
            assertFalse(transport.hasUiResponse())
            controller.toggleExecutionMode(true)
            assertEquals(buildJsonObject { put("type", "extension_ui_response"); put("id", "pending"); put("value", "Approve") }, transport.uiResponse())
            val resumed = awaitState { it.feed.filterIsInstance<ApprovalCardItem>().singleOrNull()?.resolution == ApprovalDecision.ALLOW_ONCE }
            assertTrue(resumed.autoApprove)
            assertEquals(SessionStatus.RUNNING, resumed.status)
            transport.acceptUser(request)
            transport.respond(request)
            transport.finish(request)
            awaitState { it.status == SessionStatus.DONE }
        }
    }

    @Test fun initialManualModeAndLaterSessionOverrideSurviveChangedCreationDefaults() = runBlocking {
        fixture(autoApprove = false) {
            gate.complete(transport)
            controller.sendPrompt("保留会话模式")
            val request = transport.request()
            transport.acceptUser(request)
            transport.respond(request)
            transport.finish(request)
            awaitState { it.status == SessionStatus.DONE }
            pool.close()
            pool = OmpProcessPool(runtime, data)
            val restored = pool.session("session", "fixture", "/workspace", autoApprove = true)
            withTimeout(5_000) { restored.state.first { !it.autoApprove } }
            restored.toggleExecutionMode(true)
            withTimeout(5_000) { restored.state.first { it.autoApprove } }
            pool.close()
            pool = OmpProcessPool(runtime, data)
            val restoredAgain = pool.session("session", "fixture", "/workspace", autoApprove = false)
            withTimeout(5_000) { restoredAgain.state.first { it.autoApprove } }
        }
    }

    @Test fun incompleteAskSubmissionStaysPendingAndValidBatchIsSentOnceInNativeOrder() = runBlocking {
        fixture {
            gate.complete(transport)
            controller.sendPrompt("分组提问")
            val prompt = transport.request()
            transport.acceptUser(prompt)
            transport.respond(prompt)
            transport.emit("""{"type":"extension_ui_request","id":"ask-batch","method":"ask","questions":[{"id":"language","question":"语言？","options":[{"label":"Kotlin (Recommended)"}]},{"id":"target","question":"平台？","multi":true,"options":[{"label":"Android"},{"label":"Linux"}]}]}""")
            awaitState { it.feed.any { item -> item.id == "ask-batch" } }
            controller.respondAsk("ask-batch", listOf(AskAnswer("language", listOf("Kotlin (Recommended)"))))
            val rejected = awaitState { it.error != null }
            assertEquals(SessionStatus.WAITING, rejected.status)
            assertFalse(rejected.feed.filterIsInstance<AskCardItem>().single().isResolved)
            assertFalse(transport.hasUiResponse())
            val answers = listOf(AskAnswer("target", listOf("Android", "Linux"), "另需桌面版"), AskAnswer("language", listOf("Kotlin (Recommended)")))
            controller.respondAsk("ask-batch", answers)
            val response = transport.uiResponse()
            val nativeAnswers = response["answers"]!!.jsonArray
            assertEquals(listOf("language", "target"), nativeAnswers.map { it.jsonObject.string("id") })
            assertEquals("Kotlin (Recommended)", nativeAnswers[0].jsonObject["selectedOptions"]!!.jsonArray.single().jsonPrimitive.content)
            val accepted = awaitState { it.feed.filterIsInstance<AskCardItem>().single().isResolved }
            assertEquals(listOf(answers[1], answers[0]), accepted.feed.filterIsInstance<AskCardItem>().single().answers)
            controller.respondAsk("ask-batch", answers)
            awaitState { it.error != null }
            assertFalse(transport.hasUiResponse())
        }
    }

    @Test fun nativeAnswerValidationRejectsWrongQuestionsUnknownChoicesAndSingleChoiceAmbiguity() {
        val question = AskQuestion("one", "选择", options = listOf(AskOption("A"), AskOption("B")))
        assertFailsWith<IllegalArgumentException> { validateAskAnswers(listOf(question), listOf(AskAnswer("one", listOf("other")))) }
        assertFailsWith<IllegalStateException> { validateAskAnswers(listOf(question), listOf(AskAnswer("different", listOf("A")))) }
        assertFailsWith<IllegalArgumentException> { validateAskAnswers(listOf(question), listOf(AskAnswer("one", listOf("A", "B")))) }
        assertFailsWith<IllegalArgumentException> { validateAskAnswers(listOf(question), listOf(AskAnswer("one", listOf("A"), "额外回答"))) }
        assertFailsWith<IllegalArgumentException> { validateAskAnswers(listOf(question), listOf(AskAnswer("one"))) }
        assertFailsWith<IllegalArgumentException> { validateAskAnswers(listOf(question), listOf(AskAnswer("one", listOf("A", "A")))) }
        assertEquals(listOf(AskAnswer("one")), validateAskAnswers(listOf(question.copy(multi = true)), listOf(AskAnswer("one"))))
    }

    @Test fun slashDuringExecutionUsesNativeParserWithoutResettingTheActiveRun() = runBlocking {
        fixture {
            gate.complete(transport)
            controller.sendPrompt("正在执行的任务")
            val prompt = transport.request()
            transport.acceptUser(prompt)
            transport.respond(prompt)
            transport.emit("""{"type":"extension_ui_request","id":"pending-ask","method":"select","title":"等待选择","options":["A","B"]}""")
            awaitState { it.status == SessionStatus.WAITING }
            controller.executeSlashCommand("todo", "help")
            val slash = transport.request()
            assertEquals("prompt", slash.string("type"))
            assertEquals(SessionStatus.WAITING, controller.state.value.status)
            transport.respond(slash, data = buildJsonObject { put("agentInvoked", false) })
            val preserved = awaitState { it.queuedPrompts.isEmpty() }
            assertEquals(SessionStatus.WAITING, preserved.status)
            assertFalse(preserved.feed.filterIsInstance<AskCardItem>().single().isResolved)
        }
    }

    @Test fun nativeCommandResponseWithoutPromptResultStopsTheSessionIndicator() = runBlocking {
        fixture {
            gate.complete(transport)
            controller.executeSlashCommand("todo", "help")
            val request = transport.request()
            assertEquals(SessionStatus.RUNNING, controller.state.value.status)
            transport.respond(request, data = buildJsonObject { put("agentInvoked", false) })
            val completed = awaitState { it.status == SessionStatus.DONE }
            assertEquals("/todo help", completed.feed.filterIsInstance<UserMessageItem>().single().text)
            assertTrue(completed.queuedPrompts.isEmpty())
        }
    }

    @Test fun missingChatStartupPublishesSanitizedIssueAndDismissalDoesNotHideNextFailure() = runBlocking {
        fixture {
            installationState = "MISSING"
            controller.sendPrompt("第一次尝试")
            opened.await()
            gate.completeExceptionally(IOException("cannot launch omp sk-private-key"))
            awaitState { it.status == SessionStatus.ERROR }
            val first = awaitIssue()
            assertFalse(first.message.contains("sk-private-key"))
            pool.dismissRuntimeIssue("fixture", first.id + 1)
            assertEquals(first, pool.runtimeIssues.value["fixture"])
            pool.dismissRuntimeIssue("fixture", first.id)
            assertTrue(pool.runtimeIssues.value.isEmpty())

            val broken = replacementChat(ready = false)
            broken.emitRaw("invalid native frame sk-private-key")
            controller.sendPrompt("第二次尝试")
            val next = awaitIssue()
            assertTrue(next.id > first.id)
            assertFalse(next.message.contains("sk-private-key"))
            pool.dismissRuntimeIssue("fixture", first.id)
            assertEquals(next, pool.runtimeIssues.value["fixture"])
            awaitState { it.status == SessionStatus.ERROR && it.feed.filterIsInstance<UserMessageItem>().isEmpty() }
            pool.dismissRuntimeIssue("fixture", next.id)
            controller.selectModel("manual", "after-reader")
            awaitState { it.modelId == "after-reader" }
            assertTrue(pool.runtimeIssues.value.isEmpty(), "The same child's startup and reader must not report twice")
        }
    }

    @Test fun healthyInstallationStartupRejectionKeepsActualErrorWithoutSuggestingRepair() = runBlocking {
        fixture {
            installationState = "READY"
            controller.sendPrompt("配置拒绝")
            opened.await()
            gate.completeExceptionally(IllegalStateException("模型设置无效"))
            val failed = awaitState { it.error == "模型设置无效" }
            assertEquals(SessionStatus.ERROR, failed.status)
            withTimeout(5_000) { inspectionFinished.await() }
            controller.selectModel("manual", "editable")
            awaitState { it.modelId == "editable" }
            assertTrue(pool.runtimeIssues.value.isEmpty())
        }
    }

    @Test fun defaultPreviewNativeFailureRefreshesSameRepositoryAfterRepairWithoutStartingChat() = runBlocking {
        fixture(withManagement = true) {
            managementCatalogue()
            managementTransport.destroy()
            val failed = awaitState { !it.modelLoading && it.error != null }
            val issue = awaitIssue()
            val repository = pool.management("fixture", "/workspace")
            pool.prepareOmpRepair("fixture")
            assertEquals(failed.error, controller.state.value.error)
            pool.dismissRuntimeIssue("fixture", issue.id)
            val replacement = replacementManagement()
            val refresh = async { pool.refreshAfterOmpRepair("fixture") }
            replacement.respondManagement(replacement.managementRequest("init"), JsonPrimitive(true))
            replacement.respondManagement(replacement.managementRequest("snapshot"), JsonObject(emptyMap()))
            replacement.respondManagement(replacement.managementRequest("modelsSnapshot"), nativeDefault)
            refresh.await()
            assertSame(repository, pool.management("fixture", "/workspace"))
            assertSame(controller, pool.session("session", "fixture", "/workspace"))
            assertEquals("resolved", controller.state.value.modelId)
            assertNull(controller.state.value.error)
            assertFalse(opened.isCompleted)
            assertTrue(pool.runtimeIssues.value.isEmpty())
        }
    }

    @Test fun repairParksOwnedProcessesPreservesAcceptedMessagesAndAllowsNewNativeAttempt() = runBlocking {
        fixture(withManagement = true) {
            managementTransport.respondManagement(managementCatalogue(), nativeDefault)
            awaitState { !it.modelLoading }
            gate.complete(transport)
            controller.sendPrompt("保留已接收消息")
            val first = transport.request()
            transport.acceptUser(first)
            transport.respond(first)
            awaitState { it.feed.filterIsInstance<UserMessageItem>().singleOrNull()?.accepted == true }
            val accepted = controller.state.value.feed.filterIsInstance<UserMessageItem>().single()
            val originalModel = controller.state.value.modelId
            pool.prepareOmpRepair("fixture")
            assertFalse(transport.isAlive)
            assertFalse(managementTransport.isAlive)
            assertTrue(pool.runtimeIssues.value.isEmpty())
            assertEquals(listOf(accepted), controller.state.value.feed.filterIsInstance<UserMessageItem>())
            val management = replacementManagement()
            val refresh = async { pool.refreshAfterOmpRepair("fixture") }
            management.respondManagement(management.managementRequest("init"), JsonPrimitive(true))
            management.respondManagement(management.managementRequest("snapshot"), JsonObject(emptyMap()))
            management.respondManagement(management.managementRequest("modelsSnapshot"), Json.parseToJsonElement("""{"models":[],"roles":[{"id":"default","model":"other/new","providerId":"other","modelId":"new"}]}"""))
            refresh.await()
            assertEquals(originalModel, controller.state.value.modelId)
            assertEquals(listOf(accepted), controller.state.value.feed.filterIsInstance<UserMessageItem>())

            val replacement = replacementChat()
            controller.sendPrompt("修复后继续")
            val next = replacement.request()
            replacement.acceptUser(next)
            replacement.respond(next)
            replacement.finish(next)
            val resumed = awaitState { it.status == SessionStatus.DONE }
            assertEquals(listOf(accepted.text, "修复后继续"), resumed.feed.filterIsInstance<UserMessageItem>().map { it.text })
            assertSame(controller, pool.session("session", "fixture", "/workspace"))
            assertTrue(pool.runtimeIssues.value.isEmpty())
            replacement.destroy()
            val issue = awaitIssue()
            assertEquals("fixture", issue.containerId)
            assertEquals(1, pool.runtimeIssues.value.size)
        }
    }

    @Test fun repairDuringStartupAndExplicitParkDoNotReportRuntimeDamage() = runBlocking {
        fixture {
            val starting = RpcTransport(ready = false)
            gate.complete(starting)
            controller.sendPrompt("等待启动")
            opened.await()
            withTimeout(5_000) { pool.prepareOmpRepair("fixture") }
            assertTrue(pool.runtimeIssues.value.isEmpty())
            val replacement = replacementChat()
            controller.sendPrompt("重新启动")
            val request = replacement.request()
            replacement.acceptUser(request)
            replacement.respond(request)
            replacement.finish(request)
            awaitState { it.status == SessionStatus.DONE }
            pool.closeSession("session")
            controller.selectModel("manual", "parked")
            awaitState { it.modelId == "parked" }
            assertFalse(replacement.isAlive)
            assertTrue(pool.runtimeIssues.value.isEmpty())
        }
    }

    @Test fun chatAndManagementFailuresMergeByContainerAndBrokenWritesCanNotifyOnANewAttempt() = runBlocking {
        fixture(withManagement = true) {
            managementTransport.respondManagement(managementCatalogue(), nativeDefault)
            gate.complete(transport)
            controller.sendPrompt("启动聊天")
            transport.request()
            transport.destroy()
            managementTransport.destroy()
            val first = awaitIssue()
            awaitState { it.status == SessionStatus.ERROR && !it.modelLoading }
            withTimeout(5_000) { pool.management("fixture", "/workspace").state.first { !it.modelsLoading && it.error != null } }
            assertEquals(mapOf("fixture" to first), pool.runtimeIssues.value)
            pool.dismissRuntimeIssue("fixture", first.id)

            val replacement = replacementChat()
            controller.sendPrompt("新的原生进程")
            val request = replacement.request()
            replacement.acceptUser(request)
            replacement.respond(request)
            replacement.finish(request)
            awaitState { it.status == SessionStatus.DONE }
            replacement.failWrites = true
            controller.selectModel("manual", "changed")
            val next = awaitIssue()
            assertTrue(next.id > first.id)
            assertEquals(1, pool.runtimeIssues.value.size)
        }
    }

    @Test fun successfulRepairRefreshDoesNotReplaceExplicitModelOrThinkingSelection() = runBlocking {
        fixture(withManagement = true) {
            managementTransport.respondManagement(managementCatalogue(), nativeDefault)
            awaitState { !it.modelLoading }
            controller.selectModel("manual", "selected")
            controller.selectThinkingLevel(ThinkingLevel.HIGH)
            awaitState { it.modelId == "selected" && it.thinkingLevel == ThinkingLevel.HIGH }
            pool.prepareOmpRepair("fixture")
            val replacement = replacementManagement()
            val refresh = async { pool.refreshAfterOmpRepair("fixture") }
            replacement.respondManagement(replacement.managementRequest("init"), JsonPrimitive(true))
            replacement.respondManagement(replacement.managementRequest("snapshot"), JsonObject(emptyMap()))
            replacement.respondManagement(replacement.managementRequest("modelsSnapshot"), nativeDefault)
            refresh.await()
            assertEquals("manual", controller.state.value.providerId)
            assertEquals("selected", controller.state.value.modelId)
            assertEquals(ThinkingLevel.HIGH, controller.state.value.thinkingLevel)
            assertFalse(opened.isCompleted)
        }
    }

    @Test fun exitBeforeGreetingOnlySuggestsRepairWhenIsolatedInstallationCheckIsBroken() = runBlocking {
        for (health in listOf("READY", "BROKEN")) fixture {
            installationState = health
            val starting = RpcTransport(ready = false)
            gate.complete(starting)
            controller.sendPrompt("未完成原生启动")
            opened.await()
            starting.emitStderr("credential-bearing native diagnostic sk-stderr-secret")
            starting.destroy()
            val failed = awaitState { it.status == SessionStatus.ERROR }
            assertFalse(failed.error.orEmpty().contains("sk-stderr-secret"))
            if (health == "BROKEN") {
                assertTrue(awaitIssue().message.contains("安装损坏"))
            } else {
                inspectionFinished.await()
                controller.selectModel("manual", "configuration-recovery")
                awaitState { it.modelId == "configuration-recovery" }
                assertTrue(pool.runtimeIssues.value.isEmpty())
            }
        }
    }

    @Test fun closingSessionDuringStartupParksControllerWithoutRepairNotification() = runBlocking {
        fixture {
            val starting = RpcTransport(ready = false)
            gate.complete(starting)
            controller.sendPrompt("关闭启动中的会话")
            opened.await()
            pool.closeSession("session")
            controller.selectModel("manual", "parked-before-ready")
            awaitState { it.modelId == "parked-before-ready" }
            assertFalse(starting.isAlive)
            assertTrue(pool.runtimeIssues.value.isEmpty())
            assertSame(controller, pool.session("session", "fixture", "/workspace"))
        }
    }

    private val nativeDefault = Json.parseToJsonElement("""{"models":[{"provider":"local","id":"resolved","available":true}],"roles":[{"id":"default","model":"local/resolved","providerId":"local","modelId":"resolved","thinking":"max"}]}""")

    private suspend fun fixture(autoApprove: Boolean = true, withManagement: Boolean = false, block: suspend Fixture.() -> Unit) {
        val fixture = Fixture(autoApprove, withManagement)
        try { block(fixture) } finally { fixture.close() }
    }

    private class Fixture(autoApprove: Boolean, withManagement: Boolean) : AutoCloseable {
        val directory = Files.createTempDirectory("wawlink-delivery-").toFile()
        val data = File(directory, "gui")
        val transport = RpcTransport()
        val managementTransport = RpcTransport(management = true)
        val gate = CompletableDeferred<Process>()
        val opened = CompletableDeferred<Unit>()
        val inspectionFinished = CompletableDeferred<Unit>()
        @Volatile var installationState = "READY"
        private val chatStarts = AtomicInteger()
        private val managementStarts = AtomicInteger()
        private val chatReplacements = Channel<RpcTransport>(Channel.UNLIMITED)
        private val managementReplacements = Channel<RpcTransport>(Channel.UNLIMITED)
        private val replacements = CopyOnWriteArrayList<RpcTransport>()
        private val local = LocalContainerRuntime(RuntimePaths(File(directory, "runtime")))
        val runtime = object : ContainerRuntime by local {
            override suspend fun openProcess(id: String, arguments: List<String>, cwd: String, environment: Map<String, String>): Process {
                if (arguments.lastOrNull() == "--inspect") {
                    inspectionFinished.complete(Unit)
                    return FinishedProcess("WAWLINK_OMP_CHECK\t$installationState\tinstallation check\n")
                }
                if (arguments.firstOrNull() == "/usr/local/bin/bun") {
                    check(withManagement) { "未配置默认模型" }
                    return if (managementStarts.getAndIncrement() == 0) managementTransport else managementReplacements.receive()
                }
                opened.complete(Unit)
                return if (chatStarts.getAndIncrement() == 0) gate.await() else chatReplacements.receive()
            }
        }
        var pool: OmpProcessPool
        val controller: AgentSessionController
        init {
            val root = File(directory, "root").apply { mkdirs() }
            File(root, "etc").mkdirs()
            val shell = File(root, "bin/sh").apply { parentFile.mkdirs() }
            Files.copy(File("/bin/sh").canonicalFile.toPath(), shell.toPath())
            check(shell.setExecutable(true))
            runBlocking { local.register(ContainerConfig("fixture", "发送验证", root.canonicalPath, arch = "amd64", link2symlink = false)) }
            pool = OmpProcessPool(runtime, data)
            controller = pool.session("session", "fixture", "/workspace", autoApprove = autoApprove)
        }
        suspend fun managementCatalogue(): JsonObject {
            managementTransport.respondManagement(managementTransport.managementRequest("init"), JsonPrimitive(true))
            managementTransport.respondManagement(managementTransport.managementRequest("snapshot"), JsonObject(emptyMap()))
            return managementTransport.managementRequest("modelsSnapshot")
        }
        suspend fun awaitState(predicate: (ChatSessionUiState) -> Boolean): ChatSessionUiState = withTimeout(5_000) { controller.state.first(predicate) }
        suspend fun awaitIssue(): OmpRuntimeIssue = withTimeout(5_000) { pool.runtimeIssues.first { "fixture" in it }.getValue("fixture") }
        suspend fun replacementChat(ready: Boolean = true): RpcTransport = RpcTransport(ready = ready).also {
            replacements += it
            chatReplacements.send(it)
        }
        suspend fun replacementManagement(): RpcTransport = RpcTransport(management = true).also {
            replacements += it
            managementReplacements.send(it)
        }
        override fun close() {
            gate.completeExceptionally(IllegalStateException("验证结束"))
            transport.destroy()
            managementTransport.destroy()
            pool.close()
            replacements.forEach(RpcTransport::destroy)
            chatReplacements.close()
            managementReplacements.close()
            local.close()
            directory.deleteRecursively()
        }
    }

    /** Controllable RPC transport: admission and terminal events are independent, as in native RPC. */
    private class RpcTransport(private val management: Boolean = false, ready: Boolean = true) : Process() {
        private val stdout = PipedInputStream(65_536)
        private val eventOutput = PipedOutputStream(stdout)
        private val stderr = PipedInputStream(4096)
        private val errorOutput = PipedOutputStream(stderr)
        private val exited = CountDownLatch(1)
        private val requests = Channel<JsonObject>(Channel.UNLIMITED)
        private val uiResponses = Channel<JsonObject>(Channel.UNLIMITED)
        private val managementRequests = Channel<JsonObject>(Channel.UNLIMITED)
        private var running = false
        @Volatile var failWrites = false
        private val stdin = object : OutputStream() {
            private val buffer = ByteArrayOutputStream()
            override fun write(value: Int) {
                if (failWrites) throw IOException("Native RPC pipe is unavailable")
                if (value != '\n'.code) { buffer.write(value); return }
                val request = Json.parseToJsonElement(buffer.toString(Charsets.UTF_8)).jsonObject
                buffer.reset()
                if (request["op"] != null) { check(managementRequests.trySend(request).isSuccess); return }
                when (request.string("type")) {
                    "prompt", "steer", "follow_up" -> check(requests.trySend(request).isSuccess)
                    "extension_ui_response" -> check(uiResponses.trySend(request).isSuccess)
                    "get_state" -> respond(request, data = buildJsonObject {
                        put("isStreaming", running); put("isSettled", !running)
                    })
                    else -> respond(request)
                }
            }
            override fun close() = destroy()
        }
        init { if (!management && ready) emit("""{"type":"ready"}""") }
        suspend fun request(): JsonObject = withTimeout(5_000) { requests.receive() }
        suspend fun managementRequest(operation: String): JsonObject = withTimeout(5_000) {
            managementRequests.receive().also { assertEquals(operation, it.string("op")) }
        }
        fun respondManagement(request: JsonObject, result: JsonElement = JsonNull, error: String? = null) {
            val models = (result as? JsonObject)?.get("models") as? JsonArray
            val body = if (request.string("op") == "modelsSnapshot" && error == null && models != null) {
                emit(buildJsonObject { put("event", "models_page"); put("id", request["id"]!!); put("models", models) })
                JsonObject(result.jsonObject - "models" + ("modelsCount" to JsonPrimitive(models.size)))
            } else result
            emit(buildJsonObject {
                put("id", request["id"]!!)
                if (error == null) put("result", body) else put("error", error)
            })
        }
        suspend fun uiResponse(): JsonObject = withTimeout(5_000) { uiResponses.receive() }
        fun hasUiResponse(): Boolean = uiResponses.tryReceive().isSuccess
        fun emit(json: String) = emit(Json.parseToJsonElement(json).jsonObject)
        @Synchronized fun emitRaw(value: String) {
            eventOutput.write((value + "\n").toByteArray())
            eventOutput.flush()
        }
        @Synchronized fun emitStderr(value: String) {
            errorOutput.write(value.toByteArray())
            errorOutput.flush()
        }
        @Synchronized fun emit(frame: JsonObject) {
            if (frame.string("type") == "agent_start") running = true
            if (frame.string("type") == "session_settled") running = false
            eventOutput.write((frame.toString() + "\n").toByteArray(Charsets.UTF_8))
            eventOutput.flush()
        }
        fun respond(request: JsonObject, error: String? = null, data: JsonObject = JsonObject(emptyMap())) = emit(buildJsonObject {
            put("type", "response"); put("id", request.string("id")); put("command", request.string("type"))
            put("success", error == null); if (error != null) put("error", error) else put("data", data)
        })
        fun acceptUser(request: JsonObject, startAgent: Boolean = true) {
            if (startAgent) emit("""{"type":"agent_start"}""")
            val messageId = "user-${request.string("id")}"
            for (type in listOf("message_start", "message_end")) emit(buildJsonObject {
                put("type", type); put("messageId", messageId)
                put("message", buildJsonObject { put("role", "user"); put("content", request.string("message")); put("timestamp", 123) })
            })
        }
        fun finish(request: JsonObject, failed: Boolean = false) {
            emit("""{"type":"agent_end","isTerminal":true,"messages":[]}""")
            emit(buildJsonObject {
                put("type", "prompt_result"); put("id", request.string("id")); put("agentInvoked", true)
                put("status", if (failed) "error" else "completed"); put("sessionSettled", true)
            })
            emit("""{"type":"session_settled"}""")
        }
        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = stderr
        override fun waitFor(): Int { exited.await(); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = exited.await(timeout, unit)
        override fun exitValue(): Int { check(!isAlive); return 0 }
        override fun isAlive(): Boolean = exited.count > 0
        override fun destroy() {
            if (!isAlive) return
            exited.countDown()
            eventOutput.close()
            errorOutput.close()
        }
    }
    private class FinishedProcess(output: String) : Process() {
        private val input = ByteArrayInputStream(output.toByteArray())
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun exitValue(): Int = 0
        override fun isAlive(): Boolean = false
        override fun destroy() { }
    }
}
