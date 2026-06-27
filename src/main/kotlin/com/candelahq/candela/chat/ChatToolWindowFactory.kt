package com.candelahq.candela.chat

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Factory that creates the "Candela Chat" tool window.
 *
 * Registers the [ChatPanel] in [ChatPanelService] so editor context
 * actions can retrieve and interact with it. Implements [DumbAware]
 * so the chat is available even during indexing.
 */
class ChatToolWindowFactory :
    ToolWindowFactory,
    DumbAware {
    companion object {
        const val TOOL_WINDOW_ID = "Candela Chat"

        /**
         * Get the chat panel for a project, if the tool window has been created.
         * Prefer using [ChatPanelService.getInstance] directly.
         */
        fun getPanel(project: Project): ChatPanel? = ChatPanelService.getInstance(project).panel
    }

    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        val panel = ChatPanel(project)
        ChatPanelService.getInstance(project).panel = panel

        val content = ContentFactory.getInstance().createContent(panel, "", false)

        // Tie panel disposal to the tool window content lifecycle
        Disposer.register(content, panel)
        toolWindow.contentManager.addContent(content)
    }
}
