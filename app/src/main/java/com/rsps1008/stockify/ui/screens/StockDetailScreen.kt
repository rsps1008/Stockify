package com.rsps1008.stockify.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.rsps1008.stockify.StockifyApplication
import com.rsps1008.stockify.ui.navigation.Screen
import com.rsps1008.stockify.ui.theme.StockifyAppTheme
import com.rsps1008.stockify.ui.viewmodel.StockDetailViewModel
import com.rsps1008.stockify.ui.viewmodel.ViewModelFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockDetailScreen(stockCode: String, navController: NavController) {
    val application = LocalContext.current.applicationContext as StockifyApplication
    val viewModel: StockDetailViewModel = viewModel(
        factory = ViewModelFactory(
            stockDao = application.database.stockDao(),
            realtimeStockDataService = application.realtimeStockDataService,
            settingsDataStore = application.settingsDataStore,
            stockCode = stockCode
        )
    )
    val holdingInfo by viewModel.holdingInfo.collectAsState()
    val transactions by viewModel.transactions.collectAsState()
    val showDeleteConfirmDialog by viewModel.showDeleteConfirmDialog.collectAsState()

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.onDeleteTransactionsCancelled() },
            title = { Text("刪除確認") },
            text = { Text("確定要刪除這支股票的所有交易紀錄嗎？此動作無法復原。") },
            confirmButton = {
                TextButton(onClick = { 
                    viewModel.onDeleteTransactionsConfirmed()
                    navController.popBackStack()
                }) {
                    Text("確定")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.onDeleteTransactionsCancelled() }) {
                    Text("取消")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "${holdingInfo?.stock?.name} ${holdingInfo?.stock?.code}") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.onDeleteTransactionsClicked() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete all transactions")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            holdingInfo?.let { StockDetailSummary(it) }
            Spacer(modifier = Modifier.height(16.dp))
            TransactionListHeader()
            TransactionList(transactions, navController)
        }
    }
}

@Composable
private fun StockDetailSummary(holdingInfo: HoldingInfo) {
    val totalPlColor = if (holdingInfo.totalPL >= 0) StockifyAppTheme.stockColors.gain else StockifyAppTheme.stockColors.loss
    val dailyPlColor = if (holdingInfo.dailyChange >= 0) StockifyAppTheme.stockColors.gain else StockifyAppTheme.stockColors.loss

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("累積損益", style = MaterialTheme.typography.bodySmall)
            Row {
                Text(
                    text = String.format("%,.0f", holdingInfo.totalPL),
                    style = MaterialTheme.typography.headlineLarge,
                    color = totalPlColor,
                    modifier = Modifier.alignByBaseline()
                )
                Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                Text(
                    text = String.format("%+.2f%%", holdingInfo.totalPLPercentage),
                    style = MaterialTheme.typography.bodyLarge,
                    color = totalPlColor,
                    modifier = Modifier.alignByBaseline()
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "成本均", style = MaterialTheme.typography.bodySmall)
                    Text(text = String.format("%,.2f", holdingInfo.averageCost), style = MaterialTheme.typography.bodyLarge)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "買均", style = MaterialTheme.typography.bodySmall)
                    Text(text = String.format("%,.2f", holdingInfo.buyAverage), style = MaterialTheme.typography.bodyLarge)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "持股數", style = MaterialTheme.typography.bodySmall)
                    Text(text = String.format("%,.0f", holdingInfo.shares), style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "持股日損益", style = MaterialTheme.typography.bodySmall)
                    Text(text = String.format("%,.0f", abs(holdingInfo.dailyChange * holdingInfo.shares)), style = MaterialTheme.typography.bodyLarge, color = dailyPlColor)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "持股市值", style = MaterialTheme.typography.bodySmall)
                    Text(text = String.format("%,.0f", holdingInfo.marketValue), style = MaterialTheme.typography.bodyLarge)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "股息收入", style = MaterialTheme.typography.bodySmall)
                    Text(text = String.format("%,.0f", holdingInfo.dividendIncome), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun TransactionListHeader() {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(text = "日期", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.5f))
        Text(text = "交易", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.5f), textAlign = TextAlign.Center)
        Text(text = "股價", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        Text(text = "收支", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

@Composable
private fun TransactionList(transactions: List<TransactionUiState>, navController: NavController) {
    LazyColumn {
        items(transactions) { transaction ->
            TransactionRow(transaction, navController)
            Divider()
        }
    }
}

@Composable
private fun TransactionRow(transaction: TransactionUiState, navController: NavController) {
    val amountColor = when (transaction.transaction.type) {
        "買進" -> StockifyAppTheme.stockColors.loss
        "賣出", "配息" -> StockifyAppTheme.stockColors.gain
        else -> Color.Unspecified
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { navController.navigate(Screen.TransactionDetail.createRoute(transaction.transaction.id)) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val sdf = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
        Text(text = sdf.format(Date(transaction.transaction.date)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1.5f))

        val transactionText = when(transaction.transaction.type) {
            "買進" -> "買${transaction.transaction.buyShares.toInt()}股"
            "賣出" -> "賣${transaction.transaction.sellShares.toInt()}股"
            "配息" -> "配息${transaction.transaction.income.toInt()}元"
            "配股" -> "配股${transaction.transaction.dividendShares.toInt()}股"
            else -> transaction.transaction.type
        }
        Text(text = transactionText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1.5f), textAlign = TextAlign.Center)

        val priceText = when (transaction.transaction.type) {
            "買進" -> String.format("%,.2f", transaction.transaction.buyPrice)
            "賣出" -> String.format("%,.2f", transaction.transaction.sellPrice)
            else -> "-"
        }
        Text(text = priceText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)

        val amount = when (transaction.transaction.type) {
            "買進" -> -transaction.transaction.expense
            "賣出" -> transaction.transaction.income
            "配息" -> transaction.transaction.income
            "配股" -> 0.0
            else -> 0.0
        }
        Text(text = String.format("%,.0f", amount), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), color = amountColor, textAlign = TextAlign.End)
    }
}
