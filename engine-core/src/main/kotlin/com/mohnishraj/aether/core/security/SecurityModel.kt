package com.mohnishraj.aether.core.security

import com.mohnishraj.aether.core.net.model.AetherUrl
import java.util.Collections

enum class SecurityResourceType {
    DOCUMENT, SCRIPT, STYLE, IMAGE, CONNECT, FONT, MEDIA, FRAME, OBJECT, FORM, WORKER
}

enum class PermissionFeature {
    CLIPBOARD_READ,
    CLIPBOARD_WRITE,
    GEOLOCATION,
    CAMERA,
    MICROPHONE,
    NOTIFICATIONS
}

enum class PermissionDecision { ALLOW, DENY, ASK }

data class SecurityDecision(
    val allowed: Boolean,
    val reason: String,
    val effectiveUrl: String? = null,
    val directive: String? = null
) {
    companion object {
        fun allow(reason: String = "allowed", effectiveUrl: String? = null, directive: String? = null) =
            SecurityDecision(true, reason, effectiveUrl, directive)

        fun block(reason: String, directive: String? = null) =
            SecurityDecision(false, reason, null, directive)
    }
}

data class AetherOrigin private constructor(
    val scheme: String,
    val host: String,
    val port: Int,
    val opaque: Boolean = false,
    val opaqueToken: String? = null
) {
    val serialized: String
        get() = if (opaque) "null" else buildString {
            append(scheme).append("://").append(hostForDisplay())
            if (port != defaultPort(scheme)) append(':').append(port)
        }

    fun sameOrigin(other: AetherOrigin): Boolean =
        !opaque && !other.opaque && scheme == other.scheme && host == other.host && port == other.port

    private fun hostForDisplay(): String = if (':' in host && !host.startsWith("[")) "[$host]" else host

    companion object {
        fun from(url: AetherUrl): AetherOrigin = AetherOrigin(url.scheme, url.host, url.effectivePort)
        fun parse(raw: String): AetherOrigin = from(AetherUrl.parse(raw))
        fun opaque(token: String): AetherOrigin = AetherOrigin("opaque", "", -1, true, token.take(128))
        private fun defaultPort(scheme: String): Int = if (scheme == "https") 443 else 80
    }
}

data class SandboxPolicy(
    val scripts: Boolean = true,
    val forms: Boolean = true,
    val sameOrigin: Boolean = true,
    val popups: Boolean = true,
    val topNavigation: Boolean = true,
    val downloads: Boolean = true,
    val modals: Boolean = true
) {
    companion object {
        val NONE = SandboxPolicy()

        fun parse(tokens: List<String>?): SandboxPolicy {
            if (tokens == null) return NONE
            val normalized = tokens.map(String::lowercase).toSet()
            return SandboxPolicy(
                scripts = "allow-scripts" in normalized,
                forms = "allow-forms" in normalized,
                sameOrigin = "allow-same-origin" in normalized,
                popups = "allow-popups" in normalized,
                topNavigation = "allow-top-navigation" in normalized || "allow-top-navigation-by-user-activation" in normalized,
                downloads = "allow-downloads" in normalized,
                modals = "allow-modals" in normalized
            )
        }
    }
}

data class PermissionsPolicy(
    private val rules: Map<PermissionFeature, Set<String>> = emptyMap()
) {
    fun allows(feature: PermissionFeature, documentOrigin: AetherOrigin, targetOrigin: AetherOrigin = documentOrigin): Boolean {
        val allowList = rules[feature] ?: return defaultFor(feature)
        if (allowList.isEmpty()) return false
        if ("*" in allowList) return true
        if ("self" in allowList && documentOrigin.sameOrigin(targetOrigin)) return true
        return targetOrigin.serialized in allowList
    }

    fun snapshot(): Map<PermissionFeature, Set<String>> = Collections.unmodifiableMap(rules.mapValues { it.value.toSet() })

    companion object {
        val DEFAULT = PermissionsPolicy()

        fun parse(value: String?): PermissionsPolicy {
            if (value.isNullOrBlank()) return DEFAULT
            val parsed = linkedMapOf<PermissionFeature, Set<String>>()
            splitDirectives(value).forEach { part ->
                val equals = part.indexOf('=')
                if (equals <= 0) return@forEach
                val feature = featureFor(part.substring(0, equals).trim()) ?: return@forEach
                val rawList = part.substring(equals + 1).trim()
                if (!rawList.startsWith('(') || !rawList.endsWith(')')) return@forEach
                val body = rawList.substring(1, rawList.length - 1).trim()
                if (body.isEmpty()) {
                    parsed[feature] = emptySet()
                } else {
                    parsed[feature] = TOKEN_REGEX.findAll(body).map { match ->
                        match.value.trim().trim('"').let { token ->
                            when (token.lowercase()) {
                                "self" -> "self"
                                "*" -> "*"
                                else -> token
                            }
                        }
                    }.filter(String::isNotBlank).toSet()
                }
            }
            return PermissionsPolicy(parsed)
        }

        private fun defaultFor(feature: PermissionFeature): Boolean = when (feature) {
            PermissionFeature.CLIPBOARD_WRITE -> true
            PermissionFeature.CLIPBOARD_READ -> true
            else -> false
        }

        private fun featureFor(raw: String): PermissionFeature? = when (raw.trim().lowercase()) {
            "clipboard-read" -> PermissionFeature.CLIPBOARD_READ
            "clipboard-write" -> PermissionFeature.CLIPBOARD_WRITE
            "geolocation" -> PermissionFeature.GEOLOCATION
            "camera" -> PermissionFeature.CAMERA
            "microphone" -> PermissionFeature.MICROPHONE
            "notifications" -> PermissionFeature.NOTIFICATIONS
            else -> null
        }

        private fun splitDirectives(value: String): List<String> {
            val output = mutableListOf<String>()
            val current = StringBuilder()
            var depth = 0
            value.forEach { character ->
                when (character) {
                    '(' -> depth++
                    ')' -> if (depth > 0) depth--
                    ',' -> if (depth == 0) {
                        output += current.toString().trim()
                        current.setLength(0)
                        return@forEach
                    }
                }
                current.append(character)
            }
            if (current.isNotBlank()) output += current.toString().trim()
            return output
        }

        private val TOKEN_REGEX = Regex("\\*|self|\"[^\"]+\"|[^\\s]+")
    }
}

data class DocumentSecurityPolicy(
    val documentUrl: String,
    val origin: AetherOrigin,
    val contentSecurityPolicy: ContentSecurityPolicy = ContentSecurityPolicy.EMPTY,
    val sandbox: SandboxPolicy = SandboxPolicy.NONE,
    val permissions: PermissionsPolicy = PermissionsPolicy.DEFAULT,
    val referrerPolicy: String = "strict-origin-when-cross-origin"
) {
    val blockAllMixedContent: Boolean get() = contentSecurityPolicy.blockAllMixedContent
    val upgradeInsecureRequests: Boolean get() = contentSecurityPolicy.upgradeInsecureRequests

    companion object {
        fun permissive(url: String): DocumentSecurityPolicy {
            val parsed = AetherUrl.parse(url)
            return DocumentSecurityPolicy(parsed.toString(), AetherOrigin.from(parsed))
        }
    }
}

data class SecurityStatistics(
    val navigationChecks: Long,
    val subresourceChecks: Long,
    val blockedNavigations: Long,
    val blockedSubresources: Long,
    val mixedContentBlocks: Long,
    val cspBlocks: Long,
    val corsBlocks: Long,
    val permissionBlocks: Long,
    val downgradeBlocks: Long
)
