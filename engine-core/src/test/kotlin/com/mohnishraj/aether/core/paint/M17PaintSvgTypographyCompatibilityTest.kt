package com.mohnishraj.aether.core.paint

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class M17PaintSvgTypographyCompatibilityTest {
    @Test fun displayListBeginsWithOpaqueCanvasColor() {
        val list = paintFixture("<p>A</p>").displayList
        val first = list.commands.first() as PaintCommand.FillRect
        assertEquals(PaintColor.WHITE, first.color)
    }

    @Test fun bodyBackgroundPropagatesToViewportCanvas() {
        val list = paintFixture(
            "<html><body><p>A</p></body></html>",
            "html{background:transparent}body{background-color:#123456}"
        ).displayList
        assertTrue(list.commands.filterIsInstance<PaintCommand.FillRect>().any {
            it.rect.width == 360.0 && it.color == PaintColor(0x12, 0x34, 0x56)
        })
    }

    @Test fun controlChromePaintsBeforeNestedSvg() {
        val fixture = paintFixture(
            "<button id='b'><svg viewBox='0 0 10 10'><path d='M0 0L10 10'/></svg></button>",
            "button{width:44px;height:44px}svg{width:20px;height:20px}"
        )
        val commands = fixture.displayList.commands
        val chrome = commands.indexOfFirst { it is PaintCommand.FillRoundedRect }
        val image = commands.indexOfFirst { it is PaintCommand.DrawImage }
        assertTrue(chrome >= 0 && image > chrome)
    }

    @Test fun inlineSvgCurrentColorIsMaterializedForNativePainter() {
        val list = paintFixture(
            "<svg viewBox='0 0 10 10' style='color:red'><path fill='currentColor' d='M0 0L10 0L10 10Z'/></svg>",
            "svg{width:20px;height:20px}"
        ).displayList
        val image = list.commands.filterIsInstance<PaintCommand.DrawImage>().single()
        assertTrue(image.source.contains("#ff0000", ignoreCase = true))
    }

    @Test fun orderedListProducesNumericMarkerCommands() {
        val fixture = paintFixture("<ol><li>One</li><li>Two</li></ol>", "li{display:list-item}")
        assertTrue(fixture.displayList.commands.filterIsInstance<PaintCommand.DrawText>().any { it.text == "2." })
    }

    @Test fun imageCommandCarriesObjectPosition() {
        val list = paintFixture(
            "<img src='asset://sample.png' alt='sample'>",
            "img{display:block;width:100px;height:60px;object-fit:cover;object-position:right bottom}"
        ).displayList
        val image = list.commands.filterIsInstance<PaintCommand.DrawImage>().single()
        assertEquals(ImageFit.COVER, image.fit)
        assertEquals(1.0, image.position.xFraction)
        assertEquals(1.0, image.position.yFraction)
    }
}
