package com.mohnishraj.aether.core.browser.features

import com.mohnishraj.aether.core.fs.FileSystem
import com.mohnishraj.aether.core.fs.VirtualPath
import com.mohnishraj.aether.core.net.NetworkRuntime
import com.mohnishraj.aether.core.net.model.AetherUrl
import com.mohnishraj.aether.core.net.model.CachePolicy
import com.mohnishraj.aether.core.net.model.NetworkHeaders
import com.mohnishraj.aether.core.net.model.NetworkRequest
import com.mohnishraj.aether.core.net.model.NetworkResult
import com.mohnishraj.aether.core.net.transport.CancellationToken
import com.mohnishraj.aether.core.net.transport.TransferObserver
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class DownloadState { QUEUED, RUNNING, PAUSED, VERIFYING, COMPLETED, CANCELLED, FAILED }

data class ManagedDownload(
    val id: Long,
    val url: String,
    val destination: VirtualPath,
    val state: DownloadState,
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val progressPercent: Int?,
    val expectedSha256: String?,
    val actualSha256: String?,
    val integrityVerified: Boolean?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val errorMessage: String? = null
)

class ManagedDownloadController(
    private val network: NetworkRuntime,
    private val fileSystem: FileSystem,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val metadataPath: VirtualPath = VirtualPath.of("/browser/downloads.db")
) {
    private val nextId = AtomicLong(0L)
    private val downloads = linkedMapOf<Long, ManagedDownload>()
    private val cancellations = ConcurrentHashMap<Long, CancellationToken>()

    init { load() }

    @Synchronized fun enqueue(url: String, destination: VirtualPath, expectedSha256: String? = null): ManagedDownload {
        val parsed = AetherUrl.parse(url)
        require(parsed.scheme in setOf("http", "https")) { "Downloads require HTTP or HTTPS" }
        require(destination != VirtualPath.ROOT) { "Download destination cannot be root" }
        val checksum = expectedSha256?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty)
        require(checksum == null || checksum.matches(Regex("[0-9a-f]{64}"))) { "SHA-256 must contain 64 hexadecimal characters" }
        val now = clockMillis()
        val item = ManagedDownload(nextId.incrementAndGet(), parsed.toString(), destination, DownloadState.QUEUED, 0L, null, 0, checksum, null, null, now, now)
        downloads[item.id] = item
        persistLocked()
        return item
    }

    @Synchronized fun snapshots(): List<ManagedDownload> = downloads.values.sortedByDescending(ManagedDownload::createdAtMillis)
    @Synchronized fun snapshot(id: Long): ManagedDownload? = downloads[id]

    fun execute(id: Long, onUpdate: (ManagedDownload) -> Unit = {}) : ManagedDownload {
        val initial = synchronized(this) {
            val item = downloads[id] ?: error("Unknown download $id")
            require(item.state in setOf(DownloadState.QUEUED, DownloadState.PAUSED, DownloadState.FAILED)) { "Download cannot start from ${item.state}" }
            updateLocked(item.copy(state = DownloadState.RUNNING, errorMessage = null, updatedAtMillis = clockMillis())).also(onUpdate)
        }
        val part = partPath(initial.destination)
        val existing = if (fileSystem.exists(part)) fileSystem.read(part) else ByteArray(0)
        val token = CancellationToken()
        cancellations[id] = token
        val headers = NetworkHeaders.builder().apply {
            if (existing.isNotEmpty()) set("Range", "bytes=${existing.size}-")
        }.build()
        val request = NetworkRequest(
            url = AetherUrl.parse(initial.url),
            headers = headers,
            maxResponseBytes = 512L * 1024L * 1024L,
            cachePolicy = CachePolicy.NETWORK_ONLY,
            tag = "download-$id"
        )
        val output = CheckpointOutput(fileSystem, part, existing)
        val observer = TransferObserver { transferred, total ->
            val combined = existing.size.toLong() + transferred
            val combinedTotal = total?.let { it + existing.size }
            val progress = combinedTotal?.takeIf { it > 0L }?.let { ((combined * 100L) / it).toInt().coerceIn(0, 100) }
            synchronized(this) {
                val current = downloads[id] ?: return@synchronized
                updateLocked(current.copy(bytesDownloaded = combined, totalBytes = combinedTotal, progressPercent = progress, updatedAtMillis = clockMillis())).also(onUpdate)
            }
        }
        val result = try {
            network.client.stream(request, output, observer, token)
        } finally {
            val state = synchronized(this) { downloads[id]?.state }
            if (state == DownloadState.CANCELLED) fileSystem.delete(part) else output.checkpoint()
            cancellations.remove(id)
        }
        return when (result) {
            is NetworkResult.Success -> {
                val response = result.value
                if (!response.isSuccessful) fail(id, "HTTP ${response.statusCode}", onUpdate)
                else {
                    val completeBytes = if (existing.isNotEmpty() && response.statusCode == 206) output.combinedBytes() else output.newBytes()
                    fileSystem.write(initial.destination, completeBytes)
                    fileSystem.delete(part)
                    verifyAndComplete(id, completeBytes, onUpdate)
                }
            }
            is NetworkResult.Failure -> {
                val current = synchronized(this) { downloads[id] ?: initial }
                if (token.isCancelled() && current.state == DownloadState.PAUSED) current
                else if (token.isCancelled() && current.state == DownloadState.CANCELLED) current
                else fail(id, result.error.message, onUpdate)
            }
        }
    }

    @Synchronized fun pause(id: Long): ManagedDownload {
        val item = downloads[id] ?: error("Unknown download $id")
        require(item.state == DownloadState.RUNNING) { "Only running downloads can be paused" }
        val updated = updateLocked(item.copy(state = DownloadState.PAUSED, updatedAtMillis = clockMillis()))
        cancellations[id]?.cancel()
        return updated
    }

    @Synchronized fun cancel(id: Long): ManagedDownload {
        val item = downloads[id] ?: error("Unknown download $id")
        if (item.state == DownloadState.COMPLETED) return item
        val updated = updateLocked(item.copy(state = DownloadState.CANCELLED, updatedAtMillis = clockMillis()))
        cancellations[id]?.cancel()
        fileSystem.delete(partPath(item.destination))
        return updated
    }

    @Synchronized fun retry(id: Long, restart: Boolean = false): ManagedDownload {
        val item = downloads[id] ?: error("Unknown download $id")
        require(item.state in setOf(DownloadState.FAILED, DownloadState.CANCELLED, DownloadState.PAUSED)) { "Download cannot be retried from ${item.state}" }
        if (restart) fileSystem.delete(partPath(item.destination))
        return updateLocked(item.copy(state = DownloadState.QUEUED, errorMessage = null, actualSha256 = null, integrityVerified = null, updatedAtMillis = clockMillis()))
    }

    private fun verifyAndComplete(id: Long, bytes: ByteArray, onUpdate: (ManagedDownload) -> Unit): ManagedDownload {
        synchronized(this) {
            val current = downloads[id] ?: error("Unknown download $id")
            updateLocked(current.copy(state = DownloadState.VERIFYING, bytesDownloaded = bytes.size.toLong(), totalBytes = bytes.size.toLong(), progressPercent = 100, updatedAtMillis = clockMillis())).also(onUpdate)
        }
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return synchronized(this) {
            val current = downloads[id] ?: error("Unknown download $id")
            val verified = current.expectedSha256?.let { it == actual } ?: true
            val final = current.copy(
                state = if (verified) DownloadState.COMPLETED else DownloadState.FAILED,
                actualSha256 = actual,
                integrityVerified = verified,
                errorMessage = if (verified) null else "SHA-256 integrity verification failed",
                updatedAtMillis = clockMillis()
            )
            updateLocked(final).also(onUpdate)
        }
    }

    private fun fail(id: Long, message: String, onUpdate: (ManagedDownload) -> Unit): ManagedDownload = synchronized(this) {
        val current = downloads[id] ?: error("Unknown download $id")
        updateLocked(current.copy(state = DownloadState.FAILED, errorMessage = message.take(512), updatedAtMillis = clockMillis())).also(onUpdate)
    }

    @Synchronized private fun updateLocked(value: ManagedDownload): ManagedDownload {
        downloads[value.id] = value
        persistLocked()
        return value
    }

    @Synchronized private fun load() {
        if (!fileSystem.exists(metadataPath)) return
        runCatching {
            fileSystem.read(metadataPath).toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).forEach { line ->
                val parts = line.split('|')
                if (parts.size != 15 || parts[0] != "D") return@forEach
                val storedState = DownloadState.valueOf(parts[4])
                val safeState = if (storedState in setOf(DownloadState.RUNNING, DownloadState.VERIFYING)) DownloadState.PAUSED else storedState
                val item = ManagedDownload(
                    id = parts[1].toLong(),
                    url = decode(parts[2]),
                    destination = VirtualPath.of(decode(parts[3])),
                    state = safeState,
                    bytesDownloaded = parts[5].toLong(),
                    totalBytes = parts[6].takeIf(String::isNotEmpty)?.toLong(),
                    progressPercent = parts[7].takeIf(String::isNotEmpty)?.toInt(),
                    expectedSha256 = parts[8].takeIf(String::isNotEmpty),
                    actualSha256 = parts[9].takeIf(String::isNotEmpty),
                    integrityVerified = parts[10].takeIf(String::isNotEmpty)?.toBooleanStrict(),
                    createdAtMillis = parts[11].toLong(),
                    updatedAtMillis = parts[12].toLong(),
                    errorMessage = parts[13].takeIf(String::isNotEmpty)?.let(::decode)
                )
                downloads[item.id] = item
                nextId.set(maxOf(nextId.get(), item.id))
            }
        }.onFailure {
            downloads.clear()
            nextId.set(0L)
        }
    }

    @Synchronized private fun persistLocked() {
        val encoded = downloads.values.joinToString("\n", postfix = if (downloads.isEmpty()) "" else "\n") { item ->
            listOf(
                "D", item.id.toString(), encode(item.url), encode(item.destination.value), item.state.name,
                item.bytesDownloaded.toString(), item.totalBytes?.toString().orEmpty(), item.progressPercent?.toString().orEmpty(),
                item.expectedSha256.orEmpty(), item.actualSha256.orEmpty(), item.integrityVerified?.toString().orEmpty(),
                item.createdAtMillis.toString(), item.updatedAtMillis.toString(), item.errorMessage?.let(::encode).orEmpty(), "1"
            ).joinToString("|")
        }
        fileSystem.write(metadataPath, encoded.toByteArray(Charsets.UTF_8))
    }

    private fun partPath(destination: VirtualPath): VirtualPath = VirtualPath.of(destination.value + ".part")

    private class CheckpointOutput(
        private val fileSystem: FileSystem,
        private val path: VirtualPath,
        private val existing: ByteArray
    ) : OutputStream() {
        private val fresh = ByteArrayOutputStream()
        private var sinceCheckpoint = 0

        override fun write(value: Int) {
            fresh.write(value)
            sinceCheckpoint++
            maybeCheckpoint()
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            fresh.write(buffer, offset, length)
            sinceCheckpoint += length
            maybeCheckpoint()
        }

        fun newBytes(): ByteArray = fresh.toByteArray()
        fun combinedBytes(): ByteArray = existing + fresh.toByteArray()
        fun checkpoint() {
            fileSystem.write(path, combinedBytes())
            sinceCheckpoint = 0
        }

        private fun maybeCheckpoint() {
            if (sinceCheckpoint >= CHECKPOINT_BYTES) checkpoint()
        }

        companion object { private const val CHECKPOINT_BYTES = 256 * 1024 }
    }

    private companion object {
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()
        private fun encode(value: String): String = encoder.encodeToString(value.toByteArray(Charsets.UTF_8))
        private fun decode(value: String): String = decoder.decode(value).toString(Charsets.UTF_8)
    }
}
