package dev.wawlink.ui

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.webkit.RenderProcessGoneDetail
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.viewinterop.AndroidView
import dev.wawlink.ui.resources.Res
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

private class GraphicBridge(private val webView: GraphicWebView, private val result: (Float?, String?) -> Unit) {
    @JavascriptInterface
    fun complete(height: Double, error: String) {
        webView.post {
            if (!webView.isReleased && !webView.rendererGone) result(height.toFloat(), error.takeIf { it.isNotEmpty() })
        }
    }
}

private const val MERMAID_ENGINE_URL = "https://wawlink.invalid/mermaid.min.js"
private val mermaidEngineLock = Mutex()
@Volatile private var mermaidEngine: ByteArray? = null
private val emptyResource = ByteArray(0)
private val svgRasterizationLock = Mutex()

internal actual suspend fun mermaidEngineScript(): String {
    mermaidEngineLock.withLock {
        if (mermaidEngine == null) mermaidEngine = Res.readBytes("files/rich/mermaid.min.js")
    }
    return "<script src=\"$MERMAID_ENGINE_URL\"></script>"
}

private open class GraphicWebViewClient(private val onRendererGone: () -> Unit) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true

    @Suppress("DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?) = true

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        val graphic = view as GraphicWebView
        if (graphic.isReleased || graphic.rendererGone) return true
        graphic.rendererGone = true
        try {
            onRendererGone()
        } finally {
            (graphic.parent as? android.view.ViewGroup)?.removeView(graphic)
            graphic.releaseGraphic()
        }
        return true
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse {
        // Only this exact app-owned subresource is served. Every other request remains blocked.
        val bytes = if (request != null && request.url.toString() == MERMAID_ENGINE_URL &&
            request.method == "GET" && !request.isForMainFrame) mermaidEngine else null
        return if (bytes != null) WebResourceResponse("application/javascript", "utf-8", ByteArrayInputStream(bytes))
            else WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(emptyResource))
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configureGraphic() {
    setBackgroundColor(android.graphics.Color.TRANSPARENT)
    settings.apply {
        javaScriptEnabled = true // Only the bundled Mermaid engine and app-owned bootstrap execute.
        allowFileAccess = false
        allowContentAccess = false
        @Suppress("DEPRECATION")
        allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        allowUniversalAccessFromFileURLs = false
        blockNetworkLoads = true
        domStorageEnabled = false
        databaseEnabled = false
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
    }
}

private class GraphicWebView(context: android.content.Context) : WebView(context) {
    var isReleased = false
        private set
    var rendererGone = false

    fun releaseGraphic() {
        if (isReleased) return
        isReleased = true
        try {
            if (!rendererGone) {
                stopLoading()
                removeJavascriptInterface("WawlinkGraphic")
            }
        } finally {
            destroy()
        }
    }
}

@Composable
internal actual fun PlatformRichGraphic(html: String, modifier: Modifier, onResult: (Float?, String?) -> Unit) {
    val context = rememberAgentViewContext()
    val callback = rememberUpdatedState(onResult)
    val alive = remember(html, context) { AtomicBoolean(true) }
    // Native allocation belongs to AndroidView, not a speculative composition. onRelease runs
    // after detachment, so a replaced document cannot leave a destroyed WebView in the view tree.
    key(html, context) {
        AndroidView<android.view.View>(
            factory = {
                var created: GraphicWebView? = null
                try {
                    GraphicWebView(context).also { created = it }.apply {
                        configureGraphic()
                        addJavascriptInterface(GraphicBridge(this) { height, error ->
                            if (alive.get()) callback.value(height, error)
                        }, "WawlinkGraphic")
                        webViewClient = object : GraphicWebViewClient({
                            if (alive.compareAndSet(true, false)) callback.value(null, "图形渲染进程已退出，请重试")
                        }) {
                            override fun onPageFinished(view: WebView, url: String?) {
                                if (!alive.get()) return
                                // Also pick up a result produced before the bridge was ready.
                                view.evaluateJavascript("window.__wawlinkResult || null") { encoded ->
                                    if (alive.get() && encoded != "null") try {
                                        val value = org.json.JSONTokener(encoded).nextValue() as String
                                        val result = JSONObject(value)
                                        callback.value(result.getDouble("height").toFloat(),
                                            result.optString("error").takeIf { it != "null" && it.isNotEmpty() })
                                    } catch (_: Exception) {
                                        callback.value(null, "图形渲染结果无效，请重试")
                                    }
                                }
                            }
                        }
                        loadDataWithBaseURL("https://wawlink.invalid/", html, "text/html", "UTF-8", null)
                    }
                } catch (_: Exception) {
                    created?.releaseGraphic()
                    android.view.View(context).apply {
                        post { if (alive.get()) callback.value(null, "图形组件不可用，请安装或更新 Android System WebView") }
                    }
                }
            },
            modifier = modifier,
            onRelease = { view ->
                alive.set(false)
                (view as? GraphicWebView)?.releaseGraphic()
            },
        )
    }
}

actual fun rasterImage(bytes: ByteArray, type: String): ImageBitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth in 1..16384 && bounds.outHeight in 1..16384 && bounds.outWidth.toLong() * bounds.outHeight <= 32_000_000) {
        "图片尺寸不受支持（最大 16,384 边长、3,200 万像素）"
    }
    return (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("图片格式无法解码")).asImageBitmap()
}

@Composable
internal actual fun rememberSvgRasterizer(): suspend (String) -> ImageBitmap {
    val context = rememberAgentViewContext()
    return remember(context) {
        { document ->
            // These temporary browsers are only used to produce a bitmap. Do not retain one
            // native browser per SVG when a long message brings many images into composition.
            svgRasterizationLock.withLock {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    val view = GraphicWebView(context)
                    try {
                        view.configureGraphic()
                        view.setInitialScale(100)
                        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(1024, android.view.View.MeasureSpec.EXACTLY),
                            android.view.View.MeasureSpec.makeMeasureSpec(560, android.view.View.MeasureSpec.EXACTLY))
                        view.layout(0, 0, 1024, 560)
                        val bounds = kotlinx.coroutines.withTimeout(25_000) {
                            kotlinx.coroutines.suspendCancellableCoroutine<android.graphics.Rect> { continuation ->
                                var measuring = false
                                view.webViewClient = GraphicWebViewClient {
                                    if (continuation.isActive) continuation.resumeWith(Result.failure(
                                        IllegalStateException("图形渲染进程已退出，请重试")))
                                }
                                view.addJavascriptInterface(GraphicBridge(view) { _, error ->
                                    if (continuation.isActive && !measuring) {
                                        measuring = true
                                        if (error != null) {
                                            continuation.resumeWith(Result.failure(IllegalArgumentException("图形 SVG 渲染失败")))
                                        } else {
                                            // The app-owned loaded() reports after two animation frames.
                                            // Measure once instead of polling the JavaScript engine every 50 ms.
                                            view.evaluateJavascript("""
                                                (function(){
                                                  var rect=document.querySelector('#graphic img').getBoundingClientRect();
                                                  return JSON.stringify({left:rect.left,top:rect.top,width:rect.width,height:rect.height});
                                                })()
                                            """.trimIndent()) { encoded ->
                                                if (continuation.isActive) {
                                                    val boundsResult = try {
                                                        val result = JSONObject(org.json.JSONTokener(encoded).nextValue() as String)
                                                        val left = kotlin.math.floor(result.getDouble("left")).toInt().coerceIn(0, 1023)
                                                        val top = kotlin.math.floor(result.getDouble("top")).toInt().coerceIn(0, 559)
                                                        val right = kotlin.math.ceil(result.getDouble("left") + result.getDouble("width")).toInt().coerceIn(left + 1, 1024)
                                                        val bottom = kotlin.math.ceil(result.getDouble("top") + result.getDouble("height")).toInt().coerceIn(top + 1, 560)
                                                        Result.success(android.graphics.Rect(left, top, right, bottom))
                                                    } catch (error: Exception) {
                                                        Result.failure(error)
                                                    }
                                                    continuation.resumeWith(boundsResult)
                                                }
                                            }
                                        }
                                    }
                                }, "WawlinkGraphic")
                                view.loadDataWithBaseURL("https://wawlink.invalid/", document, "text/html", "UTF-8", null)
                            }
                        }
                        kotlinx.coroutines.delay(32)
                        check(!view.rendererGone) { "图形渲染进程已退出，请重试" }
                        val bitmap = android.graphics.Bitmap.createBitmap(bounds.width(), bounds.height(), android.graphics.Bitmap.Config.ARGB_8888)
                        var published = false
                        try {
                            val canvas = android.graphics.Canvas(bitmap)
                            canvas.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
                            view.draw(canvas)
                            bitmap.asImageBitmap().also { published = true }
                        } finally {
                            if (!published) bitmap.recycle()
                        }
                    } finally {
                        view.releaseGraphic()
                    }
                }
            }
        }
    }
}
