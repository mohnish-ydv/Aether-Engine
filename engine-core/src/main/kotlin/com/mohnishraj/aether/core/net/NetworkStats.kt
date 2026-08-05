package com.mohnishraj.aether.core.net

import java.util.concurrent.atomic.AtomicLong

data class NetworkStatsSnapshot(
    val requests: Long,
    val successes: Long,
    val failures: Long,
    val redirects: Long,
    val cacheHits: Long,
    val bytesSent: Long,
    val bytesReceived: Long
)

class NetworkStats {
    private val requests = AtomicLong()
    private val successes = AtomicLong()
    private val failures = AtomicLong()
    private val redirects = AtomicLong()
    private val cacheHits = AtomicLong()
    private val bytesSent = AtomicLong()
    private val bytesReceived = AtomicLong()

    fun request(bytes: Long) { requests.incrementAndGet(); bytesSent.addAndGet(bytes) }
    fun success(bytes: Long, fromCache: Boolean) { successes.incrementAndGet(); bytesReceived.addAndGet(bytes); if (fromCache) cacheHits.incrementAndGet() }
    fun failure() { failures.incrementAndGet() }
    fun redirect() { redirects.incrementAndGet() }
    fun snapshot() = NetworkStatsSnapshot(requests.get(), successes.get(), failures.get(), redirects.get(), cacheHits.get(), bytesSent.get(), bytesReceived.get())
}
