package com.mohnishraj.aether.core.html

import com.mohnishraj.aether.core.EngineRuntime
import com.mohnishraj.aether.core.crash.InMemoryCrashReporter
import com.mohnishraj.aether.core.devtools.BuiltinCommands
import com.mohnishraj.aether.core.devtools.CommandContext
import com.mohnishraj.aether.core.devtools.DevConsole
import com.mohnishraj.aether.core.fs.MemoryFileSystem
import com.mohnishraj.aether.core.fs.VirtualPath
import com.mohnishraj.aether.core.log.EngineLogger
import com.mohnishraj.aether.core.profile.PerformanceProfiler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HtmlEngineTest {
    @Test fun statisticsAccumulateAcrossDocuments() {
        val engine = HtmlEngine()
        engine.parse("<p>A")
        engine.parse("<p>B")
        val stats = engine.statistics()
        assertEquals(2, stats.documentsParsed)
        assertTrue(stats.inputCharacters >= 8)
        assertTrue(stats.tokensProduced >= 6)
        assertTrue(stats.nodesProduced >= 10)
    }

    @Test fun parserRecordsProfilerMetrics() {
        val profiler = PerformanceProfiler()
        val engine = HtmlEngine(profiler = profiler)
        engine.parse("<main>x</main>")
        assertEquals(1, profiler.metric("html.parse")?.samples)
        assertEquals(1, profiler.counterSnapshots()["html.documents"])
    }

    @Test fun parserLogsRecoverableIssues() {
        val logger = EngineLogger()
        val engine = HtmlEngine(logger = logger)
        engine.parse("<div></ghost>")
        assertTrue(logger.recent().any { it.tag == "HTML" })
    }

    @Test fun developerConsoleParsesInlineHtml() {
        val runtime = runtime()
        val result = runtime.console.execute(CommandContext(runtime), "html-parse '<p id=demo>Hello</p>'")
        assertTrue(result.success)
        assertTrue("<p id=\"demo\">" in result.output)
    }

    @Test fun developerConsoleParsesVirtualFile() {
        val runtime = runtime()
        runtime.fileSystem.write(VirtualPath.of("/pages/test.html"), "<h1>File</h1>".toByteArray())
        val result = runtime.console.execute(CommandContext(runtime), "html-file /pages/test.html")
        assertTrue(result.success)
        assertTrue("<h1>" in result.output)
    }

    @Test fun developerConsoleNormalizesMarkup() {
        val runtime = runtime()
        val result = runtime.console.execute(CommandContext(runtime), "html-normalize '<p>A &amp; B'")
        assertTrue(result.success)
        assertTrue("A &amp; B" in result.output)
        assertTrue("</p>" in result.output)
    }

    private fun runtime(): EngineRuntime {
        val logger = EngineLogger()
        val profiler = PerformanceProfiler()
        val console = DevConsole().also(BuiltinCommands::install)
        return EngineRuntime(MemoryFileSystem(), logger, InMemoryCrashReporter(), profiler, console).also { it.start() }
    }
}
