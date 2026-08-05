package com.mohnishraj.aether.core.layout

import com.mohnishraj.aether.core.paint.PaintValueParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class M16FlexFormsReplacedCompatibilityTest {
    @Test fun buttonGeneratesContainerInsteadOfReplacedBox() {
        val fixture = layoutFixture("<button id='b'><span>Search</span></button>", "button{display:inline-flex;width:120px;height:44px}")
        assertNotEquals(LayoutBoxKind.REPLACED, fixture.box("b").kind)
    }

    @Test fun buttonSvgChildRetainsOwnLayoutBox() {
        val fixture = layoutFixture(
            "<button id='b'><svg id='s' viewBox='0 0 10 10'><path d='M0 0L10 10'/></svg></button>",
            "button{display:inline-flex;width:44px;height:44px}svg{width:20px;height:20px}"
        )
        assertNotNull(fixture.tree.boxFor(fixture.document.getElementById("s")!!))
    }

    @Test fun flexAutoMarginConsumesAvailableMainAxisSpace() {
        val fixture = layoutFixture(
            "<div id='f'><div id='a'>A</div><div id='b'>B</div></div>",
            "#f{display:flex;width:300px}#a{width:50px}#b{width:50px;margin-left:auto}"
        )
        assertTrue(fixture.box("b").borderBox.x - fixture.box("a").borderBox.right > 150.0)
    }

    @Test fun textareaRowsAndColumnsAffectIntrinsicSize() {
        val fixture = layoutFixture("<textarea id='t' rows='4' cols='30'></textarea>")
        assertTrue(fixture.box("t").borderBox.width > 200.0)
        assertTrue(fixture.box("t").borderBox.height > 60.0)
    }

    @Test fun inputSizeAttributeAffectsTextFieldWidth() {
        val small = layoutFixture("<input id='x' size='8'>").box("x").borderBox.width
        val large = layoutFixture("<input id='x' size='30'>").box("x").borderBox.width
        assertTrue(large > small)
    }

    @Test fun selectUsesLongestOptionForIntrinsicWidth() {
        val short = layoutFixture("<select id='x'><option>A</option></select>").box("x").borderBox.width
        val long = layoutFixture("<select id='x'><option>A much longer option</option></select>").box("x").borderBox.width
        assertTrue(long > short)
    }

    @Test fun objectPositionParsesKeywordAndPercentageAxes() {
        val position = PaintValueParser.imagePosition("right 25%")
        assertEquals(1.0, position.xFraction)
        assertEquals(0.25, position.yFraction)
    }
}
