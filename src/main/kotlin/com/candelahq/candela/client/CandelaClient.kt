package com.candelahq.candela.client

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/**
 * HTTP client for the Candela ConnectRPC API.
 *
 * Mirrors the TypeScript CandelaClient from candela-vscode.
 * Uses the consolidated GetDashboardData RPC with fallback to legacy RPCs.
 *
 * All public methods are suspend functions that run blocking I/O on [Dispatchers.IO].
 */
class CandelaClient(
    baseUrl: String = "http://localhost:8181",
    private val cacheTtlMs: Long = 0,
) {
    private val baseUrl = baseUrl.trimEnd('/')
    private val http =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build()
    private val gson = Gson()

    @Volatile
    private var alive: Boolean? = null

    @Volatile
    private var cache: CacheEntry? = null

    private data class CacheEntry(
        val data: DashboardData,
        val fetchedAt: Long,
    )

    // ── Health ────────────────────────────────────────────────────────────

    suspend fun isAlive(): Boolean =
        withContext(Dispatchers.IO) {
            alive?.let { if (it) return@withContext true }
            try {
                ensureActive()
                val req =
                    HttpRequest
                        .newBuilder()
                        .uri(URI.create("$baseUrl/healthz"))
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build()
                val res = http.send(req, HttpResponse.BodyHandlers.ofString())
                (res.statusCode() in 200..299).also { alive = it }
            } catch (
                @Suppress("TooGenericExceptionCaught")
                _: Exception,
            ) {
                alive = false
                false
            }
        }

    fun resetHealth() {
        alive = null
    }

    fun invalidateCache() {
        cache = null
    }

    // ── Public API ────────────────────────────────────────────────────────

    suspend fun getDashboardData(hours: Int = 24): DashboardData? {
        if (!isAlive()) return null

        cache?.let { c ->
            if (cacheTtlMs > 0 && (System.currentTimeMillis() - c.fetchedAt) < cacheTtlMs) {
                return c.data
            }
        }

        val data = tryGetDashboardData(hours) ?: legacyFanout(hours) ?: return null
        cache = CacheEntry(data, System.currentTimeMillis())
        return data
    }

    // ── Private: consolidated RPC ─────────────────────────────────────────

    private suspend fun tryGetDashboardData(hours: Int): DashboardData? =
        withContext(Dispatchers.IO) {
            try {
                ensureActive()
                val body =
                    buildTimeRangeBody(hours).apply {
                        addProperty("include_budget", true)
                    }
                val res = postRpc("candela.v1.DashboardService/GetDashboardData", body)
                if (res.statusCode() == 404 || res.statusCode() == 501) return@withContext null
                if (res.statusCode() !in 200..299) return@withContext null

                val json = gson.fromJson(res.body(), JsonObject::class.java)
                parseDashboardResponse(json)
            } catch (
                @Suppress("TooGenericExceptionCaught")
                _: Exception,
            ) {
                null
            }
        }

    private suspend fun legacyFanout(hours: Int): DashboardData? =
        coroutineScope {
            try {
                val timeRange = buildTimeRangeBody(hours)

                // Fan out two requests concurrently
                val summaryDeferred =
                    async(Dispatchers.IO) {
                        http.send(
                            buildRpcRequest("candela.v1.DashboardService/GetUsageSummary", timeRange),
                            HttpResponse.BodyHandlers.ofString(),
                        )
                    }
                val budgetDeferred =
                    async(Dispatchers.IO) {
                        http.send(
                            buildRpcRequest("candela.v1.UserService/GetMyBudget", JsonObject()),
                            HttpResponse.BodyHandlers.ofString(),
                        )
                    }

                var usage = UsageSummary()
                val summaryRes = summaryDeferred.await()
                if (summaryRes.statusCode() in 200..299) {
                    val s = gson.fromJson(summaryRes.body(), JsonObject::class.java)
                    usage = parseUsageSummary(s)
                }

                var budget: BudgetInfo? = null
                var activeGrants: List<GrantInfo> = emptyList()
                var totalRemainingUsd: Double? = null

                val budgetRes = budgetDeferred.await()
                if (budgetRes.statusCode() in 200..299) {
                    val b = gson.fromJson(budgetRes.body(), JsonObject::class.java)
                    budget = parseBudget(b.getAsJsonObject("budget"))
                    activeGrants = parseGrants(b.getAsJsonArray("activeGrants") ?: b.getAsJsonArray("active_grants"))
                    val raw = (b.get("totalRemainingUsd") ?: b.get("total_remaining_usd"))?.asDouble
                    if (raw != null && raw >= 0) totalRemainingUsd = raw
                }

                DashboardData(usage, emptyList(), budget, activeGrants, totalRemainingUsd)
            } catch (
                @Suppress("TooGenericExceptionCaught")
                _: Exception,
            ) {
                null
            }
        }

    // ── Parsing ───────────────────────────────────────────────────────────

    private fun parseDashboardResponse(json: JsonObject): DashboardData {
        val s = json.getAsJsonObject("summary") ?: JsonObject()
        val usage = parseUsageSummary(s)

        val modelsArray = json.getAsJsonArray("models")
        val models =
            modelsArray?.mapNotNull { el ->
                val m = el.asJsonObject
                ModelUsage(
                    model = m.str("model"),
                    provider = m.str("provider"),
                    inputTokens = m.long("inputTokens", "input_tokens"),
                    outputTokens = m.long("outputTokens", "output_tokens"),
                    totalCostUsd = m.dbl("costUsd", "cost_usd"),
                    requestCount = m.int("callCount", "call_count"),
                    cacheReadTokens = m.long("cacheReadTokens", "cache_read_tokens"),
                    cacheCreationTokens = m.long("cacheCreationTokens", "cache_creation_tokens"),
                )
            } ?: emptyList()

        val bc = json.getAsJsonObject("budgetContext") ?: json.getAsJsonObject("budget_context")
        var budget: BudgetInfo? = null
        var activeGrants: List<GrantInfo> = emptyList()
        var totalRemainingUsd: Double? = null

        if (bc != null) {
            budget = parseBudget(bc.getAsJsonObject("budget"))
            activeGrants = parseGrants(bc.getAsJsonArray("activeGrants") ?: bc.getAsJsonArray("active_grants"))
            val raw = (bc.get("totalRemainingUsd") ?: bc.get("total_remaining_usd"))?.asDouble
            if (raw != null && raw >= 0) totalRemainingUsd = raw
        }

        return DashboardData(usage, models, budget, activeGrants, totalRemainingUsd)
    }

    private fun parseUsageSummary(s: JsonObject): UsageSummary {
        val input = s.long("totalInputTokens", "total_input_tokens")
        val output = s.long("totalOutputTokens", "total_output_tokens")
        return UsageSummary(
            totalTokens = input + output,
            inputTokens = input,
            outputTokens = output,
            totalCostUsd = s.dbl("totalCostUsd", "total_cost_usd"),
            requestCount = s.int("totalLlmCalls", "total_llm_calls"),
        )
    }

    private fun parseBudget(raw: JsonObject?): BudgetInfo? {
        if (raw == null) return null
        val limitUsd = raw.dbl("limitUsd", "limit_usd")
        val spentUsd = raw.dbl("spentUsd", "spent_usd")
        if (!limitUsd.isFinite() || !spentUsd.isFinite()) return null
        val remaining = maxOf(0.0, limitUsd - spentUsd)
        val fraction = if (limitUsd > 0) minOf(1.0, spentUsd / limitUsd) else 0.0
        val periodEndRaw = raw.str("periodEnd", "period_end")
        val periodEnd =
            periodEndRaw.takeIf { it.isNotEmpty() }?.let {
                try {
                    Instant.parse(it)
                } catch (
                    @Suppress("TooGenericExceptionCaught")
                    _: Exception,
                ) {
                    null
                }
            }
        return BudgetInfo(
            limitUsd = limitUsd,
            spentUsd = spentUsd,
            remainingUsd = remaining,
            percentUsed = fraction * 100,
            isNearLimit = fraction >= 0.8,
            isExhausted = spentUsd >= limitUsd,
            periodEnd = periodEnd,
            resetLabel = computeResetLabel(periodEnd),
        )
    }

    private fun parseGrants(raw: com.google.gson.JsonArray?): List<GrantInfo> {
        if (raw == null) return emptyList()
        return raw.mapNotNull { el ->
            val g = el.asJsonObject
            val amountUsd = g.dbl("amountUsd", "amount_usd")
            val spentUsd = g.dbl("spentUsd", "spent_usd")
            val expiresRaw = g.str("expiresAt", "expires_at")
            val expiresAt =
                expiresRaw.takeIf { it.isNotEmpty() }?.let {
                    try {
                        Instant.parse(it)
                    } catch (
                        @Suppress("TooGenericExceptionCaught")
                        _: Exception,
                    ) {
                        null
                    }
                }
            GrantInfo(
                id = g.str("id"),
                amountUsd = amountUsd,
                spentUsd = spentUsd,
                remainingUsd = maxOf(0.0, amountUsd - spentUsd),
                reason = g.str("reason"),
                expiresAt = expiresAt,
                isExpiringSoon =
                    expiresAt != null &&
                        expiresAt.isAfter(Instant.now()) &&
                        Duration.between(Instant.now(), expiresAt).toDays() < 7,
                isExhausted = spentUsd >= amountUsd,
            )
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun buildTimeRangeBody(hours: Int): JsonObject {
        val now = Instant.now()
        val start = now.minusSeconds(hours * 3600L)
        return JsonObject().apply {
            add(
                "time_range",
                JsonObject().apply {
                    add(
                        "start",
                        JsonObject().apply {
                            addProperty("seconds", start.epochSecond.toString())
                            addProperty("nanos", 0)
                        },
                    )
                    add(
                        "end",
                        JsonObject().apply {
                            addProperty("seconds", now.epochSecond.toString())
                            addProperty("nanos", 0)
                        },
                    )
                },
            )
        }
    }

    private fun buildRpcRequest(
        method: String,
        body: JsonObject,
    ): HttpRequest =
        HttpRequest
            .newBuilder()
            .uri(URI.create("$baseUrl/$method"))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .build()

    private fun postRpc(
        method: String,
        body: JsonObject,
    ): HttpResponse<String> = http.send(buildRpcRequest(method, body), HttpResponse.BodyHandlers.ofString())

    private fun computeResetLabel(periodEnd: Instant?): String {
        if (periodEnd == null) return ""
        val diff = Duration.between(Instant.now(), periodEnd)
        if (diff.isNegative) return "resetting"
        val hours = diff.toHours()
        val minutes = diff.toMinutesPart()
        return if (hours >= 1) "resets in ${hours}h ${minutes}m" else "resets in ${minutes}m"
    }

    // Extension helpers for JsonObject
    private fun JsonObject.str(vararg keys: String): String =
        keys.firstNotNullOfOrNull { get(it)?.takeIf { e -> !e.isJsonNull }?.asString } ?: ""

    private fun JsonObject.dbl(vararg keys: String): Double =
        keys.firstNotNullOfOrNull { get(it)?.takeIf { e -> !e.isJsonNull }?.asDouble } ?: 0.0

    private fun JsonObject.long(vararg keys: String): Long =
        keys.firstNotNullOfOrNull { get(it)?.takeIf { e -> !e.isJsonNull }?.asLong } ?: 0L

    private fun JsonObject.int(vararg keys: String): Int = keys.firstNotNullOfOrNull { get(it)?.takeIf { e -> !e.isJsonNull }?.asInt } ?: 0
}
