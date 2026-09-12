package com.github.andreyasadchy.xtra.util.updater

import android.content.SharedPreferences
import androidx.core.content.edit
import com.github.andreyasadchy.xtra.util.C
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class UpdateDownloadTraceEvent(
    val timestamp: Long,
    val description: String,
)

/** The last download attempt, kept small enough to survive process restarts. */
@Serializable
data class UpdateDownloadAttempt(
    val attemptId: String,
    val releaseId: String,
    val targetVersion: String,
    val assetName: String,
    val endpoint: String,
    val createdAt: Long,
    val enqueueAt: Long? = null,
    val downloadId: Long? = null,
    val monitorStartedAt: Long? = null,
    val lastPollAt: Long? = null,
    val firstByteAt: Long? = null,
    val lastByteAt: Long? = null,
    val completedAt: Long? = null,
    val lastStatusChangedAt: Long? = null,
    val lastStatus: Int? = null,
    val lastReason: Int? = null,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val bytesPerSecond: Long? = null,
    val pollCount: Long = 0L,
    val queryFailureCount: Long = 0L,
    val processRecoveryCount: Long = 0L,
    val monitorState: String = MONITOR_NOT_STARTED,
    val lastMonitorStopReason: String? = null,
    val lastErrorType: String? = null,
    val outcome: String? = null,
    val events: List<UpdateDownloadTraceEvent> = emptyList(),
)

const val MONITOR_NOT_STARTED = "NOT_STARTED"
const val MONITOR_ACTIVE = "ACTIVE"

/**
 * Records state transitions and coarse polling heartbeats without writing every 400 ms sample
 * to disk. The most recent sample is persisted at most once every five seconds, while changes
 * that explain a failure are persisted immediately.
 */
class UpdateDownloadTelemetry(
    private val preferences: SharedPreferences,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var attempt: UpdateDownloadAttempt? = load()
    private var lastPollPersistedAt: Long? = attempt?.lastPollAt

    @Synchronized
    fun begin(release: UpdateRelease, asset: UpdateAsset): String {
        val createdAt = nowMs()
        val next = UpdateDownloadAttempt(
            attemptId = UUID.randomUUID().toString(),
            releaseId = release.id,
            targetVersion = release.displayVersion,
            assetName = asset.name,
            endpoint = UpdateDiagnostics.sanitizeEndpoint(asset.downloadUrl),
            createdAt = createdAt,
            events = listOf(UpdateDownloadTraceEvent(createdAt, "download attempt created")),
        )
        attempt = next
        lastPollPersistedAt = null
        persist()
        return next.attemptId
    }

    @Synchronized
    fun recordEnqueued(attemptId: String, downloadId: Long) {
        update(attemptId) { current ->
            val timestamp = nowMs()
            current.copy(
                enqueueAt = timestamp,
                downloadId = downloadId,
                lastStatusChangedAt = timestamp,
                events = current.events.plusEvent(timestamp, "DownloadManager enqueued"),
            )
        }
        persist()
    }

    @Synchronized
    fun recordEnqueueFailure(error: Throwable) {
        attempt = attempt?.let { current ->
            val timestamp = nowMs()
            current.copy(
                lastErrorType = error.javaClass.simpleName,
                outcome = "ENQUEUE_FAILED",
                completedAt = timestamp,
                monitorState = "STOPPED",
                lastMonitorStopReason = "ENQUEUE_FAILED",
                events = current.events.plusEvent(timestamp, "enqueue failed: ${error.javaClass.simpleName}"),
            )
        }
        persist()
    }

    @Synchronized
    fun recordMonitorStarted(downloadId: Long) {
        update(downloadId) { current ->
            val timestamp = nowMs()
            current.copy(
                monitorStartedAt = timestamp,
                monitorState = MONITOR_ACTIVE,
                lastMonitorStopReason = null,
                events = current.events.plusEvent(timestamp, "monitor started"),
            )
        }
        persist()
    }

    @Synchronized
    fun recordProcessRecovery(downloadId: Long) {
        update(downloadId) { current ->
            val timestamp = nowMs()
            current.copy(
                processRecoveryCount = current.processRecoveryCount + 1L,
                events = current.events.plusEvent(timestamp, "process recovery"),
            )
        }
        persist()
    }

    @Synchronized
    fun recordObservation(downloadId: Long, record: UpdateDownloadRecord, progress: DownloadProgress) {
        val timestamp = nowMs()
        update(downloadId) { current ->
            val statusChanged = current.lastStatus != record.status || current.lastReason != record.reason
            val bytesChanged = current.downloadedBytes?.let { record.downloadedBytes > it } == true
            val receivedFirstByte = record.downloadedBytes > 0L
            current.copy(
                lastPollAt = timestamp,
                lastStatus = record.status,
                lastReason = record.reason,
                downloadedBytes = record.downloadedBytes,
                totalBytes = record.totalBytes ?: current.totalBytes,
                bytesPerSecond = progress.bytesPerSecond,
                firstByteAt = current.firstByteAt ?: timestamp.takeIf { receivedFirstByte },
                lastByteAt = timestamp.takeIf { bytesChanged || (receivedFirstByte && current.firstByteAt == null) }
                    ?: current.lastByteAt,
                lastStatusChangedAt = timestamp.takeIf { statusChanged } ?: current.lastStatusChangedAt,
                monitorState = MONITOR_ACTIVE,
                events = if (statusChanged) {
                    current.events.plusEvent(timestamp, observationDescription(record, progress))
                } else {
                    current.events
                },
                pollCount = current.pollCount + 1L,
            )
        }
        if (lastPollPersistedAt == null || timestamp - lastPollPersistedAt!! >= POLL_PERSIST_INTERVAL_MS) {
            lastPollPersistedAt = timestamp
            persist()
        }
    }

    @Synchronized
    fun recordQueryFailure(downloadId: Long, errorType: String) {
        update(downloadId) { current ->
            val timestamp = nowMs()
            current.copy(
                lastPollAt = timestamp,
                queryFailureCount = current.queryFailureCount + 1L,
                lastErrorType = errorType,
                events = current.events.plusEvent(timestamp, "DownloadManager query failed: $errorType"),
            )
        }
        persist()
    }

    @Synchronized
    fun recordMissing(downloadId: Long) {
        update(downloadId) { current ->
            val timestamp = nowMs()
            current.copy(
                lastPollAt = timestamp,
                outcome = "RECORD_MISSING",
                completedAt = timestamp,
                monitorState = "STOPPED",
                lastMonitorStopReason = "RECORD_MISSING",
                events = current.events.plusEvent(timestamp, "DownloadManager record missing"),
            )
        }
        persist()
    }

    @Synchronized
    fun recordCompleted(downloadId: Long) {
        update(downloadId) { current ->
            val timestamp = nowMs()
            current.copy(
                completedAt = timestamp,
                outcome = "SUCCESS",
                events = current.events.plusEvent(timestamp, "download completed"),
            )
        }
        persist()
    }

    @Synchronized
    fun recordFailed(downloadId: Long, reason: Int?, errorType: String? = null) {
        update(downloadId) { current ->
            val timestamp = nowMs()
            current.copy(
                completedAt = timestamp,
                lastReason = reason ?: current.lastReason,
                lastErrorType = errorType ?: current.lastErrorType,
                outcome = "FAILED",
                events = current.events.plusEvent(timestamp, "download failed"),
            )
        }
        persist()
    }

    @Synchronized
    fun recordCancelled(downloadId: Long?) {
        attempt = attempt?.takeIf { downloadId == null || it.downloadId == downloadId }?.let { current ->
            val timestamp = nowMs()
            current.copy(
                completedAt = timestamp,
                outcome = "CANCELLED",
                monitorState = "STOPPED",
                lastMonitorStopReason = "CANCELLED",
                events = current.events.plusEvent(timestamp, "download cancelled"),
            )
        }
        persist()
    }

    @Synchronized
    fun recordMonitorStopped(downloadId: Long, reason: String) {
        update(downloadId) { current ->
            val timestamp = nowMs()
            current.copy(
                monitorState = "STOPPED",
                lastMonitorStopReason = reason,
                events = current.events.plusEvent(timestamp, "monitor stopped: $reason"),
            )
        }
        persist()
    }

    @Synchronized
    fun snapshot(): UpdateDownloadAttempt? = attempt

    private fun update(downloadId: Long, transform: (UpdateDownloadAttempt) -> UpdateDownloadAttempt) {
        attempt = attempt?.takeIf { it.downloadId == downloadId }?.let(transform)
    }

    private fun update(attemptId: String, transform: (UpdateDownloadAttempt) -> UpdateDownloadAttempt) {
        attempt = attempt?.takeIf { it.attemptId == attemptId }?.let(transform)
    }

    private fun load(): UpdateDownloadAttempt? = preferences.getString(C.UPDATE_DOWNLOAD_DIAGNOSTICS, null)
        ?.let { encoded -> runCatching { json.decodeFromString<UpdateDownloadAttempt>(encoded) }.getOrNull() }

    private fun persist() {
        preferences.edit {
            attempt?.let { putString(C.UPDATE_DOWNLOAD_DIAGNOSTICS, json.encodeToString(it)) }
                ?: remove(C.UPDATE_DOWNLOAD_DIAGNOSTICS)
        }
    }

    private fun observationDescription(record: UpdateDownloadRecord, progress: DownloadProgress): String {
        val status = when (record.status) {
            android.app.DownloadManager.STATUS_PENDING -> "PENDING"
            android.app.DownloadManager.STATUS_RUNNING -> "RUNNING"
            android.app.DownloadManager.STATUS_PAUSED -> "PAUSED"
            android.app.DownloadManager.STATUS_SUCCESSFUL -> "SUCCESSFUL"
            android.app.DownloadManager.STATUS_FAILED -> "FAILED"
            else -> "UNKNOWN"
        }
        return "$status bytes=${record.downloadedBytes} rate=${progress.bytesPerSecond}"
    }

    private fun List<UpdateDownloadTraceEvent>.plusEvent(timestamp: Long, description: String): List<UpdateDownloadTraceEvent> =
        (this + UpdateDownloadTraceEvent(timestamp, description)).takeLast(MAX_TRACE_EVENTS)

    companion object {
        private const val MAX_TRACE_EVENTS = 24
        private const val POLL_PERSIST_INTERVAL_MS = 5_000L
    }
}
