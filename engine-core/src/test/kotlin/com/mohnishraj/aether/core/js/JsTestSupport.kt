package com.mohnishraj.aether.core.js

import com.mohnishraj.aether.core.log.EngineLogger
import com.mohnishraj.aether.core.profile.PerformanceProfiler

internal fun testJsEngine(limits: JsLimits = JsLimits()): JsEngine = JsEngine(EngineLogger(), PerformanceProfiler(), limits)
internal fun JsEvaluationResult.requireSuccess(): JsEvaluationResult {
    check(success) { error?.pretty() ?: issues.joinToString("\n") }
    return this
}
internal fun JsEvaluationResult.numberValue(): Double = (requireSuccess().value as JsValue.NumberValue).value
internal fun JsEvaluationResult.stringValue(): String = (requireSuccess().value as JsValue.StringValue).value
