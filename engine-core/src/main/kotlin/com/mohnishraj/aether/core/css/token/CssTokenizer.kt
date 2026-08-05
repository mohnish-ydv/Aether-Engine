package com.mohnishraj.aether.core.css.token

import com.mohnishraj.aether.core.css.CssIssue
import com.mohnishraj.aether.core.css.CssLimits
import com.mohnishraj.aether.core.css.CssSpan
import java.util.Locale

class CssTokenizer(private val limits: CssLimits = CssLimits()) {
    fun tokenize(source: String): CssTokenizeResult {
        require(source.length <= limits.maxInputChars) { "CSS input exceeds ${limits.maxInputChars} characters" }
        val tokens = ArrayList<CssToken>()
        val issues = ArrayList<CssIssue>()
        var index = 0

        fun add(token: CssToken) {
            if (tokens.size >= limits.maxTokens) error("CSS token limit exceeded")
            tokens += token
        }
        fun span(start: Int) = CssSpan(start, index)
        fun isNameStart(c: Char): Boolean = c == '_' || c == '-' || c.code >= 0x80 || c.isLetter()
        fun isName(c: Char): Boolean = isNameStart(c) || c.isDigit()
        fun consumeEscape(): Char {
            if (index >= source.length) return '\uFFFD'
            val start = index
            var digits = 0
            var value = 0
            while (index < source.length && digits < 6 && source[index].digitToIntOrNull(16) != null) {
                value = value * 16 + source[index].digitToInt(16)
                index++
                digits++
            }
            if (digits > 0) {
                if (index < source.length && source[index].isWhitespace()) index++
                return if (value == 0 || value > 0x10FFFF || value in 0xD800..0xDFFF) '\uFFFD' else value.toChar()
            }
            index = start + 1
            return source[start]
        }
        fun consumeName(): String = buildString {
            while (index < source.length) {
                val c = source[index]
                when {
                    isName(c) -> { append(c); index++ }
                    c == '\\' -> { index++; append(consumeEscape()) }
                    else -> break
                }
            }
        }
        fun startsNumber(at: Int): Boolean {
            if (at >= source.length) return false
            var p = at
            if (source[p] == '+' || source[p] == '-') p++
            if (p >= source.length) return false
            if (source[p].isDigit()) return true
            return source[p] == '.' && p + 1 < source.length && source[p + 1].isDigit()
        }
        fun consumeNumber(): Pair<String, Double> {
            val start = index
            if (index < source.length && (source[index] == '+' || source[index] == '-')) index++
            while (index < source.length && source[index].isDigit()) index++
            if (index + 1 < source.length && source[index] == '.' && source[index + 1].isDigit()) {
                index++
                while (index < source.length && source[index].isDigit()) index++
            }
            if (index < source.length && (source[index] == 'e' || source[index] == 'E')) {
                var p = index + 1
                if (p < source.length && (source[p] == '+' || source[p] == '-')) p++
                if (p < source.length && source[p].isDigit()) {
                    index = p + 1
                    while (index < source.length && source[index].isDigit()) index++
                }
            }
            val raw = source.substring(start, index)
            return raw to (raw.toDoubleOrNull() ?: 0.0)
        }
        fun consumeString(quote: Char, start: Int): CssToken {
            index++
            val value = buildString {
                while (index < source.length) {
                    val c = source[index++]
                    when {
                        c == quote -> return CssToken(CssTokenType.STRING, toString(), span = span(start))
                        c == '\n' || c == '\r' || c == '\u000C' -> {
                            issues += CssIssue("bad-string", "Unterminated CSS string", CssSpan(start, index))
                            return CssToken(CssTokenType.STRING, toString(), span = span(start))
                        }
                        c == '\\' && index < source.length -> append(consumeEscape())
                        else -> append(c)
                    }
                }
            }
            issues += CssIssue("eof-in-string", "Unexpected end of CSS string", CssSpan(start, index))
            return CssToken(CssTokenType.STRING, value, span = span(start))
        }

        while (index < source.length) {
            val start = index
            val c = source[index]
            when {
                c.isWhitespace() -> {
                    index++
                    while (index < source.length && source[index].isWhitespace()) index++
                    add(CssToken(CssTokenType.WHITESPACE, " ", span = span(start)))
                }
                c == '/' && index + 1 < source.length && source[index + 1] == '*' -> {
                    index += 2
                    val close = source.indexOf("*/", index)
                    if (close < 0) {
                        issues += CssIssue("eof-in-comment", "Unterminated CSS comment", CssSpan(start, source.length))
                        index = source.length
                    } else index = close + 2
                }
                source.startsWith("<!--", index) -> { index += 4; add(CssToken(CssTokenType.CDO, span = span(start))) }
                source.startsWith("-->", index) -> { index += 3; add(CssToken(CssTokenType.CDC, span = span(start))) }
                c == '"' || c == '\'' -> add(consumeString(c, start))
                c == '@' && index + 1 < source.length && isNameStart(source[index + 1]) -> {
                    index++
                    add(CssToken(CssTokenType.AT_KEYWORD, consumeName().lowercase(Locale.ROOT), span = span(start)))
                }
                c == '#' && index + 1 < source.length && isName(source[index + 1]) -> {
                    index++
                    add(CssToken(CssTokenType.HASH, consumeName(), span = span(start)))
                }
                startsNumber(index) -> {
                    val (raw, number) = consumeNumber()
                    when {
                        index < source.length && source[index] == '%' -> { index++; add(CssToken(CssTokenType.PERCENTAGE, raw, number, "%", span(start))) }
                        index < source.length && (isNameStart(source[index]) || source[index] == '\\') -> {
                            val unit = consumeName().lowercase(Locale.ROOT)
                            add(CssToken(CssTokenType.DIMENSION, raw + unit, number, unit, span(start)))
                        }
                        else -> add(CssToken(CssTokenType.NUMBER, raw, number, span = span(start)))
                    }
                }
                isNameStart(c) || c == '\\' -> {
                    val name = consumeName()
                    if (index < source.length && source[index] == '(') {
                        index++
                        add(CssToken(CssTokenType.FUNCTION, name.lowercase(Locale.ROOT), span = span(start)))
                    } else add(CssToken(CssTokenType.IDENT, name, span = span(start)))
                }
                else -> {
                    index++
                    val type = when (c) {
                        ':' -> CssTokenType.COLON
                        ';' -> CssTokenType.SEMICOLON
                        ',' -> CssTokenType.COMMA
                        '{' -> CssTokenType.LEFT_BRACE
                        '}' -> CssTokenType.RIGHT_BRACE
                        '[' -> CssTokenType.LEFT_BRACKET
                        ']' -> CssTokenType.RIGHT_BRACKET
                        '(' -> CssTokenType.LEFT_PAREN
                        ')' -> CssTokenType.RIGHT_PAREN
                        else -> CssTokenType.DELIM
                    }
                    add(CssToken(type, if (type == CssTokenType.DELIM) c.toString() else "", span = span(start)))
                }
            }
        }
        add(CssToken(CssTokenType.EOF, span = CssSpan(index, index)))
        return CssTokenizeResult(tokens, issues)
    }
}
