package com.mohnishraj.aether.core.render

import kotlin.math.abs
import kotlin.math.hypot

class ScrollController(private val durationMillis: Long = 280L) {
    private var viewportWidth = 0.0
    private var viewportHeight = 0.0
    private var contentWidth = 0.0
    private var contentHeight = 0.0
    private var position = ScrollOffset()
    private var start = position
    private var target = position
    private var animationStartNanos = 0L
    private var animationDurationNanos = durationMillis * 1_000_000L
    private var animating = false

    data class Update(val position: ScrollOffset, val changed: Boolean, val animating: Boolean)

    fun setExtents(contentWidth: Double, contentHeight: Double, viewportWidth: Double, viewportHeight: Double): Update {
        require(listOf(contentWidth, contentHeight, viewportWidth, viewportHeight).all { it >= 0.0 && it.isFinite() })
        this.contentWidth = contentWidth
        this.contentHeight = contentHeight
        this.viewportWidth = viewportWidth
        this.viewportHeight = viewportHeight
        val clamped = clamp(position)
        val changed = clamped != position
        position = clamped
        target = clamp(target)
        return Update(position, changed, animating)
    }

    fun current(): ScrollOffset = position
    fun target(): ScrollOffset = target
    fun isAnimating(): Boolean = animating

    fun scrollTo(x: Double, y: Double, behavior: ScrollBehavior, nowNanos: Long): Update {
        require(x.isFinite() && y.isFinite() && nowNanos >= 0L)
        val destination = clamp(ScrollOffset(x, y))
        if (behavior == ScrollBehavior.INSTANT || destination == position) {
            val changed = destination != position
            position = destination
            start = destination
            target = destination
            animating = false
            return Update(position, changed, false)
        }
        start = position
        target = destination
        animationStartNanos = nowNanos
        val distance = hypot(target.x - start.x, target.y - start.y)
        val distanceFactor = (distance / 1_000.0).coerceIn(0.45, 1.75)
        animationDurationNanos = (durationMillis * distanceFactor * 1_000_000.0).toLong().coerceAtLeast(1L)
        animating = true
        return Update(position, changed = false, animating = true)
    }

    fun scrollBy(dx: Double, dy: Double, behavior: ScrollBehavior, nowNanos: Long): Update =
        scrollTo(position.x + dx, position.y + dy, behavior, nowNanos)

    fun advance(nowNanos: Long): Update {
        require(nowNanos >= 0L)
        if (!animating) return Update(position, changed = false, animating = false)
        val previous = position
        val elapsed = (nowNanos - animationStartNanos).coerceAtLeast(0L)
        val progress = (elapsed.toDouble() / animationDurationNanos.toDouble()).coerceIn(0.0, 1.0)
        val eased = 1.0 - (1.0 - progress) * (1.0 - progress) * (1.0 - progress)
        position = ScrollOffset(
            start.x + (target.x - start.x) * eased,
            start.y + (target.y - start.y) * eased
        )
        if (progress >= 1.0 || (abs(position.x - target.x) < 0.001 && abs(position.y - target.y) < 0.001)) {
            position = target
            animating = false
        }
        return Update(position, changed = previous != position, animating = animating)
    }

    fun cancel() {
        target = position
        start = position
        animating = false
    }

    private fun clamp(offset: ScrollOffset): ScrollOffset = ScrollOffset(
        x = offset.x.coerceIn(0.0, (contentWidth - viewportWidth).coerceAtLeast(0.0)),
        y = offset.y.coerceIn(0.0, (contentHeight - viewportHeight).coerceAtLeast(0.0))
    )
}
