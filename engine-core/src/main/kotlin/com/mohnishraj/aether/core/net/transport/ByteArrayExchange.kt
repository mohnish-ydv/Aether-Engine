package com.mohnishraj.aether.core.net.transport

import com.mohnishraj.aether.core.net.model.NetworkHeaders

class ByteArrayExchange(
    override val statusCode: Int,
    override val reasonPhrase: String = "",
    override val headers: NetworkHeaders = NetworkHeaders.EMPTY,
    override val protocol: String = "HTTP/1.1",
    private val payload: ByteArray = byteArrayOf(),
    override val details: Map<String, String> = emptyMap()
) : TransportExchange {
    private var position = 0
    private var closed = false
    override val contentLength: Long get() = payload.size.toLong()
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        check(!closed) { "Exchange is closed" }
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size)
        if (position >= payload.size) return -1
        val count = minOf(length, payload.size - position)
        payload.copyInto(buffer, offset, position, position + count)
        position += count
        return count
    }
    override fun close() { closed = true }
}
