package com.mohnishraj.aether.core.css.selector

import com.mohnishraj.aether.core.html.dom.DocumentNode
import com.mohnishraj.aether.core.html.dom.ElementNode
import java.net.URI
import java.util.Locale

/** CSS specificity tuple. Saturating addition avoids hostile-selector integer overflow. */
data class Specificity(val ids: Int = 0, val classes: Int = 0, val types: Int = 0) : Comparable<Specificity> {
    override fun compareTo(other: Specificity): Int = when {
        ids != other.ids -> ids.compareTo(other.ids)
        classes != other.classes -> classes.compareTo(other.classes)
        else -> types.compareTo(other.types)
    }

    operator fun plus(other: Specificity) = Specificity(
        ids = saturatingAdd(ids, other.ids),
        classes = saturatingAdd(classes, other.classes),
        types = saturatingAdd(types, other.types)
    )

    override fun toString(): String = "$ids,$classes,$types"

    companion object {
        private fun saturatingAdd(left: Int, right: Int): Int =
            if (left > Int.MAX_VALUE - right) Int.MAX_VALUE else left + right
    }
}

enum class Combinator { DESCENDANT, CHILD, ADJACENT_SIBLING, GENERAL_SIBLING }
enum class AttributeOperator { EXISTS, EQUALS, INCLUDES, DASH_MATCH, PREFIX, SUFFIX, SUBSTRING }

data class AttributeSelector(
    val name: String,
    val operator: AttributeOperator,
    val value: String? = null,
    val caseInsensitive: Boolean = false
)

sealed interface SimpleSelector {
    val specificity: Specificity
    fun matches(element: ElementNode): Boolean
}

data class TypeSelector(val name: String, val namespacePrefix: String? = null) : SimpleSelector {
    override val specificity = Specificity(types = 1)
    override fun matches(element: ElementNode): Boolean {
        val localMatches = name == "*" || element.localName.equals(name, ignoreCase = element.namespace.name == "HTML")
        if (!localMatches) return false
        return when (namespacePrefix?.lowercase(Locale.ROOT)) {
            null, "*" -> true
            "" -> element.namespace.name == "HTML"
            "svg" -> element.namespace.name == "SVG"
            "math", "mathml" -> element.namespace.name == "MATHML"
            else -> true // Namespace declarations are not retained yet; do not reject otherwise-valid selectors.
        }
    }
}

data object UniversalSelector : SimpleSelector {
    override val specificity = Specificity()
    override fun matches(element: ElementNode) = true
}

data class IdSelector(val id: String) : SimpleSelector {
    override val specificity = Specificity(ids = 1)
    override fun matches(element: ElementNode) = element.id == id
}

data class ClassSelector(val className: String) : SimpleSelector {
    override val specificity = Specificity(classes = 1)
    override fun matches(element: ElementNode) = className in element.classNames
}

data class AttributeSimpleSelector(val selector: AttributeSelector) : SimpleSelector {
    override val specificity = Specificity(classes = 1)
    override fun matches(element: ElementNode): Boolean {
        val actual = element.getAttribute(selector.name)
            ?: return selector.operator == AttributeOperator.EXISTS && element.hasAttribute(selector.name)
        if (selector.operator == AttributeOperator.EXISTS) return true
        val expected = selector.value.orEmpty()
        val left = if (selector.caseInsensitive) actual.lowercase(Locale.ROOT) else actual
        val right = if (selector.caseInsensitive) expected.lowercase(Locale.ROOT) else expected
        return when (selector.operator) {
            AttributeOperator.EXISTS -> true
            AttributeOperator.EQUALS -> left == right
            AttributeOperator.INCLUDES -> left.split(Regex("\\s+")).any { it == right }
            AttributeOperator.DASH_MATCH -> left == right || left.startsWith("$right-")
            AttributeOperator.PREFIX -> right.isNotEmpty() && left.startsWith(right)
            AttributeOperator.SUFFIX -> right.isNotEmpty() && left.endsWith(right)
            AttributeOperator.SUBSTRING -> right.isNotEmpty() && right in left
        }
    }
}

/** Pseudo-elements never match the originating element's style rule. */
data class PseudoElementSelector(val name: String) : SimpleSelector {
    override val specificity = Specificity(types = 1)
    override fun matches(element: ElementNode): Boolean = false
}

data class RelativeSelector(val relation: Combinator, val selector: ComplexSelector)

data class PseudoClassSelector(
    val name: String,
    val argument: String? = null,
    val nested: List<ComplexSelector> = emptyList(),
    val relative: List<RelativeSelector> = emptyList()
) : SimpleSelector {
    override val specificity: Specificity = when (name) {
        "where" -> Specificity()
        "is", "not", "has" -> {
            val absolute = nested.maxOfOrNull { it.specificity }
            val relativeSpecificity = relative.maxOfOrNull { it.selector.specificity }
            listOfNotNull(absolute, relativeSpecificity).maxOrNull() ?: Specificity(classes = 1)
        }
        else -> Specificity(classes = 1)
    }

    override fun matches(element: ElementNode): Boolean = when (name) {
        "root", "scope" -> element.parent?.nodeName == "#document"
        "first-child" -> element.previousElementSibling() == null
        "last-child" -> element.nextElementSibling() == null
        "only-child" -> element.previousElementSibling() == null && element.nextElementSibling() == null
        "first-of-type" -> element.previousElementSiblingOfType() == null
        "last-of-type" -> element.nextElementSiblingOfType() == null
        "only-of-type" -> element.previousElementSiblingOfType() == null && element.nextElementSiblingOfType() == null
        "empty" -> element.children.none { child -> child is ElementNode || child.textContent.isNotEmpty() }
        "disabled" -> isDisabled(element)
        "enabled" -> isFormControl(element) && !isDisabled(element)
        "checked" -> element.hasAttribute("checked") || element.hasAttribute("selected") || element.getAttribute("aria-checked") == "true"
        "indeterminate" -> element.getAttribute("aria-checked") == "mixed" || element.getAttribute("data-aether-indeterminate") == "true"
        "required" -> element.hasAttribute("required")
        "optional" -> isFormControl(element) && !element.hasAttribute("required")
        "read-only" -> !isTextEditable(element) || element.hasAttribute("readonly")
        "read-write" -> isTextEditable(element) && !element.hasAttribute("readonly") && !isDisabled(element)
        "placeholder-shown" -> element.getAttribute("placeholder") != null && element.getAttribute("value").isNullOrEmpty()
        "focus", "focus-visible" -> element.getAttribute("data-aether-focus") == "true" || element.hasAttribute("autofocus")
        "focus-within" -> matchesFocus(element) || element.descendants().filterIsInstance<ElementNode>().any(::matchesFocus)
        "valid" -> isValid(element)
        "invalid" -> isFormControl(element) && !isValid(element)
        "link", "any-link" -> element.localName in setOf("a", "area") && element.hasAttribute("href")
        "visited" -> false // Privacy: history state is deliberately not exposed to selectors.
        "target" -> matchesTarget(element)
        "defined" -> !element.localName.contains('-') || element.getAttribute("data-aether-defined") == "true"
        "lang" -> matchesLanguage(element, argument.orEmpty())
        "dir" -> matchesDirection(element, argument.orEmpty())
        "not" -> nested.none { it.matches(element) }
        "is", "where" -> nested.any { it.matches(element) }
        "has" -> relative.any { it.matchesRelativeTo(element) }
        "nth-child" -> nthMatches(element, argument.orEmpty(), fromEnd = false, ofType = false)
        "nth-last-child" -> nthMatches(element, argument.orEmpty(), fromEnd = true, ofType = false)
        "nth-of-type" -> nthMatches(element, argument.orEmpty(), fromEnd = false, ofType = true)
        "nth-last-of-type" -> nthMatches(element, argument.orEmpty(), fromEnd = true, ofType = true)
        else -> false
    }

    private fun RelativeSelector.matchesRelativeTo(element: ElementNode): Boolean = when (relation) {
        Combinator.CHILD -> element.children.filterIsInstance<ElementNode>().any(selector::matches)
        Combinator.ADJACENT_SIBLING -> element.nextElementSibling()?.let(selector::matches) == true
        Combinator.GENERAL_SIBLING -> generateSequence(element.nextElementSibling()) { it.nextElementSibling() }.any(selector::matches)
        Combinator.DESCENDANT -> element.descendants().filterIsInstance<ElementNode>().any(selector::matches)
    }

    private fun nthMatches(element: ElementNode, rawExpression: String, fromEnd: Boolean, ofType: Boolean): Boolean {
        val (formula, selectorFilter) = splitNthOf(rawExpression)
        var siblings = element.parent?.children?.filterIsInstance<ElementNode>().orEmpty()
        if (ofType) siblings = siblings.filter { it.localName == element.localName && it.namespace == element.namespace }
        if (selectorFilter.isNotEmpty()) siblings = siblings.filter { candidate -> selectorFilter.any { it.matches(candidate) } }
        val rawIndex = siblings.indexOf(element)
        if (rawIndex < 0) return false
        val index = if (fromEnd) siblings.size - rawIndex else rawIndex + 1
        return nthFormulaMatches(index, formula)
    }

    private fun splitNthOf(raw: String): Pair<String, List<ComplexSelector>> {
        var depth = 0
        var quote: Char? = null
        var index = 0
        while (index + 3 < raw.length) {
            val c = raw[index]
            if (quote != null) {
                if (c == '\\') index++ else if (c == quote) quote = null
            } else when (c) {
                '\'', '"' -> quote = c
                '(', '[' -> depth++
                ')', ']' -> depth--
                else -> if (depth == 0 && c.isWhitespace()) {
                    val tail = raw.substring(index).trimStart()
                    if (tail.startsWith("of ", ignoreCase = true)) {
                        val selectorText = tail.substring(3).trim()
                        return raw.substring(0, index).trim() to runCatching { CssSelectorParser().parseList(selectorText) }.getOrDefault(emptyList())
                    }
                }
            }
            index++
        }
        return raw.trim() to emptyList()
    }

    private fun nthFormulaMatches(index: Int, expression: String): Boolean {
        val normalized = expression.lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
        if (normalized == "odd") return index % 2 == 1
        if (normalized == "even") return index % 2 == 0
        normalized.toIntOrNull()?.let { return index == it }
        val match = Regex("([+-]?\\d*)n([+-]\\d+)?").matchEntire(normalized) ?: return false
        val a = when (val raw = match.groupValues[1]) {
            "", "+" -> 1
            "-" -> -1
            else -> raw.toIntOrNull() ?: return false
        }
        val b = match.groupValues[2].toIntOrNull() ?: 0
        if (a == 0) return index == b
        val difference = index - b
        return difference * a >= 0 && difference % a == 0
    }

    private fun isDisabled(element: ElementNode): Boolean {
        if (element.hasAttribute("disabled")) return true
        if (element.localName !in FORM_CONTROLS) return false
        return generateSequence(element.parent as? ElementNode) { it.parent as? ElementNode }
            .any { ancestor -> ancestor.localName == "fieldset" && ancestor.hasAttribute("disabled") }
    }

    private fun isValid(element: ElementNode): Boolean {
        if (!isFormControl(element) || isDisabled(element)) return true
        val value = element.getAttribute("value") ?: if (element.localName == "textarea") element.textContent else ""
        if (element.hasAttribute("required") && value.isBlank()) return false
        val pattern = element.getAttribute("pattern")
        if (!pattern.isNullOrEmpty() && value.isNotEmpty() && runCatching { !Regex("^(?:$pattern)$").matches(value) }.getOrDefault(false)) return false
        return true
    }

    private fun matchesTarget(element: ElementNode): Boolean {
        val url = (element.root() as? DocumentNode)?.url ?: return false
        val fragment = runCatching { URI(url).fragment }.getOrNull() ?: return false
        return fragment.isNotEmpty() && element.id == fragment
    }

    private fun matchesLanguage(element: ElementNode, requested: String): Boolean {
        if (requested.isBlank()) return false
        val actual = generateSequence(element as ElementNode?) { it.parent as? ElementNode }
            .mapNotNull { it.getAttribute("lang") ?: it.getAttribute("xml:lang") }
            .firstOrNull() ?: return false
        val wanted = requested.trim().trim('\'', '"').lowercase(Locale.ROOT)
        val language = actual.lowercase(Locale.ROOT)
        return language == wanted || language.startsWith("$wanted-")
    }

    private fun matchesDirection(element: ElementNode, requested: String): Boolean {
        val wanted = requested.trim().trim('\'', '"').lowercase(Locale.ROOT)
        if (wanted !in setOf("ltr", "rtl")) return false
        val actual = generateSequence(element as ElementNode?) { it.parent as? ElementNode }
            .mapNotNull { it.getAttribute("dir")?.lowercase(Locale.ROOT) }
            .firstOrNull { it in setOf("ltr", "rtl") } ?: "ltr"
        return actual == wanted
    }

    companion object {
        private val FORM_CONTROLS = setOf("button", "input", "select", "textarea", "option", "optgroup", "fieldset")
        private fun isFormControl(element: ElementNode): Boolean = element.localName in FORM_CONTROLS
        private fun isTextEditable(element: ElementNode): Boolean =
            element.localName in setOf("textarea") || (element.localName == "input" && element.getAttribute("type")?.lowercase(Locale.ROOT) !in setOf("button", "checkbox", "color", "file", "hidden", "image", "radio", "range", "reset", "submit"))
        private fun matchesFocus(element: ElementNode): Boolean = element.getAttribute("data-aether-focus") == "true" || element.hasAttribute("autofocus")
    }
}

data class CompoundSelector(val selectors: List<SimpleSelector>) {
    val specificity: Specificity = selectors.fold(Specificity()) { acc, selector -> acc + selector.specificity }
    fun matches(element: ElementNode): Boolean = selectors.all { it.matches(element) }
}

data class ComplexSelector(
    val compounds: List<CompoundSelector>,
    val combinators: List<Combinator>,
    val source: String
) {
    init { require(compounds.isNotEmpty() && combinators.size == compounds.size - 1) }
    val specificity: Specificity = compounds.fold(Specificity()) { acc, compound -> acc + compound.specificity }

    fun matches(element: ElementNode): Boolean = matchesAt(compounds.lastIndex, element)

    private fun matchesAt(index: Int, element: ElementNode): Boolean {
        if (!compounds[index].matches(element)) return false
        if (index == 0) return true
        return when (combinators[index - 1]) {
            Combinator.CHILD -> (element.parent as? ElementNode)?.let { matchesAt(index - 1, it) } == true
            Combinator.DESCENDANT -> generateSequence(element.parent as? ElementNode) { it.parent as? ElementNode }
                .any { matchesAt(index - 1, it) }
            Combinator.ADJACENT_SIBLING -> element.previousElementSibling()?.let { matchesAt(index - 1, it) } == true
            Combinator.GENERAL_SIBLING -> generateSequence(element.previousElementSibling()) { it.previousElementSibling() }
                .any { matchesAt(index - 1, it) }
        }
    }
}

internal fun ElementNode.previousElementSibling(): ElementNode? {
    var sibling = previousSibling
    while (sibling != null && sibling !is ElementNode) sibling = sibling.previousSibling
    return sibling as? ElementNode
}

internal fun ElementNode.nextElementSibling(): ElementNode? {
    var sibling = nextSibling
    while (sibling != null && sibling !is ElementNode) sibling = sibling.nextSibling
    return sibling as? ElementNode
}

private fun ElementNode.previousElementSiblingOfType(): ElementNode? =
    generateSequence(previousElementSibling()) { it.previousElementSibling() }
        .firstOrNull { it.localName == localName && it.namespace == namespace }

private fun ElementNode.nextElementSiblingOfType(): ElementNode? =
    generateSequence(nextElementSibling()) { it.nextElementSibling() }
        .firstOrNull { it.localName == localName && it.namespace == namespace }
