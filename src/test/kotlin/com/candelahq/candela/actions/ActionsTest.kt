package com.candelahq.candela.actions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the [buildCodeFence] utility function.
 */
class ActionsTest {
    @Test
    fun `basic code fence with language`() {
        val result = buildCodeFence("val x = 1", "kotlin")
        assertEquals("```kotlin\nval x = 1\n```", result)
    }

    @Test
    fun `basic code fence without language`() {
        val result = buildCodeFence("some code")
        assertEquals("```\nsome code\n```", result)
    }

    @Test
    fun `code containing triple backticks uses longer fence`() {
        val code = "Here is a code block:\n```\ninner code\n```"
        val result = buildCodeFence(code, "markdown")
        // Should use 4+ backticks to avoid breaking
        assertTrue(result.startsWith("````"), "Should use at least 4 backticks, got: $result")
        assertTrue(result.contains(code), "Original code should be preserved")
    }

    @Test
    fun `code containing quadruple backticks uses even longer fence`() {
        val code = "````\ndeep nesting\n````"
        val result = buildCodeFence(code)
        assertTrue(result.startsWith("`````"), "Should use at least 5 backticks, got: $result")
    }

    @Test
    fun `empty code produces valid fence`() {
        val result = buildCodeFence("")
        assertEquals("```\n\n```", result)
    }

    @Test
    fun `code with no backticks uses standard triple fence`() {
        val code = "println(\"hello world\")"
        val result = buildCodeFence(code, "kotlin")
        assertTrue(result.startsWith("```kotlin"), "Should use standard triple fence")
        assertTrue(result.endsWith("```"), "Should end with standard triple fence")
    }

    @Test
    fun `multiline code preserves all lines`() {
        val code = "line 1\nline 2\nline 3"
        val result = buildCodeFence(code, "text")
        val lines = result.split("\n")
        assertEquals(5, lines.size, "Expected opening fence + 3 lines + closing fence")
        assertEquals("```text", lines[0])
        assertEquals("line 1", lines[1])
        assertEquals("line 2", lines[2])
        assertEquals("line 3", lines[3])
        assertEquals("```", lines[4])
    }
}
