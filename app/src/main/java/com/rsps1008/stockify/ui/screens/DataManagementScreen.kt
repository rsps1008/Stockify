package com.rsps1008.stockify.ui.screens

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.rsps1008.stockify.R
import com.rsps1008.stockify.StockifyApplication
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

    val context = LocalContext.current

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
                result.data?.let { viewModel.handleSignInResult(it) }
            }
        }
    )

    val exportCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
        onResult = { uri: Uri? -> uri?.let { viewModel.exportTransactions(it) } }
    )

    val importCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? -> uri?.let { viewModel.onImportRequest(it) } }
    )

    // Toast message
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.onMessageShown()
        }
    }

    // Sign-out handler
    LaunchedEffect(viewModel) {
        viewModel.onSignOut.collectLatest {
            googleSignInClient.signOut().addOnCompleteListener { viewModel.onSignOutComplete() }
        }
    }

    // ===== UI (像 SettingsScreen) =====
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 固定 Logo，不捲動
        Image(
            painter = painterResource(id = R.drawable.stockify),
            contentDescription = "Stockify Logo",
            modifier = Modifier.fillMaxWidth(0.35f)
        )
        Spacer(modifier = Modifier.height(16.dp))

        // 主體可捲動 LazyColumn
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ===== 資料管理區塊 =====
            item {
                DataManagementSection(
                    viewModel = viewModel,
                    isLoading = isLoading,
                    exportCsvLauncher = exportCsvLauncher,
                    importCsvLauncher = importCsvLauncher,
                    googleSignInAccount = googleSignInAccount,
                    onSignInClick = { googleSignInLauncher.launch(googleSignInClient.signInIntent) }
                )
            }
        }
    }

    // 匯入確認 Dialog
    if (showImportConfirmDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.onImportCancel() },
            title = { Text("匯入確認") },
            text = { Text("匯入新資料前是否要刪除所有現有交易紀錄？") },
            confirmButton = {
                TextButton(onClick = { viewModel.onImportConfirm(true) }) { Text("是，刪除並匯入") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.onImportConfirm(false) }) { Text("否，直接匯入") }
                    TextButton(onClick = { viewModel.onImportCancel() }) { Text("取消") }
                }
            }
        )
    }
}

@Composable
private fun DataManagementSection(
    viewModel: SettingsViewModel,
    isLoading: Boolean,
    exportCsvLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    importCsvLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    googleSignInAccount: GoogleSignInAccount?,
    onSignInClick: () -> Unit
) {
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showClearCacheConfirmDialog by remember { mutableStateOf(false) }

    // ===== 卡片 (像 SettingsScreen) =====
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {

            Text("備份管理", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(16.dp))

            // --- 雲端備份 ---
            Text("雲端備份", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            if (googleSignInAccount == null) {
                Button(onClick = onSignInClick) {
                    Text("登入 Google 帳號")
                }
            } else {
                Text("已登入: ${googleSignInAccount.email}")
                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = { viewModel.backupToGoogleDrive() },
                    enabled = !isLoading
                ) {
                    Text("備份到 Google Drive")
                }
                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = { viewModel.restoreFromGoogleDrive() },
                    enabled = !isLoading
                ) {
                    Text("從 Google Drive 還原")
                }
                Spacer(modifier = Modifier.height(8.dp))

                Button(onClick = { viewModel.signOut() }) {
                    Text("登出")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- 本地儲存 ---
            Text("手機本地資料", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                    val fileName = "stockify_backup_${sdf.format(Date())}.csv"
                    exportCsvLauncher.launch(fileName)
                }) {
                    Text("匯出 CSV")
                }

                Button(onClick = {
                    importCsvLauncher.launch("*/*")
                }) {
                    Text("匯入 CSV")
                }
            }
        }
    }

    // 第二張卡片 (清除快取、刪除紀錄)
    Spacer(modifier = Modifier.height(16.dp))

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("本地資料管理", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = { showClearCacheConfirmDialog = true }) {
                Text("清除股價快取")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { showDeleteConfirmDialog = true }) {
                Text("刪除所有交易紀錄")
            }
        }
    }

    // Dialogs
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("確認刪除") },
            text = { Text("刪除所有交易紀錄，此動作無法復原。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAllDataAndShowToast()
                    showDeleteConfirmDialog = false
                }) { Text("刪除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) { Text("取消") }
            }
        )
    }

    if (showClearCacheConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheConfirmDialog = false },
            title = { Text("確認清除") },
            text = { Text("刪除本地股價快取，下次將重新抓取。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearRealtimeStockInfoCache()
                    showClearCacheConfirmDialog = false
                }) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheConfirmDialog = false }) { Text("取消") }
            }
        )
    }
}