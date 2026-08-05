package com.mohnishraj.aether.core.net

import com.mohnishraj.aether.core.net.cache.MemoryHttpCache
import com.mohnishraj.aether.core.net.cookie.InMemoryCookieJar
import com.mohnishraj.aether.core.net.model.CachePolicy
import com.mohnishraj.aether.core.net.model.HttpMethod
import com.mohnishraj.aether.core.net.model.NetworkFailureKind
import com.mohnishraj.aether.core.net.model.NetworkHeaders
import com.mohnishraj.aether.core.net.model.NetworkRequest
import com.mohnishraj.aether.core.net.model.NetworkResult
import com.mohnishraj.aether.core.net.model.RedirectPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkClientTest {
    @Test fun followsRelativeRedirect() {
        val transport = ScriptedTransport { request ->
            if (request.url.encodedPath == "/start") response(302, NetworkHeaders.of("Location" to "/done"))
            else response(body = "ok".toByteArray())
        }
        val result = NetworkClient(transport, MemoryHttpCache(), InMemoryCookieJar())
            .execute(NetworkRequest.Builder("https://example.com/start").build()).valueOrThrow()
        assertEquals("/done", result.finalUrl.encodedPath)
        assertEquals(1, result.redirectChain.size)
    }

    @Test fun rewritesPostToGetOn303() {
        val transport = ScriptedTransport { request ->
            if (request.url.encodedPath == "/submit") response(303, NetworkHeaders.of("Location" to "/done"))
            else response(body = request.method.name.toByteArray())
        }
        val result = NetworkClient(transport, MemoryHttpCache(), InMemoryCookieJar()).execute(
            NetworkRequest.Builder("https://example.com/submit").method(HttpMethod.POST, "body".toByteArray()).build()
        ).valueOrThrow()
        assertEquals("GET", result.bodyText())
    }

    @Test fun sameUrl303CanTransitionFromPostToGet() {
        var calls = 0
        val transport = ScriptedTransport { request ->
            calls++
            if (request.method == HttpMethod.POST) response(303, NetworkHeaders.of("Location" to request.url.toString()))
            else response(body = request.method.name.toByteArray())
        }
        val response = NetworkClient(transport, MemoryHttpCache(), InMemoryCookieJar()).execute(
            NetworkRequest.Builder("https://example.com/submit").method(HttpMethod.POST, "body".toByteArray()).build()
        ).valueOrThrow()
        assertEquals("GET", response.bodyText())
        assertEquals(2, calls)
    }

    @Test fun redirectReselectsCookiesForTargetPath() {
        val jar = InMemoryCookieJar()
        val transport = ScriptedTransport { request ->
            if (request.url.encodedPath == "/private/start") {
                response(302, NetworkHeaders.of(
                    "Location" to "/public/final",
                    "Set-Cookie" to "public=2; Path=/public; Secure"
                ))
            } else {
                response(body = (request.headers["Cookie"] ?: "none").toByteArray())
            }
        }
        jar.saveFromResponse(
            com.mohnishraj.aether.core.net.model.AetherUrl.parse("https://example.com/private/start"),
            NetworkHeaders.of("Set-Cookie" to "private=1; Path=/private; Secure")
        )
        val response = NetworkClient(transport, MemoryHttpCache(), jar).execute(
            NetworkRequest.Builder("https://example.com/private/start").build()
        ).valueOrThrow()
        assertEquals("public=2", response.bodyText())
    }

    @Test fun stripsAuthorizationAcrossOrigins() {
        val transport = ScriptedTransport { request ->
            if (request.url.host == "a.test") response(302, NetworkHeaders.of("Location" to "https://b.test/final"))
            else response(body = (request.headers["Authorization"] ?: "none").toByteArray())
        }
        val result = NetworkClient(transport, MemoryHttpCache(), InMemoryCookieJar()).execute(
            NetworkRequest.Builder("https://a.test/start").header("Authorization", "Bearer secret").build()
        ).valueOrThrow()
        assertEquals("none", result.bodyText())
    }

    @Test fun honorsManualAndErrorRedirectPolicies() {
        val transport = ScriptedTransport { response(302, NetworkHeaders.of("Location" to "/next")) }
        val client = NetworkClient(transport, MemoryHttpCache(), InMemoryCookieJar())
        val manual = client.execute(NetworkRequest.Builder("https://example.com/").redirectPolicy(RedirectPolicy.MANUAL).build()).valueOrThrow()
        assertEquals(302, manual.statusCode)
        val error = client.execute(NetworkRequest.Builder("https://example.com/error").redirectPolicy(RedirectPolicy.ERROR).build())
        assertTrue(error is NetworkResult.Failure && error.error.kind == NetworkFailureKind.REDIRECT)
    }

    @Test fun decodesGzipAndRemovesEncodingHeader() {
        val transport = ScriptedTransport { response(headers = NetworkHeaders.of("Content-Encoding" to "gzip"), body = gzip("hello")) }
        val result = NetworkClient(transport, MemoryHttpCache(), InMemoryCookieJar())
            .execute(NetworkRequest.Builder("https://example.com/").build()).valueOrThrow()
        assertEquals("hello", result.bodyText())
        assertFalse(result.headers.contains("Content-Encoding"))
    }

    @Test fun transportFramingHeadersAreEngineControlled() {
        val transport = ScriptedTransport { request ->
            val leaked = listOf("Host", "Content-Length", "Transfer-Encoding", "Connection", "Proxy-Connection", "Upgrade")
                .filter(request.headers::contains)
            response(body = leaked.joinToString(",").toByteArray())
        }
        val response = NetworkClient(transport, MemoryHttpCache(), InMemoryCookieJar()).execute(
            NetworkRequest.Builder("https://example.com/")
                .header("Host", "attacker.test")
                .header("Content-Length", "999")
                .header("Transfer-Encoding", "chunked")
                .header("Connection", "close")
                .header("Proxy-Connection", "keep-alive")
                .header("Upgrade", "h2c")
                .build()
        ).valueOrThrow()
        assertEquals("", response.bodyText())
    }

    @Test fun unsupportedEncodingPreservesHeaderAndRawBody() {
        val raw = byteArrayOf(1, 2, 3, 4)
        val client = NetworkClient(
            ScriptedTransport { response(headers = NetworkHeaders.of("Content-Encoding" to "br"), body = raw) },
            MemoryHttpCache(),
            InMemoryCookieJar()
        )
        val response = client.execute(NetworkRequest.Builder("https://example.com/br").build()).valueOrThrow()
        assertEquals("br", response.headers["Content-Encoding"])
        assertTrue(response.body.contentEquals(raw))
    }

    @Test fun enforcesDecodedResponseLimit() {
        val transport = ScriptedTransport { response(body = ByteArray(20)) }
        val result = NetworkClient(transport, MemoryHttpCache(), InMemoryCookieJar()).execute(
            NetworkRequest.Builder("https://example.com/").maxResponseBytes(10).build()
        )
        assertTrue(result is NetworkResult.Failure && result.error.kind == NetworkFailureKind.RESPONSE_TOO_LARGE)
    }

    @Test fun freshCacheAvoidsSecondTransportCall() {
        val transport = ScriptedTransport { response(headers = NetworkHeaders.of("Cache-Control" to "max-age=60"), body = "cached".toByteArray()) }
        val client = NetworkClient(transport, MemoryHttpCache(), InMemoryCookieJar())
        client.execute(NetworkRequest.Builder("https://example.com/a").build()).valueOrThrow()
        val second = client.execute(NetworkRequest.Builder("https://example.com/a").build()).valueOrThrow()
        assertTrue(second.fromCache)
        assertEquals(1, transport.requests.size)
    }

    @Test fun cacheOnlyMissDoesNotOpenTransport() {
        val transport = ScriptedTransport { response() }
        val result = NetworkClient(transport, MemoryHttpCache(), InMemoryCookieJar()).execute(
            NetworkRequest.Builder("https://example.com/a").cachePolicy(CachePolicy.CACHE_ONLY).build()
        )
        assertTrue(result is NetworkResult.Failure && result.error.kind == NetworkFailureKind.CACHE_MISS)
        assertTrue(transport.requests.isEmpty())
    }
}
