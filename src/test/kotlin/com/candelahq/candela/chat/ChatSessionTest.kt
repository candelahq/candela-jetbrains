package com.candelahq.candela.chat

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [ChatSession] — Job-based cancellation, message management, thread safety.
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
    fun `isStreaming false when no job assigned`() {
        val session = ChatSession()
        assertFalse(session.isStreaming)
        assertNull(session.streamingJob)
    }

    @Test
    fun `isStreaming true when active job assigned`() =
        runTest {
            val session = ChatSession()
            session.streamingJob =
                launch {
                    delay(10_000) // Long-running task
                }
            assertTrue(session.isStreaming, "Should be streaming with active job")
            session.cancelStreaming()
        }

    @Test
    fun `cancelStreaming cancels active job`() =
        runTest {
            val session = ChatSession()
            val job =
                launch {
                    delay(10_000)
                }
            session.streamingJob = job
            assertTrue(job.isActive)

            session.cancelStreaming()
            assertTrue(job.isCancelled, "Job should be cancelled")
            assertFalse(session.isStreaming, "Should not be streaming after cancel")
            assertNull(session.streamingJob, "streamingJob should be cleared")
        }

    @Test
    fun `cancelStreaming is safe when no job`() {
        val session = ChatSession()
        // Should not throw
        session.cancelStreaming()
        assertFalse(session.isStreaming)
    }

    @Test
    fun `isStreaming false after job completes naturally`() =
        runTest {
            val session = ChatSession()
            val job =
                launch {
                    // Completes immediately
                }
            session.streamingJob = job
            job.join()
            assertFalse(session.isStreaming, "Should not be streaming after job completes")
        }

    @Test
    fun `clear resets all state`() =
        runTest {
            val session = ChatSession()
            session.addUserMessage("hello")
            session.addAssistantMessage("world")
            session.streamingJob = launch { delay(10_000) }

            // Preconditions — state is dirty
            assertEquals(2, session.messages.size, "Should have 2 messages before clear")
            assertTrue(session.isStreaming, "Should be streaming before clear")

            session.clear()
            assertTrue(session.messages.isEmpty(), "Messages should be cleared")
            assertFalse(session.isStreaming, "Streaming should be stopped")
            assertNull(session.streamingJob, "streamingJob should be null")
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
    fun `multiple streaming cycles work correctly`() =
        runTest {
            val session = ChatSession()

            // First cycle
            val job1 = launch { delay(10_000) }
            session.streamingJob = job1
            session.cancelStreaming()
            assertTrue(job1.isCancelled)

            // Second cycle — new job should work
            val job2 = launch { delay(10_000) }
            session.streamingJob = job2
            assertTrue(session.isStreaming, "Should be streaming with new job")
            session.cancelStreaming()
        }

    @Test
    fun `messages returns immutable snapshot`() {
        val session = ChatSession()
        session.addUserMessage("hello")
        val snapshot = session.messages
        session.addUserMessage("world")
        assertEquals(1, snapshot.size, "Snapshot should not be affected by later adds")
        assertEquals(2, session.messages.size, "Current messages should have both")
    }
}
