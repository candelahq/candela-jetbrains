package com.candelahq.candela.chat

import com.intellij.openapi.diagnostic.Logger

/**
 * Structured telemetry for chat code block actions.
 *
 * Logs events that can be forwarded to the Candela proxy's OTel pipeline
 * once a telemetry ingestion endpoint is available. Each event captures
 * enough context to answer: "Which models produce code that developers
 * actually keep?"
 *
 * Events:
 * - `code_block.copy` — user copied a code block
 * - `code_block.insert` — user inserted a code block at cursor
 * - `code_block.replace` — user replaced their selection with a code block
 * - `code_block.replace_fallback` — replace fell back to insert (marker invalid)
 */
object ChatTelemetry {
    private val log = Logger.getInstance(ChatTelemetry::class.java)

    fun logCopy(codeLength: Int) {
        log.info("candela.chat.code_block.copy | code_length=$codeLength")
    }

    fun logInsert(codeLength: Int) {
        log.info("candela.chat.code_block.insert | code_length=$codeLength")
    }

    fun logReplace(
        originalLength: Int,
        replacementLength: Int,
    ) {
        log.info(
            "candela.chat.code_block.replace" +
                " | original_length=$originalLength" +
                " | replacement_length=$replacementLength" +
                " | delta=${replacementLength - originalLength}",
        )
    }

    fun logReplaceFallback(reason: String) {
        log.info("candela.chat.code_block.replace_fallback | reason=$reason")
    }
}
