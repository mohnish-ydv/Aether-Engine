package com.mohnishraj.aether.core.js

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JsRuntimeTest {
    @Test fun evaluatesArithmetic() {
        assertEquals(7.0, testJsEngine().evaluate("1+2*3;").numberValue())
    }
    @Test fun evaluatesUnaryOperators() {
        assertEquals(-2.0, testJsEngine().evaluate("-3 + +1;").numberValue())
    }
    @Test fun evaluatesBooleanNegation() {
        assertEquals(JsValue.BooleanValue(true), testJsEngine().evaluate("!0;").requireSuccess().value)
    }
    @Test fun evaluatesTypeofUndeclared() {
        assertEquals("undefined", testJsEngine().evaluate("typeof missing;").stringValue())
    }
    @Test fun concatenatesStrings() {
        assertEquals("A7true", testJsEngine().evaluate("'A'+7+true;").stringValue())
    }
    @Test fun supportsLooseEquality() {
        assertEquals(JsValue.BooleanValue(true), testJsEngine().evaluate("'2'==2;").requireSuccess().value)
    }
    @Test fun supportsStrictEquality() {
        assertEquals(JsValue.BooleanValue(false), testJsEngine().evaluate("'2'===2;").requireSuccess().value)
    }
    @Test fun shortCircuitsAnd() {
        assertEquals(0.0, testJsEngine().evaluate("0 && missing;").numberValue())
    }
    @Test fun shortCircuitsOr() {
        assertEquals(7.0, testJsEngine().evaluate("7 || missing;").numberValue())
    }
    @Test fun evaluatesConditional() {
        assertEquals(1.0, testJsEngine().evaluate("true?1:2;").numberValue())
    }
    @Test fun declaresLetAndAssigns() {
        assertEquals(4.0, testJsEngine().evaluate("let x=1;x+=3;x;").numberValue())
    }
    @Test fun protectsConst() {
        val result = testJsEngine().evaluate("const x=1;x=2;")
        assertFalse(result.success)
        assertEquals("TypeError", result.error?.kind)
    }
    @Test fun supportsVarFunctionScope() {
        assertEquals(3.0, testJsEngine().evaluate("function f(){if(true){var x=3;}return x;}f();").numberValue())
    }
    @Test fun isolatesBlockLet() {
        assertEquals(1.0, testJsEngine().evaluate("let x=1;{let x=2;}x;").numberValue())
    }
    @Test fun callsFunctionDeclaration() {
        assertEquals(42.0, testJsEngine().evaluate("function add(a,b){return a+b;}add(20,22);").numberValue())
    }
    @Test fun supportsRecursionWithinLimit() {
        assertEquals(120.0, testJsEngine().evaluate("function f(n){if(n<=1)return 1;return n*f(n-1);}f(5);").numberValue())
    }
    @Test fun capturesClosure() {
        assertEquals(42.0, testJsEngine().evaluate("function outer(x){return function(y){return x+y;};}outer(40)(2);").numberValue())
    }
    @Test fun exposesArgumentsArray() {
        assertEquals(3.0, testJsEngine().evaluate("function f(){return arguments.length;}f(1,2,3);").numberValue())
    }
    @Test fun readsAndWritesObjectProperties() {
        assertEquals(42.0, testJsEngine().evaluate("let o={x:40};o.x+=2;o['x'];").numberValue())
    }
    @Test fun supportsObjectShorthand() {
        assertEquals(9.0, testJsEngine().evaluate("let x=9;let o={x};o.x;").numberValue())
    }
    @Test fun readsAndWritesArrayIndexes() {
        assertEquals(9.0, testJsEngine().evaluate("let a=[1,2];a[1]=9;a[1];").numberValue())
    }
    @Test fun expandsArrayForSparseAssignment() {
        assertEquals(4.0, testJsEngine().evaluate("let a=[];a[3]=1;a.length;").numberValue())
    }
    @Test fun arrayPushAndPop() {
        assertEquals(3.0, testJsEngine().evaluate("let a=[1,2];a.push(3);a.pop();").numberValue())
    }
    @Test fun arrayJoin() {
        assertEquals("1-2-3", testJsEngine().evaluate("[1,2,3].join('-');").stringValue())
    }
    @Test fun arraySlice() {
        val value = testJsEngine().evaluate("[1,2,3,4].slice(1,3);").requireSuccess().value
        assertEquals(listOf<JsValue>(JsValue.NumberValue(2.0), JsValue.NumberValue(3.0)), assertIs<JsValue.ArrayValue>(value).elements)
    }
    @Test fun stringMethods() {
        assertEquals("ETHER", testJsEngine().evaluate("'aether'.substring(1).toUpperCase();").stringValue())
    }
    @Test fun executesIfElse() {
        assertEquals(2.0, testJsEngine().evaluate("let x=0;if(false)x=1;else x=2;x;").numberValue())
    }
    @Test fun executesWhileLoop() {
        assertEquals(10.0, testJsEngine().evaluate("let i=0,s=0;while(i<5){s+=i;i++;}s;").numberValue())
    }
    @Test fun executesForLoop() {
        assertEquals(10.0, testJsEngine().evaluate("let s=0;for(let i=0;i<5;i++)s+=i;s;").numberValue())
    }
    @Test fun handlesBreak() {
        assertEquals(3.0, testJsEngine().evaluate("let i=0;while(true){i++;if(i===3)break;}i;").numberValue())
    }
    @Test fun handlesContinue() {
        assertEquals(4.0, testJsEngine().evaluate("let s=0;for(let i=0;i<4;i++){if(i===2)continue;s+=i;}s;").numberValue())
    }
    @Test fun capturesConsoleOutput() {
        val result = testJsEngine().evaluate("console.log('answer',42);").requireSuccess()
        assertEquals(listOf("\"answer\" 42"), result.output)
    }
    @Test fun providesMathBuiltins() {
        assertEquals(42.0, testJsEngine().evaluate("Math.max(2,Math.sqrt(1764));").numberValue())
    }
    @Test fun providesConversionBuiltins() {
        assertEquals(42.0, testJsEngine().evaluate("Number('40')+parseInt('2px');").numberValue())
    }
    @Test fun stringifiesJson() {
        assertEquals("{\"a\":1,\"b\":[true,null]}", testJsEngine().evaluate("JSON.stringify({a:1,b:[true,null]});").stringValue())
    }
    @Test fun runsMicrotasksBeforeTimers() {
        val result = testJsEngine().evaluate("setTimeout(function(){console.log('timer');},0);queueMicrotask(function(){console.log('micro');});").requireSuccess()
        assertEquals(listOf("\"micro\"", "\"timer\""), result.output)
        assertEquals(2, result.tasksExecuted)
    }
    @Test fun clearsTimeout() {
        val result = testJsEngine().evaluate("let id=setTimeout(function(){console.log('bad');},0);clearTimeout(id);").requireSuccess()
        assertTrue(result.output.isEmpty())
    }
    @Test fun delayedTimerWaitsForVirtualTime() {
        val engine = testJsEngine()
        val first = engine.evaluate("setTimeout(function(){console.log('later');},10);", freshRealm = false).requireSuccess()
        assertTrue(first.output.isEmpty())
        assertEquals(1, engine.advanceTimeBy(10))
    }
    @Test fun reportsUndefinedIdentifier() {
        val result = testJsEngine().evaluate("missing;")
        assertFalse(result.success)
        assertEquals("ReferenceError", result.error?.kind)
    }
    @Test fun reportsCallingNonFunction() {
        val result = testJsEngine().evaluate("let x=1;x();")
        assertFalse(result.success)
        assertEquals("TypeError", result.error?.kind)
    }
    @Test fun returnsSyntaxErrorWithoutExecuting() {
        val result = testJsEngine().evaluate("let = 1;")
        assertFalse(result.success)
        assertEquals("SyntaxError", result.error?.kind)
    }
    @Test fun keepsPersistentRealmWhenRequested() {
        val engine = testJsEngine()
        val first = engine.evaluate("let x=40; console.log('first');", freshRealm = false).requireSuccess()
        assertEquals(listOf("\"first\""), first.output)
        val second = engine.evaluate("x+2;", freshRealm = false).requireSuccess()
        assertEquals(42.0, second.numberValue())
        assertTrue(second.output.isEmpty())
    }
    @Test fun freshRealmDoesNotLeakBindings() {
        val engine = testJsEngine()
        engine.evaluate("let x=1;").requireSuccess()
        assertFalse(engine.evaluate("x;").success)
    }
    @Test fun resetRealmClearsPersistentBindings() {
        val engine = testJsEngine()
        engine.evaluate("let x=1;", freshRealm = false).requireSuccess()
        engine.resetRealm()
        assertFalse(engine.evaluate("x;", freshRealm = false).success)
    }
    @Test fun statisticsTrackSuccessAndFailure() {
        val engine = testJsEngine()
        engine.evaluate("1;")
        engine.evaluate("missing;")
        val stats = engine.statistics()
        assertEquals(2, stats.scriptsEvaluated)
        assertEquals(1, stats.scriptsSucceeded)
        assertEquals(1, stats.scriptsFailed)
    }
}
