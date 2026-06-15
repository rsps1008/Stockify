package com.rsps1008.stockify.ui.screens

import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.rsps1008.stockify.R
import com.rsps1008.stockify.StockifyApplication
import com.rsps1008.stockify.ui.navigation.Screen
import com.rsps1008.stockify.ui.theme.StockifyAppTheme
import com.rsps1008.stockify.ui.viewmodel.HoldingsViewModel
import com.rsps1008.stockify.ui.viewmodel.ViewModelFactory
import kotlin.math.abs
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.graphics.Color
import com.rsps1008.stockify.data.LimitState
import com.rsps1008.stockify.data.RealtimeStockInfo
import com.rsps1008.stockify.ui.theme.StockGain
import com.rsps1008.stockify.ui.theme.StockLoss
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import android.widget.Toast
import com.rsps1008.stockify.data.HomeDisplayMode
import com.rsps1008.stockify.data.formatHomeAmount
import com.rsps1008.stockify.data.formatMarketAmount
import com.rsps1008.stockify.data.formatShareCount
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState


@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HoldingsScreen(navController: NavController) {
    val application = LocalContext.current.applicationContext as StockifyApplication
    val viewModel: HoldingsViewModel = viewModel(
        factory = ViewModelFactory(
            stockDao = application.database.stockDao(),
            realtimeStockDataService = application.realtimeStockDataService,
            settingsDataStore = application.settingsDataStore,
            exchangeRateService = application.exchangeRateService
        )
    )
    val uiState by viewModel.uiState.collectAsState()
    val homeDisplayMode by viewModel.homeDisplayMode.collectAsState()
    val holdingsOrder by viewModel.holdingsOrder.collectAsState()
    val realizedHoldingsOrder by viewModel.realizedHoldingsOrder.collectAsState()
    val activeHoldings = uiState.holdings.filter { it.shares > 1e-6 }
    var orderedActiveHoldings by remember { mutableStateOf(emptyList<HoldingInfo>()) }
    val unrealizedCount = activeHoldings.size
    val unrealizedPL = activeHoldings.sumOf { it.totalPL }
    val zeroHoldings = uiState.holdings.filter { kotlin.math.abs(it.shares) < 1e-6 }
    var orderedZeroHoldings by remember { mutableStateOf(emptyList<HoldingInfo>()) }
    val clearedCount = zeroHoldings.size
    val realizedPL = zeroHoldings.sumOf { it.totalPL }

    // ★ 從 application 的 realtimeStockDataService 取得即時股價 Map
    val realtimeMap by application.realtimeStockDataService.realtimeStockInfo.collectAsState()
    val lastUpdated = realtimeMap.values.maxOfOrNull { it.lastUpdated }
    val lastUpdatedText = lastUpdated?.let {
        java.text.SimpleDateFormat("MM/dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(it))
    } ?: "--:--"

    LaunchedEffect(activeHoldings, holdingsOrder) {
        orderedActiveHoldings = activeHoldings.sortedByHoldingsOrder(holdingsOrder)
    }

    LaunchedEffect(zeroHoldings, realizedHoldingsOrder) {
        orderedZeroHoldings = zeroHoldings.sortedByHoldingsOrder(realizedHoldingsOrder)
    }

    val lazyListState = rememberLazyListState()
    val activeHoldingsStartIndex = 3
    val reorderableLazyListState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromIndex = from.index - activeHoldingsStartIndex
        val toIndex = to.index - activeHoldingsStartIndex
        if (fromIndex in orderedActiveHoldings.indices && toIndex in orderedActiveHoldings.indices) {
            orderedActiveHoldings = orderedActiveHoldings.toMutableList().apply {
                add(toIndex, removeAt(fromIndex))
            }
            viewModel.setHoldingsOrder(orderedActiveHoldings.map { it.holdingOrderKey() })
            return@rememberReorderableLazyListState
        }

        val zeroHoldingsStartIndex = activeHoldingsStartIndex + orderedActiveHoldings.size + 2
        val zeroFromIndex = from.index - zeroHoldingsStartIndex
        val zeroToIndex = to.index - zeroHoldingsStartIndex
        if (zeroFromIndex in orderedZeroHoldings.indices && zeroToIndex in orderedZeroHoldings.indices) {
            orderedZeroHoldings = orderedZeroHoldings.toMutableList().apply {
                add(zeroToIndex, removeAt(zeroFromIndex))
            }
            viewModel.setRealizedHoldingsOrder(orderedZeroHoldings.map { it.holdingOrderKey() })
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ★★★★★ 這裡是固定最上方的圖片，不會滑動 ★★★★★
        // ★★★★★ 這裡是固定最上方的圖片，不會滑動 ★★★★★
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.stockify),
                contentDescription = "Stockify Logo",
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(0.35f)
            )

            val context = LocalContext.current
            IconButton(
                onClick = { navController.navigate(Screen.DividendInfo.route) },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onLongPress = {
                                Toast.makeText(context, "查詢持股最新配息配股資訊", Toast.LENGTH_SHORT).show()
                            },
                            onTap = {
                                navController.navigate(Screen.DividendInfo.route)
                            }
                        )
                    }
            ) {
                Icon(
                    imageVector = Icons.Default.AttachMoney,
                    contentDescription = "Dividend Info"
                )
            }
        }

        // ★ LazyColumn 會在圖片下方滑動
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            state = lazyListState
        ) {
            item {
                // ★ 改成傳時間字串，不再把 viewModel 丟進去
                SummarySection(
                    uiState = uiState,
                    lastUpdatedText = lastUpdatedText,
                    currentMode = homeDisplayMode,
                    onModeSelected = viewModel::setHomeDisplayMode,
                    onRefreshClick = viewModel::refreshAllHoldingsQuotes
                )
            }

            item {
                HoldingsHeader(
                    count = unrealizedCount,
                    unrealizedPL = unrealizedPL,
                    currentMode = homeDisplayMode
                )
            }

            stickyHeader {
                HoldingsListHeaderSticky()
            }

            items(
                items = orderedActiveHoldings,
                key = { it.holdingOrderKey() }
            ) { holding ->
                ReorderableItem(
                    state = reorderableLazyListState,
                    key = holding.holdingOrderKey()
                ) { isDragging ->
                    HoldingCard(
                        holding = holding,
                        navController = navController,
                        modifier = Modifier
                            .zIndex(if (isDragging) 1f else 0f)
                            .longPressDraggableHandle()
                    )
                }
            }

            if (zeroHoldings.isNotEmpty()) {
                item {
                    ClearedHoldingsHeader(
                        count = clearedCount,
                        realizedPL = realizedPL,
                        currentMode = homeDisplayMode
                    )
                }
                stickyHeader {
                    HoldingsListHeaderStickySells()
                }
                items(
                    items = orderedZeroHoldings,
                    key = { "realized-${it.holdingOrderKey()}" }
                ) { holding ->
                    ReorderableItem(
                        state = reorderableLazyListState,
                        key = "realized-${holding.holdingOrderKey()}"
                    ) { isDragging ->
                        ZeroHoldingCard(
                            holding = holding,
                            navController = navController,
                            modifier = Modifier
                                .zIndex(if (isDragging) 1f else 0f)
                                .longPressDraggableHandle()
                        )
                    }
                }
            }
            item {
                Spacer(modifier = Modifier.height(10.dp))
            }

//            item {
//                Column(modifier = Modifier.padding(vertical = 8.dp)) {
//                    Text(
//                        text = "===== DEBUG SHARES =====",
//                        fontSize = 12.sp,
//                        color = MaterialTheme.colorScheme.error
//                    )
//
//                    uiState.holdings.forEach {
//                        Text(
//                            text = "DEBUG ${it.stock.code} shares=${it.shares}",
//                            fontSize = 10.sp
//                        )
//                    }
//
//                    Text(
//                        text = "DEBUG active=${activeHoldings.size}, zero=${zeroHoldings.size}",
//                        fontSize = 10.sp
//                    )
//                }
//                Spacer(modifier = Modifier.height(1600.dp))
//            }
        }
    }
}

private fun HoldingInfo.holdingOrderKey(): String =
    "${stock.market}:${stock.code}"

private fun List<HoldingInfo>.sortedByHoldingsOrder(order: List<String>): List<HoldingInfo> {
    val orderIndex = order.withIndex().associate { it.value to it.index }
    return sortedWith(
        compareBy<HoldingInfo> { orderIndex[it.holdingOrderKey()] ?: Int.MAX_VALUE }
            .thenBy { it.stock.market }
            .thenBy { it.stock.code }
    )
}

@Composable
fun HoldingsListHeaderSticky() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background) // ★ 必加，避免透明
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text("股票/股數", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text("股價", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        Text("成本均/買均", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        Text("總損益", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

@Composable
fun HoldingsListHeaderStickySells() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background) // ★ 必加，避免透明
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text("股票/股數", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text("股價", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        Text("賣均/買均", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        Text("總損益", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

@Composable
fun SummarySection(
    uiState: HoldingsUiState,
    lastUpdatedText: String,
    currentMode: String,
    onModeSelected: (String) -> Unit,
    onRefreshClick: () -> Unit
) {
    var showMarketValue by remember { mutableStateOf(true) }

    val dailyPlColor =
        if (uiState.dailyPL >= 0) StockifyAppTheme.stockColors.gain else StockifyAppTheme.stockColors.loss

    val cumulativePlColor =
        if (uiState.cumulativePL >= 0) StockifyAppTheme.stockColors.gain else StockifyAppTheme.stockColors.loss

    Card(modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clickable { onRefreshClick() }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    AnimatedContent(
                        targetState = lastUpdatedText,
                        transitionSpec = { fadeIn() togetherWith fadeOut() }
                    ) { time ->
                        Text(
                            text = time,
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Refresh quotes",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.height(10.dp)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .padding(bottom = 4.dp)
            ) {
                Text("累積損益", style = MaterialTheme.typography.bodySmall)

                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = formatHomeAmount(uiState.cumulativePL, currentMode),
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

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(modifier = Modifier.weight(3f)) {
                        Text("持股日損益", style = MaterialTheme.typography.bodySmall)
                        Text(
                            formatHomeAmount(kotlin.math.abs(uiState.dailyPL), currentMode),
                            style = MaterialTheme.typography.bodyLarge,
                            color = dailyPlColor
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(3.5f)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { showMarketValue = !showMarketValue }
                    ) {
                        Text(
                            text = buildAnnotatedString {
                                withStyle(
                                    style = SpanStyle(
                                        fontWeight = if (showMarketValue) FontWeight.Bold else FontWeight.Normal
                                    )
                                ) { append("持股市值") }

                                append("/")

                                withStyle(
                                    style = SpanStyle(
                                        fontWeight = if (!showMarketValue) FontWeight.Bold else FontWeight.Normal
                                    )
                                ) { append("成本") }
                            },
                            style = MaterialTheme.typography.bodySmall
                        )

                        val value = if (showMarketValue) uiState.marketValue else uiState.totalCost
                        Text(
                            formatHomeAmount(value, currentMode),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }

                    Column(modifier = Modifier.weight(2.5f)) {
                        Text("股息收入", style = MaterialTheme.typography.bodySmall)
                        Text(
                            formatHomeAmount(uiState.dividendIncome, currentMode),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        HomeModeCyclePill(
                            currentMode = currentMode,
                            onModeSelected = onModeSelected,
                            modifier = Modifier.offset(y = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HoldingsListHeader() {

    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text = "股票/股數", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(text = "股價    ", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        Text(text = "成本均/買均", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
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
fun AutoResizeNameText(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
    maxTextSize: Float = 14f,
    minTextSize: Float = 11f
) {
    var textSize by remember { mutableStateOf(maxTextSize) }

    Text(
        text = text,
        fontSize = textSize.sp,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        lineHeight = (textSize + 2).sp,
        modifier = modifier,
        onTextLayout = { result ->
            if (result.didOverflowHeight && textSize > minTextSize) {
                textSize -= 0.5f
            }
        }
    )
}

@Composable
fun HoldingCard(
    holding: HoldingInfo,
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val dailyChangeColor = if (holding.dailyChange >= 0) StockifyAppTheme.stockColors.gain else StockifyAppTheme.stockColors.loss
    val totalPlColor = if (holding.totalPL >= 0) StockifyAppTheme.stockColors.gain else StockifyAppTheme.stockColors.loss
    val dailyChangeSymbol = if (holding.dailyChange >= 0) "▴" else "▾"

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.5.dp)
            .clickable { navController.navigate(Screen.StockDetail.createRoute(holding.stock.code)) }
    ) {
        Row(modifier = Modifier.padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 68.dp),
                contentAlignment = Alignment.CenterStart   // ★ 垂直置中，水平靠左
            ) {
                Column {

                    Text(
                        text = holding.stock.code,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    AutoResizeNameText(
                        text = holding.stock.name,
                        maxLines = 2,
                        maxTextSize = 14f,
                        minTextSize = 11f
                    )

                    Text(text = "${formatShareCount(holding.shares)}股", style = MaterialTheme.typography.bodySmall)
                }
            }

            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
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
                        color = when (holding.limitState) {
                            LimitState.LIMIT_UP,
                            LimitState.LIMIT_DOWN -> Color.White   // ★ 漲跌停白字
                            LimitState.NONE -> dailyChangeColor    // ★ 平常維持原本紅綠
                        },
                        textAlign = TextAlign.End,
                        modifier = Modifier
                            .background(
                                color = limitBackgroundColor(holding.limitState),
                                shape = MaterialTheme.shapes.small
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
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
                        color = dailyChangeColor,
                        textAlign = TextAlign.End
                    )
                }
            }

            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
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
                        text = formatMarketAmount(targetTotalPL, holding.stock.market),
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

@Composable
fun ZeroHoldingsSection(
    holdings: List<HoldingInfo>,
    navController: NavController
) {
    Column {
        holdings.forEach { holding ->
            ZeroHoldingCard(holding, navController)
        }
    }
}

@Composable
fun ZeroHoldingCard(
    holding: HoldingInfo,
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val dailyChangeColor = if (holding.dailyChange >= 0) StockifyAppTheme.stockColors.gain else StockifyAppTheme.stockColors.loss
    val totalPlColor = if (holding.totalPL >= 0) StockifyAppTheme.stockColors.gain else StockifyAppTheme.stockColors.loss
    val dailyChangeSymbol = if (holding.dailyChange >= 0) "▴" else "▾"
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.5.dp)
            .clickable { navController.navigate(Screen.StockDetail.createRoute(holding.stock.code)) }
    ) {
        Row(modifier = Modifier.padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 68.dp),
                contentAlignment = Alignment.CenterStart   // ★ 垂直置中，水平靠左
            ) {
                Column {

                    Text(
                        text = holding.stock.code,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    AutoResizeNameText(
                        text = holding.stock.name,
                        maxLines = 2,
                        maxTextSize = 14f,
                        minTextSize = 11f
                    )

                    Text(text = "${formatShareCount(holding.shares)}股", style = MaterialTheme.typography.bodySmall)
                }
            }

            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
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
                        color = when (holding.limitState) {
                            LimitState.LIMIT_UP,
                            LimitState.LIMIT_DOWN -> Color.White   // ★ 漲跌停白字
                            LimitState.NONE -> dailyChangeColor    // ★ 平常維持原本紅綠
                        },
                        textAlign = TextAlign.End,
                        modifier = Modifier
                            .background(
                                color = limitBackgroundColor(holding.limitState),
                                shape = MaterialTheme.shapes.small
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
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
                        color = dailyChangeColor,
                        textAlign = TextAlign.End
                    )
                }
            }

            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(text = String.format("%,.2f", holding.sellAverage), style = MaterialTheme.typography.bodyLarge)
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
                        text = formatMarketAmount(targetTotalPL, holding.stock.market),
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

@Composable
fun HoldingsHeader(
    count: Int,
    unrealizedPL: Double,
    currentMode: String
) {
    val plColor =
        if (unrealizedPL >= 0)
            StockifyAppTheme.stockColors.gain
        else
            StockifyAppTheme.stockColors.loss

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(14.dp)
                .background(
                    MaterialTheme.colorScheme.outline,
                    shape = MaterialTheme.shapes.small
                )
        )

        Spacer(modifier = Modifier.width(8.dp))

        // 標題 + 數量
        Text(
            text = "未實現 ($count)",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.weight(1f))
        // 未實現總計
        Text(
            text = "總計: ",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = formatHomeAmount(kotlin.math.abs(unrealizedPL), currentMode),
            style = MaterialTheme.typography.bodySmall,
            color = plColor
        )
    }
}

@Composable
fun ClearedHoldingsHeader
(
    count: Int,
    realizedPL: Double,
    currentMode: String
) {
    val plColor =
        if (realizedPL >= 0)
            StockifyAppTheme.stockColors.gain
        else
            StockifyAppTheme.stockColors.loss

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左側細條
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(14.dp)
                .background(
                    MaterialTheme.colorScheme.outline,
                    shape = MaterialTheme.shapes.small
                )
        )

        Spacer(modifier = Modifier.width(8.dp))

        // 標題+數量
        Text(
            text = "已實現 ($count)",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.weight(1f))
        // 已實現損益
        Text(
            text = "總計: ",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = formatHomeAmount(kotlin.math.abs(realizedPL), currentMode),
            style = MaterialTheme.typography.bodySmall,
            color = plColor
        )
    }
}

@Composable
fun HomeModeCyclePill(
    currentMode: String,
    onModeSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedMode = HomeDisplayMode.normalize(currentMode)

    androidx.compose.material3.Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        onClick = {
            val nextMode = when (selectedMode) {
                HomeDisplayMode.TW -> HomeDisplayMode.US
                HomeDisplayMode.US -> HomeDisplayMode.COMBINED
                else -> HomeDisplayMode.TW
            }
            onModeSelected(nextMode)
        }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            val twSelected = selectedMode == HomeDisplayMode.TW || selectedMode == HomeDisplayMode.COMBINED
            val usSelected = selectedMode == HomeDisplayMode.US || selectedMode == HomeDisplayMode.COMBINED

            Text(
                text = "TW",
                fontSize = 8.sp,
                lineHeight = 8.sp,
                fontWeight = if (twSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (twSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "US",
                fontSize = 8.sp,
                lineHeight = 8.sp,
                fontWeight = if (usSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (usSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun limitBackgroundColor(limitState: LimitState) =
    when (limitState) {
        LimitState.LIMIT_UP ->
            StockGain.copy(alpha = 0.95f)
        LimitState.LIMIT_DOWN ->
            StockLoss.copy(alpha = 0.95f)
        LimitState.NONE ->
            StockLoss.copy(alpha = 0f)
    }
