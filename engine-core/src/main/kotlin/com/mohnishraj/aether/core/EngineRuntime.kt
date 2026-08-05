package com.mohnishraj.aether.core

import com.mohnishraj.aether.core.browser.BrowserApiRuntime
import com.mohnishraj.aether.core.browser.features.BrowserFeatures
import com.mohnishraj.aether.core.crash.CrashReporter
import com.mohnishraj.aether.core.css.CssEngine
import com.mohnishraj.aether.core.devtools.DevConsole
import com.mohnishraj.aether.core.fs.FileSystem
import com.mohnishraj.aether.core.html.HtmlEngine
import com.mohnishraj.aether.core.layout.LayoutEngine
import com.mohnishraj.aether.core.js.JsEngine
import com.mohnishraj.aether.core.log.EngineLogger
import com.mohnishraj.aether.core.net.NetworkRuntime
import com.mohnishraj.aether.core.paint.PaintEngine
import com.mohnishraj.aether.core.profile.PerformanceProfiler
import com.mohnishraj.aether.core.render.RenderPipeline
import com.mohnishraj.aether.core.shell.BrowserShellRuntime
import com.mohnishraj.aether.core.security.SecurityEngine
import java.util.concurrent.atomic.AtomicReference

enum class EngineState { CREATED, STARTING, RUNNING, STOPPING, STOPPED, FAILED }

data class IncognitoRuntime(
    val fileSystem: FileSystem,
    val network: NetworkRuntime,
    val browser: BrowserApiRuntime,
    val shell: BrowserShellRuntime
) {
    fun wipe() {
        shell.stopLoading()
        shell.wipeSession()
        browser.clearAllStorage()
        network.clearPrivateData()
        fileSystem.list().forEach { fileSystem.delete(it.path) }
    }
}

class EngineRuntime(
    val fileSystem: FileSystem,
    val logger: EngineLogger,
    val crashReporter: CrashReporter,
    val profiler: PerformanceProfiler,
    val console: DevConsole,
    val network: NetworkRuntime? = null,
    val html: HtmlEngine = HtmlEngine(logger, profiler),
    val css: CssEngine = CssEngine(logger, profiler),
    val layout: LayoutEngine = LayoutEngine(logger, profiler),
    val paint: PaintEngine = PaintEngine(logger, profiler),
    val render: RenderPipeline = RenderPipeline(css, layout, paint, logger, profiler),
    val js: JsEngine = JsEngine(logger, profiler),
    val security: SecurityEngine = SecurityEngine(logger, profiler),
    val browser: BrowserApiRuntime = BrowserApiRuntime(fileSystem, network, html, js, logger, profiler, security = security),
    val features: BrowserFeatures = BrowserFeatures(fileSystem),
    val shell: BrowserShellRuntime = BrowserShellRuntime(browser, render, network, fileSystem, logger, profiler, security = security, browsingHistory = features.history),
    val incognito: IncognitoRuntime? = null
) {
    private val stateRef = AtomicReference(EngineState.CREATED)
    val state: EngineState get() = stateRef.get()

    fun start() {
        if (!stateRef.compareAndSet(EngineState.CREATED, EngineState.STARTING) &&
            !stateRef.compareAndSet(EngineState.STOPPED, EngineState.STARTING)
        ) return
        runCatching {
            logger.info("Kernel", "Starting ${BuildInfo.ENGINE_NAME} ${BuildInfo.ENGINE_VERSION}")
            profiler.increment("kernel.starts")
            stateRef.set(EngineState.RUNNING)
            logger.info("Kernel", "Engine state=RUNNING")
        }.onFailure {
            stateRef.set(EngineState.FAILED)
            crashReporter.capture(it, mapOf("phase" to "runtime.start"))
            logger.error("Kernel", "Engine failed to start", it)
            throw it
        }
    }

    fun stop() {
        if (!stateRef.compareAndSet(EngineState.RUNNING, EngineState.STOPPING)) return
        logger.info("Kernel", "Stopping engine")
        stateRef.set(EngineState.STOPPED)
    }
}
