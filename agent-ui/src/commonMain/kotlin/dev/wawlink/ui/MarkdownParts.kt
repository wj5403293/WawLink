package dev.wawlink.ui

/** Graphics are separated before the existing native Markdown renderer sees inline syntax. */
internal sealed interface MarkdownPart {
    data class Text(val text: String) : MarkdownPart
    data class Code(val text: String, val language: String?) : MarkdownPart
    data class Graphic(val kind: GraphicKind, val source: String, val description: String = "") : MarkdownPart
}

internal enum class GraphicKind { Svg, Mermaid, Image }

private val openingFence = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")

internal fun markdownParts(text: String): List<MarkdownPart> {
    val parts = mutableListOf<MarkdownPart>()
    val plain = StringBuilder()
    fun flush() {
        if (plain.isNotEmpty()) {
            parts += MarkdownPart.Text(plain.toString())
            plain.clear()
        }
    }
    val lines = text.split('\n')
    var lineIndex = 0
    var codeTicks = 0
    while (lineIndex < lines.size) {
        val line = lines[lineIndex]
        val fence = openingFence.matchEntire(line)
        if (fence != null) {
            codeTicks = 0
            val marker = fence.groupValues[1]
            val language = fence.groupValues[2].trim().lowercase()
            var end = lineIndex + 1
            while (end < lines.size && !isClosingFence(lines[end], marker)) end++
            val kind = when (language) {
                "svg", "image/svg+xml" -> GraphicKind.Svg
                "mermaid" -> GraphicKind.Mermaid
                else -> null
            }
            // Incomplete streamed diagrams stay code until the closing fence arrives.
            flush()
            val source = lines.subList(lineIndex + 1, end).joinToString("\n")
            if (kind != null && end < lines.size) {
                parts += MarkdownPart.Graphic(kind, source)
            } else {
                parts += MarkdownPart.Code(source, language.takeIf(String::isNotEmpty))
            }
            lineIndex = (end + 1).coerceAtMost(lines.size)
            continue
        }
        if (line.isBlank()) codeTicks = 0
        if (line.startsWith("    ") || line.startsWith('\t')) {
            plain.append(line)
            if (lineIndex < lines.lastIndex) plain.append('\n')
            lineIndex++
            continue
        }
        if (codeTicks == 0 && line.trimStart().startsWith("<svg", ignoreCase = true)) {
            var end = lineIndex
            while (end < lines.size && !lines[end].contains("</svg>", ignoreCase = true)) end++
            if (end < lines.size) {
                flush()
                parts += MarkdownPart.Graphic(GraphicKind.Svg, lines.subList(lineIndex, end + 1).joinToString("\n"))
                lineIndex = end + 1
                continue
            }
        }
        var cursor = 0
        while (cursor < line.length) {
            if (line[cursor] == '\\' && cursor + 1 < line.length) {
                plain.append(line, cursor, cursor + 2)
                cursor += 2
                continue
            }
            if (line[cursor] == '`') {
                var end = cursor + 1
                while (end < line.length && line[end] == '`') end++
                val ticks = end - cursor
                if (codeTicks == 0) codeTicks = ticks else if (codeTicks == ticks) codeTicks = 0
                plain.append(line, cursor, end)
                cursor = end
                continue
            }
            val image = if (codeTicks == 0 && line.startsWith("![", cursor)) imageAt(line, cursor) else null
            if (image != null) {
                flush()
                parts += MarkdownPart.Graphic(GraphicKind.Image, image.second.second, image.second.first)
                cursor = image.first
            } else {
                plain.append(line[cursor++])
            }
        }
        if (lineIndex < lines.lastIndex) plain.append('\n')
        lineIndex++
    }
    flush()
    return parts
}

private fun isClosingFence(line: String, marker: String): Boolean {
    val trimmed = line.trimStart(' ')
    return line.length - trimmed.length <= 3 && trimmed.takeWhile { it == marker[0] }.length >= marker.length &&
        trimmed.dropWhile { it == marker[0] }.isBlank()
}

/** Balanced destinations keep data SVGs and URL parentheses intact; titles aren't part of the URL. */
private fun imageAt(line: String, start: Int): Pair<Int, Pair<String, String>>? {
    var labelEnd = start + 2
    while (labelEnd < line.length) {
        if (line[labelEnd] == '\\') labelEnd += 2
        else if (line[labelEnd] == ']') break
        else labelEnd++
    }
    if (labelEnd >= line.length || !line.startsWith("](", labelEnd)) return null
    var cursor = labelEnd + 2
    while (cursor < line.length && line[cursor].isWhitespace()) cursor++
    val angled = cursor < line.length && line[cursor] == '<'
    if (angled) cursor++
    val urlStart = cursor
    var depth = 0
    while (cursor < line.length) {
        val ch = line[cursor]
        if (ch == '\\' && cursor + 1 < line.length) { cursor += 2; continue }
        if (angled && ch == '>') break
        if (!angled && depth == 0 && (ch == ')' || ch.isWhitespace())) break
        if (!angled && ch == '(') depth++
        if (!angled && ch == ')') depth--
        cursor++
    }
    if (cursor == urlStart || cursor >= line.length) return null
    val url = line.substring(urlStart, cursor).replace(Regex("\\\\([()])"), "$1")
    if (angled) {
        if (line[cursor] != '>') return null
        cursor++
    }
    while (cursor < line.length && line[cursor].isWhitespace()) cursor++
    if (cursor < line.length && (line[cursor] == '"' || line[cursor] == '\'')) {
        val quote = line[cursor++]
        while (cursor < line.length && line[cursor] != quote) {
            cursor += if (line[cursor] == '\\') 2 else 1
        }
        if (cursor >= line.length) return null
        cursor++
        while (cursor < line.length && line[cursor].isWhitespace()) cursor++
    }
    if (cursor >= line.length || line[cursor] != ')') return null
    return (cursor + 1) to (line.substring(start + 2, labelEnd) to url)
}

/** Commits complete blocks, leaving fences and SVGs intact while their streaming tail grows. */
internal class StableMarkdownBuffer {
    private var previous = ""
    private var committedThrough = 0
    private val committed = mutableListOf<String>()

    fun update(text: String): List<String> {
        if (!text.startsWith(previous)) {
            committed.clear()
            committedThrough = 0
        }
        previous = text
        var fence: String? = null
        var svg = false
        var lineStart = committedThrough
        while (lineStart < text.length) {
            val end = text.indexOf('\n', lineStart)
            if (end < 0) break
            val line = text.substring(lineStart, end).removeSuffix("\r")
            val marker = fence
            if (marker != null) {
                if (isClosingFence(line, marker)) fence = null
            } else if (svg) {
                if (line.contains("</svg>", ignoreCase = true)) svg = false
            } else {
                fence = openingFence.matchEntire(line)?.groupValues?.get(1)
                if (fence == null && line.trimStart().startsWith("<svg", ignoreCase = true)) {
                    svg = !line.contains("</svg>", ignoreCase = true)
                }
                if (fence == null && !svg && line.isBlank()) {
                    committed += text.substring(committedThrough, end + 1)
                    committedThrough = end + 1
                }
            }
            lineStart = end + 1
        }
        return buildList {
            addAll(committed)
            if (committedThrough < text.length) add(text.substring(committedThrough))
        }
    }
}
