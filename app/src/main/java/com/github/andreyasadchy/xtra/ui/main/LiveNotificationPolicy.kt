package com.github.andreyasadchy.xtra.ui.main

import com.github.andreyasadchy.xtra.util.C

internal const val FULL_EVENTSUB_RECONCILE_INTERVAL_MS = 15 * 60 * 1000L
internal const val PARTIAL_EVENTSUB_RECONCILE_INTERVAL_MS = 60 * 1000L
internal const val NO_CHANNELS_RECONCILE_INTERVAL_MS = 6 * 60 * 60 * 1000L
internal const val LIVE_NOTIFICATION_WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L
internal const val LIVE_NOTIFICATION_OWNER_HEARTBEAT_INTERVAL_MS = 60 * 1000L
internal const val LIVE_NOTIFICATION_OWNER_HEALTH_TIMEOUT_MS = 3 * 60 * 1000L
internal const val NETWORK_RETRY_INTERVAL_MS = 60 * 1000L
internal const val RATE_LIMIT_RETRY_INTERVAL_MS = 30 * 1000L
internal const val MIN_RATE_LIMIT_RETRY_DELAY_MS = 1_000L
internal const val RATE_LIMIT_SAFETY_MARGIN_MS = 1_000L

internal enum class LiveNotificationCoverageState {
    NO_CHANNELS,
    COMPLETE,
    PARTIAL,
    DISCONNECTED,
}

internal fun liveNotificationCoverageState(
    desiredChannelCount: Int,
    activeEventSubChannelCount: Int,
    eventSubConnected: Boolean,
    eventSubSuspended: Boolean,
    monitoredChannelCount: Int = desiredChannelCount,
): LiveNotificationCoverageState {
    if (monitoredChannelCount <= 0) return LiveNotificationCoverageState.NO_CHANNELS
    if (desiredChannelCount > 0 &&
        eventSubConnected &&
        !eventSubSuspended &&
        activeEventSubChannelCount >= desiredChannelCount
    ) {
        return LiveNotificationCoverageState.COMPLETE
    }
    return if (eventSubConnected && !eventSubSuspended) {
        LiveNotificationCoverageState.PARTIAL
    } else {
        LiveNotificationCoverageState.DISCONNECTED
    }
}

internal fun reconcileIntervalMs(
    desiredChannelCount: Int,
    activeEventSubChannelCount: Int,
    eventSubConnected: Boolean,
    eventSubSuspended: Boolean,
    monitoredChannelCount: Int = desiredChannelCount,
): Long {
    return when (liveNotificationCoverageState(
        desiredChannelCount,
        activeEventSubChannelCount,
        eventSubConnected,
        eventSubSuspended,
        monitoredChannelCount,
    )) {
        LiveNotificationCoverageState.NO_CHANNELS -> NO_CHANNELS_RECONCILE_INTERVAL_MS
        LiveNotificationCoverageState.COMPLETE -> FULL_EVENTSUB_RECONCILE_INTERVAL_MS
        LiveNotificationCoverageState.PARTIAL,
        LiveNotificationCoverageState.DISCONNECTED,
        -> PARTIAL_EVENTSUB_RECONCILE_INTERVAL_MS
    }
}

internal fun liveNotificationFailureRetryDelayMs(
    statusCode: Int,
    rateLimitResetEpochSeconds: Long?,
    nowEpochMs: Long,
): Long = rateLimitResetEpochSeconds?.let {
    (it * 1_000L - nowEpochMs + RATE_LIMIT_SAFETY_MARGIN_MS)
        .coerceAtLeast(MIN_RATE_LIMIT_RETRY_DELAY_MS)
} ?: if (statusCode == 429) {
    RATE_LIMIT_RETRY_INTERVAL_MS
} else {
    NETWORK_RETRY_INTERVAL_MS
}

internal fun applyHelixMinimumDelay(
    coverageDelayMs: Long,
    helixMinimumDelayMs: Long?,
): Long = maxOf(coverageDelayMs, helixMinimumDelayMs ?: 0L)

internal fun shouldSkipLiveNotificationWorker(hasHealthyRealtimeOwner: Boolean): Boolean =
    hasHealthyRealtimeOwner

internal fun liveNotificationOwnerIsHealthy(
    runnerRunning: Boolean,
    networkWakeAvailable: Boolean,
    heartbeatFresh: Boolean = true,
): Boolean = runnerRunning && networkWakeAvailable
    && heartbeatFresh

internal fun shouldScheduleLiveNotificationWatchdog(
    notificationsEnabled: Boolean,
    notificationsAllowed: Boolean,
    mode: String,
): Boolean = notificationsEnabled &&
    notificationsAllowed &&
    mode != C.LIVE_NOTIFICATIONS_MODE_BATTERY

internal fun shouldRunLiveNotificationWatchdog(
    notificationsEnabled: Boolean,
    notificationsAllowed: Boolean,
    mode: String,
    healthyRealtimeOwner: Boolean,
): Boolean = shouldScheduleLiveNotificationWatchdog(
    notificationsEnabled = notificationsEnabled,
    notificationsAllowed = notificationsAllowed,
    mode = mode,
) && !healthyRealtimeOwner

internal fun offlineLiveNotificationRetryDelayMs(
    cachedChannelCount: Int,
    networkWakeAvailable: Boolean,
): Long = if (cachedChannelCount == 0 && networkWakeAvailable) {
    NO_CHANNELS_RECONCILE_INTERVAL_MS
} else {
    NETWORK_RETRY_INTERVAL_MS
}
