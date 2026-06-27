package com.candelahq.candela.actions

import com.candelahq.candela.chat.ChatToolWindowFactory
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Editor context action: "Explain Code"
 *
 * Sends the selected code to Candela Chat with an "Explain this code" prompt.
 */
class ExplainCodeAction :
    AnAction(),
    DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selectedText = editor.selectionModel.selectedText ?: return

        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
        val lang = psiFile?.language?.id?.lowercase() ?: e.getData(CommonDataKeys.VIRTUAL_FILE)?.extension ?: ""
        val fileName = psiFile?.name ?: e.getData(CommonDataKeys.VIRTUAL_FILE)?.name ?: "unknown"

        val message = "Explain this code from `$fileName`:\n\n```$lang\n$selectedText\n```"

        val toolWindow =
            ToolWindowManager
                .getInstance(project)
                .getToolWindow(ChatToolWindowFactory.TOOL_WINDOW_ID) ?: return
        toolWindow.show {
            ChatToolWindowFactory.getPanel(project)?.sendMessage(message)
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
