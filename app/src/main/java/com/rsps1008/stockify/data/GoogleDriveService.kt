package com.rsps1008.stockify.data

import android.accounts.Account
import android.content.Context
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.HttpResponseException
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

enum class GoogleDriveAuthState {
    NOT_SIGNED_IN,
    AUTHORIZED,
    NEEDS_REAUTHORIZATION,
    TEMPORARILY_UNAVAILABLE
}

object GoogleDriveAuthResolutionHelper {
    fun resolveAuthState(
        email: String?,
        hasResolution: Boolean,
        hasDriveScope: Boolean,
        isNetworkOrServiceError: Boolean = false
    ): GoogleDriveAuthState {
        if (email.isNullOrBlank()) {
            return GoogleDriveAuthState.NOT_SIGNED_IN
        }
        if (isNetworkOrServiceError) {
            return GoogleDriveAuthState.TEMPORARILY_UNAVAILABLE
        }
        if (hasResolution || !hasDriveScope) {
            return GoogleDriveAuthState.NEEDS_REAUTHORIZATION
        }
        return GoogleDriveAuthState.AUTHORIZED
    }

    fun resolveUploadFailureAuthState(throwable: Throwable): GoogleDriveAuthState? {
        val causes = generateSequence(throwable) { it.cause }.toList()

        // 1. Explicit user recoverable auth error
        if (causes.any { it.javaClass.name.contains("UserRecoverableAuthIOException") }) {
            return GoogleDriveAuthState.NEEDS_REAUTHORIZATION
        }

        // 2. GoogleJsonResponseException with status code and details
        val googleJsonException = causes.filterIsInstance<GoogleJsonResponseException>().firstOrNull()
        if (googleJsonException != null) {
            val statusCode = googleJsonException.statusCode
            if (statusCode == 401) {
                return GoogleDriveAuthState.NEEDS_REAUTHORIZATION
            }
            if (statusCode == 403) {
                val reasons = googleJsonException.details?.errors?.mapNotNull { it.reason } ?: emptyList()
                val message = googleJsonException.message ?: ""

                val isAuthReason = reasons.any { reason ->
                    reason.equals("authError", ignoreCase = true) ||
                        reason.equals("insufficientPermissions", ignoreCase = true)
                } || (reasons.isEmpty() && (
                    message.contains("authError", ignoreCase = true) ||
                        message.contains("insufficientPermissions", ignoreCase = true)
                ))
                if (isAuthReason) {
                    return GoogleDriveAuthState.NEEDS_REAUTHORIZATION
                }

                val isRateLimit = reasons.any { reason ->
                    reason.equals("rateLimitExceeded", ignoreCase = true) ||
                        reason.equals("userRateLimitExceeded", ignoreCase = true) ||
                        reason.equals("dailyLimitExceeded", ignoreCase = true) ||
                        reason.equals("quotaExceeded", ignoreCase = true)
                } || (reasons.isEmpty() && (
                    message.contains("rateLimitExceeded", ignoreCase = true) ||
                        message.contains("userRateLimitExceeded", ignoreCase = true) ||
                        message.contains("quotaExceeded", ignoreCase = true)
                ))
                if (isRateLimit) {
                    return GoogleDriveAuthState.TEMPORARILY_UNAVAILABLE
                }

                val isStorageQuota = reasons.any { reason ->
                    reason.equals("storageQuotaExceeded", ignoreCase = true)
                } || (reasons.isEmpty() && message.contains("storageQuotaExceeded", ignoreCase = true))
                if (isStorageQuota) {
                    return null
                }

                return null
            }
            if (statusCode in 500..599) {
                return GoogleDriveAuthState.TEMPORARILY_UNAVAILABLE
            }
            return null
        }

        // 3. HttpResponseException without GoogleJson details
        val httpException = causes.filterIsInstance<HttpResponseException>().firstOrNull()
        if (httpException != null) {
            val statusCode = httpException.statusCode
            if (statusCode == 401) {
                return GoogleDriveAuthState.NEEDS_REAUTHORIZATION
            }
            if (statusCode == 403) {
                val message = httpException.message ?: ""
                if (message.contains("authError", ignoreCase = true) ||
                    message.contains("insufficientPermissions", ignoreCase = true)
                ) {
                    return GoogleDriveAuthState.NEEDS_REAUTHORIZATION
                }
                if (message.contains("rateLimitExceeded", ignoreCase = true) ||
                    message.contains("userRateLimitExceeded", ignoreCase = true) ||
                    message.contains("quotaExceeded", ignoreCase = true)
                ) {
                    return GoogleDriveAuthState.TEMPORARILY_UNAVAILABLE
                }
                if (message.contains("storageQuotaExceeded", ignoreCase = true)) {
                    return null
                }
                return null
            }
            if (statusCode in 500..599) {
                return GoogleDriveAuthState.TEMPORARILY_UNAVAILABLE
            }
            return null
        }

        // 4. Any remaining network or I/O failure
        if (causes.any { it is IOException }) {
            return GoogleDriveAuthState.TEMPORARILY_UNAVAILABLE
        }
        return null
    }

    fun isAuthVersionMatching(
        currentEmail: String?,
        currentVersion: Long,
        expectedEmail: String?,
        expectedVersion: Long,
        attemptTime: Long = 0L,
        lastValidationTime: Long = 0L
    ): Boolean {
        if (currentEmail != expectedEmail || currentVersion != expectedVersion) {
            return false
        }
        if (attemptTime > 0L && lastValidationTime > 0L && attemptTime < lastValidationTime) {
            return false
        }
        return true
    }
}

data class GoogleDriveAccount(
    val email: String,
    val displayName: String? = null
) {
    val account: Account
        get() = Account(email, "com.google")

    companion object {
        private const val PREFS_NAME = "com.google.android.gms.signin"
        private const val KEY_DEFAULT_ACCOUNT = "defaultGoogleSignInAccount"
        private const val PREFIX_ACCOUNT = "googleSignInAccount:"

        fun fromLegacyStorage(context: Context): GoogleDriveAccount? {
            try {
                @Suppress("DEPRECATION")
                val legacyGsa = com.google.android.gms.auth.api.signin.internal.Storage.getInstance(context).savedDefaultGoogleSignInAccount
                val gsaEmail = legacyGsa?.email
                if (!gsaEmail.isNullOrBlank()) {
                    return GoogleDriveAccount(gsaEmail, legacyGsa.displayName)
                }
            } catch (_: Throwable) {
            }
            return try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val defaultAccount = prefs.getString(KEY_DEFAULT_ACCOUNT, null) ?: return null
                val accountJson = prefs.getString("$PREFIX_ACCOUNT$defaultAccount", null) ?: return null
                val json = org.json.JSONObject(accountJson)
                val email = json.optString("email").takeIf { it.isNotBlank() } ?: return null
                val displayName = json.optString("displayName").takeIf { it.isNotBlank() }
                GoogleDriveAccount(email, displayName)
            } catch (_: Exception) {
                null
            }
        }

        fun clearLegacyStorage(context: Context) {
            try {
                com.google.android.gms.auth.api.signin.internal.Storage.getInstance(context).clear()
            } catch (_: Throwable) {
            }
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val defaultAccount = prefs.getString(KEY_DEFAULT_ACCOUNT, null)
                prefs.edit().apply {
                    remove(KEY_DEFAULT_ACCOUNT)
                    if (!defaultAccount.isNullOrBlank()) {
                        remove("$PREFIX_ACCOUNT$defaultAccount")
                    }
                    apply()
                }
            } catch (_: Exception) {
                // Ignore failure to clear legacy preferences
            }
        }
    }
}

data class GoogleDriveBackupFile(
    val content: ByteArray,
    val modifiedAtMillis: Long
)

internal fun queryGoogleDriveBackupModifiedTime(drive: Drive, fileName: String): Long? {
    val fileList = drive.files().list()
        .setQ("name='$fileName' and 'appDataFolder' in parents and trashed = false")
        .setSpaces("appDataFolder")
        .setFields("files(id, name, modifiedTime)")
        .setOrderBy("modifiedTime desc")
        .execute()
    val file = fileList.files.orEmpty().firstOrNull() ?: return null
    return file.modifiedTime?.value?.takeIf { it > 0L }
        ?: error("Google Drive 未回傳備份檔案的修改時間")
}

class GoogleDriveService(context: Context, val account: Account) {

    constructor(context: Context, email: String) : this(context, Account(email, "com.google"))
    constructor(context: Context, googleAccount: GoogleDriveAccount) : this(context, googleAccount.account)

    private val drive: Drive

    init {
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            setOf(DriveScopes.DRIVE_APPDATA)
        )
        credential.selectedAccount = account
        drive = Drive.Builder(
            com.google.api.client.http.javanet.NetHttpTransport(),
            GsonFactory(),
            credential
        ).setApplicationName("Stockify").build()
    }

    suspend fun uploadBackup(
        fileName: String,
        content: ByteArray,
        mimeType: String = "text/csv"
    ): Result<Unit> = withContext(Dispatchers.IO) {
        CloudBackupUploadLocks.withFileLock(account.name, fileName) {
            try {
                val fileMetadata = File().apply {
                    name = fileName
                }
                val mediaContent = ByteArrayContent(mimeType, content)

                // Check for existing file in the appDataFolder
                val fileList = drive.files().list()
                    .setQ("name='$fileName' and 'appDataFolder' in parents and trashed = false")
                    .setSpaces("appDataFolder")
                    .setFields("files(id, name)")
                    .setOrderBy("modifiedTime desc")
                    .execute()

                if (fileList.files.orEmpty().isEmpty()) {
                    // No existing file, create a new one in appDataFolder
                    fileMetadata.parents = listOf("appDataFolder")
                    drive.files().create(fileMetadata, mediaContent).execute()
                } else {
                    // File exists, update it
                    val fileId = fileList.files.first().id
                    drive.files().update(fileId, null, mediaContent).execute()
                }
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                Result.failure(e)
            }
        }
    }

    suspend fun deleteAllAppDataFiles(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val filesToDelete = mutableListOf<File>()
            val visitedFolders = mutableSetOf<String>()

            suspend fun collectFiles(parentId: String) {
                if (!visitedFolders.add(parentId)) return
                var pageToken: String? = null
                do {
                    val page = drive.files().list()
                        .setQ("'$parentId' in parents and trashed = false")
                        .setSpaces("appDataFolder")
                        .setFields("nextPageToken, files(id, name, mimeType)")
                        .setPageSize(1000)
                        .setPageToken(pageToken)
                        .execute()
                    val children = page.files.orEmpty().filter { !it.id.isNullOrBlank() }
                    children.filter { it.mimeType == FOLDER_MIME_TYPE }
                        .forEach { child -> child.id?.let { collectFiles(it) } }
                    filesToDelete += children
                    pageToken = page.nextPageToken
                } while (pageToken != null)
            }

            collectFiles(APP_DATA_FOLDER_ID)
            var deletedCount = 0
            try {
                filesToDelete.forEach { file ->
                    drive.files().delete(file.id).execute()
                    deletedCount++
                }
            } catch (e: Exception) {
                throw IOException("已刪除 $deletedCount 個雲端項目，後續刪除失敗：${e.message}", e)
            }
            Result.success(deletedCount)
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

    suspend fun restoreAssetBalances(): AssetBalances = withContext(Dispatchers.IO) {
        val files = drive.files().list()
            .setQ("name='${AssetBalanceBackupCodec.FILE_NAME}' and 'appDataFolder' in parents and trashed = false")
            .setSpaces("appDataFolder")
            .setFields("files(id)")
            .setOrderBy("modifiedTime desc")
            .execute()
        val file = files.files.firstOrNull() ?: error("Google Drive 尚無存款與貸款備份")
        drive.files().get(file.id).executeMediaAsInputStream().use(AssetBalanceBackupCodec::read)
    }

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
            Result.success(queryGoogleDriveBackupModifiedTime(drive, fileName))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    private companion object {
        const val APP_DATA_FOLDER_ID = "appDataFolder"
        const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
    }
}
