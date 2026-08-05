package com.mohnishraj.aether.core.layout

import com.mohnishraj.aether.core.css.ComputedStyle
import com.mohnishraj.aether.core.html.dom.ElementNode
import java.util.Collections
import kotlin.math.max
import kotlin.math.min

data class LayoutLimits(
    val maxBoxes: Int = 50_000,
    val maxDepth: Int = 256,
    val maxLineBoxes: Int = 100_000,
    val maxInlineFragments: Int = 500_000,
    val maxTextCharacters: Int = 5_000_000,
    val maxCoordinatePx: Double = 10_000_000.0
) {
    init {
        require(maxBoxes > 0)
        require(maxDepth > 0)
        require(maxLineBoxes > 0)
        require(maxInlineFragments > 0)
        require(maxTextCharacters > 0)
        require(maxCoordinatePx > 0.0)
    }
}

data class LayoutViewport(
    val widthPx: Double = 360.0,
    val heightPx: Double = 800.0,
    val rootFontSizePx: Double = 16.0,
    val deviceScaleFactor: Double = 1.0
) {
    init {
        require(widthPx >= 0.0 && widthPx.isFinite())
        require(heightPx >= 0.0 && heightPx.isFinite())
        require(rootFontSizePx > 0.0 && rootFontSizePx.isFinite())
        require(deviceScaleFactor > 0.0 && deviceScaleFactor.isFinite())
    }
}

data class LayoutPoint(val x: Double, val y: Double)

data class LayoutSize(val width: Double, val height: Double) {
    init {
        require(width >= 0.0 && width.isFinite())
        require(height >= 0.0 && height.isFinite())
    }
}

data class LayoutRect(val x: Double, val y: Double, val width: Double, val height: Double) {
    init {
        require(x.isFinite() && y.isFinite())
        require(width >= 0.0 && width.isFinite())
        require(height >= 0.0 && height.isFinite())
    }

    val right: Double get() = x + width
    val bottom: Double get() = y + height
    val size: LayoutSize get() = LayoutSize(width, height)

    fun translated(dx: Double, dy: Double): LayoutRect = copy(x = x + dx, y = y + dy)

    fun inset(edges: LayoutEdges): LayoutRect = LayoutRect(
        x = x + edges.left,
        y = y + edges.top,
        width = max(0.0, width - edges.horizontal),
        height = max(0.0, height - edges.vertical)
    )

    fun union(other: LayoutRect): LayoutRect {
        val left = min(x, other.x)
        val top = min(y, other.y)
        val right = max(right, other.right)
        val bottom = max(bottom, other.bottom)
        return LayoutRect(left, top, right - left, bottom - top)
    }

    fun intersection(other: LayoutRect): LayoutRect? {
        val left = max(x, other.x)
        val top = max(y, other.y)
        val right = min(right, other.right)
        val bottom = min(bottom, other.bottom)
        return if (right <= left || bottom <= top) null else LayoutRect(left, top, right - left, bottom - top)
    }

    fun contains(point: LayoutPoint): Boolean = point.x >= x && point.x <= right && point.y >= y && point.y <= bottom
}

data class LayoutEdges(
    val top: Double = 0.0,
    val right: Double = 0.0,
    val bottom: Double = 0.0,
    val left: Double = 0.0
) {
    init {
        require(top.isFinite() && right.isFinite() && bottom.isFinite() && left.isFinite())
    }

    val horizontal: Double get() = left + right
    val vertical: Double get() = top + bottom

    companion object {
        val ZERO = LayoutEdges()
    }
}

enum class LayoutBoxKind { VIEWPORT, BLOCK, FLEX, INLINE_BLOCK, LIST_ITEM, TABLE_FALLBACK, REPLACED }
enum class PositionScheme { STATIC, RELATIVE, ABSOLUTE, FIXED, STICKY }
enum class OverflowMode { VISIBLE, HIDDEN, CLIP, SCROLL, AUTO }
enum class LayoutIssueSeverity { WARNING, ERROR }

data class LayoutIssue(
    val code: String,
    val message: String,
    val nodeId: Long? = null,
    val severity: LayoutIssueSeverity = LayoutIssueSeverity.WARNING
)

data class InlineFragment(
    val sourceNodeId: Long,
    val ownerElementNodeId: Long,
    val text: String,
    val rect: LayoutRect,
    val baselinePx: Double,
    val fontSizePx: Double,
    val color: String,
    val isWhitespace: Boolean = false
)

data class LineBox(
    val rect: LayoutRect,
    val baselinePx: Double,
    val fragments: List<InlineFragment>
)

class LayoutBox internal constructor(
    val nodeId: Long,
    val element: ElementNode?,
    val kind: LayoutBoxKind,
    val style: ComputedStyle,
    val documentOrder: Int,
    var position: PositionScheme,
    var zIndex: Int,
    var margin: LayoutEdges,
    var border: LayoutEdges,
    var padding: LayoutEdges,
    var borderBox: LayoutRect,
    var contentBox: LayoutRect,
    var flowBorderBox: LayoutRect,
    var clipRect: LayoutRect?,
    var scrollSize: LayoutSize,
    var overflowX: OverflowMode,
    var overflowY: OverflowMode,
    var establishesStackingContext: Boolean
) {
    private val mutableChildren = ArrayList<LayoutBox>()
    private val mutableLineBoxes = ArrayList<LineBox>()

    val children: List<LayoutBox> get() = Collections.unmodifiableList(mutableChildren)
    val lineBoxes: List<LineBox> get() = Collections.unmodifiableList(mutableLineBoxes)
    val elementName: String get() = element?.localName ?: "#viewport"
    val isScrollableX: Boolean get() = overflowX == OverflowMode.SCROLL || (overflowX == OverflowMode.AUTO && scrollSize.width > contentBox.width)
    val isScrollableY: Boolean get() = overflowY == OverflowMode.SCROLL || (overflowY == OverflowMode.AUTO && scrollSize.height > contentBox.height)

    internal fun addChild(child: LayoutBox) {
        mutableChildren += child
    }

    internal fun addLine(line: LineBox) {
        mutableLineBoxes += line
    }

    internal fun translate(dx: Double, dy: Double) {
        if (dx == 0.0 && dy == 0.0) return
        borderBox = borderBox.translated(dx, dy)
        contentBox = contentBox.translated(dx, dy)
        clipRect = clipRect?.translated(dx, dy)
        for (index in mutableLineBoxes.indices) {
            val line = mutableLineBoxes[index]
            mutableLineBoxes[index] = line.copy(
                rect = line.rect.translated(dx, dy),
                fragments = line.fragments.map { it.copy(rect = it.rect.translated(dx, dy)) }
            )
        }
        mutableChildren.forEach { it.translate(dx, dy) }
    }
}

class LayoutTree internal constructor(
    val viewport: LayoutViewport,
    val root: LayoutBox,
    private val boxesByNodeId: Map<Long, LayoutBox>,
    val issues: List<LayoutIssue>,
    val paintOrder: List<LayoutBox>
) {
    fun boxFor(element: ElementNode): LayoutBox? = boxesByNodeId[element.nodeId]
    fun boxForNodeId(nodeId: Long): LayoutBox? = boxesByNodeId[nodeId]
    val boxCount: Int get() = boxesByNodeId.size + 1
    val lineBoxCount: Int get() = boxesByNodeId.values.sumOf { it.lineBoxes.size }
    val inlineFragmentCount: Int get() = boxesByNodeId.values.sumOf { box -> box.lineBoxes.sumOf { it.fragments.size } }
}

data class LayoutStatistics(
    val layoutsCompleted: Long,
    val boxesProduced: Long,
    val lineBoxesProduced: Long,
    val inlineFragmentsProduced: Long,
    val issuesSeen: Long,
    val lastLayoutMillis: Double
)
