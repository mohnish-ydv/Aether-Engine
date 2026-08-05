package com.mohnishraj.aether.core.html.selftest

import com.mohnishraj.aether.core.html.HtmlEngine
import com.mohnishraj.aether.core.html.dom.HtmlNamespace
import com.mohnishraj.aether.core.html.dom.QuirksMode
import com.mohnishraj.aether.core.html.inspect.DomInspector
import com.mohnishraj.aether.core.html.inspect.HtmlSerializer
import com.mohnishraj.aether.core.selftest.SelfTestCheck

object HtmlSelfTest {
    fun run(engine: HtmlEngine): List<SelfTestCheck> {
        val checks = mutableListOf<SelfTestCheck>()
        fun check(name: String, block: () -> String) {
            val result = runCatching(block)
            checks += if (result.isSuccess) SelfTestCheck(name, true, result.getOrThrow())
            else SelfTestCheck(name, false, result.exceptionOrNull()?.message ?: "unknown error")
        }

        check("html tokenizer") {
            val result = engine.parse("<!doctype html><p data-x='1' disabled>Hello &amp; world</p>")
            require(result.tokenCount >= 5 && result.document.body?.textContent == "Hello & world")
            "doctype, tags, attributes, text and entities tokenized"
        }
        check("document skeleton") {
            val document = engine.parse("<title>Aether</title><main>Ready</main>").document
            require(document.documentElement != null && document.head != null && document.body != null)
            require(document.head?.textContent == "Aether" && document.body?.textContent == "Ready")
            "implied html/head/body nodes inserted"
        }
        check("dom attributes") {
            val document = engine.parse("<div ID='hero' class='one two'>x</div>").document
            val element = document.getElementById("hero") ?: error("id lookup failed")
            require(element.classNames == setOf("one", "two") && element.getAttribute("id") == "hero")
            "case-normalized attributes and id lookup verified"
        }
        check("raw text") {
            val document = engine.parse("<script>if (a < b) x = '&amp;';</script><textarea>A&amp;B</textarea>").document
            val script = document.getElementsByTagName("script").single()
            val textarea = document.getElementsByTagName("textarea").single()
            require(script.textContent.contains("a < b") && script.textContent.contains("&amp;"))
            require(textarea.textContent == "A&B")
            "RAWTEXT and RCDATA modes separated"
        }
        check("optional end tags") {
            val document = engine.parse("<ul><li>one<li>two</ul><p>a<div>b</div>").document
            require(document.getElementsByTagName("li").size == 2)
            require(document.getElementsByTagName("p").single().textContent == "a")
            "li and p implied closures recovered"
        }
        check("malformed recovery") {
            val result = engine.parse("<div><b>bold</div></ghost>")
            require(result.issues.isNotEmpty() && result.document.body?.textContent == "bold")
            "misnested and stray end tags reported without aborting"
        }
        check("comments and doctype") {
            val document = engine.parse("<!DOCTYPE html><!--a--><p>b</p>").document
            require(document.quirksMode == QuirksMode.NO_QUIRKS)
            require(DomInspector.summarize(document).comments == 1)
            "doctype mode and comment node verified"
        }
        check("table recovery") {
            val document = engine.parse("<table><td>A<td>B</table>").document
            require(document.getElementsByTagName("tbody").size == 1)
            require(document.getElementsByTagName("tr").size == 1)
            require(document.getElementsByTagName("td").size == 2)
            "tbody and tr synthesized for orphan cells"
        }
        check("foreign namespace") {
            val document = engine.parse("<svg><circle cx='4'></circle></svg><math><mi>x</mi></math>").document
            require(document.getElementsByTagName("svg").single().namespace == HtmlNamespace.SVG)
            require(document.getElementsByTagName("math").single().namespace == HtmlNamespace.MATHML)
            "SVG and MathML namespaces preserved"
        }
        check("dom inspector") {
            val document = engine.parse("<main><h1>Aether</h1><p>Engine</p></main>").document
            val tree = DomInspector.tree(document)
            val summary = DomInspector.summarize(document)
            require("<main>" in tree && summary.elements >= 6 && summary.maxDepth >= 3)
            "tree and structural summary generated"
        }
        check("serializer") {
            val document = engine.parse("<!doctype html><p title='a&b'>A &amp; B<br>C</p>").document
            val serialized = HtmlSerializer.serialize(document)
            require("title=\"a&amp;b\"" in serialized && "A &amp; B<br>C" in serialized)
            "normalized HTML serialization escaped safely"
        }
        check("parser limits") {
            val limited = HtmlEngine(limits = com.mohnishraj.aether.core.html.HtmlLimits(maxDepth = 4))
            val result = limited.parse("<div><div><div><div><div>x</div></div></div></div></div>")
            require(result.issues.any { it.code == "depth-limit" })
            "nesting limit enforced deterministically"
        }
        return checks
    }
}
