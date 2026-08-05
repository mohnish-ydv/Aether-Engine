package com.mohnishraj.aether.core.html.dom

import com.mohnishraj.aether.core.html.SourceSpan
import java.util.ArrayDeque
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class DomNodeType { DOCUMENT, DOCUMENT_TYPE, ELEMENT, TEXT, COMMENT, DOCUMENT_FRAGMENT }
enum class HtmlNamespace(val uri: String) {
    HTML("http://www.w3.org/1999/xhtml"),
    SVG("http://www.w3.org/2000/svg"),
    MATHML("http://www.w3.org/1998/Math/MathML")
}

enum class QuirksMode { NO_QUIRKS, LIMITED_QUIRKS, QUIRKS }

data class DomAttribute(
    val name: String,
    val value: String,
    val namespace: String? = null,
    val sourceSpan: SourceSpan = SourceSpan.EMPTY
)

sealed class DomNode(
    val nodeType: DomNodeType,
    open val sourceSpan: SourceSpan
) {
    val nodeId: Long = NEXT_ID.incrementAndGet()
    private val mutableChildren = ArrayList<DomNode>()
    private var mutableParent: DomNode? = null

    val parent: DomNode? get() = mutableParent
    val children: List<DomNode> get() = Collections.unmodifiableList(mutableChildren)
    val firstChild: DomNode? get() = mutableChildren.firstOrNull()
    val lastChild: DomNode? get() = mutableChildren.lastOrNull()
    val previousSibling: DomNode?
        get() = mutableParent?.mutableChildren?.let { siblings ->
            val index = siblings.indexOf(this)
            if (index > 0) siblings[index - 1] else null
        }
    val nextSibling: DomNode?
        get() = mutableParent?.mutableChildren?.let { siblings ->
            val index = siblings.indexOf(this)
            if (index >= 0 && index + 1 < siblings.size) siblings[index + 1] else null
        }

    abstract val nodeName: String

    open val textContent: String
        get() = mutableChildren.joinToString(separator = "") { it.textContent }

    fun appendChild(child: DomNode): DomNode = insertBefore(child, null)

    fun insertBefore(child: DomNode, reference: DomNode?): DomNode {
        require(nodeType != DomNodeType.TEXT && nodeType != DomNodeType.COMMENT && nodeType != DomNodeType.DOCUMENT_TYPE) {
            "$nodeName cannot have children"
        }
        require(child !== this) { "a node cannot contain itself" }
        require(!child.containsNode(this)) { "insertion would create a cycle" }
        require(reference == null || reference.mutableParent === this) { "reference is not a child of this node" }
        if (child is DocumentNode) error("a document cannot be inserted as a child")

        child.mutableParent?.removeChild(child)
        val index = reference?.let(mutableChildren::indexOf) ?: mutableChildren.size
        mutableChildren.add(index, child)
        child.mutableParent = this
        return child
    }

    fun removeChild(child: DomNode): DomNode {
        val index = mutableChildren.indexOf(child)
        require(index >= 0) { "node is not a child of $nodeName" }
        mutableChildren.removeAt(index)
        child.mutableParent = null
        return child
    }

    fun replaceChild(newChild: DomNode, oldChild: DomNode): DomNode {
        val index = mutableChildren.indexOf(oldChild)
        require(index >= 0) { "node is not a child of $nodeName" }
        require(newChild !== this && !newChild.containsNode(this)) { "replacement would create a cycle" }
        newChild.mutableParent?.removeChild(newChild)
        mutableChildren[index] = newChild
        newChild.mutableParent = this
        oldChild.mutableParent = null
        return oldChild
    }

    fun removeAllChildren() {
        mutableChildren.forEach { it.mutableParent = null }
        mutableChildren.clear()
    }

    fun descendants(includeSelf: Boolean = false): Sequence<DomNode> = sequence {
        if (includeSelf) yield(this@DomNode)
        val stack = ArrayDeque<DomNode>()
        for (index in mutableChildren.indices.reversed()) stack.addLast(mutableChildren[index])
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            yield(current)
            for (index in current.mutableChildren.indices.reversed()) stack.addLast(current.mutableChildren[index])
        }
    }

    fun root(): DomNode = generateSequence(this) { it.parent }.last()

    internal fun mutableChildList(): MutableList<DomNode> = mutableChildren

    private fun containsNode(target: DomNode): Boolean = this === target || descendants().any { it === target }

    companion object {
        private val NEXT_ID = AtomicLong(0)
    }
}

class DocumentNode(
    val url: String? = null,
    override val sourceSpan: SourceSpan = SourceSpan.EMPTY
) : DomNode(DomNodeType.DOCUMENT, sourceSpan) {
    override val nodeName: String = "#document"
    var quirksMode: QuirksMode = QuirksMode.QUIRKS
        internal set

    val documentElement: ElementNode? get() = children.filterIsInstance<ElementNode>().firstOrNull { it.localName == "html" }
    val head: ElementNode? get() = documentElement?.children?.filterIsInstance<ElementNode>()?.firstOrNull { it.localName == "head" }
    val body: ElementNode? get() = documentElement?.children?.filterIsInstance<ElementNode>()?.firstOrNull { it.localName == "body" }

    fun createElement(name: String, namespace: HtmlNamespace = HtmlNamespace.HTML, span: SourceSpan = SourceSpan.EMPTY): ElementNode =
        ElementNode(name, namespace, span)

    fun createTextNode(data: String, span: SourceSpan = SourceSpan.EMPTY): TextNode = TextNode(data, span)
    fun createComment(data: String, span: SourceSpan = SourceSpan.EMPTY): CommentNode = CommentNode(data, span)
    fun createDocumentFragment(): DocumentFragmentNode = DocumentFragmentNode()

    fun getElementById(id: String): ElementNode? = descendants().filterIsInstance<ElementNode>().firstOrNull { it.id == id }

    fun getElementsByTagName(name: String): List<ElementNode> {
        val normalized = name.lowercase(Locale.ROOT)
        return descendants().filterIsInstance<ElementNode>().filter { normalized == "*" || it.localName == normalized }.toList()
    }
}

class DocumentFragmentNode(
    override val sourceSpan: SourceSpan = SourceSpan.EMPTY
) : DomNode(DomNodeType.DOCUMENT_FRAGMENT, sourceSpan) {
    override val nodeName: String = "#document-fragment"
}

class DocumentTypeNode(
    val name: String,
    val publicId: String? = null,
    val systemId: String? = null,
    override val sourceSpan: SourceSpan = SourceSpan.EMPTY
) : DomNode(DomNodeType.DOCUMENT_TYPE, sourceSpan) {
    override val nodeName: String = name
    override val textContent: String = ""
}

class ElementNode(
    name: String,
    val namespace: HtmlNamespace = HtmlNamespace.HTML,
    override val sourceSpan: SourceSpan = SourceSpan.EMPTY
) : DomNode(DomNodeType.ELEMENT, sourceSpan) {
    val localName: String = if (namespace == HtmlNamespace.HTML) name.lowercase(Locale.ROOT) else name
    override val nodeName: String get() = localName.uppercase(Locale.ROOT)
    private val mutableAttributes = LinkedHashMap<String, DomAttribute>()
    val attributes: Map<String, DomAttribute> get() = Collections.unmodifiableMap(mutableAttributes)

    val id: String? get() = getAttribute("id")
    val classNames: Set<String>
        get() = getAttribute("class").orEmpty().split(Regex("\\s+")).filter(String::isNotBlank).toSet()

    fun setAttribute(name: String, value: String, span: SourceSpan = SourceSpan.EMPTY) {
        val normalized = normalizeAttributeName(name)
        mutableAttributes[normalized] = DomAttribute(normalized, value, sourceSpan = span)
    }

    fun setAttribute(attribute: DomAttribute) {
        val normalized = normalizeAttributeName(attribute.name)
        mutableAttributes[normalized] = if (normalized == attribute.name) attribute else attribute.copy(name = normalized)
    }

    fun getAttribute(name: String): String? = mutableAttributes[normalizeAttributeName(name)]?.value
    fun hasAttribute(name: String): Boolean = mutableAttributes.containsKey(normalizeAttributeName(name))
    fun removeAttribute(name: String): DomAttribute? = mutableAttributes.remove(normalizeAttributeName(name))

    fun getElementsByTagName(name: String): List<ElementNode> {
        val normalized = name.lowercase(Locale.ROOT)
        return descendants().filterIsInstance<ElementNode>().filter { normalized == "*" || it.localName == normalized }.toList()
    }

    fun closest(localName: String): ElementNode? {
        val wanted = localName.lowercase(Locale.ROOT)
        return generateSequence(this as DomNode?) { it.parent }.filterIsInstance<ElementNode>().firstOrNull { it.localName == wanted }
    }

    private fun normalizeAttributeName(name: String): String = if (namespace == HtmlNamespace.HTML) name.lowercase(Locale.ROOT) else name
}

class TextNode(
    data: String,
    override val sourceSpan: SourceSpan = SourceSpan.EMPTY
) : DomNode(DomNodeType.TEXT, sourceSpan) {
    var data: String = data
        private set
    override val nodeName: String = "#text"
    override val textContent: String get() = data

    fun append(value: String) {
        data += value
    }

    fun replace(value: String) {
        data = value
    }
}

class CommentNode(
    val data: String,
    override val sourceSpan: SourceSpan = SourceSpan.EMPTY
) : DomNode(DomNodeType.COMMENT, sourceSpan) {
    override val nodeName: String = "#comment"
    override val textContent: String = ""
}
