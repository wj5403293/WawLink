package dev.wawlink.ui

import android.graphics.Typeface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal actual fun PlatformCodeEditor(text: String, language: String?, onChange: (String) -> Unit, modifier: Modifier) {
    val context = rememberAgentViewContext()
    val colors = LocalAgentColors.current
    val dark = colors.surface.luminance() < 0.5f
    val change by rememberUpdatedState(onChange)
    val editor = remember(context) {
        CodeEditor(context).apply {
            isEditable = true
            isWordwrap = false
            isLineNumberEnabled = true
            setTypefaceText(Typeface.MONOSPACE)
            setTextSize(15f)
            setText(text)
        }
    }
    var appliedText by remember(editor) { mutableStateOf(text) }
    var applying by remember(editor) { mutableStateOf(false) }
    var tokenizer by remember(language, dark) { mutableStateOf<OfflineTextMateTokenizer?>(null) }
    var appliedTokenizer by remember(editor) { mutableStateOf<OfflineTextMateTokenizer?>(null) }
    LaunchedEffect(language, dark) {
        tokenizer = if (language?.let(::canonicalCodeLanguage) != null) withContext(Dispatchers.Default) {
            BundledTextMate.tokenizer(dark).also { prepared -> language?.let(prepared::grammar) }
        } else null
    }
    DisposableEffect(editor) {
        val receipt = editor.subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
            if (!applying) {
                val value = editor.text.toString()
                appliedText = value
                change(value)
            }
        }
        onDispose { receipt.unsubscribe(); editor.release() }
    }
    key(editor) { AndroidView(factory = { editor }, modifier = modifier, update = { view ->
        if (appliedText != text) {
            val cursor = view.cursor
            val leftLine = cursor.leftLine
            val leftColumn = cursor.leftColumn
            val rightLine = cursor.rightLine
            val rightColumn = cursor.rightColumn
            applying = true
            view.setText(text)
            val last = view.text.lineCount - 1
            val startLine = leftLine.coerceIn(0, last)
            val endLine = rightLine.coerceIn(startLine, last)
            view.setSelectionRegion(startLine, leftColumn.coerceAtMost(view.text.getColumnCount(startLine)),
                endLine, rightColumn.coerceAtMost(view.text.getColumnCount(endLine)))
            appliedText = text
            applying = false
        }
        if (appliedTokenizer !== tokenizer) {
            val prepared = tokenizer
            val native = language?.let { prepared?.soraLanguage(it) }
            view.colorScheme = if (native != null && prepared != null)
                TextMateColorScheme.create(prepared.themes, prepared.themes.currentThemeModel) else EditorColorScheme()
            view.setEditorLanguage(native ?: EmptyLanguage())
            appliedTokenizer = prepared
        }
        view.colorScheme.apply {
            setColor(EditorColorScheme.WHOLE_BACKGROUND, colors.surface.toArgb())
            setColor(EditorColorScheme.TEXT_NORMAL, colors.onSurface.toArgb())
            setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, colors.surfaceContainerLow.toArgb())
            setColor(EditorColorScheme.LINE_NUMBER, colors.outline.toArgb())
            setColor(EditorColorScheme.LINE_NUMBER_CURRENT, colors.primary.toArgb())
            setColor(EditorColorScheme.CURRENT_LINE, colors.surfaceContainerLow.toArgb())
            setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, colors.primaryContainer.toArgb())
            setColor(EditorColorScheme.TEXT_SELECTED, colors.onPrimaryContainer.toArgb())
            setColor(EditorColorScheme.SELECTION_HANDLE, colors.primary.toArgb())
        }
    }) }
}
