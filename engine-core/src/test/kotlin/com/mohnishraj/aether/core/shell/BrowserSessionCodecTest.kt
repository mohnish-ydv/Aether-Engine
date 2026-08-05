package com.mohnishraj.aether.core.shell

import com.mohnishraj.aether.core.fs.MemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserSessionCodecTest {
    private fun snapshot(): BrowserSessionSnapshot {
        val entries = listOf(
            BrowserHistoryEntry("https://one.test/", "One", 1L, NavigationTransition.TYPED),
            BrowserHistoryEntry("https://two.test/", "Two", 2L, NavigationTransition.LINK)
        )
        return BrowserSessionSnapshot(
            BrowserTabId(9),
            listOf(PersistedTab(BrowserTabId(9), entries, 1, pinned = true)),
            listOf(ClosedTabSnapshot("Closed", entries, 0))
        )
    }

    @Test fun codecRoundTrips() {
        assertEquals(snapshot(), BrowserSessionCodec.decode(BrowserSessionCodec.encode(snapshot())))
    }

    @Test fun codecRejectsBadMagic() {
        val bytes = BrowserSessionCodec.encode(snapshot())
        bytes[0] = 0
        assertFailsWith<IllegalArgumentException> { BrowserSessionCodec.decode(bytes) }
    }

    @Test fun codecRejectsTrailingBytes() {
        val bytes = BrowserSessionCodec.encode(snapshot()) + byteArrayOf(1)
        assertFailsWith<IllegalArgumentException> { BrowserSessionCodec.decode(bytes) }
    }

    @Test fun codecPreservesPinnedState() {
        assertTrue(BrowserSessionCodec.decode(BrowserSessionCodec.encode(snapshot())).tabs.single().pinned)
    }

    @Test fun codecPreservesTransition() {
        val decoded = BrowserSessionCodec.decode(BrowserSessionCodec.encode(snapshot()))
        assertEquals(NavigationTransition.LINK, decoded.tabs.single().entries.last().transition)
    }

    @Test fun codecPreservesActiveTab() {
        assertEquals(BrowserTabId(9), BrowserSessionCodec.decode(BrowserSessionCodec.encode(snapshot())).activeTabId)
    }

    @Test fun storeReturnsNullWhenMissing() {
        assertNull(BrowserSessionStore(MemoryFileSystem(), BrowserShellLimits()).load())
    }

    @Test fun storeSavesAndLoads() {
        val fileSystem = MemoryFileSystem()
        val store = BrowserSessionStore(fileSystem, BrowserShellLimits())
        store.save(snapshot())
        assertEquals(snapshot(), store.load())
    }

    @Test fun storeClearDeletesSession() {
        val fileSystem = MemoryFileSystem()
        val store = BrowserSessionStore(fileSystem, BrowserShellLimits())
        store.save(snapshot())
        assertTrue(store.clear())
        assertNull(store.load())
    }

    @Test fun corruptStoreIsPurged() {
        val fileSystem = MemoryFileSystem()
        fileSystem.write(com.mohnishraj.aether.core.fs.VirtualPath.of("/browser/session/m10.bin"), byteArrayOf(1, 2, 3))
        val store = BrowserSessionStore(fileSystem, BrowserShellLimits())
        assertNull(store.load())
        assertFalse(fileSystem.exists(com.mohnishraj.aether.core.fs.VirtualPath.of("/browser/session/m10.bin")))
    }

    @Test fun snapshotRequiresHistoryIndex() {
        val entry = BrowserHistoryEntry("https://x/", "x", 0, NavigationTransition.TYPED)
        assertFailsWith<IllegalArgumentException> { PersistedTab(BrowserTabId(1), listOf(entry), 2) }
        val tab = PersistedTab(BrowserTabId(1), listOf(entry), 0)
        assertFailsWith<IllegalArgumentException> { BrowserSessionSnapshot(BrowserTabId(2), listOf(tab), emptyList()) }
        assertFailsWith<IllegalArgumentException> { BrowserSessionSnapshot(BrowserTabId(1), listOf(tab, tab), emptyList()) }
    }

    @Test fun decodedSnapshotHasClosedTab() {
        assertNotNull(BrowserSessionCodec.decode(BrowserSessionCodec.encode(snapshot())).closedTabs.singleOrNull())
    }
}
