package com.mohnishraj.aether.core.security

import com.mohnishraj.aether.core.net.model.AetherUrl
import java.util.Collections
import java.util.Locale

class ContentSecurityPolicy private constructor(
    private val directives: Map<String, List<String>>
) {
    val upgradeInsecureRequests: Boolean get() = directives.containsKey("upgrade-insecure-requests")
    val blockAllMixedContent: Boolean get() = directives.containsKey("block-all-mixed-content")
    val sandboxTokens: List<String>? get() = directives["sandbox"]

    fun directive(name: String): List<String>? = directives[name.lowercase(Locale.ROOT)]?.toList()
    fun snapshot(): Map<String, List<String>> = Collections.unmodifiableMap(directives.mapValues { it.value.toList() })

    fun allowsExternal(
        type: SecurityResourceType,
        target: AetherUrl,
        documentOrigin: AetherOrigin
    ): SecurityDecision {
        val directiveName = directiveFor(type)
        val values = sourceList(directiveName) ?: return SecurityDecision.allow("No CSP source restriction")
        if (values.any { it.equals("'none'", ignoreCase = true) }) {
            return SecurityDecision.block("CSP $directiveName contains 'none'", directiveName)
        }
        val matched = values.any { sourceMatches(it, target, documentOrigin) }
        return if (matched) SecurityDecision.allow("CSP source matched", target.toString(), directiveName)
        else SecurityDecision.block("CSP $directiveName blocked ${target.origin}", directiveName)
    }

    fun allowsInline(type: SecurityResourceType, nonce: String? = null): SecurityDecision {
        require(type == SecurityResourceType.SCRIPT || type == SecurityResourceType.STYLE) {
            "Inline checks are only valid for script and style"
        }
        val directiveName = directiveFor(type)
        val values = sourceList(directiveName) ?: return SecurityDecision.allow("No CSP inline restriction")
        if (values.any { it.equals("'none'", ignoreCase = true) }) {
            return SecurityDecision.block("CSP $directiveName contains 'none'", directiveName)
        }
        if (values.any { it.equals("'unsafe-inline'", ignoreCase = true) }) {
            return SecurityDecision.allow("CSP allows inline content", directive = directiveName)
        }
        if (nonce != null && values.any { it.equals("'nonce-$nonce'", ignoreCase = false) }) {
            return SecurityDecision.allow("CSP nonce matched", directive = directiveName)
        }
        return SecurityDecision.block("CSP $directiveName blocks inline content", directiveName)
    }

    fun allowsFormAction(target: AetherUrl, documentOrigin: AetherOrigin): SecurityDecision {
        val values = directives["form-action"] ?: return SecurityDecision.allow("No form-action restriction")
        if (values.any { it.equals("'none'", ignoreCase = true) }) {
            return SecurityDecision.block("CSP form-action contains 'none'", "form-action")
        }
        return if (values.any { sourceMatches(it, target, documentOrigin) }) {
            SecurityDecision.allow("CSP form-action matched", target.toString(), "form-action")
        } else {
            SecurityDecision.block("CSP form-action blocked ${target.origin}", "form-action")
        }
    }

    private fun sourceList(name: String): List<String>? = directives[name] ?: directives["default-src"]

    private fun sourceMatches(rawSource: String, target: AetherUrl, documentOrigin: AetherOrigin): Boolean {
        val source = rawSource.trim()
        if (source.isEmpty()) return false
        if (source == "*") return true
        if (source.equals("'self'", ignoreCase = true)) return documentOrigin.sameOrigin(AetherOrigin.from(target))
        if (source.equals("'none'", ignoreCase = true)) return false
        if (source.startsWith("'nonce-") || source.startsWith("'sha")) return false
        if (source.endsWith(':') && "://" !in source) return target.scheme == source.dropLast(1).lowercase(Locale.ROOT)

        val parsed = parseHostSource(source) ?: return false
        if (parsed.scheme != null && parsed.scheme != target.scheme) return false
        val hostMatches = when {
            parsed.host == "*" -> true
            parsed.host.startsWith("*.") -> {
                val suffix = parsed.host.removePrefix("*.")
                target.host != suffix && target.host.endsWith(".$suffix")
            }
            else -> target.host == parsed.host
        }
        if (!hostMatches) return false
        if (parsed.port != null && parsed.port != target.effectivePort) return false
        if (parsed.pathPrefix != null && !target.encodedPath.startsWith(parsed.pathPrefix)) return false
        return true
    }

    private data class HostSource(val scheme: String?, val host: String, val port: Int?, val pathPrefix: String?)

    private fun parseHostSource(source: String): HostSource? {
        val cleaned = source.trim().lowercase(Locale.ROOT)
        val withScheme = "://" in cleaned
        val scheme = if (withScheme) cleaned.substringBefore("://") else null
        val remainder = if (withScheme) cleaned.substringAfter("://") else cleaned
        if (remainder.isBlank() || remainder.startsWith("'")) return null
        val authority = remainder.substringBefore('/')
        val path = remainder.substringAfter('/', "").takeIf(String::isNotEmpty)?.let { "/$it" }
        val host: String
        val port: Int?
        if (authority.startsWith('[')) {
            val closing = authority.indexOf(']')
            if (closing <= 1) return null
            host = authority.substring(1, closing)
            val suffix = authority.substring(closing + 1)
            port = if (suffix.startsWith(':')) suffix.drop(1).toIntOrNull() else null
        } else {
            val colon = authority.lastIndexOf(':')
            if (colon > 0 && authority.indexOf(':') == colon) {
                host = authority.substring(0, colon)
                port = authority.substring(colon + 1).takeUnless { it == "*" }?.toIntOrNull()
            } else {
                host = authority
                port = null
            }
        }
        if (host.isBlank()) return null
        return HostSource(scheme, host, port, path)
    }

    companion object {
        val EMPTY = ContentSecurityPolicy(emptyMap())

        fun parse(values: List<String>): ContentSecurityPolicy {
            if (values.isEmpty()) return EMPTY
            val directives = linkedMapOf<String, MutableList<String>>()
            values.forEach headerLoop@ { header ->
                header.split(';').forEach directiveLoop@ { rawDirective ->
                    val trimmed = rawDirective.trim()
                    if (trimmed.isBlank()) return@directiveLoop
                    val parts = trimmed.split(WHITESPACE).filter(String::isNotBlank)
                    if (parts.isEmpty()) return@directiveLoop
                    val name = parts.first().lowercase(Locale.ROOT)
                    if (name !in KNOWN_DIRECTIVES || name in directives) return@directiveLoop
                    directives[name] = parts.drop(1).toMutableList()
                }
            }
            return ContentSecurityPolicy(directives)
        }

        fun parse(value: String?): ContentSecurityPolicy = parse(value?.let(::listOf).orEmpty())

        private fun directiveFor(type: SecurityResourceType): String = when (type) {
            SecurityResourceType.DOCUMENT -> "navigate-to"
            SecurityResourceType.SCRIPT -> "script-src"
            SecurityResourceType.STYLE -> "style-src"
            SecurityResourceType.IMAGE -> "img-src"
            SecurityResourceType.CONNECT -> "connect-src"
            SecurityResourceType.FONT -> "font-src"
            SecurityResourceType.MEDIA -> "media-src"
            SecurityResourceType.FRAME -> "frame-src"
            SecurityResourceType.OBJECT -> "object-src"
            SecurityResourceType.FORM -> "form-action"
            SecurityResourceType.WORKER -> "worker-src"
        }

        private val WHITESPACE = Regex("\\s+")
        private val KNOWN_DIRECTIVES = setOf(
            "default-src", "script-src", "style-src", "img-src", "connect-src", "font-src", "media-src",
            "frame-src", "object-src", "worker-src", "form-action", "base-uri", "navigate-to", "sandbox",
            "upgrade-insecure-requests", "block-all-mixed-content"
        )
    }
}
