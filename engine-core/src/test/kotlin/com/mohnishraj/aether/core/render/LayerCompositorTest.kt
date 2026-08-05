package com.mohnishraj.aether.core.render

import com.mohnishraj.aether.core.paint.PaintInvalidation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LayerCompositorTest {
    @Test fun initialCompositionContainsRootLayer() {
        val fixture = renderFixture()
        assertTrue(fixture.first.composition.layers.any { LayerPromotionReason.ROOT in it.reasons })
    }

    @Test fun fixedElementIsPromoted() {
        val fixture = renderFixture()
        assertTrue(fixture.first.composition.layers.any { LayerPromotionReason.FIXED_POSITION in it.reasons })
    }

    @Test fun opacityElementIsPromoted() {
        val fixture = renderFixture()
        assertTrue(fixture.first.composition.layers.any { LayerPromotionReason.OPACITY in it.reasons })
    }

    @Test fun initialCompositionIsFullRedraw() {
        val fixture = renderFixture()
        assertTrue(fixture.first.composition.fullRedraw)
        assertEquals(listOf(fixture.first.displayList.viewport), fixture.first.composition.damageRects)
    }

    @Test fun compositeOnlyScrollReusesStyleLayoutPaint() {
        val fixture = renderFixture()
        fixture.session.scrollTo(0.0, 200.0, ScrollBehavior.INSTANT, 2_000_000_000L)
        val frame = fixture.session.renderNow(2_016_666_667L)
        assertTrue(frame.reuse.styleTree)
        assertTrue(frame.reuse.layoutTree)
        assertTrue(frame.reuse.displayList)
    }

    @Test fun smallScrollProducesScrollReuse() {
        val fixture = renderFixture()
        fixture.session.scrollTo(0.0, 100.0, ScrollBehavior.INSTANT, 2_000_000_000L)
        val frame = fixture.session.renderNow(2_016_666_667L)
        assertNotNull(frame.composition.scrollReuse)
    }

    @Test fun largeScrollRequiresFullViewportDamage() {
        val fixture = renderFixture(RenderViewport(360.0, 300.0))
        fixture.session.scrollTo(0.0, 1000.0, ScrollBehavior.INSTANT, 2_000_000_000L)
        val frame = fixture.session.renderNow(2_016_666_667L)
        assertTrue(frame.composition.damageRects.contains(frame.displayList.viewport))
    }

    @Test fun fixedLayerDoesNotReceiveScrollTranslation() {
        val fixture = renderFixture()
        fixture.session.scrollTo(0.0, 100.0, ScrollBehavior.INSTANT, 2_000_000_000L)
        val frame = fixture.session.renderNow(2_016_666_667L)
        val fixed = frame.composition.layers.first { LayerPromotionReason.FIXED_POSITION in it.reasons }
        assertEquals(0.0, fixed.transform.translateY)
    }

    @Test fun rootLayerReceivesNegativeScrollTranslation() {
        val fixture = renderFixture()
        fixture.session.scrollTo(0.0, 100.0, ScrollBehavior.INSTANT, 2_000_000_000L)
        val frame = fixture.session.renderNow(2_016_666_667L)
        val root = frame.composition.layers.first { LayerPromotionReason.ROOT in it.reasons }
        assertEquals(-100.0, root.transform.translateY)
    }

    @Test fun unchangedLayerContentIsReused() {
        val fixture = renderFixture()
        fixture.session.scrollTo(0.0, 20.0, ScrollBehavior.INSTANT, 2_000_000_000L)
        val frame = fixture.session.renderNow(2_016_666_667L)
        assertTrue(frame.composition.reusedLayerCount > 0)
    }

    @Test fun layerCountHonorsLimit() {
        val fixture = renderFixture()
        assertTrue(fixture.first.composition.layerCount <= fixture.runtime.render.limits.maxLayers)
    }

    @Test fun compositorCanProduceCleanDamage() {
        val fixture = renderFixture()
        val compositor = LayerCompositor()
        val composition = compositor.compose(
            fixture.first.layout,
            fixture.first.displayList,
            fixture.first.scroll,
            fixture.first.composition,
            PaintInvalidation(null, emptyList(), false),
            RenderInvalidation.CLEAN
        )
        assertFalse(composition.fullRedraw)
        assertTrue(composition.damageRects.isEmpty())
    }
}
