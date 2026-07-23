package com.rsps1008.stockify.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rsps1008.stockify.data.RealtimeStockDataService
import com.rsps1008.stockify.data.CalculationRoundingMode
import com.rsps1008.stockify.data.SettingsDataStore
import com.rsps1008.stockify.data.Stock
import com.rsps1008.stockify.data.StockDao
import com.rsps1008.stockify.data.StockMarket
import com.rsps1008.stockify.data.StockTransaction
import com.rsps1008.stockify.data.Account
import com.rsps1008.stockify.data.dividend.YahooDividendRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.max
import java.util.UUID

data class MarginLotOption(
    val lotId: String,
    val label: String,
    val remainingPrincipal: Double
)

data class ShortLotOption(val lotId: String, val label: String, val remainingShares: Double)

class AddTransactionViewModel(
    private val stockDao: StockDao,
    private val settingsDataStore: SettingsDataStore,
    private val transactionId: Int?,
    private val realtimeStockDataService: RealtimeStockDataService,
    private val dividendRepository: YahooDividendRepository
) : ViewModel() {
    val taxRateNormalListedStock: StateFlow<Double> =
        settingsDataStore.taxRateNormalListedStockFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 0.003)

    val taxRateDomesticStockEtf: StateFlow<Double> =
        settingsDataStore.taxRateDomesticStockEtfFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 0.001)

    val taxRateBondEtf: StateFlow<Double> =
        settingsDataStore.taxRateBondEtfFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 0.0)

    val taxRateDayTrading: StateFlow<Double> =
        settingsDataStore.taxRateDayTradingFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 0.0015)

    private val _transactionToEdit = MutableStateFlow<StockTransaction?>(null)
    val transactionToEdit = _transactionToEdit.asStateFlow()

    private val _fee = MutableStateFlow(0.0)
    val fee = _fee.asStateFlow()

    private val _tax = MutableStateFlow(0.0)
    val tax = _tax.asStateFlow()

    private val _taxRate = MutableStateFlow(0.0)
    val taxRate = _taxRate.asStateFlow()

    private val _expense = MutableStateFlow(0.0)
    val expense = _expense.asStateFlow()

    private val _income = MutableStateFlow(0.0)
    val income = _income.asStateFlow()

    val calculationRoundingMode: StateFlow<String> = settingsDataStore.calculationRoundingModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), CalculationRoundingMode.ROUND)

    val defaultDividendFee: StateFlow<Int> = settingsDataStore.dividendFeeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 10)

    val marginFeatureEnabled: StateFlow<Boolean> = settingsDataStore.marginFeatureEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), false)
    val marginDayCount: StateFlow<Int> = settingsDataStore.marginDayCountFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 365)
    private val _marginLots = MutableStateFlow<List<MarginLotOption>>(emptyList())
    val marginLots: StateFlow<List<MarginLotOption>> = _marginLots.asStateFlow()
    // 融資與融券共用同一個實驗功能開關。
    val shortSellingFeatureEnabled: StateFlow<Boolean> = marginFeatureEnabled
    private val _shortLots = MutableStateFlow<List<ShortLotOption>>(emptyList())
    val shortLots: StateFlow<List<ShortLotOption>> = _shortLots.asStateFlow()

    val accounts: StateFlow<List<Account>> = stockDao.getAllAccountsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val _selectedAccountId = MutableStateFlow(1)
    val selectedAccountId = _selectedAccountId.asStateFlow()

    fun selectAccount(accountId: Int) {
        _selectedAccountId.value = accountId
    }

    fun loadMarginLots(stockCode: String) {
        viewModelScope.launch {
            val transactions = stockDao.getTransactionsForStock(stockCode).firstOrNull().orEmpty()
                .filter { it.accountId == _selectedAccountId.value }
            val summary = com.rsps1008.stockify.data.MarginCalculationSupport.calculate(
                transactions, System.currentTimeMillis(), marginDayCount.value
            )
            _marginLots.value = summary.lots.map {
                MarginLotOption(
                    lotId = it.lotId,
                    label = "${java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.getDefault()).format(java.util.Date(it.openedAt))} / ${it.annualRate}% / 剩餘 ${it.remainingPrincipal}",
                    remainingPrincipal = it.remainingPrincipal
                )
            }
        }
    }

    fun loadShortLots(stockCode: String) {
        viewModelScope.launch {
            val transactions = stockDao.getTransactionsForStock(stockCode).firstOrNull().orEmpty().filter { it.accountId == _selectedAccountId.value }
            _shortLots.value = com.rsps1008.stockify.data.ShortSellingCalculationSupport
                .calculate(transactions, System.currentTimeMillis(), marginDayCount.value).lots.map {
                    ShortLotOption(it.lotId, "${java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.getDefault()).format(java.util.Date(it.openedAt))} / ${it.annualRate}% / 剩餘 ${it.remainingShares} 股", it.remainingShares)
                }
        }
    }

    init {
        viewModelScope.launch {
            val activeId = settingsDataStore.activeAccountIdFlow.firstOrNull() ?: 0
            _selectedAccountId.value = if (activeId == 0) 1 else activeId

            transactionId?.let { txId ->
                val transaction = stockDao.getTransactionById(txId).firstOrNull()
                _transactionToEdit.value = transaction
                transaction?.let { tx ->
                    _fee.value = tx.fee
                    _tax.value = tx.tax
                    _expense.value = tx.expense
                    _income.value = tx.income
                    _selectedAccountId.value = tx.accountId
                }
            }
        }
    }

    //除息
    fun autoFillDividendCashFromYahooUsingHolding(
        stockCode: String,
        onResult: (cashDividend: Double, holdingShares: Double, dateStr: String?) -> Unit,
        onFail: () -> Unit
    ) {
        viewModelScope.launch {
            try {
                val holdingShares = stockDao.getHoldingShares(stockCode)
                if (holdingShares <= 0) {
                    onFail()
                    return@launch
                }

                val result = dividendRepository.fetchLatestCashDividend(stockCode)
                if (result == null) {
                    onFail()
                    return@launch
                }

                onResult(result.amount, holdingShares, result.date)
            } catch (e: Exception) {
                onFail()
            }
        }
    }

    //除權
    fun autoFillDividendStockFromYahooUsingHolding(
        stockCode: String,
        onResult: (rate: Double, holdingShares: Double, dateStr: String?) -> Unit,
        onFail: () -> Unit
    ) {
        viewModelScope.launch {
            try {
                val holdingShares = stockDao.getHoldingShares(stockCode)
                if (holdingShares <= 0) {
                    onFail()
                    return@launch
                }

                val result = dividendRepository.fetchLatestStockDividend(stockCode)
                if (result == null) {
                    onFail()
                    return@launch
                }

                onResult(result.amount, holdingShares, result.date)
            } catch (e: Exception) {
                onFail()
            }
        }
    }


    val stocks: StateFlow<List<Stock>> = stockDao.getAllStocks()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    val feeSettings = combine(
        settingsDataStore.feeDiscountFlow,
        settingsDataStore.minFeeRegularFlow,
        settingsDataStore.minFeeOddLotFlow
    ) { discount, minRegular, minOdd ->
        Triple(discount, minRegular, minOdd)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), Triple(0.28, 1, 1))

    fun calculateBuyCosts(price: Double, shares: Double, market: String = StockMarket.TW) {
        if (price <= 0 || shares <= 0) {
            return
        }

        if (StockMarket.isUs(market)) {
            _expense.value = roundCalculatedCurrency(price * shares + _fee.value, market)
            return
        }

        val (discount, minFeeRegular, minFeeOddLot) = feeSettings.value
        val transactionValue = price * shares
        val calculatedFee = transactionValue * 0.001425 * discount
        val minFee = if (shares % 1000 == 0.0) minFeeRegular else minFeeOddLot
        val finalFee = roundCalculatedAmount(max(calculatedFee, minFee.toDouble()))

        _fee.value = finalFee
        _expense.value = roundCalculatedAmount(transactionValue + _fee.value)
    }


    fun calculateSellCosts(
        price: Double,
        shares: Double,
        market: String,
        stockType: String,
        isDayTrading: Boolean = false,
        isBondEtf: Boolean = false
    ) {
        if (price <= 0 || shares <= 0) return

        if (StockMarket.isUs(market)) {
            _taxRate.value = 0.0
            _income.value = roundCalculatedCurrency(price * shares - _fee.value - _tax.value, market)
            return
        }

        val (discount, minFeeRegular, minFeeOddLot) = feeSettings.value
        val transactionValue = price * shares

        val calculatedFee = transactionValue * 0.001425 * discount
        val minFee = if (shares % 1000 == 0.0) minFeeRegular else minFeeOddLot
        val autoFee = roundCalculatedAmount(max(calculatedFee, minFee.toDouble()))
        _fee.value = autoFee

        val taxRateValue = when {
            isDayTrading -> taxRateDayTrading.value
            isBondEtf -> taxRateBondEtf.value
            stockType == "ETF" -> taxRateDomesticStockEtf.value
            else -> taxRateNormalListedStock.value
        }

        _taxRate.value = taxRateValue
        val finalTax = roundCalculatedAmount(transactionValue * taxRateValue)
        _tax.value = finalTax

        _income.value = roundCalculatedAmount(transactionValue - _fee.value - finalTax)
    }

    suspend fun addOrUpdateTransaction(
        stockName: String,
        stockCode: String,
        date: Long,
        type: String,
        price: Double,
        shares: Double,
        cashDividend: Double = 0.0,
        exDividendShares: Double = 0.0,
        stockDividend: Double = 0.0,
        exRightsShares: Double = 0.0,
        dividendFee: Double = 0.0,
        note: String = "",
        capitalReductionRatio: Double = 0.0,
        sharesBeforeReduction: Double = 0.0,
        sharesAfterReduction: Double = 0.0,
        cashReturned: Double = 0.0,
        stockSplitRatio: Double = 0.0,
        sharesBeforeSplit: Double = 0.0,
        sharesAfterSplit: Double = 0.0,
        dividendIncome: Double? = null,
        marginPrincipal: Double = 0.0,
        marginAnnualRate: Double = 0.0,
        marginLotId: String = "",
        marginRepaymentLotId: String = "",
        marginRepayment: Double = 0.0,
        shortBorrowPrincipal: Double = 0.0,
        shortBorrowAnnualRate: Double = 0.0,
        shortLotId: String = "",
        shortCoverLotId: String = "",
        shortCoverShares: Double = 0.0,
        shortCompensationLotId: String = "",
        shortCompensation: Double = 0.0
    ) {
        val finalFee = when (type) {
            "配息" -> dividendFee
            else -> _fee.value
        }

        val finalIncome = when (type) {
            "賣出", "融券賣出" -> _income.value
            "配息" -> dividendIncome ?: (price - finalFee).coerceAtLeast(0.0)
            "減資" -> cashReturned
            else -> 0.0
        }

        val finalExpense = when (type) {
            "買進", "融資買進", "買券還券" -> _expense.value
            "融資還款" -> marginRepayment
            "融券補償" -> shortCompensation
            else -> 0.0
        }
        val finalTax = if (type == "賣出") _tax.value else 0.0
        val finalShares = if (type == "配股") 0.0 else shares
        val finalDividendShares = if (type == "配股") shares else 0.0
        val finalDividendIncome = if (type == "配息") finalIncome else 0.0

        if (transactionId == null) {
            addTransaction(
                stockName, stockCode, date, type,
                price, finalShares,
                finalFee, finalTax, finalIncome, finalExpense,
                cashDividend, exDividendShares, stockDividend,
                finalDividendShares, exRightsShares,
                note, finalDividendIncome, capitalReductionRatio,
                sharesBeforeReduction, sharesAfterReduction, cashReturned,
                stockSplitRatio, sharesBeforeSplit, sharesAfterSplit,
                marginPrincipal, marginAnnualRate, marginLotId, marginRepaymentLotId, marginRepayment, shortBorrowPrincipal, shortBorrowAnnualRate, shortLotId, shortCoverLotId, shortCoverShares, shortCompensationLotId, shortCompensation
            )
        } else {
            updateTransaction(
                stockCode, date, type,
                price, finalShares,
                finalFee, finalTax, finalIncome, finalExpense,
                cashDividend, exDividendShares, stockDividend,
                finalDividendShares, exRightsShares,
                note, finalDividendIncome, capitalReductionRatio,
                sharesBeforeReduction, sharesAfterReduction, cashReturned,
                stockSplitRatio, sharesBeforeSplit, sharesAfterSplit,
                marginPrincipal, marginAnnualRate, marginLotId, marginRepaymentLotId, marginRepayment, shortBorrowPrincipal, shortBorrowAnnualRate, shortLotId, shortCoverLotId, shortCoverShares, shortCompensationLotId, shortCompensation
            )
        }
    }

    private suspend fun addTransaction(
        stockName: String,
        stockCode: String,
        date: Long,
        type: String,
        price: Double,
        shares: Double,
        fee: Double,
        tax: Double,
        income: Double,
        expense: Double,
        cashDividend: Double,
        exDividendShares: Double,
        stockDividend: Double,
        dividendShares: Double,
        exRightsShares: Double,
        note: String,
        dividendIncome: Double,
        capitalReductionRatio: Double,
        sharesBeforeReduction: Double,
        sharesAfterReduction: Double,
        cashReturned: Double,
        stockSplitRatio: Double,
        sharesBeforeSplit: Double,
        sharesAfterSplit: Double
        ,marginPrincipal: Double
        ,marginAnnualRate: Double
        ,marginLotId: String
        ,marginRepaymentLotId: String
        ,marginRepayment: Double, shortBorrowPrincipal: Double, shortBorrowAnnualRate: Double, shortLotId: String, shortCoverLotId: String, shortCoverShares: Double, shortCompensationLotId: String, shortCompensation: Double
    ) {
        val inferredMarket = StockMarket.inferFromCode(stockCode)
        var stock = stockDao.getStockByCode(stockCode)

        if (stock == null) {
            val newStock = Stock(name = stockName, code = stockCode, market = inferredMarket, industry = "")
            stockDao.insertStock(newStock)
            stock = stockDao.getStockByCode(stockCode)
        } else if (StockMarket.normalize(stock.market) != inferredMarket) {
            val updatedStock = stock.copy(market = inferredMarket)
            stockDao.updateStock(updatedStock)
            stock = updatedStock
        }

        stock?.let { currentStock ->
            val resolvedLotId = if (type == "融資買進") marginLotId.ifBlank { UUID.randomUUID().toString() } else marginLotId
            val resolvedShortLotId = if (type == "融券賣出") shortLotId.ifBlank { UUID.randomUUID().toString() } else shortLotId
            val transaction = StockTransaction(
                stockCode = currentStock.code,
                accountId = _selectedAccountId.value,
                date = date,
                recordTime = System.currentTimeMillis(),
                type = type,
                buyPrice = if (type == "買進" || type == "融資買進") price else 0.0,
                buyShares = if (type == "買進" || type == "融資買進") shares else 0.0,
                sellPrice = if (type == "賣出" || type == "融券賣出") price else 0.0,
                sellShares = if (type == "賣出" || type == "融券賣出") shares else 0.0,
                fee = fee,
                tax = tax,
                income = income,
                expense = expense,
                cashDividend = cashDividend,
                exDividendShares = exDividendShares,
                stockDividend = stockDividend,
                dividendShares = dividendShares,
                exRightsShares = exRightsShares,
                note = note,
                dividendIncome = dividendIncome,
                capitalReductionRatio = capitalReductionRatio,
                sharesBeforeReduction = sharesBeforeReduction,
                sharesAfterReduction = sharesAfterReduction,
                cashReturned = cashReturned,
                stockSplitRatio = stockSplitRatio,
                sharesBeforeSplit = sharesBeforeSplit,
                sharesAfterSplit = sharesAfterSplit,
                marginPrincipal = marginPrincipal,
                marginAnnualRate = marginAnnualRate,
                marginLotId = resolvedLotId,
                marginRepaymentLotId = marginRepaymentLotId,
                marginRepayment = marginRepayment, shortBorrowPrincipal = shortBorrowPrincipal, shortBorrowAnnualRate = shortBorrowAnnualRate, shortLotId = resolvedShortLotId, shortCoverLotId = shortCoverLotId, shortCoverShares = shortCoverShares, shortCompensationLotId = shortCompensationLotId, shortCompensation = shortCompensation
            )
            stockDao.insertTransaction(transaction)
            realtimeStockDataService.refreshStock(stockCode)

        }
    }

    private suspend fun updateTransaction(
        stockCode: String,
        date: Long,
        type: String,
        price: Double,
        shares: Double,
        fee: Double,
        tax: Double,
        income: Double,
        expense: Double,
        cashDividend: Double,
        exDividendShares: Double,
        stockDividend: Double,
        dividendShares: Double,
        exRightsShares: Double,
        note: String,
        dividendIncome: Double,
        capitalReductionRatio: Double,
        sharesBeforeReduction: Double,
        sharesAfterReduction: Double,
        cashReturned: Double,
        stockSplitRatio: Double,
        sharesBeforeSplit: Double,
        sharesAfterSplit: Double
        ,marginPrincipal: Double
        ,marginAnnualRate: Double
        ,marginLotId: String
        ,marginRepaymentLotId: String
        ,marginRepayment: Double, shortBorrowPrincipal: Double, shortBorrowAnnualRate: Double, shortLotId: String, shortCoverLotId: String, shortCoverShares: Double, shortCompensationLotId: String, shortCompensation: Double
    ) {
        _transactionToEdit.value?.let {
            val updatedTransaction = it.copy(
                stockCode = stockCode,
                accountId = _selectedAccountId.value,
                date = date,
                type = type,
                buyPrice = if (type == "買進" || type == "融資買進") price else 0.0,
                buyShares = if (type == "買進" || type == "融資買進") shares else 0.0,
                sellPrice = if (type == "賣出" || type == "融券賣出") price else 0.0,
                sellShares = if (type == "賣出" || type == "融券賣出") shares else 0.0,
                fee = fee,
                tax = tax,
                income = income,
                expense = expense,
                cashDividend = cashDividend,
                exDividendShares = exDividendShares,
                stockDividend = stockDividend,
                dividendShares = dividendShares,
                exRightsShares = exRightsShares,
                note = note,
                dividendIncome = dividendIncome,
                capitalReductionRatio = capitalReductionRatio,
                sharesBeforeReduction = sharesBeforeReduction,
                sharesAfterReduction = sharesAfterReduction,
                cashReturned = cashReturned,
                stockSplitRatio = stockSplitRatio,
                sharesBeforeSplit = sharesBeforeSplit,
                sharesAfterSplit = sharesAfterSplit,
                marginPrincipal = marginPrincipal,
                marginAnnualRate = marginAnnualRate,
                marginLotId = marginLotId,
                marginRepaymentLotId = marginRepaymentLotId,
                marginRepayment = marginRepayment, shortBorrowPrincipal = shortBorrowPrincipal, shortBorrowAnnualRate = shortBorrowAnnualRate, shortLotId = shortLotId, shortCoverLotId = shortCoverLotId, shortCoverShares = shortCoverShares, shortCompensationLotId = shortCompensationLotId, shortCompensation = shortCompensation
            )
            stockDao.updateTransaction(updatedTransaction)
            realtimeStockDataService.refreshStock(stockCode)
        }
    }

    fun resetCalculatedValues() {
        _fee.value = 0.0
        _tax.value = 0.0
        _expense.value = 0.0
        _income.value = 0.0
        _taxRate.value = 0.0
    }

    fun resetEditState() {
        _transactionToEdit.value = null
    }

    fun resetForm() {
        resetEditState()
        resetCalculatedValues()
    }

    fun updateFee(
        newFee: Double,
        price: Double,
        shares: Double,
        type: String,
        tax: Double = _tax.value,
        market: String = StockMarket.TW
    ) {
        _fee.value = newFee

        when (type) {
            "買進" -> {
                val transactionValue = price * shares
                _expense.value = roundCalculatedCurrency(transactionValue + newFee, market)
            }

            "賣出" -> {
                val transactionValue = price * shares
                _income.value = roundCalculatedCurrency(transactionValue - newFee - tax, market)
            }
        }
    }

    fun updateTax(
        newTax: Double,
        price: Double,
        shares: Double,
        fee: Double = _fee.value,
        market: String = StockMarket.TW
    ) {
        _tax.value = newTax

        val transactionValue = price * shares
        _income.value = roundCalculatedCurrency(transactionValue - fee - newTax, market)
    }

    fun updateExpense(newExpense: Double) {
        _expense.value = newExpense
    }

    fun updateIncome(newIncome: Double) {
        _income.value = newIncome
    }

    fun roundCalculatedAmount(value: Double): Double {
        return CalculationRoundingMode.apply(value, calculationRoundingMode.value)
    }

    fun roundCalculatedCurrency(value: Double, market: String?): Double {
        return CalculationRoundingMode.applyCurrency(value, market, calculationRoundingMode.value)
    }

}
