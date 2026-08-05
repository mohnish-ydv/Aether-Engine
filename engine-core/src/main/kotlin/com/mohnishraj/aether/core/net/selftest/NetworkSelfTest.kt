package com.mohnishraj.aether.core.net.selftest

import com.mohnishraj.aether.core.fs.MemoryFileSystem
import com.mohnishraj.aether.core.fs.VirtualPath
import com.mohnishraj.aether.core.net.NetworkClient
import com.mohnishraj.aether.core.net.cache.MemoryHttpCache
import com.mohnishraj.aether.core.net.cookie.InMemoryCookieJar
import com.mohnishraj.aether.core.net.download.DownloadManager
import com.mohnishraj.aether.core.net.model.AetherUrl
import com.mohnishraj.aether.core.net.model.NetworkHeaders
import com.mohnishraj.aether.core.net.model.NetworkRequest
import com.mohnishraj.aether.core.net.model.NetworkResult
import com.mohnishraj.aether.core.net.transport.ByteArrayExchange
import com.mohnishraj.aether.core.net.transport.NetworkTransport
import com.mohnishraj.aether.core.selftest.SelfTestCheck
import com.mohnishraj.aether.core.time.EngineClock
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

object NetworkSelfTest {
    fun run(): List<SelfTestCheck> {
        val checks = mutableListOf<SelfTestCheck>()
        fun check(name: String, block: () -> String) {
            val result = runCatching(block)
            checks += if (result.isSuccess) SelfTestCheck(name, true, result.getOrThrow())
            else SelfTestCheck(name, false, result.exceptionOrNull()?.message ?: "unknown error")
        }

        check("url model") {
            val url = AetherUrl.parse("https://Exämple.com:443/a/../b?q=1#x")
            require(url.host == "xn--exmple-cua.com" && url.encodedPath == "/b")
            require(url.resolve("../c").encodedPath == "/c")
            "IDN, normalization and relative resolution verified"
        }
        check("headers") {
            val headers = NetworkHeaders.builder().add("X-Test", "one").add("x-test", "two").build()
            require(headers.values("X-TEST") == listOf("one", "two"))
            "case-insensitive multi-value headers verified"
        }
        check("cookies") {
            val jar = InMemoryCookieJar(FixedClock(1_000))
            val url = AetherUrl.parse("https://example.com/account/login")
            jar.saveFromResponse(url, NetworkHeaders.of("Set-Cookie" to "sid=abc; Path=/account; Secure; HttpOnly; SameSite=Lax"))
            require(jar.loadForRequest(AetherUrl.parse("https://example.com/account/me")).single().name == "sid")
            require(jar.loadForRequest(AetherUrl.parse("http://example.com/account/me")).isEmpty())
            "secure/path cookie matching verified"
        }

        val transport = FixtureTransport()
        val client = NetworkClient(transport, MemoryHttpCache(), InMemoryCookieJar())
        check("redirects") {
            val response = client.execute(NetworkRequest.Builder("https://example.test/redirect").build()).success()
            require(response.finalUrl.encodedPath == "/final" && response.redirectChain.size == 1)
            "manual 302 chain followed safely"
        }
        check("compression") {
            val response = client.execute(NetworkRequest.Builder("https://example.test/gzip").build()).success()
            require(response.bodyText() == "compressed payload")
            require(!response.headers.contains("Content-Encoding"))
            "gzip stream decoded"
        }
        check("fresh cache") {
            val first = client.execute(NetworkRequest.Builder("https://example.test/cache").build()).success()
            val second = client.execute(NetworkRequest.Builder("https://example.test/cache").build()).success()
            require(!first.fromCache && second.fromCache && transport.calls("/cache") == 1)
            "fresh response served without transport"
        }
        check("revalidation") {
            val first = client.execute(NetworkRequest.Builder("https://example.test/revalidate").build()).success()
            val second = client.execute(NetworkRequest.Builder("https://example.test/revalidate").build()).success()
            require(first.bodyText() == "version-one" && second.fromCache && second.bodyText() == "version-one")
            require(transport.sawConditional)
            "ETag conditional request and 304 merge verified"
        }
        check("response limit") {
            val result = client.execute(NetworkRequest.Builder("https://example.test/large").maxResponseBytes(8).build())
            require(result is NetworkResult.Failure && result.error.kind.name == "RESPONSE_TOO_LARGE")
            "oversized response rejected"
        }
        check("streaming download") {
            val fileSystem = MemoryFileSystem()
            val download = DownloadManager(client, fileSystem).download(
                NetworkRequest.Builder("https://example.test/download").maxResponseBytes(1024).build(),
                VirtualPath.of("/downloads/probe.bin")
            ).success()
            require(download.bytesWritten == 256L && fileSystem.read(download.path).size == 256)
            "atomic streaming write verified"
        }
        return checks
    }

    private fun <T> NetworkResult<T>.success(): T = when (this) {
        is NetworkResult.Success -> value
        is NetworkResult.Failure -> error("${error.kind}: ${error.message}")
    }

    private class FixedClock(private val value: Long) : EngineClock { override fun nowMillis(): Long = value }

    private class FixtureTransport : NetworkTransport {
        private val counts = linkedMapOf<String, Int>()
        var sawConditional = false
            private set

        fun calls(path: String): Int = counts[path] ?: 0

        override fun open(request: NetworkRequest): NetworkResult<com.mohnishraj.aether.core.net.transport.TransportExchange> {
            val path = request.url.encodedPath
            counts[path] = calls(path) + 1
            val exchange = when (path) {
                "/redirect" -> ByteArrayExchange(302, "Found", NetworkHeaders.of("Location" to "/final"))
                "/final" -> ByteArrayExchange(200, "OK", NetworkHeaders.of("Content-Type" to "text/plain; charset=utf-8"), payload = "redirected".toByteArray())
                "/gzip" -> ByteArrayExchange(200, "OK", NetworkHeaders.of("Content-Encoding" to "gzip"), payload = gzip("compressed payload".toByteArray()))
                "/cache" -> ByteArrayExchange(200, "OK", NetworkHeaders.of("Cache-Control" to "max-age=60"), payload = "cacheable".toByteArray())
                "/revalidate" -> if (request.headers["If-None-Match"] == "\"v1\"") {
                    sawConditional = true
                    ByteArrayExchange(304, "Not Modified", NetworkHeaders.of("Cache-Control" to "max-age=60", "ETag" to "\"v1\""))
                } else ByteArrayExchange(200, "OK", NetworkHeaders.of("Cache-Control" to "max-age=0", "ETag" to "\"v1\""), payload = "version-one".toByteArray())
                "/large" -> ByteArrayExchange(200, "OK", payload = ByteArray(32) { it.toByte() })
                "/download" -> ByteArrayExchange(200, "OK", payload = ByteArray(256) { (it and 0xff).toByte() })
                else -> ByteArrayExchange(404, "Not Found", payload = "missing".toByteArray())
            }
            return NetworkResult.Success(exchange)
        }

        private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { it.write(bytes) }
            output.toByteArray()
        }
    }
}
