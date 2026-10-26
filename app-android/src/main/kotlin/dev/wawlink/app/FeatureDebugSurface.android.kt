package dev.wawlink.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import dev.wawlink.runtime.*
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Operates only on the already-mounted trusted WebView; navigation invalidates every pending reply. */
internal class AndroidFeatureDebugPage(
    private val view: WebView,
    private val trusted: () -> Boolean,
    private val reload: (String?) -> Unit,
) : FeatureDebugPage, AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean()
    private val epoch = AtomicLong()
    private val nativeErrors = ArrayDeque<JsonObject>() // Main-thread only.
    fun navigating() { epoch.incrementAndGet(); nativeErrors.clear() }
    fun recordError(message: String) {
        if (nativeErrors.size == 80) nativeErrors.removeFirst()
        nativeErrors.addLast(buildJsonObject { put("time", System.currentTimeMillis()); put("message", message.take(4096)) })
    }

    override suspend fun request(operation: String, expression: String?): JsonElement {
        val generation = epoch.get()
        return withTimeout(12_000) {
            when (operation) {
                "inspect" -> {
                    val native = onMain(generation) {
                        buildJsonObject {
                            put("width", view.width); put("height", view.height)
                            put("attached", view.isAttachedToWindow); put("visible", visible())
                        }
                    }
                    val layout = json(FEATURE_DEBUG_INSPECT_SCRIPT, generation)
                    buildJsonObject { put("native", native); put("layout", layout) }
                }
                "logs" -> {
                    val browser = json(FEATURE_DEBUG_LOGS_SCRIPT, generation).jsonObject
                    val errors = onMain(generation) { JsonArray(nativeErrors.toList()) }
                    buildJsonObject { browser.forEach { (key, value) -> put(key, value) }; put("nativeErrors", errors) }
                }
                "eval" -> evaluate(requireNotNull(expression) { "缺少 JavaScript 表达式" }, generation)
                "screenshot" -> screenshot(generation)
                "reload" -> onMain(generation) {
                    reload(expression)
                    buildJsonObject { put("reloaded", true) }
                }
                else -> error("不支持的页面调试操作：$operation")
            }
        }
    }

    private fun visible(): Boolean = view.isShown && view.getGlobalVisibleRect(Rect())

    private suspend fun evaluate(expression: String, generation: Long): JsonElement {
        val id = UUID.randomUUID().toString()
        try {
            javascript(featureDebugEvalStart(id, expression), generation)
            while (true) {
                val result = json(featureDebugEvalPoll(id), generation).jsonObject
                if (result["done"]?.jsonPrimitive?.booleanOrNull == true) {
                    check(result["ok"]?.jsonPrimitive?.booleanOrNull == true) {
                        result["error"]?.jsonPrimitive?.contentOrNull ?: "JavaScript 执行失败"
                    }
                    return result["value"] ?: JsonNull
                }
                delay(25)
            }
        } finally {
            // Do not run cleanup in the replacement document after navigation.
            main.post { if (!closed.get() && epoch.get() == generation && trusted()) view.evaluateJavascript(featureDebugEvalCancel(id), null) }
        }
    }

    private suspend fun screenshot(generation: Long): JsonObject {
        val bitmap = onMain(generation) {
            val width = view.width; val height = view.height
            check(visible()) { "页面当前不可见，无法截取真实画面" }
            require(width > 0 && height > 0 && width.toLong() * height <= 8_000_000) { "页面尺寸无效或超过 800 万像素" }
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { image ->
                try { view.draw(Canvas(image)) } catch (failure: Throwable) { image.recycle(); throw failure }
            }
        }
        try {
            return withContext(Dispatchers.Default) {
                val base64 = LimitedAndroidPngOutput().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG 编码失败" }
                    output.base64()
                }
                ensureCurrent(generation)
                buildJsonObject {
                    put("mimeType", "image/png"); put("width", bitmap.width); put("height", bitmap.height)
                    put("base64", base64)
                }
            }
        } finally { bitmap.recycle() }
    }

    private suspend fun json(script: String, generation: Long): JsonElement {
        val encoded = Json.parseToJsonElement(javascript(script, generation)) as? JsonPrimitive
        val value = encoded?.takeIf { it.isString }?.content ?: error("页面未返回调试数据，可能仍在加载")
        return Json.parseToJsonElement(value)
    }

    private suspend fun javascript(script: String, generation: Long): String = suspendCancellableCoroutine { continuation ->
        main.post {
            if (!continuation.isActive) return@post
            try {
                ensureCurrent(generation)
                check(trusted()) { "页面尚未就绪或已离开受信来源" }
                view.evaluateJavascript(script) { value ->
                    if (continuation.isActive) try {
                        ensureCurrent(generation)
                        continuation.resume(value ?: "null")
                    } catch (failure: Throwable) { continuation.resumeWithException(failure) }
                }
            } catch (failure: Throwable) { if (continuation.isActive) continuation.resumeWithException(failure) }
        }
    }

    private suspend fun <T> onMain(generation: Long, action: () -> T): T = suspendCancellableCoroutine { continuation ->
        main.post {
            if (!continuation.isActive) return@post
            try {
                ensureCurrent(generation)
                check(trusted()) { "页面尚未就绪或已离开受信来源" }
                continuation.resume(action())
            } catch (failure: Throwable) { if (continuation.isActive) continuation.resumeWithException(failure) }
        }
    }

    private fun ensureCurrent(generation: Long) {
        check(!closed.get() && epoch.get() == generation) { "页面已关闭或导航，调试请求已失效" }
    }

    override fun close() { closed.set(true); epoch.incrementAndGet() }
}

private class LimitedAndroidPngOutput : ByteArrayOutputStream() {
    fun base64(): String {
        val encoded = Base64.getEncoder().encode(ByteBuffer.wrap(buf, 0, count))
        return String(encoded.array(), encoded.arrayOffset(), encoded.remaining(), Charsets.US_ASCII)
    }
    override fun write(value: Int) {
        require(count < 16 * 1024 * 1024) { "截图超过 16 MiB" }
        super.write(value)
    }
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(length <= 16 * 1024 * 1024 - count) { "截图超过 16 MiB" }
        super.write(bytes, offset, length)
    }
}
