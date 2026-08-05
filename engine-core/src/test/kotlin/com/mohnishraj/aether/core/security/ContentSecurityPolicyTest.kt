package com.mohnishraj.aether.core.security

import com.mohnishraj.aether.core.net.model.AetherUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ContentSecurityPolicyTest {
    private val origin = AetherOrigin.parse("https://app.test/")

    @Test fun emptyPolicyAllowsExternalResource() {
        assertTrue(ContentSecurityPolicy.EMPTY.allowsExternal(SecurityResourceType.IMAGE, AetherUrl.parse("https://cdn.test/a.png"), origin).allowed)
    }

    @Test fun noneBlocksResource() {
        val policy = ContentSecurityPolicy.parse("default-src 'none'")
        assertFalse(policy.allowsExternal(SecurityResourceType.IMAGE, AetherUrl.parse("https://app.test/a.png"), origin).allowed)
    }

    @Test fun selfMatchesSameOrigin() {
        val policy = ContentSecurityPolicy.parse("default-src 'self'")
        assertTrue(policy.allowsExternal(SecurityResourceType.IMAGE, AetherUrl.parse("https://app.test/a.png"), origin).allowed)
    }

    @Test fun selfBlocksOtherOrigin() {
        val policy = ContentSecurityPolicy.parse("default-src 'self'")
        assertFalse(policy.allowsExternal(SecurityResourceType.IMAGE, AetherUrl.parse("https://cdn.test/a.png"), origin).allowed)
    }

    @Test fun typeDirectiveOverridesDefault() {
        val policy = ContentSecurityPolicy.parse("default-src 'none'; img-src https://cdn.test")
        assertTrue(policy.allowsExternal(SecurityResourceType.IMAGE, AetherUrl.parse("https://cdn.test/a.png"), origin).allowed)
    }

    @Test fun schemeSourceMatches() {
        val policy = ContentSecurityPolicy.parse("connect-src https:")
        assertTrue(policy.allowsExternal(SecurityResourceType.CONNECT, AetherUrl.parse("https://api.test/data"), origin).allowed)
    }

    @Test fun schemeSourceBlocksHttp() {
        val policy = ContentSecurityPolicy.parse("connect-src https:")
        assertFalse(policy.allowsExternal(SecurityResourceType.CONNECT, AetherUrl.parse("http://api.test/data"), origin).allowed)
    }

    @Test fun wildcardSubdomainMatchesChild() {
        val policy = ContentSecurityPolicy.parse("img-src https://*.cdn.test")
        assertTrue(policy.allowsExternal(SecurityResourceType.IMAGE, AetherUrl.parse("https://images.cdn.test/a.png"), origin).allowed)
    }

    @Test fun wildcardSubdomainDoesNotMatchApex() {
        val policy = ContentSecurityPolicy.parse("img-src https://*.cdn.test")
        assertFalse(policy.allowsExternal(SecurityResourceType.IMAGE, AetherUrl.parse("https://cdn.test/a.png"), origin).allowed)
    }

    @Test fun portSourceIsEnforced() {
        val policy = ContentSecurityPolicy.parse("connect-src https://api.test:8443")
        assertFalse(policy.allowsExternal(SecurityResourceType.CONNECT, AetherUrl.parse("https://api.test/data"), origin).allowed)
    }

    @Test fun pathPrefixIsEnforced() {
        val policy = ContentSecurityPolicy.parse("img-src https://cdn.test/public/")
        assertFalse(policy.allowsExternal(SecurityResourceType.IMAGE, AetherUrl.parse("https://cdn.test/private/a.png"), origin).allowed)
    }

    @Test fun unsafeInlineAllowsInlineScript() {
        val policy = ContentSecurityPolicy.parse("script-src 'unsafe-inline'")
        assertTrue(policy.allowsInline(SecurityResourceType.SCRIPT).allowed)
    }

    @Test fun nonceAllowsMatchingInlineScript() {
        val policy = ContentSecurityPolicy.parse("script-src 'nonce-aether'")
        assertTrue(policy.allowsInline(SecurityResourceType.SCRIPT, "aether").allowed)
    }

    @Test fun missingNonceBlocksInlineScript() {
        val policy = ContentSecurityPolicy.parse("script-src 'nonce-aether'")
        assertFalse(policy.allowsInline(SecurityResourceType.SCRIPT).allowed)
    }

    @Test fun formActionSelfAllowsSameOrigin() {
        val policy = ContentSecurityPolicy.parse("form-action 'self'")
        assertTrue(policy.allowsFormAction(AetherUrl.parse("https://app.test/submit"), origin).allowed)
    }

    @Test fun formActionSelfBlocksCrossOrigin() {
        val policy = ContentSecurityPolicy.parse("form-action 'self'")
        assertFalse(policy.allowsFormAction(AetherUrl.parse("https://evil.test/submit"), origin).allowed)
    }

    @Test fun upgradeDirectiveIsDetected() {
        assertTrue(ContentSecurityPolicy.parse("upgrade-insecure-requests").upgradeInsecureRequests)
    }

    @Test fun mixedContentDirectiveIsDetected() {
        assertTrue(ContentSecurityPolicy.parse("block-all-mixed-content").blockAllMixedContent)
    }

    @Test fun firstDuplicateDirectiveWins() {
        val policy = ContentSecurityPolicy.parse("img-src 'self'; img-src *")
        assertFalse(policy.allowsExternal(SecurityResourceType.IMAGE, AetherUrl.parse("https://other.test/a.png"), origin).allowed)
    }

    @Test fun snapshotContainsParsedDirective() {
        assertNotNull(ContentSecurityPolicy.parse("object-src 'none'").snapshot()["object-src"])
    }

    @Test fun directiveNameIsReportedOnBlock() {
        val result = ContentSecurityPolicy.parse("connect-src 'none'").allowsExternal(SecurityResourceType.CONNECT, AetherUrl.parse("https://api.test/"), origin)
        assertEquals("connect-src", result.directive)
    }
}
