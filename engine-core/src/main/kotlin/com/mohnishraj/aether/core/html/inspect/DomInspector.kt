package com.mohnishraj.aether.core.html.inspect

import com.mohnishraj.aether.core.html.dom.CommentNode
import com.mohnishraj.aether.core.html.dom.DocumentNode
import com.mohnishraj.aether.core.html.dom.DocumentTypeNode
import com.mohnishraj.aether.core.html.dom.DomNode
import com.mohnishraj.aether.core.html.dom.ElementNode
import com.mohnishraj.aether.core.html.dom.TextNode
import java.util.ArrayDeque

object DomInspector {
    fun summarize(document: DocumentNode): DomSummary {
        var elements = 0
        var textNodes = 0
        var comments = 0
        var doctypes = 0
        var textCharacters = 0L
        var maxDepth = 0
        val stack = ArrayDeque<Pair<DomNode, Int>>()
        stack.addLast(document to 0)
        while (stack.isNotEmpty()) {
            val (node, depth) = stack.removeLast()
            maxDepth = maxOf(maxDepth, depth)
            when (node) {
                is ElementNode -> elements++
                is TextNode -> {
                    textNodes++
                    textCharacters += node.data.length
                }
                is CommentNode -> comments++
                is DocumentTypeNode -> doctypes++
                else -> Unit
            }
            node.children.asReversed().forEach { stack.addLast(it to depth + 1) }
        }
        return DomSummary(
            totalNodes = elements + textNodes + comments + doctypes + 1,
            elements = elements,
            textNodes = textNodes,
            comments = comments,
            doctypes = doctypes,
            textCharacters = textCharacters,
            maxDepth = maxDepth,
            quirksMode = document.quirksMode.name
        )
    }

    fun tree(node: DomNode, maxDepth: Int = 64, maxText: Int = 120): String {
        require(maxDepth >= 0)
        require(maxText >= 8)
        val output = StringBuilder()
        fun visit(current: DomNode, depth: Int) {
            output.append("  ".repeat(depth)).append(describe(current, maxText)).append('\n')
            if (depth >= maxDepth) {
                if (current.children.isNotEmpty()) output.append("  ".repeat(depth + 1)).append("… depth limit\n")
                return
            }
            current.children.forEach { visit(it, depth + 1) }
        }
        visit(node, 0)
        return output.toString().trimEnd()
    }

    fun path(node: DomNode): String {
        val components = ArrayDeque<String>()
        var current: DomNode? = node
        while (current != null) {
            val component = when (current) {
                is DocumentNode -> "#document"
                is ElementNode -> {
                    val element = current
                    val sameNameBefore = element.parent?.children.orEmpty().takeWhile { it !== element }.count { it is ElementNode && it.localName == element.localName }
                    val id = element.id?.let { "#$it" }.orEmpty()
                    "${element.localName}$id:nth-of-type(${sameNameBefore + 1})"
                }
                is TextNode -> "#text"
                is CommentNode -> "#comment"
                is DocumentTypeNode -> "!doctype"
                else -> current.nodeName
            }
            components.addFirst(component)
            current = current.parent
        }
        return components.joinToString(" > ")
    }

    private fun describe(node: DomNode, maxText: Int): String = when (node) {
        is DocumentNode -> "#document quirks=${node.quirksMode}"
        is DocumentTypeNode -> "<!DOCTYPE ${node.name}${node.publicId?.let { " PUBLIC \"${clip(it, maxText)}\"" }.orEmpty()}${node.systemId?.let { " \"${clip(it, maxText)}\"" }.orEmpty()}>"
        is ElementNode -> buildString {
            append('<').append(node.localName)
            node.attributes.values.forEach { append(' ').append(it.name).append("=\"").append(clip(it.value, maxText)).append('"') }
            if (node.namespace.name != "HTML") append(" ns=").append(node.namespace.name.lowercase())
            append('>')
        }
        is TextNode -> "#text \"${clip(node.data.replace("\n", "\\n"), maxText)}\""
        is CommentNode -> "<!--${clip(node.data, maxText)}-->"
        else -> node.nodeName
    }

    private fun clip(value: String, limit: Int): String = if (value.length <= limit) value else value.take(limit - 1) + "…"
}

data class DomSummary(
    val totalNodes: Int,
    val elements: Int,
    val textNodes: Int,
    val comments: Int,
    val doctypes: Int,
    val textCharacters: Long,
    val maxDepth: Int,
    val quirksMode: String
)
