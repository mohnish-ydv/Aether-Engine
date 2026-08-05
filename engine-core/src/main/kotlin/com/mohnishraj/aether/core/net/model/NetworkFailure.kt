package com.mohnishraj.aether.core.net.model

enum class NetworkFailureKind { INVALID_REQUEST, DNS, CONNECT, TLS, TIMEOUT, PROTOCOL, REDIRECT, RESPONSE_TOO_LARGE, CANCELLED, CACHE_MISS, IO, UNKNOWN }

data class NetworkFailure(val kind: NetworkFailureKind, val message: String, val url: AetherUrl? = null, val causeType: String? = null)

sealed interface NetworkResult<out T> {
    data class Success<T>(val value: T) : NetworkResult<T>
    data class Failure(val error: NetworkFailure) : NetworkResult<Nothing>
    fun getOrNull(): T? = when (this) { is Success -> value; is Failure -> null }
}
