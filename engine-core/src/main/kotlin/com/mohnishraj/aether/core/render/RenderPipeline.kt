package com.mohnishraj.aether.core.render

import com.mohnishraj.aether.core.browser.BrowserPage
import com.mohnishraj.aether.core.browser.mutation.MutationObserverOptions
import com.mohnishraj.aether.core.css.CssEngine
import com.mohnishraj.aether.core.css.CssOrigin
import com.mohnishraj.aether.core.css.MediaEnvironment
import com.mohnishraj.aether.core.css.parser.CssStyleSheet
import com.mohnishraj.aether.core.layout.LayoutEngine
import com.mohnishraj.aether.core.layout.LayoutViewport
import com.mohnishraj.aether.core.log.EngineLogger
import com.mohnishraj.aether.core.paint.DisplayList
import com.mohnishraj.aether.core.paint.PaintCommand
import com.mohnishraj.aether.core.paint.PaintEngine
import com.mohnishraj.aether.core.paint.PaintIssue
import com.mohnishraj.aether.core.paint.PaintInvalidation
import com.mohnishraj.aether.core.paint.PaintInvalidationTracker
import com.mohnishraj.aether.core.profile.PerformanceProfiler
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class RenderPipeline(
    private val css: CssEngine,
    private val layout: LayoutEngine,
    private val paint: PaintEngine,
    private val logger: EngineLogger? = null,
    private val profiler: PerformanceProfiler? = null,
    val limits: RenderLimits = RenderLimits()
) {
    private val sessionsCreated = AtomicLong()
    private val framesProduced = AtomicLong()
    private val stylePasses = AtomicLong()
    private val layoutPasses = AtomicLong()
    private val paintPasses = AtomicLong()
    private val compositePasses = AtomicLong()
    private val withinBudget = AtomicLong()
    private val droppedRequests = AtomicLong()
    private val lastFrameNanos = AtomicLong()
    private val currentSessionRef = AtomicReference<RenderSession?>()

    val currentSession: RenderSession? get() = currentSessionRef.get()

    fun open(
        page: BrowserPage,
        styleSheetSource: String,
        viewport: RenderViewport = RenderViewport(),
        sourceUrl: String? = page.url,
        userAgentStyleSheetSource: String? = null
    ): RenderSession {
        currentSessionRef.getAndSet(null)?.close()
        val session = RenderSession(
            page = page,
            initialStyleSheetSource = styleSheetSource,
            initialStyleSheetUrl = sourceUrl,
            initialUserAgentStyleSheetSource = userAgentStyleSheetSource,
            initialViewport = viewport,
            css = css,
            layout = layout,
            paint = paint,
            logger = logger,
            profiler = profiler,
            limits = limits,
            onFrame = ::recordFrame
        )
        currentSessionRef.set(session)
        sessionsCreated.incrementAndGet()
        profiler?.increment("render.sessions")
        logger?.info("Render", "Opened rendering session ${page.url} ${viewport.widthPx}x${viewport.heightPx}")
        return session
    }

    fun closeCurrentSession() {
        currentSessionRef.getAndSet(null)?.close()
    }

    fun statistics(): RenderPipelineStatistics = RenderPipelineStatistics(
        sessionsCreated = sessionsCreated.get(),
        framesProduced = framesProduced.get(),
        stylePasses = stylePasses.get(),
        layoutPasses = layoutPasses.get(),
        paintPasses = paintPasses.get(),
        compositePasses = compositePasses.get(),
        framesWithinBudget = withinBudget.get(),
        droppedFrameRequests = droppedRequests.get(),
        lastFrameMillis = lastFrameNanos.get() / 1_000_000.0
    )

    private fun recordFrame(frame: RenderFrame, schedulerDrops: Long) {
        framesProduced.incrementAndGet()
        if (!frame.reuse.styleTree) stylePasses.incrementAndGet()
        if (!frame.reuse.layoutTree) layoutPasses.incrementAndGet()
        if (!frame.reuse.displayList) paintPasses.incrementAndGet()
        compositePasses.incrementAndGet()
        if (frame.timings.totalNanos <= 1_000_000_000L / limits.targetFramesPerSecond) withinBudget.incrementAndGet()
        droppedRequests.set(schedulerDrops)
        lastFrameNanos.set(frame.timings.totalNanos)
        profiler?.record("render.frame", frame.timings.totalNanos)
        profiler?.increment("render.frames")
    }
}

class RenderSession internal constructor(
    val page: BrowserPage,
    initialStyleSheetSource: String,
    initialStyleSheetUrl: String?,
    initialUserAgentStyleSheetSource: String?,
    initialViewport: RenderViewport,
    private val css: CssEngine,
    private val layout: LayoutEngine,
    private val paint: PaintEngine,
    private val logger: EngineLogger?,
    private val profiler: PerformanceProfiler?,
    private val limits: RenderLimits,
    private val onFrame: (RenderFrame, Long) -> Unit
) {
    private val invalidation = RenderInvalidationTracker(limits)
    private val scheduler = FrameScheduler(limits.targetFramesPerSecond)
    private val scroller = ScrollController(limits.smoothScrollDurationMillis)
    private val compositor = LayerCompositor(limits)
    private var styleSheetSource = initialStyleSheetSource
    private var styleSheetUrl = initialStyleSheetUrl
    private var styleSheet: CssStyleSheet = css.parse(initialStyleSheetSource, initialStyleSheetUrl, CssOrigin.AUTHOR)
    private val userAgentStyleSheet: CssStyleSheet? = initialUserAgentStyleSheetSource?.let {
        css.parse(it, "aether://user-agent.css", CssOrigin.USER_AGENT)
    }
    private var viewport = initialViewport
    private var frame: RenderFrame? = null
    private var generation = 0L
    private var closed = false
    private var lastTaskPumpNanos = System.nanoTime().coerceAtLeast(0L)
    private val observer = page.document.observe(
        page.document.document,
        MutationObserverOptions(childList = true, attributes = true, characterData = true, subtree = true)
    ) { records ->
        invalidation.recordMutations(records)
        scheduler.request(InvalidationCause.DOM_STRUCTURE, System.nanoTime().coerceAtLeast(0L))
    }

    init {
        page.updateViewport(viewport.widthPx, viewport.heightPx, viewport.deviceScaleFactor)
        scheduler.request(InvalidationCause.INITIAL_LOAD, 0L)
    }

    val currentFrame: RenderFrame? get() = frame
    fun currentViewport(): RenderViewport = viewport
    fun currentScroll(): ScrollOffset = scroller.current()
    fun hasPendingFrame(): Boolean = scheduler.hasPendingFrame() || scroller.isAnimating() || page.hasPendingTasks()
    fun nextScriptTaskDelayMillis(): Long? = page.nextTaskDelayMillis()

    fun requestFrame(cause: InvalidationCause, nowNanos: Long = System.nanoTime().coerceAtLeast(0L)): Long {
        checkOpen()
        invalidation.invalidate(cause)
        return scheduler.request(cause, nowNanos)
    }

    fun updateStyleSheet(source: String, sourceUrl: String? = styleSheetUrl, nowNanos: Long = System.nanoTime().coerceAtLeast(0L)) {
        checkOpen()
        styleSheet = css.parse(source, sourceUrl)
        styleSheetSource = source
        styleSheetUrl = sourceUrl
        invalidation.invalidate(InvalidationCause.STYLESHEET, forceFull = true)
        scheduler.request(InvalidationCause.STYLESHEET, nowNanos)
    }

    fun resize(newViewport: RenderViewport, nowNanos: Long = System.nanoTime().coerceAtLeast(0L)) {
        checkOpen()
        if (newViewport == viewport) return
        viewport = newViewport
        page.updateViewport(newViewport.widthPx, newViewport.heightPx, newViewport.deviceScaleFactor)
        invalidation.invalidate(InvalidationCause.VIEWPORT, forceFull = true)
        scheduler.request(InvalidationCause.VIEWPORT, nowNanos)
    }

    fun scrollTo(
        x: Double,
        y: Double,
        behavior: ScrollBehavior = ScrollBehavior.INSTANT,
        nowNanos: Long = System.nanoTime().coerceAtLeast(0L)
    ): ScrollOffset {
        checkOpen()
        val update = scroller.scrollTo(x, y, behavior, nowNanos)
        if (update.changed || update.animating) {
            invalidation.invalidate(InvalidationCause.SCROLL)
            scheduler.request(InvalidationCause.SCROLL, nowNanos)
        }
        return update.position
    }

    fun scrollBy(
        dx: Double,
        dy: Double,
        behavior: ScrollBehavior = ScrollBehavior.INSTANT,
        nowNanos: Long = System.nanoTime().coerceAtLeast(0L)
    ): ScrollOffset {
        checkOpen()
        val update = scroller.scrollBy(dx, dy, behavior, nowNanos)
        if (update.changed || update.animating) {
            invalidation.invalidate(InvalidationCause.SCROLL)
            scheduler.request(InvalidationCause.SCROLL, nowNanos)
        }
        return update.position
    }

    fun renderIfDue(nowNanos: Long): RenderFrame? {
        checkOpen()
        prepare(nowNanos)
        scheduler.consumeIfDue(nowNanos) ?: return null
        return produceFrame()
    }

    fun renderNow(nowNanos: Long = System.nanoTime().coerceAtLeast(0L)): RenderFrame {
        checkOpen()
        prepare(nowNanos)
        scheduler.consumeNow(nowNanos)
        if (invalidation.peek().isClean && frame != null) return frame!!
        return produceFrame()
    }

    fun close() {
        if (closed) return
        closed = true
        scheduler.cancel()
        scroller.cancel()
        page.document.disconnect(observer)
        logger?.debug("Render", "Closed rendering session ${page.url}")
    }

    private fun prepare(nowNanos: Long) {
        val elapsedMillis = ((nowNanos - lastTaskPumpNanos).coerceAtLeast(0L) / 1_000_000L).coerceAtMost(MAX_SCRIPT_TIME_STEP_MILLIS)
        lastTaskPumpNanos = nowNanos
        if (elapsedMillis > 0L || page.nextTaskDelayMillis() == 0L) {
            page.advanceTimeBy(elapsedMillis)
        }
        page.document.deliverMutations()
        val scrollUpdate = scroller.advance(nowNanos)
        if (scrollUpdate.changed) {
            invalidation.invalidate(InvalidationCause.SCROLL)
            scheduler.request(InvalidationCause.SCROLL, nowNanos)
        }
        if (scrollUpdate.animating) scheduler.request(InvalidationCause.SCROLL, nowNanos + 1L)
    }

    private fun produceFrame(): RenderFrame {
        val started = System.nanoTime()
        val dirty = invalidation.consume().let { if (it.isClean && frame == null) RenderInvalidation.INITIAL else it }
        val previous = frame
        var styleNanos = 0L
        var layoutNanos = 0L
        var paintNanos = 0L

        val styles = if (previous == null || dirty.requires(RenderStage.STYLE)) {
            val start = System.nanoTime()
            val result = css.compute(
                page.document.document,
                listOfNotNull(userAgentStyleSheet, styleSheet),
                MediaEnvironment(viewport.widthPx, viewport.heightPx, colorScheme = viewport.colorScheme, rootFontSizePx = viewport.rootFontSizePx)
            )
            styleNanos = System.nanoTime() - start
            result
        } else previous.styles
        page.updateComputedStyles { element -> styles.styleFor(element)?.properties.orEmpty() }

        val layoutTree = if (previous == null || dirty.requires(RenderStage.LAYOUT)) {
            val start = System.nanoTime()
            val result = layout.layout(
                page.document.document,
                styles,
                LayoutViewport(viewport.widthPx, viewport.heightPx, viewport.rootFontSizePx, viewport.deviceScaleFactor)
            )
            layoutNanos = System.nanoTime() - start
            result
        } else previous.layout

        val extentUpdate = scroller.setExtents(
            layoutTree.root.scrollSize.width,
            layoutTree.root.scrollSize.height,
            viewport.widthPx,
            viewport.heightPx
        )
        if (extentUpdate.changed) invalidation.invalidate(InvalidationCause.SCROLL)

        val displayList = if (previous == null || dirty.requires(RenderStage.PAINT)) {
            val start = System.nanoTime()
            val result = authorizeImageCommands(paint.paint(layoutTree))
            paintNanos = System.nanoTime() - start
            result
        } else previous.displayList

        val paintDamage = if (displayList === previous?.displayList) {
            PaintInvalidation(null, emptyList(), fullRepaint = false)
        } else {
            PaintInvalidationTracker.compare(previous?.displayList, displayList)
        }
        val compositeStart = System.nanoTime()
        val composition = compositor.compose(layoutTree, displayList, scroller.current(), previous?.composition, paintDamage, dirty)
        val compositeNanos = System.nanoTime() - compositeStart
        val total = (System.nanoTime() - started).coerceAtLeast(styleNanos + layoutNanos + paintNanos + compositeNanos)
        val result = RenderFrame(
            generation = ++generation,
            viewport = viewport,
            scroll = scroller.current(),
            styles = styles,
            layout = layoutTree,
            displayList = displayList,
            composition = composition,
            invalidation = dirty,
            timings = RenderStageTimings(styleNanos, layoutNanos, paintNanos, compositeNanos, total),
            reuse = FrameReuse(
                styleTree = styles === previous?.styles,
                layoutTree = layoutTree === previous?.layout,
                displayList = displayList === previous?.displayList,
                reusedLayers = composition.reusedLayerCount
            )
        )
        frame = result
        onFrame(result, scheduler.droppedRequestCount())
        logger?.debug(
            "Render",
            "Frame ${result.generation} stages=${dirty.stages} layers=${composition.layerCount} damage=${composition.damageRects.size} ${"%.3f".format(java.util.Locale.US, result.timings.totalMillis)}ms"
        )
        profiler?.increment("render.layers", composition.layerCount.toLong())
        return result
    }

    private fun authorizeImageCommands(list: DisplayList): DisplayList {
        val blocked = mutableListOf<PaintIssue>()
        val commands = list.commands.mapNotNull { command ->
            if (command !is PaintCommand.DrawImage || !command.source.startsWith("http", ignoreCase = true)) return@mapNotNull command
            val decision = page.authorizeImage(command.source)
            if (decision.allowed) command.copy(source = decision.effectiveUrl ?: command.source)
            else {
                blocked += PaintIssue("paint-image-security", decision.reason, command.nodeId)
                null
            }
        }
        return if (blocked.isEmpty() && commands == list.commands) list
        else DisplayList(commands, list.issues + blocked, list.viewport, list.generation)
    }

    private fun checkOpen() = check(!closed) { "Rendering session is closed" }

    fun styleSheetSource(): String = styleSheetSource

    private companion object {
        const val MAX_SCRIPT_TIME_STEP_MILLIS = 1_000L
    }
}
