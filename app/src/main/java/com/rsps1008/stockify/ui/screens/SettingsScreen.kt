package com.rsps1008.stockify.ui.screens

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.rsps1008.stockify.StockifyApplication
import com.rsps1008.stockify.ui.viewmodel.SettingsViewModel
import com.rsps1008.stockify.ui.viewmodel.ViewModelFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val application = LocalContext.current.applicationContext as StockifyApplication
    val viewModel: SettingsViewModel = viewModel(
        factory = ViewModelFactory(
            stockDao = application.database.stockDao(),
            settingsDataStore = application.settingsDataStore,
            application = application
        )
    )

    val isLoading by viewModel.isLoading.collectAsState()
    val message by viewModel.message.collectAsState()
    val lastUpdateTime by viewModel.lastStockListUpdateTime.collectAsState()
    val showImportConfirmDialog by viewModel.showImportConfirmDialog.collectAsState()
    val googleSignInAccount by viewModel.googleSignInAccount.collectAsState()

    val feeDiscount by viewModel.feeDiscount.collectAsState()
    val minFeeRegular by viewModel.minFeeRegular.collectAsState()
    val minFeeOddLot by viewModel.minFeeOddLot.collectAsState()
    val preDeductSellFees by viewModel.preDeductSellFees.collectAsState()
    val yahooFetchInterval by viewModel.yahooFetchInterval.collectAsState()
    val theme by viewModel.theme.collectAsState()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val googleSignInClient = remember {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            // 建議改用 DRIVE_APPDATA，這樣使用者權限請求比較不敏感
            .requestScopes(Scope(DriveScopes.DRIVE_APPDATA))
            .build()
        GoogleSignIn.getClient(context, gso)
    }

    // Launcher for Google Sign-In
    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            // 加入這行 Log 檢查 resultCode
            println("GoogleSignInLauncher: resultCode = ${result.resultCode}")
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.let { viewModel.handleSignInResult(it) }
            } else {
                // 如果失敗，通常會在這裡印出 resultCode 為 0 (Canceled)
                println("GoogleSignInLauncher: Sign-in failed or canceled")
            }
        }
    )

    // Launcher for CSV export (create file)
    val exportCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
        onResult = { uri: Uri? ->
            uri?.let {
                viewModel.exportTransactions(it)
            }
        }
    )

    // Launcher for CSV import (pick file)
    val importCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? ->
            uri?.let {
                viewModel.onImportRequest(it)
            }
        }
    )

    if (showImportConfirmDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.onImportCancel() },
            title = { Text("匯入確認") },
            text = { Text("匯入新資料前，是否要先刪除所有現有交易紀錄？") },
            confirmButton = {
                TextButton(onClick = { viewModel.onImportConfirm(true) }) {
                    Text("是，刪除並匯入")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.onImportConfirm(false) }) {
                        Text("否，直接匯入")
                    }
                    TextButton(onClick = { viewModel.onImportCancel() }) {
                        Text("取消")
                    }
                }
            }
        )
    }

    LaunchedEffect(key1 = viewModel) {
        viewModel.onSignOut.collectLatest {
            googleSignInClient.signOut().addOnCompleteListener {
                viewModel.onSignOutComplete()
            }
        }
    }

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.onMessageShown() // Reset message after showing
        }
    }

    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("外觀", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(16.dp))

                val themeOptions = listOf("System", "Light", "Dark")
                var expanded by remember { mutableStateOf(false) }

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = theme,
                        onValueChange = { },
                        label = { Text("主題") },
                        readOnly = true,
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                        },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        themeOptions.forEach { theme ->
                            DropdownMenuItem(
                                text = { Text(theme) },
                                onClick = {
                                    scope.launch {
                                        delay(300) // Delay to allow dropdown to close
                                        viewModel.setTheme(theme)
                                    }
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        }
        // Stock Data Update Section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("股票資料更新", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.updateStockListFromTwse() }, enabled = !isLoading) {
                        Text("更新股票列表")
                    }
                    if (isLoading) {
                        CircularProgressIndicator()
                    }
                    val updateTimeText = lastUpdateTime?.let {
                        "(${formatTimestamp(it)})"
                    } ?: "(預設列表)"
                    Text(
                        text = updateTimeText,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                val intervalOptions = listOf(10, 15, 30, 60)
                var expanded by remember { mutableStateOf(false) }

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = "$yahooFetchInterval 秒",
                        onValueChange = { },
                        label = { Text("Yahoo 資料更新頻率") },
                        readOnly = true,
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                        },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        intervalOptions.forEach { interval ->
                            DropdownMenuItem(
                                text = { Text("$interval 秒") },
                                onClick = {
                                    viewModel.setYahooFetchInterval(interval)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        // P&L Calculation Settings Section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("損益計算設定", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("預先扣除賣出費用與稅金", modifier = Modifier.weight(1f))
                    Switch(
                        checked = preDeductSellFees,
                        onCheckedChange = { viewModel.setPreDeductSellFees(it) }
                    )
                }
            }
        }

        // Fee Settings Section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("手續費設定", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(16.dp))

                var feeDiscountText by remember { mutableStateOf(feeDiscount.toString()) }
                LaunchedEffect(feeDiscount) { feeDiscountText = feeDiscount.toString() }
                OutlinedTextField(
                    value = feeDiscountText,
                    onValueChange = {
                        feeDiscountText = it
                        it.toDoubleOrNull()?.let { discount -> viewModel.setFeeDiscount(discount) }
                    },
                    label = { Text("手續費折數 (例如 0.28)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                var minFeeRegularText by remember { mutableStateOf(minFeeRegular.toString()) }
                LaunchedEffect(minFeeRegular) { minFeeRegularText = minFeeRegular.toString() }
                OutlinedTextField(
                    value = minFeeRegularText,
                    onValueChange = {
                        minFeeRegularText = it
                        it.toIntOrNull()?.let { fee -> viewModel.setMinFeeRegular(fee) }
                    },
                    label = { Text("整股最低手續費 (元)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                var minFeeOddLotText by remember { mutableStateOf(minFeeOddLot.toString()) }
                LaunchedEffect(minFeeOddLot) { minFeeOddLotText = minFeeOddLot.toString() }
                OutlinedTextField(
                    value = minFeeOddLotText,
                    onValueChange = {
                        minFeeOddLotText = it
                        it.toIntOrNull()?.let { fee -> viewModel.setMinFeeOddLot(fee) }
                    },
                    label = { Text("零股最低手續費 (元)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

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

@Composable
private fun DataManagementSection(
    viewModel: SettingsViewModel,
    isLoading: Boolean,
    exportCsvLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    importCsvLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    googleSignInAccount: GoogleSignInAccount?,
    onSignInClick: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("資料管理", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(16.dp))

            if (googleSignInAccount == null) {
                Button(onClick = onSignInClick) {
                    Text("登入 Google 帳號")
                }
            } else {
                Text("已登入: ${googleSignInAccount.email}", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { viewModel.signOut() }) {
                    Text("登出")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                    val fileName = "stockify_backup_${sdf.format(Date())}.csv"
                    exportCsvLauncher.launch(fileName)
                }, enabled = !isLoading) {
                    Text("匯出 CSV")
                }
                Button(onClick = {
                    importCsvLauncher.launch("*/*")
                }, enabled = !isLoading) {
                    Text("匯入 CSV")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.backupToGoogleDrive() }, 
                    enabled = !isLoading && googleSignInAccount != null
                ) {
                    Text("備份到 Google Drive")
                }
                Button(
                    onClick = { viewModel.restoreFromGoogleDrive() }, 
                    enabled = !isLoading && googleSignInAccount != null
                ) {
                    Text("從 Google Drive 還原")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { viewModel.deleteAllData() }) {
                Text("刪除所有資料")
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
