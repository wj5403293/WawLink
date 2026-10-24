package dev.wawlink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Resolve model-authored file links in the host's current session, never as host file:// URIs. */
val LocalAgentFileLinkHandler = staticCompositionLocalOf<((String) -> Unit)?> { null }

/** Pace only the pending suffix; expansion pauses preserve the already visible prefix. */
@Composable
internal fun streamedText(text: String, streaming: Boolean, paused: Boolean = false): String {
    val smooth = LocalAgentAppearance.current.smoothStreaming
    val latest by rememberUpdatedState(text)
    var visible by remember { mutableStateOf(text) }
    SideEffect { if (!paused && (!streaming || !smooth)) visible = text }
    LaunchedEffect(streaming, smooth, paused) {
        if (paused) return@LaunchedEffect
        while (streaming && smooth) {
            val target = latest
            if (!target.startsWith(visible)) visible = target
            else if (visible.length < target.length) {
                val count = ((target.length - visible.length) / 8).coerceIn(1, 64)
                var end = (visible.length + count).coerceAtMost(target.length)
                if (end < target.length && target[end - 1].isHighSurrogate() && target[end].isLowSurrogate()) end++
                visible = target.take(end)
            }
            delay(32)
        }
        visible = latest
    }
    return if (paused || streaming && smooth) visible else text
}

@Composable
internal fun Markdown(text: String, modifier: Modifier = Modifier) {
    val parts = remember(text) { markdownParts(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEach { part ->
            when (part) {
                is MarkdownPart.Text -> if (part.text.isNotBlank()) PlainMarkdown(part.text)
                is MarkdownPart.Code -> MarkdownCode(part)
                is MarkdownPart.Graphic -> RichGraphic(part)
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
                when {
                    line.matches(Regex("^\\s{0,3}([-*_])(?:\\s*\\1){2,}\\s*$")) -> HorizontalDivider(color = c.outlineVariant)
                    line.trim().startsWith("|") && index + 1 < lines.size && lines[index + 1].matches(Regex("^[| :\\-]+$")) -> {
                        val rows = mutableListOf(line)
                        index += 2
                        while (index < lines.size && lines[index].trim().startsWith("|")) rows.add(lines[index++])
                        Surface(color = c.surfaceContainerLow, shape = RoundedCornerShape(12.dp)) {
                            Column(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp)) {
                                rows.forEachIndexed { rowIndex, row ->
                                    Row {
                                        row.trim().trim('|').split('|').forEach { cell ->
                                            InlineMarkdownText(cell.trim(), Modifier.widthIn(min = 110.dp, max = 240.dp).padding(8.dp),
                                                MaterialTheme.typography.bodyLarge.copy(fontWeight = if (rowIndex == 0) FontWeight.Bold else FontWeight.Normal))
                                        }
                                    }
                                    if (rowIndex == 0) HorizontalDivider(color = c.outlineVariant)
                                }
                            }
                        }
                        continue
                    }
                    line.isBlank() -> Spacer(Modifier.height(2.dp))
                    else -> {
                        val heading = Regex("^(#{1,6})\\s+(.*)$").find(line)
                        val bullet = Regex("^(\\s*)([-+*]|\\d+[.)])\\s+(.*)$").find(line)
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
                            val checkbox = Regex("^\\[([ xX])]\\s+(.*)$").find(body)
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
                }
                index++
            }
        }
    }
}

@Composable
private fun InlineMarkdownText(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge) {
    val annotated = inlineMarkdown(text)
    val fileHandler = LocalAgentFileLinkHandler.current
    val uri = LocalUriHandler.current
    ClickableText(annotated, modifier, style = style, onClick = { offset ->
        val file = annotated.getStringAnnotations("FILE", offset, offset).firstOrNull()
        if (file != null) fileHandler?.invoke(file.item)
        else annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { uri.openUri(it.item) }
    })
}

private val inlineTokens = Regex(
    "\\\\[\\\\`*{}\\[\\]()#+.!_>~|\\-]|" +
        "(`+)(.+?)\\1|\\[([^]]+)]\\(([^)]+)\\)|\\*\\*(.+?)\\*\\*|" +
        "(?<![\\p{L}\\p{N}_])__(.+?)__(?![\\p{L}\\p{N}_])|" +
        "~~(.+?)~~|(?<!\\*)\\*([^*]+)\\*(?!\\*)|" +
        "(?<![\\p{L}\\p{N}_])_(.+?)_(?![\\p{L}\\p{N}_])|" +
        "(?<![\\p{L}\\p{N}_./-])([\\p{L}\\p{N}_@+.-]+)[ \\t]*[(（]([^()（）\\r\\n]+)[)）]"
)

@Composable
private fun inlineMarkdown(text: String): AnnotatedString {
    val c = LocalAgentColors.current
    val mono = LocalAgentMono.current
    return remember(text, c, mono) {
        buildInline(text, SpanStyle(color = c.primary, textDecoration = TextDecoration.Underline),
            SpanStyle(color = c.onPrimaryContainer, fontFamily = mono, fontSize = 15.sp, background = c.primaryContainer))
    }
}

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
                val code = match.groupValues[2]
                val path = fileReference(code)
                if (path != null) appendFile(path, code, codeStyle.copy(textDecoration = TextDecoration.Underline))
                else withStyle(codeStyle) { append(code) }
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
