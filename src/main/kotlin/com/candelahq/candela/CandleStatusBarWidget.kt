package com.candelahq.candela

import com.candelahq.candela.client.CandelaClient
import com.candelahq.candela.client.DashboardData
import com.candelahq.candela.settings.CandleSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.event.MouseEvent
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

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
    }

    private var statusBar: StatusBar? = null
    private var currentText = "🕯️ Candela"
    private var currentTooltip = "Candela — LLM Cost Tracker"
    private var lastData: DashboardData? = null

    private val scheduler =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "candela-status-bar").apply { isDaemon = true }
        }
    private var refreshTask: ScheduledFuture<*>? = null
    private var client: CandelaClient? = null

    override fun ID(): String = ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        val settings = CandleSettings.getInstance().state
        client = CandelaClient(settings.serverUrl, cacheTtlMs = 30_000)
        scheduleRefresh(settings.autoRefreshIntervalSeconds)
        // Immediate first fetch
        scheduler.submit { refresh() }
    }

    override fun dispose() {
        refreshTask?.cancel(true)
        scheduler.shutdownNow()
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
        scheduler.submit { refresh() }
    }

    private fun scheduleRefresh(intervalSeconds: Int) {
        refreshTask?.cancel(false)
        if (intervalSeconds <= 0) return
        refreshTask =
            scheduler.scheduleAtFixedRate(
                { refresh() },
                intervalSeconds.toLong(),
                intervalSeconds.toLong(),
                TimeUnit.SECONDS,
            )
    }

    private fun refresh() {
        val data = client?.getDashboardData()
        if (data == null) {
            currentText = "🕯️ offline"
            currentTooltip = "Candela is not running"
            // Back off to 5 minutes when offline
            refreshTask?.cancel(false)
            refreshTask =
                scheduler.scheduleAtFixedRate(
                    { refresh() },
                    300,
                    300,
                    TimeUnit.SECONDS,
                )
        } else {
            lastData = data
            currentText = formatStatusText(data)
            currentTooltip = formatTooltip(data)

            // Check for budget warning
            val settings = CandleSettings.getInstance().state
            val threshold = settings.budgetWarningThreshold
            data.budget?.let { budget ->
                if (budget.percentUsed >= threshold) {
                    ApplicationManager.getApplication().invokeLater {
                        CandleNotifications.showBudgetWarning(project, budget)
                    }
                }
            }

            // Restore normal polling interval
            val interval = CandleSettings.getInstance().state.autoRefreshIntervalSeconds
            scheduleRefresh(interval)
        }

        ApplicationManager.getApplication().invokeLater {
            statusBar?.updateWidget(ID)
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
