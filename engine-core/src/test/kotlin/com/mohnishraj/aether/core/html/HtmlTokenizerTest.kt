package com.mohnishraj.aether.core.html

import com.mohnishraj.aether.core.html.token.AttributeQuote
import com.mohnishraj.aether.core.html.token.HtmlToken
import com.mohnishraj.aether.core.html.token.HtmlTokenizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HtmlTokenizerTest {
    @Test fun tokenizesStartEndAndText() {
        val result = HtmlTokenizer("<p>Hello</p>").tokenize()
        assertIs<HtmlToken.StartTag>(result.tokens[0])
        assertEquals("Hello", assertIs<HtmlToken.Text>(result.tokens[1]).data)
        assertEquals("p", assertIs<HtmlToken.EndTag>(result.tokens[2]).name)
        assertIs<HtmlToken.Eof>(result.tokens.last())
    }

    @Test fun normalizesTagAndAttributeNames() {
        val tag = assertIs<HtmlToken.StartTag>(HtmlTokenizer("<DIV DATA-X=One>").tokenize().tokens.first())
        assertEquals("div", tag.name)
        assertEquals("data-x", tag.attributes.single().name)
        assertEquals("One", tag.attributes.single().value)
        assertEquals(AttributeQuote.UNQUOTED, tag.attributes.single().quote)
    }

    @Test fun preservesFirstDuplicateAttribute() {
        val result = HtmlTokenizer("<p id='first' ID='second'>").tokenize()
        val tag = assertIs<HtmlToken.StartTag>(result.tokens.first())
        assertEquals("first", tag.attributes.single().value)
        assertTrue(result.issues.any { it.code == "duplicate-attribute" })
    }

    @Test fun decodesNamedAndNumericReferences() {
        val text = assertIs<HtmlToken.Text>(HtmlTokenizer("&lt;&#65;&#x1F680;&amp;").tokenize().tokens.first())
        assertEquals("<A🚀&", text.data)
    }

    @Test fun reportsMissingReferenceSemicolon() {
        val result = HtmlTokenizer("A &copy B").tokenize()
        assertEquals("A © B", assertIs<HtmlToken.Text>(result.tokens.first()).data)
        assertTrue(result.issues.any { it.code == "missing-character-reference-semicolon" })
    }

    @Test fun doesNotDecodeAmbiguousAttributeReference() {
        val tag = assertIs<HtmlToken.StartTag>(HtmlTokenizer("<a x='&copy=1'>").tokenize().tokens.first())
        assertEquals("&copy=1", tag.attributes.single().value)
    }

    @Test fun replacesInvalidNumericReference() {
        val result = HtmlTokenizer("&#0;").tokenize()
        assertEquals("�", assertIs<HtmlToken.Text>(result.tokens.first()).data)
        assertTrue(result.issues.any { it.code == "invalid-character-reference" })
    }

    @Test fun parsesComments() {
        val comment = assertIs<HtmlToken.Comment>(HtmlTokenizer("<!-- hello -->").tokenize().tokens.first())
        assertEquals(" hello ", comment.data)
    }

    @Test fun recoversUnclosedComment() {
        val result = HtmlTokenizer("<!--open").tokenize()
        assertEquals("open", assertIs<HtmlToken.Comment>(result.tokens.first()).data)
        assertTrue(result.issues.any { it.code == "eof-in-comment" })
    }

    @Test fun parsesHtmlDoctype() {
        val token = assertIs<HtmlToken.Doctype>(HtmlTokenizer("<!DOCTYPE html>").tokenize().tokens.first())
        assertEquals("html", token.name)
        assertFalse(token.forceQuirks)
    }

    @Test fun parsesPublicAndSystemDoctypeIdentifiers() {
        val token = assertIs<HtmlToken.Doctype>(
            HtmlTokenizer("<!DOCTYPE html PUBLIC '-//W3C//DTD XHTML 1.0 Transitional//EN' 'about:legacy-compat'>").tokenize().tokens.first()
        )
        assertEquals("-//W3C//DTD XHTML 1.0 Transitional//EN", token.publicIdentifier)
        assertEquals("about:legacy-compat", token.systemIdentifier)
    }

    @Test fun separatesRawTextFromRcdata() {
        val tokens = HtmlTokenizer("<script>a < b &amp;</script><textarea>A&amp;B</textarea>").tokenize().tokens
        val texts = tokens.filterIsInstance<HtmlToken.Text>()
        assertEquals("a < b &amp;", texts[0].data)
        assertEquals("A&B", texts[1].data)
    }

    @Test fun plaintextConsumesRemainder() {
        val tokens = HtmlTokenizer("<plaintext><b>not a tag").tokenize().tokens
        assertEquals("<b>not a tag", tokens.filterIsInstance<HtmlToken.Text>().single().data)
        assertTrue(tokens.none { it is HtmlToken.StartTag && it.name == "b" })
    }

    @Test fun recognizesSelfClosingSyntax() {
        val tag = assertIs<HtmlToken.StartTag>(HtmlTokenizer("<custom x='1'/>").tokenize().tokens.first())
        assertTrue(tag.selfClosing)
    }

    @Test fun malformedEndTagBecomesRecoverableInput() {
        val result = HtmlTokenizer("</  >text").tokenize()
        assertTrue(result.issues.any { it.code == "invalid-end-tag" })
        assertTrue(result.tokens.filterIsInstance<HtmlToken.Text>().joinToString("") { it.data }.startsWith("<"))
    }

    @Test fun replacesLiteralNullCharacters() {
        val result = HtmlTokenizer("a\u0000b").tokenize()
        assertEquals("a�b", assertIs<HtmlToken.Text>(result.tokens.first()).data)
        assertTrue(result.issues.any { it.code == "null-character" })
    }

    @Test fun enforcesInputLimit() {
        val result = HtmlTokenizer("12345", HtmlLimits(maxInputChars = 4)).tokenize()
        assertTrue(result.issues.any { it.code == "input-too-large" && it.severity == HtmlIssueSeverity.ERROR })
        assertEquals(1, result.tokens.size)
    }

    @Test fun sourceSpansTrackOffsetsAndLines() {
        val tokens = HtmlTokenizer("x\n<p>y</p>").tokenize().tokens
        val tag = tokens.filterIsInstance<HtmlToken.StartTag>().single()
        assertEquals(2, tag.sourceSpan.start.line)
        assertEquals(1, tag.sourceSpan.start.column)
        assertEquals(2, tag.sourceSpan.start.offset)
    }
}
