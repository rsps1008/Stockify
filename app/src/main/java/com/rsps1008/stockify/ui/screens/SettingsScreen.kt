package com.rsps1008.stockify.ui.screens

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
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
import com.rsps1008.stockify.R
import com.rsps1008.stockify.StockifyApplication
import com.rsps1008.stockify.ui.viewmodel.SettingsViewModel
import com.rsps1008.stockify.ui.viewmodel.ViewModelFactory
import kotlinx.coroutines.delay
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

    val feeDiscount by viewModel.feeDiscount.collectAsState()
    val minFeeRegular by viewModel.minFeeRegular.collectAsState()
    val minFeeOddLot by viewModel.minFeeOddLot.collectAsState()
    val dividendFee by viewModel.dividendFee.collectAsState()
    val preDeductSellFees by viewModel.preDeductSellFees.collectAsState()
    val fetchInterval by viewModel.fetchInterval.collectAsState()
    val theme by viewModel.theme.collectAsState()
    val stockDataSource by viewModel.stockDataSource.collectAsState()
    val notifyFallbackRepeatedly by viewModel.notifyFallbackRepeatedly.collectAsState()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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
        Spacer(modifier = Modifier.height(6.dp))

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
                        Text(
                            text = "*如果有新上市的股票可以自動新增。",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )

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
                                    style = MaterialTheme.typography.bodySmall,
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
                                value = "$fetchInterval 秒",
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
                                            viewModel.setFetchInterval(interval)
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
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
