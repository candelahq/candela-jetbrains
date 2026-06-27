package com.candelahq.candela.chat

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Project-level service that holds a reference to the [ChatPanel].
 *
 * Replaces the previous `WeakHashMap<Project, ChatPanel>` in
 * [ChatToolWindowFactory], providing thread-safe, lifecycle-aware access.
 */
@Service(Service.Level.PROJECT)
class ChatPanelService(private val project: Project) {

    var panel: ChatPanel? = null
        internal set

    companion object {
        fun getInstance(project: Project): ChatPanelService =
            project.getService(ChatPanelService::class.java)
    }
}
