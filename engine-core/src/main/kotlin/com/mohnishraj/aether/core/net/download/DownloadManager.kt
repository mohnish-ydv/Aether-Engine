package com.mohnishraj.aether.core.net.download

import com.mohnishraj.aether.core.fs.AtomicFileWriter
import com.mohnishraj.aether.core.fs.FileSystem
import com.mohnishraj.aether.core.fs.StreamingFileSystem
import com.mohnishraj.aether.core.fs.VirtualPath
import com.mohnishraj.aether.core.net.NetworkClient
import com.mohnishraj.aether.core.net.model.CachePolicy
import com.mohnishraj.aether.core.net.model.NetworkFailure
import com.mohnishraj.aether.core.net.model.NetworkFailureKind
import com.mohnishraj.aether.core.net.model.NetworkRequest
import com.mohnishraj.aether.core.net.model.NetworkResponse
import com.mohnishraj.aether.core.net.model.NetworkResult
import com.mohnishraj.aether.core.net.transport.CancellationToken
import com.mohnishraj.aether.core.net.transport.TransferObserver

data class DownloadResult(val path: VirtualPath, val response: NetworkResponse, val bytesWritten: Long)

class DownloadManager(private val client: NetworkClient, private val fileSystem: FileSystem) {
    fun download(
        request: NetworkRequest,
        destination: VirtualPath,
        observer: TransferObserver = TransferObserver.NONE,
        cancellation: CancellationToken = CancellationToken()
    ): NetworkResult<DownloadResult> {
        val networkRequest = request.copy(cachePolicy = CachePolicy.NETWORK_ONLY)
        if (fileSystem is StreamingFileSystem) return streamToAtomicFile(networkRequest, destination, observer, cancellation)

        return when (val result = client.execute(networkRequest, observer, cancellation)) {
            is NetworkResult.Success -> {
                if (!result.value.isSuccessful) {
                    NetworkResult.Failure(NetworkFailure(NetworkFailureKind.PROTOCOL, "Download failed with HTTP ${result.value.statusCode}", result.value.finalUrl))
                } else {
                    try {
                        fileSystem.write(destination, result.value.body)
                        NetworkResult.Success(DownloadResult(destination, result.value, result.value.body.size.toLong()))
                    } catch (error: Exception) {
                        writeFailure(request, error)
                    }
                }
            }
            is NetworkResult.Failure -> result
        }
    }

    private fun streamToAtomicFile(
        request: NetworkRequest,
        destination: VirtualPath,
        observer: TransferObserver,
        cancellation: CancellationToken
    ): NetworkResult<DownloadResult> {
        var writer: AtomicFileWriter? = null
        return try {
            writer = (fileSystem as StreamingFileSystem).openAtomicWriter(destination)
            when (val result = client.stream(request, writer.output, observer, cancellation)) {
                is NetworkResult.Success -> {
                    if (!result.value.isSuccessful) {
                        writer.abort()
                        NetworkResult.Failure(NetworkFailure(NetworkFailureKind.PROTOCOL, "Download failed with HTTP ${result.value.statusCode}", result.value.finalUrl))
                    } else {
                        writer.commit()
                        NetworkResult.Success(DownloadResult(destination, result.value, result.value.bytesReceived))
                    }
                }
                is NetworkResult.Failure -> {
                    writer.abort()
                    result
                }
            }
        } catch (error: Exception) {
            writer?.abort()
            writeFailure(request, error)
        }
    }

    private fun writeFailure(request: NetworkRequest, error: Exception): NetworkResult.Failure = NetworkResult.Failure(
        NetworkFailure(NetworkFailureKind.IO, error.message ?: "Download write failed", request.url, error::class.java.name)
    )
}
