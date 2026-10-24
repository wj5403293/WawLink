package dev.wawlink.app

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.wawlink.runtime.ContainerFeatureSession
import java.io.ByteArrayInputStream

/** Deliberately no addJavascriptInterface, file chooser, downloads, popups or external intents. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun ContainerWebSurface(session: ContainerFeatureSession, css: String, modifier: Modifier, onError: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val currentCss by rememberUpdatedState(css)
    val report by rememberUpdatedState(onError)
    val webView = remember(session) {
        WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
                safeBrowsingEnabled = true
                mediaPlaybackRequiresUserGesture = true
                setGeolocationEnabled(false)
                defaultFontSize = 18
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = !containerFeatureAllows(request.url.toString(), session)
                @Deprecated("Legacy WebView navigation")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = !containerFeatureAllows(url, session)
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    return if (containerFeatureAllows(request.url.toString(), session)) null else WebResourceResponse(
                        "text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream("仅允许当前功能的本地来源".toByteArray()),
                    )
                }
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    if (!containerFeatureAllows(url, session)) { view.stopLoading(); report("已阻止离开当前功能来源的导航") }
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (containerFeatureAllows(url, session)) view.evaluateJavascript(containerFeatureThemeScript(currentCss), null)
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) report("页面加载失败：${error.description}")
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                    if (request.isForMainFrame) report("本地页面返回 HTTP ${errorResponse.statusCode}")
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) { handler.cancel() }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) { callback.invoke(origin, false, false) }
                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean = false
                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean { callback.onReceiveValue(null); return true }
            }
            setDownloadListener { _, _, _, _, _ -> report("安全策略不允许功能页面下载到宿主文件；请使用容器文件管理") }
            loadUrl(session.url)
        }
    }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        }
    }
    AndroidView(factory = { webView }, modifier = modifier, update = { view ->
        if (containerFeatureAllows(view.url, session)) view.evaluateJavascript(containerFeatureThemeScript(css), null)
    })
}
