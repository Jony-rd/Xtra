package com.github.andreyasadchy.xtra.ui.multiview.ui

/**
 * Encapsulates quality resolution, formatting, and selection matching logic
 * for the Multiview Stream Deck Studio.
 */
object StreamDeckQualityHelper {

    fun standardMasterQualities(autoLabel: String): List<String> {
        return listOf(autoLabel, "1080p", "720p", "480p", "360p", "160p")
    }

    fun shortLabel(quality: String, autoLabel: String): String {
        return when {
            quality.equals(autoLabel, ignoreCase = true) -> "Auto"
            quality.contains("1080", ignoreCase = true) -> "1080p"
            quality.contains("720", ignoreCase = true) -> "720p"
            quality.contains("480", ignoreCase = true) -> "480p"
            quality.contains("360", ignoreCase = true) -> "360p"
            quality.contains("160", ignoreCase = true) -> "160p"
            else -> quality.split(" ").firstOrNull()?.takeIf { it.isNotBlank() } ?: quality
        }
    }

    /**
     * Resolves the target quality string against a stream's available qualities.
     * Returns null when Auto is selected so that auto bitrate algorithm takes over.
     */
    fun resolveTargetQuality(
        selectedLabel: String,
        autoLabel: String,
        availableQualities: List<String>,
    ): String? {
        if (selectedLabel.equals(autoLabel, ignoreCase = true) || selectedLabel.equals("auto", ignoreCase = true)) {
            return null
        }
        return availableQualities.firstOrNull { it.contains(selectedLabel, ignoreCase = true) } ?: selectedLabel
    }

    /**
     * Determines whether a given pill tag is considered active / selected.
     */
    fun isSelected(
        pillTag: String,
        overrideQuality: String?,
        playingQuality: String?,
        autoLabel: String,
    ): Boolean {
        if (overrideQuality != null) {
            return pillTag.equals(overrideQuality, ignoreCase = true)
        }
        val activeLabel = playingQuality.orEmpty().trim()
        if (activeLabel.isBlank()) {
            return pillTag.equals(autoLabel, ignoreCase = true)
        }
        if (pillTag.equals(autoLabel, ignoreCase = true)) {
            return false
        }
        return pillTag.contains(activeLabel, ignoreCase = true) || activeLabel.contains(pillTag, ignoreCase = true)
    }
}
