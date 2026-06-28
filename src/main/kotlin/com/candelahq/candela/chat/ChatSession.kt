package com.candelahq.candela.chat

import com.candelahq.candela.client.ChatMessage
import com.candelahq.candela.settings.CandleSettings
import kotlinx.coroutines.Job
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manages the state of a single chat conversation.
 *
 * Thread-safe: [_messages] uses [CopyOnWriteArrayList] for safe concurrent
 * access between EDT and background coroutines. Streaming state is managed
 * via [streamingJob] — cancellation is handled by cancelling the coroutine Job.
 */
class ChatSession {
    private val _messages = CopyOnWriteArrayList<ChatMessage>()

    /** Immutable snapshot of the conversation history (excludes system prompt). */
    val messages: List<ChatMessage> get() = _messages.toList()

    /**
     * The currently running streaming coroutine, if any.
     * Set by [ChatPanel] when starting a stream, cancelled to abort.
     */
    @Volatile
    var streamingJob: Job? = null

    /** Whether a streaming response is in progress. */
    val isStreaming: Boolean get() = streamingJob?.isActive == true

    fun addUserMessage(content: String) {
        _messages.add(ChatMessage("user", content))
    }

    fun addAssistantMessage(content: String) {
        _messages.add(ChatMessage("assistant", content))
    }

    /** Cancel the current streaming response (if any). */
    fun cancelStreaming() {
        streamingJob?.cancel()
        streamingJob = null
    }

    fun clear() {
        cancelStreaming()
        _messages.clear()
    }

    /**
     * Build the full message list for the API request, including the system prompt.
     */
    fun buildRequestMessages(): List<ChatMessage> {
        val settings = CandleSettings.getInstance().state
        val systemPrompt = settings.systemPrompt
        val result = mutableListOf<ChatMessage>()
        if (systemPrompt.isNotBlank()) {
            result.add(ChatMessage("system", systemPrompt))
        }
        result.addAll(_messages)
        return result
    }
}
