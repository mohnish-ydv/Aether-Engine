package com.mohnishraj.aether.core.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class M15FormattingContextCompatibilityTest {
    @Test fun clampResolvesAgainstViewport() {
        val value = LayoutValueParser.resolveLength(
            "clamp(40px,50vw,300px)",
            LengthContext(360.0, 16.0, 16.0, LayoutViewport(360.0, 800.0))
        )
        assertEquals(180.0, value)
    }

    @Test fun minAndMaxFunctionsResolveLengths() {
        val context = LengthContext(400.0, 16.0, 16.0, LayoutViewport(400.0, 800.0))
        assertEquals(80.0, LayoutValueParser.resolveLength("min(20vw,120px)", context))
        assertEquals(120.0, LayoutValueParser.resolveLength("max(20vw,120px)", context))
    }

    @Test fun dynamicViewportUnitsUseCurrentViewport() {
        val context = LengthContext(400.0, 16.0, 16.0, LayoutViewport(400.0, 800.0))
        assertEquals(100.0, LayoutValueParser.resolveLength("25dvw", context))
        assertEquals(200.0, LayoutValueParser.resolveLength("25dvh", context))
    }

    @Test fun borderBoxResponsiveContainerDoesNotOverflowViewport() {
        val fixture = layoutFixture(
            "<main id='m'><p>Hello</p></main>",
            "body{box-sizing:border-box;margin:0;padding:16px}main{box-sizing:border-box;width:auto;max-width:680px;margin:12px auto;padding:20px}"
        )
        assertTrue(fixture.tree.root.scrollSize.width <= 360.01)
        assertTrue(fixture.box("m").borderBox.right <= 360.01)
    }

    @Test fun displayContentsSkipsPrincipalBoxButKeepsChildren() {
        val fixture = layoutFixture(
            "<div><span id='contents'><b id='child'>B</b></span></div>",
            "#contents{display:contents}#child{display:block;width:40px;height:20px}"
        )
        assertNull(fixture.tree.boxFor(fixture.document.getElementById("contents")!!))
        assertTrue(fixture.tree.boxFor(fixture.document.getElementById("child")!!) != null)
    }

    @Test fun textTransformAndIndentReachInlineFragments() {
        val fixture = layoutFixture("<p id='p'>hello world</p>", "#p{text-transform:uppercase;text-indent:24px;width:200px}")
        val box = fixture.box("p")
        val text = box.lineBoxes.flatMap { it.fragments }.joinToString("") { it.text }
        assertTrue(text.contains("HELLO"))
        assertTrue(box.lineBoxes.first().fragments.first().rect.x >= box.contentBox.x + 23.0)
    }

    @Test fun rootScrollWidthTracksDescendantGeometry() {
        val fixture = layoutFixture("<div id='wide'></div>", "#wide{width:640px;height:10px}", width = 360.0)
        assertTrue(fixture.tree.root.scrollSize.width >= 640.0)
    }
}
