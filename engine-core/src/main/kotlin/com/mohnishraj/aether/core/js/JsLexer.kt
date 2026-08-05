package com.mohnishraj.aether.core.js

import java.util.Locale

class JsLexer(
    private val source: String,
    private val limits: JsLimits = JsLimits()
) {
    private val tokens = mutableListOf<JsToken>()
    private val issues = mutableListOf<JsIssue>()
    private var start = 0
    private var current = 0
    private var line = 1
    private var column = 1
    private var startLine = 1
    private var startColumn = 1

    fun lex(): JsLexResult {
        if (source.length > limits.maxSourceChars) {
            issue("source-too-large", "JavaScript source exceeds ${limits.maxSourceChars} characters", JsSourceSpan.UNKNOWN)
            return JsLexResult(listOf(JsToken(JsTokenType.EOF, "", span = JsSourceSpan.UNKNOWN)), immutableCopy(issues))
        }
        while (!isAtEnd() && tokens.size < limits.maxTokens - 1) {
            start = current
            startLine = line
            startColumn = column
            scanToken()
        }
        if (!isAtEnd()) issue("token-limit", "Token limit ${limits.maxTokens} reached", currentSpan())
        tokens += JsToken(JsTokenType.EOF, "", span = positionSpan())
        return JsLexResult(immutableCopy(tokens), immutableCopy(issues))
    }

    private fun scanToken() {
        when (val character = advance()) {
            ' ', '\t', '\r' -> Unit
            '\n' -> Unit
            '(' -> add(JsTokenType.LEFT_PAREN)
            ')' -> add(JsTokenType.RIGHT_PAREN)
            '{' -> add(JsTokenType.LEFT_BRACE)
            '}' -> add(JsTokenType.RIGHT_BRACE)
            '[' -> add(JsTokenType.LEFT_BRACKET)
            ']' -> add(JsTokenType.RIGHT_BRACKET)
            '.' -> if (peek().isDigit()) number(startedWithDot = true) else add(JsTokenType.DOT)
            ',' -> add(JsTokenType.COMMA)
            ';' -> add(JsTokenType.SEMICOLON)
            ':' -> add(JsTokenType.COLON)
            '?' -> add(JsTokenType.QUESTION)
            '~' -> add(JsTokenType.TILDE)
            '+' -> add(if (match('+')) JsTokenType.PLUS_PLUS else if (match('=')) JsTokenType.PLUS_EQUAL else JsTokenType.PLUS)
            '-' -> add(if (match('-')) JsTokenType.MINUS_MINUS else if (match('=')) JsTokenType.MINUS_EQUAL else JsTokenType.MINUS)
            '*' -> add(if (match('=')) JsTokenType.STAR_EQUAL else JsTokenType.STAR)
            '%' -> add(if (match('=')) JsTokenType.PERCENT_EQUAL else JsTokenType.PERCENT)
            '!' -> add(if (match('=')) { if (match('=')) JsTokenType.STRICT_NOT_EQUAL else JsTokenType.BANG_EQUAL } else JsTokenType.BANG)
            '=' -> add(if (match('>')) JsTokenType.ARROW else if (match('=')) { if (match('=')) JsTokenType.STRICT_EQUAL else JsTokenType.EQUAL_EQUAL } else JsTokenType.EQUAL)
            '<' -> add(if (match('=')) JsTokenType.LESS_EQUAL else JsTokenType.LESS)
            '>' -> add(if (match('=')) JsTokenType.GREATER_EQUAL else JsTokenType.GREATER)
            '&' -> if (match('&')) add(JsTokenType.AND_AND) else issue("unexpected-character", "Single '&' is not supported", currentSpan())
            '|' -> if (match('|')) add(JsTokenType.OR_OR) else issue("unexpected-character", "Single '|' is not supported", currentSpan())
            '/' -> when {
                match('/') -> lineComment()
                match('*') -> blockComment()
                match('=') -> add(JsTokenType.SLASH_EQUAL)
                else -> add(JsTokenType.SLASH)
            }
            '\'', '"' -> string(character)
            else -> when {
                character.isDigit() -> number(startedWithDot = false)
                isIdentifierStart(character) -> identifier()
                else -> issue("unexpected-character", "Unexpected character U+${character.code.toString(16).uppercase(Locale.ROOT).padStart(4, '0')}", currentSpan())
            }
        }
    }

    private fun lineComment() {
        while (peek() != '\n' && !isAtEnd()) advance()
    }

    private fun blockComment() {
        var closed = false
        while (!isAtEnd()) {
            if (peek() == '*' && peekNext() == '/') {
                advance(); advance(); closed = true; break
            }
            advance()
        }
        if (!closed) issue("unterminated-comment", "Unterminated block comment", currentSpan())
    }

    private fun string(quote: Char) {
        val builder = StringBuilder()
        var terminated = false
        while (!isAtEnd()) {
            val character = advance()
            when {
                character == quote -> { terminated = true; break }
                character == '\n' || character == '\r' -> {
                    issue("unterminated-string", "Unterminated string literal", currentSpan())
                    return
                }
                character == '\\' -> {
                    if (isAtEnd()) break
                    val escaped = advance()
                    when (escaped) {
                        'n' -> builder.append('\n')
                        'r' -> builder.append('\r')
                        't' -> builder.append('\t')
                        'b' -> builder.append('\b')
                        'f' -> builder.append('\u000C')
                        'v' -> builder.append('\u000B')
                        '0' -> builder.append('\u0000')
                        '\\' -> builder.append('\\')
                        '\'' -> builder.append('\'')
                        '"' -> builder.append('"')
                        'x' -> appendHexEscape(builder, 2, "hex")
                        'u' -> appendHexEscape(builder, 4, "unicode")
                        '\n' -> Unit
                        '\r' -> if (peek() == '\n') advance()
                        else -> builder.append(escaped)
                    }
                }
                else -> builder.append(character)
            }
            if (builder.length > limits.maxStringChars) {
                issue("string-too-large", "String literal exceeds ${limits.maxStringChars} characters", currentSpan())
                return
            }
        }
        if (!terminated) {
            issue("unterminated-string", "Unterminated string literal", currentSpan())
            return
        }
        add(JsTokenType.STRING, JsValue.StringValue(builder.toString()))
    }

    private fun appendHexEscape(builder: StringBuilder, digits: Int, label: String) {
        if (current + digits > source.length) {
            issue("invalid-$label-escape", "Incomplete $label escape", currentSpan())
            return
        }
        val value = source.substring(current, current + digits)
        if (!value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            issue("invalid-$label-escape", "Invalid $label escape '$value'", currentSpan())
            repeat(digits) { if (!isAtEnd()) advance() }
            return
        }
        repeat(digits) { advance() }
        builder.append(value.toInt(16).toChar())
    }

    private fun number(startedWithDot: Boolean) {
        if (!startedWithDot && source[start] == '0' && current < source.length) {
            val marker = peek().lowercaseChar()
            val radix = when (marker) { 'x' -> 16; 'b' -> 2; 'o' -> 8; else -> 0 }
            if (radix != 0) {
                advance()
                val digitsStart = current
                while (digitForRadix(peek(), radix)) advance()
                val digits = source.substring(digitsStart, current)
                if (digits.isEmpty()) {
                    issue("invalid-number", "Expected digits after radix prefix", currentSpan())
                    add(JsTokenType.NUMBER, JsValue.NumberValue(Double.NaN))
                } else {
                    add(JsTokenType.NUMBER, JsValue.NumberValue(digits.toLongOrNull(radix)?.toDouble() ?: Double.NaN))
                }
                return
            }
        }
        while (peek().isDigit()) advance()
        if (!startedWithDot && peek() == '.' && peekNext().isDigit()) {
            advance()
            while (peek().isDigit()) advance()
        }
        if (peek().lowercaseChar() == 'e') {
            val checkpoint = current
            val checkpointLine = line
            val checkpointColumn = column
            advance()
            if (peek() == '+' || peek() == '-') advance()
            if (!peek().isDigit()) {
                current = checkpoint
                line = checkpointLine
                column = checkpointColumn
            } else while (peek().isDigit()) advance()
        }
        val raw = source.substring(start, current)
        val numeric = raw.toDoubleOrNull()
        if (numeric == null) issue("invalid-number", "Invalid number literal '$raw'", currentSpan())
        add(JsTokenType.NUMBER, JsValue.NumberValue(numeric ?: Double.NaN))
    }

    private fun identifier() {
        while (isIdentifierPart(peek())) advance()
        val text = source.substring(start, current)
        val type = KEYWORDS[text] ?: JsTokenType.IDENTIFIER
        val literal = when (type) {
            JsTokenType.TRUE -> JsValue.BooleanValue(true)
            JsTokenType.FALSE -> JsValue.BooleanValue(false)
            JsTokenType.NULL -> JsValue.Null
            JsTokenType.UNDEFINED -> JsValue.Undefined
            else -> null
        }
        add(type, literal)
    }

    private fun add(type: JsTokenType, literal: JsValue? = null) {
        if (tokens.size >= limits.maxTokens - 1) return
        tokens += JsToken(type, source.substring(start, current), literal, currentSpan())
    }

    private fun advance(): Char {
        val character = source[current++]
        if (character == '\n') { line++; column = 1 } else column++
        return character
    }

    private fun match(expected: Char): Boolean {
        if (isAtEnd() || source[current] != expected) return false
        advance()
        return true
    }

    private fun peek(): Char = if (isAtEnd()) '\u0000' else source[current]
    private fun peekNext(): Char = if (current + 1 >= source.length) '\u0000' else source[current + 1]
    private fun isAtEnd(): Boolean = current >= source.length
    private fun currentSpan(): JsSourceSpan = JsSourceSpan(JsSourcePosition(start, startLine, startColumn), JsSourcePosition(current, line, column))
    private fun positionSpan(): JsSourceSpan = JsSourceSpan(JsSourcePosition(current, line, column), JsSourcePosition(current, line, column))
    private fun issue(code: String, message: String, span: JsSourceSpan) { issues += JsIssue(code, message, span) }

    companion object {
        private val KEYWORDS = mapOf(
            "let" to JsTokenType.LET, "const" to JsTokenType.CONST, "var" to JsTokenType.VAR,
            "function" to JsTokenType.FUNCTION, "return" to JsTokenType.RETURN,
            "if" to JsTokenType.IF, "else" to JsTokenType.ELSE, "while" to JsTokenType.WHILE, "for" to JsTokenType.FOR, "of" to JsTokenType.OF,
            "true" to JsTokenType.TRUE, "false" to JsTokenType.FALSE, "null" to JsTokenType.NULL,
            "undefined" to JsTokenType.UNDEFINED, "break" to JsTokenType.BREAK, "continue" to JsTokenType.CONTINUE,
            "try" to JsTokenType.TRY, "catch" to JsTokenType.CATCH, "finally" to JsTokenType.FINALLY, "throw" to JsTokenType.THROW,
            "new" to JsTokenType.NEW, "typeof" to JsTokenType.TYPEOF
        )
        private fun isIdentifierStart(character: Char): Boolean = character == '_' || character == '$' || character.isLetter()
        private fun isIdentifierPart(character: Char): Boolean {
            val type = Character.getType(character)
            return isIdentifierStart(character) || character.isDigit() ||
                type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() ||
                type == Character.CONNECTOR_PUNCTUATION.toInt()
        }
        private fun digitForRadix(character: Char, radix: Int): Boolean = Character.digit(character, radix) >= 0
    }
}
