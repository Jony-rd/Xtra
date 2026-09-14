package com.github.andreyasadchy.xtra.ui.main

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.github.andreyasadchy.xtra.XtraApp

class LiveUpdateActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val module = (context.applicationContext as? XtraApp)?.xtraModule ?: return
        when (intent.action) {
            ACTION_UNPIN_PREDICTION -> module.predictionLiveUpdateManager.untrack()
            ACTION_UNPIN_DROPS -> module.dropsLiveUpdateManager.untrack()
            ACTION_CLOSE_CHAT_BUBBLE -> module.chatBubbleManager.close()
        }
    }

    companion object {
        const val ACTION_UNPIN_PREDICTION = "com.github.andreyasadchy.xtra.UNPIN_PREDICTION"
        const val ACTION_UNPIN_DROPS = "com.github.andreyasadchy.xtra.UNPIN_DROPS"
        const val ACTION_CLOSE_CHAT_BUBBLE = "com.github.andreyasadchy.xtra.CLOSE_CHAT_BUBBLE"
    }
}
