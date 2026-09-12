package com.github.andreyasadchy.xtra.util.updater

import android.app.DownloadManager
import android.content.ContextWrapper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDiagnosticsTest {
    @Test
    fun downloadReasonIsOnlyReportedForPausedOrFailedRecords() {
        assertFalse(
            UpdateDiagnostics.hasMeaningfulDownloadReason(
                DownloadManager.STATUS_PENDING,
                DownloadManager.PAUSED_WAITING_FOR_NETWORK,
            ),
        )
        assertFalse(
            UpdateDiagnostics.hasMeaningfulDownloadReason(
                DownloadManager.STATUS_RUNNING,
                DownloadManager.ERROR_HTTP_DATA_ERROR,
            ),
        )
        assertTrue(
            UpdateDiagnostics.hasMeaningfulDownloadReason(
                DownloadManager.STATUS_PAUSED,
                DownloadManager.PAUSED_WAITING_FOR_NETWORK,
            ),
        )
        assertTrue(
            UpdateDiagnostics.hasMeaningfulDownloadReason(
                DownloadManager.STATUS_FAILED,
                DownloadManager.ERROR_HTTP_DATA_ERROR,
            ),
        )
    }

    @Test
    fun unavailableExternalUpdateStorageDoesNotFallBackToInternalStorage() {
        assertNull(UpdateDiagnostics.availableStorageBytes(NoExternalStorageContext()))
    }

    private class NoExternalStorageContext : ContextWrapper(null) {
        override fun getExternalFilesDir(type: String?): java.io.File? = null
    }
}
