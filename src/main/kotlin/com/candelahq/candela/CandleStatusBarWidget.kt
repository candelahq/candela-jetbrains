package com.candelahq.candela

import com.candelahq.candela.client.CandelaClient
import com.candelahq.candela.client.DashboardData
import com.candelahq.candela.settings.CandleSettings
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Component
import java.awt.event.MouseEvent

class CandleStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = "CandelaStatusBar"

    override fun getDisplayName(): String = "Candela Cost Tracker"

    override fun isAvailable(project: Project): Boolean = CandleSettings.getInstance().state.statusBarEnabled

    override fun createWidget(project: Project): StatusBarWidget = CandleStatusBarWidget(project)
}

class CandleStatusBarWidget(
    private val project: Project,
) : StatusBarWidget,
    StatusBarWidget.TextPresentation {
    companion object {
        const val ID = "CandelaStatusBar"
        internal const val INITIAL_BACKOFF_MS = 30_000L // 30 seconds
        internal const val MAX_BACKOFF_MS = 300_000L // 5 minutes
        private const val JITTER_FACTOR = 0.2 // ±20%
        private const val BUDGET_WARNING_COOLDOWN_MS = 30 * 60 * 1000L // 30 minutes

        /**
         * Calculate backoff delay with exponential increase and random jitter.
         *
         * Formula: min(INITIAL * 2^(failures-1), MAX) ± 20% jitter
         */
        internal fun calculateBackoff(consecutiveFailures: Int): Long {
            val exponential = INITIAL_BACKOFF_MS * (1L shl (consecutiveFailures - 1).coerceIn(0, 10))
            val capped = exponential.coerceAtMost(MAX_BACKOFF_MS)
            val jitter = (capped * JITTER_FACTOR * (2 * Math.random() - 1)).toLong()
            return (capped + jitter).coerceAtLeast(INITIAL_BACKOFF_MS / 2)
        }
    }

    private val log = Logger.getInstance(CandleStatusBarWidget::class.java)

    private var statusBar: StatusBar? = null

    @Volatile
    private var currentText = "🕯️ Candela"

    @Volatile
    private var currentTooltip = "Candela — LLM Cost Tracker"

    @Volatile
    private var lastData: DashboardData? = null

    /**
     * Coroutine scope — child of the project-level scope, cancelled in [dispose].
     * Inherits full parent context (modality, tracing) and adds a [SupervisorJob]
     * so individual refresh failures don't cancel the scope.
     */
    private val scope =
        project.service<CandelaCoroutineService>().scope.let { parentScope ->
            CoroutineScope(
                parentScope.coroutineContext +
                    SupervisorJob(parentScope.coroutineContext[Job]) +
                    Dispatchers.Default,
            )
        }

    private var refreshJob: Job? = null
    private var client: CandelaClient? = null
    private var activeServerUrl: String = ""
    private var lastBudgetWarningMs: Long = 0L

    override fun ID(): String = ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        val settings = CandleSettings.getInstance().state
        client = CandelaClient(settings.serverUrl, cacheTtlMs = 30_000)
        startRefreshLoop(settings.autoRefreshIntervalSeconds)
    }

    override fun dispose() {
        scope.cancel("StatusBarWidget disposed")
    }

    // ── TextPresentation ──────────────────────────────────────────────────

    override fun getText(): String = currentText

    override fun getTooltipText(): String = currentTooltip

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getClickConsumer(): Consumer<MouseEvent> =
        Consumer {
            // Show cost summary on click
            val data = lastData ?: return@Consumer
            CandleNotifications.showCostSummary(project, data)
        }

    // ── Refresh ───────────────────────────────────────────────────────────

    fun forceRefresh() {
        client?.invalidateCache()
        client?.resetHealth()
        refreshJob?.cancel()
        startRefreshLoop(CandleSettings.getInstance().state.autoRefreshIntervalSeconds)
    }

    internal fun startRefreshLoop(intervalSeconds: Int) {
        refreshJob?.cancel()
        if (intervalSeconds <= 0) return
        refreshJob =
            scope.launch {
                var consecutiveFailures = 0
                while (true) {
                    val success = refresh()
                    if (success) {
                        consecutiveFailures = 0
                        delay(intervalSeconds * 1000L)
                    } else {
                        consecutiveFailures++
                        val backoff = calculateBackoff(consecutiveFailures)
                        log.info("Offline backoff: ${backoff}ms (failure #$consecutiveFailures)")
                        delay(backoff)
                    }
                }
            }
    }

    /**
     * Fetch dashboard data, update widget text, and return success/failure.
     *
     * Returns `true` if data was fetched successfully, `false` if the server
     * is offline or an error occurred. The caller uses this to decide whether
     * to apply the normal refresh interval or the offline backoff delay.
     */
    @Suppress("TooGenericExceptionCaught")
    internal suspend fun refresh(): Boolean {
        val settings = CandleSettings.getInstance().state
        val serverUrl = settings.serverUrl
        if (serverUrl != activeServerUrl) {
            activeServerUrl = serverUrl
            client = CandelaClient(serverUrl, cacheTtlMs = 30_000)
        }

        val success =
            try {
                val data = client?.getDashboardData()
                if (data != null) {
                    lastData = data
                    currentText = formatStatusText(data)
                    currentTooltip = formatTooltip(data)

                    // Check for budget warning (with cooldown to prevent notification spam)
                    val threshold = settings.budgetWarningThreshold
                    data.budget?.let { budget ->
                        val now = System.currentTimeMillis()
                        if (budget.percentUsed >= threshold && (now - lastBudgetWarningMs) > BUDGET_WARNING_COOLDOWN_MS) {
                            lastBudgetWarningMs = now
                            withContext(Dispatchers.Main) {
                                CandleNotifications.showBudgetWarning(project, budget)
                            }
                        }
                    }
                    true
                } else {
                    lastData = null
                    currentText = "🕯️ offline"
                    currentTooltip = "Candela is not running"
                    log.info("Candela status: offline")
                    false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Status bar refresh failed", e)
                lastData = null
                currentText = "🕯️ offline"
                currentTooltip = "Candela is not running"
                false
            }

        // Update the status bar widget on EDT
        updateWidgetOnEdt()
        return success
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun updateWidgetOnEdt() {
        try {
            withContext(Dispatchers.Main) {
                statusBar?.updateWidget(ID)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Failed to update status bar widget", e)
        }
    }

    // ── Formatting ────────────────────────────────────────────────────────

    private fun formatStatusText(data: DashboardData): String {
        val tokens = formatTokenCount(data.usage.totalTokens)
        val cost = formatCost(data.usage.totalCostUsd)
        val budgetPart =
            data.budget?.let { b ->
                val pct = b.percentUsed.toInt()
                val icon =
                    when {
                        b.isExhausted -> "🔴"
                        b.isNearLimit -> "🟡"
                        else -> "🟢"
                    }
                " · $icon$pct%"
            } ?: ""
        return "🔥 $tokens · $cost$budgetPart"
    }

    private fun formatTooltip(data: DashboardData): String {
        val sb = StringBuilder()
        sb.appendLine("Candela — Today's Usage")
        sb.appendLine("─".repeat(30))
        sb.appendLine(
            "Tokens: ${formatTokenCount(
                data.usage.totalTokens,
            )} (${formatTokenCount(data.usage.inputTokens)} in / ${formatTokenCount(data.usage.outputTokens)} out)",
        )
        sb.appendLine("Cost: ${formatCost(data.usage.totalCostUsd)}")
        sb.appendLine("Requests: ${data.usage.requestCount}")

        data.budget?.let { b ->
            sb.appendLine()
            sb.appendLine("Budget: ${formatCost(b.spentUsd)} / ${formatCost(b.limitUsd)} (${b.percentUsed.toInt()}%)")
            if (b.resetLabel.isNotEmpty()) sb.appendLine("  ${b.resetLabel}")
        }

        if (data.activeGrants.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Active Grants:")
            for (grant in data.activeGrants) {
                val expiry = grant.expiresAt?.let { " (expires ${it.toString().substringBefore('T')})" } ?: ""
                sb.appendLine("  🎁 ${formatCost(grant.remainingUsd)} — ${grant.reason}$expiry")
            }
        }

        if (data.models.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("By Model:")
            for (m in data.models.sortedByDescending { it.totalCostUsd }) {
                sb.appendLine("  ${m.model} (${m.provider}): ${formatTokenCount(m.totalTokens)}, ${formatCost(m.totalCostUsd)}")
            }
        }

        return sb.toString()
    }
}

// ── Shared formatting ─────────────────────────────────────────────────────

internal fun formatTokenCount(tokens: Long): String =
    when {
        tokens >= 1_000_000 -> "%.1fM".format(tokens / 1_000_000.0)
        tokens >= 1_000 -> "%.1fK".format(tokens / 1_000.0)
        else -> tokens.toString()
    }

internal fun formatCost(usd: Double): String = "\$%.2f".format(usd)
