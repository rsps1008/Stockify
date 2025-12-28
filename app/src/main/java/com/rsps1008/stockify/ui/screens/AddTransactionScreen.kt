package com.rsps1008.stockify.ui.screens

import android.widget.Toast
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
import androidx.compose.material3.ExposedDropdownMenuBox
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
import com.rsps1008.stockify.ui.navigation.Screen
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
            transactionId = transactionId
        )
    )
    val context = LocalContext.current
    val allStocks by viewModel.stocks.collectAsState()
    val transactionToEdit by viewModel.transactionToEdit.collectAsState()
    val fee by viewModel.fee.collectAsState()
    val tax by viewModel.tax.collectAsState()
    val expense by viewModel.expense.collectAsState()
    val income by viewModel.income.collectAsState()

    var stockName by remember { mutableStateOf("") }
    var stockCode by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(System.currentTimeMillis()) }
    var transactionType by remember { mutableStateOf("buy") }
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
            "buy" -> viewModel.calculateBuyCosts(price.toDoubleOrNull() ?: 0.0, shares.toDoubleOrNull() ?: 0.0)
            "sell" -> viewModel.calculateSellCosts(price.toDoubleOrNull() ?: 0.0, shares.toDoubleOrNull() ?: 0.0)
        }
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
                "buy", "sell" -> {
                    price = it.price.toString()
                    shares = it.shares.toInt().toString()
                }
                "dividend" -> {
                    cashDividend = it.cashDividend.toString()
                    exDividendShares = it.exDividendShares.toInt().toString()
                    price = it.income.toInt().toString()
                }
                "stock_dividend" -> {
                    stockDividendRate = it.stockDividend.toString()
                    exRightsShares = it.exRightsShares.toInt().toString()
                    shares = it.shares.toInt().toString() // shares is 'dividendShares'
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
        }
    }

    // Auto-calculate total dividend amount
    LaunchedEffect(cashDividend, exDividendShares) {
        if (transactionType == "dividend") {
            val pps = cashDividend.toDoubleOrNull()
            val s = exDividendShares.toDoubleOrNull()
            if (pps != null && s != null) {
                price = (pps * s).toInt().toString()
            }
        }
    }

    // Auto-calculate total stock dividend shares
    LaunchedEffect(stockDividendRate, exRightsShares) {
        if (transactionType == "stock_dividend") {
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
        "buy", "sell" ->
            stockName.isNotBlank() &&
            stockCode.isNotBlank() &&
            (price.toDoubleOrNull() ?: 0.0) > 0.0 &&
            (shares.toLongOrNull() ?: 0L) > 0L
        "dividend" ->
            stockName.isNotBlank() &&
            stockCode.isNotBlank() &&
            (price.toDoubleOrNull() ?: -1.0) >= 0.0
        "stock_dividend" ->
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
        transactionType = "buy" // This will trigger the LaunchedEffect to reset other fields
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
            dividendShares = shares.toDoubleOrNull() ?: 0.0
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
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                OutlinedTextField(
                    value = stockName,
                    onValueChange = { stockName = it },
                    placeholder = { Text("輸入股票名稱或代號搜尋") },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    colors = ExposedDropdownMenuDefaults.textFieldColors(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    for (selectionOption in filteredStocks.take(5)) {
                        DropdownMenuItem(
                            text = { Text(text = "${selectionOption.code} ${selectionOption.name}") },
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
            Button(onClick = { transactionType = "buy" }, enabled = transactionType != "buy") { Text("買進") }
            Button(onClick = { transactionType = "sell" }, enabled = transactionType != "sell") { Text("賣出") }
            Button(onClick = { transactionType = "dividend" }, enabled = transactionType != "dividend") { Text("配息") }
            Button(onClick = { transactionType = "stock_dividend" }, enabled = transactionType != "stock_dividend") { Text("配股") }
        }
        Spacer(modifier = Modifier.height(16.dp))

        when (transactionType) {
            "buy" -> {
                LabeledOutlinedTextField(label = "股價", value = price, onValueChange = { price = it }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Spacer(modifier = Modifier.height(8.dp))
                LabeledOutlinedTextField(label = "股數", value = shares, onValueChange = { shares = it.filter { c -> c.isDigit() } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Spacer(modifier = Modifier.height(8.dp))
                LabeledOutlinedTextField(label = "手續費", value = fee.toInt().toString(), onValueChange = { /* Read-only */ }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), readOnly = true)
                Spacer(modifier = Modifier.height(8.dp))
                LabeledOutlinedTextField(label = "支出金額", value = expense.toInt().toString(), onValueChange = { /* Read-only */ }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), readOnly = true)

            }
            "sell" -> {
                LabeledOutlinedTextField(label = "股價", value = price, onValueChange = { price = it }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Spacer(modifier = Modifier.height(8.dp))
                LabeledOutlinedTextField(label = "股數", value = shares, onValueChange = { shares = it.filter { c -> c.isDigit() } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Spacer(modifier = Modifier.height(8.dp))
                LabeledOutlinedTextField(label = "手續費", value = fee.toInt().toString(), onValueChange = { /* Read-only */ }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), readOnly = true)
                Spacer(modifier = Modifier.height(8.dp))
                LabeledOutlinedTextField(label = "交易稅", value = tax.toInt().toString(), onValueChange = { /* Read-only */ }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), readOnly = true)
                Spacer(modifier = Modifier.height(8.dp))
                LabeledOutlinedTextField(label = "收入金額", value = income.toInt().toString(), onValueChange = { /* Read-only */ }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), readOnly = true)
            }
            "dividend" -> {
                LabeledOutlinedTextField(label = "每股股息(可略過)", value = cashDividend, onValueChange = { cashDividend = it }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Spacer(modifier = Modifier.height(8.dp))
                LabeledOutlinedTextField(label = "除息股數(可略過)", value = exDividendShares, onValueChange = { exDividendShares = it.filter { c -> c.isDigit() } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Spacer(modifier = Modifier.height(8.dp))
                LabeledOutlinedTextField(label = "股息收入", value = price, onValueChange = { price = it.filter { c -> c.isDigit() } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Spacer(modifier = Modifier.height(8.dp))
                LabeledOutlinedTextField(label = "手續費", value = fee.toInt().toString(), onValueChange = { /* Read-only */ }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), readOnly = true)
            }
            "stock_dividend" -> {
                LabeledOutlinedTextField(label = "每股股利(可略過)", value = stockDividendRate, onValueChange = { stockDividendRate = it }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Spacer(modifier = Modifier.height(8.dp))
                LabeledOutlinedTextField(label = "除權股數(可略過)", value = exRightsShares, onValueChange = { exRightsShares = it.filter { c -> c.isDigit() } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Spacer(modifier = Modifier.height(8.dp))
                LabeledOutlinedTextField(label = "配發股數", value = shares, onValueChange = { shares = it.filter { c -> c.isDigit() } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        }

        Spacer(modifier = Modifier.weight(1f, fill = false))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (transactionId == null) {
                Button(onClick = { onAddOrUpdateTransaction(); clearForm() }, enabled = isFormValid) { Text("再記一筆") }
                Spacer(modifier = Modifier.padding(horizontal = 8.dp))
            }
            Button(
                onClick = {
                    onAddOrUpdateTransaction()
                    navController.navigate(Screen.Holdings.route) {
                        popUpTo(Screen.Holdings.route) { inclusive = true }
                    }
                },
                enabled = isFormValid
            ) {
                Text(if (transactionId == null) "完成" else "儲存")
            }
        }
    }
}

@Composable
private fun LabeledOutlinedTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    Column(modifier = modifier) {
        Text(text = label, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            readOnly = readOnly,
            keyboardOptions = keyboardOptions,
        )
    }
}