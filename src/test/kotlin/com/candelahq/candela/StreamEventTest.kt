package com.candelahq.candela

import com.candelahq.candela.client.ChunkUsage
import com.candelahq.candela.client.StreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [StreamEvent] sealed interface behavior.
 *
 * These test the Flow contract and event semantics in isolation,
 * without requiring a real HTTP server.
 */
class StreamEventTest {
    @Test
    fun `token events preserve emission order`() =
        runTest {
            val events =
                flow {
                    emit(StreamEvent.Token("Hello"))
                    emit(StreamEvent.Token(" "))
                    emit(StreamEvent.Token("World"))
                    emit(StreamEvent.Complete(null))
                }.toList()

            assertEquals(4, events.size)
            assertEquals("Hello", (events[0] as StreamEvent.Token).content)
            assertEquals(" ", (events[1] as StreamEvent.Token).content)
            assertEquals("World", (events[2] as StreamEvent.Token).content)
            assertInstanceOf(StreamEvent.Complete::class.java, events[3])
        }

    @Test
    fun `complete event carries usage metadata`() =
        runTest {
            val usage = ChunkUsage(promptTokens = 10, completionTokens = 50, totalTokens = 60)
            val events =
                flow {
                    emit(StreamEvent.Token("test"))
                    emit(StreamEvent.Complete(usage))
                }.toList()

            val complete = events.last() as StreamEvent.Complete
            assertEquals(10, complete.usage?.promptTokens)
            assertEquals(50, complete.usage?.completionTokens)
            assertEquals(60, complete.usage?.totalTokens)
        }

    @Test
    fun `complete event with null usage is valid`() =
        runTest {
            val events =
                flow {
                    emit(StreamEvent.Token("test"))
                    emit(StreamEvent.Complete(null))
                }.toList()

            val complete = events.last() as StreamEvent.Complete
            assertEquals(null, complete.usage)
        }

    @Test
    fun `error event carries exception`() =
        runTest {
            val events =
                flow {
                    emit(StreamEvent.Token("partial"))
                    emit(StreamEvent.Error(RuntimeException("stream broke")))
                }.toList()

            assertEquals(2, events.size)
            val error = events[1] as StreamEvent.Error
            assertEquals("stream broke", error.exception.message)
        }

    @Test
    fun `cancellation stops flow collection`() {
        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking {
                val collected = mutableListOf<StreamEvent>()
                val infiniteFlow: Flow<StreamEvent> =
                    flow {
                        var i = 0
                        while (true) {
                            emit(StreamEvent.Token("token$i"))
                            i++
                        }
                    }
                // Cancel after collecting a few tokens
                kotlinx.coroutines.withTimeout(50) {
                    infiniteFlow.collect { event ->
                        collected.add(event)
                    }
                }
            }
        }
    }

    @Test
    fun `when expression exhaustively covers all event types`() =
        runTest {
            val events =
                listOf(
                    StreamEvent.Token("hello"),
                    StreamEvent.Complete(ChunkUsage(1, 2, 3)),
                    StreamEvent.Error(RuntimeException("err")),
                )

            val descriptions = mutableListOf<String>()
            for (event in events) {
                when (event) {
                    is StreamEvent.Token -> descriptions.add("token:${event.content}")
                    is StreamEvent.Complete -> descriptions.add("complete:${event.usage?.totalTokens}")
                    is StreamEvent.Error -> descriptions.add("error:${event.exception.message}")
                }
            }

            assertEquals(listOf("token:hello", "complete:3", "error:err"), descriptions)
        }

    @Test
    fun `empty stream with only complete is valid`() =
        runTest {
            val events =
                flow {
                    emit(StreamEvent.Complete(null))
                }.toList()

            assertEquals(1, events.size)
            assertInstanceOf(StreamEvent.Complete::class.java, events[0])
        }

    @Test
    fun `token data class equality works correctly`() {
        val a = StreamEvent.Token("hello")
        val b = StreamEvent.Token("hello")
        val c = StreamEvent.Token("world")

        assertEquals(a, b)
        assertTrue(a != c)
    }

    @Test
    fun `error followed by tokens is valid flow pattern`() =
        runTest {
            // A non-fatal error mid-stream followed by recovery tokens
            val events =
                flow {
                    emit(StreamEvent.Token("before"))
                    emit(StreamEvent.Error(RuntimeException("transient")))
                    emit(StreamEvent.Token("after"))
                    emit(StreamEvent.Complete(null))
                }.toList()

            assertEquals(4, events.size)
            assertInstanceOf(StreamEvent.Token::class.java, events[0])
            assertInstanceOf(StreamEvent.Error::class.java, events[1])
            assertInstanceOf(StreamEvent.Token::class.java, events[2])
            assertInstanceOf(StreamEvent.Complete::class.java, events[3])
        }
}
