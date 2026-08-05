package com.mohnishraj.aether.core

import com.mohnishraj.aether.core.fs.VirtualPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VirtualPathTest {
    @Test fun normalizesPath() {
        assertEquals("/a/c", VirtualPath.of("a//b/../c").value)
    }

    @Test fun blocksRootEscape() {
        assertFailsWith<IllegalArgumentException> { VirtualPath.of("../../secret") }
    }
}
