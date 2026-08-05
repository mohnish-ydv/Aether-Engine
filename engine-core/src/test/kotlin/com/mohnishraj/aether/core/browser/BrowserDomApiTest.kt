package com.mohnishraj.aether.core.browser

import com.mohnishraj.aether.core.browser.mutation.MutationObserverOptions
import com.mohnishraj.aether.core.html.dom.ElementNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserDomApiTest {
    @Test fun querySelectorFindsId() {
        val page = browserRuntime().browser.open(PAGE_HTML, "https://example.test/page")
        assertEquals("main", page.document.querySelector("#app")?.localName)
    }

    @Test fun querySelectorAllSupportsCombinators() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        assertEquals(2, page.document.querySelectorAll("form > input").size)
    }

    @Test fun classNameQueryWorks() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        assertEquals("p", page.document.getElementsByClassName("lead").single().localName)
    }

    @Test fun invalidSelectorFails() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        assertFailsWith<IllegalArgumentException> { page.document.querySelector("[") }
    }

    @Test fun createAppendAndRemoveNodes() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        val body = assertNotNull(page.document.body)
        val section = page.document.createElement("section")
        page.document.appendChild(body, section)
        assertTrue(section.parent === body)
        page.document.removeChild(body, section)
        assertNull(section.parent)
    }

    @Test fun insertBeforePreservesOrder() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        val body = assertNotNull(page.document.body)
        val first = page.document.createElement("aside")
        page.document.insertBefore(body, first, body.firstChild)
        assertTrue(body.firstChild === first)
    }

    @Test fun replaceChildDetachesOldNode() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        val main = assertNotNull(page.document.getElementById("app"))
        val old = main.children.first()
        val replacement = page.document.createElement("header")
        page.document.replaceChild(main, replacement, old)
        assertNull(old.parent)
        assertTrue(replacement.parent === main)
    }

    @Test fun attributesCanBeMutated() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        val main = assertNotNull(page.document.getElementById("app"))
        page.document.setAttribute(main, "data-state", "ready")
        assertEquals("ready", main.getAttribute("data-state"))
        assertTrue(page.document.removeAttribute(main, "data-state"))
        assertFalse(main.hasAttribute("data-state"))
    }

    @Test fun setTextContentReplacesChildren() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        val heading = assertNotNull(page.document.querySelector("h1"))
        page.document.setTextContent(heading, "M8 Ready")
        assertEquals("M8 Ready", heading.textContent)
        assertEquals(1, heading.children.size)
    }

    @Test fun setInnerHtmlParsesAndClonesFragment() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        val main = assertNotNull(page.document.getElementById("app"))
        page.document.setInnerHtml(main, "<section id='new'><b>Done</b></section>")
        assertEquals("Done", page.document.querySelector("#new b")?.textContent)
    }

    @Test fun serializerExposesInnerAndOuterHtml() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        val main = assertNotNull(page.document.getElementById("app"))
        assertTrue(page.document.innerHtml(main).contains("<h1>Aether</h1>"))
        assertTrue(page.document.outerHtml(main).startsWith("<main"))
    }

    @Test fun cloneNodeIsIndependent() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        val main = assertNotNull(page.document.getElementById("app"))
        val clone = page.document.cloneNode(main) as ElementNode
        clone.setAttribute("id", "copy")
        assertEquals("app", main.id)
        assertEquals("copy", clone.id)
    }

    @Test fun mutationObserverReceivesAttributeRecord() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        val main = assertNotNull(page.document.getElementById("app"))
        var records = 0
        page.document.observe(main, MutationObserverOptions(attributes = true, attributeOldValue = true)) { batch ->
            records += batch.size
            assertEquals("shell", batch.single().oldValue)
        }
        page.document.setAttribute(main, "class", "updated")
        assertEquals(1, page.document.deliverMutations())
        assertEquals(1, records)
    }
}
