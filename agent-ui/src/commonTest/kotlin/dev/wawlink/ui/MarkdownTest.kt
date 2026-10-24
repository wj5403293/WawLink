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

    @Test fun inlineAndParenthesizedFilesKeepDisplayedOffsetsAndGuestPaths() {
        val absolute = "/workspace/demo/app/build/outputs/apk/release/app-release.apk"
        val relative = "app/build/outputs/apk/release/app-release.apk"
        val parsed = inline("**产物**：`$absolute`，app-release.apk ($relative)")
        assertEquals("产物：$absolute，app-release.apk ($relative)", parsed.text)
        val files = parsed.getStringAnnotations("FILE", 0, parsed.length)
        assertEquals(listOf(absolute, relative), files.map { it.item })
        assertEquals(listOf(absolute, "app-release.apk ($relative)"), files.map { parsed.text.substring(it.start, it.end) })
        assertEquals(relative, parsed.getStringAnnotations("FILE", files.last().start + 2, files.last().start + 2).single().item)
    }

    @Test fun explicitFileLinksStaySeparateFromWebLinksAndNeverOpenHostUris() {
        val parsed = inline("[配置](./项目/settings.json) [说明](README.md) [网站](https://example.org/a.apk) [宿主](file:///tmp/private.apk) [脚本](javascript:alert)")
        assertEquals(listOf("./项目/settings.json", "README.md"), parsed.getStringAnnotations("FILE", 0, parsed.length).map { it.item })
        assertEquals(listOf("https://example.org/a.apk"), parsed.getStringAnnotations("URL", 0, parsed.length).map { it.item })
        assertEquals("配置 说明 网站 宿主 脚本", parsed.text)
    }

    @Test fun ordinaryCodeAndMismatchedParenthesesAreNotFiles() {
        val parsed = inline("`git status` `node app/main.js` `1.5` `model.id` `https://example.org/file.apk` 版本 (1.5) app.apk (build/other.apk)")
        assertTrue(parsed.getStringAnnotations("FILE", 0, parsed.length).isEmpty())
        assertTrue(parsed.getStringAnnotations("URL", 0, parsed.length).isEmpty())
    }

    @Test fun quotedUnicodePathsAndChineseParenthesesPreserveOriginalText() {
        val absolute = "/workspace/我的项目/输出 文件.apk"
        val parsed = inline("`$absolute` app.apk（build/app.apk）")
        assertEquals("$absolute app.apk（build/app.apk）", parsed.text)
        assertEquals(listOf(absolute, "build/app.apk"), parsed.getStringAnnotations("FILE", 0, parsed.length).map { it.item })
    }
}
