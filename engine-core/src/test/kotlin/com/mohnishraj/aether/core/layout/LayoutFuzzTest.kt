package com.mohnishraj.aether.core.layout

import com.mohnishraj.aether.core.css.CssEngine
import com.mohnishraj.aether.core.css.MediaEnvironment
import com.mohnishraj.aether.core.html.HtmlEngine
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class LayoutFuzzTest {
    @Test fun oneThousandGeneratedDocumentsKeepFiniteGeometryAndStableInvariants() {
        val random = Random(0xA37E5)
        val html = HtmlEngine()
        val css = CssEngine()
        val layout = LayoutEngine()
        repeat(1_000) { index ->
            val width = random.nextInt(160, 1200).toDouble()
            val childCount = random.nextInt(1, 8)
            val markup = buildString {
                append("<main id='root'>")
                repeat(childCount) { child ->
                    val tag = listOf("div", "p", "section", "span", "img")[random.nextInt(5)]
                    append('<').append(tag).append(" id='n").append(child).append("'>")
                    append("Aether ").append(index).append(' ').append("x".repeat(random.nextInt(0, 60)))
                    append("</").append(tag).append('>')
                }
                append("</main>")
            }
            val stylesheet = buildString {
                append("#root { width:").append(random.nextInt(30, 101)).append("%; padding:").append(random.nextInt(0, 20)).append("px; overflow:")
                append(listOf("visible", "hidden", "auto")[random.nextInt(3)]).append(" }")
                repeat(childCount) { child ->
                    append(" #n").append(child).append(" { display:")
                    append(listOf("block", "inline", "inline-block")[random.nextInt(3)])
                    append("; width:").append(random.nextInt(10, 260)).append("px; margin:").append(random.nextInt(-5, 20)).append("px; ")
                    if (random.nextInt(8) == 0) append("position:relative; left:").append(random.nextInt(-20, 21)).append("px; ")
                    append('}')
                }
            }
            val document = html.parse(markup).document
            val styleTree = css.compute(document, listOf(css.parse(stylesheet)), MediaEnvironment(width, 800.0))
            val tree = layout.layout(document, styleTree, LayoutViewport(width, 800.0))
            assertTrue(tree.paintOrder.distinct().size == tree.paintOrder.size)
            tree.paintOrder.forEach { box ->
                assertTrue(box.borderBox.x.isFinite() && box.borderBox.y.isFinite())
                assertTrue(box.borderBox.width.isFinite() && box.borderBox.height.isFinite())
                assertTrue(box.borderBox.width >= 0.0 && box.borderBox.height >= 0.0)
            }
        }
    }
}
