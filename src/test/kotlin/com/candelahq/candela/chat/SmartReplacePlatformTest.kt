package com.candelahq.candela.chat

import com.candelahq.candela.CandelaPlatformTestCase
import com.candelahq.candela.actions.captureSelectionContext
import com.intellij.openapi.command.WriteCommandAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Platform integration tests for Smart Replace Selection.
 *
 * Tests [captureSelectionContext] and the replace workflow
 * using a real IntelliJ editor fixture.
 */
class SmartReplacePlatformTest : CandelaPlatformTestCase() {
    fun `test captureSelectionContext returns null without selection`() {
        myFixture.configureByText("Test.kt", "fun hello() { println(\"world\") }")
        val editor = myFixture.editor
        // No selection set
        val ctx = captureSelectionContext(editor)
        assertNull(ctx)
    }

    fun `test captureSelectionContext captures selection with RangeMarker`() {
        myFixture.configureByText("Test.kt", "fun hello() { println(\"world\") }")
        val editor = myFixture.editor
        // Select "println("world")" (offset 15 to 31)
        editor.selectionModel.setSelection(15, 31)
        val ctx = captureSelectionContext(editor)
        assertNotNull(ctx)
        assertTrue(ctx!!.marker.isValid)
        assertEquals(15, ctx.marker.startOffset)
        assertEquals(31, ctx.marker.endOffset)
    }

    fun `test RangeMarker adjusts when text is inserted above selection`() {
        myFixture.configureByText("Test.kt", "fun hello() { println(\"world\") }")
        val editor = myFixture.editor
        editor.selectionModel.setSelection(15, 31)
        val ctx = captureSelectionContext(editor)!!

        // Insert text ABOVE the selection
        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(0, "// comment\n")
        }

        // RangeMarker should have shifted
        assertTrue(ctx.marker.isValid)
        assertEquals(15 + "// comment\n".length, ctx.marker.startOffset)
        assertEquals(31 + "// comment\n".length, ctx.marker.endOffset)
    }

    fun `test RangeMarker tracks replacement correctly`() {
        myFixture.configureByText("Test.kt", "fun hello() { println(\"world\") }")
        val editor = myFixture.editor
        editor.selectionModel.setSelection(15, 31)
        val ctx = captureSelectionContext(editor)!!

        // Replace the selection
        WriteCommandAction.runWriteCommandAction(project) {
            ctx.document.replaceString(
                ctx.marker.startOffset,
                ctx.marker.endOffset,
                "log.info(\"hello\")",
            )
        }

        // Verify the document was updated
        assertEquals(
            "fun hello() { log.info(\"hello\") }",
            editor.document.text,
        )
    }

    fun `test RangeMarker handles full document deletion`() {
        myFixture.configureByText("Test.kt", "fun hello() { println(\"world\") }")
        val editor = myFixture.editor
        editor.selectionModel.setSelection(15, 31)
        val ctx = captureSelectionContext(editor)!!

        // Delete the entire document
        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.deleteString(0, editor.document.textLength)
        }

        // Marker should still be accessible without throwing
        assertTrue(ctx.marker.startOffset >= 0)
    }
}
