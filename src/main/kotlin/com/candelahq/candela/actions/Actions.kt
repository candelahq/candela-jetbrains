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

class ShowCostSummaryAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = CandleSettings.getInstance().state
        val client = CandelaClient(settings.serverUrl)
        val data = client.getDashboardData()
        if (data != null) {
            CandleNotifications.showCostSummary(project, data)
        } else {
            CandleNotifications.showOffline(project)
        }
    }
}

class CheckBudgetAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = CandleSettings.getInstance().state
        val client = CandelaClient(settings.serverUrl)
        val data = client.getDashboardData()
        if (data?.budget != null) {
            CandleNotifications.showCostSummary(project, data)
        } else if (data != null) {
            CandleNotifications.showCostSummary(project, data)
        } else {
            CandleNotifications.showOffline(project)
        }
    }
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
