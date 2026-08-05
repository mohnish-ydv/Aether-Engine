package com.mohnishraj.aether.core.net.cookie

import com.mohnishraj.aether.core.fs.FileSystem
import com.mohnishraj.aether.core.fs.VirtualPath
import com.mohnishraj.aether.core.net.model.AetherUrl
import com.mohnishraj.aether.core.net.model.NetworkHeaders
import com.mohnishraj.aether.core.time.EngineClock
import java.util.Base64

interface CookieJar {
    fun loadForRequest(url: AetherUrl): List<HttpCookie>
    fun saveFromResponse(url: AetherUrl, headers: NetworkHeaders)
    fun snapshot(): List<HttpCookie>
    fun clear()
}

open class InMemoryCookieJar(private val clock: EngineClock = EngineClock.SYSTEM) : CookieJar {
    protected val lock = Any()
    protected val cookies = mutableListOf<HttpCookie>()

    override fun loadForRequest(url: AetherUrl): List<HttpCookie> = synchronized(lock) {
        removeExpiredLocked()
        cookies.filter { it.matches(url, clock.nowMillis()) }
            .sortedWith(compareByDescending<HttpCookie> { it.path.length }.thenBy { it.creationTimeMillis })
    }

    override fun saveFromResponse(url: AetherUrl, headers: NetworkHeaders) {
        synchronized(lock) {
            val now = clock.nowMillis()
            for (value in headers.values("Set-Cookie")) {
                val parsed = HttpCookie.parse(value, url, now) ?: continue
                cookies.removeAll { it.name == parsed.name && it.domain == parsed.domain && it.path == parsed.path }
                if (!parsed.isExpired(now)) cookies += parsed
            }
            removeExpiredLocked()
            enforceLimitsLocked()
            onChangedLocked()
        }
    }

    override fun snapshot(): List<HttpCookie> = synchronized(lock) { removeExpiredLocked(); cookies.toList() }
    override fun clear() = synchronized(lock) { cookies.clear(); onChangedLocked() }
    protected open fun onChangedLocked() = Unit
    protected fun removeExpiredLocked() { cookies.removeAll { it.isExpired(clock.nowMillis()) } }
    protected fun enforceLimitsLocked() {
        while (cookies.size > MAX_COOKIES_TOTAL) {
            val oldest = cookies.minByOrNull { it.creationTimeMillis } ?: break
            cookies.remove(oldest)
        }
        cookies.groupBy { it.domain }.forEach { (_, domainCookies) ->
            val excess = (domainCookies.size - MAX_COOKIES_PER_DOMAIN).coerceAtLeast(0)
            domainCookies.sortedBy { it.creationTimeMillis }.take(excess).forEach(cookies::remove)
        }
    }

    private companion object {
        const val MAX_COOKIES_TOTAL = 512
        const val MAX_COOKIES_PER_DOMAIN = 180
    }
}

class PersistentCookieJar(
    private val fileSystem: FileSystem,
    private val path: VirtualPath = VirtualPath.of("/network/cookies.db"),
    clock: EngineClock = EngineClock.SYSTEM
) : InMemoryCookieJar(clock) {
    init { load() }

    override fun onChangedLocked() {
        val encoded = cookies.joinToString("\n") { encode(it) }
        runCatching { fileSystem.write(path, encoded.toByteArray(Charsets.UTF_8)) }
    }

    private fun load() = synchronized(lock) {
        if (!fileSystem.exists(path)) return@synchronized
        runCatching {
            fileSystem.read(path).toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.mapNotNull(::decode).forEach(cookies::add)
            removeExpiredLocked()
            enforceLimitsLocked()
        }.onFailure { cookies.clear() }
    }

    private fun encode(cookie: HttpCookie): String = listOf(
        enc(cookie.name), enc(cookie.value), enc(cookie.domain), enc(cookie.path), cookie.expiresAtMillis?.toString().orEmpty(),
        cookie.secure.toString(), cookie.httpOnly.toString(), cookie.hostOnly.toString(), cookie.sameSite.name, cookie.creationTimeMillis.toString()
    ).joinToString("|")

    private fun decode(line: String): HttpCookie? = runCatching {
        val parts = line.split('|')
        require(parts.size == 10)
        HttpCookie(dec(parts[0]), dec(parts[1]), dec(parts[2]), dec(parts[3]), parts[4].takeIf(String::isNotEmpty)?.toLong(),
            parts[5].toBooleanStrict(), parts[6].toBooleanStrict(), parts[7].toBooleanStrict(), SameSite.valueOf(parts[8]), parts[9].toLong())
    }.getOrNull()

    companion object {
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()
        private fun enc(value: String) = encoder.encodeToString(value.toByteArray(Charsets.UTF_8))
        private fun dec(value: String) = decoder.decode(value).toString(Charsets.UTF_8)
    }
}

fun CookieJar.cookieHeader(url: AetherUrl): String? {
    val result = StringBuilder()
    for (cookie in loadForRequest(url)) {
        val item = "${cookie.name}=${cookie.value}"
        val separatorLength = if (result.isEmpty()) 0 else 2
        if (item.length + separatorLength > MAX_COOKIE_HEADER_CHARS) continue
        if (result.length + separatorLength + item.length > MAX_COOKIE_HEADER_CHARS) break
        if (result.isNotEmpty()) result.append("; ")
        result.append(item)
    }
    return result.toString().ifEmpty { null }
}

private const val MAX_COOKIE_HEADER_CHARS = 8_192
