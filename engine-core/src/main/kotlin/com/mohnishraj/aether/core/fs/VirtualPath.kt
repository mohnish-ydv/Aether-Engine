package com.mohnishraj.aether.core.fs

import java.util.ArrayDeque

@JvmInline
value class VirtualPath private constructor(val value: String) {
    val name: String get() = if (value == "/") "/" else value.substringAfterLast('/')
    val parent: VirtualPath get() = when {
        value == "/" -> this
        value.substringBeforeLast('/', "").isEmpty() -> ROOT
        else -> of(value.substringBeforeLast('/'))
    }

    fun resolve(child: String): VirtualPath = of(if (value == "/") "/$child" else "$value/$child")
    fun isWithin(parent: VirtualPath): Boolean = value == parent.value || value.startsWith(parent.value.trimEnd('/') + "/")
    override fun toString(): String = value

    companion object {
        val ROOT = VirtualPath("/")

        fun of(raw: String): VirtualPath {
            require(raw.indexOf('\u0000') < 0) { "NUL is not allowed in paths" }
            val parts = raw.replace('\\', '/').split('/')
            val clean = ArrayDeque<String>()
            for (part in parts) {
                when (part) {
                    "", "." -> Unit
                    ".." -> require(clean.isNotEmpty()) { "Path escapes virtual root: $raw" }.also { clean.removeLast() }
                    else -> clean.addLast(part)
                }
            }
            val normalized = "/" + clean.joinToString("/")
            return if (normalized == "/") ROOT else VirtualPath(normalized)
        }
    }
}
