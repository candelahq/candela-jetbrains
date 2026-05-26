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
