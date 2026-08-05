package com.mohnishraj.aether.core

import com.mohnishraj.aether.core.fs.VirtualPath
import com.mohnishraj.aether.core.net.model.AetherUrl
import com.mohnishraj.aether.core.net.model.NetworkHeaders
import com.mohnishraj.aether.core.shell.BrowserShellRuntime
import com.mohnishraj.aether.core.shell.shellFixture
import com.mohnishraj.aether.core.shell.shellViewport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IncognitoIsolationTest {
    @Test fun privateCookiesCacheTabsHistoryStorageAndFilesAreWipedTogether() {
        val fixture = shellFixture()
        val network = requireNotNull(fixture.runtime.network)
        val shell = BrowserShellRuntime(
            browser = fixture.runtime.browser,
            render = fixture.runtime.render,
            network = network,
            fileSystem = fixture.fileSystem,
            sessionPersistenceEnabled = false
        )
        val privateRuntime = IncognitoRuntime(fixture.fileSystem, network, fixture.runtime.browser, shell)
        val loaded = shell.openTab("https://site.test/one", viewport = shellViewport())
        val page = requireNotNull(loaded.page)
        page.localStorage.setItem("private-local", "secret")
        page.sessionStorage.setItem("private-session", "secret")
        network.cookies.saveFromResponse(
            AetherUrl.parse("https://site.test/one"),
            NetworkHeaders.of("Set-Cookie" to "private=yes; Secure; Path=/")
        )
        fixture.fileSystem.write(VirtualPath.of("/private/marker.bin"), byteArrayOf(1, 2, 3))
        shell.saveSession()

        assertFalse(fixture.fileSystem.exists(VirtualPath.of("/browser/session/m10.bin")))
        assertTrue(network.cookies.snapshot().isNotEmpty())
        assertTrue(network.cache.stats().entries > 0)
        assertEquals(1, shell.tabCount())

        privateRuntime.wipe()

        assertEquals(0, shell.tabCount())
        assertTrue(network.cookies.snapshot().isEmpty())
        assertEquals(0, network.cache.stats().entries)
        assertTrue(fixture.fileSystem.list().isEmpty())
        val reopened = fixture.runtime.browser.open("<p>fresh</p>", "https://site.test/one")
        assertNull(reopened.localStorage.getItem("private-local"))
        assertNull(reopened.sessionStorage.getItem("private-session"))
    }
}
