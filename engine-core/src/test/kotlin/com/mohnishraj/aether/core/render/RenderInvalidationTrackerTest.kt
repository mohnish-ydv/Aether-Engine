package com.mohnishraj.aether.core.render

import com.mohnishraj.aether.core.browser.mutation.MutationRecord
import com.mohnishraj.aether.core.browser.mutation.MutationType
import com.mohnishraj.aether.core.html.dom.TextNode
import com.mohnishraj.aether.core.layout.LayoutRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RenderInvalidationTrackerTest {
    @Test fun startsWithInitialFullInvalidation() {
        val value = RenderInvalidationTracker().peek()
        assertTrue(value.full)
        assertEquals(RenderStage.entries.toSet(), value.stages)
    }

    @Test fun consumeClearsPendingState() {
        val tracker = RenderInvalidationTracker()
        tracker.consume()
        assertTrue(tracker.peek().isClean)
    }

    @Test fun styleInvalidationExpandsAllStages() {
        val tracker = RenderInvalidationTracker()
        tracker.clear()
        tracker.invalidate(InvalidationCause.STYLESHEET)
        assertEquals(RenderStage.entries.toSet(), tracker.peek().stages)
    }

    @Test fun layoutInvalidationExpandsDownstreamStages() {
        val tracker = RenderInvalidationTracker()
        tracker.clear()
        tracker.merge(RenderInvalidation(setOf(RenderStage.LAYOUT), setOf(InvalidationCause.EXPLICIT)))
        assertEquals(setOf(RenderStage.LAYOUT, RenderStage.PAINT, RenderStage.COMPOSITE), tracker.peek().stages)
    }

    @Test fun paintInvalidationExpandsComposite() {
        val tracker = RenderInvalidationTracker()
        tracker.clear()
        tracker.merge(RenderInvalidation(setOf(RenderStage.PAINT), setOf(InvalidationCause.ANIMATION)))
        assertEquals(setOf(RenderStage.PAINT, RenderStage.COMPOSITE), tracker.peek().stages)
    }

    @Test fun scrollInvalidationIsCompositeOnly() {
        val tracker = RenderInvalidationTracker()
        tracker.clear()
        tracker.invalidate(InvalidationCause.SCROLL)
        assertEquals(setOf(RenderStage.COMPOSITE), tracker.peek().stages)
    }

    @Test fun dirtyRectsAreUnioned() {
        val tracker = RenderInvalidationTracker()
        tracker.clear()
        tracker.invalidate(InvalidationCause.ANIMATION, dirtyRect = LayoutRect(0.0, 0.0, 10.0, 10.0))
        tracker.invalidate(InvalidationCause.ANIMATION, dirtyRect = LayoutRect(8.0, 8.0, 10.0, 10.0))
        assertEquals(LayoutRect(0.0, 0.0, 18.0, 18.0), tracker.peek().dirtyRect)
    }

    @Test fun dirtyNodesAreDeduplicated() {
        val tracker = RenderInvalidationTracker()
        tracker.clear()
        tracker.invalidate(InvalidationCause.ATTRIBUTE, 42L)
        tracker.invalidate(InvalidationCause.ATTRIBUTE, 42L)
        assertEquals(setOf(42L), tracker.peek().dirtyNodeIds)
    }

    @Test fun dirtyNodeLimitPromotesFullInvalidation() {
        val tracker = RenderInvalidationTracker(RenderLimits(maxDirtyNodes = 1))
        tracker.clear()
        tracker.invalidate(InvalidationCause.ATTRIBUTE, 1L)
        tracker.invalidate(InvalidationCause.ATTRIBUTE, 2L)
        assertTrue(tracker.peek().full)
    }

    @Test fun characterMutationRequestsStyleLayoutPaintComposite() {
        val tracker = RenderInvalidationTracker()
        tracker.clear()
        val node = TextNode("old")
        tracker.recordMutations(listOf(MutationRecord(MutationType.CHARACTER_DATA, node, oldValue = "old")))
        val value = tracker.peek()
        assertTrue(InvalidationCause.TEXT_CONTENT in value.causes)
        assertEquals(RenderStage.entries.toSet(), value.stages)
    }

    @Test fun childMutationIsStructural() {
        val tracker = RenderInvalidationTracker()
        tracker.clear()
        val node = TextNode("x")
        tracker.recordMutations(listOf(MutationRecord(MutationType.CHILD_LIST, node)))
        assertTrue(InvalidationCause.DOM_STRUCTURE in tracker.peek().causes)
    }

    @Test fun cleanConstantHasNoWork() {
        assertTrue(RenderInvalidation.CLEAN.isClean)
        assertFalse(RenderInvalidation.INITIAL.isClean)
    }
}
