package com.mohnishraj.aether.core.net.model

import java.util.Locale

class NetworkHeaders private constructor(private val entries: List<Pair<String, String>>) : Iterable<Pair<String, String>> {
    val size: Int get() = entries.size
    fun values(name: String): List<String> = entries.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }
    operator fun get(name: String): String? = values(name).lastOrNull()
    fun contains(name: String): Boolean = entries.any { it.first.equals(name, ignoreCase = true) }
    fun names(): Set<String> = entries.mapTo(linkedSetOf()) { it.first.lowercase(Locale.ROOT) }
    fun toMultimap(): Map<String, List<String>> = names().associateWith(::values)
    fun newBuilder(): Builder = Builder().also { builder -> entries.forEach { (name, value) -> builder.add(name, value) } }
    override fun iterator(): Iterator<Pair<String, String>> = entries.iterator()
    override fun toString(): String = entries.joinToString("\n") { "${it.first}: ${it.second}" }

    class Builder {
        private val entries = mutableListOf<Pair<String, String>>()
        fun add(name: String, value: String): Builder = apply {
            require(entries.size < MAX_HEADER_ENTRIES) { "Too many HTTP header fields" }
            validateName(name)
            validateValue(value)
            entries += name.trim() to value.trim()
        }
        fun set(name: String, value: String): Builder = apply { removeAll(name); add(name, value) }
        fun removeAll(name: String): Builder = apply { entries.removeAll { it.first.equals(name, ignoreCase = true) } }
        fun build(): NetworkHeaders = NetworkHeaders(entries.toList())

        private fun validateName(name: String) {
            require(name.isNotBlank()) { "Header name is blank" }
            require(name.length <= MAX_HEADER_NAME_CHARS) { "Header name is too long" }
            val separators = "()<>@,;:\\\"/[]?={} \t"
            require(name.all { it.code in 33..126 && it !in separators }) { "Invalid header name: $name" }
        }
        private fun validateValue(value: String) {
            require(value.length <= MAX_HEADER_VALUE_CHARS) { "Header value is too long" }
            require(value.none { it == '\r' || it == '\n' || it.code == 0 }) { "Invalid header value" }
        }
    }

    companion object {
        private const val MAX_HEADER_ENTRIES = 512
        private const val MAX_HEADER_NAME_CHARS = 256
        private const val MAX_HEADER_VALUE_CHARS = 16_384
        val EMPTY = Builder().build()
        fun builder(): Builder = Builder()
        fun of(vararg pairs: Pair<String, String>): NetworkHeaders = Builder().also { b -> pairs.forEach { b.add(it.first, it.second) } }.build()
    }
}
