package com.candelahq.candela.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [estimatedCostUsd] and [formatCostUsd].
 */
class CostEstimationTest {
    @Test
    fun `known model returns estimated cost`() {
        val usage = ChunkUsage(promptTokens = 1000, completionTokens = 500, totalTokens = 1500)
        val cost = usage.estimatedCostUsd("gpt-4o")
        assertNotNull(cost)
        // gpt-4o: $2.50/M in, $10.00/M out
        // (1000 * 2.50 + 500 * 10.00) / 1_000_000 = (2500 + 5000) / 1_000_000 = 0.0075
        assertEquals(0.0075, cost!!, 0.0001)
    }

    @Test
    fun `unknown model returns null`() {
        val usage = ChunkUsage(promptTokens = 100, completionTokens = 50, totalTokens = 150)
        assertNull(usage.estimatedCostUsd("some-custom-local-model"))
    }

    @Test
    fun `model matching is case insensitive`() {
        val usage = ChunkUsage(promptTokens = 1000, completionTokens = 500, totalTokens = 1500)
        val cost = usage.estimatedCostUsd("GPT-4o-MINI")
        assertNotNull(cost)
    }

    @Test
    fun `model matching uses substring containment`() {
        val usage = ChunkUsage(promptTokens = 1000, completionTokens = 1000, totalTokens = 2000)
        // "my-org/gpt-4o-mini" should match "gpt-4o-mini"
        val cost = usage.estimatedCostUsd("my-org/gpt-4o-mini")
        assertNotNull(cost)
    }

    @Test
    fun `gpt-4o-mini matches before gpt-4o`() {
        val usage = ChunkUsage(promptTokens = 1000, completionTokens = 1000, totalTokens = 2000)
        val miniCost = usage.estimatedCostUsd("gpt-4o-mini")
        val fullCost = usage.estimatedCostUsd("gpt-4o")
        assertNotNull(miniCost)
        assertNotNull(fullCost)
        // Mini should be cheaper
        assertTrue(miniCost!! < fullCost!!, "gpt-4o-mini ($miniCost) should be cheaper than gpt-4o ($fullCost)")
    }

    @Test
    fun `zero tokens returns zero cost`() {
        val usage = ChunkUsage(promptTokens = 0, completionTokens = 0, totalTokens = 0)
        val cost = usage.estimatedCostUsd("gpt-4o")
        assertNotNull(cost)
        assertEquals(0.0, cost!!, 0.0001)
    }

    @Test
    fun `local model (llama) returns zero cost`() {
        val usage = ChunkUsage(promptTokens = 5000, completionTokens = 2000, totalTokens = 7000)
        val cost = usage.estimatedCostUsd("llama-3.3-70b")
        assertNotNull(cost)
        assertEquals(0.0, cost!!, 0.0001)
    }

    @Test
    fun `claude-3-opus pricing is correct`() {
        val usage = ChunkUsage(promptTokens = 2000, completionTokens = 1000, totalTokens = 3000)
        val cost = usage.estimatedCostUsd("claude-3-opus-20240229")
        assertNotNull(cost)
        // $15/M in, $75/M out → (2000*15 + 1000*75) / 1M = (30000 + 75000) / 1M = 0.105
        assertEquals(0.105, cost!!, 0.0001)
    }

    @Test
    fun `gemini-2_5-flash pricing is correct`() {
        val usage = ChunkUsage(promptTokens = 10000, completionTokens = 5000, totalTokens = 15000)
        val cost = usage.estimatedCostUsd("gemini-2.5-flash")
        assertNotNull(cost)
        // $0.15/M in, $0.60/M out → (10000*0.15 + 5000*0.60) / 1M = (1500 + 3000) / 1M = 0.0045
        assertEquals(0.0045, cost!!, 0.0001)
    }

    // ── formatCostUsd ────────────────────────────────────────────────────

    @Test
    fun `format large cost shows two decimals`() {
        assertEquals("\$0.03", formatCostUsd(0.03))
        assertEquals("\$1.50", formatCostUsd(1.50))
    }

    @Test
    fun `format small cost shows three decimals`() {
        assertEquals("\$0.008", formatCostUsd(0.008))
        assertEquals("\$0.003", formatCostUsd(0.003))
    }

    @Test
    fun `format very small cost shows less-than`() {
        assertEquals("<\$0.001", formatCostUsd(0.0005))
        assertEquals("<\$0.001", formatCostUsd(0.0))
    }

    // ── Locale safety ────────────────────────────────────────────────────

    @Test
    fun `formatCostUsd uses dot separator regardless of default locale`() {
        // The Locale.US fix ensures we always get "." not ","
        val formatted = formatCostUsd(0.0075)
        assertTrue(formatted.contains("."), "Should use dot separator, got: $formatted")
        assertFalse(formatted.contains(","), "Should not use comma separator, got: $formatted")
    }

    // ── Additional model families ────────────────────────────────────────

    @Test
    fun `deepseek-r1 pricing is correct`() {
        val usage = ChunkUsage(promptTokens = 5000, completionTokens = 3000, totalTokens = 8000)
        val cost = usage.estimatedCostUsd("deepseek-r1")
        assertNotNull(cost)
        // $0.55/M in, $2.19/M out → (5000*0.55 + 3000*2.19) / 1M = (2750 + 6570) / 1M = 0.00932
        assertEquals(0.00932, cost!!, 0.0001)
    }

    @Test
    fun `mistral-small pricing is correct`() {
        val usage = ChunkUsage(promptTokens = 2000, completionTokens = 1000, totalTokens = 3000)
        val cost = usage.estimatedCostUsd("mistral-small-latest")
        assertNotNull(cost)
        // $0.20/M in, $0.60/M out → (2000*0.20 + 1000*0.60) / 1M = (400 + 600) / 1M = 0.001
        assertEquals(0.001, cost!!, 0.0001)
    }

    // ── Specificity ordering ─────────────────────────────────────────────

    @Test
    fun `claude-3-haiku matches its own pricing not claude-3`() {
        val usage = ChunkUsage(promptTokens = 1000, completionTokens = 1000, totalTokens = 2000)
        val haikuCost = usage.estimatedCostUsd("claude-3-haiku-20240307")
        val sonnetCost = usage.estimatedCostUsd("claude-3-sonnet-20240229")
        assertNotNull(haikuCost)
        assertNotNull(sonnetCost)
        assertTrue(haikuCost!! < sonnetCost!!, "Haiku should be cheaper than Sonnet")
    }

    @Test
    fun `mistral matches generic mistral pricing`() {
        val usage = ChunkUsage(promptTokens = 1000, completionTokens = 1000, totalTokens = 2000)
        val cost = usage.estimatedCostUsd("mistral-7b-instruct")
        assertNotNull(cost, "Should match generic 'mistral' pattern")
    }

    // ── Edge cases ───────────────────────────────────────────────────────

    @Test
    fun `empty model string returns null`() {
        val usage = ChunkUsage(promptTokens = 100, completionTokens = 50, totalTokens = 150)
        assertNull(usage.estimatedCostUsd(""))
    }

    @Test
    fun `input-only tokens priced correctly`() {
        val usage = ChunkUsage(promptTokens = 1_000_000, completionTokens = 0, totalTokens = 1_000_000)
        val cost = usage.estimatedCostUsd("gpt-4o")
        assertNotNull(cost)
        // 1M tokens at $2.50/M in, 0 out = $2.50
        assertEquals(2.50, cost!!, 0.01)
    }

    @Test
    fun `output-only tokens priced correctly`() {
        val usage = ChunkUsage(promptTokens = 0, completionTokens = 1_000_000, totalTokens = 1_000_000)
        val cost = usage.estimatedCostUsd("gpt-4o")
        assertNotNull(cost)
        // 0 in, 1M tokens at $10.00/M out = $10.00
        assertEquals(10.00, cost!!, 0.01)
    }

    // ── Format boundaries ────────────────────────────────────────────────

    @Test
    fun `format exactly at 0_001 boundary shows three decimals`() {
        assertEquals("\$0.001", formatCostUsd(0.001))
    }

    @Test
    fun `format exactly at 0_01 boundary shows two decimals`() {
        assertEquals("\$0.01", formatCostUsd(0.01))
    }
}
