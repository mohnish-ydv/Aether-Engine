package com.mohnishraj.aether.core.net.model

import java.net.IDN
import java.net.URI
import java.util.Locale

data class AetherUrl private constructor(
    val scheme: String,
    val host: String,
    val port: Int,
    val encodedPath: String,
    val encodedQuery: String?,
    val fragment: String?
) {
    val isSecure: Boolean get() = scheme == "https"
    val effectivePort: Int get() = if (port != -1) port else if (isSecure) 443 else 80
    val origin: String get() = buildString {
        append(scheme).append("://").append(hostForDisplay())
        if (port != -1 && port != defaultPort(scheme)) append(':').append(port)
    }
    val requestTarget: String get() = encodedPath + (encodedQuery?.let { "?$it" } ?: "")

    fun resolve(location: String): AetherUrl = parse(toUri().resolve(location).toASCIIString())
    fun sameOrigin(other: AetherUrl): Boolean = scheme == other.scheme && host == other.host && effectivePort == other.effectivePort
    fun withoutFragment(): AetherUrl = if (fragment == null) this else copy(fragment = null)
    fun toUri(): URI = URI(toString())

    override fun toString(): String = buildString {
        append(origin).append(encodedPath)
        if (encodedQuery != null) append('?').append(encodedQuery)
        if (fragment != null) append('#').append(fragment)
    }

    private fun hostForDisplay(): String = if (':' in host && !host.startsWith("[")) "[$host]" else host

    companion object {
        fun parse(raw: String): AetherUrl {
            val trimmed = raw.trim()
            require(trimmed.isNotEmpty()) { "URL is blank" }
            require(trimmed.length <= 8_192) { "URL exceeds 8192 characters" }
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: throw IllegalArgumentException("URL requires a scheme")
            require(scheme == "http" || scheme == "https") { "Unsupported URL scheme: $scheme" }
            require(uri.rawUserInfo == null) { "User-info in URLs is not supported" }
            val authority = parseAuthority(uri.rawAuthority)
            val rawHost = authority.host
            val canonicalHost = if (':' in rawHost) {
                rawHost.lowercase(Locale.ROOT)
            } else {
                val withoutTrailingDot = rawHost.trimEnd('.')
                require(withoutTrailingDot.isNotBlank()) { "URL requires a host" }
                IDN.toASCII(withoutTrailingDot, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
            }
            require(canonicalHost.length <= 253) { "Host exceeds 253 characters" }
            val path = uri.rawPath?.ifEmpty { "/" } ?: "/"
            require(path.startsWith('/')) { "URL path must be absolute" }
            return AetherUrl(scheme, canonicalHost, authority.port, normalizePath(path), uri.rawQuery, uri.rawFragment)
        }

        private data class Authority(val host: String, val port: Int)

        private fun parseAuthority(rawAuthority: String?): Authority {
            require(!rawAuthority.isNullOrBlank()) { "URL requires a host" }
            require('@' !in rawAuthority) { "User-info in URLs is not supported" }
            if (rawAuthority.startsWith('[')) {
                val closing = rawAuthority.indexOf(']')
                require(closing > 1) { "Invalid IPv6 authority" }
                val host = rawAuthority.substring(1, closing)
                val suffix = rawAuthority.substring(closing + 1)
                val port = parsePortSuffix(suffix)
                return Authority(host, port)
            }
            require(rawAuthority.count { it == ':' } <= 1) { "IPv6 hosts must use brackets" }
            val colon = rawAuthority.lastIndexOf(':')
            return if (colon < 0) {
                Authority(rawAuthority, -1)
            } else {
                val host = rawAuthority.substring(0, colon)
                require(host.isNotBlank()) { "URL requires a host" }
                Authority(host, parsePortSuffix(rawAuthority.substring(colon)))
            }
        }

        private fun parsePortSuffix(suffix: String): Int {
            if (suffix.isEmpty()) return -1
            require(suffix.startsWith(':')) { "Invalid URL authority" }
            val digits = suffix.substring(1)
            require(digits.isNotEmpty() && digits.all(Char::isDigit)) { "Invalid URL port" }
            val port = digits.toIntOrNull() ?: throw IllegalArgumentException("Invalid URL port")
            require(port in 0..65_535) { "Invalid port: $port" }
            return port
        }

        private fun normalizePath(path: String): String {
            val segments = ArrayDeque<String>()
            path.split('/').forEach { segment ->
                when (segment) {
                    "", "." -> Unit
                    ".." -> if (segments.isNotEmpty()) segments.removeLast()
                    else -> segments.add(segment)
                }
            }
            val normalized = "/" + segments.joinToString("/")
            return if (path.endsWith('/') && normalized != "/") "$normalized/" else normalized
        }

        private fun defaultPort(scheme: String): Int = if (scheme == "https") 443 else 80
    }
}
