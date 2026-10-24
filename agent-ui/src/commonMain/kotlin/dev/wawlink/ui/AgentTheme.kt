package dev.wawlink.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wawlink.protocol.AgentAppearance
import dev.wawlink.protocol.AgentColorSource
import dev.wawlink.ui.resources.*
import org.jetbrains.compose.resources.Font

val EmphEasing = CubicBezierEasing(.2f, 0f, 0f, 1f)
val EmphDecelEasing = CubicBezierEasing(.05f, .7f, .1f, 1f)
val EmphAccelEasing = CubicBezierEasing(.3f, 0f, .8f, .15f)
fun <T> agentSpring() = spring<T>(dampingRatio = .65f, stiffness = 380f)

@Immutable
data class AgentColorScheme(
    val material: ColorScheme = AgentBlueLightScheme,
    // These are semantic success / warning colors, deliberately independent of the accent seed.
    val tertiary: Color = Color(0xFF146C2E), val tertiaryContainer: Color = Color(0xFFC4EED0),
    val warn: Color = Color(0xFFE65100), val warnContainer: Color = Color(0xFFFFDCC1),
) {
    val primary: Color get() = material.primary
    val onPrimary: Color get() = material.onPrimary
    val primaryContainer: Color get() = material.primaryContainer
    val onPrimaryContainer: Color get() = material.onPrimaryContainer
    val secondaryContainer: Color get() = material.secondaryContainer
    val error: Color get() = material.error
    val errorContainer: Color get() = material.errorContainer
    val surface: Color get() = material.surface
    val onSurface: Color get() = material.onSurface
    val onSurfaceVariant: Color get() = material.onSurfaceVariant
    val outline: Color get() = material.outline
    val outlineVariant: Color get() = material.outlineVariant
    val surfaceContainerLow: Color get() = material.surfaceContainerLow
    val surfaceContainer: Color get() = material.surfaceContainer
    val surfaceContainerHigh: Color get() = material.surfaceContainerHigh
    val surfaceContainerHighest: Color get() = material.surfaceContainerHighest

    companion object {
        fun dark() = fromMaterial(AgentBlueDarkScheme, dark = true)

        internal fun fromMaterial(scheme: ColorScheme, dark: Boolean, assist: Boolean = false): AgentColorScheme {
            val accessible = if (assist) scheme.copy(
                error = if (dark) Color(0xFFFFC485) else Color(0xFF9C4400),
                onError = if (dark) Color(0xFF502400) else Color.White,
                errorContainer = if (dark) Color(0xFF703400) else Color(0xFFFFDCC1),
                onErrorContainer = if (dark) Color(0xFFFFDCC1) else Color(0xFF321200),
            ) else scheme
            return AgentColorScheme(
                material = accessible,
                tertiary = if (assist) { if (dark) Color(0xFF91D7FF) else Color(0xFF005A8D) }
                    else if (dark) Color(0xFF8DD8A3) else Color(0xFF146C2E),
                tertiaryContainer = if (assist) { if (dark) Color(0xFF004366) else Color(0xFFC8E6FF) }
                    else if (dark) Color(0xFF00531E) else Color(0xFFC4EED0),
                warn = if (assist) { if (dark) Color(0xFFFFDDA0) else Color(0xFF7D5700) }
                    else if (dark) Color(0xFFFFB77D) else Color(0xFFE65100),
                warnContainer = if (dark) Color(0xFF6D3200) else Color(0xFFFFDCC1),
            )
        }
    }
}

val LocalAgentColors = staticCompositionLocalOf { AgentColorScheme() }
val LocalAgentMono = staticCompositionLocalOf<FontFamily> { FontFamily.Monospace }
val LocalAgentAppearance = staticCompositionLocalOf { AgentAppearance() }
internal val LocalAgentThemeProvided = staticCompositionLocalOf { false }
private val LocalAgentBaseDensity = staticCompositionLocalOf<Density?> { null }

// Keep page width fixed while the user changes content zoom.
val AgentPageMaxWidth: Dp
    @Composable get() = 460.dp / LocalAgentAppearance.current.densityScale
private val LocalSymbols = staticCompositionLocalOf { emptyMap<String, String>() }
private val LocalSymbolFont = staticCompositionLocalOf<FontFamily> { FontFamily.Default }
private val LocalFilledSymbolFont = staticCompositionLocalOf<FontFamily> { FontFamily.Default }

@Composable
fun AgentTheme(appearance: AgentAppearance = LocalAgentAppearance.current, colors: AgentColorScheme? = null, content: @Composable () -> Unit) {
    val inheritedDensity = LocalAgentBaseDensity.current
    val deviceDensity = LocalDensity.current
    val windowWidth = if (inheritedDensity == null) LocalWindowInfo.current.containerSize.width else 0
    val baseDensity = remember(inheritedDensity, deviceDensity, windowWidth) {
        inheritedDensity ?: if (windowWidth > 0) {
            val referenceDensity = minOf(deviceDensity.density, windowWidth / 460f)
            if (referenceDensity == deviceDensity.density) deviceDensity else Density(referenceDensity, deviceDensity.fontScale)
        } else deviceDensity
    }
    val density = remember(baseDensity, appearance.densityScale) {
        if (appearance.densityScale == 1f) baseDensity else Density(baseDensity.density * appearance.densityScale, baseDensity.fontScale)
    }
    val inheritedSymbols = LocalSymbols.current
    var symbols by remember { mutableStateOf(inheritedSymbols) }
    LaunchedEffect(Unit) {
        if (symbols.isEmpty()) symbols = Res.readBytes("files/material_symbols_rounded.codepoints").decodeToString().lineSequence()
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size == 2) parts[1].toIntOrNull(16)?.let { point ->
                    val glyph = if (point <= 0xFFFF) point.toChar().toString() else {
                        val value = point - 0x10000
                        "${(0xD800 + (value shr 10)).toChar()}${(0xDC00 + (value and 0x3FF)).toChar()}"
                    }
                    parts[0] to glyph
                } else null
            }.toMap()
    }
    val font = FontFamily(Font(Res.font.noto_sans_sc))
    val mono = FontFamily(Font(Res.font.jetbrains_mono))
    val symbolFont = FontFamily(Font(Res.font.material_symbols_rounded))
    val filledFont = FontFamily(Font(Res.font.material_symbols_filled))
    val providedColors = colors ?: if (LocalAgentThemeProvided.current && LocalAgentAppearance.current == appearance) LocalAgentColors.current else null
    val system = if (providedColors == null && appearance.colorSource == AgentColorSource.SYSTEM) agentSystemColorScheme(appearance.darkTheme) else null
    val c = providedColors ?: remember(appearance.darkTheme, appearance.colorVisionAssist, appearance.colorSource, appearance.customColor, system) {
        AgentColorScheme.fromMaterial(agentPaletteScheme(appearance, system), appearance.darkTheme, appearance.colorVisionAssist)
    }
    val scheme = c.material
    val normal = TextStyle(fontFamily = font, fontSize = 17.sp, lineHeight = 26.35.sp)
    CompositionLocalProvider(LocalAgentColors provides c, LocalAgentAppearance provides appearance, LocalContentColor provides c.onSurface,
        LocalAgentMono provides mono, LocalSymbols provides symbols, LocalSymbolFont provides symbolFont,
        LocalFilledSymbolFont provides filledFont, LocalAgentThemeProvided provides true,
        LocalAgentBaseDensity provides baseDensity, LocalDensity provides density) {
        MaterialTheme(colorScheme = scheme, typography = Typography(
            bodyLarge = normal.copy(fontSize = 18.sp, lineHeight = 30.6.sp), bodyMedium = normal,
            bodySmall = normal.copy(fontSize = 15.sp, lineHeight = 22.sp),
            titleLarge = normal.copy(fontSize = 22.sp, fontWeight = FontWeight.Medium, lineHeight = 30.sp),
            titleMedium = normal.copy(fontSize = 19.sp, fontWeight = FontWeight.Bold),
            titleSmall = normal.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium),
            labelLarge = normal.copy(fontWeight = FontWeight.Medium), labelMedium = normal.copy(fontSize = 16.sp),
            labelSmall = normal.copy(fontSize = 13.sp, lineHeight = 18.sp),
            headlineSmall = normal.copy(fontSize = 24.sp, lineHeight = 32.sp),
        ), content = content)
    }
}

@Composable
fun Symbol(name: String, modifier: Modifier = Modifier, size: Dp = 24.dp, tint: Color = LocalContentColor.current, filled: Boolean = false, contentDescription: String? = null) {
    val fill by animateFloatAsState(if (filled) 1f else 0f, tween(350, easing = EmphEasing))
    val glyph = LocalSymbols.current[name] ?: "\uE887"
    val outlined = LocalSymbolFont.current
    val solid = LocalFilledSymbolFont.current
    Box(modifier.size(size).semantics { if (contentDescription != null) this.contentDescription = contentDescription }, contentAlignment = Alignment.Center) {
        Text(glyph, fontFamily = outlined, fontSize = size.value.sp, lineHeight = size.value.sp, color = tint,
            modifier = Modifier.graphicsLayer { alpha = 1f - fill })
        Text(glyph, fontFamily = solid, fontSize = size.value.sp, lineHeight = size.value.sp, color = tint,
            modifier = Modifier.graphicsLayer { alpha = fill })
    }
}

@Composable
fun AgentIconButton(icon: String, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = false, enabled: Boolean = true) {
    IconButton(onClick, modifier.size(48.dp), enabled = enabled) { Symbol(icon, tint = if (enabled) LocalAgentColors.current.onSurfaceVariant else LocalAgentColors.current.outline, filled = filled, contentDescription = description) }
}

@Composable
fun AgentTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) AgentIconButton("arrow_back", "返回", onBack)
        Text(title, Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
    }
}

@Composable
fun AgentSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AgentSheet(title, onDismiss, actions = {}, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSheet(title: String, onDismiss: () -> Unit, actions: @Composable RowScope.() -> Unit,
    contentPadding: PaddingValues = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp),
    content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = LocalAgentColors.current.surface, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = Modifier.widthIn(max = AgentPageMaxWidth), dragHandle = {
            Box(Modifier.padding(top = 16.dp, bottom = 8.dp).size(32.dp, 4.dp).background(LocalAgentColors.current.outline.copy(alpha = .5f), RoundedCornerShape(2.dp)))
        }) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            actions()
            AgentIconButton("close", "关闭", onDismiss)
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).padding(contentPadding), content = content)
    }
}
