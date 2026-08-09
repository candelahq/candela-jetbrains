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
    // "fun hello() { println(\"world\") }"
    //  0123456789012345678901234567890
    //                ^              ^
    //               14             30  (exclusive end)

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
        // Select "println("world")" (offset 14 to 30, exclusive end)
        editor.selectionModel.setSelection(14, 30)
        val ctx = captureSelectionContext(editor)
        assertNotNull(ctx)
        assertTrue(ctx!!.marker.isValid)
        assertEquals(14, ctx.marker.startOffset)
        assertEquals(30, ctx.marker.endOffset)
    }

    fun `test RangeMarker adjusts when text is inserted above selection`() {
        myFixture.configureByText("Test.kt", "fun hello() { println(\"world\") }")
        val editor = myFixture.editor
        editor.selectionModel.setSelection(14, 30)
        val ctx = captureSelectionContext(editor)!!

        // Insert text ABOVE the selection
        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(0, "// comment\n")
        }

        // RangeMarker should have shifted
        assertTrue(ctx.marker.isValid)
        assertEquals(14 + "// comment\n".length, ctx.marker.startOffset)
        assertEquals(30 + "// comment\n".length, ctx.marker.endOffset)
    }

    fun `test RangeMarker tracks replacement correctly`() {
        myFixture.configureByText("Test.kt", "fun hello() { println(\"world\") }")
        val editor = myFixture.editor
        editor.selectionModel.setSelection(14, 30)
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
        editor.selectionModel.setSelection(14, 30)
        val ctx = captureSelectionContext(editor)!!

        // Delete the entire document
        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.deleteString(0, editor.document.textLength)
        }

        // Marker should still be accessible without throwing
        assertTrue(ctx.marker.startOffset >= 0)
    }
}
