package com.mohnishraj.aether.core.fs

import com.mohnishraj.aether.core.time.EngineClock
import java.io.ByteArrayOutputStream
import java.nio.file.NoSuchFileException

class MemoryFileSystem(private val clock: EngineClock = EngineClock.SYSTEM) : StreamingFileSystem {
    private data class StoredFile(val bytes: ByteArray, val modifiedAt: Long)
    private val files = linkedMapOf<VirtualPath, StoredFile>()

    @Synchronized override fun write(path: VirtualPath, bytes: ByteArray) {
        require(path != VirtualPath.ROOT) { "Cannot write to root" }
        files[path] = StoredFile(bytes.copyOf(), clock.nowMillis())
    }

    @Synchronized override fun read(path: VirtualPath): ByteArray =
        files[path]?.bytes?.copyOf() ?: throw NoSuchFileException(path.value)

    @Synchronized override fun exists(path: VirtualPath): Boolean =
        path == VirtualPath.ROOT || files.containsKey(path) || files.keys.any { it.isWithin(path) }

    @Synchronized override fun delete(path: VirtualPath): Boolean {
        if (path == VirtualPath.ROOT) return false
        val removed = files.remove(path) != null
        val children = files.keys.filter { it.isWithin(path) }
        children.forEach(files::remove)
        return removed || children.isNotEmpty()
    }

    @Synchronized override fun list(path: VirtualPath): List<FileEntry> {
        if (!exists(path)) return emptyList()
        val direct = linkedMapOf<VirtualPath, FileEntry>()
        files.forEach { (filePath, file) ->
            if (!filePath.isWithin(path) || filePath == path) return@forEach
            val relative = filePath.value.removePrefix(path.value.trimEnd('/')).trimStart('/')
            val first = relative.substringBefore('/')
            val child = path.resolve(first)
            if ('/' in relative) {
                direct.putIfAbsent(child, FileEntry(child, 0, file.modifiedAt, true))
            } else {
                direct[child] = FileEntry(child, file.bytes.size.toLong(), file.modifiedAt, false)
            }
        }
        return direct.values.sortedWith(compareBy<FileEntry> { !it.isDirectory }.thenBy { it.path.value })
    }

    override fun openAtomicWriter(path: VirtualPath): AtomicFileWriter {
        require(path != VirtualPath.ROOT) { "Cannot write to root" }
        val buffer = ByteArrayOutputStream()
        return object : AtomicFileWriter {
            private var finished = false
            override val output = buffer
            override fun commit() {
                if (finished) return
                write(path, buffer.toByteArray())
                finished = true
                buffer.close()
            }
            override fun abort() {
                if (finished) return
                finished = true
                buffer.reset()
                buffer.close()
            }
        }
    }

    @Synchronized override fun stat(path: VirtualPath): FileEntry? {
        if (path == VirtualPath.ROOT) return FileEntry(path, 0, 0, true)
        files[path]?.let { return FileEntry(path, it.bytes.size.toLong(), it.modifiedAt, false) }
        val children = files.filterKeys { it.isWithin(path) }
        if (children.isNotEmpty()) return FileEntry(path, 0, children.values.maxOf { it.modifiedAt }, true)
        return null
    }
}
