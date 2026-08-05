package com.mohnishraj.aether.core.layout.selftest

import com.mohnishraj.aether.core.css.CssEngine
import com.mohnishraj.aether.core.css.MediaEnvironment
import com.mohnishraj.aether.core.html.HtmlEngine
import com.mohnishraj.aether.core.layout.LengthContext
import com.mohnishraj.aether.core.layout.LayoutEngine
import com.mohnishraj.aether.core.layout.LayoutValueParser
import com.mohnishraj.aether.core.layout.LayoutViewport
import com.mohnishraj.aether.core.layout.PositionScheme
import com.mohnishraj.aether.core.layout.inspect.LayoutInspector
import com.mohnishraj.aether.core.selftest.SelfTestCheck
import kotlin.math.abs

object LayoutSelfTest {
    fun run(engine: LayoutEngine, html: HtmlEngine, css: CssEngine): List<SelfTestCheck> {
        val checks = mutableListOf<SelfTestCheck>()
        fun check(name: String, block: () -> String) {
            val result = runCatching(block)
            checks += if (result.isSuccess) SelfTestCheck(name, true, result.getOrThrow())
            else SelfTestCheck(name, false, result.exceptionOrNull()?.message ?: "unknown error")
        }
        fun layout(markup: String, stylesheet: String, width: Double = 360.0, height: Double = 800.0): Triple<com.mohnishraj.aether.core.html.dom.DocumentNode, com.mohnishraj.aether.core.css.StyleTree, com.mohnishraj.aether.core.layout.LayoutTree> {
            val document = html.parse(markup).document
            val viewport = LayoutViewport(width, height)
            val styles = css.compute(document, listOf(css.parse(stylesheet)), MediaEnvironment(width, height))
            return Triple(document, styles, engine.layout(document, styles, viewport))
        }

        check("layout lengths") {
            val viewport = LayoutViewport(400.0, 800.0, 16.0)
            val context = LengthContext(200.0, 20.0, 16.0, viewport)
            require(LayoutValueParser.resolveLength("50%", context) == 100.0)
            require(LayoutValueParser.resolveLength("2em", context) == 40.0)
            require(LayoutValueParser.resolveLength("10vw", context) == 40.0)
            "percent, em and viewport units resolved"
        }
        check("layout calc") {
            val value = LayoutValueParser.resolveLength("calc(100% - 20px + 1rem)", LengthContext(200.0, 16.0, 16.0, LayoutViewport()))
            require(abs((value ?: 0.0) - 196.0) < 0.001)
            "calc additive lengths resolved"
        }
        check("block box model") {
            val (doc, _, tree) = layout("<main id='x'>A</main>", "#x { width: 200px; padding: 10px; border: 2px solid; box-sizing: border-box }")
            val box = tree.boxFor(doc.getElementById("x")!!)!!
            require(abs(box.borderBox.width - 200.0) < 0.001)
            require(abs(box.contentBox.width - 176.0) < 0.001)
            "border-box width and content width verified"
        }
        check("block flow") {
            val (doc, _, tree) = layout("<main><div id='a'>A</div><div id='b'>B</div></main>", "div { height: 30px }")
            val a = tree.boxFor(doc.getElementById("a")!!)!!
            val b = tree.boxFor(doc.getElementById("b")!!)!!
            require(b.flowBorderBox.y >= a.flowBorderBox.bottom)
            "block children placed vertically"
        }
        check("margin collapse") {
            val (doc, _, tree) = layout("<main><div id='a'></div><div id='b'></div></main>", "#a { height: 10px; margin-bottom: 20px } #b { height: 10px; margin-top: 30px }")
            val a = tree.boxFor(doc.getElementById("a")!!)!!
            val b = tree.boxFor(doc.getElementById("b")!!)!!
            require(abs((b.flowBorderBox.y - a.flowBorderBox.bottom) - 30.0) < 0.001)
            "adjacent vertical margins collapsed"
        }
        check("inline wrapping") {
            val (doc, _, tree) = layout("<p id='p'>Aether engine wraps this text into several deterministic lines.</p>", "#p { width: 100px; font-size: 16px }")
            val box = tree.boxFor(doc.getElementById("p")!!)!!
            require(box.lineBoxes.size >= 2 && box.lineBoxes.all { it.rect.width <= box.contentBox.width + 0.001 })
            "inline text wrapped within containing width"
        }
        check("whitespace collapse") {
            val (doc, _, tree) = layout("<p id='p'>A   B\n C</p>", "#p { width: 300px; white-space: normal }")
            val text = tree.boxFor(doc.getElementById("p")!!)!!.lineBoxes.flatMap { it.fragments }.joinToString("") { it.text }
            require("  " !in text && text.trim() == "A B C")
            "normal white-space collapsed"
        }
        check("relative positioning") {
            val (doc, _, tree) = layout("<div id='x'></div>", "#x { width: 40px; height: 20px; position: relative; left: 12px; top: 7px }")
            val box = tree.boxFor(doc.getElementById("x")!!)!!
            require(abs((box.borderBox.x - box.flowBorderBox.x) - 12.0) < 0.001)
            require(abs((box.borderBox.y - box.flowBorderBox.y) - 7.0) < 0.001)
            "relative visual offset preserves flow box"
        }
        check("absolute positioning") {
            val (doc, _, tree) = layout("<main id='p'><div id='x'></div></main>", "#p { position: relative; width: 200px; height: 100px } #x { position: absolute; left: 25px; top: 15px; width: 20px; height: 10px }")
            val parent = tree.boxFor(doc.getElementById("p")!!)!!
            val child = tree.boxFor(doc.getElementById("x")!!)!!
            require(child.position == PositionScheme.ABSOLUTE)
            require(child.borderBox.x >= parent.borderBox.x + 25.0)
            require(child.borderBox.y >= parent.borderBox.y + 15.0)
            "absolute box resolved against positioned ancestor"
        }
        check("fixed positioning") {
            val (doc, _, tree) = layout("<div id='x'></div>", "#x { position: fixed; right: 10px; bottom: 20px; width: 30px; height: 40px }")
            val box = tree.boxFor(doc.getElementById("x")!!)!!
            require(abs(box.borderBox.right - 350.0) < 0.001)
            require(abs(box.borderBox.bottom - 780.0) < 0.001)
            "fixed box anchored to viewport"
        }
        check("overflow clipping") {
            val (doc, _, tree) = layout("<div id='p'><div id='c'></div></div>", "#p { width: 100px; height: 40px; overflow: auto } #c { width: 220px; height: 90px }")
            val box = tree.boxFor(doc.getElementById("p")!!)!!
            require(box.clipRect != null && box.isScrollableX && box.isScrollableY)
            "overflow auto exposes clip and scroll extents"
        }
        check("stacking order") {
            val (doc, _, tree) = layout("<main><div id='back'></div><div id='front'></div></main>", "div { position: absolute; width: 10px; height: 10px } #back { z-index: -1 } #front { z-index: 5 }")
            val back = tree.boxFor(doc.getElementById("back")!!)!!
            val front = tree.boxFor(doc.getElementById("front")!!)!!
            require(tree.paintOrder.indexOf(back) < tree.paintOrder.indexOf(front))
            "z-index influences deterministic paint order"
        }
        check("responsive units") {
            val (smallDoc, _, smallTree) = layout("<div id='x'></div>", "#x { width: 50vw; height: 10vh }", 320.0, 600.0)
            val small = smallTree.boxFor(smallDoc.getElementById("x")!!)!!
            require(abs(small.borderBox.width - 160.0) < 0.001 && abs(small.contentBox.height - 60.0) < 0.001)
            "vw and vh react to viewport"
        }
        check("display none") {
            val (doc, _, tree) = layout("<main><div id='gone'>X</div><p id='kept'>Y</p></main>", "#gone { display: none }")
            require(tree.boxFor(doc.getElementById("gone")!!) == null)
            require(tree.boxFor(doc.getElementById("kept")!!) != null)
            "display:none removes layout box"
        }
        check("replaced element") {
            val (doc, _, tree) = layout("<img id='image' width='120' height='60'>", "#image { display: block }")
            val box = tree.boxFor(doc.getElementById("image")!!)!!
            require(abs(box.contentBox.height - 60.0) < 0.001)
            "replaced element intrinsic attributes used"
        }
        check("layout inspector") {
            val (_, _, tree) = layout("<main><p>Aether layout</p></main>", "main { padding: 8px }")
            val output = LayoutInspector.tree(tree)
            require("AETHER LAYOUT TREE" in output && "@line" in output)
            "box, line and fragment diagnostics emitted"
        }
        return checks
    }
}
