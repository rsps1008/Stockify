package com.rsps1008.stockify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.rsps1008.stockify.ui.viewmodel.TransactionsViewModel
import com.rsps1008.stockify.ui.viewmodel.ViewModelFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TransactionsScreen(navController: NavController) {
    val application = LocalContext.current.applicationContext as StockifyApplication
    val viewModel: TransactionsViewModel = viewModel(
        factory = ViewModelFactory(application.database.stockDao())
    )
    val transactions by viewModel.transactions.collectAsState()

    val groupedTransactions = transactions.groupBy {
        SimpleDateFormat("yyyy/MM/dd (E)", Locale.getDefault()).format(Date(it.transaction.date))
    }

    Column {
        TransactionsListHeader()
        LazyColumn(modifier = Modifier.padding(horizontal = 16.dp)) {
            groupedTransactions.forEach { (date, transactionsOnDate) ->
                item {
                    Text(
                        text = date,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                items(transactionsOnDate) { transaction ->
                    TransactionRow(transaction, navController)
                }
            }
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
            "買進" -> "買${transaction.transaction.buyShares.toInt()}股"
            "賣出" -> "賣${transaction.transaction.sellShares.toInt()}股"
            "配息" -> "配息${transaction.transaction.income.toInt()}元"
            "配股" -> "配股${transaction.transaction.dividendShares.toInt()}股"
            else -> transaction.transaction.type
        }
        Text(
            text = transactionText,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1.5f), 
            textAlign = TextAlign.Center
        )

        val priceText = when (transaction.transaction.type) {
            "買進" -> String.format("%,.2f", transaction.transaction.buyPrice)
            "賣出" -> String.format("%,.2f", transaction.transaction.sellPrice)
            else -> "-"
        }
        Text(
            text = priceText,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center
        )

        val amount = when (transaction.transaction.type) {
            "買進" -> -transaction.transaction.expense
            "賣出" -> transaction.transaction.income
            "配息" -> transaction.transaction.income
            "配股" -> 0.0
            else -> 0.0
        }
        Text(
            text = String.format("%,.0f", amount),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            color = amountColor,
            textAlign = TextAlign.End
        )
    }
}
