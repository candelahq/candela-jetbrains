package com.candelahq.candela

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for the shared formatting functions used by [CandleStatusBarWidget].
 */
class StatusBarFormattingTest {
    // ── formatTokenCount ──────────────────────────────────────────────────

    @Test
    fun `formatTokenCount - below 1K shows raw number`() {
        assertEquals("0", formatTokenCount(0))
        assertEquals("1", formatTokenCount(1))
        assertEquals("999", formatTokenCount(999))
    }

    @Test
    fun `formatTokenCount - 1K to 999K shows K suffix`() {
        assertEquals("1.0K", formatTokenCount(1_000))
        assertEquals("1.5K", formatTokenCount(1_500))
        assertEquals("999.9K", formatTokenCount(999_900))
    }

    @Test
    fun `formatTokenCount - 1M+ shows M suffix`() {
        assertEquals("1.0M", formatTokenCount(1_000_000))
        assertEquals("2.5M", formatTokenCount(2_500_000))
        assertEquals("100.0M", formatTokenCount(100_000_000))
    }

    // ── formatCost ────────────────────────────────────────────────────────

    @Test
    fun `formatCost - zero`() {
        assertEquals("$0.00", formatCost(0.0))
    }

    @Test
    fun `formatCost - rounds to 2 decimal places`() {
        assertEquals("$1.23", formatCost(1.234))
        assertEquals("$1.24", formatCost(1.235))
    }

    @Test
    fun `formatCost - large amounts`() {
        assertEquals("$1234.56", formatCost(1234.56))
    }
}
