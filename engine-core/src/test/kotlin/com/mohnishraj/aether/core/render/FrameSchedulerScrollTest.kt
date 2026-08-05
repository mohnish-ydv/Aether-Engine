package com.mohnishraj.aether.core.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameSchedulerScrollTest {
    @Test fun schedulerStartsIdle() {
        assertFalse(FrameScheduler().hasPendingFrame())
    }

    @Test fun schedulerAlignsToFrameBoundary() {
        val scheduler = FrameScheduler(60)
        scheduler.request(InvalidationCause.ANIMATION, 1L)
        assertTrue((scheduler.nextDueNanos() ?: 0L) > 1L)
    }

    @Test fun schedulerConsumesOnlyWhenDue() {
        val scheduler = FrameScheduler(60)
        scheduler.request(InvalidationCause.ANIMATION, 1L)
        assertNull(scheduler.consumeIfDue(2L))
        assertNotNull(scheduler.consumeIfDue(Long.MAX_VALUE))
    }

    @Test fun schedulerCoalescesCauses() {
        val scheduler = FrameScheduler(60)
        val a = scheduler.request(InvalidationCause.SCROLL, 1L)
        val b = scheduler.request(InvalidationCause.ANIMATION, 2L)
        val frame = scheduler.consumeIfDue(Long.MAX_VALUE) ?: error("missing")
        assertEquals(a, b)
        assertEquals(setOf(InvalidationCause.SCROLL, InvalidationCause.ANIMATION), frame.causes)
    }

    @Test fun schedulerTracksDroppedRequests() {
        val scheduler = FrameScheduler(60)
        scheduler.request(InvalidationCause.SCROLL, 1L)
        repeat(4) { scheduler.request(InvalidationCause.SCROLL, it + 2L) }
        assertEquals(4L, scheduler.droppedRequestCount())
    }

    @Test fun schedulerCancelClearsPendingFrame() {
        val scheduler = FrameScheduler(60)
        scheduler.request(InvalidationCause.SCROLL, 1L)
        scheduler.cancel()
        assertFalse(scheduler.hasPendingFrame())
    }

    @Test fun scrollClampsToExtents() {
        val scroll = ScrollController()
        scroll.setExtents(1000.0, 2000.0, 300.0, 500.0)
        scroll.scrollTo(9999.0, 9999.0, ScrollBehavior.INSTANT, 0L)
        assertEquals(ScrollOffset(700.0, 1500.0), scroll.current())
    }

    @Test fun scrollDoesNotMoveWhenContentFits() {
        val scroll = ScrollController()
        scroll.setExtents(100.0, 100.0, 300.0, 500.0)
        scroll.scrollTo(50.0, 50.0, ScrollBehavior.INSTANT, 0L)
        assertEquals(ScrollOffset(), scroll.current())
    }

    @Test fun instantScrollReportsChange() {
        val scroll = ScrollController()
        scroll.setExtents(1000.0, 1000.0, 100.0, 100.0)
        assertTrue(scroll.scrollTo(20.0, 30.0, ScrollBehavior.INSTANT, 0L).changed)
    }

    @Test fun smoothScrollAdvancesMonotonically() {
        val scroll = ScrollController(200L)
        scroll.setExtents(1000.0, 1000.0, 100.0, 100.0)
        scroll.scrollTo(0.0, 500.0, ScrollBehavior.SMOOTH, 1_000_000_000L)
        val first = scroll.advance(1_050_000_000L).position.y
        val second = scroll.advance(1_100_000_000L).position.y
        assertTrue(first > 0.0 && second > first)
    }

    @Test fun smoothScrollFinishesAtTarget() {
        val scroll = ScrollController(100L)
        scroll.setExtents(1000.0, 1000.0, 100.0, 100.0)
        scroll.scrollTo(0.0, 500.0, ScrollBehavior.SMOOTH, 1_000_000_000L)
        scroll.advance(2_000_000_000L)
        assertEquals(500.0, scroll.current().y)
        assertFalse(scroll.isAnimating())
    }

    @Test fun extentShrinkClampsExistingPosition() {
        val scroll = ScrollController()
        scroll.setExtents(1000.0, 2000.0, 300.0, 500.0)
        scroll.scrollTo(500.0, 1200.0, ScrollBehavior.INSTANT, 0L)
        val update = scroll.setExtents(400.0, 600.0, 300.0, 500.0)
        assertTrue(update.changed)
        assertEquals(ScrollOffset(100.0, 100.0), scroll.current())
    }
}
