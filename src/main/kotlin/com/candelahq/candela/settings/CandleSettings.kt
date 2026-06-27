package com.candelahq.candela.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(name = "CandleSettings", storages = [Storage("candela.xml")])
class CandleSettings : PersistentStateComponent<CandleSettings.State> {
    data class State(
        var serverUrl: String = "http://localhost:8181",
        var statusBarEnabled: Boolean = true,
        var autoRefreshIntervalSeconds: Int = 60,
        var budgetWarningThreshold: Int = 80,
        // Chat settings
        var chatServerUrl: String = "http://127.0.0.1:1234",
        var defaultModel: String = "",
        var systemPrompt: String = "You are a helpful coding assistant working inside a JetBrains IDE.",
        var maxTokens: Int = 4096,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun getInstance(): CandleSettings = ApplicationManager.getApplication().getService(CandleSettings::class.java)
    }
}
