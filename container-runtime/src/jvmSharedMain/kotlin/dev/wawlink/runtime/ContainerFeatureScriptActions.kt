package dev.wawlink.runtime

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

/** Independent finite actions, not service state. The caller owns presentation and cancellation. */
internal class ContainerFeatureScriptActions(private val runtime: ContainerRuntime) : AutoCloseable {
    private data class Invocation(val containerId: String, val feature: ContainerFeature, val job: Job)
    private val invocations = ConcurrentHashMap<String, Invocation>()
    private val gate = Any()
    private var closed = false

    suspend fun run(containerId: String, feature: ContainerFeature, action: ContainerFeatureAction, validate: suspend () -> Unit): String =
        withContext(Dispatchers.IO) {
            coroutineScope {
                require(action.type == ContainerFeatureActionType.SCRIPT && action in feature.actions) { "只能执行清单声明的 script 动作" }
                val script = requireNotNull(action.script) { "script 动作缺少脚本路径" }
                validateFeatureRelativeScript(script)
                val key = "$containerId/${feature.id}/${action.id}"
                val invocation = Invocation(containerId, feature, currentCoroutineContext().job)
                synchronized(gate) {
                    check(!closed) { "功能服务已关闭" }
                    check(invocations.putIfAbsent(key, invocation) == null) { "动作正在执行，请等待当前执行完成" }
                }
                var process: Process? = null
                try {
                    val container = runtime.containers.value.firstOrNull { it.config.id == containerId && it.running }
                        ?: error("容器未运行")
                    validate()
                    ensureActive()
                    check(runtime.containers.value.any { it.running && it.config.id == containerId && it.runGeneration == container.runGeneration }) { "容器已停止或运行身份已变化" }
                    val child = withContext(NonCancellable) {
                        invocation.job.ensureActive()
                        check(runtime.containers.value.any { it.running && it.config.id == containerId && it.runGeneration == container.runGeneration }) { "容器已停止或运行身份已变化" }
                        runtime.openRunningProcess(containerId, listOf("/bin/sh", "-c", featureScriptActionSupervisor(feature, script)), "/", mapOf(
                            "WAWLINK_FEATURE_ID" to feature.id,
                            "WAWLINK_FEATURE_DIR" to feature.directory,
                            "WAWLINK_ACTION_ID" to action.id,
                        )).also { process = it }
                    }
                    ensureActive()
                    // Keep the stdin control pipe open until completion or cancellation.
                    val stdout = async { child.inputStream.use { featureReadBounded(it, 4 * 1024 * 1024).toString(Charsets.UTF_8) } }
                    val stderr = async { child.errorStream.use { featureReadBounded(it, 4 * 1024 * 1024).toString(Charsets.UTF_8) } }
                    val exit = runInterruptible { child.waitFor() }
                    val output = stdout.await()
                    val errors = stderr.await()
                    check(exit == 0) { "动作执行失败（退出码 $exit）：\n$output$errors" }
                    output + errors
                } finally {
                    withContext(NonCancellable) {
                        process?.let(::finishFeatureActionProcess)
                        invocations.remove(key, invocation)
                    }
                }
            }
        }

    suspend fun cancelFeature(containerId: String, featureId: String, pageOnly: Boolean = false) {
        val selected = invocations.values.filter {
            it.containerId == containerId && it.feature.id == featureId && (!pageOnly || it.feature.lifecycle == ContainerFeatureLifecycle.PAGE)
        }
        selected.forEach { it.job.cancel(CancellationException("功能动作已停止")) }
        selected.map { it.job }.joinAll()
    }

    suspend fun cancelStoppedContainers(running: Set<String>) {
        val selected = invocations.values.filter { it.containerId !in running }
        selected.forEach { it.job.cancel(CancellationException("容器已停止")) }
        selected.map { it.job }.joinAll()
    }

    override fun close() {
        val jobs = synchronized(gate) {
            if (closed) return
            closed = true
            invocations.values.map { it.job }
        }
        jobs.forEach { it.cancel(CancellationException("功能服务已关闭")) }
        runBlocking(Dispatchers.IO) { jobs.joinAll() }
    }
}

/** The guest supervisor owns a session/process group and tears it down even after the script exits. */
internal fun featureScriptActionSupervisor(feature: ContainerFeature, script: String): String {
    require(feature.id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))) { "功能 ID 无效" }
    validateFeatureRelativeScript(script)
    val groupScript = """
        set -eu
        case "${'$'}HOME" in /*) ;; *) echo '容器 HOME 无效' >&2; exit 1;; esac
        home=${'$'}(readlink -f -- "${'$'}HOME")
        expected="${'$'}{home%/}/.wawlink/features/"${shellQuote(feature.id)}
        dir=${shellQuote(feature.directory)}
        [ "${'$'}dir" = "${'$'}expected" ] || { echo '功能目录不属于当前 HOME 的原始目录' >&2; exit 1; }
        [ "${'$'}(readlink -f -- "${'$'}dir")" = "${'$'}dir" ] || { echo '功能目录已变化或包含链接' >&2; exit 1; }
        cd -- "${'$'}dir"
        file=${'$'}(readlink -f -- ${shellQuote(script)})
        case "${'$'}file" in "${'$'}dir/"*) ;; *) echo '动作脚本越出功能目录' >&2; exit 1;; esac
        [ -f "${'$'}file" ] || { echo '动作脚本必须为普通文件' >&2; exit 1; }
        exec /bin/sh "${'$'}file"
    """.trimIndent()
    return """
        set -eu
        command -v setsid >/dev/null || { echo '缺少 setsid，请安装 util-linux' >&2; exit 127; }
        pid=''
        watcher=''
        supervisor=${'$'}${'$'}
        exec 3<&0
        cleanup() {
            trap '' TERM INT HUP
            if [ -n "${'$'}watcher" ]; then kill "${'$'}watcher" 2>/dev/null || true; fi
            if [ -n "${'$'}pid" ]; then
                kill -TERM -"${'$'}pid" 2>/dev/null || true
                sleep 1
                kill -KILL -"${'$'}pid" 2>/dev/null || true
                wait "${'$'}pid" 2>/dev/null || true
            fi
        }
        trap cleanup 0
        trap 'exit 143' TERM HUP
        trap 'exit 130' INT
        (IFS= read -r request <&3 || :; kill -TERM "${'$'}supervisor" 2>/dev/null || true) &
        watcher=${'$'}!
        setsid /bin/sh -c ${shellQuote(groupScript)} wawlink-feature-action 3<&- < /dev/null &
        pid=${'$'}!
        set +e
        wait "${'$'}pid"
        code=${'$'}?
        exit "${'$'}code"
    """.trimIndent()
}

private fun finishFeatureActionProcess(process: Process) {
    runCatching { process.outputStream.apply { write("stop\n".toByteArray()); flush(); close() } }
    if (!process.waitFor(2500, TimeUnit.MILLISECONDS)) {
        process.destroy()
        if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
    }
}
