package dev.wawlink.runtime

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TerminalInitialCommandTest {
    @Test fun invalidCommandIsRejectedBeforeOpeningATerminal() {
        assertFailsWith<IllegalArgumentException> { terminalArguments("/bin/sh", "bad\u0000script") }
    }

    @Test fun successAndFailureBothRunOnceThenEnterTheConfiguredShell() {
        val directory = Files.createTempDirectory("wawlink-initial-command-").toFile()
        try {
            val shell = File(directory, "shell with ' quotes").apply {
                writeText("#!/bin/sh\nprintf 'INTERACTIVE:%s\\n' \"\$1\"\n")
                assertTrue(setExecutable(true))
            }
            for (exit in listOf(0, 23)) {
                val script = "printf 'RUN_ONCE\\n'; printf '%s\\n' \"a ' quote\"; exit $exit"
                val process = ProcessBuilder(terminalArguments(shell.path, script)).redirectErrorStream(true).start()
                assertTrue(process.waitFor(5, TimeUnit.SECONDS))
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertEquals(0, process.exitValue(), output)
                assertEquals(1, Regex("RUN_ONCE").findAll(output).count(), output)
                assertTrue(output.contains("a ' quote"), output)
                assertTrue(output.contains("INTERACTIVE:-i"), output)
                assertEquals(exit != 0, output.contains("退出码 23"), output)
            }
        } finally {
            RootfsArchives.deleteTree(directory)
        }
    }
}
