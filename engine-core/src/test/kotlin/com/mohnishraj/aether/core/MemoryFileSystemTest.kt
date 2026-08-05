package com.mohnishraj.aether.core

import com.mohnishraj.aether.core.fs.MemoryFileSystem
import com.mohnishraj.aether.core.fs.VirtualPath
import com.mohnishraj.aether.core.time.EngineClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryFileSystemTest {
    @Test fun supportsFoldersAndCopiesBytes() {
        var now = 10L
        val fs = MemoryFileSystem(EngineClock { now++ })
        val original = byteArrayOf(1, 2, 3)
        fs.write(VirtualPath.of("/cache/page.bin"), original)
        original[0] = 9
        assertEquals(1, fs.read(VirtualPath.of("/cache/page.bin"))[0])
        assertTrue(fs.list(VirtualPath.ROOT).first().isDirectory)
        assertTrue(fs.delete(VirtualPath.of("/cache")))
        assertFalse(fs.exists(VirtualPath.of("/cache/page.bin")))
    }
}
