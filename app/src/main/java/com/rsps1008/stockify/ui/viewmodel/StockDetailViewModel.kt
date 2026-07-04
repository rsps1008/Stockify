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
import com.rsps1008.stockify.data.CashFlow
import com.rsps1008.stockify.data.ReturnRateCalculator
import com.rsps1008.stockify.ui.screens.HoldingInfo
import com.rsps1008.stockify.ui.screens.TransactionUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    data class Error(val message: String) : HistoryState
}

sealed interface DetailHistoryStateInternal {
    object Idle : DetailHistoryStateInternal
    data class Loading(val progress: Float, val statusText: String) : DetailHistoryStateInternal
    data class Success(val range: HistoryRange, val rawPoints: List<StockHistoryPoint>) : DetailHistoryStateInternal
    data class Error(val message: String) : DetailHistoryStateInternal
}

private data class DetailSettingsBundle(
    val preDeductSellFees: Boolean,
    val feeDiscount: Double,
    val minFeeRegular: Int,
    val returnRateMode: ReturnRateMode
)

private data class HistoricalPointCalculationResult(
    val point: PersonalHistoryPoint,
    val xirrGuessRate: Double?
)

class StockDetailViewModel(
    private val stockCode: String,
    private val stockDao: StockDao,
    stockRepository: StockRepository,
    val realtimeStockDataService: RealtimeStockDataService,
    private val twseStockHistoryService: TwseStockHistoryService,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    val holdingInfo: StateFlow<HoldingInfo?> = stockRepository.getHoldingInfo(stockCode)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val transactions: StateFlow<List<TransactionUiState>> = stockRepository.getTransactionsForStock(stockCode)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val realtimeStockInfo = realtimeStockDataService.realtimeStockInfo

    private val _showDeleteConfirmDialog = MutableStateFlow(false)
    val showDeleteConfirmDialog: StateFlow<Boolean> = _showDeleteConfirmDialog.asStateFlow()

    private val _historyStateInternal = MutableStateFlow<DetailHistoryStateInternal>(DetailHistoryStateInternal.Idle)

    val detailHistoryChartExpanded: StateFlow<Boolean> = settingsDataStore.detailHistoryChartExpandedFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), true)

    private val settingsCombined = combine(
        settingsDataStore.preDeductSellFeesFlow,
        settingsDataStore.feeDiscountFlow,
        settingsDataStore.minFeeRegularFlow,
        settingsDataStore.returnRateModeFlow
    ) { preDeduct, discount, minFee, mode ->
        DetailSettingsBundle(preDeduct, discount, minFee, mode)
    }

    val historyState: StateFlow<HistoryState> = combine(
        _historyStateInternal,
        transactions,
        settingsCombined,
        holdingInfo
    ) { historyInternal, txList, settings, holding ->
        if (historyInternal is DetailHistoryStateInternal.Success) {
            val stockTransactions = txList.map { it.transaction }.sortedBy { it.date }
            val firstTxTime = stockTransactions.minOfOrNull { it.date }
            val minFee = settings.minFeeRegular.toDouble()

            val personalPoints = mutableListOf<PersonalHistoryPoint>()
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).apply {
                timeZone = java.util.TimeZone.getTimeZone("Asia/Taipei")
            }
            val stockType = holding?.stock?.stockType ?: ""
            val market = holding?.stock?.market ?: StockMarket.inferFromCode(stockCode)

            val rawPoints = historyInternal.rawPoints.toMutableList()
            var previousXirrGuessRate: Double? = null

            for (pt in rawPoints) {
                val dayStart = sdf.parse(pt.date)?.time ?: 0L
                val dayEnd = dayStart + 24 * 60 * 60 * 1000L - 1L

                if (firstTxTime != null && dayEnd < firstTxTime) {
                    continue
                }

                val result = calculateHistoricalHoldingAt(
                    ptDateStr = pt.date,
                    ptPrice = pt.price,
                    transactions = stockTransactions,
                    preDeductSellFees = settings.preDeductSellFees,
                    returnRateMode = settings.returnRateMode,
                    feeDiscount = settings.feeDiscount,
                    minFeeRegular = minFee,
                    market = market,
                    stockType = stockType,
                    dayEnd = dayEnd,
                    xirrGuessRate = previousXirrGuessRate
                )
                personalPoints.add(result.point)
                previousXirrGuessRate = result.xirrGuessRate ?: previousXirrGuessRate
            }

            if (personalPoints.isEmpty()) {
                HistoryState.Success(
                    historyInternal.range,
                    rawPoints.map {
                        PersonalHistoryPoint(it.date, it.price, 0.0, 0.0, 0.0, 0.0)
                    }
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
        viewModelScope.launch {
            val market = StockMarket.inferFromCode(stockCode)
            if (StockMarket.isTw(market) || market == StockMarket.US) {
                fetchStockHistory(HistoryRange.ONE_MONTH)
            }
        }
    }

    fun fetchStockHistory(range: HistoryRange) {
        viewModelScope.launch {
            val rangeMonths = when (range) {
                HistoryRange.ONE_MONTH -> 1
                HistoryRange.SIX_MONTHS -> 6
                HistoryRange.ONE_YEAR -> 12
            }
            val market = holdingInfo.value?.stock?.market ?: StockMarket.inferFromCode(stockCode)

            val cachedPoints = twseStockHistoryService.getCachedHistory(stockCode, rangeMonths)
            val hasCachedPoints = cachedPoints.isNotEmpty()
            if (hasCachedPoints) {
                _historyStateInternal.value = DetailHistoryStateInternal.Success(range, cachedPoints)
            } else {
                _historyStateInternal.value = DetailHistoryStateInternal.Loading(0f, "準備下載歷史股價...")
            }

            try {
                val rawPoints = twseStockHistoryService.fetchHistory(stockCode, rangeMonths) { step, total ->
                    val progress = step.toFloat() / total.toFloat()
                    val statusText = if (StockMarket.isUs(market)) {
                        "正在載入美股歷史股價..."
                    } else {
                        "正在載入第 $step/$total 個月..."
                    }
                    if (!hasCachedPoints) {
                        _historyStateInternal.value = DetailHistoryStateInternal.Loading(progress, statusText)
                    }
                }
                
                if (rawPoints.isEmpty()) {
                    if (!hasCachedPoints) {
                        _historyStateInternal.value = DetailHistoryStateInternal.Error("歷史股價回傳資料為空，請稍後重試。")
                    }
                    return@launch
                }

                _historyStateInternal.value = DetailHistoryStateInternal.Success(range, rawPoints)
            } catch (e: Exception) {
                if (!hasCachedPoints) {
                    _historyStateInternal.value = DetailHistoryStateInternal.Error("載入失敗: ${e.localizedMessage}")
                }
            }
        }
    }

    private fun adjustTransactionsForSplits(transactions: List<StockTransaction>): List<StockTransaction> {
        val chronologicallySorted = transactions.sortedBy { it.date }
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

    private fun calculateHistoricalHoldingAt(
        ptDateStr: String,
        ptPrice: Double,
        transactions: List<StockTransaction>,
        preDeductSellFees: Boolean,
        returnRateMode: ReturnRateMode,
        feeDiscount: Double,
        minFeeRegular: Double,
        market: String,
        stockType: String,
        dayEnd: Long,
        xirrGuessRate: Double?
    ): HistoricalPointCalculationResult {
        val txs = transactions.filter { it.date <= dayEnd }

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
                "分割" -> {
                    shares += it.sharesAfterSplit - it.sharesBeforeSplit
                }
            }
        }

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

        var nextXirrGuessRate: Double? = null
        val totalPLPercentage = when (returnRateMode) {
            ReturnRateMode.REMAINING_POSITION -> {
                val denominator = if (shares > 0.0) costBasis else totalInvestment
                if (denominator > 0) (totalPL / denominator) * 100 else 0.0
            }
            ReturnRateMode.CUMULATIVE_INVESTMENT -> if (totalInvestment > 0) (totalPL / totalInvestment) * 100 else 0.0
            ReturnRateMode.XIRR -> {
                val xirrRate = ReturnRateCalculator.calculateXirrRate(
                    cashFlows = buildHistoricalCashFlows(txs, shares, ptPrice, dayEnd),
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
        terminalDateMillis: Long
    ): List<CashFlow> {
        val cashFlows = transactions.mapNotNull { transaction ->
            when (transaction.type) {
                "買進" -> CashFlow(transaction.date, -transaction.expense)
                "賣出" -> CashFlow(transaction.date, transaction.income)
                "配息" -> CashFlow(transaction.date, transaction.dividendIncome)
                "減資" -> CashFlow(transaction.date, transaction.cashReturned)
                else -> null
            }
        }.toMutableList()

        if (shares > 0.0 && price > 0.0) {
            cashFlows.add(CashFlow(terminalDateMillis, shares * price))
        }

        return cashFlows
    }

    fun onDeleteTransactionsClicked() {
        _showDeleteConfirmDialog.value = true
    }

    fun onDeleteTransactionsConfirmed() {
        viewModelScope.launch {
            stockDao.deleteTransactionsByStockCode(stockCode)
        }
        _showDeleteConfirmDialog.value = false
    }

    fun onDeleteTransactionsCancelled() {
        _showDeleteConfirmDialog.value = false
    }

    fun setDetailHistoryChartExpanded(expanded: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setDetailHistoryChartExpanded(expanded)
        }
    }
}
