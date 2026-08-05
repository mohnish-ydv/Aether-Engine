package com.mohnishraj.aether.core.html.inspect

import com.mohnishraj.aether.core.html.dom.CommentNode
import com.mohnishraj.aether.core.html.dom.DocumentNode
import com.mohnishraj.aether.core.html.dom.DocumentTypeNode
import com.mohnishraj.aether.core.html.dom.DomNode
import com.mohnishraj.aether.core.html.dom.ElementNode
import com.mohnishraj.aether.core.html.dom.TextNode

object HtmlSerializer {
    private val voidElements = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")
    private val rawTextElements = setOf("script", "style", "xmp", "iframe", "noembed", "noframes", "plaintext")

    fun serialize(node: DomNode): String = buildString { appendNode(node, this) }

    private fun appendNode(node: DomNode, output: StringBuilder) {
        when (node) {
            is DocumentNode -> node.children.forEach { appendNode(it, output) }
            is DocumentTypeNode -> {
                output.append("<!DOCTYPE ").append(node.name)
                node.publicId?.let { output.append(" PUBLIC \"").append(escapeAttribute(it)).append('"') }
                node.systemId?.let { if (node.publicId == null) output.append(" SYSTEM") ; output.append(" \"").append(escapeAttribute(it)).append('"') }
                output.append('>')
            }
            is CommentNode -> output.append("<!--").append(node.data.replace("--", "- -")).append("-->")
            is TextNode -> {
                val parentName = (node.parent as? ElementNode)?.localName
                output.append(if (parentName in rawTextElements) node.data else escapeText(node.data))
            }
            is ElementNode -> {
                output.append('<').append(node.localName)
                node.attributes.values.forEach { output.append(' ').append(it.name).append("=\"").append(escapeAttribute(it.value)).append('"') }
                output.append('>')
                if (node.localName !in voidElements) {
                    node.children.forEach { appendNode(it, output) }
                    output.append("</").append(node.localName).append('>')
                }
            }
            else -> node.children.forEach { appendNode(it, output) }
        }
    }

    private fun escapeText(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun escapeAttribute(value: String): String = escapeText(value).replace("\"", "&quot;")
}
