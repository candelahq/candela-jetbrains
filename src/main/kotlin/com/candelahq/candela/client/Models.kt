package com.candelahq.candela.client

import java.time.Instant

/** Aggregated usage for a time range. */
data class UsageSummary(
    val totalTokens: Long = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val totalCostUsd: Double = 0.0,
    val requestCount: Int = 0,
)

/** Per-model usage breakdown. */
data class ModelUsage(
    val model: String = "",
    val provider: String = "",
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val totalCostUsd: Double = 0.0,
    val requestCount: Int = 0,
    val cacheReadTokens: Long = 0,
    val cacheCreationTokens: Long = 0,
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

/** Budget state for the current period. */
data class BudgetInfo(
    val limitUsd: Double,
    val spentUsd: Double,
    val remainingUsd: Double,
    val percentUsed: Double,
    val isNearLimit: Boolean,
    val isExhausted: Boolean,
    val periodEnd: Instant?,
    val resetLabel: String,
)

/** A one-time bonus budget grant. */
data class GrantInfo(
    val id: String,
    val amountUsd: Double,
    val spentUsd: Double,
    val remainingUsd: Double,
    val reason: String,
    val expiresAt: Instant?,
    val isExpiringSoon: Boolean,
    val isExhausted: Boolean,
)

/** Consolidated dashboard data — usage + budget in one response. */
data class DashboardData(
    val usage: UsageSummary,
    val models: List<ModelUsage>,
    val budget: BudgetInfo?,
    val activeGrants: List<GrantInfo>,
    val totalRemainingUsd: Double?,
)

// ── Chat Types ────────────────────────────────────────────────────────────

/** A single message in a chat conversation. */
data class ChatMessage(
    val role: String,     // "system", "user", "assistant"
    val content: String,
)

/** Model info from GET /v1/models. */
data class ModelInfo(
    val id: String,
    val `object`: String = "model",
    val owned_by: String = "",
    val max_context_length: Int? = null,
)

/** Response wrapper for GET /v1/models. */
data class ModelsResponse(
    val `object`: String = "list",
    val data: List<ModelInfo> = emptyList(),
)

/** A single SSE chunk from POST /v1/chat/completions (stream=true). */
data class ChatCompletionChunk(
    val id: String = "",
    val `object`: String = "",
    val created: Long = 0,
    val model: String = "",
    val choices: List<ChunkChoice> = emptyList(),
    val usage: ChunkUsage? = null,
)

data class ChunkChoice(
    val index: Int = 0,
    val delta: ChunkDelta? = null,
    val finish_reason: String? = null,
)

data class ChunkDelta(
    val role: String? = null,
    val content: String? = null,
)

data class ChunkUsage(
    val prompt_tokens: Int = 0,
    val completion_tokens: Int = 0,
    val total_tokens: Int = 0,
)
