package com.mohnishraj.aether.core.html

import com.mohnishraj.aether.core.html.dom.DomNode
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class HtmlFuzzTest {
    @Test fun deterministicMalformedCorpusNeverCrashesOrCorruptsTree() {
        val random = Random(20260802)
        val tags = listOf(
            "div", "p", "span", "a", "b", "ul", "li", "table", "tr", "td", "head", "body",
            "title", "script", "style", "svg", "circle", "math", "mi", "form", "button", "select", "option", "textarea", "custom"
        )
        val atoms = listOf("text", "&amp;", "&#0;", "<!--c-->", "<!doctype html>", "<", ">", "\u0000", "'", "\"")
        val engine = HtmlEngine()
        repeat(1_000) {
            val source = buildString {
                repeat(random.nextInt(1, 80)) {
                    when (random.nextInt(6)) {
                        0 -> {
                            val tag = tags.random(random)
                            append('<').append(tag)
                            if (random.nextBoolean()) append(" id='x'")
                            if (random.nextInt(6) == 0) append('/')
                            append('>')
                        }
                        1 -> append("</").append(tags.random(random)).append('>')
                        else -> append(atoms.random(random))
                    }
                }
            }
            val result = engine.parse(source)
            assertNotNull(result.document.documentElement)
            assertNotNull(result.document.head)
            assertNotNull(result.document.body)
            validateTree(result.document)
            assertEquals(result.document.descendants(includeSelf = true).count(), result.nodeCount)
        }
    }

    private fun validateTree(root: DomNode) {
        val ids = HashSet<Long>()
        fun visit(node: DomNode) {
            check(ids.add(node.nodeId)) { "cycle or duplicate DOM node" }
            node.children.forEach { child ->
                assertSame(node, child.parent)
                visit(child)
            }
        }
        visit(root)
    }
}
