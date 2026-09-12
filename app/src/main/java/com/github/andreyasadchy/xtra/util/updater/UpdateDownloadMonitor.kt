package com.github.andreyasadchy.xtra.util.updater

import android.app.DownloadManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

sealed interface UpdateDownloadEvent {
    data class Progress(
        val record: UpdateDownloadRecord,
        val telemetry: DownloadProgress,
    ) : UpdateDownloadEvent

    data class Completed(val record: UpdateDownloadRecord) : UpdateDownloadEvent
    data class Failed(
        val record: UpdateDownloadRecord?,
        val reason: Int?,
        val queryFailed: Boolean = false,
        val queryErrorType: String? = null,
    ) : UpdateDownloadEvent
    data object Cancelled : UpdateDownloadEvent
}

/** Polls DownloadManager and reports observations. It never owns application update state. */
class UpdateDownloadMonitor(
    private val store: UpdateDownloadStore,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val pollMillis: Long = 400L,
    private val pendingRestartAfterMs: Long = 15_000L,
) {
    @Volatile
    private var job: Job? = null
    @Volatile
    private var monitoredId: Long? = null

    fun start(id: Long, onEvent: suspend (UpdateDownloadEvent) -> Unit): Boolean {
        return start(id, onEvent, null)
    }

    fun start(
        id: Long,
        onEvent: suspend (UpdateDownloadEvent) -> Unit,
        onStopped: ((Long, String) -> Unit)?,
    ): Boolean {
        if (job?.isActive == true && monitoredId == id) return false
        job?.cancel()
        monitoredId = id
        job = scope.launch {
            val estimator = TransferRateEstimator()
            var pendingSinceMs: Long? = null
            var stopReason: String? = null
            try {
                while (isActive && monitoredId == id) {
                    val record = try {
                        store.query(id)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        stopReason = "QUERY_ERROR"
                        onEvent(
                            UpdateDownloadEvent.Failed(
                                record = null,
                                reason = null,
                                queryFailed = true,
                                queryErrorType = error.javaClass.simpleName,
                            ),
                        )
                        break
                    }
                    if (record == null) {
                        stopReason = "RECORD_MISSING"
                        onEvent(UpdateDownloadEvent.Failed(null, null))
                        break
                    }
                    when (record.status) {
                        DownloadManager.STATUS_PENDING -> {
                            estimator.reset()
                            val observedAt = nowMs()
                            val pendingSince = pendingSinceMs ?: observedAt.also { pendingSinceMs = it }
                            onEvent(
                                UpdateDownloadEvent.Progress(
                                    record,
                                    DownloadProgress(
                                        downloadedBytes = record.downloadedBytes,
                                        totalBytes = record.totalBytes,
                                        stalled = observedAt - pendingSince >= pendingRestartAfterMs,
                                    ),
                                ),
                            )
                        }
                        DownloadManager.STATUS_RUNNING -> {
                            pendingSinceMs = null
                            val rate = estimator.sample(record.downloadedBytes, nowMs())
                            onEvent(
                                UpdateDownloadEvent.Progress(
                                    record,
                                    DownloadProgress(
                                        downloadedBytes = record.downloadedBytes,
                                        totalBytes = record.totalBytes,
                                        bytesPerSecond = rate.bytesPerSecond,
                                        etaSeconds = if (rate.stable) {
                                            calculateEtaSeconds(
                                                record.downloadedBytes,
                                                record.totalBytes,
                                                rate.bytesPerSecond,
                                            )
                                        } else null,
                                        stalled = rate.stalled,
                                    ),
                                ),
                            )
                        }
                        DownloadManager.STATUS_PAUSED -> {
                            pendingSinceMs = null
                            estimator.reset()
                            onEvent(
                                UpdateDownloadEvent.Progress(
                                    record,
                                    DownloadProgress(
                                        downloadedBytes = record.downloadedBytes,
                                        totalBytes = record.totalBytes,
                                    ),
                                ),
                            )
                        }
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            stopReason = "COMPLETED"
                            onEvent(UpdateDownloadEvent.Completed(record))
                            break
                        }
                        else -> {
                            stopReason = "DOWNLOAD_FAILED"
                            onEvent(UpdateDownloadEvent.Failed(record, record.reason))
                            break
                        }
                    }
                    delay(pollMillis)
                }
            } finally {
                val wasCancelled = !isActive || monitoredId != id
                if (monitoredId == id) {
                    monitoredId = null
                    job = null
                }
                val reason = stopReason ?: if (wasCancelled) "CANCELLED" else "UNEXPECTED"
                runCatching { onStopped?.invoke(id, reason) }
            }
        }
        return true
    }

    fun isMonitoring(id: Long): Boolean = job?.isActive == true && monitoredId == id

    fun cancel() {
        monitoredId = null
        job?.cancel()
        job = null
    }
}
