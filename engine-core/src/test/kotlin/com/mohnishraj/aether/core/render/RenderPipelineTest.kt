package com.mohnishraj.aether.core.render

import com.mohnishraj.aether.core.render.inspect.RenderInspector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RenderPipelineTest {
    @Test fun openCreatesCurrentSession() {
        val fixture = renderFixture()
        assertSame(fixture.session, fixture.runtime.render.currentSession)
    }

    @Test fun firstFrameRunsEveryStage() {
        val frame = renderFixture().first
        assertEquals(RenderStage.entries.toSet(), frame.invalidation.stages)
        assertFalse(frame.reuse.styleTree)
    }

    @Test fun cleanRenderReturnsSameFrame() {
        val fixture = renderFixture()
        assertSame(fixture.first, fixture.session.renderNow(1_100_000_000L))
    }

    @Test fun stylesheetUpdateRecomputesAllStages() {
        val fixture = renderFixture()
        fixture.session.updateStyleSheet(RENDER_CSS + "#title{color:red}", nowNanos = 2_000_000_000L)
        val frame = fixture.session.renderNow(2_016_666_667L)
        assertTrue(InvalidationCause.STYLESHEET in frame.invalidation.causes)
        assertFalse(frame.reuse.styleTree)
        assertFalse(frame.reuse.layoutTree)
        assertFalse(frame.reuse.displayList)
    }

    @Test fun resizeRecomputesAllStages() {
        val fixture = renderFixture()
        fixture.session.resize(RenderViewport(500.0, 700.0), 2_000_000_000L)
        val frame = fixture.session.renderNow(2_016_666_667L)
        assertEquals(500.0, frame.viewport.widthPx)
        assertTrue(frame.invalidation.full)
    }

    @Test fun textMutationTriggersPipeline() {
        val fixture = renderFixture()
        val page = fixture.session.page
        page.document.setTextContent(page.document.getElementById("text") ?: error("missing"), "Changed")
        page.document.deliverMutations()
        val frame = fixture.session.renderNow(2_000_000_000L)
        assertTrue(InvalidationCause.TEXT_CONTENT in frame.invalidation.causes || InvalidationCause.DOM_STRUCTURE in frame.invalidation.causes)
        assertFalse(frame.reuse.layoutTree)
    }

    @Test fun attributeMutationTriggersPipeline() {
        val fixture = renderFixture()
        val page = fixture.session.page
        page.document.setAttribute(page.document.getElementById("title") ?: error("missing"), "class", "hot")
        page.document.deliverMutations()
        val frame = fixture.session.renderNow(2_000_000_000L)
        assertTrue(InvalidationCause.ATTRIBUTE in frame.invalidation.causes)
    }

    @Test fun childMutationTriggersPipeline() {
        val fixture = renderFixture()
        val page = fixture.session.page
        val main = page.document.querySelector("main") ?: error("missing")
        page.document.appendChild(main, page.document.createElement("section"))
        page.document.deliverMutations()
        val frame = fixture.session.renderNow(2_000_000_000L)
        assertTrue(InvalidationCause.DOM_STRUCTURE in frame.invalidation.causes)
    }

    @Test fun renderIfDueWaitsForDeadline() {
        val fixture = renderFixture()
        fixture.session.requestFrame(InvalidationCause.ANIMATION, 3_000_000_001L)
        assertEquals(null, fixture.session.renderIfDue(3_000_000_002L))
        assertNotNull(fixture.session.renderIfDue(Long.MAX_VALUE))
    }

    @Test fun requestFrameMarksPendingWork() {
        val fixture = renderFixture()
        fixture.session.requestFrame(InvalidationCause.ANIMATION, 3_000_000_000L)
        assertTrue(fixture.session.hasPendingFrame())
    }

    @Test fun closeRejectsNewWork() {
        val fixture = renderFixture()
        fixture.session.close()
        assertFailsWith<IllegalStateException> { fixture.session.requestFrame(InvalidationCause.EXPLICIT) }
    }

    @Test fun pipelineCloseClearsCurrentSession() {
        val fixture = renderFixture()
        fixture.runtime.render.closeCurrentSession()
        assertEquals(null, fixture.runtime.render.currentSession)
    }

    @Test fun openingSecondSessionClosesFirst() {
        val fixture = renderFixture()
        val page2 = fixture.runtime.browser.open("<p>second</p>", "https://second.test/")
        val second = fixture.runtime.render.open(page2, "body{margin:0}")
        assertSame(second, fixture.runtime.render.currentSession)
        assertFailsWith<IllegalStateException> { fixture.session.requestFrame(InvalidationCause.EXPLICIT) }
    }

    @Test fun statisticsCountFramesAndPasses() {
        val fixture = renderFixture()
        fixture.session.scrollTo(0.0, 20.0, ScrollBehavior.INSTANT, 2_000_000_000L)
        fixture.session.renderNow(2_016_666_667L)
        val stats = fixture.runtime.render.statistics()
        assertTrue(stats.framesProduced >= 2)
        assertEquals(stats.framesProduced, stats.compositePasses)
        assertTrue(stats.stylePasses >= 1)
    }

    @Test fun inspectorIncludesCoreFrameMetrics() {
        val summary = RenderInspector.summary(renderFixture().first)
        assertTrue("layers=" in summary)
        assertTrue("damage=" in summary)
        assertTrue("reuse style=" in summary)
    }
}
