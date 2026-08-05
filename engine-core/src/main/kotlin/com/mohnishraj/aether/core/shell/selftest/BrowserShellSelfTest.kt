package com.mohnishraj.aether.core.shell.selftest

import com.mohnishraj.aether.core.EngineRuntime
import com.mohnishraj.aether.core.browser.BrowserApiRuntime
import com.mohnishraj.aether.core.fs.MemoryFileSystem
import com.mohnishraj.aether.core.render.RenderPipeline
import com.mohnishraj.aether.core.render.RenderViewport
import com.mohnishraj.aether.core.selftest.SelfTestCheck
import com.mohnishraj.aether.core.shell.AddressResolver
import com.mohnishraj.aether.core.shell.BrowserHistoryEntry
import com.mohnishraj.aether.core.shell.BrowserSessionCodec
import com.mohnishraj.aether.core.shell.BrowserSessionSnapshot
import com.mohnishraj.aether.core.shell.BrowserShellRuntime
import com.mohnishraj.aether.core.shell.BrowserTabId
import com.mohnishraj.aether.core.shell.ClosedTabSnapshot
import com.mohnishraj.aether.core.shell.NavigationTransition
import com.mohnishraj.aether.core.shell.PersistedTab
import com.mohnishraj.aether.core.shell.SecurityIndicator
import com.mohnishraj.aether.core.shell.TabLoadState

object BrowserShellSelfTest {
    fun run(runtime: EngineRuntime): List<SelfTestCheck> {
        val checks = mutableListOf<SelfTestCheck>()
        fun check(name: String, block: () -> String) {
            val result = runCatching(block)
            checks += if (result.isSuccess) SelfTestCheck(name, true, result.getOrThrow())
            else SelfTestCheck(name, false, result.exceptionOrNull()?.message ?: "unknown error")
        }

        val fileSystem = MemoryFileSystem()
        val browser = BrowserApiRuntime(fileSystem, null, runtime.html, runtime.js, runtime.logger, runtime.profiler)
        val render = RenderPipeline(runtime.css, runtime.layout, runtime.paint, runtime.logger, runtime.profiler)
        val shell = BrowserShellRuntime(browser, render, null, fileSystem, runtime.logger, runtime.profiler, clockMillis = { 10L })
        val viewport = RenderViewport(360.0, 640.0)

        check("shell address home") {
            require(AddressResolver().resolve("about:blank").internal)
            "internal new-tab URL resolved"
        }
        check("shell address direct") {
            require(AddressResolver().resolve("example.com").url == "https://example.com/")
            "domain upgraded to HTTPS"
        }
        check("shell address search") {
            require(AddressResolver().resolve("aether browser").wasSearch)
            "query encoded into search URL"
        }
        check("shell first tab") {
            val id = shell.openTab()
            require(id.value > 0L && shell.tabCount() == 1)
            "tab id=$id"
        }
        check("shell home navigation") {
            val id = shell.activeTabId() ?: error("missing tab")
            val result = shell.navigate(id, "about:blank", viewport = viewport)
            require(result.committed && result.tab.loadState == TabLoadState.COMPLETE)
            "${result.tab.title}, history=${result.tab.historySize}"
        }
        check("shell internal security") {
            require(shell.activeSnapshot()?.security == SecurityIndicator.INTERNAL)
            "internal security indicator"
        }
        check("shell rendering") {
            val frame = shell.activeRenderSession()?.renderNow(1_000_000_000L) ?: error("missing session")
            require(frame.composition.layerCount > 0)
            "frame=${frame.generation}, layers=${frame.composition.layerCount}"
        }
        check("shell home text separation") {
            val frame = shell.activeRenderSession()?.renderNow(1_016_000_000L) ?: error("missing session")
            val text = frame.displayList.commands.filterIsInstance<com.mohnishraj.aether.core.paint.PaintCommand.DrawText>()
            val distinctOrigins = text.filter { it.text.isNotBlank() }.map { it.rect.x to (it.rect.y + it.baselinePx) }.distinct().size
            require(text.size >= 8 && distinctOrigins == text.filter { it.text.isNotBlank() }.size)
            "${text.size} text fragments occupy distinct paint origins"
        }
        check("shell multi-tab") {
            shell.openTab("about:blank", viewport = viewport)
            require(shell.tabCount() == 2)
            "two tabs retained"
        }
        check("shell tab activation") {
            val first = shell.snapshots().first().id
            shell.activateTab(first, viewport)
            require(shell.activeTabId() == first)
            "activated tab $first"
        }
        check("shell duplicate") {
            val duplicate = shell.duplicateTab(viewport = viewport)
            require(duplicate.tab.active && shell.tabCount() == 3)
            "duplicated into tab ${duplicate.tab.id}"
        }
        check("shell close") {
            val active = shell.activeTabId() ?: error("missing active")
            shell.closeTab(active, viewport)
            require(shell.tabCount() == 2 && shell.closedTabCount() == 1)
            "closed tab retained for undo"
        }
        check("shell reopen") {
            val reopened = shell.reopenClosedTab(viewport) ?: error("not reopened")
            require(reopened.tab.active && shell.closedTabCount() == 0)
            "reopened tab ${reopened.tab.id}"
        }
        check("shell failed navigation page") {
            val active = shell.activeTabId() ?: error("missing active")
            val failed = shell.navigate(active, "https://offline.invalid/", viewport = viewport)
            require(failed.tab.loadState == TabLoadState.FAILED && failed.page != null)
            "typed failure rendered safely"
        }
        check("shell history back") {
            val back = shell.goBack(viewport) ?: error("no back entry")
            require(back.tab.canGoForward)
            "back traversal retained forward entry"
        }
        check("shell history forward") {
            val forward = shell.goForward(viewport) ?: error("no forward entry")
            require(forward.tab.url == "https://offline.invalid/")
            "forward traversal restored URL"
        }
        check("shell session codec") {
            val entry = BrowserHistoryEntry("https://a.test/", "A", 1L, NavigationTransition.TYPED)
            val snapshot = BrowserSessionSnapshot(BrowserTabId(1), listOf(PersistedTab(BrowserTabId(1), listOf(entry), 0)), listOf(ClosedTabSnapshot("A", listOf(entry), 0)))
            require(BrowserSessionCodec.decode(BrowserSessionCodec.encode(snapshot)) == snapshot)
            "session binary codec round-trip"
        }
        check("shell session restore") {
            shell.saveSession()
            val restored = BrowserShellRuntime(browser, render, null, fileSystem)
            require(restored.restoreSession() && restored.tabCount() == shell.tabCount())
            "${restored.tabCount()} tabs restored"
        }
        check("shell render release") {
            shell.releaseRenderSessions()
            require(shell.activeRenderSession() == null && shell.activePage() == null)
            "activity-bound render state released"
        }
        check("shell render rebuild") {
            val active = shell.activeTabId() ?: error("missing active")
            val rebuilt = shell.activateTab(active, viewport)
            require(rebuilt.committed && rebuilt.renderSession != null)
            "renderer rebuilt for tab $active"
        }

        check("shell statistics") {
            val stats = shell.statistics()
            require(stats.tabsOpened >= 3 && stats.navigationsStarted >= 2 && stats.sessionSaves > 0)
            "tabs=${stats.tabsOpened}, navigations=${stats.navigationsStarted}, saves=${stats.sessionSaves}"
        }

        render.closeCurrentSession()
        return checks
    }
}
