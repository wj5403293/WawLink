package dev.wawlink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp

private val inlineContentTag = buildAnnotatedString { appendInlineContent("code", " ") }
    .getStringAnnotations(0, 1).single().tag

/** Inline replacements annotate the original characters; copying never sees decorative spacing. */
internal fun inlineCodeContent(text: AnnotatedString, fragments: List<IntRange>): AnnotatedString {
    if (fragments.isEmpty()) return text
    val builder = AnnotatedString.Builder(text)
    fragments.forEachIndexed { index, range ->
        builder.addStringAnnotation(inlineContentTag, "code-$index", range.first, range.last + 1)
    }
    return builder.toAnnotatedString()
}

internal fun inlineLinkAt(text: AnnotatedString, offset: Int): AnnotatedString.Range<String>? =
    text.getStringAnnotations("FILE", offset, offset).firstOrNull { offset >= it.start && offset < it.end }
        ?: text.getStringAnnotations("URL", offset, offset).firstOrNull { offset >= it.start && offset < it.end }

@Composable
internal fun InlineMarkdownText(text: String, modifier: Modifier = Modifier, style: TextStyle) {
    val colors = LocalAgentColors.current
    val mono = LocalAgentMono.current
    val dark = LocalAgentAppearance.current.darkTheme
    val density = LocalDensity.current
    val codeSize = (if (style.fontSize.isUnspecified) 18.sp else style.fontSize) * .86f
    val codeStyle = style.copy(
        color = colors.onSurface.copy(alpha = .92f),
        fontFamily = mono,
        fontSize = codeSize,
        lineHeight = codeSize,
        textDecoration = TextDecoration.None,
    )
    val annotated = remember(text, colors, mono, codeSize) {
        buildInline(text, SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline),
            SpanStyle(color = colors.onSurface.copy(alpha = .92f), fontFamily = mono, fontSize = codeSize))
    }
    val codes = remember(annotated) { annotated.getStringAnnotations("CODE", 0, annotated.length) }
    val measurer = if (codes.isEmpty()) null else rememberTextMeasurer()
    val background = (if (dark) Color.White else colors.onSurface).copy(alpha = if (dark) .10f else .06f)
    val fileHandler = LocalAgentFileLinkHandler.current
    val uri = LocalUriHandler.current
    val externalContent = LocalMarkdownExternalContent.current
    BoxWithConstraints(modifier) {
        val horizontal = with(density) { 5.dp.roundToPx() }
        val vertical = with(density) { 3.dp.roundToPx() }
        val available = if (constraints.hasBoundedWidth) (constraints.maxWidth - horizontal * 2).coerceAtLeast(1) else Constraints.Infinity
        val fragments = remember(annotated, codeStyle, available, measurer) {
            buildList {
                if (measurer != null) codes.forEach { code ->
                    val source = annotated.text.substring(code.start, code.end)
                    val measured = measurer.measure(source, codeStyle, constraints = Constraints(maxWidth = available))
                    var start = 0
                    for (line in 0 until measured.lineCount) {
                        val end = measured.getLineEnd(line)
                        if (end > start) add((code.start + start) until (code.start + end))
                        start = end
                    }
                }
            }
        }
        val displayed = remember(annotated, fragments) { inlineCodeContent(annotated, fragments) }
        val content = remember(annotated, fragments, codeStyle, density, background, measurer) {
            fragments.mapIndexed { index, range ->
                val source = annotated.text.substring(range)
                val linked = inlineLinkAt(annotated, range.first)?.tag == "FILE"
                val fragmentStyle = if (linked) codeStyle.copy(textDecoration = TextDecoration.Underline) else codeStyle
                val measured = checkNotNull(measurer).measure(source, fragmentStyle, softWrap = false)
                "code-$index" to InlineTextContent(
                    Placeholder(
                        width = with(density) { (measured.size.width + horizontal * 2).toSp() },
                        height = with(density) { (measured.size.height + vertical * 2).toSp() },
                        placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                    ),
                ) { original ->
                    // The parent remains the sole selectable, carrying every original code character.
                    DisableSelection {
                        BasicText(original, Modifier.fillMaxSize().background(background, RoundedCornerShape(6.dp))
                            .padding(horizontal = 5.dp, vertical = 3.dp), style = fragmentStyle, softWrap = false)
                    }
                }
            }.toMap()
        }
        var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
        BasicText(
            text = displayed,
            modifier = Modifier.fillMaxWidth().pointerInput(displayed, fileHandler, uri, externalContent) {
                detectTapGestures { position ->
                    if (!externalContent) return@detectTapGestures
                    val result = layout ?: return@detectTapGestures
                    val placeholder = result.placeholderRects.indexOfFirst { it?.contains(position) == true }
                    val offset = if (placeholder >= 0) fragments[placeholder].first else result.getOffsetForPosition(position)
                    inlineLinkAt(annotated, offset)?.let { link ->
                        if (link.tag == "FILE") fileHandler?.invoke(link.item) else uri.openUri(link.item)
                    }
                }
            },
            style = style,
            inlineContent = content,
            onTextLayout = { layout = it },
        )
    }
}
