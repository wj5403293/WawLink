package dev.wawlink.ui

import androidx.compose.runtime.Immutable

/** Graphics are separated before the existing native Markdown renderer sees inline syntax. */
@Immutable
internal sealed interface MarkdownPart {
    data class Text(val text: String) : MarkdownPart
    data class Code(val text: String, val language: String?) : MarkdownPart
    data class Graphic(val kind: GraphicKind, val source: String, val description: String = "") : MarkdownPart
}

internal enum class GraphicKind { Svg, Mermaid, Image }

private val openingFence = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
private val escapedImageParentheses = Regex("\\\\([()])")


private fun isClosingFence(line: String, marker: String): Boolean = isClosingFence(line, 0, line.length, marker)

private fun isClosingFence(text: String, start: Int, end: Int, marker: String): Boolean {
    var cursor = start
    while (cursor < end && text[cursor] == ' ') cursor++
    if (cursor - start > 3) return false
    val run = cursor
    while (cursor < end && text[cursor] == marker[0]) cursor++
    if (cursor - run < marker.length) return false
    while (cursor < end) if (!text[cursor++].isWhitespace()) return false
    return true
}

private fun hasSvgClosingTag(text: String, start: Int, end: Int): Boolean {
    var cursor = start
    while (cursor <= end - 6) {
        if (text[cursor] == '<' && text.regionMatches(cursor, "</svg>", 0, 6, ignoreCase = true)) return true
        cursor++
    }
    return false
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
    val url = line.substring(urlStart, cursor).replace(escapedImageParentheses, "$1")
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

/** Keeps completed lines/parts, replaying only the unfinished line for a new stream snapshot. */
internal class MarkdownPartsBuffer(private val recognizeSvg: Boolean = true) {
    private data class Fence(val marker: String, val language: String?, val contentStart: Int)
    private class Svg(val start: Int) {
        var fallback: MarkdownPartsBuffer? = null
    }

    private var previous = ""
    private var result = emptyList<MarkdownPart>()
    private val parts = mutableListOf<MarkdownPart>()
    private val plain = StringBuilder()
    private var codeTicks = 0
    private var fence: Fence? = null
    private var svg: Svg? = null
    private var lineStart = 0
    private var searchedThrough = 0
    private var previewStart: Int? = null

    fun update(text: String): List<MarkdownPart> {
        if (text == previous) return result
        if (!text.startsWith(previous)) {
            parts.clear()
            plain.clear()
            codeTicks = 0
            fence = null
            svg = null
            lineStart = 0
            searchedThrough = 0
        }
        var end = text.indexOf('\n', searchedThrough)
        while (end >= 0) {
            consumeLine(text, lineStart, end, terminated = true)
            lineStart = end + 1
            end = text.indexOf('\n', lineStart)
        }
        searchedThrough = text.length
        previous = text

        // A final line can still become a fence, image or closing marker. Preview it without
        // committing its parser state; completed source lines never need to be replayed.
        val count = parts.size
        val length = plain.length
        val ticks = codeTicks
        val pendingFence = fence
        val pendingSvg = svg
        previewStart = 0
        try {
            consumeLine(text, lineStart, text.length, terminated = false)
            fence?.let { parts += fencedPart(it, text.substring(it.contentStart), closed = false) }
            svg?.let { pending ->
                // Until </svg> exists the original parser treats this as ordinary Markdown.
                // No nested raw SVG can be complete either, so its provisional parser can
                // skip SVG lookahead while still recognizing code, images and inline spans.
                val fallback = pending.fallback ?: MarkdownPartsBuffer(recognizeSvg = false).also { pending.fallback = it }
                fallback.update(text.substring(pending.start)).forEach { part ->
                    if (part is MarkdownPart.Text) plain.append(part.text)
                    else {
                        flushPlain()
                        parts += part
                    }
                }
            }
            flushPlain()
            result = parts.toList()
        } finally {
            while (parts.size > count) parts.removeAt(parts.lastIndex)
            plain.setLength(length)
            codeTicks = ticks
            fence = pendingFence
            svg = pendingSvg
            previewStart = null
        }
        return result
    }

    private fun flushPlain() {
        val start = previewStart ?: 0
        if (plain.length > start) {
            parts += MarkdownPart.Text(if (start == 0) plain.toString() else plain.substring(start))
        }
        if (previewStart == null) plain.clear() else previewStart = plain.length
    }

    private fun fencedPart(fence: Fence, source: String, closed: Boolean): MarkdownPart {
        val kind = when (fence.language) {
            "svg", "image/svg+xml" -> GraphicKind.Svg
            "mermaid" -> GraphicKind.Mermaid
            else -> null
        }
        return if (kind != null && closed) MarkdownPart.Graphic(kind, source)
        else MarkdownPart.Code(source, fence.language)
    }

    private fun consumeLine(text: String, start: Int, end: Int, terminated: Boolean) {
        fence?.let { pending ->
            if (isClosingFence(text, start, end, pending.marker)) {
                parts += fencedPart(pending, text.substring(pending.contentStart, maxOf(pending.contentStart, start - 1)), closed = true)
                fence = null
            }
            return
        }
        svg?.let { pending ->
            if (hasSvgClosingTag(text, start, end)) {
                flushPlain()
                parts += MarkdownPart.Graphic(GraphicKind.Svg, text.substring(pending.start, end))
                svg = null
            }
            return
        }
        val line = text.substring(start, end)
        openingFence.matchEntire(line)?.let { opening ->
            codeTicks = 0
            flushPlain()
            fence = Fence(opening.groupValues[1], opening.groupValues[2].trim().lowercase().takeIf(String::isNotEmpty), if (terminated) end + 1 else end)
            return
        }
        if (line.isBlank()) codeTicks = 0
        if (line.startsWith("    ") || line.startsWith('\t')) {
            plain.append(line)
        } else if (recognizeSvg && codeTicks == 0 && line.trimStart().startsWith("<svg", ignoreCase = true)) {
            if (line.contains("</svg>", ignoreCase = true)) {
                flushPlain()
                parts += MarkdownPart.Graphic(GraphicKind.Svg, line)
            } else svg = Svg(start)
            return
        } else {
            var cursor = 0
            while (cursor < line.length) {
                when {
                    line[cursor] == '\\' && cursor + 1 < line.length -> {
                        plain.append(line, cursor, cursor + 2)
                        cursor += 2
                    }
                    line[cursor] == '`' -> {
                        var next = cursor + 1
                        while (next < line.length && line[next] == '`') next++
                        val ticks = next - cursor
                        if (codeTicks == 0) codeTicks = ticks else if (codeTicks == ticks) codeTicks = 0
                        plain.append(line, cursor, next)
                        cursor = next
                    }
                    else -> {
                        val image = if (codeTicks == 0 && line.startsWith("![", cursor)) imageAt(line, cursor) else null
                        if (image != null) {
                            flushPlain()
                            parts += MarkdownPart.Graphic(GraphicKind.Image, image.second.second, image.second.first)
                            cursor = image.first
                        } else plain.append(line[cursor++])
                    }
                }
            }
        }
        if (terminated) plain.append('\n')
    }
}

/** Commits complete blocks, leaving fences and SVGs intact while their streaming tail grows. */
internal class StableMarkdownBuffer {
    private var previous = ""
    private var committedThrough = 0
    private val committed = mutableListOf<String>()
    private var scannedThrough = 0
    private var searchedThrough = 0
    private var fence: String? = null
    private var svg = false
    private var result = emptyList<String>()

    fun update(text: String): List<String> {
        if (text == previous) return result
        if (!text.startsWith(previous)) {
            committed.clear()
            committedThrough = 0
            scannedThrough = 0
            searchedThrough = 0
            fence = null
            svg = false
        }
        previous = text
        var lineStart = scannedThrough
        var end = text.indexOf('\n', searchedThrough)
        while (end >= 0) {
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
            scannedThrough = lineStart
            end = text.indexOf('\n', lineStart)
        }
        searchedThrough = text.length
        result = buildList {
            addAll(committed)
            if (committedThrough < text.length) add(text.substring(committedThrough))
        }
        return result
    }
}
