package com.candelahq.candela.client

import java.net.http.HttpClient
import java.time.Duration

/**
 * Shared [HttpClient] singleton for the Candela plugin.
 *
 * Both [CandelaClient] (dashboard API) and [ChatClient] (LLM streaming)
 * share this instance, reducing thread count and enabling HTTP connection reuse.
 *
 * Configuration:
 * - 10-second connect timeout (suitable for both local and remote servers)
 * - Default HTTP/2 preference (negotiated via ALPN; falls back to HTTP/1.1)
 */
object SharedHttpClient {
    val instance: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()
}
