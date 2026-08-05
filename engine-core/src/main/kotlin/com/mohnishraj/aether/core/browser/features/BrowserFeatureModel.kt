package com.mohnishraj.aether.core.browser.features

import com.mohnishraj.aether.core.fs.FileSystem

class BrowserFeatures(
    fileSystem: FileSystem,
    val bookmarks: BookmarkManager = BookmarkManager(fileSystem),
    val history: BrowsingHistory = BrowsingHistory(fileSystem),
    val reader: ReaderModeEngine = ReaderModeEngine(),
    val find: FindInPageEngine = FindInPageEngine()
)

enum class ImportDuplicatePolicy { SKIP, REPLACE, KEEP_BOTH }

data class ImportSummary(val imported: Int, val skipped: Int, val replaced: Int, val foldersCreated: Int)

enum class HistoryPeriod { TODAY, YESTERDAY, EARLIER_THIS_WEEK, OLDER }

data class HistoryGroup(val period: HistoryPeriod, val visits: List<HistoryVisit>)

enum class ReaderTheme { LIGHT, SEPIA, DARK }

data class ReaderSettings(
    val fontScale: Double = 1.0,
    val lineHeight: Double = 1.55,
    val theme: ReaderTheme = ReaderTheme.LIGHT
) {
    init {
        require(fontScale in 0.75..2.0)
        require(lineHeight in 1.0..2.5)
    }
}

data class ReaderHeading(val level: Int, val text: String)

data class ReaderArticle(
    val title: String,
    val byline: String?,
    val headings: List<ReaderHeading>,
    val paragraphs: List<String>,
    val wordCount: Int,
    val estimatedMinutes: Int
)

data class FindMatch(val start: Int, val endExclusive: Int)

data class FindResult(
    val query: String,
    val caseSensitive: Boolean,
    val matches: List<FindMatch>,
    val selectedIndex: Int
) {
    val count: Int get() = matches.size
    val current: FindMatch? get() = matches.getOrNull(selectedIndex)
}
