package com.github.andreyasadchy.xtra.ui.player

import com.github.andreyasadchy.xtra.model.VideoQuality

internal fun resolveAudioModeRestoreQuality(
    previousQuality: VideoQuality?,
    qualities: List<VideoQuality>?,
): VideoQuality? {
    return previousQuality
        ?.takeUnless {
            it.name == BasePlaybackService.AUDIO_ONLY_QUALITY ||
                it.name == BasePlaybackService.CHAT_ONLY_QUALITY
        }
        ?: qualities?.firstOrNull { it.name == BasePlaybackService.AUTO_QUALITY }
        ?: qualities?.firstOrNull {
            it.name != BasePlaybackService.AUDIO_ONLY_QUALITY &&
                it.name != BasePlaybackService.CHAT_ONLY_QUALITY
        }
}
