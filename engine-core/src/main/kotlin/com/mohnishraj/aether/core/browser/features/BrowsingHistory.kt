package com.mohnishraj.aether.core.browser.features

import com.mohnishraj.aether.core.fs.FileSystem
import com.mohnishraj.aether.core.fs.VirtualPath
import com.mohnishraj.aether.core.shell.NavigationTransition
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

data class HistoryVisit(
    val id: Long,
    val url: String,
    val title: String,
    val visitedAtMillis: Long,
    val transition: NavigationTransition,
    val visitNumberForUrl: Int
)

class BrowsingHistory(
    private val fileSystem: FileSystem,
    private val path: VirtualPath = VirtualPath.of("/browser/history.db"),
    private val maxEntries: Int = 20_000,
    private val clockMillis: () -> Long = System::currentTimeMillis
) {
    private val lock = Any()
    private val nextId = AtomicLong(0L)
    private val visits = arrayListOf<HistoryVisit>()

    init { require(maxEntries > 0); load() }

    fun record(url: String, title: String, transition: NavigationTransition, atMillis: Long = clockMillis()): HistoryVisit = synchronized(lock) {
        require(url.isNotBlank())
        val count = (visits.asSequence().filter { it.url == url }.maxOfOrNull(HistoryVisit::visitNumberForUrl) ?: 0) + 1
        val visit = HistoryVisit(nextId.incrementAndGet(), url, title.take(512), atMillis.coerceAtLeast(0L), transition, count)
        visits += visit
        if (visits.size > maxEntries) visits.subList(0, visits.size - maxEntries).clear()
        persistLocked()
        visit
    }

    fun all(): List<HistoryVisit> = synchronized(lock) { visits.asReversed().toList() }

    fun search(query: String): List<HistoryVisit> = synchronized(lock) {
        val needle = query.trim().lowercase(Locale.ROOT)
        visits.asReversed().filter { needle.isEmpty() || it.url.lowercase(Locale.ROOT).contains(needle) || it.title.lowercase(Locale.ROOT).contains(needle) }
    }

    fun visitCount(url: String): Int = synchronized(lock) { visits.count { it.url == url } }

    fun clearSelected(ids: Set<Long>): Int = synchronized(lock) {
        if (ids.isEmpty()) return@synchronized 0
        val before = visits.size
        visits.removeAll { it.id in ids }
        val removed = before - visits.size
        if (removed > 0) persistLocked()
        removed
    }

    fun clearAll(): Int = synchronized(lock) {
        val removed = visits.size
        visits.clear()
        persistLocked()
        removed
    }

    fun groups(nowMillis: Long = clockMillis(), zoneId: ZoneId = ZoneId.systemDefault()): List<HistoryGroup> = synchronized(lock) {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        visits.asReversed().groupBy { visit ->
            val date = Instant.ofEpochMilli(visit.visitedAtMillis).atZone(zoneId).toLocalDate()
            val days = ChronoUnit.DAYS.between(date, today)
            when {
                days <= 0 -> HistoryPeriod.TODAY
                days == 1L -> HistoryPeriod.YESTERDAY
                days in 2..6 -> HistoryPeriod.EARLIER_THIS_WEEK
                else -> HistoryPeriod.OLDER
            }
        }.let { grouped -> HistoryPeriod.entries.mapNotNull { period -> grouped[period]?.let { HistoryGroup(period, it) } } }
    }

    private fun load() = synchronized(lock) {
        if (!fileSystem.exists(path)) return@synchronized
        runCatching {
            fileSystem.read(path).toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).forEach { line ->
                val parts = line.split('|')
                if (parts.size != 7 || parts[0] != "H") return@forEach
                val visit = HistoryVisit(parts[1].toLong(), decode(parts[2]), decode(parts[3]), parts[4].toLong(), NavigationTransition.valueOf(parts[5]), parts[6].toInt())
                visits += visit
                nextId.set(maxOf(nextId.get(), visit.id))
            }
            if (visits.size > maxEntries) visits.subList(0, visits.size - maxEntries).clear()
        }.onFailure { visits.clear(); nextId.set(0L) }
    }

    private fun persistLocked() {
        val encoded = visits.joinToString("\n", postfix = if (visits.isEmpty()) "" else "\n") {
            "H|${it.id}|${encode(it.url)}|${encode(it.title)}|${it.visitedAtMillis}|${it.transition.name}|${it.visitNumberForUrl}"
        }
        fileSystem.write(path, encoded.toByteArray(Charsets.UTF_8))
    }

    companion object {
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()
        private fun encode(value: String): String = encoder.encodeToString(value.toByteArray(Charsets.UTF_8))
        private fun decode(value: String): String = decoder.decode(value).toString(Charsets.UTF_8)
    }
}
