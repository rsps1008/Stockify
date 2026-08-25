package com.rsps1008.stockify.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.rsps1008.stockify.data.AppDatabase
import com.rsps1008.stockify.data.RealtimeStockDataService
import com.rsps1008.stockify.data.SettingsDataStore
import com.rsps1008.stockify.data.StockRepository
import com.rsps1008.stockify.data.TaiwanWeightedIndexService
import com.rsps1008.stockify.data.StockDao
import com.rsps1008.stockify.data.TwseStockHistoryService
import com.rsps1008.stockify.data.ReturnRateMode
import com.rsps1008.stockify.data.StockTransaction
import com.rsps1008.stockify.data.StockHistoryPoint
import com.rsps1008.stockify.data.Stock
import com.rsps1008.stockify.data.HomeDisplayMode
import com.rsps1008.stockify.data.HistoryChartCalculationSupport
import com.rsps1008.stockify.data.TransactionDateSupport
import com.rsps1008.stockify.data.HoldingCalculationSupport
import com.rsps1008.stockify.data.HistoricalLongPositionTimeline
import com.rsps1008.stockify.data.HistoricalTransactionCashFlowTimeline
import com.rsps1008.stockify.data.LongPositionReplaySummary
import com.rsps1008.stockify.data.StockMarket
import com.rsps1008.stockify.data.toStockKey
import com.rsps1008.stockify.data.UsdTwdExchangeRateService
import com.rsps1008.stockify.data.CashFlow
import com.rsps1008.stockify.data.ReturnRateCalculator
import com.rsps1008.stockify.data.MarginCalculationSupport
import com.rsps1008.stockify.data.MarginSummary
import com.rsps1008.stockify.data.ShortSellingCalculationSupport
import com.rsps1008.stockify.data.ShortSellingSummary
import com.rsps1008.stockify.data.TransactionCostSupport
import com.rsps1008.stockify.data.Account
import com.rsps1008.stockify.ui.screens.HoldingsUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
    val minFeeOddLot: Int,
    val returnRateMode: ReturnRateMode,
    val marginDayCount: Int
)

private data class HomeHistoryCalculationBundle(
    val settings: HomeSettingsBundle,
    val displayMode: String,
    val usdToTwdRate: Double
)

private data class HistoricalHoldingStats(
    val shares: Double,
    val totalInvestment: Double,
    val remainingPositionInvestment: Double,
    val marketValue: Double,
    val totalPL: Double,
    val marginSummary: MarginSummary,
    val shortSummary: ShortSellingSummary
)

private const val MAX_PARALLEL_HISTORY_DOWNLOADS = 3

internal class PortfolioHistoryProgressTracker(private val stockCount: Int) {
    private val lock = Any()
    private val completedSteps = IntArray(stockCount)
    private val totalSteps = IntArray(stockCount)
    private var lastProgress = 0f

    init {
        require(stockCount > 0) { "stockCount must be positive" }
    }

    fun update(stockIndex: Int, step: Int, total: Int): Float = synchronized(lock) {
        require(stockIndex in 0 until stockCount) { "stockIndex is out of range" }
        val safeTotal = total.coerceAtLeast(1)
        totalSteps[stockIndex] = maxOf(totalSteps[stockIndex], safeTotal)
        completedSteps[stockIndex] = maxOf(
            completedSteps[stockIndex],
            step.coerceIn(0, safeTotal)
        )
        recalculateProgress()
    }

    fun markComplete(stockIndex: Int): Float = synchronized(lock) {
        require(stockIndex in 0 until stockCount) { "stockIndex is out of range" }
        totalSteps[stockIndex] = maxOf(totalSteps[stockIndex], 1)
        completedSteps[stockIndex] = totalSteps[stockIndex]
        recalculateProgress()
    }

    private fun recalculateProgress(): Float {
        val normalizedProgress = (0 until stockCount).sumOf { index ->
            val total = totalSteps[index].coerceAtLeast(1)
            completedSteps[index].coerceAtMost(total).toDouble() / total
        } / stockCount
        lastProgress = maxOf(lastProgress, normalizedProgress.toFloat()).coerceIn(0f, 1f)
        return lastProgress
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HoldingsViewModel(
    private val settingsDataStore: SettingsDataStore,
    private val realtimeStockDataService: RealtimeStockDataService,
    private val taiwanWeightedIndexService: TaiwanWeightedIndexService,
    private val stockDao: StockDao,
    private val twseStockHistoryService: TwseStockHistoryService,
    private val exchangeRateService: UsdTwdExchangeRateService,
    private val appDatabase: AppDatabase,
    stockRepository: StockRepository
) : ViewModel() {

    val uiState: StateFlow<HoldingsUiState> = stockRepository.getHoldings()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = HoldingsUiState()
        )

    private val historyStocks: StateFlow<List<Stock>> = uiState
        .map { state ->
            state.holdings
                .map { it.stock }
                .filter { StockMarket.isTw(it.market) || StockMarket.isUs(it.market) }
                .distinctBy { StockMarket.normalize(it.market) to it.code }
                .sortedWith(compareBy<Stock> { StockMarket.normalize(it.market) }.thenBy { it.code })
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

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

    private val _accountOperationError = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val accountOperationError: SharedFlow<String> = _accountOperationError.asSharedFlow()

    fun addAccount(name: String) {
        val normalizedName = name.trim()
        if (normalizedName.isBlank()) return
        viewModelScope.launch {
            runAccountOperation {
                stockDao.insertAccount(Account(name = normalizedName))
            }
        }
    }

    fun renameAccount(account: Account, name: String) {
        val normalizedName = name.trim()
        if (normalizedName.isBlank()) return
        viewModelScope.launch {
            runAccountOperation {
                stockDao.updateAccount(account.copy(name = normalizedName))
            }
        }
    }

    fun deleteAccount(account: Account) {
        val accountId = account.id
        viewModelScope.launch {
            runAccountOperation {
                appDatabase.withTransaction {
                    stockDao.deleteTransactionsByAccountId(accountId)
                    stockDao.deleteAccount(account)
                }
                if (settingsDataStore.activeAccountIdFlow.first() == accountId) {
                    settingsDataStore.setActiveAccountId(0)
                }
            }
        }
    }

    private suspend fun runAccountOperation(operation: suspend () -> Unit) {
        try {
            operation()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("HoldingsViewModel", "Account operation failed", e)
            _accountOperationError.emit("帳戶操作失敗，請稍後再試")
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

    private val baseSettingsCombined = combine(
        settingsDataStore.preDeductSellFeesFlow,
        settingsDataStore.feeDiscountFlow,
        settingsDataStore.minFeeRegularFlow,
        settingsDataStore.returnRateModeFlow,
        settingsDataStore.marginDayCountFlow
    ) { preDeduct, discount, minFeeRegular, mode, marginDayCount ->
        HomeSettingsBundle(preDeduct, discount, minFeeRegular, 0, mode, marginDayCount)
    }

    private val settingsCombined = baseSettingsCombined.combine(
        settingsDataStore.minFeeOddLotFlow
    ) { settings, minFeeOddLot ->
        settings.copy(minFeeOddLot = minFeeOddLot)
    }

    private val historyCalculationBundle = combine(
        settingsCombined,
        homeDisplayMode,
        exchangeRateService.usdToTwdRate
    ) { settings, displayMode, usdToTwdRate ->
        HomeHistoryCalculationBundle(settings, displayMode, usdToTwdRate)
    }

    private val historyTransactions = settingsDataStore.activeAccountIdFlow
        .distinctUntilChanged()
        .flatMapLatest { accountId ->
            val transactions = if (accountId == 0) {
                stockDao.getAllTransactions()
            } else {
                stockDao.getTransactionsForAccount(accountId)
            }
            transactions.map { accountId to it }
        }

    val historyState: StateFlow<HistoryState> = combine(
        _historyStateInternal,
        historyTransactions,
        historyCalculationBundle,
        historyStocks,
        settingsDataStore.activeAccountIdFlow
    ) { historyInternal, scopedTransactions, calculationBundle, stocks, activeAccountId ->
        if (historyInternal is HomeHistoryStateInternal.Success) {
            if (scopedTransactions.first != activeAccountId) {
                return@combine HistoryState.Loading(0f, "切換帳戶資料中...")
            }
            val expectedPortfolioKey = buildPortfolioKey(
                stocks,
                calculationBundle.displayMode,
                activeAccountId
            )
            if (historyInternal.portfolioKey != expectedPortfolioKey) {
                return@combine HistoryState.Loading(0f, "切換歷史資料中...")
            }

            val settings = calculationBundle.settings
            val minFeeRegular = settings.minFeeRegular.toDouble()
            val minFeeOddLot = settings.minFeeOddLot.toDouble()
            val normalizedMode = HomeDisplayMode.normalize(calculationBundle.displayMode)
            val normalizedUsdToTwdRate = calculationBundle.usdToTwdRate.takeIf { it > 0.0 } ?: 1.0
            val allTxs = scopedTransactions.second

            val personalPoints = mutableListOf<PersonalHistoryPoint>()
            val selectedStocksByKey = stocks.associateBy { it.toStockKey().cacheKey() }
            val marketByStockKey = historyInternal.allRawPoints.keys.associateWith { stockKey ->
                StockMarket.normalize(
                    selectedStocksByKey[stockKey]?.market
                        ?: stockKey.substringBefore(':').takeIf { it != stockKey }
                        ?: StockMarket.TW
                )
            }

            val twTxs = allTxs.filter { tx ->
                historyInternal.allRawPoints.containsKey(tx.toStockKey().cacheKey())
            }

            val historicalTxsByStock = twTxs.groupBy { it.toStockKey().cacheKey() }.mapValues { (_, txList) ->
                txList.sortedWith(
                    compareBy<StockTransaction> { it.date }
                        .thenBy { it.recordTime }
                        .thenBy { it.id }
                )
            }
            val historicalTimelinesByStock = historicalTxsByStock.mapValues { (_, transactions) ->
                HistoricalLongPositionTimeline(transactions, transactionsAreOrdered = true)
            }
            val historicalMarginTimelinesByStock = historicalTxsByStock.mapValues { (_, transactions) ->
                if (transactions.any { transaction ->
                        transaction.type == "融資買進" ||
                            transaction.marginRepaymentLotId.isNotBlank() ||
                            transaction.marginRepayment > 0.0 ||
                            transaction.marginActualInterest > 0.0
                    }
                ) {
                    MarginCalculationSupport.HistoricalTimeline(
                        transactions = transactions,
                        dayCount = settings.marginDayCount,
                        transactionsAreOrdered = true
                    )
                } else {
                    null
                }
            }
            val historicalShortTimelinesByStock = historicalTxsByStock.mapValues { (_, transactions) ->
                if (transactions.any { transaction ->
                        transaction.type == "融券賣出" ||
                            transaction.type == "買券還券" ||
                            transaction.type == "融券補償"
                    }
                ) {
                    ShortSellingCalculationSupport.HistoricalTimeline(
                        transactions = transactions,
                        dayCount = settings.marginDayCount,
                        transactionsAreOrdered = true
                    )
                } else {
                    null
                }
            }
            val xirrZoneId = HistoryChartCalculationSupport.zoneIdForHomeXirr(normalizedMode)
            val transactionDateMapper: (Long) -> Long = { transactionDate ->
                TransactionDateSupport.moveToZoneDateStartMillis(transactionDate, xirrZoneId)
            }
            val historicalCashFlowTimelinesByStock = if (settings.returnRateMode == ReturnRateMode.XIRR) {
                historicalTxsByStock.mapValues { (stockKey, transactions) ->
                    val stockMarket = marketByStockKey.getValue(stockKey)
                    val currencyRate = if (
                        normalizedMode == HomeDisplayMode.COMBINED && StockMarket.isUs(stockMarket)
                    ) {
                        normalizedUsdToTwdRate
                    } else {
                        1.0
                    }
                    HistoricalTransactionCashFlowTimeline(
                        transactions = transactions,
                        currencyRate = currencyRate,
                        transactionDateMapper = transactionDateMapper,
                        transactionsAreOrdered = true
                    )
                }
            } else {
                emptyMap()
            }
            val historicalShortXirrTimelinesByStock = if (settings.returnRateMode == ReturnRateMode.XIRR) {
                historicalTxsByStock.mapValues { (_, transactions) ->
                    ShortSellingCalculationSupport.HistoricalXirrTimeline(
                        transactions = transactions,
                        transactionDateMapper = transactionDateMapper,
                        transactionsAreOrdered = true
                    )
                }
            } else {
                emptyMap()
            }
            if (twTxs.isEmpty()) {
                return@combine HistoryState.Empty(
                    range = historyInternal.range,
                    message = "所選期間沒有可用的持股歷史股價。"
                )
            }

            var previousPortfolioXirrGuessRate: Double? = null

            for (pt in historyInternal.rawPoints) {
                val twDayEnd = HistoryChartCalculationSupport.valuationDateEndMillis(
                    pt.date,
                    StockMarket.TW
                ) ?: continue
                val usDayEnd = HistoryChartCalculationSupport.valuationDateEndMillis(
                    pt.date,
                    StockMarket.US
                ) ?: continue
                val dayEndByMarket = mapOf(
                    StockMarket.TW to twDayEnd,
                    StockMarket.US to usDayEnd
                )
                val transactionCutoff = TransactionDateSupport.replayCutoffMillis(pt.date)
                    ?: continue

                val stocksRequiringPrice = historicalTxsByStock
                    .filter { (_, stockTxs) ->
                        stockTxs.any { it.date <= transactionCutoff }
                    }
                    .keys
                if (stocksRequiringPrice.isEmpty()) continue
                if (!HistoryChartCalculationSupport.hasHistoryAtOrBeforeForStocks(
                        date = pt.date,
                        stockCodes = stocksRequiringPrice,
                        allRawPoints = historyInternal.allRawPoints
                    )
                ) {
                    continue
                }

                var totalMarketValue = 0.0
                var totalInvestment = 0.0
                var totalRemainingPositionInvestment = 0.0
                var totalPL = 0.0
                var totalShares = 0.0
                val portfolioCashFlows = mutableListOf<CashFlow>()

                for ((stockKey, rawList) in historyInternal.allRawPoints) {
                    val dailyPrice = HistoryChartCalculationSupport.priceAtOrBefore(rawList, pt.date) ?: 0.0

                    val timeline = historicalTimelinesByStock[stockKey] ?: continue
                    val stockType = selectedStocksByKey[stockKey]?.stockType ?: ""
                    val stockMarket = marketByStockKey.getValue(stockKey)
                    val stockDayEnd = dayEndByMarket.getValue(stockMarket)
                    val replay = timeline.advanceTo(transactionCutoff)
                    val stockTxs = timeline.transactionsAtCurrentDate()
                    val marginSummary = historicalMarginTimelinesByStock[stockKey]?.advanceTo(transactionCutoff)
                        ?: MarginSummary()
                    val shortSummary = historicalShortTimelinesByStock[stockKey]?.advanceTo(transactionCutoff)
                        ?: ShortSellingSummary()

                    val stats = calculateHistoricalHoldingStatsAt(
                        ptPrice = dailyPrice,
                        replay = replay,
                        preDeductSellFees = settings.preDeductSellFees,
                        feeDiscount = settings.feeDiscount,
                        minFeeRegular = minFeeRegular,
                        minFeeOddLot = minFeeOddLot,
                        market = stockMarket,
                        stockType = stockType,
                        marginSummary = marginSummary,
                        shortSummary = shortSummary,
                        shortIncome = timeline.shortIncome,
                        shortCoverExpense = timeline.shortCoverExpense,
                        hasMarginPurchase = timeline.hasMarginPurchase
                    )
                    val currencyRate = if (
                        normalizedMode == HomeDisplayMode.COMBINED &&
                        StockMarket.isUs(stockMarket)
                    ) {
                        normalizedUsdToTwdRate
                    } else {
                        1.0
                    }
                    totalMarketValue += stats.marketValue * currencyRate
                    totalInvestment += stats.totalInvestment * currencyRate
                    totalRemainingPositionInvestment += stats.remainingPositionInvestment * currencyRate
                    totalPL += stats.totalPL * currencyRate
                    totalShares += stats.shares
                    if (settings.returnRateMode == ReturnRateMode.XIRR) {
                        portfolioCashFlows += buildHistoricalCashFlows(
                            transactions = stockTxs,
                            shares = stats.shares,
                            price = dailyPrice,
                            terminalDateMillis = stockDayEnd,
                            transactionCutoffMillis = transactionCutoff,
                            currencyRate = currencyRate,
                            marginSummary = stats.marginSummary,
                            shortSummary = stats.shortSummary,
                            historicalCashFlowTimeline = historicalCashFlowTimelinesByStock[stockKey],
                            historicalShortXirrTimeline = historicalShortXirrTimelinesByStock[stockKey]
                        )
                    }
                }

                val totalPLPercentage = when (settings.returnRateMode) {
                    ReturnRateMode.REMAINING_POSITION -> {
                        val denominator = totalRemainingPositionInvestment
                        if (denominator > 0) (totalPL / denominator) * 100 else 0.0
                    }
                    ReturnRateMode.CUMULATIVE_INVESTMENT -> if (totalInvestment > 0) (totalPL / totalInvestment) * 100 else 0.0
                    ReturnRateMode.XIRR -> {
                        val xirrRate = ReturnRateCalculator.calculateXirrRate(
                            cashFlows = portfolioCashFlows,
                            guess = previousPortfolioXirrGuessRate ?: 0.1,
                            zoneId = HistoryChartCalculationSupport.zoneIdForHomeXirr(normalizedMode)
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
                HistoryState.Empty(
                    range = historyInternal.range,
                    message = "所選期間沒有可用的持股歷史股價。"
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
            combine(historyStocks, homeDisplayMode, activeAccountId) { stocks, mode, accountId ->
                buildPortfolioKey(stocks, mode, accountId)
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

    fun fetchPortfolioHistory(
        range: HistoryRange,
        forceRefreshCurrentMonth: Boolean = false
    ) {
        _selectedHomeHistoryRange.value = range
        val rangeMonths = when (range) {
            HistoryRange.ONE_MONTH -> 1
            HistoryRange.SIX_MONTHS -> 6
            HistoryRange.ONE_YEAR -> 12
        }

        fetchPortfolioHistoryJob?.cancel()
        val requestVersion = ++homeHistoryRequestVersion
        _historyStateInternal.value = HomeHistoryStateInternal.Loading(0f, "準備載入歷史股價...")
        fetchPortfolioHistoryJob = viewModelScope.launch {
            val selectedStocks = historyStocks.value
            val portfolioKey = buildPortfolioKey(historyStocks.value, homeDisplayMode.value, activeAccountId.value)
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
                val cachedPoints = twseStockHistoryService.getCachedHistory(stock.code, rangeMonths, stock.market)
                if (cachedPoints.isNotEmpty()) {
                    cachedRawPoints[stock.toStockKey().cacheKey()] = cachedPoints
                }
            }
            val hasCompleteCachedPoints = HistoryChartCalculationSupport.hasHistoryForAllStocks(
                stockCodes = selectedStocks.map { it.toStockKey().cacheKey() },
                allRawPoints = cachedRawPoints,
                rangeMonths = rangeMonths
            )
            if (hasCompleteCachedPoints) {
                if (requestVersion == homeHistoryRequestVersion) {
                    _historyStateInternal.value = buildHomeHistorySuccess(range, portfolioKey, cachedRawPoints)
                }
            } else {
                if (requestVersion == homeHistoryRequestVersion) {
                    _historyStateInternal.value = HomeHistoryStateInternal.Loading(0f, "準備下載歷史股價...")
                }
            }

            try {
                val totalStocks = selectedStocks.size
                val downloadSemaphore = Semaphore(MAX_PARALLEL_HISTORY_DOWNLOADS)
                val progressTracker = PortfolioHistoryProgressTracker(totalStocks)
                val progressStateLock = Any()
                val downloadResults = coroutineScope {
                    selectedStocks.mapIndexed { index, stock ->
                        async(Dispatchers.IO) {
                            downloadSemaphore.withPermit {
                                val rawPoints = twseStockHistoryService.fetchHistory(
                                    stock.code,
                                    rangeMonths,
                                    stock.market,
                                    forceRefreshCurrentMonth = forceRefreshCurrentMonth
                                ) { step, total ->
                                    if (!hasCompleteCachedPoints) {
                                        val statusText = if (StockMarket.isUs(stock.market)) {
                                            "正在載入 ${stock.name} (${index + 1}/$totalStocks) 美股歷史股價..."
                                        } else {
                                            "正在載入 ${stock.name} (${index + 1}/$totalStocks) 第 $step/$total 個月..."
                                        }
                                        synchronized(progressStateLock) {
                                            val progress = progressTracker.update(index, step, total)
                                            if (requestVersion == homeHistoryRequestVersion) {
                                                _historyStateInternal.value = HomeHistoryStateInternal.Loading(
                                                    progress,
                                                    statusText
                                                )
                                            }
                                        }
                                    }
                                }
                                if (!hasCompleteCachedPoints) {
                                    synchronized(progressStateLock) {
                                        val progress = progressTracker.markComplete(index)
                                        if (requestVersion == homeHistoryRequestVersion) {
                                            _historyStateInternal.value = HomeHistoryStateInternal.Loading(
                                                progress,
                                                "已完成 ${stock.name} (${index + 1}/$totalStocks) 歷史股價"
                                            )
                                        }
                                    }
                                }
                                stock.toStockKey().cacheKey() to rawPoints
                            }
                        }
                    }.awaitAll()
                }
                val allRawPoints = downloadResults.toMap()

                val availableRawPoints = HistoryChartCalculationSupport.filterEmptyHistorySeries(allRawPoints)

                if (availableRawPoints.isEmpty()) {
                    if (!hasCompleteCachedPoints && requestVersion == homeHistoryRequestVersion) {
                        _historyStateInternal.value = HomeHistoryStateInternal.Error("無歷史股價數據，請稍後重試。")
                    }
                    return@launch
                }

                if (requestVersion == homeHistoryRequestVersion) {
                    _historyStateInternal.value = buildHomeHistorySuccess(range, portfolioKey, availableRawPoints)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!hasCompleteCachedPoints && requestVersion == homeHistoryRequestVersion) {
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

    private fun buildPortfolioKey(stocks: List<Stock>, mode: String, accountId: Int): String {
        val codes = stocks
            .map { "${StockMarket.normalize(it.market)}:${it.code}" }
            .sorted()
        return "$accountId|${HomeDisplayMode.normalize(mode)}|${codes.joinToString(",")}"
    }

    private fun calculateHistoricalHoldingStatsAt(
        ptPrice: Double,
        replay: LongPositionReplaySummary,
        preDeductSellFees: Boolean,
        feeDiscount: Double,
        minFeeRegular: Double,
        minFeeOddLot: Double,
        market: String,
        stockType: String,
        marginSummary: MarginSummary,
        shortSummary: ShortSellingSummary,
        shortIncome: Double,
        shortCoverExpense: Double,
        hasMarginPurchase: Boolean
    ): HistoricalHoldingStats {
        val shares = replay.shares
        val totalBuyExpense = replay.totalBuyExpense
        val totalSellIncome = replay.totalSellIncome
        val totalSellNetIncome = replay.totalSellNetIncome
        val sellAmountBeforeFee = replay.sellAmountBeforeFee
        val totalDividendIncome = replay.totalDividendIncome
        val costBasis = totalBuyExpense - totalSellIncome - totalDividendIncome
        val totalSellFeeAndTax = (sellAmountBeforeFee - totalSellNetIncome).coerceAtLeast(0.0)
        val longInvestment = if (hasMarginPurchase) {
            marginSummary.selfFundedCapital + totalSellFeeAndTax
        } else {
            totalBuyExpense + totalSellFeeAndTax
        }
        val investmentBasis = HoldingCalculationSupport.positionInvestmentBasis(
            shares = shares,
            costBasis = costBasis,
            longInvestment = longInvestment,
            financedRemainingInvestment = if (hasMarginPurchase) {
                (-marginSummary.cashBalance).coerceAtLeast(0.0)
            } else {
                null
            },
            marginDebt = marginSummary.outstandingPrincipal + marginSummary.accruedInterest,
            shortOutstandingShares = shortSummary.outstandingShares,
            shortRemainingInvestment = shortSummary.openedPrincipal,
            shortCumulativeInvestment = shortSummary.cumulativeOpenedPrincipal
        )
        val marketValue = shares * ptPrice
        var totalPL = marketValue - costBasis
        totalPL -= marginSummary.totalInterestExpense
        totalPL += shortIncome - shortCoverExpense - shortSummary.outstandingShares * ptPrice - shortSummary.accruedBorrowFee - shortSummary.compensationExpense

        if (preDeductSellFees && marketValue > 0.0 && StockMarket.isTw(market)) {
            val minimumFee = TransactionCostSupport.minimumTaiwanSellFee(
                shares = shares,
                minFeeRegular = minFeeRegular,
                minFeeOddLot = minFeeOddLot
            )
            val sellFee = (marketValue * 0.001425 * feeDiscount).coerceAtLeast(minimumFee)
            val taxRate = if (stockType == "ETF") 0.001 else 0.003
            val sellTax = marketValue * taxRate
            totalPL -= (sellFee + sellTax)
        }

        return HistoricalHoldingStats(
            shares = shares,
            totalInvestment = investmentBasis.cumulative,
            remainingPositionInvestment = investmentBasis.remaining,
            marketValue = marketValue,
            totalPL = totalPL,
            marginSummary = marginSummary,
            shortSummary = shortSummary
        )
    }

    private fun buildHistoricalCashFlows(
        transactions: List<StockTransaction>,
        shares: Double,
        price: Double,
        terminalDateMillis: Long,
        transactionCutoffMillis: Long,
        currencyRate: Double,
        marginSummary: MarginSummary,
        shortSummary: ShortSellingSummary,
        historicalCashFlowTimeline: HistoricalTransactionCashFlowTimeline?,
        historicalShortXirrTimeline: ShortSellingCalculationSupport.HistoricalXirrTimeline?
    ): List<CashFlow> {
        val cashFlows = historicalCashFlowTimeline
            ?.cashFlowsAt(transactionCutoffMillis)
            ?.toMutableList()
            ?: transactions.mapNotNull { transaction ->
                when (transaction.type) {
                    "買進" -> CashFlow(transaction.date, -transaction.expense * currencyRate)
                    "融資買進" -> CashFlow(transaction.date, -(if (transaction.marginSelfFundedOverridden) transaction.marginSelfFunded else transaction.expense - transaction.marginPrincipal) * currencyRate)
                    "賣出" -> CashFlow(transaction.date, (transaction.income - transaction.marginRepayment - transaction.marginActualInterest) * currencyRate)
                    "融資還款" -> CashFlow(transaction.date, -(transaction.marginRepayment + transaction.marginActualInterest) * currencyRate)
                    "配息" -> CashFlow(
                        transaction.date,
                        HoldingCalculationSupport.resolveDividendIncome(transaction) * currencyRate
                    )
                    "減資" -> CashFlow(transaction.date, transaction.cashReturned * currencyRate)
                    else -> null
                }
            }.toMutableList()

        historicalShortXirrTimeline?.cashFlowsAt(
            valuationDate = transactionCutoffMillis,
            currentPrice = price,
            shortSummary = shortSummary,
            terminalDate = terminalDateMillis
        )?.let { shortCashFlows ->
            cashFlows += shortCashFlows.map { it.copy(amount = it.amount * currencyRate) }
        }

        val hasValuedPosition = shares > 0.0 && price > 0.0
        val hasMarginDebt = marginSummary.outstandingPrincipal > 0.0 || marginSummary.accruedInterest > 0.0
        if (hasValuedPosition || hasMarginDebt) {
            cashFlows.add(CashFlow(terminalDateMillis, (shares * price - marginSummary.outstandingPrincipal - marginSummary.accruedInterest) * currencyRate))
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
            fetchPortfolioHistory(
                selectedHomeHistoryRange.value,
                forceRefreshCurrentMonth = true
            )
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
