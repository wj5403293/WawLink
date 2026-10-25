package dev.wawlink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

/** Resolve model-authored file links in the host's current session, never as host file:// URIs. */
val LocalAgentFileLinkHandler = staticCompositionLocalOf<((String) -> Unit)?> { null }

internal val LocalMarkdownExternalContent = staticCompositionLocalOf { true }

/** Untrusted Skill documents reuse native Markdown without remote image loads or actionable links. */
@Composable
fun SkillContentPreview(text: String, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalMarkdownExternalContent provides false, LocalAgentFileLinkHandler provides null) {
        Markdown(text, modifier)
    }
}

/** Pace pending output only; an idle stream does no polling or repeated full-prefix comparison. */
@Composable
internal fun streamedText(text: String, streaming: Boolean, paused: Boolean = false): String {
    val smooth = LocalAgentAppearance.current.smoothStreaming
    val latest by rememberUpdatedState(text)
    var visible by remember { mutableStateOf(text) }
    SideEffect { if (!paused && (!streaming || !smooth)) visible = text }
    LaunchedEffect(streaming, smooth, paused) {
        if (paused) return@LaunchedEffect
        if (!streaming || !smooth) {
            visible = latest
            return@LaunchedEffect
        }
        coroutineScope {
            val updates = Channel<String>(Channel.CONFLATED)
            launch(start = CoroutineStart.UNDISPATCHED) {
                snapshotFlow { latest }.collect { updates.send(it) }
            }
            var target = visible
            try {
                while (true) {
                    if (visible.length >= target.length) {
                        target = updates.receive()
                        if (!target.startsWith(visible)) visible = target
                    } else {
                        delay(32)
                        updates.tryReceive().getOrNull()?.let { next ->
                            target = next
                            if (!target.startsWith(visible)) visible = target
                        }
                        if (visible.length < target.length) {
                            val count = ((target.length - visible.length) / 8).coerceIn(1, 64)
                            var end = (visible.length + count).coerceAtMost(target.length)
                            if (end < target.length && target[end - 1].isHighSurrogate() && target[end].isLowSurrogate()) end++
                            visible = target.substring(0, end)
                        }
                    }
                }
            } finally {
                updates.cancel()
            }
        }
    }
    return if (paused || streaming && smooth) visible else text
}

private val markdownParsing = Dispatchers.Default.limitedParallelism(2)
private data class ParsedMarkdown(val source: String, val parts: List<MarkdownPart>)

/** One current result and one conflated pending source; no retained parser or character builder. */
@Composable
private fun preparedMarkdownParts(text: String): List<MarkdownPart>? {
    val latest by rememberUpdatedState(text)
    val prepared by produceState<ParsedMarkdown?>(null) {
        snapshotFlow { latest }.conflate().collect { source ->
            val parts = withContext(markdownParsing) { markdownParts(source) }
            if (latest === source || latest.startsWith(source)) value = ParsedMarkdown(source, parts)
        }
    }
    val current = prepared ?: return null
    return current.parts.takeIf { text === current.source || text.startsWith(current.source) }
}

internal fun markdownHorizontalRule(line: String): Boolean {
    fun whitespace(ch: Char) = ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n' || ch == '\u000b' || ch == '\u000c'
    var cursor = 0
    while (cursor < line.length && whitespace(line[cursor])) cursor++
    if (cursor > 3 || cursor == line.length) return false
    val marker = line[cursor]
    if (marker != '-' && marker != '*' && marker != '_') return false
    var count = 0
    while (cursor < line.length) {
        val ch = line[cursor++]
        if (ch == marker) count++ else if (!whitespace(ch)) return false
    }
    return count >= 3
}
private val headingLine = Regex("^(#{1,6})\\s+(.*)$")
private val bulletLine = Regex("^(\\s*)([-+*]|\\d+[.)])\\s+(.*)$")
private val checkboxLine = Regex("^\\[([ xX])]\\s+(.*)$")

@Composable
internal fun Markdown(text: String, modifier: Modifier = Modifier) {
    // Short committed paragraphs stay cheap; only large blocks need a worker/queue.
    val parts = if (text.length <= 4096) remember(text) { markdownParts(text) } else preparedMarkdownParts(text)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (parts == null) Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        parts?.forEach { part ->
            when (part) {
                is MarkdownPart.Text -> if (part.text.isNotBlank()) PlainMarkdown(part.text)
                is MarkdownPart.Code -> MarkdownCode(part)
                is MarkdownPart.Graphic -> if (!LocalMarkdownExternalContent.current && part.kind == GraphicKind.Image && !part.source.startsWith("data:", true)) {
                    PlainMarkdown(part.description.ifBlank { "图片" })
                } else RichGraphic(part)
            }
        }
    }
}

@Composable
internal fun AskRichPreview(text: String, modifier: Modifier = Modifier) = Markdown(text, modifier)

@Composable
private fun MarkdownCode(part: MarkdownPart.Code) {
    val colors = LocalAgentColors.current
    val clipboard = LocalClipboardManager.current
    var detail by remember { mutableStateOf(false) }
    Surface(color = colors.surfaceContainerLow, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(part.language ?: "代码", Modifier.weight(1f), color = colors.onSurfaceVariant, fontSize = 13.sp)
                AgentIconButton("content_copy", "复制代码", { clipboard.setText(AnnotatedString(part.text)) })
                AgentIconButton("open_in_full", "全屏查看代码", { detail = true })
            }
            HighlightedCode(part.text, part.language, Modifier.fillMaxWidth())
        }
    }
    if (detail) CodeDetailScreen(part.language ?: "代码详情", part.text, part.language) { detail = false }
}

@Composable
private fun PlainMarkdown(text: String, modifier: Modifier = Modifier) {
    val c = LocalAgentColors.current
    val lines = remember(text) { text.lines() }
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var index = 0
            while (index < lines.size) {
                val line = lines[index]
                val table = if ('|' in line) remember(lines, index) { markdownTable(lines, index) } else null
                if (markdownHorizontalRule(line)) {
                    HorizontalDivider(color = c.outlineVariant)
                } else if (table != null) {
                    MarkdownTable(table)
                    index = table.nextLine
                    continue
                } else {
                    PlainMarkdownLine(line)
                }
                index++
            }
        }
    }
}

@Composable
private fun PlainMarkdownLine(line: String) {
    val c = LocalAgentColors.current
    if (line.isBlank()) {
        Spacer(Modifier.height(2.dp))
        return
    }
    val heading = remember(line) { headingLine.find(line) }
    val bullet = remember(line) { bulletLine.find(line) }
    val quote = line.startsWith(">")
    val body = when {
        heading != null -> heading.groupValues[2]
        bullet != null -> bullet.groupValues[3]
        quote -> line.removePrefix(">").trimStart()
        else -> line
    }
    val prefix = bullet?.let { if (it.groupValues[2].first().isDigit()) it.groupValues[2] else "•" }
    Row(Modifier.fillMaxWidth().then(if (quote) Modifier.background(c.surfaceContainerLow, RoundedCornerShape(6.dp)).padding(12.dp) else Modifier)) {
        if (prefix != null) Text(prefix, Modifier.padding(start = (bullet.groupValues[1].length * 4).dp, end = 10.dp), fontSize = 18.sp)
        val checkbox = remember(body) { checkboxLine.find(body) }
        if (checkbox != null) {
            Symbol(if (checkbox.groupValues[1].isBlank()) "check_box_outline_blank" else "check_box", tint = c.primary)
            Spacer(Modifier.width(8.dp))
        }
        InlineMarkdownText(checkbox?.groupValues?.get(2) ?: body,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge.copy(
                color = if (quote) c.onSurfaceVariant else c.onSurface,
                fontSize = if (heading != null) (28 - heading.groupValues[1].length * 2).coerceAtLeast(18).sp else 18.sp,
                fontWeight = if (heading != null) FontWeight.Bold else FontWeight.Normal))
    }
}


private val inlineTokens = Regex(
    "\\\\[\\\\`*{}\\[\\]()#+.!_>~|\\-]|" +
        "(`+)(.+?)\\1|\\[([^]]+)]\\(([^)]+)\\)|\\*\\*(.+?)\\*\\*|" +
        "(?<![\\p{L}\\p{N}_])__(.+?)__(?![\\p{L}\\p{N}_])|" +
        "~~(.+?)~~|(?<!\\*)\\*([^*]+)\\*(?!\\*)|" +
        "(?<![\\p{L}\\p{N}_])_(.+?)_(?![\\p{L}\\p{N}_])|" +
        "(?<![\\p{L}\\p{N}_./-])([\\p{L}\\p{N}_@+.-]+)[ \\t]*[(（]([^()（）\\r\\n]+)[)）]"
)


private val uriScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
private val unsafePathCharacters = Regex("[\\u0000\\r\\n\\t<>|?*`\"{}\\[\\]=;&$\\\\]")

private fun fileReference(value: String, allowBareName: Boolean = false): String? {
    if (value.isEmpty() || value != value.trim() || uriScheme.containsMatchIn(value) || value.startsWith("//") || unsafePathCharacters.containsMatchIn(value)) return null
    val name = value.substringAfterLast('/')
    if (name.isEmpty() || name == "." || name == "..") return null
    val rooted = value.startsWith('/') || value.startsWith("./") || value.startsWith("../")
    if (!rooted && !allowBareName && '/' !in value) return null
    if (!rooted && (value.any(Char::isWhitespace) || !name.contains('.') || name.startsWith('.'))) return null
    return value
}

internal fun buildInline(text: String, linkStyle: SpanStyle, codeStyle: SpanStyle): AnnotatedString = buildAnnotatedString {
    fun appendFile(path: String, display: String, style: SpanStyle) {
        pushStringAnnotation("FILE", path)
        withStyle(style) { append(display) }
        pop()
    }
    var last = 0
    inlineTokens.findAll(text).forEach { match ->
        append(text, last, match.range.first)
        when {
            text[match.range.first] == '\\' -> append(text[match.range.first + 1])
            match.groupValues[1].isNotEmpty() -> {
                pushStringAnnotation("CODE", "")
                val code = match.groupValues[2]
                val path = fileReference(code)
                if (path != null) appendFile(path, code, codeStyle.copy(textDecoration = TextDecoration.Underline))
                else withStyle(codeStyle) { append(code) }
                pop()
            }
            match.groupValues[3].isNotEmpty() -> {
                val target = match.groupValues[4]
                val path = fileReference(target, allowBareName = true)
                if (path != null) appendFile(path, match.groupValues[3], linkStyle)
                else if (target.startsWith("https://", true) || target.startsWith("http://", true) || target.startsWith("mailto:", true)) {
                    pushStringAnnotation("URL", target); withStyle(linkStyle) { append(match.groupValues[3]) }; pop()
                } else append(match.groupValues[3])
            }
            match.groupValues[10].isNotEmpty() -> {
                val target = fileReference(match.groupValues[11].trim(), allowBareName = true)
                if (target != null && match.groupValues[10] == target.substringAfterLast('/')) appendFile(target, match.value, linkStyle)
                else append(match.value)
            }
            match.groupValues[5].isNotEmpty() || match.groupValues[6].isNotEmpty() ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(match.groupValues[5].ifEmpty { match.groupValues[6] }) }
            match.groupValues[7].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(match.groupValues[7]) }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(match.groupValues[8].ifEmpty { match.groupValues[9] }) }
        }
        last = match.range.last + 1
    }
    append(text, last, text.length)
}
