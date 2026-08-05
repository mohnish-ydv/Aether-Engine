package com.mohnishraj.aether.core.browser.storage

import com.mohnishraj.aether.core.browser.BrowserApiCounters
import com.mohnishraj.aether.core.browser.BrowserApiLimits
import com.mohnishraj.aether.core.fs.FileSystem
import com.mohnishraj.aether.core.fs.VirtualPath
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class StorageQuotaExceededException(message: String) : IllegalStateException(message)

class BrowserStorageArea internal constructor(
    private val limits: BrowserApiLimits,
    initial: Map<String, String> = emptyMap(),
    private val persist: ((Map<String, String>) -> Unit)? = null,
    private val counters: BrowserApiCounters? = null
) {
    private val entries = LinkedHashMap<String, String>().apply { putAll(initial) }

    val length: Int @Synchronized get() = entries.size

    @Synchronized fun key(index: Int): String? = entries.keys.elementAtOrNull(index)
    @Synchronized fun getItem(key: String): String? = entries[key]

    @Synchronized fun setItem(key: String, value: String) {
        validate(key, value)
        val candidate = LinkedHashMap(entries).apply { put(key, value) }
        val bytes = candidate.entries.sumOf { (k, v) -> k.toByteArray().size + v.toByteArray().size }
        if (bytes > limits.maxStorageBytesPerOrigin) {
            throw StorageQuotaExceededException("Storage quota ${limits.maxStorageBytesPerOrigin} bytes exceeded")
        }
        entries[key] = value
        persist?.invoke(entries)
        counters?.storageWrites?.incrementAndGet()
    }

    @Synchronized fun removeItem(key: String) {
        if (entries.remove(key) != null) {
            persist?.invoke(entries)
            counters?.storageWrites?.incrementAndGet()
        }
    }

    @Synchronized fun clear() {
        if (entries.isNotEmpty()) {
            entries.clear()
            persist?.invoke(entries)
            counters?.storageWrites?.incrementAndGet()
        }
    }

    @Synchronized fun snapshot(): Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(entries))

    private fun validate(key: String, value: String) {
        require(key.length <= limits.maxStorageKeyChars) { "Storage key exceeds ${limits.maxStorageKeyChars} characters" }
        require(value.length <= limits.maxStorageValueChars) { "Storage value exceeds ${limits.maxStorageValueChars} characters" }
    }
}

internal class BrowserStorageManager(
    private val fileSystem: FileSystem,
    private val limits: BrowserApiLimits,
    private val counters: BrowserApiCounters
) {
    private val local = ConcurrentHashMap<String, BrowserStorageArea>()
    private val session = ConcurrentHashMap<String, BrowserStorageArea>()

    fun localStorage(origin: String): BrowserStorageArea = local.computeIfAbsent(origin) {
        val path = storagePath("local", origin)
        BrowserStorageArea(limits, load(path), { persist(path, it) }, counters)
    }

    fun sessionStorage(origin: String): BrowserStorageArea = session.computeIfAbsent(origin) {
        BrowserStorageArea(limits, counters = counters)
    }

    fun clearSession(origin: String? = null) {
        if (origin == null) session.clear() else session.remove(origin)
    }

    fun clearAll() {
        local.values.forEach(BrowserStorageArea::clear)
        local.clear()
        session.clear()
        fileSystem.delete(VirtualPath.of("/browser/storage"))
    }

    private fun storagePath(kind: String, origin: String): VirtualPath {
        val digest = MessageDigest.getInstance("SHA-256").digest(origin.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return VirtualPath.of("/browser/storage/$kind/$digest.db")
    }

    private fun load(path: VirtualPath): Map<String, String> = runCatching {
        val raw = String(fileSystem.read(path), Charsets.UTF_8)
        val decoder = Base64.getUrlDecoder()
        raw.lineSequence().filter(String::isNotEmpty).associate { line ->
            val separator = line.indexOf('\t')
            require(separator >= 0) { "Malformed storage line" }
            val key = String(decoder.decode(line.substring(0, separator)), Charsets.UTF_8)
            val value = String(decoder.decode(line.substring(separator + 1)), Charsets.UTF_8)
            key to value
        }
    }.getOrDefault(emptyMap())

    private fun persist(path: VirtualPath, values: Map<String, String>) {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val text = values.entries.joinToString("\n") { (key, value) ->
            encoder.encodeToString(key.toByteArray(Charsets.UTF_8)) + "\t" + encoder.encodeToString(value.toByteArray(Charsets.UTF_8))
        }
        fileSystem.write(path, text.toByteArray(Charsets.UTF_8))
    }
}
