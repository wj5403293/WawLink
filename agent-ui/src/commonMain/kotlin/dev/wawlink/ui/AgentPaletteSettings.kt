package dev.wawlink.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.wawlink.protocol.AgentAppearance
import dev.wawlink.protocol.AgentColorSource

private val AgentSeedSwatches = listOf(AgentBlueSeed, 0xFF146C2EL, 0xFFB85C00L, 0xFFBA1A1AL, 0xFF8B3D9CL, 0xFF795548L)

/** Palette-only preferences; the host owns persistence and the surrounding settings screen. */
@Composable
fun AgentPaletteSettings(appearance: AgentAppearance, onChange: (AgentAppearance) -> Unit) {
    val colors = LocalAgentColors.current
    val systemSupported = agentSystemColorScheme(appearance.darkTheme) != null
    var draft by rememberSaveable { mutableStateOf(agentSeedHex(appearance.customColor)) }
    LaunchedEffect(appearance.customColor) {
        if (parseAgentSeedHex(draft) != appearance.customColor) draft = agentSeedHex(appearance.customColor)
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsLabel("主题配色")
        ManagementSegments(
            options = listOf(AgentColorSource.BLUE.name to "默认蓝色", AgentColorSource.SYSTEM.name to "系统配色", AgentColorSource.CUSTOM.name to "自定义"),
            selected = appearance.colorSource.name,
            onSelect = { onChange(appearance.copy(colorSource = AgentColorSource.valueOf(it))) },
        )
        if (appearance.colorSource == AgentColorSource.SYSTEM) {
            Text(
                if (systemSupported) "跟随 Android 系统动态配色（Monet），随壁纸和系统配置更新。"
                else "当前平台不支持系统动态配色（需 Android 12 或更高版本），现使用默认蓝色。",
                color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
            )
        }
        if (appearance.colorSource == AgentColorSource.CUSTOM) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                AgentSeedSwatches.forEach { seed ->
                    val selected = appearance.customColor == seed
                    val swatch = Color(seed)
                    Surface(
                        onClick = {
                            draft = agentSeedHex(seed)
                            onChange(appearance.copy(customColor = seed))
                        },
                        modifier = Modifier.size(48.dp).semantics { contentDescription = "种子颜色 ${agentSeedHex(seed)}${if (selected) "，已选择" else ""}" },
                        shape = CircleShape, color = swatch,
                        border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) colors.onSurface else colors.outlineVariant),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (selected) Symbol("check", tint = if (swatch.luminance() > .5f) Color.Black else Color.White)
                        }
                    }
                }
            }
            val invalid = parseAgentSeedHex(draft) == null
            OutlinedTextField(
                value = draft,
                onValueChange = { value ->
                    draft = value
                    parseAgentSeedHex(value)?.let { seed ->
                        if (seed != appearance.customColor) onChange(appearance.copy(customColor = seed))
                    }
                },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("种子颜色 HEX") }, placeholder = { Text("#0B57D0") },
                isError = invalid,
                supportingText = if (invalid) ({ Text("请输入 6 位 HEX，如 #0B57D0") }) else null,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = LocalAgentMono.current),
            )
        }
    }
}
