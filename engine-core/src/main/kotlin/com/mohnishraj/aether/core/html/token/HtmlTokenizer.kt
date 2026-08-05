package com.mohnishraj.aether.core.html.token

import com.mohnishraj.aether.core.html.HtmlIssue
import com.mohnishraj.aether.core.html.HtmlIssueSeverity
import com.mohnishraj.aether.core.html.HtmlIssueStage
import com.mohnishraj.aether.core.html.HtmlLimits
import com.mohnishraj.aether.core.html.SourceMap
import java.util.Locale

class HtmlTokenizer(
    private val source: String,
    private val limits: HtmlLimits = HtmlLimits()
) {
    private val sourceMap = SourceMap(source)
    private val tokens = ArrayList<HtmlToken>()
    private val issues = ArrayList<HtmlIssue>()
    private var index = 0
    private var halted = false

    fun tokenize(): HtmlTokenizationResult {
        if (source.length > limits.maxInputChars) {
            issue("input-too-large", "Input contains ${source.length} characters; limit is ${limits.maxInputChars}", 0, HtmlIssueSeverity.ERROR, HtmlIssueStage.LIMIT)
            return HtmlTokenizationResult(listOf(HtmlToken.Eof(sourceMap.span(0, 0))), issues.toList())
        }

        while (index < source.length && !halted) {
            when {
                source[index] != '<' -> parseText()
                source.startsWith("<!--", index) -> parseComment()
                source.regionMatches(index, "<!doctype", 0, 9, ignoreCase = true) && isBoundary(index + 9) -> parseDoctype()
                source.startsWith("</", index) -> parseEndTagOrText()
                source.startsWith("<!", index) || source.startsWith("<?", index) -> parseBogusComment()
                source.getOrNull(index + 1)?.isTagNameStart() == true -> parseStartTag()
                else -> {
                    emitText("<", index, index + 1)
                    index++
                }
            }
        }
        emit(HtmlToken.Eof(sourceMap.span(index, index)))
        return HtmlTokenizationResult(tokens.toList(), issues.toList())
    }

    private fun parseText() {
        val start = index
        val end = source.indexOf('<', start).let { if (it < 0) source.length else it }
        val decoded = decodeCharacterReferences(source.substring(start, end), start, inAttribute = false)
        emitText(decoded, start, end)
        index = end
    }

    private fun parseComment() {
        val start = index
        val contentStart = index + 4
        val close = source.indexOf("-->", contentStart)
        if (close < 0) {
            issue("eof-in-comment", "Comment was not closed before end of input", start)
            emit(HtmlToken.Comment(sanitizeNulls(source.substring(contentStart), contentStart), sourceMap.span(start, source.length)))
            index = source.length
            return
        }
        val raw = source.substring(contentStart, close)
        if ("--" in raw) issue("nested-comment-marker", "Comment data contains a double hyphen", contentStart + raw.indexOf("--"))
        emit(HtmlToken.Comment(sanitizeNulls(raw, contentStart), sourceMap.span(start, close + 3)))
        index = close + 3
    }

    private fun parseBogusComment() {
        val start = index
        val dataStart = (index + 2).coerceAtMost(source.length)
        val close = source.indexOf('>', dataStart).let { if (it < 0) source.length else it }
        issue("bogus-comment", "Unsupported markup declaration was treated as a comment", start)
        emit(HtmlToken.Comment(sanitizeNulls(source.substring(dataStart, close), dataStart), sourceMap.span(start, (close + 1).coerceAtMost(source.length))))
        index = (close + 1).coerceAtMost(source.length)
    }

    private fun parseDoctype() {
        val start = index
        index += 9
        skipWhitespace()
        val nameStart = index
        while (index < source.length && !source[index].isHtmlWhitespace() && source[index] != '>') index++
        val name = source.substring(nameStart, index).lowercase(Locale.ROOT)
        var publicId: String? = null
        var systemId: String? = null
        var forceQuirks = name.isBlank()

        skipWhitespace()
        if (source.regionMatches(index, "public", 0, 6, ignoreCase = true) && isBoundary(index + 6)) {
            index += 6
            skipWhitespace()
            publicId = parseDoctypeIdentifier().also { if (it == null) forceQuirks = true }
            skipWhitespace()
            systemId = parseDoctypeIdentifier()
        } else if (source.regionMatches(index, "system", 0, 6, ignoreCase = true) && isBoundary(index + 6)) {
            index += 6
            skipWhitespace()
            systemId = parseDoctypeIdentifier().also { if (it == null) forceQuirks = true }
        }
        skipWhitespace()
        if (source.getOrNull(index) != '>') {
            forceQuirks = true
            issue("malformed-doctype", "DOCTYPE contains unexpected data", index.coerceAtMost(source.length))
            index = source.indexOf('>', index).let { if (it < 0) source.length else it }
        }
        if (source.getOrNull(index) == '>') index++ else issue("eof-in-doctype", "DOCTYPE was not closed", start)
        emit(HtmlToken.Doctype(name.ifBlank { "html" }, publicId, systemId, forceQuirks, sourceMap.span(start, index)))
    }

    private fun parseDoctypeIdentifier(): String? {
        val quote = source.getOrNull(index)
        if (quote != '\'' && quote != '"') return null
        val start = ++index
        while (index < source.length && source[index] != quote) index++
        val value = sanitizeNulls(source.substring(start, index), start)
        if (source.getOrNull(index) == quote) index++ else issue("eof-in-doctype-identifier", "Quoted DOCTYPE identifier was not closed", start)
        return value
    }

    private fun parseEndTagOrText() {
        val start = index
        var cursor = index + 2
        while (source.getOrNull(cursor)?.isHtmlWhitespace() == true) cursor++
        if (source.getOrNull(cursor)?.isTagNameStart() != true) {
            issue("invalid-end-tag", "Malformed end tag was emitted as text", start)
            emitText("<", start, start + 1)
            index++
            return
        }
        val nameStart = cursor
        while (source.getOrNull(cursor)?.isTagNameChar() == true) cursor++
        val name = source.substring(nameStart, cursor).lowercase(Locale.ROOT)
        while (source.getOrNull(cursor)?.isHtmlWhitespace() == true) cursor++
        if (source.getOrNull(cursor) != '>') {
            issue("end-tag-with-attributes", "Unexpected data appeared after an end-tag name", cursor.coerceAtMost(source.length))
            cursor = source.indexOf('>', cursor).let { if (it < 0) source.length else it }
        }
        if (source.getOrNull(cursor) == '>') cursor++ else issue("eof-in-end-tag", "End tag was not closed", start)
        emit(HtmlToken.EndTag(name, sourceMap.span(start, cursor)))
        index = cursor
    }

    private fun parseStartTag() {
        val start = index
        index++
        val nameStart = index
        while (source.getOrNull(index)?.isTagNameChar() == true) index++
        val name = source.substring(nameStart, index).lowercase(Locale.ROOT)
        val attributes = ArrayList<HtmlAttributeToken>()
        val seenNames = HashSet<String>()
        var selfClosing = false
        var closed = false

        while (index < source.length) {
            skipWhitespace()
            when (source.getOrNull(index)) {
                '>' -> {
                    index++
                    closed = true
                    break
                }
                '/' -> {
                    if (source.getOrNull(index + 1) == '>') {
                        index += 2
                        selfClosing = true
                        closed = true
                        break
                    }
                    issue("unexpected-solidus", "Unexpected slash inside start tag", index)
                    index++
                }
                null -> break
                else -> {
                    val attribute = parseAttribute()
                    if (attribute != null) {
                        if (attributes.size >= limits.maxAttributesPerTag) {
                            issue("too-many-attributes", "Tag <$name> exceeds ${limits.maxAttributesPerTag} attributes", attribute.sourceSpan.start.offset, HtmlIssueSeverity.ERROR, HtmlIssueStage.LIMIT)
                        } else if (!seenNames.add(attribute.name)) {
                            issue("duplicate-attribute", "Duplicate attribute '${attribute.name}' was ignored", attribute.sourceSpan.start.offset)
                        } else {
                            attributes += attribute
                        }
                    }
                }
            }
        }
        if (!closed) issue("eof-in-start-tag", "Start tag <$name> was not closed", start)
        val tag = HtmlToken.StartTag(name, attributes, selfClosing, sourceMap.span(start, index))
        emit(tag)
        if (!selfClosing && !halted) parseRawTextIfNeeded(name)
    }

    private fun parseAttribute(): HtmlAttributeToken? {
        val start = index
        val nameStart = index
        while (index < source.length && !source[index].isHtmlWhitespace() && source[index] !in setOf('=', '/', '>', '\u0000', '"', '\'', '<')) index++
        if (index == nameStart) {
            issue("invalid-attribute-name", "Invalid character in attribute name", index)
            index++
            return null
        }
        val rawName = sanitizeNulls(source.substring(nameStart, index), nameStart)
        val name = rawName.lowercase(Locale.ROOT)
        skipWhitespace()
        if (source.getOrNull(index) != '=') return HtmlAttributeToken(name, "", AttributeQuote.EMPTY, sourceMap.span(start, index))
        index++
        skipWhitespace()

        val quote = source.getOrNull(index)
        val valueStart: Int
        val rawValue: String
        val quoteType: AttributeQuote
        when (quote) {
            '"', '\'' -> {
                quoteType = if (quote == '"') AttributeQuote.DOUBLE else AttributeQuote.SINGLE
                valueStart = ++index
                while (index < source.length && source[index] != quote) index++
                rawValue = source.substring(valueStart, index)
                if (source.getOrNull(index) == quote) index++ else issue("eof-in-attribute-value", "Quoted value for '$name' was not closed", valueStart)
            }
            null -> {
                valueStart = index
                rawValue = ""
                quoteType = AttributeQuote.UNQUOTED
                issue("missing-attribute-value", "Attribute '$name' has no value", index)
            }
            else -> {
                quoteType = AttributeQuote.UNQUOTED
                valueStart = index
                while (index < source.length && !source[index].isHtmlWhitespace() && source[index] !in setOf('>', '/')) {
                    if (source[index] in setOf('"', '\'', '<', '=', '`')) issue("unsafe-unquoted-attribute-character", "Unexpected character in unquoted value", index)
                    index++
                }
                rawValue = source.substring(valueStart, index)
            }
        }
        val value = decodeCharacterReferences(rawValue, valueStart, inAttribute = true)
        return HtmlAttributeToken(name, value, quoteType, sourceMap.span(start, index))
    }

    private fun parseRawTextIfNeeded(tagName: String) {
        val mode = when (tagName) {
            "title", "textarea" -> RawMode.RCDATA
            "script", "style", "xmp", "iframe", "noembed", "noframes" -> RawMode.RAWTEXT
            "plaintext" -> RawMode.PLAINTEXT
            else -> return
        }
        val contentStart = index
        if (mode == RawMode.PLAINTEXT) {
            val data = sanitizeNulls(source.substring(index), index)
            emitText(data, index, source.length)
            index = source.length
            return
        }

        val closeStart = findRawClosingTag(tagName, index)
        val contentEnd = if (closeStart < 0) source.length else closeStart
        val raw = source.substring(contentStart, contentEnd)
        val data = if (mode == RawMode.RCDATA) decodeCharacterReferences(raw, contentStart, inAttribute = false) else sanitizeNulls(raw, contentStart)
        emitText(data, contentStart, contentEnd)
        index = contentEnd
        if (closeStart < 0) {
            issue("eof-in-raw-text", "Raw-text element <$tagName> was not closed", contentStart)
            return
        }
        parseEndTagOrText()
    }

    private fun findRawClosingTag(tagName: String, from: Int): Int {
        var cursor = from
        while (cursor < source.length) {
            cursor = source.indexOf("</", cursor)
            if (cursor < 0) return -1
            val nameStart = cursor + 2
            if (source.regionMatches(nameStart, tagName, 0, tagName.length, ignoreCase = true) && isBoundary(nameStart + tagName.length)) return cursor
            cursor += 2
        }
        return -1
    }

    private fun decodeCharacterReferences(raw: String, absoluteStart: Int, inAttribute: Boolean): String {
        if ('&' !in raw && '\u0000' !in raw) return raw
        val output = StringBuilder(raw.length)
        var local = 0
        while (local < raw.length) {
            val char = raw[local]
            if (char == '\u0000') {
                issue("null-character", "Null character was replaced with U+FFFD", absoluteStart + local)
                output.append('\uFFFD')
                local++
                continue
            }
            if (char != '&') {
                output.append(char)
                local++
                continue
            }
            val decoded = CharacterReferences.decode(raw, local, inAttribute)
            if (decoded == null) {
                output.append('&')
                local++
                continue
            }
            if (decoded.missingSemicolon) issue("missing-character-reference-semicolon", "Character reference is missing a semicolon", absoluteStart + local)
            if (decoded.invalidCodePoint) issue("invalid-character-reference", "Character reference used an invalid or legacy code point", absoluteStart + local)
            output.append(decoded.value)
            local += decoded.consumed
        }
        return output.toString()
    }

    private fun sanitizeNulls(value: String, absoluteStart: Int): String {
        if ('\u0000' !in value) return value
        val output = StringBuilder(value.length)
        value.forEachIndexed { local, char ->
            if (char == '\u0000') {
                issue("null-character", "Null character was replaced with U+FFFD", absoluteStart + local)
                output.append('\uFFFD')
            } else output.append(char)
        }
        return output.toString()
    }

    private fun emitText(data: String, start: Int, end: Int) {
        if (data.isEmpty()) return
        val previous = tokens.lastOrNull()
        if (previous is HtmlToken.Text && previous.sourceSpan.end.offset == start) {
            tokens[tokens.lastIndex] = HtmlToken.Text(previous.data + data, sourceMap.span(previous.sourceSpan.start.offset, end))
        } else emit(HtmlToken.Text(data, sourceMap.span(start, end)))
    }

    private fun emit(token: HtmlToken) {
        if (halted) return
        if (tokens.size >= limits.maxTokens) {
            issue("too-many-tokens", "Tokenizer exceeded ${limits.maxTokens} tokens", index, HtmlIssueSeverity.ERROR, HtmlIssueStage.LIMIT)
            halted = true
            return
        }
        tokens += token
    }

    private fun issue(
        code: String,
        message: String,
        offset: Int,
        severity: HtmlIssueSeverity = HtmlIssueSeverity.WARNING,
        stage: HtmlIssueStage = HtmlIssueStage.TOKENIZER
    ) {
        issues += HtmlIssue(stage, code, message, sourceMap.position(offset.coerceIn(0, source.length)), severity)
    }

    private fun skipWhitespace() {
        while (source.getOrNull(index)?.isHtmlWhitespace() == true) index++
    }

    private fun isBoundary(position: Int): Boolean {
        val char = source.getOrNull(position) ?: return true
        return char.isHtmlWhitespace() || char == '>' || char == '/'
    }

    private enum class RawMode { RCDATA, RAWTEXT, PLAINTEXT }

    private fun Char.isHtmlWhitespace(): Boolean = this == ' ' || this == '\t' || this == '\n' || this == '\r' || this == '\u000C'
    private fun Char.isTagNameStart(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
    private fun Char.isTagNameChar(): Boolean = !isHtmlWhitespace() && this !in setOf('/', '>', '\u0000')
}
