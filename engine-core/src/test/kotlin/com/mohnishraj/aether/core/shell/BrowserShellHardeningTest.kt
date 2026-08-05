package com.mohnishraj.aether.core.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BrowserShellHardeningTest {
    @Test fun maxTabsIsEnforced() {
        val fixture = shellFixture()
        val shell = BrowserShellRuntime(
            fixture.runtime.browser,
            fixture.runtime.render,
            fixture.runtime.network,
            fixture.fileSystem,
            limits = BrowserShellLimits(maxTabs = 1)
        )
        shell.openTab()
        assertFailsWith<IllegalArgumentException> { shell.openTab() }
    }

    @Test fun historyLimitIsEnforced() {
        val fixture = shellFixture()
        val shell = BrowserShellRuntime(
            fixture.runtime.browser,
            fixture.runtime.render,
            fixture.runtime.network,
            fixture.fileSystem,
            limits = BrowserShellLimits(maxHistoryEntriesPerTab = 2)
        )
        val id = shell.openTab("https://site.test/one", viewport = shellViewport()).tab.id
        shell.navigate(id, "https://site.test/two", viewport = shellViewport())
        val result = shell.navigate(id, "https://site.test/plain", viewport = shellViewport())
        assertEquals(2, result.tab.historySize)
    }

    @Test fun closedTabLimitIsEnforced() {
        val fixture = shellFixture()
        val shell = BrowserShellRuntime(
            fixture.runtime.browser,
            fixture.runtime.render,
            fixture.runtime.network,
            fixture.fileSystem,
            limits = BrowserShellLimits(maxClosedTabs = 1)
        )
        val first = shell.openTab("https://site.test/one", viewport = shellViewport()).tab.id
        val second = shell.openTab("https://site.test/two", viewport = shellViewport()).tab.id
        shell.closeTab(first, shellViewport())
        shell.closeTab(second, shellViewport())
        assertEquals(1, shell.closedTabCount())
    }

    @Test fun stopLoadingInvalidatesInFlightToken() {
        val shell = shellFixture().runtime.shell
        val id = shell.openTab()
        shell.stopLoading(id)
        assertTrue(shell.snapshot(id)?.loadState in setOf(TabLoadState.NEW, TabLoadState.COMPLETE))
    }

    @Test fun sessionSaveCreatesVirtualFile() {
        val fixture = shellFixture()
        fixture.runtime.shell.openTab("https://site.test/one", viewport = shellViewport())
        fixture.runtime.shell.saveSession()
        assertTrue(fixture.fileSystem.exists(com.mohnishraj.aether.core.fs.VirtualPath.of("/browser/session/m10.bin")))
    }

    @Test fun sessionRestoresTabs() {
        val fixture = shellFixture()
        fixture.runtime.shell.openTab("https://site.test/one", viewport = shellViewport())
        val restored = BrowserShellRuntime(
            fixture.runtime.browser,
            fixture.runtime.render,
            fixture.runtime.network,
            fixture.fileSystem
        )
        assertTrue(restored.restoreSession())
        assertEquals(1, restored.tabCount())
    }

    @Test fun clearSessionRemovesPersistedState() {
        val fixture = shellFixture()
        fixture.runtime.shell.openTab()
        fixture.runtime.shell.clearSession()
        val restored = BrowserShellRuntime(fixture.runtime.browser, fixture.runtime.render, fixture.runtime.network, fixture.fileSystem)
        assertFalse(restored.restoreSession())
    }

    @Test fun activePageExistsAfterNavigation() {
        val shell = shellFixture().runtime.shell
        shell.openTab("https://site.test/one", viewport = shellViewport())
        assertNotNull(shell.activePage())
    }

    @Test fun activeRenderSessionExistsAfterNavigation() {
        val shell = shellFixture().runtime.shell
        shell.openTab("https://site.test/one", viewport = shellViewport())
        assertNotNull(shell.activeRenderSession())
    }

    @Test fun navigationProgressCompletes() {
        val result = shellFixture().runtime.shell.openTab("https://site.test/one", viewport = shellViewport())
        assertEquals(100, result.tab.progress)
    }

    @Test fun unsupportedSchemeIsRejected() {
        assertFailsWith<IllegalArgumentException> { AddressResolver().resolve("ftp://example.com/file") }
    }

    @Test fun releasingRenderSessionsClearsActivityBoundState() {
        val shell = shellFixture().runtime.shell
        shell.openTab("https://site.test/one", viewport = shellViewport())
        assertNotNull(shell.activeRenderSession())
        shell.releaseRenderSessions()
        assertEquals(null, shell.activeRenderSession())
        assertEquals(null, shell.activePage())
    }

    @Test fun tabCanRebuildRendererAfterActivityRecreation() {
        val shell = shellFixture().runtime.shell
        val loaded = shell.openTab("https://site.test/two", viewport = shellViewport())
        shell.releaseRenderSessions()
        val rebuilt = shell.activateTab(loaded.tab.id, shellViewport())
        assertTrue(rebuilt.committed)
        assertNotNull(rebuilt.renderSession)
        assertEquals(TabLoadState.COMPLETE, rebuilt.tab.loadState)
    }

    @Test fun shellStatisticsTrackTabsClosed() {
        val shell = shellFixture().runtime.shell
        val id = shell.openTab()
        shell.closeTab(id, shellViewport())
        assertTrue(shell.statistics().tabsClosed >= 1)
    }
}
