package com.github.andreyasadchy.xtra.util.updater

import android.app.DownloadManager
import android.content.SharedPreferences
import com.github.andreyasadchy.xtra.util.C
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDownloadTelemetryTest {
    @Test
    fun persistsAttemptMilestonesAndRedactsEndpointQuery() {
        val preferences = MemoryPreferences()
        var now = 1_000L
        val telemetry = UpdateDownloadTelemetry(preferences) { now }
        val asset = UpdateAsset(
            name = "app-release.apk",
            contentType = UpdateRepository.APK_MIME_TYPE,
            downloadUrl = "https://downloads.example/update.apk?token=secret",
            size = 100L,
        )
        val release = release(asset)

        val attemptId = telemetry.begin(release, asset)
        telemetry.recordEnqueued(attemptId, 42L)
        telemetry.recordMonitorStarted(42L)
        telemetry.recordObservation(
            42L,
            record(DownloadManager.STATUS_PENDING, 0L),
            DownloadProgress(0L, 100L),
        )
        now = 7_000L
        telemetry.recordObservation(
            42L,
            record(DownloadManager.STATUS_RUNNING, 20L),
            DownloadProgress(20L, 100L, bytesPerSecond = 20L),
        )

        val recovered = UpdateDownloadTelemetry(preferences) { now }
        val snapshot = recovered.snapshot()

        assertNotNull(snapshot)
        assertEquals(attemptId, snapshot?.attemptId)
        assertEquals(42L, snapshot?.downloadId)
        assertEquals(2L, snapshot?.pollCount)
        assertEquals(20L, snapshot?.downloadedBytes)
        assertEquals(100L, snapshot?.totalBytes)
        assertEquals(20L, snapshot?.bytesPerSecond)
        assertEquals(7_000L, snapshot?.firstByteAt)
        assertEquals(7_000L, snapshot?.lastByteAt)
        assertTrue(snapshot?.endpoint?.isNotBlank() == true)
        assertFalse(snapshot?.endpoint?.contains("token") == true)
        assertFalse(preferences.getString(C.UPDATE_DOWNLOAD_DIAGNOSTICS, "").orEmpty().contains("secret"))
        assertTrue(snapshot?.events.orEmpty().any { it.description.contains("RUNNING") })
    }

    @Test
    fun queryFailureIsPersistedWithoutStoringAnExceptionMessage() {
        val preferences = MemoryPreferences()
        val telemetry = UpdateDownloadTelemetry(preferences) { 5_000L }
        val asset = UpdateAsset(
            name = "app-release.apk",
            contentType = UpdateRepository.APK_MIME_TYPE,
            downloadUrl = "https://downloads.example/update.apk",
            size = 100L,
        )
        telemetry.begin(release(asset), asset)
        telemetry.recordEnqueued(telemetry.snapshot()!!.attemptId, 42L)

        telemetry.recordQueryFailure(42L, "SecurityException")

        val snapshot = telemetry.snapshot()!!
        assertEquals(1L, snapshot.queryFailureCount)
        assertEquals("SecurityException", snapshot.lastErrorType)
        assertFalse(preferences.getString(C.UPDATE_DOWNLOAD_DIAGNOSTICS, "").orEmpty().contains("/data/"))
    }

    @Test
    fun failedObservationPersistsStatusReasonAndProgressBeforeOutcome() {
        val preferences = MemoryPreferences()
        val telemetry = UpdateDownloadTelemetry(preferences) { 5_000L }
        val asset = UpdateAsset(
            name = "app-release.apk",
            contentType = UpdateRepository.APK_MIME_TYPE,
            downloadUrl = "https://downloads.example/update.apk",
            size = 100L,
        )
        telemetry.begin(release(asset), asset)
        telemetry.recordEnqueued(telemetry.snapshot()!!.attemptId, 42L)
        telemetry.recordObservation(
            42L,
            record(DownloadManager.STATUS_FAILED, 20L, DownloadManager.ERROR_HTTP_DATA_ERROR),
            DownloadProgress(20L, 100L, bytesPerSecond = 20L),
        )
        telemetry.recordFailed(42L, DownloadManager.ERROR_HTTP_DATA_ERROR)

        val snapshot = telemetry.snapshot()!!
        assertEquals(DownloadManager.STATUS_FAILED, snapshot.lastStatus)
        assertEquals(DownloadManager.ERROR_HTTP_DATA_ERROR, snapshot.lastReason)
        assertEquals(20L, snapshot.downloadedBytes)
        assertEquals(100L, snapshot.totalBytes)
        assertEquals("FAILED", snapshot.outcome)
    }

    private fun release(asset: UpdateAsset) = UpdateRelease(
        tagName = "v9.9.9",
        versionName = "9.9.9",
        buildNumber = 999L,
        title = "Xtra",
        releaseNotes = emptyList(),
        rawBody = "",
        releaseUrl = "https://example.test/release",
        publishedAt = null,
        assets = listOf(asset),
        prerelease = false,
        draft = false,
    )

    private fun record(status: Int, bytes: Long, reason: Int? = null) = UpdateDownloadRecord(
        status = status,
        downloadedBytes = bytes,
        totalBytes = 100L,
        uri = null,
        reason = reason,
    )

    private class MemoryPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = values.toMap()
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = values[key] as? Set<String> ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (values[key] as? Number)?.toInt() ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (values[key] as? Number)?.toLong() ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (values[key] as? Number)?.toFloat() ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val updates = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = put(key, value)
            override fun putStringSet(key: String?, value: Set<String>?): SharedPreferences.Editor = put(key, value)
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = put(key, value)
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = put(key, value)
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = put(key, value)
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = put(key, value)
            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) removals += key
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                values.keys.toList().forEach(removals::add)
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                removals.forEach(values::remove)
                updates.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
            }
            private fun <T> put(key: String?, value: T): SharedPreferences.Editor {
                if (key != null) updates[key] = value
                return this
            }
        }
    }
}
