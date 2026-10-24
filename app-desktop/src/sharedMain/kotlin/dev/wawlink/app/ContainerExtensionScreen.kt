package dev.wawlink.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wawlink.runtime.*
import dev.wawlink.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.net.URI

@Composable
internal fun ContainerExtensionScreen(service: ContainerFeatureService, containerId: String, feature: ContainerFeature, onBack: () -> Unit) {
    val allStates by service.states.collectAsState()
    val state = allStates[service.stateKey(containerId, feature.id)] ?: ContainerFeatureState()
    var attempt by remember(containerId, feature.id) { mutableStateOf(0) }
    var showOutput by remember(containerId, feature.id) { mutableStateOf(false) }
    var surfaceError by remember(containerId, feature.id, attempt) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val css = containerFeatureThemeCss()
    SideEffect { service.setTheme(css) }
    ContainerFeatureIcons(service)
    LaunchedEffect(containerId, feature.id, attempt) {
        try { service.start(containerId, feature) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { showOutput = true /* The service retains the actual failure and output. */ }
    }
    DisposableEffect(containerId, feature.id) { onDispose { service.leavePage(containerId, feature.id) } }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        AgentTopBar(feature.title, onBack = {
            scope.launch {
                try { service.leave(containerId, feature.id) }
                finally { onBack() }
            }
        }, actions = {
            AgentIconButton("terminal", "查看服务输出", { showOutput = !showOutput }, filled = showOutput)
            if (state.status == ContainerFeatureStatus.RUNNING) AgentIconButton("stop_circle", "停止服务", {
                scope.launch { service.stop(containerId, feature.id) }
            })
            if (state.status == ContainerFeatureStatus.FAILED || state.status == ContainerFeatureStatus.STOPPED) AgentIconButton("refresh", "重新启动", { attempt++ })
        })
        Text(if (feature.lifecycle == ContainerFeatureLifecycle.MANUAL) "手动生命周期 · 离页继续运行，可在此页或功能卡片停止" else "页面生命周期 · 离开此页自动停止服务", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AnimatedVisibility(showOutput, enter = expandVertically(tween(420, easing = EmphEasing)), exit = shrinkVertically(tween(360, easing = EmphEasing))) {
            SelectionContainer {
                Text(state.output.ifEmpty { "暂无进程输出" }, Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp), fontFamily = LocalAgentMono.current, fontSize = 14.sp)
            }
        }
        val error = surfaceError ?: state.error
        if (error != null) {
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Text(error, Modifier.fillMaxWidth().padding(16.dp), fontSize = 18.sp, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        when (state.status) {
            ContainerFeatureStatus.RUNNING -> state.session?.let { session ->
                ContainerWebSurface(session, css, Modifier.fillMaxSize(), onError = { surfaceError = it })
            }
            ContainerFeatureStatus.STARTING, ContainerFeatureStatus.STOPPING -> Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                CircularProgressIndicator()
                Text(if (state.status == ContainerFeatureStatus.STARTING) "正在启动并等待本地服务就绪…" else "正在停止并回收服务进程…", fontSize = 20.sp)
                Text("仅运行当前容器内的脚本，不访问宿主文件或开放原生 JS 桥。", fontSize = 17.sp)
            }
            ContainerFeatureStatus.STOPPED, ContainerFeatureStatus.FAILED -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Symbol(if (state.status == ContainerFeatureStatus.FAILED) "error" else "stop_circle", size = 40.dp, tint = MaterialTheme.colorScheme.primary)
                Text(if (state.status == ContainerFeatureStatus.FAILED) "功能未能运行" else "服务已停止", fontSize = 24.sp)
                Text("可查看真实输出以排查依赖、端口、令牌验证或启动脚本问题。", fontSize = 18.sp)
                Button({ attempt++ }, modifier = Modifier.heightIn(min = 48.dp)) { Text("重新启动", fontSize = 18.sp) }
            }
        }
    }
}

@Composable
internal fun containerFeatureThemeCss(): String {
    val colors = MaterialTheme.colorScheme
    fun Color.hex() = "#%06x".format(toArgb() and 0xffffff)
    return """
        @font-face{font-family:'Material Symbols Rounded';src:url('/__wawlink_native/material-symbols.ttf') format('truetype');font-weight:100 700;font-style:normal;font-display:block}
        :root{--md-sys-color-primary:${colors.primary.hex()};--md-sys-color-primary-container:${colors.primaryContainer.hex()};--md-sys-color-on-primary:${colors.onPrimary.hex()};--md-sys-color-on-primary-container:${colors.onPrimaryContainer.hex()};--md-sys-color-surface:#ffffff;--md-sys-color-on-surface:#1f1f1f;--md-sys-color-outline:${colors.outline.hex()};--wawlink-motion:cubic-bezier(.2,0,0,1);color-scheme:light}
        body{background:#fff;color:var(--md-sys-color-on-surface);font-family:system-ui,'Noto Sans CJK SC',sans-serif;font-size:18px;line-height:1.6}button,input,select,textarea{font:inherit}button,[role=button]{min-height:48px;transition:background-color .3s var(--wawlink-motion),transform .45s var(--wawlink-motion)}button:active{transform:scale(.96)}.material-symbols-outlined,.material-symbols-rounded{font-family:'Material Symbols Rounded';font-weight:normal;font-style:normal;font-size:24px;line-height:1;letter-spacing:normal;text-transform:none;display:inline-block;white-space:nowrap;word-wrap:normal;direction:ltr;font-feature-settings:'liga';-webkit-font-smoothing:antialiased}@media(prefers-reduced-motion:reduce){*,*::before,*::after{animation-duration:.01ms!important;transition-duration:.01ms!important}}
    """.trimIndent()
}

@Composable
internal fun ContainerFeatureIcons(service: ContainerFeatureService) {
    LaunchedEffect(service) {
        service.setIconFont(dev.wawlink.ui.resources.Res.readBytes("font/material_symbols_rounded.ttf"))
    }
}

internal fun containerFeatureAllows(url: String?, session: ContainerFeatureSession): Boolean = try {
    val expected = URI(session.url)
    val actual = URI(url ?: "")
    actual.scheme == "http" && actual.host == "127.0.0.1" && actual.port == expected.port && actual.rawUserInfo == null
} catch (_: Exception) { false }

internal fun containerFeatureThemeScript(css: String): String {
    val quoted = buildString {
        append('"')
        css.forEach { c -> when (c) { '\\' -> append("\\\\"); '"' -> append("\\\""); '\n' -> append("\\n"); '\r' -> append("\\r"); '\u2028' -> append("\\u2028"); '\u2029' -> append("\\u2029"); else -> append(c) } }
        append('"')
    }
    return "(function(){var s=document.getElementById('wawlink-native-theme');if(!s){s=document.createElement('style');s.id='wawlink-native-theme';(document.head||document.documentElement).appendChild(s);}s.textContent=$quoted;})()"
}
