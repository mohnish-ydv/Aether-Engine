package com.mohnishraj.aether.core.css

import com.mohnishraj.aether.core.html.dom.ElementNode

data class CssSpan(val start: Int, val endExclusive: Int) {
    init { require(start >= 0 && endExclusive >= start) }
    val length: Int get() = endExclusive - start
    companion object { val EMPTY = CssSpan(0, 0) }
}

enum class CssIssueSeverity { WARNING, ERROR }

data class CssIssue(
    val code: String,
    val message: String,
    val span: CssSpan = CssSpan.EMPTY,
    val severity: CssIssueSeverity = CssIssueSeverity.WARNING
)

data class CssLimits(
    val maxInputChars: Int = 1_000_000,
    val maxTokens: Int = 200_000,
    val maxRules: Int = 20_000,
    val maxDeclarationsPerRule: Int = 1_000,
    val maxSelectorLength: Int = 8_192,
    val maxNestingDepth: Int = 32,
    val maxVariableDepth: Int = 32
)

enum class CssOrigin { USER_AGENT, AUTHOR, INLINE }
enum class ColorScheme { LIGHT, DARK }

data class MediaEnvironment(
    val widthPx: Double = 360.0,
    val heightPx: Double = 800.0,
    val mediaType: String = "screen",
    val colorScheme: ColorScheme = ColorScheme.LIGHT,
    val rootFontSizePx: Double = 16.0
) {
    init {
        require(widthPx >= 0.0 && heightPx >= 0.0)
        require(rootFontSizePx > 0.0)
    }
}

data class FontFace(
    val family: String,
    val source: String?,
    val style: String = "normal",
    val weight: String = "normal",
    val display: String = "auto",
    val descriptors: Map<String, String>
)

data class ComputedStyle(
    val properties: Map<String, String>,
    val customProperties: Map<String, String>,
    val matchedRuleCount: Int
) {
    operator fun get(property: String): String? = properties[property.lowercase()]
}

data class StyledElement(val element: ElementNode, val style: ComputedStyle)

class StyleTree internal constructor(
    private val stylesByNodeId: Map<Long, ComputedStyle>,
    val fontFaces: List<FontFace>,
    val issues: List<CssIssue>
) {
    fun styleFor(element: ElementNode): ComputedStyle? = stylesByNodeId[element.nodeId]
    val size: Int get() = stylesByNodeId.size
}
