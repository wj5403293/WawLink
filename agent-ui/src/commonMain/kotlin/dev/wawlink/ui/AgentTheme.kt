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
import dev.wawlink.ui.resources.*
import org.jetbrains.compose.resources.Font

val EmphEasing = CubicBezierEasing(.2f, 0f, 0f, 1f)
val EmphDecelEasing = CubicBezierEasing(.05f, .7f, .1f, 1f)
val EmphAccelEasing = CubicBezierEasing(.3f, 0f, .8f, .15f)
fun <T> agentSpring() = spring<T>(dampingRatio = .65f, stiffness = 380f)

@Immutable
data class AgentColorScheme(
    val primary: Color = Color(0xFF0B57D0), val onPrimary: Color = Color.White,
    val primaryContainer: Color = Color(0xFFD3E3FD), val onPrimaryContainer: Color = Color(0xFF041E49),
    val secondaryContainer: Color = Color(0xFFE8DEF8),
    val tertiary: Color = Color(0xFF146C2E), val tertiaryContainer: Color = Color(0xFFC4EED0),
    val warn: Color = Color(0xFFE65100), val warnContainer: Color = Color(0xFFFFDCC1),
    val error: Color = Color(0xFFB3261E), val errorContainer: Color = Color(0xFFF9DEDC),
    val surface: Color = Color.White, val onSurface: Color = Color(0xFF1F1F1F),
    val onSurfaceVariant: Color = Color(0xFF444746), val outline: Color = Color(0xFF747775),
    val outlineVariant: Color = Color(0xFFC4C7C5),
    val surfaceContainerLow: Color = Color(0xFFF8FAFD), val surfaceContainer: Color = Color(0xFFF0F4F9),
    val surfaceContainerHigh: Color = Color(0xFFE9EEF6), val surfaceContainerHighest: Color = Color(0xFFDDE3EA),
) {
    companion object {
        fun dark() = AgentColorScheme(
            primary = Color(0xFFA8C7FA), onPrimary = Color(0xFF062E6F),
            primaryContainer = Color(0xFF0842A0), onPrimaryContainer = Color(0xFFD3E3FD),
            secondaryContainer = Color(0xFF4A4458), tertiary = Color(0xFF8DD8A3), tertiaryContainer = Color(0xFF00531E),
            warn = Color(0xFFFFB77D), warnContainer = Color(0xFF6D3200),
            error = Color(0xFFF2B8B5), errorContainer = Color(0xFF8C1D18),
            surface = Color(0xFF131314), onSurface = Color(0xFFE3E3E3), onSurfaceVariant = Color(0xFFC4C7C5),
            outline = Color(0xFF8E918F), outlineVariant = Color(0xFF444746),
            surfaceContainerLow = Color(0xFF1B1B1F), surfaceContainer = Color(0xFF1E1F20),
            surfaceContainerHigh = Color(0xFF282A2C), surfaceContainerHighest = Color(0xFF333537),
        )
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
fun AgentTheme(appearance: AgentAppearance = AgentAppearance(), colors: AgentColorScheme? = null, content: @Composable () -> Unit) {
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
    val base = colors ?: if (appearance.darkTheme) AgentColorScheme.dark() else AgentColorScheme()
    val c = if (appearance.colorVisionAssist) base.copy(
        tertiary = if (appearance.darkTheme) Color(0xFF91D7FF) else Color(0xFF005A8D),
        error = if (appearance.darkTheme) Color(0xFFFFC485) else Color(0xFF9C4400),
        warn = if (appearance.darkTheme) Color(0xFFFFDDA0) else Color(0xFF7D5700),
    ) else base
    val scheme = lightColorScheme(
        primary = c.primary, onPrimary = c.onPrimary, primaryContainer = c.primaryContainer,
        onPrimaryContainer = c.onPrimaryContainer, secondaryContainer = c.secondaryContainer,
        tertiary = c.tertiary, tertiaryContainer = c.tertiaryContainer, error = c.error,
        errorContainer = c.errorContainer, surface = c.surface, background = c.surface,
        onSurface = c.onSurface, onBackground = c.onSurface, onSurfaceVariant = c.onSurfaceVariant,
        surfaceVariant = c.surfaceContainer, outline = c.outline, outlineVariant = c.outlineVariant,
        surfaceContainerLow = c.surfaceContainerLow, surfaceContainer = c.surfaceContainer,
        surfaceContainerHigh = c.surfaceContainerHigh, surfaceContainerHighest = c.surfaceContainerHighest,
    )
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
