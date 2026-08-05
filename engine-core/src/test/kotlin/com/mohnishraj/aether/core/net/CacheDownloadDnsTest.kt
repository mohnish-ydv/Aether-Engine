package com.mohnishraj.aether.core.net

import com.mohnishraj.aether.core.fs.MemoryFileSystem
import com.mohnishraj.aether.core.fs.VirtualPath
import com.mohnishraj.aether.core.net.cache.CachePolicyEvaluator
import com.mohnishraj.aether.core.net.cache.FileSystemHttpCache
import com.mohnishraj.aether.core.net.cache.MemoryHttpCache
import com.mohnishraj.aether.core.net.cookie.InMemoryCookieJar
import com.mohnishraj.aether.core.net.dns.CachingDnsResolver
import com.mohnishraj.aether.core.net.dns.DnsAnswer
import com.mohnishraj.aether.core.net.dns.DnsResolver
import com.mohnishraj.aether.core.net.download.DownloadManager
import com.mohnishraj.aether.core.net.model.AetherUrl
import com.mohnishraj.aether.core.net.model.NetworkHeaders
import com.mohnishraj.aether.core.net.model.NetworkFailureKind
import com.mohnishraj.aether.core.net.model.NetworkRequest
import com.mohnishraj.aether.core.net.model.NetworkResult
import com.mohnishraj.aether.core.time.EngineClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CacheDownloadDnsTest {
    @Test fun fileCacheRoundTripsBinaryEntry() {
        val fs = MemoryFileSystem()
        val cache = FileSystemHttpCache(fs)
        val request = NetworkRequest.Builder("https://example.com/a").header("Accept-Language", "en").build()
        val entry = CachePolicyEvaluator.buildEntry(
            request, 200, "OK", NetworkHeaders.of("Cache-Control" to "max-age=60", "Vary" to "Accept-Language"),
            byteArrayOf(0, 1, 2, -1), "HTTP/1.1", 1_000
        ) ?: error("entry")
        cache.put(entry)
        assertEquals(entry.body.toList(), cache.get(request)?.body?.toList())
    }

    @Test fun varyMismatchIsCacheMiss() {
        val cache = MemoryHttpCache()
        val en = NetworkRequest.Builder("https://example.com/a").header("Accept-Language", "en").build()
        val entry = CachePolicyEvaluator.buildEntry(en, 200, "OK", NetworkHeaders.of("Cache-Control" to "max-age=60", "Vary" to "Accept-Language"), byteArrayOf(1), "HTTP/1.1", 1_000)!!
        cache.put(entry)
        val hi = NetworkRequest.Builder("https://example.com/a").header("Accept-Language", "hi").build()
        assertEquals(null, cache.get(hi))
    }

    @Test fun filesystemCacheRejectsUnsafeCapacityConfiguration() {
        val fs = MemoryFileSystem()
        assertFailsWith<IllegalArgumentException> { FileSystemHttpCache(fs, maxBytes = 0) }
        assertFailsWith<IllegalArgumentException> { FileSystemHttpCache(fs, maxBytes = Int.MAX_VALUE.toLong() + 1) }
        assertFailsWith<IllegalArgumentException> { FileSystemHttpCache(fs, maxEntries = 0) }
        assertFailsWith<IllegalArgumentException> { MemoryHttpCache(maxBytes = 0) }
    }

    @Test fun atomicDownloadCommitsOnlyOnSuccess() {
        val fs = MemoryFileSystem()
        val transport = ScriptedTransport { response(body = ByteArray(64) { it.toByte() }) }
        val client = NetworkClient(transport, MemoryHttpCache(), InMemoryCookieJar())
        val result = DownloadManager(client, fs).download(NetworkRequest.Builder("https://example.com/file").build(), VirtualPath.of("/d/file.bin")).valueOrThrow()
        assertEquals(64L, result.bytesWritten)
        assertEquals(64, fs.read(result.path).size)
    }

    @Test fun invalidAtomicDestinationReturnsTypedWriteFailure() {
        val fs = MemoryFileSystem()
        val client = NetworkClient(ScriptedTransport { response(body = "data".toByteArray()) }, MemoryHttpCache(), InMemoryCookieJar())
        val result = DownloadManager(client, fs).download(
            NetworkRequest.Builder("https://example.com/file").build(),
            VirtualPath.ROOT
        )
        assertTrue(result is NetworkResult.Failure && result.error.kind == NetworkFailureKind.IO)
    }

    @Test fun failedDownloadDoesNotReplaceDestination() {
        val fs = MemoryFileSystem()
        val path = VirtualPath.of("/d/file.bin")
        fs.write(path, "old".toByteArray())
        val transport = ScriptedTransport { response(500, body = "bad".toByteArray()) }
        val client = NetworkClient(transport, MemoryHttpCache(), InMemoryCookieJar())
        val result = DownloadManager(client, fs).download(NetworkRequest.Builder("https://example.com/file").build(), path)
        assertTrue(result is NetworkResult.Failure)
        assertEquals("old", fs.read(path).toString(Charsets.UTF_8))
    }

    @Test fun dnsCacheUsesDelegateOnceUntilExpiry() {
        class Clock(var now: Long = 0) : EngineClock { override fun nowMillis() = now }
        val clock = Clock()
        var calls = 0
        val delegate = DnsResolver { host -> calls++; NetworkResult.Success(DnsAnswer(host, listOf("127.0.0.1"), clock.now, false)) }
        val cache = CachingDnsResolver(delegate, ttlMillis = 100, clock = clock)
        assertFalse(cache.resolve("Example.com").valueOrThrow().fromCache)
        assertTrue(cache.resolve("example.com.").valueOrThrow().fromCache)
        assertEquals(1, calls)
        clock.now = 101
        cache.resolve("example.com").valueOrThrow()
        assertEquals(2, calls)
    }

    @Test fun sameOriginIncludesEffectivePort() {
        assertTrue(AetherUrl.parse("https://example.com/a").sameOrigin(AetherUrl.parse("https://example.com:443/b")))
        assertFalse(AetherUrl.parse("https://example.com/a").sameOrigin(AetherUrl.parse("https://example.com:444/b")))
    }
}
