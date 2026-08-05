package com.mohnishraj.aether.core.css

import com.mohnishraj.aether.core.css.cascade.CascadeEngine
import com.mohnishraj.aether.core.css.parser.CssParser
import com.mohnishraj.aether.core.css.parser.CssStyleSheet
import com.mohnishraj.aether.core.html.dom.DocumentNode
import com.mohnishraj.aether.core.log.EngineLogger
import com.mohnishraj.aether.core.profile.PerformanceProfiler
import java.util.concurrent.atomic.AtomicLong

class CssEngine(
    private val logger: EngineLogger? = null,
    private val profiler: PerformanceProfiler? = null,
    private val limits: CssLimits = CssLimits()
) {
    private val parser = CssParser(limits)
    private val cascade = CascadeEngine(limits)
    private val sheetsParsed = AtomicLong()
    private val rulesParsed = AtomicLong()
    private val declarationsParsed = AtomicLong()
    private val elementsStyled = AtomicLong()
    private val issuesSeen = AtomicLong()
    private val lastElapsedNanos = AtomicLong()

    fun parse(source: String, sourceUrl: String? = null, origin: CssOrigin = CssOrigin.AUTHOR): CssStyleSheet {
        val started = System.nanoTime()
        return try {
            val sheet = parser.parse(source, sourceUrl, origin)
            val ruleCount = countRules(sheet.rules)
            val declarationCount = countDeclarations(sheet.rules)
            sheetsParsed.incrementAndGet()
            rulesParsed.addAndGet(ruleCount.toLong())
            declarationsParsed.addAndGet(declarationCount.toLong())
            issuesSeen.addAndGet(sheet.issues.size.toLong())
            profiler?.increment("css.stylesheets")
            profiler?.increment("css.rules", ruleCount.toLong())
            logger?.debug("CSS", "Parsed stylesheet rules=$ruleCount declarations=$declarationCount issues=${sheet.issues.size}")
            sheet
        } finally {
            val elapsed = System.nanoTime() - started
            lastElapsedNanos.set(elapsed)
            profiler?.record("css.parse", elapsed)
        }
    }

    fun compute(
        document: DocumentNode,
        styleSheets: List<CssStyleSheet>,
        environment: MediaEnvironment = MediaEnvironment()
    ): StyleTree {
        val started = System.nanoTime()
        return try {
            val tree = cascade.compute(document, styleSheets, environment)
            elementsStyled.addAndGet(tree.size.toLong())
            issuesSeen.addAndGet(tree.issues.size.toLong())
            profiler?.increment("css.elementsStyled", tree.size.toLong())
            logger?.debug("CSS", "Computed styles elements=${tree.size} fonts=${tree.fontFaces.size} issues=${tree.issues.size}")
            tree
        } finally {
            val elapsed = System.nanoTime() - started
            lastElapsedNanos.set(elapsed)
            profiler?.record("css.cascade", elapsed)
        }
    }

    fun parseAndCompute(
        document: DocumentNode,
        source: String,
        environment: MediaEnvironment = MediaEnvironment(),
        sourceUrl: String? = null
    ): Pair<CssStyleSheet, StyleTree> {
        val sheet = parse(source, sourceUrl)
        return sheet to compute(document, listOf(sheet), environment)
    }

    fun statistics(): CssEngineStats = CssEngineStats(
        styleSheetsParsed = sheetsParsed.get(),
        rulesParsed = rulesParsed.get(),
        declarationsParsed = declarationsParsed.get(),
        elementsStyled = elementsStyled.get(),
        issuesSeen = issuesSeen.get(),
        lastOperationMillis = lastElapsedNanos.get() / 1_000_000.0
    )

    private fun countRules(rules: List<com.mohnishraj.aether.core.css.parser.CssRule>): Int = rules.sumOf { rule ->
        1 + when (rule) {
            is com.mohnishraj.aether.core.css.parser.MediaRule -> countRules(rule.rules)
            is com.mohnishraj.aether.core.css.parser.SupportsRule -> countRules(rule.rules)
            else -> 0
        }
    }

    private fun countDeclarations(rules: List<com.mohnishraj.aether.core.css.parser.CssRule>): Int = rules.sumOf { rule ->
        when (rule) {
            is com.mohnishraj.aether.core.css.parser.StyleRule -> rule.declarations.size
            is com.mohnishraj.aether.core.css.parser.FontFaceRule -> rule.declarations.size
            is com.mohnishraj.aether.core.css.parser.MediaRule -> countDeclarations(rule.rules)
            is com.mohnishraj.aether.core.css.parser.SupportsRule -> countDeclarations(rule.rules)
        }
    }
}

data class CssEngineStats(
    val styleSheetsParsed: Long,
    val rulesParsed: Long,
    val declarationsParsed: Long,
    val elementsStyled: Long,
    val issuesSeen: Long,
    val lastOperationMillis: Double
)
