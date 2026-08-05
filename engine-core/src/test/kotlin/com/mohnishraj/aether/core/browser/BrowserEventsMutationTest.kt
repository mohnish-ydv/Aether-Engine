package com.mohnishraj.aether.core.browser

import com.mohnishraj.aether.core.browser.events.BrowserEvent
import com.mohnishraj.aether.core.browser.events.BrowserEventPhase
import com.mohnishraj.aether.core.browser.events.EventListenerOptions
import com.mohnishraj.aether.core.browser.mutation.MutationObserverOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserEventsMutationTest {
    @Test fun eventsCaptureTargetAndBubbleInOrder() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        val body = page.document.body!!
        val main = page.document.getElementById("app")!!
        val heading = page.document.querySelector("h1")!!
        val order = mutableListOf<String>()
        page.document.addEventListener(body, "click", { order += "capture-${it.eventPhase}" }, EventListenerOptions(capture = true))
        page.document.addEventListener(heading, "click", { order += "target-${it.eventPhase}" })
        page.document.addEventListener(main, "click", { order += "bubble-${it.eventPhase}" })
        assertTrue(page.document.dispatchEvent(heading, BrowserEvent("click")))
        assertEquals(listOf("capture-CAPTURING_PHASE", "target-AT_TARGET", "bubble-BUBBLING_PHASE"), order)
    }

    @Test fun preventDefaultChangesDispatchResult() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        val target = page.document.querySelector("h1")!!
        page.document.addEventListener(target, "submit", { it.preventDefault() })
        assertFalse(page.document.dispatchEvent(target, BrowserEvent("submit", cancelable = true)))
    }

    @Test fun nonCancelableEventCannotBePrevented() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        val target = page.document.querySelector("h1")!!
        val event = BrowserEvent("ready", cancelable = false)
        page.document.addEventListener(target, "ready", { it.preventDefault() })
        assertTrue(page.document.dispatchEvent(target, event))
        assertFalse(event.defaultPrevented)
    }

    @Test fun onceListenerRunsOnlyOnce() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        val target = page.document.querySelector("h1")!!
        var count = 0
        page.document.addEventListener(target, "ping", { count++ }, EventListenerOptions(once = true))
        page.document.dispatchEvent(target, BrowserEvent("ping"))
        page.document.dispatchEvent(target, BrowserEvent("ping"))
        assertEquals(1, count)
    }

    @Test fun stopPropagationPreventsAncestorBubble() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        val main = page.document.getElementById("app")!!
        val target = page.document.querySelector("h1")!!
        var bubbled = false
        page.document.addEventListener(target, "tap", { it.stopPropagation() })
        page.document.addEventListener(main, "tap", { bubbled = true })
        page.document.dispatchEvent(target, BrowserEvent("tap"))
        assertFalse(bubbled)
    }

    @Test fun listenerCanBeRemovedByHandle() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        val target = page.document.querySelector("h1")!!
        var count = 0
        val handle = page.document.addEventListener(target, "ping", { count++ })
        assertTrue(page.document.removeEventListener(target, handle))
        page.document.dispatchEvent(target, BrowserEvent("ping"))
        assertEquals(0, count)
    }

    @Test fun childListObserverReceivesAddedNode() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        val main = page.document.getElementById("app")!!
        var added = 0
        page.document.observe(main, MutationObserverOptions(childList = true)) { records -> added += records.sumOf { it.addedNodes.size } }
        page.document.appendChild(main, page.document.createElement("footer"))
        page.document.deliverMutations()
        assertEquals(1, added)
    }

    @Test fun subtreeObserverReceivesDescendantChange() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        val main = page.document.getElementById("app")!!
        val heading = page.document.querySelector("h1")!!
        var count = 0
        page.document.observe(main, MutationObserverOptions(attributes = true, subtree = true)) { count += it.size }
        page.document.setAttribute(heading, "title", "hello")
        page.document.deliverMutations()
        assertEquals(1, count)
    }

    @Test fun attributeFilterSuppressesOtherAttributes() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        val main = page.document.getElementById("app")!!
        var count = 0
        page.document.observe(main, MutationObserverOptions(attributes = true, attributeFilter = setOf("data-watch"))) { count += it.size }
        page.document.setAttribute(main, "class", "other")
        page.document.setAttribute(main, "data-watch", "yes")
        page.document.deliverMutations()
        assertEquals(1, count)
    }

    @Test fun characterDataObserverGetsOldValue() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        val text = page.document.querySelector("h1")!!.firstChild!!
        var old: String? = null
        page.document.observe(text, MutationObserverOptions(characterData = true, characterDataOldValue = true)) { old = it.single().oldValue }
        page.document.setTextContent(text, "New")
        page.document.deliverMutations()
        assertEquals("Aether", old)
    }
}
