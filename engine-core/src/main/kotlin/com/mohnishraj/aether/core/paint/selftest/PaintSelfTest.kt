package com.mohnishraj.aether.core.paint.selftest

import com.mohnishraj.aether.core.css.CssEngine
import com.mohnishraj.aether.core.css.MediaEnvironment
import com.mohnishraj.aether.core.html.HtmlEngine
import com.mohnishraj.aether.core.layout.LayoutEngine
import com.mohnishraj.aether.core.layout.LayoutViewport
import com.mohnishraj.aether.core.paint.PaintColor
import com.mohnishraj.aether.core.paint.PaintCommand
import com.mohnishraj.aether.core.paint.PaintEngine
import com.mohnishraj.aether.core.paint.PaintInvalidationTracker
import com.mohnishraj.aether.core.paint.PaintValueParser
import com.mohnishraj.aether.core.paint.inspect.PaintInspector
import com.mohnishraj.aether.core.selftest.SelfTestCheck

object PaintSelfTest {
    fun run(engine: PaintEngine, html: HtmlEngine, css: CssEngine, layout: LayoutEngine): List<SelfTestCheck> {
        val checks = mutableListOf<SelfTestCheck>()
        fun check(name: String, block: () -> String) {
            val result = runCatching(block)
            checks += if (result.isSuccess) SelfTestCheck(name, true, result.getOrThrow())
            else SelfTestCheck(name, false, result.exceptionOrNull()?.message ?: "unknown error")
        }
        fun paint(markup: String, stylesheet: String, width: Double = 360.0): com.mohnishraj.aether.core.paint.DisplayList {
            val document = html.parse(markup).document
            val styles = css.compute(document, listOf(css.parse(stylesheet)), MediaEnvironment(width, 800.0))
            val tree = layout.layout(document, styles, LayoutViewport(width, 800.0))
            return engine.paint(tree)
        }

        check("paint colors") {
            require(PaintValueParser.color("#55e6c1") == PaintColor(85, 230, 193))
            require(PaintValueParser.color("rgba(255, 0, 0, .5)")?.alpha in 127..128)
            "hex and rgba colors parsed"
        }
        check("paint rounded background") {
            val list = paint("<main id='x'>Aether</main>", "#x { background-color:#123456; border-radius:12px; width:120px; padding:8px }")
            require(list.commands.any { it is PaintCommand.FillRoundedRect && it.color == PaintColor(18, 52, 86) })
            "rounded background emitted"
        }
        check("paint border") {
            val list = paint("<div id='x'>X</div>", "#x { border:2px solid #ff0000; width:80px; height:20px }")
            require(list.commands.any { it is PaintCommand.DrawBorder && it.border.visible })
            "border command emitted"
        }
        check("paint shadow") {
            val list = paint("<div id='x'>X</div>", "#x { box-shadow:2px 4px 8px 1px rgba(0,0,0,.5); width:80px; height:20px }")
            require(list.commands.any { it is PaintCommand.DrawShadow && !it.shadow.inset })
            "outer shadow emitted"
        }
        check("paint gradient") {
            val list = paint("<div id='x'>X</div>", "#x { background-image:linear-gradient(90deg,#000000,#ffffff); width:80px; height:20px }")
            require(list.commands.any { it is PaintCommand.DrawLinearGradient && it.angleDegrees == 90.0 })
            "two-stop linear gradient emitted"
        }
        check("paint text") {
            val list = paint("<p id='x'>Aether paint</p>", "#x { color:#112233; font-size:18px; width:200px }")
            val text = list.commands.filterIsInstance<PaintCommand.DrawText>()
            require(text.isNotEmpty() && text.joinToString("") { it.text }.contains("Aether paint"))
            require(text.first().color == PaintColor(17, 34, 51))
            "line fragments converted to text commands"
        }
        check("paint image") {
            val list = paint("<img id='x' src='asset://hero.png' alt='Hero' width='120' height='60'>", "#x { display:block; object-fit:cover }")
            require(list.commands.any { it is PaintCommand.DrawImage && it.source == "asset://hero.png" && it.altText == "Hero" })
            "replaced image command emitted"
        }
        check("paint opacity") {
            val list = paint("<div id='x'>X</div>", "#x { background:#ff0000; opacity:.4; width:80px; height:20px }")
            require(list.commands.filterIsInstance<PaintCommand.FillRect>().any { it.opacity == 0.4 })
            "element opacity retained"
        }
        check("paint visibility") {
            val list = paint("<div id='x'>Hidden</div>", "#x { visibility:hidden; background:red }")
            require(list.commands.none { it is PaintCommand.DrawText || it is PaintCommand.FillRect || it is PaintCommand.FillRoundedRect })
            "visibility hidden skips visual commands"
        }
        check("paint clipping") {
            val list = paint("<div id='x'>A very long text value for clipping.</div>", "#x { width:40px; height:20px; overflow:hidden }")
            require(list.commands.any { it is PaintCommand.PushClip } && list.commands.any { it is PaintCommand.PopClip })
            "overflow clip stack balanced"
        }
        check("paint order") {
            val list = paint("<main><div id='a'>A</div><div id='b'>B</div></main>", "div { position:absolute; width:20px; height:20px; background:red } #a { z-index:-1 } #b { z-index:5; background:blue }")
            val fills = list.commands.filterIsInstance<PaintCommand.FillRect>()
            require(fills.indexOfFirst { it.color == PaintColor(255, 0, 0) } < fills.indexOfFirst { it.color == PaintColor(0, 0, 255) })
            "layout stacking order preserved"
        }
        check("paint hit testing") {
            val list = paint("<div id='x'>X</div>", "#x { background:red; width:100px; height:50px }")
            require(list.commandsAt(10.0, 10.0).isNotEmpty())
            "display-list bounds query works"
        }
        check("paint invalidation") {
            val first = paint("<div id='x'>X</div>", "#x { background:red; width:100px; height:50px }")
            val same = paint("<div id='x'>X</div>", "#x { background:red; width:100px; height:50px }")
            val changed = paint("<div id='x'>X</div>", "#x { background:blue; width:100px; height:50px }")
            require(PaintInvalidationTracker.compare(first, same).isClean)
            require(!PaintInvalidationTracker.compare(same, changed).isClean)
            "unchanged and dirty display lists distinguished"
        }
        check("paint inspector") {
            val output = PaintInspector.displayList(paint("<p>Aether</p>", "p { color:teal }"))
            require("AETHER PAINT DISPLAY LIST" in output && "TEXT" in output)
            "display-list diagnostics emitted"
        }
        check("paint command bounds") {
            val list = paint("<div id='x'>X</div>", "#x { box-shadow:4px 4px 8px #000; width:20px; height:20px }")
            val shadow = list.commands.filterIsInstance<PaintCommand.DrawShadow>().first()
            require(shadow.bounds.width > shadow.rect.width && shadow.bounds.height > shadow.rect.height)
            "effect bounds include shadow blur"
        }
        check("paint statistics") {
            val before = engine.statistics().displayListsBuilt
            paint("<p>stats</p>", "")
            require(engine.statistics().displayListsBuilt == before + 1)
            "paint counters advanced"
        }
        return checks
    }
}
