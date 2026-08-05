package com.mohnishraj.aether.core.js

internal data class JsBinding(var value: JsValue, val mutable: Boolean)

class JsEnvironment internal constructor(
    private val parent: JsEnvironment? = null,
    private val functionBoundary: Boolean = false
) {
    private val bindings: MutableMap<String, JsBinding> = linkedMapOf()

    fun child(functionBoundary: Boolean = false): JsEnvironment = JsEnvironment(this, functionBoundary)

    fun declare(name: String, value: JsValue, mutable: Boolean, allowRedeclare: Boolean = false) {
        if (!allowRedeclare && bindings.containsKey(name)) throw JsRuntimeException("SyntaxError", "Identifier '$name' has already been declared")
        val existing = bindings[name]
        if (existing != null && allowRedeclare) {
            if (!existing.mutable) throw JsRuntimeException("TypeError", "Cannot redeclare constant '$name'")
            existing.value = value
        } else bindings[name] = JsBinding(value, mutable)
    }

    fun declareVar(name: String, value: JsValue) {
        val target = functionScope()
        target.declare(name, value, mutable = true, allowRedeclare = true)
    }

    fun get(name: String): JsValue {
        val binding = bindings[name]
        if (binding != null) return binding.value
        return parent?.get(name) ?: throw JsRuntimeException("ReferenceError", "$name is not defined")
    }

    fun getOrNull(name: String): JsValue? = bindings[name]?.value ?: parent?.getOrNull(name)

    fun assign(name: String, value: JsValue): JsValue {
        val binding = bindings[name]
        if (binding != null) {
            if (!binding.mutable) throw JsRuntimeException("TypeError", "Assignment to constant variable '$name'")
            binding.value = value
            return value
        }
        if (parent != null) return parent.assign(name, value)
        throw JsRuntimeException("ReferenceError", "$name is not defined")
    }

    fun snapshot(): Map<String, JsValue> = bindings.mapValues { it.value.value }

    internal fun defineHost(name: String, value: JsValue) {
        require(name.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*"))) { "Invalid host binding name: $name" }
        bindings[name] = JsBinding(value, mutable = false)
    }

    private fun functionScope(): JsEnvironment = if (functionBoundary || parent == null) this else parent.functionScope()
}
