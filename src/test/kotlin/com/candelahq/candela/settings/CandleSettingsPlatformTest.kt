package com.candelahq.candela.settings

import com.candelahq.candela.CandelaPlatformTestCase

/**
 * Platform tests for [CandleSettings].
 *
 * Verifies that the PersistentStateComponent correctly stores and restores
 * settings via [loadState] / [getState] roundtrips.
 */
class CandleSettingsPlatformTest : CandelaPlatformTestCase() {
    fun testDefaultSettingsValues() {
        val settings = CandleSettings.getInstance()
        val state = settings.state

        assertEquals("http://localhost:8181", state.serverUrl)
        assertTrue(state.statusBarEnabled)
        assertEquals(60, state.autoRefreshIntervalSeconds)
        assertEquals(80, state.budgetWarningThreshold)
        assertEquals("http://127.0.0.1:1234", state.chatServerUrl)
        assertEquals("", state.defaultModel)
        assertEquals(
            "You are a helpful coding assistant working inside a JetBrains IDE.",
            state.systemPrompt,
        )
        assertEquals(4096, state.maxTokens)
    }

    fun testLoadStateGetStateRoundtrip() {
        val settings = CandleSettings.getInstance()

        val custom =
            CandleSettings.State(
                serverUrl = "https://custom.example.com",
                statusBarEnabled = false,
                autoRefreshIntervalSeconds = 120,
                budgetWarningThreshold = 50,
                chatServerUrl = "http://10.0.0.1:9999",
                defaultModel = "gpt-4",
                systemPrompt = "Custom prompt",
                maxTokens = 8192,
            )

        settings.loadState(custom)
        val restored = settings.state

        assertEquals("https://custom.example.com", restored.serverUrl)
        assertFalse(restored.statusBarEnabled)
        assertEquals(120, restored.autoRefreshIntervalSeconds)
        assertEquals(50, restored.budgetWarningThreshold)
        assertEquals("http://10.0.0.1:9999", restored.chatServerUrl)
        assertEquals("gpt-4", restored.defaultModel)
        assertEquals("Custom prompt", restored.systemPrompt)
        assertEquals(8192, restored.maxTokens)
    }

    fun testCustomValuesPersistThroughLoadCycle() {
        val settings = CandleSettings.getInstance()

        // First load: set custom values
        val first =
            CandleSettings.State(
                serverUrl = "https://first.example.com",
                defaultModel = "claude-3",
                maxTokens = 2048,
            )
        settings.loadState(first)
        assertEquals("https://first.example.com", settings.state.serverUrl)
        assertEquals("claude-3", settings.state.defaultModel)
        assertEquals(2048, settings.state.maxTokens)

        // Second load: overwrite with new values
        val second =
            CandleSettings.State(
                serverUrl = "https://second.example.com",
                defaultModel = "gemini-pro",
                maxTokens = 16384,
            )
        settings.loadState(second)
        assertEquals("https://second.example.com", settings.state.serverUrl)
        assertEquals("gemini-pro", settings.state.defaultModel)
        assertEquals(16384, settings.state.maxTokens)

        // Verify getState returns the latest values
        val currentState = settings.state
        assertEquals("https://second.example.com", currentState.serverUrl)
        assertEquals("gemini-pro", currentState.defaultModel)
    }
}
