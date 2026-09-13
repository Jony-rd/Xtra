package com.github.andreyasadchy.xtra.ui.main

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Wakes the fallback scheduler without starting a second long-running monitor. */
class LiveNotificationWatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_WATCHDOG) {
            LiveNotificationScheduler.onWatchdogAlarm(context)
        }
    }

    companion object {
        const val ACTION_WATCHDOG = "com.github.andreyasadchy.xtra.action.LIVE_NOTIFICATION_WATCHDOG"
    }
}
