package com.mohnishraj.aether.core.net.transport

import com.mohnishraj.aether.core.net.model.NetworkHeaders
import com.mohnishraj.aether.core.net.model.NetworkRequest
import com.mohnishraj.aether.core.net.model.NetworkResult

interface TransportExchange : AutoCloseable {
    val statusCode: Int
    val reasonPhrase: String
    val headers: NetworkHeaders
    val protocol: String
    val contentLength: Long
    val details: Map<String, String>
    fun read(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size): Int
    override fun close()
}

fun interface NetworkTransport { fun open(request: NetworkRequest): NetworkResult<TransportExchange> }
fun interface TransferObserver {
    fun onProgress(bytesTransferred: Long, totalBytes: Long?)
    companion object { val NONE = TransferObserver { _, _ -> } }
}
class CancellationToken {
    @Volatile private var cancelled = false
    fun cancel() { cancelled = true }
    fun isCancelled(): Boolean = cancelled
}
