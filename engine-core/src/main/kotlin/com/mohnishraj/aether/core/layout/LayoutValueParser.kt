package com.mohnishraj.aether.core.layout

import com.mohnishraj.aether.core.css.ComputedStyle
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class LengthContext(
    val referencePx: Double,
    val fontSizePx: Double,
    val rootFontSizePx: Double,
    val viewport: LayoutViewport
)

internal data class ResolvedBoxModel(
    val margin: LayoutEdges,
    val marginAutoTop: Boolean,
    val marginAutoRight: Boolean,
    val marginAutoBottom: Boolean,
    val marginAutoLeft: Boolean,
    val border: LayoutEdges,
    val padding: LayoutEdges,
    val requestedWidth: Double?,
    val requestedHeight: Double?,
    val minWidth: Double?,
    val maxWidth: Double?,
    val minHeight: Double?,
    val maxHeight: Double?,
    val boxSizingBorderBox: Boolean,
    val fontSizePx: Double,
    val lineHeightPx: Double
)

object LayoutValueParser {
    private val NUMBER_UNIT = Regex("^([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))(px|%|em|rem|ex|ch|vw|vh|svw|svh|lvw|lvh|dvw|dvh|vmin|vmax|pt|pc|in|cm|mm|q)?$", RegexOption.IGNORE_CASE)

    fun resolveLength(value: String?, context: LengthContext, allowNegative: Boolean = true): Double? {
        val raw = value?.trim()?.lowercase(Locale.ROOT) ?: return null
        if (raw.isEmpty() || raw in setOf("auto", "none", "normal", "initial", "inherit", "unset", "max-content", "min-content", "fit-content")) return null
        val resolved = when {
            raw.startsWith("calc(") && raw.endsWith(')') -> resolveCalc(raw.substring(5, raw.length - 1), context)
            raw.startsWith("min(") && raw.endsWith(')') -> resolveFunctionArguments(raw.substring(4, raw.length - 1), context).minOrNull()
            raw.startsWith("max(") && raw.endsWith(')') -> resolveFunctionArguments(raw.substring(4, raw.length - 1), context).maxOrNull()
            raw.startsWith("clamp(") && raw.endsWith(')') -> resolveClamp(raw.substring(6, raw.length - 1), context)
            raw.startsWith("fit-content(") && raw.endsWith(')') -> resolveLength(raw.substring(12, raw.length - 1), context, allowNegative)
            raw.startsWith("env(") && raw.endsWith(')') -> 0.0
            raw == "thin" -> 1.0
            raw == "medium" -> 3.0
            raw == "thick" -> 5.0
            else -> resolveSimple(raw, context)
        } ?: return null
        if (!resolved.isFinite()) return null
        return if (allowNegative) resolved else max(0.0, resolved)
    }

    fun resolveFontSize(style: ComputedStyle, parentFontSizePx: Double, viewport: LayoutViewport): Double {
        val value = style["font-size"]?.trim()?.lowercase(Locale.ROOT) ?: return parentFontSizePx
        val keyword = when (value) {
            "xx-small" -> 9.0
            "x-small" -> 10.0
            "small" -> 13.0
            "medium" -> 16.0
            "large" -> 18.0
            "x-large" -> 24.0
            "xx-large" -> 32.0
            "smaller" -> parentFontSizePx * 0.8
            "larger" -> parentFontSizePx * 1.2
            else -> null
        }
        if (keyword != null) return keyword.coerceIn(1.0, 4096.0)
        return resolveLength(
            value,
            LengthContext(parentFontSizePx, parentFontSizePx, viewport.rootFontSizePx, viewport),
            allowNegative = false
        )?.coerceIn(1.0, 4096.0) ?: parentFontSizePx
    }

    fun resolveLineHeight(style: ComputedStyle, fontSizePx: Double, viewport: LayoutViewport): Double {
        val raw = style["line-height"]?.trim()?.lowercase(Locale.ROOT) ?: "normal"
        if (raw == "normal") return fontSizePx * 1.2
        raw.toDoubleOrNull()?.let { multiplier ->
            if (multiplier.isFinite() && multiplier >= 0.0) return (fontSizePx * multiplier).coerceAtMost(100_000.0)
        }
        return resolveLength(raw, LengthContext(fontSizePx, fontSizePx, viewport.rootFontSizePx, viewport), allowNegative = false)
            ?.coerceAtLeast(0.0) ?: fontSizePx * 1.2
    }

    internal fun resolveBoxModel(
        style: ComputedStyle,
        containingWidthPx: Double,
        containingHeightPx: Double?,
        parentFontSizePx: Double,
        viewport: LayoutViewport
    ): ResolvedBoxModel {
        val fontSize = resolveFontSize(style, parentFontSizePx, viewport)
        val horizontalContext = LengthContext(containingWidthPx, fontSize, viewport.rootFontSizePx, viewport)
        val verticalReference = containingHeightPx ?: containingWidthPx
        val verticalContext = LengthContext(verticalReference, fontSize, viewport.rootFontSizePx, viewport)
        val marginRaw = resolveEdgeTokens(style, "margin")
        val paddingRaw = resolveEdgeTokens(style, "padding")
        val borderRaw = resolveBorderTokens(style)

        fun edgeValue(raw: String, vertical: Boolean, allowNegative: Boolean): Double =
            resolveLength(raw, if (vertical) verticalContext else horizontalContext, allowNegative) ?: 0.0

        val margin = LayoutEdges(
            top = edgeValue(marginRaw.top, true, true),
            right = edgeValue(marginRaw.right, false, true),
            bottom = edgeValue(marginRaw.bottom, true, true),
            left = edgeValue(marginRaw.left, false, true)
        )
        val padding = LayoutEdges(
            top = edgeValue(paddingRaw.top, true, false),
            right = edgeValue(paddingRaw.right, false, false),
            bottom = edgeValue(paddingRaw.bottom, true, false),
            left = edgeValue(paddingRaw.left, false, false)
        )
        val border = LayoutEdges(
            top = edgeValue(borderRaw.top, true, false),
            right = edgeValue(borderRaw.right, false, false),
            bottom = edgeValue(borderRaw.bottom, true, false),
            left = edgeValue(borderRaw.left, false, false)
        )
        val width = resolveLength(style["width"], horizontalContext, allowNegative = false)
        val height = containingHeightPx?.let { resolveLength(style["height"], verticalContext, allowNegative = false) }
            ?: resolveLength(style["height"]?.takeUnless { it.trim().endsWith('%') }, verticalContext, allowNegative = false)
        val minWidth = resolveLength(style["min-width"], horizontalContext, allowNegative = false)
        val maxWidth = style["max-width"]?.takeUnless { it.trim().equals("none", true) }
            ?.let { resolveLength(it, horizontalContext, allowNegative = false) }
        val minHeight = containingHeightPx?.let { resolveLength(style["min-height"], verticalContext, allowNegative = false) }
            ?: resolveLength(style["min-height"]?.takeUnless { it.trim().endsWith('%') }, verticalContext, allowNegative = false)
        val maxHeight = style["max-height"]?.takeUnless { it.trim().equals("none", true) }
            ?.let { candidate ->
                if (containingHeightPx == null && candidate.trim().endsWith('%')) null
                else resolveLength(candidate, verticalContext, allowNegative = false)
            }
        return ResolvedBoxModel(
            margin = margin,
            marginAutoTop = marginRaw.top.equals("auto", true),
            marginAutoRight = marginRaw.right.equals("auto", true),
            marginAutoBottom = marginRaw.bottom.equals("auto", true),
            marginAutoLeft = marginRaw.left.equals("auto", true),
            border = border,
            padding = padding,
            requestedWidth = width,
            requestedHeight = height,
            minWidth = minWidth,
            maxWidth = maxWidth,
            minHeight = minHeight,
            maxHeight = maxHeight,
            boxSizingBorderBox = style["box-sizing"].equals("border-box", true),
            fontSizePx = fontSize,
            lineHeightPx = resolveLineHeight(style, fontSize, viewport)
        )
    }


    fun resolveAspectRatio(style: ComputedStyle): Double? {
        val raw = style["aspect-ratio"]?.trim()?.lowercase(Locale.ROOT) ?: return null
        if (raw in setOf("auto", "initial", "inherit", "unset")) return null
        val selected = raw.substringAfter("auto", raw).trim().ifEmpty { raw }
        val parts = selected.split('/').map(String::trim)
        val ratio = when (parts.size) {
            1 -> parts[0].toDoubleOrNull()
            2 -> {
                val width = parts[0].toDoubleOrNull()
                val height = parts[1].toDoubleOrNull()
                if (width != null && height != null && height != 0.0) width / height else null
            }
            else -> null
        }
        return ratio?.takeIf { it.isFinite() && it > 0.0 }
    }

    fun resolveGap(style: ComputedStyle, axis: String, context: LengthContext): Double {
        val shorthand = style["gap"]?.trim()?.split(Regex("\\s+"), limit = 2).orEmpty()
        val value = when (axis) {
            "row" -> style["row-gap"] ?: shorthand.getOrNull(0)
            else -> style["column-gap"] ?: shorthand.getOrNull(1) ?: shorthand.getOrNull(0)
        }
        return resolveLength(value, context, allowNegative = false) ?: 0.0
    }

    fun resolvePosition(style: ComputedStyle): PositionScheme = when (style["position"]?.trim()?.lowercase(Locale.ROOT)) {
        "relative" -> PositionScheme.RELATIVE
        "absolute" -> PositionScheme.ABSOLUTE
        "fixed" -> PositionScheme.FIXED
        "sticky", "-webkit-sticky" -> PositionScheme.STICKY
        else -> PositionScheme.STATIC
    }

    fun resolveOverflow(style: ComputedStyle): Pair<OverflowMode, OverflowMode> {
        val shorthand = style["overflow"]?.trim()?.split(Regex("\\s+"), limit = 2).orEmpty()
        val x = style["overflow-x"] ?: shorthand.getOrNull(0) ?: "visible"
        val y = style["overflow-y"] ?: shorthand.getOrNull(1) ?: shorthand.getOrNull(0) ?: "visible"
        return parseOverflow(x) to parseOverflow(y)
    }

    fun resolveZIndex(style: ComputedStyle, position: PositionScheme): Int {
        if (position == PositionScheme.STATIC) return 0
        val raw = style["z-index"]?.trim()?.lowercase(Locale.ROOT) ?: return 0
        if (raw == "auto") return 0
        return raw.toDoubleOrNull()?.toInt()?.coerceIn(-1_000_000, 1_000_000) ?: 0
    }

    fun resolveOffset(
        style: ComputedStyle,
        name: String,
        referencePx: Double,
        fontSizePx: Double,
        viewport: LayoutViewport
    ): Double? = resolveLength(style[name], LengthContext(referencePx, fontSizePx, viewport.rootFontSizePx, viewport), allowNegative = true)

    fun collapseMargins(first: Double, second: Double): Double = when {
        first >= 0.0 && second >= 0.0 -> max(first, second)
        first <= 0.0 && second <= 0.0 -> min(first, second)
        else -> first + second
    }

    internal fun splitCssWhitespace(value: String): List<String> {
        val result = ArrayList<String>()
        val current = StringBuilder()
        var depth = 0
        var quote: Char? = null
        value.forEach { character ->
            when {
                quote != null -> {
                    current.append(character)
                    if (character == quote) quote = null
                }
                character == '\'' || character == '"' -> {
                    quote = character
                    current.append(character)
                }
                character == '(' -> {
                    depth++
                    current.append(character)
                }
                character == ')' -> {
                    depth = max(0, depth - 1)
                    current.append(character)
                }
                character.isWhitespace() && depth == 0 -> {
                    if (current.isNotEmpty()) {
                        result += current.toString()
                        current.clear()
                    }
                }
                else -> current.append(character)
            }
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }

    private fun resolveSimple(raw: String, context: LengthContext): Double? {
        val match = NUMBER_UNIT.matchEntire(raw) ?: return null
        val number = match.groupValues[1].toDoubleOrNull() ?: return null
        return when (match.groupValues[2].lowercase(Locale.ROOT)) {
            "", "px" -> number
            "%" -> context.referencePx * number / 100.0
            "em" -> context.fontSizePx * number
            "rem" -> context.rootFontSizePx * number
            "ex" -> context.fontSizePx * 0.5 * number
            "ch" -> context.fontSizePx * 0.56 * number
            "vw", "svw", "lvw", "dvw" -> context.viewport.widthPx * number / 100.0
            "vh", "svh", "lvh", "dvh" -> context.viewport.heightPx * number / 100.0
            "vmin" -> min(context.viewport.widthPx, context.viewport.heightPx) * number / 100.0
            "vmax" -> max(context.viewport.widthPx, context.viewport.heightPx) * number / 100.0
            "pt" -> number * 96.0 / 72.0
            "pc" -> number * 16.0
            "in" -> number * 96.0
            "cm" -> number * 96.0 / 2.54
            "mm" -> number * 96.0 / 25.4
            "q" -> number * 96.0 / 101.6
            else -> null
        }
    }


    private fun resolveFunctionArguments(expression: String, context: LengthContext): List<Double> =
        splitTopLevelCommas(expression).mapNotNull { resolveLength(it, context, allowNegative = true) }

    private fun resolveClamp(expression: String, context: LengthContext): Double? {
        val values = splitTopLevelCommas(expression).map { resolveLength(it, context, allowNegative = true) ?: return null }
        if (values.size != 3) return null
        return values[1].coerceIn(min(values[0], values[2]), max(values[0], values[2]))
    }

    private fun splitTopLevelCommas(value: String): List<String> {
        val output = ArrayList<String>()
        var start = 0
        var depth = 0
        var quote: Char? = null
        value.forEachIndexed { index, character ->
            if (quote != null) {
                if (character == quote && (index == 0 || value[index - 1] != '\\')) quote = null
            } else when (character) {
                '\'', '"' -> quote = character
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 0) { output += value.substring(start, index).trim(); start = index + 1 }
            }
        }
        output += value.substring(start).trim()
        return output.filter(String::isNotEmpty)
    }

    private fun resolveCalc(expression: String, context: LengthContext): Double? {
        var total = 0.0
        var sign = 1.0
        var index = 0
        var sawTerm = false
        while (index < expression.length) {
            while (index < expression.length && expression[index].isWhitespace()) index++
            if (index >= expression.length) break
            when (expression[index]) {
                '+' -> { sign = 1.0; index++; continue }
                '-' -> { sign = -1.0; index++; continue }
            }
            val start = index
            var depth = 0
            while (index < expression.length) {
                val character = expression[index]
                if (character == '(') depth++
                if (character == ')') depth--
                if (depth == 0 && (character == '+' || character == '-')) break
                index++
            }
            val token = expression.substring(start, index).trim()
            val resolved = resolveSimple(token, context) ?: return null
            total += sign * resolved
            sign = 1.0
            sawTerm = true
        }
        return total.takeIf { sawTerm }
    }

    private fun parseOverflow(value: String): OverflowMode = when (value.trim().lowercase(Locale.ROOT)) {
        "hidden" -> OverflowMode.HIDDEN
        "clip" -> OverflowMode.CLIP
        "scroll" -> OverflowMode.SCROLL
        "auto" -> OverflowMode.AUTO
        else -> OverflowMode.VISIBLE
    }

    private fun resolveEdgeTokens(style: ComputedStyle, prefix: String): RawEdges {
        val shorthand = expandFour(style[prefix])
        return RawEdges(
            top = style["$prefix-top"] ?: shorthand.top,
            right = style["$prefix-right"] ?: shorthand.right,
            bottom = style["$prefix-bottom"] ?: shorthand.bottom,
            left = style["$prefix-left"] ?: shorthand.left
        )
    }

    private fun resolveBorderTokens(style: ComputedStyle): RawEdges {
        val widthShorthand = expandFour(style["border-width"])
        val borderAll = firstBorderWidth(style["border"])
        fun side(side: String, fallback: String): String =
            style["border-$side-width"] ?: firstBorderWidth(style["border-$side"]) ?: fallback
        return RawEdges(
            top = side("top", widthShorthand.top.takeUnless { it == "0" } ?: borderAll ?: "0"),
            right = side("right", widthShorthand.right.takeUnless { it == "0" } ?: borderAll ?: "0"),
            bottom = side("bottom", widthShorthand.bottom.takeUnless { it == "0" } ?: borderAll ?: "0"),
            left = side("left", widthShorthand.left.takeUnless { it == "0" } ?: borderAll ?: "0")
        )
    }

    private fun firstBorderWidth(value: String?): String? = value?.let(::splitCssWhitespace)?.firstOrNull { token ->
        token.lowercase(Locale.ROOT) in setOf("thin", "medium", "thick") || NUMBER_UNIT.matches(token.lowercase(Locale.ROOT))
    }

    private fun expandFour(value: String?): RawEdges {
        val tokens = value?.let(::splitCssWhitespace).orEmpty()
        return when (tokens.size) {
            1 -> RawEdges(tokens[0], tokens[0], tokens[0], tokens[0])
            2 -> RawEdges(tokens[0], tokens[1], tokens[0], tokens[1])
            3 -> RawEdges(tokens[0], tokens[1], tokens[2], tokens[1])
            in 4..Int.MAX_VALUE -> RawEdges(tokens[0], tokens[1], tokens[2], tokens[3])
            else -> RawEdges("0", "0", "0", "0")
        }
    }

    private data class RawEdges(val top: String, val right: String, val bottom: String, val left: String)
}
