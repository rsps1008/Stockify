package com.rsps1008.stockify.ui.screens

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.rsps1008.stockify.R
import com.rsps1008.stockify.StockifyApplication
import com.rsps1008.stockify.data.PdfStockImportPreview
import com.rsps1008.stockify.ui.viewmodel.SettingsViewModel
import com.rsps1008.stockify.ui.viewmodel.ViewModelFactory
import kotlinx.coroutines.flow.collectLatest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DataManagementScreen() {
    val application = LocalContext.current.applicationContext as StockifyApplication
    val viewModel: SettingsViewModel = viewModel(
        factory = ViewModelFactory(
            stockDao = application.database.stockDao(),
            settingsDataStore = application.settingsDataStore,
            application = application,
            realtimeStockDataService = application.realtimeStockDataService
        )
    )

    val isLoading by viewModel.isLoading.collectAsState()
    val message by viewModel.message.collectAsState()
    val showImportConfirmDialog by viewModel.showImportConfirmDialog.collectAsState()
    val googleSignInAccount by viewModel.googleSignInAccount.collectAsState()
    val showPdfPasswordDialog by viewModel.showPdfPasswordDialog.collectAsState()
    val pdfPassword by viewModel.pdfPassword.collectAsState()
    val pdfImportPreview by viewModel.pdfImportPreview.collectAsState()
    val skipPdfImportTutorial by viewModel.skipPdfImportTutorial.collectAsState()

    val context = LocalContext.current
    var showPdfTutorialDialog by remember { mutableStateOf(false) }
    var dontShowPdfTutorialAgain by remember(skipPdfImportTutorial) {
        mutableStateOf(skipPdfImportTutorial)
    }

    val googleSignInClient = remember {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_APPDATA))
            .build()
        GoogleSignIn.getClient(context, gso)
    }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.let(viewModel::handleSignInResult)
            }
        }
    )

    val exportCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
        onResult = { uri: Uri? -> uri?.let(viewModel::exportTransactions) }
    )

    val importCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? -> uri?.let(viewModel::onImportRequest) }
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

    LaunchedEffect(viewModel) {
        viewModel.onSignOut.collectLatest {
            googleSignInClient.signOut().addOnCompleteListener { viewModel.onSignOutComplete() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = R.drawable.stockify),
            contentDescription = "Stockify Logo",
            modifier = Modifier.fillMaxWidth(0.35f)
        )
        Spacer(modifier = Modifier.height(6.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                DataManagementSection(
                    viewModel = viewModel,
                    isLoading = isLoading,
                    exportCsvLauncher = exportCsvLauncher,
                    importCsvLauncher = importCsvLauncher,
                    googleSignInAccount = googleSignInAccount,
                    onSignInClick = { googleSignInLauncher.launch(googleSignInClient.signInIntent) },
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
        }
    }

    if (showImportConfirmDialog) {
        AlertDialog(
            onDismissRequest = viewModel::onImportCancel,
            title = { Text("匯入確認") },
            text = { Text("要先清空現有交易資料，再匯入這份 CSV 嗎？") },
            confirmButton = {
                TextButton(onClick = { viewModel.onImportConfirm(true) }) {
                    Text("清空後匯入")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.onImportConfirm(false) }) {
                        Text("直接新增")
                    }
                    TextButton(onClick = viewModel::onImportCancel) {
                        Text("取消")
                    }
                }
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

@Composable
private fun DataManagementSection(
    viewModel: SettingsViewModel,
    isLoading: Boolean,
    exportCsvLauncher: ActivityResultLauncher<String>,
    importCsvLauncher: ActivityResultLauncher<String>,
    googleSignInAccount: GoogleSignInAccount?,
    onSignInClick: () -> Unit,
    onImportPdfClick: () -> Unit
) {
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showClearCacheConfirmDialog by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("資料管理", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(16.dp))

            Text("Google Drive 備份", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            if (googleSignInAccount == null) {
                Button(onClick = onSignInClick) {
                    Text("登入 Google")
                }
            } else {
                Text("目前帳號: ${googleSignInAccount.email}")
                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = viewModel::backupToGoogleDrive,
                    enabled = !isLoading
                ) {
                    Text("備份到 Google Drive")
                }
                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = viewModel::restoreFromGoogleDrive,
                    enabled = !isLoading
                ) {
                    Text("從 Google Drive 還原")
                }
                Spacer(modifier = Modifier.height(8.dp))

                Button(onClick = viewModel::signOut) {
                    Text("登出")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text("匯入與匯出", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                        val fileName = "stockify_backup_${sdf.format(Date())}.csv"
                        exportCsvLauncher.launch(fileName)
                    },
                    enabled = !isLoading
                ) {
                    Text("匯出 CSV")
                }

                Button(
                    onClick = { importCsvLauncher.launch("*/*") },
                    enabled = !isLoading
                ) {
                    Text("匯入 CSV")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onImportPdfClick,
                enabled = !isLoading
            ) {
                Text("匯入『集保E存摺』 庫存 (PDF)")
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("其他資料操作", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = { showClearCacheConfirmDialog = true }) {
                Text("清除即時價格快取")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { showDeleteConfirmDialog = true }) {
                Text("刪除全部交易資料")
            }
        }
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("確認刪除") },
            text = { Text("這會刪除所有交易資料，且無法復原。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAllDataAndShowToast()
                    showDeleteConfirmDialog = false
                }) {
                    Text("刪除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
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
        Image(
            painter = painterResource(id = resId),
            contentDescription = "PDF 匯出教學步驟 $step",
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.FillWidth
        )
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
                    items(preview.items.size) { index ->
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
                                Text("庫存: ${String.format("%,d", item.balance)} 股")
                                Text(
                                    if (item.currentPrice != null) {
                                        "現價: ${String.format("%.2f", item.currentPrice)}，市值約 ${String.format("%,.0f", item.marketValue ?: 0.0)}"
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
