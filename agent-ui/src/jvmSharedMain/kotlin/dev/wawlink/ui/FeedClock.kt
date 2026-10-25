package dev.wawlink.ui

import androidx.compose.ui.text.style.TextDirection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

internal actual fun feedEpochMillis(): Long = System.currentTimeMillis()

internal actual fun firstStrongTextDirection(text: String): TextDirection? {
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        when (Character.getDirectionality(codePoint)) {
            Character.DIRECTIONALITY_LEFT_TO_RIGHT,
            Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING,
            Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE -> return TextDirection.Ltr
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE -> return TextDirection.Rtl
        }
        index += Character.charCount(codePoint)
    }
    return null
}

private val explorerTimeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
private val explorerDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

internal actual fun explorerTimestamp(value: String, full: Boolean): String {
    val instant = value.toLongOrNull()?.let(Instant::ofEpochMilli) ?: try {
        Instant.parse(value)
    } catch (_: DateTimeParseException) { return value }
    return (if (full) explorerDateFormat else explorerTimeFormat).format(instant.atZone(ZoneId.systemDefault()))
}
