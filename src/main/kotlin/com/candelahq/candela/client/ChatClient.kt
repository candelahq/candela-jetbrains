package com.candelahq.candela.client

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streaming chat client for the Candela LM Studio-compatible API (port 1234).
 *
 * Uses [java.net.http.HttpClient] with manual line-by-line SSE parsing.
 * Does NOT use ktor's SSE client (which has broken EOF handling).
 */
class ChatClient {

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    private val gson = Gson()

    /**
     * Fetch available models from GET /v1/models.
     *
     * @return list of model info, empty on error
     */
    fun fetchModels(baseUrl: String): List<ModelInfo> {
        return try {
            val request = HttpRequest.newBuilder()
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
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Stream a chat completion via SSE.
     *
     * Sends POST /v1/chat/completions with stream=true and reads the response
     * line-by-line, parsing SSE `data:` events.
     *
     * @param baseUrl   Chat server base URL (e.g. "http://127.0.0.1:1234")
     * @param model     Model ID to use
     * @param messages  Conversation messages (including system prompt)
     * @param maxTokens Maximum tokens to generate
     * @param cancelled Set to true to abort the stream
     * @param onToken   Called for each content delta (on the calling thread)
     * @param onComplete Called when stream finishes, with optional usage info
     * @param onError   Called on any exception
     */
    fun streamChat(
        baseUrl: String,
        model: String,
        messages: List<ChatMessage>,
        maxTokens: Int,
        cancelled: AtomicBoolean,
        onToken: (String) -> Unit,
        onComplete: (ChunkUsage?) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        try {
            val body = buildRequestBody(model, messages, maxTokens)

            val request = HttpRequest.newBuilder()
                .uri(URI.create("${baseUrl.trimEnd('/')}/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(300)) // Long timeout for streaming
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())

            if (response.statusCode() !in 200..299) {
                val errorBody = response.body().bufferedReader().use { it.readText() }
                onError(RuntimeException("Chat API returned ${response.statusCode()}: $errorBody"))
                return
            }

            var lastUsage: ChunkUsage? = null
            var doneReceived = false

            BufferedReader(InputStreamReader(response.body(), Charsets.UTF_8)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    if (cancelled.get()) break

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

                    // Stream terminator
                    if (data == "[DONE]") {
                        doneReceived = true
                        break
                    }

                    try {
                        val chunk = gson.fromJson(data, ChatCompletionChunk::class.java)

                        // Capture usage if present (usually in the final chunk)
                        if (chunk.usage != null) {
                            lastUsage = chunk.usage
                        }

                        // Extract content delta — null-safe for all the quirks
                        val choice = chunk.choices.firstOrNull()
                        val content = choice?.delta?.content ?: ""

                        if (content.isNotEmpty()) {
                            onToken(content)
                        }
                    } catch (_: Exception) {
                        // Skip malformed chunks silently
                    }

                    line = reader.readLine()
                }
            }

            // Signal completion if not cancelled
            if (!cancelled.get()) {
                onComplete(lastUsage)
            }
        } catch (e: Exception) {
            if (!cancelled.get()) {
                onError(e)
            }
        }
    }

    private fun buildRequestBody(
        model: String,
        messages: List<ChatMessage>,
        maxTokens: Int,
    ): String {
        val body = JsonObject().apply {
            addProperty("model", model)
            addProperty("stream", true)
            addProperty("max_tokens", maxTokens)

            val messagesArray = com.google.gson.JsonArray()
            for (msg in messages) {
                val msgObj = JsonObject().apply {
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
