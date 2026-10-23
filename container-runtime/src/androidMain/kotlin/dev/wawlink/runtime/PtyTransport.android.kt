package dev.wawlink.runtime

import com.termux.terminal.AndroidPtyProcess

internal actual fun createPtyTransport(builder: ProcessBuilder): PtyTransport {
    val child = AndroidPtyProcess(builder)
    return object : PtyTransport {
        override val process: Process = child
        override fun resize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
            child.resize(columns, rows, cellWidthPixels, cellHeightPixels)
        }
        override fun close() = child.close()
    }
}
