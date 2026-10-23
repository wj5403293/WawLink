package dev.wawlink.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rsyntaxtextarea.TokenTypes

/** The desktop viewer and inline code use the same native multiline lexer, not a regex fallback. */
internal actual suspend fun highlightPlatformCode(text: String, language: String, colors: AgentColorScheme): AnnotatedString =
    withContext(Dispatchers.Default) {
        val style = desktopSyntaxStyle(language)
        if (style == SyntaxConstants.SYNTAX_STYLE_NONE) return@withContext AnnotatedString(text)
        val document = RSyntaxDocument(style)
        document.insertString(0, text, null)
        val result = AnnotatedString.Builder(text)
        val styles = HashMap<Int, SpanStyle>()
        for (line in 0 until document.defaultRootElement.elementCount) {
            ensureActive()
            var token = document.getTokenListForLine(line)
            while (token != null && token.isPaintable) {
                val start = token.offset.coerceIn(0, text.length)
                val end = (token.offset + token.length()).coerceIn(start, text.length)
                val type = token.type
                if (start < end) result.addStyle(styles.getOrPut(type) {
                    SpanStyle(color = desktopTokenColor(type, colors))
                }, start, end)
                token = token.nextToken
            }
        }
        result.toAnnotatedString()
    }

internal fun desktopTokenColor(type: Int, colors: AgentColorScheme): Color = when (type) {
    in TokenTypes.COMMENT_EOL..TokenTypes.COMMENT_MARKUP, TokenTypes.MARKUP_COMMENT -> colors.onSurfaceVariant
    TokenTypes.RESERVED_WORD, TokenTypes.RESERVED_WORD_2, TokenTypes.DATA_TYPE,
    TokenTypes.ANNOTATION, TokenTypes.FUNCTION, TokenTypes.MARKUP_TAG_NAME -> colors.primary
    TokenTypes.LITERAL_STRING_DOUBLE_QUOTE, TokenTypes.LITERAL_CHAR, TokenTypes.LITERAL_BACKQUOTE,
    TokenTypes.MARKUP_TAG_ATTRIBUTE_VALUE -> colors.tertiary
    TokenTypes.LITERAL_BOOLEAN, TokenTypes.LITERAL_NUMBER_DECIMAL_INT,
    TokenTypes.LITERAL_NUMBER_FLOAT, TokenTypes.LITERAL_NUMBER_HEXADECIMAL -> colors.warn
    else -> colors.onSurface
}
