package dev.wawlink.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownPartsTest {
    private fun markdownParts(text: String) = MarkdownPartsBuffer().update(text)

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

    @Test fun incrementalPartsMatchFullParsingAtEveryStreamingBoundary() {
        val sources = listOf(
            "before ![图](https://example.test/a_(b).png \"title\") after\n\\![escaped](no-load)\n``multiline\n![code](no-load)\n``\n\n![visible](data:image/png;base64,AA==)",
            "前文\n  ````TypeScript\nconst x = `a`;\n```\n![literal](no-load)\n  ````\n后文\n~~~mermaid\ngraph LR\n\nA-->B\n~~~\n尾部",
            "前文\n<svg viewBox=\"0 0 2 2\">\n![provisional](https://example.test/a.png)\n```text\n\n<svg>\n```\n</svg>\n**后文**",
            "~~~unknown\nfirst\n\nsecond\n",
            "````\n```\n````\n\n    ![indented](no-load)\n\t![tab](no-load)\n",
            "# 标题\r\n\r\n~~~js\r\nconst x = 1;\r\n~~~\r\n![图](<data:image/svg+xml,%3Csvg%3E%3C/svg%3E>)",
            "开始😀\n\n`/workspace/输出 文件.apk`\n<SVG></SVG>\n尾部\r正文",
        )
        for (source in sources) {
            val buffer = MarkdownPartsBuffer()
            for (length in 0..source.length) {
                val snapshot = source.take(length)
                assertEquals(markdownParts(snapshot), buffer.update(snapshot), "prefix $length of $source")
            }
        }
    }

    @Test fun correctedAndShortenedSnapshotsDiscardParserState() {
        val snapshots = listOf(
            "前文\n\n```mermaid\ngraph LR\n\nA-->B",
            "前文\n\n```mermaid\ngraph LR\n\nA-->B\n```\n后文😀",
            "前文\n\n```mermaid\ngraph LR\n\nA-->B\n```\n后文\uD83D",
            "前文\n\n<svg>\n\n进行中",
            "前文\n\n<svg>\n\n</svg>\n新尾部",
            "前文\n\n",
            "",
            "新文本😀\n\n![图片](https://example.test/a.png)",
        )
        val parts = MarkdownPartsBuffer()
        val stable = StableMarkdownBuffer()
        for (snapshot in snapshots) {
            assertEquals(markdownParts(snapshot), parts.update(snapshot))
            assertEquals(StableMarkdownBuffer().update(snapshot), stable.update(snapshot))
        }
    }

    @Test fun stableChunksMatchFreshScanningAtEveryStreamingBoundary() {
        val sources = listOf(
            "标题\n\n段落\n\n````python\nprint('😀')\n\n```\nprint('二')\n````\n\n后文",
            "标题\r\n\r\n~~~js\r\nconst x = 1;\r\n\r\nx++;\r\n~~~\r\n\r\n后文",
            "前文\n\n<svg>\n\n<path/>\n</svg>\n\n后文\n\n\n",
            "只有一条增长的长行，没有换行😀",
        )
        for (source in sources) {
            val buffer = StableMarkdownBuffer()
            for (length in 0..source.length) {
                val snapshot = source.take(length)
                val chunks = buffer.update(snapshot)
                assertEquals(StableMarkdownBuffer().update(snapshot), chunks, "prefix $length of $source")
                assertEquals(snapshot, chunks.joinToString(""))
            }
        }
    }

    @Test fun laterStreamingUpdatesDoNotMutateEarlierSnapshots() {
        val buffer = MarkdownPartsBuffer()
        val first = buffer.update("before\n```kotlin\nval x = 1\n```\nafter")
        val second = buffer.update("before\n```kotlin\nval x = 1\n```\nafter grows")
        assertEquals(markdownParts("before\n```kotlin\nval x = 1\n```\nafter grows"), second)
        buffer.update("replacement")
        assertEquals(markdownParts("before\n```kotlin\nval x = 1\n```\nafter"), first)

        val stable = StableMarkdownBuffer()
        val chunks = stable.update("first\n\nsecond")
        val appended = stable.update("first\n\nsecond grows")
        assertEquals(listOf("first\n\n", "second grows"), appended)
        stable.update("replacement")
        assertEquals(listOf("first\n\n", "second"), chunks)
    }

}
