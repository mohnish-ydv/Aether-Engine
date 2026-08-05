package com.mohnishraj.aether.core.browser.features

import com.mohnishraj.aether.core.fs.MemoryFileSystem
import com.mohnishraj.aether.core.fs.VirtualPath
import com.mohnishraj.aether.core.net.NetworkClient
import com.mohnishraj.aether.core.net.NetworkRuntime
import com.mohnishraj.aether.core.net.cache.MemoryHttpCache
import com.mohnishraj.aether.core.net.cookie.InMemoryCookieJar
import com.mohnishraj.aether.core.net.dns.DnsAnswer
import com.mohnishraj.aether.core.net.dns.DnsResolver
import com.mohnishraj.aether.core.net.download.DownloadManager
import com.mohnishraj.aether.core.net.model.NetworkHeaders
import com.mohnishraj.aether.core.net.model.NetworkResult
import com.mohnishraj.aether.core.net.transport.ByteArrayExchange
import com.mohnishraj.aether.core.net.transport.NetworkTransport
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManagedDownloadControllerTest {
    @Test fun downloadCompletesAndVerifiesSha256() {
        val payload = ByteArray(32_768) { (it % 251).toByte() }
        val expected = sha256(payload)
        val fileSystem = MemoryFileSystem()
        val controller = controller(fileSystem) { request ->
            assertEquals(null, request.headers["Range"])
            ByteArrayExchange(200, "OK", NetworkHeaders.of("Content-Length" to payload.size.toString()), payload = payload)
        }
        val item = controller.enqueue("https://example.com/a.bin", VirtualPath.of("/downloads/a.bin"), expected)
        val completed = controller.execute(item.id)

        assertEquals(DownloadState.COMPLETED, completed.state)
        assertTrue(completed.integrityVerified == true)
        assertEquals(expected, completed.actualSha256)
        assertEquals(payload.toList(), fileSystem.read(VirtualPath.of("/downloads/a.bin")).toList())
    }

    @Test fun existingCheckpointUsesRangeAndCanFailIntegrity() {
        val prefix = "hello ".toByteArray()
        val suffix = "world".toByteArray()
        val fileSystem = MemoryFileSystem()
        fileSystem.write(VirtualPath.of("/downloads/range.txt.part"), prefix)
        val controller = controller(fileSystem) { request ->
            assertEquals("bytes=${prefix.size}-", request.headers["Range"])
            ByteArrayExchange(206, "Partial Content", NetworkHeaders.of("Content-Length" to suffix.size.toString()), payload = suffix)
        }
        val item = controller.enqueue("https://example.com/range.txt", VirtualPath.of("/downloads/range.txt"), "0".repeat(64))
        val completed = controller.execute(item.id)

        assertEquals(DownloadState.FAILED, completed.state)
        assertFalse(completed.integrityVerified ?: true)
        assertEquals("hello world", fileSystem.read(VirtualPath.of("/downloads/range.txt")).toString(Charsets.UTF_8))
    }


    @Test fun metadataSurvivesControllerRecreation() {
        val fileSystem = MemoryFileSystem()
        val first = controller(fileSystem) { error("Network should not run") }
        val queued = first.enqueue("https://example.com/persist.bin", VirtualPath.of("/downloads/persist.bin"))

        val restored = controller(fileSystem) { error("Network should not run") }.snapshot(queued.id)

        assertEquals(DownloadState.QUEUED, restored?.state)
        assertEquals("https://example.com/persist.bin", restored?.url)
        assertEquals(VirtualPath.of("/downloads/persist.bin"), restored?.destination)
    }

    @Test fun cancelDeletesPartialCheckpointAndPersistsCancelledState() {
        val fileSystem = MemoryFileSystem()
        val destination = VirtualPath.of("/downloads/cancel.bin")
        val checkpoint = VirtualPath.of("/downloads/cancel.bin.part")
        fileSystem.write(checkpoint, byteArrayOf(1, 2, 3))
        val first = controller(fileSystem) { error("Network should not run") }
        val queued = first.enqueue("https://example.com/cancel.bin", destination)

        val cancelled = first.cancel(queued.id)
        val restored = controller(fileSystem) { error("Network should not run") }.snapshot(queued.id)

        assertEquals(DownloadState.CANCELLED, cancelled.state)
        assertFalse(fileSystem.exists(checkpoint))
        assertEquals(DownloadState.CANCELLED, restored?.state)
    }

    private fun controller(fileSystem: MemoryFileSystem, exchange: (com.mohnishraj.aether.core.net.model.NetworkRequest) -> ByteArrayExchange): ManagedDownloadController {
        val cookies = InMemoryCookieJar()
        val cache = MemoryHttpCache()
        val client = NetworkClient(NetworkTransport { request -> NetworkResult.Success(exchange(request)) }, cache, cookies)
        val network = NetworkRuntime(
            client,
            DownloadManager(client, fileSystem),
            DnsResolver { host -> NetworkResult.Success(DnsAnswer(host, listOf("127.0.0.1"), 0L, false)) },
            cookies,
            cache
        )
        return ManagedDownloadController(network, fileSystem)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
