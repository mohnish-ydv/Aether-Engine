package com.mohnishraj.aether.core.css

import com.mohnishraj.aether.core.css.selector.CssSelectorParser
import com.mohnishraj.aether.core.html.HtmlEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CssSelectorTest {
    private val html = HtmlEngine()
    private val parser = CssSelectorParser()

    @Test fun computesSpecificity() {
        val value = parser.parseList("article#app.card[data-x]:first-child > p").single().specificity
        assertEquals("1,3,2", value.toString())
    }

    @Test fun matchesDescendantAndChildCombinators() {
        val doc = html.parse("<main id='a'><section><p class='x'>A</p></section></main>").document
        val p = doc.getElementsByTagName("p").single()
        assertTrue(parser.parseList("main#a section > p.x").single().matches(p))
        assertFalse(parser.parseList("main#a > p.x").single().matches(p))
    }

    @Test fun matchesSiblingCombinators() {
        val doc = html.parse("<div><h1>A</h1><p id='one'>B</p><p id='two'>C</p></div>").document
        val one = doc.getElementById("one")!!
        val two = doc.getElementById("two")!!
        assertTrue(parser.parseList("h1 + p").single().matches(one))
        assertTrue(parser.parseList("h1 ~ p#two").single().matches(two))
    }

    @Test fun matchesAttributeOperators() {
        val doc = html.parse("<p id='x' data-tags='one two' lang='en-US' title='prefix-middle-suffix'>A</p>").document
        val p = doc.getElementById("x")!!
        assertTrue(parser.parseList("[data-tags~='two'][lang|='en'][title^='prefix'][title$='suffix'][title*='middle']").single().matches(p))
    }

    @Test fun matchesCaseInsensitiveAttribute() {
        val element = html.parse("<p data-x='HELLO'>A</p>").document.getElementsByTagName("p").single()
        assertTrue(parser.parseList("[data-x='hello' i]").single().matches(element))
    }

    @Test fun matchesStructuralPseudoClasses() {
        val doc = html.parse("<ul><li id='a'></li><li id='b'>x</li><li id='c'></li></ul>").document
        assertTrue(parser.parseList("li:first-child:empty").single().matches(doc.getElementById("a")!!))
        assertTrue(parser.parseList("li:last-child").single().matches(doc.getElementById("c")!!))
        assertTrue(parser.parseList("li:nth-child(2)").single().matches(doc.getElementById("b")!!))
    }

    @Test fun matchesNthExpressions() {
        val doc = html.parse("<ol><li id='a'><li id='b'><li id='c'><li id='d'></ol>").document
        assertTrue(parser.parseList("li:nth-child(odd)").single().matches(doc.getElementById("c")!!))
        assertTrue(parser.parseList("li:nth-child(2n)").single().matches(doc.getElementById("d")!!))
        assertFalse(parser.parseList("li:nth-child(2n+1)").single().matches(doc.getElementById("d")!!))
    }

    @Test fun matchesIsNotAndWhere() {
        val p = html.parse("<p class='note'>A</p>").document.getElementsByTagName("p").single()
        assertTrue(parser.parseList("p:is(.note, .other):not(.blocked)").single().matches(p))
        assertEquals("0,0,1", parser.parseList("p:where(.note, #strong)").single().specificity.toString())
    }

    @Test fun invalidSelectorProducesIssue() {
        val issues = mutableListOf<CssIssue>()
        assertTrue(parser.parseList("div[", issues).isEmpty())
        assertTrue(issues.any { it.code == "invalid-selector" })
    }
}
