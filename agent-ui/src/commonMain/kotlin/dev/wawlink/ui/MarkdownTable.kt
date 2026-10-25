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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
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

@Immutable
internal data class MarkdownTableData(
    val rows: List<List<String>>,
    val alignments: List<MarkdownColumnAlignment>,
    val nextLine: Int,
)

private val tableDelimiter = Regex(":?-{3,}:?")

/** Separators inside escaped text or a complete code span belong to the cell. */
internal fun markdownTableCells(line: String): List<String>? {
    if ('|' !in line) return null
    var codeRuns: Map<Int, List<Int>>? = null
    val separators = mutableListOf<Int>()
    var cursor = 0
    while (cursor < line.length) {
        when (line[cursor]) {
            '\\' -> cursor += if (cursor + 1 < line.length) 2 else 1
            '`' -> {
                val start = cursor
                while (cursor < line.length && line[cursor] == '`') cursor++
                val ticks = cursor - start
                val runs = codeRuns ?: backtickRunEnds(line).also { codeRuns = it }
                val ends = runs[ticks]
                if (ends != null) {
                    val found = ends.binarySearch(cursor)
                    val next = if (found >= 0) found + 1 else -found - 1
                    if (next < ends.size) cursor = ends[next]
                }
            }
            '|' -> { separators += cursor; cursor++ }
            else -> cursor++
        }
    }
    if (separators.isEmpty()) return null
    val cells = mutableListOf<String>()
    var start = 0
    separators.forEach { separator ->
        cells += line.substring(start, separator).trim().replace("\\|", "|")
        start = separator + 1
    }
    cells += line.substring(start).trim().replace("\\|", "|")
    if (cells.first().isEmpty()) cells.removeAt(0)
    if (cells.lastOrNull()?.isEmpty() == true) cells.removeAt(cells.lastIndex)
    return cells
}

/** Index raw tick runs once: code-span closers can contain escaped ticks too. */
private fun backtickRunEnds(line: String): Map<Int, List<Int>> {
    val runs = mutableMapOf<Int, MutableList<Int>>()
    var cursor = 0
    while (cursor < line.length) {
        if (line[cursor] != '`') { cursor++; continue }
        val start = cursor
        while (cursor < line.length && line[cursor] == '`') cursor++
        runs.getOrPut(cursor - start) { mutableListOf() } += cursor
    }
    return runs
}
internal fun markdownTable(
    lines: List<String>,
    start: Int,
    previous: MarkdownTableData? = null,
    unchangedThroughLine: Int = start,
): MarkdownTableData? {
    if (start + 1 >= lines.size) return null
    val reusable = previous?.takeIf { unchangedThroughLine > start + 1 }
    val headings: List<String>
    val alignments: List<MarkdownColumnAlignment>
    if (reusable != null) {
        headings = reusable.rows.first()
        alignments = reusable.alignments
    } else {
        headings = markdownTableCells(lines[start]) ?: return null
        val delimiters = markdownTableCells(lines[start + 1]) ?: return null
        if (headings.isEmpty() || headings.size != delimiters.size || delimiters.any { !tableDelimiter.matches(it) }) return null
        alignments = delimiters.map {
            when {
                it.startsWith(':') && it.endsWith(':') -> MarkdownColumnAlignment.Center
                it.endsWith(':') -> MarkdownColumnAlignment.Right
                else -> MarkdownColumnAlignment.Left
            }
        }
    }
    val rows = mutableListOf(headings)
    var next = start + 2
    if (reusable != null) {
        val count = (minOf(unchangedThroughLine, reusable.nextLine) - next).coerceAtLeast(0)
        for (row in 1..count) rows += reusable.rows[row]
        next += count
    }
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
                        val row = remember(cells, table.alignments) { MarkdownTableRowModel(cells, table.alignments) }
                        MarkdownTableRow(row, header = rowIndex == 0)
                    }
                }
            }
        }
    }
}

@Immutable
private class MarkdownTableRowModel(val cells: List<String>, val alignments: List<MarkdownColumnAlignment>)

@Composable
private fun MarkdownTableRow(row: MarkdownTableRowModel, header: Boolean) {
    val colors = LocalAgentColors.current
    val dark = LocalAgentAppearance.current.darkTheme
    val tint = if (dark) Color.White else colors.onSurface
    val grid = tint.copy(alpha = .07f)
    val columns = row.alignments.size
    val style = MaterialTheme.typography.bodyLarge.copy(
        fontSize = if (header) 13.sp else 13.5.sp,
        lineHeight = (if (header) 13f else 13.5f).times(1.45f).sp,
        fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
        color = colors.onSurface.copy(alpha = if (header) 1f else .88f),
    )
    Layout(
        modifier = Modifier.fillMaxWidth().drawBehind {
            if (header) drawRect(tint.copy(alpha = if (dark) .06f else .04f))
            val stroke = .5.dp.toPx()
            if (!header) drawLine(grid, Offset.Zero, Offset(size.width, 0f), stroke)
            for (column in 1 until columns) {
                val x = size.width * column / columns
                drawLine(grid, Offset(x, 0f), Offset(x, size.height), stroke)
            }
        },
        content = {
            row.cells.forEachIndexed { column, cell ->
                InlineMarkdownText(cell, style = style.copy(textAlign = when (row.alignments[column]) {
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
