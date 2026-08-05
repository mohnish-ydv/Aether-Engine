package com.mohnishraj.aether.core.render.selftest

import com.mohnishraj.aether.core.EngineRuntime
import com.mohnishraj.aether.core.browser.mutation.MutationObserverOptions
import com.mohnishraj.aether.core.render.FrameScheduler
import com.mohnishraj.aether.core.render.InvalidationCause
import com.mohnishraj.aether.core.render.RenderStage
import com.mohnishraj.aether.core.render.RenderViewport
import com.mohnishraj.aether.core.render.ScrollBehavior
import com.mohnishraj.aether.core.selftest.SelfTestCheck

object RenderSelfTest {
    private const val HTML = """<!doctype html><html><body><header id='bar'>Aether</header><main><h1 id='title'>M9 Pipeline</h1><p>Independent rendering pipeline.</p><div id='spacer'>scroll target</div></main></body></html>"""
    private const val CSS = """
        html, body { margin:0; width:100%; background:#0b1020; color:#eaf2ff; font-size:16px }
        body { min-height:1400px }
        #bar { position:fixed; top:0; left:0; width:100%; height:48px; background:#14213d; z-index:10 }
        main { padding:72px 16px 16px }
        #title { padding:12px; background:#182746; border-radius:10px }
        #spacer { margin-top:900px; height:120px; opacity:0.9; background:#55e6c1 }
    """

    fun run(runtime: EngineRuntime): List<SelfTestCheck> {
        val checks = mutableListOf<SelfTestCheck>()
        fun check(name: String, block: () -> String) {
            val result = runCatching(block)
            checks += if (result.isSuccess) SelfTestCheck(name, true, result.getOrThrow())
            else SelfTestCheck(name, false, result.exceptionOrNull()?.message ?: "unknown error")
        }

        val page = runtime.browser.open(HTML, "https://render.aether/")
        val session = runtime.render.open(page, CSS, RenderViewport(360.0, 640.0))
        val first = session.renderNow(1_000_000_000L)

        check("render initial frame") {
            require(first.generation == 1L && first.invalidation.requires(RenderStage.STYLE))
            "generation=${first.generation}"
        }
        check("render style layout paint") {
            require(first.styles.size > 0 && first.layout.boxCount > 1 && first.displayList.commandCount > 0)
            "styles=${first.styles.size} boxes=${first.layout.boxCount} commands=${first.displayList.commandCount}"
        }
        check("render compositor layers") {
            require(first.composition.layerCount >= 2)
            "layers=${first.composition.layerCount} items=${first.composition.itemCount}"
        }
        check("render fixed promotion") {
            require(first.composition.layers.any { layer -> layer.reasons.any { it.name == "FIXED_POSITION" } })
            "fixed layer promoted"
        }
        check("render initial damage") {
            require(first.composition.fullRedraw && first.composition.damageRects.isNotEmpty())
            "full damage verified"
        }
        check("render unchanged reuse") {
            val same = session.renderNow(1_016_666_667L)
            require(same === first)
            "clean render returned retained frame"
        }
        check("render instant scroll") {
            session.scrollTo(0.0, 320.0, ScrollBehavior.INSTANT, 1_020_000_000L)
            val scrolled = session.renderNow(1_033_333_334L)
            require(scrolled.scroll.y > 0.0 && scrolled.reuse.styleTree && scrolled.reuse.layoutTree && scrolled.reuse.displayList)
            "scroll=${scrolled.scroll.y} composite-only"
        }
        check("render scroll damage") {
            val frame = session.currentFrame ?: error("missing frame")
            require(frame.composition.damageRects.isNotEmpty() && !frame.composition.fullRedraw)
            "damageRects=${frame.composition.damageRects.size}"
        }
        check("render smooth scroll") {
            val before = session.currentScroll().y
            session.scrollTo(0.0, 700.0, ScrollBehavior.SMOOTH, 2_000_000_000L)
            session.renderNow(2_140_000_000L)
            val middle = session.currentScroll().y
            session.renderNow(2_600_000_000L)
            require(middle > before && session.currentScroll().y >= middle)
            "smooth ${before.toInt()} -> ${middle.toInt()} -> ${session.currentScroll().y.toInt()}"
        }
        check("render DOM mutation invalidation") {
            val title = page.document.getElementById("title") ?: error("title missing")
            page.document.setTextContent(title, "Updated pipeline")
            page.document.deliverMutations()
            val changed = session.renderNow(3_000_000_000L)
            require(changed.invalidation.causes.any { it == InvalidationCause.TEXT_CONTENT || it == InvalidationCause.DOM_STRUCTURE })
            require(!changed.reuse.layoutTree && !changed.reuse.displayList)
            "mutation reran layout and paint"
        }
        check("render attribute invalidation") {
            val title = page.document.getElementById("title") ?: error("title missing")
            page.document.setAttribute(title, "class", "active")
            page.document.deliverMutations()
            val changed = session.renderNow(3_100_000_000L)
            require(InvalidationCause.ATTRIBUTE in changed.invalidation.causes)
            "attribute invalidation propagated"
        }
        check("render stylesheet invalidation") {
            session.updateStyleSheet(CSS + "\n#title { color:#55e6c1 }", nowNanos = 3_200_000_000L)
            val changed = session.renderNow(3_216_666_667L)
            require(InvalidationCause.STYLESHEET in changed.invalidation.causes && !changed.reuse.styleTree)
            "stylesheet recomputed"
        }
        check("render viewport invalidation") {
            session.resize(RenderViewport(420.0, 720.0), 3_300_000_000L)
            val changed = session.renderNow(3_316_666_667L)
            require(changed.viewport.widthPx == 420.0 && changed.invalidation.full)
            "viewport=${changed.viewport.widthPx}x${changed.viewport.heightPx}"
        }
        check("render mutation observer") {
            var records = 0
            val observer = page.document.observe(page.document.document, MutationObserverOptions(attributes = true, subtree = true)) { records += it.size }
            val title = page.document.getElementById("title") ?: error("title missing")
            page.document.setAttribute(title, "data-probe", "1")
            page.document.deliverMutations()
            page.document.disconnect(observer)
            require(records == 1)
            "observer disconnect verified"
        }
        check("render scheduler coalescing") {
            val scheduler = FrameScheduler(60)
            val firstToken = scheduler.request(InvalidationCause.ANIMATION, 1L)
            val secondToken = scheduler.request(InvalidationCause.SCROLL, 2L)
            require(firstToken == secondToken && scheduler.droppedRequestCount() == 1L)
            require(scheduler.consumeIfDue(Long.MAX_VALUE)?.causes?.size == 2)
            "coalesced two requests"
        }
        check("render bounded composition") {
            val frame = session.currentFrame ?: error("missing frame")
            require(frame.composition.layerCount <= runtime.render.limits.maxLayers)
            require(frame.composition.itemCount <= runtime.render.limits.maxLayerItems)
            "bounds enforced"
        }
        check("render statistics") {
            val stats = runtime.render.statistics()
            require(stats.sessionsCreated > 0 && stats.framesProduced > 0 && stats.compositePasses == stats.framesProduced)
            "frames=${stats.framesProduced} composites=${stats.compositePasses}"
        }
        check("render session close") {
            session.close()
            require(runCatching { session.requestFrame(InvalidationCause.EXPLICIT) }.isFailure)
            "closed session rejects work"
        }
        return checks
    }
}
