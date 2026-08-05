package com.mohnishraj.aether.core.text

import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Small immutable UTF-8 value type used at engine boundaries.
 * Keeping bytes explicit prevents accidental platform-default encodings.
 */
class Utf8String private constructor(private val bytes: ByteArray) : Comparable<Utf8String> {
    val byteLength: Int get() = bytes.size
    val text: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        bytes.toString(StandardCharsets.UTF_8)
    }

    fun copyBytes(): ByteArray = bytes.copyOf()
    fun isBlank(): Boolean = text.isBlank()
    fun normalizedWhitespace(): Utf8String = of(text.trim().replace(Regex("\\s+"), " "))
    fun lowerAscii(): Utf8String = of(buildString(text.length) {
        text.forEach { ch -> append(if (ch in 'A'..'Z') ch + 32 else ch) }
    })
    fun startsWith(prefix: Utf8String): Boolean = text.startsWith(prefix.text)
    fun contains(fragment: Utf8String, ignoreCase: Boolean = false): Boolean =
        text.contains(fragment.text, ignoreCase)

    override fun compareTo(other: Utf8String): Int = text.compareTo(other.text)
    override fun equals(other: Any?): Boolean = other is Utf8String && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
    override fun toString(): String = text

    companion object {
        val EMPTY: Utf8String = Utf8String(byteArrayOf())

        fun of(value: String): Utf8String = Utf8String(value.toByteArray(StandardCharsets.UTF_8))
        fun fromBytes(value: ByteArray): Utf8String = Utf8String(value.copyOf())

        fun escapeJson(value: String): String = buildString(value.length + 16) {
            value.forEach { ch ->
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (ch.code < 0x20) append("\\u%04x".format(Locale.ROOT, ch.code)) else append(ch)
                }
            }
        }
    }
}
