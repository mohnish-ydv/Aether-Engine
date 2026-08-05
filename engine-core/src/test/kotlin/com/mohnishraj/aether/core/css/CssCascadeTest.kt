package com.mohnishraj.aether.core.css

import com.mohnishraj.aether.core.html.HtmlEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CssCascadeTest {
    private val html = HtmlEngine()
    private val css = CssEngine()

    @Test fun laterRuleWinsEqualSpecificity() {
        val doc = html.parse("<p>A</p>").document
        val style = css.compute(doc, listOf(css.parse("p{color:red} p{color:blue}"))).styleFor(doc.getElementsByTagName("p").single())!!
        assertEquals("blue", style["color"])
    }

    @Test fun idWinsClassAndType() {
        val doc = html.parse("<p id='x' class='c'>A</p>").document
        val style = css.compute(doc, listOf(css.parse("p{color:a}.c{color:b}#x{color:c}"))).styleFor(doc.getElementById("x")!!)!!
        assertEquals("c", style["color"])
    }

    @Test fun importantWinsInlineNormal() {
        val doc = html.parse("<p id='x' style='color:purple'>A</p>").document
        val style = css.compute(doc, listOf(css.parse("#x{color:red!important}"))).styleFor(doc.getElementById("x")!!)!!
        assertEquals("red", style["color"])
    }

    @Test fun inlineImportantWinsAuthorImportant() {
        val doc = html.parse("<p id='x' style='color:purple!important'>A</p>").document
        val style = css.compute(doc, listOf(css.parse("#x{color:red!important}"))).styleFor(doc.getElementById("x")!!)!!
        assertEquals("purple", style["color"])
    }

    @Test fun inheritedAndNonInheritedPropertiesDiffer() {
        val doc = html.parse("<main><p>A</p></main>").document
        val p = doc.getElementsByTagName("p").single()
        val style = css.compute(doc, listOf(css.parse("main{color:navy;background-color:red}"))).styleFor(p)!!
        assertEquals("navy", style["color"])
        assertEquals("transparent", style["background-color"])
    }

    @Test fun cssWideKeywordsWork() {
        val doc = html.parse("<main><p>A</p><span>B</span></main>").document
        val tree = css.compute(doc, listOf(css.parse("main{color:navy} p{color:inherit;display:initial} span{color:unset}")))
        assertEquals("navy", tree.styleFor(doc.getElementsByTagName("p").single())?.get("color"))
        assertEquals("block", tree.styleFor(doc.getElementsByTagName("p").single())?.get("display"))
        assertEquals("navy", tree.styleFor(doc.getElementsByTagName("span").single())?.get("color"))
    }

    @Test fun customPropertiesInheritAndFallback() {
        val doc = html.parse("<main><p>A</p></main>").document
        val p = doc.getElementsByTagName("p").single()
        val style = css.compute(doc, listOf(css.parse("main{--x:teal} p{color:var(--x);outline-color:var(--missing,orange)}"))).styleFor(p)!!
        assertEquals("teal", style["color"])
        assertEquals("orange", style["outline-color"])
    }

    @Test fun variableCycleDoesNotCrash() {
        val doc = html.parse("<p>A</p>").document
        val tree = css.compute(doc, listOf(css.parse("p{--a:var(--b);--b:var(--a);color:var(--a,red)}")))
        assertNotNull(tree.styleFor(doc.getElementsByTagName("p").single()))
        assertTrue(tree.issues.any { it.code == "variable-cycle" })
    }

    @Test fun currentColorResolves() {
        val doc = html.parse("<p>A</p>").document
        val style = css.compute(doc, listOf(css.parse("p{color:green;border-color:currentColor}"))).styleFor(doc.getElementsByTagName("p").single())!!
        assertEquals("green", style["border-color"])
    }


    @Test fun compatibilityAliasesNormalizeLogicalAndLegacyFlexProperties() {
        val doc = html.parse("<div id='x'></div>").document
        val style = css.compute(doc, listOf(css.parse("#x{display:-webkit-box;inline-size:80%;padding-inline:12px 20px;inset:1px 2px 3px 4px;place-items:center end;-webkit-box-pack:center}")))
            .styleFor(doc.getElementById("x")!!)!!
        assertEquals("flex", style["display"])
        assertEquals("80%", style["width"])
        assertEquals("12px", style["padding-left"])
        assertEquals("20px", style["padding-right"])
        assertEquals("1px", style["top"])
        assertEquals("2px", style["right"])
        assertEquals("3px", style["bottom"])
        assertEquals("4px", style["left"])
        assertEquals("center", style["align-items"])
        assertEquals("center", style["justify-content"])
    }

    @Test fun hiddenAttributeAndHiddenInputAreRemovedFromLayoutCascade() {
        val doc = html.parse("<div hidden>A</div><input type='hidden' value='secret'><span>B</span>").document
        val tree = css.compute(doc, emptyList())
        assertEquals("none", tree.styleFor(doc.getElementsByTagName("div").single())?.get("display"))
        assertEquals("none", tree.styleFor(doc.getElementsByTagName("input").single())?.get("display"))
        assertEquals("inline", tree.styleFor(doc.getElementsByTagName("span").single())?.get("display"))
    }

    @Test fun defaultDisplayIsElementAware() {
        val doc = html.parse("<div><span>A</span><table><tr><td>X</td></tr></table></div>").document
        val tree = css.compute(doc, emptyList())
        assertEquals("block", tree.styleFor(doc.getElementsByTagName("div").single())?.get("display"))
        assertEquals("inline", tree.styleFor(doc.getElementsByTagName("span").single())?.get("display"))
        assertEquals("table", tree.styleFor(doc.getElementsByTagName("table").single())?.get("display"))
        assertEquals("table-cell", tree.styleFor(doc.getElementsByTagName("td").single())?.get("display"))
    }
}
