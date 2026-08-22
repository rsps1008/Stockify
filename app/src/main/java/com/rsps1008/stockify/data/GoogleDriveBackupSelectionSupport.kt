package com.rsps1008.stockify.data

object GoogleDriveBackupSelectionSupport {
    fun selectHoldingsOrder(
        bundleFile: GoogleDriveBackupFile?,
        restoredBundle: GoogleDriveBackupRestored?,
        legacyOrderFile: GoogleDriveBackupFile?
    ): ByteArray? {
        val legacyIsNewer = legacyOrderFile != null &&
            (bundleFile == null || legacyOrderFile.modifiedAtMillis > bundleFile.modifiedAtMillis)
        return if (legacyIsNewer) {
            legacyOrderFile?.content
        } else {
            restoredBundle?.holdingsOrderJson ?: legacyOrderFile?.content
        }
    }
}
