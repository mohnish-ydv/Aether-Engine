package com.mohnishraj.aether.core.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BrowserNavigationTest {
    @Test fun ensureStartedLoadsInternalHome() {
        val fixture = shellFixture()
        val result = fixture.runtime.shell.ensureStarted(shellViewport())
        assertTrue(result.committed)
        assertEquals("Aether New Tab", result.tab.title)
        assertEquals(SecurityIndicator.INTERNAL, result.tab.security)
    }

    @Test fun networkNavigationLoadsHtmlTitle() {
        val fixture = shellFixture()
        val id = fixture.runtime.shell.openTab()
        val result = fixture.runtime.shell.navigate(id, "https://site.test/one", viewport = shellViewport())
        assertEquals("One", result.tab.title)
        assertEquals(TabLoadState.COMPLETE, result.tab.loadState)
        assertNotNull(result.renderSession)
    }

    @Test fun embeddedStyleIsAppliedToSession() {
        val fixture = shellFixture()
        val result = fixture.runtime.shell.openTab("https://site.test/one", viewport = shellViewport())
        assertTrue("body{color:#fff}" in (result.renderSession?.styleSheetSource() ?: ""))
    }


    @Test fun externalStylesheetsBaseAndImportsAreLoadedInDocumentOrder() {
        val fixture = shellFixture()
        val result = fixture.runtime.shell.openTab("https://site.test/styled", viewport = shellViewport())
        val source = result.renderSession?.styleSheetSource().orEmpty()
        assertTrue(".hero{padding-inline:12px" in source)
        assertTrue(".hero{display:flex" in source)
        assertTrue(".inline{color:#123456}" in source)
        assertTrue("https://site.test/assets/theme.css" in fixture.requestedUrls)
        assertTrue("https://site.test/assets/nested.css" in fixture.requestedUrls)
        assertTrue(source.indexOf("padding-inline") < source.indexOf("display:flex"))
        assertTrue(source.indexOf("display:flex") < source.indexOf(".inline"))
    }

    @Test fun plainTextIsWrappedSafely() {
        val fixture = shellFixture()
        val result = fixture.runtime.shell.openTab("https://site.test/plain", viewport = shellViewport())
        val text = result.page?.document?.body?.textContent.orEmpty()
        assertTrue("plain <text> & safe" in text)
    }

    @Test fun binaryResponseGetsUnsupportedPage() {
        val fixture = shellFixture()
        val result = fixture.runtime.shell.openTab("https://site.test/binary", viewport = shellViewport())
        assertTrue("Unsupported document" in result.page?.document?.body?.textContent.orEmpty())
    }

    @Test fun httpUrlGetsInsecureIndicator() {
        val fixture = shellFixture()
        val result = fixture.runtime.shell.openTab("http://site.test/one", viewport = shellViewport())
        assertEquals(SecurityIndicator.INSECURE, result.tab.security)
    }

    @Test fun httpsUrlGetsSecureIndicator() {
        val fixture = shellFixture()
        val result = fixture.runtime.shell.openTab("https://site.test/one", viewport = shellViewport())
        assertEquals(SecurityIndicator.SECURE, result.tab.security)
    }

    @Test fun firstNavigationReplacesNewTabHistory() {
        val fixture = shellFixture()
        val result = fixture.runtime.shell.openTab("https://site.test/one", viewport = shellViewport())
        assertEquals(1, result.tab.historySize)
        assertFalse(result.tab.canGoBack)
    }

    @Test fun secondNavigationAddsHistory() {
        val fixture = shellFixture()
        val first = fixture.runtime.shell.openTab("https://site.test/one", viewport = shellViewport())
        val second = fixture.runtime.shell.navigate(first.tab.id, "https://site.test/two", viewport = shellViewport())
        assertEquals(2, second.tab.historySize)
        assertTrue(second.tab.canGoBack)
    }

    @Test fun reloadDoesNotAddHistory() {
        val fixture = shellFixture()
        fixture.runtime.shell.openTab("https://site.test/one", viewport = shellViewport())
        val result = fixture.runtime.shell.reload(shellViewport())
        assertEquals(1, result.tab.historySize)
    }

    @Test fun failedNetworkShowsErrorDocument() {
        val runtime = com.mohnishraj.aether.core.browser.browserRuntime()
        val id = runtime.shell.openTab()
        val result = runtime.shell.navigate(id, "https://offline.test/", viewport = shellViewport())
        assertEquals(TabLoadState.FAILED, result.tab.loadState)
        assertTrue("Page could not load" in result.page?.document?.body?.textContent.orEmpty())
    }

    @Test fun statisticsTrackNavigation() {
        val fixture = shellFixture()
        fixture.runtime.shell.openTab("https://site.test/one", viewport = shellViewport())
        val stats = fixture.runtime.shell.statistics()
        assertTrue(stats.navigationsStarted >= 1)
        assertTrue(stats.navigationsCommitted >= 1)
    }
}
