package com.candelahq.candela.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for [ChatClient] — SSE streaming, model fetching, cancellation.
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

    // ── streamChat ───────────────────────────────────────────────────────

    @Test
    fun `streamChat calls onToken for each content delta`() =
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

            val tokens = mutableListOf<String>()
            var completed = false
            client.streamChat(
                baseUrl = baseUrl,
                model = "test",
                messages = listOf(ChatMessage("user", "hi")),
                maxTokens = 100,
                onToken = { tokens.add(it) },
                onComplete = { completed = true },
                onError = { fail("Should not error: ${it.message}") },
            )

            assertEquals(listOf("Hello", " world"), tokens)
            assertTrue(completed)
        }

    @Test
    fun `streamChat reports usage when present`() =
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

            var usage: ChunkUsage? = null
            client.streamChat(
                baseUrl = baseUrl,
                model = "test",
                messages = listOf(ChatMessage("user", "hi")),
                maxTokens = 100,
                onToken = {},
                onComplete = { usage = it },
                onError = { fail("Should not error") },
            )

            assertNotNull(usage)
            assertEquals(10, usage!!.promptTokens)
            assertEquals(5, usage!!.completionTokens)
            assertEquals(15, usage!!.totalTokens)
        }

    @Test
    fun `streamChat calls onError on non-2xx status`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(429)
                    .setBody("Rate limited"),
            )

            var error: Exception? = null
            client.streamChat(
                baseUrl = baseUrl,
                model = "test",
                messages = listOf(ChatMessage("user", "hi")),
                maxTokens = 100,
                onToken = { fail("Should not receive tokens") },
                onComplete = { fail("Should not complete") },
                onError = { error = it },
            )

            assertNotNull(error)
            assertTrue(error!!.message!!.contains("429"))
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

            val tokens = mutableListOf<String>()
            var completed = false
            client.streamChat(
                baseUrl = baseUrl,
                model = "test",
                messages = listOf(ChatMessage("user", "hi")),
                maxTokens = 100,
                onToken = { tokens.add(it) },
                onComplete = { completed = true },
                onError = { fail("Should not error on malformed chunk") },
            )

            assertEquals(listOf("good", " end"), tokens, "Should skip malformed chunks")
            assertTrue(completed)
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

            val tokens = mutableListOf<String>()
            client.streamChat(
                baseUrl = baseUrl,
                model = "test",
                messages = listOf(ChatMessage("user", "hi")),
                maxTokens = 100,
                onToken = { tokens.add(it) },
                onComplete = {},
                onError = { fail("Should not error") },
            )

            assertTrue(tokens.isEmpty(), "No tokens from empty choices")
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

            val tokens = mutableListOf<String>()
            client.streamChat(
                baseUrl = baseUrl,
                model = "test",
                messages = listOf(ChatMessage("user", "hi")),
                maxTokens = 100,
                onToken = { tokens.add(it) },
                onComplete = {},
                onError = { fail("Should not error") },
            )

            assertTrue(tokens.isEmpty(), "No tokens from null content")
        }

    @Test
    fun `streamChat errors on unexpected EOF without DONE`() =
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

            val tokens = mutableListOf<String>()
            var error: Exception? = null
            client.streamChat(
                baseUrl = baseUrl,
                model = "test",
                messages = listOf(ChatMessage("user", "hi")),
                maxTokens = 100,
                onToken = { tokens.add(it) },
                onComplete = { fail("Should not complete without [DONE]") },
                onError = { error = it },
            )

            assertEquals(listOf("partial"), tokens, "Should still deliver tokens before EOF")
            assertNotNull(error, "Should error on EOF without [DONE]")
            assertTrue(error!!.message!!.contains("unexpectedly"))
        }

    @Test
    fun `streamChat coroutine cancellation stops stream`() =
        runTest {
            // Use a slow response to simulate a long stream
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
                    .setBody(sseBody),
            )

            val tokens = mutableListOf<String>()
            val job =
                launch {
                    client.streamChat(
                        baseUrl = baseUrl,
                        model = "test",
                        messages = listOf(ChatMessage("user", "hi")),
                        maxTokens = 100,
                        onToken = {
                            tokens.add(it)
                            // Cancel after first token
                            if (tokens.size == 1) {
                                this.coroutineContext[kotlinx.coroutines.Job]?.cancel()
                            }
                        },
                        onComplete = {},
                        onError = {},
                    )
                }

            try {
                job.join()
            } catch (_: CancellationException) {
                // Expected
            }

            // Should have received at least one token before cancellation
            assertTrue(tokens.isNotEmpty(), "Should have received at least one token")
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

            client.streamChat(
                baseUrl = baseUrl,
                model = "gpt-4",
                messages =
                    listOf(
                        ChatMessage("system", "You are helpful"),
                        ChatMessage("user", "hello"),
                    ),
                maxTokens = 2048,
                onToken = {},
                onComplete = {},
                onError = {},
            )

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

            val tokens = mutableListOf<String>()
            client.streamChat(
                baseUrl = baseUrl,
                model = "test",
                messages = listOf(ChatMessage("user", "hi")),
                maxTokens = 100,
                onToken = { tokens.add(it) },
                onComplete = {},
                onError = { fail("Should not error") },
            )

            assertEquals(listOf("content"), tokens, "Should only process data lines")
        }
}
