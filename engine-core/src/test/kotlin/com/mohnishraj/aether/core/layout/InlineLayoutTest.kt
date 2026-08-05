package com.mohnishraj.aether.core.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InlineLayoutTest {
    @Test fun textCreatesLineAndFragments() {
        val fixture = layoutFixture("<p id='p'>Hello Aether</p>")
        val box = fixture.box("p")
        assertTrue(box.lineBoxes.isNotEmpty())
        assertTrue(box.lineBoxes.flatMap { it.fragments }.isNotEmpty())
    }

    @Test fun narrowWidthWrapsText() {
        val fixture = layoutFixture("<p id='p'>Aether engine wraps deterministic text across lines.</p>", "#p { width:90px }")
        assertTrue(fixture.box("p").lineBoxes.size >= 2)
    }

    @Test fun normalWhitespaceCollapsesRuns() {
        val fixture = layoutFixture("<p id='p'>A   B\n C</p>", "#p { white-space:normal }")
        val value = fixture.box("p").lineBoxes.flatMap { it.fragments }.joinToString("") { it.text }
        assertEquals("A B C", value.trim())
    }

    @Test fun preWhitespacePreservesNewlines() {
        val fixture = layoutFixture("<p id='p'>A  B\nC</p>", "#p { white-space:pre }")
        val box = fixture.box("p")
        assertEquals(2, box.lineBoxes.size)
        assertTrue(box.lineBoxes.first().fragments.joinToString("") { it.text }.contains("A  B"))
    }

    @Test fun brForcesLineBreak() {
        val fixture = layoutFixture("<p id='p'>A<br>B</p>")
        assertEquals(2, fixture.box("p").lineBoxes.size)
    }

    @Test fun nowrapKeepsTextOnOneLine() {
        val fixture = layoutFixture("<p id='p'>Long text that exceeds the small width</p>", "#p { width:50px; white-space:nowrap }")
        assertEquals(1, fixture.box("p").lineBoxes.size)
    }

    @Test fun centerAlignmentOffsetsLineWithinContentBox() {
        val fixture = layoutFixture("<p id='p'>Hi</p>", "#p { width:200px; text-align:center }")
        val box = fixture.box("p")
        assertTrue(box.lineBoxes.single().rect.x > box.contentBox.x)
    }

    @Test fun rightAlignmentOffsetsLineToRightEdge() {
        val fixture = layoutFixture("<p id='p'>Hi</p>", "#p { width:200px; text-align:right }")
        val box = fixture.box("p")
        val line = box.lineBoxes.single()
        assertTrue(kotlin.math.abs(line.rect.right - box.contentBox.right) < 0.001)
    }

    @Test fun longUnbrokenWordSplitsWithoutOverflowingLine() {
        val fixture = layoutFixture("<p id='p'>supercalifragilisticexpialidocious</p>", "#p { width:60px }")
        val box = fixture.box("p")
        assertTrue(box.lineBoxes.size > 1)
        assertTrue(box.lineBoxes.all { it.rect.width <= box.contentBox.width + 0.001 })
    }

    @Test fun inlineBlockParticipatesInLineFlow() {
        val fixture = layoutFixture("<p id='p'>A <span id='chip'>chip</span> B</p>", "#chip { display:inline-block; width:40px; height:20px; padding:2px }")
        val chip = fixture.box("chip")
        assertEquals(LayoutBoxKind.INLINE_BLOCK, chip.kind)
        assertTrue(fixture.box("p").children.contains(chip))
    }
}
