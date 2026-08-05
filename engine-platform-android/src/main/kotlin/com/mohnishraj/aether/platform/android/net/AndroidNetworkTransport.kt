package com.mohnishraj.aether.platform.android.net

import com.mohnishraj.aether.core.net.model.NetworkFailure
import com.mohnishraj.aether.core.net.model.NetworkFailureKind
import com.mohnishraj.aether.core.net.model.NetworkHeaders
import com.mohnishraj.aether.core.net.model.NetworkRequest
import com.mohnishraj.aether.core.net.model.NetworkResult
import com.mohnishraj.aether.core.net.transport.NetworkTransport
import com.mohnishraj.aether.core.net.transport.TransportExchange
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import javax.security.auth.x500.X500Principal

class AndroidNetworkTransport : NetworkTransport {
    override fun open(request: NetworkRequest): NetworkResult<TransportExchange> {
        var connection: HttpURLConnection? = null
        return try {
            connection = request.url.toUri().toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.doInput = true
            connection.connectTimeout = request.connectTimeoutMillis
            connection.readTimeout = request.readTimeoutMillis
            connection.requestMethod = request.method.name
            request.headers.forEach { (name, value) -> connection.addRequestProperty(name, value) }
            request.body?.let { body ->
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { output ->
                    output.write(body)
                    output.flush()
                }
            }

            val statusCode = connection.responseCode
            val headers = NetworkHeaders.builder().apply {
                connection.headerFields.forEach { (name, values) ->
                    if (name != null) values.orEmpty().forEach { value -> add(name, value) }
                }
            }.build()
            val protocol = connection.headerFields[null]?.firstOrNull()?.substringBefore(' ') ?: "HTTP/1.1"
            val stream = responseStream(connection, statusCode)
            val details = linkedMapOf<String, String>(
                "transport" to "HttpURLConnection",
                "urlConnectionClass" to connection::class.java.name
            )
            if (connection is HttpsURLConnection) {
                runCatching { details["cipherSuite"] = connection.cipherSuite }
                runCatching {
                    val certificate = connection.serverCertificates.firstOrNull()
                    val principal = (certificate as? java.security.cert.X509Certificate)?.subjectX500Principal
                    if (principal != null) details["peerPrincipal"] = principal.getName(X500Principal.RFC2253)
                }
            }
            NetworkResult.Success(
                AndroidTransportExchange(
                    connection,
                    stream,
                    statusCode,
                    connection.responseMessage.orEmpty(),
                    headers,
                    protocol,
                    details
                )
            )
        } catch (error: Exception) {
            connection?.disconnect()
            NetworkResult.Failure(
                NetworkFailure(
                    kindFor(error),
                    error.message ?: error::class.java.simpleName,
                    request.url,
                    error::class.java.name
                )
            )
        }
    }

    private fun responseStream(connection: HttpURLConnection, statusCode: Int): InputStream {
        if (connection.requestMethod == "HEAD" || statusCode in 100..199 || statusCode in setOf(204, 304)) {
            return ByteArrayInputStream(byteArrayOf())
        }
        val selected = if (statusCode >= 400) {
            connection.errorStream ?: runCatching { connection.inputStream }.getOrNull()
        } else {
            runCatching { connection.inputStream }.getOrNull()
        }
        return selected ?: ByteArrayInputStream(byteArrayOf())
    }

    private fun kindFor(error: Throwable): NetworkFailureKind = when (error) {
        is SocketTimeoutException -> NetworkFailureKind.TIMEOUT
        is UnknownHostException -> NetworkFailureKind.DNS
        is ConnectException -> NetworkFailureKind.CONNECT
        is SSLException -> NetworkFailureKind.TLS
        is java.net.ProtocolException, is IllegalArgumentException -> NetworkFailureKind.PROTOCOL
        is java.io.IOException -> NetworkFailureKind.IO
        else -> NetworkFailureKind.UNKNOWN
    }

    private class AndroidTransportExchange(
        private val connection: HttpURLConnection,
        private val stream: InputStream,
        override val statusCode: Int,
        override val reasonPhrase: String,
        override val headers: NetworkHeaders,
        override val protocol: String,
        override val details: Map<String, String>
    ) : TransportExchange {
        override val contentLength: Long get() = connection.contentLengthLong

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = stream.read(buffer, offset, length)

        override fun close() {
            runCatching { stream.close() }
            connection.disconnect()
        }
    }
}
