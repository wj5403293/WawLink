@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.wawlink.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.border
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wawlink.ui.Symbol

internal typealias SkillObject = Map<String, Any?>
internal fun SkillObject.skillText(key: String, fallback: String = "") = this[key]?.toString() ?: fallback
internal fun SkillObject.skillBool(key: String) = this[key] == true
internal fun SkillObject.skillObject(key: String): SkillObject = (this[key] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap()
internal fun SkillObject.skillObjects(key: String): List<SkillObject> = (this[key] as? List<*>)?.mapNotNull { value -> (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } } ?: emptyList()
internal fun SkillObject.skillStrings(key: String) = (this[key] as? List<*>)?.map { it.toString() } ?: emptyList()
internal fun skillSource(source: String) = when (source) { "native" -> "OMP 独立技能"; "custom" -> "自定义目录"; "skillshare" -> "Skillshare 技能包"; "omp-plugins" -> "扩展包"; "claude-plugins" -> "兼容插件"; "agent-plugins" -> "标准插件"; "managed", "managed-skills" -> "自动学习"; "agents" -> ".agents 目录"; "claude" -> "Claude 目录"; "codex" -> "Codex 目录"; else -> source }
internal fun skillFlag(flag: String) = when (flag) { "hidden" -> "不在模型目录中"; "ignored" -> "已被忽略规则过滤"; "excluded" -> "不在 include 规则内"; "disabled" -> "已禁用"; "conflict" -> "同名冲突 · 使用别名"; "inactive" -> "当前加载设置未选用"; else -> flag }
@Composable internal fun SkillIconButton(symbol: String, label: String, enabled: Boolean = true, onClick: () -> Unit) { IconButton(onClick, enabled = enabled) { Symbol(symbol, contentDescription = label) } }
@Composable internal fun SkillHeading(text: String, symbol: String = "", action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 24.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (symbol.isNotEmpty()) Symbol(symbol, size = 20.dp, tint = MaterialTheme.colorScheme.primary)
        Text(text, Modifier.weight(1f), color = MaterialTheme.colorScheme.primary, fontSize = 16.sp, fontWeight = FontWeight.Medium); action?.invoke()
    }
}
@Composable internal fun SkillBanner(text: String, symbol: String = "info", error: Boolean = false, warning: Boolean = false, content: (@Composable ColumnScope.() -> Unit)? = null) {
    PluginBanner(text, error = error, warning = warning, icon = symbol, content = content)
}
@Composable internal fun SkillRow(title: String, subtitle: String = "", icon: String = "school", first: Boolean = false, last: Boolean = false, muted: Boolean = false, onClick: (() -> Unit)? = null, metadata: (@Composable ColumnScope.() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(topStart = if (first) 24.dp else 6.dp, topEnd = if (first) 24.dp else 6.dp, bottomStart = if (last) 24.dp else 6.dp, bottomEnd = if (last) 24.dp else 6.dp)
    Row(Modifier.fillMaxWidth().padding(bottom = 3.dp).clip(shape).background(Color(0xFFF8FAFD)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).heightIn(min = 72.dp).padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(44.dp).background(if (muted) Color(0xFFE9EEF6) else c.primaryContainer, RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) { Symbol(icon, tint = if (muted) c.onSurfaceVariant else c.onPrimaryContainer) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 18.sp, color = if (muted) c.onSurfaceVariant else c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Text(subtitle, fontSize = 16.sp, color = c.onSurfaceVariant, maxLines = if (metadata == null) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
            metadata?.invoke(this)
        }
        if (trailing != null) trailing() else if (onClick != null) Symbol("chevron_right", tint = c.onSurfaceVariant)
    }
}
@Composable internal fun SkillValues(values: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) { values.forEachIndexed { i, (key, value) ->
        Row(Modifier.fillMaxWidth().background(Color(0xFFF8FAFD), RoundedCornerShape(topStart = if (i == 0) 20.dp else 6.dp, topEnd = if (i == 0) 20.dp else 6.dp, bottomStart = if (i == values.lastIndex) 20.dp else 6.dp, bottomEnd = if (i == values.lastIndex) 20.dp else 6.dp)).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) { Text(key, Modifier.widthIn(min = 84.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp); SelectionContainer { Text(value, fontSize = 16.sp) } }
    } }
}
@Composable internal fun SkillEmpty(text: String, icon: String = "school") { Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) { Symbol(icon, size = 48.dp, tint = MaterialTheme.colorScheme.outline); Text(text, fontSize = 17.sp) } }
@Composable internal fun SkillField(label: String, value: String, onChange: (String) -> Unit, singleLine: Boolean = true, mono: Boolean = false) {
    val c = MaterialTheme.colorScheme
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val shape = RoundedCornerShape(16.dp)
    Text(label, Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp), color = c.onSurfaceVariant, fontSize = 16.sp)
    BasicTextField(value, onChange, Modifier.fillMaxWidth().heightIn(min = if (singleLine) 56.dp else 140.dp).clip(shape).background(if (focused) Color.White else Color(0xFFF0F4F9)).border(if (focused) 2.dp else 0.dp, if (focused) c.primary else Color.Transparent, shape).padding(horizontal = 18.dp, vertical = 14.dp), singleLine = singleLine, minLines = if (singleLine) 1 else 4, interactionSource = interactions, cursorBrush = SolidColor(c.primary), textStyle = LocalTextStyle.current.copy(fontSize = if (mono) 16.sp else 18.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default, color = c.onSurface, lineHeight = 26.sp))
}
@Composable internal fun SkillSegments(options: List<Pair<String, String>>, value: String, onChange: (String) -> Unit) {
    Column(Modifier.padding(vertical = 8.dp)) { PluginSegments(options, value, onChange) }
}
