package dev.wawlink.ui

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.mutableStateMapOf
import dev.wawlink.protocol.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeedExpansionStateTest {
    private val saverScope = object : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    private fun restore(values: Map<String, Boolean>): MutableMap<String, Boolean> {
        val state = mutableStateMapOf<String, Boolean>().apply { putAll(values) }
        val saved = with(BooleanStateMapSaver) { saverScope.save(state) }
        return requireNotNull(BooleanStateMapSaver.restore(requireNotNull(saved)))
    }

    private fun FeedRenderIndex.observe(feed: List<FeedItem>, expanded: MutableMap<String, Boolean>, running: MutableMap<String, Boolean>) {
        update(feed, null, showCompacted = true, hideTools = false)
        updateGroupExpansion(expanded, running)
    }

    @Test fun completionCollapsesOnceForEveryTerminalStatus() {
        for (terminal in listOf(NodeStatus.OK, NodeStatus.ERROR, NodeStatus.STOPPED)) {
            val expanded = mutableMapOf("group" to true)
            val running = mutableMapOf<String, Boolean>()
            val index = FeedRenderIndex()
            val group = StepGroupNodeItem("group", "执行步骤")
            index.observe(listOf(group), expanded, running)
            assertTrue(expanded.getValue(group.id))

            val completed = group.copy(state = terminal)
            index.observe(listOf(completed), expanded, running)
            assertFalse(expanded.getValue(group.id))

            // A user reopens the completed row, then scrolls it out and back in.
            expanded[group.id] = true
            repeat(3) { index.observe(listOf(completed), expanded, running) }
            assertTrue(expanded.getValue(group.id))
        }
    }

    @Test fun firstObservationOfHistoryDoesNotOverwriteUserExpansion() {
        val expanded = mutableMapOf("group" to true)
        val running = mutableMapOf<String, Boolean>()
        val index = FeedRenderIndex()
        index.observe(listOf(StepGroupNodeItem("group", "历史步骤", state = NodeStatus.OK)), expanded, running)
        assertTrue(expanded.getValue("group"))
        assertEquals(false, running["group"])
    }

    @Test fun nestedTransitionsAreObservedEvenWhenParentsAreCollapsed() {
        val child = StepGroupNodeItem("child", "子步骤")
        val parent = StepGroupNodeItem("parent", "父步骤", children = listOf(child), isExpanded = false)
        val expanded = mutableMapOf("parent" to false, "child" to true, "tool" to true)
        val running = mutableMapOf<String, Boolean>()
        val index = FeedRenderIndex()
        index.observe(listOf(parent), expanded, running)
        index.observe(listOf(parent.copy(children = listOf(child.copy(state = NodeStatus.OK)))), expanded, running)
        assertFalse(expanded.getValue("parent"))
        assertFalse(expanded.getValue("child"))
        assertTrue(expanded.getValue("tool"))

        expanded["child"] = true
        index.observe(listOf(parent.copy(children = listOf(child.copy(state = NodeStatus.ERROR)))), expanded, running)
        assertTrue(expanded.getValue("child"))
    }

    @Test fun restoredExpansionAndConsumedCompletionSurviveSettingsNavigation() {
        val expanded = mutableMapOf("group" to true, "thinking" to true, "plan" to false, "tool" to true)
        val running = mutableMapOf<String, Boolean>()
        val index = FeedRenderIndex()
        val group = StepGroupNodeItem("group", "执行步骤")
        index.observe(listOf(group), expanded, running)
        index.observe(listOf(group.copy(state = NodeStatus.OK)), expanded, running)
        expanded["group"] = true

        val restoredExpanded = restore(expanded)
        val restoredRunning = restore(running)
        FeedRenderIndex().observe(listOf(group.copy(state = NodeStatus.OK)), restoredExpanded, restoredRunning)
        assertEquals(expanded, restoredExpanded)
        assertTrue(restoredExpanded.getValue("group"))
    }

    @Test fun completionWhileAwayIsConsumedOnReturnOnlyOnce() {
        val group = StepGroupNodeItem("group", "执行步骤")
        val expanded = mutableMapOf("group" to true)
        val running = mutableMapOf<String, Boolean>()
        FeedRenderIndex().observe(listOf(group), expanded, running)
        val restoredExpanded = restore(expanded)
        val restoredRunning = restore(running)
        val index = FeedRenderIndex()
        val completed = listOf(group.copy(state = NodeStatus.STOPPED))
        index.observe(completed, restoredExpanded, restoredRunning)
        assertFalse(restoredExpanded.getValue("group"))
        restoredExpanded["group"] = true
        index.observe(completed, restoredExpanded, restoredRunning)
        assertTrue(restoredExpanded.getValue("group"))
    }
}
