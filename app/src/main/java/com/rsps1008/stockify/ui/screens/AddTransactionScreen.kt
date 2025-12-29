package com.rsps1008.stockify.ui.screens

import android.widget.Toast
import kotlin.math.roundToInt
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
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.rsps1008.stockify.StockifyApplication
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import com.rsps1008.stockify.ui.viewmodel.AddTransactionViewModel
import com.rsps1008.stockify.ui.viewmodel.ViewModelFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTransactionScreen(navController: NavController, transactionId: Int?) {
    val application = LocalContext.current.applicationContext as StockifyApplication
    val viewModel: AddTransactionViewModel = viewModel(
        factory = ViewModelFactory(
            stockDao = application.database.stockDao(),
            settingsDataStore = application.settingsDataStore,
            transactionId = transactionId,
            application = application,
            realtimeStockDataService = application.realtimeStockDataService
        )
    )
    val context = LocalContext.current
    val allStocks by viewModel.stocks.collectAsState()
    val transactionToEdit by viewModel.transactionToEdit.collectAsState()
    val fee by viewModel.fee.collectAsState()
    val tax by viewModel.tax.collectAsState()
    val expense by viewModel.expense.collectAsState()
    val income by viewModel.income.collectAsState()
    var dividendFee by remember { mutableStateOf("10") }

    var stockName by remember { mutableStateOf("") }
    var stockCode by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(System.currentTimeMillis()) }
    var transactionType by remember { mutableStateOf("買進") }
    var price by remember { mutableStateOf("") } // Represents total amount for dividend, price per share for buy/sell
    var shares by remember { mutableStateOf("") } // Represents shares for buy/sell/stock_dividend

    var showDatePicker by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    // Optional fields for dividend calculation
    var cashDividend by remember { mutableStateOf("") }
    var exDividendShares by remember { mutableStateOf("") }

    // Optional fields for stock dividend calculation
    var stockDividendRate by remember { mutableStateOf("") }
    var exRightsShares by remember { mutableStateOf("") }

    LaunchedEffect(price, shares, transactionType) {
        when (transactionType) {
            "買進" -> viewModel.calculateBuyCosts(price.toDoubleOrNull() ?: 0.0, shares.toDoubleOrNull() ?: 0.0)
            "賣出" -> viewModel.calculateSellCosts(price.toDoubleOrNull() ?: 0.0, shares.toDoubleOrNull() ?: 0.0)
        }
    }

    LaunchedEffect(stockName) {
        expanded = stockName.isNotBlank() && stockCode.isBlank()
    }

    // Load data when editing an existing transaction
    LaunchedEffect(transactionToEdit, allStocks) {
        if (transactionToEdit != null && allStocks.isNotEmpty()) {
            val it = transactionToEdit!!
            val stock = allStocks.find { s -> s.code == it.stockCode }
            stockName = stock?.name ?: ""
            stockCode = stock?.code ?: ""
            date = it.date
            transactionType = it.type

            when (it.type) {
                "買進" -> {
                    price = it.buyPrice.toString()
                    shares = it.buyShares.toInt().toString()
                }
                "賣出" -> {
                    price = it.sellPrice.toString()
                    shares = it.sellShares.toInt().toString()
                }
                "配息" -> {
                    cashDividend = it.cashDividend.toString()
                    exDividendShares = it.exDividendShares.toInt().toString()
                    price = it.income.toInt().toString()
                    dividendFee = it.fee.toInt().toString()
                }
                "配股" -> {
                    stockDividendRate = it.stockDividend.toString()
                    exRightsShares = it.exRightsShares.toInt().toString()
                    shares = it.dividendShares.toInt().toString()
                }
            }
        }
    }

    // Reset fields when transaction type changes for a new transaction
    LaunchedEffect(transactionType) {
        if (transactionId == null) { // Only for new transactions
            price = ""
            shares = ""
            cashDividend = ""
            exDividendShares = ""
            stockDividendRate = ""
            exRightsShares = ""
            dividendFee = "10"
        }
    }

    // Auto-calculate total dividend amount
    LaunchedEffect(cashDividend, exDividendShares) {
        if (transactionType == "配息") {
            val pps = cashDividend.toDoubleOrNull()
            val s = exDividendShares.toDoubleOrNull()
            if (pps != null && s != null) {
                price = (pps * s).roundToInt().toString()
            }
        }
    }

    // Auto-calculate total stock dividend shares
    LaunchedEffect(stockDividendRate, exRightsShares) {
        if (transactionType == "配股") {
            val rate = stockDividendRate.toDoubleOrNull()
            val baseShares = exRightsShares.toDoubleOrNull()
            // Assuming Taiwan stock market rules: rate is NTD per 10 NTD par value share.
            // So a 1 NTD stock dividend means 100 shares for every 1000 shares held.
            if (rate != null && baseShares != null) {
                shares = (baseShares / 10 * rate).toInt().toString()
            }
        }
    }

    val isFormValid = when (transactionType) {
        "買進", "賣出" ->
            stockName.isNotBlank() &&
            stockCode.isNotBlank() &&
            (price.toDoubleOrNull() ?: 0.0) > 0.0 &&
            (shares.toLongOrNull() ?: 0L) > 0L
        "配息" ->
            stockName.isNotBlank() &&
            stockCode.isNotBlank() &&
            (price.toDoubleOrNull() ?: -1.0) >= 0.0
        "配股" ->
            stockName.isNotBlank() &&
            stockCode.isNotBlank() &&
            (shares.toLongOrNull() ?: 0L) > 0L
        else -> false
    }

    val filteredStocks = allStocks.filter {
        it.name.contains(stockName, ignoreCase = true) || it.code.contains(stockName, ignoreCase = true)
    }

    fun clearForm() {
        stockName = ""
        stockCode = ""
        transactionType = "買進" // This will trigger the LaunchedEffect to reset other fields
    }

    val onAddOrUpdateTransaction: () -> Unit = {
        viewModel.addOrUpdateTransaction(
            stockName = stockName,
            stockCode = stockCode,
            date = date,
            type = transactionType,
            price = price.toDoubleOrNull() ?: 0.0,
            shares = shares.toDoubleOrNull() ?: 0.0,
            cashDividend = cashDividend.toDoubleOrNull() ?: 0.0,
            exDividendShares = exDividendShares.toDoubleOrNull() ?: 0.0,
            stockDividend = stockDividendRate.toDoubleOrNull() ?: 0.0,
            exRightsShares = exRightsShares.toDoubleOrNull() ?: 0.0,
            dividendShares = shares.toDoubleOrNull() ?: 0.0,
            dividendFee = dividendFee.toDoubleOrNull() ?: 0.0
        )
        val message = if (transactionId == null) "新增成功" else "更新成功"
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = Modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        if (transactionId == null) {
            Text(text = "股票名稱或代號", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Box {
                OutlinedTextField(
                    value = stockName,
                    onValueChange = {
                        stockName = it
                        stockCode = ""
                    },
                    placeholder = { Text("輸入股票名稱或代號搜尋") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    },
                    colors = ExposedDropdownMenuDefaults.textFieldColors()
                )

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.fillMaxWidth(),
                    properties = PopupProperties(focusable = false)  // ✅ 讓選單不要搶焦點
                ) {
                    filteredStocks.take(5).forEach { selectionOption ->
                        DropdownMenuItem(
                            text = { Text("${selectionOption.code} ${selectionOption.name}") },
                            onClick = {
                                stockName = selectionOption.name
                                stockCode = selectionOption.code
                                expanded = false
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            LabeledOutlinedTextField(
                label = "股票代號",
                value = stockCode,
                onValueChange = {},
                readOnly = true
            )
        } else {
            LabeledOutlinedTextField(label = "股票", value = "$stockCode $stockName", onValueChange = {}, readOnly = true)
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "交易日期", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Button(onClick = { showDatePicker = true }) {
            Text(text = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(date)))
        }
        if (showDatePicker) {
            val datePickerState = rememberDatePickerState(initialSelectedDateMillis = date)
            DatePickerDialog(onDismissRequest = { showDatePicker = false }, confirmButton = { TextButton(onClick = { date = datePickerState.selectedDateMillis!!; showDatePicker = false }) { Text("確定") } }, dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } }) {
                DatePicker(state = datePickerState)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "交易類型", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { transactionType = "買進" }, enabled = transactionType != "買進") { Text("買進") }
            Button(onClick = { transactionType = "賣出" }, enabled = transactionType != "賣出") { Text("賣出") }
            Button(onClick = { transactionType = "配息" }, enabled = transactionType != "配息") { Text("配息") }
            Button(onClick = { transactionType = "配股" }, enabled = transactionType != "配股") { Text("配股") }
        }
        Spacer(modifier = Modifier.height(16.dp))

        when (transactionType) {
            "買進" -> {
                LabeledOutlinedTextField(
                    label = "買進價格",
                    value = price,
                    onValueChange = { price = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )

                Spacer(modifier = Modifier.height(8.dp))

                LabeledOutlinedTextField(
                    label = "買進股數",
                    value = shares,
                    onValueChange = { shares = it.filter { c -> c.isDigit() } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // ★ 卡牌 UI（手續費 + 支出金額）
                androidx.compose.material3.Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {

                        // 手續費
                        Text(
                            text = "手續費",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = if (fee > 0) fee.toInt().toString() else "-",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        androidx.compose.material3.Divider()

                        Spacer(modifier = Modifier.height(12.dp))

                        // 支出金額
                        Text(
                            text = "支出金額",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = if (expense > 0) expense.toInt().toString() else "-",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
            "賣出" -> {
                LabeledOutlinedTextField(
                    label = "賣出價格",
                    value = price,
                    onValueChange = { price = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )

                Spacer(modifier = Modifier.height(8.dp))

                LabeledOutlinedTextField(
                    label = "賣出股數",
                    value = shares,
                    onValueChange = { shares = it.filter { c -> c.isDigit() } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // ★ 賣出的手續費 + 稅 + 收入金額 卡牌
                androidx.compose.material3.Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {

                        // 手續費
                        Text("手續費", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = if (fee > 0) fee.toInt().toString() else "-",
                            style = MaterialTheme.typography.bodyLarge
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        androidx.compose.material3.Divider()
                        Spacer(modifier = Modifier.height(12.dp))

                        // 交易稅
                        Text("交易稅", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = if (tax > 0) tax.toInt().toString() else "-",
                            style = MaterialTheme.typography.bodyLarge
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        androidx.compose.material3.Divider()
                        Spacer(modifier = Modifier.height(12.dp))

                        // 收入金額
                        Text("收入金額", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = if (income > 0) income.toInt().toString() else "-",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
            "配息" -> {
                LabeledOutlinedTextField(label = "每股股息(可省略)", value = cashDividend, onValueChange = { cashDividend = it }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Spacer(modifier = Modifier.height(8.dp))
                LabeledOutlinedTextField(label = "除息股數(可省略)", value = exDividendShares, onValueChange = { exDividendShares = it }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Spacer(modifier = Modifier.height(8.dp))
                androidx.compose.material3.Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        LabeledOutlinedTextField(
                            label = "配息手續費",
                            value = dividendFee,
                            onValueChange = { dividendFee = it },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                        LabeledOutlinedTextField(
                            label = "股息總額",
                            value = price,
                            onValueChange = { price = it },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                }

            }
            "配股" -> {
                LabeledOutlinedTextField(
                    label = "每股股票股利(可省略)",
                    value = stockDividendRate,
                    onValueChange = { stockDividendRate = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Spacer(modifier = Modifier.height(8.dp))
                LabeledOutlinedTextField(
                    label = "除權股數(可省略)",
                    value = exRightsShares,
                    onValueChange = { exRightsShares = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(modifier = Modifier.height(8.dp))
                androidx.compose.material3.Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        LabeledOutlinedTextField(
                            label = "配發股數",
                            value = shares,
                            onValueChange = { shares = it },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = {
                onAddOrUpdateTransaction()
                viewModel.resetForm()
                navController.popBackStack()
            },
            enabled = isFormValid,
            modifier = Modifier.fillMaxWidth()
        ) {
            val buttonText = if (transactionId == null) "新增交易" else "更新交易"
            Text(buttonText)
        }
    }
}

@Composable
fun LabeledOutlinedTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            readOnly = readOnly,
            keyboardOptions = keyboardOptions
        )
    }
}