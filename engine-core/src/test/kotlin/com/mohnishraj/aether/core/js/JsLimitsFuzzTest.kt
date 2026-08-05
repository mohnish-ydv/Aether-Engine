package com.mohnishraj.aether.core.js

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsLimitsFuzzTest {
    @Test fun stopsInfiniteLoopAtStepLimit() {
        val result = testJsEngine(JsLimits(maxSteps = 500)).evaluate("while(true){}")
        assertFalse(result.success)
        assertTrue(result.error?.message?.contains("step limit") == true)
    }
    @Test fun stopsDeepRecursionAtCallLimit() {
        val result = testJsEngine(JsLimits(maxCallDepth = 12)).evaluate("function f(){return f();}f();")
        assertFalse(result.success)
        assertTrue(result.error?.message?.contains("call depth") == true)
    }
    @Test fun limitsArrayGrowth() {
        val result = testJsEngine(JsLimits(maxArrayElements = 3)).evaluate("let a=[];a.push(1,2,3,4);")
        assertFalse(result.success)
    }
    @Test fun limitsObjectGrowth() {
        val result = testJsEngine(JsLimits(maxObjectProperties = 2)).evaluate("let o={};o.a=1;o.b=2;o.c=3;")
        assertFalse(result.success)
    }
    @Test fun limitsConsoleOutput() {
        val result = testJsEngine(JsLimits(maxConsoleLines = 2)).evaluate("console.log(1);console.log(2);console.log(3);")
        assertFalse(result.success)
    }
    @Test fun limitsTimerQueue() {
        val result = testJsEngine(JsLimits(maxTimers = 2)).evaluate("setTimeout(function(){},1);setTimeout(function(){},1);setTimeout(function(){},1);")
        assertFalse(result.success)
    }
    @Test fun malformedFuzzDoesNotEscapeTypedResult() {
        val random = Random(7007)
        repeat(2_000) {
            val alphabet = "abc123{}[]();,+-*/='\"!?: \n"
            val source = buildString { repeat(random.nextInt(0, 120)) { append(alphabet[random.nextInt(alphabet.length)]) } }
            val result = testJsEngine(JsLimits(maxSteps = 5_000, maxTokens = 2_000, maxAstNodes = 2_000)).evaluate(source)
            assertTrue(result.success || result.error != null)
        }
    }
    @Test fun deterministicProgramFuzzPreservesNumberResult() {
        val random = Random(42)
        repeat(1_000) {
            val a = random.nextInt(-1_000, 1_000)
            val b = random.nextInt(-1_000, 1_000)
            val result = testJsEngine().evaluate("let a=$a,b=$b;(a+b)-b;")
            assertTrue(result.success)
            assertTrue((result.value as JsValue.NumberValue).value == a.toDouble())
        }
    }
}
