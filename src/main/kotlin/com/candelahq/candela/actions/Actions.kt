package com.candelahq.candela.actions

import com.candelahq.candela.CandleNotifications
import com.candelahq.candela.CandleStatusBarWidget
import com.candelahq.candela.client.CandelaClient
import com.candelahq.candela.settings.CandleSettings
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared coroutine scope for fire-and-forget actions.
 * Cancelled via [cancelActionScope] (e.g. during plugin unload).
 * Uses [SupervisorJob] so individual action failures don't cancel other actions.
 */
private val actionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** Cancel the shared action scope — called during plugin disposal if needed. */
@Suppress("unused")
fun cancelActionScope() = actionScope.cancel("Plugin unloading")

/**
 * Fetch dashboard data and show a notification.
 * Shared implementation for [ShowCostSummaryAction] and [CheckBudgetAction].
 */
private fun fetchAndShowDashboard(e: AnActionEvent) {
    val project = e.project ?: return
    val settings = CandleSettings.getInstance().state
    val client = CandelaClient(settings.serverUrl)
    actionScope.launch {
        val data = client.getDashboardData()
        withContext(Dispatchers.Main) {
            if (data != null) {
                CandleNotifications.showCostSummary(project, data)
            } else {
                CandleNotifications.showOffline(project)
            }
        }
    }
}

class ShowCostSummaryAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) = fetchAndShowDashboard(e)
}

class CheckBudgetAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) = fetchAndShowDashboard(e)
}

class OpenDashboardAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val settings = CandleSettings.getInstance().state
        BrowserUtil.browse("${settings.serverUrl}/_local/")
    }
}

class RefreshStatusAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val statusBar = WindowManager.getInstance().getStatusBar(project) ?: return
        val widget = statusBar.getWidget(CandleStatusBarWidget.ID) as? CandleStatusBarWidget
        widget?.forceRefresh()
    }
}

/**
 * Build a fenced code block that safely handles code containing backticks.
 *
 * If the code contains triple backticks, the fence uses more backticks
 * (e.g. ``````) to avoid premature closing.
 */
fun buildCodeFence(
    code: String,
    lang: String = "",
): String {
    var fenceLength = 3
    val backtickRun = Regex("`{3,}")
    for (match in backtickRun.findAll(code)) {
        fenceLength = maxOf(fenceLength, match.value.length + 1)
    }
    val fence = "`".repeat(fenceLength)
    return "$fence$lang\n$code\n$fence"
}
