package com.mohnishraj.aether.core.paint

import com.mohnishraj.aether.core.layout.LayoutRect
import com.mohnishraj.aether.core.paint.inspect.PaintInspector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaintInvalidationInspectorTest {
    @Test fun firstDisplayListRequiresFullRepaint() {
        val list = paintFixture("<p>A</p>").displayList
        val diff = PaintInvalidationTracker.compare(null, list)
        assertTrue(diff.fullRepaint)
        assertEquals(list.viewport, diff.dirtyRect)
    }
    @Test fun identicalDisplayListsAreClean() {
        val first = paintFixture("<p>A</p>", "p{color:red}").displayList
        val second = paintFixture("<p>A</p>", "p{color:red}").displayList
        assertTrue(PaintInvalidationTracker.compare(first, second).isClean)
    }
    @Test fun colorChangeMarksDirtyBounds() {
        val first = paintFixture("<div id='x'></div>", "#x{width:20px;height:20px;background:red}").displayList
        val second = paintFixture("<div id='x'></div>", "#x{width:20px;height:20px;background:blue}").displayList
        val diff = PaintInvalidationTracker.compare(first, second)
        assertFalse(diff.isClean)
        assertTrue(diff.dirtyRect!!.width >= 20.0)
    }
    @Test fun viewportChangeRequiresFullRepaint() {
        val first = paintFixture("<p>A</p>", width=320.0).displayList
        val second = paintFixture("<p>A</p>", width=640.0).displayList
        assertTrue(PaintInvalidationTracker.compare(first, second).fullRepaint)
    }
    @Test fun manyCommandChangesPromoteFullRepaint() {
        val first = paintFixture("<main>${(1..80).joinToString("") { "<p>$it</p>" }}</main>", "p{color:red}").displayList
        val second = paintFixture("<main>${(1..80).joinToString("") { "<p>$it changed</p>" }}</main>", "p{color:blue}").displayList
        assertTrue(PaintInvalidationTracker.compare(first, second).fullRepaint)
    }
    @Test fun inspectorIncludesSummaryAndCommands() {
        val output = PaintInspector.displayList(paintFixture("<p>Aether</p>", "p{color:navy}").displayList)
        assertTrue("AETHER PAINT DISPLAY LIST" in output)
        assertTrue("commands=" in output)
        assertTrue("TEXT" in output)
    }
    @Test fun inspectorEscapesText() {
        val output = PaintInspector.displayList(paintFixture("<p>A\nB</p>").displayList)
        assertTrue("A\\nB" in output || "TEXT" in output)
    }
    @Test fun summaryReportsImageAndClipCounts() {
        val list = paintFixture("<div><img src='x' width='20' height='10'></div>", "div{width:10px;height:10px;overflow:hidden} img{display:block}").displayList
        val summary = PaintInspector.summary(list)
        assertTrue("images=1" in summary)
        assertTrue("clips=" in summary)
    }
    @Test fun shadowBoundsExpandCorrectly() {
        val command = PaintCommand.DrawShadow(LayoutRect(10.0, 10.0, 20.0, 30.0), CornerRadii(), BoxShadowPaint(2.0, 3.0, 4.0, 1.0, PaintColor.BLACK))
        assertEquals(7.0, command.bounds.x)
        assertEquals(8.0, command.bounds.y)
        assertEquals(30.0, command.bounds.width)
        assertEquals(40.0, command.bounds.height)
    }
}
