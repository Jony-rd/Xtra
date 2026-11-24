package com.github.andreyasadchy.xtra.util.watch

import com.github.andreyasadchy.xtra.model.stats.ViewingPlaybackMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

data class PrimaryPlaybackWatchState(
    val ownerId: Long,
    val generation: Long,
    val metadata: ViewingPlaybackMetadata,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val liveEligible: Boolean,
    val playWhenReady: Boolean = false,
    val isAudioOnly: Boolean = false,
    val isBackground: Boolean = false,
    val isMuted: Boolean = false,
)

/** Process-wide snapshot published beside the primary player's viewing-stat callbacks. */
class PrimaryPlaybackWatchStateStore {
    private val lock = Any()
    private val nextOwnerId = AtomicLong(0L)
    private var nextGeneration = 0L
    private val _state = MutableStateFlow<PrimaryPlaybackWatchState?>(null)
    val state: StateFlow<PrimaryPlaybackWatchState?> = _state.asStateFlow()

    fun newOwnerId(): Long = nextOwnerId.incrementAndGet()

    fun begin(
        ownerId: Long,
        metadata: ViewingPlaybackMetadata,
        liveEligible: Boolean,
        isPlaying: Boolean = false,
        isBuffering: Boolean = true,
        playWhenReady: Boolean = false,
        isAudioOnly: Boolean = false,
        isBackground: Boolean = false,
        isMuted: Boolean = false,
    ): Long = synchronized(lock) {
        val generation = ++nextGeneration
        _state.value = PrimaryPlaybackWatchState(
            ownerId = ownerId,
            generation = generation,
            metadata = metadata,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            liveEligible = liveEligible,
            playWhenReady = playWhenReady,
            isAudioOnly = isAudioOnly,
            isBackground = isBackground,
            isMuted = isMuted,
        )
        generation
    }

    fun update(
        ownerId: Long,
        generation: Long,
        metadata: ViewingPlaybackMetadata,
        isPlaying: Boolean,
        isBuffering: Boolean,
        liveEligible: Boolean,
        playWhenReady: Boolean = false,
        isAudioOnly: Boolean = false,
        isBackground: Boolean = false,
        isMuted: Boolean = false,
    ): Long? = synchronized(lock) {
        val current = _state.value
        if (current == null || current.ownerId != ownerId || current.generation != generation) {
            return@synchronized null
        }
        if (!current.metadata.hasSamePlaybackAs(metadata)) {
            val nextGen = ++nextGeneration
            _state.value = PrimaryPlaybackWatchState(
                ownerId = ownerId,
                generation = nextGen,
                metadata = metadata,
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                liveEligible = liveEligible,
                playWhenReady = playWhenReady,
                isAudioOnly = isAudioOnly,
                isBackground = isBackground,
                isMuted = isMuted,
            )
            nextGen
        } else {
            _state.value = current.copy(
                metadata = metadata,
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                liveEligible = liveEligible,
                playWhenReady = playWhenReady,
                isAudioOnly = isAudioOnly,
                isBackground = isBackground,
                isMuted = isMuted,
            )
            generation
        }
    }

    fun release(ownerId: Long, generation: Long) = synchronized(lock) {
        val current = _state.value
        if (current?.ownerId == ownerId && current.generation == generation) {
            _state.value = null
        }
    }
}
