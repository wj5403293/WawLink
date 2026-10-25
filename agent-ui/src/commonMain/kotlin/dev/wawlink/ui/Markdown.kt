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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

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

/** Pace only the pending suffix; expansion pauses preserve the already visible prefix. */
@Composable
internal fun streamedText(text: String, streaming: Boolean, paused: Boolean = false): String {
    val smooth = LocalAgentAppearance.current.smoothStreaming
    val latest by rememberUpdatedState(text)
    var visible by remember { mutableStateOf(text) }
    SideEffect { if (!paused && (!streaming || !smooth)) visible = text }
    LaunchedEffect(streaming, smooth, paused) {
        if (paused) return@LaunchedEffect
        if (streaming && smooth) coroutineScope {
            val changes = Channel<Unit>(Channel.CONFLATED)
            val observer = launch(start = CoroutineStart.UNDISPATCHED) {
                snapshotFlow { latest }.collect { changes.trySend(Unit) }
            }
            try {
                var checkedTarget: String? = null
                while (true) {
                    val target = latest
                    if (target !== checkedTarget) {
                        checkedTarget = target
                        if (!target.startsWith(visible)) {
                            visible = target
                            delay(32)
                            continue
                        }
                    }
                    if (visible.length < target.length) {
                        visible = target.take(streamedTextEnd(target, visible.length))
                        delay(32)
                    } else {
                        // No timer per idle/completed node. Events wake the same pacing loop,
                        // rather than restarting its delay for every incoming token.
                        changes.receive()
                    }
                }
            } finally {
                observer.cancel()
                changes.close()
            }
        }
        visible = latest
    }
    return if (paused || streaming && smooth) visible else text
}

internal fun streamedTextEnd(target: String, visibleLength: Int): Int {
    val count = ((target.length - visibleLength) / 8).coerceIn(1, 64)
    var end = (visibleLength + count).coerceAtMost(target.length)
    if (end in 1 until target.length && target[end - 1].isHighSurrogate() && target[end].isLowSurrogate()) end++
    return end
}

@Composable
internal fun Markdown(text: String, modifier: Modifier = Modifier) {
    val buffer = remember { MarkdownPartsBuffer() }
    val parts = remember(text) { buffer.update(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEach { part ->
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

private val horizontalRule = Regex("^\\s{0,3}([-*_])(?:\\s*\\1){2,}\\s*$")
private val markdownHeading = Regex("^(#{1,6})\\s+(.*)$")
private val markdownBullet = Regex("^(\\s*)([-+*]|\\d+[.)])\\s+(.*)$")
private val markdownCheckbox = Regex("^\\[([ xX])]\\s+(.*)$")

@Immutable
internal sealed interface PlainMarkdownBlock {
    object Divider : PlainMarkdownBlock
    object Blank : PlainMarkdownBlock
    data class Table(val data: MarkdownTableData) : PlainMarkdownBlock
    data class Text(
        val body: String,
        val headingLevel: Int,
        val prefix: String?,
        val indent: Int,
        val quote: Boolean,
        val checked: Boolean?,
    ) : PlainMarkdownBlock
}

/** Retains completed line/block models, including table rows, across append-only snapshots. */
internal class PlainMarkdownBuffer {
    private data class Entry(val start: Int, val end: Int, val block: PlainMarkdownBlock)
    private var previous: String? = null
    private val lines = mutableListOf<String>()
    private val starts = mutableListOf<Int>()
    private val entries = mutableListOf<Entry>()
    private var result = emptyList<PlainMarkdownBlock>()

    fun update(text: String): List<PlainMarkdownBlock> {
        val old = previous
        if (text == old) return result
        val append = old != null && text.startsWith(old)
        val changed = if (append) (lines.lastIndex - if (old!!.endsWith('\r')) 1 else 0).coerceAtLeast(0) else 0
        val start = if (append) starts[changed] else 0
        while (lines.size > changed) {
            lines.removeAt(lines.lastIndex)
            starts.removeAt(starts.lastIndex)
        }
        var lineStart = start
        var cursor = start
        while (cursor < text.length) {
            val ch = text[cursor]
            if (ch == '\r' || ch == '\n') {
                starts += lineStart
                lines += text.substring(lineStart, cursor)
                if (ch == '\r' && cursor + 1 < text.length && text[cursor + 1] == '\n') cursor++
                lineStart = cursor + 1
            }
            cursor++
        }
        starts += lineStart
        lines += text.substring(lineStart)
        previous = text

        // The preceding line may acquire a table delimiter, or a previous table may
        // acquire another row when the last line receives its first unescaped pipe.
        val invalidFrom = (changed - 1).coerceAtLeast(0)
        var keep = if (append) entries.size else 0
        while (keep > 0 && entries[keep - 1].end > invalidFrom) keep--
        val restart = entries.getOrNull(keep)
        var index = restart?.start ?: if (keep > 0) entries[keep - 1].end else 0
        while (entries.size > keep) entries.removeAt(entries.lastIndex)
        while (index < lines.size) {
            val line = lines[index]
            val priorTable = if (append && restart != null && restart.start == index) (restart.block as? PlainMarkdownBlock.Table)?.data else null
            val divider = horizontalRule.matches(line)
            val table = if (!divider) markdownTable(lines, index, priorTable, changed) else null
            val block = when {
                divider -> PlainMarkdownBlock.Divider
                table != null -> PlainMarkdownBlock.Table(table)
                line.isBlank() -> PlainMarkdownBlock.Blank
                else -> {
                    val heading = markdownHeading.find(line)
                    val bullet = markdownBullet.find(line)
                    val quote = line.startsWith(">")
                    val body = when {
                        heading != null -> heading.groupValues[2]
                        bullet != null -> bullet.groupValues[3]
                        quote -> line.removePrefix(">").trimStart()
                        else -> line
                    }
                    val checkbox = markdownCheckbox.find(body)
                    PlainMarkdownBlock.Text(
                        body = checkbox?.groupValues?.get(2) ?: body,
                        headingLevel = heading?.groupValues?.get(1)?.length ?: 0,
                        prefix = bullet?.let { if (it.groupValues[2].first().isDigit()) it.groupValues[2] else "•" },
                        indent = bullet?.groupValues?.get(1)?.length ?: 0,
                        quote = quote,
                        checked = checkbox?.groupValues?.get(1)?.isNotBlank(),
                    )
                }
            }
            val next = table?.nextLine ?: index + 1
            entries += Entry(index, next, block)
            index = next
        }
        result = entries.map { it.block }
        return result
    }
}

@Composable
private fun PlainMarkdown(text: String, modifier: Modifier = Modifier) {
    val buffer = remember { PlainMarkdownBuffer() }
    val blocks = remember(text) { buffer.update(text) }
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEach { PlainMarkdownBlockContent(it) }
        }
    }
}

@Composable
private fun PlainMarkdownBlockContent(block: PlainMarkdownBlock) {
    val c = LocalAgentColors.current
    when (block) {
        PlainMarkdownBlock.Divider -> HorizontalDivider(color = c.outlineVariant)
        PlainMarkdownBlock.Blank -> Spacer(Modifier.height(2.dp))
        is PlainMarkdownBlock.Table -> MarkdownTable(block.data)
        is PlainMarkdownBlock.Text -> Row(Modifier.fillMaxWidth().then(if (block.quote) Modifier.background(c.surfaceContainerLow, RoundedCornerShape(6.dp)).padding(12.dp) else Modifier)) {
            if (block.prefix != null) Text(block.prefix, Modifier.padding(start = (block.indent * 4).dp, end = 10.dp), fontSize = 18.sp)
            if (block.checked != null) {
                Symbol(if (block.checked) "check_box" else "check_box_outline_blank", tint = c.primary)
                Spacer(Modifier.width(8.dp))
            }
            InlineMarkdownText(block.body,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = if (block.quote) c.onSurfaceVariant else c.onSurface,
                    fontSize = if (block.headingLevel > 0) (28 - block.headingLevel * 2).coerceAtLeast(18).sp else 18.sp,
                    fontWeight = if (block.headingLevel > 0) FontWeight.Bold else FontWeight.Normal))
        }
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
