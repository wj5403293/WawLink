package dev.wawlink.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.wawlink.runtime.ContainerConfig
import dev.wawlink.runtime.TerminalSession
import dev.wawlink.runtime.wasInterrupted
import dev.wawlink.ui.AgentTopBar
import dev.wawlink.ui.ErrorMessage
import kotlinx.coroutines.*

/** A separate destination: no chat renderer, command input field, or bottom sheet. */
@Composable
internal fun TerminalScreen(app: AppCoordinator, config: ContainerConfig, onBack: () -> Unit, installationId: String? = null) {
    var session by remember(config.id, installationId) { mutableStateOf<TerminalSession?>(null) }
    var error by remember(config.id, installationId) { mutableStateOf<String?>(null) }
    var interrupted by remember(config.id, installationId) { mutableStateOf(false) }
    LaunchedEffect(config.id, installationId) {
        var opened: TerminalSession? = null
        val generation = app.runtime.containers.value.firstOrNull { it.config.id == config.id }?.stopGeneration
        try {
            // Preserve ownership even if navigation cancels while the engine is starting.
            withContext(NonCancellable + Dispatchers.IO) { opened = app.openTerminal(config.id, installationId) }
            ensureActive()
            session = opened
            awaitCancellation()
        } catch (cancelled: CancellationException) {
            if (isActive) interrupted = true
            throw cancelled
        } catch (failure: Exception) {
            ensureActive()
            if (generation != null && app.runtime.wasInterrupted(config.id, generation)) interrupted = true
            else error = failure.message ?: failure.toString()
        }
        finally {
            opened?.close()
            session = null
            app.finishOmpInstallation(config.id, installationId, opened)
        }
    }
    val colors = MaterialTheme.colorScheme.copy(surface = Color.White, background = Color.White)
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize(), color = Color.White) {
            Column(Modifier.fillMaxSize().imePadding()) {
                AgentTopBar("${config.displayName} · 终端", onBack)
                val connected = session
                if (connected != null) {
                    val alive by connected.isAlive.collectAsState()
                    if (!alive) Text("终端进程已退出，返回后可重新打开。", Modifier.fillMaxWidth().padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TerminalSurface(connected, Modifier.weight(1f).fillMaxWidth())
                } else if (error != null) {
                    ErrorMessage("无法启动终端：$error", Modifier.padding(20.dp))
                } else if (interrupted) {
                    Text("终端进程已退出，返回后可重新打开。", Modifier.fillMaxWidth().padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在连接容器交互式终端…", Modifier.padding(20.dp))
                }
            }
        }
    }
}
