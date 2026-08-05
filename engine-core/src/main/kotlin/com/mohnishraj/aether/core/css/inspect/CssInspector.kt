package com.mohnishraj.aether.core.css.inspect

import com.mohnishraj.aether.core.css.StyleTree
import com.mohnishraj.aether.core.css.parser.CssRule
import com.mohnishraj.aether.core.css.parser.CssStyleSheet
import com.mohnishraj.aether.core.css.parser.FontFaceRule
import com.mohnishraj.aether.core.css.parser.MediaRule
import com.mohnishraj.aether.core.css.parser.StyleRule
import com.mohnishraj.aether.core.css.parser.SupportsRule
import com.mohnishraj.aether.core.html.dom.DocumentNode
import com.mohnishraj.aether.core.html.dom.ElementNode

object CssInspector {
    fun styleSheet(sheet: CssStyleSheet): String = buildString {
        appendLine("CSS STYLESHEET")
        appendLine("rules=${countRules(sheet.rules)} issues=${sheet.issues.size} tokens=${sheet.tokenCount}")
        sheet.rules.forEach { appendRule(it, 0) }
        if (sheet.issues.isNotEmpty()) {
            appendLine("ISSUES")
            sheet.issues.take(30).forEach { appendLine("  ${it.code}: ${it.message}") }
        }
    }.trimEnd()

    fun computedTree(document: DocumentNode, tree: StyleTree, maxProperties: Int = 12): String = buildString {
        document.descendants(includeSelf = true).filterIsInstance<ElementNode>().forEach { element ->
            val style = tree.styleFor(element) ?: return@forEach
            val depth = generateSequence(element.parent) { it.parent }.count() - 1
            append("  ".repeat(depth.coerceAtLeast(0)))
            append('<').append(element.localName)
            element.id?.let { append('#').append(it) }
            element.classNames.forEach { append('.').append(it) }
            appendLine("> matched=${style.matchedRuleCount}")
            style.properties.entries.take(maxProperties).forEach { (name, value) ->
                append("  ".repeat(depth.coerceAtLeast(0) + 1)).append(name).append(": ").appendLine(value)
            }
            style.customProperties.forEach { (name, value) ->
                append("  ".repeat(depth.coerceAtLeast(0) + 1)).append(name).append(": ").appendLine(value)
            }
        }
        if (tree.fontFaces.isNotEmpty()) {
            appendLine("FONT FACES")
            tree.fontFaces.forEach { appendLine("  ${it.family} weight=${it.weight} style=${it.style} src=${it.source ?: "none"}") }
        }
    }.trimEnd()

    private fun StringBuilder.appendRule(rule: CssRule, depth: Int) {
        val prefix = "  ".repeat(depth)
        when (rule) {
            is StyleRule -> {
                appendLine("$prefix${rule.selectorText}  specificity=${rule.selectors.joinToString { it.specificity.toString() }}")
                rule.declarations.forEach { appendLine("$prefix  ${it.name}: ${it.value}${if (it.important) " !important" else ""}") }
            }
            is MediaRule -> { appendLine("$prefix@media ${rule.query}"); rule.rules.forEach { appendRule(it, depth + 1) } }
            is SupportsRule -> { appendLine("$prefix@supports ${rule.condition}"); rule.rules.forEach { appendRule(it, depth + 1) } }
            is FontFaceRule -> {
                appendLine("$prefix@font-face")
                rule.declarations.forEach { appendLine("$prefix  ${it.name}: ${it.value}") }
            }
        }
    }

    private fun countRules(rules: List<CssRule>): Int = rules.sumOf { rule ->
        1 + when (rule) {
            is MediaRule -> countRules(rule.rules)
            is SupportsRule -> countRules(rule.rules)
            else -> 0
        }
    }
}
