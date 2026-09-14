package com.github.andreyasadchy.xtra.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveUpdateLogicTest {
    @Test
    fun predictionPercentagesUseAuthoritativePoints() {
        val outcomes = listOf(
            PredictionOutcomeSnapshot("a", "A", 75, 3),
            PredictionOutcomeSnapshot("b", "B", 25, 1),
        )
        assertEquals(listOf(75, 25), LiveUpdateLogic.outcomePercentages(outcomes))
    }

    @Test
    fun finalPredictionStatusesAreNotOngoing() {
        assertTrue(LiveUpdateLogic.predictionIsFinal("RESOLVED"))
        assertTrue(LiveUpdateLogic.predictionIsFinal("CANCELED"))
        assertFalse(LiveUpdateLogic.predictionIsFinal("LOCKED"))
    }

    @Test
    fun dropsNeverExceedOneHundredPercent() {
        assertEquals(0, LiveUpdateLogic.dropPercent(0, 20))
        assertEquals(50, LiveUpdateLogic.dropPercent(10, 20))
        assertEquals(100, LiveUpdateLogic.dropPercent(30, 20))
    }

    @Test
    fun remainingTimeUsesCompactMinutesAndClockFormat() {
        assertEquals("1:42", LiveUpdateLogic.formatRemaining(102))
        assertEquals("8m", LiveUpdateLogic.formatRemaining(8 * 60L))
        assertEquals(null, LiveUpdateLogic.formatRemaining(null))
    }

    @Test
    fun remainingDropTimeUsesAuthoritativeValues() {
        assertEquals(18, LiveUpdateLogic.dropRemainingMinutes(18, 36))
        assertEquals(0, LiveUpdateLogic.dropRemainingMinutes(36, 18))
    }

    @Test
    fun promotionRequiresAndroidAndPermission() {
        assertFalse(LiveUpdateLogic.promotedOngoingAllowed(35, true, true))
        assertFalse(LiveUpdateLogic.promotedOngoingAllowed(36, true, false))
        assertFalse(LiveUpdateLogic.promotedOngoingAllowed(36, true, true, systemPromotionAllowed = false))
        assertTrue(LiveUpdateLogic.promotedOngoingAllowed(36, true, true))
    }

    @Test
    fun pausedOrClaimedDropsAreNotActive() {
        val paused = DropsLiveUpdateSnapshot("drop", "campaign", "Campaign", "Game", "Reward", 4, 10, false, LiveUpdateLogic.DROP_PAUSED, updatedAtMs = 1)
        val claimed = paused.copy(claimed = true, state = LiveUpdateLogic.DROP_ACTIVE)
        val expired = paused.copy(state = LiveUpdateLogic.DROP_ACTIVE, expiresAtMs = 100)
        assertFalse(LiveUpdateLogic.dropIsActive(paused))
        assertFalse(LiveUpdateLogic.dropIsActive(claimed))
        assertFalse(LiveUpdateLogic.dropIsActive(expired, nowMs = 101))
    }

    @Test
    fun chatBubbleTagsAreStableAndFeatureScoped() {
        assertEquals("xtra_chat_bubble:123", LiveUpdateLogic.chatBubbleTag("123"))
    }
}
