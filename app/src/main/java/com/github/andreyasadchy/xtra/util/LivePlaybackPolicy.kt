package com.github.andreyasadchy.xtra.util

import androidx.media3.exoplayer.DefaultLoadControl

/** Catches up at the later of six seconds total offset or four seconds beyond the target. */
object LivePriorityCatchUpPolicy {
    private const val MINIMUM_CATCH_UP_OFFSET_MS = 6_000L
    private const val ALLOWED_DELAY_BEYOND_TARGET_MS = 4_000L
    // The user's reported offset varied by about two seconds while the clock was stale.
    const val MINIMUM_PROGRESS_MS = 3_000L

    fun thresholdMs(targetOffsetMs: Long): Long =
        maxOf(MINIMUM_CATCH_UP_OFFSET_MS, targetOffsetMs + ALLOWED_DELAY_BEYOND_TARGET_MS)

    fun madeProgress(previousOffsetMs: Long, currentOffsetMs: Long): Boolean =
        previousOffsetMs - currentOffsetMs >= MINIMUM_PROGRESS_MS
}

/** Tracks single-attempt progress for live catch-up seeks so stalled windows don't seek in a loop. */
class LivePriorityCatchUpTracker(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val recoveryMarginMs: Long = DEFAULT_RECOVERY_MARGIN_MS,
) {
    private var lastSeekRealtimeMs: Long = Long.MIN_VALUE
    private var lastSeekOffsetMs: Long? = null
    private var suppressed: Boolean = false

    fun reset() {
        lastSeekRealtimeMs = Long.MIN_VALUE
        lastSeekOffsetMs = null
        suppressed = false
    }

    fun recordSeek(liveOffsetMs: Long?, realtimeMs: Long) {
        lastSeekOffsetMs = liveOffsetMs
        lastSeekRealtimeMs = realtimeMs
        suppressed = false
    }

    fun requestSeek(
        liveOffsetMs: Long,
        targetOffsetMs: Long,
        realtimeMs: Long,
    ): Boolean {
        if (liveOffsetMs < 0L) return false
        if (liveOffsetMs <= targetOffsetMs + recoveryMarginMs) {
            reset()
        }
        val thresholdMs = LivePriorityCatchUpPolicy.thresholdMs(targetOffsetMs)
        if (liveOffsetMs <= thresholdMs || suppressed) return false

        if (lastSeekRealtimeMs != Long.MIN_VALUE) {
            if (realtimeMs - lastSeekRealtimeMs < cooldownMs) return false
            val previousOffsetMs = lastSeekOffsetMs
            if (previousOffsetMs != null &&
                !LivePriorityCatchUpPolicy.madeProgress(previousOffsetMs, liveOffsetMs)
            ) {
                suppressed = true
                return false
            }
        }

        lastSeekOffsetMs = liveOffsetMs
        lastSeekRealtimeMs = realtimeMs
        return true
    }

    fun isSuppressed(): Boolean = suppressed

    companion object {
        const val DEFAULT_COOLDOWN_MS = 15_000L
        const val DEFAULT_RECOVERY_MARGIN_MS = 2_000L
    }
}

data class PlaybackBufferPolicy(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    val prioritizeTimeOverSizeThresholds: Boolean,
) {
    fun buildLoadControl(
        configure: DefaultLoadControl.Builder.() -> Unit = {},
    ): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMsForStreaming(
            minBufferMs,
            maxBufferMs,
            bufferForPlaybackMs,
            bufferForPlaybackAfterRebufferMs,
        )
        .setPrioritizeTimeOverSizeThresholdsForStreaming(prioritizeTimeOverSizeThresholds)
        .apply(configure)
        .build()
}

data class LivePlaybackPolicy(
    val lowLatency: Boolean,
    val buffers: PlaybackBufferPolicy,
    val targetOffsetMs: Long,
)

data class LivePlaybackPolicySet(
    val normal: LivePlaybackPolicy,
    val minimumDelay: LivePlaybackPolicy,
    val custom: LivePlaybackPolicy,
    val buffered: LivePlaybackPolicy,
    val recovering: LivePlaybackPolicy,
) {
    fun forMode(mode: LivePlaybackMode): LivePlaybackPolicy = when (mode) {
        LivePlaybackMode.MINIMUM_DELAY -> minimumDelay
        LivePlaybackMode.CUSTOM -> custom
        LivePlaybackMode.NORMAL -> normal
    }

    fun forAdaptiveState(state: AdaptiveLivePlaybackController.State): LivePlaybackPolicy = when (state) {
        AdaptiveLivePlaybackController.State.NORMAL -> normal
        AdaptiveLivePlaybackController.State.LOW_LATENCY -> minimumDelay
        AdaptiveLivePlaybackController.State.BUFFERED -> buffered
        AdaptiveLivePlaybackController.State.RECOVERING -> recovering
    }
}

object LivePlaybackPolicies {
    val NORMAL = LivePlaybackPolicy(
        lowLatency = false,
        buffers = PlaybackBufferPolicy(
            minBufferMs = 15_000,
            maxBufferMs = 50_000,
            bufferForPlaybackMs = 2_000,
            bufferForPlaybackAfterRebufferMs = 2_000,
            prioritizeTimeOverSizeThresholds = false,
        ),
        targetOffsetMs = C.NORMAL_LATENCY_TARGET_OFFSET_MS,
    )

    val LOW_LATENCY = LivePlaybackPolicy(
        lowLatency = true,
        buffers = PlaybackBufferPolicy(
            minBufferMs = 1_500,
            maxBufferMs = 6_000,
            bufferForPlaybackMs = 250,
            bufferForPlaybackAfterRebufferMs = 1_000,
            prioritizeTimeOverSizeThresholds = true,
        ),
        targetOffsetMs = C.LOW_LATENCY_TARGET_OFFSET_MS,
    )

    val BUFFERED = LivePlaybackPolicy(
        lowLatency = true,
        buffers = PlaybackBufferPolicy(
            minBufferMs = 6_000,
            maxBufferMs = 18_000,
            bufferForPlaybackMs = 1_000,
            bufferForPlaybackAfterRebufferMs = 2_000,
            prioritizeTimeOverSizeThresholds = true,
        ),
        targetOffsetMs = 6_000L,
    )

    val RECOVERING = LivePlaybackPolicy(
        lowLatency = true,
        buffers = PlaybackBufferPolicy(
            minBufferMs = 12_000,
            maxBufferMs = 30_000,
            bufferForPlaybackMs = 1_500,
            bufferForPlaybackAfterRebufferMs = 3_000,
            prioritizeTimeOverSizeThresholds = true,
        ),
        targetOffsetMs = 10_000L,
    )

    fun forLowLatency(enabled: Boolean): LivePlaybackPolicy = if (enabled) LOW_LATENCY else NORMAL

    fun forSettings(settings: LivePlaybackSettings): LivePlaybackPolicySet {
        val customBuffers = NORMAL.buffers.withOverrides(settings.buffers)
        return LivePlaybackPolicySet(
            normal = NORMAL,
            minimumDelay = LOW_LATENCY,
            custom = LivePlaybackPolicy(
                lowLatency = true,
                buffers = customBuffers,
                targetOffsetMs = settings.targetOffsetMs ?: NORMAL.targetOffsetMs,
            ),
            buffered = BUFFERED,
            recovering = RECOVERING,
        )
    }

    private fun PlaybackBufferPolicy.withOverrides(overrides: PlaybackBufferOverrides): PlaybackBufferPolicy {
        val requestedMin = overrides.minBufferMs
        val requestedMax = overrides.maxBufferMs
        val min = requestedMin ?: if (requestedMax != null && requestedMax <= minBufferMs) {
            (requestedMax / 2).coerceAtLeast(250)
        } else {
            minBufferMs
        }
        val max = requestedMax ?: if (requestedMin != null && requestedMin >= maxBufferMs) {
            (requestedMin * 2).coerceAtMost(120_000)
        } else {
            maxBufferMs
        }
        val validPair = min < max
        val effectiveMin = if (validPair) min else minBufferMs
        val effectiveMax = if (validPair) max else maxBufferMs
        return copy(
            minBufferMs = effectiveMin,
            maxBufferMs = effectiveMax,
            bufferForPlaybackMs = (overrides.bufferForPlaybackMs ?: bufferForPlaybackMs).coerceAtMost(effectiveMin),
            bufferForPlaybackAfterRebufferMs =
                (overrides.bufferForPlaybackAfterRebufferMs ?: bufferForPlaybackAfterRebufferMs).coerceAtMost(effectiveMin),
        )
    }

}

