package com.mohnishraj.aether.core.render

import com.mohnishraj.aether.core.layout.LayoutBox
import com.mohnishraj.aether.core.layout.LayoutRect
import com.mohnishraj.aether.core.layout.LayoutTree
import com.mohnishraj.aether.core.layout.PositionScheme
import com.mohnishraj.aether.core.paint.DisplayList
import com.mohnishraj.aether.core.paint.PaintCommand
import com.mohnishraj.aether.core.paint.PaintInvalidation
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/** Turns a display list into independently reusable compositor layers. */
class LayerCompositor(private val limits: RenderLimits = RenderLimits()) {
    private val generation = AtomicLong()

    fun compose(
        layout: LayoutTree,
        displayList: DisplayList,
        scroll: ScrollOffset,
        previous: CompositionFrame?,
        paintInvalidation: PaintInvalidation,
        invalidation: RenderInvalidation
    ): CompositionFrame {
        val viewport = displayList.viewport
        val promoted = promotedBoxes(layout)
        val previousByNode = previous?.layers.orEmpty().associateBy { it.nodeId }
        val itemBuckets = linkedMapOf<Long?, MutableList<LayerPaintItem>>()
        val clipStack = ArrayDeque<PaintCommand.PushClip>()
        var totalItems = 0

        displayList.commands.forEach { command ->
            when (command) {
                is PaintCommand.PushClip -> clipStack.addLast(command)
                is PaintCommand.PopClip -> if (clipStack.isNotEmpty()) clipStack.removeLast()
                else -> if (totalItems < limits.maxLayerItems) {
                    val owner = nearestPromotedNode(command.nodeId, layout, promoted)
                    itemBuckets.getOrPut(owner) { ArrayList() } += LayerPaintItem(command, clipStack.toList())
                    totalItems++
                }
            }
        }

        val orderedBoxes = buildList {
            add(null)
            layout.paintOrder.asSequence().map { it.nodeId }.filter { it in promoted }.distinct().forEach(::add)
        }.take(limits.maxLayers)
        val layers = ArrayList<CompositorLayer>(orderedBoxes.size)
        var reusedLayers = 0
        orderedBoxes.forEachIndexed { index, nodeId ->
            val box = nodeId?.let(layout::boxForNodeId)
            val reasons = reasonsFor(box)
            val items = itemBuckets[nodeId].orEmpty()
            val bounds = if (box == null) viewport else layerBounds(box, items)
            val opacity = box?.style?.properties?.get("opacity")?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 1.0
            val transform = layerTransform(box, scroll)
            val hash = contentHash(items, bounds, box?.clipRect, opacity)
            val old = previousByNode[nodeId]
            val reused = old != null && old.contentHash == hash && old.bounds == bounds && old.clip == box?.clipRect && old.opacity == opacity
            if (reused) reusedLayers++
            layers += CompositorLayer(
                id = stableLayerId(nodeId, index),
                nodeId = nodeId,
                reasons = reasons,
                bounds = bounds,
                clip = box?.clipRect,
                opacity = opacity,
                transform = transform,
                items = items,
                contentHash = hash,
                reused = reused
            )
        }

        val damage = damage(viewport, previous, scroll, paintInvalidation, invalidation)
        val scrollReuse = scrollReuse(viewport, previous?.scroll, scroll)
        val full = previous == null || invalidation.full || paintInvalidation.fullRepaint || damage.size == 1 && damage[0] == viewport
        return CompositionFrame(
            generation = generation.incrementAndGet(),
            layers = layers,
            damageRects = damage.take(limits.maxDamageRects),
            fullRedraw = full,
            reusedLayerCount = reusedLayers,
            scrollReuse = if (full) null else scrollReuse,
            viewport = viewport,
            scroll = scroll
        )
    }

    private fun promotedBoxes(layout: LayoutTree): Set<Long> = layout.paintOrder.asSequence()
        .filter { box ->
            box.element != null && (
                box.position == PositionScheme.FIXED ||
                    box.position == PositionScheme.STICKY ||
                    box.establishesStackingContext ||
                    box.isScrollableX || box.isScrollableY ||
                    box.style.properties["transform"]?.trim()?.lowercase(Locale.ROOT)?.let { it.isNotEmpty() && it != "none" } == true ||
                    (box.style.properties["opacity"]?.toDoubleOrNull() ?: 1.0) < 1.0
                )
        }
        .map { it.nodeId }
        .take((limits.maxLayers - 1).coerceAtLeast(0))
        .toSet()

    private fun nearestPromotedNode(nodeId: Long?, layout: LayoutTree, promoted: Set<Long>): Long? {
        var box = nodeId?.let(layout::boxForNodeId) ?: return null
        while (true) {
            if (box.nodeId in promoted) return box.nodeId
            val parentElement = box.element?.parent ?: return null
            box = layout.boxForNodeId(parentElement.nodeId) ?: return null
        }
    }

    private fun reasonsFor(box: LayoutBox?): Set<LayerPromotionReason> {
        if (box == null) return setOf(LayerPromotionReason.ROOT)
        val reasons = linkedSetOf<LayerPromotionReason>()
        if (box.position == PositionScheme.FIXED) reasons += LayerPromotionReason.FIXED_POSITION
        if (box.position == PositionScheme.STICKY) reasons += LayerPromotionReason.STICKY_POSITION
        if (box.establishesStackingContext) reasons += LayerPromotionReason.STACKING_CONTEXT
        if (box.isScrollableX || box.isScrollableY) reasons += LayerPromotionReason.SCROLL_CONTAINER
        if ((box.style.properties["opacity"]?.toDoubleOrNull() ?: 1.0) < 1.0) reasons += LayerPromotionReason.OPACITY
        if (box.style.properties["transform"]?.trim()?.lowercase(Locale.ROOT)?.let { it.isNotEmpty() && it != "none" } == true) {
            reasons += LayerPromotionReason.TRANSFORM
        }
        return reasons.ifEmpty { setOf(LayerPromotionReason.STACKING_CONTEXT) }
    }

    private fun layerTransform(box: LayoutBox?, scroll: ScrollOffset): LayerTransform {
        if (box?.position == PositionScheme.FIXED) return LayerTransform()
        val styleTransform = box?.style?.properties?.get("transform").orEmpty()
        val translated = TRANSLATE.find(styleTransform)
        val cssX = translated?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0
        val cssY = translated?.groupValues?.getOrNull(2)?.toDoubleOrNull() ?: 0.0
        return LayerTransform(cssX - scroll.x, cssY - scroll.y)
    }

    private fun layerBounds(box: LayoutBox, items: List<LayerPaintItem>): LayoutRect =
        items.mapNotNull { it.command.bounds }.fold<LayoutRect, LayoutRect?>(null) { acc, rect -> acc?.union(rect) ?: rect }
            ?: box.borderBox

    private fun contentHash(items: List<LayerPaintItem>, bounds: LayoutRect, clip: LayoutRect?, opacity: Double): Int {
        var result = 17
        result = 31 * result + bounds.hashCode()
        result = 31 * result + (clip?.hashCode() ?: 0)
        result = 31 * result + opacity.hashCode()
        items.forEach { item ->
            result = 31 * result + item.command.hashCode()
            result = 31 * result + item.clips.hashCode()
        }
        return result
    }

    private fun stableLayerId(nodeId: Long?, index: Int): Long = nodeId ?: -(index + 1L)

    private fun damage(
        viewport: LayoutRect,
        previous: CompositionFrame?,
        scroll: ScrollOffset,
        paint: PaintInvalidation,
        invalidation: RenderInvalidation
    ): List<LayoutRect> {
        if (previous == null || invalidation.full || paint.fullRepaint) return listOf(viewport)
        val result = ArrayList<LayoutRect>()
        paint.dirtyRect?.translated(-scroll.x, -scroll.y)?.intersection(viewport)?.let(result::add)
        invalidation.dirtyRect?.translated(-scroll.x, -scroll.y)?.intersection(viewport)?.let(result::add)
        exposedScrollDamage(viewport, previous.scroll, scroll).forEach(result::add)
        if (result.isEmpty() && invalidation.requires(RenderStage.COMPOSITE)) result += viewport
        return coalesce(result)
    }

    private fun exposedScrollDamage(viewport: LayoutRect, old: ScrollOffset, current: ScrollOffset): List<LayoutRect> {
        val dx = current.x - old.x
        val dy = current.y - old.y
        if (dx == 0.0 && dy == 0.0) return emptyList()
        if (abs(dx) >= viewport.width || abs(dy) >= viewport.height) return listOf(viewport)
        val result = ArrayList<LayoutRect>(2)
        if (dx > 0.0) result += LayoutRect(viewport.right - dx, viewport.y, dx, viewport.height)
        else if (dx < 0.0) result += LayoutRect(viewport.x, viewport.y, -dx, viewport.height)
        if (dy > 0.0) result += LayoutRect(viewport.x, viewport.bottom - dy, viewport.width, dy)
        else if (dy < 0.0) result += LayoutRect(viewport.x, viewport.y, viewport.width, -dy)
        return result
    }

    private fun scrollReuse(viewport: LayoutRect, old: ScrollOffset?, current: ScrollOffset): ScrollReuse? {
        old ?: return null
        val dx = old.x - current.x
        val dy = old.y - current.y
        if (dx == 0.0 && dy == 0.0 || abs(dx) >= viewport.width || abs(dy) >= viewport.height) return null
        val source = LayoutRect(
            viewport.x + maxOf(0.0, -dx),
            viewport.y + maxOf(0.0, -dy),
            (viewport.width - abs(dx)).coerceAtLeast(0.0),
            (viewport.height - abs(dy)).coerceAtLeast(0.0)
        )
        return ScrollReuse(source, source.translated(dx, dy), dx, dy)
    }

    private fun coalesce(rects: List<LayoutRect>): List<LayoutRect> {
        if (rects.size <= 1) return rects
        val result = ArrayList<LayoutRect>()
        rects.forEach { rect ->
            val intersectingIndex = result.indexOfFirst { existing -> existing.intersection(rect) != null }
            if (intersectingIndex >= 0) result[intersectingIndex] = result[intersectingIndex].union(rect) else result += rect
        }
        return result
    }

    companion object {
        private val TRANSLATE = Regex("translate\\(\\s*(-?[0-9.]+)(?:px)?(?:\\s*,\\s*|\\s+)(-?[0-9.]+)(?:px)?\\s*\\)", RegexOption.IGNORE_CASE)
    }
}
