package dev.wawlink.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import com.termux.terminal.TerminalSession as EmulatorSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dev.wawlink.runtime.TerminalSession

/** Love2Droid's pinned terminal host/key layout, using WawLink's runtime-owned PTY. */
@Composable
internal fun TerminalSurface(session: TerminalSession, modifier: Modifier) {
    val context = LocalContext.current
    val controller = remember(session, context) { AndroidTerminalController(context, session) }
    val alive by session.isAlive.collectAsState()
    val orientation = LocalConfiguration.current.orientation
    DisposableEffect(controller) { onDispose { controller.detach() } }
    AndroidView(factory = { controller.host }, modifier = modifier, update = { controller.updateKeys(alive, orientation) })
}

private class AndroidTerminalController(context: Context, private val transport: TerminalSession) : TerminalSessionClient, TerminalViewClient {
    private var ctrl = false
    private var alt = false
    private var disposed = false
    private var fontSize = 12f * context.resources.displayMetrics.scaledDensity
    private val typeface = requireNotNull(ResourcesCompat.getFont(context, R.font.maple_mono_nf_cn_regular))
    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val input = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private val keys = mutableListOf<TextView>()
    private var ctrlButton: TextView? = null
    private var altButton: TextView? = null
    val view = TerminalView(context, null)
    val host = LinearLayout(context)
    private val emulator = EmulatorSession(transport.process, transport.processId.toInt(), transport::resize, 10_000, this)
    private val windowFocusListener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused ->
        if (focused) view.post { connectInput() } else view.setTerminalCursorBlinkerState(false, false)
    }
    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(attached: View) {
            view.viewTreeObserver.addOnWindowFocusChangeListener(windowFocusListener)
            view.post { connectInput() }
        }
        override fun onViewDetachedFromWindow(detached: View) {
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(windowFocusListener)
            view.setTerminalCursorBlinkerState(false, false)
        }
    }

    init {
        view.setTerminalViewClient(this)
        view.addOnAttachStateChangeListener(attachListener)
        // XML supplied both flags upstream; a programmatically constructed TerminalView has neither.
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.setBackgroundColor(Color.BLACK)
        view.setTextSize(fontSize.toInt())
        view.setTypeface(typeface)
        view.keepScreenOn = true
        view.setTerminalCursorBlinkerRate(500)
        view.attachSession(emulator)
        host.orientation = LinearLayout.VERTICAL
        host.setBackgroundColor(Color.BLACK)
        host.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF2D2D2D.toInt())
            setPadding(dp(4), dp(3), dp(4), dp(3))
            // Touching an extra key must not replace the terminal's InputConnection.
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        bar.addView(keyRow(listOf(
            TerminalKey("ESC", "\u001b"), TerminalKey("/", "/"), TerminalKey("—", "-"),
            TerminalKey("HOME", "\u001b[H"), TerminalKey("↑", "\u001b[A"),
            TerminalKey("END", "\u001b[F"), TerminalKey("PGUP", "\u001b[5~"),
        )))
        bar.addView(View(context), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        bar.addView(keyRow(listOf(
            TerminalKey("⇥", "\t"), TerminalKey("CTRL", modifier = KeyEvent.KEYCODE_CTRL_LEFT),
            TerminalKey("ALT", modifier = KeyEvent.KEYCODE_ALT_LEFT), TerminalKey("←", "\u001b[D"),
            TerminalKey("↓", "\u001b[B"), TerminalKey("→", "\u001b[C"), TerminalKey("PGDN", "\u001b[6~"),
        )))
        host.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun dp(value: Int) = (value * view.resources.displayMetrics.density + .5f).toInt()
    private fun keyRow(row: List<TerminalKey>) = LinearLayout(view.context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        row.forEach { key ->
            val button = TextView(context).apply {
                text = key.label
                contentDescription = if (key.label == "⇥") "TAB" else key.label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 11f else 12f)
                typeface = this@AndroidTerminalController.typeface
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { setColor(KEY_COLOR); cornerRadius = dp(4).toFloat() }
                isFocusable = false
                isFocusableInTouchMode = false
                layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(1); marginEnd = dp(1) }
                setOnClickListener {
                    when (key.modifier) {
                        KeyEvent.KEYCODE_CTRL_LEFT -> { ctrl = !ctrl; updateModifiers() }
                        KeyEvent.KEYCODE_ALT_LEFT -> { alt = !alt; updateModifiers() }
                        else -> emulator.write(key.sequence)
                    }
                    view.requestFocus()
                }
            }
            when (key.modifier) {
                KeyEvent.KEYCODE_CTRL_LEFT -> ctrlButton = button
                KeyEvent.KEYCODE_ALT_LEFT -> altButton = button
            }
            keys += button
            addView(button)
        }
    }

    private fun updateModifiers() {
        (ctrlButton?.background as? GradientDrawable)?.setColor(if (ctrl) MODIFIER_COLOR else KEY_COLOR)
        (altButton?.background as? GradientDrawable)?.setColor(if (alt) MODIFIER_COLOR else KEY_COLOR)
    }
    fun updateKeys(alive: Boolean, orientation: Int) {
        val size = if (orientation == Configuration.ORIENTATION_LANDSCAPE) 11f else 12f
        keys.forEach { it.isEnabled = alive; it.setTextSize(TypedValue.COMPLEX_UNIT_SP, size) }
    }
    private fun connectInput() {
        if (disposed || !view.isAttachedToWindow || !view.hasWindowFocus()) return
        if (view.requestFocus()) {
            // Run after attachment/window focus, not while AndroidView.factory is still constructing.
            input.restartInput(view)
            view.setTerminalCursorBlinkerState(true, true)
        }
    }
    private fun keyboard() {
        view.post {
            connectInput()
            if (!disposed && view.isAttachedToWindow && view.hasWindowFocus() && view.hasFocus()) {
                input.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }
    private fun paste() {
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount > 0) emulator.write(clip.getItemAt(0).coerceToText(view.context).toString())
        view.requestFocus()
    }
    fun detach() {
        if (disposed) return
        disposed = true
        view.removeOnAttachStateChangeListener(attachListener)
        if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(windowFocusListener)
        view.keepScreenOn = false
        view.stopTextSelectionMode()
        view.setTerminalCursorBlinkerState(false, false)
        input.hideSoftInputFromWindow(view.windowToken, 0)
        transport.close()
    }
    override fun onTextChanged(changedSession: EmulatorSession) { if (!disposed) view.onScreenUpdated() }
    override fun onTitleChanged(changedSession: EmulatorSession) = Unit
    override fun onSessionFinished(finishedSession: EmulatorSession) { if (!disposed) view.onScreenUpdated() }
    override fun onCopyTextToClipboard(session: EmulatorSession, text: String?) { clipboard.setPrimaryClip(ClipData.newPlainText("终端", text.orEmpty())) }
    override fun onPasteTextFromClipboard(session: EmulatorSession?) = paste()
    override fun onBell(session: EmulatorSession) = Unit
    override fun onColorsChanged(session: EmulatorSession) { if (!disposed) view.invalidate() }
    override fun onTerminalCursorStateChange(state: Boolean) { if (!disposed) view.setTerminalCursorBlinkerState(state, true) }
    override fun setTerminalShellPid(session: EmulatorSession, pid: Int) = Unit
    override fun getTerminalCursorStyle(): Int = 0
    override fun onScale(scale: Float): Float {
        fontSize = (fontSize * scale).coerceIn(8f * view.resources.displayMetrics.scaledDensity, 32f * view.resources.displayMetrics.scaledDensity)
        view.setTextSize(fontSize.toInt())
        return 1f
    }
    override fun onSingleTapUp(event: MotionEvent?) = keyboard()
    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = false
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = !disposed && view.isAttachedToWindow && view.isShown && view.hasFocus()
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, event: KeyEvent?, session: EmulatorSession?) = false
    override fun onKeyUp(keyCode: Int, event: KeyEvent?) = false
    override fun onLongPress(event: MotionEvent?) = false
    override fun readControlKey(): Boolean = ctrl.also { ctrl = false; updateModifiers() }
    override fun readAltKey(): Boolean = alt.also { alt = false; updateModifiers() }
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: EmulatorSession?) = false
    override fun onEmulatorSet() { if (!disposed) view.setTerminalCursorBlinkerState(true, true) }
    override fun logError(tag: String?, message: String?) { Log.e(tag ?: "Terminal", message.orEmpty()) }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: "Terminal", message.orEmpty()) }
    override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: "Terminal", message.orEmpty()) }
    override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: "Terminal", message.orEmpty()) }
    override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: "Terminal", message.orEmpty()) }
    override fun logStackTraceWithMessage(tag: String?, message: String?, error: Exception?) { Log.e(tag ?: "Terminal", message.orEmpty(), error) }
    override fun logStackTrace(tag: String?, error: Exception?) { Log.e(tag ?: "Terminal", "终端错误", error) }

    private data class TerminalKey(val label: String, val sequence: String = "", val modifier: Int? = null)
    private companion object {
        const val KEY_COLOR = 0xFF424242.toInt()
        const val MODIFIER_COLOR = 0xFF5C6BC0.toInt()
    }
}
