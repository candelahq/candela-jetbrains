package com.candelahq.candela.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests for [ChatPanel.adaptiveThrottleMs] — adaptive streaming throttle.
 */
class AdaptiveThrottleTest {
    @Test
    fun `short content uses minimum throttle`() {
        assertEquals(80L, ChatPanel.adaptiveThrottleMs(0))
        assertEquals(80L, ChatPanel.adaptiveThrottleMs(100))
        assertEquals(80L, ChatPanel.adaptiveThrottleMs(4_999))
    }

    @Test
    fun `medium content uses mid throttle`() {
        assertEquals(150L, ChatPanel.adaptiveThrottleMs(5_001))
        assertEquals(150L, ChatPanel.adaptiveThrottleMs(8_000))
        assertEquals(150L, ChatPanel.adaptiveThrottleMs(9_999))
    }

    @Test
    fun `large content uses max throttle`() {
        assertEquals(250L, ChatPanel.adaptiveThrottleMs(10_001))
        assertEquals(250L, ChatPanel.adaptiveThrottleMs(50_000))
        assertEquals(250L, ChatPanel.adaptiveThrottleMs(100_000))
    }

    @Test
    fun `exact boundary at 5000 uses minimum`() {
        assertEquals(80L, ChatPanel.adaptiveThrottleMs(5_000))
    }

    @Test
    fun `exact boundary at 10000 uses mid`() {
        assertEquals(150L, ChatPanel.adaptiveThrottleMs(10_000))
    }
}
