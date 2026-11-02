package dev.wawlink.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal data class RichGraphicTheme(val foreground: String, val background: String, val accent: String)
internal sealed interface PreparedGraphic {
    data class Html(val document: String) : PreparedGraphic
    data class Raster(val image: ImageBitmap, val bytes: ByteArray, val mimeType: String, val fileName: String) : PreparedGraphic
}

val LocalAgentImageSaveHandler = staticCompositionLocalOf<(suspend (name: String, mimeType: String, bytes: ByteArray) -> Boolean)?> { null }
private fun Color.cssColor() = "#" + (toArgb() and 0xffffff).toString(16).padStart(6, '0')

@Composable
internal fun RichGraphic(graphic: MarkdownPart.Graphic, modifier: Modifier = Modifier) {
    val colors = LocalAgentColors.current
    val svgRasterizer = rememberSvgRasterizer()
    val theme = remember(colors.onSurface, colors.surfaceContainerLow, colors.primary) {
        RichGraphicTheme(colors.onSurface.cssColor(), colors.surfaceContainerLow.cssColor(), colors.primary.cssColor())
    }
    var prepared by remember(graphic.kind, graphic.source) { mutableStateOf<PreparedGraphic?>(null) }
    var failure by remember(graphic.kind, graphic.source) { mutableStateOf<String?>(null) }
    var loading by remember(graphic.kind, graphic.source) { mutableStateOf(true) }
    var height by remember(graphic.kind, graphic.source) { mutableStateOf(96f) }
    var retry by remember(graphic.kind, graphic.source) { mutableStateOf(0) }
    var expanded by remember(graphic.kind, graphic.source) { mutableStateOf(false) }
    LaunchedEffect(graphic.kind, graphic.source, theme, retry, svgRasterizer) {
        // Native raster pixels do not depend on the chat theme or the image description.
        val current = prepared
        if (current is PreparedGraphic.Raster && current.mimeType != "image/svg+xml") return@LaunchedEffect
        prepared = null
        loading = true
        failure = null
        try {
            prepared = withContext(Dispatchers.IO) { prepareRichGraphic(graphic, theme, svgRasterizer) }
            if (prepared is PreparedGraphic.Raster) loading = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = error.message?.takeIf { it.startsWith("图") } ?: "图形加载失败，请检查内容或网络连接"
            loading = false
        }
    }
    LaunchedEffect(prepared) {
        if (prepared is PreparedGraphic.Html) {
            delay(25_000)
            if (loading) {
                failure = "图形渲染超时，请更新图形运行组件或检查内容"
                loading = false
            }
        }
    }
    Surface(modifier.fillMaxWidth(), color = colors.surfaceContainerLow, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(8.dp)) {
            if (loading) {
                Text("正在加载图形…", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
            }
            if (failure != null) {
                ErrorMessage(failure!!, Modifier.padding(8.dp), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, details = failure!!)
                TextButton(onClick = { prepared = null; retry++ }) { Text("重试") }
            } else {
                when (val result = prepared) {
                    is PreparedGraphic.Raster -> Image(result.image, graphic.description.ifBlank { "图片" },
                        Modifier.fillMaxWidth().heightIn(max = 560.dp).clickable { expanded = true }, contentScale = ContentScale.Fit)
                    is PreparedGraphic.Html -> PlatformRichGraphic(result.document,
                        Modifier.fillMaxWidth().height(height.dp).semantics {
                            contentDescription = graphic.description.ifBlank {
                                if (graphic.kind == GraphicKind.Mermaid) "Mermaid 图表" else "图片"
                            }
                        }) { measuredHeight, error ->
                        if (measuredHeight != null && measuredHeight.isFinite()) height = measuredHeight.coerceIn(48f, 560f)
                        failure = error
                        loading = false
                    }
                    null -> Unit
                }
            }
        }
    }
    if (expanded) (prepared as? PreparedGraphic.Raster)?.let { image ->
        ImageDetailScreen(image, graphic.description.ifBlank { "图片" }) { expanded = false }
    }
}

@Composable
internal expect fun rememberSvgRasterizer(): suspend (String) -> ImageBitmap

internal expect suspend fun prepareRichGraphic(graphic: MarkdownPart.Graphic, theme: RichGraphicTheme, svgRasterizer: suspend (String) -> ImageBitmap): PreparedGraphic

/** Supplies only the bundled, app-owned Mermaid engine; never model-authored script. */
internal expect suspend fun mermaidEngineScript(): String

/** The host displays only trusted offline documents/resources; it never resolves model URLs. */
@Composable
internal expect fun PlatformRichGraphic(html: String, modifier: Modifier, onResult: (Float?, String?) -> Unit)
