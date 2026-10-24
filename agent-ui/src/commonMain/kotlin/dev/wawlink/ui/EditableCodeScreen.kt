package dev.wawlink.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Full-window native editor. The caller receives a draft only after explicit Save. */
@Composable
fun EditableCodeScreen(title: String, text: String, language: String?, onDismiss: () -> Unit,
    registerDismiss: ((() -> Unit)?) -> Unit = {}, error: String? = null, saving: Boolean = false, onSave: (String) -> Unit) {
    ManagementEditableCodeScreen(title, text, language, onDismiss, registerDismiss, error, saving, onSave)
}

@Composable
internal fun ManagementEditableCodeScreen(title: String, text: String, language: String?, onDismiss: () -> Unit,
    registerDismiss: ((() -> Unit)?) -> Unit, error: String? = null, saving: Boolean = false, onSave: (String) -> Unit) {
    val colors = LocalAgentColors.current
    var draft by remember { mutableStateOf(text) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val dismiss = { if (!saving) { if (draft != text) confirmDiscard = true else onDismiss() } }
    val currentDismiss by rememberUpdatedState(dismiss)
    val currentRegistration by rememberUpdatedState(registerDismiss)
    DisposableEffect(Unit) {
        currentRegistration { currentDismiss() }
        onDispose { currentRegistration(null) }
    }
    PlatformCodeDetail(dismiss) {
        Surface(Modifier.fillMaxSize(), color = colors.surface) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    AgentIconButton("arrow_back", "返回", dismiss)
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { onSave(draft) }, enabled = !saving) { Text(if (saving) "保存中…" else "保存") }
                }
                HorizontalDivider(color = colors.outlineVariant)
                error?.let { Text(it, Modifier.fillMaxWidth().padding(12.dp), color = colors.error) }
                PlatformCodeEditor(draft, language, { draft = it }, Modifier.fillMaxWidth().weight(1f), editable = !saving)
            }
        }
        if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false },
            title = { Text("舍弃未保存的修改？") }, text = { Text("返回后将保留进入编辑器前的内容。") },
            confirmButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("舍弃") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } })
    }
}

@Composable
internal expect fun PlatformCodeEditor(text: String, language: String?, onChange: (String) -> Unit, modifier: Modifier, editable: Boolean)
