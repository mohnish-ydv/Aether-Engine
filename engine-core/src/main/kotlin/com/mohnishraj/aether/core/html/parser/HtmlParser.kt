package com.mohnishraj.aether.core.html.parser

import com.mohnishraj.aether.core.html.HtmlIssue
import com.mohnishraj.aether.core.html.HtmlIssueSeverity
import com.mohnishraj.aether.core.html.HtmlIssueStage
import com.mohnishraj.aether.core.html.HtmlLimits
import com.mohnishraj.aether.core.html.SourcePosition
import com.mohnishraj.aether.core.html.SourceSpan
import com.mohnishraj.aether.core.html.dom.CommentNode
import com.mohnishraj.aether.core.html.dom.DocumentNode
import com.mohnishraj.aether.core.html.dom.DocumentTypeNode
import com.mohnishraj.aether.core.html.dom.DomAttribute
import com.mohnishraj.aether.core.html.dom.DomNode
import com.mohnishraj.aether.core.html.dom.ElementNode
import com.mohnishraj.aether.core.html.dom.HtmlNamespace
import com.mohnishraj.aether.core.html.dom.QuirksMode
import com.mohnishraj.aether.core.html.dom.TextNode
import com.mohnishraj.aether.core.html.token.HtmlToken
import com.mohnishraj.aether.core.html.token.HtmlTokenizer
import java.util.Locale

/**
 * A defensive HTML tree builder for Aether's independent engine.
 *
 * M3 intentionally implements the high-value browser parsing core: implied html/head/body
 * nodes, common optional end tags, raw-text tokens, basic table/select recovery, foreign
 * namespaces and deterministic malformed-markup recovery. Later compatibility milestones
 * can extend this toward the complete WHATWG insertion-mode and adoption-agency algorithms.
 */
class HtmlParser(private val limits: HtmlLimits = HtmlLimits()) {
    fun parse(source: String, documentUrl: String? = null): HtmlParseResult {
        val started = System.nanoTime()
        val tokenization = HtmlTokenizer(source, limits).tokenize()
        val builder = TreeBuilder(DocumentNode(documentUrl, spanForDocument(source.length)), limits, tokenization.issues)
        tokenization.tokens.forEach(builder::process)
        val document = builder.finish()
        return HtmlParseResult(
            document = document,
            issues = builder.issues.toList(),
            tokenCount = tokenization.tokens.size,
            nodeCount = document.descendants(includeSelf = true).count(),
            elapsedNanos = System.nanoTime() - started
        )
    }

    private fun spanForDocument(length: Int): SourceSpan = SourceSpan(
        SourcePosition(0, 1, 1),
        SourcePosition(length, 1, length + 1)
    )
}

data class HtmlParseResult(
    val document: DocumentNode,
    val issues: List<HtmlIssue>,
    val tokenCount: Int,
    val nodeCount: Int,
    val elapsedNanos: Long
) {
    val succeeded: Boolean get() = issues.none { it.severity == HtmlIssueSeverity.ERROR }
    val parseErrorCount: Int get() = issues.size
}

private class TreeBuilder(
    private val document: DocumentNode,
    private val limits: HtmlLimits,
    initialIssues: List<HtmlIssue>
) {
    val issues = ArrayList<HtmlIssue>(initialIssues)
    private val openElements = ArrayList<ElementNode>()
    private var htmlElement: ElementNode? = null
    private var headElement: ElementNode? = null
    private var bodyElement: ElementNode? = null
    private var doctypeSeen = false
    private var nodeCount = 1
    private var eofSeen = false

    fun process(token: HtmlToken) {
        if (eofSeen) return
        when (token) {
            is HtmlToken.Doctype -> processDoctype(token)
            is HtmlToken.Comment -> appendComment(token)
            is HtmlToken.Text -> processText(token)
            is HtmlToken.StartTag -> processStartTag(token)
            is HtmlToken.EndTag -> processEndTag(token)
            is HtmlToken.Eof -> eofSeen = true
        }
    }

    fun finish(): DocumentNode {
        ensureHtml(SourceSpan.EMPTY)
        ensureHead(SourceSpan.EMPTY)
        ensureBody(SourceSpan.EMPTY)
        openElements.clear()
        if (!doctypeSeen) document.quirksMode = QuirksMode.QUIRKS
        return document
    }

    private fun processDoctype(token: HtmlToken.Doctype) {
        if (doctypeSeen || htmlElement != null) {
            issue("misplaced-doctype", "DOCTYPE is only accepted before the document element", token.sourceSpan)
            return
        }
        doctypeSeen = true
        appendNode(document, DocumentTypeNode(token.name, token.publicIdentifier, token.systemIdentifier, token.sourceSpan), token.sourceSpan)
        document.quirksMode = determineQuirksMode(token)
    }

    private fun determineQuirksMode(token: HtmlToken.Doctype): QuirksMode {
        if (token.forceQuirks || !token.name.equals("html", ignoreCase = true)) return QuirksMode.QUIRKS
        val publicId = token.publicIdentifier?.lowercase(Locale.ROOT).orEmpty()
        if (publicId.startsWith("-//w3c//dtd xhtml 1.0 transitional//") || publicId.startsWith("-//w3c//dtd xhtml 1.0 frameset//")) {
            return if (token.systemIdentifier == null) QuirksMode.QUIRKS else QuirksMode.LIMITED_QUIRKS
        }
        if (publicId.startsWith("-//w3c//dtd xhtml 1.0")) return QuirksMode.LIMITED_QUIRKS
        if (publicId.startsWith("-//w3c//dtd html 4.01 transitional//") || publicId.startsWith("-//w3c//dtd html 4.01 frameset//")) {
            return if (token.systemIdentifier == null) QuirksMode.QUIRKS else QuirksMode.LIMITED_QUIRKS
        }
        return QuirksMode.NO_QUIRKS
    }

    private fun appendComment(token: HtmlToken.Comment) {
        val parent = openElements.lastOrNull() ?: htmlElement ?: document
        appendNode(parent, CommentNode(token.data, token.sourceSpan), token.sourceSpan)
    }

    private fun processText(token: HtmlToken.Text) {
        if (token.data.isEmpty()) return
        val current = openElements.lastOrNull()
        if (current != null && current.localName in RAW_OR_HEAD_TEXT_ELEMENTS) {
            appendText(current, token.data, token.sourceSpan)
            return
        }
        if (bodyElement == null && token.data.isBlank()) {
            val parent = current ?: headElement ?: return
            appendText(parent, token.data, token.sourceSpan)
            return
        }
        if (bodyElement == null) {
            closeHeadIfOpen()
            ensureBody(token.sourceSpan)
        }
        appendText(openElements.lastOrNull() ?: bodyElement ?: ensureBody(token.sourceSpan), token.data, token.sourceSpan)
    }

    private fun processStartTag(token: HtmlToken.StartTag) {
        when (token.name) {
            "html" -> {
                val html = ensureHtml(token.sourceSpan)
                mergeAttributes(html, token)
                return
            }
            "head" -> {
                if (bodyElement != null || headElement != null) {
                    issue("duplicate-head", "A second or misplaced <head> tag was ignored", token.sourceSpan)
                    return
                }
                val head = ensureHead(token.sourceSpan)
                mergeAttributes(head, token)
                resetStackTo(head)
                return
            }
            "body" -> {
                val body = ensureBody(token.sourceSpan)
                mergeAttributes(body, token)
                resetStackTo(body)
                return
            }
        }

        if (bodyElement == null && isHeadElement(token.name)) {
            val head = ensureHead(token.sourceSpan)
            resetStackTo(head)
            val element = createElement(token, namespaceFor(token.name)) ?: return
            appendNode(head, element, token.sourceSpan)
            if (!isVoid(token.name) && !token.selfClosing) push(element, token.sourceSpan)
            return
        }

        closeHeadIfOpen()
        val body = ensureBody(token.sourceSpan)
        if (openElements.none { it === body }) resetStackTo(body)
        processBodyStartTag(token)
    }

    private fun processBodyStartTag(token: HtmlToken.StartTag) {
        val name = token.name
        when {
            name == "form" && hasInScope("form") -> {
                issue("nested-form", "Nested <form> element was ignored", token.sourceSpan)
                return
            }
            name == "p" -> closeIfOpen("p")
            name in BLOCK_START_CLOSES_P -> closeIfOpen("p")
            name == "li" -> {
                closeIfOpen("li")
                closeIfOpen("p")
            }
            name == "dt" || name == "dd" -> {
                closeIfOpen("dt")
                closeIfOpen("dd")
                closeIfOpen("p")
            }
            name in HEADING_ELEMENTS -> {
                closeIfOpen("p")
                closeFirstOpen(HEADING_ELEMENTS)
            }
            name == "a" && hasInScope("a") -> {
                issue("nested-anchor", "Nested <a> element forced the previous anchor closed", token.sourceSpan)
                closeIfOpen("a")
            }
            name == "button" && hasInScope("button") -> {
                issue("nested-button", "Nested <button> element forced the previous button closed", token.sourceSpan)
                closeIfOpen("button")
            }
            name == "option" -> closeIfOpen("option")
            name == "optgroup" -> {
                closeIfOpen("option")
                closeIfOpen("optgroup")
            }
            name == "tr" -> prepareTableRow(token.sourceSpan)
            name == "td" || name == "th" -> prepareTableCell(token.sourceSpan)
            name in TABLE_SECTION_ELEMENTS -> prepareTableSection(token.sourceSpan)
            name == "col" && currentName() != "colgroup" -> insertImplied("colgroup", token.sourceSpan)
        }

        val element = createElement(token, namespaceFor(name)) ?: return
        val parent = openElements.lastOrNull() ?: bodyElement ?: return
        appendNode(parent, element, token.sourceSpan)
        if (!isVoid(name) && !token.selfClosing) push(element, token.sourceSpan)
        if (token.selfClosing && !isVoid(name)) issue("self-closing-non-void", "Self-closing syntax was honored for non-void <$name>", token.sourceSpan)
    }

    private fun processEndTag(token: HtmlToken.EndTag) {
        val name = token.name
        when (name) {
            "html" -> {
                closeIfOpen("body")
                openElements.clear()
                return
            }
            "head" -> {
                if (!closeIfOpen("head")) issue("stray-end-tag", "No open <head> element", token.sourceSpan)
                return
            }
            "body" -> {
                if (!closeIfOpen("body")) issue("stray-end-tag", "No open <body> element", token.sourceSpan)
                return
            }
            "p" -> {
                if (!hasInScope("p")) {
                    issue("stray-p-end-tag", "A missing <p> start tag was synthesized", token.sourceSpan)
                    val implied = HtmlToken.StartTag("p", emptyList(), false, token.sourceSpan)
                    processBodyStartTag(implied)
                }
                closeIfOpen("p")
                return
            }
            "li", "dt", "dd", "option", "optgroup", "tr", "td", "th" -> {
                if (!closeIfOpen(name)) issue("stray-end-tag", "No open <$name> element", token.sourceSpan)
                return
            }
            "br" -> {
                issue("end-tag-br", "</br> was treated as <br>", token.sourceSpan)
                processBodyStartTag(HtmlToken.StartTag("br", emptyList(), true, token.sourceSpan))
                return
            }
        }

        val index = openElements.indexOfLast { it.localName == name }
        if (index < 0) {
            issue("stray-end-tag", "No open <$name> element", token.sourceSpan)
            return
        }
        if (index != openElements.lastIndex) {
            val implicitlyClosed = openElements.subList(index + 1, openElements.size).joinToString { it.localName }
            issue("misnested-end-tag", "Closing </$name> also closed: $implicitlyClosed", token.sourceSpan)
        }
        while (openElements.size > index) openElements.removeAt(openElements.lastIndex)
    }

    private fun ensureHtml(span: SourceSpan): ElementNode {
        htmlElement?.let { return it }
        val html = ElementNode("html", HtmlNamespace.HTML, span)
        appendNode(document, html, span)
        htmlElement = html
        openElements += html
        return html
    }

    private fun ensureHead(span: SourceSpan): ElementNode {
        headElement?.let { return it }
        val html = ensureHtml(span)
        val head = ElementNode("head", HtmlNamespace.HTML, span)
        val body = bodyElement
        if (body == null) appendNode(html, head, span) else html.insertBefore(head, body).also { nodeCount++ }
        headElement = head
        return head
    }

    private fun ensureBody(span: SourceSpan): ElementNode {
        bodyElement?.let { return it }
        val html = ensureHtml(span)
        ensureHead(span)
        val body = ElementNode("body", HtmlNamespace.HTML, span)
        appendNode(html, body, span)
        bodyElement = body
        resetStackTo(body)
        return body
    }

    private fun closeHeadIfOpen() {
        val index = openElements.indexOfLast { it.localName == "head" }
        if (index >= 0) while (openElements.size > index) openElements.removeAt(openElements.lastIndex)
    }

    private fun resetStackTo(element: ElementNode) {
        openElements.clear()
        htmlElement?.let(openElements::add)
        if (element !== htmlElement) openElements += element
    }

    private fun prepareTableSection(span: SourceSpan) {
        closeIfOpen("td")
        closeIfOpen("th")
        closeIfOpen("tr")
        if (!hasInScope("table")) {
            issue("table-section-outside-table", "Table section was wrapped in an implied <table>", span)
            insertImplied("table", span)
        }
    }

    private fun prepareTableRow(span: SourceSpan) {
        closeIfOpen("td")
        closeIfOpen("th")
        closeIfOpen("tr")
        if (currentName() !in TABLE_SECTION_ELEMENTS) {
            if (!hasInScope("table")) {
                issue("row-outside-table", "Table row was wrapped in an implied <table>", span)
                insertImplied("table", span)
            }
            insertImplied("tbody", span)
        }
    }

    private fun prepareTableCell(span: SourceSpan) {
        closeIfOpen("td")
        closeIfOpen("th")
        if (currentName() != "tr") {
            issue("cell-outside-row", "Table cell was wrapped in an implied <tr>", span)
            prepareTableRow(span)
            insertImplied("tr", span)
        }
    }

    private fun insertImplied(name: String, span: SourceSpan): ElementNode {
        val token = HtmlToken.StartTag(name, emptyList(), false, span)
        val element = createElement(token, HtmlNamespace.HTML) ?: error("node limit prevented required implied element")
        appendNode(openElements.lastOrNull() ?: ensureBody(span), element, span)
        push(element, span)
        return element
    }

    private fun namespaceFor(name: String): HtmlNamespace {
        if (name == "svg") return HtmlNamespace.SVG
        if (name == "math") return HtmlNamespace.MATHML
        return when (openElements.lastOrNull()?.namespace) {
            HtmlNamespace.SVG -> if (name == "foreignobject") HtmlNamespace.HTML else HtmlNamespace.SVG
            HtmlNamespace.MATHML -> HtmlNamespace.MATHML
            else -> HtmlNamespace.HTML
        }
    }

    private fun createElement(token: HtmlToken.StartTag, namespace: HtmlNamespace): ElementNode? {
        if (nodeCount >= limits.maxNodes) {
            issue("node-limit", "Document exceeded ${limits.maxNodes} DOM nodes", token.sourceSpan, HtmlIssueSeverity.ERROR, HtmlIssueStage.LIMIT)
            return null
        }
        val element = ElementNode(token.name, namespace, token.sourceSpan)
        token.attributes.forEach { element.setAttribute(DomAttribute(it.name, it.value, sourceSpan = it.sourceSpan)) }
        return element
    }

    private fun appendNode(parent: DomNode, child: DomNode, span: SourceSpan) {
        if (nodeCount >= limits.maxNodes) {
            issue("node-limit", "Document exceeded ${limits.maxNodes} DOM nodes", span, HtmlIssueSeverity.ERROR, HtmlIssueStage.LIMIT)
            return
        }
        parent.appendChild(child)
        nodeCount++
    }

    private fun appendText(parent: DomNode, value: String, span: SourceSpan) {
        if (value.isEmpty()) return
        val previous = parent.lastChild as? TextNode
        if (previous != null && previous.data.length + value.length <= limits.maxTextNodeChars) {
            previous.append(value)
            return
        }
        val clipped = if (value.length > limits.maxTextNodeChars) {
            issue("text-node-limit", "Text node was truncated to ${limits.maxTextNodeChars} characters", span, HtmlIssueSeverity.ERROR, HtmlIssueStage.LIMIT)
            value.take(limits.maxTextNodeChars)
        } else value
        appendNode(parent, TextNode(clipped, span), span)
    }

    private fun push(element: ElementNode, span: SourceSpan) {
        if (openElements.size >= limits.maxDepth) {
            issue("depth-limit", "DOM nesting exceeded ${limits.maxDepth} open elements", span, HtmlIssueSeverity.ERROR, HtmlIssueStage.LIMIT)
            return
        }
        openElements += element
    }

    private fun mergeAttributes(element: ElementNode, token: HtmlToken.StartTag) {
        token.attributes.forEach { if (!element.hasAttribute(it.name)) element.setAttribute(it.name, it.value, it.sourceSpan) }
    }

    private fun closeIfOpen(name: String): Boolean {
        val index = openElements.indexOfLast { it.localName == name }
        if (index < 0) return false
        while (openElements.size > index) openElements.removeAt(openElements.lastIndex)
        return true
    }

    private fun closeFirstOpen(names: Set<String>): Boolean {
        val index = openElements.indexOfLast { it.localName in names }
        if (index < 0) return false
        while (openElements.size > index) openElements.removeAt(openElements.lastIndex)
        return true
    }

    private fun hasInScope(name: String): Boolean = openElements.any { it.localName == name }
    private fun currentName(): String? = openElements.lastOrNull()?.localName

    private fun isHeadElement(name: String): Boolean = name in HEAD_ELEMENTS
    private fun isVoid(name: String): Boolean = name in VOID_ELEMENTS

    private fun issue(
        code: String,
        message: String,
        span: SourceSpan,
        severity: HtmlIssueSeverity = HtmlIssueSeverity.WARNING,
        stage: HtmlIssueStage = HtmlIssueStage.TREE_BUILDER
    ) {
        issues += HtmlIssue(stage, code, message, span.start, severity)
    }

    companion object {
        private val VOID_ELEMENTS = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")
        private val HEAD_ELEMENTS = setOf("base", "basefont", "bgsound", "link", "meta", "title", "noscript", "noframes", "style", "script", "template")
        private val RAW_OR_HEAD_TEXT_ELEMENTS = setOf("title", "style", "script", "noscript", "noframes", "textarea", "xmp", "iframe", "noembed", "plaintext")
        private val HEADING_ELEMENTS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
        private val TABLE_SECTION_ELEMENTS = setOf("tbody", "thead", "tfoot")
        private val BLOCK_START_CLOSES_P = setOf(
            "address", "article", "aside", "blockquote", "div", "dl", "fieldset", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6",
            "header", "hgroup", "hr", "main", "menu", "nav", "ol", "p", "pre", "section", "table", "ul"
        )
    }
}
