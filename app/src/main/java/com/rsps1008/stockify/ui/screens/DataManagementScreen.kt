package com.rsps1008.stockify.ui.screens

import com.rsps1008.stockify.data.CloudRestoreSources

import android.accounts.AccountManager
import android.app.Activity
import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.RadioButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.rsps1008.stockify.data.GoogleDriveAccount
import com.rsps1008.stockify.data.GoogleDriveAuthState
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.rsps1008.stockify.R
import com.rsps1008.stockify.StockifyApplication
import com.rsps1008.stockify.data.Account
import com.rsps1008.stockify.data.PdfStockImportPreview
import com.rsps1008.stockify.ui.viewmodel.SettingsViewModel
import com.rsps1008.stockify.ui.viewmodel.ViewModelFactory
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DataManagementButtonShape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)

@Composable
fun DataManagementScreen() {
    val application = LocalContext.current.applicationContext as StockifyApplication
    val viewModel: SettingsViewModel = viewModel(
        factory = ViewModelFactory(
            stockDao = application.database.stockDao(),
            settingsDataStore = application.settingsDataStore,
            application = application,
            realtimeStockDataService = application.realtimeStockDataService,
            exchangeRateService = application.exchangeRateService,
            twseStockHistoryService = application.twseStockHistoryService
        )
    )

    val isLoading by viewModel.isLoading.collectAsState()
    val message by viewModel.message.collectAsState()
    val showImportConfirmDialog by viewModel.showImportConfirmDialog.collectAsState()
    val showForceImportConfirmDialog by viewModel.showForceImportConfirmDialog.collectAsState()
    val forceImportReason by viewModel.forceImportReason.collectAsState()
    val showLocalCsvRestoreFeeHintDialog by viewModel.showLocalCsvRestoreFeeHintDialog.collectAsState()
    val downloadBackupFiles by viewModel.downloadBackupFiles.collectAsState()
    val downloadBackupType by viewModel.downloadBackupType.collectAsState()
    val googleSignInAccount by viewModel.googleSignInAccount.collectAsState()
    val googleDriveAuthState by viewModel.googleDriveAuthState.collectAsState()
    val showPdfPasswordDialog by viewModel.showPdfPasswordDialog.collectAsState()
    val pdfPassword by viewModel.pdfPassword.collectAsState()
    val pdfImportPreview by viewModel.pdfImportPreview.collectAsState()
    val skipPdfImportTutorial by viewModel.skipPdfImportTutorial.collectAsState()
    val cloudDataBackupUpdatedAt by viewModel.cloudDataBackupUpdatedAt.collectAsState()
    val autoCloudBackupEnabled by application.settingsDataStore.autoCloudBackupEnabledFlow.collectAsState(initial = false)
    val autoCloudBackupIntervalDays by application.settingsDataStore.autoCloudBackupIntervalDaysFlow.collectAsState(initial = 1)
    val autoCloudBackupLastSuccessAt by application.settingsDataStore.autoCloudBackupLastSuccessAtFlow.collectAsState(initial = null)
    val autoCloudBackupLastAttemptAt by application.settingsDataStore.autoCloudBackupLastAttemptAtFlow.collectAsState(initial = null)
    val autoCloudBackupLastError by application.settingsDataStore.autoCloudBackupLastErrorFlow.collectAsState(initial = null)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshCloudBackupTimes()
    }
    val cloudRestoreSources by viewModel.cloudRestoreSources.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    val activeAccountId by viewModel.activeAccountId.collectAsState()

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showPdfTutorialDialog by remember { mutableStateOf(false) }
    var showCloudBackupOverwriteDialog by remember { mutableStateOf(false) }
    var showCloudRestoreSourceDialog by remember { mutableStateOf(false) }
    var dontShowPdfTutorialAgain by remember(skipPdfImportTutorial) {
        mutableStateOf(skipPdfImportTutorial)
    }

    var pendingAuthEmail by rememberSaveable { mutableStateOf<String?>(null) }

    val authorizationClient = remember(context) { Identity.getAuthorizationClient(context) }

    lateinit var authorizationLauncher: ActivityResultLauncher<IntentSenderRequest>

    fun requestAccountAuthorization(email: String) {
        pendingAuthEmail = email
        val driveScope = Scope(DriveScopes.DRIVE_APPDATA)
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(driveScope, Scope("email")))
            .setAccount(android.accounts.Account(email, "com.google"))
            .build()
        authorizationClient.authorize(request)
            .addOnSuccessListener { authResult ->
                if (authResult.hasResolution()) {
                    val pendingIntent = authResult.pendingIntent
                    if (pendingIntent != null) {
                        authorizationLauncher.launch(
                            IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                        )
                    } else {
                        viewModel.handleAuthorizationFailure(IllegalStateException("無法開啟 Google 授權視窗"))
                    }
                } else if (authResult.grantedScopes.any { it.contains("drive.appdata") }) {
                    viewModel.handleAccountAuthorized(email, authResult)
                    pendingAuthEmail = null
                } else {
                    viewModel.handleAuthorizationFailure(IllegalStateException("未授予 Google Drive 權限"))
                }
            }
            .addOnFailureListener { e ->
                viewModel.handleAuthorizationFailure(e)
            }
    }

    authorizationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
        onResult = { result ->
            val email = pendingAuthEmail
            val intent = result.data
            if (result.resultCode == Activity.RESULT_OK && intent != null) {
                try {
                    val authResult = authorizationClient.getAuthorizationResultFromIntent(intent)
                    if (authResult.hasResolution()) {
                        val pendingIntent = authResult.pendingIntent
                        if (pendingIntent != null) {
                            authorizationLauncher.launch(
                                IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                            )
                        } else {
                            viewModel.handleAuthorizationFailure(IllegalStateException("無法開啟 Google 授權視窗"))
                        }
                    } else if (authResult.grantedScopes.any { it.contains("drive.appdata") }) {
                        if (!email.isNullOrBlank()) {
                            viewModel.handleAccountAuthorized(email, authResult)
                        } else {
                            viewModel.handleAuthorizationResult(authResult)
                        }
                        pendingAuthEmail = null
                    } else {
                        viewModel.handleAuthorizationFailure(IllegalStateException("未授予 Google Drive 權限"))
                    }
                } catch (e: Exception) {
                    pendingAuthEmail = null
                    viewModel.handleAuthorizationFailure(e)
                }
            } else if (intent != null) {
                try {
                    authorizationClient.getAuthorizationResultFromIntent(intent)
                } catch (e: Exception) {
                    pendingAuthEmail = null
                    if (e !is ApiException || (e.statusCode != CommonStatusCodes.CANCELED && e.statusCode != 12501)) {
                        viewModel.handleAuthorizationFailure(e)
                    }
                }
            } else {
                pendingAuthEmail = null
            }
        }
    )

    val accountChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val email = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
                if (!email.isNullOrBlank()) {
                    pendingAuthEmail = email
                    requestAccountAuthorization(email)
                } else {
                    viewModel.handleAuthorizationFailure(IllegalStateException("未選擇 Google 帳號"))
                }
            }
        }
    )

    fun startAuthorizationFlow() {
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
            viewModel.handleAuthorizationFailure(e)
        }
    }

    val exportCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
        onResult = { uri: Uri? -> uri?.let(viewModel::exportTransactions) }
    )

    val exportCsvFallbackLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
        onResult = { uri: Uri? -> uri?.let(viewModel::exportTransactions) }
    )

    val importCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? -> uri?.let(viewModel::onImportRequest) }
    )

    val exportAccountsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
        onResult = { uri: Uri? -> uri?.let(viewModel::exportAccounts) }
    )

    val exportAccountsFallbackLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
        onResult = { uri: Uri? -> uri?.let(viewModel::exportAccounts) }
    )

    val importAccountsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? -> uri?.let(viewModel::importAccounts) }
    )

    val exportHoldingsOrderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
        onResult = { uri: Uri? -> uri?.let(viewModel::exportHoldingsOrder) }
    )

    val exportHoldingsOrderFallbackLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
        onResult = { uri: Uri? -> uri?.let(viewModel::exportHoldingsOrder) }
    )

    val importHoldingsOrderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? -> uri?.let(viewModel::importHoldingsOrder) }
    )

    val importPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? -> uri?.let(viewModel::onPdfImportRequest) }
    )

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.onMessageShown()
        }
    }

    fun launchCreateDocumentSafely(
        launcher: ActivityResultLauncher<String>,
        fallbackLauncher: ActivityResultLauncher<String>,
        fileName: String,
        directFallback: () -> Unit
    ) {
        try {
            launcher.launch(fileName)
        } catch (_: ActivityNotFoundException) {
            try {
                fallbackLauncher.launch(fileName)
            } catch (_: ActivityNotFoundException) {
                directFallback()
            }
        }
    }

    fun launchImportSafely(
        launcher: ActivityResultLauncher<String>,
        mimeType: String,
        backupType: String
    ) {
        try {
            launcher.launch(mimeType)
        } catch (_: ActivityNotFoundException) {
            viewModel.showDownloadBackups(backupType)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.onSignOut.collectLatest {
            try {
                val driveScope = Scope(DriveScopes.DRIVE_APPDATA)
                val account = googleSignInAccount?.account
                val builder = RevokeAccessRequest.builder()
                    .setScopes(listOf(driveScope, Scope("email")))
                if (account != null) {
                    builder.setAccount(account)
                }
                authorizationClient.revokeAccess(builder.build())
                    .addOnCompleteListener { viewModel.onSignOutComplete() }
            } catch (_: Exception) {
                viewModel.onSignOutComplete()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SampledResourceImage(
            resId = R.drawable.stockify,
            contentDescription = "Stockify Logo",
            modifier = Modifier.fillMaxWidth(0.35f)
        )
        Spacer(modifier = Modifier.height(6.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                CloudBackupSection(
                    viewModel = viewModel,
                    isLoading = isLoading,
                    googleSignInAccount = googleSignInAccount,
                    googleDriveAuthState = googleDriveAuthState,
                    cloudDataBackupUpdatedAt = cloudDataBackupUpdatedAt,
                    onBackupClick = { showCloudBackupOverwriteDialog = true },
                    onRestoreClick = {
                        showCloudRestoreSourceDialog = true
                        viewModel.inspectCloudRestoreSources()
                    },
                    autoBackupEnabled = autoCloudBackupEnabled,
                    autoBackupIntervalDays = autoCloudBackupIntervalDays,
                    autoBackupLastSuccessAt = autoCloudBackupLastSuccessAt,
                    autoBackupLastAttemptAt = autoCloudBackupLastAttemptAt,
                    autoBackupLastError = autoCloudBackupLastError,
                    onAutoBackupEnabledChange = { enabled ->
                        coroutineScope.launch { application.settingsDataStore.setAutoCloudBackupEnabled(enabled) }
                    },
                    onAutoBackupIntervalChange = { days ->
                        coroutineScope.launch { application.settingsDataStore.setAutoCloudBackupIntervalDays(days) }
                    },
                    onSignInClick = ::startAuthorizationFlow,
                    onReauthorizeClick = {
                        val email = googleSignInAccount?.email
                        if (!email.isNullOrBlank()) {
                            requestAccountAuthorization(email)
                        } else {
                            startAuthorizationFlow()
                        }
                    },
                    onRetryVerificationClick = viewModel::checkGoogleDriveAuthorization,
                    onSignOutClick = viewModel::signOut
                )
            }

            item {
                LocalBackupSection(
                    viewModel = viewModel,
                    isLoading = isLoading,
                    exportCsvLauncher = exportCsvLauncher,
                    exportCsvFallbackLauncher = exportCsvFallbackLauncher,
                    importCsvLauncher = importCsvLauncher,
                    exportAccountsLauncher = exportAccountsLauncher,
                    exportAccountsFallbackLauncher = exportAccountsFallbackLauncher,
                    importAccountsLauncher = importAccountsLauncher,
                    exportHoldingsOrderLauncher = exportHoldingsOrderLauncher,
                    exportHoldingsOrderFallbackLauncher = exportHoldingsOrderFallbackLauncher,
                    importHoldingsOrderLauncher = importHoldingsOrderLauncher,
                    launchCreateDocumentSafely = ::launchCreateDocumentSafely,
                    launchImportSafely = ::launchImportSafely
                )
            }

            item {
                ExternalImportSection(
                    viewModel = viewModel,
                    isLoading = isLoading,
                    onImportPdfClick = {
                        if (skipPdfImportTutorial) {
                            importPdfLauncher.launch("application/pdf")
                        } else {
                            dontShowPdfTutorialAgain = skipPdfImportTutorial
                            showPdfTutorialDialog = true
                        }
                    }
                )
            }

            item {
                OtherDataOperationsSection(
                    viewModel = viewModel,
                    accounts = accounts,
                    activeAccountId = activeAccountId,
                    isLoading = isLoading,
                    googleDriveSignedIn = googleSignInAccount != null
                )
            }
        }
    }

    if (showImportConfirmDialog) {
        AlertDialog(
            onDismissRequest = viewModel::onImportCancel,
            title = { Text("還原確認") },
            text = { Text("要先清空現有交易資料，再還原這份 CSV 嗎？") },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { viewModel.onImportConfirm(true) }) {
                        Text("清空後還原")
                    }
                    TextButton(onClick = { viewModel.onImportConfirm(false) }) {
                        Text("直接新增")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::onImportCancel) {
                        Text("取消")
                    }
                }
            }
        )
    }

    if (showCloudBackupOverwriteDialog) {
        AlertDialog(
            onDismissRequest = { showCloudBackupOverwriteDialog = false },
            title = { Text("確認雲端備份") },
            text = { Text("備份會覆蓋目前 Google Drive 中的雲端備份檔案，要繼續嗎？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCloudBackupOverwriteDialog = false
                        viewModel.backupToGoogleDrive()
                    }
                ) { Text("繼續備份") }
            },
            dismissButton = {
                TextButton(onClick = { showCloudBackupOverwriteDialog = false }) { Text("取消") }
            }
        )
    }

    if (showCloudRestoreSourceDialog) {
        AlertDialog(
            onDismissRequest = { showCloudRestoreSourceDialog = false },
            title = { Text("選擇雲端備份") },
            text = {
                Text(
                    when (cloudRestoreSources) {
                        CloudRestoreSources.Loading -> "正在確認雲端備份檔案…"
                        CloudRestoreSources.QueryFailed -> "無法查詢 Google Drive 備份，請稍後重試。"
                        CloudRestoreSources.Available(false, false) -> "Google Drive 中找不到可還原的手動或自動備份。"
                        else -> "請選擇要還原的備份來源。"
                    }
                )
            },
            confirmButton = {
                Row {
                    if (cloudRestoreSources == CloudRestoreSources.QueryFailed) {
                        TextButton(onClick = viewModel::inspectCloudRestoreSources) { Text("重試") }
                    } else {
                        TextButton(
                            enabled = (cloudRestoreSources as? CloudRestoreSources.Available)?.manual == true,
                            onClick = {
                                showCloudRestoreSourceDialog = false
                                viewModel.restoreFromGoogleDrive(isAutomatic = false)
                            }
                        ) { Text("手動備份") }
                        TextButton(
                            enabled = (cloudRestoreSources as? CloudRestoreSources.Available)?.automatic == true,
                            onClick = {
                                showCloudRestoreSourceDialog = false
                                viewModel.restoreFromGoogleDrive(isAutomatic = true)
                            }
                        ) { Text("自動備份") }
                    }
                    TextButton(onClick = { showCloudRestoreSourceDialog = false }) { Text("取消") }
                }
            }
        )
    }

    if (showForceImportConfirmDialog) {
        AlertDialog(
            onDismissRequest = viewModel::onForceImportCancel,
            title = { Text("匯入驗證失敗") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(forceImportReason.orEmpty())
                    Text(
                        "仍可選擇強制匯入，但這會略過交易、持股、融資融券與既有資料關聯驗證；雲端帳戶／排序附檔若損壞也可能不會還原。匯入後可能造成持股數、成本、損益、融資融券或報酬率計算錯誤；請先確認沒有其他可用的本地備份。"
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::onForceImportConfirm) {
                    Text("仍要強制匯入")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onForceImportCancel) {
                    Text("取消")
                }
            }
        )
    }

    if (showLocalCsvRestoreFeeHintDialog) {
        AlertDialog(
            onDismissRequest = viewModel::onLocalCsvRestoreFeeHintCancel,
            title = { Text("本地資料還原提醒") },
            text = {
                Text("手續費必須先在表格中計算完成。還原本地資料時，App 不會重新計算手續費、交易稅、支出或收入。")
            },
            confirmButton = {
                TextButton(onClick = viewModel::onLocalCsvRestoreFeeHintConfirm) {
                    Text("知道了，繼續")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onLocalCsvRestoreFeeHintCancel) {
                    Text("取消")
                }
            }
        )
    }

    downloadBackupType?.let { backupType ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDownloadBackups,
            title = { Text("選擇 Download/Stockify 備份") },
            text = {
                if (downloadBackupFiles.isEmpty()) {
                    Text("找不到可還原的本地備份。請先用「備份」建立檔案，或安裝檔案管理 App 後選擇其他位置的檔案。")
                } else {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        downloadBackupFiles.forEach { file ->
                            TextButton(
                                onClick = { viewModel.restoreDownloadBackup(file) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(file.displayName)
                                    Text(
                                        formatBackupTime(file.modifiedAt),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = viewModel::dismissDownloadBackups) { Text("關閉") }
            }
        )
    }

    if (showPdfTutorialDialog) {
        PdfImportTutorialDialog(
            dontShowAgain = dontShowPdfTutorialAgain,
            onDontShowAgainChange = { checked ->
                dontShowPdfTutorialAgain = checked
            },
            onDismiss = { showPdfTutorialDialog = false },
            onContinue = {
                viewModel.setSkipPdfImportTutorial(dontShowPdfTutorialAgain)
                showPdfTutorialDialog = false
                importPdfLauncher.launch("application/pdf")
            }
        )
    }

    if (showPdfPasswordDialog) {
        PdfPasswordDialog(
            password = pdfPassword,
            isLoading = isLoading,
            onPasswordChange = viewModel::updatePdfPassword,
            onConfirm = viewModel::parsePdfImport,
            onDismiss = viewModel::onPdfPasswordDialogDismiss
        )
    }

    pdfImportPreview?.let { preview ->
        PdfImportPreviewDialog(
            preview = preview,
            isLoading = isLoading,
            onReplaceImport = { viewModel.importPdfPortfolio(replaceExisting = true) },
            onAppendImport = { viewModel.importPdfPortfolio(replaceExisting = false) },
            onDismiss = viewModel::dismissPdfImportPreview
        )
    }
}

private fun formatBackupTime(timeMillis: Long?): String {
    return timeMillis?.let {
        SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(Date(it))
    } ?: "尚無備份"
}

@Composable
private fun OtherDataOperationsSection(
    viewModel: SettingsViewModel,
    accounts: List<Account>,
    activeAccountId: Int,
    isLoading: Boolean,
    googleDriveSignedIn: Boolean
) {
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showDeleteAllDataConfirmDialog by remember { mutableStateOf(false) }
    var showClearCacheConfirmDialog by remember { mutableStateOf(false) }
    var showClearHistoryPricesConfirmDialog by remember { mutableStateOf(false) }
    var showDeleteGoogleDriveDataConfirmDialog by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("其他資料操作(請警慎操作)", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { showClearCacheConfirmDialog = true },
                shape = DataManagementButtonShape
            ) {
                Text("清除即時價格快取")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { showClearHistoryPricesConfirmDialog = true },
                shape = DataManagementButtonShape
            ) {
                Text("清除圖表歷史價格資料")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { showDeleteConfirmDialog = true },
                shape = DataManagementButtonShape
            ) {
                Text("刪除全部交易資料")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { showDeleteAllDataConfirmDialog = true },
                shape = DataManagementButtonShape
            ) {
                Text("刪除全部資料")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { showDeleteGoogleDriveDataConfirmDialog = true },
                enabled = googleDriveSignedIn && !isLoading,
                shape = DataManagementButtonShape
            ) {
                Text("刪除 Google Drive 所有備份資料")
            }
            if (!googleDriveSignedIn) {
                Text("請先登入 Google Drive 才能刪除雲端資料", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("確認刪除") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("請選擇要刪除的交易範圍。帳戶資料本身不會被刪除。")
                    if (accounts.isEmpty()) {
                        Text("目前沒有帳戶資料。")
                    } else {
                        accounts.forEach { account ->
                            OutlinedButton(
                                onClick = {
                                    viewModel.deleteAccountTransactionsAndShowToast(account.id, account.name)
                                    showDeleteConfirmDialog = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = DataManagementButtonShape
                            ) {
                                val currentLabel = if (account.id == activeAccountId) "目前帳戶" else "帳戶"
                                Text("刪除${currentLabel}「${account.name}」的交易")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAllDataAndShowToast()
                    showDeleteConfirmDialog = false
                }) {
                    Text("刪除所有帳戶交易")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showDeleteAllDataConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDataConfirmDialog = false },
            title = { Text("確認刪除全部資料") },
            text = { Text("這會刪除所有持股、帳戶與排序資料，也會清除即時價格快取，且無法復原。股票代號清單不會刪除。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAllUserDataAndShowToast()
                    showDeleteAllDataConfirmDialog = false
                }) {
                    Text("全部刪除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDataConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showDeleteGoogleDriveDataConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteGoogleDriveDataConfirmDialog = false },
            title = { Text("刪除 Google Drive 所有備份資料？") },
            text = {
                Text(
                    "這會永久刪除 Google Drive 中 Stockify 此 App 專用資料夾內的所有檔案與子資料夾，" +
                        "不依檔名篩選，並會關閉自動備份。此操作無法復原，不會刪除本機資料。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteAllGoogleDriveAppData()
                        showDeleteGoogleDriveDataConfirmDialog = false
                    },
                    enabled = !isLoading
                ) {
                    Text("永久刪除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteGoogleDriveDataConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showClearCacheConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheConfirmDialog = false },
            title = { Text("確認清除快取") },
            text = { Text("這會清掉目前儲存的即時價格快取，不會影響交易資料。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearRealtimeStockInfoCache()
                    showClearCacheConfirmDialog = false
                }) {
                    Text("清除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showClearHistoryPricesConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryPricesConfirmDialog = false },
            title = { Text("確認清除圖表資料") },
            text = { Text("這會清掉所有已下載的圖表歷史價格資料，不會影響交易資料。下次開啟圖表時會重新下載。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearHistoryPriceData()
                    showClearHistoryPricesConfirmDialog = false
                }) {
                    Text("清除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryPricesConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun PdfImportTutorialDialog(
    dontShowAgain: Boolean,
    onDontShowAgainChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onContinue: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("PDF 匯出教學") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("匯入前請先使用『集保E存摺 APP』並依照下列步驟匯出 PDF。")
                PdfTutorialImage(resId = R.drawable.pdf_import_tutorial_1, step = 1)
                PdfTutorialImage(resId = R.drawable.pdf_import_tutorial_2, step = 2)
                PdfTutorialImage(resId = R.drawable.pdf_import_tutorial_3, step = 3)
                PdfTutorialImage(resId = R.drawable.pdf_import_tutorial_4, step = 4)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = dontShowAgain,
                        onCheckedChange = onDontShowAgainChange
                    )
                    Text("下次不再提醒")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text("我知道了，繼續匯入")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun PdfTutorialImage(
    resId: Int,
    step: Int
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("步驟 $step", style = MaterialTheme.typography.titleMedium)
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val context = LocalContext.current
            val density = LocalDensity.current
            val targetWidthPx = with(density) { maxWidth.roundToPx() }
            val bitmap by produceState<Bitmap?>(
                initialValue = null,
                key1 = resId,
                key2 = targetWidthPx
            ) {
                value = withContext(Dispatchers.IO) {
                    decodeSampledResource(context.resources, resId, targetWidthPx)
                }
            }

            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "PDF 匯出教學步驟 $step",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth
                )
            }
        }
    }

}

@Composable
private fun CloudBackupSection(
    viewModel: SettingsViewModel,
    isLoading: Boolean,
    googleSignInAccount: GoogleDriveAccount?,
    googleDriveAuthState: GoogleDriveAuthState,
    cloudDataBackupUpdatedAt: Long?,
    onBackupClick: () -> Unit,
    onRestoreClick: () -> Unit,
    autoBackupEnabled: Boolean,
    autoBackupIntervalDays: Int,
    autoBackupLastSuccessAt: Long?,
    autoBackupLastAttemptAt: Long?,
    autoBackupLastError: String?,
    onAutoBackupEnabledChange: (Boolean) -> Unit,
    onAutoBackupIntervalChange: (Int) -> Unit,
    onSignInClick: () -> Unit,
    onReauthorizeClick: () -> Unit,
    onRetryVerificationClick: () -> Unit,
    onSignOutClick: () -> Unit
) {
    // The primary backup time is based on the holdings data backup, not the
    // separately uploaded holdings-order backup.
    val lastBackupAt = cloudDataBackupUpdatedAt

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_google_drive),
                    contentDescription = "Google Drive",
                    tint = androidx.compose.ui.graphics.Color.Unspecified
                )
                Text("雲端備份", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(modifier = Modifier.height(12.dp))

            if (googleSignInAccount == null || googleDriveAuthState == GoogleDriveAuthState.NOT_SIGNED_IN) {
                Text("尚未登入 Google Drive")
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onSignInClick, shape = DataManagementButtonShape) {
                    Text("登入 Google")
                }
            } else {
                Text("目前帳號: ${googleSignInAccount.email}")
                Text("最後手動備份時間: ${formatBackupTime(lastBackupAt)}")
                when (googleDriveAuthState) {
                    GoogleDriveAuthState.NEEDS_REAUTHORIZATION -> {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "需要重新授權 Google Drive",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            OutlinedButton(
                                onClick = onReauthorizeClick,
                                shape = DataManagementButtonShape
                            ) {
                                Text("重新授權")
                            }
                        }
                    }
                    GoogleDriveAuthState.TEMPORARILY_UNAVAILABLE -> {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "雲端連線暫時無法驗證",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            OutlinedButton(
                                onClick = onRetryVerificationClick,
                                shape = DataManagementButtonShape
                            ) {
                                Text("重試連線")
                            }
                        }
                    }
                    GoogleDriveAuthState.AUTHORIZED,
                    GoogleDriveAuthState.NOT_SIGNED_IN -> {
                        // 正常已授權，無須額外警告
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onBackupClick,
                        enabled = !isLoading && googleDriveAuthState != GoogleDriveAuthState.NEEDS_REAUTHORIZATION,
                        shape = DataManagementButtonShape
                    ) {
                        BackupRestoreLabel("雲端備份", "備份")
                    }
                    Button(
                        onClick = onRestoreClick,
                        enabled = !isLoading && googleDriveAuthState != GoogleDriveAuthState.NEEDS_REAUTHORIZATION,
                        shape = DataManagementButtonShape
                    ) {
                        BackupRestoreLabel("雲端還原", "還原")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onSignOutClick) {
                    Text("登出")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("離開 App 嘗試自動備份", style = MaterialTheme.typography.titleMedium)
                    Text("(僅在帳號登入並正常關閉 APP 時執行)", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = autoBackupEnabled,
                    onCheckedChange = onAutoBackupEnabledChange,
                    enabled = (googleSignInAccount != null && googleDriveAuthState != GoogleDriveAuthState.NOT_SIGNED_IN) || autoBackupEnabled
                )
            }
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 32.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        modifier = Modifier.width(48.dp),
                        selected = autoBackupIntervalDays == 0,
                        onClick = { onAutoBackupIntervalChange(0) },
                        enabled = autoBackupEnabled
                    )
                    Text("每次關閉都自動備份")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf(1 to "1 天", 3 to "3 天", 7 to "1 週").forEach { (days, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                modifier = Modifier.width(48.dp),
                                selected = autoBackupIntervalDays == days,
                                onClick = { onAutoBackupIntervalChange(days) },
                                enabled = autoBackupEnabled
                            )
                            Text(label)
                        }
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "至多更新一次",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (googleSignInAccount != null && googleDriveAuthState != GoogleDriveAuthState.NOT_SIGNED_IN) {
                Text("最後自動備份成功：${formatBackupTime(autoBackupLastSuccessAt)}")
                val error = autoBackupLastError
                if (error != null) {
                    val attemptTimeText = autoBackupLastAttemptAt?.takeIf { it > 0 }?.let { "（${formatBackupTime(it)}）" }.orEmpty()
                    Text("上次自動備份失敗$attemptTimeText：$error", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun LocalBackupSection(
    viewModel: SettingsViewModel,
    isLoading: Boolean,
    exportCsvLauncher: ActivityResultLauncher<String>,
    exportCsvFallbackLauncher: ActivityResultLauncher<String>,
    importCsvLauncher: ActivityResultLauncher<String>,
    exportAccountsLauncher: ActivityResultLauncher<String>,
    exportAccountsFallbackLauncher: ActivityResultLauncher<String>,
    importAccountsLauncher: ActivityResultLauncher<String>,
    exportHoldingsOrderLauncher: ActivityResultLauncher<String>,
    exportHoldingsOrderFallbackLauncher: ActivityResultLauncher<String>,
    importHoldingsOrderLauncher: ActivityResultLauncher<String>,
    launchCreateDocumentSafely: (ActivityResultLauncher<String>, ActivityResultLauncher<String>, String, () -> Unit) -> Unit,
    launchImportSafely: (ActivityResultLauncher<String>, String, String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("本地備份", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(16.dp))

            Text("持股資料", style = MaterialTheme.typography.titleMedium)
            BackupButtonRow(
                backupText = "備份持股",
                restoreText = "還原持股",
                isLoading = isLoading,
                onBackup = {
                    val fileName = "stockify_backup_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.csv"
                    launchCreateDocumentSafely(
                        exportCsvLauncher,
                        exportCsvFallbackLauncher,
                        fileName,
                        viewModel::exportTransactionsToDownloads
                    )
                },
                onRestore = { launchImportSafely(importCsvLauncher, "*/*", "transactions") }
            )

            Spacer(modifier = Modifier.height(12.dp))
            Text("帳戶資料（名稱與手續費設定）", style = MaterialTheme.typography.titleMedium)
            BackupButtonRow(
                backupText = "備份帳戶",
                restoreText = "還原帳戶",
                isLoading = isLoading,
                onBackup = {
                    val fileName = "stockify_accounts_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.json"
                    launchCreateDocumentSafely(
                        exportAccountsLauncher,
                        exportAccountsFallbackLauncher,
                        fileName,
                        viewModel::exportAccountsToDownloads
                    )
                },
                onRestore = { launchImportSafely(importAccountsLauncher, "application/json", "accounts") }
            )

            Spacer(modifier = Modifier.height(12.dp))
            Text("排序資料", style = MaterialTheme.typography.titleMedium)
            BackupButtonRow(
                backupText = "備份排序",
                restoreText = "還原排序",
                isLoading = isLoading,
                onBackup = {
                    val fileName = "stockify_holdings_order_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.json"
                    launchCreateDocumentSafely(
                        exportHoldingsOrderLauncher,
                        exportHoldingsOrderFallbackLauncher,
                        fileName,
                        viewModel::exportHoldingsOrderToDownloads
                    )
                },
                onRestore = { launchImportSafely(importHoldingsOrderLauncher, "*/*", "order") }
            )
        }
    }
}

@Composable
private fun BackupButtonRow(
    backupText: String,
    restoreText: String,
    isLoading: Boolean,
    onBackup: () -> Unit,
    onRestore: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onBackup, enabled = !isLoading, shape = DataManagementButtonShape) {
            BackupRestoreLabel(backupText, "備份")
        }
        Button(onClick = onRestore, enabled = !isLoading, shape = DataManagementButtonShape) {
            BackupRestoreLabel(restoreText, "還原")
        }
    }
}

@Composable
private fun BackupRestoreLabel(text: String, emphasizedWord: String) {
    val wordStart = text.indexOf(emphasizedWord)
    Text(
        buildAnnotatedString {
            if (wordStart < 0) {
                append(text)
            } else {
                append(text.substring(0, wordStart))
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(emphasizedWord) }
                append(text.substring(wordStart + emphasizedWord.length))
            }
        }
    )
}

@Composable
private fun ExternalImportSection(
    viewModel: SettingsViewModel,
    isLoading: Boolean,
    onImportPdfClick: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("外部匯入", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(12.dp))
            Text("從集保 E 存摺匯入庫存資料")
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onImportPdfClick, enabled = !isLoading, shape = DataManagementButtonShape) {
                Text("匯入集保 E 存摺庫存 (PDF)")
            }
        }
    }
}

@Composable
private fun PdfPasswordDialog(
    password: String,
    isLoading: Boolean,
    onPasswordChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("匯入 PDF 庫存") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("請輸入 PDF 密碼，APP會嘗試解密、抽取文字，再依照目前現價當作成本、整理股票代號與庫存。")
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text("PDF 密碼") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = password.isNotBlank() && !isLoading
            ) {
                Text("解析")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun PdfImportPreviewDialog(
    preview: PdfStockImportPreview,
    isLoading: Boolean,
    onReplaceImport: () -> Unit,
    onAppendImport: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("PDF 匯入預覽") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("已抽取 ${preview.extractedTextLength} 個字元，解析出 ${preview.items.size} 檔股票。")
                Text("匯入時會建立買進快照交易，價格使用目前抓到的現價，手續費固定為 0。")
                LazyColumn(
                    modifier = Modifier.height(260.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        count = preview.items.size,
                        key = { index ->
                            val item = preview.items[index]
                            "${item.stockCode}#$index"
                        }
                    ) { index ->
                        val item = preview.items[index]
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                val title = if (item.stockName.isBlank()) {
                                    item.stockCode
                                } else {
                                    "${item.stockCode} ${item.stockName}"
                                }

                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text("庫存: ${String.format(Locale.US, "%,d", item.balance)} 股")
                                Text(
                                    if (item.currentPrice != null) {
                                        "現價: ${String.format(Locale.US, "%.2f", item.currentPrice)}，市值約 ${String.format(Locale.US, "%,.0f", item.marketValue ?: 0.0)}"
                                    } else {
                                        "現價: 抓取失敗，這筆資料匯入時會略過"
                                    }
                                )
                            }
                        }
                    }
                }
                Text("要用替代方式還是新增方式把目前 PDF 的庫存匯入 App？")
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onReplaceImport,
                    enabled = !isLoading
                ) {
                    Text("替代匯入")
                }
                TextButton(
                    onClick = onAppendImport,
                    enabled = !isLoading
                ) {
                    Text("新增匯入")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
