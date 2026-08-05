package com.mohnishraj.aether.core.browser

import com.mohnishraj.aether.core.browser.dom.BrowserDocument
import com.mohnishraj.aether.core.browser.events.BrowserEvent
import com.mohnishraj.aether.core.browser.fetch.BrowserFetchBridge
import com.mohnishraj.aether.core.browser.forms.BrowserFormController
import com.mohnishraj.aether.core.browser.js.BrowserJsBindings
import com.mohnishraj.aether.core.browser.storage.BrowserStorageArea
import com.mohnishraj.aether.core.browser.storage.BrowserStorageManager
import com.mohnishraj.aether.core.fs.FileSystem
import com.mohnishraj.aether.core.html.HtmlEngine
import com.mohnishraj.aether.core.js.JsEngine
import com.mohnishraj.aether.core.js.JsEvaluationResult
import com.mohnishraj.aether.core.js.JsIssue
import com.mohnishraj.aether.core.js.JsLimits
import com.mohnishraj.aether.core.js.JsRuntimeException
import com.mohnishraj.aether.core.js.JsValue
import com.mohnishraj.aether.core.log.EngineLogger
import com.mohnishraj.aether.core.net.NetworkRuntime
import com.mohnishraj.aether.core.net.model.AetherUrl
import com.mohnishraj.aether.core.profile.PerformanceProfiler
import com.mohnishraj.aether.core.security.DocumentSecurityPolicy
import com.mohnishraj.aether.core.security.PermissionFeature
import com.mohnishraj.aether.core.security.SecurityDecision
import com.mohnishraj.aether.core.security.SecurityEngine
import com.mohnishraj.aether.core.security.SecurityResourceType
import com.mohnishraj.aether.core.html.dom.ElementNode
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList


data class BrowserNavigationRequest(val rawUrl: String, val replace: Boolean)

class BrowserPage internal constructor(
    val url: String,
    val origin: String,
    val document: BrowserDocument,
    val localStorage: BrowserStorageArea,
    val sessionStorage: BrowserStorageArea,
    val forms: BrowserFormController,
    val fetch: BrowserFetchBridge,
    val securityPolicy: DocumentSecurityPolicy,
    private val security: SecurityEngine,
    private val js: JsEngine,
    private val clipboard: ClipboardPort,
    private val limits: BrowserApiLimits,
    private val counters: BrowserApiCounters,
    private val logger: EngineLogger?
) {
    private var realmInitialized = false
    private val startedNanos = System.nanoTime()
    private val navigationRequest = AtomicReference<BrowserNavigationRequest?>()
    private val computedStyleProvider = AtomicReference<(ElementNode) -> Map<String, String>>({ emptyMap() })
    private val consoleMessages = CopyOnWriteArrayList<String>()

    @Volatile var viewportWidth: Double = 360.0
        private set
    @Volatile var viewportHeight: Double = 800.0
        private set
    @Volatile var deviceScaleFactor: Double = 1.0
        private set
    @Volatile var activeElement: ElementNode? = null
    @Volatile var readyState: String = "loading"
        private set

    val jsLimits: JsLimits get() = js.limitsSnapshot()

    @Synchronized fun evaluate(source: String, freshRealm: Boolean = false, nonce: String? = null): JsEvaluationResult {
        val authorization = security.authorizeInlineScript(securityPolicy, nonce)
        if (!authorization.allowed) {
            val error = JsRuntimeException("SecurityError", authorization.reason)
            return JsEvaluationResult(
                success = false,
                value = JsValue.Undefined,
                output = emptyList(),
                issues = listOf(JsIssue("security-error", authorization.reason)),
                error = error,
                tokenCount = 0,
                astNodeCount = 0,
                steps = 0L,
                tasksExecuted = 0,
                elapsedNanos = 0L
            )
        }
        val bindings = BrowserJsBindings(this, clipboard, limits, counters).globals()
        counters.scriptsEvaluated.incrementAndGet()
        val useFreshRealm = freshRealm || !realmInitialized
        val result = js.evaluate(source, url, freshRealm = useFreshRealm, hostGlobals = bindings)
        realmInitialized = true
        document.deliverMutations()
        logger?.debug("BrowserAPI", "Evaluated page script success=${result.success} url=$url")
        return result
    }

    @Synchronized
    fun evaluateExternal(source: String, sourceUrl: String): JsEvaluationResult {
        if (!securityPolicy.sandbox.scripts) return blockedEvaluation("Sandbox blocks script execution")
        val authorization = security.authorizeSubresource(securityPolicy, SecurityResourceType.SCRIPT, sourceUrl)
        if (!authorization.allowed) return blockedEvaluation(authorization.reason)
        val bindings = BrowserJsBindings(this, clipboard, limits, counters).globals()
        counters.scriptsEvaluated.incrementAndGet()
        val result = js.evaluate(source, sourceUrl, freshRealm = !realmInitialized, hostGlobals = bindings)
        realmInitialized = true
        document.deliverMutations()
        logger?.debug("BrowserAPI", "Evaluated external script success=${result.success} url=$sourceUrl")
        return result
    }

    fun updateViewport(widthPx: Double, heightPx: Double, scale: Double = 1.0) {
        viewportWidth = widthPx.coerceAtLeast(0.0)
        viewportHeight = heightPx.coerceAtLeast(0.0)
        deviceScaleFactor = scale.coerceAtLeast(0.1)
    }

    fun updateComputedStyles(provider: (ElementNode) -> Map<String, String>) { computedStyleProvider.set(provider) }
    fun computedStyle(element: ElementNode): Map<String, String> = computedStyleProvider.get().invoke(element)
    fun monotonicMillis(): Long = (System.nanoTime() - startedNanos) / 1_000_000L

    fun consoleMessage(message: String) {
        if (consoleMessages.size >= jsLimits.maxConsoleLines) consoleMessages.removeAt(0)
        consoleMessages += message
        logger?.info("PageConsole", message)
    }

    fun consoleMessages(): List<String> = consoleMessages.toList()

    fun requestNavigation(rawUrl: String, replace: Boolean) { navigationRequest.set(BrowserNavigationRequest(rawUrl, replace)) }
    fun consumeNavigationRequest(): BrowserNavigationRequest? = navigationRequest.getAndSet(null)

    fun documentTitle(): String = document.head?.descendants(includeSelf = false)
        ?.filterIsInstance<ElementNode>()
        ?.firstOrNull { it.localName == "title" }
        ?.textContent
        .orEmpty()

    fun setDocumentTitle(value: String) {
        val head = document.head ?: return
        var title = head.descendants(includeSelf = false).filterIsInstance<ElementNode>().firstOrNull { it.localName == "title" }
        if (title == null) {
            title = document.createElement("title")
            document.appendChild(head, title)
        }
        document.setTextContent(title, value)
    }

    fun markInteractive() {
        readyState = "interactive"
        document.dispatchEvent(document.document, BrowserEvent("DOMContentLoaded", bubbles = true, cancelable = false))
        js.advanceTimeBy(0L)
        document.deliverMutations()
    }

    fun markComplete() {
        readyState = "complete"
        document.dispatchEvent(document.document, BrowserEvent("load", bubbles = false, cancelable = false))
        js.advanceTimeBy(0L)
        document.deliverMutations()
    }

    fun advanceTimeBy(millis: Long): Int {
        val executed = js.advanceTimeBy(millis.coerceAtLeast(0L))
        document.deliverMutations()
        return executed
    }

    fun hasPendingTasks(): Boolean = js.hasPendingTasks()
    fun nextTaskDelayMillis(): Long? = js.nextTaskDelayMillis()

    private fun blockedEvaluation(reason: String): JsEvaluationResult {
        val error = JsRuntimeException("SecurityError", reason)
        return JsEvaluationResult(
            success = false,
            value = JsValue.Undefined,
            output = emptyList(),
            issues = listOf(JsIssue("security-error", reason)),
            error = error,
            tokenCount = 0,
            astNodeCount = 0,
            steps = 0L,
            tasksExecuted = 0,
            elapsedNanos = 0L
        )
    }

    fun authorizePermission(feature: PermissionFeature, userGesture: Boolean = false): SecurityDecision =
        security.authorizePermission(securityPolicy, feature, userGesture = userGesture)

    fun authorizeImage(targetUrl: String): SecurityDecision =
        security.authorizeSubresource(securityPolicy, SecurityResourceType.IMAGE, targetUrl)

    fun authorizeForm(targetUrl: String): SecurityDecision {
        val resolved = runCatching { AetherUrl.parse(url).resolve(targetUrl).toString() }
            .getOrElse { return SecurityDecision.block("Invalid form destination") }
        return security.authorizeForm(securityPolicy, resolved)
    }
}

class BrowserApiRuntime(
    private val fileSystem: FileSystem,
    private val network: NetworkRuntime?,
    private val html: HtmlEngine,
    private val js: JsEngine,
    private val logger: EngineLogger? = null,
    private val profiler: PerformanceProfiler? = null,
    val clipboard: ClipboardPort = InMemoryClipboardPort(),
    val limits: BrowserApiLimits = BrowserApiLimits(),
    val security: SecurityEngine = SecurityEngine(logger, profiler)
) {
    private val counters = BrowserApiCounters()
    private val storage = BrowserStorageManager(fileSystem, limits, counters)
    private val currentPageRef = AtomicReference<BrowserPage?>()

    val currentPage: BrowserPage? get() = currentPageRef.get()

    fun open(
        markup: String,
        url: String = "https://aether.local/",
        securityPolicy: DocumentSecurityPolicy = security.buildDocumentPolicy(url, markup = markup)
    ): BrowserPage = profiler?.measure("browser.open") {
        createPage(markup, url, securityPolicy)
    } ?: createPage(markup, url, securityPolicy)

    fun closeCurrentPage() { currentPageRef.set(null) }
    fun clearSession(origin: String? = null) = storage.clearSession(origin)
    fun clearAllStorage() = storage.clearAll()
    fun statistics(): BrowserApiStatistics = counters.snapshot()

    private fun createPage(markup: String, url: String, securityPolicy: DocumentSecurityPolicy): BrowserPage {
        val parsedUrl = AetherUrl.parse(url)
        require(securityPolicy.documentUrl == parsedUrl.toString()) { "Security policy URL does not match page URL" }
        js.resetRealm()
        val parsed = html.parse(markup, parsedUrl.toString())
        val browserDocument = BrowserDocument(parsed.document, html, limits, counters)
        val fetchBridge = BrowserFetchBridge(network, limits, counters, security, securityPolicy)
        val page = BrowserPage(
            url = parsedUrl.toString(),
            origin = parsedUrl.origin,
            document = browserDocument,
            localStorage = storage.localStorage(parsedUrl.origin),
            sessionStorage = storage.sessionStorage(parsedUrl.origin),
            forms = BrowserFormController(browserDocument),
            fetch = fetchBridge,
            securityPolicy = securityPolicy,
            security = security,
            js = js,
            clipboard = clipboard,
            limits = limits,
            counters = counters,
            logger = logger
        )
        currentPageRef.set(page)
        counters.pagesOpened.incrementAndGet()
        profiler?.increment("browser.pages")
        logger?.info("BrowserAPI", "Opened ${parsedUrl.origin} nodes=${parsed.nodeCount}")
        return page
    }
}
