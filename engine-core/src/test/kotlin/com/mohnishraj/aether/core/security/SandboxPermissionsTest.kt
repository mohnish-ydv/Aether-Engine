package com.mohnishraj.aether.core.security

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SandboxPermissionsTest {
    @Test fun noSandboxDirectiveIsPermissive() {
        assertTrue(SandboxPolicy.parse(null).scripts)
    }

    @Test fun emptySandboxBlocksScripts() {
        assertFalse(SandboxPolicy.parse(emptyList()).scripts)
    }

    @Test fun allowScriptsTokenEnablesScripts() {
        assertTrue(SandboxPolicy.parse(listOf("allow-scripts")).scripts)
    }

    @Test fun formsRemainBlockedWithoutToken() {
        assertFalse(SandboxPolicy.parse(listOf("allow-scripts")).forms)
    }

    @Test fun sameOriginRequiresToken() {
        assertFalse(SandboxPolicy.parse(listOf("allow-scripts")).sameOrigin)
    }

    @Test fun topNavigationUserActivationTokenEnablesNavigation() {
        assertTrue(SandboxPolicy.parse(listOf("allow-top-navigation-by-user-activation")).topNavigation)
    }

    @Test fun emptyCameraAllowListBlocksCamera() {
        val policy = PermissionsPolicy.parse("camera=()")
        assertFalse(policy.allows(PermissionFeature.CAMERA, AetherOrigin.parse("https://app.test/")))
    }

    @Test fun selfAllowListAllowsSameOrigin() {
        val origin = AetherOrigin.parse("https://app.test/")
        assertTrue(PermissionsPolicy.parse("camera=(self)").allows(PermissionFeature.CAMERA, origin, origin))
    }

    @Test fun selfAllowListBlocksOtherOrigin() {
        val origin = AetherOrigin.parse("https://app.test/")
        assertFalse(PermissionsPolicy.parse("camera=(self)").allows(PermissionFeature.CAMERA, origin, AetherOrigin.parse("https://other.test/")))
    }

    @Test fun wildcardAllowListAllowsOtherOrigin() {
        val origin = AetherOrigin.parse("https://app.test/")
        assertTrue(PermissionsPolicy.parse("camera=(*)").allows(PermissionFeature.CAMERA, origin, AetherOrigin.parse("https://other.test/")))
    }

    @Test fun explicitOriginAllowListMatches() {
        val origin = AetherOrigin.parse("https://app.test/")
        assertTrue(PermissionsPolicy.parse("camera=(\"https://camera.test\")").allows(PermissionFeature.CAMERA, origin, AetherOrigin.parse("https://camera.test/")))
    }

    @Test fun sensitivePermissionDefaultsToDeniedPolicy() {
        assertFalse(PermissionsPolicy.DEFAULT.allows(PermissionFeature.CAMERA, AetherOrigin.parse("https://app.test/")))
    }

    @Test fun clipboardWriteDefaultPolicyIsAvailable() {
        assertTrue(PermissionsPolicy.DEFAULT.allows(PermissionFeature.CLIPBOARD_WRITE, AetherOrigin.parse("https://app.test/")))
    }
}
