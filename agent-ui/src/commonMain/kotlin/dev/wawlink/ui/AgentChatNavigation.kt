package dev.wawlink.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import dev.wawlink.protocol.FeedItem
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp

/** Host navigation requests are separate from a session's saved composer and reading state. */
class AgentChatNavigation {
    internal data class Location(val id: String, val sequence: Long)
    private var sequence = 0L
    internal var location by mutableStateOf<Location?>(null)
        private set
    internal var backHandler: (() -> Boolean)? = null

    fun locate(chatItemId: String) { location = Location(chatItemId, ++sequence) }
    fun back(): Boolean = backHandler?.invoke() ?: false
    internal fun located(request: Location) { if (location == request) location = null }
}

internal expect fun explorerTimestamp(value: String, full: Boolean = false): String

/** Detail actions resolve native identity; direct copying only uses the displayed message source. */
internal val LocalFeedHistoryRequest = staticCompositionLocalOf<((String, FeedHistoryAction) -> Unit)?> { null }
internal val LocalFeedHistoryRunning = staticCompositionLocalOf { false }

@Composable
internal fun FeedHistoryActions(item: FeedItem, alignEnd: Boolean = false) {
    val request = LocalFeedHistoryRequest.current ?: return
    val running = LocalFeedHistoryRunning.current
    val clipboard = LocalClipboardManager.current
    var menu by remember(item.id) { mutableStateOf(false) }
    var copied by remember(item.id) { mutableStateOf(0) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(copied) {
        if (copied > 0) { delay(1500); copied = 0 }
    }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start) {
            IconButton({
                try {
                    val text = messageCopyText(item)
                    check(text.isNotEmpty()) { "这条消息没有可复制的文本" }
                    clipboard.setText(AnnotatedString(text))
                    copied++
                    error = null
                } catch (failure: Exception) {
                    error = failure.message ?: "复制失败，剪贴板不可用"
                }
            }, Modifier.size(40.dp)) {
                AnimatedContent(copied > 0, transitionSpec = {
                    (fadeIn(tween(180)) + scaleIn(tween(240, easing = EmphDecelEasing), initialScale = .8f)) togetherWith fadeOut(tween(120))
                }) { success ->
                    Symbol(if (success) "check" else "content_copy", size = 20.dp,
                        tint = if (success) LocalAgentColors.current.primary else LocalAgentColors.current.onSurfaceVariant,
                        contentDescription = if (success) "已复制消息内容" else "复制消息内容")
                }
            }
            Box {
                IconButton({ menu = true }, Modifier.size(40.dp)) {
                    Symbol("more_horiz", size = 20.dp, tint = LocalAgentColors.current.onSurfaceVariant, contentDescription = "消息操作")
                }
                ManagementMenu(menu, { menu = false }) {
                    ManagementMenuItem(text = { Text("选择复制") }, leadingIcon = { Symbol("content_copy") },
                        onClick = { menu = false; request(item.id, FeedHistoryAction.COPY_BLOCKS) })
                    ManagementMenuItem(text = { Text("创建分支") }, leadingIcon = { Symbol("alt_route") }, enabled = !running,
                        onClick = { menu = false; request(item.id, FeedHistoryAction.SWITCH) })
                    ManagementMenuItem(text = { Text("在历史树中定位") }, leadingIcon = { Symbol("account_tree") },
                        onClick = { menu = false; request(item.id, FeedHistoryAction.LOCATE) })
                }
            }
        }
        error?.let { Text(it, color = LocalAgentColors.current.error) }
    }
}
