package com.mohnishraj.aether.core.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AddressResolverTest {
    private val resolver = AddressResolver()

    @Test fun blankInputOpensNewTab() {
        val result = resolver.resolve("   ")
        assertEquals(AddressResolver.HOME_URL, result.url)
        assertTrue(result.internal)
    }

    @Test fun aboutBlankOpensNewTab() {
        assertEquals(AddressResolver.HOME_URL, resolver.resolve("about:blank").url)
    }

    @Test fun aetherNewTabOpensNewTab() {
        assertTrue(resolver.resolve("aether:newtab").internal)
    }

    @Test fun explicitHttpsIsPreserved() {
        val result = resolver.resolve("https://Example.com/a")
        assertEquals("https://example.com/a", result.url)
        assertFalse(result.wasSearch)
    }

    @Test fun explicitHttpIsPreserved() {
        assertEquals("http://example.com/", resolver.resolve("http://example.com").url)
    }

    @Test fun domainGetsHttps() {
        assertEquals("https://example.com/", resolver.resolve("example.com").url)
    }

    @Test fun domainPathGetsHttps() {
        assertEquals("https://example.com/docs", resolver.resolve("example.com/docs").url)
    }

    @Test fun localhostGetsHttps() {
        assertEquals("https://localhost/", resolver.resolve("localhost").url)
    }

    @Test fun searchQueryIsEncoded() {
        val result = resolver.resolve("aether browser engine")
        assertTrue(result.wasSearch)
        assertTrue("aether%20browser%20engine" in result.url)
    }

    @Test fun unicodeQueryIsAccepted() {
        assertTrue(resolver.resolve("नमस्ते वेब").wasSearch)
    }

    @Test fun customTemplateRequiresPlaceholder() {
        assertFailsWith<IllegalArgumentException> { AddressResolver("https://search.invalid/") }
    }

    @Test fun longInputIsBounded() {
        val result = resolver.resolve("x".repeat(20_000))
        assertTrue(result.url.length < 20_000)
    }
}
