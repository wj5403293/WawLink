package dev.wawlink.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import dev.wawlink.runtime.FEATURE_DEBUG_BOOTSTRAP

@Composable
internal fun ContainerFeatureCardSurface(html: String?, url: String?, css: String, data: String, modifier: Modifier, onAction: (String) -> Unit, onError: (String) -> Unit) {
    require((html == null) != (url == null)) { "Exactly one of html and url is required" }
    var font by remember { mutableStateOf<FeatureWebFont?>(null) }
    val report by rememberUpdatedState(onError)
    LaunchedEffect(Unit) {
        try { font = featureWebFont() }
        catch (failure: Exception) { report("图标字体加载失败：${failure.message}") }
    }
    font?.let { bytes ->
        val documentUrl = url ?: FEATURE_CARD_URL
        AndroidFeatureSurface(featureDocumentOrigin(documentUrl), documentUrl, html, css, data, bytes, true, modifier, onAction, onError)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun AndroidFeatureSurface(
    origin: String, url: String, html: String?, css: String, data: String?, font: FeatureWebFont?, rounded: Boolean,
    modifier: Modifier, onAction: ((String) -> Unit)?, onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val report by rememberUpdatedState(onError)
    val action by rememberUpdatedState(onAction)
    val currentCss by rememberUpdatedState(css)
    val currentData by rememberUpdatedState(data)
    val debugContext = LocalFeatureDebugContext.current
    val density = LocalDensity.current.density
    val holder = remember(origin, url, html) {
        val web = WebView(context)
        // WRAP_CONTENT makes WebView treat the CSS viewport height as zero, even with an exact Compose size.
        web.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val bridge = FeatureAndroidLuaBridge(web, origin, if (onAction == null) null else { value -> action?.invoke(value) })
        val debug = AndroidFeatureDebugPage(web, { featureSameOrigin(web.url, origin) }) { refreshedHtml ->
            if (html == null) web.loadUrl(url)
            else web.loadDataWithBaseURL(url, featureCardDocument(requireNotNull(refreshedHtml), currentCss, currentData ?: "{}", requireNotNull(font), ""), "text/html", "utf-8", null)
        }
        AndroidFeatureWeb(web, bridge, debug)
    }
    DisposableEffect(holder, debugContext) {
        val registration = debugContext?.takeUnless { holder.released }?.let { binding ->
            binding.debugger.register(binding.target, dev.wawlink.runtime.FeatureDebugPage { operation, expression ->
                val argument = if (operation == "reload" && html != null) binding.debugger.readCardHtml(binding.target) else expression
                holder.debug.request(operation, argument)
            })
        }
        holder.debugRegistration = registration
        onDispose {
            if (holder.debugRegistration === registration) {
                holder.debugRegistration = null
                registration?.close()
            }
        }
    }
    DisposableEffect(holder) {
        val web = holder.view
        fun allowed(value: String?): Boolean = featureSameOrigin(value, origin)
        web.apply {
            if (rounded) {
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, 28 * density) }
                }
                clipToOutline = true
            }
            setBackgroundColor(android.graphics.Color.WHITE)
            settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true
                allowFileAccess = false; allowContentAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                javaScriptCanOpenWindowsAutomatically = false; setSupportMultipleWindows(false)
                // HTTPS static cards may fetch loopback HTTP. CSP and request interception restrict it to loopback.
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE; safeBrowsingEnabled = true
                mediaPlaybackRequiresUserGesture = true; setGeolocationEnabled(false); defaultFontSize = 18
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    if (!holder.released) {
                        try { holder.release(rendererGone = true) }
                        finally { report("页面渲染进程已退出，请离开后重新打开") }
                    }
                    return true
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    holder.released || !request.isForMainFrame || !allowed(request.url.toString()) || html != null
                @Deprecated("Legacy WebView navigation")
                override fun shouldOverrideUrlLoading(view: WebView, value: String): Boolean = holder.released || !allowed(value) || html != null
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val value = request.url.toString()
                    if (request.isForMainFrame) {
                        if (allowed(value)) return null
                    } else if (featureResourceAllowed(value)) return null
                    return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
                override fun onPageStarted(view: WebView, value: String, favicon: Bitmap?) {
                    if (holder.released) return
                    holder.debug.navigating()
                    holder.bridge.invalidate()
                    if (!allowed(value)) { view.stopLoading(); report("已阻止离开当前功能来源的导航") }
                }
                override fun onPageFinished(view: WebView, value: String) {
                    if (holder.released) return
                    if (allowed(value)) view.evaluateJavascript(
                        (currentData?.let { featureCardUpdateScript(currentCss, it) } ?: containerFeatureThemeScript(currentCss)) + ";" + featureReadyScript(), null,
                    )
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (holder.released) return
                    holder.debug.recordError("页面加载失败：${error.description}")
                    if (request.isForMainFrame) report("页面加载失败：${error.description}")
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (holder.released) return
                    holder.debug.recordError("页面 HTTP ${response.statusCode}：${request.url.scheme}://${request.url.host}${request.url.path}")
                    if (request.isForMainFrame) report("本地页面返回 HTTP ${response.statusCode}")
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                    handler.cancel()
                    if (!holder.released) {
                        holder.debug.recordError("页面 TLS 验证失败：${error.primaryError}")
                        report("页面 TLS 验证失败：${error.primaryError}")
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    if (holder.released) return false
                    if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR || message.messageLevel() == ConsoleMessage.MessageLevel.WARNING) {
                        holder.debug.recordError("${message.messageLevel()}：${message.message()}")
                    }
                    return false
                }
                // Browser media prompts are not the Lua Java bridge; Lua can use Android's permission APIs directly.
                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) { callback.invoke(origin, false, false) }
                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean = false
                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean { callback.onReceiveValue(null); return true }
            }
        }
        val initialization = FEATURE_DEBUG_BOOTSTRAP + ";" + featureDocumentPolicyScript() + ";" +
            (if (html == null) font?.let { featureDocumentFontScript(it) } ?: "" else "") + ";" +
            featureWhenDocumentExists(data?.let { featureCardUpdateScript(css, it) } ?: containerFeatureThemeScript(css)) + ";" + featureReadyScript()
        val installed = runCatching { holder.bridge.install(initialization) }
            .onFailure { report("原生桥初始化失败：${it.message}") }.getOrDefault(false)
        if (installed) {
            holder.installed = true
            if (html == null) web.loadUrl(url)
            else web.loadDataWithBaseURL(url, featureCardDocument(html, css, data ?: "{}", requireNotNull(font), ""), "text/html", "utf-8", null)
        } else report("当前 Android System WebView 不支持安全的原生桥，请更新系统 WebView。")
        onDispose { holder.release() }
    }
    if (holder.released) Box(modifier)
    else AndroidView(factory = { holder.view }, modifier = modifier, update = { web ->
        if (!holder.released && holder.installed) web.evaluateJavascript(data?.let { featureCardUpdateScript(css, it) } ?: containerFeatureThemeScript(css), null)
    })
}

private class AndroidFeatureWeb(val view: WebView, val bridge: FeatureAndroidLuaBridge, val debug: AndroidFeatureDebugPage) {
    var installed = false
    var released by mutableStateOf(false)
        private set
    var debugRegistration: AutoCloseable? = null

    fun release(rendererGone: Boolean = false) {
        if (released) return
        released = true
        installed = false
        try {
            debug.close()
            debugRegistration?.close()
            debugRegistration = null
            bridge.close(rendererGone)
            // A dead renderer must only be detached and destroyed, not queried or reconfigured.
            if (!rendererGone) {
                view.stopLoading(); view.webChromeClient = null; view.webViewClient = WebViewClient()
                view.removeAllViews()
            }
        } finally {
            try { (view.parent as? ViewGroup)?.removeView(view) }
            finally { view.destroy() }
        }
    }
}
