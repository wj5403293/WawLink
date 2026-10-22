package dev.wawlink.ui

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownTest {
    private fun inline(text: String) = buildInline(text, SpanStyle(), SpanStyle(fontFamily = FontFamily.Monospace))

    @Test fun identifiersRetainLiteralUnderscores() {
        val source = "QUEUE_EDIT_OK user__model__id 模型_思考_预算"
        val parsed = inline(source)
        assertEquals(source, parsed.text)
        assertTrue(parsed.spanStyles.none { it.item.fontStyle == FontStyle.Italic || it.item.fontWeight == FontWeight.Bold })
    }

    @Test fun standaloneUnderscoresStillEmphasizeIdentifiers() {
        val parsed = inline("_hello_world_ __hello_world__")
        assertEquals("hello_world hello_world", parsed.text)
        val italic = parsed.spanStyles.single { it.item.fontStyle == FontStyle.Italic }
        val bold = parsed.spanStyles.single { it.item.fontWeight == FontWeight.Bold }
        assertEquals("hello_world", parsed.text.substring(italic.start, italic.end))
        assertEquals("hello_world", parsed.text.substring(bold.start, bold.end))
    }

    @Test fun escapedDelimitersAndCodeSpansStayLiteral() {
        val parsed = inline("""\_literal_ \\_italic_ `QUEUE_EDIT_OK`""")
        assertEquals("_literal_ \\italic QUEUE_EDIT_OK", parsed.text)
        val italic = parsed.spanStyles.single { it.item.fontStyle == FontStyle.Italic }
        val code = parsed.spanStyles.single { it.item.fontFamily == FontFamily.Monospace }
        assertEquals("italic", parsed.text.substring(italic.start, italic.end))
        assertEquals("QUEUE_EDIT_OK", parsed.text.substring(code.start, code.end))
    }
}
