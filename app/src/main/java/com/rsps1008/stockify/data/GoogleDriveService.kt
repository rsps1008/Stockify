package com.rsps1008.stockify.data

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

data class GoogleDriveBackupFile(
    val content: ByteArray,
    val modifiedAtMillis: Long
)

@Suppress("DEPRECATION")
class GoogleDriveService(context: Context, account: GoogleSignInAccount) {

    private val drive: Drive

    init {
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            setOf(DriveScopes.DRIVE_APPDATA)
        )
        credential.selectedAccount = account.account
        drive = Drive.Builder(
            com.google.api.client.extensions.android.http.AndroidHttp.newCompatibleTransport(),
            GsonFactory(),
            credential
        ).setApplicationName("Stockify").build()
    }

    suspend fun uploadBackup(
        fileName: String,
        content: ByteArray,
        mimeType: String = "text/csv"
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val fileMetadata = File().apply {
                name = fileName
            }
            val mediaContent = ByteArrayContent(mimeType, content)

            // Check for existing file in the appDataFolder
            val fileList = drive.files().list()
                .setQ("name='$fileName' and 'appDataFolder' in parents")
                .setSpaces("appDataFolder")
                .setFields("files(id, name)")
                .setOrderBy("modifiedTime desc")
                .execute()

            if (fileList.files.isEmpty()) {
                // No existing file, create a new one in appDataFolder
                fileMetadata.parents = listOf("appDataFolder")
                drive.files().create(fileMetadata, mediaContent).execute()
            } else {
                // File exists, update it
                val fileId = fileList.files.first().id
                drive.files().update(fileId, null, mediaContent).execute()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    suspend fun restoreBackup(fileName: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            // Find the file in the appDataFolder
            val fileList = drive.files().list()
                .setQ("name='$fileName' and 'appDataFolder' in parents")
                .setSpaces("appDataFolder")
                .setFields("files(id, name)")
                .setOrderBy("modifiedTime desc")
                .execute()

            if (fileList.files.isEmpty()) {
                Result.failure(Exception("Backup file not found on Google Drive."))
            } else {
                val fileId = fileList.files.first().id
                val outputStream = ByteArrayOutputStream()
                drive.files().get(fileId).executeMediaAndDownloadTo(outputStream)
                Result.success(outputStream.toByteArray())
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    suspend fun restoreBackupIfPresent(fileName: String): Result<ByteArray?> =
        restoreBackupWithModifiedTimeIfPresent(fileName).map { it?.content }

    suspend fun restoreBackupWithModifiedTimeIfPresent(
        fileName: String
    ): Result<GoogleDriveBackupFile?> = withContext(Dispatchers.IO) {
        try {
            val fileList = drive.files().list()
                .setQ("name='$fileName' and 'appDataFolder' in parents")
                .setSpaces("appDataFolder")
                .setFields("files(id, name, modifiedTime)")
                .setOrderBy("modifiedTime desc")
                .execute()

            val file = fileList.files.firstOrNull()
                ?: return@withContext Result.success(null)
            val outputStream = ByteArrayOutputStream()
            drive.files().get(file.id).executeMediaAndDownloadTo(outputStream)
            Result.success(
                GoogleDriveBackupFile(
                    content = outputStream.toByteArray(),
                    modifiedAtMillis = file.modifiedTime?.value ?: 0L
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    suspend fun getBackupModifiedTime(fileName: String): Result<Long?> = withContext(Dispatchers.IO) {
        try {
            val fileList = drive.files().list()
                .setQ("name='$fileName' and 'appDataFolder' in parents")
                .setSpaces("appDataFolder")
                .setFields("files(id, name, modifiedTime)")
                .setOrderBy("modifiedTime desc")
                .execute()

            Result.success(fileList.files.firstOrNull()?.modifiedTime?.value)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }
}
