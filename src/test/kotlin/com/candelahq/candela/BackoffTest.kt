package com.candelahq.candela

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [CandleStatusBarWidget.calculateBackoff] — exponential backoff with jitter.
 * Calls the production companion object method directly.
 */
class BackoffTest {
    @Test
    fun `first failure backoff is around 30s`() {
        val results = (1..100).map { CandleStatusBarWidget.calculateBackoff(1) }
        val avg = results.average()
        assertTrue(avg in 24_000.0..36_000.0, "Average first backoff should be ~30s, got: ${avg}ms")
    }

    @Test
    fun `second failure doubles to around 60s`() {
        val results = (1..100).map { CandleStatusBarWidget.calculateBackoff(2) }
        val avg = results.average()
        assertTrue(avg in 48_000.0..72_000.0, "Average second backoff should be ~60s, got: ${avg}ms")
    }

    @Test
    fun `third failure quadruples to around 120s`() {
        val results = (1..100).map { CandleStatusBarWidget.calculateBackoff(3) }
        val avg = results.average()
        assertTrue(avg in 96_000.0..144_000.0, "Average third backoff should be ~120s, got: ${avg}ms")
    }

    @Test
    fun `backoff caps at 5 minutes`() {
        val results = (1..100).map { CandleStatusBarWidget.calculateBackoff(20) }
        val max = results.max()
        assertTrue(max <= 360_000L, "Max backoff should not exceed 360s (5min + 20% jitter), got: ${max}ms")
    }

    @Test
    fun `jitter produces variation`() {
        val results = (1..100).map { CandleStatusBarWidget.calculateBackoff(3) }
        val distinct = results.distinct().size
        assertTrue(distinct > 10, "Jitter should produce variation, got only $distinct distinct values")
    }

    @Test
    fun `backoff never goes below minimum floor`() {
        val results = (1..1000).map { CandleStatusBarWidget.calculateBackoff(1) }
        val minResult = results.min()
        val floor = CandleStatusBarWidget.INITIAL_BACKOFF_MS / 2
        assertTrue(minResult >= floor, "Backoff should never be below ${floor}ms, got: ${minResult}ms")
    }

    @Test
    fun `zero failures treated safely`() {
        // Edge case: should not crash or produce negative values
        val result = CandleStatusBarWidget.calculateBackoff(0)
        assertTrue(result > 0, "Backoff for 0 failures should be positive, got: $result")
    }

    @Test
    fun `negative failures treated safely`() {
        val result = CandleStatusBarWidget.calculateBackoff(-1)
        assertTrue(result > 0, "Backoff for negative failures should be positive, got: $result")
    }
}
