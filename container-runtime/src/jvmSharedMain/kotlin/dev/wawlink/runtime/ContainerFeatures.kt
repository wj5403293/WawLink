package dev.wawlink.runtime

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

enum class ContainerFeatureLifecycle { PAGE, MANUAL, CONTAINER }
enum class ContainerFeatureActionType { OPEN, START, STOP, TOGGLE, LOGS, SCRIPT, LUA }
data class ContainerFeatureAction(
    val id: String,
    val title: String,
    val icon: String,
    val type: ContainerFeatureActionType,
    val script: String? = null,
    val lua: String? = null,
)
data class ContainerFeature(
    val id: String,
    val title: String,
    val description: String,
    val icon: String,
    val directory: String,
    val lifecycle: ContainerFeatureLifecycle = ContainerFeatureLifecycle.PAGE,
    val color: String? = null,
    val cardHtml: String? = null,
    val cardHeight: Int = 172,
    val showStatus: Boolean = false,
    val showMemory: Boolean = false,
    val cardSpan: Int = 1,
    val actions: List<ContainerFeatureAction> = emptyList(),
    val cardUrl: String? = null,
)
data class ContainerFeatureCatalog(val home: String, val features: List<ContainerFeature>, val errors: List<String>)
data class ContainerFeatureSession(val containerId: String, val featureId: String, val url: String, val token: String)
enum class ContainerFeatureStatus { STOPPED, STARTING, RUNNING, STOPPING, FAILED }
data class ContainerFeatureState(val status: ContainerFeatureStatus = ContainerFeatureStatus.STOPPED, val session: ContainerFeatureSession? = null, val output: String = "", val error: String? = null)

/** Guest-owned cards. Discovery only reads data; processes always use the configured runtime identity. */
class ContainerFeatureService(private val runtime: ContainerRuntime) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Any()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val runs = mutableMapOf<String, ContainerRun>()
    private val allRuns = mutableSetOf<ContainerRun>()
    private val instances = ConcurrentHashMap<String, Instance>()
    private val scriptActions = ContainerFeatureScriptActions(runtime)
    private val _states = MutableStateFlow<Map<String, ContainerFeatureState>>(emptyMap())
    val states: StateFlow<Map<String, ContainerFeatureState>> = _states.asStateFlow()
    private val _errors = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val errors: StateFlow<Map<String, List<String>>> = _errors.asStateFlow()
    val debugger = ContainerFeatureDebugger(runtime, { discover(it, runningOnly = true) }) { containerId, featureId ->
        states.value[stateKey(containerId, featureId)] ?: ContainerFeatureState()
    }
    @Volatile private var closed = false
    @Volatile private var themeCss = DEFAULT_FEATURE_CSS
    @Volatile private var iconFont: ByteArray? = null
    fun setIconFont(bytes: ByteArray) { iconFont = bytes }

    private class ContainerRun(val containerId: String, val generation: Long, val stopGeneration: Long, parent: Job) {
        val job = SupervisorJob(parent)
        val scope = CoroutineScope(job + Dispatchers.IO + FeatureRunningOnly + FeatureOperation(containerId, stopGeneration, runningOnly = true))
        val stoppedFeatures = mutableSetOf<String>()
    }

    private class Instance(val key: String, val feature: ContainerFeature, val run: ContainerRun, val startJob: Job) {
        @Volatile var process: Process? = null
        @Volatile var gateway: ContainerFeatureGateway? = null
        @Volatile var identity: FeatureProcessIdentity? = null
        @Volatile var stopping = false
        @Volatile var stopRequested = false
        var port = 0
        var token = ""
        val reaping = AtomicBoolean()
        val reaped = CompletableDeferred<Unit>()
    }

    init {
        FeatureDebugServices.attach(runtime, debugger)
        scope.launch {
            runtime.containers.collect { containers ->
                debugger.containersChanged()
                val running = containers.filter { it.running }.associateBy { it.config.id }
                synchronized(gate) {
                    runs.filter { (id, run) -> running[id]?.runGeneration != run.generation || running[id]?.stopGeneration != run.stopGeneration }.keys.forEach { id ->
                        runs.remove(id)?.let { run ->
                            if (unexpectedStop(run)) _errors.update { it + (id to (it[id].orEmpty() + "容器运行进程已退出").distinct()) }
                            else _errors.update { it - id }
                            run.job.cancel(CancellationException("容器已停止或重新启动"))
                        }
                    }
                    if (!closed) running.values.forEach { attachLocked(it) }
                }
                scriptActions.cancelStoppedContainers()
            }
        }
    }

    /** Metadata updates do not create a new epoch, rediscover, or retry a stopped/failed feature. */
    private fun attachLocked(container: ContainerInfo): ContainerRun {
        val containerId = container.config.id
        runs[containerId]?.let { previous ->
            if (previous.generation == container.runGeneration && previous.stopGeneration == container.stopGeneration) return previous
            runs.remove(containerId)
            previous.job.cancel(CancellationException("容器已重新启动"))
        }
        return ContainerRun(containerId, container.runGeneration, container.stopGeneration, scope.coroutineContext.job).also { run ->
            runs[containerId] = run
            allRuns += run
            run.job.invokeOnCompletion { synchronized(gate) { allRuns.remove(run) } }
            run.scope.launch {
                try {
                    val catalog = discover(containerId, run)
                    catalog.features.filter { it.lifecycle == ContainerFeatureLifecycle.CONTAINER }.forEach { feature ->
                        launch {
                            try { startInRun(run, feature) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { /* startInRun publishes the genuine failure. */ }
                        }
                    }
                } catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                    publishErrors(containerId, listOf("功能扫描失败：${failure.message}"), run)
                }
            }
        }
    }

    private fun current(run: ContainerRun): Boolean = !closed && runs[run.containerId] === run && run.job.isActive &&
        runtime.containers.value.any { it.config.id == run.containerId && it.running && it.runGeneration == run.generation && it.stopGeneration == run.stopGeneration }

    private fun interrupted(run: ContainerRun): Boolean = closed || runtime.wasInterrupted(run.containerId, run.stopGeneration) ||
        runtime.containers.value.any { it.config.id == run.containerId && it.running && it.runGeneration != run.generation }

    private fun unexpectedStop(run: ContainerRun): Boolean = !interrupted(run) &&
        runtime.containers.value.any { it.config.id == run.containerId && !it.running }

    private fun requireCurrent(run: ContainerRun) {
        if (!current(run)) {
            if (unexpectedStop(run)) error("容器运行进程已退出")
            throw CancellationException("容器已停止或重新启动")
        }
    }

    private suspend fun publishErrors(containerId: String, errors: List<String>, run: ContainerRun? = null) {
        currentCoroutineContext().ensureActive()
        val operation = currentCoroutineContext()[FeatureOperation]
        synchronized(gate) {
            if (!closed && (operation == null || !runtime.wasInterrupted(containerId, operation.stopGeneration)) &&
                (run == null || !interrupted(run))) _errors.update { it + (containerId to errors) }
        }
    }

    fun stateKey(containerId: String, featureId: String) = "$containerId/$featureId"
    fun setTheme(css: String) {
        themeCss = css
        instances.values.forEach { it.gateway?.themeCss = css }
    }
    private fun update(key: String, change: (ContainerFeatureState) -> ContainerFeatureState) {
        _states.update { states -> states + (key to change(states[key] ?: ContainerFeatureState())) }
    }
    private fun append(instance: Instance, text: String) = synchronized(gate) {
        if (instances[instance.key] === instance && current(instance.run)) update(instance.key) { it.copy(output = (it.output + text).takeLast(32_768)) }
    }

    /** Also called before agent startup, so the real native Skill is discoverable without opening Functions. */
    suspend fun installSkill(containerId: String) = installContainerFeatureSkill(runtime, containerId)

    suspend fun catalog(containerId: String): ContainerFeatureCatalog = withFeatureOperation(runtime, containerId) {
        try { discover(containerId) }
        catch (failure: Exception) {
            ensureCurrent(runtime)
            if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
            publishErrors(containerId, listOf("功能扫描失败：${failure.message}"))
            throw failure
        }
    }

    private suspend fun discover(containerId: String, run: ContainerRun? = null, runningOnly: Boolean = false): ContainerFeatureCatalog = withFeatureOperation(runtime, containerId) {
        if (runningOnly) this.runningOnly = true
        if (closed) throw CancellationException("功能服务已关闭")
        if (run != null) synchronized(gate) { requireCurrent(run) }
        val errors = mutableListOf<String>()
        if (!runningOnly) {
            try { installSkill(containerId) } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { ensureCurrent(runtime); errors += "内置 Skill 注入失败：${failure.message}" }
        }
        val bytes = featureCommand(runtime, containerId, """
            set -eu
            case "${'$'}HOME" in /*) ;; *) echo '容器 HOME 无效' >&2; exit 1;; esac
            home=${'$'}(readlink -f -- "${'$'}HOME")
            printf '%s\000' "${'$'}home"
            root="${'$'}home/.wawlink/features"
            if [ ! -d "${'$'}root" ]; then exit 0; fi
            actual=${'$'}(readlink -f -- "${'$'}root")
            case "${'$'}actual/" in "${'$'}home/"*) ;; *) printf 'E\000功能目录通过符号链接越出 HOME\000'; exit 0;; esac
            for dir in "${'$'}root"/*; do
                [ -d "${'$'}dir" ] || continue
                id=${'$'}{dir##*/}
                resolved=${'$'}(readlink -f -- "${'$'}dir")
                card=${'$'}(readlink -f -- "${'$'}dir/card.json")
                start=${'$'}(readlink -f -- "${'$'}dir/start.sh")
                case "${'$'}resolved/" in "${'$'}actual/"*) ;; *) printf 'E\000%s：目录越界\000' "${'$'}id"; continue;; esac
                case "${'$'}card" in "${'$'}resolved/"*) ;; *) printf 'E\000%s：card.json 越界\000' "${'$'}id"; continue;; esac
                case "${'$'}start" in "${'$'}resolved/"*) ;; *) printf 'E\000%s：start.sh 越界\000' "${'$'}id"; continue;; esac
                if [ ! -f "${'$'}dir/card.json" ] || [ ! -f "${'$'}dir/start.sh" ]; then printf 'E\000%s：缺少 card.json 或 start.sh\000' "${'$'}id"; continue; fi
                if [ "${'$'}(stat -Lc %s -- "${'$'}dir/card.json")" -gt 65536 ]; then printf 'E\000%s：card.json 超过 64 KiB\000' "${'$'}id"; continue; fi
                printf 'F\000%s\000%s\000' "${'$'}id" "${'$'}resolved"
                head -c 65537 -- "${'$'}dir/card.json" | base64 | tr -d '\n'
                printf '\000'
            done
        """.trimIndent(), runningOnly = runningOnly).toString(Charsets.UTF_8).split('\u0000')
        val home = bytes.firstOrNull().orEmpty()
        require(home.startsWith('/')) { "未能读取容器实际 HOME" }
        val features = mutableListOf<ContainerFeature>()
        var index = 1
        while (index < bytes.size - 1) {
            when (bytes[index++]) {
                "E" -> errors.add(bytes[index++])
                "F" -> {
                    val id = bytes[index++]; val directory = bytes[index++]; val document = bytes[index++]
                    try {
                        require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))) { "目录 ID 格式无效" }
                        val manifest = Base64.getDecoder().decode(document)
                        require(manifest.size <= 65_536) { "card.json 超过 64 KiB" }
                        val value = Json.parseToJsonElement(manifest.toString(Charsets.UTF_8)).jsonObject
                        fun required(name: String): String = value[name]?.jsonPrimitive?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: error("缺少字符串字段 $name")
                        val icon = required("icon")
                        require(icon.matches(Regex("[a-z][a-z0-9_]{0,79}"))) { "icon 必须是 Material Symbols 名称" }
                        val lifecycle = parseFeatureLifecycle(value)
                        val color = value["color"]?.let {
                            require(it is JsonPrimitive && it.isString && it.content.matches(Regex("#[0-9a-fA-F]{6}"))) { "color 必须为 #RRGGBB" }
                            it.content
                        }
                        val cardFile = value["card"]?.let {
                            require(it is JsonPrimitive && it.isString && it.content.isNotBlank()) { "card 必须为相对 HTML 文件路径" }
                            it.content
                        }
                        fun boolean(name: String): Boolean = value[name]?.let {
                            require(it is JsonPrimitive && !it.isString && it.booleanOrNull != null) { "$name 必须为布尔值" }
                            it.boolean
                        } ?: false
                        val height = value["cardHeight"]?.let {
                            require(it is JsonPrimitive && !it.isString && it.intOrNull != null) { "cardHeight 必须为整数 dp" }
                            it.int.coerceIn(80, 480)
                        } ?: 172
                        val cardUrl = parseFeatureCardUrl(value)
                        val html = if (cardUrl == null) readFeatureCardHtml(runtime, containerId, directory, cardFile) else null
                        features += ContainerFeature(id, required("title"), required("description"), icon, directory, lifecycle,
                            color, html, height, boolean("showStatus"), boolean("showMemory"),
                            parseFeatureCardSpan(value), parseFeatureActions(value), cardUrl)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { ensureCurrent(runtime); errors += "$id：${failure.message}" }
                }
                else -> error("容器功能扫描返回无效数据")
            }
        }
        ensureCurrent(runtime)
        if (run != null) synchronized(gate) { requireCurrent(run) }
        publishErrors(containerId, errors, run)
        ContainerFeatureCatalog(home, features.sortedBy { it.title }, errors)
    }

    suspend fun start(containerId: String, feature: ContainerFeature): ContainerFeatureSession = withFeatureOperation(runtime, containerId) {
        if (closed) throw CancellationException("功能服务已关闭")
        if (runtime.containers.value.none { it.config.id == containerId && it.running }) catalog(containerId)
        ensureCurrent(runtime)
        val run = synchronized(gate) {
            if (closed) throw CancellationException("功能服务已关闭")
            val container = runtime.containers.value.firstOrNull { it.config.id == containerId && it.running } ?: error("容器未运行")
            attachLocked(container).also { it.stoppedFeatures.remove(feature.id) }
        }
        val startup = run.scope.async { startInRun(run, feature, validate = true) }
        try { startup.await() }
        catch (cancelled: CancellationException) {
            startup.cancel(cancelled)
            withContext(NonCancellable) {
                startup.join()
                if (feature.lifecycle == ContainerFeatureLifecycle.PAGE) {
                    val key = stateKey(containerId, feature.id)
                    val instance = instances[key]?.takeIf { it.run === run && it.feature == feature }
                    if (instance != null) {
                        instance.stopRequested = true
                        reap(instance, ContainerFeatureStatus.STOPPED, stopHook = true)
                    }
                }
            }
            throw cancelled
        }
    }

    private suspend fun startInRun(run: ContainerRun, feature: ContainerFeature, validate: Boolean = false): ContainerFeatureSession =
        withContext(FeatureOperation(run.containerId, run.stopGeneration, runningOnly = true)) {
        val containerId = run.containerId
        val key = stateKey(containerId, feature.id)
        locks.getOrPut(key) { Mutex() }.withLock {
            synchronized(gate) {
                requireCurrent(run)
                if (feature.id in run.stoppedFeatures) throw CancellationException("功能已停止")
                instances[key]?.let { instance ->
                    states.value[key]?.session?.takeIf { instance.run === run && instance.process?.isAlive == true && !instance.stopping }
                        ?.let { return@withLock it }
                }
            }
            val instance = Instance(key, feature, run, currentCoroutineContext().job)
            synchronized(gate) {
                requireCurrent(run)
                if (feature.id in run.stoppedFeatures) throw CancellationException("功能已停止")
                instances[key] = instance
                update(key) { ContainerFeatureState(status = ContainerFeatureStatus.STARTING) }
            }
            try {
                if (validate) {
                    val current = catalog(containerId).features.firstOrNull { it.id == feature.id } ?: error("功能已移除或清单无效")
                    require(current == feature) { "功能清单已变化，请返回功能页刷新后重试" }
                }
                val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
                val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
                instance.port = port
                instance.token = token
                val identityMarker = "WAWLINK_PROCESS_${java.util.UUID.randomUUID()}"
                val processIdentity = CompletableDeferred<FeatureProcessIdentity?>()
                val groupScript = """
                    set -eu
                    $FEATURE_PROC_IDENTITY_SH
                    identity=${'$'}(proc_identity "${'$'}${'$'}") || identity=''
                    if [ -n "${'$'}identity" ] && [ -n "${'$'}2" ]; then
                        set -- "${'$'}1" "${'$'}2" "${'$'}3" ${'$'}identity
                        if [ "${'$'}4" = "${'$'}${'$'}" ] && [ "${'$'}5" = "${'$'}${'$'}" ]; then
                            printf '%s %s %s %s %s\n' "${'$'}3" "${'$'}1" "${'$'}2" "${'$'}${'$'}" "${'$'}6"
                        else
                            printf '%s unavailable\n' "${'$'}3"
                        fi
                    else
                        printf '%s unavailable\n' "${'$'}3"
                    fi
                    exec /bin/sh ./start.sh
                """.trimIndent()
                val process = withContext(NonCancellable) {
                    instance.startJob.ensureActive()
                    synchronized(gate) {
                        requireCurrent(run)
                        if (instance.stopping) throw CancellationException("功能已停止")
                    }
                    runtime.openRunningProcess(containerId, listOf("/bin/sh", "-c", """
                    set -eu
                    dir=${'$'}1
                    expected=${'$'}2
                    actual=${'$'}(readlink -f -- "${'$'}dir")
                    [ "${'$'}actual" = "${'$'}expected" ] || { echo '功能目录已变化' >&2; exit 1; }
                    cd -- "${'$'}dir"
                    [ -f ./start.sh ] || { echo '缺少 start.sh' >&2; exit 1; }
                    start=${'$'}(readlink -f ./start.sh)
                    case "${'$'}start" in "${'$'}actual/"*) ;; *) echo 'start.sh 越界' >&2; exit 1;; esac
                    command -v setsid >/dev/null || { echo '缺少 setsid，请安装 util-linux' >&2; exit 127; }
                    pid=''
                    watcher=''
                    supervisor=${'$'}${'$'}
                    $FEATURE_PROC_IDENTITY_SH
                    supervisor_identity=${'$'}(proc_identity "${'$'}supervisor") || supervisor_identity=''
                    supervisor_start=''
                    if [ -n "${'$'}supervisor_identity" ]; then
                        set -- ${'$'}supervisor_identity
                        supervisor_start=${'$'}3
                    fi
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
                    setsid /bin/sh -c ${shellQuote(groupScript)} wawlink-feature-group "${'$'}supervisor" "${'$'}supervisor_start" ${shellQuote(identityMarker)} 3<&- &
                    pid=${'$'}!
                    set +e
                    wait "${'$'}pid"
                    code=${'$'}?
                    printf '\n功能启动进程退出（%s）；start.sh 必须以前台方式运行服务。\n' "${'$'}code" >&2
                    exit "${'$'}code"
                """.trimIndent(), "wawlink-feature", feature.directory, feature.directory), "/", mapOf(
                    "WAWLINK_HOST" to "127.0.0.1", "WAWLINK_PORT" to port.toString(), "WAWLINK_TOKEN" to token,
                    "WAWLINK_FEATURE_ID" to feature.id, "WAWLINK_FEATURE_DIR" to feature.directory,
                    "WAWLINK_THEME_CSS" to themeCss,
                    )).also { instance.process = it }
                }
                currentCoroutineContext().ensureActive()
                listOf(process.inputStream, process.errorStream).forEachIndexed { index, stream ->
                    run.scope.launch {
                        try {
                            if (index == 0) processIdentity.complete(readFeatureIdentity(stream, identityMarker))
                            stream.bufferedReader().use { reader ->
                                val buffer = CharArray(2048)
                                while (true) { val count = reader.read(buffer); if (count < 0) break; append(instance, String(buffer, 0, count)) }
                            }
                        } catch (failure: Exception) {
                            if (index == 0) processIdentity.completeExceptionally(failure)
                            if (process.isAlive) append(instance, "\n读取功能输出失败：${failure.message}\n")
                        }
                    }
                }
                val identity = withTimeout(30_000) {
                    val identity = processIdentity.await()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        synchronized(gate) { requireCurrent(run) }
                        check(process.isAlive) { "启动进程已退出（${process.exitValue()}），请查看输出" }
                        if (ready(port, token)) break
                        delay(180)
                    }
                    identity
                }
                instance.identity = identity
                currentCoroutineContext().ensureActive()
                val gateway = ContainerFeatureGateway(port, token, themeCss) { iconFont }
                instance.gateway = gateway
                val session = ContainerFeatureSession(containerId, feature.id, gateway.url, token)
                synchronized(gate) {
                    requireCurrent(run)
                    if (instance.stopping || instances[key] !== instance) throw CancellationException("功能已停止")
                    update(key) { it.copy(status = ContainerFeatureStatus.RUNNING, session = session, error = null) }
                }
                run.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        // Install cancellation cleanup before returning; dispatch the blocking wait off the startup lock.
                        yield()
                        val exit = runInterruptible(Dispatchers.IO) { process.waitFor() }
                        reap(instance, ContainerFeatureStatus.FAILED, "服务进程已退出（$exit）", stopHook = false)
                    } finally {
                        if (unexpectedStop(run)) reap(instance, ContainerFeatureStatus.FAILED, "容器运行进程已退出", stopHook = false)
                        else reap(instance, ContainerFeatureStatus.STOPPED, stopHook = true)
                    }
                }
                session
            } catch (failure: Throwable) {
                val stoppedUnexpectedly = unexpectedStop(run)
                val cancelled = instance.stopping || interrupted(run) ||
                    (!stoppedUnexpectedly && (!currentCoroutineContext().isActive ||
                        (failure is CancellationException && failure !is TimeoutCancellationException)))
                val error = when {
                    cancelled -> null
                    failure is TimeoutCancellationException -> "30 秒内未就绪：服务须监听 WAWLINK_HOST/WAWLINK_PORT，并对根路径返回 HTTP 2xx/3xx"
                    stoppedUnexpectedly -> "容器运行进程已退出"
                    else -> failure.message ?: "启动失败"
                }
                reap(instance, if (cancelled) ContainerFeatureStatus.STOPPED else ContainerFeatureStatus.FAILED, error, stopHook = cancelled)
                if (cancelled) {
                    currentCoroutineContext().ensureActive()
                    throw CancellationException("容器或功能已停止").also { it.initCause(failure) }
                }
                throw failure
            }
        }
    }

    /** Only an unchanged, explicitly declared script action can execute in the current guest identity. */
    suspend fun runScriptAction(containerId: String, feature: ContainerFeature, action: ContainerFeatureAction): String =
        scriptActions.run(containerId, feature, action) {
            val key = stateKey(containerId, feature.id)
            locks.getOrPut(key) { Mutex() }.withLock {
                check(!closed) { "功能服务已关闭" }
                val current = catalog(containerId).features.firstOrNull { it.id == feature.id }
                    ?: error("功能已移除或清单无效")
                require(current == feature) { "功能清单已变化，请返回功能页刷新后重试" }
                require(action.type == ContainerFeatureActionType.SCRIPT && action in current.actions) { "只能执行清单声明的 script 动作" }
            }
        }

    suspend fun stop(containerId: String, featureId: String) = withContext(Dispatchers.IO) {
        val key = stateKey(containerId, featureId)
        cancelStarting(containerId, featureId, key)
        locks.getOrPut(key) { Mutex() }.withLock { stopLocked(containerId, featureId, key) }
    }

    private suspend fun cancelStarting(containerId: String, featureId: String, key: String) {
        val starting = synchronized(gate) {
            runs[containerId]?.stoppedFeatures?.add(featureId)
            instances[key]?.also { it.stopRequested = true; it.stopping = true }?.startJob?.takeIf { it.isActive }
        }
        starting?.cancel(CancellationException("功能已停止"))
        starting?.join()
    }

    private suspend fun stopLocked(containerId: String, featureId: String, key: String) {
        scriptActions.cancelFeature(containerId, featureId)
        instances[key]?.let { reap(it, ContainerFeatureStatus.STOPPED, stopHook = true) }
    }

    /** Every path uses the guest supervisor's control pipe before any host-wrapper fallback. */
    private suspend fun reap(instance: Instance, status: ContainerFeatureStatus, error: String? = null, stopHook: Boolean): Unit = withContext(
        NonCancellable + Dispatchers.IO + FeatureRunningOnly + FeatureOperation(instance.run.containerId, instance.run.stopGeneration, runningOnly = true)
    ) {
        if (!instance.reaping.compareAndSet(false, true)) {
            instance.reaped.await()
            return@withContext
        }
        instance.stopping = true
        val containerId = instance.run.containerId
        val featureId = instance.feature.id
        var failure: String? = null
        try {
            synchronized(gate) {
                if (instances[instance.key] === instance) {
                    update(instance.key) { it.copy(status = ContainerFeatureStatus.STOPPING, session = null) }
                }
            }
            instance.gateway?.close()
            val canRunHook = synchronized(gate) { runs[containerId] === instance.run } &&
                runtime.containers.value.any { it.config.id == containerId && it.running && it.runGeneration == instance.run.generation }
            if (stopHook && instance.process != null && canRunHook) {
                try {
                    val output = command(containerId, """
                        set -eu
                        expected=${shellQuote(instance.feature.directory)}
                        actual=${'$'}(readlink -f -- "${'$'}expected")
                        [ "${'$'}actual" = "${'$'}expected" ] || { echo '功能目录已变化，未执行 stop.sh' >&2; exit 1; }
                        cd -- "${'$'}actual"
                        if [ -f ./stop.sh ]; then
                            stop=${'$'}(readlink -f ./stop.sh)
                            case "${'$'}stop" in "${'$'}actual/"*) ;; *) echo 'stop.sh 越界' >&2; exit 1;; esac
                            command -v timeout >/dev/null || { echo '缺少 timeout，请安装 coreutils' >&2; exit 127; }
                            timeout -k 1 15 /bin/sh ./stop.sh
                        fi
                    """.trimIndent(), mapOf("WAWLINK_FEATURE_ID" to featureId, "WAWLINK_FEATURE_DIR" to instance.feature.directory,
                        "WAWLINK_TOKEN" to instance.token,
                        "WAWLINK_PORT" to instance.port.toString(), "WAWLINK_HOST" to "127.0.0.1"))
                    if (output.isNotEmpty()) append(instance, output.toString(Charsets.UTF_8))
                } catch (_: TimeoutCancellationException) {
                    failure = "停止脚本超时（20 秒），已强制回收服务"
                } catch (_: CancellationException) {
                    // A stop/restart invalidated the hook; it is not a failed stop script.
                } catch (problem: Exception) { failure = "停止脚本失败：${problem.message}" }
            }
        } finally {
            try {
                instance.process?.let(::finishProcess)
            } catch (problem: Exception) {
                failure = "回收功能进程失败：${problem.message}"
            } finally {
                synchronized(gate) {
                    if (instances.remove(instance.key, instance)) {
                        val cancelled = interrupted(instance.run)
                        val reportedFailure = if (cancelled) null else failure ?: error?.takeUnless { instance.stopRequested }
                        val reportedStatus = when {
                            reportedFailure != null -> ContainerFeatureStatus.FAILED
                            cancelled || instance.stopRequested -> ContainerFeatureStatus.STOPPED
                            else -> status
                        }
                        update(instance.key) { it.copy(status = reportedStatus, session = null, error = reportedFailure) }
                        if (reportedFailure != null) {
                            val message = "${instance.feature.id}：$reportedFailure"
                            _errors.update { errors -> errors + (containerId to (errors[containerId].orEmpty() + message).distinct()) }
                        }
                    }
                }
                instance.reaped.complete(Unit)
            }
        }
    }

    /** Sum of supervised guest process-group RSS; null means unavailable, never host/container RAM. */
    suspend fun memoryBytes(containerId: String, featureId: String): Long? = withContext(Dispatchers.IO) {
        val key = stateKey(containerId, featureId)
        locks.getOrPut(key) { Mutex() }.withLock {
            val running = instances[key] ?: return@withLock null
            val identity = running.identity ?: return@withLock null
            if (running.stopping || running.process?.isAlive != true || runtime.containers.value.none { it.config.id == containerId && it.running }) return@withLock null
            try { readFeatureMemoryBytes(runtime, containerId, identity) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
    }

    /** Deletes only the exact verified guest HOME feature directory, using no-follow file operations. */
    suspend fun uninstall(containerId: String, feature: ContainerFeature) = withContext(Dispatchers.IO) {
        val key = stateKey(containerId, feature.id)
        cancelStarting(containerId, feature.id, key)
        locks.getOrPut(key) { Mutex() }.withLock {
            check(!closed) { "功能服务已关闭" }
            require(feature.id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))) { "功能 ID 无效" }
            val config = runtime.containers.value.firstOrNull { it.config.id == containerId }?.config ?: error("容器不存在")
            val home = command(containerId, """
                set -eu
                case "${'$'}HOME" in /*) ;; *) echo '容器 HOME 无效' >&2; exit 1;; esac
                readlink -f -- "${'$'}HOME"
            """.trimIndent()).toString(Charsets.UTF_8).removeSuffix("\n")
            require(home.startsWith('/')) { "未能读取容器实际 HOME" }
            val directory = home.trimEnd('/') + "/.wawlink/features/" + feature.id
            require(feature.directory == directory) { "只能卸载实际 HOME/.wawlink/features/id 下的原始功能目录，不能卸载链接或别名" }
            val boundaries = listOf("/workspace", "/proc", "/sys", "/dev", "/run", "/etc/resolv.conf") + config.mounts.map { ContainerFileService.path(it.target) }
            require(boundaries.none { it == directory || it.startsWith("$directory/") || directory.startsWith(it.trimEnd('/') + "/") }) { "不能卸载受保护目录或宿主挂载中的功能" }
            val files = ContainerFileService(runtime, config)
            require(files.stat(directory).directory) { "功能目录不存在或是符号链接" }
            command(containerId, featureRemovalMountGuard(directory))
            stopLocked(containerId, feature.id, key)
            command(containerId, featureRemovalScript(directory))
            _states.update { it - key }
        }
    }

    suspend fun leave(containerId: String, featureId: String) {
        scriptActions.cancelFeature(containerId, featureId, pageOnly = true)
        val running = instances[stateKey(containerId, featureId)]
        if (running?.feature?.lifecycle == ContainerFeatureLifecycle.PAGE) stop(containerId, featureId)
    }

    /** Composition disposal cannot depend on a cancelled composition CoroutineScope. */
    fun leavePage(containerId: String, featureId: String) {
        if (!closed) scope.launch { runCatching { leave(containerId, featureId) } }
    }

    private fun finishProcess(process: Process) {
        runCatching { process.outputStream.apply { write("stop\n".toByteArray()); flush(); close() } }
        if (!process.waitFor(2500, TimeUnit.MILLISECONDS)) {
            process.destroy()
            if (!process.waitFor(1000, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            process.waitFor(1000, TimeUnit.MILLISECONDS)
        }
    }

    private suspend fun command(containerId: String, script: String, environment: Map<String, String> = emptyMap()): ByteArray =
        featureCommand(runtime, containerId, script, environment)

    private fun ready(port: Int, token: String): Boolean = try {
        val connection = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 400; connection.readTimeout = 400; connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("X-WawLink-Token", token)
            connection.responseCode in 200..399
        } finally { connection.disconnect() }
    } catch (_: java.io.IOException) { false }

    override fun close() {
        val jobs = synchronized(gate) {
            if (closed) return
            closed = true
            allRuns.map { it.job }.also { jobs -> jobs.forEach { it.cancel(CancellationException("功能服务已关闭")) } }
        }
        debugger.close()
        scriptActions.close()
        runBlocking(Dispatchers.IO) {
            jobs.joinAll()
            instances.values.toList().map { instance -> async { reap(instance, ContainerFeatureStatus.STOPPED, stopHook = true) } }.awaitAll()
            synchronized(gate) { runs.clear() }
        }
        scope.cancel()
    }
}

/** Synchronize pristine bundled content before discovery or native Agent startup. */
suspend fun installContainerFeatureSkill(runtime: ContainerRuntime, containerId: String) = withFeatureOperation(runtime, containerId) {
    val shared = runtime.containers.value.firstOrNull { it.config.id == containerId }?.config?.engine == ContainerEngineType.CHROOT
    featureCommand(runtime, containerId, if (shared) FEATURE_SKILL_SHARED_INSTALL else FEATURE_SKILL_INSTALL)
    ensureCurrent(runtime)
    FeatureDebugServices.install(runtime, containerId)
}

private object FeatureRunningOnly : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<FeatureRunningOnly>
}

internal suspend fun featureCommand(runtime: ContainerRuntime, containerId: String, script: String, environment: Map<String, String> = emptyMap(), runningOnly: Boolean = false, stdinBytes: ByteArray? = null): ByteArray = withFeatureOperation(runtime, containerId) {
    val operation = this
    coroutineScope {
    val owner = currentCoroutineContext().job
    val requireRunning = runningOnly || operation.runningOnly || currentCoroutineContext()[FeatureRunningOnly.Key] != null
    var child: Process? = null
    try {
        val process = withContext(NonCancellable) {
            owner.ensureActive()
            operation.ensureCurrent(runtime)
            (if (requireRunning) runtime.openRunningProcess(containerId, listOf("/bin/sh", "-c", script), "/", environment)
            else runtime.openProcess(containerId, listOf("/bin/sh", "-c", script), "/", environment)).also {
                child = it
                operation.runningOnly = true
            }
        }
        operation.ensureCurrent(runtime)
        val stdout = async(Dispatchers.IO) { process.inputStream.use { featureReadBounded(it, 4 * 1024 * 1024) } }
        val stderr = async(Dispatchers.IO) { process.errorStream.use { featureReadBounded(it, 32_768).toString(Charsets.UTF_8) } }
        val stdin = async(Dispatchers.IO) {
            try {
                runInterruptible { process.outputStream.use { if (stdinBytes != null) it.write(stdinBytes) } }
                Result.success(Unit)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { Result.failure<Unit>(failure) }
        }
        withTimeout(20_000) {
            val written = stdin.await()
            runInterruptible(Dispatchers.IO) { process.waitFor() }
            val output = stdout.await(); val error = stderr.await()
            operation.ensureCurrent(runtime)
            check(process.exitValue() == 0) { "容器功能命令失败（${process.exitValue()}）：$error${output.toString(Charsets.UTF_8).takeLast(32_768)}" }
            written.getOrThrow()
            output
        }
    } finally {
        child?.let { process ->
            try {
                try { if (process.isAlive) process.destroy() }
                finally {
                    try { process.inputStream.close() }
                    finally {
                        try { process.errorStream.close() }
                        finally { process.outputStream.close() }
                    }
                }
            } catch (failure: Exception) {
                owner.ensureActive()
                operation.ensureCurrent(runtime)
                throw failure
            }
        }
    }
}
}

internal const val DEFAULT_FEATURE_CSS = ":root{--md-sys-color-primary:#0b57d0;--md-sys-color-primary-container:#d3e3fd;--md-sys-color-on-primary:#ffffff;--md-sys-color-on-primary-container:#041e49;--md-sys-color-surface:#ffffff;--md-sys-color-on-surface:#1f1f1f;--wawlink-motion:cubic-bezier(.2,0,0,1);color-scheme:light}body{background:#fff;color:#1f1f1f;font-family:system-ui,'Noto Sans CJK SC',sans-serif;font-size:18px}button,input,textarea,select{font:inherit}button{transition:background-color .3s var(--wawlink-motion),transform .45s var(--wawlink-motion)}button:active{transform:scale(.96)}"
