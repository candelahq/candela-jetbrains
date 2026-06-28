package com.candelahq.candela

import com.candelahq.candela.client.CandelaClient
import com.candelahq.candela.client.DashboardData
import com.candelahq.candela.settings.CandleSettings
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
        private const val OFFLINE_BACKOFF_MS = 300_000L // 5 minutes
        private const val BUDGET_WARNING_COOLDOWN_MS = 30 * 60 * 1000L // 30 minutes
    }

    private val log = Logger.getInstance(CandleStatusBarWidget::class.java)

    private var statusBar: StatusBar? = null

    @Volatile
    private var currentText = "🕯️ Candela"

    @Volatile
    private var currentTooltip = "Candela — LLM Cost Tracker"

    @Volatile
    private var lastData: DashboardData? = null

    /** Coroutine scope for this widget — cancelled in [dispose]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

    private fun startRefreshLoop(intervalSeconds: Int) {
        refreshJob?.cancel()
        if (intervalSeconds <= 0) return
        refreshJob =
            scope.launch {
                // Immediate first fetch
                refresh()
                // Then loop at the configured interval
                while (true) {
                    delay(intervalSeconds * 1000L)
                    refresh()
                }
            }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun refresh() {
        val settings = CandleSettings.getInstance().state
        val serverUrl = settings.serverUrl
        if (serverUrl != activeServerUrl) {
            activeServerUrl = serverUrl
            client = CandelaClient(serverUrl, cacheTtlMs = 30_000)
        }

        try {
            val data = client?.getDashboardData()
            if (data == null) {
                currentText = "🕯️ offline"
                currentTooltip = "Candela is not running"
                log.info("Candela status: offline")
                // Back off when offline
                delay(OFFLINE_BACKOFF_MS)
            } else {
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
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Status bar refresh failed", e)
            currentText = "🕯️ offline"
            currentTooltip = "Candela is not running"
        }

        // Update the status bar widget on EDT (protected to not crash the loop)
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
