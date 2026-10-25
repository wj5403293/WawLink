package dev.wawlink.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.json.*
import org.luaj.vm2.*
import org.luaj.vm2.lib.DebugLib
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.CoerceJavaToLua
import org.luaj.vm2.lib.jse.JsePlatform
import java.util.Collections
import java.util.IdentityHashMap
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
    private var globalsGeneration = -1L // Worker-thread only.
    private var globals: Globals? = null
    private val context = webView.context
    private val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()

    fun install(css: String): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false
        WebViewCompat.addWebMessageListener(webView, "__wawlinkNative", setOf(origin)) { _, message, source, mainFrame, reply ->
            if (closed.get() || !mainFrame || source.toString().trimEnd('/') != origin) return@addWebMessageListener
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
                        "action" -> onMain(epoch) {
                            val action = args[0].jsonPrimitive.content
                            require(action in setOf("open", "start", "stop")) { "Unknown card action: $action" }
                            val callback = onAction ?: error("Card actions are unavailable on full pages")
                            callback(action); JsonNull
                        }
                        else -> onMain(epoch) { helper(op, args.map { (it as? JsonPrimitive)?.contentOrNull }); JsonNull }
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
        script = WebViewCompat.addDocumentStartJavaScript(webView, featureAndroidBootstrap() + ";" + featureWhenDocumentExists(containerFeatureThemeScript(css)), setOf(origin))
        return true
    }

    fun invalidate() {
        generation.incrementAndGet()
        document = null
        synchronized(tasks) { tasks.toList().forEach { it.cancel(true) }; tasks.clear() }
    }

    private fun ensureActive(epoch: Long) {
        if (closed.get() || epoch != generation.get() || Thread.currentThread().isInterrupted) throw FeatureDocumentDisposed()
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

    private fun <T> onMain(epoch: Long, block: () -> T): T {
        ensureActive(epoch)
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val task = FutureTask { ensureActive(epoch); block() }
        main.post(task)
        return try { task.get() } finally { task.cancel(false); main.removeCallbacks(task) }
    }

    private fun helper(op: String, args: List<String?>) {
        val target = activity ?: context
        fun launch(intent: Intent) {
            if (target !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            target.startActivity(intent)
        }
        when (op) {
            "openUrl", "openBrowser" -> {
                val uri = Uri.parse(requireNotNull(args.getOrNull(0)))
                val intent = if (op == "openBrowser") {
                    Intent(Intent.ACTION_VIEW, uri).apply {
                        addCategory(Intent.CATEGORY_BROWSABLE)
                        selector = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
                    }
                } else Intent(Intent.ACTION_VIEW, uri)
                launch(intent)
            }
            "share" -> {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, requireNotNull(args.getOrNull(0)))
                    args.getOrNull(1)?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
                }
                launch(Intent.createChooser(intent, args.getOrNull(1)))
            }
            "launchApp" -> launch(context.packageManager.getLaunchIntentForPackage(requireNotNull(args.getOrNull(0)))
                ?: error("No launch activity for package ${args.getOrNull(0)}"))
            else -> error("Unknown host operation: $op")
        }
    }

    private fun evaluate(source: String, epoch: Long): JsonElement {
        if (globalsGeneration != epoch) {
            globals = JsePlatform.standardGlobals().apply {
                load(object : DebugLib() {
                    override fun onInstruction(pc: Int, v: Varargs?, top: Int) {
                        ensureActive(epoch)
                        super.onInstruction(pc, v, top)
                    }
                })
                set("context", CoerceJavaToLua.coerce(context))
                set("activity", activity?.let(CoerceJavaToLua::coerce) ?: LuaValue.NIL)
                val host = LuaTable()
                host.set("context", get("context")); host.set("activity", get("activity"))
                val ui = object : VarArgFunction() {
                    override fun invoke(args: Varargs): Varargs = onMain(epoch) { args.checkfunction(1).invoke() }
                }
                host.set("runOnUiThread", ui); set("runOnUiThread", ui)
                listOf("openUrl", "openBrowser", "share", "launchApp").forEach { name ->
                    val function = object : VarArgFunction() {
                        override fun invoke(args: Varargs): Varargs {
                            onMain(epoch) { helper(name, (1..args.narg()).map { if (args.isnil(it)) null else args.checkjstring(it) }) }
                            return LuaValue.NIL
                        }
                    }
                    host.set(name, function); set(name, function)
                }
                set("host", host)
            }
            globalsGeneration = epoch
        }
        val result = requireNotNull(globals).load(source, "wawlink.lua").invoke()
        ensureActive(epoch)
        return when (result.narg()) {
            0 -> JsonNull
            1 -> luaJson(result.arg1(), IdentityHashMap())
            else -> JsonArray((1..result.narg()).map { luaJson(result.arg(it), IdentityHashMap()) })
        }
    }

    private fun luaJson(value: LuaValue, seen: IdentityHashMap<Any, Boolean>): JsonElement = when (value.type()) {
        LuaValue.TNIL -> JsonNull
        LuaValue.TBOOLEAN -> JsonPrimitive(value.toboolean())
        LuaValue.TNUMBER -> { val number = value.todouble(); require(number.isFinite()) { "Lua returned a non-finite number" }; JsonPrimitive(number) }
        LuaValue.TSTRING -> JsonPrimitive(value.tojstring())
        LuaValue.TTABLE -> {
            require(seen.put(value, true) == null) { "Lua returned a cyclic table" }
            try {
                val table = value.checktable()
                val keys = table.keys()
                val length = table.length()
                if (keys.isNotEmpty() && keys.size == length && keys.all { it.isinttype() && it.toint() in 1..length })
                    JsonArray((1..length).map { luaJson(table.rawget(it), seen) })
                else JsonObject(keys.associate {
                    require(it.type() == LuaValue.TSTRING) { "JSON object keys must be strings (or use a contiguous Lua array)" }
                    it.tojstring() to luaJson(table.rawget(it), seen)
                })
            } finally { seen.remove(value) }
        }
        LuaValue.TUSERDATA -> javaJson(value.touserdata(), seen)
        else -> error("Lua returned a non-JSON value: ${value.typename()}")
    }

    private fun javaJson(value: Any?, seen: IdentityHashMap<Any, Boolean>): JsonElement {
        if (value == null) return JsonNull
        return when (value) {
            is String -> JsonPrimitive(value)
            is Char -> JsonPrimitive(value.toString())
            is Boolean -> JsonPrimitive(value)
            is Number -> { require(value.toDouble().isFinite()) { "Non-finite Java number" }; JsonPrimitive(value) }
            else -> {
                require(seen.put(value, true) == null) { "Cyclic Java return value" }
                try {
                    when {
                        value is Map<*, *> -> JsonObject(value.entries.associate { require(it.key is String) { "JSON map keys must be strings" }; it.key as String to javaJson(it.value, seen) })
                        value is Iterable<*> -> JsonArray(value.map { javaJson(it, seen) })
                        value.javaClass.isArray -> JsonArray((0 until java.lang.reflect.Array.getLength(value)).map { javaJson(java.lang.reflect.Array.get(value, it), seen) })
                        else -> error("Java return value is not JSON-compatible: ${value.javaClass.name}")
                    }
                } finally { seen.remove(value) }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        invalidate()
        script?.remove(); script = null
        if (listenerInstalled) WebViewCompat.removeWebMessageListener(webView, "__wawlinkNative")
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
    }
}

/** Not caught by Lua pcall: disposal must also terminate loops which catch ordinary Lua errors. */
private class FeatureDocumentDisposed : Error("Document disposed")
