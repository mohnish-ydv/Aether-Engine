package com.mohnishraj.aether.core.css.cascade

import com.mohnishraj.aether.core.css.ComputedStyle
import com.mohnishraj.aether.core.css.CssIssue
import com.mohnishraj.aether.core.css.CssLimits
import com.mohnishraj.aether.core.css.CssOrigin
import com.mohnishraj.aether.core.css.FontFace
import com.mohnishraj.aether.core.css.MediaEnvironment
import com.mohnishraj.aether.core.css.StyleTree
import com.mohnishraj.aether.core.css.parser.CssDeclaration
import com.mohnishraj.aether.core.css.parser.CssParser
import com.mohnishraj.aether.core.css.parser.CssRule
import com.mohnishraj.aether.core.css.parser.CssStyleSheet
import com.mohnishraj.aether.core.css.parser.FontFaceRule
import com.mohnishraj.aether.core.css.parser.MediaRule
import com.mohnishraj.aether.core.css.parser.StyleRule
import com.mohnishraj.aether.core.css.parser.SupportsRule
import com.mohnishraj.aether.core.css.selector.Specificity
import com.mohnishraj.aether.core.html.dom.DocumentNode
import com.mohnishraj.aether.core.html.dom.ElementNode
import java.util.Locale

/** Standards-oriented cascade with declaration-time shorthand expansion. */
class CascadeEngine(private val limits: CssLimits = CssLimits()) {
    private val declarationParser = CssParser(limits)
    private val variableResolver = CssVariableResolver(limits)

    fun compute(
        document: DocumentNode,
        styleSheets: List<CssStyleSheet>,
        environment: MediaEnvironment = MediaEnvironment()
    ): StyleTree {
        val issues = styleSheets.flatMapTo(mutableListOf()) { it.issues }
        val activeRules = ArrayList<ActiveStyleRule>()
        val fontFaces = ArrayList<FontFace>()
        styleSheets.forEachIndexed { sheetIndex, sheet ->
            collectRules(sheet.rules, sheet.origin, environment, activeRules, fontFaces, issues, sheetIndex * 1_000_000)
        }
        val styles = LinkedHashMap<Long, ComputedStyle>()
        document.documentElement?.let { computeElement(it, null, activeRules, styles, issues) }
        return StyleTree(styles, fontFaces, issues.toList())
    }

    private fun computeElement(
        element: ElementNode,
        parentStyle: ComputedStyle?,
        rules: List<ActiveStyleRule>,
        output: MutableMap<Long, ComputedStyle>,
        issues: MutableList<CssIssue>
    ) {
        val winners = LinkedHashMap<String, Candidate>()
        var matchedRules = 0
        rules.forEach { active ->
            val matchingSpecificity = active.rule.selectors.asSequence()
                .filter { it.matches(element) }
                .map { it.specificity }
                .maxOrNull()
            if (matchingSpecificity != null) {
                matchedRules++
                active.rule.declarations.forEach { declaration ->
                    consider(winners, declaration, active.origin, matchingSpecificity, active.baseOrder + declaration.sourceOrder)
                }
            }
        }
        element.getAttribute("style")?.let { inline ->
            declarationParser.parseDeclarations(inline, issues).forEach { declaration ->
                consider(winners, declaration, CssOrigin.INLINE, Specificity(ids = 1), Int.MAX_VALUE / 2 + declaration.sourceOrder)
            }
        }

        val allCandidate = winners["all"]
        if (allCandidate != null) {
            CASCADE_PROPERTIES.forEach { name ->
                val current = winners[name]
                if (current == null || allCandidate > current) winners[name] = allCandidate
            }
            winners.remove("all")
        }

        val custom = LinkedHashMap<String, String>()
        custom.putAll(parentStyle?.customProperties.orEmpty())
        winners.filterKeys { it.startsWith("--") }.forEach { (name, candidate) ->
            when (candidate.value.trim().lowercase(Locale.ROOT)) {
                "initial", "revert", "revert-layer" -> custom.remove(name)
                "inherit", "unset" -> Unit
                else -> custom[name] = candidate.value
            }
        }

        val properties = LinkedHashMap<String, String>()
        INITIAL_VALUES.forEach { (name, value) -> properties[name] = value }
        properties["display"] = defaultDisplay(element.localName)
        INHERITED_PROPERTIES.forEach { name -> parentStyle?.properties?.get(name)?.let { properties[name] = it } }

        winners.filterKeys { !it.startsWith("--") }.forEach { (name, candidate) ->
            val lower = candidate.value.trim().lowercase(Locale.ROOT)
            val value = when (lower) {
                "inherit" -> parentStyle?.properties?.get(name) ?: initialValue(name, element)
                "initial", "revert", "revert-layer" -> initialValue(name, element)
                "unset" -> if (name in INHERITED_PROPERTIES) {
                    parentStyle?.properties?.get(name) ?: initialValue(name, element)
                } else initialValue(name, element)
                else -> variableResolver.resolve(candidate.value, custom, issues)
            }
            if (value != null) properties[name] = value
        }
        normalizeCompatibilityValues(properties)
        if (element.hasAttribute("hidden") || (element.localName == "input" && element.getAttribute("type").equals("hidden", true))) {
            properties["display"] = "none"
        }
        val color = properties["color"] ?: "canvastext"
        properties.keys.toList().forEach { name ->
            if (properties[name].equals("currentcolor", true)) properties[name] = color
        }
        val style = ComputedStyle(properties.toMap(), custom.toMap(), matchedRules)
        output[element.nodeId] = style
        element.children.filterIsInstance<ElementNode>().forEach { child -> computeElement(child, style, rules, output, issues) }
    }

    private fun consider(
        winners: MutableMap<String, Candidate>,
        declaration: CssDeclaration,
        origin: CssOrigin,
        specificity: Specificity,
        order: Int
    ) {
        expandDeclaration(declaration).forEachIndexed { expansionIndex, expanded ->
            val candidate = Candidate(expanded.value, declaration.important, origin, specificity, order.toLong() * 32L + expansionIndex)
            val current = winners[expanded.name]
            if (current == null || candidate > current) winners[expanded.name] = candidate
        }
    }

    private fun expandDeclaration(declaration: CssDeclaration): List<ExpandedDeclaration> {
        val name = canonicalProperty(declaration.name)
        val value = declaration.value
        fun one(property: String = name, candidate: String = value) = listOf(ExpandedDeclaration(property, candidate))
        fun four(prefix: String, suffix: String = ""): List<ExpandedDeclaration> {
            val values = expandFour(splitCssWhitespace(value)) ?: return one()
            val tail = if (suffix.isBlank()) "" else "-$suffix"
            return listOf("top", "right", "bottom", "left").mapIndexed { index, side ->
                ExpandedDeclaration("$prefix-$side$tail", values[index])
            }
        }
        fun pair(first: String, second: String): List<ExpandedDeclaration> {
            val values = splitCssWhitespace(value)
            if (values.isEmpty()) return one()
            return listOf(ExpandedDeclaration(first, values[0]), ExpandedDeclaration(second, values.getOrElse(1) { values[0] }))
        }

        return when (name) {
            "margin", "padding" -> four(name)
            "inset" -> {
                val values = expandFour(splitCssWhitespace(value)) ?: return one()
                listOf("top", "right", "bottom", "left").mapIndexed { index, side -> ExpandedDeclaration(side, values[index]) }
            }
            "margin-inline" -> pair("margin-left", "margin-right")
            "margin-block" -> pair("margin-top", "margin-bottom")
            "padding-inline" -> pair("padding-left", "padding-right")
            "padding-block" -> pair("padding-top", "padding-bottom")
            "inset-inline" -> pair("left", "right")
            "inset-block" -> pair("top", "bottom")
            "border-width" -> four("border", "width")
            "border-style" -> four("border", "style")
            "border-color" -> listOf(ExpandedDeclaration("border-color", value)) + four("border", "color")
            "border-radius" -> expandRadius(value)
            "border" -> expandBorder(value, listOf("top", "right", "bottom", "left"))
            "border-top", "border-right", "border-bottom", "border-left" -> expandBorder(value, listOf(name.removePrefix("border-")))
            "overflow" -> pair("overflow-x", "overflow-y")
            "gap" -> pair("row-gap", "column-gap")
            "place-content" -> pair("align-content", "justify-content")
            "place-items" -> pair("align-items", "justify-items")
            "place-self" -> pair("align-self", "justify-self")
            "flex-flow" -> expandFlexFlow(value)
            "flex" -> expandFlex(value)
            "background" -> expandBackground(value)
            "text-decoration" -> expandTextDecoration(value)
            "list-style" -> expandListStyle(value)
            else -> one()
        }
    }

    private fun canonicalProperty(name: String): String = when (name.lowercase(Locale.ROOT)) {
        "inline-size" -> "width"
        "block-size" -> "height"
        "min-inline-size" -> "min-width"
        "max-inline-size" -> "max-width"
        "min-block-size" -> "min-height"
        "max-block-size" -> "max-height"
        "-webkit-box-align" -> "align-items"
        "-webkit-box-pack" -> "justify-content"
        "-webkit-box-flex" -> "flex-grow"
        "-webkit-order" -> "order"
        else -> name.lowercase(Locale.ROOT)
    }

    private fun expandBorder(value: String, sides: List<String>): List<ExpandedDeclaration> {
        val tokens = splitCssWhitespace(value)
        val width = tokens.firstOrNull { it.lowercase(Locale.ROOT) in BORDER_WIDTH_KEYWORDS || LENGTH_TOKEN.matches(it) } ?: "medium"
        val style = tokens.firstOrNull { it.lowercase(Locale.ROOT) in BORDER_STYLE_KEYWORDS } ?: "none"
        val color = tokens.firstOrNull { it != width && it != style } ?: "currentcolor"
        return sides.flatMap { side ->
            listOf(
                ExpandedDeclaration("border-$side-width", width),
                ExpandedDeclaration("border-$side-style", style),
                ExpandedDeclaration("border-$side-color", color)
            )
        }
    }

    private fun expandRadius(value: String): List<ExpandedDeclaration> {
        val halves = splitTopLevelSlash(value)
        val horizontal = expandFour(splitCssWhitespace(halves.first)) ?: return listOf(ExpandedDeclaration("border-radius", value))
        val vertical = expandFour(splitCssWhitespace(halves.second ?: halves.first)) ?: horizontal
        val names = listOf("border-top-left-radius", "border-top-right-radius", "border-bottom-right-radius", "border-bottom-left-radius")
        return listOf(ExpandedDeclaration("border-radius", value)) + names.mapIndexed { index, name ->
            val candidate = if (horizontal[index] == vertical[index]) horizontal[index] else "${horizontal[index]} ${vertical[index]}"
            ExpandedDeclaration(name, candidate)
        }
    }

    private fun expandFlexFlow(value: String): List<ExpandedDeclaration> {
        val tokens = splitCssWhitespace(value).map { it.lowercase(Locale.ROOT) }
        val direction = tokens.firstOrNull { it in FLEX_DIRECTIONS } ?: "row"
        val wrap = tokens.firstOrNull { it in FLEX_WRAPS } ?: "nowrap"
        return listOf(ExpandedDeclaration("flex-direction", direction), ExpandedDeclaration("flex-wrap", wrap))
    }

    private fun expandFlex(value: String): List<ExpandedDeclaration> {
        val normalized = value.trim().lowercase(Locale.ROOT)
        val (grow, shrink, basis) = when (normalized) {
            "none" -> Triple("0", "0", "auto")
            "auto" -> Triple("1", "1", "auto")
            "initial" -> Triple("0", "1", "auto")
            else -> {
                val tokens = splitCssWhitespace(value)
                when (tokens.size) {
                    1 -> if (tokens[0].toDoubleOrNull() != null) Triple(tokens[0], "1", "0%") else Triple("1", "1", tokens[0])
                    2 -> if (tokens[1].toDoubleOrNull() != null) Triple(tokens[0], tokens[1], "0%") else Triple(tokens[0], "1", tokens[1])
                    else -> Triple(tokens.getOrElse(0) { "0" }, tokens.getOrElse(1) { "1" }, tokens.drop(2).joinToString(" ").ifBlank { "auto" })
                }
            }
        }
        return listOf(
            ExpandedDeclaration("flex-grow", grow),
            ExpandedDeclaration("flex-shrink", shrink),
            ExpandedDeclaration("flex-basis", basis)
        )
    }

    private fun expandBackground(value: String): List<ExpandedDeclaration> {
        val result = mutableListOf(ExpandedDeclaration("background", value))
        val tokens = splitCssWhitespace(value)
        tokens.firstOrNull { looksLikeColor(it) }?.let { result += ExpandedDeclaration("background-color", it) }
        tokens.firstOrNull { it.startsWith("url(", true) || it.contains("gradient(", true) || it.equals("none", true) }
            ?.let { result += ExpandedDeclaration("background-image", it) }
        return result
    }

    private fun expandTextDecoration(value: String): List<ExpandedDeclaration> {
        val tokens = splitCssWhitespace(value)
        val lines = tokens.filter { it.lowercase(Locale.ROOT) in setOf("none", "underline", "overline", "line-through", "blink") }
        val style = tokens.firstOrNull { it.lowercase(Locale.ROOT) in setOf("solid", "double", "dotted", "dashed", "wavy") }
        val color = tokens.firstOrNull { looksLikeColor(it) }
        return buildList {
            add(ExpandedDeclaration("text-decoration", value))
            if (lines.isNotEmpty()) add(ExpandedDeclaration("text-decoration-line", lines.joinToString(" ")))
            if (style != null) add(ExpandedDeclaration("text-decoration-style", style))
            if (color != null) add(ExpandedDeclaration("text-decoration-color", color))
        }
    }

    private fun expandListStyle(value: String): List<ExpandedDeclaration> {
        val tokens = splitCssWhitespace(value)
        val position = tokens.firstOrNull { it.lowercase(Locale.ROOT) in setOf("inside", "outside") }
        val image = tokens.firstOrNull { it.startsWith("url(", true) || it.equals("none", true) }
        val type = tokens.firstOrNull { it != position && it != image }
        return buildList {
            add(ExpandedDeclaration("list-style", value))
            if (position != null) add(ExpandedDeclaration("list-style-position", position))
            if (image != null) add(ExpandedDeclaration("list-style-image", image))
            if (type != null) add(ExpandedDeclaration("list-style-type", type))
        }
    }

    private fun normalizeCompatibilityValues(properties: MutableMap<String, String>) {
        properties["display"] = when (properties["display"]?.trim()?.lowercase(Locale.ROOT)) {
            "-webkit-box", "-webkit-flex" -> "flex"
            "-webkit-inline-box", "-webkit-inline-flex" -> "inline-flex"
            else -> properties["display"] ?: "inline"
        }
        properties["align-items"] = when (properties["align-items"]?.trim()?.lowercase(Locale.ROOT)) {
            "start" -> "flex-start"
            "end" -> "flex-end"
            else -> properties["align-items"] ?: "normal"
        }
        properties["justify-content"] = when (properties["justify-content"]?.trim()?.lowercase(Locale.ROOT)) {
            "start" -> "flex-start"
            "end" -> "flex-end"
            else -> properties["justify-content"] ?: "normal"
        }
        properties["-webkit-box-orient"]?.trim()?.lowercase(Locale.ROOT)?.let { orientation ->
            if (properties["flex-direction"].isNullOrBlank()) properties["flex-direction"] = if (orientation == "vertical") "column" else "row"
        }
    }

    private fun collectRules(
        rules: List<CssRule>,
        origin: CssOrigin,
        environment: MediaEnvironment,
        output: MutableList<ActiveStyleRule>,
        fonts: MutableList<FontFace>,
        issues: MutableList<CssIssue>,
        base: Int
    ) {
        rules.forEach { rule -> when (rule) {
            is StyleRule -> output += ActiveStyleRule(rule, origin, base + rule.sourceOrder * 10_000)
            is MediaRule -> if (MediaQueryEvaluator.matches(rule.query, environment)) collectRules(rule.rules, origin, environment, output, fonts, issues, base + rule.sourceOrder * 10_000)
            is SupportsRule -> if (supports(rule.condition)) collectRules(rule.rules, origin, environment, output, fonts, issues, base + rule.sourceOrder * 10_000)
            is FontFaceRule -> parseFontFace(rule, fonts, issues)
        } }
    }

    private fun parseFontFace(rule: FontFaceRule, fonts: MutableList<FontFace>, issues: MutableList<CssIssue>) {
        val descriptors = rule.declarations.associate { it.name to it.value }
        val family = descriptors["font-family"]?.trim('\'', '"', ' ')
        if (family.isNullOrBlank()) {
            issues += CssIssue("font-face-family", "@font-face requires font-family")
            return
        }
        fonts += FontFace(
            family = family,
            source = descriptors["src"],
            style = descriptors["font-style"] ?: "normal",
            weight = descriptors["font-weight"] ?: "normal",
            display = descriptors["font-display"] ?: "auto",
            descriptors = descriptors
        )
    }

    private fun supports(condition: String): Boolean {
        val normalized = condition.trim().lowercase(Locale.ROOT)
        if (normalized.startsWith("not ")) return !supports(normalized.removePrefix("not "))
        if (" and " in normalized) return normalized.split(" and ").all(::supports)
        if (" or " in normalized) return normalized.split(" or ").any(::supports)
        return Regex("^\\([a-z-]+\\s*:\\s*.+\\)$").matches(normalized)
    }

    private fun initialValue(name: String, element: ElementNode): String =
        if (name == "display") defaultDisplay(element.localName) else INITIAL_VALUES[name] ?: "initial"

    private fun defaultDisplay(tag: String): String = when (tag) {
        "html", "body", "main", "header", "footer", "section", "article", "aside", "nav", "div", "p", "ul", "ol", "form", "h1", "h2", "h3", "h4", "h5", "h6", "figure", "figcaption", "blockquote", "pre", "fieldset", "address", "dl", "dt", "dd", "details", "dialog" -> "block"
        "li", "summary" -> "list-item"
        "img", "svg", "video", "audio", "canvas", "iframe", "input", "button", "select", "textarea" -> "inline-block"
        "head", "title", "meta", "link", "style", "script", "template", "base", "source", "track" -> "none"
        "table" -> "table"
        "tbody" -> "table-row-group"
        "thead" -> "table-header-group"
        "tfoot" -> "table-footer-group"
        "tr" -> "table-row"
        "td", "th" -> "table-cell"
        "colgroup" -> "table-column-group"
        "col" -> "table-column"
        "caption" -> "table-caption"
        else -> "inline"
    }

    private data class ActiveStyleRule(val rule: StyleRule, val origin: CssOrigin, val baseOrder: Int)
    private data class ExpandedDeclaration(val name: String, val value: String)

    private data class Candidate(
        val value: String,
        val important: Boolean,
        val origin: CssOrigin,
        val specificity: Specificity,
        val order: Long
    ) : Comparable<Candidate> {
        override fun compareTo(other: Candidate): Int {
            if (important != other.important) return important.compareTo(other.important)
            val originComparison = originRank(origin, important).compareTo(originRank(other.origin, other.important))
            if (originComparison != 0) return originComparison
            val specificityComparison = specificity.compareTo(other.specificity)
            if (specificityComparison != 0) return specificityComparison
            return order.compareTo(other.order)
        }

        companion object {
            private fun originRank(origin: CssOrigin, important: Boolean): Int = if (important) {
                when (origin) {
                    CssOrigin.AUTHOR -> 1
                    CssOrigin.INLINE -> 2
                    CssOrigin.USER_AGENT -> 3
                }
            } else {
                when (origin) {
                    CssOrigin.USER_AGENT -> 0
                    CssOrigin.AUTHOR -> 1
                    CssOrigin.INLINE -> 2
                }
            }
        }
    }

    companion object {
        private val INHERITED_PROPERTIES = setOf(
            "color", "font-family", "font-size", "font-style", "font-weight", "font-variant", "font-stretch", "line-height",
            "text-align", "text-indent", "text-transform", "text-rendering", "text-shadow", "visibility", "white-space",
            "letter-spacing", "word-spacing", "word-break", "overflow-wrap", "hyphens", "direction", "cursor",
            "list-style-type", "list-style-position", "list-style-image", "quotes"
        )
        private val INITIAL_VALUES = linkedMapOf(
            "color" to "canvastext",
            "background-color" to "transparent",
            "background-image" to "none",
            "font-family" to "sans-serif",
            "font-size" to "16px",
            "font-style" to "normal",
            "font-weight" to "400",
            "line-height" to "normal",
            "visibility" to "visible",
            "position" to "static",
            "float" to "none",
            "clear" to "none",
            "overflow-x" to "visible",
            "overflow-y" to "visible",
            "opacity" to "1",
            "box-sizing" to "content-box",
            "width" to "auto",
            "height" to "auto",
            "min-width" to "auto",
            "min-height" to "auto",
            "max-width" to "none",
            "max-height" to "none",
            "margin-top" to "0",
            "margin-right" to "0",
            "margin-bottom" to "0",
            "margin-left" to "0",
            "padding-top" to "0",
            "padding-right" to "0",
            "padding-bottom" to "0",
            "padding-left" to "0",
            "border-top-width" to "0",
            "border-right-width" to "0",
            "border-bottom-width" to "0",
            "border-left-width" to "0",
            "border-top-style" to "none",
            "border-right-style" to "none",
            "border-bottom-style" to "none",
            "border-left-style" to "none",
            "text-align" to "start",
            "white-space" to "normal",
            "word-break" to "normal",
            "overflow-wrap" to "normal",
            "flex-direction" to "row",
            "flex-wrap" to "nowrap",
            "flex-grow" to "0",
            "flex-shrink" to "1",
            "flex-basis" to "auto",
            "align-items" to "stretch",
            "align-content" to "normal",
            "justify-content" to "normal",
            "order" to "0",
            "list-style-type" to "disc",
            "list-style-position" to "outside"
        )
        private val CASCADE_PROPERTIES = INITIAL_VALUES.keys + INHERITED_PROPERTIES + setOf(
            "display", "top", "right", "bottom", "left", "z-index", "transform", "object-fit", "object-position",
            "border-radius", "border-color", "box-shadow", "text-overflow", "vertical-align", "aspect-ratio", "appearance"
        )
        private val BORDER_WIDTH_KEYWORDS = setOf("thin", "medium", "thick")
        private val BORDER_STYLE_KEYWORDS = setOf("none", "hidden", "dotted", "dashed", "solid", "double", "groove", "ridge", "inset", "outset")
        private val FLEX_DIRECTIONS = setOf("row", "row-reverse", "column", "column-reverse")
        private val FLEX_WRAPS = setOf("nowrap", "wrap", "wrap-reverse")
        private val LENGTH_TOKEN = Regex("^[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[a-zA-Z%]+)?$")

        private fun splitCssWhitespace(value: String): List<String> {
            val output = ArrayList<String>()
            val current = StringBuilder()
            var depth = 0
            var quote: Char? = null
            value.forEach { character ->
                when {
                    quote != null -> {
                        current.append(character)
                        if (character == quote) quote = null
                    }
                    character == '\'' || character == '"' -> { quote = character; current.append(character) }
                    character == '(' || character == '[' -> { depth++; current.append(character) }
                    character == ')' || character == ']' -> { depth--; current.append(character) }
                    character.isWhitespace() && depth == 0 -> {
                        if (current.isNotEmpty()) { output += current.toString(); current.clear() }
                    }
                    else -> current.append(character)
                }
            }
            if (current.isNotEmpty()) output += current.toString()
            return output
        }

        private fun expandFour(tokens: List<String>): List<String>? = when (tokens.size) {
            1 -> listOf(tokens[0], tokens[0], tokens[0], tokens[0])
            2 -> listOf(tokens[0], tokens[1], tokens[0], tokens[1])
            3 -> listOf(tokens[0], tokens[1], tokens[2], tokens[1])
            4 -> tokens
            else -> null
        }

        private fun splitTopLevelSlash(value: String): Pair<String, String?> {
            var depth = 0
            var quote: Char? = null
            value.forEachIndexed { index, character ->
                if (quote != null) {
                    if (character == quote && (index == 0 || value[index - 1] != '\\')) quote = null
                } else when (character) {
                    '\'', '"' -> quote = character
                    '(' -> depth++
                    ')' -> depth--
                    '/' -> if (depth == 0) return value.substring(0, index).trim() to value.substring(index + 1).trim()
                }
            }
            return value.trim() to null
        }

        private fun looksLikeColor(token: String): Boolean {
            val lower = token.lowercase(Locale.ROOT)
            return lower.startsWith('#') || lower.startsWith("rgb(") || lower.startsWith("rgba(") || lower.startsWith("hsl(") ||
                lower.startsWith("hsla(") || lower in COLOR_KEYWORDS || lower == "currentcolor"
        }

        private val COLOR_KEYWORDS = setOf(
            "transparent", "black", "silver", "gray", "white", "maroon", "red", "purple", "fuchsia", "green", "lime",
            "olive", "yellow", "navy", "blue", "teal", "aqua", "orange", "rebeccapurple", "canvas", "canvastext"
        )
    }
}
