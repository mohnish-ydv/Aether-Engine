package com.mohnishraj.aether.core.js

import com.mohnishraj.aether.core.log.EngineLogger
import com.mohnishraj.aether.core.profile.PerformanceProfiler
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class JsEngine(
    private val logger: EngineLogger,
    private val profiler: PerformanceProfiler,
    private val limits: JsLimits = JsLimits()
) {
    private val scriptsEvaluated = AtomicLong()
    private val scriptsSucceeded = AtomicLong()
    private val scriptsFailed = AtomicLong()
    private val tokensProduced = AtomicLong()
    private val astNodesProduced = AtomicLong()
    private val stepsExecuted = AtomicLong()
    private val tasksExecuted = AtomicLong()
    private val lastEvaluationNanos = AtomicLong()
    private val lastRealm = AtomicReference<JsRealm?>()

    @Synchronized
    fun evaluate(
        source: String,
        sourceName: String = "aether://script",
        drainTasks: Boolean = true,
        freshRealm: Boolean = true,
        hostGlobals: Map<String, JsValue> = emptyMap()
    ): JsEvaluationResult {
        val started = System.nanoTime()
        scriptsEvaluated.incrementAndGet()
        val realm = if (freshRealm || lastRealm.get() == null) JsRealm(limits).also { JsBuiltins.install(it); lastRealm.set(it) } else lastRealm.get()!!
        hostGlobals.forEach { (name, value) -> realm.global.defineHost(name, value) }
        realm.output.clear()
        val parse = JsParser.parse(source, limits)
        tokensProduced.addAndGet(parse.tokenCount.toLong())
        astNodesProduced.addAndGet(parse.astNodeCount.toLong())
        var interpreter: JsInterpreter? = null
        var tasks = 0
        val result = if (parse.hasErrors) {
            val first = parse.issues.first { it.severity == JsIssueSeverity.ERROR }
            val error = JsRuntimeException("SyntaxError", first.message, first.span)
            scriptsFailed.incrementAndGet()
            JsEvaluationResult(false, JsValue.Undefined, immutableCopy(realm.output), parse.issues, error, parse.tokenCount, parse.astNodeCount, 0L, 0, 0L)
        } else {
            interpreter = JsInterpreter(realm)
            runCatching {
                val value = interpreter.execute(parse.program)
                if (drainTasks) tasks = interpreter.drainTasks()
                scriptsSucceeded.incrementAndGet()
                JsEvaluationResult(true, value, immutableCopy(realm.output), parse.issues, null, parse.tokenCount, parse.astNodeCount, interpreter.stepsExecuted(), tasks, 0L)
            }.getOrElse { failure ->
                val error = when (failure) {
                    is JsRuntimeException -> failure
                    is JsThrownValue -> JsRuntimeException("Uncaught", failure.value.displayString())
                    else -> JsRuntimeException("InternalError", failure.message ?: failure::class.java.simpleName)
                }
                scriptsFailed.incrementAndGet()
                logger.warn("JavaScript", "$sourceName failed: ${error.pretty()}")
                JsEvaluationResult(false, JsValue.Undefined, immutableCopy(realm.output), parse.issues, error, parse.tokenCount, parse.astNodeCount, interpreter.stepsExecuted(), tasks, 0L)
            }
        }
        val elapsed = System.nanoTime() - started
        lastEvaluationNanos.set(elapsed)
        stepsExecuted.addAndGet(interpreter?.stepsExecuted() ?: 0L)
        tasksExecuted.addAndGet(tasks.toLong())
        profiler.record("js.evaluate", elapsed)
        profiler.increment("js.scripts")
        if (result.success) profiler.increment("js.successes") else profiler.increment("js.failures")
        logger.debug("JavaScript", "$sourceName success=${result.success} tokens=${result.tokenCount} nodes=${result.astNodeCount} steps=${result.steps}")
        return result.copy(elapsedNanos = elapsed)
    }

    fun compile(source: String): JsParseResult = profiler.measure("js.compile") { JsParser.parse(source, limits) }

    @Synchronized
    fun advanceTimeBy(millis: Long): Int {
        val realm = lastRealm.get() ?: return 0
        realm.tasks.advanceBy(millis)
        val interpreter = JsInterpreter(realm)
        val count = interpreter.drainTasks()
        tasksExecuted.addAndGet(count.toLong())
        stepsExecuted.addAndGet(interpreter.stepsExecuted())
        return count
    }

    @Synchronized
    fun resetRealm() { lastRealm.set(null) }

    @Synchronized
    fun hasPendingTasks(): Boolean = lastRealm.get()?.tasks?.hasPendingTasks() == true

    @Synchronized
    fun nextTaskDelayMillis(): Long? = lastRealm.get()?.tasks?.nextDelayMillis()

    fun limitsSnapshot(): JsLimits = limits

    fun statistics(): JsStatistics = JsStatistics(
        scriptsEvaluated.get(), scriptsSucceeded.get(), scriptsFailed.get(), tokensProduced.get(), astNodesProduced.get(),
        stepsExecuted.get(), tasksExecuted.get(), lastEvaluationNanos.get() / 1_000_000.0
    )
}
