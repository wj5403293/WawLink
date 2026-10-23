package dev.wawlink.ui

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NativeCodeHighlightTest {
    @Test fun inlineHtmlUsesNativeMarkupAndEmbeddedLexersWithoutChangingRawSource() = runBlocking {
        val text = "<style>body { color: red; }</style>\r\n<script>const n = 42;</script>\n<div title=\"😀\">&amp;</div>"
        val result = highlightPlatformCode(text, "html", AgentColorScheme())
        assertEquals(text, result.text)
        fun colorAt(needle: String) = result.spanStyles.lastOrNull { text.indexOf(needle) in it.start until it.end }?.item?.color
        assertNotEquals(colorAt("const"), colorAt("42"), "Embedded script keywords and numbers must be visibly distinct")
        assertTrue(result.spanStyles.all { it.start >= 0 && it.start < it.end && it.end <= text.length })
    }

    @Test fun unknownLanguageRemainsPlain() = runBlocking {
        val text = "/* not inferred */ <b>raw</b>"
        val result = highlightPlatformCode(text, "unknown-language", AgentColorScheme())
        assertEquals(text, result.text)
        assertTrue(result.spanStyles.isEmpty())
    }

    @Test fun nativeMultilineLexicalStateSurvivesLineBoundaries() = runBlocking {
        val text = "/* first\nsecond */\nclass Example {}"
        val colors = AgentColorScheme()
        val result = highlightPlatformCode(text, "java", colors)
        fun colorAt(needle: String) = result.spanStyles.lastOrNull { text.indexOf(needle) in it.start until it.end }?.item?.color
        assertEquals(colorAt("first"), colorAt("second"), "A multiline comment must retain its token class")
        assertNotEquals(colorAt("second"), colorAt("class"), "Code after the comment must leave comment styling")
    }
}
