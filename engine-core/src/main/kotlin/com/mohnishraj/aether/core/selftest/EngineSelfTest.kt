package com.mohnishraj.aether.core.selftest

import com.mohnishraj.aether.core.BuildInfo
import com.mohnishraj.aether.core.browser.selftest.BrowserApiSelfTest
import com.mohnishraj.aether.core.EngineRuntime
import com.mohnishraj.aether.core.devtools.CommandContext
import com.mohnishraj.aether.core.css.selftest.CssSelfTest
import com.mohnishraj.aether.core.fs.VirtualPath
import com.mohnishraj.aether.core.html.selftest.HtmlSelfTest
import com.mohnishraj.aether.core.layout.selftest.LayoutSelfTest
import com.mohnishraj.aether.core.js.selftest.JsSelfTest
import com.mohnishraj.aether.core.memory.ByteArena
import com.mohnishraj.aether.core.net.selftest.NetworkSelfTest
import com.mohnishraj.aether.core.paint.selftest.PaintSelfTest
import com.mohnishraj.aether.core.render.selftest.RenderSelfTest
import com.mohnishraj.aether.core.shell.selftest.BrowserShellSelfTest
import com.mohnishraj.aether.core.security.selftest.SecuritySelfTest
import com.mohnishraj.aether.core.crash.CrashEnvelope
import com.mohnishraj.aether.core.crash.CrashEnvelopeCodec
import com.mohnishraj.aether.core.text.Utf8String

data class SelfTestCheck(val name: String, val passed: Boolean, val detail: String)

data class SelfTestReport(val checks: List<SelfTestCheck>, val elapsedNanos: Long) {
    val passed: Boolean get() = checks.all { it.passed }
    fun pretty(): String = buildString {
        appendLine("AETHER M11 SELF-TEST")
        appendLine("===================")
        checks.forEach { appendLine("${if (it.passed) "PASS" else "FAIL"}  ${it.name} — ${it.detail}") }
        appendLine("-------------------")
        append("RESULT: ${if (passed) "PASS" else "FAIL"} (${checks.count { it.passed }}/${checks.size}) in ${elapsedNanos / 1_000_000.0} ms")
    }
}

object EngineSelfTest {
    fun run(runtime: EngineRuntime): SelfTestReport {
        val started = System.nanoTime()
        val checks = mutableListOf<SelfTestCheck>()
        fun check(name: String, block: () -> String) {
            val result = runCatching(block)
            checks += if (result.isSuccess) SelfTestCheck(name, true, result.getOrThrow())
            else SelfTestCheck(name, false, result.exceptionOrNull()?.message ?: "unknown error")
        }

        check("identity") {
            require(BuildInfo.ENGINE_NAME.isNotBlank() && !BuildInfo.USES_WEBVIEW)
            "${BuildInfo.ENGINE_VERSION}, custom engine path"
        }
        check("utf8") {
            val value = Utf8String.of("Aether • नमस्ते • 🚀")
            require(Utf8String.fromBytes(value.copyBytes()) == value)
            "${value.byteLength} UTF-8 bytes round-trip"
        }
        check("memory arena") {
            val arena = ByteArena(128)
            val slice = arena.allocate(16, alignment = 8)
            slice.write(byteArrayOf(1, 2, 3))
            require(slice.read().take(3) == listOf<Byte>(1, 2, 3))
            "aligned allocation, used=${arena.usedBytes()}"
        }
        check("filesystem") {
            val path = VirtualPath.of("/diagnostics/selftest.txt")
            runtime.fileSystem.write(path, "ok".toByteArray())
            require(String(runtime.fileSystem.read(path)) == "ok")
            require(runtime.fileSystem.list(VirtualPath.of("/diagnostics")).any { it.path == path })
            "read/write/list verified"
        }
        check("logger") {
            val before = runtime.logger.size()
            runtime.logger.info("SelfTest", "logger probe")
            require(runtime.logger.size() == before + 1)
            "ring buffer accepted entry"
        }
        check("profiler") {
            runtime.profiler.measure("selftest.probe") { repeat(100) { it * it } }
            require(runtime.profiler.metric("selftest.probe")?.samples ?: 0 > 0)
            "timing sample captured"
        }
        check("devtools") {
            val result = runtime.console.execute(CommandContext(runtime), "echo engine ready")
            require(result.success && result.output == "engine ready")
            "command registry and tokenizer verified"
        }
        check("crash codec") {
            val before = runtime.crashReporter.recent().size
            val envelope = CrashEnvelope(
                id = "self-test-envelope",
                timestampMillis = 1L,
                threadName = "self-test",
                exceptionType = "java.lang.IllegalStateException",
                message = "codec probe",
                stackTrace = "self-test stack",
                context = mapOf("probe" to "true")
            )
            require(CrashEnvelopeCodec.decode(CrashEnvelopeCodec.encode(envelope)) == envelope)
            require(runtime.crashReporter.recent().size == before)
            "codec round-trip verified without storing a crash"
        }
        check("kernel") {
            require(runtime.state.name in setOf("RUNNING", "STARTING"))
            "state=${runtime.state}"
        }
        checks += NetworkSelfTest.run()
        checks += HtmlSelfTest.run(runtime.html)
        checks += CssSelfTest.run(runtime.css, runtime.html)
        checks += LayoutSelfTest.run(runtime.layout, runtime.html, runtime.css)
        checks += PaintSelfTest.run(runtime.paint, runtime.html, runtime.css, runtime.layout)
        checks += JsSelfTest.run(runtime.js)
        checks += BrowserApiSelfTest.run(runtime)
        checks += RenderSelfTest.run(runtime)
        checks += BrowserShellSelfTest.run(runtime)
        checks += SecuritySelfTest.run(runtime)

        val elapsed = System.nanoTime() - started
        runtime.profiler.record("selftest.total", elapsed)
        return SelfTestReport(checks, elapsed)
    }
}
