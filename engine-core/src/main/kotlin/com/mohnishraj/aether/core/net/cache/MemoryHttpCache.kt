package com.mohnishraj.aether.core.net.cache

import com.mohnishraj.aether.core.net.model.AetherUrl
import com.mohnishraj.aether.core.net.model.NetworkRequest
import java.util.LinkedHashMap

class MemoryHttpCache(private val maxBytes: Long = 16L * 1024L * 1024L) : HttpCache {
    init { require(maxBytes in 1..Int.MAX_VALUE.toLong()) { "maxBytes out of range" } }

    private val entries = object : LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {}
    private var bytes = 0L
    private var hits = 0L
    private var misses = 0L

    @Synchronized override fun get(request: NetworkRequest): CacheEntry? {
        val entry = entries[key(request.url)]
        return if (entry != null && entry.matches(request)) { hits++; entry.copy(body = entry.body.copyOf()) } else { misses++; null }
    }
    @Synchronized override fun put(entry: CacheEntry) {
        val key = key(entry.url)
        entries.remove(key)?.let { bytes -= it.body.size }
        entries[key] = entry.copy(body = entry.body.copyOf())
        bytes += entry.body.size
        trim()
    }
    @Synchronized override fun remove(url: AetherUrl) { entries.remove(key(url))?.let { bytes -= it.body.size } }
    @Synchronized override fun clear() { entries.clear(); bytes = 0 }
    @Synchronized override fun stats(): CacheStats = CacheStats(entries.size, bytes, hits, misses)

    private fun trim() {
        val iterator = entries.entries.iterator()
        while (bytes > maxBytes && iterator.hasNext()) {
            val entry = iterator.next().value
            bytes -= entry.body.size
            iterator.remove()
        }
    }
    private fun key(url: AetherUrl) = url.withoutFragment().toString()
}
