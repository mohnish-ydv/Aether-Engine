package com.mohnishraj.aether.core.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BrowserScriptExecutionTest {
    @Test fun inlineAndDeferredExternalScriptsExecuteBeforeFirstFrame() {
        val fixture = shellFixture()
        val tab = fixture.runtime.shell.openTab(activate = true)
        val result = fixture.runtime.shell.navigate(tab, "https://site.test/scripted", viewport = shellViewport())
        val page = assertNotNull(result.page)
        assertEquals("Inline Ran", page.documentTitle())
        assertEquals("external", page.document.getElementById("state")?.textContent)
        assertEquals("yes", page.document.body?.getAttribute("data-js"))
        assertEquals("complete", page.readyState)
    }

    @Test fun externalScriptUsesDocumentRelativeUrl() {
        val fixture = shellFixture()
        val tab = fixture.runtime.shell.openTab(activate = true)
        fixture.runtime.shell.navigate(tab, "https://site.test/scripted", viewport = shellViewport())
        assertTrue(fixture.requestedUrls.any { it == "https://site.test/assets/app.js" })
    }

    @Test fun documentScriptsRemainAttachedWhenTabIsDuplicated() {
        val fixture = shellFixture()
        val tab = fixture.runtime.shell.openTab(activate = true)
        fixture.runtime.shell.navigate(tab, "https://site.test/scripted", viewport = shellViewport())
        val duplicate = fixture.runtime.shell.duplicateTab(tab, shellViewport())
        assertEquals("external", duplicate.page?.document?.getElementById("state")?.textContent)
    }

    @Test fun timerMutationInvalidatesRenderSession() {
        val fixture = shellFixture()
        val tab = fixture.runtime.shell.openTab(activate = true)
        val result = fixture.runtime.shell.navigate(tab, "https://site.test/timed", viewport = shellViewport())
        val session = assertNotNull(result.renderSession)
        session.renderNow()
        assertTrue(session.hasPendingFrame())
        result.page?.advanceTimeBy(10)
        val frame = session.renderNow()
        assertEquals("1", result.page?.document?.getElementById("clock")?.textContent)
        assertTrue(frame.generation >= 2)
    }

    @Test fun scriptFailuresDoNotDiscardLoadedDocument() {
        val fixture = shellFixture()
        val tab = fixture.runtime.shell.openTab(activate = true)
        val result = fixture.runtime.shell.navigate(tab, "https://site.test/scripted", viewport = shellViewport())
        assertTrue(result.committed)
        assertNotNull(result.renderSession?.renderNow())
    }
}
