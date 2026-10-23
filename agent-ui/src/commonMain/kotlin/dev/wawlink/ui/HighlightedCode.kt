package dev.wawlink.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Inline code owns horizontal scrolling only; feed/detail parents decide vertical scrolling. */
@Composable
internal fun HighlightedCode(
    text: String,
    language: String? = null,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 14.sp,
    lineHeight: TextUnit = 22.4.sp,
) {
    val colors = LocalAgentColors.current
    val highlighted = rememberHighlightedCode(text, language)
    val numbers = remember(text) { (1..(text.count { it == '\n' } + 1)).joinToString("\n") }
    val mono = LocalAgentMono.current
    Row(modifier.horizontalScroll(rememberScrollState())) {
        DisableSelection {
            Text(numbers, Modifier.padding(end = 14.dp), color = colors.outline, fontFamily = mono,
                fontSize = fontSize, lineHeight = lineHeight, textAlign = TextAlign.End, softWrap = false)
        }
        SelectionContainer {
            Text(highlighted, color = colors.onSurface, fontFamily = mono,
                fontSize = fontSize, lineHeight = lineHeight, softWrap = false)
        }
    }
}

@Composable
internal fun rememberHighlightedCode(text: String, language: String?): AnnotatedString {
    val colors = LocalAgentColors.current
    val canonical = remember(language, text) { inferCodeLanguage(explicit = language, text = text) }
    var result by remember(text) { mutableStateOf(AnnotatedString(text)) }
    LaunchedEffect(text, canonical, colors) {
        result = CodeHighlightCache.highlight(text, canonical, colors)
    }
    return result
}

private data class HighlightKey(val text: String, val language: String, val colors: AgentColorScheme)

/** Bounded by source characters as well as entry count; jobs never publish cancelled/stale results. */
private object CodeHighlightCache {
    private val mutex = Mutex()
    private val entries = LinkedHashMap<HighlightKey, AnnotatedString>()
    private var characters = 0
    private const val maxCharacters = 512_000

    suspend fun highlight(text: String, language: String?, colors: AgentColorScheme): AnnotatedString {
        val syntax = language?.let { name -> SyntaxLanguage.entries.firstOrNull { it.name.equals(name, true) } }
            ?: return AnnotatedString(text)
        val key = HighlightKey(text, syntax.name, colors)
        mutex.withLock {
            entries.remove(key)?.let { entries[key] = it; return it }
        }
        val result = withContext(Dispatchers.Default) {
            ensureActive()
            val theme = SyntaxTheme(
                key = "wawlink", code = colors.onSurface.toArgb(), keyword = colors.primary.toArgb(),
                string = colors.tertiary.toArgb(), literal = colors.warn.toArgb(),
                comment = colors.onSurfaceVariant.toArgb(), metadata = colors.primary.toArgb(),
                multilineComment = colors.onSurfaceVariant.toArgb(),
                punctuation = colors.primary.toArgb(), mark = colors.onSurface.toArgb(),
            )
            val highlights = Highlights.Builder().code(text).language(syntax).theme(theme).build().getHighlights()
            ensureActive()
            val builder = AnnotatedString.Builder(text)
            highlights.forEach { highlight ->
                ensureActive()
                if (highlight is ColorHighlight) {
                    val start = highlight.location.start.coerceIn(0, text.length)
                    val end = highlight.location.end.coerceIn(start, text.length)
                    if (start < end) builder.addStyle(SpanStyle(color = Color(highlight.rgb or 0xFF000000.toInt())), start, end)
                }
            }
            builder.toAnnotatedString()
        }
        currentCoroutineContext().ensureActive()
        if (text.length <= maxCharacters) mutex.withLock {
            if (!entries.containsKey(key)) {
                entries[key] = result
                characters += text.length
                while (entries.size > 16 || characters > maxCharacters) {
                    val oldest = entries.keys.first()
                    characters -= oldest.text.length
                    entries.remove(oldest)
                }
            }
        }
        return result
    }
}
