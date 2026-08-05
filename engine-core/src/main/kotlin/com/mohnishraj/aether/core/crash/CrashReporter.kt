package com.mohnishraj.aether.core.crash

import java.util.ArrayDeque
import com.mohnishraj.aether.core.text.Utf8String
import com.mohnishraj.aether.core.time.EngineClock
import java.util.Base64
import java.util.UUID

interface CrashReporter {
    fun capture(throwable: Throwable, context: Map<String, String> = emptyMap()): CrashEnvelope
    fun recent(limit: Int = 20): List<CrashEnvelope>
}

data class CrashEnvelope(
    val id: String,
    val timestampMillis: Long,
    val threadName: String,
    val exceptionType: String,
    val message: String,
    val stackTrace: String,
    val context: Map<String, String>
) {
    fun toJson(): String = buildString {
        append('{')
        append("\"id\":\"").append(Utf8String.escapeJson(id)).append("\",")
        append("\"timestampMillis\":").append(timestampMillis).append(',')
        append("\"threadName\":\"").append(Utf8String.escapeJson(threadName)).append("\",")
        append("\"exceptionType\":\"").append(Utf8String.escapeJson(exceptionType)).append("\",")
        append("\"message\":\"").append(Utf8String.escapeJson(message)).append("\",")
        append("\"stackTrace\":\"").append(Utf8String.escapeJson(stackTrace)).append("\",")
        append("\"context\":{")
        context.entries.forEachIndexed { index, entry ->
            if (index > 0) append(',')
            append('"').append(Utf8String.escapeJson(entry.key)).append("\":\"")
                .append(Utf8String.escapeJson(entry.value)).append('"')
        }
        append("}}")
    }
}

object CrashEnvelopeCodec {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private fun enc(value: String) = encoder.encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun dec(value: String) = String(decoder.decode(value), Charsets.UTF_8)

    fun encode(envelope: CrashEnvelope): String = buildString {
        appendLine("id=${enc(envelope.id)}")
        appendLine("time=${envelope.timestampMillis}")
        appendLine("thread=${enc(envelope.threadName)}")
        appendLine("type=${enc(envelope.exceptionType)}")
        appendLine("message=${enc(envelope.message)}")
        appendLine("stack=${enc(envelope.stackTrace)}")
        envelope.context.forEach { (key, value) -> appendLine("ctx.${enc(key)}=${enc(value)}") }
    }

    fun decode(serialized: String): CrashEnvelope {
        val values = linkedMapOf<String, String>()
        val context = linkedMapOf<String, String>()
        serialized.lineSequence().filter { '=' in it }.forEach { line ->
            val key = line.substringBefore('=')
            val value = line.substringAfter('=')
            if (key.startsWith("ctx.")) context[dec(key.removePrefix("ctx."))] = dec(value)
            else values[key] = value
        }
        return CrashEnvelope(
            id = dec(values.getValue("id")),
            timestampMillis = values.getValue("time").toLong(),
            threadName = dec(values.getValue("thread")),
            exceptionType = dec(values.getValue("type")),
            message = dec(values.getValue("message")),
            stackTrace = dec(values.getValue("stack")),
            context = context
        )
    }
}

class InMemoryCrashReporter(private val clock: EngineClock = EngineClock.SYSTEM) : CrashReporter {
    private val lock = Any()
    private val crashes = ArrayDeque<CrashEnvelope>()

    override fun capture(throwable: Throwable, context: Map<String, String>): CrashEnvelope {
        val envelope = CrashEnvelope(
            id = UUID.randomUUID().toString(),
            timestampMillis = clock.nowMillis(),
            threadName = Thread.currentThread().name,
            exceptionType = throwable::class.java.name,
            message = throwable.message.orEmpty(),
            stackTrace = throwable.stackTraceToString(),
            context = context.toSortedMap()
        )
        synchronized(lock) {
            crashes.addLast(envelope)
            while (crashes.size > 50) crashes.removeFirst()
        }
        return envelope
    }

    override fun recent(limit: Int): List<CrashEnvelope> = synchronized(lock) { crashes.toList().takeLast(limit.coerceAtLeast(0)) }
}
