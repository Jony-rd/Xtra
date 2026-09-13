package com.github.andreyasadchy.xtra.ui.main

import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.repository.auth.AuthSessionMaintenanceState
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.sanitizeLiveNotificationTechnicalMessage
import kotlinx.coroutines.CancellationException
import androidx.core.content.edit

internal object LiveNotificationFallbackGate {

    private const val COALESCE_WINDOW_MS = 30_000L
    private val lock = Any()
    private var running = false
    private var lastCompletedElapsedMs = 0L

    fun tryAcquire(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Boolean = synchronized(lock) {
        if (running ||
            (lastCompletedElapsedMs > 0L &&
                nowElapsedMs - lastCompletedElapsedMs < COALESCE_WINDOW_MS)
        ) {
            false
        } else {
            running = true
            true
        }
    }

    fun release(success: Boolean, nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        synchronized(lock) {
            if (success) {
                lastCompletedElapsedMs = nowElapsedMs
            }
            running = false
        }
    }
}

class LiveNotificationWorker(
    private val context: android.content.Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    private val monitor = LiveNotificationMonitor(context)

    override suspend fun doWork(): Result {
        val startedAt = System.currentTimeMillis()
        val startElapsed = SystemClock.elapsedRealtime()
        val baselineOnly = inputData.getBoolean(INPUT_BASELINE_ONLY, false)

        try {
            if (shouldSkipLiveNotificationWorker(
                    LiveNotificationScheduler.hasHealthyRealtimeOwner(context),
            )
            ) {
                Log.d(TAG, "Skipping fallback reconciliation because a realtime owner is healthy")
                return Result.success()
            }
            if (!LiveNotificationFallbackGate.tryAcquire()) {
                Log.d(TAG, "Skipping fallback reconciliation because another fallback is active or recent")
                return Result.success()
            }
            var completed = false
            try {
                context.prefs().edit {
                    putLong(C.LIVE_NOTIFICATION_LAST_RUN, startedAt)
                }
                val authMaintainer = (context.applicationContext as? XtraApp)?.xtraModule?.authSessionMaintainer
                if (authMaintainer?.validateIfDue() == AuthSessionMaintenanceState.REAUTHORIZATION_REQUIRED) {
                    completed = true
                    return Result.success()
                }
                val result = monitor.poll(baselineOnly = baselineOnly)
                recordSuccess(startedAt, result.delivered, result.api)
                Log.d(TAG, "Live notification reconciliation completed in ${SystemClock.elapsedRealtime() - startElapsed}ms; delivered=${result.delivered}")
                completed = true
                return Result.success()
            } finally {
                LiveNotificationFallbackGate.release(success = completed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordFailure(startedAt, e)
            return Result.retry()
        }
    }

    private fun recordSuccess(startedAt: Long, delivered: Int, api: String) {
        context.prefs().edit {
            putLong(C.LIVE_NOTIFICATION_LAST_SUCCESS, System.currentTimeMillis())
            putString(C.LIVE_NOTIFICATION_LAST_API, api)
            putInt(C.LIVE_NOTIFICATION_LAST_EVENT_COUNT, delivered)
            remove(C.LIVE_NOTIFICATION_LAST_ERROR)
        }
        Log.d(TAG, "Live notification worker succeeded; elapsed=${System.currentTimeMillis() - startedAt}ms; delivered=$delivered")
    }

    private fun recordFailure(startedAt: Long, error: Exception) {
        val message = sanitizeLiveNotificationTechnicalMessage(
            "${error::class.simpleName}: ${error.message.orEmpty()}"
        ) ?: error::class.simpleName.orEmpty()
        context.prefs().edit {
            putLong(C.LIVE_NOTIFICATION_LAST_ERROR_AT, System.currentTimeMillis())
            putString(C.LIVE_NOTIFICATION_LAST_ERROR, message)
        }
        Log.w(TAG, "Live notification worker failed after ${System.currentTimeMillis() - startedAt}ms", error)
    }

    companion object {
        private const val TAG = "LiveNotificationWorker"
        const val INPUT_BASELINE_ONLY = "baseline_only"
    }
}
