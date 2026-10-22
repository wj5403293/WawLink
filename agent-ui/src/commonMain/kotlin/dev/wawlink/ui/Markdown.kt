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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Keeps a streaming suffix paced without replaying an already visible prefix. */
@Composable
internal fun streamedText(text: String, streaming: Boolean): String {
    val smooth = LocalAgentAppearance.current.smoothStreaming
    var visible by remember { mutableStateOf(if (streaming && smooth) "" else text) }
    LaunchedEffect(text, streaming, smooth) {
        if (!streaming || !smooth || !text.startsWith(visible)) visible = text
        else while (visible.length < text.length) {
            val count = ((text.length - visible.length) / 12).coerceIn(1, 32)
            visible = text.take((visible.length + count).coerceAtMost(text.length))
            delay(16)
        }
    }
    return visible
}

@Composable
internal fun Markdown(text: String, modifier: Modifier = Modifier) {
    val c = LocalAgentColors.current
    val mono = LocalAgentMono.current
    val uri = LocalUriHandler.current
    val lines = remember(text) { text.lines() }
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var index = 0
            while (index < lines.size) {
                val line = lines[index]
                when {
                    line.startsWith("```") || line.startsWith("~~~") -> {
                        val fence = line.take(3)
                        val language = line.drop(3).trim()
                        val start = ++index
                        while (index < lines.size && !lines[index].startsWith(fence)) index++
                        val code = lines.subList(start, index).joinToString("\n")
                        Surface(color = c.surfaceContainerLow, shape = RoundedCornerShape(14.dp)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                if (language.isNotEmpty()) Text(language, color = c.onSurfaceVariant, fontSize = 13.sp)
                                Text(code, Modifier.horizontalScroll(rememberScrollState()), fontFamily = mono, fontSize = 14.sp, lineHeight = 22.4.sp)
                            }
                        }
                    }
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
                                            Text(inlineMarkdown(cell.trim()), Modifier.widthIn(min = 110.dp, max = 240.dp).padding(8.dp),
                                                fontWeight = if (rowIndex == 0) FontWeight.Bold else FontWeight.Normal)
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
                            ClickableText(inlineMarkdown(checkbox?.groupValues?.get(2) ?: body),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    color = if (quote) c.onSurfaceVariant else c.onSurface,
                                    fontSize = if (heading != null) (28 - heading.groupValues[1].length * 2).coerceAtLeast(18).sp else 18.sp,
                                    fontWeight = if (heading != null) FontWeight.Bold else FontWeight.Normal),
                                onClick = { offset ->
                                    inlineMarkdownForLinks(checkbox?.groupValues?.get(2) ?: body).getStringAnnotations("URL", offset, offset)
                                        .firstOrNull()?.let { uri.openUri(it.item) }
                                })
                        }
                    }
                }
                index++
            }
        }
    }
}

private val inlineTokens = Regex(
    "\\\\[\\\\`*{}\\[\\]()#+.!_>~|\\-]|" +
        "(`+)(.+?)\\1|\\[([^]]+)]\\(([^)]+)\\)|\\*\\*(.+?)\\*\\*|" +
        "(?<![\\p{L}\\p{N}_])__(.+?)__(?![\\p{L}\\p{N}_])|" +
        "~~(.+?)~~|(?<!\\*)\\*([^*]+)\\*(?!\\*)|" +
        "(?<![\\p{L}\\p{N}_])_(.+?)_(?![\\p{L}\\p{N}_])"
)

@Composable
private fun inlineMarkdown(text: String): AnnotatedString {
    val c = LocalAgentColors.current
    val mono = LocalAgentMono.current
    return buildInline(text, SpanStyle(color = c.primary, textDecoration = TextDecoration.Underline),
        SpanStyle(fontFamily = mono, fontSize = 15.sp, background = c.surfaceContainer))
}

private fun inlineMarkdownForLinks(text: String) = buildInline(text, SpanStyle(), SpanStyle())
internal fun buildInline(text: String, linkStyle: SpanStyle, codeStyle: SpanStyle): AnnotatedString = buildAnnotatedString {
    var last = 0
    inlineTokens.findAll(text).forEach { match ->
        append(text, last, match.range.first)
        when {
            text[match.range.first] == '\\' -> append(text[match.range.first + 1])
            match.groupValues[1].isNotEmpty() -> withStyle(codeStyle) { append(match.groupValues[2]) }
            match.groupValues[3].isNotEmpty() -> {
                pushStringAnnotation("URL", match.groupValues[4]); withStyle(linkStyle) { append(match.groupValues[3]) }; pop()
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
