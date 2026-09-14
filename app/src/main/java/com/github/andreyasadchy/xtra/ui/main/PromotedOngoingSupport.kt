package com.github.andreyasadchy.xtra.ui.main

import androidx.core.app.NotificationCompat

fun NotificationCompat.Builder.requestPromotedOngoingIfAllowed(
    sdkInt: Int,
    requested: Boolean,
    permissionGranted: Boolean,
    systemPromotionAllowed: Boolean = true,
    shortCriticalText: String? = null,
): NotificationCompat.Builder = apply {
    if (LiveUpdateLogic.promotedOngoingAllowed(sdkInt, requested, permissionGranted, systemPromotionAllowed)) {
        setOngoing(true)
        setRequestPromotedOngoing(true)
        shortCriticalText?.takeIf { it.isNotBlank() }?.let(::setShortCriticalText)
    }
}
