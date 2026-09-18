package com.github.andreyasadchy.xtra.ui.player

import com.github.andreyasadchy.xtra.model.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioModeQualityPolicyTest {

    @Test
    fun audioOnlyStartupWithoutPreviousQualityRestoresAutoVideoQuality() {
        val restoredQuality = resolveAudioModeRestoreQuality(
            previousQuality = null,
            qualities = listOf(
                VideoQuality(BasePlaybackService.AUDIO_ONLY_QUALITY),
                VideoQuality(BasePlaybackService.AUTO_QUALITY),
                VideoQuality("720p60"),
            ),
        )

        assertEquals(BasePlaybackService.AUTO_QUALITY, restoredQuality?.name)
    }
}
