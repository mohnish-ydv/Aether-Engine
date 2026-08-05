package com.mohnishraj.aether.core.browser.features

import com.mohnishraj.aether.core.fs.FileSystem
import com.mohnishraj.aether.core.fs.VirtualPath
import java.net.URI
import java.util.Base64
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

data class BookmarkFolder(val id: Long, val name: String, val createdAtMillis: Long)

data class Bookmark(
    val id: Long,
    val title: String,
    val url: String,
    val folderId: Long?,
    val createdAtMillis: Long,
    val modifiedAtMillis: Long
)

class BookmarkManager(
    private val fileSystem: FileSystem,
    private val path: VirtualPath = VirtualPath.of("/browser/bookmarks.db"),
    private val clockMillis: () -> Long = System::currentTimeMillis
) {
    private val lock = Any()
    private val nextId = AtomicLong(0L)
    private val folders = linkedMapOf<Long, BookmarkFolder>()
    private val bookmarks = linkedMapOf<Long, Bookmark>()

    init { load() }

    fun folders(): List<BookmarkFolder> = synchronized(lock) { folders.values.sortedBy { it.name.lowercase(Locale.ROOT) } }
    fun all(): List<Bookmark> = synchronized(lock) { bookmarks.values.sortedByDescending(Bookmark::modifiedAtMillis) }

    fun createFolder(name: String): BookmarkFolder = synchronized(lock) {
        val clean = name.trim().take(120)
        require(clean.isNotEmpty()) { "Folder name is required" }
        folders.values.firstOrNull { it.name.equals(clean, ignoreCase = true) }?.let { return@synchronized it }
        val folder = BookmarkFolder(nextId.incrementAndGet(), clean, clockMillis())
        folders[folder.id] = folder
        persistLocked()
        folder
    }

    fun renameFolder(id: Long, name: String): BookmarkFolder = synchronized(lock) {
        val existing = folders[id] ?: error("Unknown bookmark folder $id")
        val clean = name.trim().take(120)
        require(clean.isNotEmpty()) { "Folder name is required" }
        require(folders.values.none { it.id != id && it.name.equals(clean, ignoreCase = true) }) { "Folder already exists" }
        existing.copy(name = clean).also { folders[id] = it; persistLocked() }
    }

    fun deleteFolder(id: Long, deleteBookmarks: Boolean = false): Boolean = synchronized(lock) {
        if (folders.remove(id) == null) return@synchronized false
        if (deleteBookmarks) bookmarks.entries.removeIf { it.value.folderId == id }
        else bookmarks.replaceAll { _, value -> if (value.folderId == id) value.copy(folderId = null, modifiedAtMillis = clockMillis()) else value }
        persistLocked()
        true
    }

    fun add(title: String, url: String, folderId: Long? = null): Bookmark = synchronized(lock) {
        val normalized = normalizeUrl(url)
        if (folderId != null) require(folders.containsKey(folderId)) { "Unknown bookmark folder $folderId" }
        bookmarks.values.firstOrNull { normalizeUrl(it.url) == normalized && it.folderId == folderId }?.let { return@synchronized it }
        val now = clockMillis()
        val bookmark = Bookmark(nextId.incrementAndGet(), sanitizeTitle(title, normalized), normalized, folderId, now, now)
        bookmarks[bookmark.id] = bookmark
        persistLocked()
        bookmark
    }

    fun edit(id: Long, title: String, url: String, folderId: Long?): Bookmark = synchronized(lock) {
        val existing = bookmarks[id] ?: error("Unknown bookmark $id")
        if (folderId != null) require(folders.containsKey(folderId)) { "Unknown bookmark folder $folderId" }
        val normalized = normalizeUrl(url)
        require(bookmarks.values.none { it.id != id && normalizeUrl(it.url) == normalized && it.folderId == folderId }) { "Duplicate bookmark" }
        existing.copy(
            title = sanitizeTitle(title, normalized),
            url = normalized,
            folderId = folderId,
            modifiedAtMillis = clockMillis()
        ).also { bookmarks[id] = it; persistLocked() }
    }

    fun delete(id: Long): Boolean = synchronized(lock) {
        val changed = bookmarks.remove(id) != null
        if (changed) persistLocked()
        changed
    }

    fun clearAll(): Int = synchronized(lock) {
        val removed = bookmarks.size
        bookmarks.clear()
        folders.clear()
        persistLocked()
        removed
    }

    fun search(query: String, folderId: Long? = null): List<Bookmark> = synchronized(lock) {
        val needle = query.trim().lowercase(Locale.ROOT)
        bookmarks.values.asSequence()
            .filter { folderId == null || it.folderId == folderId }
            .filter { needle.isEmpty() || it.title.lowercase(Locale.ROOT).contains(needle) || it.url.lowercase(Locale.ROOT).contains(needle) }
            .sortedByDescending(Bookmark::modifiedAtMillis)
            .toList()
    }

    fun isBookmarked(url: String): Boolean = synchronized(lock) {
        val normalized = runCatching { normalizeUrl(url) }.getOrNull() ?: return@synchronized false
        bookmarks.values.any { normalizeUrl(it.url) == normalized }
    }

    fun exportJson(): String = synchronized(lock) {
        buildString {
            append("{\n  \"version\": 1,\n  \"folders\": [")
            folders.values.forEachIndexed { index, folder ->
                if (index > 0) append(',')
                append("\n    {\"id\":").append(folder.id)
                    .append(",\"name\":\"").append(jsonEscape(folder.name)).append("\",\"createdAtMillis\":")
                    .append(folder.createdAtMillis).append('}')
            }
            if (folders.isNotEmpty()) append('\n').append("  ")
            append("],\n  \"bookmarks\": [")
            bookmarks.values.forEachIndexed { index, bookmark ->
                if (index > 0) append(',')
                append("\n    {\"id\":").append(bookmark.id)
                    .append(",\"title\":\"").append(jsonEscape(bookmark.title))
                    .append("\",\"url\":\"").append(jsonEscape(bookmark.url))
                    .append("\",\"folderId\":").append(bookmark.folderId?.toString() ?: "null")
                    .append(",\"createdAtMillis\":").append(bookmark.createdAtMillis)
                    .append(",\"modifiedAtMillis\":").append(bookmark.modifiedAtMillis).append('}')
            }
            if (bookmarks.isNotEmpty()) append('\n').append("  ")
            append("]\n}")
        }
    }

    fun importJson(json: String, policy: ImportDuplicatePolicy = ImportDuplicatePolicy.SKIP): ImportSummary = synchronized(lock) {
        require(json.length <= 5_000_000) { "Bookmark import is too large" }
        val folderMap = mutableMapOf<Long, Long>()
        var foldersCreated = 0
        extractArrayObjects(json, "folders").forEach { objectText ->
            val sourceId = longField(objectText, "id") ?: return@forEach
            val name = stringField(objectText, "name")?.trim().orEmpty()
            if (name.isEmpty()) return@forEach
            val folder = folders.values.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: run {
                foldersCreated++
                BookmarkFolder(nextId.incrementAndGet(), name.take(120), clockMillis()).also { folders[it.id] = it }
            }
            folderMap[sourceId] = folder.id
        }
        var imported = 0
        var skipped = 0
        var replaced = 0
        extractArrayObjects(json, "bookmarks").forEach { objectText ->
            val url = stringField(objectText, "url") ?: return@forEach
            val normalized = runCatching { normalizeUrl(url) }.getOrNull() ?: return@forEach
            val title = sanitizeTitle(stringField(objectText, "title").orEmpty(), normalized)
            val sourceFolder = nullableLongField(objectText, "folderId")
            val targetFolder = sourceFolder?.let(folderMap::get)
            val duplicate = bookmarks.values.firstOrNull { normalizeUrl(it.url) == normalized && it.folderId == targetFolder }
            when {
                duplicate == null || policy == ImportDuplicatePolicy.KEEP_BOTH -> {
                    val now = clockMillis()
                    val nextTitle = if (duplicate == null) title else "$title (imported)"
                    val added = Bookmark(nextId.incrementAndGet(), nextTitle.take(512), normalized, targetFolder, now, now)
                    bookmarks[added.id] = added
                    imported++
                }
                policy == ImportDuplicatePolicy.REPLACE -> {
                    bookmarks[duplicate.id] = duplicate.copy(title = title, modifiedAtMillis = clockMillis())
                    replaced++
                }
                else -> skipped++
            }
        }
        persistLocked()
        ImportSummary(imported, skipped, replaced, foldersCreated)
    }

    private fun load() = synchronized(lock) {
        if (!fileSystem.exists(path)) return@synchronized
        runCatching {
            fileSystem.read(path).toString(Charsets.UTF_8).lineSequence().forEach { line ->
                val parts = line.split('|')
                when (parts.firstOrNull()) {
                    "F" -> if (parts.size == 4) {
                        val folder = BookmarkFolder(parts[1].toLong(), decode(parts[2]), parts[3].toLong())
                        folders[folder.id] = folder
                        nextId.set(maxOf(nextId.get(), folder.id))
                    }
                    "B" -> if (parts.size == 7) {
                        val bookmark = Bookmark(parts[1].toLong(), decode(parts[2]), decode(parts[3]), parts[4].takeIf(String::isNotEmpty)?.toLong(), parts[5].toLong(), parts[6].toLong())
                        bookmarks[bookmark.id] = bookmark
                        nextId.set(maxOf(nextId.get(), bookmark.id))
                    }
                }
            }
        }.onFailure { folders.clear(); bookmarks.clear(); nextId.set(0L) }
    }

    private fun persistLocked() {
        val text = buildString {
            folders.values.forEach { append("F|").append(it.id).append('|').append(encode(it.name)).append('|').append(it.createdAtMillis).append('\n') }
            bookmarks.values.forEach {
                append("B|").append(it.id).append('|').append(encode(it.title)).append('|').append(encode(it.url)).append('|')
                    .append(it.folderId?.toString().orEmpty()).append('|').append(it.createdAtMillis).append('|').append(it.modifiedAtMillis).append('\n')
            }
        }
        fileSystem.write(path, text.toByteArray(Charsets.UTF_8))
    }

    private fun sanitizeTitle(title: String, fallback: String): String = title.trim().ifEmpty { fallback }.take(512)

    private fun normalizeUrl(raw: String): String {
        val input = raw.trim()
        require(input.isNotEmpty()) { "Bookmark URL is required" }
        val uri = URI(input)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme in setOf("http", "https", "about", "data", "file")) { "Unsupported bookmark scheme" }
        if (scheme in setOf("http", "https")) require(!uri.host.isNullOrBlank()) { "Bookmark host is required" }
        return uri.normalize().toString()
    }

    companion object {
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()
        private fun encode(value: String): String = encoder.encodeToString(value.toByteArray(Charsets.UTF_8))
        private fun decode(value: String): String = decoder.decode(value).toString(Charsets.UTF_8)
        private fun jsonEscape(value: String): String = buildString {
            value.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
                }
            }
        }
        private fun extractArrayObjects(json: String, name: String): List<String> {
            val marker = Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*\\[").find(json) ?: return emptyList()
            var index = marker.range.last + 1
            var depth = 0
            var inString = false
            var escaped = false
            var start = -1
            val result = mutableListOf<String>()
            while (index < json.length) {
                val ch = json[index]
                if (inString) {
                    if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') inString = false
                } else {
                    when (ch) {
                        '"' -> inString = true
                        '{' -> { if (depth == 0) start = index; depth++ }
                        '}' -> { depth--; if (depth == 0 && start >= 0) result += json.substring(start, index + 1) }
                        ']' -> if (depth == 0) break
                    }
                }
                index++
            }
            return result
        }
        private fun stringField(objectText: String, name: String): String? {
            val match = Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"").find(objectText) ?: return null
            return jsonUnescape(match.groupValues[1])
        }
        private fun longField(objectText: String, name: String): Long? =
            Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*(-?\\d+)").find(objectText)?.groupValues?.get(1)?.toLongOrNull()
        private fun nullableLongField(objectText: String, name: String): Long? = longField(objectText, name)
        private fun jsonUnescape(value: String): String {
            val result = StringBuilder()
            var index = 0
            while (index < value.length) {
                val ch = value[index]
                if (ch != '\\' || index + 1 >= value.length) result.append(ch) else {
                    when (val next = value[++index]) {
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000c')
                        '\\', '/', '"' -> result.append(next)
                        'u' -> {
                            val end = (index + 5).coerceAtMost(value.length)
                            val hex = value.substring(index + 1, end)
                            result.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                            index += 4
                        }
                        else -> result.append(next)
                    }
                }
                index++
            }
            return result.toString()
        }
    }
}
