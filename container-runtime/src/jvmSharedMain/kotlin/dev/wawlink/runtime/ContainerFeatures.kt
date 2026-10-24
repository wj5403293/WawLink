package dev.wawlink.runtime

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

enum class ContainerFeatureLifecycle { PAGE, MANUAL }
data class ContainerFeature(val id: String, val title: String, val description: String, val icon: String, val directory: String, val lifecycle: ContainerFeatureLifecycle = ContainerFeatureLifecycle.PAGE)
data class ContainerFeatureCatalog(val home: String, val features: List<ContainerFeature>, val errors: List<String>)
data class ContainerFeatureSession(val containerId: String, val featureId: String, val url: String, val token: String)
enum class ContainerFeatureStatus { STOPPED, STARTING, RUNNING, STOPPING, FAILED }
data class ContainerFeatureState(val status: ContainerFeatureStatus = ContainerFeatureStatus.STOPPED, val session: ContainerFeatureSession? = null, val output: String = "", val error: String? = null)

/** Guest-owned cards. Discovery only reads data; processes always use the configured runtime identity. */
class ContainerFeatureService(private val runtime: ContainerRuntime) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val active = ConcurrentHashMap<String, Running>()
    private val starting = ConcurrentHashMap<String, Process>()
    private val _states = MutableStateFlow<Map<String, ContainerFeatureState>>(emptyMap())
    val states: StateFlow<Map<String, ContainerFeatureState>> = _states.asStateFlow()
    @Volatile private var closed = false
    @Volatile private var themeCss = DEFAULT_FEATURE_CSS
    @Volatile private var iconFont: ByteArray? = null
    fun setIconFont(bytes: ByteArray) { iconFont = bytes }
    private class Running(val feature: ContainerFeature, val process: Process, val gateway: ContainerFeatureGateway) {
        @Volatile var stopping = false
    }

    init {
        scope.launch {
            runtime.containers.collect { containers ->
                val running = containers.filter { it.running }.map { it.config.id }.toSet()
                active.entries.filter { it.key.substringBefore('/') !in running }.forEach { (key, value) ->
                    value.stopping = true
                    value.gateway.close()
                    value.process.destroy()
                    active.remove(key, value)
                    update(key) { it.copy(status = ContainerFeatureStatus.STOPPED, session = null, error = "容器已停止") }
                }
            }
        }
    }

    fun stateKey(containerId: String, featureId: String) = "$containerId/$featureId"
    fun setTheme(css: String) {
        themeCss = css
        active.values.forEach { it.gateway.themeCss = css }
    }
    private fun update(key: String, change: (ContainerFeatureState) -> ContainerFeatureState) {
        _states.update { states -> states + (key to change(states[key] ?: ContainerFeatureState())) }
    }
    private fun append(key: String, text: String) = update(key) { it.copy(output = (it.output + text).takeLast(32_768)) }

    /** Also called before agent startup, so the real native Skill is discoverable without opening Functions. */
    suspend fun installSkill(containerId: String) = installContainerFeatureSkill(runtime, containerId)

    suspend fun catalog(containerId: String): ContainerFeatureCatalog {
        check(!closed) { "功能服务已关闭" }
        val errors = mutableListOf<String>()
        try { installSkill(containerId) } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { errors += "内置 Skill 注入失败：${failure.message}" }
        val bytes = command(containerId, """
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
                if [ "${'$'}(wc -c < "${'$'}dir/card.json")" -gt 65536 ]; then printf 'E\000%s：card.json 超过 64 KiB\000' "${'$'}id"; continue; fi
                printf 'F\000%s\000%s\000' "${'$'}id" "${'$'}resolved"
                base64 < "${'$'}dir/card.json" | tr -d '\n'
                printf '\000'
            done
        """.trimIndent()).toString(Charsets.UTF_8).split('\u0000')
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
                        val value = Json.parseToJsonElement(Base64.getDecoder().decode(document).toString(Charsets.UTF_8)).jsonObject
                        fun required(name: String): String = value[name]?.jsonPrimitive?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: error("缺少字符串字段 $name")
                        val icon = required("icon")
                        require(icon.matches(Regex("[a-z][a-z0-9_]{0,79}"))) { "icon 必须是 Material Symbols 名称" }
                        val lifecycle = when (value["lifecycle"]?.jsonPrimitive?.content ?: "page") {
                            "page" -> ContainerFeatureLifecycle.PAGE
                            "manual" -> ContainerFeatureLifecycle.MANUAL
                            else -> error("lifecycle 只能为 page 或 manual")
                        }
                        features += ContainerFeature(id, required("title"), required("description"), icon, directory, lifecycle)
                    } catch (failure: Exception) { errors += "$id：${failure.message}" }
                }
                else -> error("容器功能扫描返回无效数据")
            }
        }
        return ContainerFeatureCatalog(home, features.sortedBy { it.title }, errors)
    }

    suspend fun start(containerId: String, feature: ContainerFeature): ContainerFeatureSession = withContext(Dispatchers.IO) {
        val key = stateKey(containerId, feature.id)
        locks.getOrPut(key) { Mutex() }.withLock {
            check(!closed) { "功能服务已关闭" }
            active[key]?.let { running ->
                states.value[key]?.session?.takeIf { running.process.isAlive && !running.stopping }?.let { return@withLock it }
            }
            update(key) { ContainerFeatureState(status = ContainerFeatureStatus.STARTING) }
            var child: Process? = null
            var gateway: ContainerFeatureGateway? = null
            try {
                val current = catalog(containerId).features.firstOrNull { it.id == feature.id } ?: error("功能已移除或清单无效")
                require(current == feature) { "功能清单已变化，请返回功能页刷新后重试" }
                val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
                val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
                child = runtime.openProcess(containerId, listOf("/bin/sh", "-c", """
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
                    setsid /bin/sh ./start.sh 3<&- &
                    pid=${'$'}!
                    set +e
                    wait "${'$'}pid"
                    code=${'$'}?
                    printf '\n功能启动进程退出（%s）；start.sh 必须以前台方式运行服务。\n' "${'$'}code" >&2
                    exit "${'$'}code"
                """.trimIndent(), "wawlink-feature", current.directory, current.directory), "/", mapOf(
                    "WAWLINK_HOST" to "127.0.0.1", "WAWLINK_PORT" to port.toString(), "WAWLINK_TOKEN" to token,
                    "WAWLINK_FEATURE_ID" to feature.id, "WAWLINK_FEATURE_DIR" to current.directory,
                    "WAWLINK_THEME_CSS" to themeCss,
                ))
                val process = child
                starting[key] = process
                check(!closed) { "功能服务已关闭" }
                listOf(process.inputStream, process.errorStream).forEach { stream ->
                    scope.launch {
                        try { stream.bufferedReader().use { reader ->
                            val buffer = CharArray(2048)
                            while (true) { val count = reader.read(buffer); if (count < 0) break; append(key, String(buffer, 0, count)) }
                        } } catch (_: java.io.IOException) { /* Process termination closes readers. */ }
                    }
                }
                withTimeout(30_000) {
                    while (true) {
                        check(process.isAlive) { "启动进程已退出（${process.exitValue()}），请查看输出" }
                        if (ready(port, token)) break
                        delay(180)
                    }
                }
                check(!closed) { "功能服务已关闭" }
                gateway = ContainerFeatureGateway(port, token, themeCss) { iconFont }
                val session = ContainerFeatureSession(containerId, feature.id, gateway.url, token)
                val running = Running(current, process, gateway)
                active[key] = running
                starting.remove(key, process)
                check(!closed) { "功能服务已关闭" }
                update(key) { it.copy(status = ContainerFeatureStatus.RUNNING, session = session, error = null) }
                scope.launch {
                    val exit = process.waitFor()
                    if (!running.stopping && active.remove(key, running)) {
                        running.gateway.close()
                        update(key) { it.copy(status = ContainerFeatureStatus.FAILED, session = null, error = "服务进程已退出（$exit）") }
                    }
                }
                session
            } catch (failure: Throwable) {
                starting.remove(key)
                active.remove(key)?.stopping = true
                gateway?.close()
                child?.let(::finishProcess)
                update(key) { it.copy(status = ContainerFeatureStatus.FAILED, session = null, error = if (failure is TimeoutCancellationException) "30 秒内未就绪：服务须监听 WAWLINK_HOST/WAWLINK_PORT，并对根路径返回 HTTP 2xx/3xx" else failure.message ?: "启动失败") }
                throw failure
            }
        }
    }

    suspend fun stop(containerId: String, featureId: String) = withContext(Dispatchers.IO) {
        val key = stateKey(containerId, featureId)
        locks.getOrPut(key) { Mutex() }.withLock {
            val running = active[key] ?: return@withLock
            running.stopping = true
            update(key) { it.copy(status = ContainerFeatureStatus.STOPPING) }
            running.gateway.close()
            var failure: String? = null
            try {
                if (runtime.containers.value.any { it.config.id == containerId && it.running }) {
                    val output = command(containerId, """
                        set -eu
                        cd -- ${shellQuote(running.feature.directory)}
                        if [ -f ./stop.sh ]; then
                            actual=${'$'}(readlink -f .)
                            stop=${'$'}(readlink -f ./stop.sh)
                            case "${'$'}stop" in "${'$'}actual/"*) ;; *) echo 'stop.sh 越界' >&2; exit 1;; esac
                            command -v timeout >/dev/null || { echo '缺少 timeout，请安装 coreutils' >&2; exit 127; }
                            timeout -k 1 15 /bin/sh ./stop.sh
                        fi
                    """.trimIndent(), mapOf("WAWLINK_FEATURE_ID" to featureId, "WAWLINK_FEATURE_DIR" to running.feature.directory,
                        "WAWLINK_TOKEN" to (states.value[key]?.session?.token ?: ""),
                        "WAWLINK_PORT" to running.gateway.upstreamPort.toString(), "WAWLINK_HOST" to "127.0.0.1"))
                    if (output.isNotEmpty()) append(key, output.toString(Charsets.UTF_8))
                }
            } catch (_: TimeoutCancellationException) {
                failure = "停止脚本超时（20 秒），已强制回收服务"
                append(key, "\n$failure\n")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (problem: Exception) { failure = "停止脚本失败：${problem.message}"; append(key, "\n$failure\n") }
            finally {
                finishProcess(running.process)
                active.remove(key, running)
                update(key) { it.copy(status = if (failure == null) ContainerFeatureStatus.STOPPED else ContainerFeatureStatus.FAILED, session = null, error = failure) }
            }
        }
    }

    suspend fun leave(containerId: String, featureId: String) {
        if (active[stateKey(containerId, featureId)]?.feature?.lifecycle != ContainerFeatureLifecycle.MANUAL) stop(containerId, featureId)
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
        if (closed) return
        closed = true
        runBlocking(Dispatchers.IO) {
            starting.values.toList().map { process -> async { finishProcess(process) } }.awaitAll()
            starting.clear()
            active.keys.toList().map { key -> async { runCatching { stop(key.substringBefore('/'), key.substringAfter('/')) } } }.awaitAll()
        }
        scope.cancel()
    }
}

/** Install before native agent spawn; an existing Skill, including user changes, is never overwritten. */
suspend fun installContainerFeatureSkill(runtime: ContainerRuntime, containerId: String) {
    featureCommand(runtime, containerId, """
        set -eu
        case "${'$'}HOME" in /*) ;; *) echo '容器 HOME 无效' >&2; exit 1;; esac
        home=${'$'}(readlink -f -- "${'$'}HOME")
        root="${'$'}home/.omp/agent/skills"
        actual=${'$'}(readlink -m -- "${'$'}root")
        case "${'$'}actual/" in "${'$'}home/"*) ;; *) echo 'Skill 目录通过符号链接越出 HOME' >&2; exit 1;; esac
        mkdir -p -- "${'$'}root"
        dir="${'$'}root/wawlink-functions"
        if [ -L "${'$'}dir" ]; then exit 0; fi
        mkdir -p -- "${'$'}dir"
        if [ -e "${'$'}dir/SKILL.md" ] || [ -L "${'$'}dir/SKILL.md" ]; then exit 0; fi
        tmp=${'$'}(mktemp "${'$'}dir/.wawlink-skill.XXXXXX")
        trap 'rm -f -- "${'$'}tmp"' 0
        printf '%s' ${shellQuote(Base64.getEncoder().encodeToString(FEATURE_SKILL.toByteArray()))} | base64 -d > "${'$'}tmp"
        ln -- "${'$'}tmp" "${'$'}dir/SKILL.md" 2>/dev/null || [ -f "${'$'}dir/SKILL.md" ]
    """.trimIndent())
}

private suspend fun featureCommand(runtime: ContainerRuntime, containerId: String, script: String, environment: Map<String, String> = emptyMap()): ByteArray = coroutineScope {
    val process = runtime.openProcess(containerId, listOf("/bin/sh", "-c", script), "/", environment)
    process.outputStream.close()
    val stdout = async(Dispatchers.IO) { process.inputStream.use { featureReadBounded(it, 4 * 1024 * 1024) } }
    val stderr = async(Dispatchers.IO) { process.errorStream.use { featureReadBounded(it, 32_768).toString(Charsets.UTF_8) } }
    try {
        withTimeout(20_000) { runInterruptible(Dispatchers.IO) { process.waitFor() } }
        val output = stdout.await(); val error = stderr.await()
        check(process.exitValue() == 0) { "容器功能命令失败（${process.exitValue()}）：$error${output.toString(Charsets.UTF_8).takeLast(32_768)}" }
        output
    } finally { if (process.isAlive) process.destroy() }
}

internal const val DEFAULT_FEATURE_CSS = ":root{--md-sys-color-primary:#0b57d0;--md-sys-color-primary-container:#d3e3fd;--md-sys-color-on-primary:#ffffff;--md-sys-color-on-primary-container:#041e49;--md-sys-color-surface:#ffffff;--md-sys-color-on-surface:#1f1f1f;--wawlink-motion:cubic-bezier(.2,0,0,1);color-scheme:light}body{background:#fff;color:#1f1f1f;font-family:system-ui,'Noto Sans CJK SC',sans-serif;font-size:18px}button,input,textarea,select{font:inherit}button{transition:background-color .3s var(--wawlink-motion),transform .45s var(--wawlink-motion)}button:active{transform:scale(.96)}"
