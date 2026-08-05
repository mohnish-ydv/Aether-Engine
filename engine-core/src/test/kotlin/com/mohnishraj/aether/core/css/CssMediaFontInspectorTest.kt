package com.mohnishraj.aether.core.css

import com.mohnishraj.aether.core.css.cascade.MediaQueryEvaluator
import com.mohnishraj.aether.core.css.inspect.CssInspector
import com.mohnishraj.aether.core.html.HtmlEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CssMediaFontInspectorTest {
    private val html = HtmlEngine()
    private val css = CssEngine()

    @Test fun mediaTypeAndViewportMatch() {
        val env = MediaEnvironment(800.0, 600.0, mediaType = "screen")
        assertTrue(MediaQueryEvaluator.matches("screen and (min-width: 40rem)", env))
        assertFalse(MediaQueryEvaluator.matches("print and (min-width: 1px)", env))
    }

    @Test fun mediaQueryListUsesOrSemantics() {
        val env = MediaEnvironment(320.0, 800.0)
        assertTrue(MediaQueryEvaluator.matches("(min-width: 1000px), (orientation: portrait)", env))
    }

    @Test fun notMediaQueryInverts() {
        assertTrue(MediaQueryEvaluator.matches("not print", MediaEnvironment(mediaType = "screen")))
    }

    @Test fun darkModeRuleActivates() {
        val doc = html.parse("<body><p>A</p></body>").document
        val p = doc.getElementsByTagName("p").single()
        val sheet = css.parse("p{color:black}@media(prefers-color-scheme:dark){p{color:white}}")
        val dark = css.compute(doc, listOf(sheet), MediaEnvironment(colorScheme = ColorScheme.DARK)).styleFor(p)!!
        val light = css.compute(doc, listOf(sheet), MediaEnvironment(colorScheme = ColorScheme.LIGHT)).styleFor(p)!!
        assertEquals("white", dark["color"])
        assertEquals("black", light["color"])
    }

    @Test fun supportsSimpleDeclaration() {
        val doc = html.parse("<main>A</main>").document
        val main = doc.getElementsByTagName("main").single()
        val sheet = css.parse("@supports (display: grid){main{display:grid}}")
        assertEquals("grid", css.compute(doc, listOf(sheet)).styleFor(main)?.get("display"))
    }

    @Test fun collectsFontFaceMetadata() {
        val doc = html.parse("<p>A</p>").document
        val sheet = css.parse("@font-face{font-family:'Aether Sans';src:url(a.woff2);font-weight:700;font-style:italic;font-display:swap}")
        val face = css.compute(doc, listOf(sheet)).fontFaces.single()
        assertEquals("Aether Sans", face.family)
        assertEquals("700", face.weight)
        assertEquals("italic", face.style)
        assertEquals("swap", face.display)
    }

    @Test fun invalidFontFaceReportsIssue() {
        val doc = html.parse("<p>A</p>").document
        val tree = css.compute(doc, listOf(css.parse("@font-face{src:url(x)}")))
        assertTrue(tree.issues.any { it.code == "font-face-family" })
    }

    @Test fun stylesheetInspectorShowsStructure() {
        val rendered = CssInspector.styleSheet(css.parse("a{color:red}@media(min-width:1px){b{display:block}}"))
        assertTrue("a" in rendered && "@media" in rendered && "display: block" in rendered)
    }

    @Test fun computedInspectorShowsElementAndStyle() {
        val doc = html.parse("<main id='app'><p>A</p></main>").document
        val sheet = css.parse("#app{color:navy}")
        val rendered = CssInspector.computedTree(doc, css.compute(doc, listOf(sheet)))
        assertTrue("<main#app>" in rendered)
        assertTrue("color: navy" in rendered)
    }
}
