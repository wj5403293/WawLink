package dev.wawlink.ui

import dev.wawlink.protocol.DiffLine
import dev.wawlink.protocol.DiffLineKind
import dev.wawlink.protocol.OutputLine
import dev.wawlink.protocol.ToolDetail
import dev.wawlink.protocol.ToolNodeItem
import kotlin.test.Test
import kotlin.test.assertEquals

class FeedPresentationTest {
    @Test fun elapsedHidesSubsecondAndSwitchesAtOneMinute() {
        assertEquals("", elapsed(0))
        assertEquals("", elapsed(-1))
        assertEquals("1s", elapsed(1))
        assertEquals("59s", elapsed(59))
        assertEquals("1:00", elapsed(60))
        assertEquals("1:01", elapsed(61))
        assertEquals("61:02", elapsed(3662))
    }

    @Test fun liveElapsedAdvancesWithoutEventsAndTerminalTimeStaysFrozen() {
        assertEquals(0, runningElapsed(0, 10_000, true, 10_999))
        assertEquals(15, runningElapsed(0, 10_000, true, 25_000))
        assertEquals(4, runningElapsed(4, 10_000, false, 120_000))
        assertEquals(4, runningElapsed(4, null, true, 120_000))
        assertEquals(4, runningElapsed(4, 10_000, true, 5_000))
    }

    @Test fun liveElapsedKeepsExactSecondBoundariesAndReportedMinimum() {
        assertEquals(1, runningElapsed(0, 10_000, true, 11_000))
        assertEquals(59, runningElapsed(0, 10_000, true, 69_999))
        assertEquals(60, runningElapsed(0, 10_000, true, 70_000))
        assertEquals(65, runningElapsed(65, 10_000, true, 70_000))
        assertEquals(Int.MAX_VALUE, runningElapsed(0, 0, true, Long.MAX_VALUE))
        assertEquals(0, runningElapsed(-1, 10_000, false, 70_000))
    }

    @Test fun copyUsesRawThenFullSheetThenCompleteDetailWithoutDisplaySubstitution() {
        val detail = ToolDetail.CommandAndOutput("printf '原文'", listOf(OutputLine("第一行"), OutputLine("错误", isError = true), OutputLine("")))
        val node = ToolNodeItem("tool", titleHtml = "命令", detailContent = detail,
            rawCopyText = "原始\r\n文本\n", fullSheetText = "完整详情\n", codeText = "仅代码", resultText = "额外结果")
        assertEquals("原始\r\n文本\n", node.copyText())
        assertEquals("完整详情\n", node.copy(rawCopyText = "").copyText())
        assertEquals("printf '原文'\n\n第一行\n错误\n", node.copy(rawCopyText = "", fullSheetText = "").copyText())
        assertEquals(" ", node.copy(rawCopyText = " ").copyText())
    }

    @Test fun copiedDiffRetainsRawPrefixesRatherThanVisualMinus() {
        val detail = ToolDetail.DiffBlock(listOf(
            DiffLine("添加", DiffLineKind.ADDED), DiffLine("移除", DiffLineKind.REMOVED), DiffLine("上下文"),
        ))
        assertEquals("+添加\n-移除\n 上下文", ToolNodeItem("diff", titleHtml = "差异", detailContent = detail).copyText())
    }

    @Test fun committedMarkdownPrefixRemainsIdenticalWhileTailGrows() {
        val buffer = StableMarkdownBuffer()
        assertEquals(listOf("# 规划\n\n", "第一段"), buffer.update("# 规划\n\n第一段"))
        assertEquals(listOf("# 规划\n\n", "第一段继续\n\n", "新的思考"), buffer.update("# 规划\n\n第一段继续\n\n新的思考"))
    }

    @Test fun codeFencesAndRawSvgKeepInternalBlankLinesInOneBlock() {
        for (text in listOf(
            "前文\n\n````python\nprint('一')\n\n```\nprint('二')\n````\n\n后文",
            "前文\r\n\r\n~~~js\r\nconst x = 1;\r\n\r\nx++;\r\n~~~\r\n\r\n后文",
            "前文\n\n<svg viewBox=\"0 0 2 2\">\n\n<path d=\"M0 0L1 1\"/>\n</svg>\n\n后文",
        )) {
            val buffer = StableMarkdownBuffer()
            var chunks = emptyList<String>()
            for (length in 1..text.length) chunks = buffer.update(text.take(length))
            assertEquals(text, chunks.joinToString(""))
            assertEquals("后文", chunks.last())
            assertEquals(text.substringAfter("前文").trimStart('\r', '\n').substringBeforeLast("后文"), chunks[1])
        }
    }

    @Test fun correctedSnapshotsReplaceOldContentWithoutDuplicates() {
        val buffer = StableMarkdownBuffer()
        buffer.update("原计划\n\n先读文件\n\n进行中")
        val replacement = "新计划\n\n先查目录\n\n继续"
        assertEquals(replacement, buffer.update(replacement).joinToString(""))
        assertEquals(replacement, buffer.update(replacement).joinToString(""))
    }
}
