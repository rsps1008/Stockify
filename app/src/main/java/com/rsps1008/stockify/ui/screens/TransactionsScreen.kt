package com.rsps1008.stockify.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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

    LazyColumn(modifier = Modifier.padding(16.dp)) {
        groupedTransactions.forEach { (date, transactionsOnDate) ->
            item {
                Text(
                    text = date,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            items(transactionsOnDate) { transaction ->
                TransactionCard(transaction, navController)
            }
        }
    }
}

@Composable
fun TransactionCard(transaction: TransactionUiState, navController: NavController) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { navController.navigate(Screen.TransactionDetail.createRoute(transaction.transaction.id)) }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = transaction.stockName, style = MaterialTheme.typography.bodyLarge)
                val transactionText = when (transaction.transaction.type) {
                    "買進" -> "買${transaction.transaction.shares.toInt()}股"
                    "賣出" -> "賣${transaction.transaction.shares.toInt()}股"
                    "配息" -> "配息${transaction.transaction.income.toInt()}元"
                    "配股" -> "配股${transaction.transaction.dividendShares.toInt()}股"
                    else -> transaction.transaction.type
                }
                Text(
                    text = transactionText,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            if (transaction.transaction.type == "買進" || transaction.transaction.type == "賣出") {
                Text(
                    text = String.format("%,.2f", transaction.transaction.price),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            val amount = when (transaction.transaction.type) {
                "買進" -> -transaction.transaction.expense
                "賣出" -> transaction.transaction.income
                "配息" -> transaction.transaction.income
                "配股" -> 0.0
                else -> 0.0
            }
            Text(
                text = String.format("%,.0f", amount),
                style = MaterialTheme.typography.bodyLarge,
                color = if (amount >= 0) Color.Red else Color.Green
            )
        }
    }
}
