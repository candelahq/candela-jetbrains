package com.candelahq.candela.chat

import com.candelahq.candela.client.ChatMessage
import com.candelahq.candela.settings.CandleSettings
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages the state of a single chat conversation.
 *
 * Thread-safe: [_messages] uses [CopyOnWriteArrayList] for safe concurrent
 * access between EDT and background threads. Streaming state is managed
 * via [cancelled] (AtomicBoolean) and volatile [isStreaming].
 */
class ChatSession {

    private val _messages = CopyOnWriteArrayList<ChatMessage>()

    /** Immutable snapshot of the conversation history (excludes system prompt). */
    val messages: List<ChatMessage> get() = _messages.toList()

    private val _cancelled = AtomicBoolean(false)

    /** Cancellation flag — set to true to abort a streaming response. */
    val cancelled: AtomicBoolean get() = _cancelled

    @Volatile
    var isStreaming: Boolean = false
        private set

    fun addUserMessage(content: String) {
        _messages.add(ChatMessage("user", content))
    }

    fun addAssistantMessage(content: String) {
        _messages.add(ChatMessage("assistant", content))
    }

    fun startStreaming() {
        isStreaming = true
        _cancelled.set(false)
    }

    fun stopStreaming() {
        isStreaming = false
    }

    fun cancelStreaming() {
        _cancelled.set(true)
        isStreaming = false
    }

    fun clear() {
        _messages.clear()
        isStreaming = false
        _cancelled.set(false)
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
