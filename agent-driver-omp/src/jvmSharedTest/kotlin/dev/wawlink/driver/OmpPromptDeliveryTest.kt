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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import kotlin.test.*

class OmpPromptDeliveryTest {
    @Test
    fun explorerRejectsMissingAndCrossInstanceReceipts() = runBlocking {
        fixture {
            gate.complete(transport)
            controller.sendPrompt("启动会话")
            transport.request()
            val explorer = pool.explorer("session")
            for (responseInstance in listOf(null, "previous-process", chatManagementInstance)) {
                val result = async { runCatching { explorer.cancelNavigation() } }
                val requestFile = withTimeout(5_000) {
                    var file: File? = null
                    while (file == null) {
                        file = directory.walkTopDown().firstOrNull { it.isFile && it.name.startsWith("request-") && it.extension == "json" }
                        if (file == null) delay(10)
                    }
                    requireNotNull(file)
                }
                val request = Json.parseToJsonElement(requestFile.readText()).jsonObject
                assertEquals("explorer_cancel_navigation", request.string("op"))
                assertTrue(chatManagementInstance.isNotBlank())
                assertEquals(chatManagementInstance, request.string("instance"))
                val responseFile = File(requestFile.parentFile, "response-${request.string("id")}.json")
                val temporary = File(requestFile.parentFile, "${responseFile.name}.tmp")
                temporary.writeText(buildJsonObject {
                    put("success", true)
                    responseInstance?.let { put("instance", it) }
                }.toString())
                assertTrue(temporary.renameTo(responseFile))
                val response = withTimeout(5_000) { result.await() }
                if (responseInstance == chatManagementInstance) assertTrue(response.isSuccess)
                else assertContains(response.exceptionOrNull()?.message.orEmpty(), "实例不匹配")
            }
        }
    }

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

    @Test fun dismissedPreviewFailureStaysDismissedWhenTheSameFailureIsReloaded() = runBlocking {
        fixture(withManagement = true) {
            val failure = "UNAVAILABLE: Invalid native model provider configuration"
            managementTransport.respondManagement(managementCatalogue(), error = failure)
            val failed = awaitState { !it.modelLoading && it.error != null }
            controller.dismissError()
            val dismissed = awaitState { it.error == null && !it.modelLoading }
            assertEquals(failed.copy(error = null), dismissed)

            pool.close()
            pool = OmpProcessPool(runtime, data)
            val replacement = replacementManagement()
            val restored = pool.session("session", "fixture", "/workspace")
            replacement.respondManagement(replacement.managementRequest("init"), JsonPrimitive(true))
            replacement.respondManagement(replacement.managementRequest("snapshot"), JsonObject(emptyMap()))
            replacement.respondManagement(replacement.managementRequest("modelsSnapshot"), error = failure)
            val replayed = withTimeout(5_000) { restored.state.first { !it.modelLoading } }
            assertNull(replayed.error)
            assertEquals(SessionStatus.IDLE, replayed.status)

            pool.close()
            pool = OmpProcessPool(runtime, data)
            val changed = replacementManagement()
            val next = pool.session("session", "fixture", "/workspace")
            changed.respondManagement(changed.managementRequest("init"), JsonPrimitive(true))
            changed.respondManagement(changed.managementRequest("snapshot"), JsonObject(emptyMap()))
            changed.respondManagement(changed.managementRequest("modelsSnapshot"), error = "另一个模型读取故障")
            val newFailure = withTimeout(5_000) { next.state.first { !it.modelLoading } }
            assertEquals("另一个模型读取故障", newFailure.error)

            pool.close()
            pool = OmpProcessPool(runtime, data)
            val recurring = replacementManagement()
            val last = pool.session("session", "fixture", "/workspace")
            recurring.respondManagement(recurring.managementRequest("init"), JsonPrimitive(true))
            recurring.respondManagement(recurring.managementRequest("snapshot"), JsonObject(emptyMap()))
            recurring.respondManagement(recurring.managementRequest("modelsSnapshot"), error = failure)
            val laterFailure = withTimeout(5_000) { last.state.first { !it.modelLoading } }
            assertEquals(failed.error, laterFailure.error)
        }
    }

    @Test fun recoveredPreviewCanReportTheSameFailureAgainAfterRestart() = runBlocking {
        fixture(withManagement = true) {
            val failure = "UNAVAILABLE: Invalid native model provider configuration"
            managementTransport.respondManagement(managementCatalogue(), error = failure)
            val initial = awaitState { !it.modelLoading && it.error != null }
            controller.dismissError()
            awaitState { !it.modelLoading && it.error == null }

            pool.close()
            pool = OmpProcessPool(runtime, data)
            val recovered = replacementManagement()
            val restored = pool.session("session", "fixture", "/workspace")
            recovered.respondManagement(recovered.managementRequest("init"), JsonPrimitive(true))
            recovered.respondManagement(recovered.managementRequest("snapshot"), JsonObject(emptyMap()))
            recovered.respondManagement(recovered.managementRequest("modelsSnapshot"), nativeDefault)
            withTimeout(5_000) { restored.state.first { !it.modelLoading && it.modelId == "resolved" } }

            pool.close()
            pool = OmpProcessPool(runtime, data)
            val recurring = replacementManagement()
            val last = pool.session("session", "fixture", "/workspace")
            recurring.respondManagement(recurring.managementRequest("init"), JsonPrimitive(true))
            recurring.respondManagement(recurring.managementRequest("snapshot"), JsonObject(emptyMap()))
            recurring.respondManagement(recurring.managementRequest("modelsSnapshot"), error = failure)
            val laterFailure = withTimeout(5_000) { last.state.first { !it.modelLoading } }
            assertEquals(initial.error, laterFailure.error)
        }
    }

    @Test fun dismissedRunErrorStaysHiddenWhenTheRunFinishesAndTheJournalReplays() = runBlocking {
        fixture {
            controller.selectModel("manual", "picked")
            awaitState { it.modelId == "picked" && !it.modelLoading }
            gate.complete(transport)
            controller.sendPrompt("已接收的消息")
            val request = transport.request()
            transport.acceptUser(request)
            transport.respond(request)
            awaitState { it.isRunning && it.feed.filterIsInstance<UserMessageItem>().singleOrNull()?.accepted == true }
            transport.emit("""{"type":"message_end","messageId":"answer","message":{"role":"assistant","content":[],"stopReason":"error","errorMessage":"模型服务不可用"}}""")
            val failed = awaitState { it.isRunning && it.error == "模型服务不可用" }
            controller.dismissError()
            val dismissed = awaitState { it.isRunning && it.error == null }
            assertEquals(failed.copy(error = null), dismissed)

            transport.finish(request, failed = true)
            transport.emit("""{"type":"notice","level":"info","message":"事件已处理"}""")
            val finished = awaitState { it.actionText == "事件已处理" }
            assertEquals(SessionStatus.ERROR, finished.status)
            assertNull(finished.error)
            assertEquals(failed.feed, finished.feed)

            pool.close()
            pool = OmpProcessPool(runtime, data)
            val restored = pool.session("session", "fixture", "/workspace")
            val replayed = withTimeout(5_000) { restored.state.first { it.status == SessionStatus.ERROR && !it.modelLoading } }
            assertNull(replayed.error)
            assertEquals(failed.feed, replayed.feed)

            val replacement = replacementChat()
            restored.sendPrompt("下一轮")
            val next = replacement.request()
            replacement.acceptUser(next)
            replacement.respond(next)
            replacement.emit("""{"type":"message_end","messageId":"next-answer","message":{"role":"assistant","content":[],"stopReason":"error","errorMessage":"模型服务不可用"}}""")
            val newFailure = withTimeout(5_000) { restored.state.first { it.error == "模型服务不可用" } }
            assertTrue(newFailure.isRunning)
        }
    }

    @Test fun dismissalPreservesLocalFailureHistoryAndDoesNotHideANewFailure() = runBlocking {
        fixture {
            controller.selectModel("manual", "picked")
            awaitState { it.modelId == "picked" && !it.modelLoading }
            val missing = AttachmentFile("missing", "缺失文件.txt", File(data, "missing").absolutePath)
            controller.sendPrompt("附件消息", listOf(missing))
            val failed = awaitState { it.error?.contains("缺失文件.txt") == true }
            controller.dismissError()
            val dismissed = awaitState { it.error == null && it.status == SessionStatus.ERROR }
            assertEquals(failed.copy(error = null), dismissed)

            pool.close()
            pool = OmpProcessPool(runtime, data)
            val restored = pool.session("session", "fixture", "/workspace")
            val replayed = withTimeout(5_000) { restored.state.first { it.status == SessionStatus.ERROR && !it.modelLoading } }
            assertNull(replayed.error)
            assertEquals(failed.feed, replayed.feed)
            restored.sendPrompt("再次发送", listOf(missing))
            val newFailure = withTimeout(5_000) { restored.state.first { it.error?.contains("缺失文件.txt") == true } }
            assertEquals(SessionStatus.ERROR, newFailure.status)
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

    @Test fun stopBeforeGreetingReleasesStartupAndPreservesQueuedInput() = runBlocking {
        fixture(withManagement = true) {
            managementTransport.respondManagement(managementCatalogue(), nativeDefault)
            awaitState { !it.modelLoading }
            val starting = RpcTransport(ready = false)
            gate.complete(starting)
            controller.sendPrompt("等待原生 ready")
            withTimeout(5_000) { starting.outputOpened.await() }
            controller.enqueuePrompt("停止后保留的追加")
            val queued = controller.state.value.queuedPrompts.single()

            controller.stopExecution()
            awaitState { it.status == SessionStatus.STOPPED }
            controller.selectModel("manual", "after-stop-before-ready")
            val stopped = awaitState { it.modelId == "after-stop-before-ready" }

            assertFalse(starting.isAlive)
            assertEquals(listOf(queued), stopped.queuedPrompts)
            assertTrue(stopped.feed.filterIsInstance<UserMessageItem>().isEmpty())
            assertNull(stopped.error)
            assertTrue(pool.runtimeIssues.value.isEmpty())
        }
    }

    @Test fun stopAfterGreetingPreservesAcceptedHistoryAndAllowsFurtherInteraction() = runBlocking {
        fixture(withManagement = true) {
            managementTransport.respondManagement(managementCatalogue(), nativeDefault)
            awaitState { !it.modelLoading }
            gate.complete(transport)
            controller.sendPrompt("已经接收的消息")
            val request = transport.request()
            transport.acceptUser(request)
            transport.respond(request)
            awaitState { it.feed.filterIsInstance<UserMessageItem>().any { user -> user.accepted } }

            controller.stopExecution()
            awaitState { it.status == SessionStatus.STOPPED }
            controller.selectModel("manual", "after-ready-stop")
            val stopped = awaitState { it.modelId == "after-ready-stop" }

            assertFalse(transport.isAlive)
            assertEquals(listOf("已经接收的消息"), stopped.feed.filterIsInstance<UserMessageItem>().map { it.text })
            assertTrue(stopped.feed.filterIsInstance<UserMessageItem>().single().accepted)
            assertNull(stopped.error)
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

    @Test fun containerStopDuringLaunchSettlesWithoutRepairAndActorCanRetryAfterRapidRestart() = runBlocking {
        fixture(withManagement = true) {
            managementTransport.respondManagement(managementCatalogue(), nativeDefault)
            awaitState { !it.modelLoading }
            controller.sendPrompt("停止启动")
            opened.await()
            stopContainer()
            restartContainer()
            gate.completeExceptionally(IOException("intentional engine teardown"))
            val stopped = awaitState { it.status == SessionStatus.STOPPED }
            assertNull(stopped.error)
            assertTrue(stopped.feed.filterIsInstance<UserMessageItem>().isEmpty())
            assertTrue(pool.runtimeIssues.value.isEmpty())

            val replacement = replacementChat()
            controller.sendPrompt("重新发送")
            val request = replacement.request()
            replacement.acceptUser(request)
            replacement.respond(request)
            replacement.finish(request)
            val resumed = awaitState { it.status == SessionStatus.DONE }
            assertEquals(listOf("重新发送"), resumed.feed.filterIsInstance<UserMessageItem>().map { it.text })
            assertNull(resumed.error)
            replacement.destroy()
            val genuineExit = awaitIssue()
            assertEquals(1L, genuineExit.stopGeneration)
            awaitState { it.status == SessionStatus.ERROR }
        }
    }

    @Test fun containerStopWithPendingQueuePreservesAcceptedHistoryAndRestoresQueueOnRetry() = runBlocking {
        fixture(withManagement = true) {
            managementTransport.respondManagement(managementCatalogue(), nativeDefault)
            awaitState { !it.modelLoading }
            gate.complete(transport)
            controller.sendPrompt("保留历史")
            val first = transport.request()
            transport.acceptUser(first)
            transport.respond(first)
            val accepted = awaitState { it.feed.filterIsInstance<UserMessageItem>().singleOrNull()?.accepted == true }
                .feed.filterIsInstance<UserMessageItem>().single()
            controller.enqueuePrompt("保留待发队列")
            transport.request()
            val queued = controller.state.value.queuedPrompts.single()
            stopContainer()
            restartContainer()
            val stopped = awaitState { it.status == SessionStatus.STOPPED && !transport.isAlive }
            assertEquals(listOf(accepted), stopped.feed.filterIsInstance<UserMessageItem>())
            assertEquals(listOf(queued), stopped.queuedPrompts)
            assertNull(stopped.error)
            assertTrue(pool.runtimeIssues.value.isEmpty())

            val replacement = replacementChat()
            controller.sendPrompt("继续")
            val restored = replacement.request()
            assertEquals("follow_up", restored.string("type"))
            assertEquals(queued.text, restored.string("message"))
            replacement.respond(restored)
            val next = replacement.request()
            replacement.acceptUser(next)
            replacement.respond(next)
            replacement.finish(next)
            val resumed = awaitState { it.status == SessionStatus.DONE }
            assertEquals(listOf(accepted.text, "继续"), resumed.feed.filterIsInstance<UserMessageItem>().map { it.text })
            assertNull(resumed.error)
            assertTrue(pool.runtimeIssues.value.isEmpty())
        }
    }

    @Test fun containerStopBeforeGreetingCancelsNativeChildWithoutLosingTheController() = runBlocking {
        fixture(withManagement = true) {
            managementTransport.respondManagement(managementCatalogue(), nativeDefault)
            awaitState { !it.modelLoading }
            val starting = RpcTransport(ready = false)
            gate.complete(starting)
            controller.sendPrompt("等待 greeting")
            opened.await()
            stopContainer()
            val stopped = awaitState { it.status == SessionStatus.STOPPED && !starting.isAlive }
            assertNull(stopped.error)
            assertTrue(stopped.feed.filterIsInstance<UserMessageItem>().isEmpty())
            controller.selectModel("manual", "still-editable")
            awaitState { it.modelId == "still-editable" }
            assertSame(controller, pool.session("session", "fixture", "/workspace"))
            assertTrue(pool.runtimeIssues.value.isEmpty())
        }
    }

    @Test fun containerStopDuringDefaultPreviewFinishesLoadingWithoutAnErrorOrChatStart() = runBlocking {
        fixture(withManagement = true) {
            managementCatalogue()
            stopContainer()
            restartContainer()
            val stopped = awaitState { !it.modelLoading && it.status == SessionStatus.STOPPED }
            assertNull(stopped.error)
            assertFalse(opened.isCompleted)
            assertTrue(pool.runtimeIssues.value.isEmpty())
        }
    }

    @Test fun delayedInstallationInspectionCannotPublishRepairAfterContainerRestart() = runBlocking {
        fixture(withManagement = true) {
            managementTransport.respondManagement(managementCatalogue(), nativeDefault)
            awaitState { !it.modelLoading }
            val delayedProbe = CompletableDeferred<Process>()
            inspectionGate = delayedProbe
            controller.sendPrompt("检查启动失败")
            opened.await()
            gate.completeExceptionally(IOException("OMP launch failed"))
            inspectionFinished.await()
            awaitState { it.status == SessionStatus.ERROR }
            stopContainer()
            restartContainer()
            val staleProbe = FinishedProcess("WAWLINK_OMP_CHECK\tBROKEN\told diagnostic\n")
            delayedProbe.complete(staleProbe)
            withTimeout(5_000) { staleProbe.destroyed.await() }
            awaitState { it.status == SessionStatus.STOPPED }
            assertTrue(pool.runtimeIssues.value.isEmpty())
            assertEquals(1, inspectionStarts.get())
        }
    }

    @Test fun containerStopBeforePromptAdmissionWithdrawsOnlyTheUnacceptedMessage() = runBlocking {
        fixture(withManagement = true) {
            managementTransport.respondManagement(managementCatalogue(), nativeDefault)
            awaitState { !it.modelLoading }
            gate.complete(transport)
            controller.sendPrompt("尚未接收")
            transport.request()
            assertFalse(controller.state.value.feed.filterIsInstance<UserMessageItem>().single().accepted)
            stopContainer()
            val stopped = awaitState { it.status == SessionStatus.STOPPED && it.feed.filterIsInstance<UserMessageItem>().isEmpty() }
            assertNull(stopped.error)
            controller.selectModel("manual", "retryable")
            awaitState { it.modelId == "retryable" }
            assertTrue(pool.runtimeIssues.value.isEmpty())
        }
    }

    @Test fun optionalSkillAndDebuggerFailuresRemainDiscoverableWithoutBlockingChatOrConfiguration() = runBlocking {
        for ((skillFailure, debugFailure) in listOf(true to false, false to true, true to true)) {
            fixture(withManagement = true) {
                managementTransport.respondManagement(managementCatalogue(), nativeDefault)
                awaitState { !it.modelLoading }
                if (skillFailure) skillInstallFailure = "Skill directory: EACCES"
                if (debugFailure) debugInstallFailure = "fchown: EPERM"
                val features = featureService()
                withTimeout(5_000) { featureDiscoveryStarted.await() }
                // Exercise discovery too: the debugger must not be reported as a Skill failure.
                val catalog = features.catalog("fixture")
                assertEquals(if (skillFailure && debugFailure) 2 else 1, catalog.errors.size)
                assertEquals(skillFailure, catalog.errors.any { it.startsWith("内置 Skill 注入失败") })
                assertEquals(debugFailure, catalog.errors.any { it.startsWith("功能调试器安装失败") })
                if (!skillFailure) assertTrue(File(directory, "home/.omp/agent/skills/wawlink-functions/SKILL.md").isFile)

                gate.complete(transport)
                controller.sendPrompt("辅助安装失败后仍可聊天")
                val request = transport.request()
                transport.acceptUser(request)
                transport.respond(request)
                transport.finish(request)
                val completed = awaitState { it.status == SessionStatus.DONE }
                assertEquals(listOf("辅助安装失败后仍可聊天"), completed.feed.filterIsInstance<UserMessageItem>().map { it.text })
                assertNull(completed.error)
                assertTrue(pool.runtimeIssues.value.isEmpty(), "Optional support must not request OMP repair")
                val warnings = features.errors.value.getValue("fixture")
                assertEquals(skillFailure, warnings.any { it.startsWith("内置 Skill 注入失败") })
                assertEquals(debugFailure, warnings.any { it.startsWith("功能调试器安装失败") })
                assertTrue(debugInstallStarts.get() > 0, "Debugger setup must still be attempted after a Skill failure")

                val management = pool.management("fixture", "/workspace")
                val reload = async { management.reload() }
                managementTransport.respondManagement(managementTransport.managementRequest("snapshot"), JsonObject(emptyMap()))
                managementTransport.respondManagement(managementTransport.managementRequest("modelsSnapshot"), nativeDefault)
                reload.await()
                assertFalse(management.state.value.loading)
                assertNull(management.state.value.error)
                assertNull(management.state.value.modelsError)
                assertEquals("resolved", management.state.value.models.single().id)
                assertTrue(pool.runtimeIssues.value.isEmpty())
            }
        }
    }

    @Test fun optionalSetupFailureDoesNotHideNativePermissionRejection() = runBlocking {
        fixture(withManagement = true) {
            managementTransport.respondManagement(managementCatalogue(), nativeDefault)
            awaitState { !it.modelLoading }
            skillInstallFailure = "Skill directory: EACCES"
            val features = featureService()
            withTimeout(5_000) { featureDiscoveryStarted.await() }
            controller.sendPrompt("原生权限错误不能被忽略")
            withTimeout(5_000) { opened.await() }
            gate.completeExceptionally(IOException("native OMP config: EACCES"))
            val failed = awaitState { it.status == SessionStatus.ERROR }
            assertContains(failed.error.orEmpty(), "native OMP config: EACCES")
            assertTrue(failed.feed.filterIsInstance<UserMessageItem>().isEmpty())
            assertTrue(features.errors.value.getValue("fixture").any { it.startsWith("内置 Skill 注入失败") })
            withTimeout(5_000) { inspectionFinished.await() }
            assertTrue(pool.runtimeIssues.value.isEmpty())
        }
    }

    @Test fun containerStopWinsOverAnOptionalInstallFailureBeforePromptAdmission() = runBlocking {
        fixture(withManagement = true) {
            managementTransport.respondManagement(managementCatalogue(), nativeDefault)
            awaitState { !it.modelLoading }
            val bootstrap = CompletableDeferred<Process>()
            skillInstallGate = bootstrap
            controller.sendPrompt("辅助安装失败前停止")
            withTimeout(5_000) { skillInstallStarted.await() }
            stopContainer()
            restartContainer()
            bootstrap.completeExceptionally(IOException("late Skill permission failure"))
            val stopped = awaitState { it.status == SessionStatus.STOPPED && it.feed.filterIsInstance<UserMessageItem>().isEmpty() }
            assertNull(stopped.error)
            assertFalse(opened.isCompleted, "A stopped prompt must never start the chat RPC")
            assertTrue(pool.runtimeIssues.value.isEmpty())
        }
    }

    @Test fun containerStopDuringFeatureSkillInstallDoesNotCancelThePromptActor() = runBlocking {
        fixture(withManagement = true) {
            managementTransport.respondManagement(managementCatalogue(), nativeDefault)
            awaitState { !it.modelLoading }
            val bootstrap = CompletableDeferred<Process>()
            skillInstallGate = bootstrap
            controller.sendPrompt("安装技能时停止")
            withTimeout(5_000) { skillInstallStarted.await() }
            stopContainer()
            restartContainer()
            bootstrap.completeExceptionally(CancellationException("container operation interrupted"))
            val stopped = awaitState { it.status == SessionStatus.STOPPED && it.feed.filterIsInstance<UserMessageItem>().isEmpty() }
            assertNull(stopped.error)
            assertTrue(pool.runtimeIssues.value.isEmpty())
            assertFalse(opened.isCompleted)

            skillInstallGate = null
            gate.complete(transport)
            controller.sendPrompt("技能启动重试")
            val request = transport.request()
            transport.acceptUser(request)
            transport.respond(request)
            transport.finish(request)
            val retried = awaitState { it.status == SessionStatus.DONE }
            assertEquals(listOf("技能启动重试"), retried.feed.filterIsInstance<UserMessageItem>().map { it.text })
            assertNull(retried.error)
            assertTrue(pool.runtimeIssues.value.isEmpty())
        }
    }

    @Test fun explicitSessionOpeningStillPreviewsNativeDefaultFromAStoppedContainer() = runBlocking {
        fixture(withManagement = true, initiallyRunning = false) {
            managementTransport.respondManagement(managementCatalogue(), nativeDefault)
            val preview = awaitState { !it.modelLoading }
            assertEquals("local", preview.providerId)
            assertEquals("resolved", preview.modelId)
            assertEquals(ThinkingLevel.MAX, preview.thinkingLevel)
            assertFalse(opened.isCompleted, "Model preview must not start the chat RPC")
        }
    }

    @Test fun chrootMessageStartupCreatesSharedSessionAndExtensionsWithoutLosingOfflineAccess() = runBlocking {
        fixture(withManagement = true, engine = ContainerEngineType.CHROOT, user = "operator") {
            managementTransport.respondManagement(managementCatalogue(), nativeDefault)
            awaitState { !it.modelLoading }
            gate.complete(transport)
            controller.sendPrompt("真实启动链")
            transport.request()
            assertEquals("1", chatEnvironment["WAWLINK_MANAGEMENT_SHARED_GROUP"])
            val sessionPath = chatArguments[chatArguments.indexOf("--session-dir") + 1]
            val session = File(root, sessionPath.trimStart('/'))
            for (directory in listOf(session, File(session, "management"))) {
                assertEquals("2770".toInt(8), (Files.getAttribute(directory.toPath(), "unix:mode") as Number).toInt() and 0xfff)
            }
            for (extension in chatArguments.zipWithNext().filter { it.first == "--extension" }.map { File(root, it.second.trimStart('/')) }) {
                assertTrue(extension.canRead())
                assertTrue(extension.canWrite())
                assertEquals("660".toInt(8), (Files.getAttribute(extension.toPath(), "unix:mode") as Number).toInt() and 0x1ff)
            }
            val before = session.listFiles().orEmpty().map { it.name }.toSet()
            val request = async { runCatching { pool.explorer("session").refreshHistory() } }
            val managed = File(session, "management")
            val receipt = withTimeout(5_000) {
                var candidate: File?
                do { candidate = managed.listFiles()?.firstOrNull { it.name.startsWith("request-") }; if (candidate == null) delay(10) } while (candidate == null)
                requireNotNull(candidate)
            }
            assertEquals("660".toInt(8), (Files.getAttribute(receipt.toPath(), "unix:mode") as Number).toInt() and 0x1ff)
            request.cancelAndJoin()
            assertEquals(before, session.listFiles().orEmpty().map { it.name }.toSet())
        }
    }

    @Test fun startupAndResumeUseCurrentUsersEffectiveHomeForEveryExtensionAndSessionPath() = runBlocking {
        for ((user, homeOverride, home) in listOf(
            Triple("root", null, "/root"),
            Triple("operator", null, "/home/operator"),
            Triple("1000", null, "/home/operator"),
            Triple("1000", "/srv/team/custom-home", "/srv/team/custom-home"),
        )) {
            fixture(withManagement = true, user = user, homeOverride = homeOverride) {
                val guestAgent = "$home/.omp/agent"
                assertEquals(guestAgent, pool.guestAgentDirectory("fixture"))
                managementTransport.respondManagement(managementCatalogue(), nativeDefault)
                awaitState { !it.modelLoading }
                assertEquals(listOf("/usr/local/bin/bun", "$guestAgent/wawlink/management/bootstrap.mjs"), managementLaunches.single())
                val sessionId = java.security.MessageDigest.getInstance("SHA-256").digest("session".toByteArray()).joinToString("") { "%02x".format(it) }
                val guestSessions = "$guestAgent/wawlink/sessions/$sessionId"
                val history = File(root, "${guestSessions.trimStart('/')}/existing.jsonl").apply {
                    parentFile.mkdirs()
                    writeText("{\"type\":\"session\",\"version\":3,\"id\":\"restored\",\"cwd\":\"/workspace\"}\n")
                }
                val original = history.readBytes()
                gate.complete(transport)
                controller.sendPrompt("恢复当前用户会话")
                transport.request()
                assertEquals(guestSessions, chatArguments[chatArguments.indexOf("--session-dir") + 1])
                assertEquals("$guestSessions/existing.jsonl", chatArguments[chatArguments.indexOf("--resume") + 1])
                val extensions = chatArguments.zipWithNext().filter { it.first == "--extension" }.map { it.second }
                assertTrue("$guestAgent/wawlink-model-payload.mjs" in extensions)
                assertTrue("$guestAgent/wawlink/management/session.mjs" in extensions)
                assertEquals("$guestSessions/management", chatEnvironment["WAWLINK_MANAGEMENT_DIRECTORY"])
                assertEquals("0", chatEnvironment["WAWLINK_MANAGEMENT_SHARED_GROUP"])
                assertTrue(File(root, "${guestAgent.trimStart('/')}/wawlink-model-payload.mjs").isFile)
                assertContentEquals(original, history.readBytes())
            }
        }
    }

    @Test fun attachmentPathsStayOnTheWireWhileRawUserWhitespaceSurvivesAcceptanceAndReplay() = runBlocking {
        fixture {
            val selected = File(directory, "selected.txt").apply { writeText("真实附件内容") }
            val attachment = AttachmentFile("local-file", selected.name, selected.absolutePath)
            val raw = "  原始问题\n第二行\n\n "
            controller.sendPrompt(raw, listOf(attachment))
            val optimistic = controller.state.value.feed.filterIsInstance<UserMessageItem>().single()
            assertEquals(raw, optimistic.text)
            assertEquals(listOf(attachment), optimistic.attachments)
            gate.complete(transport)
            val request = coroutineScope {
                val failure = async { controller.state.first { it.status == SessionStatus.ERROR } }
                val pending = async { transport.request() }
                try {
                    kotlinx.coroutines.selects.select {
                        failure.onAwait { error("附件发送未进入 RPC：${it.error}") }
                        pending.onAwait { it }
                    }
                } finally { failure.cancel(); pending.cancel() }
            }
            val pending = awaitState { it.feed.filterIsInstance<UserMessageItem>().singleOrNull()?.attachments?.singleOrNull()?.source == AttachmentSource.WORKSPACE }
                .feed.filterIsInstance<UserMessageItem>().single()
            assertEquals(raw, pending.text)
            assertEquals(optimistic.id, pending.id)
            assertEquals(attachment.id, pending.attachments.single().id)
            assertTrue(pending.attachments.single().path.startsWith("/workspace/.wawlink/attachments/"))
            assertEquals(attachmentWireMessage(raw, pending.attachments), request.string("message"))
            transport.acceptUser(request)
            transport.respond(request)
            transport.finish(request)
            val accepted = awaitState { it.status == SessionStatus.DONE }.feed.filterIsInstance<UserMessageItem>().single()
            assertEquals(pending.copy(accepted = true), accepted)
            pool.close()
            pool = OmpProcessPool(runtime, data)
            val restored = pool.session("session", "fixture", "/workspace")
            val replayed = withTimeout(5_000) { restored.state.first { it.feed.filterIsInstance<UserMessageItem>().isNotEmpty() } }
            assertEquals(listOf(accepted), replayed.feed.filterIsInstance<UserMessageItem>())
        }
    }

    @Test fun queuedAttachmentCanBeEditedToBlankAndTakenOnlyAfterExactWireRemovalSucceeds() = runBlocking {
        fixture {
            gate.complete(transport)
            controller.sendPrompt("开始")
            val first = transport.request()
            transport.acceptUser(first)
            transport.respond(first)
            val file = File(directory, "queued.txt").apply { writeText("排队附件") }
            controller.enqueuePrompt("原始追加\n ", listOf(AttachmentFile("queued-file", file.name, file.absolutePath)))
            val queuedRequest = transport.request()
            val originalWire = queuedRequest.string("message")
            transport.emit(buildJsonObject {
                put("type", "queue_update"); put("steering", JsonArray(emptyList())); put("followUp", buildJsonArray { add(originalWire) })
            })
            transport.respond(queuedRequest)
            val queued = awaitState { it.queuedPrompts.singleOrNull()?.text == "原始追加\n " }.queuedPrompts.single()
            transport.interceptQueueControls = true
            val blank = " \n\t"
            controller.updateQueuedPrompt(queued.id, blank)
            val remove = transport.queueControl()
            assertEquals("remove_queued_message", remove.string("type"))
            assertEquals(originalWire, remove.string("message"))
            assertEquals("followUp", remove.string("queue"))
            transport.emit("""{"type":"queue_update","steering":[],"followUp":[]}""")
            transport.respond(remove, data = buildJsonObject { put("removed", true) })
            val edited = transport.request()
            assertEquals("follow_up", edited.string("type"))
            assertEquals(attachmentWireMessage(blank, queued.attachments), edited.string("message"))
            transport.emit(buildJsonObject {
                put("type", "queue_update"); put("steering", JsonArray(emptyList())); put("followUp", buildJsonArray { add(edited.string("message")) })
            })
            transport.respond(edited)
            val updated = awaitState { it.queuedPrompts.singleOrNull()?.text == blank }.queuedPrompts.single()
            assertEquals(queued.copy(text = blank), updated)
            assertTrue(File(root, queued.attachments.single().path.trimStart('/')).delete())
            controller.updateQueuedPrompt(queued.id, "失败时不能覆盖原文")
            val unreadable = awaitState { it.error?.contains("无法读取附件") == true }
            assertEquals(listOf(updated), unreadable.queuedPrompts)

            val rejected = async { runCatching { controller.takeQueuedPrompt(queued.id) } }
            val rejectedRemove = transport.queueControl()
            assertEquals(edited.string("message"), rejectedRemove.string("message"))
            transport.respond(rejectedRemove, data = buildJsonObject { put("removed", false) })
            assertTrue(rejected.await().isFailure)
            assertEquals(listOf(updated), controller.state.value.queuedPrompts)
            val taken = async { controller.takeQueuedPrompt(queued.id) }
            val acceptedRemove = transport.queueControl()
            assertEquals(edited.string("message"), acceptedRemove.string("message"))
            transport.respond(acceptedRemove, data = buildJsonObject { put("removed", true) })
            assertEquals(RestoredSessionPrompt(blank, queued.attachments), taken.await())
            assertTrue(controller.state.value.queuedPrompts.isEmpty())
        }
    }

    @Test fun stopRestoresRawAttachmentOnlySteeringAndOfflineEditsDoNotReadFiles() = runBlocking {
        fixture {
            gate.complete(transport)
            controller.sendPrompt("开始")
            val first = transport.request()
            transport.acceptUser(first)
            transport.respond(first)
            val selected = File(directory, "steering.txt").apply { writeText("停止后保留附件") }
            controller.sendPrompt("", listOf(AttachmentFile("steering-file", selected.name, selected.absolutePath)))
            val queuedRequest = transport.request()
            assertEquals("steer", queuedRequest.string("type"))
            val wire = queuedRequest.string("message")
            transport.emit(buildJsonObject {
                put("type", "queue_update"); put("steering", buildJsonArray { add(wire) }); put("followUp", JsonArray(emptyList()))
            })
            transport.respond(queuedRequest)
            val queued = awaitState { it.queuedPrompts.singleOrNull()?.text == "" }.queuedPrompts.single()
            transport.interceptQueueControls = true
            controller.stopExecution()
            val abort = transport.queueControl()
            assertEquals("abort_and_restore_queue", abort.string("type"))
            transport.emit("""{"type":"queue_update","steering":[],"followUp":[]}""")
            transport.respond(abort, data = buildJsonObject {
                put("steering", buildJsonArray { add(buildJsonObject { put("text", wire) }) }); put("followUp", JsonArray(emptyList()))
            })
            val stopped = awaitState { it.status == SessionStatus.STOPPED }
            assertEquals(listOf(queued), stopped.queuedPrompts)
            val guest = queued.attachments.single().path
            assertTrue(File(root, guest.trimStart('/')).delete())
            controller.updateQueuedPrompt(queued.id, "\n ")
            val edited = awaitState { it.queuedPrompts.singleOrNull()?.text == "\n " }
            assertEquals(listOf(queued.copy(text = "\n ")), edited.queuedPrompts)
            assertNull(edited.error)
            pool.close()
            pool = OmpProcessPool(runtime, data)
            val restored = pool.session("session", "fixture", "/workspace")
            val replayed = withTimeout(5_000) { restored.state.first { it.queuedPrompts.singleOrNull()?.text == "\n " } }
            assertEquals(edited.queuedPrompts, replayed.queuedPrompts)
            assertEquals(RestoredSessionPrompt("\n ", queued.attachments), restored.takeQueuedPrompt(queued.id))
        }
    }

    private val nativeDefault = Json.parseToJsonElement("""{"models":[{"provider":"local","id":"resolved","available":true}],"roles":[{"id":"default","model":"local/resolved","providerId":"local","modelId":"resolved","thinking":"max"}]}""")

    private suspend fun fixture(autoApprove: Boolean = true, withManagement: Boolean = false, initiallyRunning: Boolean = true, user: String = "root", homeOverride: String? = null, engine: ContainerEngineType = ContainerEngineType.PROROOT, block: suspend Fixture.() -> Unit) {
        val fixture = Fixture(autoApprove, withManagement, initiallyRunning, user, homeOverride, engine)
        try { block(fixture) } finally { fixture.close() }
    }

    private class Fixture(autoApprove: Boolean, withManagement: Boolean, initiallyRunning: Boolean, user: String, homeOverride: String?, engine: ContainerEngineType) : AutoCloseable {
        val directory = Files.createTempDirectory("wawlink-delivery-").toFile()
        val data = File(directory, "gui")
        val root = File(directory, "root").apply { mkdirs() }
        val managementLaunches = CopyOnWriteArrayList<List<String>>()
        @Volatile var chatArguments: List<String> = emptyList()
        @Volatile var chatEnvironment: Map<String, String> = emptyMap()
        val transport = RpcTransport()
        val managementTransport = RpcTransport(management = true)
        val gate = CompletableDeferred<Process>()
        val opened = CompletableDeferred<Unit>()
        val inspectionFinished = CompletableDeferred<Unit>()
        @Volatile var installationState = "READY"
        @Volatile var inspectionGate: CompletableDeferred<Process>? = null
        val inspectionStarts = AtomicInteger()
        @Volatile var skillInstallGate: CompletableDeferred<Process>? = null
        val skillInstallStarted = CompletableDeferred<Unit>()
        @Volatile var skillInstallFailure: String? = null
        @Volatile var debugInstallFailure: String? = null
        val debugInstallStarts = AtomicInteger()
        val featureDiscoveryStarted = CompletableDeferred<Unit>()
        private var features: ContainerFeatureService? = null
        @Volatile var chatManagementInstance = ""
        private val containerState = MutableStateFlow<List<ContainerInfo>>(emptyList())
        private val chatStarts = AtomicInteger()
        private val managementStarts = AtomicInteger()
        private val chatReplacements = Channel<RpcTransport>(Channel.UNLIMITED)
        private val managementReplacements = Channel<RpcTransport>(Channel.UNLIMITED)
        private val replacements = CopyOnWriteArrayList<RpcTransport>()
        private val local = LocalContainerRuntime(RuntimePaths(File(directory, "runtime")))
        val runtime = object : ContainerRuntime by local {
            override val containers = containerState
            override suspend fun openRunningProcess(id: String, arguments: List<String>, cwd: String, environment: Map<String, String>): Process {
                check(containers.value.any { it.config.id == id && it.running }) { "容器已停止" }
                return openProcess(id, arguments, cwd, environment)
            }
            override suspend fun openProcess(id: String, arguments: List<String>, cwd: String, environment: Map<String, String>): Process {
                if (arguments.lastOrNull() == "--inspect") {
                    inspectionStarts.incrementAndGet()
                    inspectionFinished.complete(Unit)
                    return inspectionGate?.await() ?: FinishedProcess("WAWLINK_OMP_CHECK\t$installationState\tinstallation check\n")
                }
                // Bootstrap is a real short-lived shell process, not the chat transport.
                if (arguments.firstOrNull() == "/bin/sh" && arguments.getOrNull(1) == "-c") {
                    val debugger = arguments.last().startsWith("exec /usr/local/bin/bun -e ")
                    if (debugger) {
                        debugInstallStarts.incrementAndGet()
                        debugInstallFailure?.let { return FinishedProcess("", status = 1, error = it) }
                    } else if (arguments.last().contains("wawlink-functions")) {
                        skillInstallFailure?.let { return FinishedProcess("", status = 1, error = it) }
                    }
                    if (!debugger && arguments.last().contains(".wawlink/features")) featureDiscoveryStarted.complete(Unit)
                    skillInstallGate?.let { blocked ->
                        skillInstallStarted.complete(Unit)
                        return blocked.await()
                    }
                    val home = File(directory, "home").apply { mkdirs() }
                    val command = if (debugger) arguments.dropLast(1) + arguments.last().replaceFirst("/usr/local/bin/bun", "node") else arguments
                    return ProcessBuilder(command).directory(directory).apply {
                        environment().clear()
                        environment().putAll(environment)
                        environment()["PATH"] = System.getenv("PATH")
                        environment()["HOME"] = home.absolutePath
                    }.start()
                }
                if (arguments.firstOrNull() == "/usr/local/bin/bun") {
                    check(withManagement) { "未配置默认模型" }
                    managementLaunches += arguments
                    return if (managementStarts.getAndIncrement() == 0) managementTransport else managementReplacements.receive()
                }
                chatManagementInstance = environment.getValue("WAWLINK_MANAGEMENT_INSTANCE")
                chatArguments = arguments.toList()
                chatEnvironment = environment.toMap()
                opened.complete(Unit)
                return if (chatStarts.getAndIncrement() == 0) gate.await() else chatReplacements.receive()
            }
        }
        var pool: OmpProcessPool
        val controller: AgentSessionController
        init {
            File(root, "etc").mkdirs()
            File(root, "etc/passwd").writeText("root:x:0:0:Root:/root:/bin/sh\noperator:x:1000:1000:Operator:/home/operator:/bin/sh\n")
            val shell = File(root, "bin/sh").apply { parentFile.mkdirs() }
            Files.copy(File("/bin/sh").canonicalFile.toPath(), shell.toPath())
            check(shell.setExecutable(true))
            runBlocking { local.register(ContainerConfig("fixture", "发送验证", root.canonicalPath, arch = "amd64", user = user, environment = homeOverride?.let { mapOf("HOME" to it) }.orEmpty(), link2symlink = false)) }
            containerState.value = local.containers.value.map { it.copy(config = it.config.copy(engine = engine), running = initiallyRunning) }
            pool = OmpProcessPool(runtime, data)
            controller = pool.session("session", "fixture", "/workspace", autoApprove = autoApprove)
        }
        fun featureService(): ContainerFeatureService = features ?: ContainerFeatureService(runtime).also { features = it }
        fun stopContainer() {
            containerState.value = containerState.value.map { it.copy(running = false, stopGeneration = it.stopGeneration + 1) }
        }
        fun restartContainer() {
            containerState.value = containerState.value.map { it.copy(running = true) }
        }
        suspend fun managementCatalogue(): JsonObject {
            val init = managementTransport.managementRequest("init")
            assertEquals(pool.guestAgentDirectory("fixture"), init["args"]!!.jsonObject["agentDir"]!!.jsonPrimitive.content)
            managementTransport.respondManagement(init, JsonPrimitive(true))
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
            features?.close()
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
        private val queueControls = Channel<JsonObject>(Channel.UNLIMITED)
        @Volatile var interceptQueueControls = false
        val outputOpened = CompletableDeferred<Unit>()
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
                    "remove_queued_message", "abort_and_restore_queue" -> if (interceptQueueControls) {
                        check(queueControls.trySend(request).isSuccess)
                    } else respond(request)
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
        suspend fun queueControl(): JsonObject = withTimeout(5_000) { queueControls.receive() }
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
        override fun getInputStream(): InputStream = stdout.also { outputOpened.complete(Unit) }
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
    private class FinishedProcess(output: String, private val status: Int = 0, error: String = "") : Process() {
        val destroyed = CompletableDeferred<Unit>()
        private val input = ByteArrayInputStream(output.toByteArray())
        private val stderr = ByteArrayInputStream(error.toByteArray())
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = stderr
        override fun waitFor(): Int = status
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun exitValue(): Int = status
        override fun isAlive(): Boolean = false
        override fun destroy() { destroyed.complete(Unit) }
    }
}
