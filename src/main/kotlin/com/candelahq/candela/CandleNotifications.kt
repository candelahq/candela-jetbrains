package com.candelahq.candela

import com.candelahq.candela.client.BudgetInfo
import com.candelahq.candela.client.DashboardData
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

object CandleNotifications {
    private fun group() =
        NotificationGroupManager
            .getInstance()
            .getNotificationGroup("Candela")

    fun showCostSummary(
        project: Project,
        data: DashboardData,
    ) {
        val sb = StringBuilder()
        sb.appendLine("<b>Today's Usage</b>")
        sb.appendLine(
            "Tokens: ${formatTokenCount(
                data.usage.totalTokens,
            )} (${formatTokenCount(data.usage.inputTokens)} in / ${formatTokenCount(data.usage.outputTokens)} out)",
        )
        sb.appendLine("Cost: ${formatCost(data.usage.totalCostUsd)} · Requests: ${data.usage.requestCount}")

        if (data.models.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("<b>By Model:</b>")
            for (m in data.models.sortedByDescending { it.totalCostUsd }) {
                sb.appendLine("  ${m.model} (${m.provider}): ${formatTokenCount(m.totalTokens)}, ${formatCost(m.totalCostUsd)}")
            }
        }

        data.budget?.let { b ->
            sb.appendLine()
            sb.appendLine("<b>Budget:</b> ${formatCost(b.spentUsd)} / ${formatCost(b.limitUsd)} (${b.percentUsed.toInt()}%)")
        }

        group()
            .createNotification("Candela Cost Summary", sb.toString(), NotificationType.INFORMATION)
            .notify(project)
    }

    fun showBudgetWarning(
        project: Project,
        budget: BudgetInfo,
    ) {
        val pct = budget.percentUsed.toInt()
        val type = if (budget.isExhausted) NotificationType.ERROR else NotificationType.WARNING
        val title = if (budget.isExhausted) "🔴 Budget Exhausted" else "⚠️ Budget Warning ($pct%)"
        val content =
            "Spent ${formatCost(budget.spentUsd)} of ${formatCost(budget.limitUsd)} daily limit. " +
                "Remaining: ${formatCost(budget.remainingUsd)}. ${budget.resetLabel}"

        group()
            .createNotification(title, content, type)
            .notify(project)
    }

    fun showOffline(project: Project) {
        group()
            .createNotification(
                "Candela Offline",
                "Could not connect to Candela. Start it with <code>candela start</code>.",
                NotificationType.INFORMATION,
            ).notify(project)
    }

    fun showOnline(
        project: Project,
        data: DashboardData,
    ) {
        val cost = formatCost(data.usage.totalCostUsd)
        val tokens = formatTokenCount(data.usage.totalTokens)
        group()
            .createNotification(
                "🕯️ Candela Connected",
                "Today: $tokens tokens · $cost",
                NotificationType.INFORMATION,
            ).notify(project)
    }
}
