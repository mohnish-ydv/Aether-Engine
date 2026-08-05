package com.mohnishraj.aether.core.shell

import com.mohnishraj.aether.core.css.CssEngine
import com.mohnishraj.aether.core.css.MediaEnvironment
import com.mohnishraj.aether.core.html.HtmlEngine
import com.mohnishraj.aether.core.layout.LayoutEngine
import com.mohnishraj.aether.core.layout.LayoutViewport
import com.mohnishraj.aether.core.paint.PaintCommand
import com.mohnishraj.aether.core.paint.PaintEngine
import kotlin.test.Test
import kotlin.test.assertTrue

class M18RealWorldCompatibilityTest {
    private fun render(width: Double): Triple<com.mohnishraj.aether.core.html.dom.DocumentNode, com.mohnishraj.aether.core.layout.LayoutTree, com.mohnishraj.aether.core.paint.DisplayList> {
        val document = HtmlEngine().parse(
            "<main><form id='f'><input id='q'><button id='b'><svg viewBox='0 0 24 24'><path d='M1 1L23 23'/></svg></button></form></main>"
        ).document
        val css = CssEngine()
        val styles = css.compute(document, listOf(css.parse(
            "html,body{margin:0}main{display:flex;justify-content:center}form{display:flex;width:min(90vw,584px);height:48px}input{flex:1 1 auto;min-width:0}button{flex:0 0 48px}svg{width:20px;height:20px}"
        )), MediaEnvironment(width, 800.0))
        val tree = LayoutEngine().layout(document, styles, LayoutViewport(width, 800.0))
        return Triple(document, tree, PaintEngine().paint(tree))
    }

    @Test fun googleStyleSearchFormFits320PxViewport() {
        val (_, tree, _) = render(320.0)
        assertTrue(tree.root.scrollSize.width <= 320.01)
    }

    @Test fun googleStyleSearchFormFits360PxViewport() {
        val (_, tree, _) = render(360.0)
        assertTrue(tree.root.scrollSize.width <= 360.01)
    }

    @Test fun googleStyleSearchFormFits412PxViewport() {
        val (_, tree, _) = render(412.0)
        assertTrue(tree.root.scrollSize.width <= 412.01)
    }

    @Test fun searchButtonRemainsInsideViewport() {
        val (document, tree, _) = render(360.0)
        val button = tree.boxFor(document.getElementById("b")!!)!!
        assertTrue(button.borderBox.right <= 360.01)
    }

    @Test fun nestedSvgPaintsAfterButtonChrome() {
        val (_, _, list) = render(360.0)
        val chrome = list.commands.indexOfFirst { it is PaintCommand.FillRoundedRect }
        val image = list.commands.indexOfFirst { it is PaintCommand.DrawImage }
        assertTrue(chrome >= 0 && image > chrome)
    }

    @Test fun realWorldFixtureProducesNoPaintOrLayoutErrors() {
        val (_, tree, list) = render(360.0)
        assertTrue(tree.issues.none { it.severity.name == "ERROR" })
        assertTrue(list.issues.none { it.severity.name == "ERROR" })
    }
}
