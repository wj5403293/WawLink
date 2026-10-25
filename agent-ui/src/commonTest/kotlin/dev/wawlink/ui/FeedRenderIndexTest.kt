package dev.wawlink.ui

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Rect
import dev.wawlink.protocol.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedRenderIndexTest {
    private fun FeedRenderIndex.show(feed: List<FeedItem>, compacted: String? = null, showCompacted: Boolean = true, hideTools: Boolean = false) =
        update(feed, compacted, showCompacted, hideTools)

    private fun rect(top: Float, bottom: Float) = Rect(0f, top, 100f, bottom)

    @Test fun projectionKeepsLazyKeysOwnershipAndSourceSnapshots() {
        val nestedTool = ToolNodeItem("nested-tool", titleHtml = "读取")
        val thinking = ThinkingNodeItem("thinking", text = "旧正文")
        val group = StepGroupNodeItem("group", "步骤", children = listOf(nestedTool, thinking))
        val feed = listOf(UserMessageItem("user", "问题"), ToolNodeItem("top-tool", titleHtml = "执行"), group)
        val index = FeedRenderIndex()
        index.show(feed, compacted = "user", showCompacted = false, hideTools = true)
        assertEquals(listOf("group"), index.visibleFeed.map { it.id })
        assertEquals(1, index.itemIndex("group"))
        assertEquals(1, index.itemIndex("nested-tool"))
        assertEquals(1, index.itemIndex("thinking"))
        assertNull(index.itemIndex("top-tool"))
        assertNull(index["user"])
        assertEquals(nestedTool, index["nested-tool"])
        val oldView = index.visibleFeed
        val replacement = group.copy(children = listOf(nestedTool, thinking.copy(text = "新正文")))
        index.show(listOf(feed[0], feed[1], replacement), "user", false, true)
        assertEquals(group, oldView.single())
        assertEquals(replacement, index.visibleFeed.single())
        assertEquals("新正文", (index["thinking"] as ThinkingNodeItem).text)

        index.show(listOf(feed[0], feed[1], replacement), "user", true, false)
        assertEquals(listOf("user", "top-tool", "group"), index.visibleFeed.map { it.id })
        assertEquals(3, index.itemIndex("thinking"))
    }

    @Test fun topologyChangesRemoveOldIdsAndReassignMovedChildren() {
        val child = ThinkingNodeItem("child", text = "正文")
        val first = StepGroupNodeItem("first", "第一组", children = listOf(child))
        val second = StepGroupNodeItem("second", "第二组")
        val index = FeedRenderIndex()
        index.show(listOf(first, second))
        assertEquals(0, index.itemIndex("child"))
        index.show(listOf(second.copy(children = listOf(child)), first.copy(children = emptyList())))
        assertEquals(0, index.itemIndex("child"))
        assertEquals(1, index.itemIndex("first"))
        index.show(listOf(first.copy(children = emptyList())))
        assertNull(index["child"])
        assertNull(index.itemIndex("second"))
        // A same-ID type replacement must also invalidate root filtering and plan membership.
        index.show(listOf(ToolNodeItem("first", titleHtml = "工具")), hideTools = true)
        assertTrue(index.visibleFeed.isEmpty())
        index.show(listOf(PlanNodeItem("first", emptyList())), hideTools = true)
        assertEquals(listOf("first"), index.visibleFeed.map { it.id })
    }

    @Test fun clonedSnapshotsRefreshPayloadAndRunningClock() {
        val thinking = ThinkingNodeItem("thinking", isStreaming = true)
        val tool = ToolNodeItem("tool", titleHtml = "工具", state = NodeStatus.OK)
        val group = StepGroupNodeItem("group", "步骤", state = NodeStatus.OK, children = listOf(thinking, tool))
        val index = FeedRenderIndex()
        val original = listOf<FeedItem>(group)
        index.show(original)
        index.show(original)
        assertTrue(index.hasRunningNodes)
        val cloned = group.copy(children = listOf(thinking.copy(isStreaming = false, text = "完成"), tool.copy(titleHtml = "新标题")))
        index.show(listOf(cloned))
        assertFalse(index.hasRunningNodes)
        assertEquals("新标题", (index["tool"] as ToolNodeItem).titleHtml)
        index.show(listOf(cloned.copy(children = listOf(thinking.copy(isStreaming = false), tool.copy(state = NodeStatus.RUNNING)))), hideTools = true)
        // Nested hidden tools remain part of the original all-node clock semantics.
        assertTrue(index.hasRunningNodes)
        index.show(listOf(tool.copy(state = NodeStatus.RUNNING)), hideTools = true)
        assertFalse(index.hasRunningNodes)
    }

    @Test fun completionRemainsOneShotForHiddenNestedGroupsAcrossCoalescedEffects() {
        for (terminal in listOf(NodeStatus.OK, NodeStatus.ERROR, NodeStatus.STOPPED)) {
            val child = StepGroupNodeItem("child", "子步骤")
            val parent = StepGroupNodeItem("parent", "父步骤", children = listOf(child), isExpanded = false)
            val expanded = mutableMapOf("parent" to false, "child" to true)
            val running = mutableMapOf<String, Boolean>()
            val index = FeedRenderIndex()
            index.show(listOf(parent), "parent", false)
            index.updateGroupExpansion(expanded, running)
            val completed = parent.copy(children = listOf(child.copy(state = terminal)))
            index.show(listOf(completed), "parent", false)
            // An unrelated payload update before the effect must not lose the pending completion.
            index.show(listOf(completed.copy(title = "父步骤更新")), "parent", false)
            index.updateGroupExpansion(expanded, running)
            assertFalse(expanded.getValue("parent"))
            assertFalse(expanded.getValue("child"))
            expanded["child"] = true
            index.show(listOf(completed.copy(title = "再次更新")), "parent", false)
            index.updateGroupExpansion(expanded, running)
            assertTrue(expanded.getValue("child"))
        }
    }

    @Test fun measuredPinsMatchOriginalPreorderEvenWithOverlappingAnimationBounds() {
        val thinking = ThinkingNodeItem("thinking", isExpanded = true)
        val tool = ToolNodeItem("tool", titleHtml = "工具", isExpanded = true)
        val child = StepGroupNodeItem("child", "子步骤", children = listOf(thinking, tool))
        val sibling = StepGroupNodeItem("sibling", "第二子步骤")
        val parent = StepGroupNodeItem("parent", "父步骤", children = listOf(child, sibling))
        val feed = listOf<FeedItem>(parent)
        val expanded = mutableMapOf<String, Boolean>()
        val bounds = linkedMapOf("parent" to rect(0f, 900f), "child" to rect(0f, 600f),
            "thinking" to rect(90f, 500f), "tool" to rect(90f, 450f), "sibling" to rect(10f, 100f))
        val index = FeedRenderIndex()
        index.show(feed)
        fun checkPins(hideTools: Boolean = false) {
            val candidates = index.measuredPins(bounds.keys, expanded)
            val visible = visibleFeedNodes(index.visibleFeed, expanded, hideTools)
            for (line in listOf(0f, 8f, 18f, 98f, 100f, 200f, 394f, 444f, 544f, 844f)) {
                fun crossing(node: FeedItem) = node.isExpanded(expanded) && bounds[node.id]?.let {
                    it.top + 8f < line && it.bottom > line + 56f
                } == true
                assertEquals(visible.filterIsInstance<StepGroupNodeItem>().lastOrNull(::crossing)?.id,
                    candidates.parentPin(bounds, line, 56f, 8f)?.id, "group at $line")
                assertEquals(visible.lastOrNull { (it is ThinkingNodeItem || it is ToolNodeItem) && crossing(it) }?.id,
                    candidates.contentPin(bounds, line, 56f, 8f)?.id, "content at $line")
            }
        }
        checkPins()
        expanded["child"] = false
        checkPins()
        expanded["child"] = true
        index.show(feed, hideTools = true)
        checkPins(hideTools = true)
        index.show(feed, "parent", false)
        assertNull(index.measuredPins(bounds.keys, expanded).parentPin(bounds, 200f, 56f, 8f))
        assertNull(index.measuredPins(bounds.keys, expanded).contentPin(bounds, 200f, 56f, 8f))
    }

    @Test fun measuredPinsReactToLayoutExpansionAndUnmounting() {
        val thinking = ThinkingNodeItem("thinking", isExpanded = true)
        val group = StepGroupNodeItem("group", "步骤", children = listOf(thinking))
        val index = FeedRenderIndex()
        index.show(listOf(group))
        val bounds = FeedMeasuredBounds()
        bounds["group"] = rect(0f, 600f)
        bounds["thinking"] = rect(20f, 500f)
        val expanded = mutableStateMapOf<String, Boolean>()
        val candidates = derivedStateOf { index.measuredPins(bounds.measuredIds(), expanded) }
        assertEquals("thinking", candidates.value.contentPin(bounds, 100f, 56f, 8f)?.id)
        bounds["thinking"] = rect(200f, 500f)
        assertNull(candidates.value.contentPin(bounds, 100f, 56f, 8f))
        expanded["group"] = false
        assertNull(candidates.value.contentPin(bounds, 300f, 56f, 8f))
        expanded["group"] = true
        assertEquals("thinking", candidates.value.contentPin(bounds, 300f, 56f, 8f)?.id)
        bounds.keys.remove("thinking")
        assertNull(candidates.value.contentPin(bounds, 300f, 56f, 8f))
        assertEquals(setOf("group"), bounds.measuredIds())
        bounds.clear()
        assertTrue(bounds.measuredIds().isEmpty())
    }

    @Test fun globalPlansMatchOriginalAtThresholdCompactionAndAnimatedPlacements() {
        val feed = (0..8).map { position ->
            if (position % 2 == 0) PlanNodeItem("plan-$position", emptyList()) else UserMessageItem("user-$position", "问题")
        }
        val index = FeedRenderIndex()
        for (compacted in listOf<String?>(null, "plan-2")) for (show in listOf(false, true)) {
            index.show(feed, compacted, show)
            for (first in 0..10) for (offset in listOf(-20, -8, 0, 20)) {
                val visible = index.visibleFeed.mapIndexed { position, item ->
                    Placement(item.id, requireNotNull(index.itemIndex(item.id)), (position - first) * 80 + offset)
                }.filter { it.index in (first - 1)..(first + 3) }
                val expected = index.visibleFeed.filterIsInstance<PlanNodeItem>().lastOrNull { plan ->
                    val position = visible.firstOrNull { it.key == plan.id }
                    position?.let { it.offset + 8f < 0 } ?: (first > requireNotNull(index.itemIndex(plan.id)))
                }
                assertEquals(expected?.id, index.globalPin(first, visible, 8f)?.id,
                    "compacted=$compacted show=$show first=$first offset=$offset")
            }
        }
    }

    private data class Placement(override val key: Any, override val index: Int, override val offset: Int) : LazyListItemInfo {
        override val size: Int = 80
        override val contentType: Any? = null
    }
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
