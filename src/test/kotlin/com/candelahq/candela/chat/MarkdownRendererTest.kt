package com.candelahq.candela.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [MarkdownRenderer] — pure function, no IntelliJ platform needed.
 */
class MarkdownRendererTest {
    @Test
    fun `bold text renders as HTML bold`() {
        val result = MarkdownRenderer.renderToHtml("Hello **world**")
        assertTrue(result.contains("<b>world</b>"), "Expected bold HTML, got: $result")
    }

    @Test
    fun `italic text renders as HTML italic`() {
        val result = MarkdownRenderer.renderToHtml("Hello *world*")
        assertTrue(result.contains("<i>world</i>"), "Expected italic HTML, got: $result")
    }

    @Test
    fun `inline code renders with code tag`() {
        val result = MarkdownRenderer.renderToHtml("Use `println()` here")
        assertTrue(result.contains("<code"), "Expected code tag, got: $result")
        assertTrue(result.contains("println()"), "Expected code content, got: $result")
    }

    @Test
    fun `inline code is protected from emphasis`() {
        // Backtick content with bold markers inside should NOT be rendered as bold
        val result = MarkdownRenderer.renderToHtml("Use `**not bold**` here")
        assertFalse(result.contains("<b>"), "Inline code should protect content from emphasis: $result")
        assertTrue(result.contains("**not bold**"), "Code content should be preserved literally: $result")
    }

    @Test
    fun `fenced code block renders as pre`() {
        val md = "```kotlin\nfun main() {}\n```"
        val result = MarkdownRenderer.renderToHtml(md)
        assertTrue(result.contains("<pre"), "Expected pre tag, got: $result")
        assertTrue(result.contains("fun main()"), "Expected code content, got: $result")
    }

    @Test
    fun `fenced code block with language label`() {
        val md = "```kotlin\nval x = 1\n```"
        val result = MarkdownRenderer.renderToHtml(md)
        assertTrue(result.contains("kotlin"), "Expected language label, got: $result")
    }

    @Test
    fun `code block content is HTML-escaped`() {
        val md = "```\n<script>alert('xss')</script>\n```"
        val result = MarkdownRenderer.renderToHtml(md)
        assertFalse(result.contains("<script>"), "Code blocks must escape HTML: $result")
        assertTrue(result.contains("&lt;script&gt;"), "Expected escaped HTML, got: $result")
    }

    @Test
    fun `headings render correctly`() {
        val result1 = MarkdownRenderer.renderToHtml("# Heading 1")
        assertTrue(result1.contains("<h3"), "Expected h3 for # heading, got: $result1")
        assertTrue(result1.contains("Heading 1"), "Expected heading text, got: $result1")
        assertFalse(result1.contains("<p><h3"), "Heading should not be wrapped in <p>, got: $result1")

        val result2 = MarkdownRenderer.renderToHtml("## Heading 2")
        assertTrue(result2.contains("<h4"), "Expected h4 for ## heading, got: $result2")
        assertFalse(result2.contains("<p><h4"), "Heading should not be wrapped in <p>, got: $result2")

        val result3 = MarkdownRenderer.renderToHtml("### Heading 3")
        assertTrue(result3.contains("<h5"), "Expected h5 for ### heading, got: $result3")
        assertFalse(result3.contains("<p><h5"), "Heading should not be wrapped in <p>, got: $result3")
    }

    @Test
    fun `unordered list renders`() {
        val md = "- item 1\n- item 2\n- item 3"
        val result = MarkdownRenderer.renderToHtml(md)
        assertTrue(result.contains("<ul>"), "Expected ul tag, got: $result")
        assertTrue(result.contains("<li>item 1</li>"), "Expected li tag, got: $result")
        assertTrue(result.contains("<li>item 2</li>"), "Expected second li tag, got: $result")
        assertTrue(result.contains("<li>item 3</li>"), "Expected third li tag, got: $result")
        assertFalse(result.contains("<p><ul>"), "List should not be wrapped in <p>, got: $result")
    }

    @Test
    fun `ordered list renders`() {
        val md = "1. first\n2. second\n3. third"
        val result = MarkdownRenderer.renderToHtml(md)
        assertTrue(result.contains("<ol>"), "Expected ol tag, got: $result")
        assertTrue(result.contains("<li>first</li>"), "Expected li tag, got: $result")
        assertTrue(result.contains("<li>second</li>"), "Expected second li tag, got: $result")
        assertTrue(result.contains("<li>third</li>"), "Expected third li tag, got: $result")
        assertFalse(result.contains("<p><ol>"), "List should not be wrapped in <p>, got: $result")
    }

    @Test
    fun `extractCodeBlocks returns code block contents`() {
        val md = "text\n```kotlin\nval x = 1\n```\nmore text\n```\nplain code\n```"
        val blocks = MarkdownRenderer.extractCodeBlocks(md)
        assertEquals(2, blocks.size, "Expected 2 code blocks")
        assertEquals("val x = 1", blocks[0])
        assertEquals("plain code", blocks[1])
    }

    @Test
    fun `wrapInHtmlDocument produces valid HTML structure`() {
        val html = MarkdownRenderer.wrapInHtmlDocument("<p>hello</p>", isUser = false)
        assertTrue(html.contains("<html>"), "Expected html tag")
        assertTrue(html.contains("<body>"), "Expected body tag")
        assertTrue(html.contains("<p>hello</p>"), "Expected content in body")
    }

    @Test
    fun `line breaks are converted`() {
        val result = MarkdownRenderer.renderToHtml("line 1\nline 2")
        assertTrue(result.contains("<br>"), "Expected br tag for newline, got: $result")
    }

    @Test
    fun `paragraphs on double newlines`() {
        val result = MarkdownRenderer.renderToHtml("para 1\n\npara 2")
        assertTrue(result.contains("</p><p"), "Expected paragraph break, got: $result")
    }

    @Test
    fun `empty input renders without errors`() {
        val result = MarkdownRenderer.renderToHtml("")
        assertTrue(result.contains("<p"), "Expected paragraph wrapper even for empty input")
    }

    @Test
    fun `mixed formatting`() {
        val md = "**bold** and *italic* and `code`"
        val result = MarkdownRenderer.renderToHtml(md)
        assertTrue(result.contains("<b>bold</b>"), "Expected bold")
        assertTrue(result.contains("<i>italic</i>"), "Expected italic")
        assertTrue(result.contains("<code"), "Expected code")
    }

    @Test
    fun `code fence language label is HTML-escaped`() {
        // The regex only captures \w* as language, so <img onerror=alert(1)> won't be
        // a valid fenced block language. The raw text falls through and gets HTML-escaped.
        val md = "```<img onerror=alert(1)>\ncode\n```"
        val result = MarkdownRenderer.renderToHtml(md)
        assertTrue(result.contains("&lt;img"), "XSS payload in language position must be escaped, got: $result")
        assertFalse(result.contains("<img"), "Raw <img> tag must not appear in output, got: $result")
    }

    @Test
    fun `code fence language with special characters is escaped`() {
        // Characters like &, ", <, > are not word characters, so they won't be captured
        // as a language label. They should still be HTML-escaped in the output.
        val md = "```&\"<>\ncode\n```"
        val result = MarkdownRenderer.renderToHtml(md)
        assertTrue(result.contains("&amp;"), "Ampersand must be escaped, got: $result")
        assertTrue(result.contains("&quot;"), "Double quote must be escaped, got: $result")
        assertTrue(result.contains("&lt;"), "Less-than must be escaped, got: $result")
        assertTrue(result.contains("&gt;"), "Greater-than must be escaped, got: $result")
    }

    @Test
    fun `nested code blocks handle backtick edge cases`() {
        // Backtick characters inside a code block should be preserved literally
        val md = "```kotlin\nval s = \"`hello`\"\n```"
        val result = MarkdownRenderer.renderToHtml(md)
        assertTrue(result.contains("<pre"), "Expected pre tag, got: $result")
        assertTrue(result.contains("`hello`"), "Backticks inside code block should be preserved, got: $result")
    }

    @Test
    fun `multiple code blocks with different languages`() {
        val md = "```kotlin\nfun greet() = 42\n```\n\n```python\ndef greet(): pass\n```\n\n```rust\nfn greet() -> i32 { 42 }\n```"
        val result = MarkdownRenderer.renderToHtml(md)
        assertTrue(result.contains("kotlin"), "Expected kotlin language label, got: $result")
        assertTrue(result.contains("python"), "Expected python language label, got: $result")
        assertTrue(result.contains("rust"), "Expected rust language label, got: $result")
        // Each content string is unique to its block — no substring overlap
        assertTrue(result.contains("fun greet() = 42"), "Expected kotlin code content, got: $result")
        assertTrue(result.contains("def greet(): pass"), "Expected python code content, got: $result")
        assertTrue(result.contains("fn greet() -&gt; i32 { 42 }"), "Expected rust code content (HTML-escaped), got: $result")
    }

    @Test
    fun `XSS in inline text is escaped`() {
        val result = MarkdownRenderer.renderToHtml("<script>alert('xss')</script>")
        assertFalse(result.contains("<script>"), "Script tag must be escaped in inline text, got: $result")
        assertTrue(result.contains("&lt;script&gt;"), "Expected escaped script tag, got: $result")
        assertTrue(result.contains("&lt;/script&gt;"), "Expected escaped closing script tag, got: $result")
    }

    @Test
    fun `bold and italic inside code blocks are not processed`() {
        val md = "```\n**bold** and *italic*\n```"
        val result = MarkdownRenderer.renderToHtml(md)
        assertFalse(result.contains("<b>"), "Bold should not be processed inside code blocks, got: $result")
        assertFalse(result.contains("<i>"), "Italic should not be processed inside code blocks, got: $result")
        assertTrue(result.contains("**bold**"), "Raw bold markers should be preserved in code, got: $result")
        assertTrue(result.contains("*italic*"), "Raw italic markers should be preserved in code, got: $result")
    }

    @Test
    fun `empty language label in code fence`() {
        val md = "```\nsome code\n```"
        val result = MarkdownRenderer.renderToHtml(md)
        assertTrue(result.contains("<pre"), "Expected pre tag for code block, got: $result")
        assertTrue(result.contains("some code"), "Expected code content, got: $result")
        // When language is empty, no language label div should be emitted
        assertFalse(
            result.contains("font-size: 9px") && result.contains("<div"),
            "No language label div should be emitted for empty language, got: $result",
        )
    }
}
