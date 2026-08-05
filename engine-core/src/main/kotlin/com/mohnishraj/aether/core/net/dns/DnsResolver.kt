package com.mohnishraj.aether.core.net.dns

import com.mohnishraj.aether.core.net.model.NetworkFailure
import com.mohnishraj.aether.core.net.model.NetworkFailureKind
import com.mohnishraj.aether.core.net.model.NetworkResult
import com.mohnishraj.aether.core.time.EngineClock
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class DnsAnswer(val host: String, val addresses: List<String>, val resolvedAtMillis: Long, val fromCache: Boolean)

fun interface DnsResolver { fun resolve(host: String): NetworkResult<DnsAnswer> }

class CachingDnsResolver(
    private val delegate: DnsResolver,
    private val ttlMillis: Long = 60_000,
    private val clock: EngineClock = EngineClock.SYSTEM,
    private val maxEntries: Int = 128
) : DnsResolver {
    init {
        require(ttlMillis in 1..86_400_000) { "ttlMillis out of range" }
        require(maxEntries in 1..4_096) { "maxEntries out of range" }
    }

    private data class Entry(val answer: DnsAnswer, val expiresAt: Long)
    private val cache = ConcurrentHashMap<String, Entry>()

    override fun resolve(host: String): NetworkResult<DnsAnswer> {
        val normalized = normalize(host) ?: return NetworkResult.Failure(NetworkFailure(NetworkFailureKind.DNS, "Invalid host: $host"))
        val now = clock.nowMillis()
        cache[normalized]?.takeIf { it.expiresAt > now }?.let { return NetworkResult.Success(it.answer.copy(fromCache = true)) }
        return when (val result = delegate.resolve(normalized)) {
            is NetworkResult.Success -> {
                val addresses = result.value.addresses.filter(String::isNotBlank).distinct()
                if (addresses.isEmpty()) return NetworkResult.Failure(NetworkFailure(NetworkFailureKind.DNS, "No addresses returned for $normalized"))
                if (cache.size >= maxEntries) cache.keys.firstOrNull()?.let(cache::remove)
                val answer = result.value.copy(host = normalized, addresses = addresses, resolvedAtMillis = now, fromCache = false)
                cache[normalized] = Entry(answer, now + ttlMillis)
                NetworkResult.Success(answer)
            }
            is NetworkResult.Failure -> result
        }
    }

    fun clear() = cache.clear()
    fun size(): Int = cache.size

    private fun normalize(host: String): String? {
        val value = host.trim().trimEnd('.').lowercase(Locale.ROOT)
        if (value.isBlank() || value.length > 253 || value.any { it.isWhitespace() || it == '/' }) return null
        return value
    }
}
