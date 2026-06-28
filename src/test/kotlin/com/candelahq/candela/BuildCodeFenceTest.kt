package com.candelahq.candela

import com.candelahq.candela.actions.buildCodeFence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [buildCodeFence] utility function.
 */
class BuildCodeFenceTest {
    @Test
    fun `simple code fence with language`() {
        val result = buildCodeFence("val x = 1", "kotlin")
        assertEquals("```kotlin\nval x = 1\n```", result)
    }

    @Test
    fun `code fence without language`() {
        val result = buildCodeFence("hello")
        assertEquals("```\nhello\n```", result)
    }

    @Test
    fun `code containing triple backticks uses longer fence`() {
        val code = "val s = \"\"\"```\"\"\""
        val result = buildCodeFence(code, "kotlin")
        assertTrue(result.startsWith("````kotlin\n"))
        assertTrue(result.endsWith("\n````"))
    }

    @Test
    fun `code containing quadruple backticks uses even longer fence`() {
        val code = "some ````code```` here"
        val result = buildCodeFence(code, "text")
        assertTrue(result.startsWith("`````text\n"))
        assertTrue(result.endsWith("\n`````"))
    }

    @Test
    fun `empty code produces valid fence`() {
        val result = buildCodeFence("", "java")
        assertEquals("```java\n\n```", result)
    }

    @Test
    fun `multiline code preserved`() {
        val code = "line1\nline2\nline3"
        val result = buildCodeFence(code, "py")
        assertEquals("```py\nline1\nline2\nline3\n```", result)
    }
}
