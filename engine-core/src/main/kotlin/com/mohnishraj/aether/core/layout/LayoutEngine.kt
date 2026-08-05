package com.mohnishraj.aether.core.layout

import com.mohnishraj.aether.core.css.ComputedStyle
import com.mohnishraj.aether.core.css.StyleTree
import com.mohnishraj.aether.core.html.dom.DocumentNode
import com.mohnishraj.aether.core.html.dom.DomNode
import com.mohnishraj.aether.core.html.dom.ElementNode
import com.mohnishraj.aether.core.html.dom.TextNode
import com.mohnishraj.aether.core.log.EngineLogger
import com.mohnishraj.aether.core.profile.PerformanceProfiler
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class LayoutEngine(
    private val logger: EngineLogger? = null,
    private val profiler: PerformanceProfiler? = null,
    private val limits: LayoutLimits = LayoutLimits()
) {
    private val layoutsCompleted = AtomicLong()
    private val boxesProduced = AtomicLong()
    private val lineBoxesProduced = AtomicLong()
    private val inlineFragmentsProduced = AtomicLong()
    private val issuesSeen = AtomicLong()
    private val lastElapsedNanos = AtomicLong()

    fun layout(
        document: DocumentNode,
        styles: StyleTree,
        viewport: LayoutViewport = LayoutViewport()
    ): LayoutTree {
        val started = System.nanoTime()
        return try {
            val session = LayoutSession(styles, viewport, limits)
            val tree = session.run(document)
            layoutsCompleted.incrementAndGet()
            boxesProduced.addAndGet((tree.boxCount - 1).toLong())
            lineBoxesProduced.addAndGet(tree.lineBoxCount.toLong())
            inlineFragmentsProduced.addAndGet(tree.inlineFragmentCount.toLong())
            issuesSeen.addAndGet(tree.issues.size.toLong())
            profiler?.increment("layout.trees")
            profiler?.increment("layout.boxes", (tree.boxCount - 1).toLong())
            profiler?.increment("layout.lines", tree.lineBoxCount.toLong())
            logger?.debug(
                "Layout",
                "Laid out boxes=${tree.boxCount - 1} lines=${tree.lineBoxCount} fragments=${tree.inlineFragmentCount} issues=${tree.issues.size}"
            )
            tree
        } finally {
            val elapsed = System.nanoTime() - started
            lastElapsedNanos.set(elapsed)
            profiler?.record("layout.compute", elapsed)
        }
    }

    fun statistics(): LayoutStatistics = LayoutStatistics(
        layoutsCompleted = layoutsCompleted.get(),
        boxesProduced = boxesProduced.get(),
        lineBoxesProduced = lineBoxesProduced.get(),
        inlineFragmentsProduced = inlineFragmentsProduced.get(),
        issuesSeen = issuesSeen.get(),
        lastLayoutMillis = lastElapsedNanos.get() / 1_000_000.0
    )
}

private class LayoutSession(
    private val styles: StyleTree,
    private val viewport: LayoutViewport,
    private val limits: LayoutLimits
) {
    private val issues = ArrayList<LayoutIssue>()
    private val boxes = LinkedHashMap<Long, LayoutBox>()
    private var order = 0
    private var lineCount = 0
    private var fragmentCount = 0
    private var textCharacterCount = 0

    fun run(document: DocumentNode): LayoutTree {
        val viewportStyle = ComputedStyle(DEFAULT_PROPERTIES, emptyMap(), 0)
        val viewportRect = LayoutRect(0.0, 0.0, viewport.widthPx, viewport.heightPx)
        val viewportBox = LayoutBox(
            nodeId = Long.MIN_VALUE,
            element = null,
            kind = LayoutBoxKind.VIEWPORT,
            style = viewportStyle,
            documentOrder = order++,
            position = PositionScheme.FIXED,
            zIndex = 0,
            margin = LayoutEdges.ZERO,
            border = LayoutEdges.ZERO,
            padding = LayoutEdges.ZERO,
            borderBox = viewportRect,
            contentBox = viewportRect,
            flowBorderBox = viewportRect,
            clipRect = viewportRect,
            scrollSize = viewportRect.size,
            overflowX = OverflowMode.AUTO,
            overflowY = OverflowMode.AUTO,
            establishesStackingContext = true
        )
        val rootElement = document.documentElement ?: document.body
        if (rootElement == null) {
            issues += LayoutIssue("missing-root", "Document has no document element", severity = LayoutIssueSeverity.ERROR)
            return LayoutTree(viewport, viewportBox, emptyMap(), issues, listOf(viewportBox))
        }
        val rootResult = layoutBlockElement(
            element = rootElement,
            containing = viewportRect,
            borderY = 0.0,
            depth = 0,
            parentFontSizePx = viewport.rootFontSizePx,
            positionedContaining = viewportRect,
            forcedX = 0.0,
            forcedBorderWidth = viewport.widthPx
        )
        if (rootResult != null) {
            viewportBox.addChild(rootResult.box)
            val root = rootResult.box
            val documentWidth = max(viewport.widthPx, max(root.borderBox.right, root.contentBox.x + root.scrollSize.width))
            val documentHeight = max(viewport.heightPx, max(root.borderBox.bottom, root.contentBox.y + root.scrollSize.height))
            viewportBox.scrollSize = LayoutSize(documentWidth, documentHeight)
        }
        val paintOrder = ArrayList<LayoutBox>()
        appendPaintOrder(viewportBox, paintOrder)
        return LayoutTree(viewport, viewportBox, boxes.toMap(), issues.toList(), paintOrder)
    }

    private fun layoutBlockElement(
        element: ElementNode,
        containing: LayoutRect,
        borderY: Double,
        depth: Int,
        parentFontSizePx: Double,
        positionedContaining: LayoutRect,
        forcedX: Double? = null,
        forcedBorderWidth: Double? = null,
        forcedBorderHeight: Double? = null
    ): BlockResult? {
        if (depth > limits.maxDepth) {
            issues += LayoutIssue("depth-limit", "Layout depth limit ${limits.maxDepth} exceeded", element.nodeId, LayoutIssueSeverity.ERROR)
            return null
        }
        if (boxes.size >= limits.maxBoxes) {
            issues += LayoutIssue("box-limit", "Layout box limit ${limits.maxBoxes} exceeded", element.nodeId, LayoutIssueSeverity.ERROR)
            return null
        }
        val style = styleFor(element)
        val display = displayOf(style)
        if (display == "none") return null
        val position = LayoutValueParser.resolvePosition(style)
        val overflow = LayoutValueParser.resolveOverflow(style)
        val model = LayoutValueParser.resolveBoxModel(
            style,
            containing.width,
            containing.height.takeIf { it > 0.0 },
            parentFontSizePx,
            viewport
        )
        val horizontalNonContent = model.border.horizontal + model.padding.horizontal
        val fixedHorizontalMargins =
            (if (model.marginAutoLeft) 0.0 else model.margin.left) +
                (if (model.marginAutoRight) 0.0 else model.margin.right)
        val widthKeyword = style["width"]?.trim()?.lowercase(Locale.ROOT)
        val intrinsicContentWidth = when (widthKeyword) {
            "max-content" -> intrinsicInlineWidth(element, model.fontSizePx)
            "min-content" -> intrinsicMinContentWidth(element, model.fontSizePx)
            "fit-content" -> min(containing.width, intrinsicInlineWidth(element, model.fontSizePx))
            else -> null
        }
        val shrinkToFitAuto = widthKeyword in setOf(null, "auto") &&
            (display in setOf("inline-block", "inline-flex", "inline-grid", "inline-table") || position in setOf(PositionScheme.ABSOLUTE, PositionScheme.FIXED))
        var borderWidth = forcedBorderWidth ?: model.requestedWidth?.let { requested ->
            if (model.boxSizingBorderBox) requested else requested + horizontalNonContent
        } ?: intrinsicContentWidth?.let { it + horizontalNonContent }
            ?: if (shrinkToFitAuto) min(
                max(0.0, containing.width - fixedHorizontalMargins),
                intrinsicInlineWidth(element, model.fontSizePx) + horizontalNonContent
            ) else max(0.0, containing.width - fixedHorizontalMargins)
        val minBorderWidth = model.minWidth?.let { if (model.boxSizingBorderBox) it else it + horizontalNonContent }
        val maxBorderWidth = model.maxWidth?.let { if (model.boxSizingBorderBox) it else it + horizontalNonContent }
        if (minBorderWidth != null) borderWidth = max(borderWidth, minBorderWidth)
        if (maxBorderWidth != null) borderWidth = min(borderWidth, maxBorderWidth)
        borderWidth = sanitizeDimension(borderWidth)

        val remaining = containing.width - borderWidth - fixedHorizontalMargins
        var marginLeft = if (model.marginAutoLeft) 0.0 else model.margin.left
        var marginRight = if (model.marginAutoRight) 0.0 else model.margin.right
        when {
            model.marginAutoLeft && model.marginAutoRight -> {
                marginLeft = max(0.0, remaining / 2.0)
                marginRight = max(0.0, remaining / 2.0)
            }
            model.marginAutoLeft -> marginLeft = max(0.0, remaining)
            model.marginAutoRight -> marginRight = max(0.0, remaining)
        }
        val resolvedMargin = model.margin.copy(left = marginLeft, right = marginRight)
        val borderX = forcedX ?: containing.x + marginLeft
        val initialHeight = model.border.vertical + model.padding.vertical
        val initialBorderRect = LayoutRect(
            sanitizeCoordinate(borderX),
            sanitizeCoordinate(borderY),
            borderWidth,
            sanitizeDimension(initialHeight)
        )
        val contentWidth = max(0.0, borderWidth - model.border.horizontal - model.padding.horizontal)
        val contentX = initialBorderRect.x + model.border.left + model.padding.left
        val contentY = initialBorderRect.y + model.border.top + model.padding.top
        val requestedContentHeight = forcedBorderHeight?.let { max(0.0, it - model.border.vertical - model.padding.vertical) }
            ?: model.requestedHeight?.let { requested -> if (model.boxSizingBorderBox) max(0.0, requested - model.border.vertical - model.padding.vertical) else requested }
            ?: 0.0
        val aspectRatio = LayoutValueParser.resolveAspectRatio(style)
        val ratioContentHeight = if (aspectRatio != null && requestedContentHeight == 0.0 && contentWidth > 0.0) contentWidth / aspectRatio else requestedContentHeight
        val provisionalContent = LayoutRect(contentX, contentY, contentWidth, sanitizeDimension(ratioContentHeight))
        val kind = boxKind(element, display)
        val zIndex = LayoutValueParser.resolveZIndex(style, position)
        val opacity = style["opacity"]?.toDoubleOrNull() ?: 1.0
        val box = LayoutBox(
            nodeId = element.nodeId,
            element = element,
            kind = kind,
            style = style,
            documentOrder = order++,
            position = position,
            zIndex = zIndex,
            margin = resolvedMargin,
            border = model.border,
            padding = model.padding,
            borderBox = initialBorderRect,
            contentBox = provisionalContent,
            flowBorderBox = initialBorderRect,
            clipRect = null,
            scrollSize = LayoutSize(contentWidth, 0.0),
            overflowX = overflow.first,
            overflowY = overflow.second,
            establishesStackingContext = position != PositionScheme.STATIC && (style["z-index"]?.trim()?.lowercase(Locale.ROOT) != "auto") || opacity < 1.0
        )
        boxes[element.nodeId] = box

        val childPositioningRect = if (position != PositionScheme.STATIC) {
            LayoutRect(
                initialBorderRect.x + model.border.left,
                initialBorderRect.y + model.border.top,
                max(0.0, initialBorderRect.width - model.border.horizontal),
                max(viewport.heightPx, containing.height)
            )
        } else positionedContaining

        val normal = when {
            kind == LayoutBoxKind.REPLACED -> NormalChildrenResult(intrinsicReplacedHeight(element, model, contentWidth), emptyList())
            display == "flex" || display == "inline-flex" -> layoutFlexChildren(box, element, model, depth, childPositioningRect)
            else -> layoutNormalChildren(box, element, model, depth, childPositioningRect)
        }
        var contentHeight = forcedBorderHeight?.let { max(0.0, it - model.border.vertical - model.padding.vertical) }
            ?: model.requestedHeight?.let { requested ->
                if (model.boxSizingBorderBox) max(0.0, requested - model.border.vertical - model.padding.vertical) else requested
            }
            ?: aspectRatio?.takeIf { model.requestedWidth != null }?.let { contentWidth / it }
            ?: normal.contentHeight
        model.minHeight?.let { minimum ->
            val minContent = if (model.boxSizingBorderBox) max(0.0, minimum - model.border.vertical - model.padding.vertical) else minimum
            contentHeight = max(contentHeight, minContent)
        }
        model.maxHeight?.let { maximum ->
            val maxContent = if (model.boxSizingBorderBox) max(0.0, maximum - model.border.vertical - model.padding.vertical) else maximum
            contentHeight = min(contentHeight, maxContent)
        }
        contentHeight = sanitizeDimension(contentHeight)
        val borderHeight = sanitizeDimension(contentHeight + model.border.vertical + model.padding.vertical)
        box.borderBox = initialBorderRect.copy(height = borderHeight)
        box.flowBorderBox = box.borderBox
        box.contentBox = provisionalContent.copy(height = contentHeight)

        val finalPositioningRect = if (position != PositionScheme.STATIC) {
            LayoutRect(
                box.borderBox.x + model.border.left,
                box.borderBox.y + model.border.top,
                max(0.0, box.borderBox.width - model.border.horizontal),
                max(0.0, box.borderBox.height - model.border.vertical)
            )
        } else positionedContaining
        normal.positioned.forEach { positionedChild ->
            layoutPositionedChild(box, positionedChild, depth + 1, model.fontSizePx, finalPositioningRect)
        }
        updateOverflow(box)
        applyVisualPosition(box, containing, model.fontSizePx)
        return BlockResult(box, initialBorderRect.y + borderHeight, resolvedMargin.bottom, model.fontSizePx)
    }

    private fun layoutFlexChildren(
        box: LayoutBox,
        element: ElementNode,
        model: ResolvedBoxModel,
        depth: Int,
        positionedContaining: LayoutRect
    ): NormalChildrenResult {
        val positioned = ArrayList<ElementNode>()
        val direction = box.style["flex-direction"]?.trim()?.lowercase(Locale.ROOT) ?: "row"
        val wrapMode = box.style["flex-wrap"]?.trim()?.lowercase(Locale.ROOT) ?: "nowrap"
        val rowAxis = direction == "row" || direction == "row-reverse"
        val reverseMain = direction.endsWith("reverse")
        val wrap = wrapMode != "nowrap"
        val reverseCross = wrapMode == "wrap-reverse"
        val mainReference = if (rowAxis) box.contentBox.width else box.contentBox.height.takeIf { it > 0.0 } ?: viewport.heightPx
        val context = LengthContext(mainReference, model.fontSizePx, viewport.rootFontSizePx, viewport)
        val mainGap = LayoutValueParser.resolveGap(box.style, if (rowAxis) "column" else "row", context)
        val crossGap = LayoutValueParser.resolveGap(box.style, if (rowAxis) "row" else "column", context)
        val items = element.children.filterIsInstance<ElementNode>().mapIndexedNotNull { sourceIndex, child ->
            val style = styleFor(child)
            if (displayOf(style) == "none") return@mapIndexedNotNull null
            val position = LayoutValueParser.resolvePosition(style)
            if (position == PositionScheme.ABSOLUTE || position == PositionScheme.FIXED) {
                positioned += child
                return@mapIndexedNotNull null
            }
            val childModel = LayoutValueParser.resolveBoxModel(
                style,
                box.contentBox.width,
                box.contentBox.height.takeIf { it > 0.0 },
                model.fontSizePx,
                viewport
            )
            val shorthand = flexShorthandParts(style)
            val basisValue = style["flex-basis"]
                ?: shorthand.getOrNull(2)
                ?: shorthand.singleOrNull()?.takeIf { it.toDoubleOrNull() != null }?.let { "0px" }
            val basisResolved = LayoutValueParser.resolveLength(
                basisValue?.takeUnless { it.trim().lowercase(Locale.ROOT) == "auto" },
                LengthContext(mainReference, childModel.fontSizePx, viewport.rootFontSizePx, viewport),
                allowNegative = false
            )
            val nonContent = if (rowAxis) childModel.border.horizontal + childModel.padding.horizontal else childModel.border.vertical + childModel.padding.vertical
            val requested = if (rowAxis) childModel.requestedWidth else childModel.requestedHeight
            val intrinsic = if (rowAxis) intrinsicInlineWidth(child, childModel.fontSizePx) else intrinsicFlexHeight(child, childModel)
            val basis = sanitizeDimension(
                when {
                    basisResolved != null -> if (childModel.boxSizingBorderBox) basisResolved else basisResolved + nonContent
                    requested != null -> if (childModel.boxSizingBorderBox) requested else requested + nonContent
                    else -> intrinsic + nonContent
                }
            )
            val minRaw = style[if (rowAxis) "min-width" else "min-height"]?.trim()?.lowercase(Locale.ROOT)
            val explicitMin = if (rowAxis) childModel.minWidth else childModel.minHeight
            val autoMin = if (minRaw == null || minRaw == "auto") min(basis, intrinsic + nonContent) else 0.0
            val maxMain = if (rowAxis) childModel.maxWidth else childModel.maxHeight
            FlexItem(
                element = child,
                style = style,
                model = childModel,
                sourceIndex = sourceIndex,
                order = style["order"]?.trim()?.toIntOrNull()?.coerceIn(-100_000, 100_000) ?: 0,
                grow = style["flex-grow"]?.trim()?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: flexGrow(shorthand),
                shrink = style["flex-shrink"]?.trim()?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: flexShrink(shorthand),
                basis = basis,
                minMain = explicitMin ?: autoMin,
                maxMain = maxMain,
                outerBefore = if (rowAxis) childModel.margin.left else childModel.margin.top,
                outerAfter = if (rowAxis) childModel.margin.right else childModel.margin.bottom,
                autoBefore = if (rowAxis) childModel.marginAutoLeft else childModel.marginAutoTop,
                autoAfter = if (rowAxis) childModel.marginAutoRight else childModel.marginAutoBottom
            )
        }.sortedWith(compareBy<FlexItem> { it.order }.thenBy { it.sourceIndex }).let { ordered -> if (reverseMain) ordered.asReversed() else ordered }

        if (element.children.any { it is TextNode && it.data.isNotBlank() }) {
            issues += LayoutIssue("flex-anonymous-text", "Anonymous text flex items are not yet boxed; text was ignored", element.nodeId)
        }
        if (items.isEmpty()) return NormalChildrenResult(0.0, positioned)

        val availableMain = if (rowAxis) box.contentBox.width else box.contentBox.height.takeIf { it > 0.0 }
        val lines = ArrayList<MutableList<FlexItem>>()
        var current = ArrayList<FlexItem>()
        var occupied = 0.0
        items.forEach { item ->
            val outer = item.basis + item.outerBefore + item.outerAfter
            val needed = if (current.isEmpty()) outer else outer + mainGap
            if (wrap && availableMain != null && current.isNotEmpty() && occupied + needed > availableMain) {
                lines += current
                current = ArrayList()
                occupied = 0.0
            }
            current += item
            occupied += if (current.size == 1) outer else outer + mainGap
        }
        if (current.isNotEmpty()) lines += current
        if (reverseCross) lines.reverse()

        return if (rowAxis) {
            layoutFlexRows(box, lines, model, depth, positionedContaining, mainGap, crossGap, availableMain ?: box.contentBox.width, positioned)
        } else {
            layoutFlexColumns(box, lines.flatten(), model, depth, positionedContaining, mainGap, availableMain, positioned)
        }
    }

    private fun layoutFlexRows(
        box: LayoutBox,
        lines: List<List<FlexItem>>,
        model: ResolvedBoxModel,
        depth: Int,
        positionedContaining: LayoutRect,
        mainGap: Double,
        crossGap: Double,
        availableMain: Double,
        positioned: List<ElementNode>
    ): NormalChildrenResult {
        var lineY = box.contentBox.y
        val justify = box.style["justify-content"]?.trim()?.lowercase(Locale.ROOT) ?: "flex-start"
        val alignItems = box.style["align-items"]?.trim()?.lowercase(Locale.ROOT) ?: "stretch"
        val linePlacements = ArrayList<FlexLinePlacement>()
        lines.forEachIndexed { lineIndex, line ->
            val resolved = resolveFlexMainSizes(line, availableMain, mainGap)
            val autoMargins = resolveFlexAutoMargins(line, resolved, availableMain, mainGap)
            val used = resolved.sumOf { pair -> pair.second + autoMargins.getValue(pair.first).before + autoMargins.getValue(pair.first).after } + mainGap * (line.size - 1).coerceAtLeast(0)
            val free = (availableMain - used).coerceAtLeast(0.0)
            val distribution = justifyDistribution(justify, free, line.size, mainGap)
            var cursorX = box.contentBox.x + distribution.first
            val placements = ArrayList<FlexPlacement>()
            line.forEachIndexed { index, item ->
                val targetWidth = resolved[index].second
                val margins = autoMargins.getValue(item)
                cursorX += margins.before
                val result = layoutBlockElement(
                    element = item.element,
                    containing = LayoutRect(box.contentBox.x, lineY, targetWidth + item.outerBefore + item.outerAfter, max(viewport.heightPx, box.contentBox.height)),
                    borderY = lineY + item.model.margin.top,
                    depth = depth + 1,
                    parentFontSizePx = model.fontSizePx,
                    positionedContaining = positionedContaining,
                    forcedX = cursorX,
                    forcedBorderWidth = targetWidth
                )
                if (result != null) {
                    box.addChild(result.box)
                    placements += FlexPlacement(item, result.box)
                }
                cursorX += targetWidth + margins.after + distribution.second
            }
            val naturalLineCross = placements.maxOfOrNull { it.box.borderBox.height + it.item.model.margin.vertical } ?: 0.0
            val lineCross = if (lines.size == 1 && box.contentBox.height > 0.0) max(naturalLineCross, box.contentBox.height) else naturalLineCross
            placements.forEach { placement ->
                val align = placement.item.style["align-self"]?.trim()?.lowercase(Locale.ROOT)?.takeUnless { it == "auto" } ?: alignItems
                if (align == "stretch" && placement.item.model.requestedHeight == null) {
                    stretchCrossSize(placement.box, max(0.0, lineCross - placement.item.model.margin.vertical))
                }
                val outerHeight = placement.box.borderBox.height + placement.item.model.margin.vertical
                val offset = when (align) {
                    "center" -> max(0.0, (lineCross - outerHeight) / 2.0)
                    "flex-end", "end", "self-end" -> max(0.0, lineCross - outerHeight)
                    else -> 0.0
                }
                if (offset != 0.0) placement.box.translate(0.0, offset)
            }
            linePlacements += FlexLinePlacement(lineY, lineCross, placements)
            lineY += lineCross
            if (lineIndex < lines.lastIndex) lineY += crossGap
        }

        val naturalCross = max(0.0, lineY - box.contentBox.y)
        val availableCross = box.contentBox.height
        if (lines.size > 1 && availableCross > naturalCross) {
            val alignContent = box.style["align-content"]?.trim()?.lowercase(Locale.ROOT)?.let { if (it == "normal") "stretch" else it } ?: "stretch"
            val freeCross = availableCross - naturalCross
            val crossDistribution = alignContentDistribution(alignContent, freeCross, lines.size)
            linePlacements.forEachIndexed { index, line ->
                val dy = crossDistribution.first + crossDistribution.second * index
                if (dy != 0.0) line.placements.forEach { it.box.translate(0.0, dy) }
            }
        }
        return NormalChildrenResult(naturalCross, positioned)
    }

    private fun layoutFlexColumns(
        box: LayoutBox,
        items: List<FlexItem>,
        model: ResolvedBoxModel,
        depth: Int,
        positionedContaining: LayoutRect,
        mainGap: Double,
        availableMain: Double?,
        positioned: List<ElementNode>
    ): NormalChildrenResult {
        val resolved = resolveFlexMainSizes(items, availableMain ?: items.sumOf(FlexItem::basis), mainGap, distribute = availableMain != null)
        val effectiveAvailable = availableMain ?: (resolved.sumOf { it.second + it.first.outerBefore + it.first.outerAfter } + mainGap * (items.size - 1).coerceAtLeast(0))
        val autoMargins = resolveFlexAutoMargins(items, resolved, effectiveAvailable, mainGap)
        val used = resolved.sumOf { pair -> pair.second + autoMargins.getValue(pair.first).before + autoMargins.getValue(pair.first).after } + mainGap * (items.size - 1).coerceAtLeast(0)
        val free = (effectiveAvailable - used).coerceAtLeast(0.0)
        val justify = box.style["justify-content"]?.trim()?.lowercase(Locale.ROOT) ?: "flex-start"
        val distribution = justifyDistribution(justify, free, items.size, mainGap)
        val alignItems = box.style["align-items"]?.trim()?.lowercase(Locale.ROOT) ?: "stretch"
        var cursorY = box.contentBox.y + distribution.first
        items.forEachIndexed { index, item ->
            val margins = autoMargins.getValue(item)
            cursorY += margins.before
            val align = item.style["align-self"]?.trim()?.lowercase(Locale.ROOT)?.takeUnless { it == "auto" } ?: alignItems
            val requestedWidth = item.model.requestedWidth?.let { requested ->
                if (item.model.boxSizingBorderBox) requested else requested + item.model.border.horizontal + item.model.padding.horizontal
            }
            val targetWidth = when {
                requestedWidth != null -> requestedWidth
                align == "stretch" -> max(0.0, box.contentBox.width - item.model.margin.horizontal)
                else -> min(box.contentBox.width, intrinsicInlineWidth(item.element, item.model.fontSizePx) + item.model.border.horizontal + item.model.padding.horizontal)
            }
            val x = when (align) {
                "center" -> box.contentBox.x + max(0.0, (box.contentBox.width - targetWidth) / 2.0)
                "flex-end", "end", "self-end" -> box.contentBox.right - targetWidth - item.model.margin.right
                else -> box.contentBox.x + item.model.margin.left
            }
            val result = layoutBlockElement(
                element = item.element,
                containing = LayoutRect(box.contentBox.x, cursorY, box.contentBox.width, max(viewport.heightPx, box.contentBox.height)),
                borderY = cursorY,
                depth = depth + 1,
                parentFontSizePx = model.fontSizePx,
                positionedContaining = positionedContaining,
                forcedX = x,
                forcedBorderWidth = targetWidth,
                forcedBorderHeight = resolved[index].second
            )
            if (result != null) box.addChild(result.box)
            cursorY += resolved[index].second + margins.after + distribution.second
        }
        return NormalChildrenResult(max(0.0, cursorY - distribution.second - box.contentBox.y), positioned)
    }

    private fun resolveFlexMainSizes(
        items: List<FlexItem>,
        availableMain: Double,
        gap: Double,
        distribute: Boolean = true
    ): List<Pair<FlexItem, Double>> {
        if (items.isEmpty()) return emptyList()
        val sizes = items.associateWith { it.basis.coerceIn(it.minMain, it.maxMain ?: Double.POSITIVE_INFINITY) }.toMutableMap()
        if (!distribute) return items.map { it to sizes.getValue(it) }
        repeat(3) {
            val used = items.sumOf { sizes.getValue(it) + it.outerBefore + it.outerAfter } + gap * (items.size - 1).coerceAtLeast(0)
            val free = availableMain - used
            if (kotlin.math.abs(free) < 0.01) return@repeat
            val eligible = if (free > 0.0) {
                items.filter { it.grow > 0.0 && sizes.getValue(it) < (it.maxMain ?: Double.POSITIVE_INFINITY) }
            } else {
                items.filter { it.shrink > 0.0 && sizes.getValue(it) > it.minMain }
            }
            if (eligible.isEmpty()) return@repeat
            val factorTotal = if (free > 0.0) eligible.sumOf(FlexItem::grow)
            else eligible.sumOf { it.shrink * max(it.basis, 1.0) }
            if (factorTotal <= 0.0) return@repeat
            eligible.forEach { item ->
                val factor = if (free > 0.0) item.grow else item.shrink * max(item.basis, 1.0)
                val proposed = sizes.getValue(item) + free * factor / factorTotal
                sizes[item] = sanitizeDimension(proposed.coerceIn(item.minMain, item.maxMain ?: Double.POSITIVE_INFINITY))
            }
        }
        return items.map { it to sizes.getValue(it) }
    }

    private fun resolveFlexAutoMargins(
        items: List<FlexItem>,
        resolved: List<Pair<FlexItem, Double>>,
        availableMain: Double,
        gap: Double
    ): Map<FlexItem, MainMargins> {
        val fixed = resolved.sumOf { it.second + it.first.outerBefore + it.first.outerAfter } + gap * (items.size - 1).coerceAtLeast(0)
        val autoCount = items.sumOf { (if (it.autoBefore) 1 else 0) + (if (it.autoAfter) 1 else 0) }
        val share = if (autoCount > 0) max(0.0, availableMain - fixed) / autoCount else 0.0
        return items.associateWith { item ->
            MainMargins(if (item.autoBefore) share else item.outerBefore, if (item.autoAfter) share else item.outerAfter)
        }
    }

    private fun stretchCrossSize(box: LayoutBox, targetBorderHeight: Double) {
        if (targetBorderHeight <= box.borderBox.height) return
        val delta = targetBorderHeight - box.borderBox.height
        box.borderBox = box.borderBox.copy(height = targetBorderHeight)
        box.flowBorderBox = box.flowBorderBox.copy(height = targetBorderHeight)
        box.contentBox = box.contentBox.copy(height = box.contentBox.height + delta)
        box.scrollSize = LayoutSize(box.scrollSize.width, max(box.scrollSize.height, box.contentBox.height))
    }

    private fun justifyDistribution(justify: String, free: Double, count: Int, baseGap: Double): Pair<Double, Double> = when (justify) {
        "center" -> free / 2.0 to baseGap
        "flex-end", "end", "right" -> free to baseGap
        "space-between" -> 0.0 to if (count > 1) baseGap + free / (count - 1) else baseGap
        "space-around" -> (if (count > 0) free / count / 2.0 else 0.0) to (if (count > 0) baseGap + free / count else baseGap)
        "space-evenly" -> (if (count > 0) free / (count + 1) else 0.0) to (if (count > 0) baseGap + free / (count + 1) else baseGap)
        else -> 0.0 to baseGap
    }

    private fun alignContentDistribution(alignContent: String, free: Double, count: Int): Pair<Double, Double> = when (alignContent) {
        "center" -> free / 2.0 to 0.0
        "flex-end", "end" -> free to 0.0
        "space-between" -> 0.0 to if (count > 1) free / (count - 1) else 0.0
        "space-around" -> (if (count > 0) free / count / 2.0 else 0.0) to (if (count > 0) free / count else 0.0)
        "space-evenly" -> (if (count > 0) free / (count + 1) else 0.0) to (if (count > 0) free / (count + 1) else 0.0)
        "stretch" -> 0.0 to if (count > 0) free / count else 0.0
        else -> 0.0 to 0.0
    }

    private fun flexShorthandParts(style: ComputedStyle): List<String> =
        style["flex"]?.trim()?.lowercase(Locale.ROOT)?.split(Regex("\\s+"))?.filter(String::isNotBlank).orEmpty()

    private fun flexGrow(parts: List<String>): Double = when (parts.singleOrNull()) {
        "none", "initial" -> 0.0
        "auto" -> 1.0
        else -> parts.getOrNull(0)?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
    }

    private fun flexShrink(parts: List<String>): Double = when (parts.singleOrNull()) {
        "none" -> 0.0
        "auto", "initial" -> 1.0
        else -> parts.getOrNull(1)?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 1.0
    }

    private fun intrinsicFlexHeight(element: ElementNode, model: ResolvedBoxModel): Double {
        if (element.localName in REPLACED_ELEMENTS) return intrinsicReplacedHeight(element, model, intrinsicInlineWidth(element, model.fontSizePx))
        val lineCountEstimate = max(1, element.textContent.count { it == '\n' } + 1)
        return model.lineHeightPx * lineCountEstimate
    }


    private fun layoutNormalChildren(
        box: LayoutBox,
        element: ElementNode,
        model: ResolvedBoxModel,
        depth: Int,
        positionedContaining: LayoutRect
    ): NormalChildrenResult {
        var cursorY = box.contentBox.y
        var pendingBottomMargin = 0.0
        val inlineAtoms = ArrayList<InlineAtom>()
        val positioned = ArrayList<ElementNode>()

        fun flushInline() {
            if (inlineAtoms.isEmpty()) return
            if (pendingBottomMargin != 0.0) {
                cursorY += pendingBottomMargin
                pendingBottomMargin = 0.0
            }
            cursorY += layoutInlineAtoms(box, inlineAtoms, cursorY, model.fontSizePx, depth + 1, positionedContaining)
            inlineAtoms.clear()
        }

        lateinit var processNode: (DomNode, ElementNode, ComputedStyle) -> Unit
        processNode = process@ { node, owner, ownerStyle ->
            when (node) {
                is TextNode -> inlineAtoms += TextAtom(node, owner.nodeId, node.data, ownerStyle)
                is ElementNode -> {
                    val childStyle = styleFor(node)
                    val display = displayOf(childStyle)
                    if (display == "none") return@process
                    if (display == "contents") {
                        node.children.forEach { descendant -> processNode(descendant, node, childStyle) }
                        return@process
                    }
                    val childPosition = LayoutValueParser.resolvePosition(childStyle)
                    if (childPosition == PositionScheme.ABSOLUTE || childPosition == PositionScheme.FIXED) {
                        positioned += node
                        return@process
                    }
                    if (isBlockLevel(display)) {
                        flushInline()
                        val childModel = LayoutValueParser.resolveBoxModel(
                            childStyle,
                            box.contentBox.width,
                            box.contentBox.height.takeIf { it > 0.0 },
                            model.fontSizePx,
                            viewport
                        )
                        val collapsed = LayoutValueParser.collapseMargins(pendingBottomMargin, childModel.margin.top)
                        val childY = cursorY + collapsed
                        val result = layoutBlockElement(
                            node,
                            box.contentBox.copy(height = max(box.contentBox.height, viewport.heightPx)),
                            childY,
                            depth + 1,
                            model.fontSizePx,
                            positionedContaining
                        )
                        if (result != null) {
                            box.addChild(result.box)
                            cursorY = result.flowBottom
                            pendingBottomMargin = result.marginBottom
                        }
                    } else {
                        collectInline(node, childStyle, inlineAtoms, positioned, depth + 1)
                    }
                }
                else -> Unit
            }
        }
        element.children.forEach { child -> processNode(child, element, box.style) }
        flushInline()
        cursorY += pendingBottomMargin
        return NormalChildrenResult(max(0.0, cursorY - box.contentBox.y), positioned)
    }

    private fun collectInline(
        element: ElementNode,
        style: ComputedStyle,
        atoms: MutableList<InlineAtom>,
        positioned: MutableList<ElementNode>,
        depth: Int
    ) {
        if (depth > limits.maxDepth) {
            issues += LayoutIssue("inline-depth-limit", "Inline traversal depth exceeded", element.nodeId)
            return
        }
        val display = displayOf(style)
        if (display == "none") return
        val position = LayoutValueParser.resolvePosition(style)
        if (position == PositionScheme.ABSOLUTE || position == PositionScheme.FIXED) {
            positioned += element
            return
        }
        if (element.localName == "br") {
            atoms += BreakAtom
            return
        }
        if (display == "inline-block" || display == "inline-table" || display == "inline-flex" || display == "inline-grid") {
            atoms += InlineBoxAtom(element, style)
            return
        }
        if (isBlockLevel(display)) {
            issues += LayoutIssue("block-in-inline", "Block-level element was treated as inline-block inside inline flow", element.nodeId)
            atoms += InlineBoxAtom(element, style)
            return
        }
        element.children.forEach { child ->
            when (child) {
                is TextNode -> atoms += TextAtom(child, element.nodeId, child.data, style)
                is ElementNode -> collectInline(child, styleFor(child), atoms, positioned, depth + 1)
                else -> Unit
            }
        }
    }

    private fun layoutInlineAtoms(
        parent: LayoutBox,
        atoms: List<InlineAtom>,
        startY: Double,
        parentFontSizePx: Double,
        depth: Int,
        positionedContaining: LayoutRect
    ): Double {
        val availableWidth = max(0.0, parent.contentBox.width)
        val indent = LayoutValueParser.resolveLength(
            parent.style["text-indent"],
            LengthContext(availableWidth, parentFontSizePx, viewport.rootFontSizePx, viewport),
            allowNegative = true
        ) ?: 0.0
        var currentIndent = indent
        var lineY = startY
        var usedWidth = currentIndent
        val pending = ArrayList<PendingInline>()
        var lastWasCollapsedSpace = false
        val textAlign = parent.style["text-align"]?.trim()?.lowercase(Locale.ROOT) ?: "start"

        fun finishLine(forceEmpty: Boolean = false, finalLine: Boolean = false) {
            if (pending.isEmpty() && !forceEmpty) return
            ensureLineCapacity(parent.nodeId)
            val baseLineHeight = LayoutValueParser.resolveLineHeight(parent.style, parentFontSizePx, viewport)
            var renderedItems: List<PendingInline> = pending
            var renderedWidth = usedWidth
            val ellipsisRequested = parent.style["text-overflow"]?.trim()?.lowercase(Locale.ROOT) == "ellipsis" &&
                availableWidth > 0.0 && usedWidth > availableWidth
            if (ellipsisRequested) {
                val mutable = pending.toMutableList()
                val template = mutable.filterIsInstance<PendingText>().lastOrNull()
                val ellipsisWidth = (template?.fontSize ?: parentFontSizePx) * 0.9
                while (mutable.isNotEmpty() && currentIndent + mutable.sumOf(PendingInline::width) + ellipsisWidth > availableWidth) {
                    mutable.removeAt(mutable.lastIndex)
                }
                if (template != null) mutable += template.copy(text = "…", width = ellipsisWidth, whitespace = false)
                renderedItems = mutable
                renderedWidth = (currentIndent + mutable.sumOf(PendingInline::width)).coerceAtMost(availableWidth)
            }
            val freeForAlignment = max(0.0, availableWidth - renderedWidth)
            val justifySpaces = if (textAlign == "justify" && !finalLine && !ellipsisRequested) {
                renderedItems.count { it is PendingText && it.whitespace }
            } else 0
            val justifyExtra = if (justifySpaces > 0) freeForAlignment / justifySpaces else 0.0
            val offset = when (textAlign) {
                "center", "-webkit-center" -> freeForAlignment / 2.0
                "right", "end", "-webkit-right" -> freeForAlignment
                else -> 0.0
            }
            val maxBaseline = renderedItems.maxOfOrNull(PendingInline::baseline) ?: baseLineHeight * 0.8
            val maxDescent = renderedItems.maxOfOrNull { max(0.0, it.height - it.baseline) } ?: baseLineHeight * 0.2
            val actualHeight = max(baseLineHeight, maxBaseline + maxDescent)
            var x = parent.contentBox.x + offset + currentIndent
            val fragments = ArrayList<InlineFragment>()
            renderedItems.forEach { item ->
                val itemY = lineY + max(0.0, maxBaseline - item.baseline)
                val extra = if (item is PendingText && item.whitespace) justifyExtra else 0.0
                when (item) {
                    is PendingText -> {
                        ensureFragmentCapacity(item.text.length, item.ownerElementNodeId)
                        fragments += InlineFragment(
                            sourceNodeId = item.sourceNodeId,
                            ownerElementNodeId = item.ownerElementNodeId,
                            text = item.text,
                            rect = LayoutRect(x, itemY, item.width + extra, item.height),
                            baselinePx = maxBaseline,
                            fontSizePx = item.fontSize,
                            color = item.color,
                            isWhitespace = item.whitespace
                        )
                    }
                    is PendingBox -> {
                        item.box.translate(x - item.box.borderBox.x, itemY - item.box.borderBox.y)
                        parent.addChild(item.box)
                    }
                }
                x += item.width + extra
            }
            val lineWidth = if (justifySpaces > 0) availableWidth else renderedWidth
            parent.addLine(LineBox(LayoutRect(parent.contentBox.x + offset, lineY, lineWidth, actualHeight), maxBaseline, fragments))
            lineCount++
            lineY += actualHeight
            pending.clear()
            currentIndent = 0.0
            usedWidth = 0.0
            lastWasCollapsedSpace = false
        }

        fun addPending(item: PendingInline, wrapAllowed: Boolean) {
            if (wrapAllowed && availableWidth > 0.0 && pending.isNotEmpty() && usedWidth + item.width > availableWidth) {
                finishLine(finalLine = false)
            }
            pending += item
            usedWidth += item.width
        }

        atoms.forEach { atom ->
            when (atom) {
                BreakAtom -> finishLine(forceEmpty = true, finalLine = false)
                is InlineBoxAtom -> {
                    val childModel = LayoutValueParser.resolveBoxModel(
                        atom.style,
                        availableWidth,
                        null,
                        parentFontSizePx,
                        viewport
                    )
                    val intrinsic = intrinsicInlineWidth(atom.element, childModel.fontSizePx)
                    val requestedContent = childModel.requestedWidth ?: intrinsic
                    val desiredBorder = if (childModel.boxSizingBorderBox && childModel.requestedWidth != null) {
                        childModel.requestedWidth
                    } else {
                        requestedContent + childModel.padding.horizontal + childModel.border.horizontal
                    }
                    val outerLimit = max(0.0, availableWidth - childModel.margin.horizontal)
                    val forcedWidth = min(max(0.0, desiredBorder), outerLimit.takeIf { it > 0.0 } ?: desiredBorder)
                    val result = layoutBlockElement(
                        atom.element,
                        LayoutRect(0.0, 0.0, max(forcedWidth, 0.0), viewport.heightPx),
                        0.0,
                        depth + 1,
                        parentFontSizePx,
                        positionedContaining,
                        forcedX = 0.0,
                        forcedBorderWidth = forcedWidth
                    )
                    if (result != null) {
                        val height = result.box.borderBox.height + result.box.margin.vertical
                        val baseline = resolveInlineBaseline(height, atom.style, parentFontSizePx)
                        addPending(PendingBox(result.box, result.box.borderBox.width + result.box.margin.horizontal, height, baseline), wrapAllowed = true)
                    }
                }
                is TextAtom -> {
                    val fontSize = LayoutValueParser.resolveFontSize(atom.style, parentFontSizePx, viewport)
                    val line = LayoutValueParser.resolveLineHeight(atom.style, fontSize, viewport)
                    val color = atom.style["color"] ?: "canvastext"
                    val whiteSpace = atom.style["white-space"]?.trim()?.lowercase(Locale.ROOT) ?: "normal"
                    val preserveSpaces = whiteSpace in setOf("pre", "pre-wrap", "break-spaces")
                    val preserveNewlines = preserveSpaces || whiteSpace == "pre-line"
                    val wrapAllowed = whiteSpace !in setOf("nowrap", "pre")
                    val wordBreak = atom.style["word-break"]?.trim()?.lowercase(Locale.ROOT) ?: "normal"
                    val overflowWrap = atom.style["overflow-wrap"]?.trim()?.lowercase(Locale.ROOT)
                        ?: atom.style["word-wrap"]?.trim()?.lowercase(Locale.ROOT)
                        ?: "normal"
                    val breakLongTokens = wordBreak != "keep-all" || overflowWrap in setOf("anywhere", "break-word")
                    val transformed = transformText(atom.text, atom.style["text-transform"])
                    val segments = textSegments(transformed, preserveSpaces, preserveNewlines)
                    segments.forEach segmentLoop@ { segment ->
                        if (segment == "\n") {
                            finishLine(forceEmpty = true, finalLine = false)
                            lastWasCollapsedSpace = false
                            return@segmentLoop
                        }
                        val whitespace = segment.all(Char::isWhitespace)
                        val text = if (preserveSpaces) segment else if (whitespace) " " else segment
                        if (!preserveSpaces && whitespace) {
                            if (pending.isEmpty() || lastWasCollapsedSpace) return@segmentLoop
                            lastWasCollapsedSpace = true
                        } else if (text.isNotEmpty()) {
                            lastWasCollapsedSpace = false
                        }
                        if (text.isEmpty()) return@segmentLoop
                        val baseline = resolveTextBaseline(line, fontSize, atom.style)
                        val width = measureText(text, fontSize, atom.style)
                        if (wrapAllowed && !whitespace && breakLongTokens && availableWidth > 0.0 && width > availableWidth && pending.isEmpty()) {
                            val chunks = splitLongToken(text, fontSize, atom.style, availableWidth)
                            chunks.forEachIndexed { index, chunk ->
                                addPending(
                                    PendingText(atom.node.nodeId, atom.ownerElementNodeId, chunk, measureText(chunk, fontSize, atom.style), line, baseline, fontSize, color, false),
                                    wrapAllowed = true
                                )
                                if (index < chunks.lastIndex) finishLine(finalLine = false)
                            }
                            return@segmentLoop
                        }
                        if (wrapAllowed && whitespace && pending.isNotEmpty() && usedWidth + width > availableWidth) {
                            finishLine(finalLine = false)
                            return@segmentLoop
                        }
                        if (wrapAllowed && !whitespace && pending.isNotEmpty() && usedWidth + width > availableWidth) {
                            finishLine(finalLine = false)
                        }
                        if (!preserveSpaces && whitespace && pending.isEmpty()) return@segmentLoop
                        addPending(
                            PendingText(atom.node.nodeId, atom.ownerElementNodeId, text, width, line, baseline, fontSize, color, whitespace),
                            wrapAllowed = false
                        )
                    }
                }
            }
        }
        finishLine(finalLine = true)
        return max(0.0, lineY - startY)
    }

    private fun layoutPositionedChild(
        parent: LayoutBox,
        child: ElementNode,
        depth: Int,
        parentFontSizePx: Double,
        positionedContaining: LayoutRect
    ) {
        val style = styleFor(child)
        val position = LayoutValueParser.resolvePosition(style)
        val containing = if (position == PositionScheme.FIXED) {
            LayoutRect(0.0, 0.0, viewport.widthPx, viewport.heightPx)
        } else positionedContaining
        val model = LayoutValueParser.resolveBoxModel(style, containing.width, containing.height, parentFontSizePx, viewport)
        val left = LayoutValueParser.resolveOffset(style, "left", containing.width, model.fontSizePx, viewport)
        val right = LayoutValueParser.resolveOffset(style, "right", containing.width, model.fontSizePx, viewport)
        val top = LayoutValueParser.resolveOffset(style, "top", containing.height, model.fontSizePx, viewport)
        val bottom = LayoutValueParser.resolveOffset(style, "bottom", containing.height, model.fontSizePx, viewport)
        val forcedWidth = if (model.requestedWidth == null && left != null && right != null) {
            max(0.0, containing.width - left - right - model.margin.horizontal)
        } else null
        val result = layoutBlockElement(
            child,
            containing,
            borderY = containing.y,
            depth = depth,
            parentFontSizePx = parentFontSizePx,
            positionedContaining = containing,
            forcedBorderWidth = forcedWidth
        ) ?: return
        val box = result.box
        val targetX = when {
            left != null -> containing.x + left + box.margin.left
            right != null -> containing.right - right - box.margin.right - box.borderBox.width
            else -> box.borderBox.x
        }
        val targetY = when {
            top != null -> containing.y + top + box.margin.top
            bottom != null -> containing.bottom - bottom - box.margin.bottom - box.borderBox.height
            else -> parent.contentBox.y
        }
        box.translate(targetX - box.borderBox.x, targetY - box.borderBox.y)
        parent.addChild(box)
    }

    private fun updateOverflow(box: LayoutBox) {
        var extentRight = box.contentBox.right
        var extentBottom = box.contentBox.bottom
        box.lineBoxes.forEach { line ->
            extentRight = max(extentRight, line.rect.right)
            extentBottom = max(extentBottom, line.rect.bottom)
        }
        box.children.forEach { child ->
            val childOverflowRight = max(
                child.borderBox.right,
                child.contentBox.x + child.scrollSize.width + child.padding.right + child.border.right
            )
            val childOverflowBottom = max(
                child.borderBox.bottom,
                child.contentBox.y + child.scrollSize.height + child.padding.bottom + child.border.bottom
            )
            extentRight = max(extentRight, childOverflowRight + max(0.0, child.margin.right))
            extentBottom = max(extentBottom, childOverflowBottom + max(0.0, child.margin.bottom))
        }
        box.scrollSize = LayoutSize(
            max(box.contentBox.width, extentRight - box.contentBox.x),
            max(box.contentBox.height, extentBottom - box.contentBox.y)
        )
        val paddingBox = box.borderBox.inset(box.border)
        val clipsX = box.overflowX != OverflowMode.VISIBLE
        val clipsY = box.overflowY != OverflowMode.VISIBLE
        box.clipRect = if (clipsX || clipsY) paddingBox else null
    }

    private fun applyVisualPosition(box: LayoutBox, containing: LayoutRect, fontSizePx: Double) {
        val style = box.style
        when (box.position) {
            PositionScheme.RELATIVE -> {
                val left = LayoutValueParser.resolveOffset(style, "left", containing.width, fontSizePx, viewport)
                val right = LayoutValueParser.resolveOffset(style, "right", containing.width, fontSizePx, viewport)
                val top = LayoutValueParser.resolveOffset(style, "top", containing.height, fontSizePx, viewport)
                val bottom = LayoutValueParser.resolveOffset(style, "bottom", containing.height, fontSizePx, viewport)
                box.translate((left ?: 0.0) - (if (left == null) right ?: 0.0 else 0.0), (top ?: 0.0) - (if (top == null) bottom ?: 0.0 else 0.0))
            }
            PositionScheme.STICKY -> {
                val top = LayoutValueParser.resolveOffset(style, "top", containing.height, fontSizePx, viewport) ?: 0.0
                val minimumY = top
                val maximumY = max(minimumY, containing.bottom - box.borderBox.height - box.margin.bottom)
                val targetY = box.borderBox.y.coerceIn(minimumY, maximumY)
                box.translate(0.0, targetY - box.borderBox.y)
            }
            else -> Unit
        }
    }

    private fun styleFor(element: ElementNode): ComputedStyle = styles.styleFor(element) ?: ComputedStyle(
        DEFAULT_PROPERTIES + ("display" to defaultDisplay(element.localName)),
        emptyMap(),
        0
    )

    private fun boxKind(element: ElementNode, display: String): LayoutBoxKind = when {
        element.localName in REPLACED_ELEMENTS -> LayoutBoxKind.REPLACED
        display == "inline-block" -> LayoutBoxKind.INLINE_BLOCK
        display == "flex" || display == "inline-flex" -> LayoutBoxKind.FLEX
        display == "list-item" -> LayoutBoxKind.LIST_ITEM
        display.startsWith("table") || display.contains("grid") -> LayoutBoxKind.TABLE_FALLBACK
        else -> LayoutBoxKind.BLOCK
    }

    private fun displayOf(style: ComputedStyle): String = style["display"]?.trim()?.lowercase(Locale.ROOT) ?: "inline"

    private fun isBlockLevel(display: String): Boolean = display in setOf(
        "block", "flow-root", "list-item", "table", "table-row", "table-cell", "table-row-group",
        "table-header-group", "table-footer-group", "flex", "grid"
    )

    private fun intrinsicReplacedHeight(element: ElementNode, model: ResolvedBoxModel, contentWidth: Double): Double {
        model.requestedHeight?.let { return it }
        val attributeHeight = element.getAttribute("height")?.toDoubleOrNull()
        if (attributeHeight != null && attributeHeight >= 0.0) return attributeHeight
        return when (element.localName) {
            "img", "video", "canvas", "iframe" -> {
                val widthAttr = element.getAttribute("width")?.toDoubleOrNull()?.takeIf { it > 0.0 }
                val heightAttr = element.getAttribute("height")?.toDoubleOrNull()?.takeIf { it > 0.0 }
                when {
                    widthAttr != null && heightAttr != null && contentWidth > 0.0 -> contentWidth * heightAttr / widthAttr
                    heightAttr != null -> heightAttr
                    else -> if (contentWidth > 0.0) contentWidth * 0.5 else 150.0
                }
            }
            "svg" -> intrinsicSvgHeight(element, contentWidth)
            "input" -> when (element.getAttribute("type")?.lowercase(Locale.ROOT) ?: "text") {
                "checkbox", "radio" -> 18.0
                "range" -> max(20.0, model.lineHeightPx)
                "color" -> 30.0
                else -> max(model.lineHeightPx + 8.0, 24.0)
            }
            "select" -> max(model.lineHeightPx + 8.0, 24.0)
            "textarea" -> model.lineHeightPx * (element.getAttribute("rows")?.toIntOrNull()?.coerceIn(1, 1000) ?: 2) + 8.0
            else -> model.lineHeightPx
        }
    }

    private fun intrinsicInlineWidth(element: ElementNode, fontSizePx: Double): Double {
        element.getAttribute("width")?.removeSuffix("px")?.toDoubleOrNull()?.let { if (it >= 0.0) return it }
        if (element.localName == "svg") {
            svgViewBox(element)?.getOrNull(2)?.takeIf { it > 0.0 }?.let { return it.coerceAtMost(viewport.widthPx) }
            return 300.0.coerceAtMost(viewport.widthPx)
        }
        if (element.localName in setOf("img", "video", "canvas", "iframe")) return 300.0.coerceAtMost(viewport.widthPx)
        if (element.localName == "input") {
            val type = element.getAttribute("type")?.lowercase(Locale.ROOT) ?: "text"
            return when (type) {
                "checkbox", "radio" -> 18.0
                "color" -> 44.0
                "range" -> min(viewport.widthPx, 129.0)
                "button", "submit", "reset" -> max(44.0, measureText(element.getAttribute("value") ?: type, fontSizePx, styleFor(element)) + 20.0)
                else -> {
                    val characters = element.getAttribute("size")?.toIntOrNull()?.coerceIn(1, 1000) ?: 20
                    min(viewport.widthPx, fontSizePx * characters * 0.56 + 16.0)
                }
            }
        }
        if (element.localName == "button") return max(44.0, measureText(element.textContent, fontSizePx, styleFor(element)) + 20.0)
        if (element.localName == "select") {
            val option = element.getElementsByTagName("option").maxByOrNull { it.textContent.length }?.textContent.orEmpty()
            return max(64.0, measureText(option, fontSizePx, styleFor(element)) + 28.0).coerceAtMost(viewport.widthPx)
        }
        if (element.localName == "textarea") {
            val columns = element.getAttribute("cols")?.toIntOrNull()?.coerceIn(1, 1000) ?: 20
            return min(viewport.widthPx, fontSizePx * columns * 0.56 + 16.0)
        }
        val text = element.textContent.take(4096)
        return max(fontSizePx, measureText(text, fontSizePx, styleFor(element))).coerceAtMost(viewport.widthPx)
    }

    private fun intrinsicMinContentWidth(element: ElementNode, fontSizePx: Double): Double {
        if (element.localName in setOf("img", "svg", "video", "canvas", "iframe", "input", "select", "textarea")) {
            return intrinsicInlineWidth(element, fontSizePx)
        }
        val style = styleFor(element)
        val longest = element.textContent.take(16_384)
            .split(Regex("\\s+"))
            .maxByOrNull(String::length)
            .orEmpty()
        return max(fontSizePx, measureText(longest, fontSizePx, style)).coerceAtMost(viewport.widthPx)
    }

    private fun intrinsicSvgHeight(element: ElementNode, contentWidth: Double): Double {
        element.getAttribute("height")?.removeSuffix("px")?.toDoubleOrNull()?.takeIf { it >= 0.0 }?.let { return it }
        val viewBox = svgViewBox(element)
        val width = viewBox?.getOrNull(2)
        val height = viewBox?.getOrNull(3)
        return if (width != null && height != null && width > 0.0 && height > 0.0 && contentWidth > 0.0) contentWidth * height / width
        else if (contentWidth > 0.0) contentWidth * 0.5 else 150.0
    }

    private fun svgViewBox(element: ElementNode): List<Double>? = element.getAttribute("viewBox")
        ?.trim()
        ?.split(Regex("[ ,]+"))
        ?.mapNotNull(String::toDoubleOrNull)
        ?.takeIf { it.size >= 4 }

    private fun measureText(text: String, fontSizePx: Double, style: ComputedStyle): Double {
        val letterSpacing = LayoutValueParser.resolveLength(
            style["letter-spacing"],
            LengthContext(fontSizePx, fontSizePx, viewport.rootFontSizePx, viewport),
            allowNegative = true
        ) ?: 0.0
        val wordSpacing = LayoutValueParser.resolveLength(
            style["word-spacing"],
            LengthContext(fontSizePx, fontSizePx, viewport.rootFontSizePx, viewport),
            allowNegative = true
        ) ?: 0.0
        val weightFactor = (style["font-weight"]?.toIntOrNull() ?: 400).let { if (it >= 600) 1.04 else 1.0 }
        var width = 0.0
        var characters = 0
        text.codePoints().forEach { codePoint ->
            val factor = when {
                Character.isWhitespace(codePoint) -> 0.33
                codePoint in 0x2E80..0x9FFF -> 1.0
                codePoint > 0xFFFF -> 1.0
                codePoint.toChar() in "ilI.,'`!|:;" -> 0.28
                codePoint.toChar() in "MW@#%&" -> 0.9
                else -> 0.56
            }
            width += fontSizePx * factor * weightFactor
            if (Character.isWhitespace(codePoint)) width += wordSpacing
            characters++
        }
        if (characters > 1) width += letterSpacing * (characters - 1)
        return max(0.0, width)
    }

    private fun textSegments(text: String, preserveSpaces: Boolean, preserveNewlines: Boolean): List<String> {
        if (text.isEmpty()) return emptyList()
        if (preserveNewlines) {
            val result = ArrayList<String>()
            text.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEachIndexed { index, line ->
                if (line.isNotEmpty()) {
                    if (preserveSpaces) result += line
                    else result += Regex("\\s+|\\S+").findAll(line).map { it.value }.toList()
                }
                if (index < text.count { it == '\n' }) result += "\n"
            }
            return result
        }
        return Regex("\\s+|\\S+").findAll(text).map { it.value }.toList()
    }

    private fun transformText(text: String, property: String?): String = when (property?.trim()?.lowercase(Locale.ROOT)) {
        "uppercase" -> text.uppercase(Locale.ROOT)
        "lowercase" -> text.lowercase(Locale.ROOT)
        "capitalize" -> Regex("(^|\\s)(\\p{L})").replace(text) { match -> match.groupValues[1] + match.groupValues[2].uppercase(Locale.ROOT) }
        else -> text
    }

    private fun resolveTextBaseline(lineHeight: Double, fontSizePx: Double, style: ComputedStyle): Double {
        val normal = lineHeight * 0.8
        return when (style["vertical-align"]?.trim()?.lowercase(Locale.ROOT)) {
            "super" -> normal + fontSizePx * 0.35
            "sub" -> max(0.0, normal - fontSizePx * 0.2)
            "middle" -> lineHeight * 0.5 + fontSizePx * 0.25
            "text-top", "top" -> fontSizePx * 0.8
            "text-bottom", "bottom" -> max(0.0, lineHeight - fontSizePx * 0.2)
            else -> normal + (LayoutValueParser.resolveLength(
                style["vertical-align"],
                LengthContext(fontSizePx, fontSizePx, viewport.rootFontSizePx, viewport),
                allowNegative = true
            ) ?: 0.0)
        }
    }

    private fun resolveInlineBaseline(height: Double, style: ComputedStyle, parentFontSizePx: Double): Double = when (style["vertical-align"]?.trim()?.lowercase(Locale.ROOT)) {
        "middle" -> height / 2.0 + parentFontSizePx * 0.25
        "top", "text-top" -> parentFontSizePx * 0.8
        "bottom", "text-bottom" -> max(0.0, height - parentFontSizePx * 0.2)
        "super" -> height + parentFontSizePx * 0.35
        "sub" -> max(0.0, height - parentFontSizePx * 0.2)
        else -> height + (LayoutValueParser.resolveLength(
            style["vertical-align"],
            LengthContext(parentFontSizePx, parentFontSizePx, viewport.rootFontSizePx, viewport),
            allowNegative = true
        ) ?: 0.0)
    }

    private fun splitLongToken(text: String, fontSizePx: Double, style: ComputedStyle, width: Double): List<String> {
        if (width <= 0.0) return listOf(text)
        val result = ArrayList<String>()
        val current = StringBuilder()
        text.codePoints().forEach { codePoint ->
            val candidate = current.toString() + String(Character.toChars(codePoint))
            if (current.isNotEmpty() && measureText(candidate, fontSizePx, style) > width) {
                result += current.toString()
                current.clear()
            }
            current.appendCodePoint(codePoint)
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }

    private fun ensureLineCapacity(nodeId: Long) {
        if (lineCount >= limits.maxLineBoxes) throw LayoutLimitException("line-box-limit", nodeId)
    }

    private fun ensureFragmentCapacity(textLength: Int, nodeId: Long) {
        if (fragmentCount >= limits.maxInlineFragments) throw LayoutLimitException("inline-fragment-limit", nodeId)
        if (textCharacterCount + textLength > limits.maxTextCharacters) throw LayoutLimitException("text-character-limit", nodeId)
        fragmentCount++
        textCharacterCount += textLength
    }

    private fun sanitizeDimension(value: Double): Double = when {
        !value.isFinite() -> 0.0
        value < 0.0 -> 0.0
        else -> min(value, limits.maxCoordinatePx)
    }

    private fun sanitizeCoordinate(value: Double): Double = when {
        !value.isFinite() -> 0.0
        else -> value.coerceIn(-limits.maxCoordinatePx, limits.maxCoordinatePx)
    }

    private fun appendPaintOrder(box: LayoutBox, output: MutableList<LayoutBox>) {
        output += box
        val sorted = box.children.sortedWith(compareBy<LayoutBox> { it.zIndex }.thenBy { it.documentOrder })
        sorted.forEach { appendPaintOrder(it, output) }
    }

    private data class FlexItem(
        val element: ElementNode,
        val style: ComputedStyle,
        val model: ResolvedBoxModel,
        val sourceIndex: Int,
        val order: Int,
        val grow: Double,
        val shrink: Double,
        val basis: Double,
        val minMain: Double,
        val maxMain: Double?,
        val outerBefore: Double,
        val outerAfter: Double,
        val autoBefore: Boolean,
        val autoAfter: Boolean
    )
    private data class MainMargins(val before: Double, val after: Double)
    private data class FlexPlacement(val item: FlexItem, val box: LayoutBox)
    private data class FlexLinePlacement(val y: Double, val crossSize: Double, val placements: List<FlexPlacement>)
    private data class BlockResult(val box: LayoutBox, val flowBottom: Double, val marginBottom: Double, val fontSizePx: Double)
    private data class NormalChildrenResult(val contentHeight: Double, val positioned: List<ElementNode>)

    private sealed interface InlineAtom
    private data class TextAtom(val node: TextNode, val ownerElementNodeId: Long, val text: String, val style: ComputedStyle) : InlineAtom
    private data class InlineBoxAtom(val element: ElementNode, val style: ComputedStyle) : InlineAtom
    private data object BreakAtom : InlineAtom

    private sealed interface PendingInline {
        val width: Double
        val height: Double
        val baseline: Double
    }
    private data class PendingText(
        val sourceNodeId: Long,
        val ownerElementNodeId: Long,
        val text: String,
        override val width: Double,
        override val height: Double,
        override val baseline: Double,
        val fontSize: Double,
        val color: String,
        val whitespace: Boolean
    ) : PendingInline
    private data class PendingBox(
        val box: LayoutBox,
        override val width: Double,
        override val height: Double,
        override val baseline: Double
    ) : PendingInline

    private class LayoutLimitException(code: String, nodeId: Long) : IllegalStateException("$code at node $nodeId")

    companion object {
        private val REPLACED_ELEMENTS = setOf("img", "svg", "video", "audio", "canvas", "iframe", "input", "select", "textarea")
        private val DEFAULT_PROPERTIES = linkedMapOf(
            "display" to "block",
            "position" to "static",
            "overflow" to "visible",
            "box-sizing" to "content-box",
            "font-size" to "16px",
            "line-height" to "normal",
            "color" to "canvastext",
            "white-space" to "normal",
            "text-align" to "start"
        )

        private fun defaultDisplay(tag: String): String = when (tag) {
            "html", "body", "main", "header", "footer", "section", "article", "aside", "nav", "div", "p", "ul", "ol", "form", "h1", "h2", "h3", "h4", "h5", "h6", "figure", "figcaption", "blockquote", "pre", "fieldset", "address", "dl", "dt", "dd" -> "block"
            "li", "summary" -> "list-item"
            "img", "svg", "video", "audio", "canvas", "iframe", "input", "button", "select", "textarea" -> "inline-block"
            "head", "title", "meta", "link", "style", "script", "template" -> "none"
            "table" -> "table"
            "tbody", "thead", "tfoot" -> "table-row-group"
            "tr" -> "table-row"
            "td", "th" -> "table-cell"
            else -> "inline"
        }
    }
}
