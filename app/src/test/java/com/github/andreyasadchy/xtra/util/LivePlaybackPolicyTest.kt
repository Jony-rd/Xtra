package com.github.andreyasadchy.xtra.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePlaybackPolicyTest {

    @Test
    fun lowLatencyPolicyKeepsBufferHeadroom() {
        val policy = LivePlaybackPolicies.LOW_LATENCY

        assertEquals(1_500, policy.buffers.minBufferMs)
        assertEquals(6_000, policy.buffers.maxBufferMs)
        assertEquals(250, policy.buffers.bufferForPlaybackMs)
        assertEquals(1_000, policy.buffers.bufferForPlaybackAfterRebufferMs)
        assertEquals(2_000L, policy.targetOffsetMs)
    }

    @Test
    fun catchUpTrackerEnforcesCooldownAndSuppressesStalledOffsets() {
        val tracker = LivePriorityCatchUpTracker(cooldownMs = 15_000L, recoveryMarginMs = 2_000L)

        // Below threshold (threshold for 2_000L target is 6_000L) -> no seek
        assertFalse(tracker.requestSeek(liveOffsetMs = 5_500L, targetOffsetMs = 2_000L, realtimeMs = 1_000L))

        // First jump above threshold -> allowed
        assertTrue(tracker.requestSeek(liveOffsetMs = 10_000L, targetOffsetMs = 2_000L, realtimeMs = 1_000L))

        // Inside cooldown -> blocked
        assertFalse(tracker.requestSeek(liveOffsetMs = 10_000L, targetOffsetMs = 2_000L, realtimeMs = 10_000L))

        // After cooldown, if offset improved by < 3_000ms -> suppressed to prevent repeat loops
        assertFalse(tracker.requestSeek(liveOffsetMs = 8_500L, targetOffsetMs = 2_000L, realtimeMs = 20_000L))
        assertTrue(tracker.isSuppressed())

        // Remains suppressed while offset stays high
        assertFalse(tracker.requestSeek(liveOffsetMs = 12_000L, targetOffsetMs = 2_000L, realtimeMs = 40_000L))

        // Once live offset recovers near target (<= target + margin), suppression clears
        assertFalse(tracker.requestSeek(liveOffsetMs = 3_500L, targetOffsetMs = 2_000L, realtimeMs = 45_000L))
        assertFalse(tracker.isSuppressed())
        assertTrue(tracker.requestSeek(liveOffsetMs = 9_500L, targetOffsetMs = 2_000L, realtimeMs = 50_000L))
    }
}
