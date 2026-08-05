package com.mohnishraj.aether.core

import com.mohnishraj.aether.core.text.Utf8String
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Utf8StringTest {
    @Test fun roundTripsUnicode() {
        val value = Utf8String.of("नमस्ते 🚀")
        assertEquals(value, Utf8String.fromBytes(value.copyBytes()))
        assertTrue(value.byteLength > value.text.length)
    }

    @Test fun normalizesWhitespace() {
        assertEquals("hello world", Utf8String.of("  hello\n\tworld  ").normalizedWhitespace().text)
    }
}
