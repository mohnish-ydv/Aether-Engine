package com.mohnishraj.aether.core

sealed interface EngineResult<out T> {
    data class Success<T>(val value: T) : EngineResult<T>
    data class Failure(val error: EngineError) : EngineResult<Nothing>

    fun <R> map(transform: (T) -> R): EngineResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    fun getOrNull(): T? = when (this) {
        is Success -> value
        is Failure -> null
    }
}

data class EngineError(
    val code: String,
    val message: String,
    val causeType: String? = null
)
