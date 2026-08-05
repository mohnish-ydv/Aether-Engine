package com.mohnishraj.aether.core.render

import com.mohnishraj.aether.core.browser.mutation.MutationRecord
import com.mohnishraj.aether.core.browser.mutation.MutationType
import com.mohnishraj.aether.core.layout.LayoutRect
import java.util.LinkedHashSet

/** Coalesces DOM, style, viewport, animation and scrolling changes into the minimum safe stage set. */
class RenderInvalidationTracker(private val limits: RenderLimits = RenderLimits()) {
    private val lock = Any()
    private val pendingStages = LinkedHashSet<RenderStage>()
    private val pendingCauses = LinkedHashSet<InvalidationCause>()
    private val pendingNodes = LinkedHashSet<Long>()
    private var pendingRect: LayoutRect? = null
    private var full = false

    init { merge(RenderInvalidation.INITIAL) }

    fun invalidate(
        cause: InvalidationCause,
        nodeId: Long? = null,
        dirtyRect: LayoutRect? = null,
        forceFull: Boolean = false
    ) {
        val baseStages = when (cause) {
            InvalidationCause.INITIAL_LOAD,
            InvalidationCause.DOM_STRUCTURE,
            InvalidationCause.TEXT_CONTENT,
            InvalidationCause.ATTRIBUTE,
            InvalidationCause.STYLESHEET,
            InvalidationCause.VIEWPORT,
            InvalidationCause.EXPLICIT -> setOf(RenderStage.STYLE)
            InvalidationCause.ANIMATION -> setOf(RenderStage.PAINT)
            InvalidationCause.SCROLL -> setOf(RenderStage.COMPOSITE)
        }
        merge(RenderInvalidation(expand(baseStages), setOf(cause), nodeId?.let(::setOf).orEmpty(), dirtyRect, forceFull))
    }

    fun recordMutations(records: List<MutationRecord>) {
        records.forEach { record ->
            when (record.type) {
                MutationType.CHILD_LIST -> invalidate(InvalidationCause.DOM_STRUCTURE, record.target.nodeId)
                MutationType.CHARACTER_DATA -> invalidate(InvalidationCause.TEXT_CONTENT, record.target.nodeId)
                MutationType.ATTRIBUTES -> invalidate(InvalidationCause.ATTRIBUTE, record.target.nodeId)
            }
        }
    }

    fun merge(invalidation: RenderInvalidation) {
        synchronized(lock) {
            pendingStages += expand(invalidation.stages)
            pendingCauses += invalidation.causes
            invalidation.dirtyNodeIds.forEach { nodeId ->
                if (pendingNodes.size < limits.maxDirtyNodes) pendingNodes += nodeId else full = true
            }
            invalidation.dirtyRect?.let { rect -> pendingRect = pendingRect?.union(rect) ?: rect }
            full = full || invalidation.full
        }
    }

    fun peek(): RenderInvalidation = synchronized(lock) { snapshot() }

    fun consume(): RenderInvalidation = synchronized(lock) {
        val result = snapshot()
        pendingStages.clear()
        pendingCauses.clear()
        pendingNodes.clear()
        pendingRect = null
        full = false
        result
    }

    fun clear() {
        synchronized(lock) {
            pendingStages.clear()
            pendingCauses.clear()
            pendingNodes.clear()
            pendingRect = null
            full = false
        }
    }

    private fun snapshot() = RenderInvalidation(
        stages = pendingStages.toSet(),
        causes = pendingCauses.toSet(),
        dirtyNodeIds = pendingNodes.toSet(),
        dirtyRect = pendingRect,
        full = full
    )

    private fun expand(stages: Set<RenderStage>): Set<RenderStage> {
        if (stages.isEmpty()) return emptySet()
        val expanded = LinkedHashSet<RenderStage>()
        stages.forEach { stage ->
            when (stage) {
                RenderStage.STYLE -> expanded += RenderStage.entries
                RenderStage.LAYOUT -> expanded += listOf(RenderStage.LAYOUT, RenderStage.PAINT, RenderStage.COMPOSITE)
                RenderStage.PAINT -> expanded += listOf(RenderStage.PAINT, RenderStage.COMPOSITE)
                RenderStage.COMPOSITE -> expanded += RenderStage.COMPOSITE
            }
        }
        return expanded
    }
}
