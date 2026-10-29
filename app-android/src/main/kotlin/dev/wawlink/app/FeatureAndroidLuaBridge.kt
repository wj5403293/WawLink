package dev.wawlink.app

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.json.*
import java.util.Collections
import java.util.concurrent.FutureTask
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Trusted host access. There is intentionally no API whitelist beyond Android's own permissions. */
internal class FeatureAndroidLuaBridge(
    private val webView: WebView,
    private val origin: String,
    private val onAction: ((String) -> Unit)?,
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "wawlink-feature-lua").apply { isDaemon = true } }
    private val closed = AtomicBoolean()
    private val generation = AtomicLong()
    private val tasks = Collections.synchronizedSet(mutableSetOf<FutureTask<Unit>>())
    private var document: String? = null // UI-thread only.
    private var script: ScriptHandler? = null
    private var listenerInstalled = false
    private val runtime = FeatureAndroidLuaRuntime(webView.context)
    private val sessionLock = Any()
    private var session: FeatureAndroidLuaRuntime.Session? = null

    fun install(initialization: String): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false
        WebViewCompat.addWebMessageListener(webView, "__wawlinkNative", setOf(origin)) { _, message, source, mainFrame, reply ->
            if (closed.get() || !mainFrame || !featureSameOrigin(source.toString(), origin)) return@addWebMessageListener
            val request = runCatching { Json.parseToJsonElement(message.data ?: "").jsonObject }.getOrNull() ?: return@addWebMessageListener
            val token = (request["document"] as? JsonPrimitive)?.contentOrNull ?: return@addWebMessageListener
            val op = (request["op"] as? JsonPrimitive)?.contentOrNull ?: return@addWebMessageListener
            if (op == "ready") { invalidate(); document = token; return@addWebMessageListener }
            if (token != document) return@addWebMessageListener
            val id = (request["id"] as? JsonPrimitive)?.contentOrNull ?: return@addWebMessageListener
            val args = request["args"] as? JsonArray ?: return@addWebMessageListener
            val epoch = generation.get()
            val task = object : FutureTask<Unit>({
                try {
                    ensureActive(epoch)
                    val result = when (op) {
                        "lua" -> evaluate(args[0].jsonPrimitive.content, epoch)
                        "action" -> runtime.onMain({ ensureActive(epoch) }) {
                            val action = args[0].jsonPrimitive.content
                            require(action in setOf("open", "start", "stop")) { "Unknown card action: $action" }
                            val callback = onAction ?: error("Card actions are unavailable on full pages")
                            callback(action); JsonNull
                        }
                        else -> runtime.onMain({ ensureActive(epoch) }) { runtime.helper(op, args.map { (it as? JsonPrimitive)?.contentOrNull }); JsonNull }
                    }
                    respond(reply, token, id, epoch, result, null)
                } catch (failure: Throwable) {
                    respond(reply, token, id, epoch, null, failure.cause?.message ?: failure.message ?: failure.javaClass.simpleName)
                }
                Unit
            }) {
                override fun done() { tasks.remove(this) }
            }
            tasks.add(task)
            worker.execute(task)
        }
        listenerInstalled = true
        script = WebViewCompat.addDocumentStartJavaScript(webView,
            "if(window===window.top){" + featureAndroidBootstrap() + ";" + initialization + "}", setOf(origin))
        return true
    }

    fun invalidate() {
        generation.incrementAndGet()
        document = null
        synchronized(tasks) { tasks.toList().forEach { it.cancel(true) }; tasks.clear() }
        synchronized(sessionLock) { session?.close(); session = null }
    }

    private fun ensureActive(epoch: Long) {
        if (closed.get() || epoch != generation.get() || Thread.currentThread().isInterrupted) throw FeatureLuaDisposed()
    }

    private fun respond(reply: JavaScriptReplyProxy, token: String, id: String, epoch: Long, value: JsonElement?, error: String?) {
        main.post {
            if (!closed.get() && generation.get() == epoch && document == token) {
                val response = buildJsonObject {
                    put("document", token); put("id", id); put("ok", error == null)
                    if (error == null) put("value", value ?: JsonNull) else put("error", error)
                }
                runCatching { reply.postMessage(response.toString()) } // The renderer may have detached after the generation check.
            }
        }
    }

    private fun evaluate(source: String, epoch: Long): JsonElement {
        val current = synchronized(sessionLock) {
            ensureActive(epoch)
            session ?: runtime.newSession { ensureActive(epoch) }.also { session = it }
        }
        return current.evaluate(source)
    }

    override fun close() = close(rendererGone = false)

    fun close(rendererGone: Boolean) {
        if (!closed.compareAndSet(false, true)) return
        invalidate()
        try {
            if (!rendererGone) {
                script?.remove()
                if (listenerInstalled) WebViewCompat.removeWebMessageListener(webView, "__wawlinkNative")
            }
        } finally {
            script = null
            listenerInstalled = false
            worker.shutdownNow()
            runtime.close()
            main.removeCallbacksAndMessages(null)
        }
    }
}

