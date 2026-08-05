package com.mohnishraj.aether.core.js

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JsLexerTest {
    @Test fun tokenizesKeywords() {
        val types = JsLexer("let const var function return if else while for true false null undefined break continue typeof").lex().tokens.map { it.type }
        assertTrue(types.containsAll(listOf(JsTokenType.LET, JsTokenType.CONST, JsTokenType.VAR, JsTokenType.FUNCTION, JsTokenType.RETURN, JsTokenType.TYPEOF)))
    }
    @Test fun tokenizesOperators() {
        val source = "+ - * / % ! ~ = += -= *= /= %= == != === !== < <= > >= && || ++ --"
        val result = JsLexer(source).lex()
        assertTrue(result.issues.isEmpty())
        assertEquals(25, result.tokens.size - 1)
    }
    @Test fun tokenizesPunctuation() {
        val result = JsLexer("(){}[].,;:?").lex()
        assertEquals(11, result.tokens.size - 1)
    }
    @Test fun parsesDecimalNumber() {
        assertEquals(12.5, assertIs<JsValue.NumberValue>(JsLexer("12.5").lex().tokens.first().literal).value)
    }
    @Test fun parsesLeadingDotNumber() {
        assertEquals(0.25, assertIs<JsValue.NumberValue>(JsLexer(".25").lex().tokens.first().literal).value)
    }
    @Test fun parsesExponentNumber() {
        assertEquals(1250.0, assertIs<JsValue.NumberValue>(JsLexer("1.25e3").lex().tokens.first().literal).value)
    }
    @Test fun parsesHexNumber() {
        assertEquals(255.0, assertIs<JsValue.NumberValue>(JsLexer("0xff").lex().tokens.first().literal).value)
    }
    @Test fun parsesBinaryNumber() {
        assertEquals(10.0, assertIs<JsValue.NumberValue>(JsLexer("0b1010").lex().tokens.first().literal).value)
    }
    @Test fun parsesOctalNumber() {
        assertEquals(8.0, assertIs<JsValue.NumberValue>(JsLexer("0o10").lex().tokens.first().literal).value)
    }
    @Test fun decodesStringEscapes() {
        assertEquals("a\nA", assertIs<JsValue.StringValue>(JsLexer("'a\\n\\x41'").lex().tokens.first().literal).value)
    }
    @Test fun decodesUnicodeEscape() {
        assertEquals("A", assertIs<JsValue.StringValue>(JsLexer("'\\u0041'").lex().tokens.first().literal).value)
    }
    @Test fun skipsLineComments() {
        assertEquals(listOf(JsTokenType.NUMBER, JsTokenType.NUMBER, JsTokenType.EOF), JsLexer("1//x\n2").lex().tokens.map { it.type })
    }
    @Test fun skipsBlockComments() {
        assertEquals(listOf(JsTokenType.NUMBER, JsTokenType.NUMBER, JsTokenType.EOF), JsLexer("1/*x*/2").lex().tokens.map { it.type })
    }
    @Test fun reportsUnterminatedString() {
        assertTrue(JsLexer("'open").lex().issues.any { it.code == "unterminated-string" })
    }
    @Test fun reportsUnterminatedComment() {
        assertTrue(JsLexer("/*open").lex().issues.any { it.code == "unterminated-comment" })
    }
    @Test fun tracksLinesAndColumns() {
        val token = JsLexer("x\n  answer").lex().tokens[1]
        assertEquals(2, token.span.start.line)
        assertEquals(3, token.span.start.column)
    }
    @Test fun enforcesSourceLimit() {
        val result = JsLexer("12345", JsLimits(maxSourceChars = 4)).lex()
        assertTrue(result.issues.any { it.code == "source-too-large" })
    }
    @Test fun enforcesTokenLimit() {
        val result = JsLexer("1 2 3 4 5", JsLimits(maxTokens = 4)).lex()
        assertTrue(result.issues.any { it.code == "token-limit" })
        assertEquals(JsTokenType.EOF, result.tokens.last().type)
    }
    @Test fun acceptsUnicodeIdentifiers() {
        val result = JsLexer("let नमस्ते=1;").lex()
        assertFalse(result.issues.any())
        assertEquals("नमस्ते", result.tokens[1].lexeme)
    }
}
