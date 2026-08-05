package com.mohnishraj.aether.core.css.token

import com.mohnishraj.aether.core.css.CssIssue
import com.mohnishraj.aether.core.css.CssSpan

enum class CssTokenType {
    IDENT, FUNCTION, AT_KEYWORD, HASH, STRING, URL, NUMBER, PERCENTAGE, DIMENSION,
    WHITESPACE, COLON, SEMICOLON, COMMA, LEFT_BRACE, RIGHT_BRACE, LEFT_BRACKET,
    RIGHT_BRACKET, LEFT_PAREN, RIGHT_PAREN, DELIM, CDO, CDC, EOF
}

data class CssToken(
    val type: CssTokenType,
    val value: String = "",
    val number: Double? = null,
    val unit: String? = null,
    val span: CssSpan = CssSpan.EMPTY
)

data class CssTokenizeResult(val tokens: List<CssToken>, val issues: List<CssIssue>)
