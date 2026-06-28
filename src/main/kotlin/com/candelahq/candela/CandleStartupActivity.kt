package com.candelahq.candela

import com.candelahq.candela.client.CandelaClient
import com.candelahq.candela.settings.CandleSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs once on project open — checks if Candela is reachable
 * and shows a brief status notification.
 *
 * Uses [withContext(Dispatchers.IO)] for blocking HTTP calls
 * so we don't tie up the coroutine dispatcher thread.
 */
class CandleStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val settings = CandleSettings.getInstance().state
        val client = CandelaClient(settings.serverUrl)

        // CandelaClient methods are now suspend funs that use Dispatchers.IO internally
        if (client.isAlive()) {
            val data = client.getDashboardData()
            if (data != null) {
                withContext(Dispatchers.Main) {
                    CandleNotifications.showOnline(project, data)
                }
            }
        }
        // Silently do nothing if offline — status bar will show "offline"
    }
}
