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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.rsps1008.stockify.StockifyApplication
import com.rsps1008.stockify.R
import com.rsps1008.stockify.ui.navigation.Screen
import com.rsps1008.stockify.ui.theme.StockifyAppTheme
import com.rsps1008.stockify.ui.viewmodel.StockDetailViewModel
import com.rsps1008.stockify.ui.viewmodel.DeleteTransactionsScope
import com.rsps1008.stockify.ui.viewmodel.DeleteTransactionsState
import com.rsps1008.stockify.ui.viewmodel.ViewModelFactory
import com.rsps1008.stockify.data.formatMarketAmount
import com.rsps1008.stockify.data.formatShareCount
import com.rsps1008.stockify.data.StockMarket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun StockDetailScreen(stockCode: String, market: String? = null, navController: NavController) {
    val normalizedMarket = StockMarket.normalize(market ?: StockMarket.inferFromCode(stockCode))
    val application = LocalContext.current.applicationContext as StockifyApplication
    val viewModel: StockDetailViewModel = viewModel(
        factory = ViewModelFactory(
            stockDao = application.database.stockDao(),
            realtimeStockDataService = application.realtimeStockDataService,
            settingsDataStore = application.settingsDataStore,
            stockCode = stockCode,
            market = normalizedMarket,
            exchangeRateService = application.exchangeRateService,
            twseStockHistoryService = application.twseStockHistoryService
        )
    )
    val holdingInfo by viewModel.holdingInfo.collectAsState()
    val transactions by viewModel.transactions.collectAsState()
    val deleteTransactionsState by viewModel.deleteTransactionsState.collectAsState()

    LaunchedEffect(deleteTransactionsState) {
        if (deleteTransactionsState is DeleteTransactionsState.Success) {
            navController.popBackStack()
        }
    }

    val deleteScope = when (val state = deleteTransactionsState) {
        is DeleteTransactionsState.Confirming -> state.scope
        is DeleteTransactionsState.Deleting -> state.scope
        is DeleteTransactionsState.Error -> state.scope
        else -> null
    }
    if (deleteScope != null) {
        val scope = deleteScope
        val isDeleting = deleteTransactionsState is DeleteTransactionsState.Deleting
        val errorMessage = (deleteTransactionsState as? DeleteTransactionsState.Error)?.message
        val stockName = holdingInfo?.stock?.name ?: stockCode
        val isAllAccounts = scope is DeleteTransactionsScope.AllAccounts

        AlertDialog(
            onDismissRequest = { viewModel.onDeleteTransactionsCancelled() },
            title = {
                Text(if (isAllAccounts) "刪除此股票全部帳戶交易" else "刪除此股票目前帳戶交易")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (isAllAccounts) {
                            "確定要刪除 $stockName 在所有帳戶的交易紀錄嗎？此動作無法復原。"
                        } else {
                            "確定要刪除 $stockName 在目前帳戶的所有交易紀錄嗎？此動作無法復原。"
                        }
                    )
                    errorMessage?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.onDeleteTransactionsConfirmed() },
                    enabled = !isDeleting
                ) {
                    if (isDeleting) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("刪除中...")
                        }
                    } else {
                        Text(if (errorMessage == null) "確定" else "重試")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.onDeleteTransactionsCancelled() },
                    enabled = !isDeleting
                ) {
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
                            Screen.AddTransaction.createRoute(null, stockCode, normalizedMarket)
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
            // 1. StockDetailSummary: Fixed at the top outside LazyColumn
            holdingInfo?.let { info ->
                StockDetailSummary(
                    holdingInfo = info,
                    onYahooClick = {
                        navController.navigate(
                            Screen.YahooQuote.createRoute(info.stock.code, info.stock.market)
                        )
                    }
                )
            }

            // 2. Scrollable LazyColumn below the summary
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                holdingInfo?.let { info ->
                    if (com.rsps1008.stockify.data.StockMarket.isTw(info.stock.market) || info.stock.market == com.rsps1008.stockify.data.StockMarket.US) {
                        item {
                            HistoryChartSection(viewModel = viewModel)
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                            RealtimePriceRow(stockCode, normalizedMarket, viewModel)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // 3. stickyHeader: TransactionListHeader sticks below StockDetailSummary when scrolled!
                stickyHeader {
                    TransactionListHeader()
                }

                items(
                    items = transactions,
                    key = { it.transaction.id }
                ) { transaction ->
                    TransactionRow(transaction, navController)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun StockDetailSummary(
    holdingInfo: HoldingInfo,
    onYahooClick: () -> Unit
) {
    val totalPlColor = if (holdingInfo.totalPL >= 0) StockifyAppTheme.stockColors.gain else StockifyAppTheme.stockColors.loss
    val positionDailyPL = holdingInfo.dailyChange * (holdingInfo.shares - holdingInfo.shortOutstandingShares)
    val dailyPlColor = if (positionDailyPL >= 0) StockifyAppTheme.stockColors.gain else StockifyAppTheme.stockColors.loss

    Card(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(
                    text = "累積損益",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {

                    // 大字（累積損益）
                    AnimatedNumberText(
                        text = formatMarketAmount(holdingInfo.totalPL, holdingInfo.stock.market),
                        color = totalPlColor,
                        style = MaterialTheme.typography.headlineLarge
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    // 小字（%）→ 用 padding 調整
                    AnimatedNumberText(
                        text = String.format(Locale.US, "%+.2f%%", holdingInfo.totalPLPercentage),
                        color = totalPlColor,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "成本均", style = MaterialTheme.typography.bodySmall)
                        Text(text = String.format(Locale.US, "%,.2f", holdingInfo.averageCost), style = MaterialTheme.typography.bodyLarge)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "買均", style = MaterialTheme.typography.bodySmall)
                        Text(text = String.format(Locale.US, "%,.2f", holdingInfo.buyAverage), style = MaterialTheme.typography.bodyLarge)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "持股數", style = MaterialTheme.typography.bodySmall)
                        Text(text = formatShareCount(holdingInfo.shares), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "持股日損益", style = MaterialTheme.typography.bodySmall)
                        AnimatedNumberText(
                            text = formatMarketAmount(kotlin.math.abs(positionDailyPL), holdingInfo.stock.market),
                            color = dailyPlColor,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "持股市值", style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = formatMarketAmount(holdingInfo.marketValue, holdingInfo.stock.market),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "股息收入", style = MaterialTheme.typography.bodySmall)
                        Text(text = formatMarketAmount(holdingInfo.dividendIncome, holdingInfo.stock.market), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (holdingInfo.marginOutstandingPrincipal > 0.0 || holdingInfo.marginAccruedInterest > 0.0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "融資未償", style = MaterialTheme.typography.bodySmall)
                            Text(text = formatMarketAmount(holdingInfo.marginOutstandingPrincipal, holdingInfo.stock.market), style = MaterialTheme.typography.bodyLarge)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "應計利息", style = MaterialTheme.typography.bodySmall)
                            Text(text = formatMarketAmount(holdingInfo.marginAccruedInterest, holdingInfo.stock.market), style = MaterialTheme.typography.bodyLarge)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "融資淨值", style = MaterialTheme.typography.bodySmall)
                            Text(text = formatMarketAmount(holdingInfo.marginNetEquity, holdingInfo.stock.market), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                if (holdingInfo.shortOutstandingShares > 0.0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "融券尚欠", style = MaterialTheme.typography.bodySmall)
                            Text(text = "${formatShareCount(holdingInfo.shortOutstandingShares)}股", style = MaterialTheme.typography.bodyLarge)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "回補市值", style = MaterialTheme.typography.bodySmall)
                            Text(text = formatMarketAmount(holdingInfo.shortMarketLiability, holdingInfo.stock.market), style = MaterialTheme.typography.bodyLarge)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "應計借券費", style = MaterialTheme.typography.bodySmall)
                            Text(text = formatMarketAmount(holdingInfo.shortAccruedBorrowFee, holdingInfo.stock.market), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 10.dp)
                    .size(24.dp)
                    .clickable(onClick = onYahooClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_yahoo_brand),
                    contentDescription = "Yahoo 股市"
                )
            }
        }
    }
}

@Composable
private fun TransactionListHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(vertical = 8.dp)
    ) {
        Text(text = "日期", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.5f))
        Text(text = "交易", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.5f), textAlign = TextAlign.Center)
        Text(text = "股價", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        Text(text = "收支", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}



@Composable
private fun RealtimePriceRow(stockCode: String, market: String, viewModel: StockDetailViewModel) {
    val realtimeMap by viewModel.realtimeStockInfo.collectAsState()
    val info = realtimeMap[com.rsps1008.stockify.data.stockCacheKey(market, stockCode)]

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
    val locale = LocalConfiguration.current.locales[0]
    val dateFormatter = remember(locale) { SimpleDateFormat("yyyy/MM/dd", locale) }
    val amountText = when (transaction.transaction.type) {
        "買進" -> formatMarketAmount(-transaction.transaction.expense, transaction.market)
        "賣出" -> formatMarketAmount(
            transaction.transaction.income - transaction.transaction.marginRepayment - transaction.transaction.marginActualInterest,
            transaction.market
        )
        "融資買進" -> formatMarketAmount(
            -(if (transaction.transaction.marginSelfFundedOverridden) {
                transaction.transaction.marginSelfFunded
            } else {
                transaction.transaction.expense - transaction.transaction.marginPrincipal
            }),
            transaction.market
        )
        "融資還款" -> formatMarketAmount(
            -(transaction.transaction.marginRepayment + transaction.transaction.marginActualInterest),
            transaction.market
        )
        "融券賣出" -> formatMarketAmount(transaction.transaction.income, transaction.market)
        "買券還券" -> formatMarketAmount(-transaction.transaction.expense, transaction.market)
        "融券補償" -> formatMarketAmount(-transaction.transaction.shortCompensation, transaction.market)
        "配息" -> formatMarketAmount(transaction.transaction.income, transaction.market)
        "配股" -> "${formatShareCount(transaction.transaction.dividendShares)}股"
        "減資" -> String.format(Locale.US, "%,.1f", transaction.transaction.cashReturned)
        "分割" -> "-"
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
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = dateFormatter.format(Date(transaction.transaction.date)),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1.5f)
        )

        val transactionText = when(transaction.transaction.type) {
            "買進" -> "買${formatShareCount(transaction.transaction.buyShares)}股"
            "融資買進" -> "融資買${formatShareCount(transaction.transaction.buyShares)}股"
            "賣出" -> if (transaction.transaction.marginRepaymentLotId.isNotBlank()) {
                "賣${formatShareCount(transaction.transaction.sellShares)}股／還融資"
            } else {
                "賣${formatShareCount(transaction.transaction.sellShares)}股"
            }
            "融券賣出" -> "融券賣${formatShareCount(transaction.transaction.sellShares)}股"
            "融資還款" -> if (transaction.transaction.marginRepayment > 0.0) {
                "還融資${formatMarketAmount(transaction.transaction.marginRepayment, transaction.market)}"
            } else {
                "付融資利息${formatMarketAmount(transaction.transaction.marginActualInterest, transaction.market)}"
            }
            "買券還券" -> "買券還${formatShareCount(transaction.transaction.shortCoverShares)}股"
            "融券補償" -> "融券補償${formatMarketAmount(transaction.transaction.shortCompensation, transaction.market)}"
            "配息" -> "配息"
            "配股" -> "配股"
            "減資" -> "減資${String.format(Locale.US, "%.1f", transaction.transaction.capitalReductionRatio)}%"
            "分割" -> "分割(1→${transaction.transaction.stockSplitRatio.toInt()})"
            else -> transaction.transaction.type
        }
        Text(text = transactionText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1.5f), textAlign = TextAlign.Center)

        val priceText = when (transaction.transaction.type) {
            "買進", "融資買進", "買券還券" -> String.format(Locale.US, "%,.2f", transaction.transaction.buyPrice)
            "賣出", "融券賣出" -> String.format(Locale.US, "%,.2f", transaction.transaction.sellPrice)
            else -> "-"
        }
        Text(text = priceText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)

        Text(text = amountText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), color = amountColor, textAlign = TextAlign.End)
    }

}
