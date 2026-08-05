package com.mohnishraj.aether.core.html

import com.mohnishraj.aether.core.html.parser.HtmlParseResult
import com.mohnishraj.aether.core.html.parser.HtmlParser
import com.mohnishraj.aether.core.log.EngineLogger
import com.mohnishraj.aether.core.profile.PerformanceProfiler
import java.util.concurrent.atomic.AtomicLong

class HtmlEngine(
    private val logger: EngineLogger? = null,
    private val profiler: PerformanceProfiler? = null,
    private val limits: HtmlLimits = HtmlLimits()
) {
    private val parseCount = AtomicLong()
    private val totalInputChars = AtomicLong()
    private val totalTokens = AtomicLong()
    private val totalNodes = AtomicLong()
    private val totalIssues = AtomicLong()
    private val lastElapsedNanos = AtomicLong()

    fun parse(source: String, documentUrl: String? = null): HtmlParseResult {
        val result = HtmlParser(limits).parse(source, documentUrl)
        parseCount.incrementAndGet()
        totalInputChars.addAndGet(source.length.toLong())
        totalTokens.addAndGet(result.tokenCount.toLong())
        totalNodes.addAndGet(result.nodeCount.toLong())
        totalIssues.addAndGet(result.issues.size.toLong())
        lastElapsedNanos.set(result.elapsedNanos)
        profiler?.record("html.parse", result.elapsedNanos)
        profiler?.increment("html.documents")
        profiler?.increment("html.tokens", result.tokenCount.toLong())
        profiler?.increment("html.nodes", result.nodeCount.toLong())
        if (result.issues.isNotEmpty()) {
            logger?.debug("HTML", "Parsed ${source.length} chars into ${result.nodeCount} nodes with ${result.issues.size} recoverable issue(s)")
        }
        return result
    }

    fun statistics(): HtmlEngineStats = HtmlEngineStats(
        documentsParsed = parseCount.get(),
        inputCharacters = totalInputChars.get(),
        tokensProduced = totalTokens.get(),
        nodesProduced = totalNodes.get(),
        parseIssues = totalIssues.get(),
        lastParseMillis = lastElapsedNanos.get() / 1_000_000.0
    )
}

data class HtmlEngineStats(
    val documentsParsed: Long,
    val inputCharacters: Long,
    val tokensProduced: Long,
    val nodesProduced: Long,
    val parseIssues: Long,
    val lastParseMillis: Double
)
