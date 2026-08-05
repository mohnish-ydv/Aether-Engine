package com.mohnishraj.aether.core.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserTabsHistoryTest {
    @Test fun openTabCreatesUniqueIds() {
        val shell = shellFixture().runtime.shell
        assertNotEquals(shell.openTab(), shell.openTab())
    }

    @Test fun activeTabTracksLatestOpened() {
        val shell = shellFixture().runtime.shell
        val id = shell.openTab()
        assertEquals(id, shell.activeTabId())
    }

    @Test fun activateTabSwitchesActiveState() {
        val shell = shellFixture().runtime.shell
        val first = shell.openTab("https://site.test/one", viewport = shellViewport()).tab.id
        val second = shell.openTab("https://site.test/two", viewport = shellViewport()).tab.id
        shell.activateTab(first, shellViewport())
        assertEquals(first, shell.activeTabId())
        assertTrue(shell.snapshot(first)?.active == true)
        assertFalse(shell.snapshot(second)?.active == true)
    }

    @Test fun backTraversesHistory() {
        val shell = shellFixture().runtime.shell
        val first = shell.openTab("https://site.test/one", viewport = shellViewport())
        shell.navigate(first.tab.id, "https://site.test/two", viewport = shellViewport())
        val back = shell.goBack(shellViewport())
        assertEquals("One", back?.tab?.title)
        assertTrue(back?.tab?.canGoForward == true)
    }

    @Test fun forwardTraversesHistory() {
        val shell = shellFixture().runtime.shell
        val first = shell.openTab("https://site.test/one", viewport = shellViewport())
        shell.navigate(first.tab.id, "https://site.test/two", viewport = shellViewport())
        shell.goBack(shellViewport())
        assertEquals("Two", shell.goForward(shellViewport())?.tab?.title)
    }

    @Test fun newNavigationAfterBackDropsForwardHistory() {
        val shell = shellFixture().runtime.shell
        val first = shell.openTab("https://site.test/one", viewport = shellViewport())
        shell.navigate(first.tab.id, "https://site.test/two", viewport = shellViewport())
        shell.goBack(shellViewport())
        val result = shell.navigate(first.tab.id, "https://site.test/plain", viewport = shellViewport())
        assertFalse(result.tab.canGoForward)
        assertEquals(2, result.tab.historySize)
    }

    @Test fun closeActiveSelectsRemainingTab() {
        val shell = shellFixture().runtime.shell
        val first = shell.openTab("https://site.test/one", viewport = shellViewport()).tab.id
        val second = shell.openTab("https://site.test/two", viewport = shellViewport()).tab.id
        shell.closeTab(second, shellViewport())
        assertEquals(first, shell.activeTabId())
    }

    @Test fun closingLastTabCreatesNewTab() {
        val shell = shellFixture().runtime.shell
        val id = shell.openTab("https://site.test/one", viewport = shellViewport()).tab.id
        val result = shell.closeTab(id, shellViewport())
        assertNotNull(result)
        assertEquals(1, shell.tabCount())
    }

    @Test fun closedTabCanBeReopened() {
        val shell = shellFixture().runtime.shell
        val id = shell.openTab("https://site.test/one", viewport = shellViewport()).tab.id
        shell.closeTab(id, shellViewport())
        val reopened = shell.reopenClosedTab(shellViewport())
        assertNotNull(reopened)
        assertEquals("One", reopened.tab.title)
    }

    @Test fun duplicateCopiesCurrentUrl() {
        val shell = shellFixture().runtime.shell
        val original = shell.openTab("https://site.test/two", viewport = shellViewport())
        val duplicate = shell.duplicateTab(original.tab.id, shellViewport())
        assertNotEquals(original.tab.id, duplicate.tab.id)
        assertEquals(original.tab.url, duplicate.tab.url)
        assertEquals(TabLoadState.COMPLETE, duplicate.tab.loadState)
        assertEquals(TabLoadState.COMPLETE, shell.snapshot(duplicate.tab.id)?.loadState)
    }

    @Test fun unknownTabSnapshotIsNull() {
        assertNull(shellFixture().runtime.shell.snapshot(BrowserTabId(999)))
    }

    @Test fun snapshotsContainAllTabs() {
        val shell = shellFixture().runtime.shell
        shell.openTab()
        shell.openTab()
        assertEquals(2, shell.snapshots().size)
    }
}
