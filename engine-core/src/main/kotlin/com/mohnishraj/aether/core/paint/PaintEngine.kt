package com.mohnishraj.aether.core.paint

import com.mohnishraj.aether.core.html.dom.DocumentNode
import com.mohnishraj.aether.core.html.dom.ElementNode
import com.mohnishraj.aether.core.html.inspect.HtmlSerializer
import com.mohnishraj.aether.core.layout.LayoutBox
import com.mohnishraj.aether.core.layout.LayoutBoxKind
import com.mohnishraj.aether.core.layout.LayoutEdges
import com.mohnishraj.aether.core.layout.LayoutRect
import com.mohnishraj.aether.core.layout.LayoutTree
import com.mohnishraj.aether.core.log.EngineLogger
import com.mohnishraj.aether.core.profile.PerformanceProfiler
import java.net.URI
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class PaintEngine(
    private val logger: EngineLogger? = null,
    private val profiler: PerformanceProfiler? = null,
    private val limits: PaintLimits = PaintLimits()
) {
    private val displayListsBuilt = AtomicLong()
    private val commandsProduced = AtomicLong()
    private val textCommandsProduced = AtomicLong()
    private val imageCommandsProduced = AtomicLong()
    private val issuesSeen = AtomicLong()
    private val lastElapsedNanos = AtomicLong()
    private val generation = AtomicLong()

    fun paint(layout: LayoutTree): DisplayList {
        val started = System.nanoTime()
        return try {
            val session = PaintSession(layout, limits, generation.incrementAndGet())
            val list = session.run()
            displayListsBuilt.incrementAndGet()
            commandsProduced.addAndGet(list.commandCount.toLong())
            textCommandsProduced.addAndGet(list.textCommandCount.toLong())
            imageCommandsProduced.addAndGet(list.imageCommandCount.toLong())
            issuesSeen.addAndGet(list.issues.size.toLong())
            profiler?.increment("paint.displayLists")
            profiler?.increment("paint.commands", list.commandCount.toLong())
            logger?.debug("Paint", "Built commands=${list.commandCount} text=${list.textCommandCount} images=${list.imageCommandCount} issues=${list.issues.size}")
            list
        } finally {
            val elapsed = System.nanoTime() - started
            lastElapsedNanos.set(elapsed)
            profiler?.record("paint.build", elapsed)
        }
    }

    fun statistics(): PaintStatistics = PaintStatistics(
        displayListsBuilt.get(), commandsProduced.get(), textCommandsProduced.get(), imageCommandsProduced.get(),
        issuesSeen.get(), lastElapsedNanos.get() / 1_000_000.0
    )
}

private class PaintSession(
    private val layout: LayoutTree,
    private val limits: PaintLimits,
    private val generation: Long
) {
    private val commands = ArrayList<PaintCommand>()
    private val issues = ArrayList<PaintIssue>()
    private var textCharacters = 0
    private var clipDepth = 0
    private val viewportRect = LayoutRect(0.0, 0.0, layout.viewport.widthPx, layout.viewport.heightPx)

    fun run(): DisplayList {
        paintCanvasBackground()
        for (box in layout.paintOrder) paintBox(box)
        while (clipDepth > 0) {
            commands += PaintCommand.PopClip()
            clipDepth--
        }
        return DisplayList(commands, issues.toList(), viewportRect, generation)
    }

    private fun paintCanvasBackground() {
        add(PaintCommand.FillRect(viewportRect, PaintColor.WHITE))
        val html = layout.paintOrder.firstOrNull { it.elementName == "html" }
        val body = layout.paintOrder.firstOrNull { it.elementName == "body" }
        fun background(box: LayoutBox?): PaintColor? {
            val properties = box?.style?.properties ?: return null
            val current = PaintValueParser.color(properties["color"], PaintColor.BLACK) ?: PaintColor.BLACK
            return (PaintValueParser.color(properties["background-color"], current)
                ?: PaintValueParser.color(properties["background"], current))
                ?.takeUnless(PaintColor::isTransparent)
        }
        val propagated = background(html) ?: background(body)
        if (propagated != null && propagated != PaintColor.WHITE) {
            add(PaintCommand.FillRect(viewportRect, propagated))
        }
    }

    private fun paintListMarker(
        box: LayoutBox,
        style: Map<String, String>,
        currentColor: PaintColor,
        opacity: Double
    ) {
        if (box.kind != LayoutBoxKind.LIST_ITEM) return
        val listStyle = (style["list-style-type"] ?: style["list-style"] ?: "disc")
            .trim().lowercase(Locale.ROOT)
        if (listStyle == "none") return
        val line = box.lineBoxes.firstOrNull() ?: return
        val fontSize = line.fragments.firstOrNull()?.fontSizePx ?: 16.0
        val parent = box.element?.parent as? ElementNode
        val ordered = parent?.localName == "ol"
        val marker = if (ordered) {
            val siblings = parent?.children.orEmpty().filterIsInstance<ElementNode>().filter { it.localName == "li" }
            val index = siblings.indexOfFirst { it.nodeId == box.nodeId }.coerceAtLeast(0) + 1
            "$index."
        } else when (listStyle) {
            "circle" -> "○"
            "square" -> "▪"
            else -> "•"
        }
        val width = fontSize * if (ordered) 2.25 else 1.25
        add(
            PaintCommand.DrawText(
                text = marker,
                rect = LayoutRect(maxOf(0.0, box.contentBox.x - width), line.rect.y, width, line.rect.height),
                baselinePx = line.baselinePx,
                fontSizePx = fontSize,
                color = currentColor,
                fontFamily = fontFamily(style),
                fontWeight = PaintValueParser.fontWeight(style["font-weight"]),
                italic = false,
                opacity = opacity,
                nodeId = box.nodeId
            )
        )
    }

    private fun paintBox(box: LayoutBox) {
        if (commands.size >= limits.maxCommands) {
            issue("paint-command-limit", "Maximum paint command count reached", box.nodeId, PaintIssueSeverity.ERROR)
            return
        }
        val style = box.style.properties
        if (isHidden(box)) return
        val opacity = effectiveOpacity(box)
        if (opacity <= 0.0) return
        val effectiveClip = effectiveClip(box)
        if (effectiveClip != null && effectiveClip.intersection(viewportRect) == null) return

        val disabled = box.element?.hasAttribute("disabled") == true
        val controlOpacity = if (disabled) opacity * 0.55 else opacity
        val currentColor = PaintValueParser.color(style["color"], PaintColor.BLACK) ?: PaintColor.BLACK
        val radii = PaintValueParser.radii(style["border-radius"], box.borderBox)
        if (effectiveClip != null && clipDepth < limits.maxClipDepth) {
            add(PaintCommand.PushClip(effectiveClip, radii, box.nodeId))
            clipDepth++
        } else if (effectiveClip != null) {
            issue("paint-clip-depth", "Maximum clip depth reached", box.nodeId, PaintIssueSeverity.ERROR)
        }

        PaintValueParser.shadows(style["box-shadow"], currentColor, limits.maxShadowsPerBox)
            .filterNot { it.inset }
            .forEach { add(PaintCommand.DrawShadow(box.borderBox, radii, it, box.nodeId)) }

        val backgroundColor = PaintValueParser.color(style["background"], currentColor)
            ?: PaintValueParser.color(style["background-color"], currentColor)
        if (backgroundColor != null && !backgroundColor.isTransparent) {
            if (radii.isSquare) add(PaintCommand.FillRect(box.borderBox, backgroundColor, controlOpacity, box.nodeId))
            else add(PaintCommand.FillRoundedRect(box.borderBox, radii, backgroundColor, controlOpacity, box.nodeId))
        }

        val backgroundImage = style["background-image"] ?: style["background"]
        PaintValueParser.linearGradient(backgroundImage, currentColor)?.let { gradient ->
            add(PaintCommand.DrawLinearGradient(box.borderBox, gradient.first, gradient.second, gradient.third, radii, controlOpacity, box.nodeId))
        }

        // Native control chrome must be emitted before child text/SVG content.
        // Painting it afterwards covered real-world icon buttons with a blank rectangle.
        paintFormControl(box, style, currentColor, controlOpacity)

        if (box.elementName == "img" || box.elementName == "svg") {
            val element = box.element
            val rawSource = if (box.elementName == "svg" && element != null) {
                HtmlSerializer.serialize(element).replace("currentColor", currentColor.toHex(), ignoreCase = true)
            } else element?.getAttribute("src")
                    ?.takeIf(String::isNotBlank)
                    ?: element?.getAttribute("srcset")?.substringBefore(',')?.trim()?.substringBefore(' ')
                    ?: element?.getAttribute("data-src").orEmpty()
            val source = resolveImageSource(
                rawSource,
                (element?.root() as? DocumentNode)?.url
            ).take(limits.maxImageSourceChars)
            val alt = element?.getAttribute("alt")
            if (source.isNotBlank()) {
                add(
                    PaintCommand.DrawImage(
                        source = source,
                        destination = box.contentBox,
                        fit = PaintValueParser.imageFit(style["object-fit"]),
                        position = PaintValueParser.imagePosition(style["object-position"]),
                        opacity = controlOpacity,
                        altText = alt,
                        lazy = box.element?.getAttribute("loading")?.equals("lazy", ignoreCase = true) == true,
                        nodeId = box.nodeId
                    )
                )
            } else if (!alt.isNullOrBlank()) {
                issue("paint-image-source", "Image has no source; alt text retained", box.nodeId)
            }
        }

        val border = PaintValueParser.border(box.border, style, box.borderBox, currentColor)
        if (border.visible && border.styles.any { it !in setOf("none", "hidden") }) {
            add(PaintCommand.DrawBorder(box.borderBox, border, controlOpacity, box.nodeId))
        }

        paintListMarker(box, style, currentColor, controlOpacity)

        for (line in box.lineBoxes) {
            for (fragment in line.fragments) {
                if (fragment.text.isEmpty()) continue
                val remaining = limits.maxTextCharacters - textCharacters
                if (remaining <= 0) {
                    issue("paint-text-limit", "Maximum text characters reached", fragment.sourceNodeId, PaintIssueSeverity.ERROR)
                    break
                }
                val text = fragment.text.take(remaining)
                if (text.length < fragment.text.length) {
                    issue("paint-text-limit", "Maximum text characters reached", fragment.sourceNodeId, PaintIssueSeverity.ERROR)
                }
                textCharacters += text.length
                val ownerStyle = layout.boxForNodeId(fragment.ownerElementNodeId)?.style?.properties ?: style
                val fragmentColor = PaintValueParser.color(fragment.color, currentColor) ?: currentColor
                add(
                    PaintCommand.DrawText(
                        text = text,
                        rect = fragment.rect,
                        baselinePx = fragment.baselinePx,
                        fontSizePx = fragment.fontSizePx,
                        color = fragmentColor,
                        fontFamily = fontFamily(ownerStyle),
                        fontWeight = PaintValueParser.fontWeight(ownerStyle["font-weight"]),
                        italic = ownerStyle["font-style"]?.trim()?.lowercase(Locale.ROOT) in setOf("italic", "oblique"),
                        letterSpacingPx = textSpacing(ownerStyle["letter-spacing"], fragment.fontSizePx),
                        wordSpacingPx = textSpacing(ownerStyle["word-spacing"], fragment.fontSizePx),
                        textDecoration = ownerStyle["text-decoration-line"] ?: ownerStyle["text-decoration"] ?: "none",
                        opacity = controlOpacity,
                        nodeId = fragment.sourceNodeId
                    )
                )
            }
        }

        PaintValueParser.shadows(style["box-shadow"], currentColor, limits.maxShadowsPerBox)
            .filter { it.inset }
            .forEach { add(PaintCommand.DrawShadow(box.borderBox, radii, it, box.nodeId)) }

        if (effectiveClip != null && clipDepth > 0) {
            add(PaintCommand.PopClip(box.nodeId))
            clipDepth--
        }
    }

    private fun paintFormControl(
        box: LayoutBox,
        style: Map<String, String>,
        currentColor: PaintColor,
        opacity: Double
    ) {
        val element = box.element ?: return
        val tag = element.localName
        if (tag !in FORM_CONTROLS) return
        val disabled = element.hasAttribute("disabled")
        val readonly = element.hasAttribute("readonly")
        val focused = element.hasAttribute("autofocus") || element.getAttribute("data-aether-focus") == "true"
        val type = element.getAttribute("type")?.lowercase(Locale.ROOT) ?: "text"
        val isToggle = tag == "input" && type in setOf("checkbox", "radio")
        val controlBackground = PaintValueParser.color(style["background-color"], currentColor)
            ?: PaintValueParser.color(style["background"], currentColor)
        val backgroundSpecified = controlBackground?.isTransparent == false ||
            PaintValueParser.linearGradient(style["background-image"] ?: style["background"], currentColor) != null
        val borderSpecified = box.border.horizontal > 0.0 || box.border.vertical > 0.0
        val controlRect = if (isToggle) {
            val size = minOf(
                box.contentBox.width.takeIf { it > 0.0 } ?: 18.0,
                box.contentBox.height.takeIf { it > 0.0 } ?: 18.0,
                22.0
            )
            LayoutRect(box.contentBox.x, box.contentBox.y + maxOf(0.0, (box.contentBox.height - size) / 2.0), size, size)
        } else {
            box.borderBox
        }
        val defaultRadii = if (type == "radio") {
            CornerRadii(controlRect.height / 2.0, controlRect.height / 2.0, controlRect.height / 2.0, controlRect.height / 2.0)
        } else {
            PaintValueParser.radii(style["border-radius"] ?: "6px", controlRect)
        }
        if (!backgroundSpecified) {
            val fill = when {
                disabled -> PaintColor(226, 230, 236)
                readonly -> PaintColor(241, 244, 248)
                tag == "button" || (tag == "input" && type in setOf("button", "submit", "reset")) -> PaintColor(231, 238, 248)
                else -> PaintColor.WHITE
            }
            if (defaultRadii.isSquare) add(PaintCommand.FillRect(controlRect, fill, opacity, box.nodeId))
            else add(PaintCommand.FillRoundedRect(controlRect, defaultRadii, fill, opacity, box.nodeId))
        }
        if (!borderSpecified) {
            val borderColor = when {
                focused -> PaintColor(46, 111, 229)
                disabled -> PaintColor(185, 192, 203)
                else -> PaintColor(120, 130, 145)
            }
            val width = if (focused) 2.0 else 1.0
            add(
                PaintCommand.DrawBorder(
                    rect = controlRect,
                    border = BorderPaint(
                        widths = LayoutEdges(width, width, width, width),
                        colors = List(4) { borderColor },
                        styles = List(4) { "solid" },
                        radii = defaultRadii
                    ),
                    opacity = opacity,
                    nodeId = box.nodeId
                )
            )
        }
        if (isToggle) {
            if (element.hasAttribute("checked")) {
                if (type == "radio") {
                    val inset = maxOf(3.0, controlRect.width * 0.28)
                    val dot = LayoutRect(
                        controlRect.x + inset,
                        controlRect.y + inset,
                        maxOf(0.0, controlRect.width - inset * 2.0),
                        maxOf(0.0, controlRect.height - inset * 2.0)
                    )
                    val dotRadius = dot.height / 2.0
                    add(
                        PaintCommand.FillRoundedRect(
                            dot,
                            CornerRadii(dotRadius, dotRadius, dotRadius, dotRadius),
                            currentColor,
                            opacity,
                            box.nodeId
                        )
                    )
                } else {
                    add(
                        PaintCommand.DrawText(
                            text = "✓",
                            rect = controlRect,
                            baselinePx = controlRect.height * 0.76,
                            fontSizePx = maxOf(12.0, controlRect.height * 0.82),
                            color = currentColor,
                            fontFamily = "sans-serif",
                            fontWeight = 700,
                            italic = false,
                            opacity = opacity,
                            nodeId = box.nodeId
                        )
                    )
                }
            }
            return
        }

        if (tag == "button" && (box.lineBoxes.isNotEmpty() || box.children.isNotEmpty())) return

        val text = controlText(element, tag, type)
        val placeholder = if (text.isEmpty()) element.getAttribute("placeholder").orEmpty() else ""
        val visibleText = if (type == "password" && text.isNotEmpty()) {
            "•".repeat(text.length.coerceAtMost(256))
        } else {
            text.ifEmpty { placeholder }
        }
        if (visibleText.isEmpty()) return
        val color = if (text.isEmpty() && placeholder.isNotEmpty()) PaintColor(125, 132, 143) else currentColor
        val fontSize = style["font-size"]?.removeSuffix("px")?.toDoubleOrNull()?.coerceIn(8.0, 96.0) ?: 16.0
        val horizontalPadding = if (tag == "button") 10.0 else 8.0
        val textRect = LayoutRect(
            box.contentBox.x + horizontalPadding,
            box.contentBox.y,
            maxOf(0.0, box.contentBox.width - horizontalPadding * 2.0),
            box.contentBox.height
        )
        add(
            PaintCommand.DrawText(
                text = visibleText.take(4096),
                rect = textRect,
                baselinePx = minOf(textRect.height - 2.0, maxOf(fontSize, textRect.height * 0.68)),
                fontSizePx = fontSize,
                color = color,
                fontFamily = fontFamily(style),
                fontWeight = if (tag == "button") maxOf(500, PaintValueParser.fontWeight(style["font-weight"])) else PaintValueParser.fontWeight(style["font-weight"]),
                italic = style["font-style"]?.trim()?.lowercase(Locale.ROOT) in setOf("italic", "oblique"),
                letterSpacingPx = textSpacing(style["letter-spacing"], fontSize),
                wordSpacingPx = textSpacing(style["word-spacing"], fontSize),
                textDecoration = style["text-decoration-line"] ?: style["text-decoration"] ?: "none",
                opacity = opacity,
                nodeId = box.nodeId
            )
        )
    }

    private fun controlText(element: ElementNode, tag: String, type: String): String = when (tag) {
        "textarea" -> element.getAttribute("value") ?: element.textContent
        "button" -> element.textContent.ifBlank { element.getAttribute("value").orEmpty() }
        "select" -> element.children.filterIsInstance<ElementNode>()
            .firstOrNull { it.localName == "option" && it.hasAttribute("selected") }
            ?.textContent
            ?: element.children.filterIsInstance<ElementNode>().firstOrNull { it.localName == "option" }?.textContent.orEmpty()
        "input" -> when (type) {
            "button", "submit", "reset" -> element.getAttribute("value") ?: type.replaceFirstChar { it.uppercaseChar() }
            else -> element.getAttribute("value").orEmpty()
        }
        else -> ""
    }

    private fun fontFamily(style: Map<String, String>): String =
        style["font-family"]?.substringBefore(',')?.trim()?.trim('\'', '"') ?: "sans-serif"

    private fun textSpacing(value: String?, fontSizePx: Double): Double {
        val raw = value?.trim()?.lowercase(Locale.ROOT) ?: return 0.0
        if (raw == "normal") return 0.0
        return when {
            raw.endsWith("rem") -> raw.removeSuffix("rem").toDoubleOrNull()?.times(layout.viewport.rootFontSizePx)
            raw.endsWith("em") -> raw.removeSuffix("em").toDoubleOrNull()?.times(fontSizePx)
            raw.endsWith("px") -> raw.removeSuffix("px").toDoubleOrNull()
            else -> raw.toDoubleOrNull()
        }?.coerceIn(-fontSizePx, fontSizePx * 4.0) ?: 0.0
    }

    private fun effectiveOpacity(box: LayoutBox): Double {
        var opacity = PaintValueParser.opacity(box.style.properties["opacity"])
        var parent = box.element?.parent
        while (parent != null) {
            val ancestor = layout.boxForNodeId(parent.nodeId)
            if (ancestor != null) opacity *= PaintValueParser.opacity(ancestor.style.properties["opacity"])
            parent = parent.parent
        }
        return opacity.coerceIn(0.0, 1.0)
    }

    private fun isHidden(box: LayoutBox): Boolean {
        if (box.style.properties["visibility"]?.trim()?.lowercase(Locale.ROOT) == "hidden") return true
        var parent = box.element?.parent
        while (parent != null) {
            val ancestor = layout.boxForNodeId(parent.nodeId)
            if (ancestor?.style?.properties?.get("visibility")?.trim()?.lowercase(Locale.ROOT) == "hidden") return true
            parent = parent.parent
        }
        return false
    }

    private fun effectiveClip(box: LayoutBox): LayoutRect? {
        var clip = box.clipRect
        var parent = box.element?.parent
        while (parent != null) {
            val ancestor = layout.boxForNodeId(parent.nodeId)
            val ancestorClip = ancestor?.clipRect
            if (ancestorClip != null) clip = clip?.intersection(ancestorClip) ?: ancestorClip
            parent = parent.parent
        }
        return clip
    }

    private fun add(command: PaintCommand) {
        if (commands.size < limits.maxCommands) commands += command
    }

    private fun resolveImageSource(raw: String, documentUrl: String?): String {
        val source = raw.trim()
        if (source.isEmpty() || source.startsWith("data:", true) || source.startsWith("asset://", true) || source.startsWith("file:", true)) return source
        if (source.startsWith("http://", true) || source.startsWith("https://", true)) return source
        return runCatching { documentUrl?.let { URI(it).resolve(source).toASCIIString() } ?: source }.getOrDefault(source)
    }

    private fun issue(code: String, message: String, nodeId: Long?, severity: PaintIssueSeverity = PaintIssueSeverity.WARNING) {
        issues += PaintIssue(code, message, nodeId, severity)
    }

    companion object {
        private val FORM_CONTROLS = setOf("input", "textarea", "button", "select")
    }
}
