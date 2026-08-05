package com.mohnishraj.aether.core.render

import com.mohnishraj.aether.core.EngineRuntime
import com.mohnishraj.aether.core.browser.browserRuntime

internal const val RENDER_HTML = """<!doctype html><html><body><header id='fixed'>Aether</header><main><h1 id='title'>Render</h1><p id='text'>Pipeline test</p><div id='deep'>Bottom</div></main></body></html>"""
internal const val RENDER_CSS = """
html,body{margin:0;width:100%;background:#0b1020;color:#eaf2ff;font-size:16px}
body{min-height:1600px}
#fixed{position:fixed;top:0;left:0;width:100%;height:44px;background:#14213d;z-index:5}
main{padding:64px 12px}
#title{padding:10px;background:#243b67;border-radius:8px}
#deep{margin-top:1100px;height:100px;background:#55e6c1;opacity:.8}
"""

internal data class RenderFixture(
    val runtime: EngineRuntime,
    val session: RenderSession,
    val first: RenderFrame
)

internal fun renderFixture(viewport: RenderViewport = RenderViewport(360.0, 640.0)): RenderFixture {
    val runtime = browserRuntime()
    val page = runtime.browser.open(RENDER_HTML, "https://render.test/")
    val session = runtime.render.open(page, RENDER_CSS, viewport)
    return RenderFixture(runtime, session, session.renderNow(1_000_000_000L))
}
