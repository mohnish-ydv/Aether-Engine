package com.mohnishraj.aether.core.layout.inspect

import com.mohnishraj.aether.core.layout.LayoutBox
import com.mohnishraj.aether.core.layout.LayoutRect
import com.mohnishraj.aether.core.layout.LayoutTree
import java.util.ArrayDeque
import java.util.Locale

data class LayoutSummary(
    val boxes: Int,
    val lineBoxes: Int,
    val fragments: Int,
    val positionedBoxes: Int,
    val scrollContainers: Int,
    val stackingContexts: Int,
    val documentWidthPx: Double,
    val documentHeightPx: Double,
    val issues: Int
)

object LayoutInspector {
    fun summarize(tree: LayoutTree): LayoutSummary {
        val all = flatten(tree.root)
        return LayoutSummary(
            boxes = tree.boxCount,
            lineBoxes = tree.lineBoxCount,
            fragments = tree.inlineFragmentCount,
            positionedBoxes = all.count { it.position.name != "STATIC" && it.kind.name != "VIEWPORT" },
            scrollContainers = all.count { it.isScrollableX || it.isScrollableY },
            stackingContexts = all.count(LayoutBox::establishesStackingContext),
            documentWidthPx = tree.root.scrollSize.width,
            documentHeightPx = tree.root.scrollSize.height,
            issues = tree.issues.size
        )
    }

    fun tree(tree: LayoutTree, maxDepth: Int = 64, includeFragments: Boolean = true): String = buildString {
        appendLine("AETHER LAYOUT TREE")
        appendLine("viewport=${format(tree.viewport.widthPx)}x${format(tree.viewport.heightPx)} boxes=${tree.boxCount} lines=${tree.lineBoxCount} fragments=${tree.inlineFragmentCount}")
        appendLine("paintOrder=${tree.paintOrder.size} issues=${tree.issues.size}")
        appendLine("------------------")
        appendBox(tree.root, 0, maxDepth, includeFragments)
        if (tree.issues.isNotEmpty()) {
            appendLine("------------------")
            appendLine("ISSUES")
            tree.issues.take(100).forEach { issue ->
                appendLine("${issue.severity} ${issue.code} node=${issue.nodeId ?: "-"}: ${issue.message}")
            }
            if (tree.issues.size > 100) appendLine("... ${tree.issues.size - 100} more")
        }
    }

    fun paintOrder(tree: LayoutTree): String = tree.paintOrder.joinToString("\n") { box ->
        "z=${box.zIndex.toString().padStart(4)} order=${box.documentOrder.toString().padStart(4)} ${box.elementName}#${box.nodeId} ${rect(box.borderBox)}"
    }

    fun box(box: LayoutBox): String = buildString {
        appendLine("node=${box.nodeId} element=${box.elementName} kind=${box.kind}")
        appendLine("position=${box.position} zIndex=${box.zIndex} stacking=${box.establishesStackingContext}")
        appendLine("borderBox=${rect(box.borderBox)}")
        appendLine("contentBox=${rect(box.contentBox)}")
        appendLine("margin=${edges(box.margin)} border=${edges(box.border)} padding=${edges(box.padding)}")
        appendLine("overflow=${box.overflowX}/${box.overflowY} scroll=${format(box.scrollSize.width)}x${format(box.scrollSize.height)}")
        appendLine("clip=${box.clipRect?.let(::rect) ?: "none"}")
        append("children=${box.children.size} lines=${box.lineBoxes.size}")
    }

    private fun StringBuilder.appendBox(box: LayoutBox, depth: Int, maxDepth: Int, includeFragments: Boolean) {
        val indent = "  ".repeat(depth)
        append(indent)
        append(box.elementName)
        append('#')
        append(box.nodeId)
        append(" kind=")
        append(box.kind)
        append(" pos=")
        append(box.position)
        append(" z=")
        append(box.zIndex)
        append(" border=")
        append(rect(box.borderBox))
        append(" content=")
        append(rect(box.contentBox))
        if (box.clipRect != null) append(" clip=${rect(box.clipRect!!)}")
        if (box.isScrollableX || box.isScrollableY) append(" scroll=${format(box.scrollSize.width)}x${format(box.scrollSize.height)}")
        appendLine()
        if (includeFragments) {
            box.lineBoxes.forEachIndexed { index, line ->
                append(indent)
                append("  @line[")
                append(index)
                append("] ")
                append(rect(line.rect))
                append(" baseline=")
                append(format(line.baselinePx))
                appendLine()
                line.fragments.forEach { fragment ->
                    append(indent)
                    append("    text#")
                    append(fragment.sourceNodeId)
                    append(' ')
                    append(rect(fragment.rect))
                    append(" ")
                    append(quote(fragment.text))
                    appendLine()
                }
            }
        }
        if (depth >= maxDepth) {
            if (box.children.isNotEmpty()) appendLine("$indent  ... depth limit")
            return
        }
        box.children.forEach { appendBox(it, depth + 1, maxDepth, includeFragments) }
    }

    private fun flatten(root: LayoutBox): List<LayoutBox> {
        val result = ArrayList<LayoutBox>()
        val stack = ArrayDeque<LayoutBox>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            result += current
            current.children.asReversed().forEach(stack::addLast)
        }
        return result
    }

    private fun rect(rect: LayoutRect): String = "(${format(rect.x)},${format(rect.y)} ${format(rect.width)}x${format(rect.height)})"
    private fun edges(edges: com.mohnishraj.aether.core.layout.LayoutEdges): String =
        "${format(edges.top)} ${format(edges.right)} ${format(edges.bottom)} ${format(edges.left)}"
    private fun format(value: Double): String = String.format(Locale.US, "%.2f", value)
    private fun quote(value: String): String = "\"${value.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"").take(160)}\""
}
