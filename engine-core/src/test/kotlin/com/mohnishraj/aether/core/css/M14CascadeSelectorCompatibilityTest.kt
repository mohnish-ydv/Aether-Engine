package com.mohnishraj.aether.core.css

import com.mohnishraj.aether.core.css.selector.CssSelectorParser
import com.mohnishraj.aether.core.html.HtmlEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class M14CascadeSelectorCompatibilityTest {
    private val html = HtmlEngine()
    private val css = CssEngine()
    private val selectors = CssSelectorParser()

    @Test fun authorOriginOverridesUserAgentOrigin() {
        val doc = html.parse("<p id='x'>A</p>").document
        val tree = css.compute(doc, listOf(
            css.parse("#x{color:red}", origin = CssOrigin.USER_AGENT),
            css.parse("p{color:blue}", origin = CssOrigin.AUTHOR)
        ))
        assertEquals("blue", tree.styleFor(doc.getElementById("x")!!)!!["color"])
    }

    @Test fun laterShorthandOverridesEarlierLonghand() {
        val doc = html.parse("<div id='x'></div>").document
        val style = css.compute(doc, listOf(css.parse("#x{margin-left:3px;margin:20px}")))
            .styleFor(doc.getElementById("x")!!)!!
        assertEquals("20px", style["margin-left"])
    }

    @Test fun laterLonghandOverridesEarlierShorthand() {
        val doc = html.parse("<div id='x'></div>").document
        val style = css.compute(doc, listOf(css.parse("#x{margin:20px;margin-left:3px}")))
            .styleFor(doc.getElementById("x")!!)!!
        assertEquals("3px", style["margin-left"])
    }

    @Test fun hasMatchesRelativeChildSelector() {
        val doc = html.parse("<main id='m'><span class='ok'></span></main>").document
        assertTrue(selectors.parseList("main:has(> .ok)").single().matches(doc.getElementById("m")!!))
    }

    @Test fun nthOfTypeIgnoresOtherElementNames() {
        val doc = html.parse("<div><i></i><b></b><i id='x'></i></div>").document
        assertTrue(selectors.parseList("i:nth-of-type(2)").single().matches(doc.getElementById("x")!!))
    }

    @Test fun formStatePseudoClassesMatchSemanticState() {
        val element = html.parse("<input id='x' required readonly value='ok'>").document.getElementById("x")!!
        assertTrue(selectors.parseList("input:required:read-only:valid").single().matches(element))
        assertFalse(selectors.parseList("input:optional:read-write").single().matches(element))
    }

    @Test fun whereContributesZeroSpecificityWhileIsContributesArgumentSpecificity() {
        assertEquals("0,0,1", selectors.parseList("p:where(#x,.a)").single().specificity.toString())
        assertEquals("1,0,1", selectors.parseList("p:is(#x,.a)").single().specificity.toString())
    }

    @Test fun pseudoElementDoesNotMatchOriginatingDomElement() {
        val element = html.parse("<p>A</p>").document.getElementsByTagName("p").single()
        assertFalse(selectors.parseList("p::before").single().matches(element))
    }
}
