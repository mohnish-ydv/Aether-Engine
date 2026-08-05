package com.mohnishraj.aether.core.browser.mutation

import com.mohnishraj.aether.core.browser.BrowserApiLimits
import com.mohnishraj.aether.core.html.dom.DomNode
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

enum class MutationType { ATTRIBUTES, CHARACTER_DATA, CHILD_LIST }

data class MutationRecord(
    val type: MutationType,
    val target: DomNode,
    val addedNodes: List<DomNode> = emptyList(),
    val removedNodes: List<DomNode> = emptyList(),
    val attributeName: String? = null,
    val oldValue: String? = null
)

data class MutationObserverOptions(
    val childList: Boolean = false,
    val attributes: Boolean = false,
    val characterData: Boolean = false,
    val subtree: Boolean = false,
    val attributeOldValue: Boolean = false,
    val characterDataOldValue: Boolean = false,
    val attributeFilter: Set<String> = emptySet()
) {
    init { require(childList || attributes || characterData) { "At least one mutation type must be observed" } }
}

fun interface MutationCallback { fun onMutations(records: List<MutationRecord>) }

class BrowserMutationObserver internal constructor(
    val id: Long,
    private val callback: MutationCallback,
    private val limits: BrowserApiLimits
) {
    private val records = ArrayDeque<MutationRecord>()
    internal var registration: MutationRegistration? = null

    internal fun enqueue(record: MutationRecord) {
        if (records.size >= limits.maxMutationRecordsPerObserver) records.removeFirst()
        records.addLast(record)
    }

    fun takeRecords(): List<MutationRecord> = buildList { while (records.isNotEmpty()) add(records.removeFirst()) }
    fun deliver(): Int {
        val batch = takeRecords()
        if (batch.isNotEmpty()) callback.onMutations(batch)
        return batch.size
    }
}

internal data class MutationRegistration(
    val target: DomNode,
    val options: MutationObserverOptions,
    val observer: BrowserMutationObserver
)

class BrowserMutationHub(private val limits: BrowserApiLimits) {
    private val ids = AtomicLong(1)
    private val observers = linkedMapOf<Long, BrowserMutationObserver>()

    @Synchronized fun observe(
        target: DomNode,
        options: MutationObserverOptions,
        callback: MutationCallback
    ): BrowserMutationObserver {
        require(observers.size < limits.maxMutationObservers) { "Mutation observer limit ${limits.maxMutationObservers} reached" }
        val observer = BrowserMutationObserver(ids.getAndIncrement(), callback, limits)
        observer.registration = MutationRegistration(target, options, observer)
        observers[observer.id] = observer
        return observer
    }

    @Synchronized fun disconnect(observer: BrowserMutationObserver) {
        observers.remove(observer.id)
        observer.registration = null
        observer.takeRecords()
    }

    fun notify(record: MutationRecord) {
        val snapshot = synchronized(this) { observers.values.toList() }
        snapshot.forEach { observer ->
            val registration = observer.registration ?: return@forEach
            if (!isTargetMatch(registration, record.target) || !isTypeMatch(registration.options, record)) return@forEach
            observer.enqueue(filterRecord(registration.options, record))
        }
    }

    fun deliverAll(): Int = synchronized(this) { observers.values.toList() }.sumOf { it.deliver() }
    @Synchronized fun observerCount(): Int = observers.size

    private fun isTargetMatch(registration: MutationRegistration, target: DomNode): Boolean =
        target === registration.target || (registration.options.subtree && generateSequence(target.parent) { it.parent }.any { it === registration.target })

    private fun isTypeMatch(options: MutationObserverOptions, record: MutationRecord): Boolean = when (record.type) {
        MutationType.CHILD_LIST -> options.childList
        MutationType.CHARACTER_DATA -> options.characterData
        MutationType.ATTRIBUTES -> options.attributes && (options.attributeFilter.isEmpty() || record.attributeName in options.attributeFilter)
    }

    private fun filterRecord(options: MutationObserverOptions, record: MutationRecord): MutationRecord = when (record.type) {
        MutationType.ATTRIBUTES -> if (options.attributeOldValue) record else record.copy(oldValue = null)
        MutationType.CHARACTER_DATA -> if (options.characterDataOldValue) record else record.copy(oldValue = null)
        MutationType.CHILD_LIST -> record
    }
}
