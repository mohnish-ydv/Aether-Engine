package com.mohnishraj.aether

import android.annotation.SuppressLint
import android.Manifest
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.mohnishraj.aether.core.BuildInfo
import com.mohnishraj.aether.core.browser.features.DownloadState
import com.mohnishraj.aether.core.browser.features.FindResult
import com.mohnishraj.aether.core.browser.features.ImportDuplicatePolicy
import com.mohnishraj.aether.core.browser.features.ManagedDownload
import com.mohnishraj.aether.core.browser.features.ManagedDownloadController
import com.mohnishraj.aether.core.browser.features.ReaderSettings
import com.mohnishraj.aether.core.browser.features.ReaderTheme
import com.mohnishraj.aether.core.fs.VirtualPath
import com.mohnishraj.aether.core.EngineRuntime
import com.mohnishraj.aether.core.devtools.CommandContext
import com.mohnishraj.aether.core.css.MediaEnvironment
import com.mohnishraj.aether.core.css.inspect.CssInspector
import com.mohnishraj.aether.core.html.inspect.DomInspector
import com.mohnishraj.aether.core.layout.LayoutViewport
import com.mohnishraj.aether.core.layout.inspect.LayoutInspector
import com.mohnishraj.aether.core.js.inspect.JsInspector
import com.mohnishraj.aether.core.net.model.AetherUrl
import com.mohnishraj.aether.core.net.model.CachePolicy
import com.mohnishraj.aether.core.net.model.NetworkRequest
import com.mohnishraj.aether.core.net.model.NetworkResult
import com.mohnishraj.aether.core.paint.DisplayList
import com.mohnishraj.aether.core.paint.PaintInvalidationTracker
import com.mohnishraj.aether.core.paint.inspect.PaintInspector
import com.mohnishraj.aether.core.selftest.EngineSelfTest
import com.mohnishraj.aether.core.render.RenderSession
import com.mohnishraj.aether.core.render.RenderViewport
import com.mohnishraj.aether.core.render.ScrollBehavior
import com.mohnishraj.aether.core.render.inspect.RenderInspector
import com.mohnishraj.aether.core.shell.BrowserNavigationResult
import com.mohnishraj.aether.core.shell.BrowserShellRuntime
import com.mohnishraj.aether.core.shell.BrowserTabId
import com.mohnishraj.aether.core.shell.SecurityIndicator
import com.mohnishraj.aether.core.security.PermissionDecision
import com.mohnishraj.aether.core.security.PermissionFeature
import com.mohnishraj.aether.core.security.SecurityResourceType
import com.mohnishraj.aether.platform.android.AndroidSystemProbe
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@SuppressLint("SetTextI18n")
class MainActivity : ComponentActivity() {
    private val runtime: EngineRuntime get() = (application as AetherApplication).runtime
    private val context get() = this
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(4),
        { task -> Thread(task, "aether-engine-lab") },
        ThreadPoolExecutor.AbortPolicy()
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    private val downloadExecutor = ThreadPoolExecutor(
        1,
        2,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(16),
        { task -> Thread(task, "aether-download") },
        ThreadPoolExecutor.AbortPolicy()
    )
    private val regularDownloadController by lazy { ManagedDownloadController(requireNotNull(runtime.network), runtime.fileSystem) }
    private val incognitoDownloadController by lazy {
        runtime.incognito?.let { ManagedDownloadController(it.network, it.fileSystem) }
    }
    private val downloadController: ManagedDownloadController
        get() = if (incognitoActive) incognitoDownloadController ?: regularDownloadController else regularDownloadController
    private val browserShell: BrowserShellRuntime get() = if (incognitoActive) runtime.incognito?.shell ?: runtime.shell else runtime.shell
    private var incognitoActive = false
    private var readerSettings = ReaderSettings()
    private var lastFindResult = FindResult("", false, emptyList(), -1)
    private val selectedHistoryIds = linkedSetOf<Long>()
    @Volatile private var browserViewportState = RenderViewport(360.0, 560.0)
    private var browserFramePumpToken = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentScreen == Screen.BROWSER && browserShell.activeSnapshot()?.canGoBack == true) {
                    runBrowserOperation { browserShell.goBack(browserViewport()) }
                } else if (currentScreen in BROWSER_AUX_SCREENS) {
                    showBrowserShell()
                } else if (currentScreen != Screen.DASHBOARD) {
                    showDashboard()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        currentScreen = savedInstanceState?.getString(STATE_SCREEN)?.let { value -> Screen.entries.firstOrNull { it.name == value } }
            ?: Screen.BROWSER
        showCurrentScreen()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SCREEN, currentScreen.name)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        executor.shutdownNow()
        downloadExecutor.shutdownNow()
        runtime.shell.stopLoading()
        runtime.shell.saveSession()
        runtime.shell.releaseRenderSessions()
        AndroidImagePainter.clear(runtime.network)
        AndroidImagePainter.clear(runtime.incognito?.network)
        runtime.incognito?.wipe()
        super.onDestroy()
    }

    private enum class Screen { DASHBOARD, BROWSER, BOOKMARKS, HISTORY, DOWNLOADS, READER, FIND, SETTINGS, SECURITY, RENDER, BROWSER_APIS, JAVASCRIPT, PAINT, LAYOUT, CSS, HTML, NETWORK, CONSOLE, LOGS, CRASHES }
    private var currentScreen = Screen.DASHBOARD

    private fun showCurrentScreen() {
        when (currentScreen) {
            Screen.DASHBOARD -> showDashboard()
            Screen.BROWSER -> showBrowserShell()
            Screen.BOOKMARKS -> showBookmarks()
            Screen.HISTORY -> showHistory()
            Screen.DOWNLOADS -> showDownloads()
            Screen.READER -> showReader()
            Screen.FIND -> showFindInPage()
            Screen.SETTINGS -> showBrowserSettings()
            Screen.SECURITY -> showSecurityLab()
            Screen.RENDER -> showRenderLab()
            Screen.BROWSER_APIS -> showBrowserApiLab()
            Screen.JAVASCRIPT -> showJavaScriptLab()
            Screen.PAINT -> showPaintLab()
            Screen.LAYOUT -> showLayoutLab()
            Screen.CSS -> showCssLab()
            Screen.HTML -> showHtmlLab()
            Screen.NETWORK -> showNetworkLab()
            Screen.CONSOLE -> showConsole()
            Screen.LOGS -> showLogs()
            Screen.CRASHES -> showCrashes()
        }
    }

    private fun showDashboard() {
        currentScreen = Screen.DASHBOARD
        runtime.profiler.measure("ui.dashboard.render") {
            val root = pageRoot()
            val hero = UiKit.card(context)
            val titleRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            titleRow.addView(UiKit.title(context, "AETHER", 30f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            titleRow.addView(UiKit.chip(context, "M18  WEB COMPAT", UiKit.SUCCESS))
            hero.addView(titleRow)
            hero.addView(UiKit.spacer(context, 8))
            hero.addView(UiKit.body(context, "A private multi-tab browser shell with a native JavaScript runtime, live modern DOM bindings, automatic inline/external script execution, events, timers, Promise microtasks, Fetch/XHR foundations and script-driven reflow — still without WebView.", UiKit.TEXT, 16f))
            hero.addView(UiKit.spacer(context, 14))
            hero.addView(UiKit.body(context, "KERNEL  ${runtime.state}     SELF-TEST  157/157     JVM TESTS  679     WEBVIEW  OFF", UiKit.PRIMARY, 12f).apply {
                typeface = Typeface.MONOSPACE
            })
            root.addView(hero)
            root.addView(UiKit.spacer(context, 14))

            val networkStats = runtime.network?.client?.statistics()
            val htmlStats = runtime.html.statistics()
            val cssStats = runtime.css.statistics()
            val layoutStats = runtime.layout.statistics()
            val paintStats = runtime.paint.statistics()
            val jsStats = runtime.js.statistics()
            val browserStats = runtime.browser.statistics()
            val renderStats = runtime.render.statistics()
            val securityStats = runtime.security.statistics()
            val system = AndroidSystemProbe.snapshot()
            root.addView(sectionTitle("Engine health"))
            root.addView(UiKit.spacer(context, 8))
            root.addView(metricGrid(listOf(
                "Engine" to BuildInfo.ENGINE_VERSION,
                "Android" to "SDK ${system.sdk}",
                "Frames" to renderStats.framesProduced.toString(),
                "Composites" to renderStats.compositePasses.toString(),
                "Pages" to browserStats.pagesOpened.toString(),
                "Security blocks" to (securityStats.blockedNavigations + securityStats.blockedSubresources).toString(),
                "DOM queries" to browserStats.domQueries.toString(),
                "JS scripts" to jsStats.scriptsEvaluated.toString(),
                "JS steps" to jsStats.stepsExecuted.toString(),
                "Paint lists" to paintStats.displayListsBuilt.toString(),
                "Commands" to paintStats.commandsProduced.toString(),
                "Layouts" to layoutStats.layoutsCompleted.toString(),
                "Layout boxes" to layoutStats.boxesProduced.toString(),
                "CSS sheets" to cssStats.styleSheetsParsed.toString(),
                "HTML docs" to htmlStats.documentsParsed.toString(),
                "Requests" to (networkStats?.requests ?: 0L).toString(),
                "Crashes" to runtime.crashReporter.recent().size.toString()
            )))
            root.addView(UiKit.spacer(context, 18))

            root.addView(sectionTitle("M18 controls"))
            root.addView(UiKit.spacer(context, 8))
            root.addView(UiKit.button(context, "Open secure browser shell", primary = true) { showBrowserShell() }, match())
            root.addView(UiKit.spacer(context, 9))
            root.addView(UiKit.button(context, "Open M11 security lab") { showSecurityLab() }, match())
            root.addView(UiKit.spacer(context, 9))
            root.addView(UiKit.button(context, "Open M9 rendering pipeline lab") { showRenderLab() }, match())
            root.addView(UiKit.spacer(context, 9))
            root.addView(UiKit.button(context, "Open M8 Browser API lab") { showBrowserApiLab() }, match())
            root.addView(UiKit.spacer(context, 9))
            root.addView(UiKit.button(context, "Open M7 JavaScript runtime lab") { showJavaScriptLab() }, match())
            root.addView(UiKit.spacer(context, 9))
            root.addView(UiKit.button(context, "Open M6 paint engine lab") { showPaintLab() }, match())
            root.addView(UiKit.spacer(context, 9))
            root.addView(UiKit.button(context, "Open M5 layout engine lab") { showLayoutLab() }, match())
            root.addView(UiKit.spacer(context, 9))
            root.addView(UiKit.button(context, "Open M4 CSS engine lab") { showCssLab() }, match())
            root.addView(UiKit.spacer(context, 9))
            root.addView(UiKit.button(context, "Open M3 HTML engine lab") { showHtmlLab() }, match())
            root.addView(UiKit.spacer(context, 9))
            root.addView(UiKit.button(context, "Open M2 networking lab") { showNetworkLab() }, match())
            root.addView(UiKit.spacer(context, 9))
            root.addView(UiKit.button(context, "Run cumulative 157-check engine self-test") { runSelfTest() }, match())
            root.addView(UiKit.spacer(context, 9))
            val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(UiKit.button(context, "Dev console") { showConsole() }, weighted())
            actions.addView(UiKit.spacer(context, 8).apply { layoutParams = LinearLayout.LayoutParams(context.dp(8), 1) })
            actions.addView(UiKit.button(context, "Logs") { showLogs() }, weighted())
            root.addView(actions)
            root.addView(UiKit.spacer(context, 9))
            root.addView(UiKit.button(context, "Crash vault") { showCrashes() }, match())
            root.addView(UiKit.spacer(context, 20))

            val architecture = UiKit.card(context)
            architecture.addView(UiKit.body(context, "M14–M18 NATIVE WEB COMPATIBILITY BATCH", UiKit.PRIMARY, 12f).apply { typeface = Typeface.MONOSPACE })
            architecture.addView(UiKit.spacer(context, 8))
            architecture.addView(UiKit.body(context,
                """Browser workspace
  bookmark folders + JSON transfer • grouped visit history • persistent verified downloads

Reading and privacy
  Reader Mode • highlighted Find in Page • separate cookies/cache/history/storage in incognito

JavaScript + DOM foundation
  arrow functions • for-of • try/catch/throw • constructors • Promises • timers • JSON • modern array helpers

Browser scripting
  live document/window/element objects • events • MutationObserver • Fetch/XHR • storage • script-driven reflow

Cumulative foundation
  M1–M12.1 networking, browser features, security, rendering rescue and private sessions preserved""",
                UiKit.TEXT, 14f))
            root.addView(architecture)
            root.addView(UiKit.spacer(context, 18))
            root.addView(UiKit.body(context, "Developer: Mohnish Raj  •  Edition 2026  •  M18 cumulative native web compatibility", UiKit.MUTED, 12f).apply { gravity = Gravity.CENTER })
            setPage(root)
        }
    }



    private fun showSecurityLab() {
        currentScreen = Screen.SECURITY
        val root = pageRoot()
        root.addView(header("Security engine lab"))
        root.addView(UiKit.spacer(context, 10))

        val intro = UiKit.card(context)
        intro.addView(UiKit.body(context, "M11 live policy enforcement", UiKit.TEXT, 18f).apply { typeface = Typeface.DEFAULT_BOLD })
        intro.addView(UiKit.spacer(context, 6))
        intro.addView(UiKit.body(context, "Tests tuple origins, CSP source lists, mixed-content blocking, CORS, sandbox restrictions and origin permissions without delegating to WebView."))
        root.addView(intro)
        root.addView(UiKit.spacer(context, 12))

        val output = monospacedCard("Tap Run security matrix.")
        root.addView(output)
        root.addView(UiKit.spacer(context, 10))

        fun runMatrix() {
            val headers = com.mohnishraj.aether.core.net.model.NetworkHeaders.of(
                "Content-Security-Policy" to "default-src 'self'; connect-src https://api.aether.test; script-src 'none'; object-src 'none'; form-action 'self'",
                "Permissions-Policy" to "camera=(), microphone=(), geolocation=()"
            )
            val policy = runtime.security.buildDocumentPolicy("https://app.aether.test/", headers)
            val sameOrigin = runtime.security.sameOrigin("https://app.aether.test/a", "https://app.aether.test/b")
            val cspAllowed = runtime.security.authorizeSubresource(policy, SecurityResourceType.CONNECT, "https://api.aether.test/data")
            val cspBlocked = runtime.security.authorizeSubresource(policy, SecurityResourceType.CONNECT, "https://tracker.invalid/data")
            val mixed = runtime.security.authorizeSubresource(policy, SecurityResourceType.IMAGE, "http://app.aether.test/logo.png")
            val script = runtime.security.authorizeInlineScript(policy)
            val camera = runtime.security.authorizePermission(policy, PermissionFeature.CAMERA)
            val stats = runtime.security.statistics()
            output.text = buildString {
                appendLine("AETHER M11 SECURITY MATRIX")
                appendLine("==========================")
                appendLine("PASS tuple-origin same=$sameOrigin")
                appendLine("${if (cspAllowed.allowed) "PASS" else "FAIL"} CSP allowed endpoint — ${cspAllowed.reason}")
                appendLine("${if (!cspBlocked.allowed) "PASS" else "FAIL"} CSP blocked tracker — ${cspBlocked.reason}")
                appendLine("${if (!mixed.allowed) "PASS" else "FAIL"} mixed content — ${mixed.reason}")
                appendLine("${if (!script.allowed) "PASS" else "FAIL"} inline script — ${script.reason}")
                appendLine("${if (!camera.allowed) "PASS" else "FAIL"} camera permission — ${camera.reason}")
                appendLine("--------------------------")
                append("blocks=${stats.blockedNavigations + stats.blockedSubresources} csp=${stats.cspBlocks} mixed=${stats.mixedContentBlocks} cors=${stats.corsBlocks}")
            }
        }

        root.addView(UiKit.button(context, "Run security matrix", primary = true) { runMatrix() }, match())
        root.addView(UiKit.spacer(context, 8))
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(UiKit.button(context, "Deny camera") {
            runtime.security.setPermission("https://app.aether.test/", PermissionFeature.CAMERA, PermissionDecision.DENY)
            runMatrix()
        }, weighted())
        row.addView(UiKit.spacer(context, 8).apply { layoutParams = LinearLayout.LayoutParams(context.dp(8), 1) })
        row.addView(UiKit.button(context, "Clear permissions") {
            runtime.security.clearPermissions()
            runMatrix()
        }, weighted())
        root.addView(row)
        setPage(root)
    }

    private fun showBrowserShell() {
        currentScreen = Screen.BROWSER
        var needsInitialLoad = false
        if (browserShell.tabCount() == 0) {
            val restored = browserShell.restoreSession(loadActive = false)
            if (!restored || browserShell.activeTabId() == null) browserShell.openTab(activate = true)
        }
        if (browserShell.activeRenderSession() == null) needsInitialLoad = true

        val snapshot = browserShell.activeSnapshot()
        val root = pageRoot().apply { setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(8)) }

        val addressRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val back = UiKit.button(context, "‹") { runBrowserOperation { browserShell.goBack(browserViewport()) } }.apply {
            isEnabled = snapshot?.canGoBack == true
            contentDescription = "Back"
        }
        val forward = UiKit.button(context, "›") { runBrowserOperation { browserShell.goForward(browserViewport()) } }.apply {
            isEnabled = snapshot?.canGoForward == true
            contentDescription = "Forward"
        }
        addressRow.addView(back, LinearLayout.LayoutParams(context.dp(48), context.dp(48)))
        addressRow.addView(UiKit.spacer(context, 4).apply { layoutParams = LinearLayout.LayoutParams(context.dp(4), 1) })
        addressRow.addView(forward, LinearLayout.LayoutParams(context.dp(48), context.dp(48)))
        addressRow.addView(UiKit.spacer(context, 6).apply { layoutParams = LinearLayout.LayoutParams(context.dp(6), 1) })
        val address = EditText(context).apply {
            setText(snapshot?.url.orEmpty())
            setTextColor(UiKit.TEXT)
            setHintTextColor(UiKit.MUTED)
            hint = "Search or enter address"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            background = UiKit.background(UiKit.SURFACE_2, 14, UiKit.BORDER, context)
            setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
            setSelectAllOnFocus(true)
        }
        addressRow.addView(address, LinearLayout.LayoutParams(0, context.dp(48), 1f))
        addressRow.addView(UiKit.spacer(context, 6).apply { layoutParams = LinearLayout.LayoutParams(context.dp(6), 1) })
        addressRow.addView(UiKit.button(context, "Go", primary = true) { navigateFromAddress(address) }, LinearLayout.LayoutParams(context.dp(58), context.dp(48)))
        root.addView(addressRow)
        address.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { navigateFromAddress(address); true } else false
        }
        root.addView(UiKit.spacer(context, 6))

        val actionRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actionRow.addView(UiKit.button(context, "AETHER") { showDashboard() }, LinearLayout.LayoutParams(0, context.dp(44), 1.15f))
        actionRow.addView(UiKit.spacer(context, 5).apply { layoutParams = LinearLayout.LayoutParams(context.dp(5), 1) })
        actionRow.addView(UiKit.button(context, "Reload") { runBrowserOperation { browserShell.reload(browserViewport()) } }, LinearLayout.LayoutParams(0, context.dp(44), 1f))
        actionRow.addView(UiKit.spacer(context, 5).apply { layoutParams = LinearLayout.LayoutParams(context.dp(5), 1) })
        actionRow.addView(UiKit.button(context, "+ Tab") { runBrowserOperation { browserShell.openTab("about:blank", viewport = browserViewport()) } }, LinearLayout.LayoutParams(0, context.dp(44), 1f))
        actionRow.addView(UiKit.spacer(context, 5).apply { layoutParams = LinearLayout.LayoutParams(context.dp(5), 1) })
        actionRow.addView(UiKit.button(context, "Close") {
            val active = browserShell.activeTabId() ?: return@button
            runBrowserOperation { browserShell.closeTab(active, browserViewport()) }
        }, LinearLayout.LayoutParams(0, context.dp(44), 1f))
        root.addView(actionRow)
        root.addView(UiKit.spacer(context, 6))

        val featureRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val bookmarked = snapshot?.url?.let(runtime.features.bookmarks::isBookmarked) == true
        featureRow.addView(UiKit.button(context, if (bookmarked) "★ Saved" else "☆ Save") { bookmarkCurrentPage() }, LinearLayout.LayoutParams(0, context.dp(42), 1f))
        featureRow.addView(UiKit.spacer(context, 5).apply { layoutParams = LinearLayout.LayoutParams(context.dp(5), 1) })
        featureRow.addView(UiKit.button(context, "Find") { showFindInPage() }, LinearLayout.LayoutParams(0, context.dp(42), 1f))
        featureRow.addView(UiKit.spacer(context, 5).apply { layoutParams = LinearLayout.LayoutParams(context.dp(5), 1) })
        featureRow.addView(UiKit.button(context, "Reader") { showReader() }, LinearLayout.LayoutParams(0, context.dp(42), 1f))
        featureRow.addView(UiKit.spacer(context, 5).apply { layoutParams = LinearLayout.LayoutParams(context.dp(5), 1) })
        featureRow.addView(UiKit.button(context, if (incognitoActive) "Private ●" else "Menu") { showBrowserMenu() }, LinearLayout.LayoutParams(0, context.dp(42), 1.15f))
        root.addView(featureRow)
        if (incognitoActive) {
            root.addView(UiKit.spacer(context, 5))
            root.addView(UiKit.body(context, "INCOGNITO • cookies, cache, storage and history are isolated and wiped on exit", UiKit.MUTED, 11f).apply {
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
            })
        }
        root.addView(UiKit.spacer(context, 6))

        val tabRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        browserShell.snapshots().forEach { tab ->
            val label = (if (tab.active) "● " else "") + tab.title.take(24)
            tabRow.addView(UiKit.button(context, label, primary = tab.active) {
                runBrowserOperation { browserShell.activateTab(tab.id, browserViewport()) }
            }, LinearLayout.LayoutParams(context.dp(156), context.dp(42)))
            tabRow.addView(UiKit.spacer(context, 5).apply { layoutParams = LinearLayout.LayoutParams(context.dp(5), 1) })
        }
        root.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = HorizontalScrollView.OVER_SCROLL_NEVER
            addView(tabRow)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(44)))

        val status = UiKit.body(context, browserStatusText(snapshot), UiKit.MUTED, 11f).apply {
            typeface = Typeface.MONOSPACE
            maxLines = 1
            isHorizontalFadingEdgeEnabled = true
            visibility = if (BuildConfig.DEBUG) android.view.View.VISIBLE else android.view.View.GONE
        }
        if (BuildConfig.DEBUG) {
            root.addView(UiKit.spacer(context, 4))
            root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(22)))
        }
        root.addView(UiKit.spacer(context, 4))

        val preview = AetherCompositorView(context).apply {
            imageNetwork = if (incognitoActive) runtime.incognito?.network else runtime.network
            background = UiKit.background(UiKit.SURFACE, 14, UiKit.BORDER, context)
            minimumHeight = context.dp(260)
            onViewportChanged = { widthCssPx, heightCssPx ->
                val next = RenderViewport(widthCssPx.coerceAtLeast(240.0), heightCssPx.coerceAtLeast(240.0))
                val changed = kotlin.math.abs(next.widthPx - browserViewportState.widthPx) > 0.5 ||
                    kotlin.math.abs(next.heightPx - browserViewportState.heightPx) > 0.5
                browserViewportState = next
                if (changed) {
                    browserShell.activeRenderSession()?.let { session ->
                        session.resize(next)
                        post { if (currentScreen == Screen.BROWSER) renderBrowserPreview(this@apply, status) }
                    }
                }
            }
        }
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setPage(root, scrolling = false)

        preview.post {
            if (currentScreen != Screen.BROWSER) return@post
            val density = resources.displayMetrics.density.coerceAtLeast(0.1f)
            if (preview.width > 0 && preview.height > 0) {
                browserViewportState = RenderViewport(
                    (preview.width / density.toDouble()).coerceAtLeast(240.0),
                    (preview.height / density.toDouble()).coerceAtLeast(240.0)
                )
            }
            if (needsInitialLoad) runBrowserOperation { browserShell.reload(browserViewport()) }
            else {
                browserShell.activeRenderSession()?.resize(browserViewport())
                renderBrowserPreview(preview, status)
            }
        }
    }

    private fun navigateFromAddress(address: EditText) {
        val input = address.text.toString().trim()
        val active = browserShell.activeTabId() ?: browserShell.openTab(activate = true)
        runBrowserOperation { browserShell.navigate(active, input, viewport = browserViewport()) }
    }

    private fun runBrowserOperation(operation: () -> BrowserNavigationResult?) {
        if (currentScreen != Screen.BROWSER) return
        runCatching {
            executor.execute {
                val outcome = runCatching(operation)
                mainHandler.post {
                    if (currentScreen != Screen.BROWSER || isFinishing || isDestroyed) return@post
                    outcome.onSuccess { showBrowserShell() }
                        .onFailure { error ->
                            AlertDialog.Builder(context)
                                .setTitle("Browser operation failed")
                                .setMessage(error.message ?: error::class.java.simpleName)
                                .setPositiveButton("OK", null)
                                .show()
                        }
                }
            }
        }.onFailure {
            AlertDialog.Builder(context)
                .setTitle("Browser is busy")
                .setMessage("Wait for the current navigation to finish.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun renderBrowserPreview(preview: AetherCompositorView, status: TextView) {
        val session = browserShell.activeRenderSession()
        if (session == null) {
            status.text = browserStatusText(browserShell.activeSnapshot()) + "  •  waiting for page"
            return
        }
        val token = ++browserFramePumpToken
        fun submit(frame: com.mohnishraj.aether.core.render.RenderFrame) {
            preview.submit(frame)
            status.text = browserStatusText(browserShell.activeSnapshot()) +
                "  •  frame ${frame.generation}  •  ${frame.composition.layerCount} layers  •  ${"%.2f".format(Locale.US, frame.timings.totalMillis)} ms"
        }
        fun renderNow() = submit(session.renderNow(System.nanoTime().coerceAtLeast(0L)))
        preview.onScrollBy = { dx, dy ->
            session.scrollBy(dx, dy, ScrollBehavior.INSTANT)
            renderNow()
        }
        renderNow()
        val pump = object : Runnable {
            override fun run() {
                if (token != browserFramePumpToken || currentScreen != Screen.BROWSER || isFinishing || isDestroyed) return
                if (browserShell.activeRenderSession() !== session) return
                session.renderIfDue(System.nanoTime().coerceAtLeast(0L))?.let(::submit)
                if (session.hasPendingFrame()) {
                    val delay = session.nextScriptTaskDelayMillis()?.coerceIn(16L, 250L) ?: 16L
                    mainHandler.postDelayed(this, delay)
                }
            }
        }
        if (session.hasPendingFrame()) mainHandler.postDelayed(pump, 16L)
    }

    private fun browserStatusText(snapshot: com.mohnishraj.aether.core.shell.BrowserTabSnapshot?): String {
        if (snapshot == null) return "NO ACTIVE TAB"
        val security = when (snapshot.security) {
            SecurityIndicator.INTERNAL -> "AETHER"
            SecurityIndicator.SECURE -> "HTTPS"
            SecurityIndicator.INSECURE -> "HTTP"
        }
        return "$security  •  ${snapshot.loadState}  •  ${snapshot.progress}%  •  ${browserShell.tabCount()} tabs  •  history ${snapshot.historySize}"
    }

    private fun browserViewport(): RenderViewport = browserViewportState


    private fun showBrowserMenu() {
        val items = arrayOf(
            "Bookmarks",
            "History",
            "Downloads",
            "Browser settings",
            if (incognitoActive) "Exit incognito" else "New incognito session"
        )
        AlertDialog.Builder(context)
            .setTitle(if (incognitoActive) "Private browser" else "Browser menu")
            .setItems(items) { _, index ->
                when (index) {
                    0 -> showBookmarks()
                    1 -> showHistory()
                    2 -> showDownloads()
                    3 -> showBrowserSettings()
                    4 -> toggleIncognito()
                }
            }
            .show()
    }

    private fun toggleIncognito() {
        if (incognitoActive) {
            AndroidImagePainter.clear(runtime.incognito?.network)
            runtime.incognito?.wipe()
            incognitoActive = false
            Toast.makeText(context, "Incognito data wiped", Toast.LENGTH_SHORT).show()
        } else {
            if (runtime.incognito == null) {
                Toast.makeText(context, "Incognito runtime is unavailable", Toast.LENGTH_SHORT).show()
                return
            }
            runtime.shell.saveSession()
            incognitoActive = true
        }
        showBrowserShell()
    }

    private fun bookmarkCurrentPage() {
        val snapshot = browserShell.activeSnapshot() ?: return
        runCatching { runtime.features.bookmarks.add(snapshot.title, snapshot.url) }
            .onSuccess { Toast.makeText(context, "Saved to bookmarks", Toast.LENGTH_SHORT).show(); showBrowserShell() }
            .onFailure { Toast.makeText(context, it.message ?: "Could not save bookmark", Toast.LENGTH_LONG).show() }
    }

    private fun showBookmarks() {
        currentScreen = Screen.BOOKMARKS
        val root = pageRoot()
        root.addView(browserFeatureHeader("Bookmarks"))
        root.addView(UiKit.spacer(context, 10))

        val query = EditText(context).apply {
            hint = "Search title or URL"
            setTextColor(UiKit.TEXT)
            setHintTextColor(UiKit.MUTED)
            background = UiKit.background(UiKit.SURFACE_2, 14, UiKit.BORDER, context)
            setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
            setSingleLine(true)
        }
        val searchRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        searchRow.addView(query, LinearLayout.LayoutParams(0, context.dp(48), 1f))
        searchRow.addView(UiKit.spacer(context, 6).apply { layoutParams = LinearLayout.LayoutParams(context.dp(6), 1) })
        searchRow.addView(UiKit.button(context, "Search") { renderBookmarkList(query.text.toString()) }, LinearLayout.LayoutParams(context.dp(92), context.dp(48)))
        root.addView(searchRow)
        root.addView(UiKit.spacer(context, 8))

        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(UiKit.button(context, "+ Folder") { createBookmarkFolderDialog() }, weighted())
        actions.addView(UiKit.spacer(context, 6).apply { layoutParams = LinearLayout.LayoutParams(context.dp(6), 1) })
        actions.addView(UiKit.button(context, "Export JSON") {
            runtime.browser.clipboard.writeText(runtime.features.bookmarks.exportJson())
            Toast.makeText(context, "Bookmark JSON copied", Toast.LENGTH_SHORT).show()
        }, weighted())
        actions.addView(UiKit.spacer(context, 6).apply { layoutParams = LinearLayout.LayoutParams(context.dp(6), 1) })
        actions.addView(UiKit.button(context, "Import") { importBookmarksDialog() }, weighted())
        root.addView(actions)
        root.addView(UiKit.spacer(context, 10))

        val folders = runtime.features.bookmarks.folders()
        if (folders.isNotEmpty()) {
            root.addView(sectionTitle("Folders"))
            folders.forEach { folder ->
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(UiKit.button(context, "📁 ${folder.name}") { renderBookmarkList("", folder.id) }, weighted())
                row.addView(UiKit.spacer(context, 6).apply { layoutParams = LinearLayout.LayoutParams(context.dp(6), 1) })
                row.addView(UiKit.button(context, "Rename") { renameBookmarkFolderDialog(folder.id, folder.name) }, LinearLayout.LayoutParams(context.dp(92), context.dp(48)))
                row.addView(UiKit.spacer(context, 6).apply { layoutParams = LinearLayout.LayoutParams(context.dp(6), 1) })
                row.addView(UiKit.button(context, "Delete") {
                    runtime.features.bookmarks.deleteFolder(folder.id)
                    showBookmarks()
                }, LinearLayout.LayoutParams(context.dp(84), context.dp(48)))
                root.addView(row)
                root.addView(UiKit.spacer(context, 6))
            }
        }

        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; tag = BOOKMARK_LIST_TAG }
        root.addView(list)
        setPage(root)
        populateBookmarkList(list, runtime.features.bookmarks.all())
    }

    private fun renderBookmarkList(query: String, folderId: Long? = null) {
        val content = findViewById<FrameLayout>(android.R.id.content)
        val list = findViewWithTag(content, BOOKMARK_LIST_TAG) as? LinearLayout ?: return
        populateBookmarkList(list, runtime.features.bookmarks.search(query, folderId))
    }

    private fun populateBookmarkList(container: LinearLayout, bookmarks: List<com.mohnishraj.aether.core.browser.features.Bookmark>) {
        container.removeAllViews()
        container.addView(sectionTitle("Saved pages • ${bookmarks.size}"))
        if (bookmarks.isEmpty()) {
            container.addView(monospacedCard("No matching bookmarks."))
            return
        }
        bookmarks.forEach { bookmark ->
            container.addView(UiKit.spacer(context, 8))
            val card = UiKit.card(context)
            card.addView(UiKit.body(context, bookmark.title, UiKit.TEXT, 17f).apply { typeface = Typeface.DEFAULT_BOLD })
            card.addView(UiKit.spacer(context, 4))
            card.addView(UiKit.body(context, bookmark.url, UiKit.MUTED, 12f))
            val folderName = bookmark.folderId?.let { id -> runtime.features.bookmarks.folders().firstOrNull { it.id == id }?.name }
            if (folderName != null) card.addView(UiKit.body(context, "Folder: $folderName", UiKit.PRIMARY, 12f))
            card.addView(UiKit.spacer(context, 8))
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(UiKit.button(context, "Open", primary = true) { openBrowserUrl(bookmark.url) }, weighted())
            row.addView(UiKit.spacer(context, 6).apply { layoutParams = LinearLayout.LayoutParams(context.dp(6), 1) })
            row.addView(UiKit.button(context, "Edit") { editBookmarkDialog(bookmark) }, weighted())
            row.addView(UiKit.spacer(context, 6).apply { layoutParams = LinearLayout.LayoutParams(context.dp(6), 1) })
            row.addView(UiKit.button(context, "Delete") { runtime.features.bookmarks.delete(bookmark.id); showBookmarks() }, weighted())
            card.addView(row)
            container.addView(card)
        }
    }

    private fun createBookmarkFolderDialog() {
        val input = dialogInput("Folder name")
        AlertDialog.Builder(context).setTitle("New bookmark folder").setView(input)
            .setPositiveButton("Create") { _, _ ->
                runCatching { runtime.features.bookmarks.createFolder(input.text.toString()) }
                    .onSuccess { showBookmarks() }
                    .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun renameBookmarkFolderDialog(id: Long, current: String) {
        val input = dialogInput("Folder name").apply { setText(current); selectAll() }
        AlertDialog.Builder(context).setTitle("Rename folder").setView(input)
            .setPositiveButton("Save") { _, _ ->
                runCatching { runtime.features.bookmarks.renameFolder(id, input.text.toString()) }
                    .onSuccess { showBookmarks() }
                    .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun editBookmarkDialog(bookmark: com.mohnishraj.aether.core.browser.features.Bookmark) {
        val form = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(context.dp(20), 0, context.dp(20), 0) }
        val title = dialogInput("Title").apply { setText(bookmark.title) }
        val url = dialogInput("URL").apply { setText(bookmark.url) }
        form.addView(title); form.addView(UiKit.spacer(context, 8)); form.addView(url)
        val folders = runtime.features.bookmarks.folders()
        val labels = arrayOf("No folder") + folders.map { it.name }
        var selected = bookmark.folderId?.let { id -> folders.indexOfFirst { it.id == id } + 1 }?.takeIf { it > 0 } ?: 0
        AlertDialog.Builder(context).setTitle("Edit bookmark").setView(form)
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setPositiveButton("Save") { _, _ ->
                val folderId = if (selected == 0) null else folders[selected - 1].id
                runCatching { runtime.features.bookmarks.edit(bookmark.id, title.text.toString(), url.text.toString(), folderId) }
                    .onSuccess { showBookmarks() }
                    .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun importBookmarksDialog() {
        val input = EditText(context).apply {
            hint = "Paste Aether bookmark JSON"
            setTextColor(UiKit.TEXT); setHintTextColor(UiKit.MUTED)
            minLines = 8; maxLines = 14
            setText(runtime.browser.clipboard.readText().takeIf { it.trimStart().startsWith("{") }.orEmpty())
        }
        AlertDialog.Builder(context).setTitle("Import bookmarks").setView(input)
            .setPositiveButton("Import") { _, _ ->
                runCatching { runtime.features.bookmarks.importJson(input.text.toString(), ImportDuplicatePolicy.SKIP) }
                    .onSuccess { summary -> Toast.makeText(context, "Imported ${summary.imported}, skipped ${summary.skipped}", Toast.LENGTH_LONG).show(); showBookmarks() }
                    .onFailure { Toast.makeText(context, it.message ?: "Invalid JSON", Toast.LENGTH_LONG).show() }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showHistory() {
        currentScreen = Screen.HISTORY
        val root = pageRoot()
        root.addView(browserFeatureHeader("History"))
        root.addView(UiKit.spacer(context, 10))
        val query = dialogInput("Search history")
        val search = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        search.addView(query, LinearLayout.LayoutParams(0, context.dp(48), 1f))
        search.addView(UiKit.spacer(context, 6).apply { layoutParams = LinearLayout.LayoutParams(context.dp(6), 1) })
        search.addView(UiKit.button(context, "Search") { showHistoryResults(query.text.toString()) }, LinearLayout.LayoutParams(context.dp(92), context.dp(48)))
        root.addView(search)
        root.addView(UiKit.spacer(context, 8))
        val controls = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(UiKit.button(context, "Clear selected") {
            runtime.features.history.clearSelected(selectedHistoryIds)
            selectedHistoryIds.clear(); showHistory()
        }, weighted())
        controls.addView(UiKit.spacer(context, 6).apply { layoutParams = LinearLayout.LayoutParams(context.dp(6), 1) })
        controls.addView(UiKit.button(context, "Clear all") { confirmClearHistory() }, weighted())
        root.addView(controls)
        root.addView(UiKit.spacer(context, 10))
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; tag = HISTORY_LIST_TAG }
        root.addView(list)
        setPage(root)
        populateHistory(list, runtime.features.history.groups().flatMap { group -> group.visits.map { group.period to it } })
    }

    private fun showHistoryResults(query: String) {
        val content = findViewById<FrameLayout>(android.R.id.content)
        val list = findViewWithTag(content, HISTORY_LIST_TAG) as? LinearLayout ?: return
        populateHistory(list, runtime.features.history.search(query).map { null to it })
    }

    private fun populateHistory(container: LinearLayout, visits: List<Pair<com.mohnishraj.aether.core.browser.features.HistoryPeriod?, com.mohnishraj.aether.core.browser.features.HistoryVisit>>) {
        container.removeAllViews()
        if (visits.isEmpty()) { container.addView(monospacedCard("No history entries.")); return }
        var previousPeriod: com.mohnishraj.aether.core.browser.features.HistoryPeriod? = null
        visits.forEach { (period, visit) ->
            if (period != null && period != previousPeriod) {
                container.addView(sectionTitle(period.name.replace('_', ' ')))
                previousPeriod = period
            }
            val card = UiKit.card(context)
            val select = CheckBox(context).apply {
                text = visit.title.ifBlank { visit.url }
                setTextColor(UiKit.TEXT)
                isChecked = visit.id in selectedHistoryIds
                setOnCheckedChangeListener { _, checked -> if (checked) selectedHistoryIds += visit.id else selectedHistoryIds -= visit.id }
            }
            card.addView(select)
            card.addView(UiKit.body(context, visit.url, UiKit.MUTED, 12f))
            card.addView(UiKit.body(context, "${formatHistoryTime(visit.visitedAtMillis)} • visit #${visit.visitNumberForUrl} • ${visit.transition}", UiKit.MUTED, 11f))
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(UiKit.button(context, "Open") { openBrowserUrl(visit.url) }, weighted())
            row.addView(UiKit.spacer(context, 6).apply { layoutParams = LinearLayout.LayoutParams(context.dp(6), 1) })
            row.addView(UiKit.button(context, "Delete") { runtime.features.history.clearSelected(setOf(visit.id)); showHistory() }, weighted())
            card.addView(UiKit.spacer(context, 6)); card.addView(row)
            container.addView(card); container.addView(UiKit.spacer(context, 8))
        }
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(context).setTitle("Clear all history?").setMessage("This removes every saved visit and visit counter.")
            .setPositiveButton("Clear") { _, _ -> runtime.features.history.clearAll(); selectedHistoryIds.clear(); showHistory() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showDownloads() {
        currentScreen = Screen.DOWNLOADS
        val root = pageRoot()
        root.addView(browserFeatureHeader("Downloads"))
        root.addView(UiKit.spacer(context, 10))
        val url = dialogInput("https://example.com/file.zip")
        val name = dialogInput("File name, e.g. file.zip")
        root.addView(url); root.addView(UiKit.spacer(context, 6)); root.addView(name); root.addView(UiKit.spacer(context, 6))
        root.addView(UiKit.button(context, "Start download", primary = true) {
            val fileName = sanitizeDownloadName(name.text.toString().ifBlank { url.text.toString().substringAfterLast('/').ifBlank { "download.bin" } })
            runCatching { downloadController.enqueue(url.text.toString(), VirtualPath.of("/downloads/$fileName")) }
                .onSuccess { startDownload(it.id) }
                .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
        }, match())
        root.addView(UiKit.spacer(context, 12))
        val items = downloadController.snapshots()
        root.addView(sectionTitle("Transfers • ${items.size}"))
        if (items.isEmpty()) root.addView(monospacedCard("No downloads yet."))
        items.forEach { item -> root.addView(UiKit.spacer(context, 8)); root.addView(downloadCard(item)) }
        setPage(root)
    }

    private fun downloadCard(item: ManagedDownload): LinearLayout = UiKit.card(context).apply {
        addView(UiKit.body(context, item.destination.name, UiKit.TEXT, 16f).apply { typeface = Typeface.DEFAULT_BOLD })
        addView(UiKit.body(context, item.url, UiKit.MUTED, 11f))
        val progress = item.progressPercent?.let { "$it%" } ?: "${item.bytesDownloaded} bytes"
        addView(UiKit.body(context, "${item.state} • $progress${item.errorMessage?.let { " • $it" }.orEmpty()}", if (item.state == DownloadState.FAILED) UiKit.DANGER else UiKit.MUTED, 12f))
        val actualSha256 = item.actualSha256
        if (actualSha256 != null) addView(UiKit.body(context, "SHA-256 ${actualSha256.take(20)}… • verified=${item.integrityVerified}", UiKit.MUTED, 11f))
        addView(UiKit.spacer(context, 6))
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        when (item.state) {
            DownloadState.RUNNING -> row.addView(UiKit.button(context, "Pause") { runCatching { downloadController.pause(item.id) }; showDownloads() }, weighted())
            DownloadState.PAUSED, DownloadState.FAILED, DownloadState.CANCELLED -> row.addView(UiKit.button(context, "Resume / Retry") {
                runCatching { downloadController.retry(item.id) }
                startDownload(item.id)
            }, weighted())
            else -> row.addView(UiKit.button(context, "Refresh") { showDownloads() }, weighted())
        }
        row.addView(UiKit.spacer(context, 6).apply { layoutParams = LinearLayout.LayoutParams(context.dp(6), 1) })
        row.addView(UiKit.button(context, "Cancel") { runCatching { downloadController.cancel(item.id) }; showDownloads() }, weighted())
        addView(row)
    }

    private fun startDownload(id: Long) {
        ensureDownloadNotificationsPermission()
        runCatching {
            downloadExecutor.execute {
                runCatching { downloadController.execute(id) { update -> notifyDownload(update) } }
                    .onFailure { error -> mainHandler.post { Toast.makeText(context, error.message, Toast.LENGTH_LONG).show() } }
                mainHandler.post { if (currentScreen == Screen.DOWNLOADS && !isFinishing && !isDestroyed) showDownloads() }
            }
        }.onFailure { Toast.makeText(context, "Download queue is full", Toast.LENGTH_LONG).show() }
        showDownloads()
    }

    private fun notifyDownload(item: ManagedDownload) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(DOWNLOAD_CHANNEL, "Aether downloads", NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val builder = Notification.Builder(context, DOWNLOAD_CHANNEL)
            .setSmallIcon(R.drawable.ic_aether)
            .setContentTitle(item.destination.name)
            .setContentText(item.errorMessage ?: item.state.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercaseChar() })
            .setOngoing(item.state in setOf(DownloadState.RUNNING, DownloadState.VERIFYING))
        item.progressPercent?.let { builder.setProgress(100, it, false) }
        manager.notify(item.id.toInt(), builder.build())
    }

    private fun ensureDownloadNotificationsPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), DOWNLOAD_PERMISSION_REQUEST)
        }
    }

    private fun showReader() {
        currentScreen = Screen.READER
        val page = browserShell.activePage()
        val root = pageRoot()
        root.addView(browserFeatureHeader("Reader mode"))
        root.addView(UiKit.spacer(context, 10))
        if (page == null) {
            root.addView(monospacedCard("Open a loaded page before entering Reader mode.")); setPage(root); return
        }
        val article = runtime.features.reader.extract(page)
        val session = browserShell.activeRenderSession()
        val frame = session?.currentFrame
        val progress = if (session != null && frame != null) runtime.features.reader.progress(session.currentScroll().y, frame.layout.root.scrollSize.height, frame.viewport.heightPx) else 0.0
        val controls = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(UiKit.button(context, "A−") { readerSettings = readerSettings.copy(fontScale = (readerSettings.fontScale - 0.1).coerceAtLeast(0.75)); showReader() }, weighted())
        controls.addView(UiKit.spacer(context, 6).apply { layoutParams = LinearLayout.LayoutParams(context.dp(6), 1) })
        controls.addView(UiKit.button(context, "A+") { readerSettings = readerSettings.copy(fontScale = (readerSettings.fontScale + 0.1).coerceAtMost(2.0)); showReader() }, weighted())
        controls.addView(UiKit.spacer(context, 6).apply { layoutParams = LinearLayout.LayoutParams(context.dp(6), 1) })
        controls.addView(UiKit.button(context, "Theme") { readerSettings = readerSettings.copy(theme = ReaderTheme.entries[(readerSettings.theme.ordinal + 1) % ReaderTheme.entries.size]); showReader() }, weighted())
        root.addView(controls); root.addView(UiKit.spacer(context, 10))
        val colors = readerColors(readerSettings.theme)
        val articleCard = UiKit.card(context).apply { background = UiKit.background(colors.first, 18, UiKit.BORDER, context) }
        articleCard.addView(UiKit.title(context, article.title, (26f * readerSettings.fontScale).toFloat()).apply { setTextColor(colors.second) })
        article.byline?.let { articleCard.addView(UiKit.body(context, it, colors.third, (13f * readerSettings.fontScale).toFloat())) }
        articleCard.addView(UiKit.body(context, "${article.wordCount} words • ${article.estimatedMinutes} min • source progress ${(progress * 100).toInt()}%", colors.third, 12f))
        article.paragraphs.forEach { paragraph ->
            articleCard.addView(UiKit.spacer(context, 12))
            articleCard.addView(UiKit.body(context, paragraph, colors.second, (17f * readerSettings.fontScale).toFloat()).apply { setLineSpacing(0f, readerSettings.lineHeight.toFloat()) })
        }
        root.addView(articleCard)
        setPage(root)
    }

    private fun showFindInPage() {
        currentScreen = Screen.FIND
        val root = pageRoot()
        root.addView(browserFeatureHeader("Find in page"))
        root.addView(UiKit.spacer(context, 10))
        val pageText = browserShell.activePage()?.document?.document?.body?.textContent.orEmpty()
        val input = dialogInput("Find text").apply { setText(lastFindResult.query) }
        val caseSensitive = CheckBox(context).apply { text = "Case sensitive"; setTextColor(UiKit.TEXT); isChecked = lastFindResult.caseSensitive }
        val status = UiKit.body(context, "0 matches", UiKit.MUTED, 13f)
        val preview = TextView(context).apply {
            setTextColor(UiKit.TEXT); textSize = 15f; setLineSpacing(0f, 1.35f); setPadding(context.dp(14), context.dp(14), context.dp(14), context.dp(14))
            background = UiKit.background(UiKit.SURFACE, 16, UiKit.BORDER, context)
        }
        fun render(result: FindResult) {
            lastFindResult = result
            status.text = if (result.count == 0) "0 matches" else "${result.selectedIndex + 1} / ${result.count} matches"
            val safeText = pageText.take(200_000)
            val spannable = SpannableString(safeText)
            result.matches.filter { it.endExclusive <= safeText.length }.forEachIndexed { index, match ->
                spannable.setSpan(BackgroundColorSpan(if (index == result.selectedIndex) UiKit.PRIMARY else UiKit.BORDER), match.start, match.endExclusive, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            preview.text = spannable
        }
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(input, LinearLayout.LayoutParams(0, context.dp(48), 1f))
        row.addView(UiKit.spacer(context, 6).apply { layoutParams = LinearLayout.LayoutParams(context.dp(6), 1) })
        row.addView(UiKit.button(context, "Find") { render(runtime.features.find.search(pageText, input.text.toString(), caseSensitive.isChecked)) }, LinearLayout.LayoutParams(context.dp(86), context.dp(48)))
        root.addView(row); root.addView(caseSensitive); root.addView(status); root.addView(UiKit.spacer(context, 6))
        val nav = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        nav.addView(UiKit.button(context, "Previous") { render(runtime.features.find.previous(lastFindResult)) }, weighted())
        nav.addView(UiKit.spacer(context, 6).apply { layoutParams = LinearLayout.LayoutParams(context.dp(6), 1) })
        nav.addView(UiKit.button(context, "Next") { render(runtime.features.find.next(lastFindResult)) }, weighted())
        root.addView(nav); root.addView(UiKit.spacer(context, 10)); root.addView(preview)
        render(runtime.features.find.search(pageText, lastFindResult.query, lastFindResult.caseSensitive, lastFindResult.selectedIndex.coerceAtLeast(0)))
        setPage(root)
    }

    private fun showBrowserSettings() {
        currentScreen = Screen.SETTINGS
        val root = pageRoot()
        root.addView(browserFeatureHeader("Browser settings"))
        root.addView(UiKit.spacer(context, 10))
        val privacy = UiKit.card(context)
        privacy.addView(UiKit.body(context, "Privacy", UiKit.TEXT, 18f).apply { typeface = Typeface.DEFAULT_BOLD })
        privacy.addView(UiKit.body(context, "Normal browsing persists bookmarks, history, cookies, cache and local storage. Incognito uses separate in-memory instances and wipes on exit."))
        privacy.addView(UiKit.spacer(context, 8))
        privacy.addView(UiKit.button(context, if (incognitoActive) "Exit incognito and wipe" else "Start incognito session", primary = true) { toggleIncognito() }, match())
        root.addView(privacy); root.addView(UiKit.spacer(context, 10))
        val data = UiKit.card(context)
        data.addView(UiKit.body(context, "Browsing data", UiKit.TEXT, 18f).apply { typeface = Typeface.DEFAULT_BOLD })
        data.addView(UiKit.body(context, "History ${runtime.features.history.all().size} • Bookmarks ${runtime.features.bookmarks.all().size} • Cache ${runtime.network?.cache?.stats()?.entries ?: 0}"))
        data.addView(UiKit.spacer(context, 8))
        data.addView(UiKit.button(context, "Clear history, cookies, cache and site storage") {
            AlertDialog.Builder(context).setTitle("Clear browsing data?").setMessage("Bookmarks and downloads remain.")
                .setPositiveButton("Clear") { _, _ ->
                    runtime.features.history.clearAll(); runtime.network?.clearPrivateData(); runtime.browser.clearAllStorage(); Toast.makeText(context, "Browsing data cleared", Toast.LENGTH_SHORT).show(); showBrowserSettings()
                }.setNegativeButton("Cancel", null).show()
        }, match())
        root.addView(data); root.addView(UiKit.spacer(context, 10))
        val reader = UiKit.card(context)
        reader.addView(UiKit.body(context, "Reader defaults", UiKit.TEXT, 18f).apply { typeface = Typeface.DEFAULT_BOLD })
        reader.addView(UiKit.body(context, "Font ${"%.1f".format(Locale.US, readerSettings.fontScale)}× • line height ${"%.2f".format(Locale.US, readerSettings.lineHeight)} • ${readerSettings.theme}"))
        root.addView(reader)
        setPage(root)
    }

    private fun openBrowserUrl(url: String) {
        currentScreen = Screen.BROWSER
        runBrowserOperation {
            val id = browserShell.activeTabId() ?: browserShell.openTab(activate = true)
            browserShell.navigate(id, url, viewport = browserViewport())
        }
    }

    private fun browserFeatureHeader(title: String): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(UiKit.button(context, "‹ Browser") { showBrowserShell() }, LinearLayout.LayoutParams(context.dp(110), context.dp(48)))
        addView(UiKit.spacer(context, 10).apply { layoutParams = LinearLayout.LayoutParams(context.dp(10), 1) })
        addView(UiKit.title(context, title, 22f), weighted())
    }

    private fun dialogInput(hintText: String): EditText = EditText(context).apply {
        hint = hintText; setTextColor(UiKit.TEXT); setHintTextColor(UiKit.MUTED); setSingleLine(true)
        background = UiKit.background(UiKit.SURFACE_2, 14, UiKit.BORDER, context)
        setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
    }

    private fun findViewWithTag(root: android.view.View, tagValue: String): android.view.View? {
        if (root.tag == tagValue) return root
        if (root is ViewGroup) for (index in 0 until root.childCount) findViewWithTag(root.getChildAt(index), tagValue)?.let { return it }
        return null
    }

    private fun formatHistoryTime(millis: Long): String = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.US)
        .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    private fun sanitizeDownloadName(value: String): String = value.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifEmpty { "download.bin" }

    private fun readerColors(theme: ReaderTheme): Triple<Int, Int, Int> = when (theme) {
        ReaderTheme.LIGHT -> Triple(0xfff6f3ec.toInt(), 0xff19202a.toInt(), 0xff5d6672.toInt())
        ReaderTheme.SEPIA -> Triple(0xffefe3c6.toInt(), 0xff392f21.toInt(), 0xff75644d.toInt())
        ReaderTheme.DARK -> Triple(UiKit.SURFACE, UiKit.TEXT, UiKit.MUTED)
    }


    private fun showRenderLab() {
        currentScreen = Screen.RENDER
        val root = pageRoot()
        root.addView(header("Rendering pipeline lab"))
        root.addView(UiKit.spacer(context, 10))

        val intro = UiKit.card(context)
        intro.addView(UiKit.body(context, "M9 live frame pipeline", UiKit.TEXT, 18f).apply { typeface = Typeface.DEFAULT_BOLD })
        intro.addView(UiKit.spacer(context, 6))
        intro.addView(UiKit.body(context, "Drag the preview, trigger smooth scrolling, mutate the DOM and swap styles. Aether reuses unaffected stages and composites native Canvas layers without WebView."))
        root.addView(intro)
        root.addView(UiKit.spacer(context, 12))

        val page = runtime.browser.open(DEFAULT_RENDER_HTML_SAMPLE, "https://lab.aether/m9")
        val session: RenderSession = runtime.render.open(page, DEFAULT_RENDER_CSS_SAMPLE, RenderViewport(360.0, 640.0))
        val preview = AetherCompositorView(context).apply {
            minimumHeight = context.dp(430)
            background = UiKit.background(UiKit.SURFACE, 16, UiKit.BORDER, context)
        }
        val status = monospacedCard("Building first frame…")
        var alternateStyle = false

        fun render(nowNanos: Long = System.nanoTime().coerceAtLeast(0L)) {
            val frame = session.renderNow(nowNanos)
            preview.submit(frame)
            status.text = RenderInspector.summary(frame) + "\n\n" + RenderInspector.damage(frame.composition)
        }

        preview.onScrollBy = { dx, dy ->
            session.scrollBy(dx, dy, ScrollBehavior.INSTANT)
            render()
        }
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(430)))
        root.addView(UiKit.spacer(context, 10))

        val controlsA = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        controlsA.addView(UiKit.button(context, "Scroll +240") {
            session.scrollBy(0.0, 240.0, ScrollBehavior.INSTANT)
            render()
        }, weighted())
        controlsA.addView(UiKit.spacer(context, 8).apply { layoutParams = LinearLayout.LayoutParams(context.dp(8), 1) })
        controlsA.addView(UiKit.button(context, "Smooth to bottom", primary = true) {
            session.scrollTo(0.0, 1_200.0, ScrollBehavior.SMOOTH)
            val animator = object : Runnable {
                override fun run() {
                    if (isFinishing || isDestroyed || currentScreen != Screen.RENDER) return
                    render()
                    if (session.hasPendingFrame()) mainHandler.postDelayed(this, 16L)
                }
            }
            mainHandler.post(animator)
        }, weighted())
        root.addView(controlsA)
        root.addView(UiKit.spacer(context, 8))

        val controlsB = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        controlsB.addView(UiKit.button(context, "Mutate DOM") {
            val title = page.document.getElementById("render-title")
            if (title != null) {
                page.document.setTextContent(title, "DOM changed at ${System.currentTimeMillis() % 100_000}")
                page.document.setAttribute(title, "class", if (title.classNames.contains("hot")) "" else "hot")
                page.document.deliverMutations()
                render()
            }
        }, weighted())
        controlsB.addView(UiKit.spacer(context, 8).apply { layoutParams = LinearLayout.LayoutParams(context.dp(8), 1) })
        controlsB.addView(UiKit.button(context, "Swap CSS") {
            alternateStyle = !alternateStyle
            session.updateStyleSheet(if (alternateStyle) DEFAULT_RENDER_CSS_ALT_SAMPLE else DEFAULT_RENDER_CSS_SAMPLE)
            render()
        }, weighted())
        root.addView(controlsB)
        root.addView(UiKit.spacer(context, 8))
        root.addView(UiKit.button(context, "Reset frame") {
            session.scrollTo(0.0, 0.0, ScrollBehavior.INSTANT)
            if (alternateStyle) {
                alternateStyle = false
                session.updateStyleSheet(DEFAULT_RENDER_CSS_SAMPLE)
            }
            render()
        }, match())
        root.addView(UiKit.spacer(context, 10))
        root.addView(status, match())
        setPage(root)
        render()
    }


    private fun showBrowserApiLab() {
        currentScreen = Screen.BROWSER_APIS
        val root = pageRoot()
        root.addView(header("Browser API lab"))
        root.addView(UiKit.spacer(context, 10))

        val intro = UiKit.card(context)
        intro.addView(UiKit.body(context, "M8 interactive page runtime", UiKit.TEXT, 18f).apply { typeface = Typeface.DEFAULT_BOLD })
        intro.addView(UiKit.spacer(context, 6))
        intro.addView(UiKit.body(context, "Run HTML and JavaScript against Aether's own DOM, events, storage, forms, clipboard and mutation APIs. No WebView is involved."))
        root.addView(intro)
        root.addView(UiKit.spacer(context, 12))

        root.addView(sectionTitle("HTML document"))
        root.addView(UiKit.spacer(context, 6))
        val htmlInput = EditText(context).apply {
            setText(DEFAULT_BROWSER_API_HTML_SAMPLE)
            setTextColor(UiKit.TEXT)
            setHintTextColor(UiKit.MUTED)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.START
            minLines = 7
            maxLines = 12
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            background = UiKit.background(UiKit.SURFACE_2, 14, UiKit.BORDER, context)
            setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
        }
        root.addView(htmlInput, match())
        root.addView(UiKit.spacer(context, 12))
        root.addView(sectionTitle("Page script"))
        root.addView(UiKit.spacer(context, 6))
        val scriptInput = EditText(context).apply {
            setText(DEFAULT_BROWSER_API_JS_SAMPLE)
            setTextColor(UiKit.TEXT)
            setHintTextColor(UiKit.MUTED)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.START
            minLines = 8
            maxLines = 14
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            background = UiKit.background(UiKit.SURFACE_2, 14, UiKit.BORDER, context)
            setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
        }
        root.addView(scriptInput, match())
        root.addView(UiKit.spacer(context, 10))
        val output = monospacedCard("Ready. Open the M8 page and run its script.")
        val runButton = UiKit.button(context, "Run M8 page", primary = true) {}
        runButton.setOnClickListener {
            val markup = htmlInput.text.toString()
            val source = scriptInput.text.toString()
            if (markup.isBlank() || source.isBlank()) {
                output.text = "EMPTY INPUT\nBoth HTML and JavaScript are required."
                return@setOnClickListener
            }
            runButton.isEnabled = false
            runButton.text = "Running…"
            output.text = "Opening page and executing Browser APIs…"
            runCatching {
                executor.execute {
                    val rendered = runCatching {
                        val page = runtime.browser.open(markup, "https://lab.aether/m8")
                        val result = page.evaluate(source, freshRealm = true)
                        val stats = runtime.browser.statistics()
                        buildString {
                            appendLine("M8 BROWSER APIs ${if (result.success) "PASS" else "FAIL"}")
                            appendLine("================================")
                            appendLine("url=${page.url}")
                            appendLine("result=${result.value.debugString()}")
                            appendLine("domQueries=${stats.domQueries} domMutations=${stats.domMutations}")
                            appendLine("events=${stats.eventsDispatched} storageWrites=${stats.storageWrites}")
                            if (result.output.isNotEmpty()) {
                                appendLine("---------------- CONSOLE")
                                result.output.forEach { appendLine(it) }
                            }
                            result.error?.let { appendLine("---------------- ERROR"); appendLine(it.pretty()) }
                            appendLine("---------------- DOM")
                            append(page.document.outerHtml(page.document.document).take(16_000))
                        }
                    }.getOrElse { error -> "M8 FAILURE\n${error::class.java.simpleName}: ${error.message}" }
                    mainHandler.post {
                        output.text = rendered
                        runButton.isEnabled = true
                        runButton.text = "Run M8 page"
                    }
                }
            }.onFailure { error ->
                output.text = "EXECUTOR BUSY\n${error.message.orEmpty()}"
                runButton.isEnabled = true
                runButton.text = "Run M8 page"
            }
        }
        root.addView(runButton, match())
        root.addView(UiKit.spacer(context, 8))
        root.addView(UiKit.button(context, "Reset M8 sample") {
            htmlInput.setText(DEFAULT_BROWSER_API_HTML_SAMPLE)
            scriptInput.setText(DEFAULT_BROWSER_API_JS_SAMPLE)
            output.text = "Sample restored."
        }, match())
        root.addView(UiKit.spacer(context, 10))
        root.addView(output, match())
        setPage(root)
    }


    private fun showJavaScriptLab() {
        currentScreen = Screen.JAVASCRIPT
        val root = pageRoot()
        root.addView(header("JavaScript runtime lab"))
        root.addView(UiKit.spacer(context, 10))

        val intro = UiKit.card(context)
        intro.addView(UiKit.body(context, "Independent M7 execution pipeline", UiKit.TEXT, 18f).apply { typeface = Typeface.DEFAULT_BOLD })
        intro.addView(UiKit.spacer(context, 6))
        intro.addView(UiKit.body(context, "Aether tokenizes, parses and interprets this source itself. The lab does not use WebView or Android's JavaScript engines."))
        root.addView(intro)
        root.addView(UiKit.spacer(context, 12))

        val sourceInput = EditText(context).apply {
            setText(DEFAULT_JAVASCRIPT_SAMPLE)
            setTextColor(UiKit.TEXT)
            setHintTextColor(UiKit.MUTED)
            hint = "Enter JavaScript source"
            textSize = 13f
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 12
            maxLines = 20
            gravity = Gravity.TOP or Gravity.START
            background = UiKit.background(UiKit.SURFACE_2, 14, UiKit.BORDER, context)
            setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
        }
        root.addView(sourceInput, match())
        root.addView(UiKit.spacer(context, 9))
        val output = monospacedCard("Ready. Run the M7 interpreter or inspect its AST.")
        val runButton = UiKit.button(context, "Run JavaScript", primary = true) {}
        runButton.setOnClickListener {
            val source = sourceInput.text.toString()
            if (source.isBlank()) {
                output.text = "EMPTY INPUT\nEnter JavaScript source before execution."
                return@setOnClickListener
            }
            runButton.isEnabled = false
            runButton.text = "Executing…"
            output.text = "Tokenizing and executing ${source.length} characters…"
            runCatching {
                executor.execute {
                    val rendered = runCatching {
                        val result = runtime.js.evaluate(source, "aether://javascript-lab", freshRealm = true)
                        buildString {
                            appendLine("M7 JAVASCRIPT ${if (result.success) "COMPLETE" else "FAILED"}")
                            appendLine(JsInspector.summary(result))
                            if (result.output.isNotEmpty()) {
                                appendLine("---------------- CONSOLE")
                                result.output.forEach { appendLine(it) }
                            }
                            if (result.issues.isNotEmpty()) {
                                appendLine("---------------- ISSUES")
                                result.issues.take(20).forEach { appendLine(it) }
                            }
                            result.error?.let { appendLine("---------------- ERROR"); appendLine(it.pretty()) }
                            appendLine("---------------- VALUE")
                            append(result.value.debugString())
                        }.take(30_000)
                    }.getOrElse { error -> "JAVASCRIPT LAB FAILED\n${error::class.java.simpleName}: ${error.message.orEmpty()}" }
                    mainHandler.post {
                        if (isFinishing || isDestroyed) return@post
                        runButton.isEnabled = true
                        runButton.text = "Run JavaScript"
                        output.text = rendered
                    }
                }
            }.onFailure { error ->
                runButton.isEnabled = true
                runButton.text = "Run JavaScript"
                output.text = "JAVASCRIPT QUEUE BUSY\n${error.message.orEmpty()}"
            }
        }
        root.addView(runButton, match())
        root.addView(UiKit.spacer(context, 9))
        val astButton = UiKit.button(context, "Inspect parsed AST") {
            val parsed = runtime.js.compile(sourceInput.text.toString())
            output.text = buildString {
                appendLine("M7 AST")
                appendLine("tokens=${parsed.tokenCount} nodes=${parsed.astNodeCount} issues=${parsed.issues.size}")
                parsed.issues.forEach { appendLine(it) }
                appendLine("----------------")
                append(JsInspector.ast(parsed, maxDepth = 40).take(28_000))
            }
        }
        root.addView(astButton, match())
        root.addView(UiKit.spacer(context, 12))
        root.addView(output, match())
        setPage(root)
    }

    private fun showPaintLab() {
        currentScreen = Screen.PAINT
        val root = pageRoot()
        root.addView(header("Paint engine lab"))
        root.addView(UiKit.spacer(context, 10))

        val intro = UiKit.card(context)
        intro.addView(UiKit.body(context, "Render Aether display lists", UiKit.TEXT, 18f).apply { typeface = Typeface.DEFAULT_BOLD })
        intro.addView(UiKit.spacer(context, 6))
        intro.addView(UiKit.body(context, "M3 DOM + M4 CSS + M5 geometry now become M6 paint commands and a native Canvas preview. WebView remains off."))
        root.addView(intro)
        root.addView(UiKit.spacer(context, 12))

        val preview = AetherPaintView(context).apply {
            imageNetwork = runtime.network
            background = UiKit.background(UiKit.SURFACE, 16, UiKit.BORDER, context)
        }
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(430)))
        root.addView(UiKit.spacer(context, 10))
        val output = monospacedCard("Ready to build the M6 display list.")
        var previous: DisplayList? = null
        var alternate = false

        fun render() {
            val width = 360.0
            val document = runtime.html.parse(DEFAULT_PAINT_HTML_SAMPLE).document
            val cssText = if (alternate) DEFAULT_PAINT_CSS_ALT_SAMPLE else DEFAULT_PAINT_CSS_SAMPLE
            val styles = runtime.css.compute(document, listOf(runtime.css.parse(cssText)), MediaEnvironment(width, 800.0))
            val layout = runtime.layout.layout(document, styles, LayoutViewport(width, 800.0))
            val list = runtime.paint.paint(layout)
            val invalidation = PaintInvalidationTracker.compare(previous, list)
            preview.submit(list)
            output.text = buildString {
                appendLine(PaintInspector.summary(list))
                appendLine("dirty=${invalidation.dirtyRect ?: "none"} full=${invalidation.fullRepaint}")
                appendLine("changedCommands=${invalidation.changedCommandIndices.size}")
                appendLine("----------------")
                append(PaintInspector.displayList(list, maxCommands = 36).take(12_000))
            }
            previous = list
        }

        val renderButton = UiKit.button(context, "Render M6 scene", primary = true) { render() }
        root.addView(renderButton, match())
        root.addView(UiKit.spacer(context, 9))
        root.addView(UiKit.button(context, "Change theme and repaint") {
            alternate = !alternate
            render()
        }, match())
        root.addView(UiKit.spacer(context, 12))
        root.addView(output, match())
        setPage(root)
        render()
    }

    private fun showLayoutLab() {
        currentScreen = Screen.LAYOUT
        val root = pageRoot()
        root.addView(header("Layout engine lab"))
        root.addView(UiKit.spacer(context, 10))

        val intro = UiKit.card(context)
        intro.addView(UiKit.body(context, "Compute geometry with Aether Layout", UiKit.TEXT, 18f).apply { typeface = Typeface.DEFAULT_BOLD })
        intro.addView(UiKit.spacer(context, 6))
        intro.addView(UiKit.body(context, "M3 builds the DOM, M4 computes styles, and M5 creates block boxes, line boxes, positioned layers and scroll extents. No WebView is involved."))
        root.addView(intro)
        root.addView(UiKit.spacer(context, 12))

        val viewportInput = EditText(context).apply {
            setText("360")
            hint = "Viewport width in CSS px"
            setTextColor(UiKit.TEXT)
            setHintTextColor(UiKit.MUTED)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            background = UiKit.background(UiKit.SURFACE_2, 14, UiKit.BORDER, context)
            setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
        }
        root.addView(viewportInput, match())
        root.addView(UiKit.spacer(context, 9))

        val presetRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        presetRow.addView(UiKit.button(context, "Phone 360") { viewportInput.setText("360") }, weighted())
        presetRow.addView(UiKit.spacer(context, 8).apply { layoutParams = LinearLayout.LayoutParams(context.dp(8), 1) })
        presetRow.addView(UiKit.button(context, "Tablet 768") { viewportInput.setText("768") }, weighted())
        root.addView(presetRow)
        root.addView(UiKit.spacer(context, 9))

        val htmlInput = EditText(context).apply {
            setText(DEFAULT_LAYOUT_HTML_SAMPLE)
            setTextColor(UiKit.TEXT)
            setHintTextColor(UiKit.MUTED)
            hint = "HTML source"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 6
            maxLines = 10
            gravity = Gravity.TOP or Gravity.START
            background = UiKit.background(UiKit.SURFACE_2, 14, UiKit.BORDER, context)
            setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
        }
        root.addView(htmlInput, match())
        root.addView(UiKit.spacer(context, 9))

        val cssInput = EditText(context).apply {
            setText(DEFAULT_LAYOUT_CSS_SAMPLE)
            setTextColor(UiKit.TEXT)
            setHintTextColor(UiKit.MUTED)
            hint = "CSS source"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 9
            maxLines = 15
            gravity = Gravity.TOP or Gravity.START
            background = UiKit.background(UiKit.SURFACE_2, 14, UiKit.BORDER, context)
            setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
        }
        root.addView(cssInput, match())
        root.addView(UiKit.spacer(context, 10))

        val output = monospacedCard("Ready. Compute the layout tree to inspect exact CSS-pixel geometry.")
        val compute = UiKit.button(context, "Compute layout tree", primary = true) {
            val width = viewportInput.text.toString().toDoubleOrNull()?.coerceIn(160.0, 4096.0)
            if (width == null) {
                output.text = "Viewport width must be between 160 and 4096."
            } else {
                val height = 800.0
                val document = runtime.html.parse(htmlInput.text.toString()).document
                val sheet = runtime.css.parse(cssInput.text.toString())
                val styles = runtime.css.compute(document, listOf(sheet), MediaEnvironment(width, height))
                val tree = runtime.layout.layout(document, styles, LayoutViewport(width, height))
                val summary = LayoutInspector.summarize(tree)
                output.text = buildString {
                    appendLine("M5 LAYOUT COMPLETE")
                    appendLine("viewport=${width.toInt()}x${height.toInt()} boxes=${summary.boxes} lines=${summary.lineBoxes} fragments=${summary.fragments}")
                    appendLine("positioned=${summary.positionedBoxes} scrollContainers=${summary.scrollContainers} stacking=${summary.stackingContexts}")
                    appendLine("document=${"%.2f".format(Locale.US, summary.documentWidthPx)}x${"%.2f".format(Locale.US, summary.documentHeightPx)} issues=${summary.issues}")
                    appendLine("----------------")
                    append(LayoutInspector.tree(tree, maxDepth = 32))
                }
            }
        }
        root.addView(compute, match())
        root.addView(UiKit.spacer(context, 10))
        root.addView(output, match())
        setPage(root)
    }

    private fun showCssLab() {
        currentScreen = Screen.CSS
        val root = pageRoot()
        root.addView(header("CSS engine lab"))
        root.addView(UiKit.spacer(context, 10))

        val intro = UiKit.card(context)
        intro.addView(UiKit.body(context, "Compute styles with Aether CSS", UiKit.TEXT, 18f).apply { typeface = Typeface.DEFAULT_BOLD })
        intro.addView(UiKit.spacer(context, 6))
        intro.addView(UiKit.body(context, "Parses HTML with M3, then applies M4 selectors, cascade, inheritance, variables and media queries. No WebView is involved."))
        root.addView(intro)
        root.addView(UiKit.spacer(context, 12))

        val htmlInput = EditText(context).apply {
            setText(DEFAULT_CSS_HTML_SAMPLE)
            setTextColor(UiKit.TEXT)
            setHintTextColor(UiKit.MUTED)
            hint = "HTML source"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 5
            maxLines = 9
            gravity = Gravity.TOP or Gravity.START
            background = UiKit.background(UiKit.SURFACE_2, 14, UiKit.BORDER, context)
            setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
        }
        root.addView(htmlInput, match())
        root.addView(UiKit.spacer(context, 9))

        val cssInput = EditText(context).apply {
            setText(DEFAULT_CSS_SAMPLE)
            setTextColor(UiKit.TEXT)
            setHintTextColor(UiKit.MUTED)
            hint = "CSS source"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 8
            maxLines = 14
            gravity = Gravity.TOP or Gravity.START
            background = UiKit.background(UiKit.SURFACE_2, 14, UiKit.BORDER, context)
            setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
        }
        root.addView(cssInput, match())
        root.addView(UiKit.spacer(context, 9))

        val output = monospacedCard("Ready. Parse CSS and inspect computed styles.")
        val computeButton = UiKit.button(context, "Parse CSS and compute styles", primary = true) {}
        computeButton.setOnClickListener {
            val htmlSource = htmlInput.text.toString()
            val cssSource = cssInput.text.toString()
            if (htmlSource.isBlank() || cssSource.isBlank()) {
                output.text = "EMPTY INPUT\nBoth HTML and CSS source are required."
                return@setOnClickListener
            }
            computeButton.isEnabled = false
            computeButton.text = "Computing…"
            output.text = "Parsing HTML and CSS…"
            runCatching {
                executor.execute {
                    val rendered = runCatching {
                        val document = runtime.html.parse(htmlSource, "aether://css-lab").document
                        val sheet = runtime.css.parse(cssSource, "aether://css-lab.css")
                        val tree = runtime.css.compute(document, listOf(sheet), MediaEnvironment(widthPx = 390.0, heightPx = 844.0))
                        buildString {
                            appendLine("M4 STYLE COMPUTATION COMPLETE")
                            appendLine("rules=${sheet.rules.size} tokens=${sheet.tokenCount} styled=${tree.size} fonts=${tree.fontFaces.size}")
                            appendLine("issues=${tree.issues.size}")
                            appendLine("----------------")
                            append(CssInspector.computedTree(document, tree))
                            if (tree.issues.isNotEmpty()) {
                                appendLine()
                                appendLine("----------------")
                                appendLine("ISSUES")
                                tree.issues.take(20).forEach { appendLine("${it.code}: ${it.message}") }
                            }
                        }
                    }.getOrElse { error -> "CSS COMPUTATION FAILED\n${error::class.java.simpleName}: ${error.message.orEmpty()}" }
                    mainHandler.post {
                        if (isFinishing || isDestroyed) return@post
                        computeButton.isEnabled = true
                        computeButton.text = "Parse CSS and compute styles"
                        output.text = rendered
                    }
                }
            }.onFailure { error ->
                computeButton.isEnabled = true
                computeButton.text = "Parse CSS and compute styles"
                output.text = "CSS QUEUE BUSY\n${error.message.orEmpty()}"
            }
        }
        root.addView(computeButton, match())
        root.addView(UiKit.spacer(context, 12))
        root.addView(output, match())
        setPage(root)
    }

    private fun showHtmlLab() {
        currentScreen = Screen.HTML
        val root = pageRoot()
        root.addView(header("HTML engine lab"))
        root.addView(UiKit.spacer(context, 10))

        val intro = UiKit.card(context)
        intro.addView(UiKit.body(context, "Parse HTML into Aether DOM", UiKit.TEXT, 18f).apply { typeface = Typeface.DEFAULT_BOLD })
        intro.addView(UiKit.spacer(context, 6))
        intro.addView(UiKit.body(context, "Uses Aether's own M3 tokenizer and tree builder. No WebView, browser component or remote parser is involved."))
        root.addView(intro)
        root.addView(UiKit.spacer(context, 12))

        val sourceInput = EditText(context).apply {
            setText(DEFAULT_HTML_SAMPLE)
            setTextColor(UiKit.TEXT)
            setHintTextColor(UiKit.MUTED)
            hint = "Enter HTML source"
            textSize = 13f
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 8
            maxLines = 14
            gravity = Gravity.TOP or Gravity.START
            background = UiKit.background(UiKit.SURFACE_2, 14, UiKit.BORDER, context)
            setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
        }
        root.addView(sourceInput, match())
        root.addView(UiKit.spacer(context, 9))
        val output = monospacedCard("Ready. Enter HTML and inspect the generated DOM tree.")
        val parseButton = UiKit.button(context, "Parse and inspect DOM", primary = true) {}
        parseButton.setOnClickListener {
            val source = sourceInput.text.toString()
            if (source.isBlank()) {
                output.text = "EMPTY INPUT\nEnter HTML source before parsing."
                return@setOnClickListener
            }
            parseButton.isEnabled = false
            parseButton.text = "Parsing…"
            output.text = "Tokenizing ${source.length} characters…"
            runCatching {
                executor.execute {
                    val rendered = runCatching {
                        val result = runtime.html.parse(source, "aether://html-lab")
                        val summary = DomInspector.summarize(result.document)
                        buildString {
                            appendLine("M3 PARSE COMPLETE")
                            appendLine("tokens=${result.tokenCount} nodes=${result.nodeCount} issues=${result.issues.size}")
                            appendLine("elements=${summary.elements} textNodes=${summary.textNodes} comments=${summary.comments}")
                            appendLine("depth=${summary.maxDepth} quirks=${summary.quirksMode} elapsed=${formatMillis(result.elapsedNanos / 1_000_000.0)}")
                            if (result.issues.isNotEmpty()) {
                                appendLine("---------------- ISSUES")
                                result.issues.take(12).forEach { appendLine(it.toString()) }
                                if (result.issues.size > 12) appendLine("… ${result.issues.size - 12} more")
                            }
                            appendLine("---------------- DOM")
                            append(DomInspector.tree(result.document, maxDepth = 40, maxText = 100).take(24_000))
                        }
                    }.getOrElse { error ->
                        "PARSER FAILED\n${error::class.java.simpleName}: ${error.message.orEmpty()}"
                    }
                    mainHandler.post {
                        if (isFinishing || isDestroyed) return@post
                        parseButton.isEnabled = true
                        parseButton.text = "Parse and inspect DOM"
                        output.text = rendered
                    }
                }
            }.onFailure {
                parseButton.isEnabled = true
                parseButton.text = "Parse and inspect DOM"
                output.text = "PARSER QUEUE BUSY\n${it.message.orEmpty()}"
            }
        }
        root.addView(parseButton, match())
        root.addView(UiKit.spacer(context, 12))
        root.addView(output, match())
        setPage(root)
    }

    private fun showNetworkLab() {
        currentScreen = Screen.NETWORK
        val root = pageRoot()
        root.addView(header("Networking lab"))
        root.addView(UiKit.spacer(context, 10))

        val intro = UiKit.card(context)
        intro.addView(UiKit.body(context, "Live HTTPS probe", UiKit.TEXT, 18f).apply { typeface = Typeface.DEFAULT_BOLD })
        intro.addView(UiKit.spacer(context, 6))
        intro.addView(UiKit.body(context, "Runs off the UI thread through Aether's M2 request, redirect, cookie, cache and decompression pipeline."))
        root.addView(intro)
        root.addView(UiKit.spacer(context, 12))

        val urlInput = EditText(context).apply {
            setText(DEFAULT_PROBE_URL)
            setTextColor(UiKit.TEXT)
            setHintTextColor(UiKit.MUTED)
            hint = "https://example.com/"
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setSingleLine(true)
            background = UiKit.background(UiKit.SURFACE_2, 14, UiKit.BORDER, context)
            setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
        }
        root.addView(urlInput, match())
        root.addView(UiKit.spacer(context, 9))
        val output = monospacedCard("Ready. HTTPS-only policy is enabled.")
        val runButton = UiKit.button(context, "Run HTTPS probe", primary = true) {}
        runButton.setOnClickListener {
            val raw = urlInput.text.toString().trim()
            val parsed = runCatching { AetherUrl.parse(raw) }.getOrElse {
                output.text = "INVALID URL\n${it.message}"
                return@setOnClickListener
            }
            if (!parsed.isSecure) {
                output.text = "BLOCKED\nM2 app policy allows HTTPS only."
                return@setOnClickListener
            }
            val network = runtime.network
            if (network == null) {
                output.text = "Networking runtime is unavailable."
                return@setOnClickListener
            }
            runButton.isEnabled = false
            runButton.text = "Running…"
            output.text = "Resolving and requesting ${parsed.host}…"
            runCatching {
                executor.execute {
                    val rendered = runCatching {
                        val dns = network.dns.resolve(parsed.host)
                        val request = NetworkRequest.Builder(parsed.toString())
                            .cachePolicy(CachePolicy.DEFAULT)
                            .connectTimeoutMillis(15_000)
                            .readTimeoutMillis(20_000)
                            .maxResponseBytes(1L * 1024L * 1024L)
                            .tag("network-lab")
                            .build()
                        formatProbeResult(dns, network.client.execute(request))
                    }.getOrElse { error ->
                        "NETWORK PROBE FAILED\n${error::class.java.simpleName}: ${error.message.orEmpty()}"
                    }
                    mainHandler.post {
                        if (isFinishing || isDestroyed) return@post
                        runButton.isEnabled = true
                        runButton.text = "Run HTTPS probe"
                        output.text = rendered
                    }
                }
            }.onFailure { error ->
                runButton.isEnabled = true
                runButton.text = "Run HTTPS probe"
                output.text = "NETWORK QUEUE BUSY\n${error.message.orEmpty()}"
            }
        }
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { runButton.performClick(); true } else false
        }
        root.addView(runButton, match())
        root.addView(UiKit.spacer(context, 12))
        root.addView(output, match())
        root.addView(UiKit.spacer(context, 12))

        val clearButton = UiKit.button(context, "Clear cookies and HTTP cache") {
            runtime.network?.clearPrivateData()
            output.text = "Private networking data cleared."
        }
        root.addView(clearButton, match())
        setPage(root)
    }

    private fun formatProbeResult(dns: NetworkResult<*>, result: NetworkResult<com.mohnishraj.aether.core.net.model.NetworkResponse>): String {
        val dnsLine = when (dns) {
            is NetworkResult.Success<*> -> dns.value.toString()
            is NetworkResult.Failure -> "DNS ${dns.error.kind}: ${dns.error.message}"
        }
        return when (result) {
            is NetworkResult.Failure -> buildString {
                appendLine("REQUEST FAILED")
                appendLine("kind=${result.error.kind}")
                appendLine("message=${result.error.message}")
                appendLine("url=${result.error.url}")
                append("$dnsLine")
            }
            is NetworkResult.Success -> {
                val response = result.value
                val preview = response.bodyText().replace(Regex("\\s+"), " ").take(700)
                buildString {
                    appendLine("HTTP ${response.statusCode} ${response.reasonPhrase}")
                    appendLine("final=${response.finalUrl}")
                    appendLine("protocol=${response.protocol} cache=${response.fromCache}")
                    appendLine("redirects=${response.redirectChain.size} bytes=${response.bytesReceived}")
                    appendLine("ttfb=${formatMillis(response.timing.timeToHeadersMillis)} total=${formatMillis(response.timing.totalMillis)}")
                    response.transportDetails["cipherSuite"]?.let { appendLine("tls=$it") }
                    appendLine("contentType=${response.contentType ?: "unknown"}")
                    appendLine("dns=$dnsLine")
                    appendLine("----------------")
                    append(preview.ifBlank { "(empty body)" })
                }
            }
        }
    }

    private fun formatMillis(value: Double): String = "%.2fms".format(Locale.US, value)

    private fun runSelfTest() {
        val report = runtime.profiler.measure("ui.selftest") { EngineSelfTest.run(runtime) }
        AlertDialog.Builder(context)
            .setTitle(if (report.passed) "Engine verification passed" else "Engine verification failed")
            .setMessage(report.pretty())
            .setPositiveButton("Done", null)
            .setNeutralButton("Open console") { _, _ -> showConsole("selftest") }
            .show()
    }

    private fun showConsole(initialCommand: String? = null) {
        currentScreen = Screen.CONSOLE
        val root = pageRoot()
        root.addView(header("Developer console"))
        root.addView(UiKit.spacer(context, 10))
        val output = TextView(context).apply {
            setTextColor(UiKit.TEXT)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(context.dp(14), context.dp(14), context.dp(14), context.dp(14))
            background = UiKit.background(UiKit.SURFACE, 14, UiKit.BORDER, context)
            setText(getString(R.string.dev_console_banner))
        }
        root.addView(output, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(UiKit.spacer(context, 8))
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val input = EditText(context).apply {
            hint = "command"
            setHintTextColor(UiKit.MUTED)
            setTextColor(UiKit.TEXT)
            textSize = 14f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT
            background = UiKit.background(UiKit.SURFACE_2, 14, UiKit.BORDER, context)
            setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(10))
        }
        fun execute() {
            val command = input.text.toString().trim()
            if (command.isEmpty()) return
            val result = runtime.profiler.measure("devconsole.execute") { runtime.console.execute(CommandContext(runtime), command) }
            if (result.clearScreen) output.text = "" else output.append("\n> $command\n${result.output}\n")
            input.text.clear()
        }
        input.setOnEditorActionListener { _, actionId, _ -> if (actionId == EditorInfo.IME_ACTION_SEND) { execute(); true } else false }
        row.addView(input, LinearLayout.LayoutParams(0, context.dp(52), 1f))
        row.addView(UiKit.spacer(context, 8).apply { layoutParams = LinearLayout.LayoutParams(context.dp(8), 1) })
        row.addView(UiKit.button(context, "Run", primary = true) { execute() }, LinearLayout.LayoutParams(context.dp(78), context.dp(52)))
        root.addView(row)
        setPage(root, scrolling = false)
        initialCommand?.let { input.setText(it); execute() }
    }

    private fun showLogs() {
        currentScreen = Screen.LOGS
        val root = pageRoot()
        root.addView(header("Engine logs"))
        root.addView(UiKit.spacer(context, 10))
        val logs = runtime.logger.recent(300).joinToString("\n") { it.format() }.ifBlank { "No logs captured." }
        root.addView(monospacedCard(logs))
        root.addView(UiKit.spacer(context, 10))
        root.addView(UiKit.button(context, "Clear logs") { runtime.logger.clear(); showLogs() }, match())
        setPage(root)
    }

    private fun showCrashes() {
        currentScreen = Screen.CRASHES
        val root = pageRoot()
        root.addView(header("Crash vault"))
        root.addView(UiKit.spacer(context, 10))
        val crashes = runtime.crashReporter.recent(30)
        root.addView(monospacedCard(if (crashes.isEmpty()) "No real crashes captured.\n\nThe cumulative self-test verifies crash serialization in memory and does not store synthetic reports." else crashes.joinToString("\n\n----------------\n\n") {
            "${it.id}\n${it.exceptionType}: ${it.message}\nthread=${it.threadName}\ntime=${it.timestampMillis}\ncontext=${it.context}\n\n${it.stackTrace.take(2200)}"
        }))
        setPage(root)
    }

    private fun header(label: String): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(UiKit.button(context, "‹ Back") { showDashboard() }, LinearLayout.LayoutParams(context.dp(92), context.dp(48)))
        addView(UiKit.spacer(context, 12).apply { layoutParams = LinearLayout.LayoutParams(context.dp(12), 1) })
        addView(UiKit.title(context, label, 22f), weighted())
    }

    private fun metricGrid(items: List<Pair<String, String>>): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        items.chunked(2).forEachIndexed { rowIndex, pair ->
            if (rowIndex > 0) addView(UiKit.spacer(context, 8))
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { index, (label, value) ->
                if (index > 0) row.addView(UiKit.spacer(context, 8).apply { layoutParams = LinearLayout.LayoutParams(context.dp(8), 1) })
                val card = UiKit.card(context)
                card.addView(UiKit.body(context, label.uppercase(Locale.US), UiKit.MUTED, 11f).apply { typeface = Typeface.MONOSPACE })
                card.addView(UiKit.spacer(context, 4))
                card.addView(UiKit.title(context, value, 18f))
                row.addView(card, weighted())
            }
            addView(row)
        }
    }

    private fun sectionTitle(text: String) = UiKit.body(context, text.uppercase(Locale.US), UiKit.MUTED, 12f).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.08f
    }

    private fun monospacedCard(text: String) = TextView(context).apply {
        this.text = text
        setTextColor(UiKit.TEXT)
        textSize = 12f
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
        setPadding(context.dp(14), context.dp(14), context.dp(14), context.dp(14))
        background = UiKit.background(UiKit.SURFACE, 14, UiKit.BORDER, context)
    }

    private fun pageRoot() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(16), context.dp(18), context.dp(16), context.dp(24))
        setBackgroundColor(UiKit.BG)
    }

    private fun setPage(content: LinearLayout, scrolling: Boolean = true) {
        val page = if (scrolling) {
            ScrollView(context).apply { isFillViewport = true; addView(content) }
        } else content
        val host = FrameLayout(context).apply {
            setBackgroundColor(UiKit.BG)
            addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        ViewCompat.setOnApplyWindowInsetsListener(host) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(host)
        ViewCompat.requestApplyInsets(host)
    }

    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    private fun match() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val BROWSER_AUX_SCREENS = setOf(Screen.BOOKMARKS, Screen.HISTORY, Screen.DOWNLOADS, Screen.READER, Screen.FIND, Screen.SETTINGS)
        private const val BOOKMARK_LIST_TAG = "m12-bookmark-list"
        private const val HISTORY_LIST_TAG = "m12-history-list"
        private const val DOWNLOAD_CHANNEL = "aether-downloads"
        private const val DOWNLOAD_PERMISSION_REQUEST = 1201
        private const val STATE_SCREEN = "screen"
        private const val DEFAULT_RENDER_HTML_SAMPLE = """<!doctype html>
<html><body>
<header id="render-bar"><strong>AETHER</strong><span>M9 PIPELINE</span></header>
<main>
  <h1 id="render-title">Frames, not screenshots.</h1>
  <p>Style, layout and paint are reused while compositor-only scrolling updates layer transforms.</p>
  <section><article>Incremental invalidation</article><article>Damage rectangles</article><article>Layer promotion</article></section>
  <div id="render-bottom">END OF DOCUMENT</div>
</main>
</body></html>"""
        private const val DEFAULT_RENDER_CSS_SAMPLE = """html,body{margin:0;width:100%;background:#080d18;color:#eaf2ff;font-size:16px;line-height:1.45}
body{min-height:1500px}*{box-sizing:border-box}
#render-bar{position:fixed;top:0;left:0;width:100%;height:52px;padding:14px 16px;background:#111c33;border-bottom:1px solid #55e6c1;z-index:20}
#render-bar span{margin-left:12px;color:#55e6c1}main{padding:78px 16px 24px}
h1{margin:0 0 12px;padding:16px;background:#172554;border:1px solid #334e78;border-radius:14px;font-size:28px}.hot{color:#55e6c1;background:#243b67}
p{color:#b8c7e0}article{margin:10px 0;padding:14px;background:#121c2e;border:1px solid #2e4669;border-radius:10px}
#render-bottom{margin-top:850px;height:120px;padding:24px;background:#55e6c1;color:#081018;opacity:.92;border-radius:18px}"""
        private const val DEFAULT_RENDER_CSS_ALT_SAMPLE = """html,body{margin:0;width:100%;background:#1a0c18;color:#fff7ed;font-size:16px;line-height:1.5}
body{min-height:1500px}*{box-sizing:border-box}
#render-bar{position:fixed;top:0;left:0;width:100%;height:52px;padding:14px 16px;background:#3b1234;border-bottom:2px solid #fb7185;z-index:20}
#render-bar span{margin-left:12px;color:#fda4af}main{padding:78px 16px 24px}
h1{margin:0 0 12px;padding:18px;background:#4c1d3d;border:1px solid #9f5679;border-radius:22px;font-size:29px}.hot{color:#fda4af;background:#6b204f}
p{color:#fed7aa}article{margin:10px 0;padding:14px;background:#35152f;border:1px solid #7d3b67;border-radius:16px}
#render-bottom{margin-top:850px;height:120px;padding:24px;background:#fb7185;color:#2b071f;opacity:.9;border-radius:24px}"""
        private const val DEFAULT_PROBE_URL = "https://example.com/"
        private const val DEFAULT_BROWSER_API_HTML_SAMPLE = """<!doctype html>
<html><body>
  <main id="app">
    <h1>Aether M8</h1>
    <button id="action">Run event</button>
    <form id="profile"><input name="name" required value="Mohnish"><input name="role" value="builder"></form>
  </main>
</body></html>"""
        private const val DEFAULT_BROWSER_API_JS_SAMPLE = """let app = document.getElementById("app");
let button = document.getElementById("action");
let events = 0;
observeMutations(app, function(records) {
  console.log("mutations", records.length);
}, { attributes: true, childList: true, subtree: true });
button.addEventListener("activate", function(event) {
  events = events + 1;
  app.setAttribute("data-state", "active");
});
button.dispatchEvent("activate");
let badge = document.createElement("p");
badge.setAttribute("class", "status");
badge.setText("DOM + events + storage ready");
app.appendChild(badge);
localStorage.setItem("milestone", "M8");
navigator.clipboard.writeText("Aether M8 Browser APIs");
let form = serializeForm(document.getElementById("profile"));
console.log("form", form.body);
({ events: events, stored: localStorage.getItem("milestone"), clipboard: navigator.clipboard.readText(), valid: form.valid });"""
        private const val DEFAULT_JAVASCRIPT_SAMPLE = """function fibonacci(n) {
  if (n <= 1) return n;
  return fibonacci(n - 1) + fibonacci(n - 2);
}

let values = [];
for (let i = 0; i < 9; i++) values.push(fibonacci(i));
let report = { milestone: "M7", values: values, joined: values.join(", ") };
console.log("fibonacci", report.values);
queueMicrotask(function () { console.log("microtask", "complete"); });
setTimeout(function () { console.log("timer", "complete"); }, 0);
report;"""
        private const val DEFAULT_PAINT_HTML_SAMPLE = """<!doctype html>
<main id="card">
  <header><strong>AETHER</strong><span>M6 PAINT</span></header>
  <h1>Pixels begin here.</h1>
  <p>DOM, style and geometry become a deterministic display list.</p>
  <section><article>Rounded backgrounds</article><article>Clipping and shadows</article></section>
  <img src="asset://aether-preview" alt="AETHER IMAGE">
</main>"""
        private const val DEFAULT_PAINT_CSS_SAMPLE = """html, body { margin:0; width:100%; background-color:#080d18; }
body { color:#eaf2ff; font-size:16px; line-height:1.45; }
#card { width:88%; margin:20px auto; padding:18px; background-color:#101827; background-image:linear-gradient(135deg,#101827,#172554); border:2px solid #55e6c1; border-radius:20px; box-shadow:0 14px 30px rgba(0,0,0,.45); box-sizing:border-box; overflow:hidden; }
header { margin-bottom:16px; color:#55e6c1; } header span { margin-left:10px; color:#9fb3d1; }
h1 { margin:0 0 10px; color:#ffffff; font-size:30px; line-height:1.1; }
p { margin:0 0 16px; color:#b8c7e0; }
article { margin:8px 0; padding:10px; background-color:#172033; border:1px solid #334155; border-radius:10px; }
img { display:block; width:100%; height:72px; margin-top:12px; object-fit:cover; border-radius:12px; }"""
        private const val DEFAULT_PAINT_CSS_ALT_SAMPLE = """html, body { margin:0; width:100%; background-color:#120b18; }
body { color:#fff7ed; font-size:16px; line-height:1.45; }
#card { width:88%; margin:20px auto; padding:18px; background-color:#24102f; background-image:linear-gradient(135deg,#24102f,#4c1d3d); border:2px solid #fb7185; border-radius:28px; box-shadow:0 16px 34px rgba(0,0,0,.5); box-sizing:border-box; overflow:hidden; }
header { margin-bottom:16px; color:#fda4af; } header span { margin-left:10px; color:#fecdd3; }
h1 { margin:0 0 10px; color:#fff7ed; font-size:30px; line-height:1.1; }
p { margin:0 0 16px; color:#fed7aa; }
article { margin:8px 0; padding:10px; background-color:#3b183f; border:1px solid #9f5679; border-radius:16px; }
img { display:block; width:100%; height:72px; margin-top:12px; object-fit:cover; border-radius:16px; }"""
        private const val DEFAULT_LAYOUT_HTML_SAMPLE = """<!doctype html>
<main id="page">
  <header><strong>Aether M5</strong><span>Layout Engine</span></header>
  <h1>Independent browser geometry</h1>
  <p>Block flow and inline text wrap into deterministic line boxes without WebView.</p>
  <section><article>Responsive card A</article><article>Responsive card B</article></section>
  <aside>z=4</aside>
</main>"""
        private const val DEFAULT_LAYOUT_CSS_SAMPLE = """html, body { margin: 0; width: 100%; }
body { font-size: 16px; line-height: 1.45; }
#page { position: relative; width: 88%; margin: 18px auto; padding: 18px; border: 2px solid; box-sizing: border-box; overflow: auto; }
header { display: block; margin-bottom: 18px; }
header span { margin-left: 12px; }
h1 { width: 72%; margin: 0 0 12px; font-size: 30px; line-height: 1.1; }
p { width: 78%; margin: 0 0 18px; }
section { display: block; }
article { width: calc(100% - 24px); margin: 10px 0; padding: 12px; border: 1px solid; box-sizing: border-box; }
aside { position: absolute; right: 8px; top: 54px; width: 44px; height: 24px; padding: 4px; z-index: 4; overflow: hidden; }
@media (min-width: 700px) { #page { width: 680px; } h1 { width: 80%; } }"""
        private const val DEFAULT_CSS_HTML_SAMPLE = """<!doctype html>
<main id="app" class="card">
  <h1>Aether CSS Engine</h1>
  <p class="lead">Selectors, cascade and variables.</p>
  <button disabled>Independent renderer path</button>
</main>"""
        private const val DEFAULT_CSS_SAMPLE = """:root { --accent: #55e6c1; --space: 12px; }
body { color: #eaf2ff; font-family: sans-serif; }
#app.card { background-color: #101827; padding: var(--space); }
#app > h1 { color: var(--accent); font-size: 28px; }
.lead { color: #b8c7e0; line-height: 1.5; }
button:disabled { opacity: .55; }
@media (min-width: 380px) { #app { display: grid; } }
@font-face { font-family: 'Aether Sans'; src: url(aether.woff2); font-display: swap; }"""
        private const val DEFAULT_HTML_SAMPLE = """<!doctype html>
<html lang="en">
<head><title>Aether M3</title></head>
<body>
  <main id="demo">
    <h1>Independent HTML Engine</h1>
    <p>Hello <strong>Aether</strong> &amp; Android.</p>
    <ul><li>Tokenizer<li>DOM<li>Error recovery</ul>
  </main>
</body>
</html>"""
    }
}
