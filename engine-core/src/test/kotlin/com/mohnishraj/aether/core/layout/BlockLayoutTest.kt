package com.mohnishraj.aether.core.layout

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlockLayoutTest {
    @Test fun autoBlockWidthFillsContainingBlock() {
        val fixture = layoutFixture("<main id='x'></main>")
        assertEquals(360.0, fixture.box("x").borderBox.width)
    }

    @Test fun contentBoxSizingAddsPaddingAndBorder() {
        val fixture = layoutFixture("<div id='x'></div>", "#x { width:100px; padding:10px; border:2px solid }")
        val box = fixture.box("x")
        assertEquals(124.0, box.borderBox.width)
        assertEquals(100.0, box.contentBox.width)
    }

    @Test fun borderBoxSizingKeepsDeclaredOuterWidth() {
        val fixture = layoutFixture("<div id='x'></div>", "#x { width:100px; padding:10px; border:2px solid; box-sizing:border-box }")
        val box = fixture.box("x")
        assertEquals(100.0, box.borderBox.width)
        assertEquals(76.0, box.contentBox.width)
    }

    @Test fun minAndMaxWidthClampUsedWidth() {
        val minFixture = layoutFixture("<div id='x'></div>", "#x { width:20px; min-width:80px }")
        val maxFixture = layoutFixture("<div id='x'></div>", "#x { width:200px; max-width:90px }")
        assertEquals(80.0, minFixture.box("x").borderBox.width)
        assertEquals(90.0, maxFixture.box("x").borderBox.width)
    }

    @Test fun autoHorizontalMarginsCenterFixedWidthBlock() {
        val fixture = layoutFixture("<div id='x'></div>", "#x { width:100px; margin:0 auto }")
        assertEquals(130.0, fixture.box("x").borderBox.x)
    }

    @Test fun blockChildrenAdvanceVerticalFlow() {
        val fixture = layoutFixture("<main><div id='a'></div><div id='b'></div></main>", "div { height:25px }")
        assertTrue(fixture.box("b").flowBorderBox.y >= fixture.box("a").flowBorderBox.bottom)
    }

    @Test fun adjacentMarginsCollapseToMaximumPositiveValue() {
        val fixture = layoutFixture("<main><div id='a'></div><div id='b'></div></main>", "#a { height:10px; margin-bottom:12px } #b { height:10px; margin-top:30px }")
        val gap = fixture.box("b").flowBorderBox.y - fixture.box("a").flowBorderBox.bottom
        assertEquals(30.0, gap)
    }

    @Test fun explicitHeightOverridesContentHeight() {
        val fixture = layoutFixture("<div id='x'><p>Many words that would otherwise create height.</p></div>", "#x { height:40px; width:120px }")
        assertEquals(40.0, fixture.box("x").contentBox.height)
    }

    @Test fun displayNoneDoesNotCreateBox() {
        val fixture = layoutFixture("<main><div id='gone'></div><p id='kept'>Y</p></main>", "#gone { display:none }")
        assertNull(fixture.tree.boxFor(fixture.document.getElementById("gone")!!))
        assertNotNull(fixture.tree.boxFor(fixture.document.getElementById("kept")!!))
    }

    @Test fun replacedImageUsesIntrinsicAttributes() {
        val fixture = layoutFixture("<img id='x' width='120' height='60'>", "#x { display:block }")
        assertEquals(60.0, fixture.box("x").contentBox.height)
    }

    @Test fun percentageAndViewportWidthsRespondToViewport() {
        val fixture = layoutFixture("<div id='x'></div>", "#x { width:50vw; height:10vh }", width=500.0, height=700.0)
        assertEquals(250.0, fixture.box("x").borderBox.width)
        assertEquals(70.0, fixture.box("x").contentBox.height)
    }

    @Test fun layoutRectsRemainFinite() {
        val fixture = layoutFixture("<div id='x'></div>", "#x { width:999999999999px; height:999999999999px }")
        val box = fixture.box("x")
        assertTrue(box.borderBox.width.isFinite() && box.borderBox.height.isFinite())
        assertTrue(box.borderBox.width <= 10_000_000.0 && box.borderBox.height <= 10_000_000.0)
    }
}
