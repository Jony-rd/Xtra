package com.github.andreyasadchy.xtra.util.updater

import android.app.DownloadManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import com.github.andreyasadchy.xtra.R
import java.text.DateFormat
import java.util.Date

data class UpdateNetworkDiagnostics(
    val active: Boolean?,
    val validated: Boolean?,
    val metered: Boolean?,
    val transports: String?,
)

data class UpdateDiagnosticsSnapshot(
    val state: String,
    val stage: String?,
    val installedVersion: String,
    val targetVersion: String?,
    val assetName: String?,
    val downloadedBytes: Long?,
    val totalBytes: Long?,
    val bytesPerSecond: Long?,
    val downloadManagerStatus: Int?,
    val downloadManagerReason: Int?,
    val lastSuccessfulCheck: Long?,
    val lastAttemptedCheck: Long?,
    val errorType: String?,
    val timestamp: Long,
    val downloadAttempt: UpdateDownloadAttempt? = null,
    val monitorActive: Boolean? = null,
    val foreground: Boolean? = null,
    val network: UpdateNetworkDiagnostics? = null,
    val availableStorageBytes: Long? = null,
)

object UpdateDiagnostics {
    fun snapshot(
        state: UpdateState,
        installedVersion: String,
        assetName: String?,
        lastSuccessfulCheck: Long?,
        lastAttemptedCheck: Long?,
        downloadRecord: UpdateDownloadRecord?,
        now: Long = System.currentTimeMillis(),
        downloadAttempt: UpdateDownloadAttempt? = null,
        monitorActive: Boolean? = null,
        foreground: Boolean? = null,
        network: UpdateNetworkDiagnostics? = null,
        availableStorageBytes: Long? = null,
    ): UpdateDiagnosticsSnapshot {
        val progress = (state as? UpdateState.Downloading)?.progress
        val release = when (state) {
            is UpdateState.Available -> state.release
            is UpdateState.Skipped -> state.release
            is UpdateState.Deferred -> state.release
            is UpdateState.Downloading -> state.release
            is UpdateState.Downloaded -> state.release
            is UpdateState.Installing -> state.release
            is UpdateState.AwaitingUserAction -> state.release
            is UpdateState.Error -> state.release
            is UpdateState.UpToDate -> state.release
            UpdateState.Idle, UpdateState.Checking -> null
        }
        val status = (state as? UpdateState.Downloading)?.downloadManagerStatus
            ?: downloadRecord?.status
        val reason = (state as? UpdateState.Downloading)?.downloadManagerReason
            ?: (state as? UpdateState.Error)?.downloadManagerReason
            ?: downloadRecord?.reason
        return UpdateDiagnosticsSnapshot(
            state = state::class.simpleName ?: "Unknown",
            stage = when (state) {
                UpdateState.Idle -> null
                UpdateState.Checking -> UpdateStage.CHECK.name
                is UpdateState.Available,
                is UpdateState.Skipped,
                is UpdateState.Deferred,
                is UpdateState.UpToDate,
                -> UpdateStage.CHECK.name
                is UpdateState.Downloading -> UpdateStage.DOWNLOAD.name
                is UpdateState.Downloaded -> "READY"
                is UpdateState.Installing,
                is UpdateState.AwaitingUserAction,
                -> if (state is UpdateState.Installing && state.sessionId == null) "VERIFYING" else UpdateStage.INSTALL.name
                is UpdateState.Error -> state.stage.name
            },
            installedVersion = installedVersion,
            targetVersion = release?.displayVersion,
            assetName = assetName,
            downloadedBytes = progress?.downloadedBytes ?: downloadRecord?.downloadedBytes,
            totalBytes = progress?.totalBytes ?: downloadRecord?.totalBytes,
            bytesPerSecond = progress?.bytesPerSecond,
            downloadManagerStatus = status,
            downloadManagerReason = reason,
            lastSuccessfulCheck = lastSuccessfulCheck,
            lastAttemptedCheck = lastAttemptedCheck,
            errorType = (state as? UpdateState.Error)?.cause?.let { it::class.simpleName },
            timestamp = now,
            downloadAttempt = downloadAttempt,
            monitorActive = monitorActive,
            foreground = foreground,
            network = network,
            availableStorageBytes = availableStorageBytes,
        )
    }

    fun format(context: Context, snapshot: UpdateDiagnosticsSnapshot): String = buildString {
        appendLine(context.getString(R.string.update_diagnostics))
        appendLine(context.getString(R.string.update_diagnostics_state, snapshot.state))
        snapshot.stage?.let { appendLine(context.getString(R.string.update_diagnostics_stage, it)) }
        appendLine(context.getString(R.string.update_diagnostics_installed, snapshot.installedVersion))
        snapshot.targetVersion?.let { appendLine(context.getString(R.string.update_diagnostics_target, it)) }
        snapshot.assetName?.let { appendLine(context.getString(R.string.update_diagnostics_asset, it)) }
        val downloaded = snapshot.downloadedBytes
        val total = snapshot.totalBytes
        if (downloaded != null) {
            val progress = total?.let {
                Formatter.formatFileSize(context, downloaded) + " / " + Formatter.formatFileSize(context, it)
            } ?: Formatter.formatFileSize(context, downloaded)
            appendLine(context.getString(R.string.update_diagnostics_progress, progress))
        }
        snapshot.bytesPerSecond?.takeIf { it > 0L }?.let {
            appendLine(
                context.getString(
                    R.string.update_diagnostics_speed,
                    Formatter.formatFileSize(context, it) + "/s",
                ),
            )
        }
        snapshot.downloadManagerStatus?.let {
            appendLine(context.getString(R.string.update_diagnostics_status, downloadStatusName(it)))
        }
        snapshot.downloadManagerReason
            ?.takeIf { hasMeaningfulDownloadReason(snapshot.downloadManagerStatus, it) }
            ?.let {
                appendLine(context.getString(R.string.update_diagnostics_reason, downloadReasonName(it), it))
            }
        snapshot.downloadAttempt?.let { attempt ->
            appendLine(context.getString(R.string.update_diagnostics_attempt, attempt.attemptId))
            attempt.downloadId?.let { appendLine(context.getString(R.string.update_diagnostics_download_id, it)) }
            appendLine(context.getString(R.string.update_diagnostics_endpoint, attempt.endpoint))
            appendLine(context.getString(R.string.update_diagnostics_attempt_created, exactTimestamp(attempt.createdAt)))
            attempt.enqueueAt?.let {
                appendLine(context.getString(R.string.update_diagnostics_enqueued, exactTimestamp(it)))
            }
            appendLine(
                context.getString(
                    R.string.update_diagnostics_attempt_progress,
                    Formatter.formatFileSize(context, attempt.downloadedBytes ?: 0L),
                    attempt.totalBytes?.let { Formatter.formatFileSize(context, it) }
                        ?: context.getString(R.string.update_diagnostics_unknown),
                    attempt.bytesPerSecond?.takeIf { it > 0L }?.let {
                        Formatter.formatFileSize(context, it) + "/s"
                    } ?: context.getString(R.string.update_diagnostics_unknown),
                ),
            )
            appendLine(
                context.getString(
                    R.string.update_diagnostics_monitor,
                    attempt.monitorState,
                    snapshot.monitorActive?.toString() ?: context.getString(R.string.update_diagnostics_unknown),
                ),
            )
            attempt.lastPollAt?.let {
                appendLine(
                    context.getString(
                        R.string.update_diagnostics_last_poll,
                        exactTimestamp(it),
                        formatAge(it, snapshot.timestamp),
                    ),
                )
            }
            attempt.firstByteAt?.let {
                appendLine(context.getString(R.string.update_diagnostics_first_byte, exactTimestamp(it)))
            }
            attempt.lastByteAt?.let {
                appendLine(context.getString(R.string.update_diagnostics_last_byte, exactTimestamp(it)))
            }
            attempt.lastStatus?.let {
                appendLine(context.getString(R.string.update_diagnostics_observed_status, downloadStatusName(it)))
            }
            attempt.lastStatusChangedAt?.let {
                appendLine(
                    context.getString(
                        R.string.update_diagnostics_status_age,
                        exactTimestamp(it),
                        formatAge(it, snapshot.timestamp),
                    ),
                )
            }
            attempt.lastReason
                ?.takeIf { hasMeaningfulDownloadReason(attempt.lastStatus, it) }
                ?.let {
                    appendLine(context.getString(R.string.update_diagnostics_observed_reason, downloadReasonName(it), it))
                }
            appendLine(context.getString(R.string.update_diagnostics_polls, attempt.pollCount))
            appendLine(context.getString(R.string.update_diagnostics_query_failures, attempt.queryFailureCount))
            appendLine(context.getString(R.string.update_diagnostics_process_recoveries, attempt.processRecoveryCount))
            attempt.lastMonitorStopReason?.let {
                appendLine(context.getString(R.string.update_diagnostics_monitor_stop, it))
            }
            attempt.lastErrorType?.let {
                appendLine(context.getString(R.string.update_diagnostics_query_error, it))
            }
            attempt.outcome?.let {
                appendLine(context.getString(R.string.update_diagnostics_outcome, it))
            }
            attempt.events.forEach { event ->
                appendLine(
                    context.getString(
                        R.string.update_diagnostics_event,
                        exactTimestamp(event.timestamp),
                        event.description,
                    ),
                )
            }
        }
        snapshot.network?.let { network ->
            appendLine(
                context.getString(
                    R.string.update_diagnostics_network,
                    network.active?.toString() ?: context.getString(R.string.update_diagnostics_unknown),
                    network.validated?.toString() ?: context.getString(R.string.update_diagnostics_unknown),
                    network.metered?.toString() ?: context.getString(R.string.update_diagnostics_unknown),
                    network.transports ?: context.getString(R.string.update_diagnostics_unknown),
                ),
            )
        }
        snapshot.availableStorageBytes?.let {
            appendLine(
                context.getString(
                    R.string.update_diagnostics_storage,
                    Formatter.formatFileSize(context, it),
                ),
            )
        }
        snapshot.foreground?.let {
            appendLine(context.getString(R.string.update_diagnostics_foreground, it))
        }
        appendLine(
            context.getString(
                R.string.update_diagnostics_last_check,
                formatTimestamp(context, snapshot.lastSuccessfulCheck, snapshot.timestamp),
            ),
        )
        appendLine(
            context.getString(
                R.string.update_diagnostics_last_attempt,
                formatTimestamp(context, snapshot.lastAttemptedCheck, snapshot.timestamp),
            ),
        )
        appendLine(
            context.getString(
                R.string.update_diagnostics_error,
                snapshot.errorType ?: context.getString(R.string.none),
            ),
        )
        appendLine(
            context.getString(
                R.string.update_diagnostics_timestamp,
                formatTimestamp(context, snapshot.timestamp, snapshot.timestamp),
            ),
        )
    }

    fun sanitizeEndpoint(raw: String): String = runCatching {
        val uri = Uri.parse(raw)
        buildString {
            uri.scheme?.let { append(it).append("://") }
            uri.host?.let(::append)
            uri.port.takeIf { it >= 0 }?.let { append(':').append(it) }
            uri.encodedPath?.takeIf { it.isNotBlank() }?.let(::append)
        }.takeIf { it.isNotBlank() } ?: "configured endpoint"
    }.getOrDefault("configured endpoint")

    fun network(context: Context): UpdateNetworkDiagnostics = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return@runCatching UpdateNetworkDiagnostics(null, null, null, null)
        val network = manager.activeNetwork
            ?: return@runCatching UpdateNetworkDiagnostics(false, false, null, null)
        val capabilities = manager.getNetworkCapabilities(network)
            ?: return@runCatching UpdateNetworkDiagnostics(true, null, null, null)
        val transports = buildList {
            if (capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) add("Wi-Fi")
            if (capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
            if (capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
            if (capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
            if (capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("Bluetooth")
        }.joinToString().ifBlank { null }
        UpdateNetworkDiagnostics(
            active = true,
            validated = capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            metered = !capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            transports = transports,
        )
    }.getOrElse { UpdateNetworkDiagnostics(null, null, null, null) }

    fun availableStorageBytes(context: Context): Long? = runCatching {
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return@runCatching null
        StatFs(directory.path).availableBytes
    }.getOrNull()

    internal fun hasMeaningfulDownloadReason(status: Int?, reason: Int): Boolean =
        reason != DownloadManager.ERROR_UNKNOWN &&
            status in setOf(DownloadManager.STATUS_PAUSED, DownloadManager.STATUS_FAILED)

    private fun downloadStatusName(status: Int): String = when (status) {
        DownloadManager.STATUS_PENDING -> "Pending"
        DownloadManager.STATUS_RUNNING -> "Running"
        DownloadManager.STATUS_PAUSED -> "Paused"
        DownloadManager.STATUS_SUCCESSFUL -> "Successful"
        DownloadManager.STATUS_FAILED -> "Failed"
        else -> "Unknown"
    }

    private fun downloadReasonName(reason: Int): String = when (reason) {
        DownloadManager.PAUSED_WAITING_TO_RETRY -> "PAUSED_WAITING_TO_RETRY"
        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "PAUSED_WAITING_FOR_NETWORK"
        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "PAUSED_QUEUED_FOR_WIFI"
        DownloadManager.PAUSED_UNKNOWN -> "PAUSED_UNKNOWN"
        DownloadManager.ERROR_FILE_ERROR -> "ERROR_FILE_ERROR"
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "ERROR_UNHANDLED_HTTP_CODE"
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "ERROR_HTTP_DATA_ERROR"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "ERROR_TOO_MANY_REDIRECTS"
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "ERROR_INSUFFICIENT_SPACE"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "ERROR_DEVICE_NOT_FOUND"
        DownloadManager.ERROR_CANNOT_RESUME -> "ERROR_CANNOT_RESUME"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "ERROR_FILE_ALREADY_EXISTS"
        else -> "UNKNOWN"
    }

    private fun formatTimestamp(context: Context, timestamp: Long?, now: Long): String =
        timestamp?.takeIf { it > 0L }?.let { UpdateTimeFormatter.format(context, it, now) }
            ?: context.getString(R.string.never)

    private fun exactTimestamp(timestamp: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(timestamp))

    private fun formatAge(timestamp: Long, now: Long): String =
        ((now - timestamp).coerceAtLeast(0L) / 1_000L).let { seconds ->
            when {
                seconds < 60L -> "${seconds}s ago"
                seconds < 3_600L -> "${seconds / 60L}m ago"
                else -> "${seconds / 3_600L}h ago"
            }
        }
}
