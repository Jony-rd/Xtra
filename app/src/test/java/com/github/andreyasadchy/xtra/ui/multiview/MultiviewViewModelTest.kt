package com.github.andreyasadchy.xtra.ui.multiview

import androidx.lifecycle.SavedStateHandle
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.multiview.playback.MultiviewPlaybackSnapshot
import com.github.andreyasadchy.xtra.ui.multiview.playback.MultiviewQualityPolicy
import com.github.andreyasadchy.xtra.ui.multiview.playback.MultiviewSlotStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MultiviewViewModelTest {
    private val initialStream = Stream(
        id = "stream-1",
        channelId = "100",
        channelLogin = "Alpha",
        channelName = "Alpha",
    )
    private val otherStream = Stream(
        id = "stream-2",
        channelId = "200",
        channelLogin = "Beta",
        channelName = "Beta",
    )

    @Test
    fun replacingStreamRemovesPlaybackSnapshotWhenSourceDisappears() {
        val snapshot = MultiviewPlaybackSnapshot(MultiviewSlotStatus.LIVE, "720p")
        val state = MultiviewSessionState(streams = listOf(otherStream))

        val playback = MultiviewViewModel.cleanupPlaybackSnapshots(
            playback = mapOf("id:100" to snapshot, "id:200" to snapshot),
            sourceIdentity = "id:100",
            state = state,
        )

        assertFalse(playback.containsKey("id:100"))
        assertEquals(snapshot, playback["id:200"])
    }

    @Test
    fun restoredActiveStreamIsNotResetBySecondInitialize() {
        val handle = SavedStateHandle()
        var state = applyInitialStream(handle, MultiviewSessionState())
        state = MultiviewSessionReducer.add(state, otherStream, initialAudioVolume = 0f)
        state = MultiviewSessionReducer.setActive(state, "id:200")

        val restoredState = applyInitialStream(recreateHandle(handle), state)

        assertEquals("id:200", restoredState.activeIdentity)
        assertEquals(listOf("id:100", "id:200"), restoredState.identities)
    }

    @Test
    fun removedInitialStreamIsNotReaddedDuringRecreation() {
        val handle = SavedStateHandle()
        var state = applyInitialStream(handle, MultiviewSessionState())
        state = MultiviewSessionReducer.remove(state, "id:100")

        val restoredState = applyInitialStream(recreateHandle(handle), state)

        assertEquals(emptyList<String>(), restoredState.identities)
    }

    @Test
    fun toggleAudioOnlyRestoresPreviousManualAndAutoOverrides() {
        val previous = mutableMapOf<String, String?>()

        // Toggle Audio-Only on from Auto (no override), then back off -> returns to Auto
        val autoToAudio = MultiviewViewModel.toggleAudioOnlyOverride(emptyMap(), "id:100", previous)
        assertEquals(MultiviewQualityPolicy.AUDIO_ONLY_LABEL, autoToAudio["id:100"])
        val audioBackToAuto = MultiviewViewModel.toggleAudioOnlyOverride(autoToAudio, "id:100", previous)
        assertFalse(audioBackToAuto.containsKey("id:100"))

        // Toggle Audio-Only on from explicit 720p60, then back off -> restores 720p60
        val manualToAudio = MultiviewViewModel.toggleAudioOnlyOverride(
            mapOf("id:200" to "720p60"),
            "id:200",
            previous,
        )
        assertEquals(MultiviewQualityPolicy.AUDIO_ONLY_LABEL, manualToAudio["id:200"])
        val audioBackToManual = MultiviewViewModel.toggleAudioOnlyOverride(manualToAudio, "id:200", previous)
        assertEquals("720p60", audioBackToManual["id:200"])
    }

    private fun applyInitialStream(
        handle: SavedStateHandle,
        state: MultiviewSessionState,
    ): MultiviewSessionState {
        return MultiviewViewModel.initializeStateOnce(
            savedStateHandle = handle,
            state = state,
            initialStream = initialStream,
            initialAudioVolume = { 0f },
        ) ?: state
    }

    private fun recreateHandle(handle: SavedStateHandle): SavedStateHandle {
        return SavedStateHandle(
            handle.keys().associateWith { key -> handle.get<Any?>(key) },
        )
    }
}
