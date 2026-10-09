package com.rsps1008.stockify.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rsps1008.stockify.StockifyApplication

const val AUTOMATIC_CLOUD_BACKUP_WORK_NAME = "automatic-google-drive-backup"

class AutomaticCloudBackupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val application = applicationContext as? StockifyApplication ?: return Result.failure()
        return if (application.performAutomaticCloudBackupIfDue()) Result.success() else Result.failure()
    }
}
