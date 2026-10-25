package dev.wawlink.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class MarkdownColumnAlignment { Left, Center, Right }

internal data class MarkdownTableData(
    val rows: List<List<String>>,
    val alignments: List<MarkdownColumnAlignment>,
    val nextLine: Int,
)

private val tableDelimiter = Regex(":?-{3,}:?")

/** Separators inside escaped text or a complete code span belong to the cell. */
internal fun markdownTableCells(line: String): List<String>? {
    if ('|' !in line) return null
    val separators = mutableListOf<Int>()
    var cursor = 0
    while (cursor < line.length) {
        when (line[cursor]) {
            '\\' -> cursor += if (cursor + 1 < line.length) 2 else 1
            '`' -> {
                val start = cursor
                while (cursor < line.length && line[cursor] == '`') cursor++
                val ticks = cursor - start
                var end = cursor
                var closing = -1
                while (end < line.length) {
                    if (line[end] != '`') { end++; continue }
                    val run = end
                    while (end < line.length && line[end] == '`') end++
                    if (end - run == ticks) { closing = end; break }
                }
                if (closing >= 0) cursor = closing
            }
            '|' -> { separators += cursor; cursor++ }
            else -> cursor++
        }
    }
    if (separators.isEmpty()) return null
    val cells = mutableListOf<String>()
    var start = 0
    separators.forEach { separator ->
        cells += line.substring(start, separator).trim()
        start = separator + 1
    }
    cells += line.substring(start).trim()
    if (line.substring(0, separators.first()).isBlank()) cells.removeAt(0)
    if (line.substring(separators.last() + 1).isBlank()) cells.removeAt(cells.lastIndex)
    return cells.map { it.replace("\\|", "|") }
}

internal fun markdownTable(lines: List<String>, start: Int): MarkdownTableData? {
    if (start + 1 >= lines.size) return null
    val headings = markdownTableCells(lines[start]) ?: return null
    val delimiters = markdownTableCells(lines[start + 1]) ?: return null
    if (headings.isEmpty() || headings.size != delimiters.size || delimiters.any { !tableDelimiter.matches(it) }) return null
    val alignments = delimiters.map {
        when {
            it.startsWith(':') && it.endsWith(':') -> MarkdownColumnAlignment.Center
            it.endsWith(':') -> MarkdownColumnAlignment.Right
            else -> MarkdownColumnAlignment.Left
        }
    }
    val rows = mutableListOf(headings)
    var next = start + 2
    while (next < lines.size && lines[next].isNotBlank()) {
        val cells = markdownTableCells(lines[next]) ?: break
        rows += List(headings.size) { cells.getOrElse(it) { "" } }
        next++
    }
    return MarkdownTableData(rows, alignments, next)
}

@Composable
internal fun MarkdownTable(table: MarkdownTableData) {
    val colors = LocalAgentColors.current
    val dark = LocalAgentAppearance.current.darkTheme
    val tint = if (dark) Color.White else colors.onSurface
    val grid = tint.copy(alpha = .07f)
    val columns = table.alignments.size
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columnWidth = if (maxWidth < 520.dp && columns >= 4) (maxWidth / 2.45f).coerceIn(112.dp, 178.dp) else maxWidth / columns
        val tableWidth = maxOf(maxWidth, columnWidth * columns)
        val scroll = rememberScrollState()
        Column(if (tableWidth > maxWidth) Modifier.horizontalScroll(scroll) else Modifier) {
            Surface(
                modifier = Modifier.width(tableWidth),
                color = Color.Transparent,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(.5.dp, tint.copy(alpha = .10f)),
            ) {
                Column {
                    table.rows.forEachIndexed { rowIndex, cells ->
                        val style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = if (rowIndex == 0) 13.sp else 13.5.sp,
                            lineHeight = (if (rowIndex == 0) 13f else 13.5f).times(1.45f).sp,
                            fontWeight = if (rowIndex == 0) FontWeight.SemiBold else FontWeight.Normal,
                            color = colors.onSurface.copy(alpha = if (rowIndex == 0) 1f else .88f),
                        )
                        Layout(
                            modifier = Modifier.fillMaxWidth().drawBehind {
                                if (rowIndex == 0) drawRect(tint.copy(alpha = if (dark) .06f else .04f))
                                val stroke = .5.dp.toPx()
                                if (rowIndex > 0) drawLine(grid, Offset.Zero, Offset(size.width, 0f), stroke)
                                for (column in 1 until columns) {
                                    val x = size.width * column / columns
                                    drawLine(grid, Offset(x, 0f), Offset(x, size.height), stroke)
                                }
                            },
                            content = {
                                cells.forEachIndexed { column, cell ->
                                    InlineMarkdownText(cell, style = style.copy(textAlign = when (table.alignments[column]) {
                                        MarkdownColumnAlignment.Left -> TextAlign.Left
                                        MarkdownColumnAlignment.Center -> TextAlign.Center
                                        MarkdownColumnAlignment.Right -> TextAlign.Right
                                    }))
                                }
                            },
                        ) { measurables, constraints ->
                            val horizontal = 12.dp.roundToPx()
                            val vertical = 8.dp.roundToPx()
                            val width = constraints.maxWidth
                            val placeables = measurables.mapIndexed { column, measurable ->
                                val cellWidth = width * (column + 1) / columns - width * column / columns
                                measurable.measure(Constraints.fixedWidth((cellWidth - horizontal * 2).coerceAtLeast(0)))
                            }
                            val height = (placeables.maxOfOrNull { it.height } ?: 0) + vertical * 2
                            layout(width, height) {
                                placeables.forEachIndexed { column, placeable ->
                                    placeable.placeRelative(width * column / columns + horizontal, (height - placeable.height) / 2)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
