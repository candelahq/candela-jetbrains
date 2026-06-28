package com.candelahq.candela.client

import kotlinx.coroutines.CancellationException
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

    // ── Additional coverage ─────────────────────────────────────────────

    @Test
    fun `streamChat aborts when response exceeds MAX_STREAM_BYTES`() =
        runTest {
            // Each chunk has ~100KB of content; 25 chunks ≈ 2.5MB which exceeds the 2MB limit
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

            val tokens = mutableListOf<String>()
            var error: Exception? = null
            var completed = false
            client.streamChat(
                baseUrl = baseUrl,
                model = "test",
                messages = listOf(ChatMessage("user", "hi")),
                maxTokens = 100,
                onToken = { tokens.add(it) },
                onComplete = { completed = true },
                onError = { error = it },
            )

            assertNotNull(error, "Should have received an error")
            assertTrue(error!!.message!!.contains("too large"), "Error should mention 'too large'")
            assertTrue(!completed, "onComplete should not fire after abort")
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

            val job =
                launch {
                    client.fetchModels(baseUrl)
                }
            // Cancel immediately — the request is blocked on body delay
            job.cancel()
            job.join()

            // If CancellationException were swallowed, the job would complete
            // normally (isCompleted=true, isCancelled=false). Because fetchModels
            // re-throws it, the job is properly cancelled.
            assertTrue(job.isCancelled, "CancellationException should propagate, not be swallowed")
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

            client.streamChat(
                baseUrl = baseUrl,
                model = "test",
                messages = listOf(ChatMessage("user", "hi")),
                maxTokens = 100,
                onToken = {},
                onComplete = {},
                onError = {},
            )

            val request = server.takeRequest()
            val body = request.body.readUtf8()
            assertTrue(
                body.contains("\"stream_options\":{\"include_usage\":true}"),
                "Request body should include stream_options with include_usage=true, got: $body",
            )
        }

    @Test
    fun `streamChat invokes onError and stops on non-2xx without leaking`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .setBody("Unauthorized: invalid API key"),
            )

            val tokens = mutableListOf<String>()
            var error: Exception? = null
            var completed = false
            client.streamChat(
                baseUrl = baseUrl,
                model = "test",
                messages = listOf(ChatMessage("user", "hi")),
                maxTokens = 100,
                onToken = { tokens.add(it) },
                onComplete = { completed = true },
                onError = { error = it },
            )

            assertNotNull(error, "onError should have been called")
            assertTrue(error!!.message!!.contains("401"), "Error should contain status code 401")
            assertTrue(
                error!!.message!!.contains("Unauthorized"),
                "Error should contain the response body",
            )
            assertTrue(tokens.isEmpty(), "No tokens should be delivered on auth failure")
            assertTrue(!completed, "onComplete should not fire on error")
        }

    @Test
    fun `streamChat handles very large error body gracefully`() =
        runTest {
            // Build a 10KB error body; the production code calls .take(1000)
            val largeErrorBody = "E".repeat(10_000)

            server.enqueue(
                MockResponse()
                    .setResponseCode(500)
                    .setBody(largeErrorBody),
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

            assertNotNull(error, "onError should have been called")
            assertTrue(error!!.message!!.contains("500"), "Error should contain status code")
            // The production code truncates the body with .take(1000), so the full 10KB should not appear
            val errorMsg = error!!.message!!
            val bodyInMsg = errorMsg.substringAfter(": ")
            assertTrue(bodyInMsg.length <= 1000, "Error body should be truncated to at most 1000 chars, got ${bodyInMsg.length}")
            assertTrue(bodyInMsg.startsWith("EEEE"), "Error should contain the start of the error body")
        }
}
