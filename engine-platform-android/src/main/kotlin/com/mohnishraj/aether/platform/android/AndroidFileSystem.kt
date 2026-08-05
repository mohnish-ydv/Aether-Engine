package com.mohnishraj.aether.platform.android

import com.mohnishraj.aether.core.fs.AtomicFileWriter
import com.mohnishraj.aether.core.fs.FileEntry
import com.mohnishraj.aether.core.fs.StreamingFileSystem
import com.mohnishraj.aether.core.fs.VirtualPath
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class AndroidFileSystem(private val root: File) : StreamingFileSystem {
    init { root.mkdirs() }

    private fun resolve(path: VirtualPath): File {
        val target = File(root, path.value.removePrefix("/")).canonicalFile
        val rootCanonical = root.canonicalFile
        require(target.path == rootCanonical.path || target.path.startsWith(rootCanonical.path + File.separator)) {
            "Path escaped engine root"
        }
        return target
    }

    override fun write(path: VirtualPath, bytes: ByteArray) {
        val writer = openAtomicWriter(path)
        try {
            writer.output.write(bytes)
            writer.commit()
        } catch (error: Exception) {
            writer.abort()
            throw error
        }
    }

    override fun openAtomicWriter(path: VirtualPath): AtomicFileWriter {
        require(path != VirtualPath.ROOT)
        val file = resolve(path)
        file.parentFile?.mkdirs()
        val parent = requireNotNull(file.parentFile) { "Destination has no parent directory" }
        val temp = File(parent, ".${file.name}.${System.nanoTime()}.tmp")
        val output = FileOutputStream(temp)
        return object : AtomicFileWriter {
            private var finished = false
            override val output: FileOutputStream = output

            override fun commit() {
                if (finished) return
                output.flush()
                output.fdSyncIfPossible()
                output.close()
                moveReplacing(temp, file)
                finished = true
            }

            override fun abort() {
                if (finished) return
                runCatching { output.close() }
                runCatching { temp.delete() }
                finished = true
            }
        }
    }

    override fun read(path: VirtualPath): ByteArray = resolve(path).readBytes()
    override fun exists(path: VirtualPath): Boolean = resolve(path).exists()

    override fun delete(path: VirtualPath): Boolean {
        if (path == VirtualPath.ROOT) return false
        val file = resolve(path)
        return when {
            !file.exists() -> false
            file.isDirectory -> file.deleteRecursively()
            else -> file.delete()
        }
    }

    override fun list(path: VirtualPath): List<FileEntry> {
        val dir = resolve(path)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles().orEmpty().map { file ->
            val relative = file.canonicalFile.relativeTo(root.canonicalFile).invariantSeparatorsPath
            FileEntry(VirtualPath.of("/$relative"), if (file.isFile) file.length() else 0, file.lastModified(), file.isDirectory)
        }.sortedWith(compareBy<FileEntry> { !it.isDirectory }.thenBy { it.path.value })
    }

    override fun stat(path: VirtualPath): FileEntry? {
        val file = resolve(path)
        if (!file.exists()) return null
        return FileEntry(path, if (file.isFile) file.length() else 0, file.lastModified(), file.isDirectory)
    }

    private fun moveReplacing(temp: File, target: File) {
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun FileOutputStream.fdSyncIfPossible() = runCatching { fd.sync() }.getOrNull()
}
