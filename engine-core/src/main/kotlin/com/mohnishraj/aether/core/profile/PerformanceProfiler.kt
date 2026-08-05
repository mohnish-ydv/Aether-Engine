package com.mohnishraj.aether.core.profile

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

data class MetricSnapshot(
    val name: String,
    val samples: Long,
    val lastNanos: Long,
    val minNanos: Long,
    val maxNanos: Long,
    val averageNanos: Double,
    val standardDeviationNanos: Double
)

class PerformanceProfiler(private val maxMetrics: Int = 256) {
    private class MetricSeries {
        private var count = 0L
        private var mean = 0.0
        private var m2 = 0.0
        private var min = Long.MAX_VALUE
        private var max = Long.MIN_VALUE
        private var last = 0L

        @Synchronized fun add(value: Long) {
            count++
            last = value
            min = minOf(min, value)
            max = maxOf(max, value)
            val delta = value - mean
            mean += delta / count
            m2 += delta * (value - mean)
        }

        @Synchronized fun snapshot(name: String): MetricSnapshot = MetricSnapshot(
            name = name,
            samples = count,
            lastNanos = last,
            minNanos = if (count == 0L) 0 else min,
            maxNanos = if (count == 0L) 0 else max,
            averageNanos = mean,
            standardDeviationNanos = if (count < 2) 0.0 else sqrt(m2 / (count - 1))
        )
    }

    private val metrics = ConcurrentHashMap<String, MetricSeries>()
    private val counters = ConcurrentHashMap<String, AtomicLong>()

    fun record(name: String, durationNanos: Long) {
        if (!metrics.containsKey(name) && metrics.size >= maxMetrics) return
        metrics.computeIfAbsent(name) { MetricSeries() }.add(durationNanos.coerceAtLeast(0))
    }

    inline fun <T> measure(name: String, block: () -> T): T {
        val start = System.nanoTime()
        return try { block() } finally { record(name, System.nanoTime() - start) }
    }

    fun increment(counter: String, amount: Long = 1): Long =
        counters.computeIfAbsent(counter) { AtomicLong() }.addAndGet(amount)

    fun metric(name: String): MetricSnapshot? = metrics[name]?.snapshot(name)
    fun snapshots(): List<MetricSnapshot> = metrics.map { (name, series) -> series.snapshot(name) }.sortedBy { it.name }
    fun counterSnapshots(): Map<String, Long> = counters.mapValues { it.value.get() }.toSortedMap()
    fun clear() { metrics.clear(); counters.clear() }
}
