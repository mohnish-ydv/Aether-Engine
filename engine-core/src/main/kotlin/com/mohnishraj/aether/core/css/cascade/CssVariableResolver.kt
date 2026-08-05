package com.mohnishraj.aether.core.css.cascade

import com.mohnishraj.aether.core.css.CssIssue
import com.mohnishraj.aether.core.css.CssLimits

class CssVariableResolver(private val limits: CssLimits) {
    fun resolve(value: String, variables: Map<String, String>, issues: MutableList<CssIssue>): String? =
        resolveInternal(value, variables, issues, LinkedHashSet(), 0)

    private fun resolveInternal(
        value: String,
        variables: Map<String, String>,
        issues: MutableList<CssIssue>,
        stack: LinkedHashSet<String>,
        depth: Int
    ): String? {
        if (depth > limits.maxVariableDepth) {
            issues += CssIssue("variable-depth", "CSS variable expansion limit exceeded")
            return null
        }
        val output = StringBuilder()
        var index = 0
        while (index < value.length) {
            val varIndex = value.indexOf("var(", index, ignoreCase = true)
            if (varIndex < 0) { output.append(value.substring(index)); break }
            output.append(value.substring(index, varIndex))
            val end = findClosingParen(value, varIndex + 3)
            if (end < 0) {
                issues += CssIssue("invalid-var", "Unclosed var() function")
                return null
            }
            val body = value.substring(varIndex + 4, end)
            val comma = findTopLevelComma(body)
            val name = (if (comma < 0) body else body.substring(0, comma)).trim()
            val fallback = if (comma < 0) null else body.substring(comma + 1).trim()
            if (!name.startsWith("--")) {
                issues += CssIssue("invalid-var-name", "var() expects a custom property name")
                return fallback?.let { resolveInternal(it, variables, issues, stack, depth + 1) }
            }
            if (!stack.add(name)) {
                issues += CssIssue("variable-cycle", "CSS variable cycle: ${(stack + name).joinToString(" -> ")}")
                return fallback?.let { resolveInternal(it, variables, issues, stack, depth + 1) }
            }
            val replacement = variables[name]?.let { resolveInternal(it, variables, issues, stack, depth + 1) }
                ?: fallback?.let { resolveInternal(it, variables, issues, stack, depth + 1) }
            stack.remove(name)
            if (replacement == null) return null
            output.append(replacement)
            index = end + 1
        }
        return output.toString().trim()
    }

    private fun findClosingParen(source: String, openParen: Int): Int {
        var depth = 0
        var quote: Char? = null
        var index = openParen
        while (index < source.length) {
            val c = source[index]
            if (quote != null) {
                if (c == '\\') index++ else if (c == quote) quote = null
            } else when (c) {
                '\'', '"' -> quote = c
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) return index }
            }
            index++
        }
        return -1
    }

    private fun findTopLevelComma(source: String): Int {
        var depth = 0
        source.forEachIndexed { index, c ->
            when (c) { '(' -> depth++; ')' -> depth--; ',' -> if (depth == 0) return index }
        }
        return -1
    }
}
