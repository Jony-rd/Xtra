package com.github.andreyasadchy.xtra.ui.multiview.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiviewStallProgressTest {

    @Test
    fun reportsNoProgressWhenPlaybackAndBufferAreFrozen() {
        val baseline = MultiviewStallSnapshot(currentPositionMs = 10_000L, bufferedPositionMs = 12_000L)

        assertFalse(
            MultiviewStallProgress.hasAdvanced(
                baseline = baseline,
                current = MultiviewStallSnapshot(currentPositionMs = 10_200L, bufferedPositionMs = 12_500L),
            ),
        )
    }

    @Test
    fun reportsProgressWhenBufferOrPositionAdvances() {
        val baseline = MultiviewStallSnapshot(currentPositionMs = 10_000L, bufferedPositionMs = 12_000L)

        assertTrue(
            MultiviewStallProgress.hasAdvanced(
                baseline = baseline,
                current = MultiviewStallSnapshot(currentPositionMs = 10_000L, bufferedPositionMs = 14_000L),
            ),
        )
        assertTrue(
            MultiviewStallProgress.hasAdvanced(
                baseline = baseline,
                current = MultiviewStallSnapshot(currentPositionMs = 11_000L, bufferedPositionMs = 12_000L),
            ),
        )
    }
}
