package com.mohnishraj.aether.core.paint

import com.mohnishraj.aether.core.layout.LayoutRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaintColorParserTest {
    @Test fun parsesThreeDigitHex() {
        assertEquals(PaintColor(170, 187, 204), PaintValueParser.color("#abc"))
    }
    @Test fun parsesFourDigitHex() {
        assertEquals(PaintColor(170, 187, 204, 221), PaintValueParser.color("#abcd"))
    }
    @Test fun parsesSixDigitHex() {
        assertEquals(PaintColor(17, 34, 51), PaintValueParser.color("#112233"))
    }
    @Test fun parsesEightDigitHexAsRgba() {
        assertEquals(PaintColor(17, 34, 51, 68), PaintValueParser.color("#11223344"))
    }
    @Test fun parsesRgbIntegers() {
        assertEquals(PaintColor(12, 34, 56), PaintValueParser.color("rgb(12,34,56)"))
    }
    @Test fun parsesRgbPercentages() {
        assertEquals(PaintColor(255, 0, 128), PaintValueParser.color("rgb(100%,0%,50%)"))
    }
    @Test fun parsesRgbaAlpha() {
        assertTrue(PaintValueParser.color("rgba(1,2,3,.5)")!!.alpha in 127..128)
    }
    @Test fun resolvesCurrentColor() {
        assertEquals(PaintColor(9, 8, 7), PaintValueParser.color("currentColor", PaintColor(9, 8, 7)))
    }
    @Test fun rejectsInvalidColor() {
        assertNull(PaintValueParser.color("not-a-color"))
    }
    @Test fun clampsOpacity() {
        assertEquals(0.0, PaintValueParser.opacity("-4"))
        assertEquals(1.0, PaintValueParser.opacity("8"))
    }
    @Test fun expandsBorderRadiusShorthand() {
        val radii = PaintValueParser.radii("4px 8px 12px 16px", LayoutRect(0.0, 0.0, 100.0, 50.0))
        assertEquals(CornerRadii(4.0, 8.0, 12.0, 16.0), radii)
    }
    @Test fun clampsBorderRadiusToHalfShortestSide() {
        val radii = PaintValueParser.radii("999px", LayoutRect(0.0, 0.0, 100.0, 40.0))
        assertEquals(20.0, radii.topLeft)
    }
    @Test fun parsesLinearGradientAngleAndColors() {
        val gradient = PaintValueParser.linearGradient("linear-gradient(45deg, #000, #fff)", PaintColor.BLACK)!!
        assertEquals(45.0, gradient.third)
        assertEquals(PaintColor.BLACK, gradient.first)
        assertEquals(PaintColor.WHITE, gradient.second)
    }
    @Test fun parsesDirectionalGradient() {
        assertEquals(90.0, PaintValueParser.linearGradient("linear-gradient(to right, red, blue)", PaintColor.BLACK)!!.third)
    }
    @Test fun parsesShadowWithInsetAndSpread() {
        val shadow = PaintValueParser.shadows("inset 1px 2px 3px 4px #112233", PaintColor.BLACK, 4).single()
        assertTrue(shadow.inset)
        assertEquals(4.0, shadow.spreadRadius)
        assertEquals(PaintColor(17, 34, 51), shadow.color)
    }
    @Test fun parsesObjectFitValues() {
        assertEquals(ImageFit.COVER, PaintValueParser.imageFit("cover"))
        assertEquals(ImageFit.SCALE_DOWN, PaintValueParser.imageFit("scale-down"))
    }
    @Test fun parsesFontWeightKeywordsAndNumbers() {
        assertEquals(700, PaintValueParser.fontWeight("bold"))
        assertEquals(550, PaintValueParser.fontWeight("550"))
    }
}
