package dev.wawlink.ui

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

/** Exact native lookup is performed by the session explorer, never by displayed message text. */
internal val LocalFeedHistoryRequest = staticCompositionLocalOf<((String, Boolean) -> Unit)?> { null }

@Composable
internal fun FeedHistoryActions(chatItemId: String, alignEnd: Boolean = false) {
    val request = LocalFeedHistoryRequest.current ?: return
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start) {
        IconButton({ request(chatItemId, true) }, Modifier.size(40.dp)) {
            Symbol("content_copy", size = 20.dp, tint = LocalAgentColors.current.onSurfaceVariant, contentDescription = "复制消息内容")
        }
        Box {
            IconButton({ menu = true }, Modifier.size(40.dp)) {
                Symbol("more_horiz", size = 20.dp, tint = LocalAgentColors.current.onSurfaceVariant, contentDescription = "消息操作")
            }
            ManagementMenu(menu, { menu = false }) {
                ManagementMenuItem(text = { Text("在历史树中定位") }, leadingIcon = { Symbol("account_tree") },
                    onClick = { menu = false; request(chatItemId, false) })
            }
        }
    }
}
