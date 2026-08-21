package com.rsps1008.stockify.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rsps1008.stockify.data.RealtimeStockDataService
import com.rsps1008.stockify.data.StockDao
import com.rsps1008.stockify.data.StockRepository
import com.rsps1008.stockify.data.StockMarket
import com.rsps1008.stockify.data.TwseStockHistoryService
import com.rsps1008.stockify.data.SettingsDataStore
import com.rsps1008.stockify.data.ReturnRateMode
import com.rsps1008.stockify.data.StockTransaction
import com.rsps1008.stockify.data.StockHistoryPoint
import com.rsps1008.stockify.data.Stock
import com.rsps1008.stockify.data.CashFlow
import com.rsps1008.stockify.data.HoldingCalculationSupport
import com.rsps1008.stockify.data.HistoricalLongPositionTimeline
import com.rsps1008.stockify.data.LongPositionReplaySummary
import com.rsps1008.stockify.data.MarginCalculationSupport
import com.rsps1008.stockify.data.MarginSummary
import com.rsps1008.stockify.data.ReturnRateCalculator
import com.rsps1008.stockify.data.ShortSellingCalculationSupport
import com.rsps1008.stockify.data.ShortSellingSummary
import com.rsps1008.stockify.data.HistoryChartCalculationSupport
import com.rsps1008.stockify.data.TransactionCostSupport
import com.rsps1008.stockify.ui.screens.HoldingInfo
import com.rsps1008.stockify.ui.screens.TransactionUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class HistoryRange {
    ONE_MONTH, SIX_MONTHS, ONE_YEAR
}

@kotlinx.serialization.Serializable
data class PersonalHistoryPoint(
    val date: String,          // "YYYY-MM-DD"
    val price: Double,         // Stock price
    val shares: Double,        // Shares held at that date
    val marketValue: Double,   // Shares * Price
    val totalPL: Double,       // Market value - Cost basis
    val totalPLPercentage: Double // Return percentage
)

sealed interface HistoryState {
    object Idle : HistoryState
    data class Loading(val progress: Float, val statusText: String) : HistoryState
    data class Success(val range: HistoryRange, val points: List<PersonalHistoryPoint>) : HistoryState
    data class Empty(val range: HistoryRange, val message: String) : HistoryState
    data class Error(val message: String) : HistoryState
}

sealed interface DetailHistoryStateInternal {
    object Idle : DetailHistoryStateInternal
    data class Loading(val progress: Float, val statusText: String) : DetailHistoryStateInternal
    data class Success(val range: HistoryRange, val rawPoints: List<StockHistoryPoint>) : DetailHistoryStateInternal
    data class Error(val message: String) : DetailHistoryStateInternal
}

sealed interface DeleteTransactionsScope {
    object AllAccounts : DeleteTransactionsScope
    data class ActiveAccount(val accountId: Int) : DeleteTransactionsScope
}

sealed interface DeleteTransactionsState {
    object Hidden : DeleteTransactionsState
    data class Confirming(val scope: DeleteTransactionsScope) : DeleteTransactionsState
    data class Deleting(val scope: DeleteTransactionsScope) : DeleteTransactionsState
    data class Error(val scope: DeleteTransactionsScope, val message: String) : DeleteTransactionsState
    object Success : DeleteTransactionsState
}

private data class DetailSettingsBundle(
    val preDeductSellFees: Boolean,
    val feeDiscount: Double,
    val minFeeRegular: Int,
    val minFeeOddLot: Int,
    val returnRateMode: ReturnRateMode,
    val marginDayCount: Int
)

private data class HistoricalPointCalculationResult(
    val point: PersonalHistoryPoint,
    val xirrGuessRate: Double?
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StockDetailViewModel(
    private val stockCode: String,
    private val market: String = StockMarket.inferFromCode(stockCode),
    private val stockDao: StockDao,
    stockRepository: StockRepository,
    val realtimeStockDataService: RealtimeStockDataService,
    private val twseStockHistoryService: TwseStockHistoryService,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val normalizedMarket = StockMarket.normalize(market)

    val activeAccountId: StateFlow<Int> = settingsDataStore.activeAccountIdFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 0)

    val holdingInfo: StateFlow<HoldingInfo?> = activeAccountId.flatMapLatest { accountId ->
        stockRepository.getHoldingInfo(stockCode, accountId, normalizedMarket)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val transactions: StateFlow<List<TransactionUiState>> = activeAccountId.flatMapLatest { accountId ->
        stockRepository.getTransactionsForStock(stockCode, accountId, normalizedMarket)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val historyStock: StateFlow<Stock?> = stockDao.getStockByCodeFlow(stockCode, normalizedMarket)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val realtimeStockInfo = realtimeStockDataService.realtimeStockInfo

    private val _deleteTransactionsState = MutableStateFlow<DeleteTransactionsState>(DeleteTransactionsState.Hidden)
    val deleteTransactionsState: StateFlow<DeleteTransactionsState> = _deleteTransactionsState.asStateFlow()

    private val _historyStateInternal = MutableStateFlow<DetailHistoryStateInternal>(DetailHistoryStateInternal.Idle)
    private var historyFetchJob: Job? = null
    private var historyRequestVersion = 0L

    val detailHistoryChartExpanded: StateFlow<Boolean> = settingsDataStore.detailHistoryChartExpandedFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), true)

    private val baseSettingsCombined = combine(
        settingsDataStore.preDeductSellFeesFlow,
        settingsDataStore.feeDiscountFlow,
        settingsDataStore.minFeeRegularFlow,
        settingsDataStore.returnRateModeFlow,
        settingsDataStore.marginDayCountFlow
    ) { preDeduct, discount, minFeeRegular, mode, marginDayCount ->
        DetailSettingsBundle(preDeduct, discount, minFeeRegular, 0, mode, marginDayCount)
    }

    private val settingsCombined = baseSettingsCombined.combine(
        settingsDataStore.minFeeOddLotFlow
    ) { settings, minFeeOddLot ->
        settings.copy(minFeeOddLot = minFeeOddLot)
    }

    val historyState: StateFlow<HistoryState> = combine(
        _historyStateInternal,
        transactions,
        settingsCombined,
        historyStock
    ) { historyInternal, txList, settings, stock ->
        if (historyInternal is DetailHistoryStateInternal.Success) {
            val stockTransactions = txList.map { it.transaction }
                .sortedWith(compareBy<StockTransaction> { it.date }.thenBy { it.recordTime }.thenBy { it.id })
            if (stockTransactions.isEmpty()) {
                return@combine HistoryState.Empty(
                    range = historyInternal.range,
                    message = "此股票在所選期間沒有可用的歷史股價。"
                )
            }
            val firstTxTime = stockTransactions.minOfOrNull { it.date }
            val minFeeRegular = settings.minFeeRegular.toDouble()
            val minFeeOddLot = settings.minFeeOddLot.toDouble()

            val personalPoints = mutableListOf<PersonalHistoryPoint>()
            val stockType = stock?.stockType ?: ""
            val market = stock?.market ?: StockMarket.inferFromCode(stockCode)

            val rawPoints = historyInternal.rawPoints.sortedBy { it.date }
            val timeline = HistoricalLongPositionTimeline(stockTransactions, transactionsAreOrdered = true)
            var previousXirrGuessRate: Double? = null

            for (pt in rawPoints) {
                val dayEnd = HistoryChartCalculationSupport.valuationDateEndMillis(pt.date, market)
                    ?: continue

                if (firstTxTime != null && dayEnd < firstTxTime) {
                    continue
                }

                // The points are chronological, so each transaction is applied once
                // and only the already-replayed prefix is passed to later calculations.
                val replay = timeline.advanceTo(dayEnd)
                val result = calculateHistoricalHoldingAt(
                    ptDateStr = pt.date,
                    ptPrice = pt.price,
                    transactions = timeline.transactionsAtCurrentDate(),
                    replay = replay,
                    preDeductSellFees = settings.preDeductSellFees,
                    returnRateMode = settings.returnRateMode,
                    feeDiscount = settings.feeDiscount,
                    minFeeRegular = minFeeRegular,
                    minFeeOddLot = minFeeOddLot,
                    market = market,
                    stockType = stockType,
                    dayEnd = dayEnd,
                    marginDayCount = settings.marginDayCount,
                    xirrGuessRate = previousXirrGuessRate,
                    hasMarginActivity = timeline.hasMarginActivity,
                    hasShortActivity = timeline.hasShortActivity,
                    shortIncome = timeline.shortIncome,
                    shortCoverExpense = timeline.shortCoverExpense,
                    hasMarginPurchase = timeline.hasMarginPurchase
                )
                personalPoints.add(result.point)
                previousXirrGuessRate = result.xirrGuessRate ?: previousXirrGuessRate
            }

            if (personalPoints.isEmpty()) {
                HistoryState.Empty(
                    range = historyInternal.range,
                    message = "此股票在所選期間沒有可用的歷史股價。"
                )
            } else {
                HistoryState.Success(historyInternal.range, personalPoints)
            }
        } else {
            when (historyInternal) {
                is DetailHistoryStateInternal.Idle -> HistoryState.Idle
                is DetailHistoryStateInternal.Loading -> HistoryState.Loading(historyInternal.progress, historyInternal.statusText)
                is DetailHistoryStateInternal.Error -> HistoryState.Error(historyInternal.message)
                else -> HistoryState.Idle
            }
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), HistoryState.Idle)

    init {
        if (StockMarket.isTw(normalizedMarket) || normalizedMarket == StockMarket.US) {
            fetchStockHistory(HistoryRange.ONE_MONTH)
        }
    }

    fun fetchStockHistory(range: HistoryRange) {
        historyFetchJob?.cancel()
        val requestVersion = ++historyRequestVersion
        historyFetchJob = viewModelScope.launch {
            fun isCurrentRequest(): Boolean = requestVersion == historyRequestVersion

            val rangeMonths = when (range) {
                HistoryRange.ONE_MONTH -> 1
                HistoryRange.SIX_MONTHS -> 6
                HistoryRange.ONE_YEAR -> 12
            }
            val market = historyStock.value?.market ?: normalizedMarket

            val cachedPoints = twseStockHistoryService.getCachedHistory(stockCode, rangeMonths, normalizedMarket)
            val hasCompleteCachedHistory = HistoryChartCalculationSupport.hasHistoryCoverage(cachedPoints, rangeMonths)
            if (!isCurrentRequest()) return@launch

            if (hasCompleteCachedHistory) {
                _historyStateInternal.value = DetailHistoryStateInternal.Success(range, cachedPoints)
            } else {
                _historyStateInternal.value = DetailHistoryStateInternal.Loading(0f, "準備下載歷史股價...")
            }

            try {
                val rawPoints = twseStockHistoryService.fetchHistory(stockCode, rangeMonths, normalizedMarket) { step, total ->
                    val progress = step.toFloat() / total.toFloat()
                    val statusText = if (StockMarket.isUs(market)) {
                        "正在載入美股歷史股價..."
                    } else {
                        "正在載入第 $step/$total 個月..."
                    }
                    if (!hasCompleteCachedHistory && isCurrentRequest()) {
                        _historyStateInternal.value = DetailHistoryStateInternal.Loading(progress, statusText)
                    }
                }

                if (!isCurrentRequest()) return@launch

                if (rawPoints.isEmpty()) {
                    if (!hasCompleteCachedHistory) {
                        _historyStateInternal.value = DetailHistoryStateInternal.Error("歷史股價回傳資料為空，請稍後重試。")
                    }
                    return@launch
                }

                _historyStateInternal.value = DetailHistoryStateInternal.Success(range, rawPoints)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!hasCompleteCachedHistory && isCurrentRequest()) {
                    _historyStateInternal.value = DetailHistoryStateInternal.Error("載入失敗: ${e.localizedMessage}")
                }
            }
        }
    }

    private fun calculateHistoricalHoldingAt(
        ptDateStr: String,
        ptPrice: Double,
        transactions: List<StockTransaction>,
        replay: LongPositionReplaySummary,
        preDeductSellFees: Boolean,
        returnRateMode: ReturnRateMode,
        feeDiscount: Double,
        minFeeRegular: Double,
        minFeeOddLot: Double,
        market: String,
        stockType: String,
        dayEnd: Long,
        marginDayCount: Int,
        xirrGuessRate: Double?,
        hasMarginActivity: Boolean,
        hasShortActivity: Boolean,
        shortIncome: Double,
        shortCoverExpense: Double,
        hasMarginPurchase: Boolean
    ): HistoricalPointCalculationResult {
        val txs = transactions
        val shares = replay.shares
        val totalBuyExpense = replay.totalBuyExpense
        val totalSellIncome = replay.totalSellIncome
        val totalSellNetIncome = replay.totalSellNetIncome
        val sellAmountBeforeFee = replay.sellAmountBeforeFee
        val totalDividendIncome = replay.totalDividendIncome
        val costBasis = totalBuyExpense - totalSellIncome - totalDividendIncome
        val totalSellFeeAndTax = (sellAmountBeforeFee - totalSellNetIncome).coerceAtLeast(0.0)
        val marginSummary = if (hasMarginActivity) {
            MarginCalculationSupport.calculate(
                transactions = txs,
                valuationDate = dayEnd,
                dayCount = marginDayCount,
                transactionsAreOrdered = true
            )
        } else {
            MarginSummary()
        }
        val shortSummary = if (hasShortActivity) {
            ShortSellingCalculationSupport.calculate(
                transactions = txs,
                valuationDate = dayEnd,
                dayCount = marginDayCount,
                transactionsAreOrdered = true
            )
        } else {
            ShortSellingSummary()
        }
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

        var nextXirrGuessRate: Double? = null
        val totalPLPercentage = when (returnRateMode) {
            ReturnRateMode.REMAINING_POSITION -> {
                val denominator = investmentBasis.remaining
                if (denominator > 0) (totalPL / denominator) * 100 else 0.0
            }
            ReturnRateMode.CUMULATIVE_INVESTMENT -> {
                val denominator = investmentBasis.cumulative
                if (denominator > 0) (totalPL / denominator) * 100 else 0.0
            }
            ReturnRateMode.XIRR -> {
                val xirrRate = ReturnRateCalculator.calculateXirrRate(
                    cashFlows = buildHistoricalCashFlows(
                        transactions = txs,
                        shares = shares,
                        price = ptPrice,
                        terminalDateMillis = dayEnd,
                        marginDayCount = marginDayCount,
                        marginSummary = marginSummary,
                        shortSummary = shortSummary
                    ),
                    guess = xirrGuessRate ?: 0.1
                )
                nextXirrGuessRate = xirrRate
                xirrRate?.times(100.0) ?: 0.0
            }
        }

        return HistoricalPointCalculationResult(
            point = PersonalHistoryPoint(
                date = ptDateStr,
                price = ptPrice,
                shares = shares,
                marketValue = marketValue,
                totalPL = totalPL,
                totalPLPercentage = totalPLPercentage
            ),
            xirrGuessRate = nextXirrGuessRate
        )
    }

    private fun buildHistoricalCashFlows(
        transactions: List<StockTransaction>,
        shares: Double,
        price: Double,
        terminalDateMillis: Long,
        marginDayCount: Int,
        marginSummary: MarginSummary,
        shortSummary: ShortSellingSummary
    ): List<CashFlow> {
        val cashFlows = transactions.mapNotNull { transaction ->
            when (transaction.type) {
                "買進" -> CashFlow(transaction.date, -transaction.expense)
                "融資買進" -> CashFlow(
                    transaction.date,
                    -(if (transaction.marginSelfFundedOverridden) transaction.marginSelfFunded else transaction.expense - transaction.marginPrincipal)
                )
                "賣出" -> CashFlow(
                    transaction.date,
                    transaction.income - transaction.marginRepayment - transaction.marginActualInterest
                )
                "融資還款" -> CashFlow(
                    transaction.date,
                    -(transaction.marginRepayment + transaction.marginActualInterest)
                )
                "配息" -> CashFlow(
                    transaction.date,
                    HoldingCalculationSupport.resolveDividendIncome(transaction)
                )
                "減資" -> CashFlow(transaction.date, transaction.cashReturned)
                else -> null
            }
        }.toMutableList()

        cashFlows += ShortSellingCalculationSupport.buildXirrCashFlows(
            transactions = transactions,
            valuationDate = terminalDateMillis,
            currentPrice = price,
            dayCount = marginDayCount,
            shortSummary = shortSummary,
            transactionsAreOrdered = true
        )

        val hasValuedPosition = shares > 0.0 && price > 0.0
        val hasMarginDebt = marginSummary.outstandingPrincipal > 0.0 || marginSummary.accruedInterest > 0.0
        if (hasValuedPosition || hasMarginDebt) {
            cashFlows.add(
                CashFlow(
                    terminalDateMillis,
                    shares * price - marginSummary.outstandingPrincipal - marginSummary.accruedInterest
                )
            )
        }

        return cashFlows
    }

    fun onDeleteTransactionsClicked() {
        val scope = activeAccountId.value.takeIf { it != 0 }
            ?.let(DeleteTransactionsScope::ActiveAccount)
            ?: DeleteTransactionsScope.AllAccounts
        _deleteTransactionsState.value = DeleteTransactionsState.Confirming(scope)
    }

    fun onDeleteTransactionsConfirmed() {
        val state = _deleteTransactionsState.value
        val scope = when (state) {
            is DeleteTransactionsState.Confirming -> state.scope
            is DeleteTransactionsState.Error -> state.scope
            else -> return
        }

        _deleteTransactionsState.value = DeleteTransactionsState.Deleting(scope)
        viewModelScope.launch {
            try {
                when (scope) {
                    DeleteTransactionsScope.AllAccounts -> {
                        stockDao.deleteTransactionsByStockCode(stockCode, normalizedMarket)
                    }
                    is DeleteTransactionsScope.ActiveAccount -> {
                        stockDao.deleteTransactionsByStockCodeAndAccountId(stockCode, normalizedMarket, scope.accountId)
                    }
                }
                _deleteTransactionsState.value = DeleteTransactionsState.Success
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _deleteTransactionsState.value = DeleteTransactionsState.Error(
                    scope = scope,
                    message = "刪除交易失敗，請稍後再試。"
                )
            }
        }
    }

    fun onDeleteTransactionsCancelled() {
        if (_deleteTransactionsState.value !is DeleteTransactionsState.Deleting) {
            _deleteTransactionsState.value = DeleteTransactionsState.Hidden
        }
    }

    fun setDetailHistoryChartExpanded(expanded: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setDetailHistoryChartExpanded(expanded)
        }
    }
}
