package com.mohnishraj.aether.core.paint

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PaintCommandGenerationTest {
    @Test fun emitsSolidBackground() {
        val list = paintFixture("<div id='x'></div>", "#x { width:80px;height:20px;background-color:#123456 }").displayList
        assertTrue(list.commands.any { it is PaintCommand.FillRect && it.color == PaintColor(18,52,86) })
    }
    @Test fun emitsRoundedBackground() {
        val list = paintFixture("<div id='x'></div>", "#x { width:80px;height:20px;background:red;border-radius:8px }").displayList
        assertTrue(list.commands.any { it is PaintCommand.FillRoundedRect })
    }
    @Test fun emitsLinearGradientAfterBackground() {
        val list = paintFixture("<div id='x'></div>", "#x { width:80px;height:20px;background-color:black;background-image:linear-gradient(red,blue) }").displayList
        val fillIndex = list.commands.indexOfFirst { it is PaintCommand.FillRect }
        val gradientIndex = list.commands.indexOfFirst { it is PaintCommand.DrawLinearGradient }
        assertTrue(fillIndex >= 0 && gradientIndex > fillIndex)
    }
    @Test fun emitsBorderUsingLayoutWidths() {
        val border = paintFixture("<div id='x'></div>", "#x { width:80px;height:20px;border:3px solid red }").displayList.commands.filterIsInstance<PaintCommand.DrawBorder>().single()
        assertEquals(3.0, border.border.widths.top)
        assertEquals(PaintColor(255,0,0), border.border.colors.first())
    }
    @Test fun skipsNoneBorderStyle() {
        val list = paintFixture("<div id='x'></div>", "#x { width:80px;height:20px;border:3px none red }").displayList
        assertFalse(list.commands.any { it is PaintCommand.DrawBorder })
    }
    @Test fun emitsOuterShadowBeforeBackground() {
        val list = paintFixture("<div id='x'></div>", "#x { width:80px;height:20px;background:white;box-shadow:1px 2px 3px black }").displayList
        val shadow = list.commands.indexOfFirst { it is PaintCommand.DrawShadow }
        val nodeBackground = list.commands.indexOfFirst { it is PaintCommand.FillRect && it.nodeId != null }
        assertTrue(shadow >= 0 && nodeBackground > shadow)
    }
    @Test fun emitsInsetShadowAfterTextAndBorder() {
        val list = paintFixture("<div id='x'>X</div>", "#x { width:80px;height:20px;border:1px solid;box-shadow:inset 1px 2px 3px black }").displayList
        val shadow = list.commands.indexOfLast { it is PaintCommand.DrawShadow }
        val border = list.commands.indexOfLast { it is PaintCommand.DrawBorder }
        assertTrue(shadow > border)
    }
    @Test fun emitsTextWithFontMetadata() {
        val text = paintFixture("<p id='x'>Aether</p>", "#x { font-size:20px;font-family:'Aether Sans';font-weight:700;font-style:italic;color:teal }").displayList.commands.filterIsInstance<PaintCommand.DrawText>().first()
        assertEquals("Aether Sans", text.fontFamily)
        assertEquals(700, text.fontWeight)
        assertTrue(text.italic)
        assertEquals(PaintColor(0,128,128), text.color)
    }
    @Test fun emitsImageWithAltAndFit() {
        val image = paintFixture("<img src='https://example.test/a.png' alt='A' width='40' height='20'>", "img { display:block;object-fit:contain }").displayList.commands.filterIsInstance<PaintCommand.DrawImage>().single()
        assertEquals("A", image.altText)
        assertEquals(ImageFit.CONTAIN, image.fit)
    }
    @Test fun imageWithoutSourceDoesNotEmitImageCommand() {
        val list = paintFixture("<img alt='Missing' width='40' height='20'>", "img { display:block }").displayList
        assertFalse(list.commands.any { it is PaintCommand.DrawImage })
        assertTrue(list.issues.any { it.code == "paint-image-source" })
    }
    @Test fun hiddenElementProducesNoNodeCommands() {
        val list = paintFixture("<div id='x'>hidden</div>", "#x { visibility:hidden;background:red }").displayList
        assertTrue(list.commands.none { it.nodeId != null && (it is PaintCommand.DrawText || it is PaintCommand.FillRect || it is PaintCommand.FillRoundedRect) })
    }
    @Test fun zeroOpacityProducesNoNodeCommands() {
        val list = paintFixture("<div id='x'>transparent</div>", "#x { opacity:0;background:red }").displayList
        assertTrue(list.commands.none { it.nodeId != null && (it is PaintCommand.DrawText || it is PaintCommand.FillRect || it is PaintCommand.FillRoundedRect) })
    }
    @Test fun overflowCreatesBalancedClipCommands() {
        val list = paintFixture("<div id='x'>long long long text</div>", "#x { width:20px;height:10px;overflow:hidden }").displayList
        assertEquals(list.commands.count { it is PaintCommand.PushClip }, list.commands.count { it is PaintCommand.PopClip })
        assertTrue(list.clipCommandCount > 0)
    }
    @Test fun offscreenClippedBoxIsCulled() {
        val list = paintFixture("<div id='x'>X</div>", "#x { position:absolute;left:900px;top:900px;width:20px;height:20px;overflow:hidden;background:red }", width=100.0, height=100.0).displayList
        assertFalse(list.commands.any { it is PaintCommand.FillRect && it.color == PaintColor(255,0,0) })
    }
    @Test fun zIndexPaintOrderIsPreserved() {
        val list = paintFixture("<main><div id='a'></div><div id='b'></div></main>", "div { position:absolute;width:10px;height:10px } #a { z-index:-1;background:red } #b { z-index:9;background:blue }").displayList
        val fills = list.commands.filterIsInstance<PaintCommand.FillRect>()
        assertTrue(fills.indexOfFirst { it.color == PaintColor(255,0,0) } < fills.indexOfFirst { it.color == PaintColor(0,0,255) })
    }
    @Test fun visualBoundsIncludeEffects() {
        val list = paintFixture("<div id='x'></div>", "#x { width:20px;height:20px;box-shadow:0 0 10px 5px black }").displayList
        assertNotNull(list.visualBounds)
        assertTrue(list.visualBounds!!.width > 20.0)
    }
    @Test fun commandBoundsSupportPointQuery() {
        val list = paintFixture("<div id='x'></div>", "#x { width:100px;height:50px;background:red }").displayList
        assertTrue(list.commandsAt(10.0,10.0).isNotEmpty())
        assertTrue(list.commandsAt(500.0,500.0).isEmpty())
    }
}
