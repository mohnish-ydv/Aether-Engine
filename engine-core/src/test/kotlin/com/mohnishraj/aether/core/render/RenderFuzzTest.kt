package com.mohnishraj.aether.core.render

import com.mohnishraj.aether.core.browser.browserRuntime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class RenderFuzzTest {
    @Test fun randomizedViewportAndScrollCasesRemainBounded() {
        val random = Random(901)
        repeat(500) { index ->
            val runtime = browserRuntime()
            val count = random.nextInt(1, 30)
            val html = buildString {
                append("<html><body>")
                repeat(count) { append("<div style='height:${random.nextInt(1, 120)}px'>node $it</div>") }
                append("</body></html>")
            }
            val page = runtime.browser.open(html, "https://fuzz$index.test/")
            val width = random.nextInt(160, 800).toDouble()
            val height = random.nextInt(200, 1000).toDouble()
            val session = runtime.render.open(page, "html,body{margin:0}div{box-sizing:border-box}", RenderViewport(width, height))
            val frame = session.renderNow(index.toLong() * 1_000_000L)
            session.scrollTo(random.nextDouble(0.0, 1000.0), random.nextDouble(0.0, 5000.0), ScrollBehavior.INSTANT, index.toLong())
            val scrolled = session.renderNow(index.toLong() * 1_000_000L + 10L)
            assertTrue(frame.composition.layerCount <= runtime.render.limits.maxLayers)
            assertTrue(scrolled.scroll.x >= 0.0 && scrolled.scroll.y >= 0.0)
        }
    }

    @Test fun randomizedInvalidationMergesNeverLoseDependencies() {
        val random = Random(902)
        repeat(2_000) {
            val tracker = RenderInvalidationTracker()
            tracker.clear()
            repeat(random.nextInt(1, 20)) {
                tracker.invalidate(InvalidationCause.entries.random(random), random.nextLong(1, 1000))
            }
            val value = tracker.peek()
            if (RenderStage.STYLE in value.stages) assertTrue(value.stages.containsAll(RenderStage.entries))
            if (RenderStage.LAYOUT in value.stages) assertTrue(RenderStage.PAINT in value.stages && RenderStage.COMPOSITE in value.stages)
            if (RenderStage.PAINT in value.stages) assertTrue(RenderStage.COMPOSITE in value.stages)
        }
    }

    @Test fun randomizedSmoothScrollAlwaysClamps() {
        val random = Random(903)
        repeat(2_000) { index ->
            val controller = ScrollController(random.nextLong(1L, 1000L))
            val contentW = random.nextDouble(0.0, 5000.0)
            val contentH = random.nextDouble(0.0, 5000.0)
            val viewportW = random.nextDouble(0.0, 1000.0)
            val viewportH = random.nextDouble(0.0, 1000.0)
            controller.setExtents(contentW, contentH, viewportW, viewportH)
            controller.scrollTo(random.nextDouble(-1000.0, 10000.0), random.nextDouble(-1000.0, 10000.0), ScrollBehavior.SMOOTH, index.toLong())
            controller.advance(index.toLong() + 10_000_000_000L)
            val position = controller.current()
            assertTrue(position.x in 0.0..(contentW - viewportW).coerceAtLeast(0.0))
            assertTrue(position.y in 0.0..(contentH - viewportH).coerceAtLeast(0.0))
        }
    }

    @Test fun repeatedDomMutationsProduceValidFrames() {
        val runtime = browserRuntime()
        val page = runtime.browser.open("<html><body><main id='m'></main></body></html>", "https://mutate.test/")
        val session = runtime.render.open(page, "html,body{margin:0}div{height:4px}")
        session.renderNow(0L)
        val main = page.document.getElementById("m") ?: error("missing")
        repeat(500) { index ->
            val child = page.document.createElement("div")
            page.document.setAttribute(child, "data-i", index.toString())
            page.document.appendChild(main, child)
            page.document.deliverMutations()
            val frame = session.renderNow(index.toLong() + 1L)
            assertTrue(frame.layout.boxCount > 0)
            assertTrue(frame.composition.layerCount > 0)
        }
    }
}
