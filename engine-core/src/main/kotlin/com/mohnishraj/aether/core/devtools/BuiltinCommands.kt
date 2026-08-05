package com.mohnishraj.aether.core.devtools

import com.mohnishraj.aether.core.BuildInfo
import com.mohnishraj.aether.core.css.MediaEnvironment
import com.mohnishraj.aether.core.css.inspect.CssInspector
import com.mohnishraj.aether.core.fs.VirtualPath
import com.mohnishraj.aether.core.html.inspect.DomInspector
import com.mohnishraj.aether.core.html.inspect.HtmlSerializer
import com.mohnishraj.aether.core.layout.LayoutViewport
import com.mohnishraj.aether.core.layout.inspect.LayoutInspector
import com.mohnishraj.aether.core.js.inspect.JsInspector
import com.mohnishraj.aether.core.log.LogLevel
import com.mohnishraj.aether.core.memory.RuntimeMemory
import com.mohnishraj.aether.core.net.model.AetherUrl
import com.mohnishraj.aether.core.paint.PaintInvalidationTracker
import com.mohnishraj.aether.core.paint.inspect.PaintInspector
import com.mohnishraj.aether.core.selftest.EngineSelfTest
import com.mohnishraj.aether.core.render.RenderViewport
import com.mohnishraj.aether.core.render.ScrollBehavior
import com.mohnishraj.aether.core.render.inspect.RenderInspector
import com.mohnishraj.aether.core.shell.BrowserTabId
import com.mohnishraj.aether.core.security.PermissionDecision
import com.mohnishraj.aether.core.security.PermissionFeature
import com.mohnishraj.aether.core.security.SecurityResourceType
import java.util.Locale
import kotlin.math.roundToLong

object BuiltinCommands {
    fun install(console: DevConsole) {
        console.register("help", "List developer commands") { ctx, _ ->
            CommandResult.ok(ctx.runtime.console.descriptions().entries.joinToString("\n") { (name, desc) -> name.padEnd(12) + desc })
        }
        console.register("about", "Show engine identity") { _, _ ->
            CommandResult.ok("${BuildInfo.ENGINE_NAME} ${BuildInfo.ENGINE_VERSION}\n${BuildInfo.MILESTONE}\nDeveloper: ${BuildInfo.DEVELOPER}\nWebView: ${BuildInfo.USES_WEBVIEW}")
        }
        console.register("status", "Show kernel state and service status") { ctx, _ ->
            val runtime = ctx.runtime
            CommandResult.ok(buildString {
                appendLine("kernel=${runtime.state}")
                appendLine("filesystem=ready")
                appendLine("network=${if (runtime.network != null) "ready" else "unavailable"}")
                appendLine("html=ready")
                appendLine("css=ready")
                appendLine("layout=ready")
                appendLine("paint=ready")
                appendLine("javascript=ready")
                appendLine("browser-api=ready")
                appendLine("render-pipeline=ready")
                appendLine("browser-shell=ready")
                appendLine("security-engine=ready")
                appendLine("logger.entries=${runtime.logger.size()}")
                appendLine("crashes=${runtime.crashReporter.recent().size}")
                append("profiler.metrics=${runtime.profiler.snapshots().size}")
            })
        }
        console.register("selftest", "Run all M1 through M11 engine checks") { ctx, _ ->
            val report = EngineSelfTest.run(ctx.runtime)
            CommandResult(report.passed, report.pretty())
        }
        console.register("ls", "List virtual files: ls [path]") { ctx, args ->
            val path = VirtualPath.of(args.firstOrNull() ?: "/")
            val entries = ctx.runtime.fileSystem.list(path)
            CommandResult.ok(if (entries.isEmpty()) "(empty)" else entries.joinToString("\n") {
                (if (it.isDirectory) "d" else "f") + " ${it.size.toString().padStart(8)} ${it.path}"
            })
        }
        console.register("cat", "Read UTF-8 file: cat <path>") { ctx, args ->
            if (args.isEmpty()) CommandResult.error("Usage: cat <path>")
            else CommandResult.ok(String(ctx.runtime.fileSystem.read(VirtualPath.of(args[0])), Charsets.UTF_8))
        }
        console.register("write", "Write text: write <path> <text>") { ctx, args ->
            if (args.size < 2) CommandResult.error("Usage: write <path> <text>")
            else {
                val path = VirtualPath.of(args[0])
                ctx.runtime.fileSystem.write(path, args.drop(1).joinToString(" ").toByteArray())
                ctx.runtime.logger.info("DevConsole", "Wrote $path")
                CommandResult.ok("wrote $path")
            }
        }
        console.register("rm", "Delete virtual path: rm <path>") { ctx, args ->
            if (args.isEmpty()) CommandResult.error("Usage: rm <path>")
            else CommandResult.ok(if (ctx.runtime.fileSystem.delete(VirtualPath.of(args[0]))) "deleted" else "not found")
        }
        console.register("memory", "Show JVM/ART memory snapshot") { _, _ ->
            val m = RuntimeMemory.snapshot()
            fun mb(v: Long) = "%.2f MiB".format(Locale.US, v / 1048576.0)
            CommandResult.ok("used=${mb(m.usedBytes)}\nfree=${mb(m.freeBytes)}\ntotal=${mb(m.totalBytes)}\nmax=${mb(m.maxBytes)}")
        }
        console.register("profile", "Show profiler metrics and counters") { ctx, _ ->
            val metrics = ctx.runtime.profiler.snapshots().joinToString("\n") {
                "${it.name}: samples=${it.samples} last=${it.lastNanos / 1_000_000.0}ms avg=${(it.averageNanos / 1_000_000.0 * 1000).roundToLong() / 1000.0}ms"
            }
            val counters = ctx.runtime.profiler.counterSnapshots().entries.joinToString("\n") { "${it.key}=${it.value}" }
            CommandResult.ok(listOf(metrics, counters).filter { it.isNotBlank() }.joinToString("\n").ifBlank { "(no samples)" })
        }
        console.register("logs", "Show logs: logs [count] [level]") { ctx, args ->
            val count = args.getOrNull(0)?.toIntOrNull()?.coerceIn(1, 500) ?: 50
            val level = args.getOrNull(1)?.uppercase(Locale.ROOT)?.let { runCatching { LogLevel.valueOf(it) }.getOrNull() } ?: LogLevel.TRACE
            CommandResult.ok(ctx.runtime.logger.recent(count, level).joinToString("\n") { it.format() }.ifBlank { "(no logs)" })
        }
        console.register("crashes", "Show captured crash envelopes") { ctx, _ ->
            val crashes = ctx.runtime.crashReporter.recent()
            CommandResult.ok(crashes.joinToString("\n\n") { "${it.id}\n${it.exceptionType}: ${it.message}\n${it.context}" }.ifBlank { "(no crashes)" })
        }
        console.register("net-status", "Show M2 networking counters") { ctx, _ ->
            val network = ctx.runtime.network ?: return@register CommandResult.error("Networking is unavailable")
            val stats = network.client.statistics()
            val cache = network.cache.stats()
            CommandResult.ok(buildString {
                appendLine("requests=${stats.requests} successes=${stats.successes} failures=${stats.failures}")
                appendLine("redirects=${stats.redirects} cacheHits=${stats.cacheHits}")
                appendLine("bytesSent=${stats.bytesSent} bytesReceived=${stats.bytesReceived}")
                append("cache.entries=${cache.entries} cache.bytes=${cache.bytes} cookies=${network.cookies.snapshot().size}")
            })
        }
        console.register("url", "Parse URL: url <https-url>") { _, args ->
            if (args.isEmpty()) CommandResult.error("Usage: url <https-url>")
            else runCatching {
                val url = AetherUrl.parse(args[0])
                CommandResult.ok("origin=${url.origin}\nhost=${url.host}\nport=${url.effectivePort}\ntarget=${url.requestTarget}\nsecure=${url.isSecure}")
            }.getOrElse { CommandResult.error(it.message ?: "Invalid URL") }
        }
        console.register("cookies", "List stored HTTP cookies") { ctx, _ ->
            val cookies = ctx.runtime.network?.cookies?.snapshot().orEmpty()
            CommandResult.ok(cookies.joinToString("\n") { "${it.name} domain=${it.domain} path=${it.path} secure=${it.secure}" }.ifBlank { "(no cookies)" })
        }
        console.register("cache", "Show HTTP cache statistics") { ctx, _ ->
            val cache = ctx.runtime.network?.cache?.stats() ?: return@register CommandResult.error("Networking is unavailable")
            CommandResult.ok("entries=${cache.entries}\nbytes=${cache.bytes}\nhits=${cache.hits}\nmisses=${cache.misses}")
        }
        console.register("net-clear", "Clear cookies and HTTP cache") { ctx, _ ->
            val network = ctx.runtime.network ?: return@register CommandResult.error("Networking is unavailable")
            network.clearPrivateData()
            CommandResult.ok("network private data cleared")
        }
        console.register("html-status", "Show M3 HTML parser counters") { ctx, _ ->
            val stats = ctx.runtime.html.statistics()
            CommandResult.ok(buildString {
                appendLine("documents=${stats.documentsParsed} inputChars=${stats.inputCharacters}")
                appendLine("tokens=${stats.tokensProduced} nodes=${stats.nodesProduced}")
                appendLine("issues=${stats.parseIssues}")
                append("lastParseMs=${"%.3f".format(Locale.US, stats.lastParseMillis)}")
            })
        }
        console.register("html-parse", "Parse inline HTML: html-parse <markup>") { ctx, args ->
            if (args.isEmpty()) CommandResult.error("Usage: html-parse <markup>")
            else {
                val result = ctx.runtime.html.parse(args.joinToString(" "))
                val summary = DomInspector.summarize(result.document)
                CommandResult.ok(buildString {
                    appendLine("tokens=${result.tokenCount} nodes=${result.nodeCount} issues=${result.issues.size}")
                    appendLine("quirks=${summary.quirksMode} depth=${summary.maxDepth} textChars=${summary.textCharacters}")
                    append(DomInspector.tree(result.document, maxDepth = 20))
                })
            }
        }
        console.register("html-file", "Parse UTF-8 file: html-file <virtual-path>") { ctx, args ->
            if (args.isEmpty()) CommandResult.error("Usage: html-file <virtual-path>")
            else runCatching {
                val source = String(ctx.runtime.fileSystem.read(VirtualPath.of(args[0])), Charsets.UTF_8)
                val result = ctx.runtime.html.parse(source)
                CommandResult.ok(DomInspector.tree(result.document, maxDepth = 32))
            }.getOrElse { CommandResult.error(it.message ?: "Unable to parse file") }
        }
        console.register("html-normalize", "Parse and serialize inline HTML") { ctx, args ->
            if (args.isEmpty()) CommandResult.error("Usage: html-normalize <markup>")
            else CommandResult.ok(HtmlSerializer.serialize(ctx.runtime.html.parse(args.joinToString(" ")).document))
        }
        console.register("css-status", "Show M4 CSS engine counters") { ctx, _ ->
            val stats = ctx.runtime.css.statistics()
            CommandResult.ok(buildString {
                appendLine("stylesheets=${stats.styleSheetsParsed} rules=${stats.rulesParsed}")
                appendLine("declarations=${stats.declarationsParsed} elementsStyled=${stats.elementsStyled}")
                appendLine("issues=${stats.issuesSeen}")
                append("lastOperationMs=${"%.3f".format(Locale.US, stats.lastOperationMillis)}")
            })
        }
        console.register("css-parse", "Parse inline CSS: css-parse <stylesheet>") { ctx, args ->
            if (args.isEmpty()) CommandResult.error("Usage: css-parse <stylesheet>")
            else CommandResult.ok(CssInspector.styleSheet(ctx.runtime.css.parse(args.joinToString(" "))))
        }
        console.register("css-file", "Parse CSS file: css-file <virtual-path>") { ctx, args ->
            if (args.isEmpty()) CommandResult.error("Usage: css-file <virtual-path>")
            else runCatching {
                val source = String(ctx.runtime.fileSystem.read(VirtualPath.of(args[0])), Charsets.UTF_8)
                CommandResult.ok(CssInspector.styleSheet(ctx.runtime.css.parse(source, args[0])))
            }.getOrElse { CommandResult.error(it.message ?: "Unable to parse CSS file") }
        }
        console.register("css-demo", "Compute CSS against a built-in DOM") { ctx, args ->
            val source = args.joinToString(" ").ifBlank { "#app { --accent: teal; color: var(--accent) } #app > p { font-weight: 700 }" }
            val document = ctx.runtime.html.parse("<main id='app'><p class='lead'>Aether CSS</p></main>").document
            val sheet = ctx.runtime.css.parse(source)
            val tree = ctx.runtime.css.compute(document, listOf(sheet), MediaEnvironment())
            CommandResult.ok(CssInspector.computedTree(document, tree))
        }
        console.register("layout-status", "Show M5 layout engine counters") { ctx, _ ->
            val stats = ctx.runtime.layout.statistics()
            CommandResult.ok(buildString {
                appendLine("layouts=${stats.layoutsCompleted} boxes=${stats.boxesProduced}")
                appendLine("lines=${stats.lineBoxesProduced} fragments=${stats.inlineFragmentsProduced}")
                appendLine("issues=${stats.issuesSeen}")
                append("lastLayoutMs=${"%.3f".format(Locale.US, stats.lastLayoutMillis)}")
            })
        }
        console.register("layout-demo", "Run built-in layout demo: layout-demo [width]") { ctx, args ->
            val width = args.firstOrNull()?.toDoubleOrNull()?.coerceIn(160.0, 4096.0) ?: 360.0
            val height = 800.0
            val document = ctx.runtime.html.parse("<main id='page'><h1>Aether Layout</h1><p>Block and inline formatting without WebView.</p><aside>ABS</aside></main>").document
            val css = ctx.runtime.css.parse("#page { width: 90%; margin: 12px auto; padding: 16px; border: 2px solid; overflow: auto } h1 { font-size: 28px; margin: 0 0 12px } p { width: 75%; line-height: 1.5 } aside { position: absolute; right: 8px; top: 8px; width: 48px; height: 24px; z-index: 3 }")
            val styles = ctx.runtime.css.compute(document, listOf(css), MediaEnvironment(width, height))
            val tree = ctx.runtime.layout.layout(document, styles, LayoutViewport(width, height))
            CommandResult.ok(LayoutInspector.tree(tree, maxDepth = 24))
        }
        console.register("layout-tree", "Layout inline HTML: layout-tree <width> <markup>") { ctx, args ->
            if (args.size < 2) CommandResult.error("Usage: layout-tree <width> <markup>")
            else {
                val width = args[0].toDoubleOrNull()?.coerceIn(160.0, 4096.0)
                    ?: return@register CommandResult.error("Width must be a number")
                val document = ctx.runtime.html.parse(args.drop(1).joinToString(" ")).document
                val sheet = ctx.runtime.css.parse("html, body { margin: 0; width: 100% } body { font-size: 16px; line-height: 1.4 }")
                val styles = ctx.runtime.css.compute(document, listOf(sheet), MediaEnvironment(width, 800.0))
                CommandResult.ok(LayoutInspector.tree(ctx.runtime.layout.layout(document, styles, LayoutViewport(width, 800.0))))
            }
        }
        console.register("layout-paint", "Show built-in demo paint order") { ctx, _ ->
            val document = ctx.runtime.html.parse("<main><div id='low'></div><div id='high'></div></main>").document
            val sheet = ctx.runtime.css.parse("div { position:absolute; width:20px; height:20px } #low { z-index:-1 } #high { z-index:9 }")
            val styles = ctx.runtime.css.compute(document, listOf(sheet), MediaEnvironment())
            val tree = ctx.runtime.layout.layout(document, styles, LayoutViewport())
            CommandResult.ok(LayoutInspector.paintOrder(tree))
        }
        console.register("paint-status", "Show M6 paint engine counters") { ctx, _ ->
            val stats = ctx.runtime.paint.statistics()
            CommandResult.ok(buildString {
                appendLine("displayLists=${stats.displayListsBuilt} commands=${stats.commandsProduced}")
                appendLine("text=${stats.textCommandsProduced} images=${stats.imageCommandsProduced}")
                appendLine("issues=${stats.issuesSeen}")
                append("lastPaintMs=${"%.3f".format(Locale.US, stats.lastPaintMillis)}")
            })
        }
        console.register("paint-demo", "Build the M6 demo display list: paint-demo [width]") { ctx, args ->
            val width = args.firstOrNull()?.toDoubleOrNull()?.coerceIn(160.0, 4096.0) ?: 360.0
            val height = 800.0
            val document = ctx.runtime.html.parse("<main id='card'><h1>Aether Paint</h1><p>Display lists without WebView.</p><img src='asset://aether.png' alt='Aether'></main>").document
            val sheet = ctx.runtime.css.parse("#card { width:88%; margin:18px auto; padding:18px; color:#eaf2ff; background-color:#101827; background-image:linear-gradient(135deg,#101827,#172554); border:2px solid #55e6c1; border-radius:18px; box-shadow:0 12px 28px rgba(0,0,0,.35) } h1 { color:#55e6c1; font-size:28px } p { color:#b8c7e0 } img { display:block; width:96px; height:56px; object-fit:cover; border-radius:10px }")
            val styles = ctx.runtime.css.compute(document, listOf(sheet), MediaEnvironment(width, height))
            val layout = ctx.runtime.layout.layout(document, styles, LayoutViewport(width, height))
            CommandResult.ok(PaintInspector.displayList(ctx.runtime.paint.paint(layout), maxCommands = 400))
        }
        console.register("paint-html", "Paint inline HTML: paint-html <width> <markup>") { ctx, args ->
            if (args.size < 2) CommandResult.error("Usage: paint-html <width> <markup>")
            else {
                val width = args[0].toDoubleOrNull()?.coerceIn(160.0, 4096.0)
                    ?: return@register CommandResult.error("Width must be a number")
                val document = ctx.runtime.html.parse(args.drop(1).joinToString(" ")).document
                val sheet = ctx.runtime.css.parse("html, body { margin:0; width:100% } body { color:#eaf2ff; background-color:#0b1020; font-size:16px; line-height:1.4 } * { box-sizing:border-box }")
                val styles = ctx.runtime.css.compute(document, listOf(sheet), MediaEnvironment(width, 800.0))
                val layout = ctx.runtime.layout.layout(document, styles, LayoutViewport(width, 800.0))
                CommandResult.ok(PaintInspector.displayList(ctx.runtime.paint.paint(layout)))
            }
        }
        console.register("paint-diff", "Compare unchanged and changed demo display lists") { ctx, _ ->
            fun list(color: String): com.mohnishraj.aether.core.paint.DisplayList {
                val document = ctx.runtime.html.parse("<div id='x'>Aether</div>").document
                val sheet = ctx.runtime.css.parse("#x { width:120px; height:48px; padding:8px; background-color:$color; border-radius:8px }")
                val styles = ctx.runtime.css.compute(document, listOf(sheet), MediaEnvironment())
                return ctx.runtime.paint.paint(ctx.runtime.layout.layout(document, styles, LayoutViewport()))
            }
            val before = list("#112233")
            val same = list("#112233")
            val changed = list("#334455")
            CommandResult.ok("same=${PaintInvalidationTracker.compare(before, same)}\nchanged=${PaintInvalidationTracker.compare(same, changed)}")
        }

        console.register("js-status", "Show M7 JavaScript runtime counters") { ctx, _ ->
            val stats = ctx.runtime.js.statistics()
            CommandResult.ok(buildString {
                appendLine("scripts=${stats.scriptsEvaluated} succeeded=${stats.scriptsSucceeded} failed=${stats.scriptsFailed}")
                appendLine("tokens=${stats.tokensProduced} astNodes=${stats.astNodesProduced}")
                appendLine("steps=${stats.stepsExecuted} tasks=${stats.tasksExecuted}")
                append("lastEvaluationMs=${"%.3f".format(Locale.US, stats.lastEvaluationMillis)}")
            })
        }
        console.register("js-run", "Evaluate JavaScript: js-run <source>") { ctx, args ->
            if (args.isEmpty()) CommandResult.error("Usage: js-run <source>")
            else {
                val result = ctx.runtime.js.evaluate(args.joinToString(" "), "aether://devtools")
                CommandResult(result.success, buildString {
                    appendLine(JsInspector.summary(result))
                    if (result.output.isNotEmpty()) {
                        appendLine("---------------- CONSOLE")
                        result.output.forEach { appendLine(it) }
                    }
                    result.error?.let { appendLine("---------------- ERROR"); append(it.pretty()) }
                }.trimEnd())
            }
        }
        console.register("js-file", "Evaluate JavaScript file: js-file <virtual-path>") { ctx, args ->
            if (args.isEmpty()) CommandResult.error("Usage: js-file <virtual-path>")
            else runCatching {
                val source = String(ctx.runtime.fileSystem.read(VirtualPath.of(args[0])), Charsets.UTF_8)
                val result = ctx.runtime.js.evaluate(source, args[0])
                CommandResult(result.success, buildString {
                    appendLine(JsInspector.summary(result))
                    if (result.output.isNotEmpty()) append(result.output.joinToString("\n", prefix = "---------------- CONSOLE\n"))
                    result.error?.let { append("\n---------------- ERROR\n${it.pretty()}") }
                }.trimEnd())
            }.getOrElse { CommandResult.error(it.message ?: "Unable to evaluate file") }
        }
        console.register("js-ast", "Parse JavaScript AST: js-ast <source>") { ctx, args ->
            if (args.isEmpty()) CommandResult.error("Usage: js-ast <source>")
            else {
                val parsed = ctx.runtime.js.compile(args.joinToString(" "))
                CommandResult(!parsed.hasErrors, buildString {
                    appendLine("tokens=${parsed.tokenCount} astNodes=${parsed.astNodeCount} issues=${parsed.issues.size}")
                    parsed.issues.forEach { appendLine(it) }
                    append(JsInspector.ast(parsed, maxDepth = 32).take(24_000))
                })
            }
        }
        console.register("js-time", "Advance JavaScript virtual time: js-time <ms>") { ctx, args ->
            val millis = args.firstOrNull()?.toLongOrNull()
            if (millis == null || millis < 0L) CommandResult.error("Usage: js-time <non-negative-ms>")
            else CommandResult.ok("executed=${ctx.runtime.js.advanceTimeBy(millis)}")
        }
        console.register("js-reset", "Reset persistent JavaScript realm") { ctx, _ ->
            ctx.runtime.js.resetRealm()
            CommandResult.ok("JavaScript realm reset")
        }

        console.register("render-status", "Show M9 rendering pipeline counters") { ctx, _ ->
            val stats = ctx.runtime.render.statistics()
            val frame = ctx.runtime.render.currentSession?.currentFrame
            CommandResult.ok(buildString {
                appendLine("sessions=${stats.sessionsCreated} frames=${stats.framesProduced} withinBudget=${stats.framesWithinBudget}")
                appendLine("style=${stats.stylePasses} layout=${stats.layoutPasses} paint=${stats.paintPasses} composite=${stats.compositePasses}")
                appendLine("coalescedRequests=${stats.droppedFrameRequests} lastFrameMs=${"%.3f".format(Locale.US, stats.lastFrameMillis)}")
                append("current=${frame?.let { "frame ${it.generation}, ${it.composition.layerCount} layers" } ?: "none"}")
            })
        }
        console.register("render-open", "Open and render HTML: render-open <markup>") { ctx, args ->
            if (args.isEmpty()) CommandResult.error("Usage: render-open <markup>")
            else {
                val page = ctx.runtime.browser.open(args.joinToString(" "), "https://render.devtools.aether/")
                val session = ctx.runtime.render.open(page, DEFAULT_RENDER_CSS, RenderViewport())
                CommandResult.ok(RenderInspector.summary(session.renderNow()))
            }
        }
        console.register("render-frame", "Inspect the current rendered frame") { ctx, _ ->
            val frame = ctx.runtime.render.currentSession?.currentFrame
                ?: return@register CommandResult.error("No rendered frame is available")
            CommandResult.ok(RenderInspector.summary(frame))
        }
        console.register("render-layers", "Inspect compositor layers") { ctx, _ ->
            val frame = ctx.runtime.render.currentSession?.currentFrame
                ?: return@register CommandResult.error("No rendered frame is available")
            CommandResult.ok(RenderInspector.layers(frame.composition))
        }
        console.register("render-damage", "Inspect current frame damage regions") { ctx, _ ->
            val frame = ctx.runtime.render.currentSession?.currentFrame
                ?: return@register CommandResult.error("No rendered frame is available")
            CommandResult.ok(RenderInspector.damage(frame.composition))
        }
        console.register("render-scroll", "Scroll current frame: render-scroll <y> [smooth]") { ctx, args ->
            val session = ctx.runtime.render.currentSession
                ?: return@register CommandResult.error("No rendering session is open")
            val y = args.firstOrNull()?.toDoubleOrNull()
                ?: return@register CommandResult.error("Usage: render-scroll <y> [smooth]")
            val behavior = if (args.getOrNull(1)?.equals("smooth", true) == true) ScrollBehavior.SMOOTH else ScrollBehavior.INSTANT
            session.scrollTo(0.0, y, behavior)
            CommandResult.ok(RenderInspector.summary(session.renderNow()))
        }
        console.register("render-css", "Replace current stylesheet: render-css <css>") { ctx, args ->
            val session = ctx.runtime.render.currentSession
                ?: return@register CommandResult.error("No rendering session is open")
            if (args.isEmpty()) CommandResult.error("Usage: render-css <css>")
            else {
                session.updateStyleSheet(args.joinToString(" "))
                CommandResult.ok(RenderInspector.summary(session.renderNow()))
            }
        }

        console.register("browser-status", "Show M8 Browser API counters") { ctx, _ ->
            val stats = ctx.runtime.browser.statistics()
            val page = ctx.runtime.browser.currentPage
            CommandResult.ok(buildString {
                appendLine("page=${page?.url ?: "none"}")
                appendLine("pages=${stats.pagesOpened} scripts=${stats.scriptsEvaluated} queries=${stats.domQueries} mutations=${stats.domMutations}")
                appendLine("events=${stats.eventsDispatched} storageWrites=${stats.storageWrites} fetches=${stats.fetches}")
                append("clipboardOps=${stats.clipboardOperations}")
            })
        }
        console.register("browser-open", "Open HTML page: browser-open <markup>") { ctx, args ->
            if (args.isEmpty()) CommandResult.error("Usage: browser-open <markup>")
            else {
                val page = ctx.runtime.browser.open(args.joinToString(" "), "https://devtools.aether/")
                CommandResult.ok("opened ${page.url} nodes=${page.document.document.descendants(includeSelf = true).count()}")
            }
        }
        console.register("dom-query", "Query current page: dom-query <selector>") { ctx, args ->
            val page = ctx.runtime.browser.currentPage ?: return@register CommandResult.error("No browser page is open")
            if (args.isEmpty()) CommandResult.error("Usage: dom-query <selector>")
            else {
                val matches = page.document.querySelectorAll(args.joinToString(" "))
                CommandResult.ok(matches.joinToString("\n") { "#${it.nodeId} ${it.nodeName} ${it.id.orEmpty()} ${it.textContent.take(80)}" }.ifBlank { "(no matches)" })
            }
        }
        console.register("dom-html", "Serialize current page node: dom-html <selector>") { ctx, args ->
            val page = ctx.runtime.browser.currentPage ?: return@register CommandResult.error("No browser page is open")
            if (args.isEmpty()) CommandResult.error("Usage: dom-html <selector>")
            else page.document.querySelector(args.joinToString(" "))?.let { CommandResult.ok(page.document.outerHtml(it)) }
                ?: CommandResult.error("No matching element")
        }
        console.register("browser-js", "Evaluate script with M8 APIs: browser-js <source>") { ctx, args ->
            val page = ctx.runtime.browser.currentPage ?: return@register CommandResult.error("No browser page is open")
            if (args.isEmpty()) CommandResult.error("Usage: browser-js <source>")
            else {
                val result = page.evaluate(args.joinToString(" "))
                CommandResult(result.success, buildString {
                    appendLine("result=${result.value.debugString()}")
                    result.output.forEach { appendLine(it) }
                    result.error?.let { append(it.pretty()) }
                }.trimEnd())
            }
        }
        console.register("storage-list", "List page storage: storage-list [local|session]") { ctx, args ->
            val page = ctx.runtime.browser.currentPage ?: return@register CommandResult.error("No browser page is open")
            val area = if (args.firstOrNull()?.equals("session", true) == true) page.sessionStorage else page.localStorage
            CommandResult.ok(area.snapshot().entries.joinToString("\n") { "${it.key}=${it.value}" }.ifBlank { "(empty)" })
        }
        console.register("form-data", "Serialize form: form-data <selector>") { ctx, args ->
            val page = ctx.runtime.browser.currentPage ?: return@register CommandResult.error("No browser page is open")
            val form = args.firstOrNull()?.let(page.document::querySelector)
                ?: return@register CommandResult.error("Usage: form-data <selector>")
            val submission = page.forms.serialize(form, page.url)
            CommandResult(submission.validation.valid, "${submission.method} ${submission.action}\n${submission.encodedBody}\nvalid=${submission.validation.valid}")
        }

        console.register("shell-status", "Show M11 tabs, navigation and security state") { ctx, _ ->
            val stats = ctx.runtime.shell.statistics()
            val active = ctx.runtime.shell.activeSnapshot()
            CommandResult.ok(buildString {
                appendLine("tabs=${ctx.runtime.shell.tabCount()} closed=${ctx.runtime.shell.closedTabCount()} active=${active?.id ?: "none"}")
                appendLine("url=${active?.url ?: "none"}")
                appendLine("state=${active?.loadState ?: "none"} history=${active?.historySize ?: 0} back=${active?.canGoBack ?: false} forward=${active?.canGoForward ?: false}")
                append("opened=${stats.tabsOpened} closedTotal=${stats.tabsClosed} navigations=${stats.navigationsCommitted}/${stats.navigationsStarted} failed=${stats.navigationsFailed}")
            })
        }
        console.register("shell-tabs", "List M11 browser tabs") { ctx, _ ->
            CommandResult.ok(ctx.runtime.shell.snapshots().joinToString("\n") {
                "${if (it.active) "*" else " "} ${it.id} ${it.loadState} ${it.title.take(40)} ${it.url}"
            }.ifBlank { "(no tabs)" })
        }
        console.register("shell-new", "Open tab: shell-new [URL or query]") { ctx, args ->
            val result = if (args.isEmpty()) ctx.runtime.shell.openTab("about:blank", viewport = RenderViewport())
            else ctx.runtime.shell.openTab(args.joinToString(" "), viewport = RenderViewport())
            CommandResult(result.committed, "tab=${result.tab.id} ${result.tab.title}\n${result.tab.url}")
        }
        console.register("shell-go", "Navigate active tab: shell-go <URL or query>") { ctx, args ->
            val active = ctx.runtime.shell.activeTabId()
                ?: return@register CommandResult.error("No active tab")
            if (args.isEmpty()) CommandResult.error("Usage: shell-go <URL or query>")
            else {
                val result = ctx.runtime.shell.navigate(active, args.joinToString(" "), viewport = RenderViewport())
                CommandResult(result.committed, "${result.tab.loadState} ${result.tab.title}\n${result.tab.url}")
            }
        }
        console.register("shell-back", "Navigate active tab backward") { ctx, _ ->
            val result = ctx.runtime.shell.goBack(RenderViewport())
                ?: return@register CommandResult.error("No back entry")
            CommandResult.ok("${result.tab.title}\n${result.tab.url}")
        }
        console.register("shell-forward", "Navigate active tab forward") { ctx, _ ->
            val result = ctx.runtime.shell.goForward(RenderViewport())
                ?: return@register CommandResult.error("No forward entry")
            CommandResult.ok("${result.tab.title}\n${result.tab.url}")
        }
        console.register("shell-reload", "Reload active tab") { ctx, _ ->
            val result = ctx.runtime.shell.reload(RenderViewport())
            CommandResult(result.committed, "${result.tab.loadState} ${result.tab.url}")
        }
        console.register("shell-close", "Close tab: shell-close [id]") { ctx, args ->
            val id = args.firstOrNull()?.toLongOrNull()?.let(::BrowserTabId)
                ?: ctx.runtime.shell.activeTabId()
                ?: return@register CommandResult.error("No active tab")
            val result = ctx.runtime.shell.closeTab(id, RenderViewport())
            CommandResult.ok("closed=$id active=${result?.tab?.id ?: "none"}")
        }
        console.register("shell-restore", "Reopen last closed tab") { ctx, _ ->
            val result = ctx.runtime.shell.reopenClosedTab(RenderViewport())
                ?: return@register CommandResult.error("No closed tab")
            CommandResult.ok("reopened=${result.tab.id} ${result.tab.url}")
        }

        console.register("security-status", "Show M11 security counters") { ctx, _ ->
            val stats = ctx.runtime.security.statistics()
            CommandResult.ok(buildString {
                appendLine("navigationChecks=${stats.navigationChecks} blocked=${stats.blockedNavigations} downgrades=${stats.downgradeBlocks}")
                appendLine("subresourceChecks=${stats.subresourceChecks} blocked=${stats.blockedSubresources}")
                append("csp=${stats.cspBlocks} mixed=${stats.mixedContentBlocks} cors=${stats.corsBlocks} permissions=${stats.permissionBlocks}")
            })
        }
        console.register("security-origin", "Compare origins: security-origin <url1> <url2>") { ctx, args ->
            if (args.size != 2) CommandResult.error("Usage: security-origin <url1> <url2>")
            else runCatching {
                CommandResult.ok("sameOrigin=${ctx.runtime.security.sameOrigin(args[0], args[1])}")
            }.getOrElse { CommandResult.error(it.message ?: "Invalid URL") }
        }
        console.register("security-csp", "Test CSP connect target: security-csp <document-url> <target-url> <policy>") { ctx, args ->
            if (args.size < 3) CommandResult.error("Usage: security-csp <document-url> <target-url> <policy>")
            else runCatching {
                val policy = ctx.runtime.security.buildDocumentPolicy(
                    args[0],
                    com.mohnishraj.aether.core.net.model.NetworkHeaders.of("Content-Security-Policy" to args.drop(2).joinToString(" "))
                )
                val decision = ctx.runtime.security.authorizeSubresource(policy, SecurityResourceType.CONNECT, args[1])
                CommandResult(
                    decision.allowed,
                    "allowed=${decision.allowed}\nreason=${decision.reason}\neffective=${decision.effectiveUrl ?: "none"}"
                )
            }.getOrElse { CommandResult.error(it.message ?: "Security evaluation failed") }
        }
        console.register("security-permission", "Set origin permission: security-permission <origin> <feature> <allow|deny|ask>") { ctx, args ->
            if (args.size != 3) CommandResult.error("Usage: security-permission <origin> <feature> <allow|deny|ask>")
            else runCatching {
                val feature = PermissionFeature.valueOf(args[1].uppercase(Locale.ROOT).replace('-', '_'))
                val decision = PermissionDecision.valueOf(args[2].uppercase(Locale.ROOT))
                ctx.runtime.security.setPermission(args[0], feature, decision)
                CommandResult.ok("${feature.name.lowercase()}=$decision for ${args[0]}")
            }.getOrElse { CommandResult.error(it.message ?: "Invalid permission") }
        }
        console.register("security-clear", "Clear all origin permission decisions") { ctx, _ ->
            ctx.runtime.security.clearPermissions()
            CommandResult.ok("security permission decisions cleared")
        }

        console.register("echo", "Echo arguments") { _, args -> CommandResult.ok(args.joinToString(" ")) }
        console.register("clear", "Clear console output") { _, _ -> CommandResult.clear() }
    }

    private const val DEFAULT_RENDER_CSS = "html,body{margin:0;width:100%;background:#0b1020;color:#eaf2ff;font-size:16px}body{padding:16px;box-sizing:border-box;min-height:1000px}*{box-sizing:border-box}"
}
