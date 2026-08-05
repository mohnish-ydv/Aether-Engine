package com.mohnishraj.aether.core.paint

import com.mohnishraj.aether.core.css.CssEngine
import com.mohnishraj.aether.core.css.MediaEnvironment
import com.mohnishraj.aether.core.html.HtmlEngine
import com.mohnishraj.aether.core.layout.LayoutEngine
import com.mohnishraj.aether.core.layout.LayoutViewport

internal data class PaintFixture(
    val html: HtmlEngine,
    val css: CssEngine,
    val layout: LayoutEngine,
    val engine: PaintEngine,
    val displayList: DisplayList
)

internal fun paintFixture(
    markup: String,
    stylesheet: String = "",
    width: Double = 360.0,
    height: Double = 800.0,
    limits: PaintLimits = PaintLimits(),
    documentUrl: String? = null
): PaintFixture {
    val html = HtmlEngine()
    val css = CssEngine()
    val layout = LayoutEngine()
    val engine = PaintEngine(limits = limits)
    val document = html.parse(markup, documentUrl).document
    val styles = css.compute(document, listOf(css.parse(stylesheet)), MediaEnvironment(width, height))
    val tree = layout.layout(document, styles, LayoutViewport(width, height))
    return PaintFixture(html, css, layout, engine, engine.paint(tree))
}
