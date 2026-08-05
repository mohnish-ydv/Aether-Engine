package com.mohnishraj.aether.core.html.token

internal object CharacterReferences {
    data class Decoded(
        val value: String,
        val consumed: Int,
        val missingSemicolon: Boolean = false,
        val invalidCodePoint: Boolean = false
    )

    private val named = mapOf(
        "amp" to "&",
        "apos" to "'",
        "bull" to "•",
        "cent" to "¢",
        "copy" to "©",
        "divide" to "÷",
        "euro" to "€",
        "gt" to ">",
        "hellip" to "…",
        "laquo" to "«",
        "ldquo" to "“",
        "lsquo" to "‘",
        "lt" to "<",
        "mdash" to "—",
        "middot" to "·",
        "nbsp" to "\u00A0",
        "ndash" to "–",
        "para" to "¶",
        "pound" to "£",
        "quot" to "\"",
        "raquo" to "»",
        "rdquo" to "”",
        "reg" to "®",
        "rsquo" to "’",
        "sect" to "§",
        "times" to "×",
        "trade" to "™",
        "yen" to "¥"
    )

    private val windows1252 = mapOf(
        0x80 to 0x20AC, 0x82 to 0x201A, 0x83 to 0x0192, 0x84 to 0x201E,
        0x85 to 0x2026, 0x86 to 0x2020, 0x87 to 0x2021, 0x88 to 0x02C6,
        0x89 to 0x2030, 0x8A to 0x0160, 0x8B to 0x2039, 0x8C to 0x0152,
        0x8E to 0x017D, 0x91 to 0x2018, 0x92 to 0x2019, 0x93 to 0x201C,
        0x94 to 0x201D, 0x95 to 0x2022, 0x96 to 0x2013, 0x97 to 0x2014,
        0x98 to 0x02DC, 0x99 to 0x2122, 0x9A to 0x0161, 0x9B to 0x203A,
        0x9C to 0x0153, 0x9E to 0x017E, 0x9F to 0x0178
    )

    fun decode(source: String, ampersandIndex: Int, inAttribute: Boolean): Decoded? {
        require(source.getOrNull(ampersandIndex) == '&')
        val next = ampersandIndex + 1
        if (next >= source.length) return null
        return if (source[next] == '#') decodeNumeric(source, ampersandIndex) else decodeNamed(source, ampersandIndex, inAttribute)
    }

    private fun decodeNamed(source: String, ampersandIndex: Int, inAttribute: Boolean): Decoded? {
        val nameStart = ampersandIndex + 1
        var cursor = nameStart
        while (cursor < source.length && source[cursor].isAsciiAlphaNumeric() && cursor - nameStart < 32) cursor++
        if (cursor == nameStart) return null

        var candidateEnd = cursor
        while (candidateEnd > nameStart) {
            val candidate = source.substring(nameStart, candidateEnd)
            val value = named[candidate]
            if (value != null) {
                val hasSemicolon = source.getOrNull(candidateEnd) == ';'
                val following = source.getOrNull(candidateEnd)
                if (!hasSemicolon && inAttribute && (following?.isAsciiAlphaNumeric() == true || following == '=')) return null
                val consumed = candidateEnd - ampersandIndex + if (hasSemicolon) 1 else 0
                return Decoded(value, consumed, missingSemicolon = !hasSemicolon)
            }
            candidateEnd--
        }
        return null
    }

    private fun decodeNumeric(source: String, ampersandIndex: Int): Decoded? {
        var cursor = ampersandIndex + 2
        var radix = 10
        if (source.getOrNull(cursor) == 'x' || source.getOrNull(cursor) == 'X') {
            radix = 16
            cursor++
        }
        val digitsStart = cursor
        while (cursor < source.length && source[cursor].digitToIntOrNull(radix) != null && cursor - digitsStart < 8) cursor++
        if (cursor == digitsStart) return null
        val hasSemicolon = source.getOrNull(cursor) == ';'
        val raw = source.substring(digitsStart, cursor).toLongOrNull(radix) ?: return null
        val normalized = normalizeCodePoint(raw)
        val consumed = cursor - ampersandIndex + if (hasSemicolon) 1 else 0
        return Decoded(
            value = String(Character.toChars(normalized.first)),
            consumed = consumed,
            missingSemicolon = !hasSemicolon,
            invalidCodePoint = normalized.second
        )
    }

    private fun normalizeCodePoint(raw: Long): Pair<Int, Boolean> {
        if (raw == 0L || raw > 0x10FFFF || raw in 0xD800..0xDFFF) return 0xFFFD to true
        val codePoint = raw.toInt()
        windows1252[codePoint]?.let { return it to true }
        val control = codePoint in 0x0001..0x0008 || codePoint in 0x000B..0x000C ||
            codePoint in 0x000E..0x001F || codePoint in 0x007F..0x009F
        val nonCharacter = codePoint in 0xFDD0..0xFDEF || (codePoint and 0xFFFF) in setOf(0xFFFE, 0xFFFF)
        return codePoint to (control || nonCharacter)
    }

    private fun Char.isAsciiAlphaNumeric(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
}
