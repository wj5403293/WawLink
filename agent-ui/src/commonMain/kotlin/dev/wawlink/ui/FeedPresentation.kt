package dev.wawlink.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.wawlink.protocol.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

internal data class FeedDetail(
    val id: String,
    val title: String,
    val text: String,
    val language: String? = null,
    val markdown: Boolean = false,
)

internal val LocalFeedClock = staticCompositionLocalOf<State<Long>?> { null }
internal val LocalFeedBounds = staticCompositionLocalOf<MutableMap<String, Rect>?> { null }
internal val LocalFeedInteraction = staticCompositionLocalOf<() -> Unit> { {} }
internal expect fun feedEpochMillis(): Long

@Composable
internal fun rememberFeedClock(running: Boolean): State<Long> = produceState(feedEpochMillis(), running) {
    value = feedEpochMillis()
    while (running) {
        delay(250)
        value = feedEpochMillis()
    }
}

internal fun elapsed(seconds: Int): String = when {
    seconds < 1 -> ""
    seconds < 60 -> "${seconds}s"
    else -> "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

internal fun runningElapsed(seconds: Int, startedAtMillis: Long?, running: Boolean, now: Long): Int =
    if (!running || startedAtMillis == null) seconds.coerceAtLeast(0)
    else maxOf(seconds, ((now - startedAtMillis).coerceAtLeast(0) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())

/** Only the mutable tail is published at a fixed cadence; opening a row never replays its prefix. */
@Composable
internal fun stableThinkingText(id: String, text: String, streaming: Boolean): String {
    val latest by rememberUpdatedState(text)
    var published by remember(id) { mutableStateOf(text) }
    LaunchedEffect(id, streaming) {
        if (!streaming) published = latest
        else while (true) {
            delay(100)
            published = latest
        }
    }
    return if (streaming) published else text
}

@Composable
internal fun ThinkingMarkdown(id: String, text: String) {
    val buffer = remember(id) { StableMarkdownBuffer() }
    val chunks = remember(text) { buffer.update(text) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        chunks.forEachIndexed { index, chunk -> key(id, index) { Markdown(chunk) } }
    }
}

internal fun flattenedFeed(feed: List<FeedItem>): List<FeedItem> = buildList {
    fun visit(items: List<FeedItem>) {
        for (item in items) {
            add(item)
            if (item is StepGroupNodeItem) visit(item.children)
        }
    }
    visit(feed)
}

internal fun FeedItem.isExpanded(expanded: Map<String, Boolean>): Boolean = expanded[id] ?: when (this) {
    is ThinkingNodeItem -> isExpanded
    is ToolNodeItem -> isExpanded
    is StepGroupNodeItem -> isExpanded
    is PlanNodeItem -> isExpanded
    else -> false
}

internal fun visibleFeedNodes(feed: List<FeedItem>, expanded: Map<String, Boolean>, hideTools: Boolean): List<FeedItem> = buildList {
    fun visit(items: List<FeedItem>) {
        for (item in items) {
            if (hideTools && item is ToolNodeItem) continue
            add(item)
            if (item is StepGroupNodeItem && item.isExpanded(expanded)) visit(item.children)
        }
    }
    visit(feed)
}

@Composable
internal fun ThinkingDetailScreen(id: String, text: String, streaming: Boolean, onBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val visible = stableThinkingText(id, text, streaming)
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = LocalAgentColors.current.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                AgentTopBar("思考", onBack, actions = {
                    AgentIconButton("content_copy", "复制全部", { clipboard.setText(AnnotatedString(text)) })
                })
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
                    ThinkingMarkdown(id, visible)
                }
            }
        }
    }
}
