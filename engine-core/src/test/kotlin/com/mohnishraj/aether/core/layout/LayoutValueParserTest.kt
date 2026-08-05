package com.mohnishraj.aether.core.layout

import com.mohnishraj.aether.core.css.ComputedStyle
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayoutValueParserTest {
    private val viewport = LayoutViewport(400.0, 800.0, 16.0)
    private val context = LengthContext(200.0, 20.0, 16.0, viewport)

    @Test fun resolvesPixelsAndUnitlessZero() {
        assertEquals(12.0, LayoutValueParser.resolveLength("12px", context))
        assertEquals(0.0, LayoutValueParser.resolveLength("0", context))
    }

    @Test fun resolvesPercentages() {
        assertEquals(50.0, LayoutValueParser.resolveLength("25%", context))
    }
    @Test fun resolvesFontRelativeUnits() {
        assertEquals(40.0, LayoutValueParser.resolveLength("2em", context))
        assertEquals(32.0, LayoutValueParser.resolveLength("2rem", context))
    }

    @Test fun resolvesViewportUnits() {
        assertEquals(40.0, LayoutValueParser.resolveLength("10vw", context))
        assertEquals(80.0, LayoutValueParser.resolveLength("10vh", context))
        assertEquals(40.0, LayoutValueParser.resolveLength("10vmin", context))
        assertEquals(80.0, LayoutValueParser.resolveLength("10vmax", context))
    }

    @Test fun resolvesPhysicalUnits() {
        assertTrue(abs(LayoutValueParser.resolveLength("1in", context)!! - 96.0) < 0.001)
        assertTrue(abs(LayoutValueParser.resolveLength("72pt", context)!! - 96.0) < 0.001)
        assertTrue(abs(LayoutValueParser.resolveLength("6pc", context)!! - 96.0) < 0.001)
    }

    @Test fun resolvesCalcAdditionAndSubtraction() {
        assertEquals(196.0, LayoutValueParser.resolveLength("calc(100% - 20px + 1rem)", context))
    }

    @Test fun rejectsUnsupportedKeywords() {
        assertNull(LayoutValueParser.resolveLength("auto", context))
        assertNull(LayoutValueParser.resolveLength("min-content", context))
        assertNull(LayoutValueParser.resolveLength("banana", context))
    }

    @Test fun clampsNegativeWhenDisallowed() {
        assertEquals(0.0, LayoutValueParser.resolveLength("-10px", context, allowNegative = false))
    }

    @Test fun parsesFontSizeKeywords() {
        val style = ComputedStyle(mapOf("font-size" to "larger"), emptyMap(), 0)
        assertEquals(24.0, LayoutValueParser.resolveFontSize(style, 20.0, viewport))
    }

    @Test fun parsesNumericLineHeight() {
        val style = ComputedStyle(mapOf("line-height" to "1.5"), emptyMap(), 0)
        assertEquals(30.0, LayoutValueParser.resolveLineHeight(style, 20.0, viewport))
    }

    @Test fun parsesOverflowAxes() {
        val style = ComputedStyle(mapOf("overflow" to "hidden auto"), emptyMap(), 0)
        assertEquals(OverflowMode.HIDDEN to OverflowMode.AUTO, LayoutValueParser.resolveOverflow(style))
    }

    @Test fun collapsesPositiveNegativeAndMixedMargins() {
        assertEquals(20.0, LayoutValueParser.collapseMargins(10.0, 20.0))
        assertEquals(-20.0, LayoutValueParser.collapseMargins(-10.0, -20.0))
        assertEquals(5.0, LayoutValueParser.collapseMargins(15.0, -10.0))
    }
}
