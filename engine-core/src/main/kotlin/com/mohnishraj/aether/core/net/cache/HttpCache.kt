package com.mohnishraj.aether.core.net.cache

import com.mohnishraj.aether.core.net.model.AetherUrl
import com.mohnishraj.aether.core.net.model.HttpMethod
import com.mohnishraj.aether.core.net.model.NetworkHeaders
import com.mohnishraj.aether.core.net.model.NetworkRequest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

interface HttpCache {
    fun get(request: NetworkRequest): CacheEntry?
    fun put(entry: CacheEntry)
    fun remove(url: AetherUrl)
    fun clear()
    fun stats(): CacheStats
}

data class CacheStats(val entries: Int, val bytes: Long, val hits: Long, val misses: Long)

data class CacheEntry(
    val url: AetherUrl,
    val statusCode: Int,
    val reasonPhrase: String,
    val headers: NetworkHeaders,
    val body: ByteArray,
    val protocol: String,
    val storedAtMillis: Long,
    val expiresAtMillis: Long,
    val varyNames: Set<String>,
    val varyRequestHeaders: Map<String, String>
) {
    fun isFresh(nowMillis: Long): Boolean = nowMillis < expiresAtMillis
    fun matches(request: NetworkRequest): Boolean = request.url.withoutFragment() == url.withoutFragment() &&
        varyNames.all { name -> request.headers[name].orEmpty() == varyRequestHeaders[name].orEmpty() }
    val etag: String? get() = headers["ETag"]
    val lastModified: String? get() = headers["Last-Modified"]
}

object CachePolicyEvaluator {
    fun buildEntry(
        request: NetworkRequest,
        statusCode: Int,
        reasonPhrase: String,
        headers: NetworkHeaders,
        body: ByteArray,
        protocol: String,
        nowMillis: Long
    ): CacheEntry? {
        if (request.method != HttpMethod.GET || request.headers.contains("Range") || statusCode !in CACHEABLE_CODES) return null
        val directives = parseDirectives(headers["Cache-Control"])
        if ("no-store" in directives) return null
        val varyNames = headers.values("Vary").flatMap { it.split(',') }.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotBlank() }.toSet()
        if ("*" in varyNames) return null
        val ageSeconds = headers["Age"]?.trim()?.toLongOrNull()?.coerceIn(0L, 31_536_000L) ?: 0L
        val expiresAt = when {
            "no-cache" in directives || headers["Pragma"]?.contains("no-cache", ignoreCase = true) == true -> nowMillis
            directives["max-age"] != null -> {
                val maxAgeSeconds = directives["max-age"]?.toLongOrNull()?.coerceIn(0L, 31_536_000L) ?: 0L
                nowMillis + (maxAgeSeconds - ageSeconds).coerceAtLeast(0L) * 1000L
            }
            headers["Expires"] != null -> (parseHttpDate(headers["Expires"].orEmpty()) ?: nowMillis) - ageSeconds * 1000L
            else -> nowMillis
        }.coerceAtLeast(nowMillis)
        val storedHeaders = sanitizeStoredHeaders(headers)
        return CacheEntry(
            request.url.withoutFragment(), statusCode, reasonPhrase, storedHeaders, body.copyOf(), protocol,
            nowMillis, expiresAt, varyNames, varyNames.associateWith { request.headers[it].orEmpty() }
        )
    }

    fun merge304(cached: CacheEntry, headers304: NetworkHeaders, nowMillis: Long): CacheEntry {
        val merged = cached.headers.newBuilder().apply {
            headers304.forEach { (name, value) -> if (!isHopByHop(name) && !name.equals("Content-Length", true)) set(name, value) }
        }.build()
        val syntheticRequest = NetworkRequest(
            cached.url,
            headers = NetworkHeaders.builder().apply { cached.varyRequestHeaders.forEach { (name, value) -> set(name, value) } }.build()
        )
        val storedHeaders = sanitizeStoredHeaders(merged)
        return buildEntry(syntheticRequest, cached.statusCode, cached.reasonPhrase, storedHeaders, cached.body, cached.protocol, nowMillis)
            ?: cached.copy(headers = storedHeaders, storedAtMillis = nowMillis, expiresAtMillis = nowMillis)
    }

    fun allowsStorage(headers: NetworkHeaders): Boolean = "no-store" !in parseDirectives(headers["Cache-Control"])

    fun requiresValidation(request: NetworkRequest): Boolean {
        val cacheControl = request.headers.values("Cache-Control").joinToString(",").lowercase(Locale.ROOT)
        return cacheControl.split(',').any { directive ->
            val normalized = directive.trim()
            normalized == "no-cache" || normalized == "max-age=0"
        } || request.headers["Pragma"]?.contains("no-cache", ignoreCase = true) == true
    }

    fun conditionalHeaders(entry: CacheEntry): NetworkHeaders = NetworkHeaders.builder().apply {
        entry.etag?.let { set("If-None-Match", it) }
        entry.lastModified?.let { set("If-Modified-Since", it) }
    }.build()

    private fun parseDirectives(value: String?): Map<String, String?> = value.orEmpty().split(',').mapNotNull { raw ->
        val item = raw.trim()
        if (item.isEmpty()) null else item.substringBefore('=').lowercase(Locale.ROOT) to
            item.substringAfter('=', "").trim().trim('"').ifEmpty { null }
    }.toMap()

    private fun sanitizeStoredHeaders(headers: NetworkHeaders): NetworkHeaders = NetworkHeaders.builder().apply {
        headers.forEach { (name, value) ->
            if (!isHopByHop(name) && !name.equals("Set-Cookie", ignoreCase = true)) add(name, value)
        }
    }.build()

    private fun parseHttpDate(value: String): Long? = runCatching {
        Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC).parse(value)).toEpochMilli()
    }.getOrNull()

    private fun isHopByHop(name: String): Boolean = name.lowercase(Locale.ROOT) in HOP_BY_HOP
    private val HOP_BY_HOP = setOf("connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade")
    private val CACHEABLE_CODES = setOf(200, 203, 204, 300, 301, 404, 405, 410, 414, 501)
}
