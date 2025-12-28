package com.rsps1008.stockify.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rsps1008.stockify.StockifyApplication
import com.rsps1008.stockify.ui.viewmodel.SettingsViewModel
import com.rsps1008.stockify.ui.viewmodel.ViewModelFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    val feeDiscount by viewModel.feeDiscount.collectAsState()
    val minFeeRegular by viewModel.minFeeRegular.collectAsState()
    val minFeeOddLot by viewModel.minFeeOddLot.collectAsState()
    val preDeductSellFees by viewModel.preDeductSellFees.collectAsState()

    val context = LocalContext.current

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
                }
                Spacer(modifier = Modifier.height(8.dp))
                lastUpdateTime?.let {
                    Text("上次更新時間：${formatTimestamp(it)}", style = MaterialTheme.typography.bodySmall)
                } ?: Text("尚未更新過股票列表", style = MaterialTheme.typography.bodySmall)
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

        // Data Management Section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("資料管理", style = MaterialTheme.typography.titleLarge)
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
                Button(onClick = { viewModel.deleteAllData() }) {
                    Text("刪除所有資料")
                }
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
