package dev.wawlink.app

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.ui.TerminalActionPresentation
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import dev.wawlink.runtime.TerminalSession
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/** JediTerm supplies ANSI/cursor emulation, raw keys, selection and Ctrl+Shift+C/V. */
@Composable
internal fun TerminalSurface(session: TerminalSession, modifier: Modifier) {
    val widget = remember(session) {
        JediTermWidget(object : DefaultSettingsProvider() {
            override fun getDefaultStyle() = TextStyle(TerminalColor.rgb(230, 237, 243), TerminalColor.rgb(13, 17, 23))
            override fun getTerminalFontSize() = 15f
            override fun getBufferMaxLinesCount() = 10_000
            override fun audibleBell() = false
            override fun getCopyActionPresentation() = TerminalActionPresentation("复制", super.getCopyActionPresentation().keyStrokes)
            override fun getPasteActionPresentation() = TerminalActionPresentation("粘贴", super.getPasteActionPresentation().keyStrokes)
            override fun getClearBufferActionPresentation() = TerminalActionPresentation("清空滚屏记录", super.getClearBufferActionPresentation().keyStrokes)
            override fun getSelectAllActionPresentation() = TerminalActionPresentation("全选", super.getSelectAllActionPresentation().keyStrokes)
            override fun getFindActionPresentation() = TerminalActionPresentation("查找", super.getFindActionPresentation().keyStrokes)
            override fun getOpenUrlActionPresentation() = TerminalActionPresentation("打开链接", super.getOpenUrlActionPresentation().keyStrokes)
        }).apply {
            ttyConnector = ContainerTtyConnector(session)
            start()
        }
    }
    DisposableEffect(widget) { onDispose { widget.close() } }
    SwingPanel(factory = { widget.apply { requestFocusInWindow() } }, modifier = modifier)
}

private class ContainerTtyConnector(private val session: TerminalSession) : TtyConnector {
    private val reader = InputStreamReader(session.process.inputStream, Charsets.UTF_8)
    private val closed = AtomicBoolean()
    override fun read(buffer: CharArray, offset: Int, length: Int) = reader.read(buffer, offset, length)
    override fun write(bytes: ByteArray) {
        synchronized(session.process.outputStream) {
            session.process.outputStream.write(bytes)
            session.process.outputStream.flush()
        }
    }
    override fun write(text: String) = write(text.toByteArray(Charsets.UTF_8))
    override fun resize(size: TermSize) = session.resize(size.columns, size.rows)
    override fun isConnected() = !closed.get() && session.process.isAlive
    override fun ready() = reader.ready()
    override fun waitFor() = session.process.waitFor()
    override fun getName() = "WawLink 容器终端"
    override fun close() { if (closed.compareAndSet(false, true)) session.close() }
}
