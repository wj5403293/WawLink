package dev.wawlink.ui

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

private class GraphicBridge(private val webView: WebView, private val result: (Float?, String?) -> Unit) {
    @JavascriptInterface
    fun complete(height: Double, error: String) {
        webView.post { result(height.toFloat(), error.takeIf { it.isNotEmpty() }) }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal actual fun PlatformRichGraphic(html: String, modifier: Modifier, onResult: (Float?, String?) -> Unit) {
    val context = rememberAgentViewContext()
    val callback = rememberUpdatedState(onResult)
    val alive = remember(html, context) { AtomicBoolean(true) }
    val webView = remember(html, context) {
        runCatching { WebView(context).apply {
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
            addJavascriptInterface(GraphicBridge(this) { height, error -> if (alive.get()) callback.value(height, error) }, "WawlinkGraphic")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse =
                    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

                override fun onPageFinished(view: WebView, url: String?) {
                    // Also pick up a result produced before the bridge was ready.
                    view.evaluateJavascript("window.__wawlinkResult || null") { encoded ->
                        if (alive.get() && encoded != "null") runCatching {
                            val value = org.json.JSONTokener(encoded).nextValue() as String
                            val result = JSONObject(value)
                            callback.value(result.getDouble("height").toFloat(), result.optString("error").takeIf { it != "null" && it.isNotEmpty() })
                        }
                    }
                }
            }
            loadDataWithBaseURL("https://wawlink.invalid/", html, "text/html", "UTF-8", null)
        } }.getOrNull()
    }
    if (webView == null) {
        LaunchedEffect(html) { callback.value(null, "图形组件不可用，请安装或更新 Android System WebView") }
        return
    }
    DisposableEffect(webView) {
        onDispose {
            alive.set(false)
            webView.stopLoading()
            webView.removeJavascriptInterface("WawlinkGraphic")
            webView.destroy()
        }
    }
    key(html, context) { AndroidView(factory = { webView }, modifier = modifier) }
}

actual fun rasterImage(bytes: ByteArray, type: String): ImageBitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth in 1..16384 && bounds.outHeight in 1..16384 && bounds.outWidth.toLong() * bounds.outHeight <= 32_000_000) {
        "图片尺寸不受支持（最大 16,384 边长、3,200 万像素）"
    }
    return (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("图片格式无法解码")).asImageBitmap()
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal actual fun rememberSvgRasterizer(): suspend (String) -> ImageBitmap {
    val context = rememberAgentViewContext()
    return remember(context) {
        { document ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                val view = WebView(context)
                try {
                    view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    view.setInitialScale(100)
                    view.settings.apply {
                        javaScriptEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        blockNetworkLoads = true
                        domStorageEnabled = false
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                    }
                    view.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?) =
                            WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                    view.measure(android.view.View.MeasureSpec.makeMeasureSpec(1024, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(560, android.view.View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, 1024, 560)
                    view.loadDataWithBaseURL("https://wawlink.invalid/", document, "text/html", "UTF-8", null)
                    val bounds = kotlinx.coroutines.withTimeout(25_000) {
                        var measured: android.graphics.Rect? = null
                        while (measured == null) {
                            kotlinx.coroutines.delay(50)
                            val encoded = kotlinx.coroutines.suspendCancellableCoroutine<String> { continuation ->
                                view.evaluateJavascript("""
                                    (function(){
                                      var state=JSON.parse(window.__wawlinkResult||'null');
                                      if(!state)return null;
                                      var image=document.querySelector('#graphic img');
                                      if(image){var rect=image.getBoundingClientRect();
                                        state.left=rect.left;state.top=rect.top;state.width=rect.width;state.height=rect.height;}
                                      return JSON.stringify(state);
                                    })()
                                """.trimIndent()) { value ->
                                    if (continuation.isActive) continuation.resumeWith(Result.success(value))
                                }
                            }
                            if (encoded != "null") {
                                val result = JSONObject(org.json.JSONTokener(encoded).nextValue() as String)
                                val error = result.optString("error")
                                require(error.isEmpty() || error == "null") { "图形 SVG 渲染失败" }
                                val left = kotlin.math.floor(result.getDouble("left")).toInt().coerceIn(0, 1023)
                                val top = kotlin.math.floor(result.getDouble("top")).toInt().coerceIn(0, 559)
                                val right = kotlin.math.ceil(result.getDouble("left") + result.getDouble("width")).toInt().coerceIn(left + 1, 1024)
                                val bottom = kotlin.math.ceil(result.getDouble("top") + result.getDouble("height")).toInt().coerceIn(top + 1, 560)
                                measured = android.graphics.Rect(left, top, right, bottom)
                            }
                        }
                        requireNotNull(measured)
                    }
                    kotlinx.coroutines.delay(32)
                    val bitmap = android.graphics.Bitmap.createBitmap(bounds.width(), bounds.height(), android.graphics.Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bitmap)
                    canvas.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
                    view.draw(canvas)
                    bitmap.asImageBitmap()
                } finally {
                    view.stopLoading()
                    view.destroy()
                }
            }
        }
    }
}
