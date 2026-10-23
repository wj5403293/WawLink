package dev.wawlink.runtime

import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize

internal actual fun createPtyTransport(builder: ProcessBuilder): PtyTransport {
    val child = PtyProcessBuilder(builder.command().toTypedArray())
        .setEnvironment(builder.environment())
        .setDirectory(builder.directory().absolutePath)
        .setInitialColumns(80)
        .setInitialRows(24)
        .start()
    return object : PtyTransport {
        override val process: Process = child
        override fun resize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
            child.winSize = WinSize(columns, rows)
        }
        override fun close() {
            child.inputStream.close()
            child.outputStream.close()
            child.errorStream.close()
        }
    }
}
