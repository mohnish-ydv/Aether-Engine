package com.mohnishraj.aether.core.net

import com.mohnishraj.aether.core.net.model.NetworkHeaders
import com.mohnishraj.aether.core.net.model.NetworkRequest
import com.mohnishraj.aether.core.net.model.NetworkResult
import com.mohnishraj.aether.core.net.transport.ByteArrayExchange
import com.mohnishraj.aether.core.net.transport.NetworkTransport
import com.mohnishraj.aether.core.net.transport.TransportExchange
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

internal class ScriptedTransport(private val handler: (NetworkRequest) -> TransportExchange) : NetworkTransport {
    val requests = mutableListOf<NetworkRequest>()
    override fun open(request: NetworkRequest): NetworkResult<TransportExchange> {
        requests += request
        return NetworkResult.Success(handler(request))
    }
}

internal fun response(
    code: Int = 200,
    headers: NetworkHeaders = NetworkHeaders.EMPTY,
    body: ByteArray = byteArrayOf(),
    reason: String = if (code == 200) "OK" else ""
) = ByteArrayExchange(code, reason, headers, payload = body)

internal fun gzip(value: String): ByteArray = ByteArrayOutputStream().use { output ->
    GZIPOutputStream(output).use { it.write(value.toByteArray()) }
    output.toByteArray()
}

internal fun <T> NetworkResult<T>.valueOrThrow(): T = when (this) {
    is NetworkResult.Success -> value
    is NetworkResult.Failure -> error("${error.kind}: ${error.message}")
}
