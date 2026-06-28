package com.candelahq.candela.client

import com.google.gson.annotations.SerializedName
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
    val role: String, // "system", "user", "assistant"
    val content: String,
)

/** Model info from GET /v1/models. */
data class ModelInfo(
    val id: String,
    @SerializedName("object")
    val obj: String = "model",
    @SerializedName("owned_by")
    val ownedBy: String = "",
    @SerializedName("max_context_length")
    val maxContextLength: Int? = null,
)

/** Response wrapper for GET /v1/models. */
data class ModelsResponse(
    @SerializedName("object")
    val obj: String = "list",
    val data: List<ModelInfo> = emptyList(),
)

/** A single SSE chunk from POST /v1/chat/completions (stream=true). */
data class ChatCompletionChunk(
    val id: String = "",
    @SerializedName("object")
    val obj: String = "",
    val created: Long = 0,
    val model: String = "",
    val choices: List<ChunkChoice>? = emptyList(),
    val usage: ChunkUsage? = null,
)

data class ChunkChoice(
    val index: Int = 0,
    val delta: ChunkDelta? = null,
    @SerializedName("finish_reason")
    val finishReason: String? = null,
)

data class ChunkDelta(
    val role: String? = null,
    val content: String? = null,
)

data class ChunkUsage(
    @SerializedName("prompt_tokens")
    val promptTokens: Int = 0,
    @SerializedName("completion_tokens")
    val completionTokens: Int = 0,
    @SerializedName("total_tokens")
    val totalTokens: Int = 0,
)

/**
 * Per-token pricing in USD (input, output) per 1M tokens.
 *
 * Covers popular models exposed through Candela's proxy. Returns `null`
 * for unknown models so the UI can gracefully omit cost display.
 */
data class TokenPricing(
    val inputPerMillion: Double,
    val outputPerMillion: Double,
)

/**
 * Estimate the USD cost of this usage chunk based on model pricing.
 *
 * @return estimated cost in USD, or `null` if the model isn't in the pricing table
 */
fun ChunkUsage.estimatedCostUsd(model: String): Double? {
    val pricing =
        MODEL_PRICING.entries
            .firstOrNull { (pattern, _) ->
                model.lowercase().contains(pattern)
            }?.value ?: return null

    return (promptTokens * pricing.inputPerMillion + completionTokens * pricing.outputPerMillion) / 1_000_000.0
}

/**
 * Format a USD cost for display.
 *
 * - Costs ≥ $0.01 → "$0.03"
 * - Costs < $0.01 → "$0.003" (3 decimal places)
 * - Costs < $0.001 → "<$0.001"
 */
fun formatCostUsd(cost: Double): String =
    when {
        cost < 0.001 -> "<\$0.001"
        cost < 0.01 -> "\$%.3f".format(cost)
        else -> "\$%.2f".format(cost)
    }

/**
 * Pricing table keyed by model ID substring (lowercase).
 * Ordered most-specific first to avoid prefix collisions.
 */
private val MODEL_PRICING =
    linkedMapOf(
        // GPT-4o family
        "gpt-4o-mini" to TokenPricing(0.15, 0.60),
        "gpt-4o" to TokenPricing(2.50, 10.00),
        "gpt-4-turbo" to TokenPricing(10.00, 30.00),
        "gpt-4" to TokenPricing(30.00, 60.00),
        // GPT-3.5
        "gpt-3.5-turbo" to TokenPricing(0.50, 1.50),
        // Claude 4
        "claude-4-opus" to TokenPricing(15.00, 75.00),
        "claude-4-sonnet" to TokenPricing(3.00, 15.00),
        // Claude 3.5
        "claude-3.5-sonnet" to TokenPricing(3.00, 15.00),
        "claude-3.5-haiku" to TokenPricing(0.80, 4.00),
        // Claude 3
        "claude-3-opus" to TokenPricing(15.00, 75.00),
        "claude-3-sonnet" to TokenPricing(3.00, 15.00),
        "claude-3-haiku" to TokenPricing(0.25, 1.25),
        // Gemini
        "gemini-2.5-pro" to TokenPricing(1.25, 10.00),
        "gemini-2.5-flash" to TokenPricing(0.15, 0.60),
        "gemini-2.0-flash" to TokenPricing(0.10, 0.40),
        "gemini-1.5-pro" to TokenPricing(1.25, 5.00),
        "gemini-1.5-flash" to TokenPricing(0.075, 0.30),
        // DeepSeek
        "deepseek-r1" to TokenPricing(0.55, 2.19),
        "deepseek-v3" to TokenPricing(0.27, 1.10),
        "deepseek-chat" to TokenPricing(0.27, 1.10),
        // Llama (local / cheap)
        "llama" to TokenPricing(0.0, 0.0),
        // Mistral
        "mistral-large" to TokenPricing(2.00, 6.00),
        "mistral-small" to TokenPricing(0.20, 0.60),
        "mistral" to TokenPricing(0.25, 0.25),
        // Codestral
        "codestral" to TokenPricing(0.30, 0.90),
        // Qwen (local / cheap)
        "qwen" to TokenPricing(0.0, 0.0),
    )
