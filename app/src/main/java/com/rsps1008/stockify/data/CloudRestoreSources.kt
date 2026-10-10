package com.rsps1008.stockify.data

import com.rsps1008.stockify.AUTO_CLOUD_BACKUP_FILE_NAME
import kotlinx.coroutines.CancellationException

sealed interface CloudRestoreSources {
    data object Loading : CloudRestoreSources
    data class Available(val manual: Boolean, val automatic: Boolean) : CloudRestoreSources
    data object QueryFailed : CloudRestoreSources
}

internal suspend fun inspectCloudRestoreSources(
    query: suspend (String) -> Result<Long?>
): CloudRestoreSources = try {
    val manualBundle = query(GoogleDriveBackupBundle.FILE_NAME).getOrThrow()
    val manualCsv = query("stockify_backup.csv").getOrThrow()
    val autoBundle = query(AUTO_CLOUD_BACKUP_FILE_NAME).getOrThrow()
    CloudRestoreSources.Available(manualBundle != null || manualCsv != null, autoBundle != null)
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    CloudRestoreSources.QueryFailed
}
