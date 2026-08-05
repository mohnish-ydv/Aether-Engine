package com.mohnishraj.aether.core.html

import com.mohnishraj.aether.core.html.dom.DocumentNode
import com.mohnishraj.aether.core.html.dom.ElementNode
import com.mohnishraj.aether.core.html.dom.TextNode
import com.mohnishraj.aether.core.html.inspect.DomInspector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DomNodeTest {
    @Test fun appendSetsParentAndSiblings() {
        val parent = ElementNode("div")
        val first = ElementNode("p")
        val second = ElementNode("span")
        parent.appendChild(first)
        parent.appendChild(second)
        assertSame(parent, first.parent)
        assertSame(second, first.nextSibling)
        assertSame(first, second.previousSibling)
    }

    @Test fun insertingBeforeMaintainsOrder() {
        val parent = ElementNode("div")
        val last = ElementNode("b")
        val first = ElementNode("a")
        parent.appendChild(last)
        parent.insertBefore(first, last)
        assertEquals(listOf(first, last), parent.children)
    }

    @Test fun reparentingDetachesFromOldParent() {
        val oldParent = ElementNode("div")
        val newParent = ElementNode("main")
        val child = ElementNode("p")
        oldParent.appendChild(child)
        newParent.appendChild(child)
        assertTrue(oldParent.children.isEmpty())
        assertSame(newParent, child.parent)
    }

    @Test fun cycleInsertionIsRejected() {
        val parent = ElementNode("div")
        val child = ElementNode("span")
        parent.appendChild(child)
        assertFailsWith<IllegalArgumentException> { child.appendChild(parent) }
    }

    @Test fun textAndCommentsCannotHaveChildren() {
        assertFailsWith<IllegalArgumentException> { TextNode("x").appendChild(ElementNode("b")) }
    }

    @Test fun removeChildClearsParent() {
        val parent = ElementNode("div")
        val child = ElementNode("p")
        parent.appendChild(child)
        assertSame(child, parent.removeChild(child))
        assertNull(child.parent)
        assertTrue(parent.children.isEmpty())
    }

    @Test fun replaceChildUpdatesBothParents() {
        val parent = ElementNode("div")
        val old = ElementNode("old")
        val replacement = ElementNode("new")
        parent.appendChild(old)
        parent.replaceChild(replacement, old)
        assertNull(old.parent)
        assertSame(parent, replacement.parent)
        assertEquals(listOf(replacement), parent.children)
    }

    @Test fun removeAllChildrenDetachesEveryNode() {
        val parent = ElementNode("div")
        val children = listOf(ElementNode("a"), ElementNode("b"))
        children.forEach(parent::appendChild)
        parent.removeAllChildren()
        assertTrue(parent.children.isEmpty())
        assertTrue(children.all { it.parent == null })
    }

    @Test fun htmlAttributesAreCaseInsensitive() {
        val element = ElementNode("DIV")
        element.setAttribute("DATA-X", "1")
        assertEquals("1", element.getAttribute("data-x"))
        assertTrue(element.hasAttribute("Data-X"))
        element.removeAttribute("DATA-x")
        assertFalse(element.hasAttribute("data-x"))
    }

    @Test fun classNamesSplitAsciiWhitespace() {
        val element = ElementNode("div")
        element.setAttribute("class", "one  two\tthree")
        assertEquals(setOf("one", "two", "three"), element.classNames)
    }

    @Test fun documentQueriesSearchDescendants() {
        val document = DocumentNode()
        val html = ElementNode("html")
        val body = ElementNode("body")
        val target = ElementNode("section").apply { setAttribute("id", "target") }
        document.appendChild(html)
        html.appendChild(body)
        body.appendChild(target)
        assertSame(target, document.getElementById("target"))
        assertEquals(listOf(target), document.getElementsByTagName("section"))
    }

    @Test fun descendantsAreDepthFirst() {
        val root = ElementNode("root")
        val a = ElementNode("a")
        val b = ElementNode("b")
        val c = ElementNode("c")
        root.appendChild(a)
        a.appendChild(b)
        root.appendChild(c)
        assertEquals(listOf(a, b, c), root.descendants().toList())
    }

    @Test fun textContentConcatenatesDescendants() {
        val root = ElementNode("div")
        root.appendChild(TextNode("A"))
        root.appendChild(ElementNode("span").apply { appendChild(TextNode("B")) })
        assertEquals("AB", root.textContent)
    }

    @Test fun inspectorPathContainsIdAndSiblingIndex() {
        val document = com.mohnishraj.aether.core.html.parser.HtmlParser().parse("<main><p>A</p><p id='b'>B</p></main>").document
        val target = document.getElementById("b") ?: error("missing")
        val path = DomInspector.path(target)
        assertTrue("p#b:nth-of-type(2)" in path)
        assertTrue(path.startsWith("#document"))
    }

    @Test fun childrenViewCannotBeMutatedThroughKotlinCast() {
        val element = ElementNode("div")
        element.appendChild(ElementNode("p"))
        @Suppress("UNCHECKED_CAST")
        val mutable = element.children as MutableList<ElementNode>
        assertFailsWith<UnsupportedOperationException> { mutable.clear() }
        assertEquals(1, element.children.size)
    }
}
