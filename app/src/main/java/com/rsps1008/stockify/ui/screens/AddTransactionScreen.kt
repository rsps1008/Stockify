package com.rsps1008.stockify.ui.screens

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.rsps1008.stockify.StockifyApplication
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import com.rsps1008.stockify.ui.viewmodel.AddTransactionViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import com.rsps1008.stockify.ui.viewmodel.ViewModelFactory
import com.rsps1008.stockify.data.dividend.YahooDividendRepository
import com.rsps1008.stockify.data.Stock
import com.rsps1008.stockify.data.StockMarket
import com.rsps1008.stockify.data.formatMarketAmount
import com.rsps1008.stockify.data.formatShareInputValue
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val AddTransactionButtonShape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTransactionScreen(navController: NavController, transactionId: Int?, prefillStockCode: String? = null) {
    val application = LocalContext.current.applicationContext as StockifyApplication
    val viewModel: AddTransactionViewModel = viewModel(
        factory = ViewModelFactory(
            stockDao = application.database.stockDao(),
            settingsDataStore = application.settingsDataStore,
            transactionId = transactionId,
            application = application,
            realtimeStockDataService = application.realtimeStockDataService,
            dividendRepository = YahooDividendRepository(application.httpClient),
            exchangeRateService = application.exchangeRateService
        )
    )
    val context = LocalContext.current
    val allStocks by viewModel.stocks.collectAsState()
    val transactionToEdit by viewModel.transactionToEdit.collectAsState()
    val fee by viewModel.fee.collectAsState()
    val tax by viewModel.tax.collectAsState()
    val taxRate by viewModel.taxRate.collectAsState()
    val expense by viewModel.expense.collectAsState()
    val income by viewModel.income.collectAsState()
    val feeSettings by viewModel.feeSettings.collectAsState()
    val defaultDividendFee by viewModel.defaultDividendFee.collectAsState()
    val calculationRoundingMode by viewModel.calculationRoundingMode.collectAsState()
    val selectedAccountId by viewModel.selectedAccountId.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    var dividendFee by remember { mutableStateOf("") }
    var dividendIncome by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    var stockName by remember { mutableStateOf("") }
    var stockCode by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(System.currentTimeMillis()) }
    var transactionType by remember { mutableStateOf("買進") }
    var price by remember { mutableStateOf("") } // Represents total amount for dividend, price per share for buy/sell
    var shares by remember { mutableStateOf("") } // Represents shares for buy/sell/stock_dividend
    var note by remember { mutableStateOf("") }

    var showDatePicker by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    // Optional fields for dividend calculation
    var cashDividend by remember { mutableStateOf("") }
    var exDividendShares by remember { mutableStateOf("") }

    val stockForDividendFee = allStocks.find { it.code == stockCode }
    val defaultDividendFeeForStock = remember(stockForDividendFee?.market, defaultDividendFee) {
        if (StockMarket.isUs(stockForDividendFee?.market) || StockMarket.isUs(StockMarket.inferFromCode(stockCode))) {
            0
        } else {
            defaultDividendFee
        }
    }

    // Optional fields for stock dividend calculation
    var stockDividendRate by remember { mutableStateOf("") }
    var exRightsShares by remember { mutableStateOf("") }

    var isDayTrading by remember { mutableStateOf(false) }

    // Capital Reduction fields
    var capitalReductionRatio by remember { mutableStateOf("") }
    var sharesBeforeReduction by remember { mutableStateOf("") }
    val sharesAfterReduction = remember(sharesBeforeReduction, capitalReductionRatio) {
        val before = sharesBeforeReduction.toDoubleOrNull() ?: 0.0
        val ratio = capitalReductionRatio.toDoubleOrNull() ?: 0.0
        if (before > 0 && ratio > 0) {
            formatShareInputValue(before * (1 - ratio / 100))
        } else {
            ""
        }
    }
    var cashReturned by remember { mutableStateOf("") }

    // Stock Split fields
    var stockSplitRatio by remember { mutableStateOf("") }
    var sharesBeforeSplit by remember { mutableStateOf("") }
    val sharesAfterSplit = remember(sharesBeforeSplit, stockSplitRatio) {
        val before = sharesBeforeSplit.toDoubleOrNull() ?: 0.0
        val ratio = stockSplitRatio.toDoubleOrNull() ?: 0.0
        if (before > 0 && ratio > 0) {
            formatShareInputValue(before * ratio)
        } else {
            ""
        }
    }

    val selectedStock = allStocks.find { it.code == stockCode }
    val isUsStock = StockMarket.isUs(selectedStock?.market.orEmpty())
    val shareStep = if (isUsStock) 1.0 else 1000.0

    LaunchedEffect(prefillStockCode, allStocks) {
        if (transactionId == null && prefillStockCode != null) {
            val stock = allStocks.find { it.code == prefillStockCode }
            if (stock != null) {
                stockName = stock.name
                stockCode = stock.code
                expanded = false
            }
        }
    }

    LaunchedEffect(price, shares, transactionType, stockCode, feeSettings, calculationRoundingMode) {
        when (transactionType) {
            "買進" -> {
                val stock = allStocks.find { it.code == stockCode }
                viewModel.calculateBuyCosts(
                    price.toDoubleOrNull() ?: 0.0,
                    shares.toDoubleOrNull() ?: 0.0,
                    stock?.market ?: ""
                )
            }
            "賣出" -> {
                val stock = allStocks.find { it.code == stockCode }
                viewModel.calculateSellCosts(
                    price.toDoubleOrNull() ?: 0.0,
                    shares.toDoubleOrNull() ?: 0.0,
                    stock?.market ?: "",
                    stock?.stockType ?: "",
                    isDayTrading = isDayTrading,
                    isBondEtf = stockCode.endsWith("B", ignoreCase = true)
                )
            }
        }
    }

    LaunchedEffect(transactionType, transactionId) {
        // Only reset transient calculated values when creating a new transaction.
        if (transactionId == null && (transactionType == "買進" || transactionType == "賣出")) {
            viewModel.resetCalculatedValues()
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
            note = it.note

            when (it.type) {
                "買進" -> {
                    price = it.buyPrice.toString()
                    shares = formatShareInputValue(it.buyShares)
                }
                "賣出" -> {
                    price = it.sellPrice.toString()
                    shares = formatShareInputValue(it.sellShares)
                }
                "配息" -> {
                    cashDividend = if (it.cashDividend != 0.0) it.cashDividend.toString() else ""
                    exDividendShares = if (it.exDividendShares != 0.0) formatShareInputValue(it.exDividendShares) else ""
                    price = formatDividendAmountInput(it.income + it.fee, stock?.market)
                    dividendIncome = formatDividendAmountInput(it.income, stock?.market)
                    dividendFee = it.fee.toString()
                }
                "配股" -> {
                    stockDividendRate = if (it.stockDividend != 0.0) it.stockDividend.toString() else ""
                    exRightsShares = if (it.exRightsShares != 0.0) formatShareInputValue(it.exRightsShares) else ""
                    shares = formatShareInputValue(it.dividendShares)
                }
                "減資" -> {
                    capitalReductionRatio = it.capitalReductionRatio.toString()
                    sharesBeforeReduction = it.sharesBeforeReduction.toString()
                    cashReturned = it.cashReturned.toString()
                }
                "分割" -> {
                    stockSplitRatio = it.stockSplitRatio.toString()
                    sharesBeforeSplit = it.sharesBeforeSplit.toString()
                }
            }
        }
    }

    // Reset fields when transaction type changes for a new transaction
    LaunchedEffect(transactionType, defaultDividendFeeForStock) {
        if (transactionId == null) { // Only for new transactions
            price = ""
            shares = ""
            cashDividend = ""
            exDividendShares = ""
            stockDividendRate = ""
            exRightsShares = ""
            dividendFee = defaultDividendFeeForStock.toString()
            dividendIncome = ""
            capitalReductionRatio = ""
            sharesBeforeReduction = ""
            cashReturned = ""
            stockSplitRatio = ""
            sharesBeforeSplit = ""
        }
    }

    // Auto-calculate total dividend amount
    LaunchedEffect(cashDividend, exDividendShares, dividendFee, selectedStock?.market, calculationRoundingMode) {
        if (transactionType == "配息") {
            val pps = cashDividend.toDoubleOrNull()
            val s = exDividendShares.toDoubleOrNull()
            if (pps != null && s != null) {
                val stock = allStocks.find { it.code == stockCode }
                val grossAmount = viewModel.roundCalculatedCurrency(pps * s, stock?.market)
                price = formatDividendAmountInput(grossAmount, stock?.market)
                val feeAmount = dividendFee.toDoubleOrNull() ?: 0.0
                val netAmount = viewModel.roundCalculatedCurrency(
                    (grossAmount - feeAmount).coerceAtLeast(0.0),
                    stock?.market
                )
                dividendIncome = formatDividendAmountInput(netAmount, stock?.market)
            }
        }
    }

    // Auto-calculate total stock dividend shares
    LaunchedEffect(stockDividendRate, exRightsShares, calculationRoundingMode, isUsStock) {
        if (transactionType == "配股") {
            val rate = stockDividendRate.toDoubleOrNull()
            val baseShares = exRightsShares.toDoubleOrNull()
            // `rate` is treated as stock dividend shares per share.
            if (rate != null && baseShares != null) {
                val calculatedShares = baseShares * rate
                shares = formatShareInputValue(
                    if (isUsStock) calculatedShares else viewModel.roundCalculatedAmount(calculatedShares)
                )
            }
        }
    }

    val isFormValid = when (transactionType) {
        "買進", "賣出" ->
            stockName.isNotBlank() &&
            stockCode.isNotBlank() &&
            (price.toDoubleOrNull() ?: 0.0) > 0.0 &&
            (shares.toDoubleOrNull() ?: 0.0) > 0.0
        "配息" ->
            stockName.isNotBlank() &&
            stockCode.isNotBlank() &&
            ((price.toDoubleOrNull() ?: -1.0) >= 0.0 ||
                (dividendIncome.toDoubleOrNull() ?: -1.0) >= 0.0)
        "配股" ->
            stockName.isNotBlank() &&
            stockCode.isNotBlank() &&
            (shares.toDoubleOrNull() ?: 0.0) > 0.0
        "減資" ->
            stockName.isNotBlank() &&
            stockCode.isNotBlank() &&
            (capitalReductionRatio.toDoubleOrNull() ?: 0.0) > 0.0 &&
            (sharesBeforeReduction.toDoubleOrNull() ?: 0.0) > 0.0 &&
            (cashReturned.toDoubleOrNull() ?: 0.0) >= 0.0
        "分割" ->
            stockName.isNotBlank() &&
            stockCode.isNotBlank() &&
            (stockSplitRatio.toDoubleOrNull() ?: 0.0) > 0.0 &&
            (sharesBeforeSplit.toDoubleOrNull() ?: 0.0) > 0.0
        else -> false
    }

    val filteredStocks = prioritizeStockSearchResults(allStocks, stockName)

    Column(
        modifier = Modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(text = "選擇帳戶", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        var accountDropdownExpanded by remember { mutableStateOf(false) }
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { accountDropdownExpanded = true },
                modifier = Modifier.fillMaxWidth(),
                shape = AddTransactionButtonShape
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val activeAccount = accounts.find { it.id == selectedAccountId }
                    Text(text = activeAccount?.name ?: "預設帳戶")
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Dropdown"
                    )
                }
            }
            DropdownMenu(
                expanded = accountDropdownExpanded,
                onDismissRequest = { accountDropdownExpanded = false },
                modifier = Modifier.fillMaxWidth()
            ) {
                accounts.forEach { account ->
                    DropdownMenuItem(
                        text = { Text(account.name) },
                        onClick = {
                            viewModel.selectAccount(account.id)
                            accountDropdownExpanded = false
                        }
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
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
                            text = {
                                Column {
                                    Text(
                                        text = "${selectionOption.market} ${selectionOption.code}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = selectionOption.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
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
                value = formatStockCodeLabel(allStocks.find { it.code == stockCode }),
                onValueChange = {},
                readOnly = true
            )
        } else {
            val stock = allStocks.find { it.code == stockCode }
            LabeledOutlinedTextField(
                label = "股票",
                value = formatStockDisplayLabel(stock, stockCode, stockName),
                onValueChange = {},
                readOnly = true
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "交易日期", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Button(
            onClick = { showDatePicker = true },
            shape = AddTransactionButtonShape
        ) {
            Text(text = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(date)))
        }
        if (showDatePicker) {
            val datePickerState = rememberDatePickerState(initialSelectedDateMillis = date)
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    TextButton(
                        onClick = {
                            date = datePickerState.selectedDateMillis ?: date
                            showDatePicker = false
                        }
                    ) { Text("確定") }
                },
                dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } }
            ) {
                DatePicker(state = datePickerState)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "交易類型", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Button(onClick = { transactionType = "買進" }, enabled = transactionType != "買進", shape = AddTransactionButtonShape) { Text("買進") }
            Button(onClick = { transactionType = "賣出" }, enabled = transactionType != "賣出", shape = AddTransactionButtonShape) { Text("賣出") }
            Button(onClick = { transactionType = "配息" }, enabled = transactionType != "配息", shape = AddTransactionButtonShape) { Text("配息") }
            Button(onClick = { transactionType = "配股" }, enabled = transactionType != "配股", shape = AddTransactionButtonShape) { Text("配股") }
            Button(onClick = { transactionType = "減資" }, enabled = transactionType != "減資", shape = AddTransactionButtonShape) { Text("減資") }
            Button(onClick = { transactionType = "分割" }, enabled = transactionType != "分割", shape = AddTransactionButtonShape) { Text("分割") }
        }
        Spacer(modifier = Modifier.height(16.dp))

        when (transactionType) {
            "買進" -> {
                // 買進上面
                androidx.compose.material3.Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        LabeledOutlinedTextField(
                            label = "買進價格",
                            value = price,
                            onValueChange = { price = it },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        ShareInputWithStepper(
                            label = "買進股數",
                            value = shares,
                            onValueChange = { shares = it },
                            step = shareStep,
                            allowDecimal = isUsStock
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                // 買進下面
                androidx.compose.material3.Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // 手續費
                        EditableTextStyled(
                            label = "手續費 (點擊數字修改)",
                            value = formatFeeDisplayValue(fee, selectedStock?.market),
                            onValueChange = { newFee ->
                                viewModel.updateFee(
                                    newFee = newFee.toDoubleOrNull() ?: 0.0,
                                    price = price.toDoubleOrNull() ?: 0.0,
                                    shares = shares.toDoubleOrNull() ?: 0.0,
                                    type = "買進",
                                    market = selectedStock?.market ?: StockMarket.TW
                                )
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                        androidx.compose.material3.HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))
                        // 支出金額
                        EditableTextStyled(
                            label = "支出金額 (點擊數字修改)",
                            value = if (expense > 0) formatMarketAmount(expense, selectedStock?.market) else "",
                            editValue = formatEditableAmountValue(expense, selectedStock?.market),
                            onValueChange = { newExpense ->
                                viewModel.updateExpense(newExpense.toDoubleOrNull() ?: 0.0)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            keyboardType = KeyboardType.Decimal
                        )
                        androidx.compose.material3.HorizontalDivider()
                    }
                }
            }
            "賣出" -> {
                // 賣出上面
                androidx.compose.material3.Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {

                        // ★ 賣出價格 + 當沖 checkbox
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(text = "賣出價格", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(4.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {

                                // 賣出價格輸入框
                                OutlinedTextField(
                                    value = price,
                                    onValueChange = { price = it },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.weight(1f)
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                // ★ 當沖勾勾（移到右方）
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = isDayTrading,
                                        onCheckedChange = { isDayTrading = it }
                                    )
                                    Text("當沖")
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        ShareInputWithStepper(
                            label = "賣出股數",
                            value = shares,
                            onValueChange = { shares = it },
                            step = shareStep,
                            allowDecimal = isUsStock
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                // ★ 賣出下面，賣出的手續費 + 稅 + 收入金額 卡牌
                androidx.compose.material3.Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // 手續費
                        EditableTextStyled(
                            label = "手續費 (點擊數字修改)",
                            value = formatFeeDisplayValue(fee, selectedStock?.market),
                            onValueChange = { newFee ->
                                viewModel.updateFee(
                                    newFee = newFee.toDoubleOrNull() ?: 0.0,
                                    price = price.toDoubleOrNull() ?: 0.0,
                                    shares = shares.toDoubleOrNull() ?: 0.0,
                                    type = "賣出",
                                    market = selectedStock?.market ?: StockMarket.TW
                                )
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )

                        androidx.compose.material3.HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))
                        // 交易稅
                        EditableTextStyled(
                            label = "交易稅 (點擊數字修改)",
                            value = formatTaxDisplayValue(tax, selectedStock?.market),
                            onValueChange = { newTax ->
                                viewModel.updateTax(
                                    newTax = newTax.toDoubleOrNull() ?: 0.0,
                                    price = price.toDoubleOrNull() ?: 0.0,
                                    shares = shares.toDoubleOrNull() ?: 0.0,
                                    market = selectedStock?.market ?: StockMarket.TW
                                )
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                        androidx.compose.material3.HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))
                        // 收入金額
                        EditableTextStyled(
                            label = "收入金額 (點擊數字修改)",
                            value = if (income > 0) formatMarketAmount(income, selectedStock?.market) else "",
                            editValue = formatEditableAmountValue(income, selectedStock?.market),
                            onValueChange = { newIncome ->
                                viewModel.updateIncome(newIncome.toDoubleOrNull() ?: 0.0)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            keyboardType = KeyboardType.Decimal
                        )
                        androidx.compose.material3.HorizontalDivider()
                    }
                }
            }
            "配息" -> {
                val context = LocalContext.current

                Box(
                    modifier = Modifier.fillMaxWidth()   // 外層滿版才能置中
                ) {
                    Button(
                        onClick = {
                            if (stockCode.isBlank()) {
                                Toast.makeText(context, "沒有股票代號", Toast.LENGTH_SHORT).show()
                                return@Button
                            }

                            viewModel.autoFillDividendCashFromYahooUsingHolding(
                                stockCode,
                                onResult = { perShare, holdingShares, dateStr ->
                                    cashDividend = perShare.toString()
                                    exDividendShares = formatShareInputValue(holdingShares)

                                    dateStr?.let {
                                        val sdf = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
                                        date = sdf.parse(it)?.time ?: date
                                    }

                                    Toast.makeText(context, "已帶入最近一次配息（${dateStr ?: "-"}）", Toast.LENGTH_SHORT).show()
                                },
                                onFail = {
                                    Toast.makeText(context, "找不到最近一次配息資料", Toast.LENGTH_SHORT).show()
                                }
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth(0.6f) // 寬度 60%
                            .align(Alignment.Center),     // ★ 置中
                        shape = AddTransactionButtonShape
                    ) {
                        Text("自動帶入最近一次配息")
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                //配息上面
                androidx.compose.material3.Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        LabeledOutlinedTextField(
                            label = "每股股息(可省略)",
                            value = cashDividend,
                            onValueChange = { cashDividend = it },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        ShareInputWithStepper(
                            label = "除息股數(可省略)",
                            value = exDividendShares,
                            onValueChange = { exDividendShares = it },
                            step = shareStep,
                            allowDecimal = isUsStock
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                //配息下面
                androidx.compose.material3.Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        EditableTextStyled(
                            label = "配息手續費 (點擊數字修改)",
                            value = formatFeeDisplayValue(dividendFee.toDoubleOrNull() ?: 0.0, selectedStock?.market),
                            editValue = dividendFee,
                            onValueChange = { dividendFee = it },
                            style = MaterialTheme.typography.bodyLarge,
                            keyboardType = KeyboardType.Decimal
                        )
                        androidx.compose.material3.HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))
                        EditableTextStyled(
                            label = "股息總額 (點擊數字修改)",
                            value = dividendIncome,
                            editValue = dividendIncome,
                            onValueChange = {
                                dividendIncome = it
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            keyboardType = KeyboardType.Decimal
                        )
                    }
                }

            }
            "配股" -> {
                Button(
                    onClick = {
                        if (stockCode.isBlank()) {
                            Toast.makeText(context, "沒有股票代號", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        viewModel.autoFillDividendStockFromYahooUsingHolding(
                            stockCode,
                            onResult = { rate, holdingShares, dateStr ->
                                stockDividendRate = rate.toString()
                                exRightsShares = formatShareInputValue(holdingShares)

                                dateStr?.let {
                                    val sdf = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
                                    date = sdf.parse(it)?.time ?: date
                                }

                                Toast.makeText(context, "已帶入最近一次配股（${dateStr ?: "-"}）", Toast.LENGTH_SHORT).show()
                            },
                            onFail = {
                                Toast.makeText(context, "找不到最近一次配股資料", Toast.LENGTH_SHORT).show()
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth(0.6f).align(Alignment.CenterHorizontally),
                    shape = AddTransactionButtonShape
                ) {
                    Text("自動帶入最近一次配股")
                }
                Spacer(modifier = Modifier.height(10.dp))
                //配股上面
                androidx.compose.material3.Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        LabeledOutlinedTextField(
                            label = "每股股票股利(可省略)",
                            value = stockDividendRate,
                            onValueChange = { stockDividendRate = it },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        ShareInputWithStepper(
                            label = "除權股數(可省略)",
                            value = exRightsShares,
                            onValueChange = { exRightsShares = it },
                            step = shareStep,
                            allowDecimal = isUsStock
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                //配股下面
                androidx.compose.material3.Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        EditableTextStyled(
                            label = "配發股數 (點擊數字修改)",
                            value = if (shares.isBlank()) "" else formatShareInputValue(shares.toDoubleOrNull() ?: 0.0),
                            editValue = shares,
                            onValueChange = {
                                shares = filterShareInput(it, isUsStock)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            keyboardType = if (isUsStock) KeyboardType.Decimal else KeyboardType.Number
                        )
                    }
                }
            }
            "減資" -> {
                androidx.compose.material3.Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        LabeledOutlinedTextField(
                            label = "減資比例 (%)",
                            value = capitalReductionRatio,
                            onValueChange = { capitalReductionRatio = it },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        ShareInputWithStepper(
                            label = "減資前股數",
                            value = sharesBeforeReduction,
                            onValueChange = { sharesBeforeReduction = it },
                            step = shareStep,
                            allowDecimal = isUsStock
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LabeledOutlinedTextField(
                            label = "減資後股數",
                            value = sharesAfterReduction,
                            onValueChange = { /* Read-only */ },
                            readOnly = true
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LabeledOutlinedTextField(
                            label = "退還股款",
                            value = cashReturned,
                            onValueChange = { cashReturned = it },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                }
            }
            "分割" -> {
                androidx.compose.material3.Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        LabeledOutlinedTextField(
                            label = "每股拆分",
                            value = stockSplitRatio,
                            onValueChange = { stockSplitRatio = it },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        ShareInputWithStepper(
                            label = "原持股數",
                            value = sharesBeforeSplit,
                            onValueChange = { sharesBeforeSplit = it },
                            step = shareStep,
                            allowDecimal = isUsStock
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LabeledOutlinedTextField(
                            label = "新持股數",
                            value = sharesAfterSplit,
                            onValueChange = { /* Read-only */ },
                            readOnly = true
                        )
                    }
                }
            }
        }
        LabeledOutlinedTextField(
            label = "交易筆記",
            value = note,
            onValueChange = { note = it }
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = {
                coroutineScope.launch {
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
                        dividendFee = dividendFee.toDoubleOrNull() ?: 0.0,
                        note = note,
                        capitalReductionRatio = capitalReductionRatio.toDoubleOrNull() ?: 0.0,
                        sharesBeforeReduction = sharesBeforeReduction.toDoubleOrNull() ?: 0.0,
                        sharesAfterReduction = sharesAfterReduction.toDoubleOrNull() ?: 0.0,
                        cashReturned = cashReturned.toDoubleOrNull() ?: 0.0,
                        stockSplitRatio = stockSplitRatio.toDoubleOrNull() ?: 0.0,
                        sharesBeforeSplit = sharesBeforeSplit.toDoubleOrNull() ?: 0.0,
                        sharesAfterSplit = sharesAfterSplit.toDoubleOrNull() ?: 0.0,
                        dividendIncome = dividendIncome.toDoubleOrNull()
                    )
                    val message = if (transactionId == null) "新增成功" else "更新成功"
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    viewModel.resetForm()
                    navController.popBackStack()
                }
            },
            enabled = isFormValid,
            modifier = Modifier.fillMaxWidth(),
            shape = AddTransactionButtonShape
        ) {
            val buttonText = if (transactionId == null) "新增交易" else "更新交易"
            Text(buttonText)
        }
    }
}

private fun prioritizeStockSearchResults(stocks: List<Stock>, query: String): List<Stock> {
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    if (normalizedQuery.isBlank()) return stocks

    return stocks
        .mapNotNull { stock ->
            val rank = stockSearchRank(stock, normalizedQuery)
            if (rank == Int.MAX_VALUE) {
                null
            } else {
                stock to rank
            }
        }
        .sortedWith(
            compareBy<Pair<Stock, Int>> { it.second }
                .thenBy { it.first.code.length }
                .thenBy { it.first.code }
                .thenBy { it.first.name }
        )
        .map { it.first }
}

private fun stockSearchRank(stock: Stock, normalizedQuery: String): Int {
    val code = stock.code.lowercase(Locale.ROOT)
    val name = stock.name.lowercase(Locale.ROOT)

    return when {
        code == normalizedQuery -> 0
        code.startsWith(normalizedQuery) -> 1
        code.contains(normalizedQuery) -> 2
        name.startsWith(normalizedQuery) -> 3
        name.contains(normalizedQuery) -> 4
        else -> Int.MAX_VALUE
    }
}

private fun formatStockCodeLabel(stock: Stock?): String {
    return if (stock == null) {
        ""
    } else {
        "${displayMarketLabel(stock.market)} ${stock.code}"
    }
}

private fun formatStockDisplayLabel(stock: Stock?, stockCode: String, stockName: String): String {
    val marketCode = displayMarketLabel(stock?.market)
    return listOf(marketCode, stockCode, stockName)
        .filter { it.isNotBlank() }
        .joinToString(" ")
}

private fun displayMarketLabel(market: String?): String {
    return when (market?.trim()) {
        StockMarket.TW -> StockMarket.TW
        StockMarket.US -> StockMarket.US
        else -> market.orEmpty()
    }
}

private fun formatFeeDisplayValue(fee: Double, market: String?): String {
    if (fee <= 0.0) return ""
    return if (StockMarket.isUs(market.orEmpty())) {
        String.format(Locale.US, "%.2f", fee)
    } else {
        fee.toInt().toString()
    }
}

private fun formatTaxDisplayValue(tax: Double, market: String?): String {
    if (tax <= 0.0) return ""
    return if (StockMarket.isUs(market.orEmpty())) {
        String.format(Locale.US, "%.2f", tax)
    } else {
        tax.toInt().toString()
    }
}

private fun formatEditableAmountValue(amount: Double, market: String?): String {
    if (amount <= 0.0) return ""
    return if (StockMarket.isUs(market.orEmpty())) {
        String.format(Locale.US, "%.2f", amount)
    } else {
        amount.toInt().toString()
    }
}

private fun formatDividendAmountInput(amount: Double, market: String?): String {
    return if (StockMarket.isUs(market.orEmpty())) {
        String.format(Locale.US, "%.2f", amount)
    } else {
        amount.roundToInt().toString()
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

@Composable
fun LabeledOutlinedTextFieldStyled(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    textStyle: androidx.compose.ui.text.TextStyle,
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
            keyboardOptions = keyboardOptions,
            textStyle = textStyle     // ★ 控制字體
        )
    }
}

@Composable
fun ShareInputWithStepper(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    step: Double = 1000.0,
    modifier: Modifier = Modifier,
    allowDecimal: Boolean = false
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = { input ->
                    onValueChange(filterShareInput(input, allowDecimal))
                },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (allowDecimal) KeyboardType.Decimal else KeyboardType.Number
                )
            )
            Spacer(modifier = Modifier.width(4.dp))
            Button(
                onClick = {
                    val current = value.toDoubleOrNull() ?: 0.0
                    onValueChange(formatShareInputValue(current + step))
                },
                shape = AddTransactionButtonShape
            ) {
                Text("+")
            }
            Spacer(modifier = Modifier.width(2.dp))
            Button(
                onClick = {
                    val current = value.toDoubleOrNull() ?: 0.0
                    val next = (current - step).coerceAtLeast(0.0)
                    onValueChange(formatShareInputValue(next))
                },
                shape = AddTransactionButtonShape
            ) {
                Text("-")
            }
        }
    }
}

private fun filterShareInput(input: String, allowDecimal: Boolean): String {
    if (!allowDecimal) return input.filter { it.isDigit() }

    var decimalPointSeen = false
    return buildString {
        input.forEach { char ->
            when {
                char.isDigit() -> append(char)
                char == '.' && !decimalPointSeen -> {
                    append(char)
                    decimalPointSeen = true
                }
            }
        }
    }
}
@Composable
fun EditableTextStyled(
    label: String,
    value: String,
    editValue: String = value,
    onValueChange: (String) -> Unit,
    style: androidx.compose.ui.text.TextStyle,
    keyboardType: KeyboardType = KeyboardType.Number
) {
    var editing by remember { mutableStateOf(false) }
    var tempText by remember { mutableStateOf(value) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))

        if (!editing) {
            Text(
                text = if (value.isBlank()) "-" else value,
                style = style,
                modifier = Modifier
                    .padding(bottom = 12.dp)
                    .clickable {
                        tempText = editValue
                        editing = true
                    }
            )
        } else {
            OutlinedTextField(
                value = tempText,
                onValueChange = { tempText = it },
                textStyle = style,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    Text(
                        "完成",
                        modifier = Modifier.clickable {
                            onValueChange(tempText)
                            editing = false
                        }
                    )
                }
            )
        }
    }
}
