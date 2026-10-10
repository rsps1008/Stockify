package com.rsps1008.stockify

import com.rsps1008.stockify.data.CloudRestoreSources
import com.rsps1008.stockify.data.GoogleDriveBackupBundle
import com.rsps1008.stockify.data.inspectCloudRestoreSources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class CloudRestoreSourcesTest {
    @Test
    fun emptySuccessfulQueriesMeanNoBackup() = runBlocking {
        assertEquals(CloudRestoreSources.Available(false, false), inspectCloudRestoreSources { Result.success(null) })
    }

    @Test
    fun legacyManualCsvAndAutomaticBackupAreRecognized() = runBlocking {
        assertEquals(CloudRestoreSources.Available(true, true), inspectCloudRestoreSources {
            Result.success(if (it == GoogleDriveBackupBundle.FILE_NAME) null else 100L)
        })
    }

    @Test
    fun failureAtAnySourceIsNotReportedAsMissingBackup() = runBlocking {
        for (failedFile in listOf(GoogleDriveBackupBundle.FILE_NAME, "stockify_backup.csv", AUTO_CLOUD_BACKUP_FILE_NAME)) {
            assertEquals(CloudRestoreSources.QueryFailed, inspectCloudRestoreSources {
                if (it == failedFile) Result.failure(IOException("network")) else Result.success(null)
            })
        }
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsPropagated() {
        runBlocking { inspectCloudRestoreSources { throw CancellationException() } }
    }
}
