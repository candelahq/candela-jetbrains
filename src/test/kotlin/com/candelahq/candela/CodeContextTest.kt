package com.candelahq.candela

import com.candelahq.candela.actions.CodeContext
import com.candelahq.candela.actions.formatContextHeader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [CodeContext] formatting utilities.
 */
class CodeContextTest {
    @Test
    fun `formatContextHeader includes file path and language`() {
        val ctx =
            CodeContext(
                fileName = "FooService.kt",
                filePath = "src/main/kotlin/com/example/FooService.kt",
                language = "kotlin",
                imports = "import kotlinx.coroutines.*",
                enclosingClass = "FooService",
                enclosingFunction = "processItems",
                lineRange = Pair(42, 58),
            )

        val header = formatContextHeader(ctx)

        assertTrue(header.contains("src/main/kotlin/com/example/FooService.kt"))
        assertTrue(header.contains("(kotlin)"))
        assertTrue(header.contains("Class: FooService"))
        assertTrue(header.contains("Function: processItems"))
        assertTrue(header.contains("Lines: 42-58"))
        assertTrue(header.contains("import kotlinx.coroutines.*"))
    }

    @Test
    fun `formatContextHeader omits null class and function`() {
        val ctx =
            CodeContext(
                fileName = "utils.py",
                filePath = "src/utils.py",
                language = "python",
                imports = "from typing import List",
                enclosingClass = null,
                enclosingFunction = null,
                lineRange = Pair(10, 20),
            )

        val header = formatContextHeader(ctx)

        assertTrue(header.contains("src/utils.py"))
        assertTrue(header.contains("(python)"))
        assertTrue(!header.contains("Class:"))
        assertTrue(!header.contains("Function:"))
        assertTrue(header.contains("Lines: 10-20"))
        assertTrue(header.contains("from typing import List"))
    }

    @Test
    fun `formatContextHeader omits imports section when empty`() {
        val ctx =
            CodeContext(
                fileName = "Main.java",
                filePath = "Main.java",
                language = "java",
                imports = "",
                enclosingClass = "Main",
                enclosingFunction = null,
                lineRange = null,
            )

        val header = formatContextHeader(ctx)

        assertTrue(header.contains("Main.java"))
        assertTrue(header.contains("Class: Main"))
        assertTrue(!header.contains("Imports:"))
    }

    @Test
    fun `formatContextHeader with full context produces expected structure`() {
        val ctx =
            CodeContext(
                fileName = "Bar.kt",
                filePath = "src/Bar.kt",
                language = "kotlin",
                imports = "package com.example\nimport java.util.List",
                enclosingClass = "Bar",
                enclosingFunction = "doWork",
                lineRange = Pair(5, 15),
            )

        val header = formatContextHeader(ctx)
        val lines = header.lines()

        // First line: file path
        assertEquals("File: `src/Bar.kt` (kotlin)", lines[0])
        // Second line: location parts joined by pipe
        assertEquals("Class: Bar | Function: doWork | Lines: 5-15", lines[1])
    }

    @Test
    fun `formatContextHeader line range only`() {
        val ctx =
            CodeContext(
                fileName = "script.sh",
                filePath = "scripts/script.sh",
                language = "bash",
                imports = "",
                enclosingClass = null,
                enclosingFunction = null,
                lineRange = Pair(1, 3),
            )

        val header = formatContextHeader(ctx)

        assertTrue(header.contains("Lines: 1-3"))
        assertTrue(!header.contains("Class:"))
        assertTrue(!header.contains("Function:"))
    }
}
