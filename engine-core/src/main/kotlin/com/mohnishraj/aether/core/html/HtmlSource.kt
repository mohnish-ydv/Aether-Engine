package com.mohnishraj.aether.core.html

data class SourcePosition(
    val offset: Int,
    val line: Int,
    val column: Int
) {
    init {
        require(offset >= 0) { "offset must be non-negative" }
        require(line >= 1) { "line must be at least 1" }
        require(column >= 1) { "column must be at least 1" }
    }

    override fun toString(): String = "$line:$column"
}

data class SourceSpan(val start: SourcePosition, val end: SourcePosition) {
    init {
        require(end.offset >= start.offset) { "span end must not precede start" }
    }

    val length: Int get() = end.offset - start.offset

    companion object {
        val EMPTY = SourceSpan(SourcePosition(0, 1, 1), SourcePosition(0, 1, 1))
    }
}

enum class HtmlIssueStage { TOKENIZER, TREE_BUILDER, LIMIT }
enum class HtmlIssueSeverity { WARNING, ERROR }

data class HtmlIssue(
    val stage: HtmlIssueStage,
    val code: String,
    val message: String,
    val position: SourcePosition,
    val severity: HtmlIssueSeverity = HtmlIssueSeverity.WARNING
) {
    override fun toString(): String = "${stage.name.lowercase()}:$code at $position — $message"
}

internal class SourceMap(private val source: String) {
    private val lineStarts: IntArray = buildList {
        add(0)
        source.forEachIndexed { index, char -> if (char == '\n') add(index + 1) }
    }.toIntArray()

    fun position(offset: Int): SourcePosition {
        val safe = offset.coerceIn(0, source.length)
        var low = 0
        var high = lineStarts.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (lineStarts[middle] <= safe) low = middle + 1 else high = middle - 1
        }
        val lineIndex = high.coerceAtLeast(0)
        return SourcePosition(safe, lineIndex + 1, safe - lineStarts[lineIndex] + 1)
    }

    fun span(start: Int, end: Int): SourceSpan = SourceSpan(position(start), position(end))
}

data class HtmlLimits(
    val maxInputChars: Int = 2_000_000,
    val maxTokens: Int = 200_000,
    val maxAttributesPerTag: Int = 256,
    val maxDepth: Int = 512,
    val maxNodes: Int = 200_000,
    val maxTextNodeChars: Int = 1_000_000
) {
    init {
        require(maxInputChars > 0)
        require(maxTokens > 0)
        require(maxAttributesPerTag > 0)
        require(maxDepth > 0)
        require(maxNodes > 0)
        require(maxTextNodeChars > 0)
    }
}
