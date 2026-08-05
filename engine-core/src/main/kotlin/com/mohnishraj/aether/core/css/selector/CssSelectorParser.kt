package com.mohnishraj.aether.core.css.selector

import com.mohnishraj.aether.core.css.CssIssue
import com.mohnishraj.aether.core.css.CssLimits
import com.mohnishraj.aether.core.css.CssSpan
import java.util.Locale

/** Bounded Selectors Level 3/4 parser used by both style matching and DOM query APIs. */
class CssSelectorParser(private val limits: CssLimits = CssLimits()) {
    fun parseList(source: String, issues: MutableList<CssIssue> = mutableListOf()): List<ComplexSelector> {
        if (source.length > limits.maxSelectorLength) {
            issues += CssIssue("selector-too-long", "Selector exceeds ${limits.maxSelectorLength} characters")
            return emptyList()
        }
        return splitTopLevel(source, ',').mapNotNull { part ->
            runCatching { parseComplex(part.trim()) }.getOrElse {
                issues += CssIssue("invalid-selector", it.message ?: "Invalid selector", CssSpan.EMPTY)
                null
            }
        }
    }

    private fun parseComplex(source: String): ComplexSelector {
        require(source.isNotBlank()) { "Empty selector" }
        val compounds = ArrayList<CompoundSelector>()
        val combinators = ArrayList<Combinator>()
        var index = 0
        var pendingDescendant = false
        while (index < source.length) {
            val hadSpace = source[index].isWhitespace()
            while (index < source.length && source[index].isWhitespace()) index++
            if (hadSpace && compounds.isNotEmpty()) pendingDescendant = true
            if (index >= source.length) break
            when (source[index]) {
                '>' -> { require(compounds.isNotEmpty()) { "Selector cannot start with >" }; appendCombinator(combinators, compounds, Combinator.CHILD); index++; pendingDescendant = false; continue }
                '+' -> { require(compounds.isNotEmpty()) { "Selector cannot start with +" }; appendCombinator(combinators, compounds, Combinator.ADJACENT_SIBLING); index++; pendingDescendant = false; continue }
                '~' -> { require(compounds.isNotEmpty()) { "Selector cannot start with ~" }; appendCombinator(combinators, compounds, Combinator.GENERAL_SIBLING); index++; pendingDescendant = false; continue }
            }
            if (pendingDescendant && combinators.size < compounds.size) combinators += Combinator.DESCENDANT
            val (compound, next) = parseCompound(source, index)
            require(next > index) { "Selector parser made no progress" }
            compounds += compound
            index = next
            pendingDescendant = false
            if (compounds.size > 1 && combinators.size < compounds.size - 1) combinators += Combinator.DESCENDANT
        }
        require(compounds.isNotEmpty()) { "Selector contains no compound" }
        require(combinators.size == compounds.size - 1) { "Malformed selector combinators" }
        return ComplexSelector(compounds, combinators, source)
    }

    private fun appendCombinator(combinators: MutableList<Combinator>, compounds: List<CompoundSelector>, value: Combinator) {
        require(combinators.size < compounds.size) { "Two combinators cannot appear consecutively" }
        combinators += value
    }

    private fun parseCompound(source: String, start: Int): Pair<CompoundSelector, Int> {
        val selectors = ArrayList<SimpleSelector>()
        var index = start
        val type = parseTypeSelector(source, index)
        if (type != null) {
            selectors += type.first
            index = type.second
        }
        loop@ while (index < source.length) {
            when (source[index]) {
                '#' -> {
                    val (name, next) = readName(source, index + 1)
                    require(name.isNotEmpty()) { "Empty id selector" }
                    selectors += IdSelector(name)
                    index = next
                }
                '.' -> {
                    val (name, next) = readName(source, index + 1)
                    require(name.isNotEmpty()) { "Empty class selector" }
                    selectors += ClassSelector(name)
                    index = next
                }
                '[' -> {
                    val end = findClosing(source, index, '[', ']')
                    selectors += AttributeSimpleSelector(parseAttribute(source.substring(index + 1, end)))
                    index = end + 1
                }
                ':' -> {
                    val pseudoElement = index + 1 < source.length && source[index + 1] == ':'
                    val nameStart = index + if (pseudoElement) 2 else 1
                    val (rawName, afterName) = readName(source, nameStart)
                    require(rawName.isNotEmpty()) { "Empty pseudo selector" }
                    val name = rawName.lowercase(Locale.ROOT)
                    index = afterName
                    var argument: String? = null
                    var nested = emptyList<ComplexSelector>()
                    var relative = emptyList<RelativeSelector>()
                    if (index < source.length && source[index] == '(') {
                        val end = findClosing(source, index, '(', ')')
                        argument = source.substring(index + 1, end).trim()
                        when (name) {
                            "not", "is", "where" -> nested = parseList(argument)
                            "has" -> relative = parseRelativeList(argument)
                        }
                        index = end + 1
                    }
                    val legacyPseudoElement = !pseudoElement && name in LEGACY_PSEUDO_ELEMENTS
                    selectors += if (pseudoElement || legacyPseudoElement) PseudoElementSelector(name)
                    else PseudoClassSelector(name, argument, nested, relative)
                }
                ' ', '\t', '\r', '\n', '>', '+', '~', ',' -> break@loop
                else -> error("Unexpected selector character '${source[index]}'")
            }
        }
        if (selectors.isEmpty()) selectors += UniversalSelector
        return CompoundSelector(selectors) to index
    }

    private fun parseTypeSelector(source: String, start: Int): Pair<SimpleSelector, Int>? {
        var index = start
        if (index >= source.length) return null
        var namespace: String? = null
        if (source.startsWith("*|", index)) {
            namespace = "*"
            index += 2
        } else if (source[index] == '|') {
            namespace = ""
            index++
        } else if (isNameStart(source[index]) || source[index] == '\\') {
            val (firstName, afterFirst) = readName(source, index)
            if (afterFirst < source.length && source[afterFirst] == '|') {
                namespace = firstName
                index = afterFirst + 1
            }
        }
        if (namespace != null) {
            if (index < source.length && source[index] == '*') return TypeSelector("*", namespace) to index + 1
            val (name, next) = readName(source, index)
            require(name.isNotEmpty()) { "Namespace prefix must be followed by a type selector" }
            return TypeSelector(name, namespace) to next
        }
        if (source[start] == '*') return UniversalSelector to start + 1
        if (isNameStart(source[start]) || source[start] == '\\') {
            val (name, next) = readName(source, start)
            return TypeSelector(name.lowercase(Locale.ROOT)) to next
        }
        return null
    }

    private fun parseRelativeList(source: String): List<RelativeSelector> = splitTopLevel(source, ',').mapNotNull { part ->
        val raw = part.trim()
        if (raw.isEmpty()) return@mapNotNull null
        val relation = when (raw.first()) {
            '>' -> Combinator.CHILD
            '+' -> Combinator.ADJACENT_SIBLING
            '~' -> Combinator.GENERAL_SIBLING
            else -> Combinator.DESCENDANT
        }
        val selectorText = if (relation == Combinator.DESCENDANT) raw else raw.drop(1).trimStart()
        runCatching { RelativeSelector(relation, parseComplex(selectorText)) }.getOrNull()
    }

    private fun parseAttribute(raw: String): AttributeSelector {
        val text = raw.trim()
        val pattern = Regex("^([_a-zA-Z][-_a-zA-Z0-9:.]*)(?:\\s*(~=|\\|=|\\^=|\\$=|\\*=|=)\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s]+)))?(?:\\s+([iIsS]))?$")
        val match = pattern.matchEntire(text) ?: error("Invalid attribute selector [$text]")
        val name = match.groupValues[1].lowercase(Locale.ROOT)
        val operator = when (match.groupValues[2]) {
            "" -> AttributeOperator.EXISTS
            "=" -> AttributeOperator.EQUALS
            "~=" -> AttributeOperator.INCLUDES
            "|=" -> AttributeOperator.DASH_MATCH
            "^=" -> AttributeOperator.PREFIX
            "$=" -> AttributeOperator.SUFFIX
            "*=" -> AttributeOperator.SUBSTRING
            else -> error("Unsupported attribute operator")
        }
        val value = listOf(match.groupValues[3], match.groupValues[4], match.groupValues[5]).firstOrNull { it.isNotEmpty() }
        return AttributeSelector(name, operator, value, match.groupValues[6].equals("i", true))
    }

    private fun readName(source: String, start: Int): Pair<String, Int> {
        var index = start
        val output = StringBuilder()
        while (index < source.length) {
            val c = source[index]
            if (isName(c)) {
                output.append(c)
                index++
            } else if (c == '\\') {
                val escape = consumeEscape(source, index)
                require(escape != null) { "Invalid CSS escape" }
                output.appendCodePoint(escape.first)
                index = escape.second
            } else break
        }
        return output.toString() to index
    }

    private fun consumeEscape(source: String, slashIndex: Int): Pair<Int, Int>? {
        var index = slashIndex + 1
        if (index >= source.length || source[index] == '\n' || source[index] == '\r' || source[index] == '\u000c') return null
        if (source[index].isHexDigit()) {
            val start = index
            while (index < source.length && index - start < 6 && source[index].isHexDigit()) index++
            val codePoint = source.substring(start, index).toIntOrNull(16)?.takeIf { it in 1..0x10FFFF } ?: 0xFFFD
            if (index < source.length && source[index].isWhitespace()) index++
            return codePoint to index
        }
        return source[index].code to index + 1
    }

    private fun findClosing(source: String, start: Int, open: Char, close: Char): Int {
        var depth = 0
        var quote: Char? = null
        var index = start
        while (index < source.length) {
            val c = source[index]
            if (quote != null) {
                if (c == '\\') index++ else if (c == quote) quote = null
            } else when (c) {
                '\'', '"' -> quote = c
                open -> depth++
                close -> { depth--; if (depth == 0) return index }
            }
            index++
        }
        error("Unclosed $open in selector")
    }

    private fun splitTopLevel(source: String, delimiter: Char): List<String> {
        val output = ArrayList<String>()
        var start = 0
        var depth = 0
        var quote: Char? = null
        for (index in source.indices) {
            val c = source[index]
            if (quote != null) {
                if (c == quote && (index == 0 || source[index - 1] != '\\')) quote = null
            } else when (c) {
                '\'', '"' -> quote = c
                '(', '[' -> depth++
                ')', ']' -> depth--
                delimiter -> if (depth == 0) { output += source.substring(start, index); start = index + 1 }
            }
        }
        output += source.substring(start)
        return output
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    private fun isNameStart(c: Char) = c == '_' || c == '-' || c.isLetter() || c.code >= 0x80
    private fun isName(c: Char) = isNameStart(c) || c.isDigit()

    companion object {
        private val LEGACY_PSEUDO_ELEMENTS = setOf("before", "after", "first-line", "first-letter")
    }
}
