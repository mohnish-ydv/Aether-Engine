package com.mohnishraj.aether.core.log

import java.util.ArrayDeque
import com.mohnishraj.aether.core.time.EngineClock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

enum class LogLevel { TRACE, DEBUG, INFO, WARN, ERROR }

data class LogEntry(
    val sequence: Long,
    val timestampMillis: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwable: String? = null
) {
    fun format(): String = buildString {
        append('#').append(sequence).append(' ')
        append(level.name.padEnd(5)).append(' ')
        append('[').append(tag).append("] ").append(message)
        throwable?.let { append('\n').append(it) }
    }
}

fun interface LogSink { fun accept(entry: LogEntry) }

class EngineLogger(
    private val capacity: Int = 500,
    private val clock: EngineClock = EngineClock.SYSTEM
) {
    init { require(capacity > 0) }
    private val sequence = AtomicLong(0)
    private val lock = Any()
    private val entries = ArrayDeque<LogEntry>()
    private val sinks = CopyOnWriteArrayList<LogSink>()

    fun addSink(sink: LogSink) { sinks += sink }
    fun removeSink(sink: LogSink) { sinks -= sink }

    fun log(level: LogLevel, tag: String, message: String, throwable: Throwable? = null): LogEntry {
        val entry = LogEntry(
            sequence = sequence.incrementAndGet(),
            timestampMillis = clock.nowMillis(),
            level = level,
            tag = tag.take(48),
            message = message,
            throwable = throwable?.stackTraceToString()
        )
        synchronized(lock) {
            entries.addLast(entry)
            while (entries.size > capacity) entries.removeFirst()
        }
        sinks.forEach { runCatching { it.accept(entry) } }
        return entry
    }

    fun trace(tag: String, message: String) = log(LogLevel.TRACE, tag, message)
    fun debug(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun info(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun warn(tag: String, message: String, throwable: Throwable? = null) = log(LogLevel.WARN, tag, message, throwable)
    fun error(tag: String, message: String, throwable: Throwable? = null) = log(LogLevel.ERROR, tag, message, throwable)

    fun recent(limit: Int = 100, minimum: LogLevel = LogLevel.TRACE): List<LogEntry> = synchronized(lock) {
        entries.filter { it.level.ordinal >= minimum.ordinal }.takeLast(limit.coerceAtLeast(0))
    }

    fun clear() = synchronized(lock) { entries.clear() }
    fun size(): Int = synchronized(lock) { entries.size }
}
