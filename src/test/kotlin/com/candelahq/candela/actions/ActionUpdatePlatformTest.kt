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
 * Only tests that genuinely require the IDE sandbox (editor, project)
 * should be here. Simple enable/disable logic is tested in [ActionsTest].
 */
class ActionUpdatePlatformTest : CandelaPlatformTestCase() {
    // ── AskCandelaAction — requires editor fixture ──────────────────

    fun testAskCandelaEnabledWithSelection() {
        myFixture.configureByText("Test.kt", "val x = 1\n")
        myFixture.editor.selectionModel.setSelection(0, 9)

        val action = AskCandelaAction()
        val event = buildEvent(withEditor = true)
        action.update(event)

        assertTrue(
            "AskCandelaAction should be enabled with selection",
            event.presentation.isEnabled,
        )
    }

    // ── ExplainCodeAction — requires editor fixture ─────────────────

    fun testExplainCodeEnabledWithSelection() {
        myFixture.configureByText("Test.kt", "val x = 1\n")
        myFixture.editor.selectionModel.setSelection(0, 9)

        val action = ExplainCodeAction()
        val event = buildEvent(withEditor = true)
        action.update(event)

        assertTrue(
            "ExplainCodeAction should be enabled with selection",
            event.presentation.isEnabled,
        )
    }

    // ── Helpers ─────────────────────────────────────────────────────

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
