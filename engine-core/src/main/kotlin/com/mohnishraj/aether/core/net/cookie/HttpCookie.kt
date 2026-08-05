package com.mohnishraj.aether.core.net.cookie

import com.mohnishraj.aether.core.net.model.AetherUrl
import java.net.IDN
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

enum class SameSite { STRICT, LAX, NONE, UNSPECIFIED }

data class HttpCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val expiresAtMillis: Long?,
    val secure: Boolean,
    val httpOnly: Boolean,
    val hostOnly: Boolean,
    val sameSite: SameSite,
    val creationTimeMillis: Long
) {
    fun isExpired(nowMillis: Long): Boolean = expiresAtMillis?.let { it <= nowMillis } ?: false
    fun matches(url: AetherUrl, nowMillis: Long): Boolean {
        if (isExpired(nowMillis) || (secure && !url.isSecure)) return false
        val hostMatches = if (hostOnly) url.host == domain else url.host == domain || url.host.endsWith(".$domain")
        return hostMatches && pathMatches(url.encodedPath, path)
    }

    companion object {
        private val token = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
        private val rfc1123 = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC)
        private val fallbackFormats = listOf(
            DateTimeFormatter.ofPattern("EEE, dd-MMM-yyyy HH:mm:ss zzz", Locale.US),
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US),
            DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss yyyy", Locale.US)
        )

        fun parse(setCookie: String, requestUrl: AetherUrl, nowMillis: Long): HttpCookie? {
            if (setCookie.length > MAX_SET_COOKIE_CHARS) return null
            val parts = setCookie.split(';')
            val pair = parts.firstOrNull()?.trim().orEmpty()
            val separator = pair.indexOf('=')
            if (separator <= 0) return null
            val name = pair.substring(0, separator).trim()
            val value = pair.substring(separator + 1).trim()
            if (!token.matches(name) || name.length > MAX_COOKIE_NAME_CHARS || value.length > MAX_COOKIE_VALUE_CHARS || !value.all(::isCookieOctet)) return null

            var domain = requestUrl.host
            var hostOnly = true
            var path = defaultPath(requestUrl.encodedPath)
            var expiresAt: Long? = null
            var secure = false
            var httpOnly = false
            var sameSite = SameSite.UNSPECIFIED

            for (rawAttribute in parts.drop(1)) {
                val attribute = rawAttribute.trim()
                val attrName = attribute.substringBefore('=').trim().lowercase(Locale.ROOT)
                val attrValue = attribute.substringAfter('=', "").trim()
                when (attrName) {
                    "domain" -> {
                        val unicodeCandidate = attrValue.trim().trimStart('.').trimEnd('.')
                        val candidate = runCatching { IDN.toASCII(unicodeCandidate, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT) }.getOrNull() ?: return null
                        if (candidate.isBlank() || isIpLiteral(requestUrl.host)) return null
                        if (requestUrl.host != candidate && !requestUrl.host.endsWith(".$candidate")) return null
                        if ('.' !in candidate && candidate != requestUrl.host) return null
                        domain = candidate
                        hostOnly = false
                    }
                    "path" -> if (attrValue.startsWith('/')) path = attrValue
                    "max-age" -> attrValue.toLongOrNull()?.let { seconds ->
                        expiresAt = if (seconds <= 0L) 0L else nowMillis.saturatedPlus(seconds.saturatedTimes(1000L))
                    }
                    "expires" -> if (expiresAt == null) expiresAt = parseHttpDate(attrValue)
                    "secure" -> secure = true
                    "httponly" -> httpOnly = true
                    "samesite" -> sameSite = when (attrValue.lowercase(Locale.ROOT)) {
                        "strict" -> SameSite.STRICT
                        "lax" -> SameSite.LAX
                        "none" -> SameSite.NONE
                        else -> SameSite.UNSPECIFIED
                    }
                }
            }
            if (secure && !requestUrl.isSecure) return null
            if (sameSite == SameSite.NONE && !secure) return null
            if (name.startsWith("__Secure-") && (!secure || !requestUrl.isSecure)) return null
            if (name.startsWith("__Host-") && (!secure || !requestUrl.isSecure || !hostOnly || path != "/")) return null
            return HttpCookie(name, value, domain, path, expiresAt, secure, httpOnly, hostOnly, sameSite, nowMillis)
        }

        fun defaultPath(requestPath: String): String {
            if (!requestPath.startsWith('/') || requestPath == "/") return "/"
            val lastSlash = requestPath.lastIndexOf('/')
            return if (lastSlash <= 0) "/" else requestPath.substring(0, lastSlash)
        }

        fun pathMatches(requestPath: String, cookiePath: String): Boolean {
            if (requestPath == cookiePath) return true
            if (!requestPath.startsWith(cookiePath)) return false
            return cookiePath.endsWith('/') || requestPath.getOrNull(cookiePath.length) == '/'
        }

        private fun isCookieOctet(character: Char): Boolean {
            val code = character.code
            return code == 0x21 || code in 0x23..0x2B || code in 0x2D..0x3A || code in 0x3C..0x5B || code in 0x5D..0x7E
        }

        private fun isIpLiteral(host: String): Boolean = ':' in host || host.all { it.isDigit() || it == '.' }

        private fun parseHttpDate(value: String): Long? {
            try { return Instant.from(rfc1123.parse(value)).toEpochMilli() } catch (_: DateTimeParseException) { }
            for (formatter in fallbackFormats) {
                try { return Instant.from(formatter.withZone(ZoneOffset.UTC).parse(value)).toEpochMilli() } catch (_: DateTimeParseException) { }
            }
            return null
        }

        private const val MAX_SET_COOKIE_CHARS = 4_096
        private const val MAX_COOKIE_NAME_CHARS = 256
        private const val MAX_COOKIE_VALUE_CHARS = 4_096

        private fun Long.saturatedTimes(other: Long): Long = try { Math.multiplyExact(this, other) } catch (_: ArithmeticException) { Long.MAX_VALUE }
        private fun Long.saturatedPlus(other: Long): Long = try { Math.addExact(this, other) } catch (_: ArithmeticException) { Long.MAX_VALUE }
    }
}
