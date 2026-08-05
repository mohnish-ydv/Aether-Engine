package com.mohnishraj.aether.core.render.inspect

import com.mohnishraj.aether.core.render.CompositionFrame
import com.mohnishraj.aether.core.render.RenderFrame
import java.util.Locale

object RenderInspector {
    fun summary(frame: RenderFrame): String = buildString {
        appendLine("frame=${frame.generation} viewport=${format(frame.viewport.widthPx)}x${format(frame.viewport.heightPx)} scroll=${format(frame.scroll.x)},${format(frame.scroll.y)}")
        appendLine("stages=${frame.invalidation.stages.joinToString(",")} causes=${frame.invalidation.causes.joinToString(",")}")
        appendLine("boxes=${frame.layout.boxCount} commands=${frame.displayList.commandCount} layers=${frame.composition.layerCount} items=${frame.composition.itemCount}")
        appendLine("damage=${frame.composition.damageRects.size} full=${frame.composition.fullRedraw} reusedLayers=${frame.composition.reusedLayerCount}")
        appendLine("reuse style=${frame.reuse.styleTree} layout=${frame.reuse.layoutTree} paint=${frame.reuse.displayList}")
        append("time=${"%.3f".format(Locale.US, frame.timings.totalMillis)}ms")
    }

    fun layers(composition: CompositionFrame, maxLayers: Int = 100): String = buildString {
        composition.layers.take(maxLayers.coerceAtLeast(0)).forEachIndexed { index, layer ->
            append(index.toString().padStart(3)).append("  layer=").append(layer.id)
                .append(" node=").append(layer.nodeId ?: "root")
                .append(" items=").append(layer.itemCount)
                .append(" reused=").append(layer.reused)
                .append(" reasons=").append(layer.reasons.joinToString("+"))
                .append(" translate=").append(format(layer.transform.translateX)).append(',').append(format(layer.transform.translateY))
                .append(" bounds=").append(format(layer.bounds.x)).append(',').append(format(layer.bounds.y))
                .append(' ').append(format(layer.bounds.width)).append('x').append(format(layer.bounds.height))
                .appendLine()
        }
        if (composition.layers.size > maxLayers) append("... ${composition.layers.size - maxLayers} more layer(s)")
    }.trimEnd()

    fun damage(composition: CompositionFrame): String = buildString {
        appendLine("fullRedraw=${composition.fullRedraw} scrollReuse=${composition.scrollReuse}")
        composition.damageRects.forEachIndexed { index, rect ->
            appendLine("$index: ${format(rect.x)},${format(rect.y)} ${format(rect.width)}x${format(rect.height)}")
        }
        if (composition.damageRects.isEmpty()) append("(clean)")
    }.trimEnd()

    private fun format(value: Double): String = "%.2f".format(Locale.US, value)
}
