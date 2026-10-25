package dev.wawlink.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import dev.wawlink.ui.resources.Res
import java.util.Base64
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.scene.Scene
import javafx.scene.web.WebView
import javafx.util.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val mermaidEngineLock = Mutex()
private var mermaidEngine: String? = null

internal actual suspend fun mermaidEngineScript(): String = mermaidEngineLock.withLock {
    mermaidEngine ?: ("<script>" + Res.readBytes("files/rich/mermaid.min.js").decodeToString()
        .replace("</script", "<\\/script", ignoreCase = true) + "</script>").also { mermaidEngine = it }
}

@Composable
internal actual fun PlatformRichGraphic(html: String, modifier: Modifier, onResult: (Float?, String?) -> Unit) {
    val callback = rememberUpdatedState(onResult)
    val density = LocalDensity.current.density
    BoxWithConstraints(modifier) {
        val width = (constraints.maxWidth / density).coerceAtLeast(1f)
        var image by remember(html, width, density) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(html, width, density) {
            try {
                withContext(Dispatchers.IO) { DesktopGraphicRuntime.start() }
                val raster = renderDesktopGraphic(html, width, density)
                image = raster.image
                callback.value(raster.height, null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: LinkageError) {
                callback.value(null, "图形组件不可用，请安装 JavaFX WebKit、GTK 3 及其系统依赖")
            } catch (error: Exception) {
                callback.value(null, error.message?.takeIf { it.startsWith("图形") } ?: "图形组件不可用，请检查 JavaFX WebKit 与桌面显示环境")
            }
        }
        image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }
}

private object DesktopGraphicRuntime {
    private var started = false
    @Synchronized fun start() {
        if (started) return
        try {
            Platform.startup { Platform.setImplicitExit(false) }
        } catch (_: IllegalStateException) {
            // A standalone UI host may already own the JavaFX toolkit.
            Platform.setImplicitExit(false)
        }
        started = true
    }
}

private data class DesktopGraphicRaster(val image: ImageBitmap, val height: Float)

private suspend fun renderDesktopGraphic(html: String, width: Float, density: Float): DesktopGraphicRaster =
    suspendCancellableCoroutine { continuation ->
        val renderer = DesktopGraphicRenderer(html, width, density) { raster, error ->
            if (continuation.isActive) {
                if (raster != null) continuation.resume(raster)
                else continuation.resumeWithException(IllegalStateException(error))
            }
        }
        continuation.invokeOnCancellation { renderer.dispose() }
    }

/** WebKit evaluates only the app-owned document. Canvas rasterization needs neither a visible window
 * nor a native child in the chat, so diagrams cannot steal focus, scrolling, or escape Compose clipping. */
private class DesktopGraphicRenderer(
    private val html: String,
    private val width: Float,
    private val density: Float,
    private val result: (DesktopGraphicRaster?, String?) -> Unit,
) {
    @Volatile private var disposed = false
    private var view: WebView? = null
    private var poll: Timeline? = null

    init { Platform.runLater { if (!disposed) start() } }

    private fun start() {
        try {
            val webView = WebView()
            view = webView
            webView.isContextMenuEnabled = false
            val engine = webView.engine
            engine.isJavaScriptEnabled = true
            engine.setCreatePopupHandler { null }
            engine.setConfirmHandler { false }
            engine.setPromptHandler { null }
            engine.setOnAlert { }
            engine.locationProperty().addListener { _, _, location ->
                if (!location.isNullOrEmpty() && location != "about:blank") complete(null, "图形组件已阻止外部页面")
            }
            engine.loadWorker.stateProperty().addListener { _, _, state ->
                if (state == Worker.State.FAILED) complete(null, "图形加载失败，请检查 JavaFX WebKit 运行环境")
            }
            Scene(webView, width.toDouble(), 560.0)
            webView.resize(width.toDouble(), 560.0)
            engine.loadContent(html, "text/html")
            poll = Timeline(KeyFrame(Duration.millis(100.0), {
                if (!disposed) try {
                    val value = engine.executeScript("""
                        (function(){
                          var state=JSON.parse(window.__wawlinkResult||'null');
                          if(state&&state.error)return JSON.stringify({error:state.error});
                          var image=document.querySelector('#graphic img');
                          if(!image||!image.complete||!image.naturalWidth)return null;
                          var ratio=Math.min($width/image.naturalWidth,560/image.naturalHeight);
                          var canvas=document.createElement('canvas');
                          canvas.width=Math.ceil(image.naturalWidth*ratio*$density);
                          canvas.height=Math.ceil(image.naturalHeight*ratio*$density);
                          canvas.getContext('2d').drawImage(image,0,0,canvas.width,canvas.height);
                          return JSON.stringify({height:image.naturalHeight*ratio,png:canvas.toDataURL('image/png')});
                        })()
                    """.trimIndent()) as? String
                    if (value != null) {
                        val json = Json.parseToJsonElement(value).jsonObject
                        val error = json["error"]?.jsonPrimitive?.contentOrNull
                        if (error != null) complete(null, error)
                        else {
                            val png = json.getValue("png").jsonPrimitive.content.substringAfter(',')
                            val bitmap = rasterImage(Base64.getDecoder().decode(png), "image/png")
                            complete(DesktopGraphicRaster(bitmap, json.getValue("height").jsonPrimitive.floatOrNull ?: 48f), null)
                        }
                    }
                } catch (_: Exception) {
                    complete(null, "图形渲染失败，请检查图形内容")
                }
            })).apply { cycleCount = Timeline.INDEFINITE; play() }
        } catch (_: Exception) {
            complete(null, "图形组件启动失败，请检查 JavaFX WebKit 运行环境")
        }
    }

    private fun complete(raster: DesktopGraphicRaster?, error: String?) {
        if (disposed) return
        dispose()
        result(raster, error)
    }

    fun dispose() {
        disposed = true
        Platform.runLater {
            poll?.stop()
            view?.engine?.loadWorker?.cancel()
            view?.engine?.load(null)
            view = null
        }
    }
}

@Composable
internal actual fun rememberSvgRasterizer(): suspend (String) -> ImageBitmap = remember {
    { document ->
        withContext(Dispatchers.IO) { DesktopGraphicRuntime.start() }
        renderDesktopGraphic(document, 1024f, 1f).image
    }
}

actual fun rasterImage(bytes: ByteArray, type: String): ImageBitmap {
    // Skia's encoded image is lazy: its dimensions are available before rasterization.
    val image = try { org.jetbrains.skia.Image.makeFromEncoded(bytes) }
        catch (_: Exception) { error("图片格式无法解码") }
    require(image.width in 1..16384 && image.height in 1..16384 && image.width.toLong() * image.height <= 32_000_000) {
        "图片尺寸不受支持（最大 16,384 边长、3,200 万像素）"
    }
    return image.toComposeImageBitmap()
}
