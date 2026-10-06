package com.rsps1008.stockify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.rsps1008.stockify.R
import com.rsps1008.stockify.StockifyApplication
import com.rsps1008.stockify.ui.navigation.Screen
import com.rsps1008.stockify.ui.theme.StockifyAppTheme
import com.rsps1008.stockify.ui.viewmodel.TransactionDateCashFlowTotal
import com.rsps1008.stockify.ui.viewmodel.TransactionsViewModel
import com.rsps1008.stockify.ui.viewmodel.TransactionsUiState
import com.rsps1008.stockify.ui.viewmodel.ViewModelFactory
import com.rsps1008.stockify.data.formatMarketAmount
import com.rsps1008.stockify.data.formatShareCount
import java.util.Locale

@Composable
fun TransactionsScreen(navController: NavController) {
    val application = LocalContext.current.applicationContext as StockifyApplication
    val viewModel: TransactionsViewModel = viewModel(
        factory = ViewModelFactory(
            stockDao = application.database.stockDao(),
            settingsDataStore = application.settingsDataStore,
            transactionListRepository = application.transactionListRepository
        )
    )
    val uiState by viewModel.uiState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    TransactionsContent(
        uiState = uiState,
        searchQuery = searchQuery,
        onSearchQueryChange = viewModel::updateSearchQuery,
        onAddTransaction = { navController.navigate(Screen.AddTransaction.createRoute()) },
        onTransactionClick = { navController.navigate(Screen.TransactionDetail.createRoute(it)) }
    )
}

@Composable
internal fun TransactionsContent(
    uiState: TransactionsUiState,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onAddTransaction: () -> Unit,
    onTransactionClick: (Int) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        TransactionsBody(
            uiState = uiState,
            searchQuery = searchQuery,
            onSearchQueryChange = onSearchQueryChange,
            onAddTransaction = onAddTransaction,
            onTransactionClick = onTransactionClick,
            compactLayout = maxHeight < 400.dp
        )
    }
}

@Composable
private fun TransactionsBody(
    uiState: TransactionsUiState,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onAddTransaction: () -> Unit,
    onTransactionClick: (Int) -> Unit,
    compactLayout: Boolean
) {
    val focusManager = LocalFocusManager.current
    Column(modifier = Modifier.fillMaxSize()) {
        if (!compactLayout) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                SampledResourceImage(
                    resId = R.drawable.stockify,
                    contentDescription = "Stockify Logo",
                    modifier = Modifier.fillMaxWidth(0.35f).height(40.dp)
                )
            }
        } else {
            Spacer(modifier = Modifier.height(16.dp))
        }

        Spacer(modifier = Modifier.height(6.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            label = { Text("搜尋交易") },
            placeholder = { Text("代號、名稱、類型、筆記或日期") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "清除搜尋")
                    }
                }
            },
            supportingText = {
                if (!uiState.isLoading) {
                    Text(
                        if (uiState.query.isBlank()) "共 ${uiState.totalCount} 筆交易"
                        else "找到 ${uiState.resultCount} 筆，共 ${uiState.totalCount} 筆"
                    )
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        )

        if (uiState.isLoading || uiState.sections.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = when {
                            uiState.isLoading -> "正在載入交易紀錄…"
                            uiState.totalCount == 0 -> "目前帳戶尚無交易紀錄"
                            else -> "找不到符合的交易"
                        },
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!uiState.isLoading) {
                        TextButton(onClick = {
                            focusManager.clearFocus()
                            if (uiState.totalCount == 0) onAddTransaction() else onSearchQueryChange("")
                        }) {
                            Text(if (uiState.totalCount == 0) "新增交易" else "清除搜尋條件")
                        }
                    }
                }
            }
        } else {
            if (!compactLayout) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    TransactionsListHeader()
                }
            }
            key(uiState.accountId, uiState.query) {
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                ) {
                    if (compactLayout) {
                        item(key = "columns", contentType = "columns") {
                            TransactionsListHeader()
                        }
                    }
                    uiState.sections.forEach { section ->
                        item(key = "date:${section.date}", contentType = "date") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = section.date,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                DailyCashFlowTotals(section.cashFlowTotals)
                            }
                        }
                        items(
                            items = section.transactions,
                            key = { it.transaction.id },
                            contentType = { "transaction" }
                        ) { transaction ->
                            TransactionRow(transaction) {
                                focusManager.clearFocus()
                                onTransactionClick(it)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DailyCashFlowTotals(totals: List<TransactionDateCashFlowTotal>) {
    if (totals.isEmpty()) return

    Column(horizontalAlignment = Alignment.End) {
        totals.forEach { total ->
            val currencyPrefix = if (total.market == "US") "\$" else ""
            val summary = buildList {
                if (total.income > 0.0) {
                    add("收入: $currencyPrefix${formatMarketAmount(total.income, total.market)}")
                }
                if (total.expense > 0.0) {
                    add("支出: $currencyPrefix${formatMarketAmount(total.expense, total.market)}")
                }
            }.joinToString("　")
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End
            )
        }
    }
}

@Composable
private fun TransactionsListHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "股票", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.5f))
        Text(text = "交易", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.5f), textAlign = TextAlign.Center)
        Text(text = "股價", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        Text(text = "收支", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

@Composable
private fun TransactionRow(transaction: TransactionUiState, onTransactionClick: (Int) -> Unit) {
    val amountText = when (transaction.transaction.type) {
        "買進" -> formatMarketAmount(-transaction.transaction.expense, transaction.market)
        "融資買進" -> {
            val selfFunded = if (transaction.transaction.marginSelfFundedOverridden) {
                transaction.transaction.marginSelfFunded
            } else {
                transaction.transaction.expense - transaction.transaction.marginPrincipal
            }
            formatMarketAmount(-selfFunded, transaction.market)
        }
        "賣出" -> formatMarketAmount(
            transaction.transaction.income - transaction.transaction.marginRepayment - transaction.transaction.marginActualInterest,
            transaction.market
        )
        "融券賣出" -> formatMarketAmount(transaction.transaction.income, transaction.market)
        "買券還券" -> formatMarketAmount(-transaction.transaction.expense, transaction.market)
        "融券補償" -> formatMarketAmount(-transaction.transaction.shortCompensation, transaction.market)
        "配息" -> formatMarketAmount(transaction.transaction.income, transaction.market)
        "配股" -> "0"
        "減資" -> String.format(Locale.US, "%,.0f", transaction.transaction.cashReturned)
        "分割" -> "-"
        "融資還款" -> formatMarketAmount(
            -(transaction.transaction.marginRepayment + transaction.transaction.marginActualInterest),
            transaction.market
        )
        else -> ""
    }

    val cashFlowAmount = transactionCashFlowAmount(transaction.transaction)
    val amountColor = when {
        cashFlowAmount == null || kotlin.math.abs(cashFlowAmount) < 1e-6 ->
            Color.Unspecified
        cashFlowAmount < 0.0 -> StockifyAppTheme.stockColors.loss
        else -> StockifyAppTheme.stockColors.gain
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTransactionClick(transaction.transaction.id) }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1.5f)) {
            Text(
                text = transaction.stockName,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = transaction.transaction.stockCode,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        val transactionText = when (transaction.transaction.type) {
            "買進" -> "買${formatShareCount(transaction.transaction.buyShares)}股"
            "融資買進" -> "融資買${formatShareCount(transaction.transaction.buyShares)}股"
            "賣出" -> if (transaction.transaction.marginRepaymentLotId.isNotBlank()) {
                "賣${formatShareCount(transaction.transaction.sellShares)}股／還融資"
            } else {
                "賣${formatShareCount(transaction.transaction.sellShares)}股"
            }
            "配息" -> "配息${formatMarketAmount(transaction.transaction.income, transaction.market)}元"
            "配股" -> "配股${formatShareCount(transaction.transaction.dividendShares)}股"
            "減資" -> "減資${String.format(Locale.US, "%.1f", transaction.transaction.capitalReductionRatio)}%"
            "分割" -> "分割(1→${transaction.transaction.stockSplitRatio.toInt()})"
            "融資還款" -> if (transaction.transaction.marginRepayment > 0.0) {
                "還融資${formatMarketAmount(transaction.transaction.marginRepayment, transaction.market)}"
            } else {
                "付融資利息${formatMarketAmount(transaction.transaction.marginActualInterest, transaction.market)}"
            }
            "融券賣出" -> "融券賣${formatShareCount(transaction.transaction.sellShares)}股"
            "買券還券" -> "買券還${formatShareCount(transaction.transaction.shortCoverShares)}股"
            "融券補償" -> "融券補償${formatMarketAmount(transaction.transaction.shortCompensation, transaction.market)}"
            else -> transaction.transaction.type
        }
        Text(
            text = transactionText,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1.5f), 
            textAlign = TextAlign.Center
        )

        val priceText = when (transaction.transaction.type) {
            "買進", "融資買進", "買券還券" -> String.format(Locale.US, "%,.2f", transaction.transaction.buyPrice)
            "賣出", "融券賣出" -> String.format(Locale.US, "%,.2f", transaction.transaction.sellPrice)
            else -> "-"
        }
        Text(
            text = priceText,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center
        )

        Text(
            text = amountText,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            color = amountColor,
            textAlign = TextAlign.End
        )
    }
}
