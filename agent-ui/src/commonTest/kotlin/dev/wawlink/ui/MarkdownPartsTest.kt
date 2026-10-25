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
    @Test fun fencedCodeRetainsExactSourceAndDoesNotLoadEmbeddedGraphics() {
        val source = "before\n  ````TypeScript\nconst value = `x`;\n```\n![literal](file:///private.png)\n  ````\nafter"
        val parts = markdownParts(source)
        val code = parts.filterIsInstance<MarkdownPart.Code>().single()
        assertEquals("typescript", code.language)
        assertEquals("const value = `x`;\n```\n![literal](file:///private.png)", code.text)
        assertTrue(parts.none { it is MarkdownPart.Graphic })
        assertTrue(parts.filterIsInstance<MarkdownPart.Text>().any { "after" in it.text })
    }

    @Test fun unfinishedFenceRetainsTrailingNewlineAndUnknownLanguage() {
        val part = markdownParts("~~~unknown\nfirst\n").filterIsInstance<MarkdownPart.Code>().single()
        assertEquals("unknown", part.language)
        assertEquals("first\n", part.text)
        val diagram = markdownParts("```mermaid\ngraph LR; A-->B").filterIsInstance<MarkdownPart.Code>().single()
        assertEquals("mermaid", diagram.language)
    }

    @Test fun explicitUnknownFenceCannotBeReclassifiedByItsShebang() {
        val code = markdownParts("```unknown-runtime\n#!/usr/bin/python3\nprint('literal')\n```")
            .filterIsInstance<MarkdownPart.Code>().single()
        assertEquals("unknown-runtime", code.language)
        assertEquals(null, inferCodeLanguage(explicit = code.language, text = code.text))
        val unnamed = markdownParts("```\n#!/usr/bin/python3\nprint('inferred')\n```")
            .filterIsInstance<MarkdownPart.Code>().single()
        assertEquals("python", inferCodeLanguage(explicit = unnamed.language, text = unnamed.text))
    }

    @Test fun emptyFenceIsStillOneCodeBlock() {
        val part = markdownParts("```\n```").filterIsInstance<MarkdownPart.Code>().single()
        assertEquals("", part.text)
        assertEquals(null, part.language)
    }

    @Test fun growingFenceKeepsCompletedParagraphsAndResetsOnRewrite() {
        val buffer = StableMarkdownBuffer()
        val prefix = "前文\n\n```kotlin\nval a = 1\n"
        val first = buffer.update(prefix)
        assertEquals(listOf("前文\n\n", "```kotlin\nval a = 1\n"), first)
        assertEquals(first, buffer.update(prefix))
        assertEquals(listOf("前文\n\n", "```kotlin\nval a = 1\n```\n\n", "后文"),
            buffer.update(prefix + "```\n\n后文"))
        assertEquals(listOf("新文\n\n", "结尾"), buffer.update("新文\n\n结尾"))
        assertEquals(listOf("前文\n\n", "```kotlin\nval a = 1\n"), first)
    }

    @Test fun streamedSvgDoesNotSplitAtInteriorBlankLines() {
        val buffer = StableMarkdownBuffer()
        val prefix = "<svg>\r\n\r\n<path d=\"M0 0L1 1\"/>\r\n"
        assertEquals(listOf(prefix), buffer.update(prefix))
        assertEquals(listOf(prefix + "</svg>\r\n\r\n", "正文"), buffer.update(prefix + "</svg>\r\n\r\n正文"))
    }

}
