package com.mohnishraj.aether.core.css.parser

import com.mohnishraj.aether.core.css.CssIssue
import com.mohnishraj.aether.core.css.CssLimits
import com.mohnishraj.aether.core.css.CssOrigin
import com.mohnishraj.aether.core.css.CssSpan
import com.mohnishraj.aether.core.css.selector.CssSelectorParser
import com.mohnishraj.aether.core.css.token.CssTokenizer
import java.util.Locale

class CssParser(private val limits: CssLimits = CssLimits()) {
    private val selectorParser = CssSelectorParser(limits)
    private val tokenizer = CssTokenizer(limits)

    fun parse(source: String, sourceUrl: String? = null, origin: CssOrigin = CssOrigin.AUTHOR): CssStyleSheet {
        require(source.length <= limits.maxInputChars) { "CSS input exceeds ${limits.maxInputChars} characters" }
        val tokenized = tokenizer.tokenize(source)
        val issues = tokenized.issues.toMutableList()
        var sourceOrder = 0
        val rules = parseRules(stripComments(source, issues), issues, 0) { sourceOrder++ }
        return CssStyleSheet(rules, issues, origin, sourceUrl, tokenized.tokens.size)
    }

    fun parseDeclarations(source: String, issues: MutableList<CssIssue> = mutableListOf()): List<CssDeclaration> {
        val chunks = splitTopLevel(source, ';')
        val declarations = ArrayList<CssDeclaration>()
        chunks.forEachIndexed { order, chunk ->
            val colon = findTopLevel(chunk, ':')
            if (colon <= 0) {
                if (chunk.isNotBlank()) issues += CssIssue("invalid-declaration", "Declaration is missing ':'")
                return@forEachIndexed
            }
            val name = chunk.substring(0, colon).trim().lowercase(Locale.ROOT)
            if (!NAME.matches(name)) {
                issues += CssIssue("invalid-property", "Invalid property name '$name'")
                return@forEachIndexed
            }
            var value = chunk.substring(colon + 1).trim()
            if (value.isEmpty()) {
                issues += CssIssue("empty-value", "Property '$name' has an empty value")
                return@forEachIndexed
            }
            val importantMatch = IMPORTANT.find(value)
            val important = importantMatch != null && importantMatch.range.last == value.lastIndex
            if (important) value = value.substring(0, importantMatch!!.range.first).trimEnd()
            declarations += CssDeclaration(name, normalizeWhitespace(value), important, order)
            if (declarations.size >= limits.maxDeclarationsPerRule) {
                issues += CssIssue("declaration-limit", "Declaration limit reached")
                return declarations
            }
        }
        return declarations
    }

    private fun parseRules(
        source: String,
        issues: MutableList<CssIssue>,
        depth: Int,
        nextOrder: () -> Int
    ): List<CssRule> {
        if (depth > limits.maxNestingDepth) {
            issues += CssIssue("nesting-limit", "CSS nesting limit exceeded")
            return emptyList()
        }
        val rules = ArrayList<CssRule>()
        var index = 0
        while (index < source.length) {
            while (index < source.length && (source[index].isWhitespace() || source[index] == ';')) index++
            if (index >= source.length) break
            if (rules.size >= limits.maxRules) {
                issues += CssIssue("rule-limit", "CSS rule limit reached")
                break
            }
            if (source[index] == '@') {
                val nameStart = ++index
                while (index < source.length && (source[index].isLetterOrDigit() || source[index] == '-')) index++
                val name = source.substring(nameStart, index).lowercase(Locale.ROOT)
                val boundary = findRuleBoundary(source, index)
                if (boundary < 0) {
                    issues += CssIssue("unterminated-at-rule", "Unterminated @$name rule", CssSpan(nameStart - 1, source.length))
                    break
                }
                val prelude = source.substring(index, boundary).trim()
                if (source[boundary] == ';') {
                    if (name !in setOf("charset", "import", "namespace")) issues += CssIssue("ignored-at-rule", "Ignored @$name rule")
                    index = boundary + 1
                    continue
                }
                val end = findMatchingBrace(source, boundary)
                if (end < 0) {
                    issues += CssIssue("unterminated-block", "Unterminated @$name block", CssSpan(boundary, source.length))
                    break
                }
                val body = source.substring(boundary + 1, end)
                val order = nextOrder()
                when (name) {
                    "media" -> rules += MediaRule(prelude, parseRules(body, issues, depth + 1, nextOrder), order)
                    "supports" -> rules += SupportsRule(prelude, parseRules(body, issues, depth + 1, nextOrder), order)
                    "font-face" -> rules += FontFaceRule(parseDeclarations(body, issues), order)
                    else -> issues += CssIssue("ignored-at-rule", "Ignored @$name block")
                }
                index = end + 1
                continue
            }
            val brace = findTopLevelFrom(source, '{', index)
            if (brace < 0) {
                if (source.substring(index).isNotBlank()) issues += CssIssue("trailing-css", "Trailing CSS without a rule block")
                break
            }
            val end = findMatchingBrace(source, brace)
            if (end < 0) {
                issues += CssIssue("unterminated-block", "Unterminated style rule", CssSpan(brace, source.length))
                break
            }
            val selectorText = source.substring(index, brace).trim()
            val selectors = selectorParser.parseList(selectorText, issues)
            val declarations = parseDeclarations(source.substring(brace + 1, end), issues)
            if (selectors.isNotEmpty() && declarations.isNotEmpty()) {
                rules += StyleRule(selectorText, selectors, declarations, nextOrder())
            }
            index = end + 1
        }
        return rules
    }

    private fun stripComments(source: String, issues: MutableList<CssIssue>): String {
        val output = StringBuilder(source.length)
        var index = 0
        while (index < source.length) {
            if (source.startsWith("/*", index)) {
                val end = source.indexOf("*/", index + 2)
                if (end < 0) {
                    issues += CssIssue("eof-in-comment", "Unterminated CSS comment", CssSpan(index, source.length))
                    output.append(' ')
                    break
                }
                repeat(end + 2 - index) { output.append(if (source[index + it] == '\n') '\n' else ' ') }
                index = end + 2
            } else output.append(source[index++])
        }
        return output.toString()
    }

    private fun findRuleBoundary(source: String, start: Int): Int {
        var quote: Char? = null
        var paren = 0
        var bracket = 0
        var index = start
        while (index < source.length) {
            val c = source[index]
            if (quote != null) {
                if (c == '\\') index++ else if (c == quote) quote = null
            } else when (c) {
                '\'', '"' -> quote = c
                '(' -> paren++
                ')' -> paren--
                '[' -> bracket++
                ']' -> bracket--
                ';', '{' -> if (paren == 0 && bracket == 0) return index
            }
            index++
        }
        return -1
    }

    private fun findMatchingBrace(source: String, openIndex: Int): Int {
        var depth = 0
        var quote: Char? = null
        var index = openIndex
        while (index < source.length) {
            val c = source[index]
            if (quote != null) {
                if (c == '\\') index++ else if (c == quote) quote = null
            } else when (c) {
                '\'', '"' -> quote = c
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return index }
            }
            index++
        }
        return -1
    }

    private fun splitTopLevel(source: String, delimiter: Char): List<String> {
        val output = ArrayList<String>()
        var start = 0
        var paren = 0
        var bracket = 0
        var quote: Char? = null
        for (index in source.indices) {
            val c = source[index]
            if (quote != null) {
                if (c == quote && (index == 0 || source[index - 1] != '\\')) quote = null
            } else when (c) {
                '\'', '"' -> quote = c
                '(' -> paren++
                ')' -> paren--
                '[' -> bracket++
                ']' -> bracket--
                delimiter -> if (paren == 0 && bracket == 0) { output += source.substring(start, index); start = index + 1 }
            }
        }
        output += source.substring(start)
        return output
    }

    private fun findTopLevel(source: String, target: Char): Int = findTopLevelFrom(source, target, 0)

    private fun findTopLevelFrom(source: String, target: Char, start: Int): Int {
        var paren = 0
        var bracket = 0
        var quote: Char? = null
        var index = start
        while (index < source.length) {
            val c = source[index]
            if (quote != null) {
                if (c == '\\') index++ else if (c == quote) quote = null
            } else when (c) {
                '\'', '"' -> quote = c
                '(' -> paren++
                ')' -> paren--
                '[' -> bracket++
                ']' -> bracket--
                target -> if (paren == 0 && bracket == 0) return index
            }
            index++
        }
        return -1
    }

    private fun normalizeWhitespace(value: String): String {
        val output = StringBuilder(value.length)
        var quote: Char? = null
        var pendingSpace = false
        value.forEachIndexed { index, c ->
            if (quote != null) {
                output.append(c)
                if (c == quote && (index == 0 || value[index - 1] != '\\')) quote = null
            } else when {
                c == '\'' || c == '"' -> { if (pendingSpace && output.isNotEmpty()) output.append(' '); pendingSpace = false; quote = c; output.append(c) }
                c.isWhitespace() -> pendingSpace = true
                else -> { if (pendingSpace && output.isNotEmpty()) output.append(' '); pendingSpace = false; output.append(c) }
            }
        }
        return output.toString().trim()
    }

    companion object {
        private val NAME = Regex("^(?:--[-_a-zA-Z0-9]+|-?[_a-zA-Z][-_a-zA-Z0-9]*)$")
        private val IMPORTANT = Regex("!\\s*important\\s*$", RegexOption.IGNORE_CASE)
    }
}
