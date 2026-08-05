package com.mohnishraj.aether.core.shell

import com.mohnishraj.aether.core.browser.BrowserPage
import com.mohnishraj.aether.core.render.RenderSession
import com.mohnishraj.aether.core.security.DocumentSecurityPolicy
import java.util.Collections

@JvmInline
value class BrowserTabId(val value: Long) {
    init { require(value > 0L) }
    override fun toString(): String = value.toString()
}

enum class TabLoadState { NEW, LOADING, COMPLETE, FAILED }
enum class NavigationTransition { TYPED, LINK, RELOAD, BACK_FORWARD, RESTORE, DUPLICATE }
enum class SecurityIndicator { INTERNAL, SECURE, INSECURE }

data class BrowserHistoryEntry(
    val url: String,
    val title: String,
    val visitedAtMillis: Long,
    val transition: NavigationTransition
) {
    init {
        require(url.isNotBlank())
        require(title.length <= 512)
        require(visitedAtMillis >= 0L)
    }
}

data class BrowserTabSnapshot(
    val id: BrowserTabId,
    val title: String,
    val url: String,
    val loadState: TabLoadState,
    val progress: Int,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    val historySize: Int,
    val active: Boolean,
    val security: SecurityIndicator,
    val errorMessage: String? = null
) {
    init { require(progress in 0..100) }
}

data class ClosedTabSnapshot(
    val title: String,
    val entries: List<BrowserHistoryEntry>,
    val currentIndex: Int
) {
    init {
        require(entries.isNotEmpty())
        require(currentIndex in entries.indices)
    }
}

data class BrowserSessionSnapshot(
    val activeTabId: BrowserTabId?,
    val tabs: List<PersistedTab>,
    val closedTabs: List<ClosedTabSnapshot>
) {
    init {
        require(tabs.map(PersistedTab::id).distinct().size == tabs.size) { "Duplicate tab identifiers" }
        require(activeTabId == null || tabs.any { it.id == activeTabId }) { "Active tab is not present" }
    }
}

data class PersistedTab(
    val id: BrowserTabId,
    val entries: List<BrowserHistoryEntry>,
    val currentIndex: Int,
    val pinned: Boolean = false
) {
    init {
        require(entries.isNotEmpty())
        require(currentIndex in entries.indices)
    }
}

data class BrowserShellLimits(
    val maxTabs: Int = 32,
    val maxHistoryEntriesPerTab: Int = 250,
    val maxClosedTabs: Int = 20,
    val maxMarkupChars: Int = 8_000_000,
    val maxStyleChars: Int = 2_000_000,
    val maxScriptChars: Int = 4_000_000,
    val maxScripts: Int = 128,
    val maxTitleChars: Int = 512,
    val maxPersistedTabs: Int = maxTabs,
    val maxSessionBytes: Int = 2_000_000
) {
    init {
        require(maxTabs in 1..256)
        require(maxHistoryEntriesPerTab in 1..10_000)
        require(maxClosedTabs in 0..1_000)
        require(maxMarkupChars > 0 && maxStyleChars > 0 && maxScriptChars > 0 && maxScripts in 1..1_024)
        require(maxTitleChars in 1..4_096)
        require(maxPersistedTabs in 1..maxTabs)
        require(maxSessionBytes in 1_024..32_000_000)
    }
}

data class ResolvedAddress(
    val originalInput: String,
    val url: String,
    val displayText: String,
    val wasSearch: Boolean,
    val internal: Boolean
)

data class PageScript(
    val source: String,
    val sourceUrl: String?,
    val nonce: String?,
    val external: Boolean,
    val defer: Boolean,
    val async: Boolean
)

data class LoadedDocument(
    val url: String,
    val title: String,
    val markup: String,
    val styleSheet: String,
    val statusCode: Int,
    val fromCache: Boolean,
    val security: SecurityIndicator,
    val policy: DocumentSecurityPolicy = DocumentSecurityPolicy.permissive(url),
    val scripts: List<PageScript> = emptyList()
)

data class BrowserNavigationResult(
    val tab: BrowserTabSnapshot,
    val page: BrowserPage?,
    val renderSession: RenderSession?,
    val committed: Boolean,
    val stale: Boolean = false
)

data class BrowserShellStatistics(
    val tabsOpened: Long,
    val tabsClosed: Long,
    val navigationsStarted: Long,
    val navigationsCommitted: Long,
    val navigationsFailed: Long,
    val historyTraversals: Long,
    val sessionSaves: Long,
    val sessionRestores: Long
)

internal data class MutableBrowserTab(
    val id: BrowserTabId,
    val history: MutableList<BrowserHistoryEntry>,
    var currentIndex: Int,
    var title: String,
    var url: String,
    var loadState: TabLoadState = TabLoadState.NEW,
    var progress: Int = 0,
    var errorMessage: String? = null,
    var markup: String = "",
    var styleSheet: String = "",
    var scripts: List<PageScript> = emptyList(),
    var page: BrowserPage? = null,
    var renderSession: RenderSession? = null,
    var securityPolicy: DocumentSecurityPolicy? = null,
    var navigationToken: Long = 0L,
    var pinned: Boolean = false
) {
    fun immutableHistory(): List<BrowserHistoryEntry> = Collections.unmodifiableList(history.toList())
}

internal data class NavigationPlan(
    val tabId: BrowserTabId,
    val token: Long,
    val address: ResolvedAddress,
    val transition: NavigationTransition,
    val targetHistoryIndex: Int? = null
)
