package dev.wawlink.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wawlink.protocol.AttachmentFile
import dev.wawlink.protocol.AttachmentSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** A bounded read; sizeBytes describes the complete original file, not the returned prefix. */
data class AttachmentContent(val bytes: ByteArray, val sizeBytes: Long, val truncated: Boolean)
fun interface AgentAttachmentHandler {
    suspend fun read(file: AttachmentFile, maxBytes: Int): AttachmentContent
}
val LocalAgentAttachmentHandler = staticCompositionLocalOf<AgentAttachmentHandler?> { null }
internal const val ATTACHMENT_TEXT_BYTES = 64 * 1024
internal const val ATTACHMENT_IMAGE_BYTES = 8 * 1024 * 1024

internal fun attachmentMimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "svg" -> "image/svg+xml"
    "json", "jsonl" -> "application/json"
    "txt", "md", "kt", "kts", "java", "js", "ts", "py", "sh", "yaml", "yml", "toml", "conf", "xml", "html", "css" -> "text/plain"
    else -> "application/octet-stream"
}
internal fun attachmentIsImage(file: AttachmentFile): Boolean =
    file.mimeType.substringBefore(';').trim().lowercase() in setOf("image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif", "image/svg+xml") || attachmentMimeType(file.name).startsWith("image/")
internal fun attachmentSourceLabel(file: AttachmentFile): String = when (file.source) {
    AttachmentSource.LOCAL -> "本机"
    AttachmentSource.WORKSPACE -> "工作区"
    AttachmentSource.CONTAINER -> "容器"
}
private fun attachmentSize(size: Long): String = when {
    size < 0 -> "大小未知"
    size >= 1024 * 1024 -> "${decimal(size / (1024.0 * 1024), 1)} MiB"
    size >= 1024 -> "${decimal(size / 1024.0, 1)} KiB"
    else -> "$size B"
}
internal fun attachmentPreviewText(bytes: ByteArray, truncated: Boolean = false): String {
    require(bytes.size <= ATTACHMENT_TEXT_BYTES) { "预览内容超过 64 KiB" }
    // A bounded prefix may end inside a valid UTF-8 character. Omit only that
    // incomplete final character; malformed content still fails strict decoding.
    var end = bytes.size
    if (truncated && bytes.isNotEmpty()) {
        var lead = bytes.lastIndex
        while (lead > 0 && (bytes[lead].toInt() and 0xc0) == 0x80 && bytes.lastIndex - lead < 3) lead--
        val first = bytes[lead].toInt() and 0xff
        val required = when (first) { in 0xc2..0xdf -> 2; in 0xe0..0xef -> 3; in 0xf0..0xf4 -> 4; else -> 1 }
        val second = if (lead + 1 < bytes.size) bytes[lead + 1].toInt() and 0xff else null
        val validSecond = second == null || when (first) {
            0xe0 -> second in 0xa0..0xbf
            0xed -> second in 0x80..0x9f
            0xf0 -> second in 0x90..0xbf
            0xf4 -> second in 0x80..0x8f
            else -> second in 0x80..0xbf
        }
        if (required > bytes.size - lead && validSecond) end = lead
    }
    val text = bytes.decodeToString(endIndex = end, throwOnInvalidSequence = true)
    require(text.none { it == '\u0000' || (it < ' ' && it !in "\t\r\n") }) { "二进制文件无法快速预览" }
    return text
}
internal expect suspend fun prepareAttachmentGraphic(file: AttachmentFile, bytes: ByteArray, theme: RichGraphicTheme,
    svgRasterizer: suspend (String) -> ImageBitmap): PreparedGraphic.Raster

@Composable
private fun AttachmentFileIcon(file: AttachmentFile) {
    val language = inferCodeLanguage(path = file.name)
    val config = language in setOf("json", "jsonc", "jsonl", "yaml", "toml", "ini")
    Box(Modifier.size(42.dp).background(if (config) Color(0xFFC4EED0) else Color(0xFFD3E3FD), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
        Symbol(if (config) "data_object" else if (language != null) "code" else "description", size = 23.dp,
            tint = if (config) Color(0xFF146C2E) else Color(0xFF0B57D0))
    }
}

@Composable
private fun Modifier.attachmentPressScale(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .96f else 1f, tween(300, easing = EmphEasing))
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

@Composable
internal fun AttachmentStrip(files: List<AttachmentFile>, enabled: Boolean, onRemove: (AttachmentFile) -> Unit) {
    val currentFiles = files.toList()
    val displayed = remember { files.toMutableStateList() }
    LaunchedEffect(currentFiles) {
        currentFiles.forEach { file ->
            val index = displayed.indexOfFirst { it.id == file.id && it.source == file.source && it.path == file.path }
            if (index < 0) displayed.add(file) else displayed[index] = file
        }
        delay(400)
        displayed.removeAll { old -> currentFiles.none { it.id == old.id && it.source == old.source && it.path == old.path } }
    }
    if (displayed.isNotEmpty()) Row(Modifier.fillMaxWidth().animateContentSize(tween(350, easing = EmphEasing)).horizontalScroll(rememberScrollState()).padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 8.dp)) {
        displayed.forEach { file -> key(file.id, file.source, file.path) {
            val visible = remember { MutableTransitionState(false) }
            visible.targetState = currentFiles.any { it.id == file.id && it.source == file.source && it.path == file.path }
            AnimatedVisibility(visible,
                enter = expandHorizontally(tween(350, easing = EmphDecelEasing)) + fadeIn(tween(250, easing = EmphDecelEasing)) + scaleIn(tween(350, easing = EmphDecelEasing), initialScale = .8f),
                exit = shrinkHorizontally(tween(350, easing = EmphEasing)) + fadeOut(tween(220, easing = EmphAccelEasing)) + scaleOut(tween(300, easing = EmphAccelEasing), targetScale = .8f)) {
                Box(Modifier.padding(end = 8.dp)) {
                    if (attachmentIsImage(file)) AttachmentImage(file, Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)))
                    else AttachmentFileCard(file, sent = false, modifier = Modifier.width(220.dp).height(72.dp), dismissSpace = true)
                    Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(CircleShape)
                        .background(if (attachmentIsImage(file)) Color.Black.copy(alpha = .68f) else LocalAgentColors.current.surfaceContainerHigh)
                        .clickable(enabled = enabled && visible.targetState, onClickLabel = "移除附件 ${file.name}") { onRemove(file) }, contentAlignment = Alignment.Center) {
                        Symbol("close", size = 15.dp, tint = if (attachmentIsImage(file)) Color.White else LocalAgentColors.current.onSurfaceVariant, contentDescription = "移除附件 ${file.name}")
                    }
                }
            }
        } }
    }
}

@Composable
internal fun AttachmentPills(files: List<AttachmentFile>) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        files.forEach { file ->
            Row(Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).background(LocalAgentColors.current.surface).padding(start = 6.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Symbol(if (attachmentIsImage(file)) "image" else "description", size = 16.dp, tint = LocalAgentColors.current.primary)
                Text("${file.name} · ${attachmentSourceLabel(file)}", fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun UserAttachments(files: List<AttachmentFile>) {
    val images = remember(files) { files.filter(::attachmentIsImage) }
    val ordinary = remember(files) { files.filterNot(::attachmentIsImage) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (images.isNotEmpty()) Column(Modifier.widthIn(max = if (images.size == 1) 280.dp else 296.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (images.size == 1) AttachmentImage(images[0], Modifier.fillMaxWidth().aspectRatio(16f / 10f), album = true)
            else {
                val rest = if (images.size == 3) {
                    AttachmentImage(images[0], Modifier.fillMaxWidth().aspectRatio(16f / 9f), album = true)
                    images.drop(1)
                } else images
                rest.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { AttachmentImage(it, Modifier.weight(1f).aspectRatio(4f / 3f), album = true) }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        ordinary.forEach { AttachmentFileCard(it, sent = true, modifier = Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun AttachmentImage(file: AttachmentFile, modifier: Modifier, album: Boolean = false) {
    val handler = LocalAgentAttachmentHandler.current
    val rasterizer = rememberSvgRasterizer()
    val c = LocalAgentColors.current
    fun Color.css() = "#" + (toArgb() and 0xffffff).toString(16).padStart(6, '0')
    val theme = remember(c) { RichGraphicTheme(c.onSurface.css(), c.surface.css(), c.primary.css()) }
    var image by remember(file, handler) { mutableStateOf<PreparedGraphic.Raster?>(null) }
    var failure by remember(file, handler) { mutableStateOf<String?>(null) }
    var retry by remember(file, handler) { mutableIntStateOf(0) }
    var expanded by remember(file) { mutableStateOf(false) }
    val interaction = remember(file) { MutableInteractionSource() }
    LaunchedEffect(file, handler, retry, rasterizer) {
        image = null; failure = null
        try {
            requireNotNull(handler) { "当前环境不支持读取附件" }
            image = withContext(Dispatchers.IO) {
                val content = handler.read(file, ATTACHMENT_IMAGE_BYTES)
                require(!content.truncated && content.bytes.size <= ATTACHMENT_IMAGE_BYTES) { "图片超过 8 MiB，无法预览" }
                prepareAttachmentGraphic(file, content.bytes, theme, rasterizer)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure = e.message ?: "图片读取失败" }
    }
    Box(modifier.attachmentPressScale(interaction).background(c.surfaceContainerHighest).clickable(interactionSource = interaction, indication = ripple(), onClickLabel = if (failure != null) "重试图片 ${file.name}" else "全屏查看 ${file.name}") {
        if (image != null) expanded = true else if (failure != null) retry++
    }, contentAlignment = Alignment.Center) {
        image?.let { Image(it.image, file.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: if (failure == null) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (album) "$failure · 点击重试" else "重试", fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                ErrorDetailsButton(failure!!, failure!!)
            }
        Text(attachmentSourceLabel(file), Modifier.align(Alignment.BottomStart).padding(if (album) 8.dp else 5.dp)
            .background(Color.Black.copy(alpha = .62f), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 2.dp), color = Color.White, fontSize = if (album) 12.sp else 10.sp)
        if (album) Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(28.dp).background(Color.Black.copy(alpha = .56f), CircleShape), contentAlignment = Alignment.Center) {
            Symbol("fullscreen", size = 17.dp, tint = Color.White, contentDescription = "全屏查看 ${file.name}")
        }
    }
    if (expanded) image?.let { ImageDetailScreen(it, file.name) { expanded = false } }
}

@Composable
private fun AttachmentFileCard(file: AttachmentFile, sent: Boolean, modifier: Modifier = Modifier, dismissSpace: Boolean = false) {
    val c = LocalAgentColors.current
    val link = LocalAgentFileLinkHandler.current
    var preview by remember(file) { mutableStateOf(false) }
    val interaction = remember(file) { MutableInteractionSource() }
    Row(modifier.attachmentPressScale(interaction).clip(RoundedCornerShape(if (sent) 18.dp else 16.dp)).background(c.surface)
        .clickable(interactionSource = interaction, indication = ripple(), onClickLabel = "查看附件 ${file.name}") {
            if (sent && file.source != AttachmentSource.LOCAL && link != null) link(file.path) else preview = true
        }.padding(start = 10.dp, end = if (dismissSpace) 30.dp else 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AttachmentFileIcon(file)
        Column(Modifier.weight(1f)) {
            Text(file.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${attachmentSize(file.sizeBytes)} · ${attachmentSourceLabel(file)}", color = c.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (sent) Symbol("more_horiz", size = 20.dp, tint = c.onSurfaceVariant)
    }
    if (preview) AttachmentQuickPreview(file) { preview = false }
}

@Composable
private fun AttachmentQuickPreview(file: AttachmentFile, onDismiss: () -> Unit) {
    val handler = LocalAgentAttachmentHandler.current
    val c = LocalAgentColors.current
    var content by remember(file, handler) { mutableStateOf<AttachmentContent?>(null) }
    var text by remember(file, handler) { mutableStateOf<String?>(null) }
    var failure by remember(file, handler) { mutableStateOf<String?>(null) }
    var retry by remember(file, handler) { mutableIntStateOf(0) }
    LaunchedEffect(file, handler, retry) {
        content = null; text = null; failure = null
        try {
            requireNotNull(handler) { "当前环境不支持读取附件" }
            val result = withContext(Dispatchers.IO) {
                val bytes = handler.read(file, ATTACHMENT_TEXT_BYTES)
                bytes to runCatching { attachmentPreviewText(bytes.bytes, bytes.truncated) }
            }
            content = result.first
            text = result.second.getOrElse { throw IllegalArgumentException("文件不是有效 UTF-8 文本或为二进制，无法快速预览", it) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure = e.message ?: "附件读取失败" }
    }
    AgentSheet(file.name, onDismiss) {
        Text("${attachmentSize(content?.sizeBytes ?: file.sizeBytes)} · ${attachmentSourceLabel(file)} · ${file.mimeType}", color = c.onSurfaceVariant, fontSize = 14.sp)
        Text(file.path, color = c.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
        if (content?.truncated == true) Text("仅预览前 64 KiB，内容已截断", color = c.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
        if (failure != null) {
            ErrorMessage(failure!!, color = c.error, details = failure!!)
            TextButton({ retry++ }) { Text("重试") }
        } else if (text == null) CircularProgressIndicator(Modifier.padding(16.dp).size(24.dp), strokeWidth = 2.dp)
        else {
            Box(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()).background(c.surfaceContainerLow, RoundedCornerShape(16.dp)).padding(14.dp)) {
                if (text!!.isEmpty()) Text("空文件", color = c.onSurfaceVariant)
                else HighlightedCode(text!!, inferCodeLanguage(path = file.name))
            }
        }
    }
}
