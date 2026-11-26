package com.github.andreyasadchy.xtra.player.hls

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@UnstableApi
class TwitchHlsPlaylistContinuityTest {

    private val baseEpochUs = 1_704_067_200_000_000L

    @Test
    fun gappedPostRecoveryPlaylistDoesNotShiftTimelineIntoFuture() {
        // Simulates a post-recovery ControlledVaftPlaylist snapshot retaining stale segments
        // [0s, 2s, 4s] alongside live segments [165s, 167s, 169s].
        val gappedPrevious = playlist(
            mediaSequence = 100L,
            startTimeUs = baseEpochUs,
            segments = listOf(
                segment(0, 0L, 2_000_000L),
                segment(1, 2_000_000L, 2_000_000L),
                segment(2, 4_000_000L, 2_000_000L),
                segment(82, 165_000_000L, 2_000_000L, relativeDiscontinuity = 1),
                segment(83, 167_000_000L, 2_000_000L, relativeDiscontinuity = 1),
                segment(84, 169_000_000L, 2_000_000L, relativeDiscontinuity = 1),
            ),
        )
        val rawUpdate = contiguousPlaylist(
            mediaSequence = 181L,
            startTimeUs = baseEpochUs + 162_000_000L,
            count = 6,
        )

        val adjustment = TwitchHlsPlaylistParserFactory.preserveProgramDateTimeContinuity(
            previousPlaylist = gappedPrevious,
            updatedPlaylist = rawUpdate,
            previousParsedAtElapsedRealtimeMs = 1_000L,
            parsedAtElapsedRealtimeMs = 2_000L,
            allowSequenceGapContinuity = true,
        )

        assertNull(adjustment)
    }

    @Test
    fun crossRenditionFallbackNeverRunsSequenceGapContinuity() {
        val oldRendition = contiguousPlaylist(
            mediaSequence = 100L,
            startTimeUs = baseEpochUs,
            count = 6,
        )
        val switchedRendition = contiguousPlaylist(
            mediaSequence = 181L,
            startTimeUs = baseEpochUs + 162_000_000L,
            count = 6,
        )

        val adjustment = TwitchHlsPlaylistParserFactory.preserveProgramDateTimeContinuity(
            previousPlaylist = oldRendition,
            updatedPlaylist = switchedRendition,
            previousParsedAtElapsedRealtimeMs = 1_000L,
            parsedAtElapsedRealtimeMs = 163_000L,
            allowSequenceGapContinuity = false,
        )

        assertNull(adjustment)
    }

    @Test
    fun crossRenditionOverlapRejectsLargeClockMismatch() {
        val oldRendition = contiguousPlaylist(
            mediaSequence = 100L,
            startTimeUs = baseEpochUs,
            count = 6,
        )
        val unrelatedBackupRendition = contiguousPlaylist(
            mediaSequence = 102L,
            startTimeUs = baseEpochUs + 165_000_000L,
            count = 6,
        )

        val adjustment = TwitchHlsPlaylistParserFactory.preserveProgramDateTimeContinuity(
            previousPlaylist = oldRendition,
            updatedPlaylist = unrelatedBackupRendition,
            previousParsedAtElapsedRealtimeMs = 1_000L,
            parsedAtElapsedRealtimeMs = 2_000L,
            allowSequenceGapContinuity = false,
        )

        assertNull(adjustment)
    }

    @Test
    fun sameRenditionSequenceGapContinuityRebasesCleanServerClockShift() {
        val previous = contiguousPlaylist(
            mediaSequence = 100L,
            startTimeUs = baseEpochUs,
            count = 6,
        )
        // 14 seconds later: 7 new segments total -> mediaSequence advanced by 7 (107), gap = 1.
        // Server PDT jumped +10s on packager failover; continuity rebases it smoothly to +14s.
        val updated = contiguousPlaylist(
            mediaSequence = 107L,
            startTimeUs = baseEpochUs + 24_000_000L,
            count = 6,
        )

        val adjustment = TwitchHlsPlaylistParserFactory.preserveProgramDateTimeContinuity(
            previousPlaylist = previous,
            updatedPlaylist = updated,
            previousParsedAtElapsedRealtimeMs = 1_000L,
            parsedAtElapsedRealtimeMs = 15_000L,
            allowSequenceGapContinuity = true,
        )

        assertNotNull(adjustment)
        assertEquals(baseEpochUs + 14_000_000L, adjustment?.playlist?.startTimeUs)
        assertEquals("sequence_gap_continuity", adjustment?.source)
    }

    private fun contiguousPlaylist(
        mediaSequence: Long,
        startTimeUs: Long,
        count: Int,
        segmentDurationUs: Long = 2_000_000L,
    ): HlsMediaPlaylist {
        val segments = (0 until count).map { idx ->
            segment(
                index = (mediaSequence + idx).toInt(),
                relativeStartTimeUs = idx * segmentDurationUs,
                durationUs = segmentDurationUs,
            )
        }
        return playlist(mediaSequence, startTimeUs, segments)
    }

    private fun segment(
        index: Int,
        relativeStartTimeUs: Long,
        durationUs: Long,
        relativeDiscontinuity: Int = 0,
    ) = HlsMediaPlaylist.Segment(
        "https://example.invalid/seg_$index.ts",
        null,
        "",
        durationUs,
        relativeDiscontinuity,
        relativeStartTimeUs,
        null,
        null,
        null,
        0L,
        C.LENGTH_UNSET.toLong(),
        false,
        emptyList(),
    )

    private fun playlist(
        mediaSequence: Long,
        startTimeUs: Long,
        segments: List<HlsMediaPlaylist.Segment>,
    ) = HlsMediaPlaylist(
        HlsMediaPlaylist.PLAYLIST_TYPE_UNKNOWN,
        "https://example.invalid/live.m3u8",
        emptyList(),
        C.TIME_UNSET,
        false,
        startTimeUs,
        true,
        0,
        mediaSequence,
        6,
        2_000_000L,
        C.TIME_UNSET,
        true,
        false,
        true,
        null,
        segments,
        emptyList(),
        HlsMediaPlaylist.ServerControl(C.TIME_UNSET, false, C.TIME_UNSET, C.TIME_UNSET, false),
        emptyMap(),
        emptyList(),
        null,
    )
}
