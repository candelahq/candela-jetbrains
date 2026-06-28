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
 *
 * @param onMessageAdded optional callback fired after each message is added,
 *        used by [ChatHistoryService] to persist to SQLite.
 */
class ChatSession(
    private val onMessageAdded: (
        (
            role: String,
            content: String,
            model: String?,
            tokenCount: Int?,
            costUsd: Double?,
        ) -> Unit
    )? = null,
) {
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
        onMessageAdded?.invoke("user", content, null, null, null)
    }

    fun addAssistantMessage(
        content: String,
        model: String? = null,
        tokenCount: Int? = null,
        costUsd: Double? = null,
    ) {
        _messages.add(ChatMessage("assistant", content))
        onMessageAdded?.invoke("assistant", content, model, tokenCount, costUsd)
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
     * Restore messages from persistence (e.g., SQLite) without triggering callbacks.
     * Used on startup to rebuild from saved state.
     */
    fun restoreMessages(messages: List<ChatMessage>) {
        _messages.clear()
        _messages.addAll(messages)
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
