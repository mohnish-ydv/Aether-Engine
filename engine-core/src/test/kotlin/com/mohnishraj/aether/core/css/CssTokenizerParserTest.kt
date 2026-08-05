package com.mohnishraj.aether.core.css

import com.mohnishraj.aether.core.css.parser.FontFaceRule
import com.mohnishraj.aether.core.css.parser.MediaRule
import com.mohnishraj.aether.core.css.parser.StyleRule
import com.mohnishraj.aether.core.css.token.CssTokenType
import com.mohnishraj.aether.core.css.token.CssTokenizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CssTokenizerParserTest {
    @Test fun tokenizesCoreSyntax() {
        val result = CssTokenizer().tokenize("@media screen { #id { width: 20px; opacity: .5 } }")
        assertTrue(result.tokens.any { it.type == CssTokenType.AT_KEYWORD && it.value == "media" })
        assertTrue(result.tokens.any { it.type == CssTokenType.HASH && it.value == "id" })
        assertTrue(result.tokens.any { it.type == CssTokenType.DIMENSION && it.unit == "px" })
    }

    @Test fun tokenizesPercentageAndExponent() {
        val result = CssTokenizer().tokenize("a{x:50%;y:1e2px}")
        assertEquals(50.0, result.tokens.first { it.type == CssTokenType.PERCENTAGE }.number)
        assertEquals(100.0, result.tokens.first { it.type == CssTokenType.DIMENSION }.number)
    }

    @Test fun decodesIdentifierEscape() {
        val result = CssTokenizer().tokenize(".\\66 oo { color: red }")
        assertTrue(result.tokens.any { it.type == CssTokenType.IDENT && it.value == "foo" })
    }

    @Test fun reportsUnterminatedComment() {
        val result = CssTokenizer().tokenize("a { color:red } /*")
        assertTrue(result.issues.any { it.code == "eof-in-comment" })
    }

    @Test fun parsesStyleRuleAndImportant() {
        val sheet = CssEngine().parse("a, .b { color: red; display: block !important }")
        val rule = sheet.rules.single() as StyleRule
        assertEquals(2, rule.selectors.size)
        assertTrue(rule.declarations.last().important)
    }

    @Test fun parsesMediaAndFontFace() {
        val sheet = CssEngine().parse("@media (min-width: 600px){a{x:1}} @font-face{font-family:X;src:url(x)}")
        assertTrue(sheet.rules[0] is MediaRule)
        assertTrue(sheet.rules[1] is FontFaceRule)
    }

    @Test fun recoversInvalidDeclaration() {
        val sheet = CssEngine().parse("a { color red; width: 10px }")
        assertTrue(sheet.issues.any { it.code == "invalid-declaration" })
        assertEquals("width", (sheet.rules.single() as StyleRule).declarations.single().name)
    }

    @Test fun preservesFunctionsAndQuotedSemicolon() {
        val sheet = CssEngine().parse("a { background: linear-gradient(red, blue); content: 'a;b' }")
        val declarations = (sheet.rules.single() as StyleRule).declarations
        assertEquals(2, declarations.size)
        assertTrue("linear-gradient" in declarations[0].value)
        assertEquals("'a;b'", declarations[1].value)
    }

    @Test fun rejectsOversizedInput() {
        val engine = CssEngine(limits = CssLimits(maxInputChars = 3))
        val result = runCatching { engine.parse("abcd") }
        assertFalse(result.isSuccess)
    }
}
