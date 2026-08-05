package com.mohnishraj.aether.core.css.cascade

import com.mohnishraj.aether.core.css.ColorScheme
import com.mohnishraj.aether.core.css.MediaEnvironment
import java.util.Locale

object MediaQueryEvaluator {
    fun matches(queryList: String, environment: MediaEnvironment): Boolean =
        splitTopLevel(queryList, ',').any { matchesSingle(it.trim(), environment) }

    private fun matchesSingle(raw: String, environment: MediaEnvironment): Boolean {
        if (raw.isBlank()) return true
        var query = raw.lowercase(Locale.ROOT).trim()
        var negate = false
        if (query.startsWith("not ")) { negate = true; query = query.removePrefix("not ").trim() }
        if (query.startsWith("only ")) query = query.removePrefix("only ").trim()
        val parts = query.split(Regex("\\s+and\\s+")).map(String::trim).filter(String::isNotEmpty)
        var result = true
        parts.forEachIndexed { index, part ->
            val matched = if (part.startsWith("(") && part.endsWith(")")) {
                matchFeature(part.substring(1, part.length - 1).trim(), environment)
            } else if (index == 0) {
                part == "all" || part == environment.mediaType.lowercase(Locale.ROOT)
            } else false
            result = result && matched
        }
        return if (negate) !result else result
    }

    private fun matchFeature(feature: String, environment: MediaEnvironment): Boolean {
        val colon = feature.indexOf(':')
        val name = (if (colon >= 0) feature.substring(0, colon) else feature).trim()
        val value = if (colon >= 0) feature.substring(colon + 1).trim() else ""
        return when (name) {
            "min-width" -> parseLength(value, environment)?.let { environment.widthPx >= it } == true
            "max-width" -> parseLength(value, environment)?.let { environment.widthPx <= it } == true
            "width" -> parseLength(value, environment)?.let { environment.widthPx == it } == true
            "min-height" -> parseLength(value, environment)?.let { environment.heightPx >= it } == true
            "max-height" -> parseLength(value, environment)?.let { environment.heightPx <= it } == true
            "height" -> parseLength(value, environment)?.let { environment.heightPx == it } == true
            "orientation" -> when (value) {
                "portrait" -> environment.heightPx >= environment.widthPx
                "landscape" -> environment.widthPx > environment.heightPx
                else -> false
            }
            "prefers-color-scheme" -> when (value) {
                "dark" -> environment.colorScheme == ColorScheme.DARK
                "light" -> environment.colorScheme == ColorScheme.LIGHT
                else -> false
            }
            "color" -> true
            else -> false
        }
    }

    private fun parseLength(raw: String, environment: MediaEnvironment): Double? {
        val match = Regex("^([+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+))(px|em|rem)?$").matchEntire(raw) ?: return null
        val number = match.groupValues[1].toDoubleOrNull() ?: return null
        return when (match.groupValues[2]) {
            "", "px" -> number
            "em", "rem" -> number * environment.rootFontSizePx
            else -> null
        }
    }

    private fun splitTopLevel(source: String, delimiter: Char): List<String> {
        val output = ArrayList<String>()
        var depth = 0
        var start = 0
        source.forEachIndexed { index, c ->
            when (c) {
                '(' -> depth++
                ')' -> depth--
                delimiter -> if (depth == 0) { output += source.substring(start, index); start = index + 1 }
            }
        }
        output += source.substring(start)
        return output
    }
}
