package com.mohnishraj.aether.core.html

import com.mohnishraj.aether.core.html.dom.HtmlNamespace
import com.mohnishraj.aether.core.html.dom.QuirksMode
import com.mohnishraj.aether.core.html.inspect.DomInspector
import com.mohnishraj.aether.core.html.inspect.HtmlSerializer
import com.mohnishraj.aether.core.html.parser.HtmlParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HtmlParserTest {
    private val parser = HtmlParser()

    @Test fun createsEmptyDocumentSkeleton() {
        val document = parser.parse("").document
        assertNotNull(document.documentElement)
        assertNotNull(document.head)
        assertNotNull(document.body)
    }

    @Test fun routesMetadataToHead() {
        val document = parser.parse("<meta charset='utf-8'><title>Hello</title><main>Body</main>").document
        assertEquals(1, document.head?.getElementsByTagName("meta")?.size)
        assertEquals("Hello", document.head?.getElementsByTagName("title")?.single()?.textContent)
        assertEquals("Body", document.body?.textContent)
    }

    @Test fun mergesAttributesOnExplicitHtmlAndBody() {
        val document = parser.parse("<html lang='en'><body class='app'>x</body></html>").document
        assertEquals("en", document.documentElement?.getAttribute("lang"))
        assertEquals("app", document.body?.getAttribute("class"))
    }

    @Test fun ignoresDuplicateHeadTag() {
        val result = parser.parse("<head><title>A</title></head><head><meta name='x'></head>")
        assertEquals(1, result.document.getElementsByTagName("head").size)
        assertTrue(result.issues.any { it.code == "duplicate-head" })
    }

    @Test fun closesParagraphBeforeBlock() {
        val document = parser.parse("<p>one<div>two</div>three").document
        assertEquals("one", document.getElementsByTagName("p").single().textContent)
        assertEquals("two", document.getElementsByTagName("div").single().textContent)
        assertEquals("onetwothree", document.body?.textContent)
    }

    @Test fun closesListItemsImplicitly() {
        val items = parser.parse("<ul><li>one<li>two<li>three</ul>").document.getElementsByTagName("li")
        assertEquals(listOf("one", "two", "three"), items.map { it.textContent })
    }

    @Test fun closesDefinitionTermsImplicitly() {
        val document = parser.parse("<dl><dt>A<dd>B<dt>C</dl>").document
        assertEquals(listOf("A", "C"), document.getElementsByTagName("dt").map { it.textContent })
        assertEquals(listOf("B"), document.getElementsByTagName("dd").map { it.textContent })
    }

    @Test fun preventsNestedAnchorAndButton() {
        val result = parser.parse("<a>one<a>two</a><button>x<button>y</button>")
        assertEquals(2, result.document.getElementsByTagName("a").size)
        assertEquals(2, result.document.getElementsByTagName("button").size)
        assertTrue(result.issues.any { it.code == "nested-anchor" })
        assertTrue(result.issues.any { it.code == "nested-button" })
    }

    @Test fun ignoresNestedForm() {
        val result = parser.parse("<form id='a'><form id='b'>x</form></form>")
        assertEquals(1, result.document.getElementsByTagName("form").size)
        assertEquals("a", result.document.getElementsByTagName("form").single().id)
        assertTrue(result.issues.any { it.code == "nested-form" })
    }

    @Test fun synthesizesTableContainers() {
        val document = parser.parse("<table><td>A<td>B</table>").document
        assertEquals(1, document.getElementsByTagName("tbody").size)
        assertEquals(1, document.getElementsByTagName("tr").size)
        assertEquals(listOf("A", "B"), document.getElementsByTagName("td").map { it.textContent })
    }

    @Test fun wrapsOrphanRowInTable() {
        val result = parser.parse("<tr><td>A</td></tr>")
        assertEquals(1, result.document.getElementsByTagName("table").size)
        assertTrue(result.issues.any { it.code == "row-outside-table" })
    }

    @Test fun closesOptionsImplicitly() {
        val options = parser.parse("<select><option>A<option>B</select>").document.getElementsByTagName("option")
        assertEquals(listOf("A", "B"), options.map { it.textContent })
    }

    @Test fun preservesSvgAndMathNamespaces() {
        val document = parser.parse("<svg><g><circle></circle></g></svg><math><mi>x</mi></math>").document
        assertEquals(HtmlNamespace.SVG, document.getElementsByTagName("circle").single().namespace)
        assertEquals(HtmlNamespace.MATHML, document.getElementsByTagName("mi").single().namespace)
    }

    @Test fun foreignObjectReturnsToHtmlNamespace() {
        val document = parser.parse("<svg><foreignObject><div>x</div></foreignObject></svg>").document
        assertEquals(HtmlNamespace.HTML, document.getElementsByTagName("div").single().namespace)
    }

    @Test fun standardDoctypeUsesNoQuirks() {
        assertEquals(QuirksMode.NO_QUIRKS, parser.parse("<!doctype html><p>x").document.quirksMode)
    }

    @Test fun missingDoctypeUsesQuirks() {
        assertEquals(QuirksMode.QUIRKS, parser.parse("<p>x").document.quirksMode)
    }

    @Test fun xhtmlTransitionalUsesLimitedQuirksWithSystemId() {
        val html = "<!DOCTYPE html PUBLIC '-//W3C//DTD XHTML 1.0 Transitional//EN' 'http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd'>"
        assertEquals(QuirksMode.LIMITED_QUIRKS, parser.parse(html).document.quirksMode)
    }

    @Test fun strayEndTagIsReportedNotFatal() {
        val result = parser.parse("<div>x</div></ghost><p>y")
        assertEquals("xy", result.document.body?.textContent)
        assertTrue(result.issues.any { it.code == "stray-end-tag" })
    }

    @Test fun selfClosingNonVoidIsHonoredAndReported() {
        val result = parser.parse("<custom/><p>x</p>")
        assertEquals(1, result.document.getElementsByTagName("custom").size)
        assertNull(result.document.getElementsByTagName("custom").single().firstChild)
        assertTrue(result.issues.any { it.code == "self-closing-non-void" })
    }

    @Test fun parserRetainsDocumentUrl() {
        assertEquals("https://example.test/", parser.parse("<p>x", "https://example.test/").document.url)
    }

    @Test fun inspectorSummaryMatchesTree() {
        val document = parser.parse("<!doctype html><!--c--><main><h1>A</h1><p>B</p></main>").document
        val summary = DomInspector.summarize(document)
        assertEquals(1, summary.comments)
        assertEquals(1, summary.doctypes)
        assertTrue(summary.elements >= 6)
        assertTrue("<main>" in DomInspector.tree(document))
    }

    @Test fun serializerProducesEscapedNormalizedMarkup() {
        val document = parser.parse("<p title='a&b'>A &amp; B</p>").document
        val serialized = HtmlSerializer.serialize(document)
        assertTrue("title=\"a&amp;b\"" in serialized)
        assertTrue("A &amp; B" in serialized)
    }

    @Test fun inputLimitReturnsErrorIssue() {
        val result = HtmlParser(HtmlLimits(maxInputChars = 3)).parse("1234")
        assertTrue(result.issues.any { it.code == "input-too-large" && it.severity == HtmlIssueSeverity.ERROR })
    }

    @Test fun depthLimitReturnsErrorIssue() {
        val result = HtmlParser(HtmlLimits(maxDepth = 3)).parse("<div><div><div><div>x</div></div></div></div>")
        assertTrue(result.issues.any { it.code == "depth-limit" && it.stage == HtmlIssueStage.LIMIT })
    }
}
