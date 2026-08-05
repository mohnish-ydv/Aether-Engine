package com.mohnishraj.aether.core.paint

import com.mohnishraj.aether.core.layout.LayoutRect

data class PaintInvalidation(
    val dirtyRect: LayoutRect?,
    val changedCommandIndices: List<Int>,
    val fullRepaint: Boolean
) {
    val isClean: Boolean get() = dirtyRect == null && changedCommandIndices.isEmpty()
}

object PaintInvalidationTracker {
    fun compare(previous: DisplayList?, current: DisplayList): PaintInvalidation {
        if (previous == null || previous.viewport != current.viewport) {
            return PaintInvalidation(current.viewport, current.commands.indices.toList(), fullRepaint = true)
        }
        val maxSize = maxOf(previous.commands.size, current.commands.size)
        val changed = ArrayList<Int>()
        var dirty: LayoutRect? = null
        for (index in 0 until maxSize) {
            val old = previous.commands.getOrNull(index)
            val new = current.commands.getOrNull(index)
            if (!visuallyEqual(old, new)) {
                changed += index
                old?.bounds?.let { dirty = dirty?.union(it) ?: it }
                new?.bounds?.let { dirty = dirty?.union(it) ?: it }
            }
        }
        val full = changed.size > maxOf(32, maxSize / 2)
        return PaintInvalidation(if (full) current.viewport else dirty, changed, full)
    }

    private fun visuallyEqual(first: PaintCommand?, second: PaintCommand?): Boolean = visualCommand(first) == visualCommand(second)

    private fun visualCommand(command: PaintCommand?): PaintCommand? = when (command) {
        null -> null
        is PaintCommand.PushClip -> command.copy(nodeId = null)
        is PaintCommand.PopClip -> command.copy(nodeId = null)
        is PaintCommand.DrawShadow -> command.copy(nodeId = null)
        is PaintCommand.FillRect -> command.copy(nodeId = null)
        is PaintCommand.FillRoundedRect -> command.copy(nodeId = null)
        is PaintCommand.DrawLinearGradient -> command.copy(nodeId = null)
        is PaintCommand.DrawBorder -> command.copy(nodeId = null)
        is PaintCommand.DrawText -> command.copy(nodeId = null)
        is PaintCommand.DrawImage -> command.copy(nodeId = null)
    }
}
