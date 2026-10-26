package dev.wawlink.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A separate full-window readonly viewer, never an AgentSheet or an editable text field. */
@Composable
fun CodeDetailScreen(title: String, text: String, language: String?, onBack: () -> Unit) {
    val colors = LocalAgentColors.current
    val clipboard = LocalClipboardManager.current
    val canonical = remember(language, title, text) { inferCodeLanguage(language, title, text) }
    var selectionRequest by remember { mutableStateOf(0) }
    var wordWrap by rememberSaveable { mutableStateOf(true) }
    var more by remember { mutableStateOf(false) }
    PlatformCodeDetail(onBack) {
        Surface(Modifier.fillMaxSize(), color = colors.surface) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AgentIconButton("arrow_back", "返回", onBack)
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 12.dp)) {
                        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(canonical ?: "纯文本", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    }
                    AgentIconButton("select_all", "全选代码", { selectionRequest++ })
                    AgentIconButton("content_copy", "复制全部代码", { clipboard.setText(AnnotatedString(text)) })
                    Box {
                        AgentIconButton("more_vert", "更多操作", { more = true })
                        ManagementMenu(more, { more = false }) {
                            ManagementMenuItem(
                                text = { Text("自动换行") },
                                onClick = { wordWrap = !wordWrap; more = false },
                                selected = wordWrap,
                                leadingIcon = { Symbol("wrap_text") },
                                trailingIcon = { if (wordWrap) Symbol("check", contentDescription = "已开启") },
                            )
                        }
                    }
                }
                HorizontalDivider(color = colors.outlineVariant)
                PlatformCodeViewer(text, canonical ?: "text", Modifier.fillMaxWidth().weight(1f), selectionRequest, wordWrap)
            }
        }
    }
}

@Composable
internal expect fun PlatformCodeDetail(onBack: () -> Unit, title: String = "代码详情", content: @Composable () -> Unit)

@Composable
internal expect fun PlatformCodeViewer(text: String, language: String?, modifier: Modifier, selectionRequest: Int, wordWrap: Boolean)
