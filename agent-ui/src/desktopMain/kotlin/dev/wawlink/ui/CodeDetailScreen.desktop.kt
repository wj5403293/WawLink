package dev.wawlink.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import java.awt.MouseInfo
import java.awt.GraphicsEnvironment
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import java.awt.Font
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.AbstractAction
import java.awt.event.ActionEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rsyntaxtextarea.SyntaxScheme
import org.fife.ui.rsyntaxtextarea.TokenTypes
import org.fife.ui.rtextarea.RTextScrollPane

@Composable
internal actual fun PlatformCodeDetail(onBack: () -> Unit, content: @Composable () -> Unit) {
    val density = LocalDensity.current.density
    val screen = remember {
        (MouseInfo.getPointerInfo()?.device ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice).defaultConfiguration.bounds
    }
    DialogWindow(onCloseRequest = onBack, title = "代码详情", undecorated = true, resizable = false,
        state = rememberDialogState(position = WindowPosition.Absolute((screen.x / density).dp, (screen.y / density).dp),
            width = (screen.width / density).dp, height = (screen.height / density).dp),
        onPreviewKeyEvent = { event ->
            (event.type == KeyEventType.KeyDown && event.key == Key.Escape).also { if (it) onBack() }
        },
    ) {
        // Swing focus can consume events before Compose's preview dispatcher sees them.
        window.rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "closeCode")
        window.rootPane.actionMap.put("closeCode", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent?) = onBack()
        })
        content()
    }
}

private class DesktopCodeView : RTextScrollPane(RSyntaxTextArea()) {
    val area = textArea as RSyntaxTextArea
    init {
        area.apply {
            isEditable = false
            lineWrap = false
            isCodeFoldingEnabled = false
            isAutoIndentEnabled = false
            font = Font(Font.MONOSPACED, Font.PLAIN, 15)
            margin = java.awt.Insets(12, 12, 12, 12)
            popupMenu = javax.swing.JPopupMenu().apply {
                add(javax.swing.JMenuItem("复制所选代码").apply { addActionListener { area.copy() } })
                add(javax.swing.JMenuItem("全选代码").apply { addActionListener { area.selectAll() } })
            }
        }
        setLineNumbersEnabled(true)
        horizontalScrollBarPolicy = HORIZONTAL_SCROLLBAR_AS_NEEDED
        verticalScrollBarPolicy = VERTICAL_SCROLLBAR_AS_NEEDED
        border = null
    }
    var colors: AgentColorScheme? = null
    var document: RSyntaxDocument? = null
    var selectionRequest = 0
}
@Composable
internal actual fun PlatformCodeViewer(text: String, language: String?, modifier: Modifier, selectionRequest: Int) {
    val colors = LocalAgentColors.current
    var document by remember(text, language) { mutableStateOf<RSyntaxDocument?>(null) }
    LaunchedEffect(text, language) {
        document = withContext(Dispatchers.Default) {
            val doc = RSyntaxDocument(desktopSyntaxStyle(language))
            ensureActive()
            // Insertion computes multiline lexical state off the EDT. RSyntax tokenizes only
            // visible lines at paint time and owns its document-local token cache thereafter.
            doc.insertString(0, text, null)
            ensureActive()
            doc
        }
    }
    SwingPanel(factory = { DesktopCodeView() }, modifier = modifier, background = colors.surface, update = { view ->
        val next = document
        if (next != null && view.document !== next) {
            val start = view.area.selectionStart.coerceAtMost(text.length)
            val end = view.area.selectionEnd.coerceAtMost(text.length)
            view.area.document = next
            view.area.select(start, end)
            view.document = next
        }
        if (view.colors != colors) {
            view.area.background = colors.surface.awt()
            view.area.foreground = colors.onSurface.awt()
            view.area.caretColor = colors.primary.awt()
            view.area.selectionColor = colors.primaryContainer.awt()
            view.area.selectedTextColor = colors.onPrimaryContainer.awt()
            view.area.currentLineHighlightColor = colors.surfaceContainerLow.awt()
            view.area.syntaxScheme = SyntaxScheme(view.area.font).apply {
                for (index in 0 until TokenTypes.DEFAULT_NUM_TOKEN_TYPES) {
                    getStyle(index).foreground = when (index) {
                        in TokenTypes.COMMENT_EOL..TokenTypes.COMMENT_MARKUP, TokenTypes.MARKUP_COMMENT -> colors.onSurfaceVariant
                        TokenTypes.RESERVED_WORD, TokenTypes.RESERVED_WORD_2, TokenTypes.DATA_TYPE,
                        TokenTypes.ANNOTATION, TokenTypes.FUNCTION, TokenTypes.MARKUP_TAG_NAME -> colors.primary
                        TokenTypes.LITERAL_STRING_DOUBLE_QUOTE, TokenTypes.LITERAL_CHAR, TokenTypes.LITERAL_BACKQUOTE,
                        TokenTypes.MARKUP_TAG_ATTRIBUTE_VALUE -> colors.tertiary
                        TokenTypes.LITERAL_BOOLEAN, TokenTypes.LITERAL_NUMBER_DECIMAL_INT,
                        TokenTypes.LITERAL_NUMBER_FLOAT, TokenTypes.LITERAL_NUMBER_HEXADECIMAL -> colors.warn
                        else -> colors.onSurface
                    }.awt()
                }
            }
            view.gutter.background = colors.surfaceContainerLow.awt()
            view.gutter.lineNumberColor = colors.outline.awt()
            view.gutter.borderColor = colors.outlineVariant.awt()
            view.colors = colors
        }
        if (next != null && selectionRequest != view.selectionRequest) {
            if (selectionRequest > 0) { view.area.requestFocusInWindow(); view.area.selectAll() }
            view.selectionRequest = selectionRequest
        }
    })
}

internal fun desktopSyntaxStyle(language: String?): String = when (language) {
    "cpp" -> "text/cpp"
    "csharp" -> "text/cs"
    "go" -> "text/golang"
    "shell" -> "text/unix"
    "properties" -> "text/properties"
    "c", "dart", "java", "kotlin", "rust", "javascript", "typescript", "perl", "python", "ruby",
    "php", "json", "yaml", "xml", "html", "css", "sql", "lua", "markdown", "ini", "dockerfile", "makefile" -> "text/$language"
    else -> "text/plain"
}

private fun Color.awt() = java.awt.Color(toArgb(), true)
