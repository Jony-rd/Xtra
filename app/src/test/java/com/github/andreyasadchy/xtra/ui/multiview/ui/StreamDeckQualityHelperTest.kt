package com.github.andreyasadchy.xtra.ui.multiview.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamDeckQualityHelperTest {

    private val auto = "Auto"
    private val available = listOf("1080p60 (source)", "720p60", "480p", "360p", "160p", "Audio Only")

    @Test
    fun shortLabelFormatsCleanly() {
        assertEquals("Auto", StreamDeckQualityHelper.shortLabel(auto, auto))
        assertEquals("1080p", StreamDeckQualityHelper.shortLabel("1080p60 (source)", auto))
        assertEquals("720p", StreamDeckQualityHelper.shortLabel("720p60", auto))
        assertEquals("480p", StreamDeckQualityHelper.shortLabel("480p", auto))
        assertEquals("360p", StreamDeckQualityHelper.shortLabel("360p", auto))
        assertEquals("160p", StreamDeckQualityHelper.shortLabel("160p", auto))
        assertEquals("Audio", StreamDeckQualityHelper.shortLabel("Audio Only", auto))
    }

    @Test
    fun resolveTargetQualityMatchesCorrectly() {
        assertNull(StreamDeckQualityHelper.resolveTargetQuality("Auto", auto, available))
        assertEquals("1080p60 (source)", StreamDeckQualityHelper.resolveTargetQuality("1080p", auto, available))
        assertEquals("720p60", StreamDeckQualityHelper.resolveTargetQuality("720p", auto, available))
        assertEquals("480p", StreamDeckQualityHelper.resolveTargetQuality("480p", auto, available))
    }

    @Test
    fun isSelectedHonorsManualOverrideFirst() {
        // When manual override is 720p, 720p should be selected even if playing is 1080p
        assertTrue(StreamDeckQualityHelper.isSelected("720p60", "720p60", "1080p60", auto))
        assertFalse(StreamDeckQualityHelper.isSelected("1080p60 (source)", "720p60", "1080p60", auto))
    }

    @Test
    fun isSelectedDetectsActiveStreamQualityWhenNoOverride() {
        // When no override is set, matches active playing label
        assertTrue(StreamDeckQualityHelper.isSelected("1080p60 (source)", null, "1080p60", auto))
        assertFalse(StreamDeckQualityHelper.isSelected("720p60", null, "1080p60", auto))
    }

    @Test
    fun isSelectedDefaultsToAutoWhenNoOverrideAndNoPlayingLabel() {
        assertTrue(StreamDeckQualityHelper.isSelected(auto, null, null, auto))
        assertTrue(StreamDeckQualityHelper.isSelected(auto, null, "", auto))
        assertFalse(StreamDeckQualityHelper.isSelected("1080p60 (source)", null, "", auto))
    }
}
