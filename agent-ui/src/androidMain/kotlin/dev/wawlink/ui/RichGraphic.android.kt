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

internal actual fun rasterImage(bytes: ByteArray, type: String): ImageBitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth in 1..16384 && bounds.outHeight in 1..16384 && bounds.outWidth.toLong() * bounds.outHeight <= 32_000_000) {
        "图片尺寸不受支持（最大 16,384 边长、3,200 万像素）"
    }
    return (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("图片格式无法解码")).asImageBitmap()
}
