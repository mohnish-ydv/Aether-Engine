package com.mohnishraj.aether.core.layout

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PositioningOverflowTest {
    @Test fun relativeOffsetsVisualBoxButNotFlowBox() {
        val fixture = layoutFixture("<div id='x'></div>", "#x { width:20px; height:10px; position:relative; left:8px; top:5px }")
        val box = fixture.box("x")
        assertEquals(8.0, box.borderBox.x - box.flowBorderBox.x)
        assertEquals(5.0, box.borderBox.y - box.flowBorderBox.y)
    }

    @Test fun absoluteElementIsRemovedFromNormalFlow() {
        val fixture = layoutFixture("<main id='p'><div id='abs'></div><div id='flow'></div></main>", "#p { position:relative } #abs { position:absolute; width:20px; height:100px } #flow { height:10px }")
        val parent = fixture.box("p")
        assertTrue(parent.contentBox.height < 100.0)
        assertEquals(PositionScheme.ABSOLUTE, fixture.box("abs").position)
    }

    @Test fun absoluteLeftTopUsePositionedAncestorPaddingBox() {
        val fixture = layoutFixture("<main id='p'><div id='x'></div></main>", "#p { position:relative; padding:10px; border:2px solid; width:200px; height:100px } #x { position:absolute; left:20px; top:15px; width:30px; height:10px }")
        val parent = fixture.box("p")
        val child = fixture.box("x")
        assertTrue(abs(child.borderBox.x - (parent.borderBox.x + parent.border.left + 20.0)) < 0.001)
        assertTrue(abs(child.borderBox.y - (parent.borderBox.y + parent.border.top + 15.0)) < 0.001)
    }

    @Test fun absoluteRightBottomAnchorToContainingBlock() {
        val fixture = layoutFixture("<main id='p'><div id='x'></div></main>", "#p { position:relative; width:200px; height:100px } #x { position:absolute; right:10px; bottom:5px; width:30px; height:20px }")
        val parent = fixture.box("p")
        val child = fixture.box("x")
        assertTrue(abs(child.borderBox.right - (parent.borderBox.right - 10.0)) < 0.001)
        assertTrue(abs(child.borderBox.bottom - (parent.borderBox.bottom - 5.0)) < 0.001)
    }

    @Test fun fixedElementAnchorsToViewport() {
        val fixture = layoutFixture("<div id='x'></div>", "#x { position:fixed; right:10px; bottom:20px; width:30px; height:40px }")
        val box = fixture.box("x")
        assertEquals(350.0, box.borderBox.right)
        assertEquals(780.0, box.borderBox.bottom)
    }

    @Test fun overflowHiddenCreatesClipRect() {
        val fixture = layoutFixture("<div id='p'><div id='c'></div></div>", "#p { width:100px; height:40px; overflow:hidden } #c { width:200px; height:90px }")
        assertNotNull(fixture.box("p").clipRect)
    }

    @Test fun overflowAutoBecomesScrollableOnlyWhenNeeded() {
        val overflow = layoutFixture("<div id='p'><div></div></div>", "#p { width:100px; height:40px; overflow:auto } #p > div { width:200px; height:80px }").box("p")
        val fitting = layoutFixture("<div id='p'><div></div></div>", "#p { width:100px; height:100px; overflow:auto } #p > div { width:50px; height:20px }").box("p")
        assertTrue(overflow.isScrollableX && overflow.isScrollableY)
        assertTrue(!fitting.isScrollableX && !fitting.isScrollableY)
    }

    @Test fun scrollOverflowIsAlwaysScrollable() {
        val box = layoutFixture("<div id='p'></div>", "#p { width:100px; height:40px; overflow:scroll }").box("p")
        assertTrue(box.isScrollableX && box.isScrollableY)
    }

    @Test fun zIndexSortsPaintOrder() {
        val fixture = layoutFixture("<main><div id='low'></div><div id='high'></div></main>", "div { position:absolute; width:10px; height:10px } #low { z-index:-2 } #high { z-index:8 }")
        assertTrue(fixture.tree.paintOrder.indexOf(fixture.box("low")) < fixture.tree.paintOrder.indexOf(fixture.box("high")))
    }

    @Test fun opacityCreatesStackingContext() {
        val box = layoutFixture("<div id='x'></div>", "#x { opacity:.5 }").box("x")
        assertTrue(box.establishesStackingContext)
    }
}
