package dev.wawlink.app

import dev.wawlink.runtime.FEATURE_DEBUG_BOOTSTRAP
import dev.wawlink.runtime.FEATURE_DEBUG_INSPECT_SCRIPT
import dev.wawlink.runtime.FEATURE_DEBUG_LOGS_SCRIPT
import dev.wawlink.runtime.FeatureDebugPage
import dev.wawlink.runtime.featureDebugEvalCancel
import dev.wawlink.runtime.featureDebugEvalPoll
import dev.wawlink.runtime.featureDebugEvalStart
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.embed.swing.SwingFXUtils
import javafx.scene.web.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO

/** Registration belongs to the WebView holder, not the temporarily removed covered SwingPanel. */
internal class DesktopFeatureDebugSurface(private val disposed: AtomicBoolean) : FeatureDebugPage {
    private class Binding(val context: FeatureDebugContext, var registration: AutoCloseable? = null)
    private val lock = Any()
    private var binding: Binding? = null
    private var attached = false
    private var web: WebView? = null
    private var trusted: () -> Boolean = { false }
    private var visible: () -> Boolean = { false }
    private var reload: (String?) -> Unit = {}
    private var staticDocument = false
    private val epoch = AtomicLong()
    private val sequence = AtomicLong()
    private val pending = ConcurrentHashMap<Long, (Throwable) -> Unit>()
    private val registered = AtomicBoolean()
    private val nativeErrors = ArrayDeque<kotlinx.serialization.json.JsonObject>() // JavaFX-thread only.

    fun recordError(message: String) {
        check(Platform.isFxApplicationThread())
        if (nativeErrors.size == 80) nativeErrors.removeFirst()
        nativeErrors.addLast(buildJsonObject {
            put("time", System.currentTimeMillis())
            put("message", message.replace(Regex("([?&]__wawlink_token=)[^&#\\s]*"), "$1[redacted]").take(4096))
        })
    }

    fun bind(context: FeatureDebugContext?): AutoCloseable {
        if (context == null) return AutoCloseable { }
        val next = Binding(context)
        synchronized(lock) {
            check(!disposed.get()) { "页面已释放" }
            binding?.registration?.close()
            registered.set(false)
            invalidate()
            binding = next
            if (attached) {
                next.registration = context.debugger.register(context.target, this)
                registered.set(true)
            }
        }
        return AutoCloseable {
            synchronized(lock) {
                next.registration?.close()
                next.registration = null
                if (binding === next) {
                    registered.set(false)
                    binding = null
                    invalidate()
                }
            }
        }
    }

    /** JavaFX thread only; callbacks always consult the current document and regenerated content. */
    fun attach(view: WebView, trustedDocument: () -> Boolean, isVisible: () -> Boolean, staticHtml: Boolean = false, reloadDocument: (String?) -> Unit) {
        check(Platform.isFxApplicationThread())
        synchronized(lock) {
            if (disposed.get()) return
            web = view
            trusted = trustedDocument
            visible = isVisible
            reload = reloadDocument
            staticDocument = staticHtml
            attached = true
            binding?.let {
                it.registration = it.context.debugger.register(it.context.target, this)
                registered.set(true)
            }
        }
    }

    /** May be called from Compose disposal before JavaFX processes its queued teardown. */
    fun dispose() {
        synchronized(lock) {
            registered.set(false)
            binding?.registration?.close()
            binding = null
            attached = false
        }
        invalidate()
    }

    fun invalidate() {
        epoch.incrementAndGet()
        val failure = IllegalStateException("页面已导航或释放，调试请求已失效")
        pending.forEach { (id, _) -> pending.remove(id)?.invoke(failure) }
    }

    fun bootstrap() {
        check(Platform.isFxApplicationThread())
        if (!disposed.get() && trusted()) web?.engine?.executeScript(FEATURE_DEBUG_BOOTSTRAP)
    }

    override suspend fun request(operation: String, expression: String?): JsonElement {
        val version = epoch.get()
        return when (operation) {
            "inspect" -> fx(version, requireReady = false) { view ->
                val ready = trusted() && view.engine.loadWorker.state == Worker.State.SUCCEEDED
                buildJsonObject {
                    put("native", buildJsonObject {
                        put("width", view.width)
                        put("height", view.height)
                        put("visible", view.isVisible && visible() && view.width > 0 && view.height > 0)
                        put("attached", view.scene != null)
                        put("zoom", view.zoom)
                        put("fontScale", view.fontScale)
                        put("loadState", view.engine.loadWorker.state.name)
                    })
                    put("layout", if (ready) scriptJson(view, FEATURE_DEBUG_INSPECT_SCRIPT) else JsonNull)
                }
            }
            "logs" -> fx(version, requireReady = false) { view ->
                val browser = if (trusted() && view.engine.loadWorker.state == Worker.State.SUCCEEDED) scriptJson(view, FEATURE_DEBUG_LOGS_SCRIPT).jsonObject
                else buildJsonObject {
                    put("entries", kotlinx.serialization.json.JsonArray(emptyList()))
                    put("dropped", 0)
                    put("unavailable", true)
                }
                buildJsonObject {
                    browser.forEach { (key, value) -> put(key, value) }
                    put("nativeErrors", kotlinx.serialization.json.JsonArray(nativeErrors.toList()))
                }
            }
            "eval" -> evaluate(version, requireNotNull(expression) { "缺少执行表达式" })
            "screenshot" -> screenshot(version)
            "reload" -> {
                val refreshedHtml = if (staticDocument) {
                    val context = synchronized(lock) { requireNotNull(binding).context }
                    withContext(Dispatchers.IO) { context.debugger.readCardHtml(context.target) }
                } else null
                fx(version, requireReady = false, navigationAllowed = true) {
                    check(trusted() || it.engine.document == null) { "当前页面不属于此功能" }
                    // Dispatch only; navigation deliberately invalidates other in-flight requests.
                    reload(refreshedHtml)
                    buildJsonObject { put("reloaded", true) }
                }
            }
            else -> error("不支持的页面调试操作：$operation")
        }
    }

    private suspend fun evaluate(version: Long, expression: String): JsonElement = withTimeout(30_000) {
        val id = UUID.randomUUID().toString()
        var document: org.w3c.dom.Document? = null
        try {
            fx(version) {
                document = it.engine.document
                it.engine.executeScript(featureDebugEvalStart(id, expression))
            }
            var result: JsonElement? = null
            while (result == null) {
                delay(25)
                val state = fx(version) { scriptJson(it, featureDebugEvalPoll(id)).jsonObject }
                if (state["done"]?.jsonPrimitive?.booleanOrNull == true) {
                    check(state["ok"]?.jsonPrimitive?.booleanOrNull == true) {
                        state["error"]?.jsonPrimitive?.content ?: "页面执行失败"
                    }
                    result = state["value"] ?: JsonNull
                }
            }
            checkCurrent(version)
            requireNotNull(result)
        } finally {
            // Cancellation also removes a pending Promise without touching a replacement document.
            Platform.runLater {
                if (!disposed.get() && document != null && web?.engine?.document === document && trusted()) {
                    runCatching { web?.engine?.executeScript(featureDebugEvalCancel(id)) }
                }
            }
        }
    }

    private suspend fun screenshot(version: Long): JsonElement {
        val image = fx(version) { view ->
            check(view.isVisible && visible() && view.scene?.window?.isShowing == true) { "页面当前不可见，无法截取真实画面" }
            val width = kotlin.math.ceil(view.width).toLong()
            val height = kotlin.math.ceil(view.height).toLong()
            require(width in 1..8_000_000 && height in 1..8_000_000 && width * height <= 8_000_000) {
                "截图尺寸超过 800 万像素或页面尚无尺寸"
            }
            SwingFXUtils.fromFXImage(view.snapshot(null, null), null)
        }
        return withContext(Dispatchers.Default) {
            val bytes = LimitedPngOutput()
            check(ImageIO.write(image, "png", bytes)) { "PNG 编码器不可用" }
            checkCurrent(version)
            buildJsonObject {
                put("mimeType", "image/png")
                put("base64", bytes.base64())
                put("width", image.width)
                put("height", image.height)
            }
        }
    }

    private fun scriptJson(view: WebView, script: String): JsonElement {
        val result = view.engine.executeScript(script) as? String ?: error("页面没有返回 JSON")
        require(result.length <= 2 * 1024 * 1024) { "页面调试输出超过 2 MiB" }
        return Json.parseToJsonElement(result)
    }

    private fun checkCurrent(version: Long) {
        check(registered.get() && !disposed.get() && version == epoch.get()) { "页面已导航或释放，调试请求已失效" }
    }

    private suspend fun <T> fx(version: Long, requireReady: Boolean = true, navigationAllowed: Boolean = false, block: (WebView) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val id = sequence.incrementAndGet()
            pending[id] = { continuation.resumeWith(Result.failure(it)) }
            continuation.invokeOnCancellation { pending.remove(id) }
            try {
                Platform.runLater {
                    if (pending.remove(id) != null && continuation.isActive) {
                        continuation.resumeWith(runCatching {
                            checkCurrent(version)
                            val view = requireNotNull(web) { "页面尚未挂载" }
                            if (requireReady) check(trusted() && view.engine.loadWorker.state == Worker.State.SUCCEEDED) {
                                "可信功能页面尚未加载完成"
                            }
                            val result = block(view)
                            if (!navigationAllowed) checkCurrent(version)
                            result
                        })
                    }
                }
            } catch (failure: Throwable) {
                pending.remove(id)?.invoke(failure)
            }
        }
}

private class LimitedPngOutput : ByteArrayOutputStream() {
    fun base64(): String {
        val encoded = Base64.getEncoder().encode(ByteBuffer.wrap(buf, 0, count))
        return String(encoded.array(), encoded.arrayOffset(), encoded.remaining(), Charsets.US_ASCII)
    }
    override fun write(value: Int) {
        require(count < 16 * 1024 * 1024) { "PNG 截图超过 16 MiB" }
        super.write(value)
    }
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(length <= 16 * 1024 * 1024 - count) { "PNG 截图超过 16 MiB" }
        super.write(bytes, offset, length)
    }
}
