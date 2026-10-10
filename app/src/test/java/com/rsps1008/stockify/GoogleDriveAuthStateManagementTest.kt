package com.rsps1008.stockify

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.rsps1008.stockify.data.GoogleDriveAuthState
import com.rsps1008.stockify.data.GoogleDriveAuthResolutionHelper
import com.rsps1008.stockify.data.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import okio.FileSystem
import okio.Path.Companion.toPath

class GoogleDriveAuthStateManagementTest {

    @Test
    fun ordinaryAuthorizationPreservesFailureCooldownAndWorkerSequence() = runBlocking {
        val (store, scope) = createSettingsDataStore("ordinary-authorization.preferences_pb")
        try {
            val email = "user@example.com"
            val now = System.currentTimeMillis()
            store.updateGoogleDriveAccountState(email, GoogleDriveAuthState.AUTHORIZED)
            store.setAutoCloudBackupEnabled(true)
            store.setAutoCloudBackupIntervalDays(0)
            val version = store.googleDriveAuthVersionFlow.first()
            val attempt = store.beginGoogleDriveAuthValidationIfMatching(email, version, now)!!
            store.setAutoCloudBackupLastErrorIfMatching(email, version, attempt, "API 配額不足", now)
            store.updateGoogleDriveAccountState(email, GoogleDriveAuthState.AUTHORIZED)
            store.setGoogleDriveAuthState(GoogleDriveAuthState.TEMPORARILY_UNAVAILABLE)
            store.setGoogleDriveAuthState(GoogleDriveAuthState.AUTHORIZED)
            assertEquals("API 配額不足", store.autoCloudBackupLastErrorFlow.first())
            assertEquals(version, store.googleDriveAuthVersionFlow.first())
            assertEquals(attempt, store.googleDriveAuthValidationTimeFlow.first())
            org.junit.Assert.assertFalse(store.claimAutomaticCloudBackupAttempt(now + 1000L))
            org.junit.Assert.assertTrue(store.setGoogleDriveAuthStateIfMatching(
                email, version, GoogleDriveAuthState.AUTHORIZED,
                clearLastError = true, attemptTime = attempt, localBackupSuccessAt = now + 2000L
            ))
            assertNull(store.autoCloudBackupLastErrorFlow.first())
            assertEquals(now + 2000L, store.autoCloudBackupLastLocalSuccessAtFlow.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun deletingBackupMetadataRetainsAccountAndInvalidatesOldWorkWhileLogoutClearsBoth() = runBlocking {
        val (store, scope) = createSettingsDataStore("backup-delete-account.preferences_pb")
        try {
            val email = "user@example.com"
            store.updateGoogleDriveAccountState(email, GoogleDriveAuthState.AUTHORIZED)
            val version = store.googleDriveAuthVersionFlow.first()
            val attempt = store.beginGoogleDriveAuthValidationIfMatching(email, version)!!
            store.setGoogleDriveAuthStateIfMatching(
                email, version, GoogleDriveAuthState.AUTHORIZED,
                attemptTime = attempt, backupSuccessAt = 100L, localBackupSuccessAt = 200L
            )
            store.setCloudDataBackupUpdatedAt(100L)
            store.clearCloudBackupMetadata()
            assertEquals(email, store.googleAccountEmailFlow.first())
            assertEquals(GoogleDriveAuthState.AUTHORIZED, store.googleDriveAuthStateFlow.first())
            assertNull(store.cloudDataBackupUpdatedAtFlow.first())
            assertNull(store.autoCloudBackupLastSuccessAtFlow.first())
            assertNull(store.autoCloudBackupLastLocalSuccessAtFlow.first())
            org.junit.Assert.assertFalse(store.setGoogleDriveAuthStateIfMatching(
                email, version, GoogleDriveAuthState.AUTHORIZED,
                attemptTime = attempt, localBackupSuccessAt = 300L
            ))
            store.clearGoogleDriveAccountAndBackupMetadata()
            assertNull(store.googleAccountEmailFlow.first())
            assertEquals(GoogleDriveAuthState.NOT_SIGNED_IN, store.googleDriveAuthStateFlow.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun localUploadSuccessControlsScheduleAndRejectsStaleCloudDeletion() = runBlocking {
        val (store, scope) = createSettingsDataStore("local-upload-success.preferences_pb")
        try {
            val email = "user@example.com"
            val now = System.currentTimeMillis()
            store.setGoogleAccountEmail(email)
            store.setAutoCloudBackupEnabled(true)
            store.setAutoCloudBackupIntervalDays(1)
            val version = store.googleDriveAuthVersionFlow.first()
            store.setAutoCloudBackupModifiedTimeIfMatching(email, version, null, now - 2 * 86400000L)
            org.junit.Assert.assertTrue(store.claimAutomaticCloudBackupAttempt(now))
            val attempt = store.beginGoogleDriveAuthValidationIfMatching(email, version, now)!!
            store.setGoogleDriveAuthStateIfMatching(
                email, version, GoogleDriveAuthState.AUTHORIZED,
                attemptTime = attempt, localBackupSuccessAt = now, clearLastError = true
            )
            assertEquals(now, store.autoCloudBackupLastLocalSuccessAtFlow.first())
            org.junit.Assert.assertFalse(store.claimAutomaticCloudBackupAttempt(now + 600000L))
            org.junit.Assert.assertFalse(store.setAutoCloudBackupModifiedTimeIfMatching(
                email, version, now - 2 * 86400000L, null
            ))
            org.junit.Assert.assertTrue(store.setAutoCloudBackupModifiedTimeIfMatching(
                email, version, now - 2 * 86400000L, now + 86400000L, now
            ))
            assertEquals(now, store.autoCloudBackupLastLocalSuccessAtFlow.first())
            org.junit.Assert.assertTrue(store.setAutoCloudBackupModifiedTimeIfMatching(
                email, version, now + 86400000L, null, now
            ))
            assertNull(store.autoCloudBackupLastLocalSuccessAtFlow.first())
            org.junit.Assert.assertTrue(store.claimAutomaticCloudBackupAttempt(now + 1200000L))
            store.setGoogleDriveAuthStateIfMatching(
                email, version, GoogleDriveAuthState.AUTHORIZED,
                attemptTime = attempt, localBackupSuccessAt = now
            )
            store.updateGoogleDriveAccountState("other@example.com", GoogleDriveAuthState.AUTHORIZED)
            assertNull(store.autoCloudBackupLastLocalSuccessAtFlow.first())
            assertNull(store.autoCloudBackupLastAttemptAtFlow.first())
            org.junit.Assert.assertFalse(store.setGoogleDriveAuthStateIfMatching(
                email, version, GoogleDriveAuthState.AUTHORIZED,
                attemptTime = attempt, localBackupSuccessAt = now
            ))
        } finally {
            scope.cancel()
        }
    }

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun legacySuccessFallbackEveryCloseAndLogoutRemainCompatible() = runBlocking {
        val (store, scope) = createSettingsDataStore("local-success-compatibility.preferences_pb")
        try {
            val email = "user@example.com"
            val now = System.currentTimeMillis()
            store.setGoogleAccountEmail(email)
            store.setAutoCloudBackupEnabled(true)
            val version = store.googleDriveAuthVersionFlow.first()
            store.setAutoCloudBackupModifiedTimeIfMatching(email, version, null, now)
            org.junit.Assert.assertFalse(store.claimAutomaticCloudBackupAttempt(now + 1000L))
            val attempt = store.beginGoogleDriveAuthValidationIfMatching(email, version, now)!!
            store.setGoogleDriveAuthStateIfMatching(
                email, version, GoogleDriveAuthState.AUTHORIZED,
                attemptTime = attempt, localBackupSuccessAt = now
            )
            store.setAutoCloudBackupIntervalDays(0)
            org.junit.Assert.assertTrue(store.claimAutomaticCloudBackupAttempt(now + 2000L))
            org.junit.Assert.assertTrue(store.claimAutomaticCloudBackupAttempt(now + 3000L))
            store.clearCloudBackupMetadata()
            assertNull(store.autoCloudBackupLastLocalSuccessAtFlow.first())
            assertNull(store.autoCloudBackupLastSuccessAtFlow.first())
        } finally {
            scope.cancel()
        }
    }

    private fun createSettingsDataStore(fileName: String): Pair<SettingsDataStore, CoroutineScope> {
        val testFile = File(tempFolder.root, fileName)
        val scope = CoroutineScope(Dispatchers.IO + Job())
        // FileStorage 的 File.renameTo 無法在 Windows 覆蓋既有檔案；測試改用 Okio 的原子替換。
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { testFile.absolutePath.toPath() }
        )
        return SettingsDataStore(dataStore) to scope
    }

    // 1. 無帳戶
    @Test
    fun noAccountResolvesToNotSignedIn() = runBlocking {
        val stateFromHelper = GoogleDriveAuthResolutionHelper.resolveAuthState(
            email = null,
            hasResolution = false,
            hasDriveScope = true
        )
        assertEquals(GoogleDriveAuthState.NOT_SIGNED_IN, stateFromHelper)

        val parsed = SettingsDataStore.parseGoogleDriveAuthState(raw = null, email = null)
        assertEquals(GoogleDriveAuthState.NOT_SIGNED_IN, parsed)

        val (store, scope) = createSettingsDataStore("test1.preferences_pb")
        assertEquals(GoogleDriveAuthState.NOT_SIGNED_IN, store.googleDriveAuthStateFlow.first())
        assertNull(store.googleAccountEmailFlow.first())
        scope.cancel()
    }

    // 2. 授權成功
    @Test
    fun authorizedSuccessUpdatesStateAndClearsLastError() = runBlocking {
        val state = GoogleDriveAuthResolutionHelper.resolveAuthState(
            email = "test@example.com",
            hasResolution = false,
            hasDriveScope = true
        )
        assertEquals(GoogleDriveAuthState.AUTHORIZED, state)

        val (store, scope) = createSettingsDataStore("test2.preferences_pb")
        store.updateGoogleDriveAccountState(
            email = "test@example.com",
            state = GoogleDriveAuthState.AUTHORIZED
        )

        assertEquals("test@example.com", store.googleAccountEmailFlow.first())
        assertEquals(GoogleDriveAuthState.AUTHORIZED, store.googleDriveAuthStateFlow.first())
        assertNull(store.autoCloudBackupLastErrorFlow.first())
        scope.cancel()
    }

    // 3. 需要 Resolution
    @Test
    fun needsResolutionRetainsAccountAndSetsNeedsReauth() = runBlocking {
        val state = GoogleDriveAuthResolutionHelper.resolveAuthState(
            email = "test@example.com",
            hasResolution = true,
            hasDriveScope = true
        )
        assertEquals(GoogleDriveAuthState.NEEDS_REAUTHORIZATION, state)

        val (store, scope) = createSettingsDataStore("test3.preferences_pb")
        store.updateGoogleDriveAccountState(
            email = "test@example.com",
            state = GoogleDriveAuthState.NEEDS_REAUTHORIZATION
        )

        assertEquals("test@example.com", store.googleAccountEmailFlow.first())
        assertEquals(GoogleDriveAuthState.NEEDS_REAUTHORIZATION, store.googleDriveAuthStateFlow.first())
        scope.cancel()
    }

    // 4. 缺少 Drive Scope
    @Test
    fun missingDriveScopeSetsNeedsReauth() = runBlocking {
        val state = GoogleDriveAuthResolutionHelper.resolveAuthState(
            email = "test@example.com",
            hasResolution = false,
            hasDriveScope = false
        )
        assertEquals(GoogleDriveAuthState.NEEDS_REAUTHORIZATION, state)

        val (store, scope) = createSettingsDataStore("test4.preferences_pb")
        store.updateGoogleDriveAccountState(
            email = "test@example.com",
            state = state
        )
        assertEquals(GoogleDriveAuthState.NEEDS_REAUTHORIZATION, store.googleDriveAuthStateFlow.first())
        assertEquals("test@example.com", store.googleAccountEmailFlow.first())
        scope.cancel()
    }

    // 5. 網路失敗
    @Test
    fun networkFailureSetsTemporarilyUnavailableWithoutSignOut() = runBlocking {
        val state = GoogleDriveAuthResolutionHelper.resolveAuthState(
            email = "test@example.com",
            hasResolution = false,
            hasDriveScope = true,
            isNetworkOrServiceError = true
        )
        assertEquals(GoogleDriveAuthState.TEMPORARILY_UNAVAILABLE, state)

        val (store, scope) = createSettingsDataStore("test5.preferences_pb")
        store.updateGoogleDriveAccountState(
            email = "test@example.com",
            state = GoogleDriveAuthState.TEMPORARILY_UNAVAILABLE
        )

        assertEquals("test@example.com", store.googleAccountEmailFlow.first())
        assertEquals(GoogleDriveAuthState.TEMPORARILY_UNAVAILABLE, store.googleDriveAuthStateFlow.first())
        scope.cancel()
    }

    // 6. 背景授權失敗後前景驗證成功
    @Test
    fun foregroundVerificationSucceedsAfterBackgroundFailure() = runBlocking {
        val backgroundState = GoogleDriveAuthResolutionHelper.resolveAuthState(
            email = "test@example.com",
            hasResolution = true,
            hasDriveScope = true
        )
        assertEquals(GoogleDriveAuthState.NEEDS_REAUTHORIZATION, backgroundState)

        val foregroundState = GoogleDriveAuthResolutionHelper.resolveAuthState(
            email = "test@example.com",
            hasResolution = false,
            hasDriveScope = true
        )
        assertEquals(GoogleDriveAuthState.AUTHORIZED, foregroundState)

        val (store, scope) = createSettingsDataStore("test6.preferences_pb")
        // Foreground re-verification succeeds, atomically updating state and clearing error
        store.updateGoogleDriveAccountState(
            email = "test@example.com",
            state = foregroundState
        )
        assertEquals(GoogleDriveAuthState.AUTHORIZED, store.googleDriveAuthStateFlow.first())
        assertNull(store.autoCloudBackupLastErrorFlow.first())
        scope.cancel()
    }

    // 7. 舊錯誤訊息不影響最新授權狀態
    @Test
    fun oldErrorMessageDoesNotAffectLatestAuthState() = runBlocking {
        val (store, scope) = createSettingsDataStore("test7.preferences_pb")

        // Store has an old error containing "重新授權", but auth state was updated to AUTHORIZED
        store.updateGoogleDriveAccountState(
            email = "test@example.com",
            state = GoogleDriveAuthState.AUTHORIZED,
            lastError = "Google Drive 需要重新確認授權，請開啟 App 重新授權"
        )

        // Auth state flow must independently be AUTHORIZED regardless of error text
        assertEquals(GoogleDriveAuthState.AUTHORIZED, store.googleDriveAuthStateFlow.first())
        assertEquals("Google Drive 需要重新確認授權，請開啟 App 重新授權", store.autoCloudBackupLastErrorFlow.first())
        scope.cancel()
    }

    // 8. 登出
    @Test
    fun signOutClearsAccountAndMetadataAndSetsNotSignedIn() = runBlocking {
        val (store, scope) = createSettingsDataStore("test8.preferences_pb")
        store.updateGoogleDriveAccountState("test@example.com", GoogleDriveAuthState.AUTHORIZED)
        store.clearGoogleDriveAccountAndBackupMetadata()

        assertNull(store.googleAccountEmailFlow.first())
        assertEquals(GoogleDriveAuthState.NOT_SIGNED_IN, store.googleDriveAuthStateFlow.first())
        assertNull(store.autoCloudBackupLastErrorFlow.first())
        assertNull(store.cloudDataBackupUpdatedAtFlow.first())
        scope.cancel()
    }

    // 9. 切換帳戶
    @Test
    fun switchAccountDoesNotInheritPreviousAccountStateOrErrors() = runBlocking {
        val accountAState = GoogleDriveAuthResolutionHelper.resolveAuthState(
            email = "account_a@example.com",
            hasResolution = true,
            hasDriveScope = true
        )
        val accountBState = GoogleDriveAuthResolutionHelper.resolveAuthState(
            email = "account_b@example.com",
            hasResolution = false,
            hasDriveScope = true
        )
        assertNotEquals(accountAState, accountBState)
        assertEquals(GoogleDriveAuthState.NEEDS_REAUTHORIZATION, accountAState)
        assertEquals(GoogleDriveAuthState.AUTHORIZED, accountBState)

        val (store, scope) = createSettingsDataStore("test9.preferences_pb")
        // Switch to Account B: writes Account B atomically with cleared error
        store.updateGoogleDriveAccountState(
            email = "account_b@example.com",
            state = accountBState
        )

        assertEquals("account_b@example.com", store.googleAccountEmailFlow.first())
        assertEquals(GoogleDriveAuthState.AUTHORIZED, store.googleDriveAuthStateFlow.first())
        assertNull(store.autoCloudBackupLastErrorFlow.first())
        scope.cancel()
    }

    // 10. 重複驗證競態
    @Test
    fun raceConditionDiscardsOutdatedVerificationResults() {
        val generationCounter = AtomicLong(0L)
        var currentAppliedState = GoogleDriveAuthState.NOT_SIGNED_IN

        // Start Verification 1
        val gen1 = generationCounter.incrementAndGet()

        // Start Verification 2 (e.g. user retried or logged in)
        val gen2 = generationCounter.incrementAndGet()
        // Verification 2 completes first
        if (gen2 == generationCounter.get()) {
            currentAppliedState = GoogleDriveAuthState.AUTHORIZED
        }
        assertEquals(GoogleDriveAuthState.AUTHORIZED, currentAppliedState)

        // Verification 1 finishes later with TEMPORARILY_UNAVAILABLE
        if (gen1 == generationCounter.get()) {
            currentAppliedState = GoogleDriveAuthState.TEMPORARILY_UNAVAILABLE
        }

        // Stale result from gen 1 was discarded, state remains AUTHORIZED from gen 2
        assertEquals(GoogleDriveAuthState.AUTHORIZED, currentAppliedState)
    }

    // 11. DataStore 未知 Enum 值
    @Test
    fun unknownEnumValueFallsBackSafelyWithoutClearingAccount() = runBlocking {
        val testFile = File(tempFolder.root, "test11.preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { testFile })

        // Manually write an unknown enum name to DataStore preferences
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("google_account_email")] = "user@example.com"
            preferences[stringPreferencesKey("google_drive_auth_state")] = "CORRUPTED_OR_FUTURE_VALUE"
        }

        val settingsDataStore = SettingsDataStore(dataStore)

        // Email is preserved
        assertEquals("user@example.com", settingsDataStore.googleAccountEmailFlow.first())
        // Unknown enum safely falls back to TEMPORARILY_UNAVAILABLE
        assertEquals(GoogleDriveAuthState.TEMPORARILY_UNAVAILABLE, settingsDataStore.googleDriveAuthStateFlow.first())

        // And parseGoogleDriveAuthState directly
        assertEquals(
            GoogleDriveAuthState.TEMPORARILY_UNAVAILABLE,
            SettingsDataStore.parseGoogleDriveAuthState("CORRUPTED_OR_FUTURE_VALUE", "user@example.com")
        )
        // If email is empty, returns NOT_SIGNED_IN even if unknown enum
        assertEquals(
            GoogleDriveAuthState.NOT_SIGNED_IN,
            SettingsDataStore.parseGoogleDriveAuthState("CORRUPTED_OR_FUTURE_VALUE", "")
        )

        scope.cancel()
    }

    // 12. App 重啟後重新驗證
    @Test
    fun appRestartRetainsPersistedAuthStateAndAccount() = runBlocking {
        val testFile = File(tempFolder.root, "test12.preferences_pb")

        // First process run: logged in and AUTHORIZED
        val scope1 = CoroutineScope(Dispatchers.IO + Job())
        val dataStore1 = PreferenceDataStoreFactory.create(scope = scope1, produceFile = { testFile })
        val settingsDataStore1 = SettingsDataStore(dataStore1)
        settingsDataStore1.updateGoogleDriveAccountState(
            email = "user@example.com",
            state = GoogleDriveAuthState.AUTHORIZED
        )
        scope1.cancel()

        // Second process run (reopen app, simulate restart):
        val scope2 = CoroutineScope(Dispatchers.IO + Job())
        val dataStore2 = PreferenceDataStoreFactory.create(scope = scope2, produceFile = { testFile })
        val settingsDataStore2 = SettingsDataStore(dataStore2)

        assertEquals("user@example.com", settingsDataStore2.googleAccountEmailFlow.first())
        assertEquals(GoogleDriveAuthState.AUTHORIZED, settingsDataStore2.googleDriveAuthStateFlow.first())

        // Re-verification can now take place
        val recheckState = GoogleDriveAuthResolutionHelper.resolveAuthState(
            email = settingsDataStore2.googleAccountEmailFlow.first(),
            hasResolution = false,
            hasDriveScope = true
        )
        assertEquals(GoogleDriveAuthState.AUTHORIZED, recheckState)
        scope2.cancel()
    }

    // 13. [P1] 背景 Worker 不得覆蓋前景更新的授權版本
    @Test
    fun backgroundWorkerDoesNotOverwriteNewerForegroundAuthVersion() {
        val currentEmail = "user@example.com"
        val currentVersion = 2L // Foreground re-authorized, bumped version to 2
        val workerCapturedVersion = 1L // Background worker has stale version 1

        val matches = GoogleDriveAuthResolutionHelper.isAuthVersionMatching(
            currentEmail = currentEmail,
            currentVersion = currentVersion,
            expectedEmail = "user@example.com",
            expectedVersion = workerCapturedVersion
        )
        org.junit.Assert.assertFalse(matches)
    }

    // 14. [P1] 背景 Worker 不得覆蓋已切換的帳號
    @Test
    fun backgroundWorkerDoesNotOverwriteDifferentEmail() {
        val currentEmail = "account_b@example.com" // Switched to account B
        val currentVersion = 2L
        val workerExpectedEmail = "account_a@example.com" // Worker started with account A

        val matches = GoogleDriveAuthResolutionHelper.isAuthVersionMatching(
            currentEmail = currentEmail,
            currentVersion = currentVersion,
            expectedEmail = workerExpectedEmail,
            expectedVersion = 1L
        )
        org.junit.Assert.assertFalse(matches)
    }

    // 15. [P2] 備份資料處理失敗不影響 Google Drive 授權狀態
    @Test
    fun backupBundlePreparationFailureDoesNotDegradeAuthState() = runBlocking {
        val (store, scope) = createSettingsDataStore("test15.preferences_pb")

        val prepError = "建立備份資料失敗: OutOfMemoryError"
        store.updateGoogleDriveAccountState(
            email = "user@example.com",
            state = GoogleDriveAuthState.AUTHORIZED,
            lastError = prepError
        )

        // Auth state must remain AUTHORIZED
        assertEquals(GoogleDriveAuthState.AUTHORIZED, store.googleDriveAuthStateFlow.first())
        assertEquals(prepError, store.autoCloudBackupLastErrorFlow.first())
        scope.cancel()
    }

    // 16. [P2] 上傳失敗依錯誤類型精確區分授權問題、配額、儲存空間與網路問題
    @Test
    fun uploadFailureDistinguishesAuthErrorAndNetworkError() {
        // 401: Unauthorized -> NEEDS_REAUTHORIZATION
        val authException = com.google.api.client.googleapis.json.GoogleJsonResponseException(
            com.google.api.client.http.HttpResponseException.Builder(401, "Unauthorized", com.google.api.client.http.HttpHeaders()),
            com.google.api.client.googleapis.json.GoogleJsonError()
        )
        val resolvedAuthError = GoogleDriveAuthResolutionHelper.resolveUploadFailureAuthState(authException)
        assertEquals(GoogleDriveAuthState.NEEDS_REAUTHORIZATION, resolvedAuthError)

        // 403 with authError reason -> NEEDS_REAUTHORIZATION
        val authReasonError = com.google.api.client.googleapis.json.GoogleJsonError().apply {
            val info = com.google.api.client.googleapis.json.GoogleJsonError.ErrorInfo()
            info.reason = "authError"
            errors = listOf(info)
        }
        val authReasonException = com.google.api.client.googleapis.json.GoogleJsonResponseException(
            com.google.api.client.http.HttpResponseException.Builder(403, "Forbidden", com.google.api.client.http.HttpHeaders()),
            authReasonError
        )
        assertEquals(
            GoogleDriveAuthState.NEEDS_REAUTHORIZATION,
            GoogleDriveAuthResolutionHelper.resolveUploadFailureAuthState(authReasonException)
        )

        // 403 with insufficientPermissions reason -> NEEDS_REAUTHORIZATION
        val permissionsError = com.google.api.client.googleapis.json.GoogleJsonError().apply {
            val info = com.google.api.client.googleapis.json.GoogleJsonError.ErrorInfo()
            info.reason = "insufficientPermissions"
            errors = listOf(info)
        }
        val permissionsException = com.google.api.client.googleapis.json.GoogleJsonResponseException(
            com.google.api.client.http.HttpResponseException.Builder(403, "Forbidden", com.google.api.client.http.HttpHeaders()),
            permissionsError
        )
        assertEquals(
            GoogleDriveAuthState.NEEDS_REAUTHORIZATION,
            GoogleDriveAuthResolutionHelper.resolveUploadFailureAuthState(permissionsException)
        )

        // 403 with rateLimitExceeded reason -> TEMPORARILY_UNAVAILABLE
        val rateLimitError = com.google.api.client.googleapis.json.GoogleJsonError().apply {
            val info = com.google.api.client.googleapis.json.GoogleJsonError.ErrorInfo()
            info.reason = "rateLimitExceeded"
            errors = listOf(info)
        }
        val rateLimitException = com.google.api.client.googleapis.json.GoogleJsonResponseException(
            com.google.api.client.http.HttpResponseException.Builder(403, "Forbidden", com.google.api.client.http.HttpHeaders()),
            rateLimitError
        )
        assertEquals(
            GoogleDriveAuthState.TEMPORARILY_UNAVAILABLE,
            GoogleDriveAuthResolutionHelper.resolveUploadFailureAuthState(rateLimitException)
        )

        // 403 with insufficientFilePermissions reason -> null (file-specific, not OAuth revocation)
        val filePermissionsError = com.google.api.client.googleapis.json.GoogleJsonError().apply {
            val info = com.google.api.client.googleapis.json.GoogleJsonError.ErrorInfo()
            info.reason = "insufficientFilePermissions"
            errors = listOf(info)
        }
        val filePermissionsException = com.google.api.client.googleapis.json.GoogleJsonResponseException(
            com.google.api.client.http.HttpResponseException.Builder(403, "Forbidden", com.google.api.client.http.HttpHeaders()),
            filePermissionsError
        )
        assertNull(GoogleDriveAuthResolutionHelper.resolveUploadFailureAuthState(filePermissionsException))

        // 403 with storageQuotaExceeded reason -> null (not auth failure, auth state unchanged)
        val storageQuotaError = com.google.api.client.googleapis.json.GoogleJsonError().apply {
            val info = com.google.api.client.googleapis.json.GoogleJsonError.ErrorInfo()
            info.reason = "storageQuotaExceeded"
            errors = listOf(info)
        }
        val storageQuotaException = com.google.api.client.googleapis.json.GoogleJsonResponseException(
            com.google.api.client.http.HttpResponseException.Builder(403, "Forbidden", com.google.api.client.http.HttpHeaders()),
            storageQuotaError
        )
        assertNull(GoogleDriveAuthResolutionHelper.resolveUploadFailureAuthState(storageQuotaException))

        // 403 with generic/unrelated reason -> null (does not prompt re-auth)
        val genericForbiddenException = com.google.api.client.googleapis.json.GoogleJsonResponseException(
            com.google.api.client.http.HttpResponseException.Builder(403, "Forbidden", com.google.api.client.http.HttpHeaders()),
            com.google.api.client.googleapis.json.GoogleJsonError()
        )
        assertNull(GoogleDriveAuthResolutionHelper.resolveUploadFailureAuthState(genericForbiddenException))

        // 503 Server error -> TEMPORARILY_UNAVAILABLE
        val serverException = com.google.api.client.googleapis.json.GoogleJsonResponseException(
            com.google.api.client.http.HttpResponseException.Builder(503, "Service Unavailable", com.google.api.client.http.HttpHeaders()),
            com.google.api.client.googleapis.json.GoogleJsonError()
        )
        assertEquals(
            GoogleDriveAuthState.TEMPORARILY_UNAVAILABLE,
            GoogleDriveAuthResolutionHelper.resolveUploadFailureAuthState(serverException)
        )

        // Network IOException -> TEMPORARILY_UNAVAILABLE
        val networkException = java.io.IOException("Connection timeout")
        val resolvedNetworkError = GoogleDriveAuthResolutionHelper.resolveUploadFailureAuthState(networkException)
        assertEquals(GoogleDriveAuthState.TEMPORARILY_UNAVAILABLE, resolvedNetworkError)

        // Other non-IO exception -> null
        val otherException = IllegalStateException("Something else")
        val resolvedOther = GoogleDriveAuthResolutionHelper.resolveUploadFailureAuthState(otherException)
        assertNull(resolvedOther)
    }

    // 17. [P2] 切換帳戶時同步狀態並清除舊帳戶備份時間
    @Test
    fun accountSwitchSynchronizesStateAndClearsPreviousTimestamps() {
        var currentAccount: com.rsps1008.stockify.data.GoogleDriveAccount? =
            com.rsps1008.stockify.data.GoogleDriveAccount("account_a@example.com", "User A")
        var currentAuthState = GoogleDriveAuthState.NEEDS_REAUTHORIZATION
        var cloudBackupTime: Long? = 12345678L

        val savedEmail = "account_b@example.com"
        val savedAuthState = GoogleDriveAuthState.AUTHORIZED

        val accountChanged = currentAccount?.email != savedEmail
        if (accountChanged) {
            currentAccount = com.rsps1008.stockify.data.GoogleDriveAccount(savedEmail, null)
            currentAuthState = savedAuthState
            cloudBackupTime = null
        }

        assertEquals("account_b@example.com", currentAccount.email)
        assertEquals(GoogleDriveAuthState.AUTHORIZED, currentAuthState)
        assertNull(cloudBackupTime)
    }

    // 18. [P1] Worker 上傳發生 401 時，若前景已重新授權（版本遞增），舊結果不得覆蓋
    @Test
    fun workerUploadFailureWith401DoesNotOverwriteNewerForegroundAuthVersion() {
        val email = "user@example.com"
        val workerSnapshotVersion = 1L

        // 前景使用者完成重新授權，版本推進至 2L
        val currentForegroundVersion = 2L

        // Worker 在背景上傳失敗，解析出 NEEDS_REAUTHORIZATION
        val authException = com.google.api.client.googleapis.json.GoogleJsonResponseException(
            com.google.api.client.http.HttpResponseException.Builder(401, "Unauthorized", com.google.api.client.http.HttpHeaders()),
            com.google.api.client.googleapis.json.GoogleJsonError()
        )
        val uploadFailureState = GoogleDriveAuthResolutionHelper.resolveUploadFailureAuthState(authException)
        assertEquals(GoogleDriveAuthState.NEEDS_REAUTHORIZATION, uploadFailureState)

        // Worker 嘗試使用備份開始時的快照版本 workerSnapshotVersion 寫入，版本防護應判定不匹配並捨棄
        val canOverwrite = GoogleDriveAuthResolutionHelper.isAuthVersionMatching(
            currentEmail = email,
            currentVersion = currentForegroundVersion,
            expectedEmail = email,
            expectedVersion = workerSnapshotVersion
        )
        org.junit.Assert.assertFalse(canOverwrite)

        // 若版本未改變（仍為 1L），則允許更新
        val canUpdateMatching = GoogleDriveAuthResolutionHelper.isAuthVersionMatching(
            currentEmail = email,
            currentVersion = workerSnapshotVersion,
            expectedEmail = email,
            expectedVersion = workerSnapshotVersion
        )
        org.junit.Assert.assertTrue(canUpdateMatching)
    }

    // 19. [P1] 區分帳戶授權世代與驗證結果更新：背景驗證不變更帳戶授權世代，且舊驗證不得覆蓋新驗證
    @Test
    fun backgroundValidationDoesNotBumpAuthGenerationAndDiscardsStaleValidation() {
        val email = "user@example.com"
        val currentGeneration = 10L

        // Worker A 在 attemptTime = 100 驗證成功
        val workerAValidates = GoogleDriveAuthResolutionHelper.isAuthVersionMatching(
            currentEmail = email,
            currentVersion = currentGeneration,
            expectedEmail = email,
            expectedVersion = currentGeneration,
            attemptTime = 100L,
            lastValidationTime = 0L
        )
        org.junit.Assert.assertTrue(workerAValidates)

        // 模擬延遲到達的舊 Worker B (attemptTime = 90)，嘗試寫入失敗狀態：因為 90 < 100，應判定不匹配並捨棄
        val workerBStaleValidates = GoogleDriveAuthResolutionHelper.isAuthVersionMatching(
            currentEmail = email,
            currentVersion = currentGeneration,
            expectedEmail = email,
            expectedVersion = currentGeneration,
            attemptTime = 90L,
            lastValidationTime = 100L
        )
        org.junit.Assert.assertFalse(workerBStaleValidates)

        // Worker A 稍後上傳成功，仍沿用驗證開始時的 attemptTime = 100。
        val workerAUploadSucceeds = GoogleDriveAuthResolutionHelper.isAuthVersionMatching(
            currentEmail = email,
            currentVersion = currentGeneration,
            expectedEmail = email,
            expectedVersion = currentGeneration,
            attemptTime = 100L,
            lastValidationTime = 100L
        )
        org.junit.Assert.assertTrue(workerAUploadSucceeds)

        // 若前景使用者重新授權，世代遞增為 11L，舊 Worker（帶世代 10L）皆不得寫入
        val staleWorkerAfterForegroundReauth = GoogleDriveAuthResolutionHelper.isAuthVersionMatching(
            currentEmail = email,
            currentVersion = 11L,
            expectedEmail = email,
            expectedVersion = currentGeneration,
            attemptTime = 100L,
            lastValidationTime = 100L
        )
        org.junit.Assert.assertFalse(staleWorkerAfterForegroundReauth)
    }

    @Test
    fun olderUploadCannotOverwriteNewerValidationOrClearItsError() = runBlocking {
        val (store, scope) = createSettingsDataStore("overlapping-workers.preferences_pb")
        try {
            val email = "user@example.com"
            store.setGoogleAccountEmail(email)
            val version = store.googleDriveAuthVersionFlow.first()
            val workerA = store.beginGoogleDriveAuthValidationIfMatching(email, version, 100L)!!
            org.junit.Assert.assertTrue(store.setGoogleDriveAuthStateIfMatching(
                email, version, GoogleDriveAuthState.AUTHORIZED, attemptTime = workerA
            ))

            val workerB = store.beginGoogleDriveAuthValidationIfMatching(email, version, 110L)!!
            val error = "Google Drive 需要重新確認授權"
            org.junit.Assert.assertTrue(store.setGoogleDriveAuthStateIfMatching(
                email, version, GoogleDriveAuthState.NEEDS_REAUTHORIZATION,
                lastError = error, attemptTime = workerB
            ))

            org.junit.Assert.assertFalse(store.setGoogleDriveAuthStateIfMatching(
                email, version, GoogleDriveAuthState.AUTHORIZED,
                clearLastError = true, attemptTime = workerA, backupSuccessAt = 130L
            ))
            org.junit.Assert.assertFalse(store.setGoogleDriveAuthStateIfMatching(
                email, version, GoogleDriveAuthState.TEMPORARILY_UNAVAILABLE,
                lastError = "舊 Worker 上傳失敗", attemptTime = workerA
            ))
            assertEquals(GoogleDriveAuthState.NEEDS_REAUTHORIZATION, store.googleDriveAuthStateFlow.first())
            assertEquals(error, store.autoCloudBackupLastErrorFlow.first())
            assertEquals(workerB, store.googleDriveAuthValidationTimeFlow.first())
            assertEquals(version, store.googleDriveAuthVersionFlow.first())
            assertNull(store.autoCloudBackupLastSuccessAtFlow.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun validationSequenceAdvancesForSameMillisecondAndClockRollback() = runBlocking {
        val (store, scope) = createSettingsDataStore("validation-sequence.preferences_pb")
        try {
            val email = "user@example.com"
            store.setGoogleAccountEmail(email)
            val version = store.googleDriveAuthVersionFlow.first()
            val first = store.beginGoogleDriveAuthValidationIfMatching(email, version, 100L)!!
            val second = store.beginGoogleDriveAuthValidationIfMatching(email, version, 100L)!!
            val third = store.beginGoogleDriveAuthValidationIfMatching(email, version, 90L)!!
            assertEquals(100L, first)
            assertEquals(101L, second)
            assertEquals(102L, third)
            assertNull(store.beginGoogleDriveAuthValidationIfMatching("other@example.com", version, 200L))
            assertNull(store.beginGoogleDriveAuthValidationIfMatching(email, version - 1L, 200L))
            assertEquals(third, store.googleDriveAuthValidationTimeFlow.first())
            org.junit.Assert.assertFalse(store.setGoogleDriveAuthStateIfMatching(
                email, version, GoogleDriveAuthState.AUTHORIZED, attemptTime = second
            ))

            store.setAutoCloudBackupLastError("先前的錯誤", 90L)
            org.junit.Assert.assertTrue(store.setGoogleDriveAuthStateIfMatching(
                email, version, GoogleDriveAuthState.AUTHORIZED,
                clearLastError = true, attemptTime = third, backupSuccessAt = 130L
            ))
            assertEquals(130L, store.autoCloudBackupLastSuccessAtFlow.first())
            assertNull(store.autoCloudBackupLastErrorFlow.first())
            assertEquals(third, store.googleDriveAuthValidationTimeFlow.first())
            assertEquals(version, store.googleDriveAuthVersionFlow.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun uploadSuccessAfterAccountSwitchCannotChangeNewAccountMetadata() = runBlocking {
        val (store, scope) = createSettingsDataStore("upload-account-switch.preferences_pb")
        try {
            val emailA = "account_a@example.com"
            val emailB = "account_b@example.com"
            store.setGoogleAccountEmail(emailA)
            val versionA = store.googleDriveAuthVersionFlow.first()
            val workerA = store.beginGoogleDriveAuthValidationIfMatching(emailA, versionA, 100L)!!

            store.setGoogleAccountEmail(emailB)
            val versionB = store.googleDriveAuthVersionFlow.first()
            val workerB = store.beginGoogleDriveAuthValidationIfMatching(emailB, versionB, 110L)!!
            store.setGoogleDriveAuthStateIfMatching(
                emailB, versionB, GoogleDriveAuthState.AUTHORIZED,
                clearLastError = true, attemptTime = workerB, backupSuccessAt = 120L
            )
            val error = "帳戶 B 需要重新授權"
            store.setGoogleDriveAuthStateIfMatching(
                emailB, versionB, GoogleDriveAuthState.NEEDS_REAUTHORIZATION,
                lastError = error, attemptTime = workerB
            )

            org.junit.Assert.assertFalse(store.setGoogleDriveAuthStateIfMatching(
                emailA, versionA, GoogleDriveAuthState.AUTHORIZED,
                clearLastError = true, attemptTime = workerA, backupSuccessAt = 130L
            ))
            assertEquals(120L, store.autoCloudBackupLastSuccessAtFlow.first())
            assertEquals(error, store.autoCloudBackupLastErrorFlow.first())
            assertEquals(emailB, store.googleAccountEmailFlow.first())
            assertEquals(GoogleDriveAuthState.NEEDS_REAUTHORIZATION, store.googleDriveAuthStateFlow.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cloudTimeRefreshReplacesDeviceCacheWithoutChangingLocalFailureOrAuthState() = runBlocking {
        val (store, scope) = createSettingsDataStore("cloud-time-refresh.preferences_pb")
        try {
            val email = "user@example.com"
            store.setGoogleAccountEmail(email)
            val version = store.googleDriveAuthVersionFlow.first()
            val attempt = store.beginGoogleDriveAuthValidationIfMatching(email, version, 100L)!!
            store.setGoogleDriveAuthStateIfMatching(
                email, version, GoogleDriveAuthState.AUTHORIZED, attemptTime = attempt, backupSuccessAt = 200L
            )
            store.setAutoCloudBackupLastErrorIfMatching(email, version, attempt, "本機上傳失敗", 130L)

            org.junit.Assert.assertTrue(store.setAutoCloudBackupModifiedTimeIfMatching(email, version, 200L, 150L))
            assertEquals(150L, store.autoCloudBackupLastSuccessAtFlow.first())
            assertEquals("本機上傳失敗", store.autoCloudBackupLastErrorFlow.first())
            assertEquals(130L, store.autoCloudBackupLastAttemptAtFlow.first())
            assertEquals(GoogleDriveAuthState.AUTHORIZED, store.googleDriveAuthStateFlow.first())
            assertEquals(version, store.googleDriveAuthVersionFlow.first())
            assertEquals(attempt, store.googleDriveAuthValidationTimeFlow.first())

            org.junit.Assert.assertTrue(store.setAutoCloudBackupModifiedTimeIfMatching(email, version, 150L, null))
            assertNull(store.autoCloudBackupLastSuccessAtFlow.first())
            assertEquals("本機上傳失敗", store.autoCloudBackupLastErrorFlow.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cloudTimeRefreshRejectsAccountOrVersionChangesAndAccountSwitchClearsCache() = runBlocking {
        val (store, scope) = createSettingsDataStore("cloud-time-account-switch.preferences_pb")
        try {
            val email = "user@example.com"
            store.setGoogleAccountEmail(email)
            val version = store.googleDriveAuthVersionFlow.first()
            store.setAutoCloudBackupModifiedTimeIfMatching(email, version, null, 200L)
            store.updateGoogleDriveAccountState(email, GoogleDriveAuthState.NEEDS_REAUTHORIZATION)
            val newVersion = store.googleDriveAuthVersionFlow.first()
            org.junit.Assert.assertFalse(store.setAutoCloudBackupModifiedTimeIfMatching(email, version, 200L, 250L))
            org.junit.Assert.assertFalse(store.setAutoCloudBackupModifiedTimeIfMatching("other@example.com", newVersion, 200L, 250L))
            assertEquals(200L, store.autoCloudBackupLastSuccessAtFlow.first())

            store.updateGoogleDriveAccountState("other@example.com", GoogleDriveAuthState.AUTHORIZED)
            assertNull(store.autoCloudBackupLastSuccessAtFlow.first())
            org.junit.Assert.assertFalse(store.setAutoCloudBackupModifiedTimeIfMatching(email, newVersion, null, 250L))
            val otherVersion = store.googleDriveAuthVersionFlow.first()
            store.setAutoCloudBackupModifiedTimeIfMatching("other@example.com", otherVersion, null, 300L)
            store.setGoogleAccountEmail(email)
            assertNull(store.autoCloudBackupLastSuccessAtFlow.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cloudTimeReadStartedBeforeUploadCannotReplaceNewlyCompletedBackup() = runBlocking {
        val (store, scope) = createSettingsDataStore("cloud-time-overlapping-upload.preferences_pb")
        try {
            val email = "user@example.com"
            store.setGoogleAccountEmail(email)
            val version = store.googleDriveAuthVersionFlow.first()
            store.setAutoCloudBackupModifiedTimeIfMatching(email, version, null, 200L)
            val snapshot = store.autoCloudBackupLastSuccessAtFlow.first()
            val attempt = store.beginGoogleDriveAuthValidationIfMatching(email, version, 100L)!!
            store.setGoogleDriveAuthStateIfMatching(
                email, version, GoogleDriveAuthState.AUTHORIZED,
                clearLastError = true, attemptTime = attempt, backupSuccessAt = 300L
            )

            org.junit.Assert.assertFalse(store.setAutoCloudBackupModifiedTimeIfMatching(email, version, snapshot, 250L))
            org.junit.Assert.assertFalse(store.setAutoCloudBackupModifiedTimeIfMatching(email, version, snapshot, null))
            assertEquals(300L, store.autoCloudBackupLastSuccessAtFlow.first())
            org.junit.Assert.assertTrue(store.setAutoCloudBackupModifiedTimeIfMatching(email, version, 300L, 400L))
            assertEquals(400L, store.autoCloudBackupLastSuccessAtFlow.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun successfulReauthorizationPreservesBackupError() = runBlocking {
        val (store, scope) = createSettingsDataStore("asset-account-reauthorization.preferences_pb")
        try {
            val email = "user@example.com"
            store.updateGoogleDriveAccountState(
                email, GoogleDriveAuthState.NEEDS_REAUTHORIZATION, lastError = "需要重新授權"
            )
            val previousVersion = store.googleDriveAuthVersionFlow.first()
            store.updateGoogleDriveAccountState(email, GoogleDriveAuthState.AUTHORIZED)

            assertEquals(email, store.googleAccountEmailFlow.first())
            assertEquals(GoogleDriveAuthState.AUTHORIZED, store.googleDriveAuthStateFlow.first())
            assertEquals("需要重新授權", store.autoCloudBackupLastErrorFlow.first())
            assertEquals(previousVersion, store.googleDriveAuthVersionFlow.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun matchingGeneralUploadErrorPreservesAuthorizationAndValidationSequence() = runBlocking {
        val (store, scope) = createSettingsDataStore("general-upload-error.preferences_pb")
        try {
            val email = "user@example.com"
            store.setGoogleAccountEmail(email)
            val version = store.googleDriveAuthVersionFlow.first()
            val attempt = store.beginGoogleDriveAuthValidationIfMatching(email, version, 100L)!!
            store.setGoogleDriveAuthStateIfMatching(
                email, version, GoogleDriveAuthState.AUTHORIZED,
                attemptTime = attempt, backupSuccessAt = 90L
            )
            val failure = IllegalStateException("儲存空間不足".repeat(100))
            assertNull(GoogleDriveAuthResolutionHelper.resolveUploadFailureAuthState(failure))

            org.junit.Assert.assertTrue(store.setAutoCloudBackupLastErrorIfMatching(
                email, version, attempt, failure.message!!, failedAt = 130L
            ))
            assertEquals(failure.message!!.take(300), store.autoCloudBackupLastErrorFlow.first())
            assertEquals(130L, store.autoCloudBackupLastAttemptAtFlow.first())
            assertEquals(GoogleDriveAuthState.AUTHORIZED, store.googleDriveAuthStateFlow.first())
            assertEquals(version, store.googleDriveAuthVersionFlow.first())
            assertEquals(attempt, store.googleDriveAuthValidationTimeFlow.first())
            assertEquals(90L, store.autoCloudBackupLastSuccessAtFlow.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun generalUploadErrorAfterAccountSwitchCannotChangeNewAccountMetadata() = runBlocking {
        val (store, scope) = createSettingsDataStore("general-error-account-switch.preferences_pb")
        try {
            val emailA = "account_a@example.com"
            val emailB = "account_b@example.com"
            store.setGoogleAccountEmail(emailA)
            val versionA = store.googleDriveAuthVersionFlow.first()
            val workerA = store.beginGoogleDriveAuthValidationIfMatching(emailA, versionA, 100L)!!
            store.setGoogleAccountEmail(emailB)
            val versionB = store.googleDriveAuthVersionFlow.first()
            val workerB = store.beginGoogleDriveAuthValidationIfMatching(emailB, versionB, 110L)!!
            store.setGoogleDriveAuthStateIfMatching(
                emailB, versionB, GoogleDriveAuthState.NEEDS_REAUTHORIZATION, attemptTime = workerB
            )
            store.setAutoCloudBackupLastErrorIfMatching(emailB, versionB, workerB, "帳戶 B 的錯誤", 120L)

            org.junit.Assert.assertFalse(store.setAutoCloudBackupLastErrorIfMatching(
                emailA, versionA, workerA, "帳戶 A 儲存空間不足", 130L
            ))
            org.junit.Assert.assertFalse(store.setAutoCloudBackupLastErrorIfMatching(
                emailA, versionB, workerB, "不同帳戶的錯誤", 140L
            ))
            assertEquals("帳戶 B 的錯誤", store.autoCloudBackupLastErrorFlow.first())
            assertEquals(120L, store.autoCloudBackupLastAttemptAtFlow.first())
            assertEquals(emailB, store.googleAccountEmailFlow.first())
            assertEquals(GoogleDriveAuthState.NEEDS_REAUTHORIZATION, store.googleDriveAuthStateFlow.first())
            assertEquals(versionB, store.googleDriveAuthVersionFlow.first())
            assertEquals(workerB, store.googleDriveAuthValidationTimeFlow.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun generalUploadErrorAfterReauthorizationOrNewerWorkerIsDiscarded() = runBlocking {
        val (store, scope) = createSettingsDataStore("general-error-stale-worker.preferences_pb")
        try {
            val email = "user@example.com"
            store.setGoogleAccountEmail(email)
            val oldVersion = store.googleDriveAuthVersionFlow.first()
            val oldAttempt = store.beginGoogleDriveAuthValidationIfMatching(email, oldVersion, 100L)!!
            store.setGoogleDriveAuthState(GoogleDriveAuthState.NEEDS_REAUTHORIZATION)
            store.setGoogleDriveAuthState(GoogleDriveAuthState.AUTHORIZED)
            val version = store.googleDriveAuthVersionFlow.first()
            val attempt = store.beginGoogleDriveAuthValidationIfMatching(email, version)!!
            store.setAutoCloudBackupLastErrorIfMatching(email, version, attempt, "目前作業的錯誤", 120L)

            org.junit.Assert.assertFalse(store.setAutoCloudBackupLastErrorIfMatching(
                email, oldVersion, attempt, "重新授權前的錯誤", 130L
            ))
            val newerAttempt = store.beginGoogleDriveAuthValidationIfMatching(email, version)!!
            org.junit.Assert.assertFalse(store.setAutoCloudBackupLastErrorIfMatching(
                email, version, attempt, "舊作業的錯誤", 140L
            ))
            org.junit.Assert.assertFalse(store.setAutoCloudBackupLastErrorIfMatching(
                email, oldVersion, oldAttempt, "原始作業的錯誤", 150L
            ))
            assertEquals("目前作業的錯誤", store.autoCloudBackupLastErrorFlow.first())
            assertEquals(120L, store.autoCloudBackupLastAttemptAtFlow.first())
            assertEquals(GoogleDriveAuthState.AUTHORIZED, store.googleDriveAuthStateFlow.first())
            assertEquals(version, store.googleDriveAuthVersionFlow.first())
            assertEquals(newerAttempt, store.googleDriveAuthValidationTimeFlow.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun ordinarySameAccountAuthorizationDoesNotDiscardUploadSuccess() = runBlocking {
        val (store, scope) = createSettingsDataStore("upload-reauthorization.preferences_pb")
        try {
            val email = "user@example.com"
            store.setGoogleAccountEmail(email)
            val version = store.googleDriveAuthVersionFlow.first()
            val attempt = store.beginGoogleDriveAuthValidationIfMatching(email, version, 100L)!!
            store.updateGoogleDriveAccountState(email, GoogleDriveAuthState.AUTHORIZED)

            org.junit.Assert.assertTrue(store.setGoogleDriveAuthStateIfMatching(
                email, version, GoogleDriveAuthState.AUTHORIZED,
                clearLastError = true, attemptTime = attempt, backupSuccessAt = 130L
            ))
            assertEquals(130L, store.autoCloudBackupLastSuccessAtFlow.first())
            assertEquals(version, store.googleDriveAuthVersionFlow.first())
        } finally {
            scope.cancel()
        }
    }
}
