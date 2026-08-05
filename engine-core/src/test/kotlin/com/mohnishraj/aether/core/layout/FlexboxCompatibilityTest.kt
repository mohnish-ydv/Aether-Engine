package com.mohnishraj.aether.core.layout

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlexboxCompatibilityTest {
    @Test fun rowFlexDistributesGrowShrinkBasisAndOrder() {
        val fixture = layoutFixture(
            "<div id='flex'><div id='a'>A</div><div id='b'>B</div><div id='c'>C</div></div>",
            """
            #flex { display:flex; width:300px; gap:10px }
            #a { flex-grow:1; flex-shrink:1; flex-basis:50px; order:2 }
            #b { flex-grow:2; flex-shrink:1; flex-basis:50px; order:1 }
            #c { flex-grow:0; flex-shrink:1; flex-basis:50px; order:3 }
            """.trimIndent(),
            width = 360.0
        )
        val a = fixture.box("a")
        val b = fixture.box("b")
        val c = fixture.box("c")

        assertTrue(b.borderBox.x < a.borderBox.x && a.borderBox.x < c.borderBox.x)
        assertTrue(b.borderBox.width > a.borderBox.width)
        assertTrue(a.borderBox.width > c.borderBox.width)
        assertNear(300.0, a.borderBox.width + b.borderBox.width + c.borderBox.width + 20.0)
        assertEquals(LayoutBoxKind.FLEX, fixture.box("flex").kind)
    }

    @Test fun wrapCreatesMultipleLinesAndAlignContentMovesThem() {
        val fixture = layoutFixture(
            "<div id='flex'><div id='a'>A</div><div id='b'>B</div><div id='c'>C</div></div>",
            """
            #flex { display:flex; flex-wrap:wrap; align-content:center; width:220px; height:220px; gap:10px }
            #flex > div { flex:0 0 100px; height:30px }
            """.trimIndent(),
            width = 260.0,
            height = 400.0
        )
        val a = fixture.box("a")
        val b = fixture.box("b")
        val c = fixture.box("c")
        assertNear(a.borderBox.y, b.borderBox.y)
        assertTrue(c.borderBox.y > a.borderBox.y + a.borderBox.height)
        assertTrue(a.borderBox.y > fixture.box("flex").contentBox.y)
    }

    @Test fun justifyAndAlignItemsPositionChildrenOnBothAxes() {
        val fixture = layoutFixture(
            "<div id='flex'><div id='a'>A</div></div>",
            """
            #flex { display:flex; width:300px; height:120px; justify-content:center; align-items:flex-end }
            #a { flex:0 0 80px; height:30px }
            """.trimIndent(),
            width = 340.0
        )
        val flex = fixture.box("flex")
        val child = fixture.box("a")
        assertNear(flex.contentBox.x + 110.0, child.borderBox.x)
        assertNear(flex.contentBox.bottom, child.borderBox.bottom)
    }

    @Test fun columnDirectionUsesHeightAsMainAxis() {
        val fixture = layoutFixture(
            "<div id='flex'><div id='a'>A</div><div id='b'>B</div></div>",
            """
            #flex { display:flex; flex-direction:column; width:180px; height:200px; gap:10px }
            #a { flex-grow:1; flex-basis:30px }
            #b { flex-grow:1; flex-basis:30px }
            """.trimIndent(),
            width = 240.0
        )
        val a = fixture.box("a")
        val b = fixture.box("b")
        assertNear(95.0, a.borderBox.height)
        assertNear(95.0, b.borderBox.height)
        assertNear(a.borderBox.bottom + 10.0, b.borderBox.y)
    }

    @Test fun aspectRatioAndPercentageSizingResolveAgainstContainingBlock() {
        val fixture = layoutFixture(
            "<div id='outer'><div id='inner'></div></div>",
            "#outer { width:320px } #inner { width:50%; aspect-ratio:16 / 9 }",
            width = 360.0
        )
        val inner = fixture.box("inner")
        assertNear(160.0, inner.borderBox.width)
        assertNear(90.0, inner.borderBox.height)
    }

    @Test fun overflowEllipsisProducesSingleEllipsisFragment() {
        val fixture = layoutFixture(
            "<div id='x'>This sentence is much wider than the available box.</div>",
            "#x { width:80px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis }"
        )
        val text = fixture.box("x").lineBoxes.flatMap { it.fragments }.joinToString("") { it.text }
        assertTrue(text.endsWith("…"), text)
    }

    private fun assertNear(expected: Double, actual: Double, tolerance: Double = 0.75) {
        assertTrue(abs(expected - actual) <= tolerance, "expected=$expected actual=$actual")
    }
}
