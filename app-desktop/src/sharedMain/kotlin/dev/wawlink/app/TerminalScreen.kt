package dev.wawlink.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.wawlink.runtime.ContainerConfig
import dev.wawlink.runtime.TerminalSession
import dev.wawlink.ui.AgentTopBar
import kotlinx.coroutines.*

/** A separate destination: no chat renderer, command input field, or bottom sheet. */
@Composable
internal fun TerminalScreen(app: AppCoordinator, config: ContainerConfig, onBack: () -> Unit) {
    var session by remember(config.id) { mutableStateOf<TerminalSession?>(null) }
    var error by remember(config.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(config.id) {
        var opened: TerminalSession? = null
        try {
            // Preserve ownership even if navigation cancels while the engine is starting.
            withContext(NonCancellable + Dispatchers.IO) { opened = app.runtime.openTerminal(config.id) }
            ensureActive()
            session = opened
            awaitCancellation()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: failure.toString() }
        finally { opened?.close(); session = null }
    }
    val colors = lightColorScheme(primary = MaterialTheme.colorScheme.primary, surface = Color.White)
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize(), color = Color.White) {
            Column(Modifier.fillMaxSize().imePadding()) {
                AgentTopBar("${config.name} · 终端", onBack)
                val connected = session
                if (connected != null) {
                    val alive by connected.isAlive.collectAsState()
                    if (!alive) Text("终端进程已退出，返回后可重新打开。", Modifier.fillMaxWidth().padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TerminalSurface(connected, Modifier.weight(1f).fillMaxWidth())
                } else if (error != null) {
                    Text("无法启动终端：$error", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error)
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在连接容器交互式终端…", Modifier.padding(20.dp))
                }
            }
        }
    }
}
