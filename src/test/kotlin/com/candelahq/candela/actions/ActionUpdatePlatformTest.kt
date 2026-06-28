package com.candelahq.candela.actions

import com.candelahq.candela.CandelaPlatformTestCase
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation

/**
 * Platform tests for action `update()` methods.
 *
 * Verifies enable/disable logic for [AskCandelaAction],
 * [ExplainCodeAction], and [FocusChatToolWindowAction] using
 * the IntelliJ test fixture's real editor and project.
 */
class ActionUpdatePlatformTest : CandelaPlatformTestCase() {
    // ── AskCandelaAction ────────────────────────────────────────────

    fun testAskCandelaDisabledWithoutSelection() {
        myFixture.configureByText("Test.kt", "val x = 1\n")
        myFixture.editor.selectionModel.removeSelection()

        val action = AskCandelaAction()
        val event = buildEvent(withEditor = true)
        action.update(event)

        assertFalse(
            "AskCandelaAction should be disabled without selection",
            event.presentation.isEnabledAndVisible,
        )
    }

    fun testAskCandelaEnabledWithSelection() {
        myFixture.configureByText("Test.kt", "val x = 1\n")
        myFixture.editor.selectionModel.setSelection(0, 9) // select "val x = 1"

        val action = AskCandelaAction()
        val event = buildEvent(withEditor = true)
        action.update(event)

        assertTrue(
            "AskCandelaAction should be enabled with selection",
            event.presentation.isEnabledAndVisible,
        )
    }

    // ── ExplainCodeAction ───────────────────────────────────────────

    fun testExplainCodeDisabledWithoutSelection() {
        myFixture.configureByText("Test.kt", "val x = 1\n")
        myFixture.editor.selectionModel.removeSelection()

        val action = ExplainCodeAction()
        val event = buildEvent(withEditor = true)
        action.update(event)

        assertFalse(
            "ExplainCodeAction should be disabled without selection",
            event.presentation.isEnabledAndVisible,
        )
    }

    fun testExplainCodeEnabledWithSelection() {
        myFixture.configureByText("Test.kt", "val x = 1\n")
        myFixture.editor.selectionModel.setSelection(0, 9)

        val action = ExplainCodeAction()
        val event = buildEvent(withEditor = true)
        action.update(event)

        assertTrue(
            "ExplainCodeAction should be enabled with selection",
            event.presentation.isEnabledAndVisible,
        )
    }

    // ── FocusChatToolWindowAction ────────────────────────────────────

    fun testFocusChatEnabledWithProject() {
        myFixture.configureByText("Test.kt", "val x = 1\n")

        val action = FocusChatToolWindowAction()
        val event = buildEvent(withEditor = false)
        action.update(event)

        assertTrue(
            "FocusChatToolWindowAction should be enabled when project is available",
            event.presentation.isEnabledAndVisible,
        )
    }

    fun testFocusChatDisabledWithoutProject() {
        val action = FocusChatToolWindowAction()

        // Build an event with no project
        val dataContext = DataContext { null }
        val presentation = Presentation()
        val event =
            AnActionEvent.createEvent(
                dataContext,
                presentation,
                "TestPlace",
                ActionUiKind.NONE,
                null,
            )
        action.update(event)

        assertFalse(
            "FocusChatToolWindowAction should be disabled without project",
            event.presentation.isEnabledAndVisible,
        )
    }

    // ── Helpers ─────────────────────────────────────────────────────

    /**
     * Build an [AnActionEvent] backed by the fixture's project and,
     * optionally, its editor.
     */
    private fun buildEvent(withEditor: Boolean): AnActionEvent {
        val editor = if (withEditor) myFixture.editor else null
        val dataContext =
            DataContext { dataId ->
                when (dataId) {
                    CommonDataKeys.PROJECT.name -> project
                    CommonDataKeys.EDITOR.name -> editor
                    else -> null
                }
            }
        return AnActionEvent.createEvent(
            dataContext,
            Presentation(),
            "TestPlace",
            ActionUiKind.NONE,
            null,
        )
    }
}
