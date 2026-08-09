package com.candelahq.candela.actions

import com.candelahq.candela.chat.ChatToolWindowFactory
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Editor context action: "Ask Candela..."
 *
 * Prompts the user for a question about the selected code,
 * then opens the Candela Chat tool window and sends the query
 * enriched with file context (path, imports, enclosing class/function).
 */
class AskCandelaAction :
    AnAction(),
    DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selectedText = editor.selectionModel.selectedText ?: return

        val question =
            Messages.showInputDialog(
                project,
                "What would you like to ask about this code?",
                "Ask Candela",
                null,
            ) ?: return

        if (question.isBlank()) return

        val ctx = extractCodeContext(e)
        val lang = ctx?.language ?: e.getData(CommonDataKeys.VIRTUAL_FILE)?.extension ?: ""
        val fileName = ctx?.fileName ?: e.getData(CommonDataKeys.VIRTUAL_FILE)?.name ?: "unknown"

        val contextHeader = ctx?.let { "\n${formatContextHeader(it)}\n\n" } ?: "\n\n"
        val message =
            "Question about this code from `$fileName`:$contextHeader${buildCodeFence(selectedText, lang)}\n\n$question"

        // Capture editor selection for Replace Selection support
        val selCtx = captureSelectionContext(editor)

        val toolWindow =
            ToolWindowManager
                .getInstance(project)
                .getToolWindow(ChatToolWindowFactory.TOOL_WINDOW_ID) ?: return
        toolWindow.show {
            ChatToolWindowFactory.getPanel(project)?.sendMessage(message, selCtx)
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible =
            project != null &&
            !project.isDisposed &&
            editor != null &&
            editor.selectionModel.hasSelection()
    }
}
