package com.mohnishraj.aether.core.browser.fetch

import com.mohnishraj.aether.core.browser.BrowserApiCounters
import com.mohnishraj.aether.core.browser.BrowserApiLimits
import com.mohnishraj.aether.core.net.NetworkRuntime
import com.mohnishraj.aether.core.net.model.AetherUrl
import com.mohnishraj.aether.core.net.model.CachePolicy
import com.mohnishraj.aether.core.net.model.HttpMethod
import com.mohnishraj.aether.core.net.model.NetworkHeaders
import com.mohnishraj.aether.core.net.model.NetworkRequest
import com.mohnishraj.aether.core.net.model.NetworkResult
import com.mohnishraj.aether.core.net.model.RedirectPolicy
import com.mohnishraj.aether.core.security.DocumentSecurityPolicy
import com.mohnishraj.aether.core.security.SecurityEngine
import com.mohnishraj.aether.core.security.SecurityResourceType

data class BrowserFetchRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val cache: String = "default",
    val redirect: String = "follow",
    val maxResponseBytes: Long? = null
)

data class BrowserFetchResponse(
    val url: String,
    val status: Int,
    val statusText: String,
    val ok: Boolean,
    val redirected: Boolean,
    val fromCache: Boolean,
    val headers: Map<String, List<String>>,
    val body: ByteArray
) {
    fun text(): String = body.toString(Charsets.UTF_8)
}

class BrowserFetchException(message: String) : IllegalStateException(message)

class BrowserFetchBridge internal constructor(
    private val network: NetworkRuntime?,
    private val limits: BrowserApiLimits,
    private val counters: BrowserApiCounters,
    private val security: SecurityEngine,
    private val documentPolicy: DocumentSecurityPolicy
) {
    fun fetch(request: BrowserFetchRequest, baseUrl: String? = null): BrowserFetchResponse {
        val runtime = network ?: throw BrowserFetchException("Networking is unavailable")
        val requestedUrl = if (baseUrl == null) AetherUrl.parse(request.url) else AetherUrl.parse(baseUrl).resolve(request.url)
        val authorization = security.authorizeSubresource(documentPolicy, SecurityResourceType.CONNECT, requestedUrl.toString())
        if (!authorization.allowed) throw BrowserFetchException(authorization.reason)
        val url = AetherUrl.parse(authorization.effectiveUrl ?: requestedUrl.toString())
        val method = runCatching { HttpMethod.valueOf(request.method.uppercase()) }
            .getOrElse { throw BrowserFetchException("Unsupported method ${request.method}") }
        val body = request.body?.toByteArray(Charsets.UTF_8)
        if (body != null && !method.permitsBody) throw BrowserFetchException("$method does not permit a request body")
        val headers = NetworkHeaders.builder().apply { request.headers.forEach(::set) }.build()
        val networkRequest = NetworkRequest(
            url = url,
            method = method,
            headers = headers,
            body = body,
            maxResponseBytes = (request.maxResponseBytes ?: limits.maxFetchResponseBytes).coerceAtMost(limits.maxFetchResponseBytes),
            cachePolicy = when (request.cache.lowercase()) {
                "no-store" -> CachePolicy.NO_STORE
                "reload", "no-cache" -> CachePolicy.NETWORK_ONLY
                "only-if-cached" -> CachePolicy.CACHE_ONLY
                "default", "force-cache" -> CachePolicy.DEFAULT
                else -> throw BrowserFetchException("Unsupported cache mode ${request.cache}")
            },
            redirectPolicy = when (request.redirect.lowercase()) {
                "follow" -> RedirectPolicy.FOLLOW
                "manual" -> RedirectPolicy.MANUAL
                "error" -> RedirectPolicy.ERROR
                else -> throw BrowserFetchException("Unsupported redirect mode ${request.redirect}")
            },
            tag = "browser-fetch"
        )
        counters.fetches.incrementAndGet()
        return when (val result = runtime.client.execute(networkRequest)) {
            is NetworkResult.Success -> {
                val response = result.value
                val redirectDecision = security.authorizeRedirect(url.toString(), response.finalUrl.toString())
                if (!redirectDecision.allowed) throw BrowserFetchException(redirectDecision.reason)
                val corsDecision = security.validateCors(documentPolicy, response.finalUrl.toString(), response.headers)
                if (!corsDecision.allowed) throw BrowserFetchException(corsDecision.reason)
                BrowserFetchResponse(
                    url = response.finalUrl.toString(),
                    status = response.statusCode,
                    statusText = response.reasonPhrase,
                    ok = response.isSuccessful,
                    redirected = response.redirectChain.isNotEmpty(),
                    fromCache = response.fromCache,
                    headers = response.headers.names().associateWith { response.headers.values(it) },
                    body = response.body.copyOf()
                )
            }
            is NetworkResult.Failure -> throw BrowserFetchException("${result.error.kind}: ${result.error.message}")
        }
    }
}
