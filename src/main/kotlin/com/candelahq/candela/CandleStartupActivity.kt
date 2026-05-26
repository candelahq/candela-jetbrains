package com.candelahq.candela

import com.candelahq.candela.client.CandelaClient
import com.candelahq.candela.settings.CandleSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Runs once on project open — checks if Candela is reachable
 * and shows a brief status notification.
 */
class CandleStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val settings = CandleSettings.getInstance().state
        val client = CandelaClient(settings.serverUrl)

        if (client.isAlive()) {
            val data = client.getDashboardData()
            if (data != null) {
                CandleNotifications.showOnline(project, data)
            }
        }
        // Silently do nothing if offline — status bar will show "offline"
    }
}
