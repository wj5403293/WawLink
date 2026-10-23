package dev.wawlink.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownPartsTest {
    @Test fun codeAndEscapedImagesNeverBecomeLoads() {
        val source = """
            \![escaped](https://example.test/one.png)
            `![code](https://example.test/two.png)`
            ``multiline
            ![still code](https://example.test/three.png)
            ``
                ![indented code](https://example.test/four.png)
        """.trimIndent()
        assertTrue(markdownParts(source).none { it is MarkdownPart.Graphic })
    }

    @Test fun longerFenceDoesNotCloseAtAnInnerTripleFence() {
        val source = "````text\n```mermaid\ngraph LR; A-->B\n```\n![code](https://example.test/code.png)\n````\n![visible](https://example.test/visible.png)"
        val graphics = markdownParts(source).filterIsInstance<MarkdownPart.Graphic>()
        assertEquals(listOf("https://example.test/visible.png"), graphics.map { it.source })
    }

    @Test fun unclosedStreamedDiagramsStayLiteralUntilComplete() {
        val prefix = "before\n~~~mermaid\ngraph LR\nA-->B"
        assertTrue(markdownParts(prefix).none { it is MarkdownPart.Graphic })
        val completed = markdownParts("$prefix\n~~~\nafter")
        val diagram = completed.filterIsInstance<MarkdownPart.Graphic>().single()
        assertEquals(GraphicKind.Mermaid, diagram.kind)
        assertEquals("graph LR\nA-->B", diagram.source)
        assertTrue(completed.filterIsInstance<MarkdownPart.Text>().any { "after" in it.text })
    }

    @Test fun imageDestinationsRetainParenthesesAndExcludeTitles() {
        val source = "before ![图](https://example.test/plot_(v2).png \"标题\") after ![svg](<data:image/svg+xml,%3Csvg%3E%3C/svg%3E>) end"
        val parts = markdownParts(source)
        assertEquals(listOf("https://example.test/plot_(v2).png", "data:image/svg+xml,%3Csvg%3E%3C/svg%3E"),
            parts.filterIsInstance<MarkdownPart.Graphic>().map { it.source })
        assertEquals("before  after  end", parts.filterIsInstance<MarkdownPart.Text>().joinToString("") { it.text })
    }

    @Test fun svgAndFollowingMarkdownRemainSeparate() {
        val parts = markdownParts("<svg xmlns=\"http://www.w3.org/2000/svg\">\n<path d=\"M0 0L1 1\"/>\n</svg>\n**后文**")
        assertEquals(GraphicKind.Svg, (parts.first() as MarkdownPart.Graphic).kind)
        assertEquals("**后文**", (parts.last() as MarkdownPart.Text).text)
    }
}
