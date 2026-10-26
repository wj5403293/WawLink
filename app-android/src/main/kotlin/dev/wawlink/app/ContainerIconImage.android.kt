package dev.wawlink.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

private fun checkedContainerBitmap(bytes: ByteArray): Bitmap {
    require(bytes.isNotEmpty() && bytes.size <= 10 * 1024 * 1024) { "图片文件必须在 10 MiB 以内" }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 16_000_000) { "图片格式无效，或超过 1600 万像素" }
    val options = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
        while (max(bounds.outWidth, bounds.outHeight) / (inSampleSize.coerceAtLeast(1) * 2) >= 512) {
            inSampleSize = inSampleSize.coerceAtLeast(1) * 2
        }
    }
    return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)) { "图片无法解码" }
}

internal fun normalizeContainerIcon(bytes: ByteArray): ByteArray {
    val original = checkedContainerBitmap(bytes)
    try {
        val ratio = minOf(1.0, 512.0 / max(original.width, original.height))
        var width = max(1, (original.width * ratio).roundToInt())
        var height = max(1, (original.height * ratio).roundToInt())
        while (true) {
            val resized = Bitmap.createScaledBitmap(original, width, height, true)
            val png = try {
                ByteArrayOutputStream().use { output ->
                    check(resized.compress(Bitmap.CompressFormat.PNG, 100, output)) { "无法编码 PNG 图片" }
                    output.toByteArray()
                }
            } finally { if (resized !== original) resized.recycle() }
            if (png.size <= 512 * 1024) return png
            width = max(1, width * 3 / 4)
            height = max(1, height * 3 / 4)
        }
    } finally { original.recycle() }
}

internal fun decodeContainerIcon(bytes: ByteArray): ImageBitmap {
    require(bytes.size <= 512 * 1024)
    val bitmap = checkedContainerBitmap(bytes)
    if (bitmap.width > 512 || bitmap.height > 512) { bitmap.recycle(); error("图标尺寸不能超过 512 × 512") }
    return bitmap.asImageBitmap()
}
