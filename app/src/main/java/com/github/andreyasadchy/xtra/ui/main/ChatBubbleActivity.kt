package com.github.andreyasadchy.xtra.ui.main

import android.os.Build
import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.ui.chat.ChatFragment

class ChatBubbleActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            finish()
            return
        }
        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID)?.takeIf(String::isNotBlank)
        val login = intent.getStringExtra(EXTRA_CHANNEL_LOGIN)?.takeIf(String::isNotBlank)
        if (channelId == null || login == null) {
            finish()
            return
        }
        (application as XtraApp).xtraModule.chatBubbleManager.clearUnread()
        val container = FrameLayout(this).apply { id = R.id.chatBubbleContainer }
        setContentView(container)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(
                    R.id.chatBubbleContainer,
                    ChatFragment.newInstance(
                        channelId = channelId,
                        channelLogin = login,
                        channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME),
                        streamId = intent.getStringExtra(EXTRA_STREAM_ID),
                        sharedSessionOnly = true,
                    ),
                )
                .commit()
        }
    }

    companion object {
        const val EXTRA_CHANNEL_ID = "chat_bubble_channel_id"
        const val EXTRA_CHANNEL_LOGIN = "chat_bubble_channel_login"
        const val EXTRA_CHANNEL_NAME = "chat_bubble_channel_name"
        const val EXTRA_STREAM_ID = "chat_bubble_stream_id"
    }

    override fun onResume() {
        super.onResume()
        (application as XtraApp).xtraModule.chatBubbleManager.setBubbleVisible(true)
    }

    override fun onPause() {
        (application as XtraApp).xtraModule.chatBubbleManager.setBubbleVisible(false)
        super.onPause()
    }
}
