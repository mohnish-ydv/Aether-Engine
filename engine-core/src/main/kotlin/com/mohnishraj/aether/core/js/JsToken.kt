package com.mohnishraj.aether.core.js

enum class JsTokenType {
    EOF,
    IDENTIFIER, NUMBER, STRING,
    LET, CONST, VAR, FUNCTION, RETURN, IF, ELSE, WHILE, FOR, OF, TRUE, FALSE, NULL, UNDEFINED, BREAK, CONTINUE,
    TRY, CATCH, FINALLY, THROW, NEW, TYPEOF,
    LEFT_PAREN, RIGHT_PAREN, LEFT_BRACE, RIGHT_BRACE, LEFT_BRACKET, RIGHT_BRACKET,
    DOT, COMMA, SEMICOLON, COLON, QUESTION,
    PLUS, MINUS, STAR, SLASH, PERCENT,
    BANG, TILDE,
    EQUAL, ARROW, PLUS_EQUAL, MINUS_EQUAL, STAR_EQUAL, SLASH_EQUAL, PERCENT_EQUAL,
    EQUAL_EQUAL, BANG_EQUAL, STRICT_EQUAL, STRICT_NOT_EQUAL,
    LESS, LESS_EQUAL, GREATER, GREATER_EQUAL,
    AND_AND, OR_OR,
    PLUS_PLUS, MINUS_MINUS
}

data class JsToken(
    val type: JsTokenType,
    val lexeme: String,
    val literal: JsValue? = null,
    val span: JsSourceSpan
)

data class JsLexResult(val tokens: List<JsToken>, val issues: List<JsIssue>)
