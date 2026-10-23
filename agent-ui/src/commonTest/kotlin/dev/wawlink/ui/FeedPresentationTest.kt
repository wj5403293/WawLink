package dev.wawlink.ui

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
