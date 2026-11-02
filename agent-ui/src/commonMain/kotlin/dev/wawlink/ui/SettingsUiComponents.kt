package dev.wawlink.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal val BooleanStateMapSaver = mapSaver<SnapshotStateMap<String, Boolean>>(
    save = { it.toMap() },
    restore = { saved -> mutableStateMapOf<String, Boolean>().apply { for ((id, value) in saved) this[id] = value as Boolean } },
)

/** One ordered writer per screen: edits and navigation barriers cannot overtake persistence. */
internal class SettingsWriteQueue(scope: CoroutineScope, private val report: (String) -> Unit) {
    private val operations = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    var pending by mutableIntStateOf(0)
        private set
    val failures = mutableStateMapOf<String, String>()

    init {
        scope.launch {
            for (operation in operations) operation()
        }
    }

    fun submit(key: String, label: String, operation: suspend () -> Unit) {
        pending++
        operations.trySend {
            try {
                operation()
                failures.remove(key)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = "$label：${error.message ?: "未知错误"}"
                failures[key] = message
                report(message)
            } finally {
                pending--
            }
        }
    }

    fun afterSaved(action: () -> Unit) {
        operations.trySend {
            if (failures.isEmpty()) action() else report("保存失败，请重试后再返回")
        }
    }
}

internal fun segmentedSettingsShape(index: Int, count: Int): RoundedCornerShape = RoundedCornerShape(
    topStart = if (index == 0) 24.dp else 8.dp,
    topEnd = if (index == 0) 24.dp else 8.dp,
    bottomStart = if (index == count - 1) 24.dp else 8.dp,
    bottomEnd = if (index == count - 1) 24.dp else 8.dp,
)

@Composable
internal fun SettingsSearch(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    SettingsField(value, onValueChange, modifier.heightIn(min = 56.dp), placeholder = placeholder, round = 28,
        leading = { Symbol("search", tint = LocalAgentColors.current.onSurfaceVariant) },
        trailing = { if (value.isNotEmpty()) AgentIconButton("close", "清除搜索", { onValueChange("") }) })
}

@Composable
internal fun SettingsField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    mono: Boolean = false,
    secret: Boolean = false,
    readOnly: Boolean = false,
    round: Int = 16,
    keyboardType: KeyboardType = KeyboardType.Text,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val colors = LocalAgentColors.current
    val background by animateColorAsState(if (focused || readOnly) colors.surface else colors.surfaceContainer, tween(250, easing = EmphEasing))
    val shape = RoundedCornerShape(round.dp)
    BasicTextField(
        value, onValueChange, modifier.fillMaxWidth().heightIn(min = 56.dp)
            .onFocusChanged { focused = it.isFocused }
            .background(background, shape)
            .then(if (focused || readOnly) Modifier.border(if (focused) 2.dp else 1.dp, if (focused) colors.primary else colors.outlineVariant, shape) else Modifier),
        singleLine = true,
        readOnly = readOnly,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        cursorBrush = SolidColor(colors.primary),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = if (readOnly) colors.onSurfaceVariant else colors.onSurface, fontSize = if (mono) 16.sp else 18.sp, fontFamily = if (mono) LocalAgentMono.current else MaterialTheme.typography.bodyLarge.fontFamily),
        decorationBox = { inner ->
            Row(Modifier.padding(start = 18.dp, end = if (trailing != null) 6.dp else 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                leading?.invoke()
                Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                    if (value.isEmpty()) Text(placeholder, color = colors.onSurfaceVariant, fontSize = if (mono) 16.sp else 18.sp)
                    inner()
                }
                trailing?.invoke()
            }
        },
    )
}

@Composable
internal fun SettingsLabel(text: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 20.dp, bottom = 8.dp).heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), color = LocalAgentColors.current.onSurfaceVariant, fontSize = 16.sp)
        action?.invoke()
    }
}

@Composable
internal fun SettingsEmpty(text: String, icon: String = "inbox") {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Symbol(icon, size = 48.dp, tint = LocalAgentColors.current.outline)
        Text(text, color = LocalAgentColors.current.onSurfaceVariant, fontSize = 17.sp)
    }
}

@Composable
internal fun SettingsFailure(queue: SettingsWriteQueue, onRetry: () -> Unit) {
    if (queue.failures.isNotEmpty()) {
        Surface(color = LocalAgentColors.current.errorContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                ErrorMessage(queue.failures.values.first(), Modifier.weight(1f), color = LocalAgentColors.current.error, fontSize = 15.sp, details = queue.failures.values.first())
                TextButton(onRetry) { Text("重试") }
            }
        }
    }
}

@Composable
internal fun SettingsSyncError(message: String?) {
    if (message != null) {
        val colors = LocalAgentColors.current
        Surface(color = colors.errorContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Symbol("error", tint = colors.error)
                ErrorMessage(message, Modifier.weight(1f), color = colors.error, fontSize = 17.sp, details = message)
            }
        }
    }
}

@Composable
internal fun SettingsSaveButton(onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    Button(onClick, enabled = enabled, modifier = modifier.height(54.dp), shape = RoundedCornerShape(27.dp)) {
        Symbol("check", size = 22.dp)
        Spacer(Modifier.width(10.dp))
        Text("保存", fontSize = 18.sp)
    }
}
