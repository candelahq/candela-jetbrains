package com.candelahq.candela.chat

import com.candelahq.candela.CandelaCoroutineService
import com.candelahq.candela.client.ChatClient
import com.candelahq.candela.client.ChunkUsage
import com.candelahq.candela.client.StreamEvent
import com.candelahq.candela.client.estimatedCostUsd
import com.candelahq.candela.client.formatCostUsd
import com.candelahq.candela.settings.CandleSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicLong
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.text.html.HTMLEditorKit

/**
 * Main chat UI panel for the Candela Chat tool window.
 *
 * Layout:
 * - NORTH: toolbar with model selector, refresh, new chat
 * - CENTER: scrollable message area
 * - SOUTH: input area with send/stop button
 *
 * Uses a [CoroutineScope] tied to the panel lifecycle for all async work.
 * Implements [Disposable] to cancel the scope when the tool window is closed.
 */
class ChatPanel(
    private val project: Project,
) : JPanel(BorderLayout()),
    Disposable {
    private val chatClient = ChatClient()
    private val session = ChatSession()

    /**
     * Coroutine scope — child of the project-level scope, cancelled in [dispose].
     * Inherits full parent context (modality, tracing) and adds a [SupervisorJob]
     * so individual operation failures don't cancel the scope.
     */
    private val scope =
        project.service<CandelaCoroutineService>().scope.let { parentScope ->
            CoroutineScope(
                parentScope.coroutineContext +
                    SupervisorJob(parentScope.coroutineContext[Job]) +
                    Dispatchers.Main.immediate,
            )
        }

    // ── UI Components ────────────────────────────────────────────────────

    private val modelSelector = JComboBox<String>()
    private val messagesPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = JBColor.PanelBackground
        }
    private val messagesScroll =
        JBScrollPane(messagesPanel).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
    private val inputArea =
        JBTextArea(3, 40).apply {
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(8)
            font = Font("JetBrains Mono", Font.PLAIN, 13)
            background = MarkdownRenderer.inputAreaBg()
        }
    private val sendButton =
        JButton("Send").apply {
            isFocusPainted = false
        }

    // Track the currently streaming assistant text pane for incremental updates
    private var streamingTextPane: JTextPane? = null

    // Thread-safe buffer for accumulating streaming tokens.
    // Written on IO dispatcher, read on EDT — StringBuffer is synchronized.
    @Volatile
    private var streamingContent = StringBuffer()

    // Throttle UI updates during streaming to avoid O(n²) re-rendering.
    private val lastUiUpdateMs = AtomicLong(0L)

    /**
     * Monotonically-increasing stream generation ID.
     * Prevents stale [SwingUtilities.invokeLater] callbacks from a cancelled
     * stream from writing into the current stream's UI.
     */
    private val streamGeneration = AtomicLong(0L)

    @Volatile
    private var disposed = false

    /** Inline "Thinking…" label shown in the chat while waiting for first token. */
    private var thinkingLabel: JPanel? = null

    init {
        buildUI()
        loadModels()
    }

    // ── Public API (used by editor actions) ──────────────────────────────

    /**
     * Send a message to the chat (used by editor context actions).
     * If a stream is in progress, it will be cancelled first.
     */
    fun sendMessage(text: String) {
        if (session.isStreaming) {
            streamGeneration.incrementAndGet()
            session.cancelStreaming()
        }
        inputArea.text = text
        doSend()
    }

    // ── Disposable ──────────────────────────────────────────────────────

    override fun dispose() {
        disposed = true
        scope.cancel("ChatPanel disposed")
        session.cancelStreaming()
        streamingTextPane = null
        ChatPanelService.getInstance(project).panel = null
        log.info("ChatPanel disposed for project: ${project.name}")
    }

    // ── UI Construction ──────────────────────────────────────────────────

    private fun buildUI() {
        // Toolbar (NORTH)
        val toolbar =
            JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
                background = MarkdownRenderer.toolbarBg()
                border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
            }

        modelSelector.preferredSize = Dimension(220, 28)
        modelSelector.toolTipText = "Select LLM model"
        toolbar.add(JLabel("Model:"))
        toolbar.add(modelSelector)

        val refreshBtn =
            JButton("↻").apply {
                toolTipText = "Refresh models"
                isFocusPainted = false
                addActionListener { loadModels() }
            }
        toolbar.add(refreshBtn)

        val newChatBtn =
            JButton("New Chat").apply {
                toolTipText = "Clear conversation"
                isFocusPainted = false
                addActionListener { clearChat() }
            }
        toolbar.add(newChatBtn)

        add(toolbar, BorderLayout.NORTH)

        // Messages area (CENTER)
        add(messagesScroll, BorderLayout.CENTER)

        // Input area (SOUTH)
        val inputPanel =
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0)
            }

        inputArea.addKeyListener(
            object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                        e.consume()
                        doSend()
                    }
                }
            },
        )

        sendButton.addActionListener { doSend() }

        val inputScroll =
            JBScrollPane(inputArea).apply {
                border = JBUI.Borders.empty()
                preferredSize = Dimension(0, 72)
            }
        inputPanel.add(inputScroll, BorderLayout.CENTER)
        inputPanel.add(sendButton, BorderLayout.EAST)

        add(inputPanel, BorderLayout.SOUTH)

        // Welcome message
        addInfoMessage("Welcome to Candela Chat. Select a model and start chatting.")
    }

    // ── Actions ──────────────────────────────────────────────────────────

    private fun doSend() {
        if (session.isStreaming) {
            // Button is in "Stop" mode — cancel the streaming coroutine
            streamGeneration.incrementAndGet()
            session.cancelStreaming()
            sendButton.text = "Send"
            return
        }

        val text = inputArea.text.trim()
        if (text.isEmpty()) return

        val model = modelSelector.selectedItem as? String ?: ""
        if (model.isEmpty() || model.startsWith("(")) {
            addErrorMessage("No valid model selected. Please check your connection and refresh.")
            return
        }

        inputArea.text = ""
        session.addUserMessage(text)
        addUserBubble(text)

        // Prepare streaming
        streamingContent = StringBuffer()
        lastUiUpdateMs.set(0L)
        val thisGeneration = streamGeneration.incrementAndGet()
        sendButton.text = "Stop"

        val assistantPane = addAssistantBubble("")
        streamingTextPane = assistantPane

        // Show inline "Thinking…" indicator until first token arrives
        thinkingLabel = addThinkingIndicator()
        val firstTokenReceived =
            java.util.concurrent.atomic
                .AtomicBoolean(false)

        val settings = CandleSettings.getInstance().state
        val baseUrl = settings.chatServerUrl
        val messages = session.buildRequestMessages()
        val maxTokens = settings.maxTokens

        // Save model selection
        settings.defaultModel = model

        log.info("Sending chat request: model=$model, messages=${messages.size}")

        // Launch streaming coroutine — Job stored in session for cancellation
        session.streamingJob =
            scope.launch {
                try {
                    chatClient
                        .streamChat(
                            baseUrl = baseUrl,
                            model = model,
                            messages = messages,
                            maxTokens = maxTokens,
                        ).collect { event ->
                            when (event) {
                                is StreamEvent.Token -> {
                                    streamingContent.append(event.content)
                                    // Remove thinking indicator on first token
                                    if (firstTokenReceived.compareAndSet(false, true)) {
                                        SwingUtilities.invokeLater {
                                            removeThinkingIndicator()
                                        }
                                    }
                                    // Adaptive throttle: increase interval as content grows
                                    val contentLength = streamingContent.length
                                    val throttleMs = adaptiveThrottleMs(contentLength)
                                    val now = System.currentTimeMillis()
                                    if (now - lastUiUpdateMs.get() >= throttleMs) {
                                        lastUiUpdateMs.set(now)
                                        val snapshot = streamingContent.toString()
                                        SwingUtilities.invokeLater {
                                            if (!disposed && streamGeneration.get() == thisGeneration) {
                                                updateStreamingBubble(snapshot)
                                            }
                                        }
                                    }
                                }

                                is StreamEvent.Complete -> {
                                    val finalContent = streamingContent.toString()
                                    session.addAssistantMessage(finalContent)
                                    SwingUtilities.invokeLater {
                                        if (!disposed && streamGeneration.get() == thisGeneration) {
                                            removeThinkingIndicator()
                                            updateStreamingBubble(finalContent)
                                            addCodeBlockActions(finalContent)
                                            if (event.usage != null) {
                                                addTokenInfo(event.usage, model)
                                            }
                                            sendButton.text = "Send"
                                            streamingTextPane = null
                                        }
                                    }
                                }

                                is StreamEvent.Error -> {
                                    log.warn("Chat stream error", event.exception)
                                    SwingUtilities.invokeLater {
                                        if (!disposed && streamGeneration.get() == thisGeneration) {
                                            removeThinkingIndicator()
                                            addErrorMessage("Error: ${event.exception.message ?: "Unknown error"}")
                                            sendButton.text = "Send"
                                            streamingTextPane = null
                                        }
                                    }
                                }
                            }
                        }
                } catch (_: CancellationException) {
                    // Stream was cancelled by user — update UI
                    SwingUtilities.invokeLater {
                        if (!disposed && streamGeneration.get() == thisGeneration) {
                            removeThinkingIndicator()
                            val partial = streamingContent.toString()
                            if (partial.isNotEmpty()) {
                                updateStreamingBubble(partial)
                                session.addAssistantMessage(partial)
                            }
                            sendButton.text = "Send"
                            streamingTextPane = null
                        }
                    }
                }
            }
    }

    private fun clearChat() {
        streamGeneration.incrementAndGet()
        session.cancelStreaming()
        session.clear()
        messagesPanel.removeAll()
        messagesPanel.revalidate()
        messagesPanel.repaint()
        sendButton.text = "Send"
        streamingTextPane = null
        addInfoMessage("Conversation cleared. Start a new chat.")
    }

    private fun loadModels() {
        modelSelector.removeAllItems()
        modelSelector.addItem("(loading…)")
        scope.launch {
            try {
                withBackgroundProgress(project, "Loading models…") {
                    val settings = CandleSettings.getInstance().state
                    val models = chatClient.fetchModels(settings.chatServerUrl)
                    if (disposed) return@withBackgroundProgress
                    modelSelector.removeAllItems()
                    for (model in models) {
                        modelSelector.addItem(model.id)
                    }
                    val defaultModel = settings.defaultModel
                    if (defaultModel.isNotEmpty()) {
                        for (i in 0 until modelSelector.itemCount) {
                            if (modelSelector.getItemAt(i) == defaultModel) {
                                modelSelector.selectedIndex = i
                                break
                            }
                        }
                    }
                    if (models.isEmpty()) {
                        modelSelector.addItem("(no models — check connection)")
                    }
                }
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (ex: Exception) {
                log.warn("Failed to load models", ex)
                if (!disposed) {
                    modelSelector.removeAllItems()
                    modelSelector.addItem("(no models — check connection)")
                }
            }
        }
    }

    // ── Thinking Indicator ────────────────────────────────────────────────

    /** Add an animated "Thinking…" label to the chat panel. */
    private fun addThinkingIndicator(): JPanel {
        val label =
            JLabel("⏳ Thinking…").apply {
                font = font.deriveFont(Font.ITALIC, 12f)
                foreground = JBColor.GRAY
            }
        val wrapper =
            JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                border = JBUI.Borders.empty(4, 8, 4, 40)
                add(label)
            }
        messagesPanel.add(wrapper)
        messagesPanel.revalidate()
        scrollToBottom()
        return wrapper
    }

    /** Remove the thinking indicator if it's still showing. */
    private fun removeThinkingIndicator() {
        thinkingLabel?.let {
            messagesPanel.remove(it)
            messagesPanel.revalidate()
            messagesPanel.repaint()
        }
        thinkingLabel = null
    }

    // ── Bubble Rendering ─────────────────────────────────────────────────

    private fun addUserBubble(text: String) {
        val bubble =
            createBubble(
                text = escapeBasicHtml(text).replace("\n", "<br>"),
                isUser = true,
            )
        val wrapper =
            JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                isOpaque = false
                border = JBUI.Borders.empty(4, 40, 4, 8)
                add(bubble)
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            }
        wrapper.alignmentX = Component.RIGHT_ALIGNMENT
        messagesPanel.add(wrapper)
        scrollToBottom()
    }

    private fun addAssistantBubble(markdown: String): JTextPane {
        val textPane =
            JTextPane().apply {
                editorKit = HTMLEditorKit()
                isEditable = false
                isOpaque = false
                border = JBUI.Borders.empty(4)
                val html =
                    MarkdownRenderer.wrapInHtmlDocument(
                        MarkdownRenderer.renderToHtml(markdown),
                        isUser = false,
                    )
                text = html
            }

        val bubblePanel =
            JPanel(BorderLayout()).apply {
                background = MarkdownRenderer.assistantBubbleBg()
                border =
                    BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(JBColor.border(), 1, true),
                        JBUI.Borders.empty(6, 10),
                    )
                add(textPane, BorderLayout.CENTER)
            }

        val wrapper =
            JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                border = JBUI.Borders.empty(4, 8, 4, 40)
                add(bubblePanel)
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            }
        wrapper.alignmentX = Component.LEFT_ALIGNMENT
        messagesPanel.add(wrapper)
        scrollToBottom()

        return textPane
    }

    private fun updateStreamingBubble(markdown: String) {
        streamingTextPane?.let { pane ->
            val html =
                MarkdownRenderer.wrapInHtmlDocument(
                    MarkdownRenderer.renderToHtml(markdown),
                    isUser = false,
                )
            pane.text = html
            // Force re-layout
            pane.parent?.let { bubble ->
                bubble.parent?.let { wrapper ->
                    wrapper.maximumSize = Dimension(Int.MAX_VALUE, wrapper.preferredSize.height)
                }
            }
            messagesPanel.revalidate()
            scrollToBottom()
        }
    }

    private fun addCodeBlockActions(markdown: String) {
        val codeBlocks = MarkdownRenderer.extractCodeBlocks(markdown)
        if (codeBlocks.isEmpty()) return

        val actionsPanel =
            JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false
                border = JBUI.Borders.empty(0, 8, 4, 8)
            }

        for ((index, code) in codeBlocks.withIndex()) {
            val label = if (codeBlocks.size > 1) "Block ${index + 1}" else "Code"
            val copyBtn =
                JButton("📋 Copy $label").apply {
                    isFocusPainted = false
                    font = font.deriveFont(11f)
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    addActionListener {
                        CopyPasteManager.getInstance().setContents(StringSelection(code))
                    }
                }
            val insertBtn =
                JButton("▶ Insert $label").apply {
                    isFocusPainted = false
                    font = font.deriveFont(11f)
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    addActionListener {
                        insertAtCursor(code)
                    }
                }
            actionsPanel.add(copyBtn)
            actionsPanel.add(insertBtn)
        }

        actionsPanel.maximumSize = Dimension(Int.MAX_VALUE, actionsPanel.preferredSize.height)
        messagesPanel.add(actionsPanel)
        messagesPanel.revalidate()
    }

    private fun addTokenInfo(
        usage: ChunkUsage,
        model: String,
    ) {
        val cost = usage.estimatedCostUsd(model)
        val costSuffix = if (cost != null && cost > 0.0) " · ~${formatCostUsd(cost)}" else ""
        val info = "\u26A1 ${usage.totalTokens} tokens (${usage.promptTokens} in / ${usage.completionTokens} out)$costSuffix"
        val label =
            JLabel(info).apply {
                font = font.deriveFont(10f)
                foreground = JBColor.GRAY
                border = JBUI.Borders.empty(0, 12, 6, 0)
            }
        val wrapper =
            JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                add(label)
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            }
        messagesPanel.add(wrapper)
        messagesPanel.revalidate()
    }

    private fun addInfoMessage(text: String) {
        val label =
            JLabel(text).apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(Font.ITALIC, 12f)
                border = JBUI.Borders.empty(12)
                horizontalAlignment = SwingConstants.CENTER
            }
        val wrapper =
            JPanel(BorderLayout()).apply {
                isOpaque = false
                add(label, BorderLayout.CENTER)
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            }
        messagesPanel.add(wrapper)
        messagesPanel.revalidate()
    }

    private fun addErrorMessage(text: String) {
        val label =
            JLabel(text).apply {
                foreground = JBColor.RED
                font = font.deriveFont(12f)
                border = JBUI.Borders.empty(6, 12)
            }
        val wrapper =
            JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                add(label)
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            }
        messagesPanel.add(wrapper)
        messagesPanel.revalidate()
        scrollToBottom()
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun createBubble(
        text: String,
        isUser: Boolean,
    ): JPanel {
        val textPane =
            JTextPane().apply {
                editorKit = HTMLEditorKit()
                isEditable = false
                isOpaque = false
                border = JBUI.Borders.empty(4)
                val html = MarkdownRenderer.wrapInHtmlDocument(text, isUser = isUser)
                this.text = html
            }

        return JPanel(BorderLayout()).apply {
            background = if (isUser) MarkdownRenderer.userBubbleBg() else MarkdownRenderer.assistantBubbleBg()
            border =
                BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBColor.border(), 1, true),
                    JBUI.Borders.empty(6, 10),
                )
            add(textPane, BorderLayout.CENTER)
        }
    }

    private fun scrollToBottom() {
        SwingUtilities.invokeLater {
            val scrollBar = messagesScroll.verticalScrollBar
            scrollBar.value = scrollBar.maximum
        }
    }

    private fun insertAtCursor(code: String) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        WriteCommandAction.runWriteCommandAction(project, "Insert from Candela Chat", null, {
            val offset = editor.caretModel.offset
            editor.document.insertString(offset, code)
            editor.caretModel.moveToOffset(offset + code.length)
        })
    }

    private fun escapeBasicHtml(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    companion object {
        private val log = Logger.getInstance(ChatPanel::class.java)

        /** Minimum interval between streaming UI updates (milliseconds). */
        private const val STREAM_THROTTLE_MIN_MS = 80L
        private const val STREAM_THROTTLE_MID_MS = 150L
        private const val STREAM_THROTTLE_MAX_MS = 250L
        private const val CONTENT_MID_THRESHOLD = 5_000
        private const val CONTENT_MAX_THRESHOLD = 10_000

        /**
         * Adaptive throttle: short responses render quickly, long responses
         * increase the interval to prevent UI lag from markdown re-rendering.
         */
        internal fun adaptiveThrottleMs(contentLength: Int): Long =
            when {
                contentLength > CONTENT_MAX_THRESHOLD -> STREAM_THROTTLE_MAX_MS
                contentLength > CONTENT_MID_THRESHOLD -> STREAM_THROTTLE_MID_MS
                else -> STREAM_THROTTLE_MIN_MS
            }
    }
}
