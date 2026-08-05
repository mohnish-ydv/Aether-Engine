package com.mohnishraj.aether.platform.android

import android.content.Context
import com.mohnishraj.aether.core.BuildInfo
import com.mohnishraj.aether.core.browser.BrowserApiRuntime
import com.mohnishraj.aether.core.EngineRuntime
import com.mohnishraj.aether.core.IncognitoRuntime
import com.mohnishraj.aether.core.devtools.BuiltinCommands
import com.mohnishraj.aether.core.devtools.DevConsole
import com.mohnishraj.aether.core.fs.MemoryFileSystem
import com.mohnishraj.aether.core.fs.VirtualPath
import com.mohnishraj.aether.core.log.EngineLogger
import com.mohnishraj.aether.core.net.NetworkClient
import com.mohnishraj.aether.core.net.NetworkRuntime
import com.mohnishraj.aether.core.net.NetworkStats
import com.mohnishraj.aether.core.net.cache.FileSystemHttpCache
import com.mohnishraj.aether.core.net.cache.MemoryHttpCache
import com.mohnishraj.aether.core.net.cookie.InMemoryCookieJar
import com.mohnishraj.aether.core.net.cookie.PersistentCookieJar
import com.mohnishraj.aether.core.net.dns.CachingDnsResolver
import com.mohnishraj.aether.core.net.download.DownloadManager
import com.mohnishraj.aether.core.profile.PerformanceProfiler
import com.mohnishraj.aether.core.security.SecurityEngine
import com.mohnishraj.aether.core.shell.BrowserShellRuntime
import com.mohnishraj.aether.platform.android.net.AndroidDnsResolver
import com.mohnishraj.aether.platform.android.net.AndroidNetworkTransport

object AndroidEngineBootstrap {
    fun create(context: Context): EngineRuntime {
        val logger = EngineLogger(capacity = 1000).also { it.addSink(AndroidLogSink()) }
        val fileSystem = AndroidFileSystem(context.filesDir.resolve("aether"))
        val crashReporter = AndroidCrashReporter(context).also { it.installAsDefaultHandler() }
        val profiler = PerformanceProfiler()
        val console = DevConsole().also(BuiltinCommands::install)

        val cookies = PersistentCookieJar(fileSystem)
        val cache = FileSystemHttpCache(fileSystem)
        val client = NetworkClient(
            transport = AndroidNetworkTransport(),
            cache = cache,
            cookieJar = cookies,
            stats = NetworkStats(),
            logger = logger,
            userAgent = "AetherEngine/${BuildInfo.ENGINE_VERSION} (Android)"
        )
        val network = NetworkRuntime(
            client = client,
            downloads = DownloadManager(client, fileSystem),
            dns = CachingDnsResolver(AndroidDnsResolver()),
            cookies = cookies,
            cache = cache
        )

        val html = com.mohnishraj.aether.core.html.HtmlEngine(logger, profiler)
        val js = com.mohnishraj.aether.core.js.JsEngine(logger, profiler)
        val security = SecurityEngine(logger, profiler)
        val browser = BrowserApiRuntime(
            fileSystem = fileSystem,
            network = network,
            html = html,
            js = js,
            logger = logger,
            profiler = profiler,
            clipboard = AndroidClipboardPort(context),
            security = security
        )
        val incognitoFileSystem = MemoryFileSystem()
        val incognitoCookies = InMemoryCookieJar()
        val incognitoCache = MemoryHttpCache()
        val incognitoClient = NetworkClient(
            transport = AndroidNetworkTransport(),
            cache = incognitoCache,
            cookieJar = incognitoCookies,
            stats = NetworkStats(),
            logger = logger,
            userAgent = "AetherEngine/${BuildInfo.ENGINE_VERSION} (Android; Incognito)"
        )
        val incognitoNetwork = NetworkRuntime(
            client = incognitoClient,
            downloads = DownloadManager(incognitoClient, incognitoFileSystem),
            dns = CachingDnsResolver(AndroidDnsResolver()),
            cookies = incognitoCookies,
            cache = incognitoCache
        )
        val incognitoHtml = com.mohnishraj.aether.core.html.HtmlEngine(logger, profiler)
        val incognitoJs = com.mohnishraj.aether.core.js.JsEngine(logger, profiler)
        val incognitoSecurity = SecurityEngine(logger, profiler)
        val incognitoBrowser = BrowserApiRuntime(
            fileSystem = incognitoFileSystem,
            network = incognitoNetwork,
            html = incognitoHtml,
            js = incognitoJs,
            logger = logger,
            profiler = profiler,
            clipboard = AndroidClipboardPort(context),
            security = incognitoSecurity
        )
        val incognitoRender = com.mohnishraj.aether.core.render.RenderPipeline(
            com.mohnishraj.aether.core.css.CssEngine(logger, profiler),
            com.mohnishraj.aether.core.layout.LayoutEngine(logger, profiler),
            com.mohnishraj.aether.core.paint.PaintEngine(logger, profiler),
            logger,
            profiler
        )
        val incognitoShell = BrowserShellRuntime(
            browser = incognitoBrowser,
            render = incognitoRender,
            network = incognitoNetwork,
            fileSystem = incognitoFileSystem,
            logger = logger,
            profiler = profiler,
            security = incognitoSecurity,
            browsingHistory = null,
            sessionPersistenceEnabled = false
        )
        val runtime = EngineRuntime(
            fileSystem = fileSystem,
            logger = logger,
            crashReporter = crashReporter,
            profiler = profiler,
            console = console,
            network = network,
            html = html,
            js = js,
            security = security,
            browser = browser,
            incognito = IncognitoRuntime(incognitoFileSystem, incognitoNetwork, incognitoBrowser, incognitoShell)
        )
        runtime.start()
        val system = AndroidSystemProbe.snapshot()
        logger.info("Platform", "Android ${system.release} (SDK ${system.sdk}), ${system.processors} cores")
        logger.info("Network", "M2 transport, private cache, cookies and DNS ready")
        logger.info("HTML", "M3 tokenizer, tree builder, DOM and inspector ready")
        logger.info("CSS", "M4 parser, selectors, cascade and computed styles ready")
        logger.info("Layout", "M5 block, inline, positioned and overflow layout ready")
        logger.info("Paint", "M6 display-list builder, clipping and repaint tracking ready")
        logger.info("JavaScript", "M7 lexer, parser, interpreter and deterministic task queue ready")
        logger.info("BrowserAPI", "M8 DOM APIs, events, storage, forms, clipboard, mutations and fetch bridge ready")
        logger.info("Render", "M9 frame scheduling, invalidation, smooth scrolling and compositing ready")
        logger.info("BrowserShell", "M10 tabs, navigation history, address resolution and session restore ready")
        logger.info("Security", "M11 origins, CSP, mixed content, CORS, sandbox and permissions ready")
        logger.info("BrowserFeatures", "M12 bookmarks, history, downloads, reader, find and isolated incognito ready")
        runCatching {
            fileSystem.write(VirtualPath.of("/runtime/boot.txt"), "Aether Engine M12 booted on ${system.device}".toByteArray())
        }.onFailure { error ->
            logger.warn("Platform", "Boot marker write failed: ${error.message.orEmpty()}")
        }
        return runtime
    }
}
