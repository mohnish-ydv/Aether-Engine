package com.mohnishraj.aether.core.render

import com.mohnishraj.aether.core.css.ColorScheme
import com.mohnishraj.aether.core.css.StyleTree
import com.mohnishraj.aether.core.layout.LayoutRect
import com.mohnishraj.aether.core.layout.LayoutTree
import com.mohnishraj.aether.core.paint.DisplayList
import java.util.Collections

/** Hard limits that keep rendering work bounded for hostile or malformed pages. */
data class RenderLimits(
    val maxLayers: Int = 4_096,
    val maxDirtyNodes: Int = 100_000,
    val maxDamageRects: Int = 64,
    val maxLayerItems: Int = 1_000_000,
    val targetFramesPerSecond: Int = 60,
    val smoothScrollDurationMillis: Long = 280L
) {
    init {
        require(maxLayers > 0)
        require(maxDirtyNodes > 0)
        require(maxDamageRects > 0)
        require(maxLayerItems > 0)
        require(targetFramesPerSecond in 1..240)
        require(smoothScrollDurationMillis in 1L..10_000L)
    }
}

data class RenderViewport(
    val widthPx: Double = 360.0,
    val heightPx: Double = 800.0,
    val rootFontSizePx: Double = 16.0,
    val deviceScaleFactor: Double = 1.0,
    val colorScheme: ColorScheme = ColorScheme.LIGHT
) {
    init {
        require(widthPx >= 0.0 && widthPx.isFinite())
        require(heightPx >= 0.0 && heightPx.isFinite())
        require(rootFontSizePx > 0.0 && rootFontSizePx.isFinite())
        require(deviceScaleFactor > 0.0 && deviceScaleFactor.isFinite())
    }

    val rect: LayoutRect get() = LayoutRect(0.0, 0.0, widthPx, heightPx)
}

data class ScrollOffset(val x: Double = 0.0, val y: Double = 0.0) {
    init { require(x.isFinite() && y.isFinite()) }
}

enum class ScrollBehavior { INSTANT, SMOOTH }
enum class RenderStage { STYLE, LAYOUT, PAINT, COMPOSITE }
enum class InvalidationCause {
    INITIAL_LOAD,
    DOM_STRUCTURE,
    TEXT_CONTENT,
    ATTRIBUTE,
    STYLESHEET,
    VIEWPORT,
    SCROLL,
    ANIMATION,
    EXPLICIT
}

data class RenderInvalidation(
    val stages: Set<RenderStage>,
    val causes: Set<InvalidationCause>,
    val dirtyNodeIds: Set<Long> = emptySet(),
    val dirtyRect: LayoutRect? = null,
    val full: Boolean = false
) {
    val isClean: Boolean get() = stages.isEmpty()
    fun requires(stage: RenderStage): Boolean = stage in stages

    companion object {
        val CLEAN = RenderInvalidation(emptySet(), emptySet())
        val INITIAL = RenderInvalidation(
            stages = RenderStage.entries.toSet(),
            causes = setOf(InvalidationCause.INITIAL_LOAD),
            full = true
        )
    }
}

data class RenderStageTimings(
    val styleNanos: Long = 0L,
    val layoutNanos: Long = 0L,
    val paintNanos: Long = 0L,
    val compositeNanos: Long = 0L,
    val totalNanos: Long = 0L
) {
    val totalMillis: Double get() = totalNanos / 1_000_000.0
}

data class FrameReuse(
    val styleTree: Boolean,
    val layoutTree: Boolean,
    val displayList: Boolean,
    val reusedLayers: Int
)

data class RenderFrame(
    val generation: Long,
    val viewport: RenderViewport,
    val scroll: ScrollOffset,
    val styles: StyleTree,
    val layout: LayoutTree,
    val displayList: DisplayList,
    val composition: CompositionFrame,
    val invalidation: RenderInvalidation,
    val timings: RenderStageTimings,
    val reuse: FrameReuse
)

data class RenderPipelineStatistics(
    val sessionsCreated: Long,
    val framesProduced: Long,
    val stylePasses: Long,
    val layoutPasses: Long,
    val paintPasses: Long,
    val compositePasses: Long,
    val framesWithinBudget: Long,
    val droppedFrameRequests: Long,
    val lastFrameMillis: Double
)

enum class LayerPromotionReason {
    ROOT,
    FIXED_POSITION,
    STICKY_POSITION,
    STACKING_CONTEXT,
    SCROLL_CONTAINER,
    OPACITY,
    TRANSFORM
}

data class LayerTransform(val translateX: Double = 0.0, val translateY: Double = 0.0) {
    init { require(translateX.isFinite() && translateY.isFinite()) }
}

data class LayerPaintItem(
    val command: com.mohnishraj.aether.core.paint.PaintCommand,
    val clips: List<com.mohnishraj.aether.core.paint.PaintCommand.PushClip>
)

class CompositorLayer internal constructor(
    val id: Long,
    val nodeId: Long?,
    val reasons: Set<LayerPromotionReason>,
    val bounds: LayoutRect,
    val clip: LayoutRect?,
    val opacity: Double,
    val transform: LayerTransform,
    items: List<LayerPaintItem>,
    val contentHash: Int,
    val reused: Boolean
) {
    val items: List<LayerPaintItem> = Collections.unmodifiableList(items.toList())
    val itemCount: Int get() = items.size
}

data class ScrollReuse(
    val source: LayoutRect,
    val destination: LayoutRect,
    val deltaX: Double,
    val deltaY: Double
)

class CompositionFrame internal constructor(
    val generation: Long,
    layers: List<CompositorLayer>,
    damageRects: List<LayoutRect>,
    val fullRedraw: Boolean,
    val reusedLayerCount: Int,
    val scrollReuse: ScrollReuse?,
    val viewport: LayoutRect,
    val scroll: ScrollOffset
) {
    val layers: List<CompositorLayer> = Collections.unmodifiableList(layers.toList())
    val damageRects: List<LayoutRect> = Collections.unmodifiableList(damageRects.toList())
    val layerCount: Int get() = layers.size
    val itemCount: Int get() = layers.sumOf { it.itemCount }
}
