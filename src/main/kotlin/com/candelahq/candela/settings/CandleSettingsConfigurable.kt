package com.candelahq.candela.settings

import com.intellij.openapi.options.Configurable
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSeparator
import javax.swing.JSpinner
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel

class CandleSettingsConfigurable : Configurable {
    private var panel: JPanel? = null
    private var serverUrlField: JTextField? = null
    private var statusBarEnabledCheckbox: JCheckBox? = null
    private var refreshIntervalField: JSpinner? = null
    private var budgetThresholdField: JSpinner? = null

    // Chat settings fields
    private var chatServerUrlField: JTextField? = null
    private var systemPromptArea: JTextArea? = null
    private var maxTokensField: JSpinner? = null

    override fun getDisplayName(): String = "Candela"

    override fun createComponent(): JComponent {
        val settings = CandleSettings.getInstance().state

        serverUrlField = JTextField(settings.serverUrl, 30)
        statusBarEnabledCheckbox = JCheckBox("Show status bar widget", settings.statusBarEnabled)
        refreshIntervalField = JSpinner(SpinnerNumberModel(settings.autoRefreshIntervalSeconds, 0, 3600, 10))
        budgetThresholdField = JSpinner(SpinnerNumberModel(settings.budgetWarningThreshold, 0, 100, 5))

        chatServerUrlField = JTextField(settings.chatServerUrl, 30)
        systemPromptArea =
            JTextArea(settings.systemPrompt, 3, 40).apply {
                lineWrap = true
                wrapStyleWord = true
            }
        maxTokensField = JSpinner(SpinnerNumberModel(settings.maxTokens, 256, 32768, 256))

        panel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = BorderFactory.createEmptyBorder(10, 10, 10, 10)

                // ── Dashboard Settings ──
                add(sectionLabel("Dashboard"))
                add(Box.createVerticalStrut(4))
                add(labeledRow("Server URL:", serverUrlField!!))
                add(Box.createVerticalStrut(8))
                add(statusBarEnabledCheckbox)
                add(Box.createVerticalStrut(8))
                add(labeledRow("Refresh interval (seconds):", refreshIntervalField!!))
                add(Box.createVerticalStrut(8))
                add(labeledRow("Budget warning threshold (%):", budgetThresholdField!!))

                // ── Chat Settings ──
                add(Box.createVerticalStrut(16))
                add(
                    JSeparator().apply {
                        maximumSize = java.awt.Dimension(Int.MAX_VALUE, 1)
                        alignmentX = JPanel.LEFT_ALIGNMENT
                    },
                )
                add(Box.createVerticalStrut(8))
                add(sectionLabel("Chat"))
                add(Box.createVerticalStrut(4))
                add(labeledRow("Chat server URL:", chatServerUrlField!!))
                add(Box.createVerticalStrut(8))
                add(labeledRow("Max tokens:", maxTokensField!!))
                add(Box.createVerticalStrut(8))
                add(JLabel("System prompt:").apply { alignmentX = JPanel.LEFT_ALIGNMENT })
                add(Box.createVerticalStrut(4))
                add(
                    JScrollPane(systemPromptArea).apply {
                        alignmentX = JPanel.LEFT_ALIGNMENT
                        maximumSize = java.awt.Dimension(Int.MAX_VALUE, 80)
                    },
                )
            }
        return panel!!
    }

    override fun isModified(): Boolean {
        val settings = CandleSettings.getInstance().state
        return serverUrlField?.text != settings.serverUrl ||
            statusBarEnabledCheckbox?.isSelected != settings.statusBarEnabled ||
            (refreshIntervalField?.value as? Int) != settings.autoRefreshIntervalSeconds ||
            (budgetThresholdField?.value as? Int) != settings.budgetWarningThreshold ||
            chatServerUrlField?.text != settings.chatServerUrl ||
            systemPromptArea?.text != settings.systemPrompt ||
            (maxTokensField?.value as? Int) != settings.maxTokens
    }

    override fun apply() {
        val settings = CandleSettings.getInstance()
        settings.loadState(
            CandleSettings.State(
                serverUrl = serverUrlField?.text ?: "http://localhost:8181",
                statusBarEnabled = statusBarEnabledCheckbox?.isSelected ?: true,
                autoRefreshIntervalSeconds = (refreshIntervalField?.value as? Int) ?: 60,
                budgetWarningThreshold = (budgetThresholdField?.value as? Int) ?: 80,
                chatServerUrl = chatServerUrlField?.text ?: "http://127.0.0.1:1234",
                defaultModel = settings.state.defaultModel, // Preserve — set by ChatPanel
                systemPrompt = systemPromptArea?.text ?: "You are a helpful coding assistant working inside a JetBrains IDE.",
                maxTokens = (maxTokensField?.value as? Int) ?: 4096,
            ),
        )
    }

    override fun reset() {
        val settings = CandleSettings.getInstance().state
        serverUrlField?.text = settings.serverUrl
        statusBarEnabledCheckbox?.isSelected = settings.statusBarEnabled
        refreshIntervalField?.value = settings.autoRefreshIntervalSeconds
        budgetThresholdField?.value = settings.budgetWarningThreshold
        chatServerUrlField?.text = settings.chatServerUrl
        systemPromptArea?.text = settings.systemPrompt
        maxTokensField?.value = settings.maxTokens
    }

    private fun labeledRow(
        label: String,
        component: JComponent,
    ): JPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = JPanel.LEFT_ALIGNMENT
            add(JLabel(label))
            add(Box.createHorizontalStrut(8))
            add(component)
        }

    private fun sectionLabel(text: String): JLabel =
        JLabel(text).apply {
            font = font.deriveFont(java.awt.Font.BOLD, font.size + 1f)
            alignmentX = JPanel.LEFT_ALIGNMENT
        }
}
