package com.mohnishraj.aether.core.shell

import com.mohnishraj.aether.core.paint.PaintCommand
import com.mohnishraj.aether.core.render.RenderViewport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrowserShellVisualRegressionTest {
    private fun homeFrame(viewport: RenderViewport = RenderViewport(360.0, 640.0)) = shellFixture().runtime.shell.run {
        openTab("about:blank", viewport = viewport)
        activeRenderSession()?.renderNow(1_000_000_000L) ?: error("missing frame")
    }

    @Test fun homeDocumentUsesTheMeasuredViewport() {
        val frame = homeFrame(RenderViewport(412.0, 733.0))
        assertEquals(412.0, frame.viewport.widthPx)
        assertEquals(733.0, frame.viewport.heightPx)
        assertEquals(412.0, frame.composition.viewport.width)
        assertEquals(733.0, frame.composition.viewport.height)
    }

    @Test fun homeTextFragmentsDoNotShareTheSameOrigin() {
        val text = homeFrame().displayList.commands.filterIsInstance<PaintCommand.DrawText>()
            .filter { it.text.isNotBlank() }
        assertTrue(text.size >= 8, "expected a meaningful home-page text display list")
        val duplicateOrigins = text.groupBy { Pair(it.rect.x, it.rect.y + it.baselinePx) }
            .filterValues { commands -> commands.map { it.text }.distinct().size > 1 }
        assertTrue(duplicateOrigins.isEmpty(), "different home-page text fragments must not be painted at the same origin: $duplicateOrigins")
    }

    @Test fun homeHeadlineAndLeadAreVerticallySeparated() {
        val text = homeFrame().displayList.commands.filterIsInstance<PaintCommand.DrawText>()
        val headline = text.first { it.text == "Aether" }
        val lead = text.first { it.text == "Independent" }
        val headlineBaseline = headline.rect.y + headline.baselinePx
        val leadBaseline = lead.rect.y + lead.baselinePx
        assertTrue(leadBaseline > headlineBaseline + headline.fontSizePx * 0.5)
    }

    @Test fun homeCardsOccupyDistinctVerticalBands() {
        val text = homeFrame().displayList.commands.filterIsInstance<PaintCommand.DrawText>()
        val cardBaselines = listOf("Address", "Multi-tab", "Custom")
            .map { title -> text.first { it.text == title }.let { it.rect.y + it.baselinePx } }
        assertEquals(cardBaselines.sorted(), cardBaselines)
        assertEquals(3, cardBaselines.distinct().size)
        assertTrue(cardBaselines.zipWithNext().all { (first, second) -> second - first >= 24.0 })
    }
}
