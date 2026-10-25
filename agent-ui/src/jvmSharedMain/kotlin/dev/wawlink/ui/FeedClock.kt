package dev.wawlink.ui

import androidx.compose.ui.text.style.TextDirection

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
