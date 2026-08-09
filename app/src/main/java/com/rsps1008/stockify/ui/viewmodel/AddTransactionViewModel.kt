package com.rsps1008.stockify.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rsps1008.stockify.data.RealtimeStockDataService
import com.rsps1008.stockify.data.CalculationRoundingMode
import com.rsps1008.stockify.data.SettingsDataStore
import com.rsps1008.stockify.data.Stock
import com.rsps1008.stockify.data.StockDao
import com.rsps1008.stockify.data.HoldingCalculationSupport
import com.rsps1008.stockify.data.StockMarket
import com.rsps1008.stockify.data.StockTransaction
import com.rsps1008.stockify.data.Account
import com.rsps1008.stockify.data.TransactionListRepository
import com.rsps1008.stockify.data.dividend.YahooDividendRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToInt
import java.util.UUID

data class MarginLotOption(
    val lotId: String,
    val label: String,
    val remainingPrincipal: Double
)

data class ShortLotOption(val lotId: String, val label: String, val remainingShares: Double)

internal fun resolveMarginOpeningLotId(type: String, lotId: String): String =
    if (type == "融資買進") lotId.ifBlank { UUID.randomUUID().toString() } else lotId

internal fun resolveShortOpeningLotId(type: String, lotId: String): String =
    if (type == "融券賣出") lotId.ifBlank { UUID.randomUUID().toString() } else lotId

internal fun transactionFeeForType(
    transactionType: String,
    calculatedFee: Double,
    dividendFee: Double
): Double {
    return when (transactionType) {
        "買進", "賣出", "融資買進", "融券賣出", "買券還券" -> calculatedFee
        "配息" -> dividendFee
        else -> 0.0
    }
}

internal fun calculateSupplementaryHealthInsurancePremium(
    grossDividend: Double,
    market: String
): Double {
    if (!grossDividend.isFinite() || !StockMarket.isTw(market) || grossDividend <= 20_000.0) {
        return 0.0
    }
    return (grossDividend * 0.0211).roundToInt().toDouble()
}

internal fun holdingSharesAtDate(
    transactions: List<StockTransaction>,
    stockCode: String,
    accountId: Int,
    valuationDate: Long
): Double {
    val scopedTransactions = transactions.filter { transaction ->
        transaction.stockCode == stockCode &&
            (accountId == 0 || transaction.accountId == accountId)
    }
    return HoldingCalculationSupport
        .replayLongPosition(scopedTransactions, valuationDate)
        .shares
}

internal fun transactionsWithCandidateForValidation(
    existingTransactions: List<StockTransaction>,
    candidate: StockTransaction
): List<StockTransaction> {
    if (candidate.id != 0) return existingTransactions + candidate

    // Room assigns a new row after existing rows. Mirror that order while
    // validating equal date/recordTime transactions instead of sorting id=0
    // before an existing opening lot.
    val maxExistingId = existingTransactions.maxOfOrNull { it.id }?.coerceAtLeast(0) ?: 0
    val provisionalId = (maxExistingId.toLong() + 1L)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
    return existingTransactions + candidate.copy(id = provisionalId)
}

internal fun transactionsBeforeCandidateForLotSelection(
    transactions: List<StockTransaction>,
    candidateDate: Long,
    candidateRecordTime: Long,
    candidateId: Int
): List<StockTransaction> {
    return transactions.filter { transaction ->
        transaction.date < candidateDate ||
            (transaction.date == candidateDate &&
                (transaction.recordTime < candidateRecordTime ||
                    (transaction.recordTime == candidateRecordTime && transaction.id < candidateId)))
    }
}

class AddTransactionViewModel(
    private val stockDao: StockDao,
    private val settingsDataStore: SettingsDataStore,
    private val transactionId: Int?,
    private val realtimeStockDataService: RealtimeStockDataService,
    private val dividendRepository: YahooDividendRepository,
    private val transactionListRepository: TransactionListRepository
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
    val defaultMarginAnnualRate: StateFlow<Double> = settingsDataStore.defaultMarginAnnualRateFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 6.45)
    val defaultShortBorrowAnnualRate: StateFlow<Double> = settingsDataStore.defaultShortBorrowAnnualRateFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 3.5)
    private val _marginLots = MutableStateFlow<List<MarginLotOption>>(emptyList())
    val marginLots: StateFlow<List<MarginLotOption>> = _marginLots.asStateFlow()
    // 融資與融券共用同一個實驗功能開關。
    val shortSellingFeatureEnabled: StateFlow<Boolean> = marginFeatureEnabled
    private val _shortLots = MutableStateFlow<List<ShortLotOption>>(emptyList())
    val shortLots: StateFlow<List<ShortLotOption>> = _shortLots.asStateFlow()
    private var marginLotRequestVersion = 0L
    private var shortLotRequestVersion = 0L

    val accounts: StateFlow<List<Account>> = stockDao.getAllAccountsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val _selectedAccountId = MutableStateFlow(1)
    val selectedAccountId = _selectedAccountId.asStateFlow()

    fun selectAccount(accountId: Int) {
        _selectedAccountId.value = accountId
    }

    fun loadMarginLots(stockCode: String, valuationDate: Long, excludeTransactionId: Int? = null) {
        val requestVersion = ++marginLotRequestVersion
        val accountId = _selectedAccountId.value
        val editedTransaction = _transactionToEdit.value
            ?.takeIf { it.id == excludeTransactionId }
        val candidateRecordTime = editedTransaction?.recordTime ?: System.currentTimeMillis()
        val candidateId = editedTransaction?.id ?: Int.MAX_VALUE
        viewModelScope.launch {
            val transactions = stockDao.getTransactionsForStock(stockCode).firstOrNull().orEmpty()
                .filter { it.accountId == accountId && it.id != excludeTransactionId }
                .let {
                    transactionsBeforeCandidateForLotSelection(
                        transactions = it,
                        candidateDate = valuationDate,
                        candidateRecordTime = candidateRecordTime,
                        candidateId = candidateId
                    )
                }
            val summary = com.rsps1008.stockify.data.MarginCalculationSupport.calculate(
                transactions, valuationDate, marginDayCount.value
            )
            if (requestVersion == marginLotRequestVersion && accountId == _selectedAccountId.value) {
                _marginLots.value = summary.lots.map {
                    MarginLotOption(
                        lotId = it.lotId,
                        label = "${java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.getDefault()).format(java.util.Date(it.openedAt))} / ${it.annualRate}% / 剩餘 ${it.remainingPrincipal}",
                        remainingPrincipal = it.remainingPrincipal
                    )
                }
            }
        }
    }

    fun loadShortLots(stockCode: String, valuationDate: Long, excludeTransactionId: Int? = null) {
        val requestVersion = ++shortLotRequestVersion
        val accountId = _selectedAccountId.value
        val editedTransaction = _transactionToEdit.value
            ?.takeIf { it.id == excludeTransactionId }
        val candidateRecordTime = editedTransaction?.recordTime ?: System.currentTimeMillis()
        val candidateId = editedTransaction?.id ?: Int.MAX_VALUE
        viewModelScope.launch {
            val transactions = stockDao.getTransactionsForStock(stockCode).firstOrNull().orEmpty()
                .filter { it.accountId == accountId && it.id != excludeTransactionId }
                .let {
                    transactionsBeforeCandidateForLotSelection(
                        transactions = it,
                        candidateDate = valuationDate,
                        candidateRecordTime = candidateRecordTime,
                        candidateId = candidateId
                    )
                }
            val lots = com.rsps1008.stockify.data.ShortSellingCalculationSupport
                .calculate(transactions, valuationDate, marginDayCount.value).lots
            if (requestVersion == shortLotRequestVersion && accountId == _selectedAccountId.value) {
                _shortLots.value = lots.map {
                    ShortLotOption(
                        it.lotId,
                        "${java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.getDefault()).format(java.util.Date(it.openedAt))} / ${it.annualRate}% / 剩餘 ${it.remainingShares} 股",
                        it.remainingShares
                    )
                }
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
        accountId: Int,
        valuationDate: Long,
        onResult: (cashDividend: Double, holdingShares: Double, dateStr: String?) -> Unit,
        onFail: () -> Unit
    ) {
        viewModelScope.launch {
            try {
                val result = dividendRepository.fetchLatestCashDividend(stockCode)
                if (result == null) {
                    onFail()
                    return@launch
                }

                val holdingShares = loadHoldingSharesForDividend(
                    stockCode = stockCode,
                    accountId = accountId,
                    valuationDate = result.date.toTransactionDateMillis() ?: valuationDate
                )
                if (holdingShares <= 0) {
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
        accountId: Int,
        valuationDate: Long,
        onResult: (rate: Double, holdingShares: Double, dateStr: String?) -> Unit,
        onFail: () -> Unit
    ) {
        viewModelScope.launch {
            try {
                val result = dividendRepository.fetchLatestStockDividend(stockCode)
                if (result == null) {
                    onFail()
                    return@launch
                }

                val holdingShares = loadHoldingSharesForDividend(
                    stockCode = stockCode,
                    accountId = accountId,
                    valuationDate = result.date.toTransactionDateMillis() ?: valuationDate
                )
                if (holdingShares <= 0) {
                    onFail()
                    return@launch
                }

                onResult(result.amount, holdingShares, result.date)
            } catch (e: Exception) {
                onFail()
            }
        }
    }

    private suspend fun loadHoldingSharesForDividend(
        stockCode: String,
        accountId: Int,
        valuationDate: Long
    ): Double {
        val transactions = stockDao.getTransactionsForStock(stockCode).firstOrNull().orEmpty()
        return holdingSharesAtDate(
            transactions = transactions,
            stockCode = stockCode,
            accountId = accountId,
            valuationDate = valuationDate
        )
    }

    private fun String.toTransactionDateMillis(): Long? {
        return runCatching {
            LocalDate.parse(this, DateTimeFormatter.ofPattern("yyyy/MM/dd"))
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
    }


    val stocks: StateFlow<List<Stock>> = transactionListRepository.snapshot
        .map { it.stocks }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = transactionListRepository.snapshot.value.stocks
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
        supplementaryHealthInsurancePremium: Double = 0.0,
        marginPrincipal: Double = 0.0,
        marginAnnualRate: Double = 0.0,
        marginLotId: String = "",
        marginRepaymentLotId: String = "",
        marginRepayment: Double = 0.0,
        marginSelfFunded: Double = 0.0,
        marginSelfFundedOverridden: Boolean = false,
        marginActualInterest: Double = 0.0,
        shortBorrowPrincipal: Double = 0.0,
        shortBorrowAnnualRate: Double = 0.0,
        shortLotId: String = "",
        shortCoverLotId: String = "",
        shortCoverShares: Double = 0.0,
        shortCompensationLotId: String = "",
        shortCompensation: Double = 0.0
    ): String? {
        val finalFee = transactionFeeForType(type, _fee.value, dividendFee)
        val finalSupplementaryHealthInsurancePremium = if (type == "配息") {
            supplementaryHealthInsurancePremium.coerceAtLeast(0.0)
        } else {
            0.0
        }

        val finalIncome = when (type) {
            "賣出", "融券賣出" -> _income.value
            "配息" -> dividendIncome ?: (price - finalFee - finalSupplementaryHealthInsurancePremium).coerceAtLeast(0.0)
            "減資" -> cashReturned
            else -> 0.0
        }

        val finalExpense = when (type) {
            "買進", "融資買進", "買券還券" -> _expense.value
            "融資還款" -> marginRepayment + marginActualInterest
            "融券補償" -> shortCompensation
            else -> 0.0
        }
        val finalTax = if (type == "賣出" || type == "融券賣出") _tax.value else 0.0
        val finalShares = if (type == "配股") 0.0 else shares
        val finalDividendShares = if (type == "配股") shares else 0.0
        val finalDividendIncome = if (type == "配息") finalIncome else 0.0

        return if (transactionId == null) {
            addTransaction(
                stockName, stockCode, date, type,
                price, finalShares,
                finalFee, finalTax, finalIncome, finalExpense,
                cashDividend, exDividendShares, stockDividend,
                finalDividendShares, exRightsShares,
                note, finalDividendIncome, finalSupplementaryHealthInsurancePremium, capitalReductionRatio,
                sharesBeforeReduction, sharesAfterReduction, cashReturned,
                stockSplitRatio, sharesBeforeSplit, sharesAfterSplit,
                marginPrincipal, marginAnnualRate, marginLotId, marginRepaymentLotId, marginRepayment, marginSelfFunded, marginSelfFundedOverridden, marginActualInterest, shortBorrowPrincipal, shortBorrowAnnualRate, shortLotId, shortCoverLotId, shortCoverShares, shortCompensationLotId, shortCompensation
            )
        } else {
            updateTransaction(
                stockCode, date, type,
                price, finalShares,
                finalFee, finalTax, finalIncome, finalExpense,
                cashDividend, exDividendShares, stockDividend,
                finalDividendShares, exRightsShares,
                note, finalDividendIncome, finalSupplementaryHealthInsurancePremium, capitalReductionRatio,
                sharesBeforeReduction, sharesAfterReduction, cashReturned,
                stockSplitRatio, sharesBeforeSplit, sharesAfterSplit,
                marginPrincipal, marginAnnualRate, marginLotId, marginRepaymentLotId, marginRepayment, marginSelfFunded, marginSelfFundedOverridden, marginActualInterest, shortBorrowPrincipal, shortBorrowAnnualRate, shortLotId, shortCoverLotId, shortCoverShares, shortCompensationLotId, shortCompensation
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
        supplementaryHealthInsurancePremium: Double,
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
        ,marginRepayment: Double, marginSelfFunded: Double, marginSelfFundedOverridden: Boolean, marginActualInterest: Double, shortBorrowPrincipal: Double, shortBorrowAnnualRate: Double, shortLotId: String, shortCoverLotId: String, shortCoverShares: Double, shortCompensationLotId: String, shortCompensation: Double
    ): String? {
        val inferredMarket = StockMarket.inferFromCode(stockCode)
        val existingStock = stockDao.getStockByCode(stockCode)
        val candidateStock = when {
            existingStock == null -> Stock(
                name = stockName,
                code = stockCode,
                market = inferredMarket,
                industry = ""
            )
            StockMarket.normalize(existingStock.market) != inferredMarket ->
                existingStock.copy(market = inferredMarket)
            else -> existingStock
        }
        val resolvedLotId = resolveMarginOpeningLotId(type, marginLotId)
        val resolvedShortLotId = resolveShortOpeningLotId(type, shortLotId)
        val transaction = StockTransaction(
                stockCode = candidateStock.code,
                accountId = _selectedAccountId.value,
                date = date,
                recordTime = System.currentTimeMillis(),
                type = type,
                buyPrice = if (type == "買進" || type == "融資買進" || type == "買券還券") price else 0.0,
                buyShares = if (type == "買進" || type == "融資買進" || type == "買券還券") shares else 0.0,
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
                supplementaryHealthInsurancePremium = supplementaryHealthInsurancePremium,
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
                marginRepayment = marginRepayment, marginSelfFunded = marginSelfFunded, marginSelfFundedOverridden = marginSelfFundedOverridden, marginActualInterest = marginActualInterest, shortBorrowPrincipal = shortBorrowPrincipal, shortBorrowAnnualRate = shortBorrowAnnualRate, shortLotId = resolvedShortLotId, shortCoverLotId = shortCoverLotId, shortCoverShares = shortCoverShares, shortCompensationLotId = shortCompensationLotId, shortCompensation = shortCompensation
        )
        validateLotBalances(transaction)?.let { return it }

        if (existingStock == null) {
            stockDao.insertStock(candidateStock)
        } else if (candidateStock != existingStock) {
            stockDao.updateStock(candidateStock)
        }
        stockDao.insertTransaction(transaction)
        realtimeStockDataService.refreshStock(stockCode)
        return null
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
        supplementaryHealthInsurancePremium: Double,
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
        ,marginRepayment: Double, marginSelfFunded: Double, marginSelfFundedOverridden: Boolean, marginActualInterest: Double, shortBorrowPrincipal: Double, shortBorrowAnnualRate: Double, shortLotId: String, shortCoverLotId: String, shortCoverShares: Double, shortCompensationLotId: String, shortCompensation: Double
    ): String? {
        return _transactionToEdit.value?.let {
            val targetStock = stockDao.getStockByCode(stockCode)
                ?: return "找不到股票資料，無法更新交易"
            val normalizedTargetStock = targetStock.copy(market = StockMarket.inferFromCode(stockCode))
            val resolvedMarginLotId = resolveMarginOpeningLotId(type, marginLotId)
            val resolvedShortLotId = resolveShortOpeningLotId(type, shortLotId)
            val updatedTransaction = it.copy(
                stockCode = stockCode,
                accountId = _selectedAccountId.value,
                date = date,
                type = type,
                buyPrice = if (type == "買進" || type == "融資買進" || type == "買券還券") price else 0.0,
                buyShares = if (type == "買進" || type == "融資買進" || type == "買券還券") shares else 0.0,
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
                supplementaryHealthInsurancePremium = supplementaryHealthInsurancePremium,
                capitalReductionRatio = capitalReductionRatio,
                sharesBeforeReduction = sharesBeforeReduction,
                sharesAfterReduction = sharesAfterReduction,
                cashReturned = cashReturned,
                stockSplitRatio = stockSplitRatio,
                sharesBeforeSplit = sharesBeforeSplit,
                sharesAfterSplit = sharesAfterSplit,
                marginPrincipal = marginPrincipal,
                marginAnnualRate = marginAnnualRate,
                marginLotId = resolvedMarginLotId,
                marginRepaymentLotId = marginRepaymentLotId,
                marginRepayment = marginRepayment, marginSelfFunded = marginSelfFunded, marginSelfFundedOverridden = marginSelfFundedOverridden, marginActualInterest = marginActualInterest, shortBorrowPrincipal = shortBorrowPrincipal, shortBorrowAnnualRate = shortBorrowAnnualRate, shortLotId = resolvedShortLotId, shortCoverLotId = shortCoverLotId, shortCoverShares = shortCoverShares, shortCompensationLotId = shortCompensationLotId, shortCompensation = shortCompensation
            )
            validateLotBalances(updatedTransaction)?.let { return it }
            if (it.stockCode != updatedTransaction.stockCode || it.accountId != updatedTransaction.accountId) {
                validateLotBalancesAfterRemoving(it)?.let { error -> return error }
            }
            if (normalizedTargetStock != targetStock) {
                stockDao.updateStock(normalizedTargetStock)
            }
            stockDao.updateTransaction(updatedTransaction)
            realtimeStockDataService.refreshStock(stockCode)
            null
        } ?: "找不到要更新的交易"
    }

    private suspend fun validateLotBalances(candidate: StockTransaction): String? {
        com.rsps1008.stockify.data.FinancingTransactionValidationSupport
            .validateFinancingMarket(candidate, StockMarket.inferFromCode(candidate.stockCode))
            ?.let { return it }
        val existingTransactions = stockDao.getTransactionsForStock(candidate.stockCode)
            .firstOrNull()
            .orEmpty()
            .filter { it.accountId == candidate.accountId && it.id != candidate.id }
        val transactions = transactionsWithCandidateForValidation(
            existingTransactions = existingTransactions,
            candidate = candidate
        )

        com.rsps1008.stockify.data.FinancingTransactionValidationSupport.validate(transactions)?.let {
            return it
        }
        if (!com.rsps1008.stockify.data.MarginCalculationSupport.hasValidRepaymentBalances(transactions)) {
            return "融資還款會超過批次剩餘本金，請調整交易日期或金額"
        }
        if (!com.rsps1008.stockify.data.ShortSellingCalculationSupport.hasValidCoverBalances(transactions)) {
            return "買券還券會超過批次剩餘股數，請調整交易日期或股數"
        }
        return null
    }

    private suspend fun validateLotBalancesAfterRemoving(transaction: StockTransaction): String? {
        val remainingTransactions = stockDao.getTransactionsForStock(transaction.stockCode)
            .firstOrNull()
            .orEmpty()
            .filter { it.accountId == transaction.accountId && it.id != transaction.id }

        com.rsps1008.stockify.data.FinancingTransactionValidationSupport.validate(remainingTransactions)?.let {
            return "變更帳戶後原帳戶的$it"
        }
        if (!com.rsps1008.stockify.data.MarginCalculationSupport.hasValidRepaymentBalances(remainingTransactions)) {
            return "變更帳戶後會破壞原帳戶的融資批次關聯"
        }
        if (!com.rsps1008.stockify.data.ShortSellingCalculationSupport.hasValidCoverBalances(remainingTransactions)) {
            return "變更帳戶後會破壞原帳戶的融券批次關聯"
        }
        return null
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
