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
import com.rsps1008.stockify.data.HomeDisplayMode
import com.rsps1008.stockify.data.HistoryChartCalculationSupport
import com.rsps1008.stockify.data.HoldingCalculationSupport
import com.rsps1008.stockify.data.StockMarket
import com.rsps1008.stockify.data.UsdTwdExchangeRateService
import com.rsps1008.stockify.data.CashFlow
import com.rsps1008.stockify.data.ReturnRateCalculator
import com.rsps1008.stockify.data.Account
import com.rsps1008.stockify.ui.screens.HoldingsUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface HomeHistoryStateInternal {
    object Idle : HomeHistoryStateInternal
    data class Loading(val progress: Float, val statusText: String) : HomeHistoryStateInternal
    data class Success(
        val range: HistoryRange,
        val portfolioKey: String,
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

private data class HomeHistoryCalculationBundle(
    val settings: HomeSettingsBundle,
    val displayMode: String,
    val usdToTwdRate: Double
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
    private val exchangeRateService: UsdTwdExchangeRateService,
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

    val activeAccountId: StateFlow<Int> = settingsDataStore.activeAccountIdFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = 0
        )

    val accounts: StateFlow<List<Account>> = stockDao.getAllAccountsFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    fun selectAccount(accountId: Int) {
        viewModelScope.launch {
            settingsDataStore.setActiveAccountId(accountId)
        }
    }

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

    val homeHistoryChartExpanded: StateFlow<Boolean> = settingsDataStore.homeHistoryChartExpandedFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = true
        )

    val taiwanWeightedIndexInfo: StateFlow<com.rsps1008.stockify.data.TaiwanWeightedIndexInfo?> =
        taiwanWeightedIndexService.indexInfo

    // --- Home Portfolio History States ---
    private val _historyStateInternal = MutableStateFlow<HomeHistoryStateInternal>(HomeHistoryStateInternal.Idle)
    private val _selectedHomeHistoryRange = MutableStateFlow(HistoryRange.ONE_MONTH)
    val selectedHomeHistoryRange: StateFlow<HistoryRange> = _selectedHomeHistoryRange.asStateFlow()
    private var fetchPortfolioHistoryJob: Job? = null
    private var homeHistoryRequestVersion = 0L

    private val settingsCombined = combine(
        settingsDataStore.preDeductSellFeesFlow,
        settingsDataStore.feeDiscountFlow,
        settingsDataStore.minFeeRegularFlow,
        settingsDataStore.returnRateModeFlow
    ) { preDeduct, discount, minFee, mode ->
        HomeSettingsBundle(preDeduct, discount, minFee, mode)
    }

    private val historyCalculationBundle = combine(
        settingsCombined,
        homeDisplayMode,
        exchangeRateService.usdToTwdRate
    ) { settings, displayMode, usdToTwdRate ->
        HomeHistoryCalculationBundle(settings, displayMode, usdToTwdRate)
    }

    val historyState: StateFlow<HistoryState> = combine(
        _historyStateInternal,
        stockDao.getAllTransactions(),
        historyCalculationBundle,
        uiState,
        settingsDataStore.activeAccountIdFlow
    ) { historyInternal, allTxs, calculationBundle, holdingsState, activeAccountId ->
        if (historyInternal is HomeHistoryStateInternal.Success) {
            val expectedPortfolioKey = buildPortfolioKey(
                holdingsState,
                calculationBundle.displayMode,
                activeAccountId
            )
            if (historyInternal.portfolioKey != expectedPortfolioKey) {
                return@combine HistoryState.Loading(0f, "切換歷史資料中...")
            }

            val settings = calculationBundle.settings
            val minFee = settings.minFeeRegular.toDouble()
            val normalizedMode = HomeDisplayMode.normalize(calculationBundle.displayMode)
            val normalizedUsdToTwdRate = calculationBundle.usdToTwdRate.takeIf { it > 0.0 } ?: 1.0
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).apply {
                timeZone = java.util.TimeZone.getTimeZone("Asia/Taipei")
            }

            val personalPoints = mutableListOf<PersonalHistoryPoint>()
            val selectedStocksByCode = holdingsState.holdings.associateBy { it.stock.code }

            val accountFilteredTxs = if (activeAccountId == 0) {
                allTxs
            } else {
                allTxs.filter { it.accountId == activeAccountId }
            }

            val twTxs = accountFilteredTxs.filter { tx ->
                historyInternal.allRawPoints.containsKey(tx.stockCode)
            }

            val historicalTxsByStock = twTxs.groupBy { it.stockCode }.mapValues { (_, txList) ->
                txList.sortedBy { it.date }
            }

            val firstTxTime = twTxs.minOfOrNull { it.date }
            var previousPortfolioXirrGuessRate: Double? = null

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
                val portfolioCashFlows = mutableListOf<CashFlow>()

                for ((stockCode, rawList) in historyInternal.allRawPoints) {
                    val dailyPrice = rawList.firstOrNull { it.date == pt.date }?.price
                        ?: rawList.filter { it.date <= pt.date }.lastOrNull()?.price
                        ?: 0.0

                    val stockTxs = historicalTxsByStock[stockCode] ?: emptyList()
                    val stockType = holdingsState.holdings.firstOrNull { it.stock.code == stockCode }?.stock?.stockType ?: ""

                    val stats = calculateHistoricalHoldingStatsAt(
                        ptPrice = dailyPrice,
                        transactions = stockTxs,
                        preDeductSellFees = settings.preDeductSellFees,
                        feeDiscount = settings.feeDiscount,
                        minFeeRegular = minFee,
                        market = selectedStocksByCode[stockCode]?.stock?.market ?: StockMarket.inferFromCode(stockCode),
                        stockType = stockType,
                        dayEnd = dayEnd
                    )
                    val currencyRate = if (
                        normalizedMode == HomeDisplayMode.COMBINED &&
                        StockMarket.isUs(selectedStocksByCode[stockCode]?.stock?.market)
                    ) {
                        normalizedUsdToTwdRate
                    } else {
                        1.0
                    }
                    totalMarketValue += stats.marketValue * currencyRate
                    totalCostBasis += stats.costBasis * currencyRate
                    totalInvestment += stats.totalInvestment * currencyRate
                    totalPL += stats.totalPL * currencyRate
                    totalShares += stats.shares
                    if (settings.returnRateMode == ReturnRateMode.XIRR) {
                        portfolioCashFlows += buildHistoricalCashFlows(
                            transactions = stockTxs.filter { it.date <= dayEnd },
                            shares = stats.shares,
                            price = dailyPrice,
                            terminalDateMillis = dayEnd,
                            currencyRate = currencyRate
                        )
                    }
                }

                val totalPLPercentage = when (settings.returnRateMode) {
                    ReturnRateMode.REMAINING_POSITION -> {
                        val denominator = HoldingCalculationSupport.remainingPositionDenominator(
                            shares = totalShares,
                            costBasis = totalCostBasis,
                            totalInvestment = totalInvestment
                        )
                        if (denominator > 0) (totalPL / denominator) * 100 else 0.0
                    }
                    ReturnRateMode.CUMULATIVE_INVESTMENT -> if (totalInvestment > 0) (totalPL / totalInvestment) * 100 else 0.0
                    ReturnRateMode.XIRR -> {
                        val xirrRate = ReturnRateCalculator.calculateXirrRate(
                            cashFlows = portfolioCashFlows,
                            guess = previousPortfolioXirrGuessRate ?: 0.1
                        )
                        previousPortfolioXirrGuessRate = xirrRate ?: previousPortfolioXirrGuessRate
                        xirrRate?.times(100.0) ?: 0.0
                    }
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
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), HistoryState.Idle)

    init {
        viewModelScope.launch {
            var lastPortfolioKey = ""
            combine(uiState, homeDisplayMode, activeAccountId) { state, mode, accountId ->
                buildPortfolioKey(state, mode, accountId)
            }.collect { portfolioKey ->
                val hasStocks = portfolioKey.substringAfterLast("|").isNotBlank()
                val shouldLoad = lastPortfolioKey.isNotBlank() || hasStocks
                if (portfolioKey != lastPortfolioKey && shouldLoad) {
                    lastPortfolioKey = portfolioKey
                    fetchPortfolioHistory(selectedHomeHistoryRange.value)
                }
            }
        }
    }

    fun fetchPortfolioHistory(range: HistoryRange) {
        _selectedHomeHistoryRange.value = range
        val rangeMonths = when (range) {
            HistoryRange.ONE_MONTH -> 1
            HistoryRange.SIX_MONTHS -> 6
            HistoryRange.ONE_YEAR -> 12
        }

        fetchPortfolioHistoryJob?.cancel()
        val requestVersion = ++homeHistoryRequestVersion
        fetchPortfolioHistoryJob = viewModelScope.launch {
            val selectedStocks = uiState.value.holdings
                .map { it.stock }
                .filter { StockMarket.isTw(it.market) || StockMarket.isUs(it.market) }
            val portfolioKey = buildPortfolioKey(uiState.value, homeDisplayMode.value, activeAccountId.value)
            if (selectedStocks.isEmpty()) {
                val mode = HomeDisplayMode.normalize(homeDisplayMode.first())
                val message = when (mode) {
                    HomeDisplayMode.US -> "目前沒有美股持股，暫無歷史資料可顯示。"
                    HomeDisplayMode.COMBINED -> "目前沒有台股或美股持股，暫無歷史資料可顯示。"
                    else -> "目前沒有台股持股，暫無歷史資料可顯示。"
                }
                if (requestVersion == homeHistoryRequestVersion) {
                    _historyStateInternal.value = HomeHistoryStateInternal.Error(message)
                }
                return@launch
            }

            val cachedRawPoints = mutableMapOf<String, List<StockHistoryPoint>>()
            for (stock in selectedStocks) {
                val cachedPoints = twseStockHistoryService.getCachedHistory(stock.code, rangeMonths)
                if (cachedPoints.isNotEmpty()) {
                    cachedRawPoints[stock.code] = cachedPoints
                }
            }
            val hasCachedPoints = cachedRawPoints.isNotEmpty()
            if (hasCachedPoints) {
                if (requestVersion == homeHistoryRequestVersion) {
                    _historyStateInternal.value = buildHomeHistorySuccess(range, portfolioKey, cachedRawPoints)
                }
            } else {
                if (requestVersion == homeHistoryRequestVersion) {
                    _historyStateInternal.value = HomeHistoryStateInternal.Loading(0f, "準備下載歷史股價...")
                }
            }

            try {
                val allRawPoints = mutableMapOf<String, List<StockHistoryPoint>>()
                val totalStocks = selectedStocks.size

                for ((index, stock) in selectedStocks.withIndex()) {
                    val rawPoints = twseStockHistoryService.fetchHistory(stock.code, rangeMonths) { step, total ->
                        val baseProgress = index.toFloat() / totalStocks
                        val stepProgress = (step.toFloat() / total) / totalStocks
                        val statusText = if (StockMarket.isUs(stock.market)) {
                            "正在載入 ${stock.name} (${index + 1}/$totalStocks) 美股歷史股價..."
                        } else {
                            "正在載入 ${stock.name} (${index + 1}/$totalStocks) 第 $step/$total 個月..."
                        }
                        if (!hasCachedPoints) {
                            if (requestVersion == homeHistoryRequestVersion) {
                                _historyStateInternal.value = HomeHistoryStateInternal.Loading(
                                    baseProgress + stepProgress,
                                    statusText
                                )
                            }
                        }
                    }
                    allRawPoints[stock.code] = rawPoints
                }

                val availableRawPoints = HistoryChartCalculationSupport.filterEmptyHistorySeries(allRawPoints)

                if (availableRawPoints.isEmpty()) {
                    if (!hasCachedPoints && requestVersion == homeHistoryRequestVersion) {
                        _historyStateInternal.value = HomeHistoryStateInternal.Error("無歷史股價數據，請稍後重試。")
                    }
                    return@launch
                }

                if (requestVersion == homeHistoryRequestVersion) {
                    _historyStateInternal.value = buildHomeHistorySuccess(range, portfolioKey, availableRawPoints)
                }
            } catch (e: Exception) {
                if (!hasCachedPoints && requestVersion == homeHistoryRequestVersion) {
                    _historyStateInternal.value = HomeHistoryStateInternal.Error("載入失敗: ${e.localizedMessage}")
                }
            }
        }
    }

    private fun buildHomeHistorySuccess(
        range: HistoryRange,
        portfolioKey: String,
        allRawPoints: Map<String, List<StockHistoryPoint>>
    ): HomeHistoryStateInternal.Success {
        val availableRawPoints = HistoryChartCalculationSupport.filterEmptyHistorySeries(allRawPoints)
        val allDates = availableRawPoints.values.flatMap { points -> points.map { it.date } }.distinct().sorted()
        val alignedRawPoints = allDates.map { date ->
            StockHistoryPoint(date, 1.0)
        }
        return HomeHistoryStateInternal.Success(range, portfolioKey, alignedRawPoints, availableRawPoints)
    }

    private fun buildPortfolioKey(state: HoldingsUiState, mode: String, accountId: Int): String {
        val codes = state.holdings
            .filter { StockMarket.isTw(it.stock.market) || StockMarket.isUs(it.stock.market) }
            .map { "${StockMarket.normalize(it.stock.market)}:${it.stock.code}" }
            .sorted()
        return "$accountId|${HomeDisplayMode.normalize(mode)}|${codes.joinToString(",")}"
    }

    private fun calculateHistoricalHoldingStatsAt(
        ptPrice: Double,
        transactions: List<StockTransaction>,
        preDeductSellFees: Boolean,
        feeDiscount: Double,
        minFeeRegular: Double,
        market: String,
        stockType: String,
        dayEnd: Long
    ): HistoricalHoldingStats {
        val txs = transactions.filter { it.date <= dayEnd }
            .sortedWith(compareBy<StockTransaction> { it.date }.thenBy { it.recordTime })

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
                    totalDividendIncome += HoldingCalculationSupport.resolveDividendIncome(it)
                }
                "減資" -> {
                    shares += HoldingCalculationSupport.capitalReductionShareChange(it, shares)
                    totalSellIncome += it.cashReturned
                }
                "分割" -> {
                    shares += HoldingCalculationSupport.splitShareChange(it, shares)
                }
            }
        }

        if (shares < 0) shares = 0.0
        val costBasis = totalBuyExpense - totalSellIncome - totalDividendIncome
        val totalSellFeeAndTax = (sellAmountBeforeFee - totalSellNetIncome).coerceAtLeast(0.0)
        val totalInvestment = totalBuyExpense + totalSellFeeAndTax
        val marketValue = shares * ptPrice
        var totalPL = marketValue - costBasis

        if (preDeductSellFees && marketValue > 0.0 && StockMarket.isTw(market)) {
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

    private fun buildHistoricalCashFlows(
        transactions: List<StockTransaction>,
        shares: Double,
        price: Double,
        terminalDateMillis: Long,
        currencyRate: Double
    ): List<CashFlow> {
        val cashFlows = transactions.mapNotNull { transaction ->
            when (transaction.type) {
                "買進" -> CashFlow(transaction.date, -transaction.expense * currencyRate)
                "賣出" -> CashFlow(transaction.date, transaction.income * currencyRate)
                "配息" -> CashFlow(
                    transaction.date,
                    HoldingCalculationSupport.resolveDividendIncome(transaction) * currencyRate
                )
                "減資" -> CashFlow(transaction.date, transaction.cashReturned * currencyRate)
                else -> null
            }
        }.toMutableList()

        if (shares > 0.0 && price > 0.0) {
            cashFlows.add(CashFlow(terminalDateMillis, shares * price * currencyRate))
        }

        return cashFlows
    }

    fun setHomeDisplayMode(mode: String) {
        _historyStateInternal.value = HomeHistoryStateInternal.Loading(0f, "切換歷史資料中...")
        viewModelScope.launch {
            settingsDataStore.setHomeDisplayMode(mode)
        }
    }

    fun refreshAllHoldingsQuotes() {
        viewModelScope.launch {
            realtimeStockDataService.refreshAllHeldStockInfo()
            fetchPortfolioHistory(selectedHomeHistoryRange.value)
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

    fun setHomeHistoryChartExpanded(expanded: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setHomeHistoryChartExpanded(expanded)
        }
    }
}
