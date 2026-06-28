package com.candelahq.candela.chat

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.Locale

/**
 * SQLite database wrapper for chat history persistence.
 *
 * Uses a single connection in WAL mode with foreign keys enabled.
 * All public methods are synchronized for thread safety.
 * Schema is auto-created on first access and versioned for migrations.
 */
class ChatDatabase(
    dbPath: String,
) : AutoCloseable {
    private val log = Logger.getInstance(ChatDatabase::class.java)
    private val connection: Connection

    init {
        // Ensure parent directory exists
        File(dbPath).parentFile?.mkdirs()

        connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        connection.autoCommit = true

        try {
            // Enable WAL mode for concurrent reads during streaming
            execute("PRAGMA journal_mode=WAL")
            execute("PRAGMA foreign_keys=ON")
            execute("PRAGMA busy_timeout=5000")

            migrate()
            log.info("ChatDatabase opened: $dbPath")
        } catch (ex: Exception) {
            try {
                connection.close()
            } catch (closeEx: Exception) {
                ex.addSuppressed(closeEx)
            }
            throw ex
        }
    }

    // ── Schema ────────────────────────────────────────────────────────────

    private fun migrate() {
        val version = queryScalar("PRAGMA user_version") ?: 0

        if (version < 1) {
            connection.createStatement().use { stmt ->
                stmt.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS sessions (
                        id          TEXT PRIMARY KEY,
                        title       TEXT NOT NULL DEFAULT 'New Chat',
                        model       TEXT NOT NULL DEFAULT '',
                        created_at  INTEGER NOT NULL,
                        updated_at  INTEGER NOT NULL,
                        message_count INTEGER NOT NULL DEFAULT 0,
                        total_tokens  INTEGER NOT NULL DEFAULT 0,
                        total_cost_usd REAL NOT NULL DEFAULT 0.0
                    )
                    """.trimIndent(),
                )
                stmt.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS messages (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        session_id  TEXT NOT NULL,
                        role        TEXT NOT NULL,
                        content     TEXT NOT NULL,
                        model       TEXT,
                        token_count INTEGER,
                        cost_usd    REAL,
                        created_at  INTEGER NOT NULL,
                        FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                stmt.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(session_id, created_at)",
                )

                // Full-text search
                stmt.executeUpdate(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(
                        content,
                        content=messages,
                        content_rowid=id
                    )
                    """.trimIndent(),
                )

                // FTS sync triggers
                stmt.executeUpdate(
                    """
                    CREATE TRIGGER IF NOT EXISTS messages_ai AFTER INSERT ON messages BEGIN
                        INSERT INTO messages_fts(rowid, content) VALUES (new.id, new.content);
                    END
                    """.trimIndent(),
                )
                stmt.executeUpdate(
                    """
                    CREATE TRIGGER IF NOT EXISTS messages_ad AFTER DELETE ON messages BEGIN
                        INSERT INTO messages_fts(messages_fts, rowid, content)
                        VALUES('delete', old.id, old.content);
                    END
                    """.trimIndent(),
                )

                stmt.executeUpdate("PRAGMA user_version = 1")
            }
            log.info("ChatDatabase migrated to version 1")
        }
    }

    // ── Session CRUD ──────────────────────────────────────────────────────

    @Synchronized
    fun createSession(
        id: String,
        title: String = "New Chat",
        model: String = "",
    ): SessionRow {
        val now = System.currentTimeMillis()
        prepareAndExecute(
            "INSERT INTO sessions (id, title, model, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
            id,
            title,
            model,
            now,
            now,
        )
        return SessionRow(id, title, model, now, now, 0, 0, 0.0)
    }

    @Synchronized
    fun getSessions(): List<SessionRow> =
        query(
            "SELECT id, title, model, created_at, updated_at, message_count, total_tokens, total_cost_usd " +
                "FROM sessions ORDER BY updated_at DESC",
        ) { rs ->
            SessionRow(
                id = rs.getString("id"),
                title = rs.getString("title"),
                model = rs.getString("model"),
                createdAt = rs.getLong("created_at"),
                updatedAt = rs.getLong("updated_at"),
                messageCount = rs.getInt("message_count"),
                totalTokens = rs.getInt("total_tokens"),
                totalCostUsd = rs.getDouble("total_cost_usd"),
            )
        }

    @Synchronized
    fun getSession(id: String): SessionRow? =
        query(
            "SELECT id, title, model, created_at, updated_at, message_count, total_tokens, total_cost_usd " +
                "FROM sessions WHERE id = ?",
            id,
        ) { rs ->
            SessionRow(
                id = rs.getString("id"),
                title = rs.getString("title"),
                model = rs.getString("model"),
                createdAt = rs.getLong("created_at"),
                updatedAt = rs.getLong("updated_at"),
                messageCount = rs.getInt("message_count"),
                totalTokens = rs.getInt("total_tokens"),
                totalCostUsd = rs.getDouble("total_cost_usd"),
            )
        }.firstOrNull()

    @Synchronized
    fun updateSessionTitle(
        id: String,
        title: String,
    ) {
        prepareAndExecute("UPDATE sessions SET title = ?, updated_at = ? WHERE id = ?", title, System.currentTimeMillis(), id)
    }

    @Synchronized
    fun deleteSession(id: String) {
        prepareAndExecute("DELETE FROM sessions WHERE id = ?", id)
    }

    @Synchronized
    fun pruneOldSessions(maxSessions: Int) {
        prepareAndExecute(
            "DELETE FROM sessions WHERE id NOT IN (SELECT id FROM sessions ORDER BY updated_at DESC LIMIT ?)",
            maxSessions,
        )
    }

    // ── Message CRUD ──────────────────────────────────────────────────────

    @Synchronized
    fun insertMessage(
        sessionId: String,
        role: String,
        content: String,
        model: String? = null,
        tokenCount: Int? = null,
        costUsd: Double? = null,
        createdAt: Long = System.currentTimeMillis(),
    ): Long {
        val savedAutoCommit = connection.autoCommit
        try {
            connection.autoCommit = false
            val ps =
                connection.prepareStatement(
                    "INSERT INTO messages (session_id, role, content, model, token_count, cost_usd, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS,
                )
            val generatedId: Long
            ps.use {
                it.setString(1, sessionId)
                it.setString(2, role)
                it.setString(3, content)
                it.setString(4, model)
                if (tokenCount != null) it.setInt(5, tokenCount) else it.setNull(5, java.sql.Types.INTEGER)
                if (costUsd != null) it.setDouble(6, costUsd) else it.setNull(6, java.sql.Types.REAL)
                it.setLong(7, createdAt)
                it.executeUpdate()

                val keys = it.generatedKeys
                generatedId = if (keys.next()) keys.getLong(1) else -1
            }

            // Update session denormalized counters
            prepareAndExecute(
                """
                UPDATE sessions SET
                    updated_at = ?,
                    message_count = (SELECT COUNT(*) FROM messages WHERE session_id = ?),
                    total_tokens = COALESCE((SELECT SUM(token_count) FROM messages WHERE session_id = ?), 0),
                    total_cost_usd = COALESCE((SELECT SUM(cost_usd) FROM messages WHERE session_id = ?), 0.0)
                WHERE id = ?
                """.trimIndent(),
                createdAt,
                sessionId,
                sessionId,
                sessionId,
                sessionId,
            )

            connection.commit()
            return generatedId
        } catch (ex: Exception) {
            try {
                connection.rollback()
            } catch (rollbackEx: Exception) {
                log.warn("Rollback failed after insert error", rollbackEx)
            }
            throw ex
        } finally {
            connection.autoCommit = savedAutoCommit
        }
    }

    @Synchronized
    fun getMessages(sessionId: String): List<MessageRow> =
        query(
            "SELECT id, session_id, role, content, model, token_count, cost_usd, created_at " +
                "FROM messages WHERE session_id = ? ORDER BY created_at ASC, id ASC",
            sessionId,
        ) { rs ->
            MessageRow(
                id = rs.getLong("id"),
                sessionId = rs.getString("session_id"),
                role = rs.getString("role"),
                content = rs.getString("content"),
                model = rs.getString("model"),
                tokenCount = rs.getInt("token_count").takeIf { !rs.wasNull() },
                costUsd = rs.getDouble("cost_usd").takeIf { !rs.wasNull() },
                createdAt = rs.getLong("created_at"),
            )
        }

    // ── Full-text search ──────────────────────────────────────────────────

    @Synchronized
    fun searchMessages(queryText: String): List<SearchResult> =
        try {
            query(
                """
                SELECT m.id, m.session_id, m.role, m.content, m.created_at, s.title as session_title
                FROM messages_fts fts
                JOIN messages m ON m.id = fts.rowid
                JOIN sessions s ON s.id = m.session_id
                WHERE messages_fts MATCH ?
                ORDER BY rank
                LIMIT 50
                """.trimIndent(),
                queryText,
            ) { rs ->
                SearchResult(
                    messageId = rs.getLong("id"),
                    sessionId = rs.getString("session_id"),
                    sessionTitle = rs.getString("session_title"),
                    role = rs.getString("role"),
                    content = rs.getString("content"),
                    createdAt = rs.getLong("created_at"),
                )
            }
        } catch (ex: Exception) {
            log.warn("FTS5 search failed for query: $queryText", ex)
            emptyList()
        }

    // ── Export ─────────────────────────────────────────────────────────────

    @Synchronized
    fun exportSessionToMarkdown(sessionId: String): String? {
        val session = getSession(sessionId) ?: return null
        val messages = getMessages(sessionId)
        val formattedCost = String.format(Locale.US, "%.4f", session.totalCostUsd)
        return buildString {
            appendLine("# ${session.title}")
            appendLine()
            appendLine(
                "Model: ${session.model} | Messages: ${session.messageCount} | " +
                    "Tokens: ${session.totalTokens} | Cost: \$$formattedCost",
            )
            appendLine()
            appendLine("---")
            appendLine()
            for (msg in messages) {
                val label = if (msg.role == "user") "**You**" else "**Assistant**"
                appendLine("### $label")
                appendLine()
                appendLine(msg.content)
                appendLine()
            }
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    private fun execute(sql: String) {
        connection.createStatement().use { it.execute(sql) }
    }

    private fun prepareAndExecute(
        sql: String,
        vararg params: Any?,
    ) {
        connection.prepareStatement(sql).use { ps ->
            params.forEachIndexed { i, param ->
                when (param) {
                    null -> ps.setNull(i + 1, java.sql.Types.NULL)
                    is String -> ps.setString(i + 1, param)
                    is Int -> ps.setInt(i + 1, param)
                    is Long -> ps.setLong(i + 1, param)
                    is Double -> ps.setDouble(i + 1, param)
                    else -> ps.setObject(i + 1, param)
                }
            }
            ps.executeUpdate()
        }
    }

    private fun <T> query(
        sql: String,
        vararg params: Any?,
        mapper: (ResultSet) -> T,
    ): List<T> {
        val results = mutableListOf<T>()
        connection.prepareStatement(sql).use { ps ->
            params.forEachIndexed { i, param ->
                when (param) {
                    null -> ps.setNull(i + 1, java.sql.Types.NULL)
                    is String -> ps.setString(i + 1, param)
                    is Int -> ps.setInt(i + 1, param)
                    is Long -> ps.setLong(i + 1, param)
                    is Double -> ps.setDouble(i + 1, param)
                    else -> ps.setObject(i + 1, param)
                }
            }
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    results.add(mapper(rs))
                }
            }
        }
        return results
    }

    private fun queryScalar(sql: String): Int? {
        connection.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                return if (rs.next()) rs.getInt(1) else null
            }
        }
    }

    override fun close() {
        try {
            if (!connection.isClosed) {
                connection.close()
                log.info("ChatDatabase closed")
            }
        } catch (ex: Exception) {
            log.warn("Error closing ChatDatabase connection", ex)
        }
    }
}

// ── Data classes ──────────────────────────────────────────────────────────

data class SessionRow(
    val id: String,
    val title: String,
    val model: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int,
    val totalTokens: Int,
    val totalCostUsd: Double,
)

data class MessageRow(
    val id: Long,
    val sessionId: String,
    val role: String,
    val content: String,
    val model: String?,
    val tokenCount: Int?,
    val costUsd: Double?,
    val createdAt: Long,
)

data class SearchResult(
    val messageId: Long,
    val sessionId: String,
    val sessionTitle: String,
    val role: String,
    val content: String,
    val createdAt: Long,
)
