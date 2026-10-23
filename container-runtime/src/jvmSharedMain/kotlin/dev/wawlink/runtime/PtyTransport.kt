package dev.wawlink.runtime

/** A real host PTY. The runtime remains the sole owner of its tracked process tree. */
internal interface PtyTransport : AutoCloseable {
    val process: Process
    fun resize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int)
}

internal expect fun createPtyTransport(builder: ProcessBuilder): PtyTransport
