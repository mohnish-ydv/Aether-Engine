package com.mohnishraj.aether.core.shell

import com.mohnishraj.aether.core.browser.BrowserApiRuntime
import com.mohnishraj.aether.core.browser.BrowserPage
import com.mohnishraj.aether.core.browser.features.BrowsingHistory
import com.mohnishraj.aether.core.fs.FileSystem
import com.mohnishraj.aether.core.log.EngineLogger
import com.mohnishraj.aether.core.net.NetworkRuntime
import com.mohnishraj.aether.core.net.model.AetherUrl
import com.mohnishraj.aether.core.net.model.CachePolicy
import com.mohnishraj.aether.core.net.model.NetworkRequest
import com.mohnishraj.aether.core.net.model.NetworkResult
import com.mohnishraj.aether.core.profile.PerformanceProfiler
import com.mohnishraj.aether.core.render.RenderPipeline
import com.mohnishraj.aether.core.render.RenderSession
import com.mohnishraj.aether.core.render.RenderViewport
import com.mohnishraj.aether.core.security.DocumentSecurityPolicy
import com.mohnishraj.aether.core.security.SecurityEngine
import com.mohnishraj.aether.core.security.SecurityResourceType
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class BrowserShellRuntime(
    private val browser: BrowserApiRuntime,
    private val render: RenderPipeline,
    private val network: NetworkRuntime?,
    fileSystem: FileSystem,
    private val logger: EngineLogger? = null,
    private val profiler: PerformanceProfiler? = null,
    val resolver: AddressResolver = AddressResolver(),
    val limits: BrowserShellLimits = BrowserShellLimits(),
    private val clockMillis: () -> Long = System::currentTimeMillis,
    val security: SecurityEngine = browser.security,
    private val browsingHistory: BrowsingHistory? = null,
    private val sessionPersistenceEnabled: Boolean = true
) {
    private val lock = Any()
    private val tabs = LinkedHashMap<BrowserTabId, MutableBrowserTab>()
    private val closedTabs = ArrayDeque<ClosedTabSnapshot>()
    private val nextTabId = AtomicLong(0L)
    private val navigationSequence = AtomicLong(0L)
    private val renderEpoch = AtomicLong(0L)
    private val store = BrowserSessionStore(fileSystem, limits)
    private var activeTabId: BrowserTabId? = null
    private val tabsOpened = AtomicLong()
    private val tabsClosed = AtomicLong()
    private val navigationsStarted = AtomicLong()
    private val navigationsCommitted = AtomicLong()
    private val navigationsFailed = AtomicLong()
    private val historyTraversals = AtomicLong()
    private val sessionSaves = AtomicLong()
    private val sessionRestores = AtomicLong()

    fun ensureStarted(viewport: RenderViewport = RenderViewport()): BrowserNavigationResult {
        val existing = synchronized(lock) { activeTabId }
        if (existing != null) return activateTab(existing, viewport)
        val restored = restoreSession(loadActive = false)
        val active = synchronized(lock) { activeTabId }
        if (restored && active != null) return navigateCurrent(NavigationTransition.RESTORE, viewport)
        val id = openTab(activate = true)
        return navigate(id, AddressResolver.HOME_URL, NavigationTransition.TYPED, viewport)
    }

    fun openTab(activate: Boolean = true, pinned: Boolean = false): BrowserTabId = synchronized(lock) {
        require(tabs.size < limits.maxTabs) { "Maximum tab count ${limits.maxTabs} reached" }
        val id = BrowserTabId(nextTabId.incrementAndGet())
        val initial = BrowserHistoryEntry(AddressResolver.HOME_URL, "New tab", clockMillis().coerceAtLeast(0L), NavigationTransition.TYPED)
        tabs[id] = MutableBrowserTab(
            id = id,
            history = mutableListOf(initial),
            currentIndex = 0,
            title = initial.title,
            url = initial.url,
            pinned = pinned
        )
        if (activate || activeTabId == null) activeTabId = id
        tabsOpened.incrementAndGet()
        logger?.info("BrowserShell", "Opened tab $id")
        persistLocked()
        id
    }

    fun openTab(input: String, activate: Boolean = true, viewport: RenderViewport = RenderViewport()): BrowserNavigationResult {
        val id = openTab(activate)
        return navigate(id, input, NavigationTransition.TYPED, viewport)
    }

    fun navigate(
        tabId: BrowserTabId,
        input: String,
        transition: NavigationTransition = NavigationTransition.TYPED,
        viewport: RenderViewport = RenderViewport(),
        cachePolicy: CachePolicy = CachePolicy.DEFAULT
    ): BrowserNavigationResult {
        val plan = beginNavigation(tabId, input, transition)
        val loaded = profiler?.measure("shell.navigate") { load(plan.address, cachePolicy) } ?: load(plan.address, cachePolicy)
        return commitNavigation(plan, loaded, viewport)
    }

    fun reload(viewport: RenderViewport = RenderViewport(), bypassCache: Boolean = false): BrowserNavigationResult {
        val tab = activeMutable() ?: return ensureStarted(viewport)
        return navigate(tab.id, tab.url, NavigationTransition.RELOAD, viewport, if (bypassCache) CachePolicy.NETWORK_ONLY else CachePolicy.DEFAULT)
    }

    fun goBack(viewport: RenderViewport = RenderViewport()): BrowserNavigationResult? {
        val plan = synchronized(lock) {
            val tab = activeMutableLocked() ?: return null
            if (tab.currentIndex <= 0) return null
            historyTraversals.incrementAndGet()
            beginHistoryNavigationLocked(tab, tab.currentIndex - 1)
        }
        val loaded = load(plan.address, CachePolicy.DEFAULT)
        return commitNavigation(plan, loaded, viewport)
    }

    fun goForward(viewport: RenderViewport = RenderViewport()): BrowserNavigationResult? {
        val plan = synchronized(lock) {
            val tab = activeMutableLocked() ?: return null
            if (tab.currentIndex >= tab.history.lastIndex) return null
            historyTraversals.incrementAndGet()
            beginHistoryNavigationLocked(tab, tab.currentIndex + 1)
        }
        val loaded = load(plan.address, CachePolicy.DEFAULT)
        return commitNavigation(plan, loaded, viewport)
    }

    fun activateTab(tabId: BrowserTabId, viewport: RenderViewport = RenderViewport()): BrowserNavigationResult {
        val tab = synchronized(lock) {
            val selected = tabs[tabId] ?: throw IllegalArgumentException("Unknown tab $tabId")
            activeTabId = tabId
            selected
        }
        val pageAndSession = if (tab.markup.isNotEmpty()) activateDocument(tab, viewport) else null
        synchronized(lock) { persistLocked() }
        val snapshot = snapshot(tabId) ?: error("Tab disappeared")
        return BrowserNavigationResult(snapshot, pageAndSession?.first, pageAndSession?.second, committed = pageAndSession != null)
    }

    fun closeTab(tabId: BrowserTabId, viewport: RenderViewport = RenderViewport()): BrowserNavigationResult? {
        val next = synchronized(lock) {
            val tab = tabs.remove(tabId) ?: return null
            tab.renderSession?.close()
            tab.renderSession = null
            closedTabs.addFirst(ClosedTabSnapshot(tab.title, tab.immutableHistory(), tab.currentIndex))
            while (closedTabs.size > limits.maxClosedTabs) closedTabs.removeLast()
            tabsClosed.incrementAndGet()
            if (activeTabId == tabId) activeTabId = tabs.keys.lastOrNull()
            logger?.info("BrowserShell", "Closed tab $tabId")
            persistLocked()
            activeTabId
        }
        if (next == null) {
            val id = openTab(activate = true)
            return navigate(id, AddressResolver.HOME_URL, NavigationTransition.TYPED, viewport)
        }
        return activateTab(next, viewport)
    }

    fun reopenClosedTab(viewport: RenderViewport = RenderViewport()): BrowserNavigationResult? {
        val restoredId = synchronized(lock) {
            if (closedTabs.isEmpty()) return null
            val closed = closedTabs.removeFirst()
            require(tabs.size < limits.maxTabs) { "Maximum tab count ${limits.maxTabs} reached" }
            val id = BrowserTabId(nextTabId.incrementAndGet())
            val current = closed.entries[closed.currentIndex]
            tabs[id] = MutableBrowserTab(
                id = id,
                history = closed.entries.toMutableList(),
                currentIndex = closed.currentIndex,
                title = current.title,
                url = current.url
            )
            activeTabId = id
            tabsOpened.incrementAndGet()
            persistLocked()
            id
        }
        return navigateCurrent(NavigationTransition.RESTORE, viewport, restoredId)
    }

    fun duplicateTab(tabId: BrowserTabId = activeTabId() ?: error("No active tab"), viewport: RenderViewport = RenderViewport()): BrowserNavigationResult {
        val duplicateId = synchronized(lock) {
            val source = tabs[tabId] ?: throw IllegalArgumentException("Unknown tab $tabId")
            require(tabs.size < limits.maxTabs) { "Maximum tab count ${limits.maxTabs} reached" }
            val id = BrowserTabId(nextTabId.incrementAndGet())
            tabs[id] = MutableBrowserTab(
                id = id,
                history = source.history.toMutableList(),
                currentIndex = source.currentIndex,
                title = source.title,
                url = source.url,
                loadState = if (source.markup.isEmpty()) TabLoadState.NEW else TabLoadState.COMPLETE,
                progress = if (source.markup.isEmpty()) 0 else 100,
                errorMessage = source.errorMessage,
                markup = source.markup,
                styleSheet = source.styleSheet,
                scripts = source.scripts,
                securityPolicy = source.securityPolicy
            )
            activeTabId = id
            tabsOpened.incrementAndGet()
            persistLocked()
            id
        }
        val result = activateTab(duplicateId, viewport)
        return result.copy(tab = result.tab.copy(loadState = TabLoadState.COMPLETE))
    }

    fun stopLoading(tabId: BrowserTabId? = activeTabId()) {
        val resolvedTabId = tabId ?: return
        synchronized(lock) {
            val tab = tabs[resolvedTabId] ?: return
            tab.navigationToken = navigationSequence.incrementAndGet()
            if (tab.loadState == TabLoadState.LOADING) {
                tab.loadState = if (tab.markup.isEmpty()) TabLoadState.NEW else TabLoadState.COMPLETE
                tab.progress = if (tab.markup.isEmpty()) 0 else 100
            }
        }
    }

    fun snapshots(): List<BrowserTabSnapshot> = synchronized(lock) {
        tabs.values.map { snapshotLocked(it) }
    }

    fun snapshot(tabId: BrowserTabId): BrowserTabSnapshot? = synchronized(lock) { tabs[tabId]?.let(::snapshotLocked) }
    fun activeSnapshot(): BrowserTabSnapshot? = synchronized(lock) { activeMutableLocked()?.let(::snapshotLocked) }
    fun activeTabId(): BrowserTabId? = synchronized(lock) { activeTabId }
    fun tabCount(): Int = synchronized(lock) { tabs.size }
    fun closedTabCount(): Int = synchronized(lock) { closedTabs.size }
    fun activePage(): BrowserPage? = synchronized(lock) { activeMutableLocked()?.page }
    fun activeRenderSession(): RenderSession? = synchronized(lock) { activeMutableLocked()?.renderSession }

    /**
     * Releases Activity-bound rendering resources while retaining tab history and page source.
     * The active tab will rebuild its renderer on the next activation or reload.
     */
    fun releaseRenderSessions() {
        renderEpoch.incrementAndGet()
        synchronized(lock) {
            tabs.values.forEach { tab ->
                tab.renderSession?.close()
                tab.renderSession = null
                tab.page = null
            }
        }
        render.closeCurrentSession()
    }

    fun saveSession() = synchronized(lock) { persistLocked() }

    fun restoreSession(loadActive: Boolean = false, viewport: RenderViewport = RenderViewport()): Boolean {
        if (!sessionPersistenceEnabled) return false
        val snapshot = store.load() ?: return false
        val activeToLoad = synchronized(lock) {
            tabs.values.forEach { it.renderSession?.close() }
            tabs.clear()
            closedTabs.clear()
            snapshot.tabs.take(limits.maxPersistedTabs).forEach { persisted ->
                val current = persisted.entries[persisted.currentIndex]
                tabs[persisted.id] = MutableBrowserTab(
                    id = persisted.id,
                    history = persisted.entries.toMutableList(),
                    currentIndex = persisted.currentIndex,
                    title = current.title,
                    url = current.url,
                    pinned = persisted.pinned
                )
                nextTabId.accumulateAndGet(persisted.id.value, ::maxOf)
            }
            snapshot.closedTabs.take(limits.maxClosedTabs).forEach(closedTabs::addLast)
            activeTabId = snapshot.activeTabId?.takeIf(tabs::containsKey) ?: tabs.keys.firstOrNull()
            sessionRestores.incrementAndGet()
            activeTabId
        }
        if (loadActive && activeToLoad != null) navigateCurrent(NavigationTransition.RESTORE, viewport, activeToLoad)
        return true
    }

    fun clearSession() {
        store.clear()
        synchronized(lock) { closedTabs.clear() }
    }

    fun wipeSession() {
        renderEpoch.incrementAndGet()
        synchronized(lock) {
            tabs.values.forEach { it.renderSession?.close() }
            tabs.clear()
            closedTabs.clear()
            activeTabId = null
            store.clear()
        }
        browser.clearSession()
        render.closeCurrentSession()
    }

    fun statistics(): BrowserShellStatistics = BrowserShellStatistics(
        tabsOpened.get(), tabsClosed.get(), navigationsStarted.get(), navigationsCommitted.get(), navigationsFailed.get(),
        historyTraversals.get(), sessionSaves.get(), sessionRestores.get()
    )

    private fun navigateCurrent(
        transition: NavigationTransition,
        viewport: RenderViewport,
        explicitTabId: BrowserTabId? = null
    ): BrowserNavigationResult {
        val tab = synchronized(lock) { tabs[explicitTabId ?: activeTabId] ?: error("No active tab") }
        return navigate(tab.id, tab.url, transition, viewport)
    }

    private fun beginNavigation(tabId: BrowserTabId, input: String, transition: NavigationTransition): NavigationPlan = synchronized(lock) {
        val tab = tabs[tabId] ?: throw IllegalArgumentException("Unknown tab $tabId")
        val address = resolver.resolve(input)
        val navigationDecision = security.authorizeNavigation(tab.url, address.url)
        require(navigationDecision.allowed) { navigationDecision.reason }
        val effectiveAddress = if (navigationDecision.effectiveUrl != null && navigationDecision.effectiveUrl != address.url) {
            address.copy(url = navigationDecision.effectiveUrl)
        } else address
        val token = navigationSequence.incrementAndGet()
        tab.navigationToken = token
        tab.loadState = TabLoadState.LOADING
        tab.progress = 10
        tab.errorMessage = null
        navigationsStarted.incrementAndGet()
        NavigationPlan(tabId, token, effectiveAddress, transition)
    }

    private fun beginHistoryNavigationLocked(tab: MutableBrowserTab, targetIndex: Int): NavigationPlan {
        val entry = tab.history[targetIndex]
        val navigationDecision = security.authorizeNavigation(tab.url, entry.url)
        require(navigationDecision.allowed) { navigationDecision.reason }
        val token = navigationSequence.incrementAndGet()
        tab.navigationToken = token
        tab.loadState = TabLoadState.LOADING
        tab.progress = 10
        tab.errorMessage = null
        navigationsStarted.incrementAndGet()
        return NavigationPlan(
            tab.id,
            token,
            ResolvedAddress(entry.url, entry.url, entry.url, wasSearch = false, internal = entry.url == AddressResolver.HOME_URL),
            NavigationTransition.BACK_FORWARD,
            targetIndex
        )
    }

    private fun load(address: ResolvedAddress, cachePolicy: CachePolicy): Result<LoadedDocument> {
        if (address.internal) return Result.success(homeDocument())
        val runtime = network ?: return Result.failure(IllegalStateException("Networking runtime is unavailable"))
        val request = NetworkRequest.Builder(address.url)
            .cachePolicy(cachePolicy)
            .maxResponseBytes(limits.maxMarkupChars.toLong())
            .tag("browser-shell")
            .build()
        return when (val result = runtime.client.execute(request)) {
            is NetworkResult.Success -> {
                val response = result.value
                val redirectDecision = security.authorizeRedirect(address.url, response.finalUrl.toString())
                if (!redirectDecision.allowed) return Result.failure(IllegalStateException(redirectDecision.reason))
                val bodyText = response.bodyText().take(limits.maxMarkupChars)
                val markup = when {
                    response.contentType?.contains("html", ignoreCase = true) == true -> bodyText
                    response.contentType?.startsWith("text/", ignoreCase = true) == true -> plainTextDocument(bodyText)
                    else -> unsupportedDocument(response.contentType ?: "unknown", response.body.size)
                }
                val documentUrl = response.finalUrl.toString()
                val policy = security.buildDocumentPolicy(documentUrl, response.headers, markup)
                val styleSheet = collectDocumentStyles(markup, documentUrl, policy, runtime)
                val scripts = collectDocumentScripts(markup, documentUrl, policy, runtime)
                Result.success(
                    LoadedDocument(
                        url = documentUrl,
                        title = extractTitle(markup).ifBlank { response.finalUrl.host },
                        markup = markup,
                        styleSheet = styleSheet,
                        statusCode = response.statusCode,
                        fromCache = response.fromCache,
                        security = if (response.finalUrl.isSecure) SecurityIndicator.SECURE else SecurityIndicator.INSECURE,
                        policy = policy,
                        scripts = scripts
                    )
                )
            }
            is NetworkResult.Failure -> Result.failure(IllegalStateException("${result.error.kind}: ${result.error.message}"))
        }
    }

    private fun commitNavigation(
        plan: NavigationPlan,
        loaded: Result<LoadedDocument>,
        viewport: RenderViewport
    ): BrowserNavigationResult {
        val document = loaded.getOrElse { failure ->
            val errorDocument = errorDocument(plan.address.url, failure.message ?: "Navigation failed")
            navigationsFailed.incrementAndGet()
            errorDocument
        }
        val tab = synchronized(lock) {
            val current = tabs[plan.tabId] ?: return BrowserNavigationResult(
                tab = BrowserTabSnapshot(plan.tabId, "Closed", plan.address.url, TabLoadState.FAILED, 0, false, false, 0, false, SecurityIndicator.INSECURE, "Tab closed"),
                page = null,
                renderSession = null,
                committed = false,
                stale = true
            )
            if (current.navigationToken != plan.token) {
                return BrowserNavigationResult(snapshotLocked(current), current.page, current.renderSession, committed = false, stale = true)
            }
            current.url = document.url
            current.title = document.title.take(limits.maxTitleChars).ifBlank { document.url }
            current.markup = document.markup.take(limits.maxMarkupChars)
            current.styleSheet = document.styleSheet.take(limits.maxStyleChars)
            current.scripts = document.scripts.take(limits.maxScripts)
            current.securityPolicy = document.policy
            current.loadState = if (loaded.isSuccess) TabLoadState.COMPLETE else TabLoadState.FAILED
            current.progress = 100
            current.errorMessage = loaded.exceptionOrNull()?.message
            updateHistoryLocked(current, plan, document)
            current
        }
        val activated = if (synchronized(lock) { activeTabId == tab.id }) activateDocument(tab, viewport, plan.token) else null
        synchronized(lock) { persistLocked() }
        if (loaded.isSuccess) {
            navigationsCommitted.incrementAndGet()
            if (plan.transition != NavigationTransition.RESTORE && document.url != AddressResolver.HOME_URL) {
                runCatching { browsingHistory?.record(document.url, tab.title, plan.transition, clockMillis()) }
                    .onFailure { logger?.warn("BrowserShell", "History write failed: ${it.message.orEmpty()}") }
            }
        }
        logger?.info("BrowserShell", "${if (loaded.isSuccess) "Loaded" else "Failed"} ${document.url} tab=${tab.id}")
        return BrowserNavigationResult(snapshot(tab.id) ?: error("Tab disappeared"), activated?.first, activated?.second, committed = true)
    }

    private fun activateDocument(
        tab: MutableBrowserTab,
        viewport: RenderViewport,
        expectedNavigationToken: Long? = null
    ): Pair<BrowserPage, RenderSession>? {
        val epoch = renderEpoch.get()
        val policy = tab.securityPolicy ?: security.buildDocumentPolicy(tab.url, markup = tab.markup)
        val page = browser.open(tab.markup, tab.url, policy)
        page.updateViewport(viewport.widthPx, viewport.heightPx, viewport.deviceScaleFactor)
        executeDocumentScripts(page, tab.scripts)
        page.markInteractive()
        val session = render.open(page, tab.styleSheet, viewport, tab.url, DEFAULT_USER_AGENT_CSS)
        page.markComplete()
        val scriptedTitle = page.documentTitle().take(limits.maxTitleChars).ifBlank { tab.title }
        synchronized(lock) {
            if (tabs[tab.id] === tab) {
                tab.title = scriptedTitle
                if (tab.currentIndex in tab.history.indices) {
                    tab.history[tab.currentIndex] = tab.history[tab.currentIndex].copy(title = scriptedTitle)
                }
            }
        }
        val accepted = synchronized(lock) {
            val current = tabs[tab.id]
            val valid = renderEpoch.get() == epoch && current === tab && activeTabId == tab.id &&
                (expectedNavigationToken == null || tab.navigationToken == expectedNavigationToken)
            if (valid) {
                tabs.values.filter { it.id != tab.id }.forEach { other ->
                    other.page = null
                    other.renderSession = null
                }
                tab.page = page
                tab.renderSession = session
            }
            valid
        }
        if (!accepted) {
            session.close()
            if (render.currentSession === session) render.closeCurrentSession()
            return null
        }
        return page to session
    }

    private fun executeDocumentScripts(page: BrowserPage, scripts: List<PageScript>) {
        val immediate = scripts.filterNot(PageScript::defer)
        val deferred = scripts.filter(PageScript::defer)
        (immediate + deferred).forEach { script ->
            val result = if (script.external) {
                page.evaluateExternal(script.source, script.sourceUrl ?: page.url)
            } else {
                page.evaluate(script.source, nonce = script.nonce)
            }
            if (!result.success) {
                logger?.warn("BrowserShell", "Script failed ${script.sourceUrl ?: "inline"}: ${result.error?.message.orEmpty()}")
            }
        }
    }

    private fun updateHistoryLocked(tab: MutableBrowserTab, plan: NavigationPlan, document: LoadedDocument) {
        when (plan.transition) {
            NavigationTransition.RELOAD -> {
                if (tab.currentIndex in tab.history.indices) {
                    val old = tab.history[tab.currentIndex]
                    tab.history[tab.currentIndex] = old.copy(url = document.url, title = document.title)
                }
            }
            NavigationTransition.BACK_FORWARD -> {
                val target = plan.targetHistoryIndex ?: tab.currentIndex
                if (target in tab.history.indices) {
                    tab.currentIndex = target
                    val old = tab.history[target]
                    tab.history[target] = old.copy(url = document.url, title = document.title)
                }
            }
            NavigationTransition.RESTORE -> {
                if (tab.currentIndex in tab.history.indices) {
                    val old = tab.history[tab.currentIndex]
                    tab.history[tab.currentIndex] = old.copy(url = document.url, title = document.title)
                }
            }
            else -> {
                val replacesInitial = tab.history.size == 1 && tab.currentIndex == 0 &&
                    tab.history[0].url == AddressResolver.HOME_URL && tab.history[0].title == "New tab"
                if (replacesInitial) {
                    tab.history[0] = BrowserHistoryEntry(document.url, document.title, clockMillis().coerceAtLeast(0L), plan.transition)
                } else {
                    if (tab.currentIndex < tab.history.lastIndex) tab.history.subList(tab.currentIndex + 1, tab.history.size).clear()
                    tab.history += BrowserHistoryEntry(document.url, document.title, clockMillis().coerceAtLeast(0L), plan.transition)
                    while (tab.history.size > limits.maxHistoryEntriesPerTab) tab.history.removeAt(0)
                    tab.currentIndex = tab.history.lastIndex
                }
            }
        }
    }

    private fun activeMutable(): MutableBrowserTab? = synchronized(lock) { activeMutableLocked() }
    private fun activeMutableLocked(): MutableBrowserTab? = activeTabId?.let(tabs::get)

    private fun snapshotLocked(tab: MutableBrowserTab): BrowserTabSnapshot = BrowserTabSnapshot(
        id = tab.id,
        title = tab.title,
        url = tab.url,
        loadState = tab.loadState,
        progress = tab.progress,
        canGoBack = tab.currentIndex > 0,
        canGoForward = tab.currentIndex < tab.history.lastIndex,
        historySize = tab.history.size,
        active = activeTabId == tab.id,
        security = when {
            tab.url == AddressResolver.HOME_URL -> SecurityIndicator.INTERNAL
            runCatching { AetherUrl.parse(tab.url).isSecure }.getOrDefault(false) -> SecurityIndicator.SECURE
            else -> SecurityIndicator.INSECURE
        },
        errorMessage = tab.errorMessage
    )

    private fun persistLocked() {
        if (!sessionPersistenceEnabled) return
        val snapshot = BrowserSessionSnapshot(
            activeTabId,
            tabs.values.take(limits.maxPersistedTabs).map {
                PersistedTab(it.id, it.immutableHistory(), it.currentIndex.coerceIn(it.history.indices), it.pinned)
            },
            closedTabs.toList().take(limits.maxClosedTabs)
        )
        runCatching { store.save(snapshot) }
            .onSuccess { sessionSaves.incrementAndGet() }
            .onFailure { logger?.warn("BrowserShell", "Session save failed: ${it.message.orEmpty()}") }
    }

    private fun homeDocument(): LoadedDocument = LoadedDocument(
        url = AddressResolver.HOME_URL,
        title = "Aether New Tab",
        markup = HOME_HTML,
        styleSheet = HOME_CSS,
        statusCode = 200,
        fromCache = false,
        security = SecurityIndicator.INTERNAL,
        policy = security.buildDocumentPolicy(
            AddressResolver.HOME_URL,
            com.mohnishraj.aether.core.net.model.NetworkHeaders.of(
                "Content-Security-Policy" to "default-src 'self'; connect-src https:; img-src 'self' https:; style-src 'self'; script-src 'none'; object-src 'none'; form-action 'self'; base-uri 'none'",
                "Permissions-Policy" to "camera=(), microphone=(), geolocation=(), notifications=()"
            ),
            HOME_HTML
        )
    )

    private fun errorDocument(url: String, message: String): LoadedDocument = LoadedDocument(
        url = url,
        title = "Navigation error",
        markup = "<!doctype html><html><body><main><h1>Page could not load</h1><p>${escapeHtml(url)}</p><pre>${escapeHtml(message)}</pre></main></body></html>",
        styleSheet = ERROR_CSS,
        statusCode = 0,
        fromCache = false,
        security = if (url.startsWith("https://")) SecurityIndicator.SECURE else SecurityIndicator.INSECURE,
        policy = security.buildDocumentPolicy(
            url,
            com.mohnishraj.aether.core.net.model.NetworkHeaders.of("Content-Security-Policy" to "default-src 'none'; style-src 'self'"),
            ""
        )
    )

    private fun plainTextDocument(text: String): String = "<!doctype html><html><body><pre>${escapeHtml(text)}</pre></body></html>"
    private fun unsupportedDocument(type: String, size: Int): String =
        "<!doctype html><html><body><main><h1>Unsupported document</h1><p>${escapeHtml(type)}</p><p>$size bytes received.</p></main></body></html>"

    private fun extractTitle(markup: String): String = TITLE_REGEX.find(markup)?.groupValues?.getOrNull(1)
        ?.replace(TAG_REGEX, " ")
        ?.replace(WHITESPACE_REGEX, " ")
        ?.trim()
        ?.take(limits.maxTitleChars)
        .orEmpty()

    private fun collectDocumentScripts(
        markup: String,
        documentUrl: String,
        policy: DocumentSecurityPolicy,
        runtime: NetworkRuntime
    ): List<PageScript> {
        val baseUrl = documentBaseUrl(markup, documentUrl)
        val scripts = mutableListOf<PageScript>()
        var totalChars = 0
        SCRIPT_REGEX.findAll(markup).forEach { match ->
            if (scripts.size >= limits.maxScripts || totalChars >= limits.maxScriptChars) return@forEach
            val attributes = match.groupValues[1]
            val inlineSource = match.groupValues[2]
            val type = attribute(attributes, "type")?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (type.isNotEmpty() && type !in SUPPORTED_SCRIPT_TYPES) {
                logger?.debug("BrowserShell", "Skipped unsupported script type=$type")
                return@forEach
            }
            val sourceAttribute = attribute(attributes, "src")
            val nonce = attribute(attributes, "nonce")
            val defer = attribute(attributes, "defer") != null || type == "module"
            val async = attribute(attributes, "async") != null
            if (!sourceAttribute.isNullOrBlank()) {
                val requestedUrl = resolveResourceUrl(baseUrl, sourceAttribute) ?: return@forEach
                val loaded = loadExternalScript(runtime, policy, requestedUrl, limits.maxScriptChars - totalChars) ?: return@forEach
                totalChars += loaded.second.length
                scripts += PageScript(loaded.second, loaded.first, nonce, external = true, defer = defer, async = async)
            } else {
                val bounded = inlineSource.take(limits.maxScriptChars - totalChars)
                if (bounded.isNotBlank()) {
                    totalChars += bounded.length
                    scripts += PageScript(bounded, null, nonce, external = false, defer = defer, async = async)
                }
            }
        }
        return scripts
    }

    private fun loadExternalScript(
        runtime: NetworkRuntime,
        policy: DocumentSecurityPolicy,
        requestedUrl: String,
        remainingChars: Int
    ): Pair<String, String>? {
        if (remainingChars <= 0) return null
        val decision = security.authorizeSubresource(policy, SecurityResourceType.SCRIPT, requestedUrl)
        if (!decision.allowed) {
            logger?.warn("BrowserShell", "Blocked script $requestedUrl: ${decision.reason}")
            return null
        }
        val effective = decision.effectiveUrl ?: requestedUrl
        val request = NetworkRequest.Builder(effective)
            .cachePolicy(CachePolicy.DEFAULT)
            .maxResponseBytes(remainingChars.toLong())
            .tag("browser-script")
            .build()
        val response = when (val result = runtime.client.execute(request)) {
            is NetworkResult.Success -> result.value
            is NetworkResult.Failure -> {
                logger?.warn("BrowserShell", "Script load failed $effective: ${result.error.message}")
                return null
            }
        }
        val redirect = security.authorizeRedirect(effective, response.finalUrl.toString())
        if (!redirect.allowed) return null
        val finalUrl = response.finalUrl.toString()
        val finalDecision = security.authorizeSubresource(policy, SecurityResourceType.SCRIPT, finalUrl)
        if (!finalDecision.allowed) return null
        return finalUrl to response.bodyText().take(remainingChars)
    }

    private fun documentBaseUrl(markup: String, documentUrl: String): String = BASE_REGEX.find(markup)?.groupValues?.getOrNull(1)
        ?.let { attributes -> attribute(attributes, "href") }
        ?.let { href -> resolveResourceUrl(documentUrl, href) }
        ?: documentUrl

    private fun collectDocumentStyles(
        markup: String,
        documentUrl: String,
        policy: DocumentSecurityPolicy,
        runtime: NetworkRuntime
    ): String {
        val baseUrl = documentBaseUrl(markup, documentUrl)
        val output = StringBuilder(minOf(markup.length / 8, limits.maxStyleChars))
        val visited = linkedSetOf<String>()
        var externalCount = 0
        STYLE_OR_LINK_REGEX.findAll(markup).forEach { match ->
            if (output.length >= limits.maxStyleChars) return@forEach
            val styleAttributes = match.groupValues[1]
            val styleBody = match.groupValues[2]
            val linkAttributes = match.groupValues[3]
            if (linkAttributes.isNotEmpty()) {
                val rel = attribute(linkAttributes, "rel")
                    ?.lowercase(Locale.ROOT)
                    ?.split(Regex("\\s+"))
                    .orEmpty()
                val href = attribute(linkAttributes, "href")
                val disabled = attribute(linkAttributes, "disabled") != null || BOOLEAN_DISABLED_REGEX.containsMatchIn(linkAttributes)
                if ("stylesheet" !in rel || href.isNullOrBlank() || disabled || externalCount >= MAX_EXTERNAL_STYLESHEETS) return@forEach
                val resolved = resolveResourceUrl(baseUrl, href) ?: return@forEach
                val loaded = loadExternalStyle(runtime, policy, resolved, visited, 0) ?: return@forEach
                externalCount++
                appendStyle(output, loaded)
            } else {
                val nonce = attribute(styleAttributes, "nonce")
                val decision = security.authorizeInlineStyle(policy, nonce)
                if (decision.allowed) appendStyle(output, styleBody)
                else logger?.warn("BrowserShell", "Blocked inline style: ${decision.reason}")
            }
        }
        return output.toString().take(limits.maxStyleChars)
    }

    private fun loadExternalStyle(
        runtime: NetworkRuntime,
        policy: DocumentSecurityPolicy,
        requestedUrl: String,
        visited: MutableSet<String>,
        depth: Int
    ): String? {
        if (depth > MAX_STYLE_IMPORT_DEPTH || requestedUrl in visited) return null
        val decision = security.authorizeSubresource(policy, SecurityResourceType.STYLE, requestedUrl)
        if (!decision.allowed) {
            logger?.warn("BrowserShell", "Blocked stylesheet $requestedUrl: ${decision.reason}")
            return null
        }
        val effective = decision.effectiveUrl ?: requestedUrl
        if (!visited.add(effective)) return null
        val request = NetworkRequest.Builder(effective)
            .cachePolicy(CachePolicy.DEFAULT)
            .maxResponseBytes(limits.maxStyleChars.toLong())
            .tag("browser-style")
            .build()
        val response = when (val result = runtime.client.execute(request)) {
            is NetworkResult.Success -> result.value
            is NetworkResult.Failure -> {
                logger?.warn("BrowserShell", "Stylesheet load failed $effective: ${result.error.message}")
                return null
            }
        }
        val redirect = security.authorizeRedirect(effective, response.finalUrl.toString())
        if (!redirect.allowed) return null
        val finalUrl = response.finalUrl.toString()
        val source = response.bodyText().take(limits.maxStyleChars)
        val imports = StringBuilder()
        IMPORT_REGEX.findAll(source).take(MAX_IMPORTS_PER_SHEET).forEach { match ->
            val href = match.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: return@forEach
            val resolved = resolveResourceUrl(finalUrl, href) ?: return@forEach
            loadExternalStyle(runtime, policy, resolved, visited, depth + 1)?.let { appendStyle(imports, it) }
        }
        val withoutImports = IMPORT_REGEX.replace(source, "")
        appendStyle(imports, withoutImports)
        return imports.toString()
    }

    private fun appendStyle(output: StringBuilder, source: String) {
        if (source.isBlank() || output.length >= limits.maxStyleChars) return
        if (output.isNotEmpty()) output.append('\n')
        val remaining = limits.maxStyleChars - output.length
        output.append(source.take(remaining))
    }

    private fun resolveResourceUrl(baseUrl: String, raw: String): String? = runCatching {
        AetherUrl.parse(baseUrl).resolve(raw.trim()).toString()
    }.getOrNull()

    companion object {
        private val TITLE_REGEX = Regex("<title\\b[^>]*>(.*?)</title\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val SCRIPT_REGEX = Regex("<script\\b([^>]*)>(.*?)</script\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val STYLE_OR_LINK_REGEX = Regex(
            "<style\\b([^>]*)>(.*?)</style\\s*>|<link\\b([^>]*)>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val BASE_REGEX = Regex("<base\\b([^>]*)>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val ATTRIBUTE_REGEX = Regex("([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
        private val BOOLEAN_DISABLED_REGEX = Regex("(?:^|\\s)disabled(?:\\s|$|/)", RegexOption.IGNORE_CASE)
        private val IMPORT_REGEX = Regex(
            "@import\\s+(?:url\\(\\s*(?:\"([^\"]+)\"|'([^']+)'|([^\\s)]+))\\s*\\)|\"([^\"]+)\"|'([^']+)')[^;]*;",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val TAG_REGEX = Regex("<[^>]+>")
        private val WHITESPACE_REGEX = Regex("\\s+")

        private fun attribute(raw: String, name: String): String? {
            ATTRIBUTE_REGEX.findAll(raw).forEach { match ->
                if (match.groupValues[1].equals(name, ignoreCase = true)) {
                    return match.groupValues.drop(2).firstOrNull(String::isNotEmpty) ?: ""
                }
            }
            if (Regex("(?:^|\\s)${Regex.escape(name)}(?:\\s|$|/)", RegexOption.IGNORE_CASE).containsMatchIn(raw)) return ""
            return null
        }

        private val SUPPORTED_SCRIPT_TYPES = setOf("", "text/javascript", "application/javascript", "application/ecmascript", "text/ecmascript")
        private const val MAX_EXTERNAL_STYLESHEETS = 32
        private const val MAX_STYLE_IMPORT_DEPTH = 3
        private const val MAX_IMPORTS_PER_SHEET = 16

        private fun escapeHtml(value: String): String = buildString(value.length) {
            value.forEach { character ->
                when (character) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(character)
                }
            }
        }

        private const val DEFAULT_USER_AGENT_CSS = """
html{display:block;min-height:100%;background:transparent;color:black;font-family:sans-serif;font-size:16px;line-height:normal}
head,base,basefont,datalist,link,meta,noembed,noframes,param,rp,script,style,template,title{display:none}
body{display:block;margin:8px;background:transparent;color:inherit}
address,article,aside,blockquote,div,dl,fieldset,figcaption,figure,footer,form,h1,h2,h3,h4,h5,h6,header,hgroup,hr,main,nav,ol,p,pre,section,table,ul{display:block}
li{display:list-item}summary{display:list-item}table{display:table;border-collapse:separate;border-spacing:2px;border-color:gray}thead{display:table-header-group}tbody{display:table-row-group}tfoot{display:table-footer-group}tr{display:table-row}td,th{display:table-cell;vertical-align:inherit}caption{display:table-caption;text-align:center}
a,abbr,acronym,b,bdi,bdo,big,br,cite,code,del,dfn,em,i,ins,kbd,label,map,mark,q,s,samp,small,span,strike,strong,sub,sup,time,tt,u,var{display:inline}
img,svg,video,canvas,audio,iframe,input,button,select,textarea{display:inline-block}
[hidden]{display:none!important}input[type=hidden]{display:none!important}
h1{font-size:2em;margin:.67em 0;font-weight:bold}h2{font-size:1.5em;margin:.83em 0;font-weight:bold}h3{font-size:1.17em;margin:1em 0;font-weight:bold}h4{margin:1.33em 0;font-weight:bold}h5{font-size:.83em;margin:1.67em 0;font-weight:bold}h6{font-size:.67em;margin:2.33em 0;font-weight:bold}
p{margin:1em 0}blockquote{margin:1em 40px}figure{margin:1em 40px}hr{border:1px inset;margin:.5em auto}pre{font-family:monospace;white-space:pre;margin:1em 0}code,kbd,samp,tt{font-family:monospace}
ul,ol{margin:1em 0;padding-left:40px}dl{margin:1em 0}dd{margin-left:40px}fieldset{margin:0 2px;padding:.35em .75em .625em;border:2px groove}legend{padding:0 2px}
button,input,select,textarea{box-sizing:border-box;font-family:inherit;font-size:inherit;font-style:inherit;font-weight:inherit;line-height:normal;color:inherit;letter-spacing:normal;word-spacing:normal;text-transform:none;text-indent:0;text-shadow:none;text-align:start;margin:0}input:not([type=checkbox]):not([type=radio]),textarea,select{padding:1px 2px;border:2px inset}button,input[type=button],input[type=submit],input[type=reset]{padding:2px 6px;border:2px outset;background-color:buttonface}button,input[type=button],input[type=submit],input[type=reset]{text-align:center}textarea{white-space:pre-wrap;overflow:auto}
a{color:#0000ee;text-decoration:underline}b,strong{font-weight:bold}i,cite,em,var,address{font-style:italic}small{font-size:smaller}sub,sup{font-size:smaller;line-height:normal}sub{vertical-align:sub}sup{vertical-align:super}
"""
        private const val HOME_CSS = """body{box-sizing:border-box;margin:0;padding:16px;background:#07101f;color:#eaf2ff}main{display:block;box-sizing:border-box;width:auto;max-width:680px;margin:12px auto;padding:20px;background:#101b31;border:1px solid #29436b;border-radius:20px}h1{display:block;font-size:30px;line-height:1.2;margin:0 0 10px;color:#55e6c1}.lead{display:block;margin:0 0 16px;color:#9fb0ca}.grid{display:block;margin:0}.card{display:block;margin:10px 0;padding:14px;background:#16233d;border:1px solid #304d79;border-radius:14px}.card-title{display:block;margin:0 0 6px;color:#ffffff;font-size:17px}.card-copy{display:block;margin:0;color:#b9c7dc}.security{display:block;margin:14px 0 0;padding:10px;background:#0b2a25;border:1px solid #2f8f7d;border-radius:12px;color:#79f2d2}"""
        private const val ERROR_CSS = """body{padding:16px;background:#160b12;color:#ffe8ed}main{display:block;width:100%;max-width:680px;margin:12px auto;padding:20px;background:#2b111c;border:1px solid #7f2946;border-radius:20px}h1,p,pre{display:block}h1{margin:0 0 12px;color:#fb7185}p{margin:0 0 12px}pre{background:#190c12;border:1px solid #512033}"""
        private const val HOME_HTML = """<!doctype html><html><head><title>Aether New Tab</title></head><body><main><h1>Aether Browser</h1><p class="lead">Independent browser shell with a measured native viewport. No WebView.</p><section class="grid"><article class="card"><strong class="card-title">Address or search</strong><p class="card-copy">Enter a URL or search query in the toolbar.</p></article><article class="card"><strong class="card-title">Multi-tab sessions</strong><p class="card-copy">Tabs, history and session restore are active.</p></article><article class="card"><strong class="card-title">Custom rendering path</strong><p class="card-copy">Network → HTML → CSS → layout → paint → compositor.</p></article></section><p class="security">M11 security: CSP, mixed content, CORS, sandbox and permissions are enforced.</p></main></body></html>"""
    }
}
