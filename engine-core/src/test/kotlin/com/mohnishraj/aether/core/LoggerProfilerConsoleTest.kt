package com.mohnishraj.aether.core

import com.mohnishraj.aether.core.crash.InMemoryCrashReporter
import com.mohnishraj.aether.core.devtools.BuiltinCommands
import com.mohnishraj.aether.core.devtools.CommandContext
import com.mohnishraj.aether.core.devtools.DevConsole
import com.mohnishraj.aether.core.fs.MemoryFileSystem
import com.mohnishraj.aether.core.log.EngineLogger
import com.mohnishraj.aether.core.profile.PerformanceProfiler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoggerProfilerConsoleTest {
    @Test fun loggerIsBounded() {
        val logger = EngineLogger(2)
        repeat(3) { logger.info("T", "$it") }
        assertEquals(listOf("1", "2"), logger.recent().map { it.message })
    }

    @Test fun profilerAggregatesSamples() {
        val profiler = PerformanceProfiler()
        profiler.record("layout", 10)
        profiler.record("layout", 30)
        assertEquals(20.0, profiler.metric("layout")?.averageNanos)
    }

    @Test fun consoleHandlesQuotedArguments() {
        val console = DevConsole().also(BuiltinCommands::install)
        val runtime = EngineRuntime(MemoryFileSystem(), EngineLogger(), InMemoryCrashReporter(), PerformanceProfiler(), console)
        runtime.start()
        val result = console.execute(CommandContext(runtime), "echo \"hello engine\"")
        assertTrue(result.success)
        assertEquals("hello engine", result.output)
    }
}
