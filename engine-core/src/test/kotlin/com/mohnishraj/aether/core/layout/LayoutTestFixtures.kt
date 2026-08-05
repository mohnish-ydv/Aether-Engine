package com.mohnishraj.aether.core.layout

import com.mohnishraj.aether.core.css.CssEngine
import com.mohnishraj.aether.core.css.MediaEnvironment
import com.mohnishraj.aether.core.html.HtmlEngine

internal data class LayoutFixture(
    val html: HtmlEngine,
    val css: CssEngine,
    val engine: LayoutEngine,
    val document: com.mohnishraj.aether.core.html.dom.DocumentNode,
    val styles: com.mohnishraj.aether.core.css.StyleTree,
    val tree: LayoutTree
)

internal fun layoutFixture(
    markup: String,
    stylesheet: String = "",
    width: Double = 360.0,
    height: Double = 800.0
): LayoutFixture {
    val html = HtmlEngine()
    val css = CssEngine()
    val engine = LayoutEngine()
    val document = html.parse(markup).document
    val styles = css.compute(document, listOf(css.parse(stylesheet)), MediaEnvironment(width, height))
    val tree = engine.layout(document, styles, LayoutViewport(width, height))
    return LayoutFixture(html, css, engine, document, styles, tree)
}

internal fun LayoutFixture.box(id: String): LayoutBox = tree.boxFor(document.getElementById(id)!!)!!
