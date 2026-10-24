package dev.wawlink.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.materialkolor.dynamiccolor.DynamicColor
import com.materialkolor.dynamiccolor.MaterialDynamicColors
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.SchemeTonalSpot
import dev.wawlink.protocol.AgentAppearance
import dev.wawlink.protocol.AgentColorSource

internal const val AgentBlueSeed = 0xFF0B57D0L

/** Material's HCT tonal palettes and contrast-aware role assignments, not RGB blends. */
internal fun agentSeedColorScheme(seed: Long, dark: Boolean): ColorScheme {
    val scheme = SchemeTonalSpot(Hct.fromInt(seed.toInt() or 0xFF000000.toInt()), dark, 0.0)
    val roles = MaterialDynamicColors()
    fun DynamicColor.color() = Color(getArgb(scheme))
    return ColorScheme(
        primary = roles.primary().color(), onPrimary = roles.onPrimary().color(),
        primaryContainer = roles.primaryContainer().color(), onPrimaryContainer = roles.onPrimaryContainer().color(),
        inversePrimary = roles.inversePrimary().color(),
        secondary = roles.secondary().color(), onSecondary = roles.onSecondary().color(),
        secondaryContainer = roles.secondaryContainer().color(), onSecondaryContainer = roles.onSecondaryContainer().color(),
        tertiary = roles.tertiary().color(), onTertiary = roles.onTertiary().color(),
        tertiaryContainer = roles.tertiaryContainer().color(), onTertiaryContainer = roles.onTertiaryContainer().color(),
        background = if (dark) roles.background().color() else Color.White,
        onBackground = roles.onBackground().color(),
        surface = if (dark) roles.surface().color() else Color.White,
        onSurface = roles.onSurface().color(), surfaceVariant = roles.surfaceVariant().color(),
        onSurfaceVariant = roles.onSurfaceVariant().color(), surfaceTint = roles.surfaceTint().color(),
        inverseSurface = roles.inverseSurface().color(), inverseOnSurface = roles.inverseOnSurface().color(),
        error = roles.error().color(), onError = roles.onError().color(),
        errorContainer = roles.errorContainer().color(), onErrorContainer = roles.onErrorContainer().color(),
        outline = roles.outline().color(), outlineVariant = roles.outlineVariant().color(), scrim = roles.scrim().color(),
        surfaceBright = roles.surfaceBright().color(), surfaceDim = roles.surfaceDim().color(),
        surfaceContainerLowest = roles.surfaceContainerLowest().color(), surfaceContainerLow = roles.surfaceContainerLow().color(),
        surfaceContainer = roles.surfaceContainer().color(), surfaceContainerHigh = roles.surfaceContainerHigh().color(),
        surfaceContainerHighest = roles.surfaceContainerHighest().color(),
    )
}

internal val AgentBlueLightScheme by lazy {
    agentSeedColorScheme(AgentBlueSeed, false).copy(
        primary = Color(AgentBlueSeed), onPrimary = Color.White, surfaceTint = Color(AgentBlueSeed),
        primaryContainer = Color(0xFFD3E3FD), onPrimaryContainer = Color(0xFF041E49),
        secondary = Color(0xFF465D7C), onSecondary = Color.White,
        secondaryContainer = Color(0xFFDCE6F3), onSecondaryContainer = Color(0xFF1B334F),
        tertiary = Color(AgentBlueSeed), onTertiary = Color.White,
        tertiaryContainer = Color(0xFFD3E3FD), onTertiaryContainer = Color(0xFF041E49),
        onSurface = Color(0xFF1F1F1F), onBackground = Color(0xFF1F1F1F), onSurfaceVariant = Color(0xFF444746),
        outline = Color(0xFF747775), outlineVariant = Color(0xFFC4C7C5),
        surfaceVariant = Color(0xFFF0F4F9), surfaceBright = Color.White, surfaceDim = Color(0xFFE1E5EA),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF8FAFD),
        surfaceContainer = Color(0xFFF0F4F9), surfaceContainerHigh = Color(0xFFE9EEF6), surfaceContainerHighest = Color(0xFFDDE3EA),
        inverseSurface = Color(0xFF303134), inverseOnSurface = Color(0xFFF1F3F4),
    )
}
internal val AgentBlueDarkScheme by lazy {
    agentSeedColorScheme(AgentBlueSeed, true).copy(
        primary = Color(0xFFA8C7FA), onPrimary = Color(0xFF062E6F), surfaceTint = Color(0xFFA8C7FA),
        primaryContainer = Color(0xFF0842A0), onPrimaryContainer = Color(0xFFD3E3FD),
        secondary = Color(0xFFB8C8E1), onSecondary = Color(0xFF20344F),
        secondaryContainer = Color(0xFF364B65), onSecondaryContainer = Color(0xFFDCE6F3),
        tertiary = Color(0xFFA8C7FA), onTertiary = Color(0xFF062E6F),
        tertiaryContainer = Color(0xFF0842A0), onTertiaryContainer = Color(0xFFD3E3FD),
        surface = Color(0xFF131314), background = Color(0xFF131314),
        onSurface = Color(0xFFE3E3E3), onBackground = Color(0xFFE3E3E3), onSurfaceVariant = Color(0xFFC4C7C5),
        outline = Color(0xFF8E918F), outlineVariant = Color(0xFF444746),
        surfaceVariant = Color(0xFF1E1F20), surfaceBright = Color(0xFF333537), surfaceDim = Color(0xFF131314),
        surfaceContainerLowest = Color(0xFF0E0E0F), surfaceContainerLow = Color(0xFF1B1B1F),
        surfaceContainer = Color(0xFF1E1F20), surfaceContainerHigh = Color(0xFF282A2C), surfaceContainerHighest = Color(0xFF333537),
        inverseSurface = Color(0xFFE3E3E3), inverseOnSurface = Color(0xFF303134),
    )
}

internal fun agentPaletteScheme(appearance: AgentAppearance, system: ColorScheme?): ColorScheme = when (appearance.colorSource) {
    AgentColorSource.BLUE -> if (appearance.darkTheme) AgentBlueDarkScheme else AgentBlueLightScheme
    AgentColorSource.SYSTEM -> system?.let {
        if (appearance.darkTheme) it else it.copy(surface = Color.White, background = Color.White)
    } ?: if (appearance.darkTheme) AgentBlueDarkScheme else AgentBlueLightScheme
    AgentColorSource.CUSTOM -> agentSeedColorScheme(appearance.customColor, appearance.darkTheme)
}

/** Accept only opaque RGB seeds; an incomplete/invalid editing draft has no persisted value. */
internal fun parseAgentSeedHex(draft: String): Long? {
    val hex = draft.trim().removePrefix("#")
    if (hex.length != 6 || hex.any { it.digitToIntOrNull(16) == null }) return null
    return hex.toLongOrNull(16)?.or(0xFF000000L)
}

internal fun agentSeedHex(seed: Long): String = "#" + (seed and 0xFFFFFFL).toString(16).uppercase().padStart(6, '0')

@Composable
internal expect fun agentSystemColorScheme(dark: Boolean): ColorScheme?
