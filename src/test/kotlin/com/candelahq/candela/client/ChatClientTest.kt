package com.candelahq.candela.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [ChatClient] — SSE streaming via Flow, model fetching, cancellation.
 * Uses MockWebServer for realistic HTTP behavior.
 */
class ChatClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ChatClient
    private lateinit var baseUrl: String

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("/").toString().trimEnd('/')
        client = ChatClient()
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    // ── fetchModels ──────────────────────────────────────────────────────

    @Test
    fun `fetchModels returns models on success`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                            "object": "list",
                            "data": [
                                {"id": "gpt-4", "object": "model", "owned_by": "openai"},
                                {"id": "claude-3", "object": "model", "owned_by": "anthropic"}
                            ]
                        }
                        """.trimIndent(),
                    ),
            )

            val models = client.fetchModels(baseUrl)
            assertEquals(2, models.size)
            assertEquals("gpt-4", models[0].id)
            assertEquals("claude-3", models[1].id)
        }

    @Test
    fun `fetchModels returns empty on server error`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))
            val models = client.fetchModels(baseUrl)
            assertTrue(models.isEmpty())
        }

    @Test
    fun `fetchModels returns empty on connection error`() =
        runTest {
            server.shutdown() // Force connection error
            val models = client.fetchModels("http://127.0.0.1:${server.port}")
            assertTrue(models.isEmpty())
        }

    // ── streamChat (Flow) ────────────────────────────────────────────────

    @Test
    fun `streamChat emits Token events for each content delta`() =
        runTest {
            val sseBody =
                """
                data: {"id":"1","object":"chat.completion.chunk","choices":[{"delta":{"content":"Hello"},"index":0}]}

                data: {"id":"1","object":"chat.completion.chunk","choices":[{"delta":{"content":" world"},"index":0}]}

                data: [DONE]

                """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(sseBody),
            )

            val events =
                client
                    .streamChat(
                        baseUrl = baseUrl,
                        model = "test",
                        messages = listOf(ChatMessage("user", "hi")),
                        maxTokens = 100,
                    ).toList()

            val tokens = events.filterIsInstance<StreamEvent.Token>().map { it.content }
            assertEquals(listOf("Hello", " world"), tokens)
            assertTrue(events.last() is StreamEvent.Complete)
        }

    @Test
    fun `streamChat reports usage in Complete event`() =
        runTest {
            val sseBody =
                """
                data: {"id":"1","object":"chat.completion.chunk","choices":[{"delta":{"content":"hi"},"index":0}]}

                data: {"id":"1","object":"chat.completion.chunk","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}

                data: [DONE]

                """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(sseBody),
            )

            val events =
                client
                    .streamChat(
                        baseUrl = baseUrl,
                        model = "test",
                        messages = listOf(ChatMessage("user", "hi")),
                        maxTokens = 100,
                    ).toList()

            val complete = events.filterIsInstance<StreamEvent.Complete>().single()
            assertNotNull(complete.usage)
            assertEquals(10, complete.usage!!.promptTokens)
            assertEquals(5, complete.usage!!.completionTokens)
            assertEquals(15, complete.usage!!.totalTokens)
        }

    @Test
    fun `streamChat emits Error on non-2xx status`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(429)
                    .setBody("Rate limited"),
            )

            val events =
                client
                    .streamChat(
                        baseUrl = baseUrl,
                        model = "test",
                        messages = listOf(ChatMessage("user", "hi")),
                        maxTokens = 100,
                    ).toList()

            assertEquals(1, events.size)
            val error = events[0] as StreamEvent.Error
            assertTrue(error.exception.message!!.contains("429"))
        }

    @Test
    fun `streamChat handles malformed JSON chunks gracefully`() =
        runTest {
            val sseBody =
                """
                data: {"id":"1","object":"chat.completion.chunk","choices":[{"delta":{"content":"good"},"index":0}]}

                data: {not valid json!!!

                data: {"id":"1","object":"chat.completion.chunk","choices":[{"delta":{"content":" end"},"index":0}]}

                data: [DONE]

                """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(sseBody),
            )

            val events =
                client
                    .streamChat(
                        baseUrl = baseUrl,
                        model = "test",
                        messages = listOf(ChatMessage("user", "hi")),
                        maxTokens = 100,
                    ).toList()

            val tokens = events.filterIsInstance<StreamEvent.Token>().map { it.content }
            assertEquals(listOf("good", " end"), tokens, "Should skip malformed chunks")
            assertTrue(events.last() is StreamEvent.Complete)
        }

    @Test
    fun `streamChat handles empty choices array`() =
        runTest {
            val sseBody =
                """
                data: {"id":"1","object":"chat.completion.chunk","choices":[]}

                data: [DONE]

                """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(sseBody),
            )

            val events =
                client
                    .streamChat(
                        baseUrl = baseUrl,
                        model = "test",
                        messages = listOf(ChatMessage("user", "hi")),
                        maxTokens = 100,
                    ).toList()

            val tokens = events.filterIsInstance<StreamEvent.Token>()
            assertTrue(tokens.isEmpty(), "No tokens from empty choices")
            assertTrue(events.last() is StreamEvent.Complete)
        }

    @Test
    fun `streamChat handles null content delta`() =
        runTest {
            val sseBody =
                """
                data: {"id":"1","object":"chat.completion.chunk","choices":[{"delta":{},"index":0}]}

                data: {"id":"1","object":"chat.completion.chunk","choices":[{"delta":{"content":null},"index":0}]}

                data: [DONE]

                """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(sseBody),
            )

            val events =
                client
                    .streamChat(
                        baseUrl = baseUrl,
                        model = "test",
                        messages = listOf(ChatMessage("user", "hi")),
                        maxTokens = 100,
                    ).toList()

            val tokens = events.filterIsInstance<StreamEvent.Token>()
            assertTrue(tokens.isEmpty(), "No tokens from null content")
        }

    @Test
    fun `streamChat emits Error on unexpected EOF without DONE`() =
        runTest {
            val sseBody =
                """
                data: {"id":"1","object":"chat.completion.chunk","choices":[{"delta":{"content":"partial"},"index":0}]}

                """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(sseBody),
            )

            val events =
                client
                    .streamChat(
                        baseUrl = baseUrl,
                        model = "test",
                        messages = listOf(ChatMessage("user", "hi")),
                        maxTokens = 100,
                    ).toList()

            val tokens = events.filterIsInstance<StreamEvent.Token>().map { it.content }
            assertEquals(listOf("partial"), tokens, "Should still deliver tokens before EOF")
            val error = events.last() as StreamEvent.Error
            assertTrue(error.exception.message!!.contains("unexpectedly"))
        }

    @Test
    fun `streamChat cancellation stops flow collection`() =
        runTest {
            val sseBody =
                """
                data: {"id":"1","object":"chat.completion.chunk","choices":[{"delta":{"content":"tok1"},"index":0}]}

                data: {"id":"1","object":"chat.completion.chunk","choices":[{"delta":{"content":"tok2"},"index":0}]}

                data: {"id":"1","object":"chat.completion.chunk","choices":[{"delta":{"content":"tok3"},"index":0}]}

                data: [DONE]

                """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(sseBody)
                    .throttleBody(40, 200, TimeUnit.MILLISECONDS),
            )

            val collected = mutableListOf<StreamEvent>()
            val job =
                launch {
                    client
                        .streamChat(
                            baseUrl = baseUrl,
                            model = "test",
                            messages = listOf(ChatMessage("user", "hi")),
                            maxTokens = 100,
                        ).collect { event ->
                            collected.add(event)
                            // Cancel after first token
                            if (event is StreamEvent.Token && collected.size == 1) {
                                this.coroutineContext[kotlinx.coroutines.Job]?.cancel()
                            }
                        }
                }

            try {
                job.join()
            } catch (_: CancellationException) {
                // Expected
            }

            assertTrue(collected.isNotEmpty(), "Should have received at least one token")
            val completions = collected.filterIsInstance<StreamEvent.Complete>()
            assertTrue(completions.isEmpty(), "Complete should NOT fire — stream was cancelled")
            assertTrue(collected.size < 3, "Should not receive all tokens after cancellation, got: $collected")
        }

    @Test
    fun `streamChat sends correct request body`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: [DONE]\n\n"),
            )

            client
                .streamChat(
                    baseUrl = baseUrl,
                    model = "gpt-4",
                    messages =
                        listOf(
                            ChatMessage("system", "You are helpful"),
                            ChatMessage("user", "hello"),
                        ),
                    maxTokens = 2048,
                ).toList()

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(request.path!!.endsWith("/v1/chat/completions"))
            assertEquals("application/json", request.getHeader("Content-Type"))
            assertEquals("text/event-stream", request.getHeader("Accept"))

            val body = request.body.readUtf8()
            assertTrue(body.contains("\"model\":\"gpt-4\""))
            assertTrue(body.contains("\"stream\":true"))
            assertTrue(body.contains("\"max_tokens\":2048"))
            assertTrue(body.contains("\"role\":\"system\""))
            assertTrue(body.contains("\"role\":\"user\""))
        }

    @Test
    fun `streamChat skips non-data SSE lines`() =
        runTest {
            val sseBody =
                """
                event: message
                id: 12345
                retry: 3000
                data: {"id":"1","object":"chat.completion.chunk","choices":[{"delta":{"content":"content"},"index":0}]}

                : this is a comment

                data: [DONE]

                """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(sseBody),
            )

            val events =
                client
                    .streamChat(
                        baseUrl = baseUrl,
                        model = "test",
                        messages = listOf(ChatMessage("user", "hi")),
                        maxTokens = 100,
                    ).toList()

            val tokens = events.filterIsInstance<StreamEvent.Token>().map { it.content }
            assertEquals(listOf("content"), tokens, "Should only process data lines")
        }

    // ── Additional coverage ─────────────────────────────────────────────

    @Test
    fun `streamChat aborts when response exceeds MAX_STREAM_BYTES`() =
        runTest {
            val bigContent = "X".repeat(100_000)
            val chunkLine =
                """data: {"id":"1","object":"chat.completion.chunk","choices":[{"delta":{"content":"$bigContent"},"index":0}]}"""
            val sseBody =
                buildString {
                    repeat(25) {
                        appendLine(chunkLine)
                        appendLine()
                    }
                    appendLine("data: [DONE]")
                    appendLine()
                }

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(sseBody),
            )

            val events =
                client
                    .streamChat(
                        baseUrl = baseUrl,
                        model = "test",
                        messages = listOf(ChatMessage("user", "hi")),
                        maxTokens = 100,
                    ).toList()

            val error = events.filterIsInstance<StreamEvent.Error>().singleOrNull()
            assertNotNull(error, "Should have received an error event")
            assertTrue(error!!.exception.message!!.contains("too large"), "Error should mention 'too large'")
            val completions = events.filterIsInstance<StreamEvent.Complete>()
            assertTrue(completions.isEmpty(), "Complete should not fire after abort")
            val tokens = events.filterIsInstance<StreamEvent.Token>()
            assertTrue(tokens.isNotEmpty(), "Should have delivered tokens before hitting the limit")
        }

    @Test
    fun `fetchModels rethrows CancellationException`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"object":"list","data":[]}""")
                    .setBodyDelay(5, TimeUnit.SECONDS),
            )

            var caughtCE = false
            val job =
                launch {
                    try {
                        client.fetchModels(baseUrl)
                    } catch (_: CancellationException) {
                        caughtCE = true
                        throw CancellationException("re-propagate")
                    }
                }
            delay(50)
            job.cancel()
            job.join()

            assertTrue(caughtCE, "CancellationException must propagate through fetchModels()")
            assertTrue(job.isCancelled, "Job should be cancelled")
        }

    @Test
    fun `streamChat request includes stream_options for usage tracking`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: [DONE]\n\n"),
            )

            client
                .streamChat(
                    baseUrl = baseUrl,
                    model = "test",
                    messages = listOf(ChatMessage("user", "hi")),
                    maxTokens = 100,
                ).toList()

            val request = server.takeRequest()
            val body = request.body.readUtf8()
            assertTrue(
                body.contains("\"stream_options\":{\"include_usage\":true}"),
                "Request body should include stream_options with include_usage=true, got: $body",
            )
        }

    @Test
    fun `streamChat emits Error on auth failure without tokens`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .setBody("Unauthorized: invalid API key"),
            )

            val events =
                client
                    .streamChat(
                        baseUrl = baseUrl,
                        model = "test",
                        messages = listOf(ChatMessage("user", "hi")),
                        maxTokens = 100,
                    ).toList()

            assertEquals(1, events.size)
            val error = events[0] as StreamEvent.Error
            assertTrue(error.exception.message!!.contains("401"), "Error should contain status code 401")
            assertTrue(error.exception.message!!.contains("Unauthorized"), "Error should contain the response body")
        }

    @Test
    fun `streamChat handles very large error body gracefully`() =
        runTest {
            val largeErrorBody = "E".repeat(10_000)

            server.enqueue(
                MockResponse()
                    .setResponseCode(500)
                    .setBody(largeErrorBody),
            )

            val events =
                client
                    .streamChat(
                        baseUrl = baseUrl,
                        model = "test",
                        messages = listOf(ChatMessage("user", "hi")),
                        maxTokens = 100,
                    ).toList()

            assertEquals(1, events.size)
            val error = events[0] as StreamEvent.Error
            assertTrue(error.exception.message!!.contains("500"), "Error should contain status code")
            val errorMsg = error.exception.message!!
            val bodyInMsg = errorMsg.substringAfter(": ")
            assertTrue(bodyInMsg.length <= 1000, "Error body should be truncated to at most 1000 chars, got ${bodyInMsg.length}")
            assertTrue(bodyInMsg.startsWith("EEEE"), "Error should contain the start of the error body")
        }
}
