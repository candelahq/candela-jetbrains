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

        val result2 = MarkdownRenderer.renderToHtml("## Heading 2")
        assertTrue(result2.contains("<h4"), "Expected h4 for ## heading, got: $result2")

        val result3 = MarkdownRenderer.renderToHtml("### Heading 3")
        assertTrue(result3.contains("<h5"), "Expected h5 for ### heading, got: $result3")
    }

    @Test
    fun `unordered list renders`() {
        val md = "- item 1\n- item 2\n- item 3"
        val result = MarkdownRenderer.renderToHtml(md)
        assertTrue(result.contains("<ul>"), "Expected ul tag, got: $result")
        assertTrue(result.contains("<li>item 1</li>"), "Expected li tag, got: $result")
    }

    @Test
    fun `ordered list renders`() {
        val md = "1. first\n2. second\n3. third"
        val result = MarkdownRenderer.renderToHtml(md)
        assertTrue(result.contains("<ol>"), "Expected ol tag, got: $result")
        assertTrue(result.contains("<li>first</li>"), "Expected li tag, got: $result")
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
}
