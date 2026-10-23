package dev.wawlink.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CodeLanguageTest {
    @Test fun explicitLanguageWinsAndUnknownStaysPlain() {
        assertEquals("kotlin", inferCodeLanguage("KT", "main.py", "#!/bin/bash"))
        assertNull(inferCodeLanguage("text", "main.py", "#!/bin/bash"))
        assertNull(inferCodeLanguage("made-up-language", "main.kt", "#!/usr/bin/python3"))
    }

    @Test fun pathsHandleWindowsAliasesAndDotfilesWithoutReadingFiles() {
        assertEquals("tsx", inferCodeLanguage(path = "C:\\project\\page.TSX"))
        assertEquals("cpp", inferCodeLanguage(path = "/tmp/example.hpp"))
        assertEquals("shell", inferCodeLanguage(path = "/home/user/.bashrc"))
        assertEquals("dockerfile", inferCodeLanguage(path = "/tmp/Dockerfile.release"))
        assertNull(inferCodeLanguage(path = "/tmp/no-extension"))
    }

    @Test fun readSelectorsAndQueriesDoNotBecomePartOfTheExtension() {
        val examples = mapOf(
            "/src/file.kt:50-100" to "kotlin",
            "/src/file.py:raw:10-" to "python",
            "/src/file.kt:10-:raw" to "kotlin",
            "/src/file.kt:5-16,19,960-973" to "kotlin",
            "/src/file.py:-60" to "python",
            "/src/file.ts:50+150" to "typescript",
            "/data/records.json?q=.items[]&limit=10" to "json",
            "/data/records.json:raw?q=.items[0].python" to "json",
            "C:\\src\\main.kt:50-100" to "kotlin",
            "C:\\src\\main.py:raw:10-" to "python",
            "/tmp/archive.zip:member/file.ts:5-10:raw" to "typescript",
            "C:\\tmp\\archive.tar.gz:member/main.py:raw:10-" to "python",
            "/tmp/archive.zip:main.kt:10" to "kotlin",
            "https://example.test:8443/src/main.ts:50-100?q=ignored.py" to "typescript",
        )
        examples.forEach { (path, expected) -> assertEquals(expected, inferCodeLanguage(path = path), path) }
        assertNull(inferCodeLanguage(path = "/src/main.kt:unknown-selector"))
        assertNull(inferCodeLanguage(path = "/src/unknown?q=/pretend.py"))
    }

    @Test fun shebangAndInterpreterAreMetadataNotExecutableCommands() {
        assertEquals("python", inferCodeLanguage(text = "\uFEFF#!/usr/bin/env -S python3.12 -u\nprint('ok')"))
        assertEquals("shell", inferCodeLanguage(text = "#!/bin/bash\necho ok", interpreter = "python3"))
        assertEquals("javascript", inferCodeLanguage(interpreter = "/usr/local/bin/node --eval"))
        assertEquals("python", inferCodeLanguage(path = "unknown.ext", interpreter = "python3 -c"))
        assertNull(inferCodeLanguage(text = "print('not guessed from contents')"))
        assertNull(inferCodeLanguage(text = "#!/usr/bin/env unknown-runtime\n<script/>"))
    }

    @Test fun embeddedAndConfigurationVariantsKeepTheirRealGrammar() {
        assertEquals("html", inferCodeLanguage(path = "index.HTML:raw"))
        assertEquals("jsx", inferCodeLanguage(explicit = "language-javascriptreact"))
        assertEquals("tsx", inferCodeLanguage(path = "component.tsx"))
        assertEquals("jsonc", inferCodeLanguage(path = "settings.jsonc"))
        assertEquals("jsonl", inferCodeLanguage(path = "records.ndjson"))
        assertEquals("toml", inferCodeLanguage(path = "Cargo.toml"))
        assertEquals("ini", inferCodeLanguage(path = "/project/.editorconfig"))
        assertNull(inferCodeLanguage(explicit = "plain", path = "index.html"))
    }
}
