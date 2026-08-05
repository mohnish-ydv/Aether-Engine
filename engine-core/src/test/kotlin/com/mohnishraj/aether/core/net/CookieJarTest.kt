package com.mohnishraj.aether.core.net

import com.mohnishraj.aether.core.fs.MemoryFileSystem
import com.mohnishraj.aether.core.net.cookie.InMemoryCookieJar
import com.mohnishraj.aether.core.net.cookie.PersistentCookieJar
import com.mohnishraj.aether.core.net.model.AetherUrl
import com.mohnishraj.aether.core.net.model.NetworkHeaders
import com.mohnishraj.aether.core.time.EngineClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CookieJarTest {
    private class Clock(var now: Long = 1_000L) : EngineClock { override fun nowMillis(): Long = now }

    @Test fun secureCookieOnlyMatchesHttpsAndPath() {
        val jar = InMemoryCookieJar(Clock())
        val origin = AetherUrl.parse("https://example.com/account/login")
        jar.saveFromResponse(origin, NetworkHeaders.of("Set-Cookie" to "sid=abc; Secure; Path=/account"))
        assertEquals(1, jar.loadForRequest(AetherUrl.parse("https://example.com/account/me")).size)
        assertTrue(jar.loadForRequest(AetherUrl.parse("http://example.com/account/me")).isEmpty())
        assertTrue(jar.loadForRequest(AetherUrl.parse("https://example.com/public")).isEmpty())
    }

    @Test fun rejectsForeignDomainAndInsecureSameSiteNone() {
        val jar = InMemoryCookieJar(Clock())
        val url = AetherUrl.parse("https://example.com/")
        jar.saveFromResponse(url, NetworkHeaders.of("Set-Cookie" to "x=1; Domain=evil.test"))
        jar.saveFromResponse(url, NetworkHeaders.of("Set-Cookie" to "y=1; SameSite=None"))
        assertTrue(jar.snapshot().isEmpty())
    }

    @Test fun maxAgeRemovesExpiredCookie() {
        val clock = Clock()
        val jar = InMemoryCookieJar(clock)
        val url = AetherUrl.parse("https://example.com/")
        jar.saveFromResponse(url, NetworkHeaders.of("Set-Cookie" to "x=1; Max-Age=1"))
        assertEquals(1, jar.snapshot().size)
        clock.now += 1_001
        assertTrue(jar.snapshot().isEmpty())
    }

    @Test fun replacementUsesNameDomainAndPathKey() {
        val jar = InMemoryCookieJar(Clock())
        val url = AetherUrl.parse("https://example.com/")
        jar.saveFromResponse(url, NetworkHeaders.of("Set-Cookie" to "x=1; Path=/"))
        jar.saveFromResponse(url, NetworkHeaders.of("Set-Cookie" to "x=2; Path=/"))
        assertEquals("2", jar.snapshot().single().value)
    }

    @Test fun persistentJarRoundTrips() {
        val fs = MemoryFileSystem()
        val clock = Clock()
        val url = AetherUrl.parse("https://example.com/")
        PersistentCookieJar(fs, clock = clock).saveFromResponse(url, NetworkHeaders.of("Set-Cookie" to "sid=abc; Secure"))
        assertEquals("abc", PersistentCookieJar(fs, clock = clock).snapshot().single().value)
    }
}
