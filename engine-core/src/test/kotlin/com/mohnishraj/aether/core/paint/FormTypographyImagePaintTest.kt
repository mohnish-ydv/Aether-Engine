package com.mohnishraj.aether.core.paint

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FormTypographyImagePaintTest {
    @Test fun formControlsPaintValuesPlaceholdersTogglesAndDisabledStates() {
        val list = paintFixture(
            """<form>
                <input id='name' value='Mohnish'>
                <input id='empty' placeholder='Your city'>
                <input id='check' type='checkbox' checked>
                <input id='radio' type='radio' checked>
                <textarea id='notes'>Aether notes</textarea>
                <select id='choice'><option>One</option><option selected>Two</option></select>
                <button id='go' disabled>Continue</button>
            </form>""".trimIndent(),
            "input, textarea, select, button { display:block; width:220px; margin:4px; height:36px }"
        ).displayList
        val texts = list.commands.filterIsInstance<PaintCommand.DrawText>()

        assertTrue(texts.any { it.text == "Mohnish" })
        assertTrue(texts.any { it.text == "Your city" && it.color != PaintColor.BLACK })
        assertTrue(texts.any { it.text == "✓" })
        assertTrue(texts.any { it.text == "Aether notes" })
        assertTrue(texts.any { it.text == "Two" })
        assertTrue(texts.any { it.text == "Continue" && it.opacity < 1.0 })
        assertTrue(list.commands.count { it is PaintCommand.DrawBorder } >= 7)
    }

    @Test fun typographyPropertiesReachNativeDisplayList() {
        val list = paintFixture(
            "<p id='copy'>Decorated words</p>",
            "#copy { font-size:20px; letter-spacing:2px; word-spacing:5px; text-decoration:underline line-through overline }"
        ).displayList
        val command = list.commands.filterIsInstance<PaintCommand.DrawText>().first { "Decorated" in it.text }
        assertEquals(2.0, command.letterSpacingPx)
        assertEquals(5.0, command.wordSpacingPx)
        assertTrue("underline" in command.textDecoration)
        assertTrue("line-through" in command.textDecoration)
        assertTrue("overline" in command.textDecoration)
    }


    @Test fun inlineSvgAndSrcsetProduceImageCommands() {
        val svg = paintFixture(
            "<svg id='logo' viewBox='0 0 100 40' width='100' height='40'><path fill='#4285f4' d='M0 0H100V40H0Z'/></svg>",
            "svg { display:inline-block }"
        ).displayList.commands.filterIsInstance<PaintCommand.DrawImage>().single()
        assertTrue(svg.source.startsWith("<svg"))
        assertEquals(100.0, svg.destination.width)
        assertEquals(40.0, svg.destination.height)

        val responsive = paintFixture(
            "<img srcset='small.webp 1x, large.webp 2x' alt='Responsive'>",
            "img { display:block; width:120px; height:60px }",
            documentUrl = "https://example.test/home/"
        ).displayList.commands.filterIsInstance<PaintCommand.DrawImage>().single()
        assertEquals("https://example.test/home/small.webp", responsive.source)
    }

    @Test fun imageCommandCarriesObjectFitLazyAndAlternativeText() {
        val list = paintFixture(
            "<img id='hero' src='data:image/png;base64,AA==' alt='Hero image' loading='lazy'>",
            "#hero { display:block; width:200px; height:100px; object-fit:cover }"
        ).displayList
        val image = list.commands.filterIsInstance<PaintCommand.DrawImage>().single()
        assertEquals(ImageFit.COVER, image.fit)
        assertEquals("Hero image", image.altText)
        assertTrue(image.lazy)
        assertEquals(200.0, image.destination.width)
        assertEquals(100.0, image.destination.height)

        val relative = paintFixture(
            "<img src='../media/hero.webp'>",
            "img { display:block; width:40px; height:20px }",
            documentUrl = "https://example.test/articles/read/index.html"
        ).displayList.commands.filterIsInstance<PaintCommand.DrawImage>().single()
        assertEquals("https://example.test/articles/media/hero.webp", relative.source)
    }
}
