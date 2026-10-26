package dev.wawlink.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.graphicsLayer
import dev.wawlink.ui.EmphEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wawlink.runtime.ContainerInfo
import dev.wawlink.ui.AgentPageMaxWidth
import dev.wawlink.ui.LocalAgentColors
import dev.wawlink.ui.Symbol
import dev.wawlink.ui.agentContainerContentColor

private val featureSheetPalette = listOf(
    "blue" to "#d3e3fd", "green" to "#c4eed0", "yellow" to "#ffefc2",
    "red" to "#f9dedc", "purple" to "#e8def8", "teal" to "#c2ecef", "orange" to "#ffdcc1",
)
private val featureSheetPaletteLabels = listOf("蓝色", "绿色", "黄色", "红色", "紫色", "青色", "橙色")

private fun featureSheetHex(value: String?): String? {
    val raw = value?.trim()?.removePrefix("#") ?: return null
    if (raw.length != 6 || raw.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
    return "#${raw.lowercase()}"
}

private fun featureSheetColor(value: String?): Color? {
    val hex = featureSheetHex(value) ?: return null
    return Color(0xFF000000L or hex.drop(1).toLong(16))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeatureSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalAgentColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.widthIn(max = AgentPageMaxWidth),
        sheetMaxWidth = AgentPageMaxWidth,
        containerColor = colors.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(Modifier.padding(top = 14.dp, bottom = 6.dp).size(32.dp, 4.dp)
                .background(colors.outline.copy(alpha = .5f), RoundedCornerShape(2.dp)))
        },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 16.dp)) {
            Text(title, Modifier.padding(top = 6.dp, bottom = 14.dp), fontSize = 22.sp,
                fontWeight = FontWeight.Medium, color = colors.onSurface)
            content()
        }
    }
}

@Composable
internal fun FeatureCardColorSheet(
    title: String,
    icon: String,
    count: Int,
    initialColor: String?,
    mixed: Boolean,
    defaultColor: String?,
    onDismiss: () -> Unit,
    onColor: (String?) -> Unit,
) {
    val colors = LocalAgentColors.current
    val initial = featureSheetHex(initialColor)
    var picked by remember(initialColor, mixed) { mutableStateOf(initial) }
    var hasPicked by remember(initialColor, mixed) { mutableStateOf(!mixed) }
    var hex by remember(initialColor, mixed) {
        mutableStateOf(if (!mixed && initial != null && featureSheetPalette.none { it.second == initial }) initial else "")
    }
    val validHex = featureSheetHex(hex)
    val invalid = hex.isNotEmpty() && validHex == null
    val previewColor = featureSheetColor(if (hasPicked) picked ?: defaultColor else defaultColor)
    val preview by animateColorAsState(previewColor ?: colors.surfaceContainer, tween(350, easing = EmphEasing))
    val foreground = if (previewColor == null) colors.onSurface else agentContainerContentColor(preview)
    FeatureSheet("卡片颜色", onDismiss) {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(2) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    repeat(4) { column ->
                        val index = row * 4 + column
                        val value = if (index == 0) null else featureSheetPalette[index - 1].second
                        val selected = hasPicked && !invalid && picked == value
                        val radius by animateDpAsState(if (selected) 32.dp else 22.dp, tween(300, easing = EmphEasing))
                        val selectionProgress by animateFloatAsState(if (selected) 1f else 0f, tween(300, easing = EmphEasing))
                        val shape = RoundedCornerShape(radius)
                        val swatch = featureSheetColor(value)
                        val background = if (swatch == null) Modifier.background(
                            Brush.sweepGradient(featureSheetPalette.map { featureSheetColor(it.second)!! } + featureSheetColor(featureSheetPalette.first().second)!!), shape,
                        ) else Modifier.background(swatch, shape)
                        Box(Modifier.size(64.dp).clip(shape).then(background)
                            .selectable(selected = selected, role = Role.RadioButton, onClick = {
                                picked = value
                                hasPicked = true
                                hex = ""
                            }).semantics { contentDescription = if (index == 0) "默认" else featureSheetPaletteLabels[index - 1] },
                            contentAlignment = Alignment.Center) {
                            Symbol(if (index == 0) "auto_awesome" else "check",
                                modifier = Modifier.graphicsLayer {
                                    alpha = selectionProgress
                                    scaleX = .4f + .6f * selectionProgress
                                    scaleY = .4f + .6f * selectionProgress
                                }, tint = agentContainerContentColor(swatch ?: featureSheetColor("#d3e3fd")!!))
                        }
                    }
                }
            }
        }
        Row(Modifier.padding(top = 14.dp).fillMaxWidth().heightIn(min = 60.dp)
            .background(colors.surfaceContainer, RoundedCornerShape(16.dp))
            .then(if (invalid) Modifier.border(2.dp, colors.error, RoundedCornerShape(16.dp)) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(28.dp).background(featureSheetColor(validHex) ?: colors.surfaceContainerHighest, RoundedCornerShape(9.dp)))
            BasicTextField(value = hex, onValueChange = {
                hex = it
                hasPicked = featureSheetHex(it) != null
                featureSheetHex(it)?.let { valid -> picked = valid }
            }, modifier = Modifier.weight(1f).semantics { contentDescription = "自定义 HEX 颜色" },
                singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = if (invalid) colors.error else colors.onSurface, fontSize = 18.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.primary),
                decorationBox = { field ->
                    Box {
                        if (hex.isEmpty()) Text("自定义 #RRGGBB", color = colors.onSurfaceVariant, fontSize = 18.sp)
                        field()
                    }
                })
        }
        Row(Modifier.padding(top = 16.dp).fillMaxWidth().background(preview, RoundedCornerShape(24.dp)).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(44.dp).background(foreground.copy(alpha = .08f), RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                Symbol(icon, size = 26.dp, tint = foreground)
            }
            Text(if (count > 1) "$title 等 $count 项" else title, Modifier.weight(1f),
                fontSize = 18.sp, fontWeight = FontWeight.Bold, color = foreground)
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDismiss) { Text("取消") }
            Button(onClick = { onColor(picked) }, enabled = hasPicked && !invalid) { Text("应用") }
        }
    }
}

@Composable
internal fun FeatureContainerSheet(
    containers: List<ContainerInfo>,
    selectedId: String?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val colors = LocalAgentColors.current
    FeatureSheet("切换容器", onDismiss) {
        if (containers.isEmpty()) {
            Text("暂无容器", Modifier.fillMaxWidth().padding(vertical = 24.dp), color = colors.onSurfaceVariant)
        }
        containers.forEach { container ->
            val selected = container.config.id == selectedId
            val foreground = if (selected) colors.onPrimaryContainer else colors.onSurface
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(if (selected) colors.primaryContainer else Color.Transparent)
                .selectable(selected, role = Role.RadioButton, onClick = { onSelect(container.config.id) })
                .heightIn(min = 72.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ContainerAvatar(container.config, round = container.running, size = 32.dp)
                Column(Modifier.weight(1f)) {
                    Text(container.config.displayName, fontSize = 18.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, color = foreground)
                    if (container.config.metadata.description.isNotBlank()) Text(container.config.metadata.description,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, color = foreground.copy(alpha = .8f))
                    if (container.config.metadata.version.isNotBlank()) Text("版本 ${container.config.metadata.version}",
                        maxLines = 1, overflow = TextOverflow.Ellipsis, color = foreground.copy(alpha = .8f))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(10.dp).background(if (container.running) colors.tertiary else colors.outline, CircleShape))
                        Text(if (container.running) "运行中" else "已停止", fontSize = 15.sp,
                            color = if (selected) foreground.copy(alpha = .8f) else colors.onSurfaceVariant)
                    }
                }
                if (selected) Symbol("check", tint = foreground)
            }
        }
    }
}
