package com.mohnishraj.aether.core.browser.events

import com.mohnishraj.aether.core.browser.BrowserApiCounters
import com.mohnishraj.aether.core.browser.BrowserApiLimits
import com.mohnishraj.aether.core.html.dom.DomNode
import java.util.concurrent.atomic.AtomicLong

enum class BrowserEventPhase { NONE, CAPTURING_PHASE, AT_TARGET, BUBBLING_PHASE }

data class EventListenerOptions(val capture: Boolean = false, val once: Boolean = false, val passive: Boolean = false)

class BrowserEvent(
    val type: String,
    val bubbles: Boolean = true,
    val cancelable: Boolean = true,
    val detail: Map<String, String> = emptyMap()
) {
    init { require(type.matches(Regex("[A-Za-z][A-Za-z0-9:_-]*"))) { "Invalid event type: $type" } }

    var target: DomNode? = null
        internal set
    var currentTarget: DomNode? = null
        internal set
    var eventPhase: BrowserEventPhase = BrowserEventPhase.NONE
        internal set
    var defaultPrevented: Boolean = false
        private set
    var propagationStopped: Boolean = false
        private set
    var immediatePropagationStopped: Boolean = false
        private set

    fun preventDefault() { if (cancelable) defaultPrevented = true }
    fun stopPropagation() { propagationStopped = true }
    fun stopImmediatePropagation() { immediatePropagationStopped = true; propagationStopped = true }
}

fun interface BrowserEventListener { fun handle(event: BrowserEvent) }

data class EventListenerHandle internal constructor(val id: Long)

class BrowserEventHub internal constructor(
    private val limits: BrowserApiLimits,
    private val counters: BrowserApiCounters
) {
    private data class Registration(
        val id: Long,
        val type: String,
        val listener: BrowserEventListener,
        val options: EventListenerOptions
    )

    private val ids = AtomicLong(1)
    private val registrations = linkedMapOf<Long, MutableList<Registration>>()
    private var listenerCount = 0

    @Synchronized fun addEventListener(
        target: DomNode,
        type: String,
        listener: BrowserEventListener,
        options: EventListenerOptions = EventListenerOptions()
    ): EventListenerHandle {
        require(listenerCount < limits.maxEventListeners) { "Event listener limit ${limits.maxEventListeners} reached" }
        val id = ids.getAndIncrement()
        registrations.getOrPut(target.nodeId) { mutableListOf() } += Registration(id, type, listener, options)
        listenerCount++
        return EventListenerHandle(id)
    }

    @Synchronized fun removeEventListener(target: DomNode, handle: EventListenerHandle): Boolean {
        val list = registrations[target.nodeId] ?: return false
        val removed = list.removeAll { it.id == handle.id }
        if (removed) listenerCount--
        if (list.isEmpty()) registrations.remove(target.nodeId)
        return removed
    }

    fun dispatch(target: DomNode, event: BrowserEvent): Boolean {
        val path = generateSequence(target.parent) { it.parent }.take(limits.maxEventPathDepth).toList()
        require(path.size < limits.maxEventPathDepth || path.lastOrNull()?.parent == null) { "Event path depth limit exceeded" }
        event.target = target
        for (node in path.asReversed()) {
            if (event.propagationStopped) break
            invoke(node, event, BrowserEventPhase.CAPTURING_PHASE, capture = true)
        }
        if (!event.propagationStopped) {
            invoke(target, event, BrowserEventPhase.AT_TARGET, capture = true)
            if (!event.immediatePropagationStopped) invoke(target, event, BrowserEventPhase.AT_TARGET, capture = false)
        }
        if (event.bubbles && !event.propagationStopped) {
            for (node in path) {
                if (event.propagationStopped) break
                invoke(node, event, BrowserEventPhase.BUBBLING_PHASE, capture = false)
            }
        }
        event.currentTarget = null
        event.eventPhase = BrowserEventPhase.NONE
        counters.eventsDispatched.incrementAndGet()
        return !event.defaultPrevented
    }

    @Synchronized fun listenerCount(): Int = listenerCount

    private fun invoke(target: DomNode, event: BrowserEvent, phase: BrowserEventPhase, capture: Boolean) {
        val snapshot = synchronized(this) { registrations[target.nodeId].orEmpty().filter { it.type == event.type && it.options.capture == capture } }
        event.currentTarget = target
        event.eventPhase = phase
        for (registration in snapshot) {
            if (event.immediatePropagationStopped) break
            registration.listener.handle(event)
            if (registration.options.once) removeEventListener(target, EventListenerHandle(registration.id))
        }
    }
}
