package com.mohnishraj.aether.core.shell

import com.mohnishraj.aether.core.EngineRuntime
import com.mohnishraj.aether.core.crash.InMemoryCrashReporter
import com.mohnishraj.aether.core.devtools.BuiltinCommands
import com.mohnishraj.aether.core.devtools.DevConsole
import com.mohnishraj.aether.core.fs.MemoryFileSystem
import com.mohnishraj.aether.core.log.EngineLogger
import com.mohnishraj.aether.core.net.NetworkClient
import com.mohnishraj.aether.core.net.NetworkRuntime
import com.mohnishraj.aether.core.net.NetworkStats
import com.mohnishraj.aether.core.net.cache.MemoryHttpCache
import com.mohnishraj.aether.core.net.cookie.InMemoryCookieJar
import com.mohnishraj.aether.core.net.dns.DnsAnswer
import com.mohnishraj.aether.core.net.dns.DnsResolver
import com.mohnishraj.aether.core.net.download.DownloadManager
import com.mohnishraj.aether.core.net.model.NetworkHeaders
import com.mohnishraj.aether.core.net.model.NetworkResult
import com.mohnishraj.aether.core.net.transport.ByteArrayExchange
import com.mohnishraj.aether.core.net.transport.NetworkTransport
import com.mohnishraj.aether.core.profile.PerformanceProfiler
import com.mohnishraj.aether.core.render.RenderViewport

internal data class ShellFixture(
    val runtime: EngineRuntime,
    val fileSystem: MemoryFileSystem,
    val requestedUrls: MutableList<String>
)

internal fun shellFixture(): ShellFixture {
    val fileSystem = MemoryFileSystem()
    val logger = EngineLogger()
    val cookies = InMemoryCookieJar()
    val cache = MemoryHttpCache()
    val requested = mutableListOf<String>()
    val transport = NetworkTransport { request ->
        requested += request.url.toString()
        val path = request.url.encodedPath
        val (code, contentType, body) = when (path) {
            "/one" -> Triple(200, "text/html; charset=utf-8", "<!doctype html><html><head><title>One</title><style>body{color:#fff}</style></head><body><h1>One</h1></body></html>")
            "/two" -> Triple(200, "text/html; charset=utf-8", "<!doctype html><html><head><title>Two</title></head><body><h1>Two</h1></body></html>")
            "/plain" -> Triple(200, "text/plain; charset=utf-8", "plain <text> & safe")
            "/binary" -> Triple(200, "application/octet-stream", "BINARY")
            "/styled" -> Triple(200, "text/html; charset=utf-8", "<!doctype html><html><head><title>Styled</title><base href='/assets/'><link rel='stylesheet' href='theme.css'><style>.inline{color:#123456}</style></head><body><div class='hero inline'>Styled</div></body></html>")
            "/assets/theme.css" -> Triple(200, "text/css; charset=utf-8", "@import url('nested.css');.hero{display:flex;inline-size:80%;place-items:center}")
            "/assets/nested.css" -> Triple(200, "text/css; charset=utf-8", ".hero{padding-inline:12px;inset:1px 2px 3px 4px}")
            "/scripted" -> Triple(200, "text/html; charset=utf-8", "<!doctype html><html><head><title>Scripted</title><script>document.title='Inline Ran';</script><script src='/assets/app.js' defer></script></head><body><p id='state'>initial</p></body></html>")
            "/assets/app.js" -> Triple(200, "application/javascript; charset=utf-8", "document.querySelector('#state').textContent='external';document.body.setAttribute('data-js','yes');")
            "/timed" -> Triple(200, "text/html; charset=utf-8", "<!doctype html><html><head><title>Timed</title></head><body><p id='clock'>0</p><script>setTimeout(()=>document.querySelector('#clock').textContent='1',10);</script></body></html>")
            "/not-found" -> Triple(404, "text/html; charset=utf-8", "<!doctype html><title>Missing</title><h1>404</h1>")
            else -> Triple(200, "text/html; charset=utf-8", "<!doctype html><html><head><title>Home</title></head><body><p>$path</p></body></html>")
        }
        NetworkResult.Success(ByteArrayExchange(code, if (code == 200) "OK" else "Not Found", NetworkHeaders.of(
            "Content-Type" to contentType,
            "Cache-Control" to "max-age=60"
        ), payload = body.toByteArray()))
    }
    val client = NetworkClient(transport, cache, cookies, NetworkStats(), logger = logger)
    val network = NetworkRuntime(
        client,
        DownloadManager(client, fileSystem),
        DnsResolver { host -> NetworkResult.Success(DnsAnswer(host, listOf("127.0.0.1"), 0L, false)) },
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
    return ShellFixture(runtime, fileSystem, requested)
}

internal fun shellViewport() = RenderViewport(360.0, 640.0)
