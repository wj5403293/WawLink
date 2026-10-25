package dev.wawlink.app

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import dev.wawlink.ui.LocalAgentAppearance
import dev.wawlink.runtime.ContainerFeatureSession
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.web.WebView
import java.awt.Color
import java.util.concurrent.atomic.AtomicBoolean

/** Embedded WebKit renderer; never opens the system browser and exposes no JVM objects to JavaScript. */
@Composable
internal fun ContainerWebSurface(session: ContainerFeatureSession, css: String, modifier: Modifier, onError: (String) -> Unit) {
    val report by rememberUpdatedState(onError)
    val currentCss by rememberUpdatedState(css)
    var symbols by remember { mutableStateOf<String?>(null) }
    var font by remember { mutableStateOf<FeatureWebFont?>(null) }
    LaunchedEffect(Unit) {
        try { font = featureWebFont(); symbols = featureSymbolScript() }
        catch (failure: Exception) { report("图标索引加载失败：${failure.message}") }
    }
    val symbolScript = symbols ?: return
    val loadedFont = font ?: return
    val holder = remember(session) { FeatureDesktopWeb() }
    val appDensity = LocalDensity.current
    val appearance = LocalAgentAppearance.current
    var nativeScale by remember { mutableFloatStateOf(appDensity.density / appearance.densityScale) }
    val nativeDensity = remember(nativeScale, appDensity.fontScale) { Density(nativeScale, appDensity.fontScale) }
    DisposableEffect(holder) {
        onDispose {
            holder.disposed.set(true)
            Platform.runLater {
                holder.view?.engine?.apply { loadWorker.cancel(); load(null) }
                holder.panel?.scene = null
                holder.view = null
            }
        }
    }
    // Swing bounds use the host display scale, not the app's content zoom.
    CompositionLocalProvider(LocalDensity provides nativeDensity) {
    SwingPanel(factory = {
        JFXPanel().apply {
            background = Color.WHITE
            addPropertyChangeListener("graphicsConfiguration") {
                graphicsConfiguration?.defaultTransform?.scaleX?.toFloat()?.let { nativeScale = it }
            }
            holder.panel = this
            Platform.setImplicitExit(false)
            Platform.runLater {
                try {
                    if (!holder.disposed.get()) {
                        registerDesktopFeatureFont(loadedFont)
                        val web = WebView()
                        holder.view = web
                        web.isContextMenuEnabled = false
                        web.fontScale = 1.1
                        val engine = web.engine
                        engine.isJavaScriptEnabled = true
                        engine.createPopupHandler = javafx.util.Callback { null }
                        engine.confirmHandler = javafx.util.Callback { false }
                        engine.promptHandler = javafx.util.Callback { null }
                        engine.setOnAlert { /* No modal native dialogs from guest JavaScript. */ }
                        engine.setOnError { event -> report("网页渲染失败：${event.message}") }
                        engine.locationProperty().addListener { _, _, location ->
                            if (!holder.disposed.get() && !containerFeatureAllows(location, session)) {
                                engine.loadWorker.cancel()
                                report("已阻止离开当前功能来源的导航")
                            }
                        }
                        engine.loadWorker.stateProperty().addListener { _, _, state ->
                            when (state) {
                                Worker.State.SUCCEEDED -> if (containerFeatureAllows(engine.location, session)) {
                                    runCatching { engine.executeScript(featureDesktopUnavailableScript()) }
                                    runCatching { engine.executeScript(symbolScript) }
                                        .onFailure { report("图标渲染失败：${it.message}") }
                                    runCatching { engine.executeScript(containerFeatureThemeScript(currentCss)) }
                                        .onFailure { report("主题注入失败：${it.message}") }
                                }
                                Worker.State.FAILED -> if (!holder.disposed.get()) report("本地页面加载失败：${engine.loadWorker.exception?.message ?: "连接不可用"}")
                                else -> Unit
                            }
                        }
                        scene = Scene(web)
                        engine.load(session.url)
                    }
                } catch (failure: Throwable) { report("无法初始化桌面 WebKit：${failure.message}") }
            }
        }
    }, modifier = modifier, update = {
        Platform.runLater {
            holder.view?.engine?.let { engine ->
                if (!holder.disposed.get() && containerFeatureAllows(engine.location, session)) {
                    runCatching { engine.executeScript(containerFeatureThemeScript(css)) }
                }
            }
        }
    })
    }
}

private class FeatureDesktopWeb {
    val disposed = AtomicBoolean()
    var panel: JFXPanel? = null
    var view: WebView? = null
}
