package com.mohnishraj.aether.core.net

import com.mohnishraj.aether.core.log.EngineLogger
import com.mohnishraj.aether.core.net.cache.CacheEntry
import com.mohnishraj.aether.core.net.cache.CachePolicyEvaluator
import com.mohnishraj.aether.core.net.cache.HttpCache
import com.mohnishraj.aether.core.net.cookie.CookieJar
import com.mohnishraj.aether.core.net.cookie.cookieHeader
import com.mohnishraj.aether.core.net.model.AetherUrl
import com.mohnishraj.aether.core.net.model.CachePolicy
import com.mohnishraj.aether.core.net.model.HttpMethod
import com.mohnishraj.aether.core.net.model.NetworkFailure
import com.mohnishraj.aether.core.net.model.NetworkFailureKind
import com.mohnishraj.aether.core.net.model.NetworkHeaders
import com.mohnishraj.aether.core.net.model.NetworkRequest
import com.mohnishraj.aether.core.net.model.NetworkResponse
import com.mohnishraj.aether.core.net.model.NetworkResult
import com.mohnishraj.aether.core.net.model.NetworkTiming
import com.mohnishraj.aether.core.net.model.RedirectHop
import com.mohnishraj.aether.core.net.model.RedirectPolicy
import com.mohnishraj.aether.core.net.transport.CancellationToken
import com.mohnishraj.aether.core.net.transport.NetworkTransport
import com.mohnishraj.aether.core.net.transport.TransferObserver
import com.mohnishraj.aether.core.net.transport.TransportExchange
import com.mohnishraj.aether.core.time.EngineClock
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import javax.net.ssl.SSLException

class NetworkClient(
    private val transport: NetworkTransport,
    val cache: HttpCache,
    val cookieJar: CookieJar,
    private val stats: NetworkStats = NetworkStats(),
    private val clock: EngineClock = EngineClock.SYSTEM,
    private val logger: EngineLogger? = null,
    private val userAgent: String = "AetherEngine/0.2"
) {
    fun execute(
        request: NetworkRequest,
        observer: TransferObserver = TransferObserver.NONE,
        cancellation: CancellationToken = CancellationToken()
    ): NetworkResult<NetworkResponse> = executeInternal(request, observer, cancellation, null)

    fun stream(
        request: NetworkRequest,
        output: OutputStream,
        observer: TransferObserver = TransferObserver.NONE,
        cancellation: CancellationToken = CancellationToken()
    ): NetworkResult<NetworkResponse> = executeInternal(request.copy(cachePolicy = CachePolicy.NETWORK_ONLY), observer, cancellation, output)

    fun statistics(): NetworkStatsSnapshot = stats.snapshot()

    private fun executeInternal(
        originalRequest: NetworkRequest,
        observer: TransferObserver,
        cancellation: CancellationToken,
        streamOutput: OutputStream?
    ): NetworkResult<NetworkResponse> {
        val started = System.nanoTime()
        var request = originalRequest.copy(url = originalRequest.url.withoutFragment())
        var cached: CacheEntry? = null
        val redirects = mutableListOf<RedirectHop>()
        val visited = linkedSetOf<String>()
        stats.request(request.body?.size?.toLong() ?: 0L)

        try {
            if (streamOutput == null && request.method == HttpMethod.GET && request.cachePolicy !in setOf(CachePolicy.NETWORK_ONLY, CachePolicy.NO_STORE)) {
                cached = cacheGet(request)
                if (cached != null && ((cached.isFresh(clock.nowMillis()) && !CachePolicyEvaluator.requiresValidation(request)) || request.cachePolicy == CachePolicy.CACHE_ONLY)) {
                    val response = cachedResponse(originalRequest, cached, started, redirects)
                    stats.success(response.bytesReceived, true)
                    return NetworkResult.Success(response)
                }
                if (request.cachePolicy == CachePolicy.CACHE_ONLY) return fail(NetworkFailureKind.CACHE_MISS, "No cached response for ${request.url}", request.url)
            }

            requestLoop@ while (true) {
                if (cancellation.isCancelled()) return fail(NetworkFailureKind.CANCELLED, "Network call cancelled", request.url)
                val visitKey = "${request.method} ${request.url}"
                if (!visited.add(visitKey)) return fail(NetworkFailureKind.REDIRECT, "Redirect loop detected", request.url)
                request = prepareRequest(request, cached)
                logger?.debug("Network", "${request.method} ${request.url}")

                val exchange = when (val opened = transport.open(request)) {
                    is NetworkResult.Success -> opened.value
                    is NetworkResult.Failure -> { stats.failure(); return opened }
                }
                val headersReceived = System.nanoTime()
                try {
                    saveCookies(request.url, exchange.headers)
                    val target = redirectTarget(exchange.statusCode, exchange.headers, request.url)
                    if (target != null) {
                        when (request.redirectPolicy) {
                            RedirectPolicy.ERROR -> return fail(NetworkFailureKind.REDIRECT, "Redirect rejected by policy", request.url)
                            RedirectPolicy.MANUAL -> Unit
                            RedirectPolicy.FOLLOW -> {
                                if (redirects.size >= request.maxRedirects) return fail(NetworkFailureKind.REDIRECT, "Too many redirects", request.url)
                                redirects += RedirectHop(exchange.statusCode, request.url, target)
                                stats.redirect()
                                request = redirectedRequest(request, exchange.statusCode, target)
                                cached = null
                                continue@requestLoop
                            }
                        }
                    }

                    if (exchange.statusCode == 304 && cached != null && streamOutput == null) {
                        val merged = CachePolicyEvaluator.merge304(cached, exchange.headers, clock.nowMillis())
                        if (CachePolicyEvaluator.allowsStorage(merged.headers)) cachePut(merged) else cacheRemove(merged.url)
                        val response = cachedResponse(originalRequest, merged, started, redirects, headersReceived)
                        stats.success(response.bytesReceived, true)
                        return NetworkResult.Success(response)
                    }

                    val bodyRead = when (val result = readBody(exchange, request, streamOutput, observer, cancellation)) {
                        is NetworkResult.Success -> result.value
                        is NetworkResult.Failure -> { stats.failure(); return result }
                    }
                    val response = NetworkResponse(
                        request = originalRequest,
                        finalUrl = request.url,
                        statusCode = exchange.statusCode,
                        reasonPhrase = exchange.reasonPhrase,
                        headers = decodedHeaders(exchange.headers, bodyRead.decodedEncoding != null),
                        body = bodyRead.body,
                        protocol = exchange.protocol,
                        fromCache = false,
                        redirectChain = redirects.toList(),
                        timing = NetworkTiming(started, headersReceived, System.nanoTime()),
                        bytesReceived = bodyRead.bytes,
                        transportDetails = exchange.details + ("contentEncoding" to (exchange.headers["Content-Encoding"] ?: "identity"))
                    )
                    if (streamOutput == null && request.cachePolicy != CachePolicy.NO_STORE) {
                        CachePolicyEvaluator.buildEntry(request, response.statusCode, response.reasonPhrase, response.headers, response.body, response.protocol, clock.nowMillis())?.let(::cachePut)
                    }
                    stats.success(response.bytesReceived, false)
                    logger?.info("Network", "${response.statusCode} ${request.url} ${"%.1f".format(Locale.US, response.timing.totalMillis)}ms")
                    return NetworkResult.Success(response)
                } finally {
                    exchange.close()
                }
            }
        } catch (error: Exception) {
            stats.failure()
            return NetworkResult.Failure(mapFailure(error, request.url))
        }
    }

    private fun prepareRequest(request: NetworkRequest, cached: CacheEntry?): NetworkRequest {
        val builder = request.headers.newBuilder().apply {
            FORBIDDEN_TRANSPORT_HEADERS.forEach(::removeAll)
        }
        if (!request.headers.contains("User-Agent")) builder.set("User-Agent", userAgent)
        if (!request.headers.contains("Accept")) builder.set("Accept", "*/*")
        if (!request.headers.contains("Accept-Encoding")) builder.set("Accept-Encoding", "gzip, deflate")
        if (!request.headers.contains("Cookie")) loadCookieHeader(request.url)?.let { builder.set("Cookie", it) }
        if (cached != null && (!cached.isFresh(clock.nowMillis()) || CachePolicyEvaluator.requiresValidation(request))) {
            CachePolicyEvaluator.conditionalHeaders(cached).forEach { (name, value) -> if (!request.headers.contains(name)) builder.set(name, value) }
        }
        return request.copy(headers = builder.build())
    }

    private fun cacheGet(request: NetworkRequest): CacheEntry? = try {
        cache.get(request)
    } catch (error: Exception) {
        logger?.warn("NetworkCache", "Read failed: ${error.message.orEmpty()}")
        null
    }

    private fun cachePut(entry: CacheEntry) {
        try {
            cache.put(entry)
        } catch (error: Exception) {
            logger?.warn("NetworkCache", "Write failed: ${error.message.orEmpty()}")
        }
    }

    private fun cacheRemove(url: AetherUrl) {
        try {
            cache.remove(url)
        } catch (error: Exception) {
            logger?.warn("NetworkCache", "Remove failed: ${error.message.orEmpty()}")
        }
    }

    private fun loadCookieHeader(url: AetherUrl): String? = try {
        cookieJar.cookieHeader(url)
    } catch (error: Exception) {
        logger?.warn("Cookies", "Read failed: ${error.message.orEmpty()}")
        null
    }

    private fun saveCookies(url: AetherUrl, headers: NetworkHeaders) {
        try {
            cookieJar.saveFromResponse(url, headers)
        } catch (error: Exception) {
            logger?.warn("Cookies", "Write failed: ${error.message.orEmpty()}")
        }
    }

    private fun redirectedRequest(previous: NetworkRequest, status: Int, target: AetherUrl): NetworkRequest {
        val changeToGet = status == 303 || ((status == 301 || status == 302) && previous.method == HttpMethod.POST)
        val headers = previous.headers.newBuilder().apply {
            removeAll("Host")
            removeAll("Cookie")
            removeAll("If-None-Match")
            removeAll("If-Modified-Since")
            if (!previous.url.sameOrigin(target)) {
                removeAll("Authorization")
                removeAll("Proxy-Authorization")
            }
            if (changeToGet) {
                removeAll("Content-Length")
                removeAll("Content-Type")
            }
        }.build()
        return previous.copy(
            url = target.withoutFragment(),
            method = if (changeToGet && previous.method != HttpMethod.HEAD) HttpMethod.GET else previous.method,
            headers = headers,
            body = if (changeToGet) null else previous.body
        )
    }

    private fun redirectTarget(status: Int, headers: NetworkHeaders, base: AetherUrl): AetherUrl? {
        if (status !in REDIRECT_CODES) return null
        val location = headers["Location"] ?: return null
        return runCatching { base.resolve(location) }.getOrNull()
    }

    private fun readBody(
        exchange: TransportExchange,
        request: NetworkRequest,
        output: OutputStream?,
        observer: TransferObserver,
        cancellation: CancellationToken
    ): NetworkResult<BodyRead> {
        if (request.method == HttpMethod.HEAD || exchange.statusCode in 100..199 || exchange.statusCode in setOf(204, 304)) {
            return NetworkResult.Success(BodyRead(byteArrayOf(), 0))
        }
        val collector = output ?: ByteArrayOutputStream()
        val raw = ExchangeInputStream(exchange)
        val encodings = exchange.headers["Content-Encoding"].orEmpty()
            .split(',')
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() && it != "identity" }
        val decodedEncoding = encodings.singleOrNull()?.takeIf { it in setOf("gzip", "x-gzip", "deflate") }
        val decoded: InputStream = when (decodedEncoding) {
            "gzip", "x-gzip" -> GZIPInputStream(raw)
            "deflate" -> InflaterInputStream(raw)
            else -> raw
        }
        var total = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        decoded.use { input ->
            while (true) {
                if (cancellation.isCancelled()) return NetworkResult.Failure(NetworkFailure(NetworkFailureKind.CANCELLED, "Network call cancelled", request.url))
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > request.maxResponseBytes) return NetworkResult.Failure(
                    NetworkFailure(NetworkFailureKind.RESPONSE_TOO_LARGE, "Response exceeded ${request.maxResponseBytes} bytes", request.url)
                )
                collector.write(buffer, 0, count)
                observer.onProgress(total, exchange.contentLength.takeIf { it >= 0 })
            }
        }
        val body = if (collector is ByteArrayOutputStream) collector.toByteArray() else byteArrayOf()
        return NetworkResult.Success(BodyRead(body, total, decodedEncoding))
    }

    private fun decodedHeaders(headers: NetworkHeaders, decoded: Boolean): NetworkHeaders = if (!decoded) headers
    else headers.newBuilder().removeAll("Content-Encoding").removeAll("Content-Length").build()

    private fun cachedResponse(
        original: NetworkRequest,
        entry: CacheEntry,
        started: Long,
        redirects: List<RedirectHop>,
        headersNanos: Long = System.nanoTime()
    ): NetworkResponse = NetworkResponse(
        original, entry.url, entry.statusCode, entry.reasonPhrase, entry.headers, entry.body.copyOf(), entry.protocol,
        true, redirects.toList(), NetworkTiming(started, headersNanos, System.nanoTime()), entry.body.size.toLong(), mapOf("cache" to "aether-private")
    )

    private fun fail(kind: NetworkFailureKind, message: String, url: AetherUrl): NetworkResult.Failure {
        stats.failure()
        return NetworkResult.Failure(NetworkFailure(kind, message, url))
    }

    private fun mapFailure(error: Throwable, url: AetherUrl): NetworkFailure {
        val kind = when (error) {
            is SocketTimeoutException -> NetworkFailureKind.TIMEOUT
            is UnknownHostException -> NetworkFailureKind.DNS
            is ConnectException -> NetworkFailureKind.CONNECT
            is SSLException -> NetworkFailureKind.TLS
            is IOException -> NetworkFailureKind.IO
            is IllegalArgumentException -> NetworkFailureKind.INVALID_REQUEST
            else -> NetworkFailureKind.UNKNOWN
        }
        return NetworkFailure(kind, error.message ?: error::class.java.simpleName, url, error::class.java.name)
    }

    private data class BodyRead(val body: ByteArray, val bytes: Long, val decodedEncoding: String? = null)
    private class ExchangeInputStream(private val exchange: TransportExchange) : InputStream() {
        private val one = ByteArray(1)
        override fun read(): Int = if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xff
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = exchange.read(buffer, offset, length)
        override fun close() = Unit
    }

    companion object {
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val FORBIDDEN_TRANSPORT_HEADERS = setOf(
            "Host",
            "Content-Length",
            "Transfer-Encoding",
            "Connection",
            "Proxy-Connection",
            "Upgrade"
        )
    }
}
