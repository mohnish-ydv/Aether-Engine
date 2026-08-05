package com.mohnishraj.aether.core.js

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsModernRuntimeTest {
    @Test fun arrowFunctionsTransformArrays() {
        assertEquals("2,4,6", testJsEngine().evaluate("[1,2,3].map(x=>x*2).join(',');").stringValue())
    }

    @Test fun parenthesizedArrowFunctionsCaptureClosures() {
        assertEquals(42.0, testJsEngine().evaluate("let add=(a,b)=>a+b; add(40,2);").numberValue())
    }

    @Test fun blockArrowFunctionsCanReturn() {
        assertEquals(42.0, testJsEngine().evaluate("let f=x=>{return x+2;}; f(40);").numberValue())
    }

    @Test fun forOfIteratesArrays() {
        assertEquals(6.0, testJsEngine().evaluate("let s=0;for(const x of [1,2,3])s+=x;s;").numberValue())
    }

    @Test fun forOfIteratesStrings() {
        assertEquals("abc", testJsEngine().evaluate("let s='';for(const x of 'abc')s+=x;s;").stringValue())
    }

    @Test fun tryCatchFinallyHandlesThrownValues() {
        assertEquals(8.0, testJsEngine().evaluate("let x=0;try{throw 7;}catch(e){x=e;}finally{x+=1;}x;").numberValue())
    }

    @Test fun uncaughtThrowBecomesRuntimeFailure() {
        val result = testJsEngine().evaluate("throw 'boom';")
        assertFalse(result.success)
        assertEquals("Uncaught", result.error?.kind)
    }

    @Test fun newConstructsAndSupportsMemberChaining() {
        assertEquals(42.0, testJsEngine().evaluate("function Box(x){this.value=x;}new Box(42).value;").numberValue())
    }

    @Test fun promiseMicrotaskUpdatesPersistentRealm() {
        val engine = testJsEngine()
        assertTrue(engine.evaluate("let answer=0;Promise.resolve(41).then(v=>answer=v+1);", freshRealm = false).success)
        assertEquals(42.0, engine.evaluate("answer;", freshRealm = false).numberValue())
    }

    @Test fun promiseCatchRecoversRejectedValue() {
        val engine = testJsEngine()
        assertTrue(engine.evaluate("let recovered='';Promise.reject('bad').catch(e=>recovered=e+'!');", freshRealm = false).success)
        assertEquals("bad!", engine.evaluate("recovered;", freshRealm = false).stringValue())
    }

    @Test fun jsonParseCreatesNestedObjects() {
        assertEquals(42.0, testJsEngine().evaluate("JSON.parse('{\"a\":{\"b\":42}}').a.b;").numberValue())
    }

    @Test fun intervalCanBeCancelledAfterRepeatedTicks() {
        val engine = testJsEngine()
        assertTrue(engine.evaluate("let ticks=0;let id=setInterval(()=>{ticks++;if(ticks===3)clearInterval(id);},5);", freshRealm = false).success)
        engine.advanceTimeBy(5)
        engine.advanceTimeBy(5)
        engine.advanceTimeBy(5)
        assertEquals(3.0, engine.evaluate("ticks;", freshRealm = false).numberValue())
        assertFalse(engine.hasPendingTasks())
    }
}
