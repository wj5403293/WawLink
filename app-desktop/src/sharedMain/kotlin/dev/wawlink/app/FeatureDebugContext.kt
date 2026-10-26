package dev.wawlink.app

import androidx.compose.runtime.staticCompositionLocalOf
import dev.wawlink.runtime.ContainerFeatureDebugger
import dev.wawlink.runtime.FeatureDebugTarget

/** Explicit native identity; never infer the target from a shared static URL or page title. */
internal data class FeatureDebugContext(val debugger: ContainerFeatureDebugger, val target: FeatureDebugTarget, val generation: Long = 0)
internal val LocalFeatureDebugContext = staticCompositionLocalOf<FeatureDebugContext?> { null }
