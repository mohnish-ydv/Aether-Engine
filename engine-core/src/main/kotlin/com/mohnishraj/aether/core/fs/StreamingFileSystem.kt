package com.mohnishraj.aether.core.fs

import java.io.OutputStream

interface AtomicFileWriter : AutoCloseable {
    val output: OutputStream
    fun commit()
    fun abort()
    override fun close() = abort()
}

interface StreamingFileSystem : FileSystem {
    fun openAtomicWriter(path: VirtualPath): AtomicFileWriter
}
