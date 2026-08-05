package com.mohnishraj.aether.core.css

import com.mohnishraj.aether.core.html.HtmlEngine
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class CssFuzzTest {
    @Test fun deterministicMalformedCorpusNeverCrashesCascade() {
        val random = Random(4042026)
        val alphabet = "abcxyz#.-_:@{}[]();,! '0123456789%>+~\\\"\n"
        val css = CssEngine()
        val document = HtmlEngine().parse("<main id='app'><p class='x'>A</p><p data-x='Y'>B</p></main>").document
        repeat(2_000) { index ->
            val length = random.nextInt(0, 220)
            val source = buildString(length) { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
            val sheet = css.parse(source, "aether://css-fuzz/$index")
            val tree = css.compute(
                document,
                listOf(sheet),
                MediaEnvironment(random.nextInt(0, 2_000).toDouble(), random.nextInt(0, 2_000).toDouble())
            )
            assertTrue(tree.size >= 1)
        }
    }
}
