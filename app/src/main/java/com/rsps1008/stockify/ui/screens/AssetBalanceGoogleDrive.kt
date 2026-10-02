package com.rsps1008.stockify.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.rsps1008.stockify.data.GoogleDriveService
import com.rsps1008.stockify.ui.viewmodel.AssetOverviewViewModel

internal data class AssetBalanceGoogleDriveActions(
    val email: String?,
    val signingIn: Boolean,
    val backup: () -> Unit,
    val restore: () -> Unit
)

@Suppress("DEPRECATION")
@Composable
internal fun rememberAssetBalanceGoogleDriveActions(model: AssetOverviewViewModel): AssetBalanceGoogleDriveActions {
    val context = LocalContext.current
    val scope = remember { Scope(DriveScopes.DRIVE_APPDATA) }
    fun authorizedAccount(): GoogleSignInAccount? = GoogleSignIn.getLastSignedInAccount(context)
        ?.takeIf { GoogleSignIn.hasPermissions(it, scope) }
    var account by remember { mutableStateOf(authorizedAccount()) }
    var pendingAction by rememberSaveable { mutableStateOf<String?>(null) }
    fun runAction(action: String, signedIn: GoogleSignInAccount) {
        val service = GoogleDriveService(context.applicationContext, signedIn)
        if (action == "backup") {
            model.backupToGoogleDrive { name, bytes, mime -> service.uploadBackup(name, bytes, mime).getOrThrow() }
        } else {
            model.previewGoogleDriveRestore { service.restoreAssetBalances() }
        }
    }
    val client = remember(context) {
        GoogleSignIn.getClient(context, GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail().requestScopes(scope).build())
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val action = pendingAction
        pendingAction = null
        if (result.resultCode == Activity.RESULT_OK) {
            try {
                val signedIn = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                    .getResult(com.google.android.gms.common.api.ApiException::class.java)
                check(GoogleSignIn.hasPermissions(signedIn, scope)) { "請授予 Google Drive 備份權限後再試" }
                account = signedIn
                if (action != null) runAction(action, signedIn)
            } catch (e: Exception) {
                model.showMessage("Google 登入失敗：${e.message ?: "請稍後再試"}")
            }
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) account = authorizedAccount()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    fun request(action: String) {
        if (model.isBusy.value || pendingAction != null) return
        val signedIn = authorizedAccount()
        account = signedIn
        if (signedIn != null) runAction(action, signedIn) else {
            pendingAction = action
            try { launcher.launch(client.signInIntent) }
            catch (e: Exception) {
                pendingAction = null
                model.showMessage("無法開啟 Google 登入：${e.message ?: "請稍後再試"}")
            }
        }
    }
    return AssetBalanceGoogleDriveActions(account?.email, pendingAction != null,
        backup = { request("backup") }, restore = { request("restore") })
}
