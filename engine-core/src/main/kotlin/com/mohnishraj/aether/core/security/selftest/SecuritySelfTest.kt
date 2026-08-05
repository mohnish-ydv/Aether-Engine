package com.mohnishraj.aether.core.security.selftest

import com.mohnishraj.aether.core.EngineRuntime
import com.mohnishraj.aether.core.net.model.NetworkHeaders
import com.mohnishraj.aether.core.security.PermissionDecision
import com.mohnishraj.aether.core.security.PermissionFeature
import com.mohnishraj.aether.core.security.SecurityResourceType
import com.mohnishraj.aether.core.selftest.SelfTestCheck

object SecuritySelfTest {
    fun run(runtime: EngineRuntime): List<SelfTestCheck> {
        val checks = mutableListOf<SelfTestCheck>()
        fun check(name: String, block: () -> String) {
            val result = runCatching(block)
            checks += if (result.isSuccess) SelfTestCheck(name, true, result.getOrThrow())
            else SelfTestCheck(name, false, result.exceptionOrNull()?.message ?: "unknown error")
        }

        val security = runtime.security
        check("security same origin") {
            require(security.sameOrigin("https://a.test/a", "https://a.test/b"))
            require(!security.sameOrigin("https://a.test/", "http://a.test/"))
            "scheme/host/port tuple enforced"
        }
        check("security CSP self") {
            val headers = NetworkHeaders.of("Content-Security-Policy" to "default-src 'self'; connect-src https://api.a.test")
            val policy = security.buildDocumentPolicy("https://a.test/", headers)
            require(security.authorizeSubresource(policy, SecurityResourceType.IMAGE, "https://a.test/logo.png").allowed)
            require(!security.authorizeSubresource(policy, SecurityResourceType.IMAGE, "https://evil.test/logo.png").allowed)
            "default-src self enforced"
        }
        check("security mixed content") {
            val policy = security.buildDocumentPolicy("https://a.test/")
            require(!security.authorizeSubresource(policy, SecurityResourceType.SCRIPT, "http://a.test/app.js").allowed)
            "HTTPS-to-HTTP subresource blocked"
        }
        check("security insecure upgrade") {
            val headers = NetworkHeaders.of("Content-Security-Policy" to "upgrade-insecure-requests; default-src *")
            val policy = security.buildDocumentPolicy("https://a.test/", headers)
            val decision = security.authorizeSubresource(policy, SecurityResourceType.IMAGE, "http://cdn.test/a.png")
            require(decision.allowed && decision.effectiveUrl == "https://cdn.test/a.png")
            "insecure request upgraded"
        }
        check("security sandbox") {
            val headers = NetworkHeaders.of("Content-Security-Policy" to "sandbox allow-forms")
            val policy = security.buildDocumentPolicy("https://a.test/", headers)
            require(!security.authorizeInlineScript(policy).allowed)
            require(policy.sandbox.forms)
            "sandbox capability set enforced"
        }
        check("security permissions") {
            val policy = security.buildDocumentPolicy("https://a.test/")
            security.setPermission("https://a.test/", PermissionFeature.CAMERA, PermissionDecision.DENY)
            require(!security.authorizePermission(policy, PermissionFeature.CAMERA).allowed)
            "origin permission override enforced"
        }
        check("security CORS") {
            val policy = security.buildDocumentPolicy("https://a.test/")
            require(!security.validateCors(policy, "https://api.test/data", NetworkHeaders.EMPTY).allowed)
            require(security.validateCors(policy, "https://api.test/data", NetworkHeaders.of("Access-Control-Allow-Origin" to "https://a.test")).allowed)
            "cross-origin response headers enforced"
        }
        check("security HTTPS downgrade") {
            require(!security.authorizeRedirect("https://a.test/", "http://a.test/").allowed)
            "secure redirect downgrade blocked"
        }
        return checks
    }
}
