package dev.wawlink.app

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Data
import org.jetbrains.skia.Codec
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.math.max
import kotlin.math.roundToInt

private fun checkedContainerImage(bytes: ByteArray): Image {
    require(bytes.isNotEmpty() && bytes.size <= 10 * 1024 * 1024) { "图片文件必须在 10 MiB 以内" }
    Data.makeFromBytes(bytes).use { data ->
        Codec.makeFromData(data).use { codec ->
            val info = codec.imageInfo
            require(info.width > 0 && info.height > 0 && info.width.toLong() * info.height <= 16_000_000) { "图片不能超过 1600 万像素" }
        }
    }
    return Image.makeFromEncoded(bytes)
}

internal fun normalizeContainerIcon(bytes: ByteArray): ByteArray = checkedContainerImage(bytes).use { image ->
    val ratio = minOf(1.0, 512.0 / max(image.width, image.height))
    var width = max(1, (image.width * ratio).roundToInt())
    var height = max(1, (image.height * ratio).roundToInt())
    while (true) {
        val png = Surface.makeRasterN32Premul(width, height).use { surface ->
            surface.canvas.clear(0)
            surface.canvas.drawImageRect(image, Rect.makeWH(width.toFloat(), height.toFloat()))
            surface.makeImageSnapshot().use { resized ->
                checkNotNull(resized.encodeToData(EncodedImageFormat.PNG)) { "无法编码 PNG 图片" }.use { it.bytes }
            }
        }
        if (png.size <= 512 * 1024) return@use png
        width = max(1, width * 3 / 4)
        height = max(1, height * 3 / 4)
    }
    @Suppress("UNREACHABLE_CODE")
    error("无法处理图片")
}

internal fun decodeContainerIcon(bytes: ByteArray): ImageBitmap = checkedContainerImage(bytes).use { image ->
    require(image.width <= 512 && image.height <= 512 && bytes.size <= 512 * 1024)
    Surface.makeRasterN32Premul(image.width, image.height).use { surface ->
        surface.canvas.clear(0)
        surface.canvas.drawImage(image, 0f, 0f)
        surface.makeImageSnapshot().toComposeImageBitmap()
    }
}
