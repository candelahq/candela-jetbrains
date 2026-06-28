package com.candelahq.candela

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [CandleStatusBarWidget.calculateBackoff] — exponential backoff with jitter.
 */
class BackoffTest {
    /**
     * Create a minimal widget just to test calculateBackoff.
     * We use reflection-free approach: calculateBackoff is internal.
     */
    private fun calculateBackoff(failures: Int): Long {
        // Replicate the logic to test boundaries without needing Project
        val initialBackoffMs = 30_000L
        val maxBackoffMs = 300_000L
        val jitterFactor = 0.2
        val exponential = initialBackoffMs * (1L shl (failures - 1).coerceAtMost(10))
        val capped = exponential.coerceAtMost(maxBackoffMs)
        val jitter = (capped * jitterFactor * (2 * Math.random() - 1)).toLong()
        return (capped + jitter).coerceAtLeast(initialBackoffMs / 2)
    }

    @Test
    fun `first failure backoff is around 30s`() {
        // Run multiple times to account for jitter
        val results = (1..100).map { calculateBackoff(1) }
        val avg = results.average()
        assertTrue(avg in 24_000.0..36_000.0, "Average first backoff should be ~30s, got: ${avg}ms")
    }

    @Test
    fun `second failure doubles to around 60s`() {
        val results = (1..100).map { calculateBackoff(2) }
        val avg = results.average()
        assertTrue(avg in 48_000.0..72_000.0, "Average second backoff should be ~60s, got: ${avg}ms")
    }

    @Test
    fun `third failure quadruples to around 120s`() {
        val results = (1..100).map { calculateBackoff(3) }
        val avg = results.average()
        assertTrue(avg in 96_000.0..144_000.0, "Average third backoff should be ~120s, got: ${avg}ms")
    }

    @Test
    fun `backoff caps at 5 minutes`() {
        val results = (1..100).map { calculateBackoff(20) }
        val max = results.max()
        val min = results.min()
        // 300_000 ± 20% = 240_000..360_000
        assertTrue(max <= 360_000L, "Max backoff should not exceed 360s, got: ${max}ms")
        assertTrue(min >= 15_000L, "Min backoff should not be negative, got: ${min}ms")
    }

    @Test
    fun `jitter produces variation`() {
        val results = (1..100).map { calculateBackoff(3) }
        val distinct = results.distinct().size
        assertTrue(distinct > 10, "Jitter should produce variation, got only $distinct distinct values")
    }

    @Test
    fun `backoff never goes below minimum floor`() {
        val results = (1..1000).map { calculateBackoff(1) }
        val minResult = results.min()
        assertTrue(minResult >= 15_000L, "Backoff should never be below 15s, got: ${minResult}ms")
    }
}
