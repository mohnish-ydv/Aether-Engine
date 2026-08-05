package com.mohnishraj.aether.core

import com.mohnishraj.aether.core.crash.CrashEnvelope
import com.mohnishraj.aether.core.crash.CrashEnvelopeCodec
import kotlin.test.Test
import kotlin.test.assertEquals

class CrashCodecTest {
    @Test fun roundTripsEnvelope() {
        val original = CrashEnvelope("id", 42, "main", "Type", "hello=world", "stack\nline", mapOf("a" to "b"))
        assertEquals(original, CrashEnvelopeCodec.decode(CrashEnvelopeCodec.encode(original)))
    }
}
