package com.mohnishraj.aether.core.browser.features

import com.mohnishraj.aether.core.fs.MemoryFileSystem
import com.mohnishraj.aether.core.html.HtmlEngine
import com.mohnishraj.aether.core.shell.NavigationTransition
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserFeaturesTest {
    @Test fun bookmarksSupportFoldersSearchDuplicatesAndJsonRoundTrip() {
        val fileSystem = MemoryFileSystem()
        var now = 10L
        val manager = BookmarkManager(fileSystem, clockMillis = { now++ })
        val folder = manager.createFolder("Research")
        val first = manager.add("Aether", "https://example.com/docs/../docs", folder.id)
        val duplicate = manager.add("Duplicate title", "https://example.com/docs", folder.id)
        manager.add("News", "https://news.example.com/")

        assertEquals(first.id, duplicate.id)
        assertEquals(1, manager.search("AETHER").size)
        assertTrue(manager.isBookmarked("https://example.com/docs"))
        val exported = manager.exportJson()

        val imported = BookmarkManager(MemoryFileSystem(), clockMillis = { now++ })
        val summary = imported.importJson(exported)
        assertEquals(2, summary.imported)
        assertEquals(1, summary.foldersCreated)
        assertEquals("Research", imported.folders().single().name)
        assertEquals(setOf("Aether", "News"), imported.all().map { it.title }.toSet())
    }

    @Test fun bookmarkImportPolicyCanReplaceOrKeepBoth() {
        val fileSystem = MemoryFileSystem()
        val manager = BookmarkManager(fileSystem)
        manager.add("Old", "https://example.com/")
        val json = """{"version":1,"folders":[],"bookmarks":[{"id":9,"title":"New","url":"https://example.com/","folderId":null,"createdAtMillis":1,"modifiedAtMillis":2}]}"""

        assertEquals(1, manager.importJson(json, ImportDuplicatePolicy.REPLACE).replaced)
        assertEquals("New", manager.all().single().title)
        assertEquals(1, manager.importJson(json, ImportDuplicatePolicy.KEEP_BOTH).imported)
        assertEquals(2, manager.all().size)
    }

    @Test fun historySearchGroupingSelectedClearAndCountersAreVisitLevel() {
        val day = 86_400_000L
        val now = 10L * day
        val history = BrowsingHistory(MemoryFileSystem(), clockMillis = { now })
        val today = history.record("https://example.com", "Example", NavigationTransition.TYPED, now)
        history.record("https://example.com", "Example again", NavigationTransition.RELOAD, now - day)
        history.record("https://old.example", "Old", NavigationTransition.LINK, now - 9L * day)

        assertEquals(2, history.visitCount("https://example.com"))
        assertEquals(listOf(2, 1), history.search("example.com").filter { it.url == "https://example.com" }.map { it.visitNumberForUrl })
        val groups = history.groups(now, ZoneId.of("UTC"))
        assertEquals(listOf(HistoryPeriod.TODAY, HistoryPeriod.YESTERDAY, HistoryPeriod.OLDER), groups.map { it.period })
        assertEquals(1, history.clearSelected(setOf(today.id)))
        assertEquals(2, history.all().size)
        assertEquals(2, history.clearAll())
        assertTrue(history.all().isEmpty())
    }

    @Test fun readerExtractsArticleHeadingsBylineAndProgress() {
        val document = HtmlEngine().parse(
            """<html><head><title>Deep Aether</title></head><body><article><p class='byline'>By Mira</p><h1>Intro</h1><p>This is a sufficiently long paragraph used to verify reader extraction behavior and word counting.</p><h2>Details</h2><p>Another meaningful paragraph keeps the extracted article readable on a compact mobile display.</p></article></body></html>"""
        ).document
        val reader = ReaderModeEngine()
        val article = reader.extract(document, "https://example.com/article")

        assertEquals("Deep Aether", article.title)
        assertEquals("By Mira", article.byline)
        assertEquals(listOf(1, 2), article.headings.map { it.level })
        assertTrue(article.paragraphs.size >= 2)
        assertTrue(article.wordCount > 10)
        assertEquals(0.5, reader.progress(300.0, 1_000.0, 400.0))
        assertEquals(1.0, reader.progress(0.0, 400.0, 400.0))
    }

    @Test fun findInPageIsIncrementalCaseAwareAndWrapsSelection() {
        val engine = FindInPageEngine()
        val insensitive = engine.search("Aether aether AETHER", "aether")
        assertEquals(3, insensitive.count)
        assertEquals(1, engine.next(insensitive).selectedIndex)
        assertEquals(2, engine.previous(insensitive).selectedIndex)
        val sensitive = engine.search("Aether aether AETHER", "Aether", caseSensitive = true)
        assertEquals(1, sensitive.count)
        assertFalse(engine.search("abc", "z").current != null)
    }
}
