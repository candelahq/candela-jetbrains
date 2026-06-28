package com.candelahq.candela.chat

import com.intellij.ui.JBColor
import java.awt.Color

/**
 * Converts a subset of Markdown to HTML suitable for JTextPane/HTMLEditorKit.
 *
 * Supports: fenced code blocks, inline code, bold, italic, headings,
 * unordered/ordered lists, and line breaks. HTML entities are escaped.
 */
object MarkdownRenderer {
    private val FENCED_CODE = Regex("```(\\w*)\\s*\\n(.*?)\\n?```", RegexOption.DOT_MATCHES_ALL)
    private val INLINE_CODE = Regex("`([^`]+)`")
    private val BOLD = Regex("\\*\\*(.+?)\\*\\*")
    private val ITALIC = Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)")
    private val HEADING3 = Regex("^###\\s+(.+)$", RegexOption.MULTILINE)
    private val HEADING2 = Regex("^##\\s+(.+)$", RegexOption.MULTILINE)
    private val HEADING1 = Regex("^#\\s+(.+)$", RegexOption.MULTILINE)
    private val ORDERED_LIST_ITEM = Regex("^\\d+\\.\\s+.*")
    private val ORDERED_LIST_PREFIX = Regex("^\\d+\\.\\s+")

    /**
     * Render markdown text to an HTML fragment (no <html>/<body> wrapper).
     */
    fun renderToHtml(markdown: String): String {
        // Step 1: Extract fenced code blocks and replace with placeholders
        val codeBlocks = mutableListOf<Pair<String, String>>() // language, code
        var processed =
            FENCED_CODE.replace(markdown) { match ->
                val lang = match.groupValues[1]
                val code = match.groupValues[2]
                val index = codeBlocks.size
                codeBlocks.add(Pair(lang, code))
                "\u0000CODEBLOCK_$index\u0000"
            }

        // Step 2: Escape HTML entities in non-code text
        processed = escapeHtml(processed)

        // Step 3: Protect inline code before applying emphasis
        val inlineCodeBlocks = mutableListOf<String>()
        processed =
            INLINE_CODE.replace(processed) { match ->
                val index = inlineCodeBlocks.size
                inlineCodeBlocks.add(match.groupValues[1])
                "\u0000INLINECODE_$index\u0000"
            }

        // Step 4: Apply inline formatting (safe — inline code is protected)
        processed = BOLD.replace(processed) { "<b>${it.groupValues[1]}</b>" }
        processed = ITALIC.replace(processed) { "<i>${it.groupValues[1]}</i>" }

        // Step 5: Restore inline code
        for ((index, code) in inlineCodeBlocks.withIndex()) {
            processed =
                processed.replace(
                    "\u0000INLINECODE_$index\u0000",
                    "<code style=\"background-color: ${colorToHex(inlineCodeBg())}; " +
                        "padding: 1px 4px; border-radius: 3px; font-family: monospace;\">" +
                        "$code</code>",
                )
        }

        // Step 6: Headings
        processed = HEADING3.replace(processed) { "<h5 style=\"margin: 8px 0 4px 0;\">${it.groupValues[1]}</h5>" }
        processed = HEADING2.replace(processed) { "<h4 style=\"margin: 10px 0 4px 0;\">${it.groupValues[1]}</h4>" }
        processed = HEADING1.replace(processed) { "<h3 style=\"margin: 12px 0 6px 0;\">${it.groupValues[1]}</h3>" }

        // Step 7: Lists — convert contiguous lines starting with "- " or "* " or "1. "
        processed = processLists(processed)

        // Step 8: Paragraphs / line breaks
        processed = processed.replace("\n\n", "</p><p style=\"margin: 4px 0;\">")
        processed = processed.replace("\n", "<br>")

        // Step 9: Restore code blocks
        for ((index, block) in codeBlocks.withIndex()) {
            val (lang, code) = block
            val langLabel =
                if (lang.isNotEmpty()) {
                    "<div style=\"font-size: 9px; color: ${colorToHex(langLabelColor())}; " +
                        "padding: 2px 8px; font-family: sans-serif;\">${escapeHtml(lang)}</div>"
                } else {
                    ""
                }
            val codeHtml =
                "<div style=\"background-color: ${colorToHex(codeBlockBg())}; " +
                    "border-radius: 6px; margin: 6px 0; overflow: hidden;\">" +
                    "$langLabel<pre style=\"margin: 0; padding: 8px; font-family: monospace; " +
                    "font-size: 12px; white-space: pre-wrap; word-wrap: break-word;\">" +
                    "${escapeHtml(code)}</pre></div>"
            processed = processed.replace("\u0000CODEBLOCK_$index\u0000", codeHtml)
        }

        return "<p style=\"margin: 4px 0;\">$processed</p>"
    }

    /**
     * Extract raw code block contents from markdown (for Copy/Insert actions).
     */
    fun extractCodeBlocks(markdown: String): List<String> = FENCED_CODE.findAll(markdown).map { it.groupValues[2] }.toList()

    /**
     * Build a full HTML document with styling for JEditorPane.
     */
    fun wrapInHtmlDocument(
        htmlFragment: String,
        isUser: Boolean = false,
    ): String {
        val textColor = colorToHex(if (isUser) userTextColor() else assistantTextColor())
        return """
            <html>
            <head>
            <style>
                body {
                    font-family: -apple-system, 'Segoe UI', sans-serif;
                    font-size: 13px;
                    color: $textColor;
                    margin: 0;
                    padding: 0;
                }
                p { margin: 4px 0; }
                code { font-family: 'JetBrains Mono', monospace; font-size: 12px; }
                pre { font-family: 'JetBrains Mono', monospace; font-size: 12px; }
                b { font-weight: bold; }
                ul, ol { margin: 4px 0; padding-left: 20px; }
                li { margin: 2px 0; }
            </style>
            </head>
            <body>$htmlFragment</body>
            </html>
            """.trimIndent()
    }

    // ── List processing ──────────────────────────────────────────────────

    private fun processLists(text: String): String {
        val lines = text.split("\n")
        val result = StringBuilder()
        var inUl = false
        var inOl = false

        for (line in lines) {
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    if (!inUl) {
                        if (inOl) {
                            result.append("</ol>")
                            inOl = false
                        }
                        result.append("<ul>")
                        inUl = true
                    }
                    result.append("<li>${trimmed.removePrefix("- ").removePrefix("* ")}</li>")
                }
                trimmed.matches(ORDERED_LIST_ITEM) -> {
                    if (!inOl) {
                        if (inUl) {
                            result.append("</ul>")
                            inUl = false
                        }
                        result.append("<ol>")
                        inOl = true
                    }
                    val content = trimmed.replaceFirst(ORDERED_LIST_PREFIX, "")
                    result.append("<li>$content</li>")
                }
                else -> {
                    if (inUl) {
                        result.append("</ul>")
                        inUl = false
                    }
                    if (inOl) {
                        result.append("</ol>")
                        inOl = false
                    }
                    result.append(line).append("\n")
                }
            }
        }
        if (inUl) result.append("</ul>")
        if (inOl) result.append("</ol>")

        return result.toString().trimEnd('\n')
    }

    // ── HTML helpers ──────────────────────────────────────────────────────

    private fun escapeHtml(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    fun colorToHex(color: Color): String = "#%02x%02x%02x".format(color.red, color.green, color.blue)

    // ── Theme-aware colors ───────────────────────────────────────────────

    fun codeBlockBg(): Color = JBColor(Color(245, 245, 245), Color(43, 43, 43))

    fun inlineCodeBg(): Color = JBColor(Color(235, 235, 235), Color(55, 55, 55))

    private fun langLabelColor(): Color = JBColor(Color(130, 130, 130), Color(150, 150, 150))

    private fun userTextColor(): Color = JBColor(Color(30, 30, 30), Color(220, 220, 220))

    private fun assistantTextColor(): Color = JBColor(Color(30, 30, 30), Color(210, 210, 210))

    fun userBubbleBg(): Color = JBColor(Color(220, 232, 250), Color(45, 58, 78))

    fun assistantBubbleBg(): Color = JBColor(Color(243, 243, 243), Color(50, 50, 50))

    fun inputAreaBg(): Color = JBColor(Color(255, 255, 255), Color(45, 45, 45))

    fun toolbarBg(): Color = JBColor(Color(248, 248, 248), Color(40, 40, 40))
}
