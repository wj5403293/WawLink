package dev.wawlink.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import android.webkit.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import java.net.URI

@Composable
internal fun ContainerFeatureCardSurface(html: String, css: String, data: String, modifier: Modifier, onAction: (String) -> Unit, onError: (String) -> Unit) {
    var font by remember { mutableStateOf<FeatureWebFont?>(null) }
    val report by rememberUpdatedState(onError)
    LaunchedEffect(Unit) {
        try { font = featureWebFont() }
        catch (failure: Exception) { report("图标字体加载失败：${failure.message}") }
    }
    font?.let { bytes ->
        AndroidFeatureSurface(FEATURE_CARD_ORIGIN, FEATURE_CARD_URL, html, css, data, bytes, modifier, onAction, onError)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun AndroidFeatureSurface(
    origin: String, url: String, html: String?, css: String, data: String?, font: FeatureWebFont?,
    modifier: Modifier, onAction: ((String) -> Unit)?, onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val report by rememberUpdatedState(onError)
    val action by rememberUpdatedState(onAction)
    val currentCss by rememberUpdatedState(css)
    val currentData by rememberUpdatedState(data)
    val density = LocalDensity.current.density
    val holder = remember(origin, url, html) {
        val web = WebView(context)
        val bridge = FeatureAndroidLuaBridge(web, origin, if (onAction == null) null else { value -> action?.invoke(value) })
        AndroidFeatureWeb(web, bridge)
    }
    DisposableEffect(holder) {
        val web = holder.view
        fun allowed(value: String?): Boolean = try {
            val actual = URI(value ?: "")
            val expected = URI(origin)
            actual.scheme == expected.scheme && actual.host == expected.host && actual.port == expected.port && actual.rawUserInfo == null
        } catch (_: Exception) { false }
        web.apply {
            if (html != null) {
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, 28 * density) }
                }
                clipToOutline = true
            }
            setBackgroundColor(android.graphics.Color.WHITE)
            settings.apply {
                javaScriptEnabled = true; domStorageEnabled = html == null
                allowFileAccess = false; allowContentAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                javaScriptCanOpenWindowsAutomatically = false; setSupportMultipleWindows(false)
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE; safeBrowsingEnabled = true
                mediaPlaybackRequiresUserGesture = true; setGeolocationEnabled(false); defaultFontSize = 18
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    !request.isForMainFrame || !allowed(request.url.toString()) || html != null
                @Deprecated("Legacy WebView navigation")
                override fun shouldOverrideUrlLoading(view: WebView, value: String): Boolean = !allowed(value) || html != null
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val value = request.url.toString()
                    if (html == null && allowed(value)) return null
                    if (html != null && value.startsWith("data:")) return null
                    return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
                override fun onPageStarted(view: WebView, value: String, favicon: Bitmap?) {
                    holder.bridge.invalidate()
                    if (!allowed(value)) { view.stopLoading(); report("已阻止离开当前功能来源的导航") }
                }
                override fun onPageFinished(view: WebView, value: String) {
                    if (allowed(value)) view.evaluateJavascript(
                        currentData?.let { featureCardUpdateScript(currentCss, it) } ?: containerFeatureThemeScript(currentCss), null,
                    )
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) report("页面加载失败：${error.description}")
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame) report("本地页面返回 HTTP ${response.statusCode}")
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) { handler.cancel() }
            }
            webChromeClient = object : WebChromeClient() {
                // Browser media prompts are not the Lua Java bridge; Lua can use Android's permission APIs directly.
                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) { callback.invoke(origin, false, false) }
                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean = false
                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean { callback.onReceiveValue(null); return true }
            }
        }
        val installed = runCatching { holder.bridge.install(css) }
            .onFailure { report("原生桥初始化失败：${it.message}") }.getOrDefault(false)
        if (installed) {
            holder.installed = true
            if (html == null) web.loadUrl(url)
            else web.loadDataWithBaseURL(url, featureCardDocument(html, css, data ?: "{}", requireNotNull(font), ""), "text/html", "utf-8", null)
        } else report("当前 Android System WebView 不支持安全的原生桥，请更新系统 WebView。")
        onDispose {
            holder.bridge.close()
            web.stopLoading(); web.webChromeClient = null; web.webViewClient = WebViewClient()
            web.removeAllViews(); web.destroy()
        }
    }
    AndroidView(factory = { holder.view }, modifier = modifier, update = { web ->
        if (holder.installed) web.evaluateJavascript(data?.let { featureCardUpdateScript(css, it) } ?: containerFeatureThemeScript(css), null)
    })
}

private class AndroidFeatureWeb(val view: WebView, val bridge: FeatureAndroidLuaBridge) {
    var installed = false
}
