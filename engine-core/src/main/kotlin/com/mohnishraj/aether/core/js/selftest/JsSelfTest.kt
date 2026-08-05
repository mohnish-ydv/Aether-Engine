package com.mohnishraj.aether.core.js.selftest

import com.mohnishraj.aether.core.js.JsEngine
import com.mohnishraj.aether.core.js.JsLexer
import com.mohnishraj.aether.core.js.JsParser
import com.mohnishraj.aether.core.js.JsTokenType
import com.mohnishraj.aether.core.js.JsValue
import com.mohnishraj.aether.core.selftest.SelfTestCheck

object JsSelfTest {
    fun run(engine: JsEngine): List<SelfTestCheck> {
        val checks = mutableListOf<SelfTestCheck>()
        fun check(name: String, block: () -> String) {
            val result = runCatching(block)
            checks += if (result.isSuccess) SelfTestCheck("js $name", true, result.getOrThrow())
            else SelfTestCheck("js $name", false, result.exceptionOrNull()?.message ?: "unknown error")
        }
        fun evaluate(source: String) = engine.evaluate(source, "aether://selftest", freshRealm = true)
        fun number(source: String): Double {
            val result = evaluate(source)
            require(result.success) { result.error?.pretty().orEmpty() }
            return (result.value as JsValue.NumberValue).value
        }

        check("lexer") {
            val lexed = JsLexer("let answer = 40 + 2;").lex()
            require(lexed.issues.isEmpty() && lexed.tokens.any { it.type == JsTokenType.LET })
            "keywords, literals and operators tokenized"
        }
        check("parser") {
            val parsed = JsParser.parse("function add(a,b){ return a+b; }")
            require(!parsed.hasErrors && parsed.program.statements.size == 1)
            "AST nodes=${parsed.astNodeCount}"
        }
        check("arithmetic") { require(number("1 + 2 * 3;") == 7.0); "precedence=7" }
        check("coercion") {
            val result = evaluate("'A' + 7 + true;")
            require(result.success && result.value == JsValue.StringValue("A7true"))
            "string and primitive coercion"
        }
        check("lexical scopes") {
            require(number("let x=2; { let x=9; } x;") == 2.0)
            "block-scoped let isolation"
        }
        check("const guard") {
            val result = evaluate("const x=1; x=2;")
            require(!result.success && result.error?.kind == "TypeError")
            "immutable binding enforced"
        }
        check("functions") { require(number("function add(a,b){return a+b;} add(20,22);") == 42.0); "declaration and call" }
        check("closures") { require(number("function outer(x){return function(y){return x+y;};} let add=outer(40); add(2);") == 42.0); "captured environment" }
        check("arrays") {
            val result = evaluate("let a=[1,2]; a.push(3); a.join('-');")
            require(result.success && result.value == JsValue.StringValue("1-2-3"))
            "indexed storage and methods"
        }
        check("objects") { require(number("let o={x:40}; o.x+=2; o.x;") == 42.0); "literal, member read/write" }
        check("control flow") { require(number("let x=0; if(true){x=42;} else{x=1;} x;") == 42.0); "if/else branch" }
        check("loops") { require(number("let s=0; for(let i=0;i<7;i++){if(i==6){break;} s+=i;} s;") == 15.0); "for, update and break" }
        check("builtins") { require(number("Math.max(4, Math.sqrt(1764));") == 42.0); "Math native functions" }
        check("console") {
            val result = evaluate("console.log('answer', 42);")
            require(result.success && result.output.single().contains("answer") && result.output.single().contains("42"))
            "captured deterministic output"
        }
        check("task queue") {
            val result = evaluate("setTimeout(function(){console.log('timer');},0); queueMicrotask(function(){console.log('micro');});")
            require(result.success && result.tasksExecuted == 2 && result.output == listOf("\"micro\"", "\"timer\""))
            "microtask-before-timer order"
        }
        check("runtime safety") {
            val result = evaluate("function recurse(){return recurse();} recurse();")
            require(!result.success && result.error?.kind == "RangeError")
            "call-depth guard stopped recursion"
        }
        return checks
    }
}
