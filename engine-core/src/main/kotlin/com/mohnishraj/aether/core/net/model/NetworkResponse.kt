package com.mohnishraj.aether.core.net.model

import java.nio.charset.Charset

data class NetworkTiming(val startedNanos: Long, val headersReceivedNanos: Long, val completedNanos: Long) {
    val totalMillis: Double get() = (completedNanos - startedNanos) / 1_000_000.0
    val timeToHeadersMillis: Double get() = (headersReceivedNanos - startedNanos) / 1_000_000.0
}

data class RedirectHop(val statusCode: Int, val from: AetherUrl, val to: AetherUrl)

data class NetworkResponse(
    val request: NetworkRequest,
    val finalUrl: AetherUrl,
    val statusCode: Int,
    val reasonPhrase: String,
    val headers: NetworkHeaders,
    val body: ByteArray,
    val protocol: String,
    val fromCache: Boolean,
    val redirectChain: List<RedirectHop>,
    val timing: NetworkTiming,
    val bytesReceived: Long = body.size.toLong(),
    val transportDetails: Map<String, String> = emptyMap()
) {
    val isSuccessful: Boolean get() = statusCode in 200..299
    val contentType: String? get() = headers["Content-Type"]
    fun charset(default: Charset = Charsets.UTF_8): Charset {
        val name = contentType?.split(';')?.drop(1)?.firstOrNull { it.trim().startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')?.trim()?.trim('"', '\'')
        return name?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: default
    }
    fun bodyText(default: Charset = Charsets.UTF_8): String = body.toString(charset(default))
}
