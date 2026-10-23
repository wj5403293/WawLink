package dev.wawlink.ui

import android.graphics.Typeface
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import io.github.rosemoe.sora.lang.styling.MappedSpans
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import java.util.TreeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Composable
internal actual fun PlatformCodeDetail(onBack: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onBack, properties = DialogProperties(
        dismissOnBackPress = true, dismissOnClickOutside = false,
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
    )) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.apply {
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            }
        }
        content()
    }
}

@Composable
internal actual fun PlatformCodeViewer(text: String, language: String?, modifier: Modifier, selectionRequest: Int) {
    val context = LocalContext.current
    val colors = LocalAgentColors.current
    val highlighted = rememberHighlightedCode(text, language)
    val editor = remember(context) {
        CodeEditor(context).apply {
            isEditable = false
            isWordwrap = false
            isLineNumberEnabled = true
            setTypefaceText(Typeface.MONOSPACE)
            setTextSize(15f)
            setText(text)
        }
    }
    val applied = remember(editor) { AppliedSoraState(text) }
    var styles by remember(highlighted) { mutableStateOf<PreparedSoraStyles?>(null) }
    LaunchedEffect(highlighted) {
        styles = withContext(Dispatchers.Default) { soraStyles(highlighted) }
    }
    DisposableEffect(editor) { onDispose { editor.release() } }
    LaunchedEffect(editor, selectionRequest) {
        if (selectionRequest > 0) editor.selectAll()
    }
    AndroidView(factory = { editor }, modifier = modifier, update = { view ->
        if (applied.text != text) {
            view.setText(text)
            applied.text = text
            applied.styles = null
        }
        val scheme = view.colorScheme
        scheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, colors.surface.toArgb())
        scheme.setColor(EditorColorScheme.TEXT_NORMAL, colors.onSurface.toArgb())
        scheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, colors.surfaceContainerLow.toArgb())
        scheme.setColor(EditorColorScheme.LINE_NUMBER, colors.outline.toArgb())
        scheme.setColor(EditorColorScheme.LINE_NUMBER_CURRENT, colors.primary.toArgb())
        scheme.setColor(EditorColorScheme.LINE_DIVIDER, colors.outlineVariant.toArgb())
        scheme.setColor(EditorColorScheme.CURRENT_LINE, colors.surfaceContainerLow.toArgb())
        scheme.setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, colors.primaryContainer.toArgb())
        scheme.setColor(EditorColorScheme.TEXT_SELECTED, colors.onPrimaryContainer.toArgb())
        scheme.setColor(EditorColorScheme.SELECTION_HANDLE, colors.primary.toArgb())
        scheme.setColor(EditorColorScheme.TEXT_ACTION_WINDOW_BACKGROUND, colors.surfaceContainerHigh.toArgb())
        scheme.setColor(EditorColorScheme.TEXT_ACTION_WINDOW_ICON_COLOR, colors.onSurface.toArgb())
        val prepared = styles
        if (applied.styles !== prepared) {
            // IDs are allocated per distinct color, not per token, and never shared between editors.
            prepared?.palette?.forEach { (rgb, id) -> scheme.setColor(id, rgb) }
            view.setStyles(prepared?.styles)
            applied.styles = prepared
        }
    })
}

private class AppliedSoraState(var text: String, var styles: PreparedSoraStyles? = null)
private data class PreparedSoraStyles(val styles: Styles, val palette: Map<Int, Int>)

/** Sweep overlapping library ranges with the same last-span-wins precedence as AnnotatedString. */
private suspend fun soraStyles(code: AnnotatedString): PreparedSoraStyles {
    data class Event(val offset: Int, val index: Int, val start: Boolean)
    val palette = LinkedHashMap<Int, Int>()
    val colorIds = IntArray(code.spanStyles.size)
    val events = ArrayList<Event>(code.spanStyles.size * 2)
    code.spanStyles.forEachIndexed { index, span ->
        val rgb = span.item.color.toArgb()
        colorIds[index] = palette.getOrPut(rgb) { 256 + palette.size }
        events += Event(span.start, index, true)
        events += Event(span.end, index, false)
    }
    events.sortWith(compareBy<Event> { it.offset }.thenBy { it.start })
    val active = TreeMap<Int, Int>()
    val builder = MappedSpans.Builder()
    var offset = 0
    var line = 0
    var column = 0
    var cursor = 0
    var previous = -1
    while (offset <= code.length) {
        if (offset % 1024 == 0) kotlinx.coroutines.currentCoroutineContext().ensureActive()
        while (cursor < events.size && events[cursor].offset == offset) {
            val event = events[cursor++]
            if (event.start) active[event.index] = colorIds[event.index] else active.remove(event.index)
        }
        val color = active.lastEntry()?.value ?: EditorColorScheme.TEXT_NORMAL
        if (column == 0 || color != previous) builder.addIfNeeded(line, column, color.toLong())
        previous = color
        if (offset == code.length) break
        if (code[offset] == '\n') { line++; column = 0 } else column++
        offset++
    }
    builder.determine(line)
    return PreparedSoraStyles(Styles(builder.build()).apply { finishBuilding() }, palette)
}
