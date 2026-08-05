package com.mohnishraj.aether.core.css.selftest

import com.mohnishraj.aether.core.css.ColorScheme
import com.mohnishraj.aether.core.css.CssEngine
import com.mohnishraj.aether.core.css.MediaEnvironment
import com.mohnishraj.aether.core.css.cascade.MediaQueryEvaluator
import com.mohnishraj.aether.core.css.inspect.CssInspector
import com.mohnishraj.aether.core.css.parser.CssParser
import com.mohnishraj.aether.core.css.selector.CssSelectorParser
import com.mohnishraj.aether.core.css.token.CssTokenType
import com.mohnishraj.aether.core.css.token.CssTokenizer
import com.mohnishraj.aether.core.html.HtmlEngine
import com.mohnishraj.aether.core.selftest.SelfTestCheck

object CssSelfTest {
    fun run(engine: CssEngine, html: HtmlEngine): List<SelfTestCheck> {
        val checks = mutableListOf<SelfTestCheck>()
        fun check(name: String, block: () -> String) {
            val result = runCatching(block)
            checks += if (result.isSuccess) SelfTestCheck(name, true, result.getOrThrow())
            else SelfTestCheck(name, false, result.exceptionOrNull()?.message ?: "unknown error")
        }

        check("css tokenizer") {
            val result = CssTokenizer().tokenize("h1 { color: #0af; width: 50%; margin: 1.5rem }")
            require(result.tokens.any { it.type == CssTokenType.HASH && it.value == "0af" })
            require(result.tokens.any { it.type == CssTokenType.PERCENTAGE && it.number == 50.0 })
            require(result.tokens.any { it.type == CssTokenType.DIMENSION && it.unit == "rem" })
            "identifiers, hashes, percentages and dimensions tokenized"
        }
        check("css parser") {
            val sheet = CssParser().parse("main, .card { color: red; padding: 8px !important } @media (min-width: 600px) { main { display: grid } }")
            require(sheet.rules.size == 2 && sheet.issues.isEmpty())
            "style and media rules parsed"
        }
        check("selector specificity") {
            val selector = CssSelectorParser().parseList("main#app.card[data-x] > p:first-child").single()
            require(selector.specificity.ids == 1 && selector.specificity.classes == 3 && selector.specificity.types == 2)
            "specificity=${selector.specificity}"
        }
        check("selector matching") {
            val doc = html.parse("<main id='app'><p class='lead'>A</p><p>B</p></main>").document
            val first = doc.getElementsByTagName("p").first()
            require(CssSelectorParser().parseList("main#app > p.lead:first-child").single().matches(first))
            "child, id, class and pseudo-class matched"
        }
        check("cascade precedence") {
            val doc = html.parse("<p id='hero' class='note' style='color: purple'>A</p>").document
            val sheet = engine.parse("p { color: blue } .note { color: green } #hero { color: red }")
            val style = engine.compute(doc, listOf(sheet)).styleFor(doc.getElementById("hero")!!)!!
            require(style["color"] == "purple")
            "inline declaration wins cascade"
        }
        check("important precedence") {
            val doc = html.parse("<p id='hero' style='color: purple'>A</p>").document
            val sheet = engine.parse("#hero { color: red !important }")
            val style = engine.compute(doc, listOf(sheet)).styleFor(doc.getElementById("hero")!!)!!
            require(style["color"] == "red")
            "author important beats inline normal"
        }
        check("inheritance") {
            val doc = html.parse("<main><span>A</span></main>").document
            val tree = engine.compute(doc, listOf(engine.parse("main { color: navy; font-size: 20px }")))
            val span = doc.getElementsByTagName("span").single()
            require(tree.styleFor(span)?.get("color") == "navy" && tree.styleFor(span)?.get("font-size") == "20px")
            "inherited text properties propagated"
        }
        check("custom properties") {
            val doc = html.parse("<main><p>A</p></main>").document
            val tree = engine.compute(doc, listOf(engine.parse("main { --accent: #09f } p { color: var(--accent) }")))
            require(tree.styleFor(doc.getElementsByTagName("p").single())?.get("color") == "#09f")
            "var() resolved from inherited custom property"
        }
        check("variable fallback") {
            val doc = html.parse("<p>A</p>").document
            val tree = engine.compute(doc, listOf(engine.parse("p { color: var(--missing, teal) }")))
            require(tree.styleFor(doc.getElementsByTagName("p").single())?.get("color") == "teal")
            "var() fallback resolved"
        }
        check("media query") {
            require(MediaQueryEvaluator.matches("screen and (min-width: 600px) and (prefers-color-scheme: dark)", MediaEnvironment(800.0, 600.0, colorScheme = ColorScheme.DARK)))
            require(!MediaQueryEvaluator.matches("(orientation: portrait)", MediaEnvironment(800.0, 600.0)))
            "viewport and color-scheme features evaluated"
        }
        check("media cascade") {
            val doc = html.parse("<main>A</main>").document
            val sheet = engine.parse("main { display: block } @media (min-width: 700px) { main { display: grid } }")
            val main = doc.getElementsByTagName("main").single()
            require(engine.compute(doc, listOf(sheet), MediaEnvironment(800.0, 600.0)).styleFor(main)?.get("display") == "grid")
            require(engine.compute(doc, listOf(sheet), MediaEnvironment(360.0, 800.0)).styleFor(main)?.get("display") == "block")
            "responsive rule activation verified"
        }
        check("font face") {
            val doc = html.parse("<p>A</p>").document
            val sheet = engine.parse("@font-face { font-family: 'Aether Sans'; src: url(aether.woff2); font-display: swap }")
            val tree = engine.compute(doc, listOf(sheet))
            require(tree.fontFaces.single().family == "Aether Sans" && tree.fontFaces.single().display == "swap")
            "font descriptors collected"
        }
        check("css inspector") {
            val doc = html.parse("<main id='x'>A</main>").document
            val sheet = engine.parse("#x { color: red }")
            val tree = engine.compute(doc, listOf(sheet))
            require("#x" in CssInspector.styleSheet(sheet) && "color: red" in CssInspector.computedTree(doc, tree))
            "stylesheet and computed tree rendered"
        }
        check("error recovery") {
            val sheet = engine.parse("a { color red; ok: yes } @unknown x { broken }")
            require(sheet.issues.isNotEmpty() && sheet.rules.isNotEmpty())
            "malformed declarations recovered without aborting"
        }
        check("css limits") {
            val limited = CssParser(com.mohnishraj.aether.core.css.CssLimits(maxDeclarationsPerRule = 2))
            val sheet = limited.parse("a { a:1; b:2; c:3 }")
            require(sheet.issues.any { it.code == "declaration-limit" })
            "declaration limit enforced"
        }
        check("current color") {
            val doc = html.parse("<p>A</p>").document
            val tree = engine.compute(doc, listOf(engine.parse("p { color: maroon; border-color: currentColor }")))
            val style = tree.styleFor(doc.getElementsByTagName("p").single())!!
            require(style["border-color"] == "maroon")
            "currentColor resolved against computed color"
        }
        return checks
    }
}
