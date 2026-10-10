package com.rsps1008.stockify.ui.screens

import android.accounts.AccountManager
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import com.google.api.services.drive.DriveScopes
import com.rsps1008.stockify.StockifyApplication
import com.rsps1008.stockify.data.GoogleDriveAccount
import com.rsps1008.stockify.data.GoogleDriveAuthState
import com.rsps1008.stockify.data.GoogleDriveService
import com.rsps1008.stockify.ui.viewmodel.AssetOverviewViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val assetBalanceAccountEmailPattern = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

internal fun resolveAssetBalanceGoogleDriveEmail(vararg candidates: String?): String? =
    candidates.asSequence()
        .mapNotNull { it?.trim() }
        .firstOrNull { assetBalanceAccountEmailPattern.matches(it) }

@Suppress("DEPRECATION")
private fun AuthorizationResult.assetBalanceAccountEmail(vararg fallbackEmails: String?): String? {
    val account = toGoogleSignInAccount()
    return resolveAssetBalanceGoogleDriveEmail(account?.email, account?.account?.name, *fallbackEmails)
}

internal data class AssetBalanceGoogleDriveActions(
    val email: String?,
    val signingIn: Boolean,
    val backup: () -> Unit,
    val restore: () -> Unit
)

@Composable
internal fun rememberAssetBalanceGoogleDriveActions(model: AssetOverviewViewModel): AssetBalanceGoogleDriveActions {
    val context = LocalContext.current
    val application = context.applicationContext as StockifyApplication
    val scope = remember { Scope(DriveScopes.DRIVE_APPDATA) }
    var accountEmail by remember { mutableStateOf<String?>(null) }
    var pendingAuthEmail by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingAction by rememberSaveable { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val authorizationClient = remember(context) { Identity.getAuthorizationClient(context) }

    fun runAction(action: String, email: String) {
        val service = GoogleDriveService(context.applicationContext, email)
        if (action == "backup") {
            model.backupToGoogleDrive { name, bytes, mime -> service.uploadBackup(name, bytes, mime).getOrThrow() }
        } else {
            model.previewGoogleDriveRestore { service.restoreAssetBalances() }
        }
    }

    suspend fun saveAuthorizedAccount(email: String) {
        application.settingsDataStore.updateGoogleDriveAccountState(
            email = email,
            state = GoogleDriveAuthState.AUTHORIZED
        )
        accountEmail = email
    }

    fun accountIdentificationFailed() {
        pendingAction = null
        pendingAuthEmail = null
        accountEmail = null
        model.showMessage("無法辨識 Google 帳戶，請重新選擇帳戶")
    }

    lateinit var launcher: ActivityResultLauncher<IntentSenderRequest>

    fun requestAccountAuthorization(email: String, action: String?) {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(scope, Scope("email")))
            .setAccount(android.accounts.Account(email, "com.google"))
            .build()
        authorizationClient.authorize(request)
            .addOnSuccessListener { authResult ->
                if (authResult.hasResolution()) {
                    val pendingIntent = authResult.pendingIntent
                    if (pendingIntent != null) {
                        launcher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                    } else {
                        pendingAction = null
                        pendingAuthEmail = null
                        model.showMessage("無法開啟 Google 授權視窗")
                    }
                } else if (authResult.grantedScopes.any { it.contains("drive.appdata") }) {
                    val targetEmail = authResult.assetBalanceAccountEmail(email)
                    if (targetEmail == null) {
                        accountIdentificationFailed()
                        return@addOnSuccessListener
                    }
                    pendingAuthEmail = null
                    coroutineScope.launch {
                        saveAuthorizedAccount(targetEmail)
                        pendingAction = null
                        if (action != null) runAction(action, targetEmail)
                    }
                } else {
                    pendingAction = null
                    pendingAuthEmail = null
                    model.showMessage("未授予 Google Drive 權限")
                }
            }
            .addOnFailureListener { e ->
                pendingAction = null
                pendingAuthEmail = null
                model.showMessage("無法開啟 Google 授權：${e.message ?: "請稍後再試"}")
            }
    }

    launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val action = pendingAction
        val email = pendingAuthEmail
        val intent = result.data
        if (result.resultCode == Activity.RESULT_OK && intent != null) {
            try {
                val authResult = authorizationClient.getAuthorizationResultFromIntent(intent)
                if (authResult.hasResolution()) {
                    val pendingIntent = authResult.pendingIntent
                    if (pendingIntent != null) {
                        launcher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                        return@rememberLauncherForActivityResult
                    }
                    error("無法開啟 Google 授權視窗")
                }
                pendingAction = null
                val hasScope = authResult.grantedScopes.any { it.contains("drive.appdata") }
                check(hasScope) { "請授予 Google Drive 備份權限後再試" }
                coroutineScope.launch {
                    val savedEmail = application.settingsDataStore.googleAccountEmailFlow.first()
                    val legacyEmail = GoogleDriveAccount.fromLegacyStorage(application)?.email
                    val targetEmail = authResult.assetBalanceAccountEmail(email, savedEmail, legacyEmail)
                    if (targetEmail == null) {
                        accountIdentificationFailed()
                        return@launch
                    }
                    pendingAuthEmail = null
                    saveAuthorizedAccount(targetEmail)
                    if (action != null) runAction(action, targetEmail)
                }
            } catch (e: Exception) {
                pendingAction = null
                pendingAuthEmail = null
                model.showMessage("Google 授權失敗：${e.message ?: "請稍後再試"}")
            }
        } else {
            pendingAction = null
            pendingAuthEmail = null
            if (intent != null) {
                try {
                    authorizationClient.getAuthorizationResultFromIntent(intent)
                } catch (e: Exception) {
                    if (e !is ApiException || (e.statusCode != CommonStatusCodes.CANCELED && e.statusCode != 12501)) {
                        model.showMessage("Google 授權失敗：${e.message ?: "請稍後再試"}")
                    }
                }
            }
        }
    }

    val accountChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val action = pendingAction
        if (result.resultCode == Activity.RESULT_OK) {
            val email = resolveAssetBalanceGoogleDriveEmail(result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME))
            if (email != null) {
                pendingAuthEmail = email
                requestAccountAuthorization(email, action)
            } else {
                accountIdentificationFailed()
            }
        } else {
            pendingAction = null
        }
    }

    fun checkAuthorization(onAuthorized: ((String) -> Unit)? = null) {
        coroutineScope.launch {
            val legacyAccount = GoogleDriveAccount.fromLegacyStorage(application)
            val savedEmail = resolveAssetBalanceGoogleDriveEmail(
                application.settingsDataStore.googleAccountEmailFlow.first(),
                legacyAccount?.email
            )
            val requestBuilder = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(scope, Scope("email")))
            if (!savedEmail.isNullOrBlank()) {
                requestBuilder.setAccount(android.accounts.Account(savedEmail, "com.google"))
            }
            try {
                val authResult = withContext(Dispatchers.IO) {
                    Tasks.await(authorizationClient.authorize(requestBuilder.build()))
                }
                if (!authResult.hasResolution() && authResult.grantedScopes.any { it.contains("drive.appdata") }) {
                    val email = authResult.assetBalanceAccountEmail(savedEmail)
                    if (email == null) {
                        accountIdentificationFailed()
                        return@launch
                    }
                    saveAuthorizedAccount(email)
                    onAuthorized?.invoke(email)
                } else if (!savedEmail.isNullOrBlank()) {
                    accountEmail = savedEmail
                    application.settingsDataStore.updateGoogleDriveAccountState(
                        savedEmail, GoogleDriveAuthState.NEEDS_REAUTHORIZATION
                    )
                } else {
                    accountEmail = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                accountEmail = savedEmail
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                checkAuthorization()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun request(action: String) {
        if (model.isBusy.value || pendingAction != null) return
        val currentEmail = accountEmail
        if (currentEmail != null) {
            pendingAction = action
            pendingAuthEmail = currentEmail
            requestAccountAuthorization(currentEmail, action)
        } else {
            pendingAction = action
            pendingAuthEmail = null
            val chooseAccountIntent = AccountManager.newChooseAccountIntent(
                null,
                null,
                arrayOf("com.google"),
                null,
                null,
                null,
                null
            )
            try {
                accountChooserLauncher.launch(chooseAccountIntent)
            } catch (e: Exception) {
                pendingAction = null
                model.showMessage("無法開啟帳號選擇器：${e.message ?: "請稍後再試"}")
            }
        }
    }

    return AssetBalanceGoogleDriveActions(
        email = accountEmail,
        signingIn = pendingAction != null,
        backup = { request("backup") },
        restore = { request("restore") }
    )
}
