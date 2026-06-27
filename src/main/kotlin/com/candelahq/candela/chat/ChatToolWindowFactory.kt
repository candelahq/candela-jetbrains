package com.candelahq.candela.chat

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import java.util.WeakHashMap

/**
 * Factory that creates the "Candela Chat" tool window.
 *
 * Stores a weak reference to each project's [ChatPanel] so editor context
 * actions can retrieve and interact with it.
 */
class ChatToolWindowFactory : ToolWindowFactory {

    companion object {
        const val TOOL_WINDOW_ID = "Candela Chat"

        private val panels = WeakHashMap<Project, ChatPanel>()

        /** Get the chat panel for a project, if the tool window has been created. */
        fun getPanel(project: Project): ChatPanel? = panels[project]
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ChatPanel(project)
        panels[project] = panel
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
