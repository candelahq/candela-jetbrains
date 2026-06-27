package com.candelahq.candela.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [ChatSession] — thread safety and message management.
 */
class ChatSessionTest {
    @Test
    fun `new session has no messages`() {
        val session = ChatSession()
        assertTrue(session.messages.isEmpty(), "New session should have no messages")
    }

    @Test
    fun `addUserMessage appends user message`() {
        val session = ChatSession()
        session.addUserMessage("hello")
        assertEquals(1, session.messages.size)
        assertEquals("user", session.messages[0].role)
        assertEquals("hello", session.messages[0].content)
    }

    @Test
    fun `addAssistantMessage appends assistant message`() {
        val session = ChatSession()
        session.addAssistantMessage("response")
        assertEquals(1, session.messages.size)
        assertEquals("assistant", session.messages[0].role)
        assertEquals("response", session.messages[0].content)
    }

    @Test
    fun `conversation preserves message order`() {
        val session = ChatSession()
        session.addUserMessage("first")
        session.addAssistantMessage("response")
        session.addUserMessage("second")
        assertEquals(3, session.messages.size)
        assertEquals("user", session.messages[0].role)
        assertEquals("assistant", session.messages[1].role)
        assertEquals("user", session.messages[2].role)
    }

    @Test
    fun `streaming state transitions`() {
        val session = ChatSession()
        assertFalse(session.isStreaming, "Should not be streaming initially")

        session.startStreaming()
        assertTrue(session.isStreaming, "Should be streaming after start")
        assertFalse(session.cancelled.get(), "Should not be cancelled after start")

        session.cancelStreaming()
        assertTrue(session.cancelled.get(), "Should be cancelled")

        session.stopStreaming()
        assertFalse(session.isStreaming, "Should not be streaming after stop")
    }

    @Test
    fun `clear resets all state`() {
        val session = ChatSession()
        session.addUserMessage("hello")
        session.addAssistantMessage("world")
        session.startStreaming()

        session.clear()
        assertTrue(session.messages.isEmpty(), "Messages should be cleared")
        assertFalse(session.isStreaming, "Streaming should be stopped")
    }

    @Test
    fun `messages list is thread-safe for concurrent access`() {
        val session = ChatSession()
        val threads =
            (1..10).map { i ->
                Thread {
                    for (j in 1..100) {
                        session.addUserMessage("msg-$i-$j")
                    }
                }
            }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(1000, session.messages.size, "All messages should be added concurrently")
    }

    @Test
    fun `multiple streaming cycles work correctly`() {
        val session = ChatSession()

        // First cycle
        session.startStreaming()
        session.cancelStreaming()
        session.stopStreaming()

        // Second cycle — cancelled should be reset
        session.startStreaming()
        assertFalse(session.cancelled.get(), "Cancelled should be reset for new stream")
        session.stopStreaming()
    }
}
