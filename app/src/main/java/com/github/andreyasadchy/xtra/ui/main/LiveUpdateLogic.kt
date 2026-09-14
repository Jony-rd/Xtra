package com.github.andreyasadchy.xtra.ui.main

import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

@Serializable
data class PredictionOutcomeSnapshot(
    val id: String,
    val title: String,
    val totalPoints: Int,
    val totalUsers: Int,
)

@Serializable
data class PredictionLiveUpdateSnapshot(
    val predictionId: String,
    val title: String,
    val channelId: String,
    val channelLogin: String,
    val channelName: String,
    val status: String,
    val outcomes: List<PredictionOutcomeSnapshot>,
    val winningOutcomeId: String? = null,
    val myOutcomeId: String? = null,
    val myAmount: Int? = null,
    val locksAtMs: Long? = null,
    val updatedAtMs: Long,
    val streamId: String? = null,
)

@Serializable
data class DropsLiveUpdateSnapshot(
    val dropId: String,
    val campaignId: String,
    val campaignName: String,
    val gameName: String,
    val rewardName: String,
    val currentMinutes: Int,
    val requiredMinutes: Int,
    val claimed: Boolean,
    val state: String = "active",
    val expiresAtMs: Long? = null,
    val imageUrl: String? = null,
    val updatedAtMs: Long,
)

object LiveUpdateLogic {
    const val PREDICTION_TAG = "xtra_prediction_live_update"
    const val PREDICTION_RESULT_TAG = "xtra_prediction_result"
    const val DROPS_TAG = "xtra_drops_live_update"
    const val DROPS_RESULT_TAG = "xtra_drops_result"
    const val CHAT_BUBBLE_TAG_PREFIX = "xtra_chat_bubble:"
    const val DROP_ACTIVE = "active"
    const val DROP_PAUSED = "paused"
    const val DROP_COMPLETED = "completed"
    const val DROP_EXPIRED = "expired"

    fun remainingSeconds(endAtMs: Long?, nowMs: Long): Long? = endAtMs
        ?.minus(nowMs)
        ?.coerceAtLeast(0L)
        ?.div(1000L)

    fun formatRemaining(seconds: Long?): String? {
        if (seconds == null) return null
        if (seconds >= 120) return "${((seconds + 59) / 60)}m"
        val minutes = seconds / 60
        val remainder = seconds % 60
        return if (minutes > 0) "$minutes:${remainder.toString().padStart(2, '0')}" else "0:${remainder.toString().padStart(2, '0')}"
    }

    fun outcomePercentages(outcomes: List<PredictionOutcomeSnapshot>): List<Int> {
        val total = outcomes.sumOf { it.totalPoints }.coerceAtLeast(0)
        if (total == 0) return outcomes.map { 0 }
        return outcomes.map { ((it.totalPoints.coerceAtLeast(0) * 100.0) / total).roundToInt().coerceIn(0, 100) }
    }

    fun predictionIsFinal(status: String): Boolean = status.uppercase() in setOf(
        "RESOLVED", "CANCELED", "CANCELLED", "REFUNDED",
    )

    fun dropPercent(currentMinutes: Int, requiredMinutes: Int): Int = if (requiredMinutes <= 0) {
        0
    } else {
        ((currentMinutes.coerceAtLeast(0) * 100L) / requiredMinutes).toInt().coerceIn(0, 100)
    }

    fun dropRemainingMinutes(currentMinutes: Int, requiredMinutes: Int): Int =
        (requiredMinutes - currentMinutes).coerceAtLeast(0)

    fun dropIsActive(snapshot: DropsLiveUpdateSnapshot, nowMs: Long = System.currentTimeMillis()): Boolean =
        snapshot.state == DROP_ACTIVE &&
            !snapshot.claimed &&
            snapshot.currentMinutes < snapshot.requiredMinutes &&
            (snapshot.expiresAtMs == null || snapshot.expiresAtMs > nowMs)

    fun promotedOngoingAllowed(
        sdkInt: Int,
        requested: Boolean,
        permissionGranted: Boolean,
        systemPromotionAllowed: Boolean = true,
    ): Boolean = sdkInt >= 36 && requested && permissionGranted && systemPromotionAllowed

    fun chatBubbleTag(channelId: String): String = "$CHAT_BUBBLE_TAG_PREFIX$channelId"
}
