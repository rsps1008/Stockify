package com.rsps1008.stockify.ui.screens

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
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
            application = application,
            realtimeStockDataService = application.realtimeStockDataService
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
    val dividendFee by viewModel.dividendFee.collectAsState()
    val preDeductSellFees by viewModel.preDeductSellFees.collectAsState()
    val yahooFetchInterval by viewModel.yahooFetchInterval.collectAsState()
    val theme by viewModel.theme.collectAsState()
    val stockDataSource by viewModel.stockDataSource.collectAsState()
    val notifyFallbackRepeatedly by viewModel.notifyFallbackRepeatedly.collectAsState()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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
            println("GoogleSignInLauncher: resultCode = ${result.resultCode}")
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.let { viewModel.handleSignInResult(it) }
            } else {
                println("GoogleSignInLauncher: Sign-in failed or canceled")
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

    if (showImportConfirmDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.onImportCancel() },
            title = { Text("匯入確認") },
            text = { Text("匯入新資料前，是否要先刪除所有現有交易紀錄？") },
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

    LaunchedEffect(key1 = viewModel) {
        viewModel.onSignOut.collectLatest {
            googleSignInClient.signOut().addOnCompleteListener { viewModel.onSignOutComplete() }
        }
    }

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.onMessageShown()
        }
    }

    // ===== 版面：上方固定 Logo，下面才捲動 =====
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
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("外觀", style = MaterialTheme.typography.titleLarge)
                        Spacer(modifier = Modifier.height(16.dp))

                        val themeOptions = remember {
                            mapOf("System" to "系統預設", "Light" to "淺色", "Dark" to "深色")
                        }
                        var expanded by remember { mutableStateOf(false) }

                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = !expanded },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = themeOptions[theme] ?: theme,
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
                                themeOptions.forEach { (key, value) ->
                                    DropdownMenuItem(
                                        text = { Text(value) },
                                        onClick = {
                                            scope.launch {
                                                delay(300)
                                                viewModel.setTheme(key)
                                            }
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("股票資料更新", style = MaterialTheme.typography.titleLarge)
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(onClick = { viewModel.updateStockListFromTwse() }, enabled = !isLoading) {
                                Text("更新股票列表")
                            }
                            if (isLoading) CircularProgressIndicator()

                            val updateTimeText = lastUpdateTime?.let { "(${formatTimestamp(it)})" } ?: "(預設列表)"
                            Text(text = updateTimeText, style = MaterialTheme.typography.bodySmall)
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        val dataSourceOptions = remember {
                            mapOf("TWSE" to "台灣證券交易所(推薦)", "Yahoo" to "Yahoo!奇摩股市(爬蟲)")
                        }
                        var expandedDataSource by remember { mutableStateOf(false) }

                        ExposedDropdownMenuBox(
                            expanded = expandedDataSource,
                            onExpandedChange = { expandedDataSource = !expandedDataSource },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = dataSourceOptions[stockDataSource] ?: stockDataSource,
                                onValueChange = { },
                                label = { Text("即時資料來源") },
                                readOnly = true,
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedDataSource)
                                },
                                modifier = Modifier.menuAnchor().fillMaxWidth(),
                                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                            )
                            ExposedDropdownMenu(
                                expanded = expandedDataSource,
                                onDismissRequest = { expandedDataSource = false },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                dataSourceOptions.forEach { (key, value) ->
                                    DropdownMenuItem(
                                        text = { Text(value) },
                                        onClick = {
                                            viewModel.setStockDataSource(key)
                                            expandedDataSource = false
                                        }
                                    )
                                }
                            }
                        }

                        Text(
                            text = "*如果選擇的來源不可用，將自動採用另一個作為備用機制。",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f, fill = false)) {
                                Text("重複提示備援來源", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "當主要資料來源失效時，持續顯示通知",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = notifyFallbackRepeatedly,
                                onCheckedChange = viewModel::setNotifyFallbackRepeatedly
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        val intervalOptions = listOf(10, 15, 30, 60)
                        var expandedInterval by remember { mutableStateOf(false) }

                        ExposedDropdownMenuBox(
                            expanded = expandedInterval,
                            onExpandedChange = { expandedInterval = !expandedInterval },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = "$yahooFetchInterval 秒",
                                onValueChange = { },
                                label = { Text("股價更新頻率(開盤刷新)") },
                                readOnly = true,
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedInterval)
                                },
                                modifier = Modifier.menuAnchor().fillMaxWidth(),
                                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                            )
                            ExposedDropdownMenu(
                                expanded = expandedInterval,
                                onDismissRequest = { expandedInterval = false },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                intervalOptions.forEach { interval ->
                                    DropdownMenuItem(
                                        text = { Text("$interval 秒") },
                                        onClick = {
                                            viewModel.setYahooFetchInterval(interval)
                                            expandedInterval = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
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
            }

            item {
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

                        Spacer(modifier = Modifier.height(8.dp))

                        var dividendFeeText by remember { mutableStateOf(dividendFee.toString()) }
                        LaunchedEffect(dividendFee) { dividendFeeText = dividendFee.toString() }
                        OutlinedTextField(
                            value = dividendFeeText,
                            onValueChange = {
                                dividendFeeText = it
                                it.toIntOrNull()?.let { fee -> viewModel.setDividendFee(fee) }
                            },
                            label = { Text("除息手續費 (元)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

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

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("確認刪除") },
            text = { Text("這會刪除所有交易紀錄，但會保留股票基本資料。此動作無法復原。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteAllDataAndShowToast()
                        showDeleteConfirmDialog = false
                    }
                ) {
                    Text("刪除交易紀錄")
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
            title = { Text("確認清除") },
            text = { Text("這會刪除本地儲存的即時股價快取，下次開啟 App 時將會重新抓取。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearRealtimeStockInfoCache()
                        showClearCacheConfirmDialog = false
                    }
                ) {
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

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("資料管理", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(16.dp))

            // 雲端備份
            Text("雲端備份", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            if (googleSignInAccount == null) {
                Button(onClick = onSignInClick) {
                    Text("登入 Google 帳號")
                }
            } else {
                Text("已登入: ${googleSignInAccount.email}", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.backupToGoogleDrive() },
                        enabled = !isLoading
                    ) {
                        Text("備份到 Google Drive")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.restoreFromGoogleDrive() },
                        enabled = !isLoading
                    ) {
                        Text("從 Google Drive 還原")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { viewModel.signOut() }) {
                    Text("登出")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 手機儲存
            Text("手機本地資料", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
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
            Button(onClick = { showClearCacheConfirmDialog = true }) {
                Text("清除股價快取")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { showDeleteConfirmDialog = true }) {
                Text("刪除所有交易紀錄")
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
