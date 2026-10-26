package dev.wawlink.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import dev.wawlink.ui.LocalAgentAppearance
import com.sun.javafx.webkit.Accessor
import com.sun.webkit.PolicyClient
import com.sun.webkit.WebPage
import javafx.application.Platform
import javafx.animation.PauseTransition
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.input.ScrollEvent
import javafx.scene.input.KeyEvent
import javafx.util.Duration
import javafx.concurrent.Worker
import javafx.embed.swing.JFXPanel
import javafx.embed.swing.SwingFXUtils
import javafx.scene.Scene
import javafx.scene.web.WebView
import org.w3c.dom.Element
import org.w3c.dom.events.EventListener
import org.w3c.dom.events.EventTarget
import java.awt.Color
import javafx.scene.shape.Rectangle
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** A real WebKit document. Only DOM action events cross to the host; no Java objects enter JS. */
@Composable
internal fun ContainerFeatureCardSurface(html: String?, url: String?, css: String, data: String, modifier: Modifier, onAction: (String) -> Unit, onError: (String) -> Unit) {
    require((html == null) != (url == null)) { "Exactly one of html and url is required" }
    val report by rememberUpdatedState(onError)
    val action by rememberUpdatedState(onAction)
    val currentCss by rememberUpdatedState(css)
    val currentData by rememberUpdatedState(data)
    val gestures by rememberUpdatedState(LocalFeatureCardNativeGestures.current)
    var font by remember { mutableStateOf<FeatureWebFont?>(null) }
    var symbols by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try { symbols = featureSymbolScript(); font = featureWebFont() }
        catch (failure: Exception) { report("图标字体加载失败：${failure.message}") }
    }
    val loadedFont = font ?: return
    val holder = remember(html, url) { DesktopFeatureCard() }
    val debugContext = LocalFeatureDebugContext.current
    DisposableEffect(holder, debugContext) {
        val registration = holder.debug.bind(debugContext)
        onDispose { registration.close() }
    }
    val covered by rememberUpdatedState(LocalFeatureCardCovered.current)
    var frame by remember(holder) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(covered, holder) {
        if (covered) Platform.runLater {
            if (!holder.disposed.get() && covered) try {
                frame = holder.captureFrame()
            } catch (failure: Exception) { report("卡片合成失败：${failure.message}") }
        }
    }
    val density = LocalDensity.current
    val appearance = LocalAgentAppearance.current
    var nativeScale by remember { mutableFloatStateOf(density.density / appearance.densityScale) }
    val nativeDensity = remember(nativeScale, density.fontScale) { Density(nativeScale, density.fontScale) }
    DisposableEffect(holder) {
        onDispose {
            holder.disposed.set(true)
            holder.debug.dispose()
            Platform.runLater {
                holder.stopGestures?.invoke(); holder.stopGestures = null
                holder.detach()
                holder.view?.engine?.apply { loadWorker.cancel(); load(null) }
                holder.panel?.scene = null; holder.view = null
            }
        }
    }
    Box(modifier) {
    if (covered && frame != null) {
        frame?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
    } else {
    CompositionLocalProvider(LocalDensity provides nativeDensity) {
        SwingPanel(factory = {
            holder.panel ?: JFXPanel().apply {
                background = Color.WHITE
                addPropertyChangeListener("graphicsConfiguration") { graphicsConfiguration?.defaultTransform?.scaleX?.toFloat()?.let { nativeScale = it } }
                holder.panel = this
                Platform.setImplicitExit(false)
                Platform.runLater {
                    if (!holder.disposed.get()) try {
                        registerDesktopFeatureFont(loadedFont)
                        val web = WebView()
                        web.zoom = (density.density / nativeScale).toDouble()
                        web.fontScale = density.fontScale.toDouble()
                        web.clip = Rectangle().apply {
                            widthProperty().bind(web.widthProperty()); heightProperty().bind(web.heightProperty())
                            arcWidth = 56.0 * density.density / nativeScale; arcHeight = arcWidth
                        }
                        holder.view = web
                        web.isContextMenuEnabled = false
                        val hold = PauseTransition(Duration.millis(500.0))
                        var pressed = false
                        var held = false
                        var moved = false
                        var selecting = false
                        var suppressClick = false
                        var startX = 0.0
                        var startY = 0.0
                        var lastScreenX = 0.0
                        var lastScreenY = 0.0
                        hold.setOnFinished {
                            if (pressed && !holder.disposed.get()) {
                                held = true; suppressClick = true
                                gestures?.onHold?.invoke(Offset((startX / web.width.coerceAtLeast(1.0)).toFloat(), (startY / web.height.coerceAtLeast(1.0)).toFloat()))
                            }
                        }
                        holder.stopGestures = { pressed = false; hold.stop() }
                        web.addEventFilter(MouseEvent.ANY) { event ->
                            if (holder.disposed.get() || gestures == null) return@addEventFilter
                            when (event.eventType) {
                                MouseEvent.MOUSE_PRESSED -> if (event.button == MouseButton.PRIMARY) {
                                    pressed = true; held = false; moved = false; suppressClick = false
                                    selecting = gestures?.selectionMode == true
                                    startX = event.x; startY = event.y; lastScreenX = event.screenX; lastScreenY = event.screenY
                                    hold.playFromStart()
                                    if (selecting) event.consume()
                                }
                                MouseEvent.MOUSE_DRAGGED -> if (pressed) {
                                    if (!held && kotlin.math.hypot(event.x - startX, event.y - startY) > 8.0) {
                                        moved = true; hold.stop()
                                    }
                                    if (held) gestures?.onDrag?.invoke(Offset(
                                        ((event.screenX - lastScreenX) / web.width.coerceAtLeast(1.0)).toFloat(),
                                        ((event.screenY - lastScreenY) / web.height.coerceAtLeast(1.0)).toFloat(),
                                    ))
                                    lastScreenX = event.screenX; lastScreenY = event.screenY
                                    if (held || selecting || gestures?.selectionMode == true) event.consume()
                                }
                                MouseEvent.MOUSE_RELEASED -> if (pressed) {
                                    pressed = false; hold.stop()
                                    if (held || selecting) {
                                        suppressClick = true
                                        if (!held && !moved) gestures?.onTap?.invoke()
                                        gestures?.onEnd?.invoke(); event.consume()
                                    }
                                }
                                MouseEvent.MOUSE_CLICKED -> if (suppressClick || gestures?.selectionMode == true) event.consume()
                            }
                            if (gestures?.selectionMode == true) event.consume()
                        }
                        web.addEventFilter(ScrollEvent.ANY) { if (gestures?.selectionMode == true) it.consume() }
                        web.addEventFilter(KeyEvent.ANY) { if (gestures?.selectionMode == true) it.consume() }
                        val engine = web.engine
                        engine.isJavaScriptEnabled = true
                        installDesktopCardPolicy(web, url, { holder.staticDocumentLoadPending }, report)
                        engine.createPopupHandler = javafx.util.Callback { null }
                        engine.confirmHandler = javafx.util.Callback { false }
                        engine.promptHandler = javafx.util.Callback { null }
                        engine.setOnAlert { }
                        engine.setOnError { holder.debug.recordError("卡片渲染失败：${it.message}"); report("卡片渲染失败：${it.message}") }
                        fun trustedDocument(): Boolean = if (url == null) engine.location.isNullOrEmpty() || engine.location == "about:blank"
                            else featureSameOrigin(engine.location, featureDocumentOrigin(url))
                        engine.locationProperty().addListener { _, _, _ ->
                            holder.debug.invalidate()
                            if (!holder.disposed.get() && !trustedDocument()) {
                                engine.loadWorker.cancel(); report("已阻止离开当前功能来源的导航")
                            }
                        }
                        engine.loadWorker.stateProperty().addListener { _, _, state ->
                            if (holder.disposed.get()) return@addListener
                            when (state) {
                                Worker.State.SCHEDULED -> { holder.debug.invalidate(); holder.detach() }
                                Worker.State.SUCCEEDED -> if (trustedDocument()) {
                                    holder.detach()
                                    holder.staticDocumentLoadPending = false
                                    holder.debug.bootstrap()
                                    val document = engine.document
                                    fun consume(element: Element) {
                                        val value = element.getAttribute("data-wawlink-native-action")
                                        if (value in setOf("open", "start", "stop")) {
                                            element.parentNode?.removeChild(element)
                                            action(value)
                                        }
                                    }
                                    val listener = EventListener { event ->
                                        val element = event.target as? Element
                                        if (element?.hasAttribute("data-wawlink-native-action") == true) {
                                            event.preventDefault(); event.stopPropagation(); consume(element)
                                        }
                                    }
                                    val target = document as EventTarget
                                    target.addEventListener("click", listener, true)
                                    holder.target = target; holder.listener = listener
                                    val queued = document.getElementsByTagName("button")
                                    (0 until queued.length).mapNotNull { queued.item(it) as? Element }
                                        .filter { it.hasAttribute("data-wawlink-native-action") }.forEach(::consume)
                                    val initialization = featureDesktopBootstrap() + ";" + featureDocumentPolicyScript() + ";" +
                                        (if (url != null) featureDocumentFontScript(loadedFont) else "") + ";" + symbols + ";" +
                                        featureCardUpdateScript(currentCss, currentData) + ";" + featureReadyScript()
                                    runCatching { engine.executeScript(initialization) }
                                        .onFailure { report("卡片初始化失败：${it.message}") }
                                    if (covered) runCatching { frame = holder.captureFrame() }
                                        .onFailure { report("卡片合成失败：${it.message}") }
                                }
                                Worker.State.FAILED -> {
                                    holder.staticDocumentLoadPending = false
                                    holder.debug.recordError("卡片加载失败：${engine.loadWorker.exception?.message}")
                                    report("卡片加载失败：${engine.loadWorker.exception?.message}")
                                }
                                Worker.State.CANCELLED -> holder.staticDocumentLoadPending = false
                                else -> Unit
                            }
                        }
                        scene = Scene(web).apply { fill = javafx.scene.paint.Color.WHITE }
                        fun loadDocument(refreshedHtml: String? = null) {
                            if (url != null) engine.load(engine.location.takeIf { featureSameOrigin(it, featureDocumentOrigin(url)) } ?: url)
                            else {
                                holder.staticDocumentLoadPending = true
                                engine.loadContent(featureCardDocument(requireNotNull(refreshedHtml ?: html), currentCss, currentData, loadedFont, featureDesktopBootstrap() + ";" + symbols), "text/html")
                            }
                        }
                        holder.debug.attach(web, ::trustedDocument, { !covered && holder.panel?.isShowing == true }, staticHtml = html != null, reloadDocument = ::loadDocument)
                        loadDocument()
                    } catch (failure: Throwable) { report("无法初始化桌面 WebKit：${failure.message}") }
                }
            }
        }, modifier = Modifier.fillMaxSize(), update = {
            Platform.runLater {
                if (!holder.disposed.get()) holder.view?.engine?.let { engine ->
                        holder.view?.apply {
                            zoom = (density.density / nativeScale).toDouble()
                            fontScale = density.fontScale.toDouble()
                            (clip as? Rectangle)?.let { outline -> outline.arcWidth = 56.0 * density.density / nativeScale; outline.arcHeight = outline.arcWidth }
                        }
                    val trusted = if (url == null) engine.location.isNullOrEmpty() || engine.location == "about:blank"
                        else featureSameOrigin(engine.location, featureDocumentOrigin(url))
                    if (trusted && engine.loadWorker.state == Worker.State.SUCCEEDED) {
                        runCatching { engine.executeScript(featureCardUpdateScript(css, data)) }
                            .onFailure { report("卡片数据更新失败：${it.message}") }
                    }
                }
            }
        })
    }
    }
    }
}

/** JavaFX 21 has no public pre-navigation veto. Install before the first load and fail closed if unavailable. */
private fun installDesktopCardPolicy(web: WebView, url: String?, staticDocumentLoadPending: () -> Boolean, report: (String) -> Unit) {
    val page = Accessor.getPageFor(web.engine)
    val origin = url?.let(::featureDocumentOrigin)
    val policy = object : PolicyClient {
        private fun initialStatic(target: URL?): Boolean = origin == null && (web.engine.document == null || staticDocumentLoadPending()) &&
            (target == null || target.toExternalForm() == "about:blank")
        private fun navigate(frame: Long, target: URL?): Boolean {
            val allowed = frame == page.mainFrame && (initialStatic(target) ||
                (origin != null && featureSameOrigin(target?.toExternalForm(), origin)))
            if (!allowed && frame == page.mainFrame) report("已阻止离开当前功能来源的导航")
            return allowed
        }
        override fun permitNavigateAction(sourceID: Long, url: URL?): Boolean = navigate(sourceID, url)
        override fun permitRedirectAction(sourceID: Long, url: URL?): Boolean = navigate(sourceID, url)
        override fun permitSubmitDataAction(sourceID: Long, url: URL?, httpMethod: String?): Boolean = navigate(sourceID, url)
        override fun permitResubmitDataAction(sourceID: Long, url: URL?, httpMethod: String?): Boolean = navigate(sourceID, url)
        override fun permitAcceptResourceAction(sourceID: Long, url: URL?): Boolean =
            sourceID == page.mainFrame && (initialStatic(url) || (url != null && featureResourceAllowed(url.toExternalForm())))
        override fun permitEnableScriptsAction(sourceID: Long, url: URL?): Boolean = sourceID == page.mainFrame
        override fun permitNewPageAction(sourceID: Long, url: URL?): Boolean = false
        override fun permitClosePageAction(sourceID: Long): Boolean = false
    }
    WebPage::class.java.getDeclaredField("policyClient").apply { isAccessible = true }.set(page, policy)
}

// Accessed only on the JavaFX application thread. WebKit does not load embedded data fonts reliably.
private var desktopFeatureFontRegistered = false
internal fun registerDesktopFeatureFont(font: FeatureWebFont) {
    check(Platform.isFxApplicationThread())
    if (!desktopFeatureFontRegistered) {
        requireNotNull(javafx.scene.text.Font.loadFont(font.bytes.inputStream(), 24.0)) { "无法注册 Material Symbols 字体" }
        desktopFeatureFontRegistered = true
    }
}

private class DesktopFeatureCard {
    val disposed = AtomicBoolean()
    val debug = DesktopFeatureDebugSurface(disposed)
    var staticDocumentLoadPending = false
    var panel: JFXPanel? = null
    var view: WebView? = null
    var target: EventTarget? = null
    var listener: EventListener? = null
    var stopGestures: (() -> Unit)? = null
    fun captureFrame(): ImageBitmap? = view?.let { SwingFXUtils.fromFXImage(it.snapshot(null, null), null).toComposeImageBitmap() }
    fun detach() {
        listener?.let { target?.removeEventListener("click", it, true) }
        target = null; listener = null
    }
}
