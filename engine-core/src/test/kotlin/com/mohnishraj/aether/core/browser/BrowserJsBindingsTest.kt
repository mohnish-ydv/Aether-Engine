package com.mohnishraj.aether.core.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserJsBindingsTest {
    @Test fun scriptCanQueryAndReadDom() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        val result = page.evaluate("document.querySelector('#app').getText();", freshRealm = true)
        assertTrue(result.success)
        assertTrue(result.value.displayString().contains("Aether"))
    }

    @Test fun scriptCanCreateAndAppendElement() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        val result = page.evaluate("let x=document.createElement('section'); x.setAttribute('id','from-js'); x.setText('Ready'); document.body.appendChild(x); document.querySelector('#from-js').getText();", freshRealm = true)
        assertTrue(result.success)
        assertEquals("Ready", result.value.displayString())
    }

    @Test fun scriptCanMutateAttributes() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        val result = page.evaluate("let x=document.getElementById('app'); x.setAttribute('data-mode','m8'); x.getAttribute('data-mode');", freshRealm = true)
        assertEquals("m8", result.value.displayString())
    }

    @Test fun scriptCanUseLocalStorage() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        val result = page.evaluate("localStorage.setItem('mode','dark'); localStorage.getItem('mode');", freshRealm = true)
        assertEquals("dark", result.value.displayString())
    }

    @Test fun scriptCanUseClipboard() {
        val runtime = browserRuntime()
        val page = runtime.browser.open(PAGE_HTML)
        val result = page.evaluate(
            "let copied=''; navigator.clipboard.writeText('Aether M13')" +
                ".then(function(){ return navigator.clipboard.readText(); })" +
                ".then(function(value){ copied=value; });",
            freshRealm = true
        )
        assertTrue(result.success)
        assertEquals("Aether M13", page.evaluate("copied;", freshRealm = false).value.displayString())
    }

    @Test fun scriptEventListenerRuns() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        val source = "let n=0; let x=document.querySelector('h1'); x.addEventListener('ping', function(e){ n=n+1; }); x.dispatchEvent('ping'); n;"
        val result = page.evaluate(source, freshRealm = true)
        assertTrue(result.success)
        assertEquals("1", result.value.displayString())
    }

    @Test fun scriptPreventDefaultReturnsFalse() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        val source = "let x=document.querySelector('h1'); x.addEventListener('submit', function(e){ e.preventDefault(); }); x.dispatchEvent('submit');"
        val result = page.evaluate(source, freshRealm = true)
        assertTrue(result.success)
        assertEquals("false", result.value.displayString())
    }

    @Test fun scriptMutationObserverReceivesRecords() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        val source = "let count=0; let x=document.getElementById('app'); observeMutations(x,function(records){count=count+records.length;},{attributes:true}); x.setAttribute('data-x','1'); count;"
        val result = page.evaluate(source, freshRealm = true)
        assertTrue(result.success)
        assertEquals("1", result.value.displayString())
    }

    @Test fun scriptCanSerializeForm() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        val result = page.evaluate("serializeForm(document.getElementById('signup')).valid;", freshRealm = true)
        assertTrue(result.success)
        assertEquals("true", result.value.displayString())
    }

    @Test fun missingDomNodeProducesRuntimeError() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        val result = page.evaluate("document.querySelector('#missing').setText('x');", freshRealm = true)
        assertFalse(result.success)
        assertEquals("TypeError", result.error?.kind)
    }

    @Test fun persistentRealmKeepsVariables() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        assertTrue(page.evaluate("var m8=40;", freshRealm = true).success)
        assertEquals("42", page.evaluate("m8=m8+2; m8;", freshRealm = false).value.displayString())
    }

    @Test fun browserStatisticsTrackOperations() {
        val runtime = browserRuntime()
        val page = runtime.browser.open(PAGE_HTML)
        page.evaluate("document.querySelector('h1').setText('M8'); localStorage.setItem('x','1');", freshRealm = true)
        val stats = runtime.browser.statistics()
        assertEquals(1, stats.pagesOpened)
        assertEquals(1, stats.scriptsEvaluated)
        assertTrue(stats.domQueries > 0)
        assertTrue(stats.domMutations > 0)
        assertTrue(stats.storageWrites > 0)
    }
}
