package com.candelahq.candela.chat

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * Tests for [ChatDatabase] — SQLite persistence layer.
 *
 * Uses a temp file for each test so tests are fully isolated.
 */
class ChatDatabaseTest {
    private lateinit var db: ChatDatabase
    private lateinit var dbFile: File

    @BeforeEach
    fun setUp() {
        dbFile = Files.createTempFile("candela-test-", ".db").toFile()
        dbFile.delete() // ChatDatabase will create it
        db = ChatDatabase(dbFile.absolutePath)
    }

    @AfterEach
    fun tearDown() {
        db.close()
        dbFile.delete()
        // WAL and SHM files
        File(dbFile.absolutePath + "-wal").delete()
        File(dbFile.absolutePath + "-shm").delete()
    }

    // ── Session CRUD ──────────────────────────────────────────────────────

    @Test
    fun `create session with defaults`() {
        val session = db.createSession("s1")
        assertEquals("s1", session.id)
        assertEquals("New Chat", session.title)
        assertEquals("", session.model)
        assertEquals(0, session.messageCount)
        assertTrue(session.createdAt > 0)
    }

    @Test
    fun `create session with custom values`() {
        val session = db.createSession("s1", title = "My Chat", model = "gpt-4o")
        assertEquals("My Chat", session.title)
        assertEquals("gpt-4o", session.model)
    }

    @Test
    fun `get sessions returns sorted by updatedAt desc`() {
        db.createSession("old")
        Thread.sleep(10)
        db.createSession("new")

        val sessions = db.getSessions()
        assertEquals(2, sessions.size)
        assertEquals("new", sessions[0].id)
        assertEquals("old", sessions[1].id)
    }

    @Test
    fun `get session by id`() {
        db.createSession("s1", title = "Test")
        val session = db.getSession("s1")
        assertNotNull(session)
        assertEquals("Test", session!!.title)
    }

    @Test
    fun `get nonexistent session returns null`() {
        assertNull(db.getSession("nope"))
    }

    @Test
    fun `update session title`() {
        db.createSession("s1")
        db.updateSessionTitle("s1", "Renamed")
        assertEquals("Renamed", db.getSession("s1")!!.title)
    }

    @Test
    fun `delete session`() {
        db.createSession("s1")
        db.deleteSession("s1")
        assertNull(db.getSession("s1"))
    }

    @Test
    fun `delete session cascades to messages`() {
        db.createSession("s1")
        db.insertMessage("s1", "user", "hello")
        db.insertMessage("s1", "assistant", "hi")
        db.deleteSession("s1")
        assertEquals(0, db.getMessages("s1").size)
    }

    @Test
    fun `prune old sessions keeps latest N`() {
        db.createSession("s1")
        Thread.sleep(10)
        db.createSession("s2")
        Thread.sleep(10)
        db.createSession("s3")

        db.pruneOldSessions(2)

        val remaining = db.getSessions()
        assertEquals(2, remaining.size)
        assertEquals("s3", remaining[0].id)
        assertEquals("s2", remaining[1].id)
    }

    // ── Message CRUD ──────────────────────────────────────────────────────

    @Test
    fun `insert and retrieve messages`() {
        db.createSession("s1")
        db.insertMessage("s1", "user", "Hello!")
        db.insertMessage("s1", "assistant", "Hi there!", model = "gpt-4o", tokenCount = 50, costUsd = 0.001)

        val messages = db.getMessages("s1")
        assertEquals(2, messages.size)

        assertEquals("user", messages[0].role)
        assertEquals("Hello!", messages[0].content)
        assertNull(messages[0].model)

        assertEquals("assistant", messages[1].role)
        assertEquals("Hi there!", messages[1].content)
        assertEquals("gpt-4o", messages[1].model)
        assertEquals(50, messages[1].tokenCount)
        assertEquals(0.001, messages[1].costUsd)
    }

    @Test
    fun `messages ordered by created_at asc`() {
        db.createSession("s1")
        db.insertMessage("s1", "user", "first")
        Thread.sleep(5)
        db.insertMessage("s1", "assistant", "second")
        Thread.sleep(5)
        db.insertMessage("s1", "user", "third")

        val messages = db.getMessages("s1")
        assertEquals("first", messages[0].content)
        assertEquals("second", messages[1].content)
        assertEquals("third", messages[2].content)
    }

    @Test
    fun `insert message updates session counters`() {
        db.createSession("s1")
        db.insertMessage("s1", "user", "hello", tokenCount = 10, costUsd = 0.001)
        db.insertMessage("s1", "assistant", "hi", tokenCount = 20, costUsd = 0.002)

        val session = db.getSession("s1")!!
        assertEquals(2, session.messageCount)
        assertEquals(30, session.totalTokens)
        assertEquals(0.003, session.totalCostUsd, 0.0001)
    }

    @Test
    fun `empty session has no messages`() {
        db.createSession("s1")
        assertEquals(0, db.getMessages("s1").size)
    }

    @Test
    fun `messages from different sessions are isolated`() {
        db.createSession("s1")
        db.createSession("s2")
        db.insertMessage("s1", "user", "msg for s1")
        db.insertMessage("s2", "user", "msg for s2")

        assertEquals(1, db.getMessages("s1").size)
        assertEquals(1, db.getMessages("s2").size)
        assertEquals("msg for s1", db.getMessages("s1")[0].content)
        assertEquals("msg for s2", db.getMessages("s2")[0].content)
    }

    // ── Full-text search ──────────────────────────────────────────────────

    @Test
    fun `search finds matching messages`() {
        db.createSession("s1", title = "Kotlin Chat")
        db.insertMessage("s1", "user", "How do I use coroutines in Kotlin?")
        db.insertMessage("s1", "assistant", "Use launch or async inside a CoroutineScope.")

        val results = db.searchMessages("coroutines")
        assertEquals(1, results.size)
        assertEquals("user", results[0].role)
        assertEquals("Kotlin Chat", results[0].sessionTitle)
    }

    @Test
    fun `search across multiple sessions`() {
        db.createSession("s1", title = "Chat 1")
        db.createSession("s2", title = "Chat 2")
        db.insertMessage("s1", "user", "Explain recursion")
        db.insertMessage("s2", "user", "What is recursion in functional programming?")

        val results = db.searchMessages("recursion")
        assertEquals(2, results.size)
    }

    @Test
    fun `search returns empty for no matches`() {
        db.createSession("s1")
        db.insertMessage("s1", "user", "Hello world")
        assertEquals(0, db.searchMessages("kubernetes").size)
    }

    @Test
    fun `search respects FTS deletion via cascade`() {
        db.createSession("s1")
        db.insertMessage("s1", "user", "unique_search_term_xyz")
        assertEquals(1, db.searchMessages("unique_search_term_xyz").size)

        db.deleteSession("s1")
        assertEquals(0, db.searchMessages("unique_search_term_xyz").size)
    }

    // ── Export ─────────────────────────────────────────────────────────────

    @Test
    fun `export session to markdown`() {
        db.createSession("s1", title = "Test Export", model = "gpt-4o")
        db.insertMessage("s1", "user", "What is Kotlin?")
        db.insertMessage("s1", "assistant", "Kotlin is a programming language.", model = "gpt-4o", tokenCount = 20, costUsd = 0.001)

        val md = db.exportSessionToMarkdown("s1")
        assertNotNull(md)
        assertTrue(md!!.contains("# Test Export"))
        assertTrue(md.contains("**You**"))
        assertTrue(md.contains("**Assistant**"))
        assertTrue(md.contains("What is Kotlin?"))
        assertTrue(md.contains("Kotlin is a programming language."))
    }

    @Test
    fun `export nonexistent session returns null`() {
        assertNull(db.exportSessionToMarkdown("nope"))
    }

    // ── Database lifecycle ────────────────────────────────────────────────

    @Test
    fun `database survives close and reopen`() {
        db.createSession("s1", title = "Persistent")
        db.insertMessage("s1", "user", "Remember me")
        db.close()

        // Reopen
        val db2 = ChatDatabase(dbFile.absolutePath)
        try {
            val session = db2.getSession("s1")
            assertNotNull(session)
            assertEquals("Persistent", session!!.title)

            val messages = db2.getMessages("s1")
            assertEquals(1, messages.size)
            assertEquals("Remember me", messages[0].content)
        } finally {
            db2.close()
        }
    }

    @Test
    fun `schema migration is idempotent`() {
        db.close()
        // Opening twice should not fail
        val db2 = ChatDatabase(dbFile.absolutePath)
        db2.createSession("test")
        assertEquals(1, db2.getSessions().size)
        db2.close()
    }
}
