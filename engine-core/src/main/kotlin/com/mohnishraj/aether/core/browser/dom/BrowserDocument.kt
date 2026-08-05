package com.mohnishraj.aether.core.browser.dom

import com.mohnishraj.aether.core.browser.BrowserApiCounters
import com.mohnishraj.aether.core.browser.BrowserApiLimits
import com.mohnishraj.aether.core.browser.events.BrowserEvent
import com.mohnishraj.aether.core.browser.events.BrowserEventHub
import com.mohnishraj.aether.core.browser.events.BrowserEventListener
import com.mohnishraj.aether.core.browser.events.EventListenerHandle
import com.mohnishraj.aether.core.browser.events.EventListenerOptions
import com.mohnishraj.aether.core.browser.mutation.BrowserMutationHub
import com.mohnishraj.aether.core.browser.mutation.BrowserMutationObserver
import com.mohnishraj.aether.core.browser.mutation.MutationCallback
import com.mohnishraj.aether.core.browser.mutation.MutationObserverOptions
import com.mohnishraj.aether.core.browser.mutation.MutationRecord
import com.mohnishraj.aether.core.browser.mutation.MutationType
import com.mohnishraj.aether.core.css.selector.CssSelectorParser
import com.mohnishraj.aether.core.html.HtmlEngine
import com.mohnishraj.aether.core.html.SourceSpan
import com.mohnishraj.aether.core.html.dom.CommentNode
import com.mohnishraj.aether.core.html.dom.DocumentFragmentNode
import com.mohnishraj.aether.core.html.dom.DocumentNode
import com.mohnishraj.aether.core.html.dom.DocumentTypeNode
import com.mohnishraj.aether.core.html.dom.DomAttribute
import com.mohnishraj.aether.core.html.dom.DomNode
import com.mohnishraj.aether.core.html.dom.ElementNode
import com.mohnishraj.aether.core.html.dom.HtmlNamespace
import com.mohnishraj.aether.core.html.dom.TextNode
import com.mohnishraj.aether.core.html.inspect.HtmlSerializer
import java.util.Locale

class BrowserDocument internal constructor(
    val document: DocumentNode,
    private val html: HtmlEngine,
    private val limits: BrowserApiLimits,
    private val counters: BrowserApiCounters
) {
    val events = BrowserEventHub(limits, counters)
    val mutations = BrowserMutationHub(limits)
    private val selectorParser = CssSelectorParser()

    val documentElement: ElementNode? get() = document.documentElement
    val head: ElementNode? get() = document.head
    val body: ElementNode? get() = document.body

    fun getElementById(id: String): ElementNode? {
        counters.domQueries.incrementAndGet()
        return document.getElementById(id)
    }

    fun getElementsByTagName(name: String): List<ElementNode> {
        counters.domQueries.incrementAndGet()
        return document.getElementsByTagName(name).take(limits.maxSelectorMatches)
    }

    fun getElementsByClassName(name: String): List<ElementNode> {
        counters.domQueries.incrementAndGet()
        return document.descendants().filterIsInstance<ElementNode>()
            .filter { name in it.classNames }.take(limits.maxSelectorMatches).toList()
    }

    fun querySelector(selector: String, root: DomNode = document): ElementNode? = querySelectorAll(selector, root).firstOrNull()

    fun querySelectorAll(selector: String, root: DomNode = document): List<ElementNode> {
        val issues = mutableListOf<com.mohnishraj.aether.core.css.CssIssue>()
        val selectors = selectorParser.parseList(selector, issues)
        require(selectors.isNotEmpty() && issues.none { it.severity == com.mohnishraj.aether.core.css.CssIssueSeverity.ERROR }) {
            issues.firstOrNull()?.message ?: "Invalid selector"
        }
        counters.domQueries.incrementAndGet()
        return root.descendants(includeSelf = root is ElementNode).filterIsInstance<ElementNode>()
            .filter { element -> selectors.any { it.matches(element) } }
            .take(limits.maxSelectorMatches)
            .toList()
    }

    fun createElement(name: String, namespace: HtmlNamespace = HtmlNamespace.HTML): ElementNode {
        require(name.matches(Regex("[A-Za-z][A-Za-z0-9:_-]*"))) { "Invalid element name: $name" }
        return document.createElement(name, namespace)
    }

    fun createTextNode(data: String): TextNode = document.createTextNode(data)
    fun createComment(data: String): CommentNode = document.createComment(data)
    fun createDocumentFragment(): DocumentFragmentNode = document.createDocumentFragment()

    fun appendChild(parent: DomNode, child: DomNode): DomNode {
        parent.appendChild(child)
        notifyChildList(parent, added = listOf(child))
        return child
    }

    fun insertBefore(parent: DomNode, child: DomNode, reference: DomNode?): DomNode {
        parent.insertBefore(child, reference)
        notifyChildList(parent, added = listOf(child))
        return child
    }

    fun removeChild(parent: DomNode, child: DomNode): DomNode {
        parent.removeChild(child)
        notifyChildList(parent, removed = listOf(child))
        return child
    }

    fun replaceChild(parent: DomNode, newChild: DomNode, oldChild: DomNode): DomNode {
        parent.replaceChild(newChild, oldChild)
        notifyChildList(parent, added = listOf(newChild), removed = listOf(oldChild))
        return oldChild
    }

    fun setAttribute(element: ElementNode, name: String, value: String) {
        val oldValue = element.getAttribute(name)
        element.setAttribute(name, value)
        mutations.notify(MutationRecord(MutationType.ATTRIBUTES, element, attributeName = name.lowercase(Locale.ROOT), oldValue = oldValue))
        counters.domMutations.incrementAndGet()
    }

    fun removeAttribute(element: ElementNode, name: String): Boolean {
        val oldValue = element.getAttribute(name) ?: return false
        element.removeAttribute(name)
        mutations.notify(MutationRecord(MutationType.ATTRIBUTES, element, attributeName = name.lowercase(Locale.ROOT), oldValue = oldValue))
        counters.domMutations.incrementAndGet()
        return true
    }

    fun setTextContent(node: DomNode, value: String) {
        when (node) {
            is TextNode -> {
                val old = node.data
                node.replace(value)
                mutations.notify(MutationRecord(MutationType.CHARACTER_DATA, node, oldValue = old))
            }
            is CommentNode, is DocumentTypeNode -> error("${node.nodeName} text content is immutable")
            else -> {
                val removed = node.children.toList()
                node.removeAllChildren()
                val added = if (value.isEmpty()) emptyList() else listOf(document.createTextNode(value).also(node::appendChild))
                notifyChildList(node, added, removed)
            }
        }
        counters.domMutations.incrementAndGet()
    }

    fun setInnerHtml(element: ElementNode, markup: String) {
        val wrapperName = if (element.localName in setOf("html", "head", "body")) "div" else element.localName
        val parsed = html.parse("<!doctype html><html><body><$wrapperName>$markup</$wrapperName></body></html>", document.url).document
        val source = parsed.body?.children?.filterIsInstance<ElementNode>()?.firstOrNull()?.children.orEmpty()
        val removed = element.children.toList()
        element.removeAllChildren()
        val added = source.map(::cloneNode)
        added.forEach(element::appendChild)
        notifyChildList(element, added, removed)
        counters.domMutations.incrementAndGet()
    }

    fun innerHtml(node: DomNode): String = node.children.joinToString("") { HtmlSerializer.serialize(it) }
    fun outerHtml(node: DomNode): String = HtmlSerializer.serialize(node)

    fun addEventListener(
        target: DomNode,
        type: String,
        listener: BrowserEventListener,
        options: EventListenerOptions = EventListenerOptions()
    ): EventListenerHandle = events.addEventListener(target, type, listener, options)

    fun removeEventListener(target: DomNode, handle: EventListenerHandle): Boolean = events.removeEventListener(target, handle)
    fun dispatchEvent(target: DomNode, event: BrowserEvent): Boolean = events.dispatch(target, event)

    fun observe(target: DomNode, options: MutationObserverOptions, callback: MutationCallback): BrowserMutationObserver =
        mutations.observe(target, options, callback)

    fun disconnect(observer: BrowserMutationObserver) = mutations.disconnect(observer)
    fun deliverMutations(): Int = mutations.deliverAll()

    fun cloneNode(node: DomNode, deep: Boolean = true): DomNode {
        val clone: DomNode = when (node) {
            is ElementNode -> ElementNode(node.localName, node.namespace, SourceSpan.EMPTY).also { copy ->
                node.attributes.values.forEach { copy.setAttribute(DomAttribute(it.name, it.value, it.namespace, SourceSpan.EMPTY)) }
            }
            is TextNode -> TextNode(node.data)
            is CommentNode -> CommentNode(node.data)
            is DocumentTypeNode -> DocumentTypeNode(node.name, node.publicId, node.systemId)
            is DocumentFragmentNode -> DocumentFragmentNode()
            is DocumentNode -> DocumentNode(node.url)
        }
        if (deep && clone !is TextNode && clone !is CommentNode && clone !is DocumentTypeNode) {
            node.children.forEach { clone.appendChild(cloneNode(it, true)) }
        }
        return clone
    }

    private fun notifyChildList(target: DomNode, added: List<DomNode> = emptyList(), removed: List<DomNode> = emptyList()) {
        mutations.notify(MutationRecord(MutationType.CHILD_LIST, target, addedNodes = added, removedNodes = removed))
        counters.domMutations.incrementAndGet()
    }
}
