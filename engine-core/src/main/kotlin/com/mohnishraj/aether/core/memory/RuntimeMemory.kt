package com.mohnishraj.aether.core.memory

data class RuntimeMemorySnapshot(
    val totalBytes: Long,
    val freeBytes: Long,
    val maxBytes: Long
) {
    val usedBytes: Long get() = totalBytes - freeBytes
}

object RuntimeMemory {
    fun snapshot(): RuntimeMemorySnapshot {
        val runtime = Runtime.getRuntime()
        return RuntimeMemorySnapshot(
            totalBytes = runtime.totalMemory(),
            freeBytes = runtime.freeMemory(),
            maxBytes = runtime.maxMemory()
        )
    }
}
