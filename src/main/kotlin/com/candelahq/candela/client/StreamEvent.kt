package com.candelahq.candela.client

/**
 * Events emitted by [ChatClient.streamChat] as a [kotlinx.coroutines.flow.Flow].
 *
 * Replaces the callback-based `onToken`/`onComplete`/`onError` API with a
 * composable, testable Flow that can be transformed, filtered, buffered,
 * and cancelled using standard coroutine primitives.
 */
sealed interface StreamEvent {
    /** A content token delta from the streaming response. */
    data class Token(
        val content: String,
    ) : StreamEvent

    /** Stream completed successfully, with optional token usage metadata. */
    data class Complete(
        val usage: ChunkUsage?,
    ) : StreamEvent

    /**
     * A non-fatal error occurred during streaming.
     *
     * Emitted for HTTP errors, premature EOF, or stream size limit exceeded.
     * The collector can decide whether to show an error or retry.
     * [CancellationException] is NOT wrapped — it propagates naturally.
     */
    data class Error(
        val exception: Exception,
    ) : StreamEvent
}
