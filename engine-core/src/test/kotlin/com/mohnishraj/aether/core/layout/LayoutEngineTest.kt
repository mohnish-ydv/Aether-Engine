package com.mohnishraj.aether.core.layout

import com.mohnishraj.aether.core.layout.inspect.LayoutInspector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LayoutEngineTest {
    @Test fun emptyDocumentProducesViewportAndIssueInsteadOfCrash() {
        val document = com.mohnishraj.aether.core.html.dom.DocumentNode()
        val styles = com.mohnishraj.aether.core.css.StyleTree::class.java
        val cssTree = com.mohnishraj.aether.core.css.CssEngine().compute(document, emptyList())
        val tree = LayoutEngine().layout(document, cssTree)
        assertEquals(LayoutBoxKind.VIEWPORT, tree.root.kind)
        assertTrue(tree.issues.isNotEmpty())
        assertNotNull(styles)
    }

    @Test fun statisticsAccumulateAcrossLayouts() {
        val fixture = layoutFixture("<main><p>A</p></main>")
        fixture.engine.layout(fixture.document, fixture.styles, LayoutViewport())
        val stats = fixture.engine.statistics()
        assertEquals(2L, stats.layoutsCompleted)
        assertTrue(stats.boxesProduced > 0)
    }

    @Test fun inspectorSummaryMatchesTreeCounts() {
        val fixture = layoutFixture("<main><p>Aether</p><p>Engine</p></main>")
        val summary = LayoutInspector.summarize(fixture.tree)
        assertEquals(fixture.tree.boxCount, summary.boxes)
        assertEquals(fixture.tree.lineBoxCount, summary.lineBoxes)
        assertEquals(fixture.tree.inlineFragmentCount, summary.fragments)
    }

    @Test fun inspectorTreeContainsGeometryAndTextFragments() {
        val output = LayoutInspector.tree(layoutFixture("<main><p>Hello</p></main>").tree)
        assertTrue("AETHER LAYOUT TREE" in output)
        assertTrue("border=" in output)
        assertTrue("text#" in output)
    }

    @Test fun paintOrderContainsEveryBoxExactlyOnce() {
        val tree = layoutFixture("<main><div></div><p>A</p></main>").tree
        assertEquals(tree.boxCount, tree.paintOrder.distinct().size)
    }

    @Test fun mediaQueryAndLayoutRespondTogether() {
        val html = com.mohnishraj.aether.core.html.HtmlEngine()
        val css = com.mohnishraj.aether.core.css.CssEngine()
        val document = html.parse("<main id='x'></main>").document
        val sheet = css.parse("#x { width:100px } @media (min-width:600px) { #x { width:400px } }")
        val smallStyles = css.compute(document, listOf(sheet), com.mohnishraj.aether.core.css.MediaEnvironment(360.0, 800.0))
        val wideStyles = css.compute(document, listOf(sheet), com.mohnishraj.aether.core.css.MediaEnvironment(800.0, 800.0))
        val engine = LayoutEngine()
        val element = document.getElementById("x")!!
        assertEquals(100.0, engine.layout(document, smallStyles, LayoutViewport(360.0, 800.0)).boxFor(element)!!.borderBox.width)
        assertEquals(400.0, engine.layout(document, wideStyles, LayoutViewport(800.0, 800.0)).boxFor(element)!!.borderBox.width)
    }

    @Test fun noWebViewClassIsRequiredByLayoutEngine() {
        val names = LayoutEngine::class.java.declaredMethods.map { it.returnType.name } + LayoutEngine::class.java.declaredFields.map { it.type.name }
        assertTrue(names.none { "WebView" in it })
    }
}
