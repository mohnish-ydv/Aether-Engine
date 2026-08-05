package com.mohnishraj.aether.core.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BrowserModernDomTest {
    @Test fun standardTextContentAndInnerHtmlMutateLiveDom() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        val result = page.evaluate("let app=document.querySelector('#app');app.innerHTML='<p id=ready>Ready</p>';document.querySelector('#ready').textContent;")
        assertTrue(result.success)
        assertEquals("Ready", result.value.displayString())
        assertNotNull(page.document.getElementById("ready"))
    }

    @Test fun liveElementPropertiesReflectAttributes() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        val result = page.evaluate("let x=document.createElement('input');x.id='field';x.value='Aether';x.disabled=true;document.body.appendChild(x);x.id+'|'+x.value+'|'+x.disabled;")
        assertEquals("field|Aether|true", result.value.displayString())
    }

    @Test fun classListDatasetAndStyleAreLive() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        val result = page.evaluate("let x=document.querySelector('#app');x.classList.add('ready');x.dataset.mode='fast';x.style.backgroundColor='red';x.classList.contains('ready')+'|'+x.dataset.mode+'|'+x.style.backgroundColor;")
        assertEquals("true|fast|red", result.value.displayString())
    }

    @Test fun eventConstructorAndArrowListenerDispatch() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        val result = page.evaluate("let count=0;let x=document.querySelector('h1');x.addEventListener('click',()=>count++);x.dispatchEvent(new Event('click'));count;")
        assertEquals("1", result.value.displayString())
    }

    @Test fun onClickPropertyHandlerDispatches() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        val result = page.evaluate("let count=0;let x=document.querySelector('h1');x.onclick=()=>count++;x.click();count;")
        assertEquals("1", result.value.displayString())
    }

    @Test fun mutationObserverConstructorReceivesRecords() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        val result = page.evaluate("let n=0;let o=new MutationObserver(items=>n+=items.length);o.observe(document.body,{childList:true});document.body.appendChild(document.createElement('p'));document.deliverMutations();n;")
        assertTrue(result.success)
        assertEquals("1", result.value.displayString())
    }

    @Test fun timeoutMutationRunsWhenVirtualTimeAdvances() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        assertTrue(page.evaluate("setTimeout(()=>document.body.setAttribute('data-ready','yes'),10);").success)
        assertTrue(page.hasPendingTasks())
        page.advanceTimeBy(10)
        assertEquals("yes", page.document.body?.getAttribute("data-ready"))
    }

    @Test fun documentReadyStateAndLifecycleEventsAreExposed() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        assertEquals("loading", page.readyState)
        page.markInteractive()
        assertEquals("interactive", page.readyState)
        page.markComplete()
        assertEquals("complete", page.readyState)
        assertEquals("complete", page.evaluate("document.readyState;").value.displayString())
    }

    @Test fun fetchReturnsPromiseResponseAndJsonBody() {
        val page = browserRuntimeWithNetwork().browser.open(PAGE_HTML, "https://app.aether.test/")
        val first = page.evaluate("let milestone='';fetch('/json').then(r=>r.json()).then(x=>milestone=x.milestone);")
        assertTrue(first.success)
        assertEquals("M8", page.evaluate("milestone;").value.displayString())
    }

    @Test fun xhrRunsLoadHandlerAndExposesResponseText() {
        val page = browserRuntimeWithNetwork().browser.open(PAGE_HTML, "https://app.aether.test/")
        val first = page.evaluate("let value='';let xhr=new XMLHttpRequest();xhr.open('GET','/json');xhr.onload=()=>value=xhr.responseText;xhr.send();")
        assertTrue(first.success)
        assertEquals("{\"milestone\":\"M8\"}", page.evaluate("value;").value.displayString())
    }

    @Test fun locationAssignmentRecordsNavigationRequest() {
        val page = browserRuntime().browser.open(PAGE_HTML, "https://app.aether.test/")
        assertTrue(page.evaluate("location.href='/next';").success)
        val request = assertNotNull(page.consumeNavigationRequest())
        assertEquals("/next", request.rawUrl)
        assertFalse(request.replace)
    }

    @Test fun objectKeysCanInspectLiveStorageProperties() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        val result = page.evaluate("localStorage.alpha='1';localStorage.beta='2';Object.keys(localStorage).join(',');")
        assertTrue(result.success)
        assertTrue(result.value.displayString().contains("alpha"))
        assertTrue(result.value.displayString().contains("beta"))
    }
}
