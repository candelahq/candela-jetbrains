package com.candelahq.candela.client

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.coroutines.coroutineContext

/**
 * Streaming chat client for the Candela LM Studio-compatible API (port 1234).
 *
 * Uses [java.net.http.HttpClient] with manual line-by-line SSE parsing.
 * All public methods are suspend functions that run blocking I/O on [Dispatchers.IO].
 * Cancellation is handled via coroutine cancellation (no more [AtomicBoolean]).
 */
class ChatClient {
    private val log = Logger.getInstance(ChatClient::class.java)

    private val client =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()
    private val gson = Gson()

    companion object {
        /** Maximum accumulated stream content size (2 MB). Prevents OOM on malicious servers. */
        private const val MAX_STREAM_BYTES = 2 * 1024 * 1024L
    }

    /**
     * Fetch available models from GET /v1/models.
     *
     * @return list of model info, empty on error
     */
    suspend fun fetchModels(baseUrl: String): List<ModelInfo> =
        withContext(Dispatchers.IO) {
            log.info("Fetching models from ${baseUrl.trimEnd('/')}")
            try {
                ensureActive()
                val request =
                    HttpRequest
                        .newBuilder()
                        .uri(URI.create("${baseUrl.trimEnd('/')}/v1/models"))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build()

                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() in 200..299) {
                    val modelsResponse = gson.fromJson(response.body(), ModelsResponse::class.java)
                    modelsResponse.data
                } else {
                    emptyList()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught")
                e: Exception,
            ) {
                log.warn("Failed to fetch models", e)
                emptyList()
            }
        }

    /**
     * Stream a chat completion via SSE.
     *
     * Sends POST /v1/chat/completions with stream=true and reads the response
     * line-by-line, parsing SSE `data:` events.
     *
     * Cancellation is handled via the coroutine's [Job] — when the caller cancels
     * the coroutine, [ensureActive] throws [CancellationException] and the stream
     * is aborted cleanly.
     *
     * @param baseUrl   Chat server base URL (e.g. "http://127.0.0.1:1234")
     * @param model     Model ID to use
     * @param messages  Conversation messages (including system prompt)
     * @param maxTokens Maximum tokens to generate
     * @param onToken   Called for each content delta (on IO dispatcher)
     * @param onComplete Called when stream finishes, with optional usage info
     * @param onError   Called on any exception (not called on cancellation)
     */
    suspend fun streamChat(
        baseUrl: String,
        model: String,
        messages: List<ChatMessage>,
        maxTokens: Int,
        onToken: (String) -> Unit,
        onComplete: (ChunkUsage?) -> Unit,
        onError: (Exception) -> Unit,
    ) = withContext(Dispatchers.IO) {
        try {
            log.info("Starting chat stream: model=$model, maxTokens=$maxTokens")
            val body = buildRequestBody(model, messages, maxTokens)

            val request =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create("${baseUrl.trimEnd('/')}/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .timeout(Duration.ofSeconds(300)) // Long timeout for streaming
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()

            ensureActive()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())

            if (response.statusCode() !in 200..299) {
                val errorBody = response.body().use { it.bufferedReader().readText().take(1000) }
                onError(RuntimeException("Chat API returned ${response.statusCode()}: $errorBody"))
                return@withContext
            }

            var lastUsage: ChunkUsage? = null
            var doneReceived = false
            var totalBytes = 0L

            // Close the InputStream on cancellation to unblock readLine()
            val inputStream = response.body()
            coroutineContext.job.invokeOnCompletion { inputStream.close() }

            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                var line = reader.readLine()
                @Suppress("LoopWithTooManyJumpStatements") // SSE protocol parsing
                while (line != null) {
                    // Check for coroutine cancellation instead of AtomicBoolean
                    coroutineContext.ensureActive()

                    // Skip empty lines (SSE event delimiters)
                    if (line.isBlank()) {
                        line = reader.readLine()
                        continue
                    }

                    // Only process data lines
                    if (!line.startsWith("data: ") && !line.startsWith("data:")) {
                        line = reader.readLine()
                        continue
                    }

                    val data = line.removePrefix("data: ").removePrefix("data:").trim()
                    log.debug("SSE data: ${data.take(200)}")

                    // Stream terminator
                    if (data == "[DONE]") {
                        doneReceived = true
                        break
                    }

                    try {
                        val chunk = gson.fromJson(data, ChatCompletionChunk::class.java)
                        if (chunk != null) {
                            // Capture usage if present (usually in the final chunk)
                            if (chunk.usage != null) {
                                lastUsage = chunk.usage
                            }

                            // Extract content delta — null-safe for all the quirks
                            val choice = chunk.choices?.firstOrNull()
                            val content = choice?.delta?.content ?: ""

                            if (content.isNotEmpty()) {
                                totalBytes += content.length
                                if (totalBytes > MAX_STREAM_BYTES) {
                                    log.warn("Stream exceeded ${MAX_STREAM_BYTES / 1024}KB limit, aborting")
                                    onError(RuntimeException("Response too large (>${MAX_STREAM_BYTES / 1024}KB) — aborting"))
                                    return@withContext
                                }
                                onToken(content)
                            }
                        }
                    } catch (
                        @Suppress("TooGenericExceptionCaught")
                        e: Exception,
                    ) {
                        log.warn("Malformed SSE chunk: ${data.take(200)}", e)
                    }

                    line = reader.readLine()
                }
            }

            // Signal completion or premature EOF (not called on cancellation)
            if (doneReceived) {
                log.info("Chat stream complete: ${lastUsage?.totalTokens ?: "?"} tokens")
                onComplete(lastUsage)
            } else {
                log.warn("Chat stream ended without [DONE] marker")
                onError(RuntimeException("Stream ended unexpectedly — response may be incomplete"))
            }
        } catch (
            @Suppress("TooGenericExceptionCaught")
            e: Exception,
        ) {
            // CancellationException propagates naturally — don't call onError
            if (e is kotlinx.coroutines.CancellationException) throw e
            log.error("Chat stream failed", e)
            onError(e)
        }
    }

    private fun buildRequestBody(
        model: String,
        messages: List<ChatMessage>,
        maxTokens: Int,
    ): String {
        val body =
            JsonObject().apply {
                addProperty("model", model)
                addProperty("stream", true)
                addProperty("max_tokens", maxTokens)

                val streamOptions = JsonObject().apply { addProperty("include_usage", true) }
                add("stream_options", streamOptions)

                val messagesArray = com.google.gson.JsonArray()
                for (msg in messages) {
                    val msgObj =
                        JsonObject().apply {
                            addProperty("role", msg.role)
                            addProperty("content", msg.content)
                        }
                    messagesArray.add(msgObj)
                }
                add("messages", messagesArray)
            }
        return gson.toJson(body)
    }
}
