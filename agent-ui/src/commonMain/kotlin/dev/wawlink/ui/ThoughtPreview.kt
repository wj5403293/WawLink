package dev.wawlink.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal expect fun firstStrongTextDirection(text: String): TextDirection?

/** Shape the visible end/head, not an unbounded paragraph containing the entire thought. */
@Composable
internal fun ThoughtPreview(text: String, tail: Boolean) {
    val layoutDirection = LocalLayoutDirection.current
    val direction = remember(text, layoutDirection) {
        firstStrongTextDirection(text) ?: if (layoutDirection == LayoutDirection.Rtl) TextDirection.Rtl else TextDirection.Ltr
    }
    val style = LocalTextStyle.current.copy(
        color = LocalAgentColors.current.onSurfaceVariant,
        fontSize = 17.sp,
        textDirection = direction,
    )
    val measurer = rememberTextMeasurer(cacheSize = 0)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = constraints.maxWidth
        val window = remember(text, tail, width, style, measurer) {
            previewWindow(text, tail, width) { candidate ->
                measurer.measure(candidate, style, softWrap = false, maxLines = 1,
                    constraints = Constraints(maxWidth = width.coerceAtMost(Constraints.Infinity / 3) * 3))
            }
        }
        val semantics = Modifier.clearAndSetSemantics { this.text = AnnotatedString(text) }
        if (tail) {
            Box(Modifier.fillMaxWidth().graphicsLayer {
                clip = true
                compositingStrategy = CompositingStrategy.Offscreen
            }.drawWithContent {
                drawContent()
                if (window.overflow) drawRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), endX = 48.dp.toPx()),
                    blendMode = BlendMode.DstIn,
                )
            }) {
                Text(window.text, semantics.fillMaxWidth().wrapContentWidth(
                    if (window.overflow) Alignment.End else Alignment.Start, unbounded = true),
                    style = style, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
            }
        } else {
            Text(window.text, semantics.fillMaxWidth(), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private data class PreviewWindow(val text: String, val overflow: Boolean)

private fun previewWindow(
    source: String,
    tail: Boolean,
    width: Int,
    measure: (String) -> TextLayoutResult,
): PreviewWindow {
    if (source.isEmpty() || width <= 0) return PreviewWindow("", false)
    var count = minOf(128, source.length)
    while (true) {
        var start = if (tail) source.length - count else 0
        var end = if (tail) source.length else count
        if (start > 0 && source[start].isLowSurrogate() && source[start - 1].isHighSurrogate()) start--
        if (end < source.length && source[end - 1].isHighSurrogate() && source[end].isLowSurrogate()) end++
        val candidate = source.substring(start, end).let { if (tail) it.replace('\n', ' ') else it }
        val layout = measure(candidate)
        val lineBreak = layout.multiParagraph.didExceedMaxLines
        val overflow = layout.didOverflowWidth || layout.size.width > width || lineBreak
        // Two viewport widths keep shaping/cluster boundaries outside the visible end.
        if (count == source.length || layout.size.width >= width * 2f || lineBreak) return PreviewWindow(candidate, overflow)
        count = if (count >= source.length - count) source.length else count * 2
    }
}
