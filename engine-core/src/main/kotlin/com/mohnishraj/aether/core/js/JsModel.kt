package com.mohnishraj.aether.core.js

import java.util.Collections
import java.util.Locale
import kotlin.math.floor

/** Source coordinate used by the lexer, parser and runtime diagnostics. */
data class JsSourcePosition(val offset: Int, val line: Int, val column: Int) {
    init {
        require(offset >= 0 && line >= 1 && column >= 1)
    }
}

data class JsSourceSpan(val start: JsSourcePosition, val end: JsSourcePosition) {
    init { require(end.offset >= start.offset) }
    companion object {
        val UNKNOWN = JsSourceSpan(JsSourcePosition(0, 1, 1), JsSourcePosition(0, 1, 1))
    }
}

enum class JsIssueSeverity { WARNING, ERROR }

data class JsIssue(
    val code: String,
    val message: String,
    val span: JsSourceSpan = JsSourceSpan.UNKNOWN,
    val severity: JsIssueSeverity = JsIssueSeverity.ERROR
) {
    override fun toString(): String = "${severity.name} $code @${span.start.line}:${span.start.column} — $message"
}

data class JsLimits(
    val maxSourceChars: Int = 1_000_000,
    val maxTokens: Int = 250_000,
    val maxAstNodes: Int = 250_000,
    val maxStatements: Int = 200_000,
    val maxSteps: Long = 2_000_000,
    val maxCallDepth: Int = 128,
    val maxCallArguments: Int = 1_024,
    val maxArrayElements: Int = 100_000,
    val maxObjectProperties: Int = 100_000,
    val maxStringChars: Int = 2_000_000,
    val maxTimers: Int = 4_096,
    val maxTasksPerDrain: Int = 10_000,
    val maxConsoleLines: Int = 10_000
) {
    init {
        require(maxSourceChars > 0 && maxTokens > 0 && maxAstNodes > 0 && maxStatements > 0)
        require(maxSteps > 0 && maxCallDepth > 0 && maxCallArguments > 0)
        require(maxArrayElements > 0 && maxObjectProperties > 0 && maxStringChars > 0)
        require(maxTimers > 0 && maxTasksPerDrain > 0 && maxConsoleLines > 0)
    }
}

sealed class JsValue {
    data object Undefined : JsValue()
    data object Null : JsValue()
    data class BooleanValue(val value: Boolean) : JsValue()
    data class NumberValue(val value: Double) : JsValue()
    data class StringValue(val value: String) : JsValue()
    class ArrayValue(elements: List<JsValue> = emptyList()) : JsValue() {
        val elements: MutableList<JsValue> = elements.toMutableList()
        val properties: MutableMap<String, JsValue> = linkedMapOf()
        override fun equals(other: Any?): Boolean = other is ArrayValue && elements == other.elements && properties == other.properties
        override fun hashCode(): Int = 31 * elements.hashCode() + properties.hashCode()
    }
    open class ObjectValue(properties: Map<String, JsValue> = emptyMap()) : JsValue() {
        val properties: MutableMap<String, JsValue> = linkedMapOf<String, JsValue>().apply { putAll(properties) }

        /**
         * Host objects override these hooks to expose live properties without copying native state into
         * the JavaScript heap. Returning null means the property is absent and evaluates to undefined.
         */
        open fun getProperty(context: JsExecutionContext, name: String): JsValue? = properties[name]

        /** Returns true when the assignment was accepted by the host object. */
        open fun setProperty(context: JsExecutionContext, name: String, value: JsValue): Boolean {
            properties[name] = value
            return true
        }

        open fun ownPropertyNames(): List<String> = properties.keys.toList()

        override fun equals(other: Any?): Boolean = other is ObjectValue && properties == other.properties
        override fun hashCode(): Int = properties.hashCode()
    }
    abstract class FunctionValue : JsValue() {
        abstract val name: String
        abstract val length: Int
        abstract fun call(context: JsExecutionContext, thisValue: JsValue, arguments: List<JsValue>): JsValue
    }

    fun typeName(): String = when (this) {
        Undefined -> "undefined"
        Null -> "object"
        is BooleanValue -> "boolean"
        is NumberValue -> "number"
        is StringValue -> "string"
        is ArrayValue, is ObjectValue -> "object"
        is FunctionValue -> "function"
    }

    fun isTruthy(): Boolean = when (this) {
        Undefined, Null -> false
        is BooleanValue -> value
        is NumberValue -> value != 0.0 && !value.isNaN()
        is StringValue -> value.isNotEmpty()
        is ArrayValue, is ObjectValue, is FunctionValue -> true
    }

    fun toNumber(): Double = when (this) {
        Undefined -> Double.NaN
        Null -> 0.0
        is BooleanValue -> if (value) 1.0 else 0.0
        is NumberValue -> value
        is StringValue -> {
            val trimmed = value.trim()
            when {
                trimmed.isEmpty() -> 0.0
                trimmed.startsWith("0x", ignoreCase = true) -> trimmed.drop(2).toLongOrNull(16)?.toDouble() ?: Double.NaN
                trimmed.startsWith("0b", ignoreCase = true) -> trimmed.drop(2).toLongOrNull(2)?.toDouble() ?: Double.NaN
                trimmed.startsWith("0o", ignoreCase = true) -> trimmed.drop(2).toLongOrNull(8)?.toDouble() ?: Double.NaN
                else -> trimmed.toDoubleOrNull() ?: Double.NaN
            }
        }
        is ArrayValue -> when (elements.size) {
            0 -> 0.0
            1 -> elements[0].toNumber()
            else -> Double.NaN
        }
        is ObjectValue, is FunctionValue -> Double.NaN
    }

    fun displayString(): String = when (this) {
        Undefined -> "undefined"
        Null -> "null"
        is BooleanValue -> value.toString()
        is NumberValue -> formatNumber(value)
        is StringValue -> value
        is ArrayValue -> elements.joinToString(",") { it.displayString() }
        is ObjectValue -> "[object Object]"
        is FunctionValue -> "function ${name.ifBlank { "anonymous" }}() { [aether code] }"
    }

    fun debugString(maxDepth: Int = 6): String = debugStringInternal(this, maxDepth, mutableSetOf())

    companion object {
        fun fromAny(value: Any?): JsValue = when (value) {
            null -> Null
            is JsValue -> value
            is Boolean -> BooleanValue(value)
            is Byte -> NumberValue(value.toDouble())
            is Short -> NumberValue(value.toDouble())
            is Int -> NumberValue(value.toDouble())
            is Long -> NumberValue(value.toDouble())
            is Float -> NumberValue(value.toDouble())
            is Double -> NumberValue(value)
            is String -> StringValue(value)
            is List<*> -> ArrayValue(value.map(::fromAny))
            is Map<*, *> -> ObjectValue(value.entries.associate { it.key.toString() to fromAny(it.value) })
            else -> StringValue(value.toString())
        }

        fun strictEquals(left: JsValue, right: JsValue): Boolean = when {
            left === Undefined && right === Undefined -> true
            left === Null && right === Null -> true
            left is BooleanValue && right is BooleanValue -> left.value == right.value
            left is NumberValue && right is NumberValue -> !left.value.isNaN() && !right.value.isNaN() && left.value == right.value
            left is StringValue && right is StringValue -> left.value == right.value
            else -> left === right
        }

        fun looseEquals(left: JsValue, right: JsValue): Boolean {
            if (strictEquals(left, right)) return true
            if ((left === Null && right === Undefined) || (left === Undefined && right === Null)) return true
            if (left is NumberValue && right is StringValue) return numberEquals(left.value, right.toNumber())
            if (left is StringValue && right is NumberValue) return numberEquals(left.toNumber(), right.value)
            if (left is BooleanValue) return looseEquals(NumberValue(left.toNumber()), right)
            if (right is BooleanValue) return looseEquals(left, NumberValue(right.toNumber()))
            if (left is NumberValue && (right is ArrayValue || right is ObjectValue)) return numberEquals(left.value, right.toNumber())
            if (right is NumberValue && (left is ArrayValue || left is ObjectValue)) return numberEquals(left.toNumber(), right.value)
            return false
        }

        private fun numberEquals(left: Double, right: Double): Boolean = !left.isNaN() && !right.isNaN() && left == right

        private fun formatNumber(value: Double): String = when {
            value.isNaN() -> "NaN"
            value == Double.POSITIVE_INFINITY -> "Infinity"
            value == Double.NEGATIVE_INFINITY -> "-Infinity"
            value == 0.0 -> "0"
            value == floor(value) && value in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble() -> value.toLong().toString()
            else -> String.format(Locale.US, "%.15g", value).replace("e+", "e")
        }

        private fun debugStringInternal(value: JsValue, depth: Int, seen: MutableSet<JsValue>): String {
            if (depth <= 0) return "…"
            return when (value) {
                Undefined, Null, is BooleanValue, is NumberValue -> value.displayString()
                is StringValue -> "\"${escape(value.value)}\""
                is FunctionValue -> "[Function ${value.name.ifBlank { "anonymous" }}]"
                is ArrayValue -> {
                    if (!seen.add(value)) return "[Circular]"
                    val rendered = value.elements.joinToString(", ") { debugStringInternal(it, depth - 1, seen) }
                    seen.remove(value)
                    "[$rendered]"
                }
                is ObjectValue -> {
                    if (!seen.add(value)) return "[Circular]"
                    val rendered = value.properties.entries.joinToString(", ") { (key, item) ->
                        "${if (key.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*"))) key else "\"${escape(key)}\""}: ${debugStringInternal(item, depth - 1, seen)}"
                    }
                    seen.remove(value)
                    "{$rendered}"
                }
            }
        }

        private fun escape(value: String): String = buildString {
            value.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(character)
                }
            }
        }
    }
}

class JsRuntimeException(
    val kind: String,
    override val message: String,
    val span: JsSourceSpan = JsSourceSpan.UNKNOWN,
    val frames: List<String> = emptyList()
) : RuntimeException(message) {
    fun pretty(): String = buildString {
        append("$kind: $message")
        if (span != JsSourceSpan.UNKNOWN) append(" at ${span.start.line}:${span.start.column}")
        frames.forEach { append("\n  at $it") }
    }
}

data class JsEvaluationResult(
    val success: Boolean,
    val value: JsValue,
    val output: List<String>,
    val issues: List<JsIssue>,
    val error: JsRuntimeException?,
    val tokenCount: Int,
    val astNodeCount: Int,
    val steps: Long,
    val tasksExecuted: Int,
    val elapsedNanos: Long
) {
    val outputText: String get() = output.joinToString("\n")
}

data class JsStatistics(
    val scriptsEvaluated: Long,
    val scriptsSucceeded: Long,
    val scriptsFailed: Long,
    val tokensProduced: Long,
    val astNodesProduced: Long,
    val stepsExecuted: Long,
    val tasksExecuted: Long,
    val lastEvaluationMillis: Double
)

internal fun <T> immutableCopy(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())
