package com.candelahq.candela.settings

import com.intellij.openapi.options.Configurable
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel

class CandleSettingsConfigurable : Configurable {
    private var panel: JPanel? = null
    private var serverUrlField: JTextField? = null
    private var statusBarEnabledCheckbox: JCheckBox? = null
    private var refreshIntervalField: JSpinner? = null
    private var budgetThresholdField: JSpinner? = null

    override fun getDisplayName(): String = "Candela"

    override fun createComponent(): JComponent {
        val settings = CandleSettings.getInstance().state

        serverUrlField = JTextField(settings.serverUrl, 30)
        statusBarEnabledCheckbox = JCheckBox("Show status bar widget", settings.statusBarEnabled)
        refreshIntervalField = JSpinner(SpinnerNumberModel(settings.autoRefreshIntervalSeconds, 0, 3600, 10))
        budgetThresholdField = JSpinner(SpinnerNumberModel(settings.budgetWarningThreshold, 0, 100, 5))

        panel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = BorderFactory.createEmptyBorder(10, 10, 10, 10)

                add(labeledRow("Server URL:", serverUrlField!!))
                add(Box.createVerticalStrut(8))
                add(statusBarEnabledCheckbox)
                add(Box.createVerticalStrut(8))
                add(labeledRow("Refresh interval (seconds):", refreshIntervalField!!))
                add(Box.createVerticalStrut(8))
                add(labeledRow("Budget warning threshold (%):", budgetThresholdField!!))
            }
        return panel!!
    }

    override fun isModified(): Boolean {
        val settings = CandleSettings.getInstance().state
        return serverUrlField?.text != settings.serverUrl ||
            statusBarEnabledCheckbox?.isSelected != settings.statusBarEnabled ||
            (refreshIntervalField?.value as? Int) != settings.autoRefreshIntervalSeconds ||
            (budgetThresholdField?.value as? Int) != settings.budgetWarningThreshold
    }

    override fun apply() {
        val settings = CandleSettings.getInstance()
        settings.loadState(
            CandleSettings.State(
                serverUrl = serverUrlField?.text ?: "http://localhost:8181",
                statusBarEnabled = statusBarEnabledCheckbox?.isSelected ?: true,
                autoRefreshIntervalSeconds = (refreshIntervalField?.value as? Int) ?: 60,
                budgetWarningThreshold = (budgetThresholdField?.value as? Int) ?: 80,
            ),
        )
    }

    override fun reset() {
        val settings = CandleSettings.getInstance().state
        serverUrlField?.text = settings.serverUrl
        statusBarEnabledCheckbox?.isSelected = settings.statusBarEnabled
        refreshIntervalField?.value = settings.autoRefreshIntervalSeconds
        budgetThresholdField?.value = settings.budgetWarningThreshold
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
}
