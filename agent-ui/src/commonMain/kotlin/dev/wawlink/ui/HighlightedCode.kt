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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
        if (language == null) return AnnotatedString(text)
        val key = HighlightKey(text, language, colors)
        mutex.withLock {
            entries.remove(key)?.let { entries[key] = it; return it }
        }
        val result = highlightPlatformCode(text, language, colors)
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

/** Native lexers own tokenization; common UI/cache never mounts a composable per token. */
internal expect suspend fun highlightPlatformCode(text: String, language: String, colors: AgentColorScheme): AnnotatedString
