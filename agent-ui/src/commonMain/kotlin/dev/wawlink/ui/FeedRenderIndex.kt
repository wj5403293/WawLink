package dev.wawlink.ui

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import dev.wawlink.protocol.*

/** Session-local cache. Payload changes visit only replaced branches; topology changes rebuild ownership. */
internal class FeedRenderIndex {
    private var source: List<FeedItem>? = null
    private val nodes = mutableMapOf<String, IndexedFeedNode>()
    private var roots = emptyList<IndexedFeedNode>()
    private val rootPositions = mutableMapOf<String, Int>()
    private val pendingGroupStates = linkedMapOf<String, Boolean>()
    private var topologyChanged = false
    private var projectedTopology = -1
    private var projectedCompaction: String? = null
    private var projectedShowCompacted = true
    private var projectedHideTools = false
    private var visibleRootPositions = IntArray(0)
    private var rootItemIndices = IntArray(0)
    private var plans = emptyList<IndexedFeedNode>()

    var revision = 0
        private set
    var topologyRevision = 0
        private set
    var visibilityRevision = 0
        private set
    var projectionRevision = 0
        private set
    var compactIndex = -1
        private set
    var visibleFeed: List<FeedItem> = emptyList()
        private set
    var hasRunningNodes = false
        private set

    operator fun get(id: String): FeedItem? = nodes[id]?.takeIf { rootItemIndices[it.owner] >= 0 }?.item

    fun itemIndex(id: String): Int? {
        val node = nodes[id] ?: return null
        return rootItemIndices[node.owner].takeIf { it >= 0 }
    }

    fun update(feed: List<FeedItem>, compactedThroughId: String?, showCompacted: Boolean, hideTools: Boolean) {
        val feedChanged = source !== feed
        if (feedChanged) {
            topologyChanged = false
            val sameRoots = roots.size == feed.size && roots.indices.all { roots[it].item.id == feed[it].id }
            if (sameRoots) {
                for (item in feed) synchronize(item)
            } else {
                roots = feed.map { synchronize(it) }
                topologyChanged = true
            }
            if (topologyChanged) rebuildTopology()
            source = feed
            revision++
        }
        val projectionChanged = projectedTopology != topologyRevision ||
            projectedCompaction != compactedThroughId || projectedShowCompacted != showCompacted || projectedHideTools != hideTools
        if (projectionChanged) {
            compactIndex = rootPositions[compactedThroughId] ?: -1
            val start = if (compactIndex >= 0 && !showCompacted) compactIndex + 1 else 0
            val positions = ArrayList<Int>(roots.size - start)
            rootItemIndices = IntArray(roots.size) { -1 }
            for (position in start until roots.size) {
                if (hideTools && roots[position].item is ToolNodeItem) continue
                rootItemIndices[position] = positions.size + if (compactIndex >= 0) 1 else 0
                positions.add(position)
            }
            visibleRootPositions = positions.toIntArray()
            plans = positions.mapNotNull { roots[it].takeIf { node -> node.item is PlanNodeItem } }
            projectedTopology = topologyRevision
            projectedCompaction = compactedThroughId
            projectedShowCompacted = showCompacted
            projectedHideTools = hideTools
            projectionRevision++
            revision++
        }
        if (feedChanged || projectionChanged) {
            // A fresh O(1) view captures the immutable source, so LazyColumn sees payload updates too.
            val positions = visibleRootPositions
            visibleFeed = object : AbstractList<FeedItem>() {
                override val size: Int get() = positions.size
                override fun get(index: Int): FeedItem = feed[positions[index]]
            }
            hasRunningNodes = visibleRootPositions.any { roots[it].runningCount > 0 }
        }
    }

    /** Consume completion even for compacted, hidden, collapsed, or recycled groups. */
    fun updateGroupExpansion(expanded: MutableMap<String, Boolean>, previouslyRunning: MutableMap<String, Boolean>) {
        for ((id, running) in pendingGroupStates) {
            val previous = previouslyRunning[id]
            if (previous != running) {
                previouslyRunning[id] = running
                if (previous == true && !running) expanded[id] = false
            }
        }
        pendingGroupStates.clear()
    }

    private fun synchronize(item: FeedItem): IndexedFeedNode {
        val existing = nodes[item.id]
        if (existing?.item === item) return existing
        val node = existing ?: IndexedFeedNode(item).also { nodes[item.id] = it; topologyChanged = true }
        if (existing != null && node.item::class != item::class) topologyChanged = true
        if (existing == null || expansionDefault(node.item) != expansionDefault(item)) visibilityRevision++
        if (item is StepGroupNodeItem && (existing == null || (node.item as? StepGroupNodeItem)?.state != item.state)) {
            pendingGroupStates[item.id] = item.state == NodeStatus.RUNNING
        }
        if (item !is StepGroupNodeItem) pendingGroupStates.remove(item.id)
        val children = (item as? StepGroupNodeItem)?.children.orEmpty()
        val oldChildren = (node.item as? StepGroupNodeItem)?.children
        var childrenRunning = if (existing == null) 0 else node.runningCount - (if (node.item.hasRunningClock()) 1 else 0)
        if (existing == null || oldChildren !== children) {
            childrenRunning = 0
            val sameChildren = node.children.size == children.size && node.children.indices.all { node.children[it].item.id == children[it].id }
            if (sameChildren) {
                for (child in children) childrenRunning += synchronize(child).runningCount
            } else {
                node.children = children.map { synchronize(it).also { child -> childrenRunning += child.runningCount } }
                topologyChanged = true
            }
        }
        node.item = item
        node.runningCount = (if (item.hasRunningClock()) 1 else 0) + childrenRunning
        return node
    }

    private fun rebuildTopology() {
        var order = 0
        val retained = HashSet<String>(nodes.size)
        fun visit(node: IndexedFeedNode, parent: IndexedFeedNode?, owner: Int) {
            node.parent = parent
            node.owner = owner
            node.order = order++
            retained.add(node.item.id)
            for (child in node.children) visit(child, node, owner)
        }
        rootPositions.clear()
        roots.forEachIndexed { owner, node ->
            rootPositions[node.item.id] = owner
            visit(node, null, owner)
        }
        nodes.keys.retainAll(retained)
        pendingGroupStates.keys.retainAll(retained)
        topologyRevision++
    }

    /** Only mounted NodeFrames participate. No history-wide visibility walk on scroll or token deltas. */
    fun measuredPins(ids: Set<String>, expanded: Map<String, Boolean>): FeedPinCandidates {
        val groups = ArrayList<IndexedFeedNode>()
        val contents = ArrayList<IndexedFeedNode>()
        for (id in ids) {
            val node = nodes[id] ?: continue
            val item = node.item
            if (item !is StepGroupNodeItem && item !is ThinkingNodeItem && item !is ToolNodeItem) continue
            if (rootItemIndices[node.owner] < 0 || projectedHideTools && item is ToolNodeItem || !item.isExpanded(expanded)) continue
            var parent = node.parent
            var visible = true
            while (parent != null) {
                if (!parent.item.isExpanded(expanded)) { visible = false; break }
                parent = parent.parent
            }
            if (visible) {
                if (item is StepGroupNodeItem) groups.add(node) else contents.add(node)
            }
        }
        groups.sortBy { it.order }
        contents.sortBy { it.order }
        return FeedPinCandidates(groups, contents)
    }

    /** Binary-search historical plans, then inspect only the current lazy viewport's placements. */
    fun globalPin(firstVisibleIndex: Int, visible: List<LazyListItemInfo>, threshold: Float): PlanNodeItem? {
        var low = 0
        var high = plans.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (rootItemIndices[plans[middle].owner] < firstVisibleIndex) low = middle + 1 else high = middle
        }
        var candidate = low - 1
        while (candidate >= 0) {
            val position = visible.firstOrNull { it.key == plans[candidate].item.id }
            if (position == null || position.offset + threshold < 0) break
            candidate--
        }
        var result = plans.getOrNull(candidate)
        for (position in visible) {
            if (position.offset + threshold >= 0) continue
            val key = position.key as? String ?: continue
            val node = nodes[key] ?: continue
            if (node.parent == null && node.item is PlanNodeItem && rootItemIndices[node.owner] >= 0 &&
                (result == null || node.order > result.order)) result = node
        }
        return result?.item as? PlanNodeItem
    }
}

private fun expansionDefault(item: FeedItem): Boolean = when (item) {
    is StepGroupNodeItem -> item.isExpanded
    is ThinkingNodeItem -> item.isExpanded
    is ToolNodeItem -> item.isExpanded
    else -> false
}

private fun FeedItem.hasRunningClock(): Boolean = when (this) {
    is ThinkingNodeItem -> isStreaming
    is ToolNodeItem -> state == NodeStatus.RUNNING
    is StepGroupNodeItem -> state == NodeStatus.RUNNING
    else -> false
}

internal class IndexedFeedNode(var item: FeedItem) {
    var children: List<IndexedFeedNode> = emptyList()
    var parent: IndexedFeedNode? = null
    var owner: Int = 0
    var order: Int = 0
    var runningCount: Int = 0
}

internal class FeedPinCandidates(
    private val groups: List<IndexedFeedNode>,
    private val contents: List<IndexedFeedNode>,
) {
    fun parentPin(bounds: Map<String, Rect>, line: Float, height: Float, threshold: Float): StepGroupNodeItem? =
        crossing(groups, bounds, line, height, threshold)?.item as? StepGroupNodeItem

    fun contentPin(bounds: Map<String, Rect>, line: Float, height: Float, threshold: Float): FeedItem? =
        crossing(contents, bounds, line, height, threshold)?.item

    private fun crossing(candidates: List<IndexedFeedNode>, bounds: Map<String, Rect>, line: Float, height: Float,
        threshold: Float): IndexedFeedNode? = candidates.lastOrNull { node ->
        bounds[node.item.id]?.let { it.top + threshold < line && it.bottom > line + height } == true
    }
}

/** Bounds change every layout; candidate membership changes only when a NodeFrame mounts or disposes. */
internal class FeedMeasuredBounds : AbstractMutableMap<String, Rect>() {
    private val bounds = mutableStateMapOf<String, Rect>()
    private val ids = mutableSetOf<String>()
    private var membershipRevision by mutableIntStateOf(0)

    fun measuredIds(): Set<String> {
        membershipRevision
        return ids
    }

    override fun get(key: String): Rect? = bounds[key]
    override fun put(key: String, value: Rect): Rect? {
        if (ids.add(key)) membershipRevision++
        return bounds.put(key, value)
    }
    override fun remove(key: String): Rect? {
        if (ids.remove(key)) membershipRevision++
        return bounds.remove(key)
    }
    override fun clear() {
        if (ids.isNotEmpty()) {
            ids.clear()
            membershipRevision++
        }
        bounds.clear()
    }
    override val entries: MutableSet<MutableMap.MutableEntry<String, Rect>> = object : AbstractMutableSet<MutableMap.MutableEntry<String, Rect>>() {
        override val size: Int get() = bounds.size
        override fun add(element: MutableMap.MutableEntry<String, Rect>): Boolean = throw UnsupportedOperationException()
        override fun iterator(): MutableIterator<MutableMap.MutableEntry<String, Rect>> {
            val iterator = bounds.entries.iterator()
            var currentKey: String? = null
            return object : MutableIterator<MutableMap.MutableEntry<String, Rect>> {
                override fun hasNext(): Boolean = iterator.hasNext()
                override fun next(): MutableMap.MutableEntry<String, Rect> = iterator.next().also { currentKey = it.key }
                override fun remove() {
                    iterator.remove()
                    if (ids.remove(checkNotNull(currentKey))) membershipRevision++
                }
            }
        }
    }
}
