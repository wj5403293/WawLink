package dev.wawlink.ui

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** JVM-only real Sora/TM4E tokenizer coverage; no emulator, Compose view, model, or network. */
class OfflineTextMateTokenizerTest {
    private fun resource(path: String): ByteArray = checkNotNull(javaClass.classLoader!!.getResourceAsStream("code/textmate/$path")) {
        "Missing offline TextMate resource: $path"
    }.use { it.readBytes() }

    private fun tokenizer(dark: Boolean = false) = OfflineTextMateTokenizer(
        resource("index.json").decodeToString(), ::resource, dark,
    )

    private fun assertScope(tokens: List<OfflineTextMateTokenizer.Token>, text: String, needle: String, scope: String) {
        val at = text.indexOf(needle)
        assertTrue(at >= 0, "Missing test needle $needle")
        assertTrue(tokens.any { at in it.start until it.end && it.scopes.any { name -> name.startsWith(scope) } },
            "$needle should carry $scope, got ${tokens.filter { at in it.start until it.end }}")
    }

    @Test fun htmlLoadsRealEmbeddedJavascriptAndCssWithMultilineState() {
        val text = """
            <!doctype html>
            <style>
            body { color: red; }
            </style>
            <script>
            /* comment
               continues */
            const pattern = /ab+/gi;
            </script>
            <div title="raw &amp; value">😀</div>
        """.trimIndent()
        val tokens = tokenizer().tokenize("html", text)
        assertScope(tokens, text, "body", "source.css")
        assertScope(tokens, text, "const", "source.js")
        assertScope(tokens, text, "continues", "comment.block")
        assertScope(tokens, text, "ab+", "string.regexp")
        assertScope(tokens, text, "div", "entity.name.tag")
    }

    @Test fun multilineStringsCommentsAndTemplateInterpolationKeepState() {
        val examples = listOf(
            Triple("python", "value = \"\"\"first\nsecond\"\"\"\nprint(value)", "second"),
            Triple("rust", "let value = r###\"first\nsecond\"###;", "second"),
            Triple("lua", "local value = [==[first\nsecond]==]", "second"),
            Triple("shell", "cat <<'EOF'\nsecond\nEOF\necho done", "second"),
        )
        val tokenizer = tokenizer()
        for ((language, text, needle) in examples) assertScope(tokenizer.tokenize(language, text), text, needle, "string")
        val js = "const value = `raw \${1 + 2}`; /* first\nsecond */ const quotient = a / b;"
        val tokens = tokenizer.tokenize("javascript", js)
        assertScope(tokens, js, "raw", "string.template")
        assertScope(tokens, js, "second", "comment.block")
        assertFalse(tokens.filter { js.indexOf("a / b") in it.start until it.end }.any { token ->
            token.scopes.any { it.startsWith("comment") || it.startsWith("string.regexp") }
        })
    }

    @Test fun tsxRetainsNativeTypeAndMarkupTokens() {
        val tsx = "interface Props { name: string }; const view = <div title=\"hello\">{props.name}</div>;"
        val tokens = tokenizer().tokenize("tsx", tsx)
        assertScope(tokens, tsx, "interface", "storage.type")
        assertScope(tokens, tsx, "div", "entity.name.tag")
    }

    @Test fun markdownFencesGiveKeywordsAndStringsDistinctColors() = runBlocking {
        val text = "# Heading\n```javascript\nconst value = 'inside';\n```\n"
        val result = tokenizer().highlight("markdown", text)
        fun colorAt(needle: String) = result.spanStyles.lastOrNull { text.indexOf(needle) in it.start until it.end }?.item?.color
        assertNotEquals(colorAt("const"), colorAt("'inside'"), "Fenced code must not remain uniformly styled Markdown body text")
    }

    @Test fun csharpAwaitRecognizesExpressionsButNotDottedMembers() {
        val tokenizer = tokenizer()
        val text = "class C { async Task M() { await First(); return await Second(); var value = await Third(); await A(); await B(); } }"
        val tokens = tokenizer.tokenize("csharp", text)
        for (match in Regex("\\bawait\\b").findAll(text)) assertTrue(tokens.any { token ->
            match.range.first in token.start until token.end && token.scopes.any { it.startsWith("keyword.operator.expression.await") }
        })
        val members = "class C { void M() { obj.await(); obj.   await(); } }"
        val memberTokens = tokenizer.tokenize("csharp", members)
        for (match in Regex("\\bawait\\b").findAll(members)) assertFalse(memberTokens.any { token ->
            match.range.first in token.start until token.end && token.scopes.any { it.startsWith("keyword.operator.expression.await") }
        })
    }

    @Test fun unregisteredLanguageRemainsUnstyled() = runBlocking {
        val tokenizer = tokenizer()
        assertTrue(tokenizer.tokenize("unregistered-language", "<script>let x = 1</script>").isEmpty())
        assertTrue(tokenizer.highlight("unregistered-language", "<b>plain</b>").spanStyles.isEmpty())
    }

    @Test fun xmlCommentsTerminateAndYamlTagDirectivesKeepTheirScopes() {
        val tokenizer = tokenizer()
        val xml = "<%-- hidden --%><visible/>"
        val xmlTokens = tokenizer.tokenize("xml", xml)
        assertScope(xmlTokens, xml, "hidden", "comment.block.xml")
        assertScope(xmlTokens, xml, "visible", "entity.name.tag")
        for (version in listOf("1.1", "1.2")) {
            val yaml = "%YAML $version\n%TAG !foo! tag:example.com,2000:app/\n---\n!foo!name value\n"
            assertScope(tokenizer.tokenize("yaml", yaml), yaml, "TAG", "keyword.other.directive.tag.yaml")
        }
    }

    @Test fun bothBundledThemesProduceStylesWithoutChangingRawCrLfOrUtf16Offsets() = runBlocking {
        val text = "<div title=\"😀\">raw &amp; text</div>\r\n<script>const n = 42;</script>\r\n"
        for (dark in listOf(false, true)) {
            val highlighted = tokenizer(dark).highlight("html", text)
            assertEquals(text, highlighted.text)
            fun colorAt(index: Int) = highlighted.spanStyles.lastOrNull { index in it.start until it.end }?.item?.color
            assertNotEquals(colorAt(text.indexOf("const")), colorAt(text.indexOf("42")), "Keywords and numeric literals must remain visibly distinct")
            assertTrue(highlighted.spanStyles.all { it.start >= 0 && it.end <= text.length && it.start < it.end })
        }
    }

}
