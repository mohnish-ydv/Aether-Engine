package com.mohnishraj.aether.core.net

import com.mohnishraj.aether.core.net.model.AetherUrl
import com.mohnishraj.aether.core.net.model.HttpMethod
import com.mohnishraj.aether.core.net.model.NetworkHeaders
import com.mohnishraj.aether.core.net.model.NetworkRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AetherUrlHeadersTest {
    @Test fun parsesIdnAndDefaultPort() {
        val url = AetherUrl.parse("https://Exämple.com:443/a")
        assertEquals("xn--exmple-cua.com", url.host)
        assertEquals("https://xn--exmple-cua.com/a", url.toString())
        assertEquals(443, url.effectivePort)
    }

    @Test fun normalizesAndResolvesPaths() {
        val url = AetherUrl.parse("https://example.com/a/b/../c")
        assertEquals("/a/c", url.encodedPath)
        assertEquals("/d", url.resolve("../../d").encodedPath)
    }

    @Test fun rejectsUnsupportedSchemesAndUserInfo() {
        assertFailsWith<IllegalArgumentException> { AetherUrl.parse("file:///tmp/a") }
        assertFailsWith<IllegalArgumentException> { AetherUrl.parse("https://user:pass@example.com/") }
    }

    @Test fun rejectsMalformedPortsAndUnbracketedIpv6() {
        assertFailsWith<IllegalArgumentException> { AetherUrl.parse("https://example.com:abc/") }
        assertFailsWith<IllegalArgumentException> { AetherUrl.parse("https://example.com:/") }
        assertFailsWith<IllegalArgumentException> { AetherUrl.parse("https://example.com:70000/") }
        assertFailsWith<IllegalArgumentException> { AetherUrl.parse("https://2001:db8::1/") }
        assertEquals("https://[2001:db8::1]/", AetherUrl.parse("https://[2001:db8::1]/").toString())
    }

    @Test fun headersAreCaseInsensitiveAndRejectInjection() {
        val headers = NetworkHeaders.builder().add("X-Test", "one").add("x-test", "two").build()
        assertEquals(listOf("one", "two"), headers.values("X-TEST"))
        assertFailsWith<IllegalArgumentException> { NetworkHeaders.builder().add("X", "ok\r\nInjected: yes") }
        assertFailsWith<IllegalArgumentException> { NetworkHeaders.builder().add("X".repeat(257), "value") }
    }

    @Test fun requestValidationProtectsBodyAndLimits() {
        assertFailsWith<IllegalArgumentException> {
            NetworkRequest(AetherUrl.parse("https://example.com"), method = HttpMethod.GET, body = byteArrayOf(1))
        }
        val request = NetworkRequest.Builder("https://example.com").method(HttpMethod.POST, "x".toByteArray()).build()
        assertTrue(request.method.permitsBody)
        assertFalse(request.url.fragment != null)
    }
}
