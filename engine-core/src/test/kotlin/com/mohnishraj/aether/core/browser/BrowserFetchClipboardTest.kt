package com.mohnishraj.aether.core.browser

import com.mohnishraj.aether.core.browser.fetch.BrowserFetchException
import com.mohnishraj.aether.core.browser.fetch.BrowserFetchRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserFetchClipboardTest {
    @Test fun fetchBridgeReadsTextResponse() {
        val page = browserRuntimeWithNetwork().browser.open(PAGE_HTML, "https://example.test/base")
        val response = page.fetch.fetch(BrowserFetchRequest("/hello"), page.url)
        assertTrue(response.ok)
        assertEquals("Aether /hello", response.text())
    }

    @Test fun fetchBridgeResolvesRelativeUrl() {
        val page = browserRuntimeWithNetwork().browser.open(PAGE_HTML, "https://example.test/path/page")
        val response = page.fetch.fetch(BrowserFetchRequest("../json"), page.url)
        assertEquals("https://example.test/json", response.url)
    }

    @Test fun fetchBridgeSupportsPostBody() {
        val page = browserRuntimeWithNetwork().browser.open(PAGE_HTML)
        val response = page.fetch.fetch(BrowserFetchRequest("/echo", method = "POST", body = "payload"), page.url)
        assertEquals("payload", response.text())
    }

    @Test fun fetchBridgeRejectsBodyForGet() {
        val page = browserRuntimeWithNetwork().browser.open(PAGE_HTML)
        assertFailsWith<BrowserFetchException> {
            page.fetch.fetch(BrowserFetchRequest("/echo", method = "GET", body = "bad"), page.url)
        }
    }

    @Test fun fetchBridgeFailsWithoutNetworkRuntime() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        assertFailsWith<BrowserFetchException> { page.fetch.fetch(BrowserFetchRequest("https://example.test/"), page.url) }
    }

    @Test fun fetchSyncIsAvailableToJavaScript() {
        val page = browserRuntimeWithNetwork().browser.open(PAGE_HTML, "https://example.test/")
        val result = page.evaluate("fetchSync('/json').text();", freshRealm = true)
        assertTrue(result.success)
        assertEquals("{\"milestone\":\"M8\"}", result.value.displayString())
    }

    @Test fun fetchSyncExposesStatusAndOk() {
        val page = browserRuntimeWithNetwork().browser.open(PAGE_HTML, "https://example.test/")
        val result = page.evaluate("let r=fetchSync('/hello'); r.status + ':' + r.ok;", freshRealm = true)
        assertEquals("200:true", result.value.displayString())
    }

    @Test fun clipboardPortRoundTrips() {
        val runtime = browserRuntime()
        runtime.browser.clipboard.writeText("M8")
        assertEquals("M8", runtime.browser.clipboard.readText())
    }

    @Test fun clipboardLimitIsEnforcedInJavaScript() {
        val runtime = browserRuntime()
        val page = runtime.browser.open(PAGE_HTML)
        val result = page.evaluate("navigator.clipboard.writeText('x'); navigator.clipboard.readText();", freshRealm = true)
        assertTrue(result.success)
        assertFalse(result.value.displayString().isEmpty())
    }

    @Test fun browserFetchCounterIncrements() {
        val runtime = browserRuntimeWithNetwork()
        val page = runtime.browser.open(PAGE_HTML, "https://example.test/")
        page.fetch.fetch(BrowserFetchRequest("https://example.test/hello"))
        assertEquals(1, runtime.browser.statistics().fetches)
    }
}
