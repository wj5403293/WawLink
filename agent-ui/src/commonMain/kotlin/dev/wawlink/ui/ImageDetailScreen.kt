package dev.wawlink.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ImageDetailScreen(image: PreparedGraphic.Raster, title: String, onBack: () -> Unit) {
    val colors = LocalAgentColors.current
    val saveHandler = LocalAgentImageSaveHandler.current
    val scope = rememberCoroutineScope()
    var saving by remember(image) { mutableStateOf(false) }
    var message by remember(image) { mutableStateOf<String?>(null) }
    var scale by remember(image) { mutableStateOf(1f) }
    var offset by remember(image) { mutableStateOf(Offset.Zero) }
    fun reset() { scale = 1f; offset = Offset.Zero }
    PlatformCodeDetail(onBack, title = "图片 · $title") {
        Surface(Modifier.fillMaxSize(), color = colors.surface, contentColor = colors.onSurface) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    AgentIconButton("arrow_back", "返回", onBack)
                    Text(title, Modifier.weight(1f).padding(8.dp), maxLines = 1, style = MaterialTheme.typography.titleMedium)
                    AgentIconButton("restart_alt", "重置缩放", { reset() })
                    AgentIconButton("zoom_in", "放大", { scale = (scale * 1.5f).coerceAtMost(8f) })
                    AgentIconButton("zoom_out", "缩小", { scale = (scale / 1.5f).coerceAtLeast(1f); if (scale == 1f) offset = Offset.Zero })
                    if (saveHandler != null && !saving) {
                        AgentIconButton("download", "保存原图", {
                            saving = true
                            message = null
                            scope.launch {
                                try {
                                    if (saveHandler(image.fileName, image.mimeType, image.bytes)) message = "图片已保存"
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    message = "图片保存失败，请重试"
                                } finally { saving = false }
                            }
                        })
                    }
                }
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (saveHandler == null) Text("当前环境不支持保存图片", Modifier.padding(12.dp), color = colors.onSurfaceVariant)
                message?.let { if (it.startsWith("图片保存失败")) ErrorMessage(it, Modifier.padding(12.dp), color = colors.error, details = it) else Text(it, Modifier.padding(12.dp), color = colors.onSurfaceVariant) }
                Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()
                    .pointerInput(image) { detectTapGestures(onDoubleTap = { reset() }) }
                    .pointerInput(image) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val next = (scale * zoom).coerceIn(1f, 8f)
                            offset = if (next == 1f) Offset.Zero else Offset(
                                (offset.x + pan.x).coerceIn(-size.width * (next - 1) / 2, size.width * (next - 1) / 2),
                                (offset.y + pan.y).coerceIn(-size.height * (next - 1) / 2, size.height * (next - 1) / 2))
                            scale = next
                        }
                    }, contentAlignment = Alignment.Center) {
                    Image(image.image, title, Modifier.fillMaxSize().graphicsLayer {
                        scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                    }, contentScale = ContentScale.Fit)
                }
            }
        }
    }
}
