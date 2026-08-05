package com.mohnishraj.aether.core

import com.mohnishraj.aether.core.memory.ArenaOutOfMemoryException
import com.mohnishraj.aether.core.memory.ByteArena
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ByteArenaTest {
    @Test fun alignsAndResets() {
        val arena = ByteArena(32)
        arena.allocate(3)
        val aligned = arena.allocate(4, 8)
        assertEquals(8, aligned.offset)
        aligned.write(byteArrayOf(9, 8, 7, 6))
        assertContentEquals(byteArrayOf(9, 8, 7, 6), aligned.read())
        arena.reset(true)
        assertEquals(0, arena.usedBytes())
    }

    @Test fun throwsOnExhaustion() {
        val arena = ByteArena(4)
        assertFailsWith<ArenaOutOfMemoryException> { arena.allocate(5) }
    }
}
