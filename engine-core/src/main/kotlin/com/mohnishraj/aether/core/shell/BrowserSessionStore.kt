package com.mohnishraj.aether.core.shell

import com.mohnishraj.aether.core.fs.FileSystem
import com.mohnishraj.aether.core.fs.VirtualPath
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class BrowserSessionStore(
    private val fileSystem: FileSystem,
    private val limits: BrowserShellLimits,
    private val path: VirtualPath = VirtualPath.of("/browser/session/m10.bin")
) {
    fun save(snapshot: BrowserSessionSnapshot) {
        val bytes = BrowserSessionCodec.encode(snapshot)
        require(bytes.size <= limits.maxSessionBytes) { "Session exceeds ${limits.maxSessionBytes} bytes" }
        fileSystem.write(path, bytes)
    }

    fun load(): BrowserSessionSnapshot? {
        if (!fileSystem.exists(path)) return null
        val bytes = fileSystem.read(path)
        if (bytes.size > limits.maxSessionBytes) {
            fileSystem.delete(path)
            return null
        }
        return runCatching { BrowserSessionCodec.decode(bytes, limits) }
            .onFailure { fileSystem.delete(path) }
            .getOrNull()
    }

    fun clear(): Boolean = fileSystem.delete(path)
}

object BrowserSessionCodec {
    private const val MAGIC = 0x41455448
    private const val VERSION = 1

    fun encode(snapshot: BrowserSessionSnapshot): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeLong(snapshot.activeTabId?.value ?: -1L)
            output.writeInt(snapshot.tabs.size)
            snapshot.tabs.forEach { writeTab(output, it) }
            output.writeInt(snapshot.closedTabs.size)
            snapshot.closedTabs.forEach { closed ->
                output.writeUTF(closed.title.take(512))
                output.writeInt(closed.currentIndex)
                writeEntries(output, closed.entries)
            }
        }
        return buffer.toByteArray()
    }

    fun decode(bytes: ByteArray, limits: BrowserShellLimits = BrowserShellLimits()): BrowserSessionSnapshot {
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC) { "Invalid session magic" }
            require(input.readInt() == VERSION) { "Unsupported session version" }
            val activeRaw = input.readLong()
            val tabCount = input.readInt()
            require(tabCount in 0..limits.maxPersistedTabs) { "Invalid tab count" }
            val tabs = List(tabCount) { readTab(input, limits) }
            val closedCount = input.readInt()
            require(closedCount in 0..limits.maxClosedTabs) { "Invalid closed-tab count" }
            val closed = List(closedCount) {
                val title = input.readUTF().take(limits.maxTitleChars)
                val currentIndex = input.readInt()
                val entries = readEntries(input, limits)
                ClosedTabSnapshot(title, entries, currentIndex)
            }
            require(input.available() == 0) { "Trailing session bytes" }
            BrowserSessionSnapshot(activeRaw.takeIf { it > 0L }?.let(::BrowserTabId), tabs, closed)
        }
    }

    private fun writeTab(output: DataOutputStream, tab: PersistedTab) {
        output.writeLong(tab.id.value)
        output.writeInt(tab.currentIndex)
        output.writeBoolean(tab.pinned)
        writeEntries(output, tab.entries)
    }

    private fun readTab(input: DataInputStream, limits: BrowserShellLimits): PersistedTab {
        val id = BrowserTabId(input.readLong())
        val currentIndex = input.readInt()
        val pinned = input.readBoolean()
        val entries = readEntries(input, limits)
        return PersistedTab(id, entries, currentIndex, pinned)
    }

    private fun writeEntries(output: DataOutputStream, entries: List<BrowserHistoryEntry>) {
        output.writeInt(entries.size)
        entries.forEach { entry ->
            output.writeUTF(entry.url)
            output.writeUTF(entry.title.take(512))
            output.writeLong(entry.visitedAtMillis)
            output.writeInt(entry.transition.ordinal)
        }
    }

    private fun readEntries(input: DataInputStream, limits: BrowserShellLimits): List<BrowserHistoryEntry> {
        val count = input.readInt()
        require(count in 1..limits.maxHistoryEntriesPerTab) { "Invalid history count" }
        return List(count) {
            val url = input.readUTF()
            val title = input.readUTF().take(limits.maxTitleChars)
            val visitedAt = input.readLong()
            val ordinal = input.readInt()
            val transition = NavigationTransition.entries.getOrNull(ordinal) ?: NavigationTransition.RESTORE
            BrowserHistoryEntry(url, title, visitedAt, transition)
        }
    }
}
