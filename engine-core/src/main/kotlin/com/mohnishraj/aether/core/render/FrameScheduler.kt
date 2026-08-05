package com.mohnishraj.aether.core.render

import java.util.LinkedHashSet

/** Deterministic vsync-like scheduler that coalesces many requests into one frame token. */
class FrameScheduler(targetFramesPerSecond: Int = 60) {
    private val frameIntervalNanos = 1_000_000_000L / targetFramesPerSecond.coerceIn(1, 240)
    private val lock = Any()
    private val causes = LinkedHashSet<InvalidationCause>()
    private var dueNanos: Long? = null
    private var token = 0L
    private var droppedRequests = 0L

    data class ScheduledFrame(val token: Long, val dueNanos: Long, val causes: Set<InvalidationCause>)

    fun request(cause: InvalidationCause, nowNanos: Long): Long = synchronized(lock) {
        require(nowNanos >= 0L)
        causes += cause
        if (dueNanos == null) {
            dueNanos = alignToNextFrame(nowNanos)
            token++
        } else {
            droppedRequests++
        }
        token
    }

    fun hasPendingFrame(): Boolean = synchronized(lock) { dueNanos != null }
    fun nextDueNanos(): Long? = synchronized(lock) { dueNanos }
    fun droppedRequestCount(): Long = synchronized(lock) { droppedRequests }

    fun consumeIfDue(nowNanos: Long): ScheduledFrame? = synchronized(lock) {
        val due = dueNanos ?: return@synchronized null
        if (nowNanos < due) return@synchronized null
        val frame = ScheduledFrame(token, due, causes.toSet())
        dueNanos = null
        causes.clear()
        frame
    }

    fun consumeNow(nowNanos: Long): ScheduledFrame? = synchronized(lock) {
        if (dueNanos == null) return@synchronized null
        val frame = ScheduledFrame(token, nowNanos, causes.toSet())
        dueNanos = null
        causes.clear()
        frame
    }

    fun cancel() {
        synchronized(lock) {
            dueNanos = null
            causes.clear()
        }
    }

    private fun alignToNextFrame(nowNanos: Long): Long {
        val remainder = nowNanos % frameIntervalNanos
        return if (remainder == 0L) nowNanos else nowNanos + (frameIntervalNanos - remainder)
    }
}
