package com.mohnishraj.aether.core.browser

import java.util.concurrent.atomic.AtomicLong

data class BrowserApiLimits(
    val maxStorageBytesPerOrigin: Int = 5 * 1024 * 1024,
    val maxStorageKeyChars: Int = 8_192,
    val maxStorageValueChars: Int = 1_000_000,
    val maxEventListeners: Int = 10_000,
    val maxEventPathDepth: Int = 1_024,
    val maxMutationObservers: Int = 1_024,
    val maxMutationRecordsPerObserver: Int = 10_000,
    val maxSelectorMatches: Int = 100_000,
    val maxClipboardChars: Int = 1_000_000,
    val maxFetchResponseBytes: Long = 16L * 1024L * 1024L
) {
    init {
        require(maxStorageBytesPerOrigin > 0)
        require(maxStorageKeyChars > 0 && maxStorageValueChars > 0)
        require(maxEventListeners > 0 && maxEventPathDepth > 0)
        require(maxMutationObservers > 0 && maxMutationRecordsPerObserver > 0)
        require(maxSelectorMatches > 0 && maxClipboardChars > 0)
        require(maxFetchResponseBytes > 0)
    }
}

data class BrowserApiStatistics(
    val pagesOpened: Long,
    val scriptsEvaluated: Long,
    val domQueries: Long,
    val domMutations: Long,
    val eventsDispatched: Long,
    val storageWrites: Long,
    val fetches: Long,
    val clipboardOperations: Long
)

internal class BrowserApiCounters {
    val pagesOpened = AtomicLong()
    val scriptsEvaluated = AtomicLong()
    val domQueries = AtomicLong()
    val domMutations = AtomicLong()
    val eventsDispatched = AtomicLong()
    val storageWrites = AtomicLong()
    val fetches = AtomicLong()
    val clipboardOperations = AtomicLong()

    fun snapshot() = BrowserApiStatistics(
        pagesOpened.get(), scriptsEvaluated.get(), domQueries.get(), domMutations.get(),
        eventsDispatched.get(), storageWrites.get(), fetches.get(), clipboardOperations.get()
    )
}

interface ClipboardPort {
    fun readText(): String
    fun writeText(value: String)
}

class InMemoryClipboardPort(initialValue: String = "") : ClipboardPort {
    @Volatile private var value: String = initialValue
    override fun readText(): String = value
    override fun writeText(value: String) { this.value = value }
}
