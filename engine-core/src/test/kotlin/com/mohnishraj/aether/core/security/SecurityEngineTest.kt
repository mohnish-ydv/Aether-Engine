package com.mohnishraj.aether.core.security

import com.mohnishraj.aether.core.net.model.NetworkHeaders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecurityEngineTest {
    private fun engine() = SecurityEngine()

    @Test fun validTopLevelNavigationIsAllowed() {
        assertTrue(engine().authorizeNavigation(null, "https://example.test/").allowed)
    }

    @Test fun unsupportedTopLevelSchemeIsBlocked() {
        assertFalse(engine().authorizeNavigation(null, "javascript:alert(1)").allowed)
    }

    @Test fun httpsRedirectDowngradeIsBlocked() {
        assertFalse(engine().authorizeRedirect("https://example.test/", "http://example.test/").allowed)
    }

    @Test fun httpToHttpsRedirectIsAllowed() {
        assertTrue(engine().authorizeRedirect("http://example.test/", "https://example.test/").allowed)
    }

    @Test fun secureMixedContentIsBlocked() {
        val security = engine()
        val policy = security.buildDocumentPolicy("https://example.test/")
        assertFalse(security.authorizeSubresource(policy, SecurityResourceType.SCRIPT, "http://example.test/app.js").allowed)
    }

    @Test fun httpDocumentCanLoadHttpResource() {
        val security = engine()
        val policy = security.buildDocumentPolicy("http://example.test/")
        assertTrue(security.authorizeSubresource(policy, SecurityResourceType.IMAGE, "http://example.test/a.png").allowed)
    }

    @Test fun upgradeInsecureRequestsRewritesUrl() {
        val security = engine()
        val policy = security.buildDocumentPolicy("https://example.test/", NetworkHeaders.of("Content-Security-Policy" to "upgrade-insecure-requests; default-src *"))
        assertEquals("https://cdn.test/a.png", security.authorizeSubresource(policy, SecurityResourceType.IMAGE, "http://cdn.test/a.png").effectiveUrl)
    }

    @Test fun metaCspIsParsed() {
        val security = engine()
        val markup = "<html><head><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'\"></head></html>"
        val policy = security.buildDocumentPolicy("https://example.test/", markup = markup)
        assertFalse(security.authorizeSubresource(policy, SecurityResourceType.IMAGE, "https://example.test/a.png").allowed)
    }

    @Test fun sandboxBlocksInlineScript() {
        val security = engine()
        val policy = security.buildDocumentPolicy("https://example.test/", NetworkHeaders.of("Content-Security-Policy" to "sandbox"))
        assertFalse(security.authorizeInlineScript(policy).allowed)
    }

    @Test fun cspBlocksInlineScriptWithoutNonce() {
        val security = engine()
        val policy = security.buildDocumentPolicy("https://example.test/", NetworkHeaders.of("Content-Security-Policy" to "script-src 'nonce-safe'"))
        assertFalse(security.authorizeInlineScript(policy).allowed)
    }

    @Test fun cspAllowsInlineScriptWithNonce() {
        val security = engine()
        val policy = security.buildDocumentPolicy("https://example.test/", NetworkHeaders.of("Content-Security-Policy" to "script-src 'nonce-safe'"))
        assertTrue(security.authorizeInlineScript(policy, "safe").allowed)
    }

    @Test fun sandboxBlocksForms() {
        val security = engine()
        val policy = security.buildDocumentPolicy("https://example.test/", NetworkHeaders.of("Content-Security-Policy" to "sandbox allow-scripts"))
        assertFalse(security.authorizeForm(policy, "https://example.test/submit").allowed)
    }

    @Test fun formActionBlocksOtherOrigin() {
        val security = engine()
        val policy = security.buildDocumentPolicy("https://example.test/", NetworkHeaders.of("Content-Security-Policy" to "form-action 'self'"))
        assertFalse(security.authorizeForm(policy, "https://evil.test/submit").allowed)
    }

    @Test fun sameOriginCorsIsAllowedWithoutHeader() {
        val security = engine()
        val policy = security.buildDocumentPolicy("https://example.test/")
        assertTrue(security.validateCors(policy, "https://example.test/data", NetworkHeaders.EMPTY).allowed)
    }

    @Test fun crossOriginCorsWithoutHeaderIsBlocked() {
        val security = engine()
        val policy = security.buildDocumentPolicy("https://example.test/")
        assertFalse(security.validateCors(policy, "https://api.test/data", NetworkHeaders.EMPTY).allowed)
    }

    @Test fun wildcardCorsIsAllowed() {
        val security = engine()
        val policy = security.buildDocumentPolicy("https://example.test/")
        assertTrue(security.validateCors(policy, "https://api.test/data", NetworkHeaders.of("Access-Control-Allow-Origin" to "*")).allowed)
    }

    @Test fun matchingCorsOriginIsAllowed() {
        val security = engine()
        val policy = security.buildDocumentPolicy("https://example.test/")
        assertTrue(security.validateCors(policy, "https://api.test/data", NetworkHeaders.of("Access-Control-Allow-Origin" to "https://example.test")).allowed)
    }

    @Test fun explicitPermissionDenyIsEnforced() {
        val security = engine()
        val policy = security.buildDocumentPolicy("https://example.test/")
        security.setPermission("https://example.test/", PermissionFeature.CLIPBOARD_WRITE, PermissionDecision.DENY)
        assertFalse(security.authorizePermission(policy, PermissionFeature.CLIPBOARD_WRITE).allowed)
    }

    @Test fun explicitPermissionAllowIsEnforced() {
        val security = engine()
        val policy = security.buildDocumentPolicy("https://example.test/", NetworkHeaders.of("Permissions-Policy" to "camera=(self)"))
        security.setPermission("https://example.test/", PermissionFeature.CAMERA, PermissionDecision.ALLOW)
        assertTrue(security.authorizePermission(policy, PermissionFeature.CAMERA).allowed)
    }

    @Test fun clearingPermissionRestoresDefault() {
        val security = engine()
        val policy = security.buildDocumentPolicy("https://example.test/")
        security.setPermission("https://example.test/", PermissionFeature.CLIPBOARD_WRITE, PermissionDecision.DENY)
        security.clearPermissions("https://example.test/")
        assertTrue(security.authorizePermission(policy, PermissionFeature.CLIPBOARD_WRITE).allowed)
    }

    @Test fun securityStatisticsCountBlocks() {
        val security = engine()
        val policy = security.buildDocumentPolicy("https://example.test/")
        security.authorizeSubresource(policy, SecurityResourceType.SCRIPT, "http://example.test/app.js")
        assertTrue(security.statistics().blockedSubresources > 0)
    }
}
