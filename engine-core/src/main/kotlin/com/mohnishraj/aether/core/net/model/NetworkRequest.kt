package com.mohnishraj.aether.core.net.model

enum class HttpMethod(val permitsBody: Boolean) { GET(false), HEAD(false), POST(true), PUT(true), PATCH(true), DELETE(true), OPTIONS(true) }
enum class CachePolicy { DEFAULT, NETWORK_ONLY, CACHE_ONLY, NO_STORE }
enum class RedirectPolicy { FOLLOW, MANUAL, ERROR }

data class NetworkRequest(
    val url: AetherUrl,
    val method: HttpMethod = HttpMethod.GET,
    val headers: NetworkHeaders = NetworkHeaders.EMPTY,
    val body: ByteArray? = null,
    val connectTimeoutMillis: Int = 15_000,
    val readTimeoutMillis: Int = 30_000,
    val maxResponseBytes: Long = 16L * 1024L * 1024L,
    val cachePolicy: CachePolicy = CachePolicy.DEFAULT,
    val redirectPolicy: RedirectPolicy = RedirectPolicy.FOLLOW,
    val maxRedirects: Int = 10,
    val tag: String? = null
) {
    init {
        require(connectTimeoutMillis in 1..120_000) { "connectTimeoutMillis out of range" }
        require(readTimeoutMillis in 1..300_000) { "readTimeoutMillis out of range" }
        require(maxResponseBytes in 0..512L * 1024L * 1024L) { "maxResponseBytes out of range" }
        require(maxRedirects in 0..20) { "maxRedirects out of range" }
        require(body == null || method.permitsBody) { "$method does not permit a request body" }
    }

    class Builder(rawUrl: String) {
        private var url = AetherUrl.parse(rawUrl)
        private var method = HttpMethod.GET
        private var headers = NetworkHeaders.builder()
        private var body: ByteArray? = null
        private var connectTimeoutMillis = 15_000
        private var readTimeoutMillis = 30_000
        private var maxResponseBytes = 16L * 1024L * 1024L
        private var cachePolicy = CachePolicy.DEFAULT
        private var redirectPolicy = RedirectPolicy.FOLLOW
        private var maxRedirects = 10
        private var tag: String? = null

        fun url(raw: String) = apply { url = AetherUrl.parse(raw) }
        fun method(value: HttpMethod, bytes: ByteArray? = null) = apply { method = value; body = bytes?.copyOf() }
        fun header(name: String, value: String) = apply { headers.set(name, value) }
        fun addHeader(name: String, value: String) = apply { headers.add(name, value) }
        fun connectTimeoutMillis(value: Int) = apply { connectTimeoutMillis = value }
        fun readTimeoutMillis(value: Int) = apply { readTimeoutMillis = value }
        fun maxResponseBytes(value: Long) = apply { maxResponseBytes = value }
        fun cachePolicy(value: CachePolicy) = apply { cachePolicy = value }
        fun redirectPolicy(value: RedirectPolicy) = apply { redirectPolicy = value }
        fun maxRedirects(value: Int) = apply { maxRedirects = value }
        fun tag(value: String?) = apply { tag = value }
        fun build() = NetworkRequest(url, method, headers.build(), body, connectTimeoutMillis, readTimeoutMillis, maxResponseBytes, cachePolicy, redirectPolicy, maxRedirects, tag)
    }
}
