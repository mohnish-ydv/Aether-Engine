package com.mohnishraj.aether.core.security

import com.mohnishraj.aether.core.log.EngineLogger
import com.mohnishraj.aether.core.net.model.AetherUrl
import com.mohnishraj.aether.core.net.model.NetworkHeaders
import com.mohnishraj.aether.core.profile.PerformanceProfiler
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class SecurityEngine(
    private val logger: EngineLogger? = null,
    private val profiler: PerformanceProfiler? = null
) {
    private val permissionOverrides = ConcurrentHashMap<String, ConcurrentHashMap<PermissionFeature, PermissionDecision>>()
    private val navigationChecks = AtomicLong()
    private val subresourceChecks = AtomicLong()
    private val blockedNavigations = AtomicLong()
    private val blockedSubresources = AtomicLong()
    private val mixedContentBlocks = AtomicLong()
    private val cspBlocks = AtomicLong()
    private val corsBlocks = AtomicLong()
    private val permissionBlocks = AtomicLong()
    private val downgradeBlocks = AtomicLong()

    fun authorizeNavigation(sourceUrl: String?, targetUrl: String): SecurityDecision {
        navigationChecks.incrementAndGet()
        profiler?.increment("security.navigation-checks")
        val target = runCatching { AetherUrl.parse(targetUrl) }.getOrElse {
            return blockNavigation("Invalid or unsupported destination: ${it.message.orEmpty()}")
        }
        if (sourceUrl != null) {
            val source = runCatching { AetherUrl.parse(sourceUrl) }.getOrNull()
            if (source != null && source.isSecure && !target.isSecure) {
                logger?.warn("Security", "Top-level HTTPS to HTTP navigation allowed with insecure indicator: ${target.origin}")
            }
        }
        return SecurityDecision.allow("Top-level navigation allowed", target.toString())
    }

    fun authorizeRedirect(initialUrl: String, finalUrl: String): SecurityDecision {
        val initial = runCatching { AetherUrl.parse(initialUrl) }.getOrElse {
            return blockNavigation("Invalid redirect source")
        }
        val final = runCatching { AetherUrl.parse(finalUrl) }.getOrElse {
            return blockNavigation("Invalid redirect destination")
        }
        if (initial.isSecure && !final.isSecure) {
            downgradeBlocks.incrementAndGet()
            return blockNavigation("HTTPS navigation was redirected to insecure HTTP")
        }
        return SecurityDecision.allow("Redirect security accepted", final.toString())
    }

    fun buildDocumentPolicy(url: String, headers: NetworkHeaders = NetworkHeaders.EMPTY, markup: String = ""): DocumentSecurityPolicy {
        val parsedUrl = AetherUrl.parse(url)
        val cspValues = buildList {
            addAll(headers.values("Content-Security-Policy"))
            META_CSP_REGEX.findAll(markup.take(MAX_META_SCAN_CHARS)).forEach { match ->
                val attributes = match.groupValues[1]
                val httpEquiv = attribute(attributes, "http-equiv")
                if (httpEquiv.equals("content-security-policy", ignoreCase = true)) {
                    attribute(attributes, "content")?.takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }
        val csp = ContentSecurityPolicy.parse(cspValues)
        val permissions = PermissionsPolicy.parse(headers["Permissions-Policy"])
        val referrer = headers["Referrer-Policy"]
            ?.split(',')
            ?.lastOrNull()
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it in KNOWN_REFERRER_POLICIES }
            ?: "strict-origin-when-cross-origin"
        return DocumentSecurityPolicy(
            documentUrl = parsedUrl.toString(),
            origin = AetherOrigin.from(parsedUrl),
            contentSecurityPolicy = csp,
            sandbox = SandboxPolicy.parse(csp.sandboxTokens),
            permissions = permissions,
            referrerPolicy = referrer
        )
    }

    fun authorizeSubresource(
        policy: DocumentSecurityPolicy,
        type: SecurityResourceType,
        targetUrl: String
    ): SecurityDecision {
        subresourceChecks.incrementAndGet()
        profiler?.increment("security.subresource-checks")
        var target = runCatching { AetherUrl.parse(targetUrl) }.getOrElse {
            return blockSubresource("Invalid or unsupported subresource URL: ${it.message.orEmpty()}")
        }
        val document = AetherUrl.parse(policy.documentUrl)
        if (document.isSecure && !target.isSecure) {
            if (policy.upgradeInsecureRequests) {
                target = AetherUrl.parse(target.toString().replaceFirst("http://", "https://"))
            } else {
                mixedContentBlocks.incrementAndGet()
                return blockSubresource("Mixed content blocked: HTTPS document requested HTTP ${type.name.lowercase()}")
            }
        }
        val csp = policy.contentSecurityPolicy.allowsExternal(type, target, policy.origin)
        if (!csp.allowed) {
            cspBlocks.incrementAndGet()
            return blockSubresource(csp.reason, csp.directive)
        }
        return SecurityDecision.allow("Subresource security accepted", target.toString(), csp.directive)
    }

    fun authorizeInlineScript(policy: DocumentSecurityPolicy, nonce: String? = null): SecurityDecision {
        if (!policy.sandbox.scripts) return blockSubresource("Sandbox blocks script execution", "sandbox")
        val decision = policy.contentSecurityPolicy.allowsInline(SecurityResourceType.SCRIPT, nonce)
        if (!decision.allowed) {
            cspBlocks.incrementAndGet()
            return blockSubresource(decision.reason, decision.directive)
        }
        return decision
    }

    fun authorizeInlineStyle(policy: DocumentSecurityPolicy, nonce: String? = null): SecurityDecision {
        val decision = policy.contentSecurityPolicy.allowsInline(SecurityResourceType.STYLE, nonce)
        if (!decision.allowed) {
            cspBlocks.incrementAndGet()
            return blockSubresource(decision.reason, decision.directive)
        }
        return decision
    }

    fun authorizeForm(policy: DocumentSecurityPolicy, targetUrl: String): SecurityDecision {
        if (!policy.sandbox.forms) return blockSubresource("Sandbox blocks form submission", "sandbox")
        val target = runCatching { AetherUrl.parse(targetUrl) }.getOrElse {
            return blockSubresource("Invalid form destination")
        }
        val mixed = authorizeSubresource(policy, SecurityResourceType.FORM, target.toString())
        if (!mixed.allowed) return mixed
        val form = policy.contentSecurityPolicy.allowsFormAction(AetherUrl.parse(mixed.effectiveUrl ?: target.toString()), policy.origin)
        if (!form.allowed) {
            cspBlocks.incrementAndGet()
            return blockSubresource(form.reason, form.directive)
        }
        return form.copy(effectiveUrl = mixed.effectiveUrl ?: target.toString())
    }

    fun authorizePermission(
        policy: DocumentSecurityPolicy,
        feature: PermissionFeature,
        targetOrigin: AetherOrigin = policy.origin,
        userGesture: Boolean = false
    ): SecurityDecision {
        if (!policy.permissions.allows(feature, policy.origin, targetOrigin)) {
            permissionBlocks.incrementAndGet()
            return SecurityDecision.block("Permissions-Policy blocks ${feature.name.lowercase().replace('_', '-')}", "permissions-policy")
        }
        val explicit = permissionOverrides[policy.origin.serialized]?.get(feature)
        val effective = explicit ?: defaultPermission(feature, userGesture)
        return when (effective) {
            PermissionDecision.ALLOW -> SecurityDecision.allow("Permission allowed")
            PermissionDecision.DENY -> {
                permissionBlocks.incrementAndGet()
                SecurityDecision.block("Permission denied for ${feature.name.lowercase().replace('_', '-')}")
            }
            PermissionDecision.ASK -> {
                permissionBlocks.incrementAndGet()
                SecurityDecision.block("Permission requires an explicit user decision")
            }
        }
    }

    fun setPermission(origin: String, feature: PermissionFeature, decision: PermissionDecision) {
        val canonical = AetherOrigin.parse(origin).serialized
        permissionOverrides.computeIfAbsent(canonical) { ConcurrentHashMap() }[feature] = decision
    }

    fun clearPermissions(origin: String? = null) {
        if (origin == null) permissionOverrides.clear()
        else permissionOverrides.remove(AetherOrigin.parse(origin).serialized)
    }

    fun validateCors(policy: DocumentSecurityPolicy, targetUrl: String, headers: NetworkHeaders): SecurityDecision {
        val target = AetherUrl.parse(targetUrl)
        if (policy.origin.sameOrigin(AetherOrigin.from(target))) return SecurityDecision.allow("Same-origin response")
        val allowedOrigins = headers.values("Access-Control-Allow-Origin").flatMap { it.split(',') }.map(String::trim)
        if ("*" in allowedOrigins || policy.origin.serialized in allowedOrigins) {
            return SecurityDecision.allow("CORS origin accepted")
        }
        corsBlocks.incrementAndGet()
        return blockSubresource("CORS blocked response from ${target.origin}")
    }

    fun sameOrigin(firstUrl: String, secondUrl: String): Boolean =
        AetherOrigin.parse(firstUrl).sameOrigin(AetherOrigin.parse(secondUrl))

    fun statistics(): SecurityStatistics = SecurityStatistics(
        navigationChecks.get(), subresourceChecks.get(), blockedNavigations.get(), blockedSubresources.get(),
        mixedContentBlocks.get(), cspBlocks.get(), corsBlocks.get(), permissionBlocks.get(), downgradeBlocks.get()
    )

    private fun blockNavigation(reason: String): SecurityDecision {
        blockedNavigations.incrementAndGet()
        logger?.warn("Security", reason)
        profiler?.increment("security.navigation-blocks")
        return SecurityDecision.block(reason)
    }

    private fun blockSubresource(reason: String, directive: String? = null): SecurityDecision {
        blockedSubresources.incrementAndGet()
        logger?.warn("Security", reason)
        profiler?.increment("security.subresource-blocks")
        return SecurityDecision.block(reason, directive)
    }

    private fun defaultPermission(feature: PermissionFeature, userGesture: Boolean): PermissionDecision = when (feature) {
        PermissionFeature.CLIPBOARD_WRITE -> if (userGesture) PermissionDecision.ALLOW else PermissionDecision.ALLOW
        PermissionFeature.CLIPBOARD_READ -> PermissionDecision.ALLOW
        else -> PermissionDecision.ASK
    }

    companion object {
        private const val MAX_META_SCAN_CHARS = 256_000
        private val META_CSP_REGEX = Regex("<meta\\b([^>]+)>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val ATTRIBUTE_REGEX = Regex("([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
        private val KNOWN_REFERRER_POLICIES = setOf(
            "no-referrer", "no-referrer-when-downgrade", "origin", "origin-when-cross-origin", "same-origin",
            "strict-origin", "strict-origin-when-cross-origin", "unsafe-url"
        )

        private fun attribute(raw: String, name: String): String? = ATTRIBUTE_REGEX.findAll(raw).firstNotNullOfOrNull { match ->
            if (!match.groupValues[1].equals(name, ignoreCase = true)) null
            else match.groupValues.drop(2).firstOrNull(String::isNotEmpty)
        }
    }
}
