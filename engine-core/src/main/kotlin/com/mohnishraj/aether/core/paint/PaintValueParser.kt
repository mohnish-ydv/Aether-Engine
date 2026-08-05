package com.mohnishraj.aether.core.paint

import com.mohnishraj.aether.core.layout.LayoutEdges
import com.mohnishraj.aether.core.layout.LayoutRect
import java.util.Locale
import kotlin.math.roundToInt

object PaintValueParser {
    private val BORDER_STYLES = setOf("none", "hidden", "solid", "dashed", "dotted", "double", "groove", "ridge", "inset", "outset")
    private val named = mapOf(
        "transparent" to PaintColor.TRANSPARENT,
        "black" to PaintColor.BLACK,
        "white" to PaintColor.WHITE,
        "red" to PaintColor(255, 0, 0),
        "green" to PaintColor(0, 128, 0),
        "blue" to PaintColor(0, 0, 255),
        "yellow" to PaintColor(255, 255, 0),
        "gray" to PaintColor(128, 128, 128),
        "grey" to PaintColor(128, 128, 128),
        "silver" to PaintColor(192, 192, 192),
        "maroon" to PaintColor(128, 0, 0),
        "purple" to PaintColor(128, 0, 128),
        "fuchsia" to PaintColor(255, 0, 255),
        "lime" to PaintColor(0, 255, 0),
        "olive" to PaintColor(128, 128, 0),
        "navy" to PaintColor(0, 0, 128),
        "teal" to PaintColor(0, 128, 128),
        "aqua" to PaintColor(0, 255, 255),
        "orange" to PaintColor(255, 165, 0),
        "canvas" to PaintColor.WHITE,
        "canvastext" to PaintColor.BLACK,
        "buttonface" to PaintColor(239, 239, 239),
        "buttontext" to PaintColor.BLACK,
        "field" to PaintColor.WHITE,
        "fieldtext" to PaintColor.BLACK,
        "graytext" to PaintColor(109, 109, 109),
        "linktext" to PaintColor(0, 0, 238)
    )

    fun color(raw: String?, currentColor: PaintColor = PaintColor.BLACK): PaintColor? {
        val value = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
        if (value == "currentcolor") return currentColor
        named[value]?.let { return it }
        if (value.startsWith("#")) return parseHex(value)
        if (value.startsWith("rgb(") || value.startsWith("rgba(")) return parseRgb(value)
        return null
    }

    private fun parseHex(value: String): PaintColor? = runCatching {
        when (val hex = value.drop(1)) {
            else -> when (hex.length) {
                3 -> PaintColor(hex[0].digitToInt(16) * 17, hex[1].digitToInt(16) * 17, hex[2].digitToInt(16) * 17)
                4 -> PaintColor(hex[0].digitToInt(16) * 17, hex[1].digitToInt(16) * 17, hex[2].digitToInt(16) * 17, hex[3].digitToInt(16) * 17)
                6 -> PaintColor(hex.substring(0, 2).toInt(16), hex.substring(2, 4).toInt(16), hex.substring(4, 6).toInt(16))
                8 -> PaintColor(hex.substring(0, 2).toInt(16), hex.substring(2, 4).toInt(16), hex.substring(4, 6).toInt(16), hex.substring(6, 8).toInt(16))
                else -> null
            }
        }
    }.getOrNull()

    private fun parseRgb(value: String): PaintColor? {
        val body = value.substringAfter('(').substringBeforeLast(')', missingDelimiterValue = "")
        if (body.isEmpty()) return null
        val parts = body.replace('/', ',').split(',').map(String::trim).filter(String::isNotEmpty)
        if (parts.size !in 3..4) return null
        fun channel(raw: String): Int? = if (raw.endsWith('%')) {
            raw.dropLast(1).toDoubleOrNull()?.let { (it.coerceIn(0.0, 100.0) * 255.0 / 100.0).roundToInt() }
        } else raw.toDoubleOrNull()?.roundToInt()?.coerceIn(0, 255)
        fun alpha(raw: String): Int? = if (raw.endsWith('%')) {
            raw.dropLast(1).toDoubleOrNull()?.let { (it.coerceIn(0.0, 100.0) * 255.0 / 100.0).roundToInt() }
        } else raw.toDoubleOrNull()?.let { (it.coerceIn(0.0, 1.0) * 255.0).roundToInt() }
        val r = channel(parts[0]) ?: return null
        val g = channel(parts[1]) ?: return null
        val b = channel(parts[2]) ?: return null
        val a = if (parts.size == 4) alpha(parts[3]) ?: return null else 255
        return PaintColor(r, g, b, a)
    }

    fun opacity(raw: String?): Double = raw?.trim()?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 1.0

    fun radii(raw: String?, rect: LayoutRect): CornerRadii {
        if (raw.isNullOrBlank()) return CornerRadii()
        val beforeSlash = raw.substringBefore('/')
        val values = beforeSlash.split(Regex("\\s+")).mapNotNull { lengthPx(it, minOf(rect.width, rect.height)) }
        val expanded = expandFour(values) ?: return CornerRadii()
        return CornerRadii(
            expanded[0].coerceAtLeast(0.0),
            expanded[1].coerceAtLeast(0.0),
            expanded[2].coerceAtLeast(0.0),
            expanded[3].coerceAtLeast(0.0)
        ).clamped(rect)
    }

    fun border(boxBorder: LayoutEdges, properties: Map<String, String>, rect: LayoutRect, currentColor: PaintColor): BorderPaint {
        val shorthand = properties["border"].orEmpty()
        val shorthandTokens = splitWhitespacePreservingFunctions(shorthand)
        val shorthandColor = shorthandTokens.firstNotNullOfOrNull { color(it, currentColor) }
        val shorthandStyle = shorthandTokens.firstOrNull { it.lowercase(Locale.ROOT) in BORDER_STYLES }?.lowercase(Locale.ROOT)
        val defaultColor = color(properties["border-color"], currentColor) ?: shorthandColor ?: currentColor
        val colors = listOf("top", "right", "bottom", "left").map { side ->
            val sideShorthand = splitWhitespacePreservingFunctions(properties["border-$side"].orEmpty())
            color(properties["border-$side-color"], currentColor)
                ?: sideShorthand.firstNotNullOfOrNull { color(it, currentColor) }
                ?: defaultColor
        }
        val defaultStyle = properties["border-style"]?.trim()?.lowercase(Locale.ROOT) ?: shorthandStyle ?: "solid"
        val styles = listOf("top", "right", "bottom", "left").map { side ->
            val sideShorthand = splitWhitespacePreservingFunctions(properties["border-$side"].orEmpty())
            properties["border-$side-style"]?.trim()?.lowercase(Locale.ROOT)
                ?: sideShorthand.firstOrNull { it.lowercase(Locale.ROOT) in BORDER_STYLES }?.lowercase(Locale.ROOT)
                ?: defaultStyle
        }
        return BorderPaint(boxBorder, colors, styles, radii(properties["border-radius"], rect))
    }

    fun shadows(raw: String?, currentColor: PaintColor, max: Int): List<BoxShadowPaint> {
        if (raw.isNullOrBlank() || raw.trim().equals("none", ignoreCase = true)) return emptyList()
        return splitTopLevel(raw, ',').take(max).mapNotNull { parseShadow(it, currentColor) }
    }

    private fun parseShadow(raw: String, currentColor: PaintColor): BoxShadowPaint? {
        val tokens = splitWhitespacePreservingFunctions(raw)
        var inset = false
        var parsedColor: PaintColor? = null
        val lengths = ArrayList<Double>()
        for (token in tokens) {
            if (token.equals("inset", ignoreCase = true)) {
                inset = true
            } else {
                val color = color(token, currentColor)
                if (color != null) parsedColor = color
                else lengthPx(token, 0.0)?.let(lengths::add)
            }
        }
        if (lengths.size < 2) return null
        return BoxShadowPaint(
            offsetX = lengths[0],
            offsetY = lengths[1],
            blurRadius = lengths.getOrElse(2) { 0.0 }.coerceAtLeast(0.0),
            spreadRadius = lengths.getOrElse(3) { 0.0 },
            color = parsedColor ?: PaintColor(0, 0, 0, 85),
            inset = inset
        )
    }

    fun linearGradient(raw: String?, currentColor: PaintColor): Triple<PaintColor, PaintColor, Double>? {
        val value = raw?.trim() ?: return null
        if (!value.startsWith("linear-gradient(", ignoreCase = true) || !value.endsWith(')')) return null
        val args = splitTopLevel(value.substringAfter('(').dropLast(1), ',').map(String::trim)
        if (args.size < 2) return null
        var index = 0
        var angle = 180.0
        if (args[0].endsWith("deg", ignoreCase = true)) {
            angle = args[0].dropLast(3).trim().toDoubleOrNull() ?: 180.0
            index = 1
        } else if (args[0].startsWith("to ", ignoreCase = true)) {
            angle = when (args[0].lowercase(Locale.ROOT)) {
                "to right" -> 90.0
                "to bottom" -> 180.0
                "to left" -> 270.0
                "to top" -> 0.0
                else -> 180.0
            }
            index = 1
        }
        if (args.size - index < 2) return null
        val first = color(args[index].substringBefore(' '), currentColor) ?: return null
        val last = color(args.last().substringBefore(' '), currentColor) ?: return null
        return Triple(first, last, angle)
    }

    fun imageFit(raw: String?): ImageFit = when (raw?.trim()?.lowercase(Locale.ROOT)) {
        "contain" -> ImageFit.CONTAIN
        "cover" -> ImageFit.COVER
        "none" -> ImageFit.NONE
        "scale-down" -> ImageFit.SCALE_DOWN
        else -> ImageFit.FILL
    }

    fun imagePosition(raw: String?): ImagePosition {
        val tokens = raw?.trim()?.lowercase(Locale.ROOT)
            ?.split(Regex("\\s+"))
            ?.filter(String::isNotBlank)
            .orEmpty()
        if (tokens.isEmpty()) return ImagePosition()

        var horizontal: Double? = null
        var vertical: Double? = null
        fun fraction(token: String): Double? = when (token) {
            "left", "top" -> 0.0
            "center" -> 0.5
            "right", "bottom" -> 1.0
            else -> token.removeSuffix("%").toDoubleOrNull()
                ?.takeIf { token.endsWith('%') }
                ?.div(100.0)
                ?.coerceIn(0.0, 1.0)
        }

        for (token in tokens.take(4)) {
            when (token) {
                "left", "right" -> if (horizontal == null) horizontal = fraction(token)
                "top", "bottom" -> if (vertical == null) vertical = fraction(token)
                "center" -> if (horizontal == null) horizontal = 0.5 else if (vertical == null) vertical = 0.5
                else -> {
                    val parsed = fraction(token) ?: continue
                    if (horizontal == null) horizontal = parsed else if (vertical == null) vertical = parsed
                }
            }
        }
        return ImagePosition(horizontal ?: 0.5, vertical ?: 0.5)
    }

    fun fontWeight(raw: String?): Int = when (raw?.trim()?.lowercase(Locale.ROOT)) {
        "normal", null -> 400
        "bold", "bolder" -> 700
        "lighter" -> 300
        else -> raw.toIntOrNull()?.coerceIn(1, 1000) ?: 400
    }

    private fun lengthPx(raw: String, percentageBase: Double): Double? {
        val value = raw.trim().lowercase(Locale.ROOT)
        return when {
            value.endsWith("px") -> value.dropLast(2).toDoubleOrNull()
            value.endsWith('%') -> value.dropLast(1).toDoubleOrNull()?.let { percentageBase * it / 100.0 }
            value == "0" -> 0.0
            else -> null
        }
    }

    private fun expandFour(values: List<Double>): List<Double>? = when (values.size) {
        1 -> listOf(values[0], values[0], values[0], values[0])
        2 -> listOf(values[0], values[1], values[0], values[1])
        3 -> listOf(values[0], values[1], values[2], values[1])
        4 -> values
        else -> null
    }

    internal fun splitTopLevel(value: String, delimiter: Char): List<String> {
        val result = ArrayList<String>()
        var depth = 0
        var quote: Char? = null
        var start = 0
        value.forEachIndexed { index, char ->
            if (quote != null) {
                if (char == quote && (index == 0 || value[index - 1] != '\\')) quote = null
            } else when (char) {
                '\'', '"' -> quote = char
                '(' -> depth++
                ')' -> if (depth > 0) depth--
                delimiter -> if (depth == 0) { result += value.substring(start, index).trim(); start = index + 1 }
            }
        }
        result += value.substring(start).trim()
        return result.filter(String::isNotEmpty)
    }

    private fun splitWhitespacePreservingFunctions(value: String): List<String> {
        val result = ArrayList<String>()
        var depth = 0
        var quote: Char? = null
        var start = 0
        value.forEachIndexed { index, char ->
            if (quote != null) {
                if (char == quote && (index == 0 || value[index - 1] != '\\')) quote = null
            } else when (char) {
                '\'', '"' -> quote = char
                '(' -> depth++
                ')' -> if (depth > 0) depth--
                ' ', '\t', '\n', '\r' -> if (depth == 0) {
                    if (start < index) result += value.substring(start, index)
                    start = index + 1
                }
            }
        }
        if (start < value.length) result += value.substring(start)
        return result.filter(String::isNotBlank)
    }
}
