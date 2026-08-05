package com.mohnishraj.aether.core.browser.selftest

import com.mohnishraj.aether.core.EngineRuntime
import com.mohnishraj.aether.core.browser.events.BrowserEvent
import com.mohnishraj.aether.core.browser.mutation.MutationObserverOptions
import com.mohnishraj.aether.core.browser.storage.StorageQuotaExceededException
import com.mohnishraj.aether.core.selftest.SelfTestCheck

object BrowserApiSelfTest {
    fun run(runtime: EngineRuntime): List<SelfTestCheck> {
        val checks = mutableListOf<SelfTestCheck>()
        fun check(name: String, block: () -> String) {
            val result = runCatching(block)
            checks += if (result.isSuccess) SelfTestCheck("browser.$name", true, result.getOrThrow())
            else SelfTestCheck("browser.$name", false, result.exceptionOrNull()?.message ?: "unknown error")
        }
        val page = runtime.browser.open(
            """<!doctype html><html><body><main id="app"><h1>Aether</h1><form id="f"><input name="q" required value="M8"></form></main></body></html>""",
            "https://selftest.aether/"
        )
        check("page") { require(page.origin == "https://selftest.aether"); "origin and document context" }
        check("selectors") { require(page.document.querySelector("#app > h1")?.textContent == "Aether"); "CSS selector queries" }
        check("dom mutation") {
            val node = page.document.createElement("section")
            page.document.appendChild(page.document.body!!, node)
            page.document.setAttribute(node, "data-m8", "ready")
            require(node.getAttribute("data-m8") == "ready")
            "create/append/attribute"
        }
        check("inner html") {
            val app = page.document.getElementById("app")!!
            page.document.setInnerHtml(app, "<p class='ready'>Browser APIs</p>")
            require(page.document.querySelector(".ready")?.textContent == "Browser APIs")
            "fragment parse and clone"
        }
        check("events") {
            val node = page.document.querySelector(".ready")!!
            var count = 0
            page.document.addEventListener(node, "probe", { count++ })
            require(page.document.dispatchEvent(node, BrowserEvent("probe")) && count == 1)
            "capture/target/bubble hub"
        }
        check("mutations") {
            val node = page.document.querySelector(".ready")!!
            var delivered = 0
            page.document.observe(node, MutationObserverOptions(attributes = true)) { delivered += it.size }
            page.document.setAttribute(node, "title", "observed")
            page.document.deliverMutations()
            require(delivered == 1)
            "observer delivery"
        }
        check("local storage") {
            page.localStorage.setItem("milestone", "M8")
            require(page.localStorage.getItem("milestone") == "M8")
            "persistent origin area"
        }
        check("session storage") {
            page.sessionStorage.setItem("tab", "1")
            require(page.sessionStorage.length == 1)
            "runtime session area"
        }
        check("storage quota") {
            val failed = runCatching { page.localStorage.setItem("oversize", "x".repeat(runtime.browser.limits.maxStorageValueChars + 1)) }
                .exceptionOrNull()
            require(failed is IllegalArgumentException || failed is StorageQuotaExceededException)
            "quota and value limits"
        }
        check("forms") {
            val probe = runtime.browser.open("<form id='f'><input name='x' value='1'></form>", "https://selftest.aether/form")
            val submission = probe.forms.serialize(probe.document.getElementById("f")!!, probe.url)
            require(submission.validation.valid && submission.encodedBody == "x=1")
            "validation and url encoding"
        }
        check("clipboard") {
            runtime.browser.clipboard.writeText("Aether M8")
            require(runtime.browser.clipboard.readText() == "Aether M8")
            "clipboard port round-trip"
        }
        check("javascript dom") {
            val probe = runtime.browser.open("<main id='x'>old</main>", "https://selftest.aether/js")
            val result = probe.evaluate("document.getElementById('x').setText('new'); document.getElementById('x').getText();", freshRealm = true)
            require(result.success && result.value.displayString() == "new")
            "host globals and DOM bridge"
        }
        check("javascript events") {
            val probe = runtime.browser.open("<button id='x'>x</button>", "https://selftest.aether/event")
            val result = probe.evaluate("let n=0;let x=document.getElementById('x');x.addEventListener('go',function(){n=n+1;});x.dispatchEvent('go');n;", freshRealm = true)
            require(result.success && result.value.displayString() == "1")
            "listener callback invocation"
        }
        check("javascript storage") {
            val probe = runtime.browser.open("<p>x</p>", "https://selftest.aether/storage")
            val result = probe.evaluate("localStorage.setItem('engine','aether');localStorage.getItem('engine');", freshRealm = true)
            require(result.success && result.value.displayString() == "aether")
            "Storage-compatible globals"
        }
        check("javascript mutations") {
            val probe = runtime.browser.open("<main id='x'></main>", "https://selftest.aether/mutation")
            val result = probe.evaluate("let n=0;let x=document.getElementById('x');observeMutations(x,function(r){n=n+r.length;},{attributes:true});x.setAttribute('data-x','1');n;", freshRealm = true)
            require(result.success && result.value.displayString() == "1")
            "observer callback bridge"
        }
        check("statistics") {
            val stats = runtime.browser.statistics()
            require(stats.pagesOpened >= 1 && stats.domQueries >= 1 && stats.scriptsEvaluated >= 1)
            "bounded API counters"
        }
        return checks
    }
}
