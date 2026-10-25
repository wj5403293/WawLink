package dev.wawlink.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.serialization.json.*
import org.luaj.vm2.*
import org.luaj.vm2.lib.DebugLib
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.CoerceJavaToLua
import org.luaj.vm2.lib.jse.JsePlatform
import java.util.IdentityHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean

/** Shared trusted engine for HTML documents and native actions; no Java API whitelist. */
internal class FeatureAndroidLuaRuntime(context: Context) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean()
    @Volatile private var context: Context? = context
    @Volatile private var activity: Activity? = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>().firstOrNull()

    private fun checkActive(ensureActive: () -> Unit) {
        if (closed.get() || Thread.currentThread().isInterrupted) throw FeatureLuaDisposed()
        ensureActive()
    }

    fun <T> onMain(ensureActive: () -> Unit, block: () -> T): T {
        checkActive(ensureActive)
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val task = FutureTask { checkActive(ensureActive); block() }
        check(main.post(task)) { "Android UI thread is unavailable" }
        return try { task.get() } finally { task.cancel(false); main.removeCallbacks(task) }
    }

    fun helper(op: String, args: List<String?>) {
        val context = checkNotNull(context) { "Lua runtime disposed" }
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

    /** A session owns globals: one per HTML document, but a fresh one for each native invocation. */
    fun newSession(ensureActive: () -> Unit): Session {
        checkActive(ensureActive)
        val context = checkNotNull(context) { "Lua runtime disposed" }
        val activity = activity
        val globals = JsePlatform.standardGlobals().apply {
            load(object : DebugLib() {
                override fun onInstruction(pc: Int, v: Varargs?, top: Int) {
                    checkActive(ensureActive)
                    super.onInstruction(pc, v, top)
                }
            })
            set("context", CoerceJavaToLua.coerce(context))
            set("activity", activity?.let(CoerceJavaToLua::coerce) ?: LuaValue.NIL)
            val host = LuaTable()
            host.set("context", get("context")); host.set("activity", get("activity"))
            val ui = object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs = onMain(ensureActive) { args.checkfunction(1).invoke() }
            }
            host.set("runOnUiThread", ui); set("runOnUiThread", ui)
            listOf("openUrl", "openBrowser", "share", "launchApp").forEach { name ->
                val function = object : VarArgFunction() {
                    override fun invoke(args: Varargs): Varargs {
                        onMain(ensureActive) { helper(name, (1..args.narg()).map { if (args.isnil(it)) null else args.checkjstring(it) }) }
                        return LuaValue.NIL
                    }
                }
                host.set(name, function); set(name, function)
            }
            set("host", host)
        }
        checkActive(ensureActive)
        return Session(globals, ensureActive)
    }

    inner class Session internal constructor(globals: Globals, private val ensureActive: () -> Unit) : AutoCloseable {
        @Volatile private var globals: Globals? = globals

        fun evaluate(source: String): JsonElement {
            checkActive(ensureActive)
            val globals = this.globals ?: throw FeatureLuaDisposed()
            val result = globals.load(source, "wawlink.lua").invoke()
            checkActive(ensureActive)
            return when (result.narg()) {
                0 -> JsonNull
                1 -> luaJson(result.arg1(), IdentityHashMap())
                else -> JsonArray((1..result.narg()).map { luaJson(result.arg(it), IdentityHashMap()) })
            }
        }

        override fun close() { globals = null }
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
        context = null; activity = null
        main.removeCallbacksAndMessages(null)
    }
}

/** Not caught by Lua pcall: disposal must terminate loops that catch ordinary Lua errors. */
internal class FeatureLuaDisposed : Error("Lua execution disposed")
