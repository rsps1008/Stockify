package com.rsps1008.stockify.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.rsps1008.stockify.R
import com.rsps1008.stockify.StockifyApplication
import com.rsps1008.stockify.ui.navigation.Screen
import com.rsps1008.stockify.ui.theme.StockifyAppTheme
import com.rsps1008.stockify.ui.viewmodel.TransactionsViewModel
import com.rsps1008.stockify.ui.viewmodel.ViewModelFactory
import com.rsps1008.stockify.data.formatMarketAmount
import com.rsps1008.stockify.data.formatShareCount
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.statusBarsPadding

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
    val transactions by viewModel.transactions.collectAsState()

    val dateFormatter = remember {
        SimpleDateFormat("yyyy/MM/dd (E)", Locale.getDefault())
    }
    val groupedTransactions = remember(transactions, dateFormatter) {
        transactions.groupBy {
            dateFormatter.format(Date(it.transaction.date))
        }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
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
                modifier = Modifier.fillMaxWidth(0.35f)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            TransactionsListHeader()
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            groupedTransactions.forEach { (date, transactionsOnDate) ->
                item {
                    Text(
                        text = date,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    )
                }
                items(
                    items = transactionsOnDate,
                    key = { it.transaction.id }
                ) { transaction ->
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
        "減資" -> String.format("%,.0f", transaction.transaction.cashReturned)
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
            "買進" -> "買${formatShareCount(transaction.transaction.buyShares)}股"
            "融資買進" -> "融資買${formatShareCount(transaction.transaction.buyShares)}股"
            "賣出" -> if (transaction.transaction.marginRepaymentLotId.isNotBlank()) {
                "賣${formatShareCount(transaction.transaction.sellShares)}股／還融資"
            } else {
                "賣${formatShareCount(transaction.transaction.sellShares)}股"
            }
            "配息" -> "配息${formatMarketAmount(transaction.transaction.income, transaction.market)}元"
            "配股" -> "配股${formatShareCount(transaction.transaction.dividendShares)}股"
            "減資" -> "減資${String.format("%.1f", transaction.transaction.capitalReductionRatio)}%"
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
            "買進", "融資買進", "買券還券" -> String.format("%,.2f", transaction.transaction.buyPrice)
            "賣出", "融券賣出" -> String.format("%,.2f", transaction.transaction.sellPrice)
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
