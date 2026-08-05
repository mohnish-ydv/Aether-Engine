package com.mohnishraj.aether.core.browser.features

import com.mohnishraj.aether.core.browser.BrowserPage
import com.mohnishraj.aether.core.html.dom.DocumentNode
import com.mohnishraj.aether.core.html.dom.ElementNode
import java.util.Locale
import kotlin.math.ceil

class ReaderModeEngine {
    fun extract(page: BrowserPage): ReaderArticle = extract(page.document.document, page.url)

    fun extract(document: DocumentNode, fallbackTitle: String = "Untitled page"): ReaderArticle {
        val title = document.getElementsByTagName("title").firstOrNull()?.textContent?.cleanText()
            ?: document.getElementsByTagName("h1").firstOrNull()?.textContent?.cleanText()
            ?: fallbackTitle
        val byline = document.descendants().filterIsInstance<ElementNode>().firstOrNull { element ->
            val marker = listOf(element.getAttribute("rel"), element.getAttribute("class"), element.getAttribute("id"), element.getAttribute("itemprop"))
                .filterNotNull().joinToString(" ").lowercase(Locale.ROOT)
            marker.contains("author") || marker.contains("byline")
        }?.textContent?.cleanText()?.takeIf(String::isNotEmpty)
        val headings = document.descendants().filterIsInstance<ElementNode>()
            .filter { it.localName.matches(Regex("h[1-6]")) }
            .mapNotNull { element -> element.textContent.cleanText().takeIf(String::isNotEmpty)?.let { ReaderHeading(element.localName.drop(1).toInt(), it) } }
            .take(128)
            .toList()
        val excluded = setOf("script", "style", "noscript", "nav", "footer", "header", "form", "button", "select", "option", "svg")
        val paragraphs = document.descendants().filterIsInstance<ElementNode>()
            .filter { it.localName in setOf("p", "article", "section", "blockquote", "li", "pre") }
            .filter { element -> generateSequence(element.parent) { it.parent }.filterIsInstance<ElementNode>().none { it.localName in excluded } }
            .map { it.textContent.cleanText() }
            .filter { it.length >= 24 }
            .distinct()
            .take(1_000)
            .toList()
            .ifEmpty {
                listOfNotNull(document.body?.textContent?.cleanText()?.takeIf { it.length >= 24 })
            }
        val words = paragraphs.sumOf { it.split(Regex("\\s+")).count(String::isNotBlank) }
        return ReaderArticle(title.take(512), byline?.take(256), headings, paragraphs, words, maxOf(1, ceil(words / 220.0).toInt()))
    }

    fun progress(scrollY: Double, contentHeight: Double, viewportHeight: Double): Double {
        val extent = (contentHeight - viewportHeight).coerceAtLeast(0.0)
        return if (extent == 0.0) 1.0 else (scrollY / extent).coerceIn(0.0, 1.0)
    }

    private fun String.cleanText(): String = replace(Regex("\\s+"), " ").trim()
}

class FindInPageEngine {
    fun search(text: String, query: String, caseSensitive: Boolean = false, selectedIndex: Int = 0): FindResult {
        if (query.isEmpty()) return FindResult(query, caseSensitive, emptyList(), -1)
        val source = if (caseSensitive) text else text.lowercase(Locale.ROOT)
        val needle = if (caseSensitive) query else query.lowercase(Locale.ROOT)
        val matches = ArrayList<FindMatch>()
        var cursor = 0
        while (cursor <= source.length - needle.length && matches.size < 10_000) {
            val index = source.indexOf(needle, cursor)
            if (index < 0) break
            matches += FindMatch(index, index + needle.length)
            cursor = index + maxOf(1, needle.length)
        }
        val selected = if (matches.isEmpty()) -1 else selectedIndex.mod(matches.size)
        return FindResult(query, caseSensitive, matches, selected)
    }

    fun next(result: FindResult): FindResult = if (result.matches.isEmpty()) result else result.copy(selectedIndex = (result.selectedIndex + 1).mod(result.matches.size))
    fun previous(result: FindResult): FindResult = if (result.matches.isEmpty()) result else result.copy(selectedIndex = (result.selectedIndex - 1).mod(result.matches.size))
}

enum class ImageFormat { PNG, JPEG, WEBP, GIF, SVG, UNKNOWN }

data class ImageMetadata(val format: ImageFormat, val width: Int?, val height: Int?, val animated: Boolean)

object ImageFormatDetector {
    fun detect(bytes: ByteArray): ImageMetadata {
        if (bytes.size >= 24 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))) {
            return ImageMetadata(ImageFormat.PNG, int32(bytes, 16), int32(bytes, 20), false)
        }
        if (bytes.size >= 4 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()) return jpeg(bytes)
        if (bytes.size >= 16 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" && bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP") {
            return ImageMetadata(ImageFormat.WEBP, null, null, false)
        }
        if (bytes.size >= 10 && bytes.copyOfRange(0, 3).toString(Charsets.US_ASCII) == "GIF") {
            val width = (bytes[6].toInt() and 0xff) or ((bytes[7].toInt() and 0xff) shl 8)
            val height = (bytes[8].toInt() and 0xff) or ((bytes[9].toInt() and 0xff) shl 8)
            return ImageMetadata(ImageFormat.GIF, width, height, true)
        }
        val prefix = bytes.take(512).toByteArray().toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (prefix.startsWith("<svg", ignoreCase = true) || prefix.startsWith("<?xml", ignoreCase = true) && prefix.contains("<svg", ignoreCase = true)) {
            val width = Regex("\\bwidth\\s*=\\s*[\"']?(\\d+)", RegexOption.IGNORE_CASE).find(prefix)?.groupValues?.get(1)?.toIntOrNull()
            val height = Regex("\\bheight\\s*=\\s*[\"']?(\\d+)", RegexOption.IGNORE_CASE).find(prefix)?.groupValues?.get(1)?.toIntOrNull()
            return ImageMetadata(ImageFormat.SVG, width, height, false)
        }
        return ImageMetadata(ImageFormat.UNKNOWN, null, null, false)
    }

    private fun jpeg(bytes: ByteArray): ImageMetadata {
        var index = 2
        while (index + 8 < bytes.size) {
            if (bytes[index] != 0xff.toByte()) { index++; continue }
            val marker = bytes[index + 1].toInt() and 0xff
            if (marker in 0xc0..0xc3 || marker in 0xc5..0xc7 || marker in 0xc9..0xcb || marker in 0xcd..0xcf) {
                val height = ((bytes[index + 5].toInt() and 0xff) shl 8) or (bytes[index + 6].toInt() and 0xff)
                val width = ((bytes[index + 7].toInt() and 0xff) shl 8) or (bytes[index + 8].toInt() and 0xff)
                return ImageMetadata(ImageFormat.JPEG, width, height, false)
            }
            if (index + 3 >= bytes.size) break
            val length = ((bytes[index + 2].toInt() and 0xff) shl 8) or (bytes[index + 3].toInt() and 0xff)
            if (length < 2) break
            index += 2 + length
        }
        return ImageMetadata(ImageFormat.JPEG, null, null, false)
    }

    private fun int32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or (bytes[offset + 3].toInt() and 0xff)
}
