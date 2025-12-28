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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.rsps1008.stockify.StockifyApplication
import com.rsps1008.stockify.ui.navigation.Screen
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
            stockCode = stockCode
        )
    )
    val holdingInfo by viewModel.holdingInfo.collectAsState()
    val transactions by viewModel.transactions.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "${holdingInfo?.stock?.name} ${holdingInfo?.stock?.code}") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            holdingInfo?.let { StockDetailSummary(it) }
            Spacer(modifier = Modifier.height(16.dp))
            TransactionListHeader()
            TransactionList(transactions, navController)
        }
    }
}

@Composable
fun StockDetailSummary(holdingInfo: HoldingInfo) {
    val totalPlColor = if (holdingInfo.totalPL >= 0) Color.Red else Color.Green
    val dailyPlColor = if (holdingInfo.dailyChange >= 0) Color.Red else Color.Green

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("累積損益", style = MaterialTheme.typography.bodySmall)
            Row {
                Text(
                    text = String.format("%,.0f", abs(holdingInfo.totalPL)),
                    style = MaterialTheme.typography.headlineLarge,
                    color = totalPlColor,
                    modifier = Modifier.alignByBaseline()
                )
                Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                Text(
                    text = "${String.format("%.2f", holdingInfo.totalPLPercentage)}%",
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
fun TransactionListHeader() {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text = "日期", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(text = "交易", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.5f))
        Text(text = "股價", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(text = "收支", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
fun TransactionList(transactions: List<TransactionUiState>, navController: NavController) {
    LazyColumn {
        items(transactions) { transaction ->
            TransactionRow(transaction, navController)
        }
    }
}

@Composable
fun TransactionRow(transaction: TransactionUiState, navController: NavController) {
    val amountColor = when (transaction.transaction.type) {
        "買進" -> Color.Green
        "賣出", "配息" -> Color.Red
        else -> Color.Unspecified
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { navController.navigate(Screen.TransactionDetail.createRoute(transaction.transaction.id)) }
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            val sdf = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
            Text(text = sdf.format(Date(transaction.transaction.date)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            
            val transactionText = when(transaction.transaction.type) {
                "買進" -> "買${transaction.transaction.shares.toInt()}股"
                "賣出" -> "賣${transaction.transaction.shares.toInt()}股"
                "配息" -> "配息${transaction.transaction.price}元"
                "配股" -> "配股${transaction.transaction.shares.toInt()}股"
                else -> transaction.transaction.type
            }
            Text(text = transactionText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1.5f))
            
            val priceText = if (transaction.transaction.type == "買進" || transaction.transaction.type == "賣出") String.format("%,.2f", transaction.transaction.price) else "-"
            Text(text = priceText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            
            val amount = when (transaction.transaction.type) {
                "買進" -> - (transaction.transaction.price * transaction.transaction.shares + transaction.transaction.fee)
                "賣出" -> transaction.transaction.price * transaction.transaction.shares - transaction.transaction.fee
                "配息" -> transaction.transaction.price * transaction.transaction.shares
                "配股" -> 0.0
                else -> 0.0
            }
            Text(text = String.format("%,.0f", amount), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), color = amountColor)
        }
    }
}