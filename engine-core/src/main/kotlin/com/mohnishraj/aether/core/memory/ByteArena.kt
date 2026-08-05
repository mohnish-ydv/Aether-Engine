package com.mohnishraj.aether.core.memory

import java.util.concurrent.atomic.AtomicLong

class ByteArena(val capacity: Int) {
    init { require(capacity > 0) { "capacity must be positive" } }

    private val storage = ByteArray(capacity)
    private var cursor = 0
    private var highWaterMark = 0
    private val allocationCount = AtomicLong(0)

    @Synchronized
    fun allocate(size: Int, alignment: Int = 1): ArenaSlice {
        require(size >= 0) { "size cannot be negative" }
        require(alignment > 0 && alignment and (alignment - 1) == 0) {
            "alignment must be a positive power of two"
        }
        val aligned = (cursor + alignment - 1) and (alignment - 1).inv()
        if (aligned + size > capacity) {
            throw ArenaOutOfMemoryException(size, availableBytes())
        }
        val slice = ArenaSlice(storage, aligned, size)
        cursor = aligned + size
        highWaterMark = maxOf(highWaterMark, cursor)
        allocationCount.incrementAndGet()
        return slice
    }

    @Synchronized fun reset(zeroFill: Boolean = false) {
        if (zeroFill && cursor > 0) storage.fill(0, 0, cursor)
        cursor = 0
    }

    @Synchronized fun usedBytes(): Int = cursor
    @Synchronized fun availableBytes(): Int = capacity - cursor
    @Synchronized fun snapshot(): ArenaSnapshot = ArenaSnapshot(
        capacity = capacity,
        used = cursor,
        available = capacity - cursor,
        highWaterMark = highWaterMark,
        allocationCount = allocationCount.get()
    )
}

class ArenaSlice internal constructor(
    private val storage: ByteArray,
    val offset: Int,
    val length: Int
) {
    fun write(source: ByteArray, sourceOffset: Int = 0) {
        require(sourceOffset >= 0 && sourceOffset <= source.size)
        val count = minOf(length, source.size - sourceOffset)
        source.copyInto(storage, offset, sourceOffset, sourceOffset + count)
        if (count < length) storage.fill(0, offset + count, offset + length)
    }

    fun read(): ByteArray = storage.copyOfRange(offset, offset + length)
    fun fill(value: Byte) = storage.fill(value, offset, offset + length)
}

data class ArenaSnapshot(
    val capacity: Int,
    val used: Int,
    val available: Int,
    val highWaterMark: Int,
    val allocationCount: Long
)

class ArenaOutOfMemoryException(requested: Int, available: Int) :
    IllegalStateException("Arena exhausted: requested=$requested available=$available")
