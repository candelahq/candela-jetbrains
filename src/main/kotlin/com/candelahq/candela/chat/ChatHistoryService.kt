package com.candelahq.candela.chat

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.UUID

/**
 * Project-level service for chat history persistence.
 *
 * Owns the [ChatDatabase] instance — created when the project opens,
 * closed when the project closes. DB is stored at `{projectDir}/.candela/chat.db`.
 *
 * All DB operations run synchronously inside [ChatDatabase] (which is
 * `@Synchronized`). The calling code (ChatPanel) should dispatch to
 * `Dispatchers.IO` when saving messages during streaming.
 */
@Service(Service.Level.PROJECT)
class ChatHistoryService(
    private val project: Project,
) : Disposable {
    private val log = Logger.getInstance(ChatHistoryService::class.java)

    /** IO-bound scope for background DB writes; cancelled on dispose. */
    val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val db: ChatDatabase by lazy {
        val basePath = project.guessProjectDir()?.path ?: System.getProperty("user.home")
        val dbPath = "$basePath/.candela/chat.db"
        ChatDatabase(dbPath)
    }

    @Volatile
    var activeSessionId: String? = null
        private set

    // ── Session lifecycle ─────────────────────────────────────────────────

    /**
     * Create a new session and set it as active.
     * Returns the session ID.
     */
    fun newSession(model: String = ""): String {
        val id = UUID.randomUUID().toString()
        db.createSession(id, "New Chat", model)
        activeSessionId = id
        log.info("New session created: $id")
        return id
    }

    /**
     * Ensure an active session exists.
     * Prefers resuming the most recently persisted session, otherwise creates one.
     */
    fun ensureActiveSession(model: String = ""): String {
        activeSessionId?.let { id ->
            if (db.getSession(id) != null) return id
        }
        // Try to resume the most recent persisted session
        val mostRecent = db.getSessions().firstOrNull()
        if (mostRecent != null) {
            activeSessionId = mostRecent.id
            log.info("Resumed most recent session: ${mostRecent.id}")
            return mostRecent.id
        }
        return newSession(model)
    }

    /**
     * Switch to an existing session.
     */
    fun switchSession(id: String) {
        activeSessionId = id
        log.info("Switched to session: $id")
    }

    // ── Message operations ────────────────────────────────────────────────

    /**
     * Save a message to the active session.
     * Auto-generates session title from first user message.
     */
    fun saveMessage(
        role: String,
        content: String,
        model: String? = null,
        tokenCount: Int? = null,
        costUsd: Double? = null,
    ) {
        val sessionId = activeSessionId ?: return
        db.insertMessage(sessionId, role, content, model, tokenCount, costUsd)

        // Auto-title from first user message
        if (role == "user") {
            val session = db.getSession(sessionId)
            if (session != null && session.title == "New Chat" && session.messageCount <= 1) {
                val title = content.take(50).replace("\n", " ").trim()
                db.updateSessionTitle(sessionId, title)
            }
        }
    }

    /**
     * Get all messages for a session.
     */
    fun getMessages(sessionId: String): List<MessageRow> = db.getMessages(sessionId)

    /**
     * Get messages for the active session.
     */
    fun getActiveMessages(): List<MessageRow> = activeSessionId?.let { db.getMessages(it) } ?: emptyList()

    // ── Session list ──────────────────────────────────────────────────────

    fun getSessions(): List<SessionRow> = db.getSessions()

    fun getSession(id: String): SessionRow? = db.getSession(id)

    fun deleteSession(id: String) {
        db.deleteSession(id)
        if (activeSessionId == id) {
            activeSessionId = null
        }
    }

    fun renameSession(
        id: String,
        title: String,
    ) {
        db.updateSessionTitle(id, title)
    }

    // ── Search ────────────────────────────────────────────────────────────

    fun searchMessages(query: String): List<SearchResult> = db.searchMessages(query)

    // ── Export ─────────────────────────────────────────────────────────────

    fun exportToMarkdown(sessionId: String): String? = db.exportSessionToMarkdown(sessionId)

    // ── Maintenance ───────────────────────────────────────────────────────

    fun pruneOldSessions(maxSessions: Int = 50) {
        db.pruneOldSessions(maxSessions)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun dispose() {
        ioScope.cancel("ChatHistoryService disposed")
        db.close()
        log.info("ChatHistoryService disposed for project: ${project.name}")
    }

    companion object {
        fun getInstance(project: Project): ChatHistoryService = project.getService(ChatHistoryService::class.java)
    }
}
