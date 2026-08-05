package com.mohnishraj.aether.core.html.token

import com.mohnishraj.aether.core.html.HtmlIssue
import com.mohnishraj.aether.core.html.SourceSpan

enum class AttributeQuote { DOUBLE, SINGLE, UNQUOTED, EMPTY }

data class HtmlAttributeToken(
    val name: String,
    val value: String,
    val quote: AttributeQuote,
    val sourceSpan: SourceSpan
)

sealed interface HtmlToken {
    val sourceSpan: SourceSpan

    data class Doctype(
        val name: String,
        val publicIdentifier: String?,
        val systemIdentifier: String?,
        val forceQuirks: Boolean,
        override val sourceSpan: SourceSpan
    ) : HtmlToken

    data class StartTag(
        val name: String,
        val attributes: List<HtmlAttributeToken>,
        val selfClosing: Boolean,
        override val sourceSpan: SourceSpan
    ) : HtmlToken

    data class EndTag(
        val name: String,
        override val sourceSpan: SourceSpan
    ) : HtmlToken

    data class Text(
        val data: String,
        override val sourceSpan: SourceSpan
    ) : HtmlToken

    data class Comment(
        val data: String,
        override val sourceSpan: SourceSpan
    ) : HtmlToken

    data class Eof(override val sourceSpan: SourceSpan) : HtmlToken
}

data class HtmlTokenizationResult(
    val tokens: List<HtmlToken>,
    val issues: List<HtmlIssue>
)
