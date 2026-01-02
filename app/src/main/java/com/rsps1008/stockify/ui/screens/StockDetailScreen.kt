package com.rsps1008.stockify.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
                    // 新增交易
                    IconButton(onClick = {
                        navController.navigate(
                            Screen.AddTransaction.createRoute(null, stockCode)
                        )
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "Add transaction")
                    }

                    // 刪除全部交易
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
            RealtimePriceRow(stockCode, viewModel)
            Spacer(modifier = Modifier.height(8.dp))
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
            Row(verticalAlignment = Alignment.Bottom) {

                // 大字（累積損益）
                AnimatedNumberText(
                    text = String.format("%,.0f", holdingInfo.totalPL),
                    color = totalPlColor,
                    style = MaterialTheme.typography.headlineLarge
                )

                Spacer(modifier = Modifier.width(4.dp))

                // 小字（%）→ 用 padding 調整
                AnimatedNumberText(
                    text = String.format("%+.2f%%", holdingInfo.totalPLPercentage),
                    color = totalPlColor,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 4.dp)   // ★ 微調這裡
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
                    AnimatedNumberText(
                        text = String.format("%,.0f", kotlin.math.abs(holdingInfo.dailyChange * holdingInfo.shares)),
                        color = dailyPlColor,
                        style = MaterialTheme.typography.bodyLarge
                    )
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
private fun RealtimePriceRow(stockCode: String, viewModel: StockDetailViewModel) {
    val realtimeMap by viewModel.realtimeStockInfo.collectAsState()
    val info = realtimeMap[stockCode]

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {

        if (info != null) {
            val isUp = info.change > 0
            val isDown = info.change < 0
            val arrow = when {
                isUp -> "▴"
                isDown -> "▾"
                else -> ""
            }

            val color = when {
                isUp -> StockifyAppTheme.stockColors.gain
                isDown -> StockifyAppTheme.stockColors.loss
                else -> Color.Unspecified
            }

            // 使用絕對值，不要有 + -
            val absChange = kotlin.math.abs(info.change)
            val absPercent = kotlin.math.abs(info.changePercent)

            // 左側：價格 + 上下漲幅
            AnimatedPriceText(
                text = if (arrow.isNotEmpty()) {
                    String.format(
                        Locale.US,
                        "%,.2f %s%.2f (%.2f%%)",
                        info.currentPrice,
                        arrow,
                        absChange,
                        absPercent
                    )
                } else {
                    // 平盤
                    String.format(Locale.US, "%,.2f 0.00 (0.00%%)", info.currentPrice)
                },
                color = color
            )

            Spacer(modifier = Modifier.weight(1f))

            // 右側：更新時間
            val timeText = info.lastUpdated?.let {
                SimpleDateFormat("MM/dd HH:mm:ss", Locale.US).format(Date(it))
            } ?: "--:--"

            AnimatedTimeText(
                text = timeText,
                color = Color.Gray
            )
        } else {
            Text(
                text = "更新中…",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )
        }
    }
}

@Composable
fun AnimatedNumberText(
    text: String,
    color: Color,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier
) {
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            slideInVertically { it } + fadeIn() togetherWith
                    slideOutVertically { -it } + fadeOut()
        },
        label = "AnimatedNumberText"
    ) { targetText ->
        Text(
            text = targetText,
            color = color,
            style = style,
            modifier = modifier
        )
    }
}


@Composable
fun AnimatedPriceText(text: String, color: Color) {
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            slideInVertically { height -> height } + fadeIn() togetherWith
                    slideOutVertically { height -> -height / 2 } + fadeOut()
        }
    ) { targetText ->
        Text(
            text = targetText,
            color = color,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun AnimatedTimeText(text: String, color: Color) {
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            slideInVertically { height -> height / 3 } + fadeIn() togetherWith
                    slideOutVertically { height -> -height / 3 } + fadeOut()
        }
    ) { targetText ->
        Text(
            text = targetText,
            color = color,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun TransactionRow(transaction: TransactionUiState, navController: NavController) {
    val amountColor = when (transaction.transaction.type) {
        "買進" -> StockifyAppTheme.stockColors.loss
        "賣出", "配息", "配股" -> StockifyAppTheme.stockColors.gain
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
            "配息" -> "配息"
            "配股" -> "配股"
            else -> transaction.transaction.type
        }
        Text(text = transactionText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1.5f), textAlign = TextAlign.Center)

        val priceText = when (transaction.transaction.type) {
            "買進" -> String.format("%,.2f", transaction.transaction.buyPrice)
            "賣出" -> String.format("%,.2f", transaction.transaction.sellPrice)
            else -> "-"
        }
        Text(text = priceText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)

        val amountText = when (transaction.transaction.type) {
            "買進" -> String.format("%,.0f", -transaction.transaction.expense)
            "賣出" -> String.format("%,.0f", transaction.transaction.income)
            "配息" -> String.format("%,.0f", transaction.transaction.income)
            "配股" -> "${transaction.transaction.dividendShares.toInt()}股"
            else -> ""
        }
        Text(text = amountText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), color = amountColor, textAlign = TextAlign.End)
    }

}
