package dev.wawlink.app

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset

/** Native embedded views use normalized coordinates so host and Compose density stay independent. */
internal class FeatureCardNativeGestures(
    val selectionMode: Boolean,
    val onTap: () -> Unit,
    val onHold: (Offset) -> Unit,
    val onDrag: (Offset) -> Unit,
    val onEnd: () -> Unit,
)

internal val LocalFeatureCardNativeGestures = staticCompositionLocalOf<FeatureCardNativeGestures?> { null }
