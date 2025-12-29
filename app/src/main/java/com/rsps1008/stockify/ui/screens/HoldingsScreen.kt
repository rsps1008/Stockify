package com.rsps1008.stockify.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.rsps1008.stockify.R
import com.rsps1008.stockify.StockifyApplication
import com.rsps1008.stockify.ui.navigation.Screen
import com.rsps1008.stockify.ui.theme.StockifyAppTheme
import com.rsps1008.stockify.ui.viewmodel.HoldingsViewModel
import com.rsps1008.stockify.ui.viewmodel.ViewModelFactory
import kotlin.math.abs

@Composable
fun HoldingsScreen(navController: NavController) {
    val application = LocalContext.current.applicationContext as StockifyApplication
    val viewModel: HoldingsViewModel = viewModel(
        factory = ViewModelFactory(
            stockDao = application.database.stockDao(),
            realtimeStockDataService = application.realtimeStockDataService,
            settingsDataStore = application.settingsDataStore
        )
    )
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = R.drawable.stockify),
            contentDescription = "Stockify Logo",
            modifier = Modifier
                .fillMaxWidth(0.35f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        SummarySection(uiState)
        Spacer(modifier = Modifier.height(16.dp))
        HoldingsListHeader()
        HoldingsList(uiState.holdings, navController)
    }
}

@Composable
fun SummarySection(uiState: HoldingsUiState) {
    val dailyPlColor = if (uiState.dailyPL >= 0) StockifyAppTheme.stockColors.gain else StockifyAppTheme.stockColors.loss
    val cumulativePlColor = if (uiState.cumulativePL >= 0) StockifyAppTheme.stockColors.gain else StockifyAppTheme.stockColors.loss
    var showMarketValue by remember { mutableStateOf(true) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("累積損益", style = MaterialTheme.typography.bodySmall)
            Row {
                Text(
                    text = String.format("%,.0f", uiState.cumulativePL),
                    style = MaterialTheme.typography.headlineLarge,
                    color = cumulativePlColor,
                    modifier = Modifier.alignByBaseline()
                )
                Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                Text(
                    text = String.format("%+.2f%%", uiState.cumulativePLPercentage),
                    style = MaterialTheme.typography.bodyLarge,
                    color = cumulativePlColor,
                    modifier = Modifier.alignByBaseline()
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "持股日損益", style = MaterialTheme.typography.bodySmall)
                    Text(text = String.format("%,.0f", abs(uiState.dailyPL)), style = MaterialTheme.typography.bodyLarge, color = dailyPlColor)
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { showMarketValue = !showMarketValue }
                ) {
                    Text(
                        text = buildAnnotatedString {
                            withStyle(style = SpanStyle(fontWeight = if (showMarketValue) FontWeight.Bold else FontWeight.Normal)) {
                                append("持股市值")
                            }
                            append("/")
                            withStyle(style = SpanStyle(fontWeight = if (!showMarketValue) FontWeight.Bold else FontWeight.Normal)) {
                                append("成本")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    val valueToShow = if (showMarketValue) uiState.marketValue else uiState.totalCost
                    Text(text = String.format("%,.0f", valueToShow), style = MaterialTheme.typography.bodyLarge)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "股息收入", style = MaterialTheme.typography.bodySmall)
                    Text(text = String.format("%,.0f", uiState.dividendIncome), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
fun HoldingsListHeader() {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text = "股票/股數", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(text = "股價", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(text = "成本均/買均", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        Text(text = "總損益", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

@Composable
fun HoldingsList(holdings: List<HoldingInfo>, navController: NavController) {
    LazyColumn {
        items(holdings) { holding ->
            HoldingCard(holding, navController)
        }
    }
}

@Composable
fun HoldingCard(holding: HoldingInfo, navController: NavController) {
    val dailyChangeColor = if (holding.dailyChange >= 0) StockifyAppTheme.stockColors.gain else StockifyAppTheme.stockColors.loss
    val totalPlColor = if (holding.totalPL >= 0) StockifyAppTheme.stockColors.gain else StockifyAppTheme.stockColors.loss

    val dailyChangeSymbol = if (holding.dailyChange >= 0) "▴" else "▾"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { navController.navigate(Screen.StockDetail.createRoute(holding.stock.code)) }
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = holding.stock.name, style = MaterialTheme.typography.bodyLarge)
                Text(text = "${String.format("%,.0f", holding.shares)}股", style = MaterialTheme.typography.bodySmall)
            }
            Column(modifier = Modifier.weight(1f)) {
                AnimatedContent(targetState = holding.currentPrice, transitionSpec = {
                    if (targetState > initialState) {
                        slideInVertically { height -> height } + fadeIn() togetherWith
                                slideOutVertically { height -> -height } + fadeOut()
                    } else {
                        slideInVertically { height -> -height } + fadeIn() togetherWith
                                slideOutVertically { height -> height } + fadeOut()
                    }.using(
                        SizeTransform(clip = false)
                    )
                }) { targetPrice ->
                    Text(
                        text = String.format("%,.2f", targetPrice),
                        style = MaterialTheme.typography.bodyLarge,
                        color = dailyChangeColor
                    )
                }
                AnimatedContent(targetState = holding.dailyChange, transitionSpec = {
                    if (targetState > initialState) {
                        slideInVertically { height -> height } + fadeIn() togetherWith
                                slideOutVertically { height -> -height } + fadeOut()
                    } else {
                        slideInVertically { height -> -height } + fadeIn() togetherWith
                                slideOutVertically { height -> height } + fadeOut()
                    }.using(
                        SizeTransform(clip = false)
                    )
                }) { targetChange ->
                    Text(
                        text = "$dailyChangeSymbol${String.format("%.2f", abs(targetChange))} (${String.format("%.2f", abs(holding.dailyChangePercentage))}%)",
                        style = MaterialTheme.typography.bodySmall,
                        color = dailyChangeColor
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = String.format("%,.2f", holding.averageCost), style = MaterialTheme.typography.bodyLarge)
                Text(text = String.format("%,.2f", holding.buyAverage), style = MaterialTheme.typography.bodySmall)
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                AnimatedContent(targetState = holding.totalPL, transitionSpec = {
                    if (targetState > initialState) {
                        slideInVertically { height -> height } + fadeIn() togetherWith
                                slideOutVertically { height -> -height } + fadeOut()
                    } else {
                        slideInVertically { height -> -height } + fadeIn() togetherWith
                                slideOutVertically { height -> height } + fadeOut()
                    }.using(
                        SizeTransform(clip = false)
                    )
                }) { targetTotalPL ->
                    Text(
                        text = String.format("%,.0f", targetTotalPL),
                        style = MaterialTheme.typography.bodyLarge,
                        color = totalPlColor
                    )
                }

                AnimatedContent(targetState = holding.totalPLPercentage, transitionSpec = {
                    if (targetState > initialState) {
                        slideInVertically { height -> height } + fadeIn() togetherWith
                                slideOutVertically { height -> -height } + fadeOut()
                    } else {
                        slideInVertically { height -> -height } + fadeIn() togetherWith
                                slideOutVertically { height -> height } + fadeOut()
                    }.using(
                        SizeTransform(clip = false)
                    )
                }) { targetTotalPLPercentage ->
                    Text(
                        text = String.format("%+.2f%%", targetTotalPLPercentage),
                        style = MaterialTheme.typography.bodySmall,
                        color = totalPlColor
                    )
                }
            }
        }
    }
}
