package dev.wawlink.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rsyntaxtextarea.SyntaxScheme
import org.fife.ui.rsyntaxtextarea.TokenTypes
import org.fife.ui.rtextarea.RTextScrollPane
import java.awt.Font
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

private class EditableDesktopCode(text: String, language: String?, val onChange: (String) -> Unit) {
    val area = RSyntaxTextArea().apply {
        this.text = text
        syntaxEditingStyle = desktopSyntaxStyle(language)
        isEditable = true
        isCodeFoldingEnabled = true
        isAutoIndentEnabled = true
        font = Font(Font.MONOSPACED, Font.PLAIN, 15)
        margin = java.awt.Insets(12, 12, 12, 12)
    }
    val pane = RTextScrollPane(area).apply { setLineNumbersEnabled(true); border = null }
    var applying = false
    var disposed = false
    var colors: AgentColorScheme? = null
    var pending = false
    var appliedText = text
    val listener = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent?) = changed()
        override fun removeUpdate(e: DocumentEvent?) = changed()
        override fun changedUpdate(e: DocumentEvent?) = changed()
        private fun changed() {
            if (applying || pending || disposed) return
            pending = true
            SwingUtilities.invokeLater {
                pending = false
                if (!disposed && !applying) {
                    appliedText = area.text
                    onChange(appliedText)
                }
            }
        }
    }
    init { area.document.addDocumentListener(listener) }
}

@Composable
internal actual fun PlatformCodeEditor(text: String, language: String?, onChange: (String) -> Unit, modifier: Modifier) {
    val colors = LocalAgentColors.current
    val change by rememberUpdatedState(onChange)
    val view = remember { EditableDesktopCode(text, language) { change(it) } }
    DisposableEffect(view) {
        onDispose {
            view.disposed = true
            view.area.document.removeDocumentListener(view.listener)
            view.area.discardAllEdits()
        }
    }
    SwingPanel(factory = { view.pane }, modifier = modifier, background = colors.surface, update = {
        if (view.appliedText != text && !view.pending) {
            val start = view.area.selectionStart.coerceAtMost(text.length)
            val end = view.area.selectionEnd.coerceAtMost(text.length)
            val scroll = view.pane.viewport.viewPosition
            view.applying = true
            view.area.text = text
            view.appliedText = text
            view.area.select(start, end)
            view.pane.viewport.viewPosition = scroll
            view.applying = false
        }
        val style = desktopSyntaxStyle(language)
        if (view.area.syntaxEditingStyle != style) view.area.syntaxEditingStyle = style
        if (view.colors != colors) {
            view.area.background = colors.surface.editorAwt()
            view.area.foreground = colors.onSurface.editorAwt()
            view.area.caretColor = colors.primary.editorAwt()
            view.area.selectionColor = colors.primaryContainer.editorAwt()
            view.area.selectedTextColor = colors.onPrimaryContainer.editorAwt()
            view.area.currentLineHighlightColor = colors.surfaceContainerLow.editorAwt()
            view.area.syntaxScheme = SyntaxScheme(view.area.font).apply {
                for (index in 0 until TokenTypes.DEFAULT_NUM_TOKEN_TYPES)
                    getStyle(index).foreground = desktopTokenColor(index, colors).editorAwt()
            }
            view.pane.gutter.background = colors.surfaceContainerLow.editorAwt()
            view.pane.gutter.lineNumberColor = colors.outline.editorAwt()
            view.pane.gutter.borderColor = colors.outlineVariant.editorAwt()
            view.colors = colors
        }
    })
}

private fun Color.editorAwt() = java.awt.Color(toArgb(), true)
