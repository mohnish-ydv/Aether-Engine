package com.mohnishraj.aether.core.fs

interface FileSystem {
    fun write(path: VirtualPath, bytes: ByteArray)
    fun read(path: VirtualPath): ByteArray
    fun exists(path: VirtualPath): Boolean
    fun delete(path: VirtualPath): Boolean
    fun list(path: VirtualPath = VirtualPath.ROOT): List<FileEntry>
    fun stat(path: VirtualPath): FileEntry?
}

data class FileEntry(
    val path: VirtualPath,
    val size: Long,
    val modifiedAtMillis: Long,
    val isDirectory: Boolean
)
