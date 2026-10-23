package dev.wawlink.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession as EmulatorSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dev.wawlink.runtime.TerminalSession

/** Direct Termux terminal view integration, adapted from the pinned Love2Droid host. */
@Composable
internal fun TerminalSurface(session: TerminalSession, modifier: Modifier) {
    val context = LocalContext.current
    val controller = remember(session, context) { AndroidTerminalController(context, session) }
    val alive by session.isAlive.collectAsState()
    DisposableEffect(controller) { onDispose { controller.detach() } }
    Column(modifier) {
        AndroidView(factory = { controller.view }, modifier = Modifier.weight(1f).fillMaxWidth())
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton({ controller.keyboard() }) { Text("键盘") }
            TextButton({ controller.copySelection() }) { Text("复制选区") }
            TextButton({ controller.paste() }, enabled = alive) { Text("粘贴") }
            Text("长按选择文本", Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val first = listOf("ESC" to KeyEvent.KEYCODE_ESCAPE, "/" to KeyEvent.KEYCODE_SLASH, "—" to KeyEvent.KEYCODE_MINUS, "HOME" to KeyEvent.KEYCODE_MOVE_HOME, "↑" to KeyEvent.KEYCODE_DPAD_UP, "END" to KeyEvent.KEYCODE_MOVE_END, "PGUP" to KeyEvent.KEYCODE_PAGE_UP)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            first.forEach { (label, code) -> OutlinedButton({ controller.key(code) }, enabled = alive, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(label) } }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton({ controller.key(KeyEvent.KEYCODE_TAB) }, enabled = alive, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("TAB") }
            FilterChip(controller.ctrl, { controller.ctrl = !controller.ctrl; controller.view.requestFocus() }, { Text("CTRL") }, enabled = alive)
            FilterChip(controller.alt, { controller.alt = !controller.alt; controller.view.requestFocus() }, { Text("ALT") }, enabled = alive)
            listOf("←" to KeyEvent.KEYCODE_DPAD_LEFT, "↓" to KeyEvent.KEYCODE_DPAD_DOWN, "→" to KeyEvent.KEYCODE_DPAD_RIGHT, "PGDN" to KeyEvent.KEYCODE_PAGE_DOWN).forEach { (label, code) ->
                OutlinedButton({ controller.key(code) }, enabled = alive, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(label) }
            }
        }
    }
}

private class AndroidTerminalController(context: Context, private val transport: TerminalSession) : TerminalSessionClient, TerminalViewClient {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)
    private var fontSize = 14f * context.resources.displayMetrics.scaledDensity
    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val input = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    val view = TerminalView(context, null)
    private val emulator = EmulatorSession(transport.process, transport.processId.toInt(), transport::resize, 10_000, this)
    init {
        view.setTerminalViewClient(this)
        view.setBackgroundColor(android.graphics.Color.rgb(13, 17, 23))
        view.setTextSize(fontSize.toInt())
        view.setTypeface(Typeface.MONOSPACE)
        view.setTerminalCursorBlinkerRate(500)
        view.attachSession(emulator)
        view.requestFocus()
    }
    fun keyboard() { view.requestFocus(); input.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT) }
    fun copySelection() {
        val selected = view.selectedText.orEmpty()
        if (selected.isNotEmpty()) {
            clipboard.setPrimaryClip(ClipData.newPlainText("终端选区", selected))
            view.stopTextSelectionMode()
        }
    }
    fun paste() {
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount > 0) emulator.emulator?.paste(clip.getItemAt(0).coerceToText(view.context).toString())
        view.requestFocus()
    }
    fun key(code: Int) {
        val modifiers = (if (ctrl) KeyEvent.META_CTRL_ON else 0) or (if (alt) KeyEvent.META_ALT_ON else 0)
        ctrl = false; alt = false
        val event = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, modifiers)
        view.onKeyDown(code, event)
        view.onKeyUp(code, KeyEvent.changeAction(event, KeyEvent.ACTION_UP))
        view.requestFocus()
    }
    fun detach() {
        view.stopTextSelectionMode()
        view.setTerminalCursorBlinkerState(false, false)
        input.hideSoftInputFromWindow(view.windowToken, 0)
        transport.close()
    }
    override fun onTextChanged(changedSession: EmulatorSession) { view.onScreenUpdated() }
    override fun onTitleChanged(changedSession: EmulatorSession) = Unit
    override fun onSessionFinished(finishedSession: EmulatorSession) { view.onScreenUpdated() }
    override fun onCopyTextToClipboard(session: EmulatorSession, text: String?) { clipboard.setPrimaryClip(ClipData.newPlainText("终端", text.orEmpty())) }
    override fun onPasteTextFromClipboard(session: EmulatorSession?) = paste()
    override fun onBell(session: EmulatorSession) = Unit
    override fun onColorsChanged(session: EmulatorSession) { view.invalidate() }
    override fun onTerminalCursorStateChange(state: Boolean) { view.setTerminalCursorBlinkerState(state, true) }
    override fun setTerminalShellPid(session: EmulatorSession, pid: Int) = Unit
    override fun getTerminalCursorStyle(): Int = 0
    override fun onScale(scale: Float): Float {
        fontSize = (fontSize * scale).coerceIn(8f * view.resources.displayMetrics.scaledDensity, 32f * view.resources.displayMetrics.scaledDensity)
        view.setTextSize(fontSize.toInt())
        return 1f
    }
    override fun onSingleTapUp(event: MotionEvent?) { view.requestFocus(); input.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT) }
    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = false
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = view.isShown
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, event: KeyEvent?, session: EmulatorSession?) = false
    override fun onKeyUp(keyCode: Int, event: KeyEvent?) = false
    override fun onLongPress(event: MotionEvent?) = false
    override fun readControlKey(): Boolean = ctrl.also { ctrl = false }
    override fun readAltKey(): Boolean = alt.also { alt = false }
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: EmulatorSession?) = false
    override fun onEmulatorSet() { view.setTerminalCursorBlinkerState(true, true) }
    override fun logError(tag: String?, message: String?) { Log.e(tag ?: "Terminal", message.orEmpty()) }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: "Terminal", message.orEmpty()) }
    override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: "Terminal", message.orEmpty()) }
    override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: "Terminal", message.orEmpty()) }
    override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: "Terminal", message.orEmpty()) }
    override fun logStackTraceWithMessage(tag: String?, message: String?, error: Exception?) { Log.e(tag ?: "Terminal", message.orEmpty(), error) }
    override fun logStackTrace(tag: String?, error: Exception?) { Log.e(tag ?: "Terminal", "终端错误", error) }
}
