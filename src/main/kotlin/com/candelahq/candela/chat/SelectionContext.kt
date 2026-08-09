package com.candelahq.candela.chat

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker

/**
 * Tracks the editor selection that triggered a chat context action.
 *
 * Uses IntelliJ's [RangeMarker] to automatically adjust offsets as the
 * document is edited between action trigger and replace click.
 * This avoids the classic "stale offset" bug where static offsets shift
 * when the user types above the selection while waiting for the LLM response.
 */
data class SelectionContext(
    /** The document containing the selection. */
    val document: Document,
    /** Range marker that tracks the selection bounds across edits. */
    val marker: RangeMarker,
)
