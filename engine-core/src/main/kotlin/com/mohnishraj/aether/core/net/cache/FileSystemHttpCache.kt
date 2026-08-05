package com.mohnishraj.aether.core.net.cache

import com.mohnishraj.aether.core.fs.FileSystem
import com.mohnishraj.aether.core.fs.VirtualPath
import com.mohnishraj.aether.core.net.model.AetherUrl
import com.mohnishraj.aether.core.net.model.NetworkHeaders
import com.mohnishraj.aether.core.net.model.NetworkRequest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Locale

class FileSystemHttpCache(
    private val fileSystem: FileSystem,
    private val root: VirtualPath = VirtualPath.of("/network/cache"),
    private val maxBytes: Long = 32L * 1024L * 1024L,
    private val maxEntries: Int = 128
) : HttpCache {
    init {
        require(maxBytes in 1..Int.MAX_VALUE.toLong()) { "maxBytes out of range" }
        require(maxEntries in 1..4_096) { "maxEntries out of range" }
    }

    private var hits = 0L
    private var misses = 0L

    @Synchronized override fun get(request: NetworkRequest): CacheEntry? {
        val path = pathFor(request.url)
        if (!fileSystem.exists(path)) { misses++; return null }
        val entry = runCatching { decode(fileSystem.read(path)) }.getOrElse { fileSystem.delete(path); null }
        return if (entry != null && entry.matches(request)) { hits++; entry } else { misses++; null }
    }
    @Synchronized override fun put(entry: CacheEntry) {
        val bytes = encode(entry)
        if (bytes.size > maxBytes) return
        fileSystem.write(pathFor(entry.url), bytes)
        trim()
    }
    @Synchronized override fun remove(url: AetherUrl) { fileSystem.delete(pathFor(url)) }
    @Synchronized override fun clear() { fileSystem.delete(root) }
    @Synchronized override fun stats(): CacheStats {
        val entries = fileSystem.list(root).filter { !it.isDirectory }
        return CacheStats(entries.size, entries.sumOf { it.size }, hits, misses)
    }

    private fun trim() {
        val entries = fileSystem.list(root).filter { !it.isDirectory }.sortedBy { it.modifiedAtMillis }.toMutableList()
        var total = entries.sumOf { it.size }
        while (entries.size > maxEntries || total > maxBytes) {
            if (entries.isEmpty()) break
            val oldest = entries.removeAt(0)
            total -= oldest.size
            fileSystem.delete(oldest.path)
        }
    }

    private fun pathFor(url: AetherUrl): VirtualPath = root.resolve(sha256(url.withoutFragment().toString()) + ".bin")
    private fun encode(entry: CacheEntry): ByteArray = ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { output ->
            output.writeInt(MAGIC); output.writeInt(VERSION); output.writeUTF(entry.url.toString())
            output.writeInt(entry.statusCode); output.writeUTF(entry.reasonPhrase.take(4096)); output.writeUTF(entry.protocol.take(128))
            output.writeLong(entry.storedAtMillis); output.writeLong(entry.expiresAtMillis)
            output.writeInt(entry.headers.size)
            entry.headers.forEach { (name, value) -> output.writeUTF(name); output.writeUTF(value) }
            output.writeInt(entry.varyNames.size)
            entry.varyNames.sorted().forEach { name -> output.writeUTF(name); output.writeUTF(entry.varyRequestHeaders[name].orEmpty()) }
            output.writeInt(entry.body.size); output.write(entry.body)
        }
        buffer.toByteArray()
    }

    private fun decode(bytes: ByteArray): CacheEntry? = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readInt() == MAGIC && input.readInt() == VERSION)
        val url = AetherUrl.parse(input.readUTF())
        val status = input.readInt(); val reason = input.readUTF(); val protocol = input.readUTF()
        val stored = input.readLong(); val expires = input.readLong()
        val headerCount = input.readInt().also { require(it in 0..1024) }
        val headers = NetworkHeaders.builder().apply { repeat(headerCount) { add(input.readUTF(), input.readUTF()) } }.build()
        val varyCount = input.readInt().also { require(it in 0..128) }
        val vary = linkedMapOf<String, String>()
        repeat(varyCount) { vary[input.readUTF()] = input.readUTF() }
        val bodySize = input.readInt().also { require(it in 0..maxBytes.toInt()) }
        val body = ByteArray(bodySize); input.readFully(body); require(input.available() == 0)
        CacheEntry(url, status, reason, headers, body, protocol, stored, expires, vary.keys, vary)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    companion object { private const val MAGIC = 0x41455448; private const val VERSION = 2 }
}
