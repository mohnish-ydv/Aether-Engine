package com.mohnishraj.aether.core.net

import com.mohnishraj.aether.core.net.cache.CacheEntry
import com.mohnishraj.aether.core.net.cache.CachePolicyEvaluator
import com.mohnishraj.aether.core.net.cache.CacheStats
import com.mohnishraj.aether.core.net.cache.HttpCache
import com.mohnishraj.aether.core.net.cache.MemoryHttpCache
import com.mohnishraj.aether.core.net.cookie.CookieJar
import com.mohnishraj.aether.core.net.cookie.HttpCookie
import com.mohnishraj.aether.core.net.cookie.InMemoryCookieJar
import com.mohnishraj.aether.core.net.cookie.cookieHeader
import com.mohnishraj.aether.core.net.dns.CachingDnsResolver
import com.mohnishraj.aether.core.net.dns.DnsAnswer
import com.mohnishraj.aether.core.net.dns.DnsResolver
import com.mohnishraj.aether.core.net.model.AetherUrl
import com.mohnishraj.aether.core.net.model.NetworkFailureKind
import com.mohnishraj.aether.core.net.model.NetworkHeaders
import com.mohnishraj.aether.core.net.model.NetworkRequest
import com.mohnishraj.aether.core.net.model.NetworkResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NetworkHardeningTest {
    @Test fun responseNoCacheIsStoredForValidationButNeverFresh() {
        val request = NetworkRequest.Builder("https://example.com/data").build()
        val entry = CachePolicyEvaluator.buildEntry(
            request,
            200,
            "OK",
            NetworkHeaders.of("Cache-Control" to "max-age=3600, no-cache", "ETag" to "v1"),
            "body".toByteArray(),
            "HTTP/1.1",
            10_000
        )
        assertNotNull(entry)
        assertFalse(entry.isFresh(10_001))
    }

    @Test fun requestNoCacheRevalidatesOtherwiseFreshResponse() {
        var calls = 0
        val transport = ScriptedTransport { request ->
            calls++
            if (request.headers["If-None-Match"] == "v1") {
                response(304, headers = NetworkHeaders.of("Cache-Control" to "max-age=3600", "ETag" to "v1"))
            } else {
                response(headers = NetworkHeaders.of("Cache-Control" to "max-age=3600", "ETag" to "v1"), body = "body-one".toByteArray())
            }
        }
        val client = NetworkClient(transport, MemoryHttpCache(), InMemoryCookieJar())
        client.execute(NetworkRequest.Builder("https://example.com/data").build()).valueOrThrow()
        val reloaded = client.execute(
            NetworkRequest.Builder("https://example.com/data").header("Cache-Control", "no-cache").build()
        ).valueOrThrow()
        assertTrue(reloaded.fromCache)
        assertEquals("body-one", reloaded.bodyText())
        assertEquals(2, calls)
    }

    @Test fun responseAgeReducesRemainingFreshness() {
        val entry = CachePolicyEvaluator.buildEntry(
            NetworkRequest.Builder("https://example.com/aged").build(),
            200,
            "OK",
            NetworkHeaders.of("Cache-Control" to "max-age=60", "Age" to "30"),
            byteArrayOf(),
            "HTTP/1.1",
            10_000
        )
        assertNotNull(entry)
        assertEquals(40_000, entry.expiresAtMillis)
    }

    @Test fun cookieRequestHeaderIsBounded() {
        val jar = InMemoryCookieJar()
        val url = AetherUrl.parse("https://example.com/")
        repeat(20) { index ->
            jar.saveFromResponse(url, NetworkHeaders.of("Set-Cookie" to "c$index=${"x".repeat(700)}; Secure; Path=/"))
        }
        val header = jar.cookieHeader(url)
        assertNotNull(header)
        assertTrue(header.length <= 8_192)
        assertFalse(header.endsWith("; "))
    }

    @Test fun cookiePrefixesAndSecureOriginRulesAreEnforced() {
        val jar = InMemoryCookieJar()
        val https = AetherUrl.parse("https://example.com/")
        val http = AetherUrl.parse("http://example.com/")
        jar.saveFromResponse(http, NetworkHeaders.of("Set-Cookie" to "plain=1; Secure"))
        jar.saveFromResponse(https, NetworkHeaders.of("Set-Cookie" to "__Secure-bad=1"))
        jar.saveFromResponse(https, NetworkHeaders.of("Set-Cookie" to "__Host-bad=1; Secure; Domain=example.com; Path=/"))
        jar.saveFromResponse(https, NetworkHeaders.of("Set-Cookie" to "__Host-good=1; Secure; Path=/"))
        assertEquals(listOf("__Host-good"), jar.snapshot().map { it.name })
    }

    @Test fun idnCookieDomainIsCanonicalized() {
        val jar = InMemoryCookieJar()
        val url = AetherUrl.parse("https://bücher.example/")
        jar.saveFromResponse(url, NetworkHeaders.of("Set-Cookie" to "sid=1; Domain=bücher.example; Secure"))
        assertEquals("xn--bcher-kva.example", jar.snapshot().single().domain)
    }

    @Test fun cookieJarAppliesGlobalBound() {
        val jar = InMemoryCookieJar()
        val url = AetherUrl.parse("https://example.com/")
        repeat(520) { index ->
            jar.saveFromResponse(url, NetworkHeaders.of("Set-Cookie" to "c$index=$index; Secure; Path=/p$index"))
        }
        assertTrue(jar.snapshot().size <= 180)
    }

    @Test fun emptyDnsAnswerIsRejected() {
        val resolver = CachingDnsResolver(DnsResolver { host ->
            NetworkResult.Success(DnsAnswer(host, emptyList(), 0, false))
        })
        val result = resolver.resolve("example.com")
        assertTrue(result is NetworkResult.Failure && result.error.kind == NetworkFailureKind.DNS)
    }

    @Test fun cacheDoesNotStorePartialRangeResponsesOrCookieHeaders() {
        val rangeRequest = NetworkRequest.Builder("https://example.com/file")
            .header("Range", "bytes=0-9")
            .build()
        val partial = CachePolicyEvaluator.buildEntry(
            rangeRequest,
            206,
            "Partial Content",
            NetworkHeaders.of("Cache-Control" to "max-age=60", "Content-Range" to "bytes 0-9/100"),
            ByteArray(10),
            "HTTP/1.1",
            0
        )
        assertEquals(null, partial)

        val full = CachePolicyEvaluator.buildEntry(
            NetworkRequest.Builder("https://example.com/file").build(),
            200,
            "OK",
            NetworkHeaders.of(
                "Cache-Control" to "max-age=60",
                "Set-Cookie" to "sid=secret",
                "Connection" to "close"
            ),
            "body".toByteArray(),
            "HTTP/1.1",
            0
        )
        assertNotNull(full)
        assertFalse(full.headers.contains("Set-Cookie"))
        assertFalse(full.headers.contains("Connection"))
    }

    @Test fun revalidationNoStoreServesCachedBodyOnceThenEvictsEntry() {
        var calls = 0
        val transport = ScriptedTransport { request ->
            calls++
            when (calls) {
                1 -> response(
                    headers = NetworkHeaders.of("Cache-Control" to "max-age=0", "ETag" to "v1"),
                    body = "body-one".toByteArray()
                )
                2 -> {
                    assertEquals("v1", request.headers["If-None-Match"])
                    response(
                        304,
                        headers = NetworkHeaders.of(
                            "Cache-Control" to "no-store",
                            "Set-Cookie" to "should-not-enter-cache=1",
                            "Connection" to "close"
                        )
                    )
                }
                else -> response(
                    headers = NetworkHeaders.of("Cache-Control" to "max-age=60"),
                    body = "body-two".toByteArray()
                )
            }
        }
        val cache = MemoryHttpCache()
        val client = NetworkClient(transport, cache, InMemoryCookieJar())
        val request = NetworkRequest.Builder("https://example.com/revalidate").build()

        client.execute(request).valueOrThrow()
        val revalidated = client.execute(request).valueOrThrow()
        assertTrue(revalidated.fromCache)
        assertEquals("body-one", revalidated.bodyText())
        assertFalse(revalidated.headers.contains("Set-Cookie"))
        assertFalse(revalidated.headers.contains("Connection"))
        assertEquals(0, cache.stats().entries)

        val refreshed = client.execute(request).valueOrThrow()
        assertFalse(refreshed.fromCache)
        assertEquals("body-two", refreshed.bodyText())
        assertEquals(3, calls)
    }

    @Test fun cacheAndCookieStorageFailuresDoNotFailSuccessfulNetworkResponse() {
        val brokenCache = object : HttpCache {
            override fun get(request: NetworkRequest): CacheEntry? = throw IllegalStateException("read unavailable")
            override fun put(entry: CacheEntry) = throw IllegalStateException("write unavailable")
            override fun remove(url: AetherUrl) = Unit
            override fun clear() = Unit
            override fun stats() = CacheStats(0, 0, 0, 0)
        }
        val brokenCookies = object : CookieJar {
            override fun loadForRequest(url: AetherUrl): List<HttpCookie> = throw IllegalStateException("read unavailable")
            override fun saveFromResponse(url: AetherUrl, headers: NetworkHeaders) = throw IllegalStateException("write unavailable")
            override fun snapshot(): List<HttpCookie> = emptyList()
            override fun clear() = Unit
        }
        val client = NetworkClient(
            ScriptedTransport { response(headers = NetworkHeaders.of("Cache-Control" to "max-age=60"), body = "ok".toByteArray()) },
            brokenCache,
            brokenCookies
        )
        val result = client.execute(NetworkRequest.Builder("https://example.com/").build())
        val response = (result as? NetworkResult.Success)?.value
        assertNotNull(response)
        assertEquals("ok", response.bodyText())
    }
}
