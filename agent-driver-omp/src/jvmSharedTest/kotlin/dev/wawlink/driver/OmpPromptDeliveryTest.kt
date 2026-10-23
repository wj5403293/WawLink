package dev.wawlink.driver

import dev.wawlink.protocol.*
import dev.wawlink.runtime.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import kotlin.test.*

class OmpPromptDeliveryTest {
    @Test fun firstMessageIsVisibleBeforeStartupAndKeepsItsIdentityAfterAcceptance() = runBlocking {
        fixture {
            controller.sendPrompt("立即显示")
            val optimistic = controller.state.value.feed.filterIsInstance<UserMessageItem>().single()
            assertEquals("立即显示", optimistic.text)
            assertEquals(SessionStatus.RUNNING, controller.state.value.status)
            opened.await()
            gate.complete(transport)
            val request = transport.request()
            transport.acceptUser(request)
            transport.respond(request)
            transport.finish(request)
            val finished = awaitState { it.status == SessionStatus.DONE }
            assertEquals(listOf(optimistic), finished.feed.filterIsInstance<UserMessageItem>())
        }
    }

    @Test fun startupFailureWithdrawsTheMessageAndPersistsTheReasonWithoutPhantomHistory() = runBlocking {
        fixture {
            controller.sendPrompt("无法启动的消息")
            val id = controller.state.value.feed.single().id
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
        }
    }

    @Test fun acknowledgedButNotAdmittedPromptIsWithdrawnOnItsLateFailureResult() = runBlocking {
        fixture {
            gate.complete(transport)
            controller.sendPrompt("延迟拒绝")
            val id = controller.state.value.feed.single().id
            val request = transport.request()
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
            assertEquals(listOf(optimistic), failed.feed.filterIsInstance<UserMessageItem>())
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
            assertNull(waiting.feed.filterIsInstance<AskCardItem>().single().selectedAnswer)
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

    private suspend fun fixture(autoApprove: Boolean = true, block: suspend Fixture.() -> Unit) {
        val fixture = Fixture(autoApprove)
        try { block(fixture) } finally { fixture.close() }
    }

    private class Fixture(autoApprove: Boolean) : AutoCloseable {
        val directory = Files.createTempDirectory("wawlink-delivery-").toFile()
        val data = File(directory, "gui")
        val transport = RpcTransport()
        val gate = CompletableDeferred<Process>()
        val opened = CompletableDeferred<Unit>()
        private val local = LocalContainerRuntime(RuntimePaths(File(directory, "runtime")))
        val runtime = object : ContainerRuntime by local {
            override suspend fun openProcess(id: String, arguments: List<String>, cwd: String, environment: Map<String, String>): Process {
                opened.complete(Unit)
                return gate.await()
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
        suspend fun awaitState(predicate: (ChatSessionUiState) -> Boolean): ChatSessionUiState = withTimeout(5_000) { controller.state.first(predicate) }
        override fun close() {
            gate.completeExceptionally(IllegalStateException("验证结束"))
            transport.destroy()
            pool.close()
            local.close()
            directory.deleteRecursively()
        }
    }

    /** Controllable RPC transport: admission and terminal events are independent, as in native RPC. */
    private class RpcTransport : Process() {
        private val stdout = PipedInputStream(65_536)
        private val eventOutput = PipedOutputStream(stdout)
        private val stderr = PipedInputStream(4096)
        private val errorOutput = PipedOutputStream(stderr)
        private val exited = CountDownLatch(1)
        private val requests = Channel<JsonObject>(Channel.UNLIMITED)
        private val uiResponses = Channel<JsonObject>(Channel.UNLIMITED)
        private var running = false
        private val stdin = object : OutputStream() {
            private val buffer = ByteArrayOutputStream()
            override fun write(value: Int) {
                if (value != '\n'.code) { buffer.write(value); return }
                val request = Json.parseToJsonElement(buffer.toString(Charsets.UTF_8)).jsonObject
                buffer.reset()
                when (request.string("type")) {
                    "prompt", "follow_up" -> check(requests.trySend(request).isSuccess)
                    "extension_ui_response" -> check(uiResponses.trySend(request).isSuccess)
                    "get_state" -> respond(request, data = buildJsonObject {
                        put("isStreaming", running); put("isSettled", !running)
                    })
                    else -> respond(request)
                }
            }
            override fun close() = destroy()
        }
        init { emit("""{"type":"ready"}""") }
        suspend fun request(): JsonObject = withTimeout(5_000) { requests.receive() }
        suspend fun uiResponse(): JsonObject = withTimeout(5_000) { uiResponses.receive() }
        fun hasUiResponse(): Boolean = uiResponses.tryReceive().isSuccess
        fun emit(json: String) = emit(Json.parseToJsonElement(json).jsonObject)
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
}
