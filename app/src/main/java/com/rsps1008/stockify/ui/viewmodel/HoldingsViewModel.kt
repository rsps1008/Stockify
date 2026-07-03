package com.rsps1008.stockify.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rsps1008.stockify.data.RealtimeStockDataService
import com.rsps1008.stockify.data.SettingsDataStore
import com.rsps1008.stockify.data.StockRepository
import com.rsps1008.stockify.data.TaiwanWeightedIndexService
import com.rsps1008.stockify.data.StockDao
import com.rsps1008.stockify.data.TwseStockHistoryService
import com.rsps1008.stockify.data.ReturnRateMode
import com.rsps1008.stockify.data.StockTransaction
import com.rsps1008.stockify.data.StockHistoryPoint
import com.rsps1008.stockify.ui.screens.HoldingsUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

sealed interface HomeHistoryStateInternal {
    object Idle : HomeHistoryStateInternal
    data class Loading(val progress: Float, val statusText: String) : HomeHistoryStateInternal
    data class Success(
        val range: HistoryRange, 
        val rawPoints: List<StockHistoryPoint>,
        val allRawPoints: Map<String, List<StockHistoryPoint>>
    ) : HomeHistoryStateInternal
    data class Error(val message: String) : HomeHistoryStateInternal
}

private data class HomeSettingsBundle(
    val preDeductSellFees: Boolean,
    val feeDiscount: Double,
    val minFeeRegular: Int,
    val returnRateMode: ReturnRateMode
)

private data class HistoricalHoldingStats(
    val shares: Double,
    val costBasis: Double,
    val totalInvestment: Double,
    val marketValue: Double,
    val totalPL: Double
)

class HoldingsViewModel(
    private val settingsDataStore: SettingsDataStore,
    private val realtimeStockDataService: RealtimeStockDataService,
    private val taiwanWeightedIndexService: TaiwanWeightedIndexService,
    private val stockDao: StockDao,
    private val twseStockHistoryService: TwseStockHistoryService,
    stockRepository: StockRepository
) : ViewModel() {

    val uiState: StateFlow<HoldingsUiState> = stockRepository.getHoldings()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = HoldingsUiState()
        )

    val homeDisplayMode: StateFlow<String> = settingsDataStore.homeDisplayModeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = com.rsps1008.stockify.data.HomeDisplayMode.COMBINED
        )

    val holdingsOrder: StateFlow<List<String>> = settingsDataStore.holdingsOrderFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    val realizedHoldingsOrder: StateFlow<List<String>> = settingsDataStore.realizedHoldingsOrderFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    val holdingsReorderHintShown: StateFlow<Boolean> = settingsDataStore.holdingsReorderHintShownFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = false
        )

    val homeHoldingsSortMode: StateFlow<String> = settingsDataStore.homeHoldingsSortModeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = "MANUAL"
        )

    val homeHoldingsSortColumn: StateFlow<String> = settingsDataStore.homeHoldingsSortColumnFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = "NONE"
        )

    val homeHoldingsSortAscending: StateFlow<Boolean> = settingsDataStore.homeHoldingsSortAscendingFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = true
        )

    val showTaiwanWeightedIndex: StateFlow<Boolean> = settingsDataStore.showTaiwanWeightedIndexFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = true
        )

    val showTaiwanPortfolioChart: StateFlow<Boolean> = settingsDataStore.showTaiwanPortfolioChartFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = true
        )

    val taiwanWeightedIndexInfo: StateFlow<com.rsps1008.stockify.data.TaiwanWeightedIndexInfo?> =
        taiwanWeightedIndexService.indexInfo

    // --- Home Portfolio History States ---
    private val _historyStateInternal = MutableStateFlow<HomeHistoryStateInternal>(HomeHistoryStateInternal.Idle)

    private val settingsCombined = combine(
        settingsDataStore.preDeductSellFeesFlow,
        settingsDataStore.feeDiscountFlow,
        settingsDataStore.minFeeRegularFlow,
        settingsDataStore.returnRateModeFlow
    ) { preDeduct, discount, minFee, mode ->
        HomeSettingsBundle(preDeduct, discount, minFee, mode)
    }

    val historyState: StateFlow<HistoryState> = combine(
        _historyStateInternal,
        stockDao.getAllTransactions(),
        settingsCombined,
        uiState
    ) { historyInternal, allTxs, settings, holdingsState ->
        if (historyInternal is HomeHistoryStateInternal.Success) {
            val minFee = settings.minFeeRegular.toDouble()
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).apply {
                timeZone = java.util.TimeZone.getTimeZone("Asia/Taipei")
            }

            val personalPoints = mutableListOf<PersonalHistoryPoint>()

            val twTxs = allTxs.filter { tx ->
                historyInternal.allRawPoints.containsKey(tx.stockCode)
            }

            val adjustedTxsByStock = twTxs.groupBy { it.stockCode }.mapValues { (_, txList) ->
                adjustTransactionsForSplits(txList)
            }

            val firstTxTime = twTxs.minOfOrNull { it.date }

            for (pt in historyInternal.rawPoints) {
                val dayStart = sdf.parse(pt.date)?.time ?: 0L
                val dayEnd = dayStart + 24 * 60 * 60 * 1000L - 1L

                if (firstTxTime != null && dayEnd < firstTxTime) {
                    continue
                }

                var totalMarketValue = 0.0
                var totalCostBasis = 0.0
                var totalInvestment = 0.0
                var totalPL = 0.0
                var totalShares = 0.0

                for ((stockCode, rawList) in historyInternal.allRawPoints) {
                    val dailyPrice = rawList.firstOrNull { it.date == pt.date }?.price
                        ?: rawList.filter { it.date <= pt.date }.lastOrNull()?.price
                        ?: 0.0

                    val stockTxs = adjustedTxsByStock[stockCode] ?: emptyList()
                    val stockType = holdingsState.holdings.firstOrNull { it.stock.code == stockCode }?.stock?.stockType ?: ""

                    val stats = calculateHistoricalHoldingStatsAt(
                        stockCode = stockCode,
                        ptPrice = dailyPrice,
                        adjustedTransactions = stockTxs,
                        preDeductSellFees = settings.preDeductSellFees,
                        feeDiscount = settings.feeDiscount,
                        minFeeRegular = minFee,
                        stockType = stockType,
                        dayEnd = dayEnd
                    )
                    totalMarketValue += stats.marketValue
                    totalCostBasis += stats.costBasis
                    totalInvestment += stats.totalInvestment
                    totalPL += stats.totalPL
                    totalShares += stats.shares
                }

                val totalPLPercentage = when (settings.returnRateMode) {
                    ReturnRateMode.REMAINING_POSITION -> if (totalCostBasis > 0) (totalPL / totalCostBasis) * 100 else 0.0
                    ReturnRateMode.CUMULATIVE_INVESTMENT -> if (totalInvestment > 0) (totalPL / totalInvestment) * 100 else 0.0
                    ReturnRateMode.XIRR -> if (totalCostBasis > 0) (totalPL / totalCostBasis) * 100 else 0.0
                }

                personalPoints.add(
                    PersonalHistoryPoint(
                        date = pt.date,
                        price = 0.0,
                        shares = totalShares,
                        marketValue = totalMarketValue,
                        totalPL = totalPL,
                        totalPLPercentage = totalPLPercentage
                    )
                )
            }

            if (personalPoints.isEmpty()) {
                HistoryState.Success(
                    historyInternal.range,
                    historyInternal.rawPoints.map {
                        PersonalHistoryPoint(it.date, it.price, 0.0, 0.0, 0.0, 0.0)
                    }
                )
            } else {
                HistoryState.Success(historyInternal.range, personalPoints)
            }
        } else {
            when (historyInternal) {
                is HomeHistoryStateInternal.Idle -> HistoryState.Idle
                is HomeHistoryStateInternal.Loading -> HistoryState.Loading(historyInternal.progress, historyInternal.statusText)
                is HomeHistoryStateInternal.Error -> HistoryState.Error(historyInternal.message)
                else -> HistoryState.Idle
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), HistoryState.Idle)

    init {
        viewModelScope.launch {
            var lastTwStockCodes = emptySet<String>()
            uiState.collect { state ->
                val twStocks = state.holdings.filter { com.rsps1008.stockify.data.StockMarket.isTw(it.stock.market) }
                val codes = twStocks.map { it.stock.code }.toSet()
                if (codes.isNotEmpty() && codes != lastTwStockCodes) {
                    lastTwStockCodes = codes
                    fetchPortfolioHistory(HistoryRange.ONE_MONTH)
                }
            }
        }
    }

    fun fetchPortfolioHistory(range: HistoryRange) {
        val rangeMonths = when (range) {
            HistoryRange.ONE_MONTH -> 1
            HistoryRange.SIX_MONTHS -> 6
            HistoryRange.ONE_YEAR -> 12
        }

        viewModelScope.launch {
            val twStocks = uiState.value.holdings.filter { com.rsps1008.stockify.data.StockMarket.isTw(it.stock.market) }.map { it.stock }
            if (twStocks.isEmpty()) {
                _historyStateInternal.value = HomeHistoryStateInternal.Error("目前沒有持有任何台股部位。")
                return@launch
            }

            _historyStateInternal.value = HomeHistoryStateInternal.Loading(0f, "準備從證交所下載數據...")
            try {
                val allRawPoints = mutableMapOf<String, List<StockHistoryPoint>>()
                val totalStocks = twStocks.size

                for ((index, stock) in twStocks.withIndex()) {
                    val rawPoints = twseStockHistoryService.fetchHistory(stock.code, rangeMonths) { step, total ->
                        val baseProgress = index.toFloat() / totalStocks
                        val stepProgress = (step.toFloat() / total) / totalStocks
                        _historyStateInternal.value = HomeHistoryStateInternal.Loading(
                            baseProgress + stepProgress,
                            "正在載入 ${stock.name} (${index + 1}/$totalStocks) 第 $step/$total 個月..."
                        )
                    }
                    allRawPoints[stock.code] = rawPoints
                }

                if (allRawPoints.values.all { it.isEmpty() }) {
                    _historyStateInternal.value = HomeHistoryStateInternal.Error("無歷史股價數據，請稍後重試。")
                    return@launch
                }

                val allDates = allRawPoints.values.flatMap { it.map { pt -> pt.date } }.distinct().sorted()
                val alignedRawPoints = allDates.map { date ->
                    StockHistoryPoint(date, 1.0)
                }

                _historyStateInternal.value = HomeHistoryStateInternal.Success(range, alignedRawPoints, allRawPoints)
            } catch (e: Exception) {
                _historyStateInternal.value = HomeHistoryStateInternal.Error("載入失敗: ${e.localizedMessage}")
            }
        }
    }

    private fun adjustTransactionsForSplits(txs: List<StockTransaction>): List<StockTransaction> {
        val chronologicallySorted = txs.sortedBy { it.date }
        val adjustedTransactions = mutableListOf<StockTransaction>()
        var splitMultiplier = 1.0

        for (tx in chronologicallySorted.reversed()) {
            if (tx.type == "分割") {
                if (tx.stockSplitRatio > 0) {
                    splitMultiplier *= tx.stockSplitRatio
                }
                continue
            }

            if (splitMultiplier != 1.0) {
                adjustedTransactions.add(tx.copy(
                    buyShares = tx.buyShares * splitMultiplier,
                    buyPrice = tx.buyPrice / splitMultiplier,
                    sellShares = tx.sellShares * splitMultiplier,
                    sellPrice = tx.sellPrice / splitMultiplier,
                    dividendShares = tx.dividendShares * splitMultiplier,
                    exDividendShares = tx.exDividendShares * splitMultiplier,
                    exRightsShares = tx.exRightsShares * splitMultiplier,
                    sharesBeforeReduction = tx.sharesBeforeReduction * splitMultiplier,
                    sharesAfterReduction = tx.sharesAfterReduction * splitMultiplier
                ))
            } else {
                adjustedTransactions.add(tx)
            }
        }

        return adjustedTransactions.reversed()
    }

    private fun calculateHistoricalHoldingStatsAt(
        stockCode: String,
        ptPrice: Double,
        adjustedTransactions: List<StockTransaction>,
        preDeductSellFees: Boolean,
        feeDiscount: Double,
        minFeeRegular: Double,
        stockType: String,
        dayEnd: Long
    ): HistoricalHoldingStats {
        val txs = adjustedTransactions.filter { it.date <= dayEnd }

        var shares = 0.0
        var totalBuyExpense = 0.0
        var totalSellIncome = 0.0
        var totalSellNetIncome = 0.0
        var sellSharesTotal = 0.0
        var sellAmountBeforeFee = 0.0
        var totalDividendIncome = 0.0
        var buySharesTotal = 0.0
        var buyCostTotal = 0.0

        for (it in txs) {
            when (it.type) {
                "買進" -> {
                    shares += it.buyShares
                    totalBuyExpense += it.expense
                    buySharesTotal += it.buyShares
                    buyCostTotal += it.expense
                }
                "賣出" -> {
                    shares -= it.sellShares
                    sellSharesTotal += it.sellShares
                    sellAmountBeforeFee += it.sellPrice * it.sellShares
                    totalSellIncome += it.income
                    totalSellNetIncome += it.income
                }
                "配股" -> {
                    shares += it.dividendShares
                }
                "配息" -> {
                    totalDividendIncome += it.income
                }
                "減資" -> {
                    shares += it.sharesAfterReduction - it.sharesBeforeReduction
                    totalSellIncome += it.cashReturned
                }
            }
        }

        if (shares < 0) shares = 0.0
        val costBasis = totalBuyExpense - totalSellIncome - totalDividendIncome
        val totalSellFeeAndTax = (sellAmountBeforeFee - totalSellNetIncome).coerceAtLeast(0.0)
        val totalInvestment = totalBuyExpense + totalSellFeeAndTax
        val marketValue = shares * ptPrice
        var totalPL = marketValue - costBasis

        if (preDeductSellFees && marketValue > 0.0) {
            val sellFee = (marketValue * 0.001425 * feeDiscount).coerceAtLeast(minFeeRegular)
            val taxRate = if (stockType == "ETF") 0.001 else 0.003
            val sellTax = marketValue * taxRate
            totalPL -= (sellFee + sellTax)
        }

        return HistoricalHoldingStats(
            shares = shares,
            costBasis = costBasis,
            totalInvestment = totalInvestment,
            marketValue = marketValue,
            totalPL = totalPL
        )
    }

    fun setHomeDisplayMode(mode: String) {
        viewModelScope.launch {
            settingsDataStore.setHomeDisplayMode(mode)
        }
    }

    fun refreshAllHoldingsQuotes() {
        viewModelScope.launch {
            realtimeStockDataService.refreshAllHeldStockInfo()
        }
    }

    fun setHoldingsOrder(order: List<String>) {
        viewModelScope.launch {
            settingsDataStore.setHoldingsOrder(order)
        }
    }

    fun setRealizedHoldingsOrder(order: List<String>) {
        viewModelScope.launch {
            settingsDataStore.setRealizedHoldingsOrder(order)
        }
    }

    fun markHoldingsReorderHintShown() {
        viewModelScope.launch {
            settingsDataStore.setHoldingsReorderHintShown(true)
        }
    }

    fun setHomeHoldingsManualSort() {
        viewModelScope.launch {
            settingsDataStore.setHomeHoldingsSortPreference(
                mode = "MANUAL",
                column = "NONE",
                ascending = true
            )
        }
    }

    fun setHomeHoldingsFixedSort(column: String, ascending: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setHomeHoldingsSortPreference(
                mode = "COLUMN",
                column = column,
                ascending = ascending
            )
        }
    }
}
