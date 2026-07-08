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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.graphics.Color
import com.rsps1008.stockify.BuildConfig
import com.rsps1008.stockify.data.LimitState
import com.rsps1008.stockify.data.RealtimeStockInfo
import com.rsps1008.stockify.ui.theme.StockGain
import com.rsps1008.stockify.ui.theme.StockLoss
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import com.rsps1008.stockify.data.StockMarket
import com.rsps1008.stockify.data.TaiwanWeightedIndexInfo
import com.rsps1008.stockify.data.formatHomeAmount
import com.rsps1008.stockify.data.formatMarketAmount
import com.rsps1008.stockify.data.formatShareCount
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.rsps1008.stockify.data.Account
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HoldingsScreen(navController: NavController) {
    val application = LocalContext.current.applicationContext as StockifyApplication
    val viewModel: HoldingsViewModel = viewModel(
        factory = ViewModelFactory(
            stockDao = application.database.stockDao(),
            realtimeStockDataService = application.realtimeStockDataService,
            settingsDataStore = application.settingsDataStore,
            exchangeRateService = application.exchangeRateService,
            taiwanWeightedIndexService = application.taiwanWeightedIndexService,
            twseStockHistoryService = application.twseStockHistoryService
        )
    )
    val uiState by viewModel.uiState.collectAsState()
    val homeDisplayMode by viewModel.homeDisplayMode.collectAsState()
    val holdingsOrder by viewModel.holdingsOrder.collectAsState()
    val realizedHoldingsOrder by viewModel.realizedHoldingsOrder.collectAsState()
    val holdingsReorderHintShown by viewModel.holdingsReorderHintShown.collectAsState()
    val persistedSortMode by viewModel.homeHoldingsSortMode.collectAsState()
    val persistedSortColumnName by viewModel.homeHoldingsSortColumn.collectAsState()
    val persistedSortAscending by viewModel.homeHoldingsSortAscending.collectAsState()
    val showTaiwanWeightedIndex by viewModel.showTaiwanWeightedIndex.collectAsState()
    val showTaiwanPortfolioChart by viewModel.showTaiwanPortfolioChart.collectAsState()
    val taiwanWeightedIndexInfo by viewModel.taiwanWeightedIndexInfo.collectAsState()
    var showUnrealizedHoldings by rememberSaveable { mutableStateOf(true) }
    var showRealizedHoldings by rememberSaveable { mutableStateOf(true) }
    val usdToTwdRate by application.exchangeRateService.usdToTwdRate.collectAsState()
    val activeAccountId by viewModel.activeAccountId.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    val activeHoldings = uiState.holdings.filter { it.shares > 1e-6 }
    var orderedActiveHoldings by remember { mutableStateOf(emptyList<HoldingInfo>()) }
    val unrealizedCount = activeHoldings.size
    val unrealizedPL = sumDisplayPL(activeHoldings, homeDisplayMode, usdToTwdRate)
    val zeroHoldings = uiState.holdings.filter { kotlin.math.abs(it.shares) < 1e-6 }
    var orderedZeroHoldings by remember { mutableStateOf(emptyList<HoldingInfo>()) }
    val clearedCount = zeroHoldings.size
    val realizedPL = sumDisplayPL(zeroHoldings, homeDisplayMode, usdToTwdRate)

    // ★ 從 application 的 realtimeStockDataService 取得即時股價 Map
    val realtimeMap by application.realtimeStockDataService.realtimeStockInfo.collectAsState()
    val lastUpdated = realtimeMap.values.maxOfOrNull { it.lastUpdated }
    val lastUpdatedText = lastUpdated?.let {
        java.text.SimpleDateFormat("MM/dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(it))
    } ?: "--:--"
    val shouldShowReorderHint = !holdingsReorderHintShown &&
            isVersionBefore(BuildConfig.VERSION_NAME, 1, 4, 0)
    val homeHoldingsSortMode = normalizeHomeHoldingsSortMode(persistedSortMode)
    val isSortingMode = homeHoldingsSortMode == HOME_HOLDINGS_SORT_MODE_COLUMN
    val persistedSortColumn = holdingsSortColumnFromName(persistedSortColumnName)
    val selectedSortColumn = if (isSortingMode && persistedSortColumn != HoldingsSortColumn.NONE) {
        persistedSortColumn
    } else {
        HoldingsSortColumn.NONE
    }
    val isSortAscending = if (isSortingMode && persistedSortColumn != HoldingsSortColumn.NONE) {
        persistedSortAscending
    } else {
        true
    }
    val normalizedUsdToTwdRate = usdToTwdRate.takeIf { it > 0.0 } ?: 1.0
    val displayedActiveHoldings = remember(orderedActiveHoldings, selectedSortColumn, isSortAscending) {
        orderedActiveHoldings.applySort(
            column = selectedSortColumn,
            ascending = isSortAscending,
            usdToTwdRate = normalizedUsdToTwdRate
        )
    }
    val displayedZeroHoldings = remember(orderedZeroHoldings, selectedSortColumn, isSortAscending) {
        orderedZeroHoldings.applySort(
            column = selectedSortColumn,
            ascending = isSortAscending,
            usdToTwdRate = normalizedUsdToTwdRate
        )
    }

    fun toggleSort(column: HoldingsSortColumn) {
        when {
            selectedSortColumn != column -> {
                viewModel.setHomeHoldingsFixedSort(column.name, true)
            }
            isSortAscending -> {
                viewModel.setHomeHoldingsFixedSort(column.name, false)
            }
            else -> {
                viewModel.setHomeHoldingsManualSort()
            }
        }
    }

    LaunchedEffect(activeHoldings, holdingsOrder) {
        orderedActiveHoldings = activeHoldings.sortedByHoldingsOrder(holdingsOrder)
    }

    LaunchedEffect(zeroHoldings, realizedHoldingsOrder) {
        orderedZeroHoldings = zeroHoldings.sortedByHoldingsOrder(realizedHoldingsOrder)
    }

    val lazyListState = rememberLazyListState()
    val reorderableLazyListState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val activeIndexByKey = orderedActiveHoldings
            .mapIndexed { index, holding -> holding.holdingOrderKey() to index }
            .toMap()
        val realizedIndexByKey = orderedZeroHoldings
            .mapIndexed { index, holding -> holding.realizedHoldingReorderKey() to index }
            .toMap()

        val fromKey = from.key as? String
        val toKey = to.key as? String

        val fromIndex = fromKey?.let(activeIndexByKey::get)
        val toIndex = toKey?.let(activeIndexByKey::get)
        if (fromIndex in orderedActiveHoldings.indices && toIndex in orderedActiveHoldings.indices) {
            orderedActiveHoldings = orderedActiveHoldings.toMutableList().apply {
                add(toIndex!!, removeAt(fromIndex!!))
            }
            viewModel.setHoldingsOrder(orderedActiveHoldings.map { it.holdingOrderKey() })
            return@rememberReorderableLazyListState
        }

        val zeroFromIndex = fromKey?.let(realizedIndexByKey::get)
        val zeroToIndex = toKey?.let(realizedIndexByKey::get)
        if (zeroFromIndex in orderedZeroHoldings.indices && zeroToIndex in orderedZeroHoldings.indices) {
            orderedZeroHoldings = orderedZeroHoldings.toMutableList().apply {
                add(zeroToIndex!!, removeAt(zeroFromIndex!!))
            }
            viewModel.setRealizedHoldingsOrder(orderedZeroHoldings.map { it.holdingOrderKey() })
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            AccountSwitcherBadge(
                activeAccountId = activeAccountId,
                accounts = accounts,
                onAccountSelected = viewModel::selectAccount,
                onAddAccount = { name ->
                    application.database.stockDao().let { dao ->
                        CoroutineScope(Dispatchers.IO).launch {
                            dao.insertAccount(Account(name = name))
                        }
                    }
                },
                onRenameAccount = { account, name ->
                    application.database.stockDao().let { dao ->
                        CoroutineScope(Dispatchers.IO).launch {
                            dao.updateAccount(account.copy(name = name))
                        }
                    }
                },
                onDeleteAccount = { account ->
                    application.database.stockDao().let { dao ->
                        CoroutineScope(Dispatchers.IO).launch {
                            dao.deleteTransactionsByAccountId(account.id)
                            dao.deleteAccount(account)
                            if (activeAccountId == account.id) {
                                viewModel.selectAccount(0)
                            }
                        }
                    }
                },
                modifier = Modifier.align(Alignment.CenterStart)
            )

            Image(
                painter = painterResource(id = R.drawable.stockify),
                contentDescription = "Stockify Logo",
                modifier = Modifier.fillMaxWidth(0.35f)
            )

            val context = LocalContext.current
            IconButton(
                onClick = { navController.navigate(Screen.DividendInfo.route) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
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
                    painter = painterResource(id = R.drawable.ic_dividend_info),
                    contentDescription = "Dividend Info"
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

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

            if (showTaiwanWeightedIndex) {
                item {
                    TaiwanWeightedIndexSection(
                        indexInfo = taiwanWeightedIndexInfo
                    )
                }
            }

            if (showTaiwanPortfolioChart) {
                item {
                    HistoryChartSection(
                        viewModel = viewModel
                    )
                }
            }

            item {
                HoldingsHeader(
                    count = unrealizedCount,
                    unrealizedPL = unrealizedPL,
                    currentMode = homeDisplayMode,
                    expanded = showUnrealizedHoldings,
                    onToggleExpanded = { showUnrealizedHoldings = !showUnrealizedHoldings }
                )
            }

            if (showUnrealizedHoldings) {
                stickyHeader {
                    HoldingsListHeaderSticky(
                        selectedSortColumn = selectedSortColumn,
                        isSortAscending = isSortAscending,
                        onSortClick = ::toggleSort
                    )
                }

                items(
                    items = displayedActiveHoldings,
                    key = { it.holdingOrderKey() }
                ) { holding ->
                    ReorderableItem(
                        state = reorderableLazyListState,
                        key = holding.holdingOrderKey()
                    ) { isDragging ->
                        HoldingCard(
                            holding = holding,
                            navController = navController,
                            longPressDisabledMessage = if (isSortingMode) {
                                "無法在排序模式下自定義排序"
                            } else {
                                null
                            },
                            modifier = Modifier.zIndex(if (isDragging) 1f else 0f).let { base ->
                                if (isSortingMode) base else base.longPressDraggableHandle()
                            }
                        )
                    }
                }
            }

            if (zeroHoldings.isNotEmpty()) {
                item {
                    ClearedHoldingsHeader(
                        count = clearedCount,
                        realizedPL = realizedPL,
                        currentMode = homeDisplayMode,
                        expanded = showRealizedHoldings,
                        onToggleExpanded = { showRealizedHoldings = !showRealizedHoldings }
                    )
                }
                if (showRealizedHoldings) {
                    stickyHeader {
                        HoldingsListHeaderStickySells(
                            selectedSortColumn = selectedSortColumn,
                            isSortAscending = isSortAscending,
                            onSortClick = ::toggleSort
                        )
                    }
                    items(
                        items = displayedZeroHoldings,
                        key = { "realized-${it.holdingOrderKey()}" }
                    ) { holding ->
                        ReorderableItem(
                            state = reorderableLazyListState,
                            key = "realized-${holding.holdingOrderKey()}"
                        ) { isDragging ->
                            ZeroHoldingCard(
                                holding = holding,
                                navController = navController,
                                longPressDisabledMessage = if (isSortingMode) {
                                    "不能在這種排序模式下長按排序"
                                } else {
                                    null
                                },
                                modifier = Modifier.zIndex(if (isDragging) 1f else 0f).let { base ->
                                    if (isSortingMode) base else base.longPressDraggableHandle()
                                }
                            )
                        }
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

    if (shouldShowReorderHint) {
        HoldingsReorderHintDialog(
            onDismiss = viewModel::markHoldingsReorderHintShown
        )
    }
}

private fun HoldingInfo.holdingOrderKey(): String =
    "${stock.market}:${stock.code}"

private fun HoldingInfo.realizedHoldingReorderKey(): String =
    "realized-${holdingOrderKey()}"

private fun sumDisplayPL(
    holdings: List<HoldingInfo>,
    homeDisplayMode: String,
    usdToTwdRate: Double
): Double {
    val mode = HomeDisplayMode.normalize(homeDisplayMode)
    return holdings.sumOf { holding ->
        val rate = if (mode == HomeDisplayMode.COMBINED && StockMarket.isUs(holding.stock.market)) {
            usdToTwdRate
        } else {
            1.0
        }
        holding.totalPL * rate
    }
}

private fun isVersionBefore(versionName: String, major: Int, minor: Int, patch: Int): Boolean {
    val parts = versionName
        .substringBefore("-")
        .split(".")
        .map { it.toIntOrNull() ?: 0 }
    val currentMajor = parts.getOrElse(0) { 0 }
    val currentMinor = parts.getOrElse(1) { 0 }
    val currentPatch = parts.getOrElse(2) { 0 }
    return when {
        currentMajor != major -> currentMajor < major
        currentMinor != minor -> currentMinor < minor
        else -> currentPatch < patch
    }
}

@Composable
private fun HoldingsReorderHintDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("🎉 新功能上線")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "長按",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Column {
                            Text("任一持股卡片", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "拖曳到想要的位置後放開即可完成排序",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Text(
                    text = "現在可以自由調整持股卡片順序！完成排序後，建議到「資料管理」備份排序設定，避免更換裝置或重新安裝後遺失。",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("知道了")
            }
        }
    )
}

private fun List<HoldingInfo>.sortedByHoldingsOrder(order: List<String>): List<HoldingInfo> {
    val orderIndex = order.withIndex().associate { it.value to it.index }
    return sortedWith(
        compareBy<HoldingInfo> { orderIndex[it.holdingOrderKey()] ?: Int.MAX_VALUE }
            .thenBy { it.stock.market }
            .thenBy { it.stock.code }
    )
}

private enum class HoldingsSortColumn {
    NONE,
    STOCK,
    SHARES,
    PRICE,
    AVERAGE_COST,
    SELL_AVERAGE,
    BUY_AVERAGE,
    TOTAL_PL,
    TOTAL_PL_PERCENTAGE
}

private fun HoldingsSortColumn.displayLabel(): String = when (this) {
    HoldingsSortColumn.NONE -> "手動拖曳"
    HoldingsSortColumn.STOCK -> "股票"
    HoldingsSortColumn.SHARES -> "股數"
    HoldingsSortColumn.PRICE -> "股價"
    HoldingsSortColumn.AVERAGE_COST -> "成本均"
    HoldingsSortColumn.SELL_AVERAGE -> "賣均"
    HoldingsSortColumn.BUY_AVERAGE -> "買均"
    HoldingsSortColumn.TOTAL_PL -> "總損益"
    HoldingsSortColumn.TOTAL_PL_PERCENTAGE -> "%"
}

private fun holdingsSortColumnFromName(name: String?): HoldingsSortColumn =
    HoldingsSortColumn.entries.firstOrNull { it.name == name } ?: HoldingsSortColumn.NONE

private const val HOME_HOLDINGS_SORT_MODE_MANUAL = "MANUAL"
private const val HOME_HOLDINGS_SORT_MODE_COLUMN = "COLUMN"
private var hasShownSortingModeLongPressHintThisProcess = false

private fun normalizeHomeHoldingsSortMode(mode: String?): String =
    when (mode) {
        HOME_HOLDINGS_SORT_MODE_COLUMN -> HOME_HOLDINGS_SORT_MODE_COLUMN
        else -> HOME_HOLDINGS_SORT_MODE_MANUAL
    }

private fun List<HoldingInfo>.applySort(
    column: HoldingsSortColumn,
    ascending: Boolean,
    usdToTwdRate: Double
): List<HoldingInfo> {
    if (column == HoldingsSortColumn.NONE) return this

    val comparator = when (column) {
        HoldingsSortColumn.NONE -> compareBy<HoldingInfo> { 0 }
        HoldingsSortColumn.STOCK -> compareBy<HoldingInfo>(
            { it.stock.code.uppercase() },
            { it.stock.market },
            { it.stock.name.uppercase() }
        )
        HoldingsSortColumn.SHARES -> compareBy<HoldingInfo>(
            { it.shares },
            { it.stock.market },
            { it.stock.code }
        )
        HoldingsSortColumn.PRICE -> compareBy<HoldingInfo>(
            { it.currentPrice.toSortableAmount(it.stock.market, usdToTwdRate) },
            { it.stock.market },
            { it.stock.code }
        )
        HoldingsSortColumn.AVERAGE_COST -> compareBy<HoldingInfo>(
            { it.averageCost.toSortableAmount(it.stock.market, usdToTwdRate) },
            { it.buyAverage.toSortableAmount(it.stock.market, usdToTwdRate) },
            { it.stock.market },
            { it.stock.code }
        )
        HoldingsSortColumn.SELL_AVERAGE -> compareBy<HoldingInfo>(
            { it.sellAverage.toSortableAmount(it.stock.market, usdToTwdRate) },
            { it.buyAverage.toSortableAmount(it.stock.market, usdToTwdRate) },
            { it.stock.market },
            { it.stock.code }
        )
        HoldingsSortColumn.BUY_AVERAGE -> compareBy<HoldingInfo>(
            { it.buyAverage.toSortableAmount(it.stock.market, usdToTwdRate) },
            { it.stock.market },
            { it.stock.code }
        )
        HoldingsSortColumn.TOTAL_PL -> compareBy<HoldingInfo>(
            { it.totalPL.toSortableAmount(it.stock.market, usdToTwdRate) },
            { it.stock.market },
            { it.stock.code }
        )
        HoldingsSortColumn.TOTAL_PL_PERCENTAGE -> compareBy<HoldingInfo>(
            { it.totalPLPercentage },
            { it.stock.market },
            { it.stock.code }
        )
    }

    return if (ascending) sortedWith(comparator) else sortedWith(comparator.reversed())
}

private fun Double.toSortableAmount(
    market: String,
    usdToTwdRate: Double
): Double = if (market == StockMarket.US) this * usdToTwdRate else this

@Composable
private fun HoldingsListHeaderSticky(
    selectedSortColumn: HoldingsSortColumn,
    isSortAscending: Boolean,
    onSortClick: (HoldingsSortColumn) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background) // ★ 必加，避免透明
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        DualSortableHeaderText(
            primaryText = "股票",
            primaryColumn = HoldingsSortColumn.STOCK,
            secondaryText = "股數",
            secondaryColumn = HoldingsSortColumn.SHARES,
            selectedSortColumn = selectedSortColumn,
            isSortAscending = isSortAscending,
            modifier = Modifier.weight(1f),
            onSortClick = onSortClick
        )
        SingleSortableHeaderText(
            text = "股價",
            column = HoldingsSortColumn.PRICE,
            selectedSortColumn = selectedSortColumn,
            isSortAscending = isSortAscending,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            onSortClick = onSortClick
        )
        DualSortableHeaderText(
            primaryText = "成本均",
            primaryColumn = HoldingsSortColumn.AVERAGE_COST,
            secondaryText = "買均",
            secondaryColumn = HoldingsSortColumn.BUY_AVERAGE,
            selectedSortColumn = selectedSortColumn,
            isSortAscending = isSortAscending,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            onSortClick = onSortClick
        )
        DualSortableHeaderText(
            primaryText = "總損益",
            primaryColumn = HoldingsSortColumn.TOTAL_PL,
            secondaryText = "%",
            secondaryColumn = HoldingsSortColumn.TOTAL_PL_PERCENTAGE,
            selectedSortColumn = selectedSortColumn,
            isSortAscending = isSortAscending,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            onSortClick = onSortClick
        )
    }
}

@Composable
private fun HoldingsListHeaderStickySells(
    selectedSortColumn: HoldingsSortColumn,
    isSortAscending: Boolean,
    onSortClick: (HoldingsSortColumn) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background) // ★ 必加，避免透明
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        DualSortableHeaderText(
            primaryText = "股票",
            primaryColumn = HoldingsSortColumn.STOCK,
            secondaryText = "股數",
            secondaryColumn = HoldingsSortColumn.SHARES,
            selectedSortColumn = selectedSortColumn,
            isSortAscending = isSortAscending,
            modifier = Modifier.weight(1f),
            onSortClick = onSortClick
        )
        SingleSortableHeaderText(
            text = "股價",
            column = HoldingsSortColumn.PRICE,
            selectedSortColumn = selectedSortColumn,
            isSortAscending = isSortAscending,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            onSortClick = onSortClick
        )
        DualSortableHeaderText(
            primaryText = "賣均",
            primaryColumn = HoldingsSortColumn.SELL_AVERAGE,
            secondaryText = "買均",
            secondaryColumn = HoldingsSortColumn.BUY_AVERAGE,
            selectedSortColumn = selectedSortColumn,
            isSortAscending = isSortAscending,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            onSortClick = onSortClick
        )
        DualSortableHeaderText(
            primaryText = "總損益",
            primaryColumn = HoldingsSortColumn.TOTAL_PL,
            secondaryText = "%",
            secondaryColumn = HoldingsSortColumn.TOTAL_PL_PERCENTAGE,
            selectedSortColumn = selectedSortColumn,
            isSortAscending = isSortAscending,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            onSortClick = onSortClick
        )
    }
}

@Composable
private fun SingleSortableHeaderText(
    text: String,
    column: HoldingsSortColumn,
    selectedSortColumn: HoldingsSortColumn,
    isSortAscending: Boolean,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start,
    onSortClick: (HoldingsSortColumn) -> Unit
) {
    Row(
        modifier = modifier,
        horizontalArrangement = if (textAlign == TextAlign.End) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        SortableHeaderLabel(
            text = text,
            column = column,
            selectedSortColumn = selectedSortColumn,
            isSortAscending = isSortAscending,
            textAlign = textAlign,
            onSortClick = onSortClick
        )
    }
}

@Composable
private fun DualSortableHeaderText(
    primaryText: String,
    primaryColumn: HoldingsSortColumn,
    secondaryText: String,
    secondaryColumn: HoldingsSortColumn,
    selectedSortColumn: HoldingsSortColumn,
    isSortAscending: Boolean,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start,
    onSortClick: (HoldingsSortColumn) -> Unit
) {
    Row(
        modifier = modifier,
        horizontalArrangement = if (textAlign == TextAlign.End) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        SortableHeaderLabel(
            text = primaryText,
            column = primaryColumn,
            selectedSortColumn = selectedSortColumn,
            isSortAscending = isSortAscending,
            textAlign = textAlign,
            onSortClick = onSortClick
        )
        Text("/", style = MaterialTheme.typography.bodySmall)
        SortableHeaderLabel(
            text = secondaryText,
            column = secondaryColumn,
            selectedSortColumn = selectedSortColumn,
            isSortAscending = isSortAscending,
            textAlign = textAlign,
            onSortClick = onSortClick
        )
    }
}

@Composable
private fun SortableHeaderLabel(
    text: String,
    column: HoldingsSortColumn,
    selectedSortColumn: HoldingsSortColumn,
    isSortAscending: Boolean,
    textAlign: TextAlign,
    onSortClick: (HoldingsSortColumn) -> Unit
) {
    val isSelected = selectedSortColumn == column

    Row(
        modifier = Modifier.clickable { onSortClick(column) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            textAlign = textAlign
        )

        if (isSelected) {
            Icon(
                imageVector = if (isSortAscending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                contentDescription = if (isSortAscending) "Ascending sort" else "Descending sort",
                modifier = Modifier
                    .padding(start = 1.dp)
                    .height(12.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
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
private fun TaiwanWeightedIndexSection(
    indexInfo: TaiwanWeightedIndexInfo?
) {
    val valueColor = when {
        indexInfo == null -> MaterialTheme.colorScheme.onSurfaceVariant
        indexInfo.change >= 0 -> StockifyAppTheme.stockColors.gain
        else -> StockifyAppTheme.stockColors.loss
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, start = 4.dp, end = 4.dp, bottom = 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "台灣加權",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (indexInfo == null) {
                "--"
            } else {
                val arrow = when {
                    indexInfo.change > 0 -> "▴"
                    indexInfo.change < 0 -> "▾"
                    else -> ""
                }
                if (arrow.isEmpty()) {
                    "${String.format("%,.2f", indexInfo.current)}  0.00  (0.00%)"
                } else {
                    val absChange = kotlin.math.abs(indexInfo.change)
                    val absPercent = kotlin.math.abs(indexInfo.changePercent)
                    "${String.format("%,.2f", indexInfo.current)}  $arrow${String.format("%.2f", absChange)}  (${String.format("%.2f%%", absPercent)})"
                }
            },
            style = MaterialTheme.typography.labelMedium,
            color = valueColor,
            fontWeight = FontWeight.Medium
        )
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
    longPressDisabledMessage: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val dailyChangeColor = if (holding.dailyChange >= 0) StockifyAppTheme.stockColors.gain else StockifyAppTheme.stockColors.loss
    val totalPlColor = if (holding.totalPL >= 0) StockifyAppTheme.stockColors.gain else StockifyAppTheme.stockColors.loss
    val dailyChangeSymbol = if (holding.dailyChange >= 0) "▴" else "▾"

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.5.dp)
            .then(
                if (longPressDisabledMessage != null) {
                    Modifier.pointerInput(longPressDisabledMessage, holding.stock.code) {
                        detectTapGestures(
                            onLongPress = {
                                if (!hasShownSortingModeLongPressHintThisProcess) {
                                    hasShownSortingModeLongPressHintThisProcess = true
                                    Toast.makeText(context, longPressDisabledMessage, Toast.LENGTH_SHORT).show()
                                }
                            },
                            onTap = {
                                navController.navigate(Screen.StockDetail.createRoute(holding.stock.code))
                            }
                        )
                    }
                } else {
                    Modifier.clickable { navController.navigate(Screen.StockDetail.createRoute(holding.stock.code)) }
                }
            )
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
    longPressDisabledMessage: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val dailyChangeColor = if (holding.dailyChange >= 0) StockifyAppTheme.stockColors.gain else StockifyAppTheme.stockColors.loss
    val totalPlColor = if (holding.totalPL >= 0) StockifyAppTheme.stockColors.gain else StockifyAppTheme.stockColors.loss
    val dailyChangeSymbol = if (holding.dailyChange >= 0) "▴" else "▾"
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.5.dp)
            .then(
                if (longPressDisabledMessage != null) {
                    Modifier.pointerInput(longPressDisabledMessage, holding.stock.code) {
                        detectTapGestures(
                            onLongPress = {
                                if (!hasShownSortingModeLongPressHintThisProcess) {
                                    hasShownSortingModeLongPressHintThisProcess = true
                                    Toast.makeText(context, longPressDisabledMessage, Toast.LENGTH_SHORT).show()
                                }
                            },
                            onTap = {
                                navController.navigate(Screen.StockDetail.createRoute(holding.stock.code))
                            }
                        )
                    }
                } else {
                    Modifier.clickable { navController.navigate(Screen.StockDetail.createRoute(holding.stock.code)) }
                }
            )
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
    currentMode: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit
) {
    val plColor =
        if (unrealizedPL >= 0)
            StockifyAppTheme.stockColors.gain
        else
            StockifyAppTheme.stockColors.loss

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggleExpanded
            )
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
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (expanded) "收起未實現區塊" else "展開未實現區塊",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ClearedHoldingsHeader
(
    count: Int,
    realizedPL: Double,
    currentMode: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit
) {
    val plColor =
        if (realizedPL >= 0)
            StockifyAppTheme.stockColors.gain
        else
            StockifyAppTheme.stockColors.loss

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggleExpanded
            )
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
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (expanded) "收起已實現區塊" else "展開已實現區塊",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
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
                fontSize = 10.sp,
                lineHeight = 10.sp,
                fontWeight = if (twSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (twSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "US",
                fontSize = 10.sp,
                lineHeight = 10.sp,
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
