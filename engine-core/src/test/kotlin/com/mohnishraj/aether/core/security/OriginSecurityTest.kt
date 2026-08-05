package com.mohnishraj.aether.core.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OriginSecurityTest {
    @Test fun sameHttpsOriginMatches() {
        assertTrue(AetherOrigin.parse("https://example.test/a").sameOrigin(AetherOrigin.parse("https://example.test/b")))
    }

    @Test fun schemeDifferenceDoesNotMatch() {
        assertFalse(AetherOrigin.parse("https://example.test/").sameOrigin(AetherOrigin.parse("http://example.test/")))
    }

    @Test fun explicitDefaultPortMatchesImplicitPort() {
        assertTrue(AetherOrigin.parse("https://example.test:443/").sameOrigin(AetherOrigin.parse("https://example.test/")))
    }

    @Test fun nonDefaultPortDoesNotMatch() {
        assertFalse(AetherOrigin.parse("https://example.test:444/").sameOrigin(AetherOrigin.parse("https://example.test/")))
    }

    @Test fun hostDifferenceDoesNotMatch() {
        assertFalse(AetherOrigin.parse("https://a.test/").sameOrigin(AetherOrigin.parse("https://b.test/")))
    }

    @Test fun originSerializationOmitsDefaultPort() {
        assertEquals("https://example.test", AetherOrigin.parse("https://example.test:443/").serialized)
    }

    @Test fun originSerializationKeepsCustomPort() {
        assertEquals("https://example.test:8443", AetherOrigin.parse("https://example.test:8443/").serialized)
    }

    @Test fun opaqueOriginsNeverMatch() {
        val first = AetherOrigin.opaque("one")
        val second = AetherOrigin.opaque("one")
        assertFalse(first.sameOrigin(second))
    }

    @Test fun opaqueOriginSerializesAsNull() {
        assertEquals("null", AetherOrigin.opaque("token").serialized)
    }

    @Test fun differentOriginObjectsAreNotEqual() {
        assertNotEquals(AetherOrigin.parse("https://a.test/"), AetherOrigin.parse("https://b.test/"))
    }
}
