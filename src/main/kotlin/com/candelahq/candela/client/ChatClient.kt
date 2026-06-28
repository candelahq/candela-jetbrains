package com.candelahq.candela.client

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

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
     * Stream a chat completion via SSE, returning a [Flow] of [StreamEvent]s.
     *
     * Sends POST /v1/chat/completions with stream=true and reads the response
     * line-by-line, parsing SSE `data:` events. Each content delta is emitted
     * as a [StreamEvent.Token], and the stream terminates with either
     * [StreamEvent.Complete] or [StreamEvent.Error].
     *
     * Cancellation is handled naturally — cancelling the collecting coroutine
     * closes the HTTP input stream and stops emission.
     *
     * @param baseUrl   Chat server base URL (e.g. "http://127.0.0.1:1234")
     * @param model     Model ID to use
     * @param messages  Conversation messages (including system prompt)
     * @param maxTokens Maximum tokens to generate
     * @return a cold [Flow] that begins streaming when collected
     */
    fun streamChat(
        baseUrl: String,
        model: String,
        messages: List<ChatMessage>,
        maxTokens: Int,
    ): Flow<StreamEvent> =
        flow {
            try {
                log.info("Starting chat stream: model=$model, maxTokens=$maxTokens")
                val body = buildRequestBody(model, messages, maxTokens)

                val request =
                    HttpRequest
                        .newBuilder()
                        .uri(URI.create("${baseUrl.trimEnd('/')}/v1/chat/completions"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .timeout(Duration.ofSeconds(300))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build()

                currentCoroutineContext().ensureActive()
                val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).await()

                if (response.statusCode() !in 200..299) {
                    val errorBody = response.body().use { it.bufferedReader().readText().take(1000) }
                    emit(StreamEvent.Error(RuntimeException("Chat API returned ${response.statusCode()}: $errorBody")))
                    return@flow
                }

                var lastUsage: ChunkUsage? = null
                var doneReceived = false
                var totalBytes = 0L

                val inputStream = response.body()
                try {
                    BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                        var line = reader.readLine()
                        @Suppress("LoopWithTooManyJumpStatements")
                        while (line != null) {
                            currentCoroutineContext().ensureActive()

                            if (line.isBlank()) {
                                line = reader.readLine()
                                continue
                            }

                            if (!line.startsWith("data: ") && !line.startsWith("data:")) {
                                line = reader.readLine()
                                continue
                            }

                            val data = line.removePrefix("data: ").removePrefix("data:").trim()
                            log.debug("SSE data: ${data.take(200)}")

                            if (data == "[DONE]") {
                                doneReceived = true
                                break
                            }

                            try {
                                val chunk = gson.fromJson(data, ChatCompletionChunk::class.java)
                                if (chunk != null) {
                                    if (chunk.usage != null) {
                                        lastUsage = chunk.usage
                                    }

                                    val choice = chunk.choices?.firstOrNull()
                                    val content = choice?.delta?.content ?: ""

                                    if (content.isNotEmpty()) {
                                        totalBytes += content.length
                                        if (totalBytes > MAX_STREAM_BYTES) {
                                            log.warn("Stream exceeded ${MAX_STREAM_BYTES / 1024}KB limit, aborting")
                                            emit(
                                                StreamEvent.Error(
                                                    RuntimeException("Response too large (>${MAX_STREAM_BYTES / 1024}KB) — aborting"),
                                                ),
                                            )
                                            return@flow
                                        }
                                        emit(StreamEvent.Token(content))
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
                } catch (_: IOException) {
                    // Stream was closed (likely due to cancellation) — check if cancelled
                    currentCoroutineContext().ensureActive()
                    // If not cancelled, treat as unexpected EOF
                    log.warn("Chat stream IOException (not cancellation)")
                    emit(StreamEvent.Error(RuntimeException("Stream ended unexpectedly — response may be incomplete")))
                    return@flow
                } finally {
                    // Always close the input stream to prevent resource leaks
                    try {
                        inputStream.close()
                    } catch (_: IOException) {
                        // Already closed
                    }
                }

                if (doneReceived) {
                    log.info("Chat stream complete: ${lastUsage?.totalTokens ?: "?"} tokens")
                    emit(StreamEvent.Complete(lastUsage))
                } else {
                    log.warn("Chat stream ended without [DONE] marker")
                    emit(StreamEvent.Error(RuntimeException("Stream ended unexpectedly — response may be incomplete")))
                }
            } catch (
                @Suppress("TooGenericExceptionCaught")
                e: Exception,
            ) {
                if (e is CancellationException) throw e
                log.error("Chat stream failed", e)
                emit(StreamEvent.Error(e))
            }
        }.flowOn(Dispatchers.IO)

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
