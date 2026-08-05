package com.mohnishraj.aether.core.browser

import com.mohnishraj.aether.core.EngineRuntime
import com.mohnishraj.aether.core.crash.InMemoryCrashReporter
import com.mohnishraj.aether.core.devtools.BuiltinCommands
import com.mohnishraj.aether.core.devtools.DevConsole
import com.mohnishraj.aether.core.fs.MemoryFileSystem
import com.mohnishraj.aether.core.log.EngineLogger
import com.mohnishraj.aether.core.profile.PerformanceProfiler

internal fun browserRuntime(): EngineRuntime {
    val runtime = EngineRuntime(
        MemoryFileSystem(),
        EngineLogger(),
        InMemoryCrashReporter(),
        PerformanceProfiler(),
        DevConsole().also(BuiltinCommands::install)
    )
    runtime.start()
    return runtime
}

internal fun browserRuntimeWithNetwork(): EngineRuntime {
    val fileSystem = MemoryFileSystem()
    val logger = EngineLogger()
    val cookies = com.mohnishraj.aether.core.net.cookie.InMemoryCookieJar()
    val cache = com.mohnishraj.aether.core.net.cache.MemoryHttpCache()
    val transport = com.mohnishraj.aether.core.net.transport.NetworkTransport { request ->
        val body = when (request.url.encodedPath) {
            "/json" -> "{\"milestone\":\"M8\"}"
            "/echo" -> request.body?.toString(Charsets.UTF_8).orEmpty()
            else -> "Aether ${request.url.requestTarget}"
        }.toByteArray()
        com.mohnishraj.aether.core.net.model.NetworkResult.Success(
            com.mohnishraj.aether.core.net.transport.ByteArrayExchange(
                200,
                "OK",
                com.mohnishraj.aether.core.net.model.NetworkHeaders.of(
                    "Content-Type" to if (request.url.encodedPath == "/json") "application/json; charset=utf-8" else "text/plain; charset=utf-8",
                    "Cache-Control" to "max-age=60"
                ),
                payload = body
            )
        )
    }
    val client = com.mohnishraj.aether.core.net.NetworkClient(
        transport,
        cache,
        cookies,
        com.mohnishraj.aether.core.net.NetworkStats(),
        logger = logger
    )
    val network = com.mohnishraj.aether.core.net.NetworkRuntime(
        client,
        com.mohnishraj.aether.core.net.download.DownloadManager(client, fileSystem),
        com.mohnishraj.aether.core.net.dns.DnsResolver { host ->
            com.mohnishraj.aether.core.net.model.NetworkResult.Success(
                com.mohnishraj.aether.core.net.dns.DnsAnswer(host, listOf("127.0.0.1"), 0L, false)
            )
        },
        cookies,
        cache
    )
    val runtime = EngineRuntime(
        fileSystem,
        logger,
        InMemoryCrashReporter(),
        PerformanceProfiler(),
        DevConsole().also(BuiltinCommands::install),
        network
    )
    runtime.start()
    return runtime
}

internal const val PAGE_HTML = """<!doctype html><html><head><title>M8</title></head><body>
<main id="app" class="shell"><h1>Aether</h1><p class="lead">Browser APIs</p>
<form id="signup" method="post" action="/join"><input name="email" type="email" required value="dev@example.com"><input name="role" value="engine"><button>Go</button></form>
</main></body></html>"""
