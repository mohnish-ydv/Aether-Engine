package com.mohnishraj.aether.core.css.parser

import com.mohnishraj.aether.core.css.CssIssue
import com.mohnishraj.aether.core.css.CssOrigin
import com.mohnishraj.aether.core.css.selector.ComplexSelector

data class CssDeclaration(
    val name: String,
    val value: String,
    val important: Boolean,
    val sourceOrder: Int
)

sealed interface CssRule { val sourceOrder: Int }

data class StyleRule(
    val selectorText: String,
    val selectors: List<ComplexSelector>,
    val declarations: List<CssDeclaration>,
    override val sourceOrder: Int
) : CssRule

data class MediaRule(
    val query: String,
    val rules: List<CssRule>,
    override val sourceOrder: Int
) : CssRule

data class FontFaceRule(
    val declarations: List<CssDeclaration>,
    override val sourceOrder: Int
) : CssRule

data class SupportsRule(
    val condition: String,
    val rules: List<CssRule>,
    override val sourceOrder: Int
) : CssRule

data class CssStyleSheet(
    val rules: List<CssRule>,
    val issues: List<CssIssue>,
    val origin: CssOrigin = CssOrigin.AUTHOR,
    val sourceUrl: String? = null,
    val tokenCount: Int = 0
)
