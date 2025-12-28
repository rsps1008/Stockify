package com.rsps1008.stockify.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rsps1008.stockify.data.SettingsDataStore
import com.rsps1008.stockify.data.Stock
import com.rsps1008.stockify.data.StockDao
import com.rsps1008.stockify.data.StockTransaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

class AddTransactionViewModel(
    private val stockDao: StockDao,
    private val settingsDataStore: SettingsDataStore,
    private val transactionId: Int?
) : ViewModel() {

    private val _transactionToEdit = MutableStateFlow<StockTransaction?>(null)
    val transactionToEdit = _transactionToEdit.asStateFlow()

    private val _fee = MutableStateFlow(0.0)
    val fee = _fee.asStateFlow()

    private val _tax = MutableStateFlow(0.0)
    val tax = _tax.asStateFlow()

    private val _expense = MutableStateFlow(0.0)
    val expense = _expense.asStateFlow()

    private val _income = MutableStateFlow(0.0)
    val income = _income.asStateFlow()

    init {
        transactionId?.let {
            viewModelScope.launch {
                val transaction = stockDao.getTransactionById(it).firstOrNull()
                _transactionToEdit.value = transaction
                transaction?.let { tx ->
                    _fee.value = tx.fee
                    _tax.value = tx.tax
                    _expense.value = tx.expense
                    _income.value = tx.income
                }
            }
        }
    }

    val stocks: StateFlow<List<Stock>> = stockDao.getAllStocks()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    private val feeSettings = combine(
        settingsDataStore.feeDiscountFlow,
        settingsDataStore.minFeeRegularFlow,
        settingsDataStore.minFeeOddLotFlow
    ) { discount, minRegular, minOdd ->
        Triple(discount, minRegular, minOdd)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), Triple(0.28, 20, 1))

    fun calculateBuyCosts(price: Double, shares: Double) {
        if (price <= 0 || shares <= 0) {
            _fee.value = 0.0
            _expense.value = 0.0
            return
        }

        val (discount, minFeeRegular, minFeeOddLot) = feeSettings.value
        val transactionValue = price * shares
        val calculatedFee = transactionValue * 0.001425 * discount

        val minFee = if (shares % 1000 == 0.0) minFeeRegular else minFeeOddLot

        val finalFee = max(calculatedFee, minFee.toDouble()).roundToInt().toDouble()
        _fee.value = finalFee
        _expense.value = (transactionValue + finalFee).roundToInt().toDouble()
    }

    fun calculateSellCosts(price: Double, shares: Double) {
        if (price <= 0 || shares <= 0) {
            _fee.value = 0.0
            _tax.value = 0.0
            _income.value = 0.0
            return
        }

        // Fee calculation
        val (discount, minFeeRegular, minFeeOddLot) = feeSettings.value
        val transactionValue = price * shares
        val calculatedFee = transactionValue * 0.001425 * discount
        val minFee = if (shares % 1000 == 0.0) minFeeRegular else minFeeOddLot
        val finalFee = max(calculatedFee, minFee.toDouble()).roundToInt().toDouble()
        _fee.value = finalFee

        // Tax calculation for sell
        val finalTax = (transactionValue * 0.003).roundToInt().toDouble()
        _tax.value = finalTax

        // Net income calculation
        _income.value = (transactionValue - finalFee - finalTax).roundToInt().toDouble()
    }


    fun addOrUpdateTransaction(
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
        dividendShares: Double = 0.0,
        capitalReturn: Double = 0.0,
        note: String = ""
    ) {
        viewModelScope.launch {
            val finalIncome = when (type) {
                "賣出" -> _income.value
                "配息" -> price
                else -> 0.0
            }

            val finalExpense = if (type == "買進") _expense.value else 0.0
            val finalTax = if (type == "賣出") _tax.value else 0.0
            val finalShares = if (type == "配股") 0.0 else shares
            val finalDividendShares = if (type == "配股") shares else 0.0
            val finalDividendIncome = if (type == "配息") finalIncome else 0.0

            if (transactionId == null) {
                addTransaction(
                    stockName, stockCode, date, type, price, finalShares, _fee.value, finalTax, finalIncome, finalExpense,
                    cashDividend, exDividendShares, stockDividend, finalDividendShares, exRightsShares,
                    capitalReturn, note, finalDividendIncome
                )
            } else {
                updateTransaction(
                    stockCode,
                    date, type, price, finalShares, _fee.value, finalTax, finalIncome, finalExpense, cashDividend,
                    exDividendShares, stockDividend, finalDividendShares, exRightsShares,
                    capitalReturn, note, finalDividendIncome
                )
            }
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
        capitalReturn: Double,
        note: String,
        dividendIncome: Double
    ) {
        var stock = stockDao.getStockByCode(stockCode)

        if (stock == null) {
            val newStock = Stock(name = stockName, code = stockCode, market = "", industry = "")
            stockDao.insertStock(newStock)
            stock = stockDao.getStockByCode(stockCode)
        }

        stock?.let {
            val transaction = StockTransaction(
                stockCode = it.code,
                date = date,
                recordTime = System.currentTimeMillis(),
                type = type,
                price = if (type == "配息" || type == "配股") 0.0 else price,
                shares = shares,
                fee = fee,
                tax = tax,
                income = income,
                expense = expense,
                cashDividend = cashDividend,
                exDividendShares = exDividendShares,
                stockDividend = stockDividend,
                dividendShares = dividendShares,
                exRightsShares = exRightsShares,
                capitalReturn = capitalReturn,
                note = note,
                dividendIncome = dividendIncome
            )
            stockDao.insertTransaction(transaction)
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
        capitalReturn: Double,
        note: String,
        dividendIncome: Double
    ) {
        _transactionToEdit.value?.let {
            val updatedTransaction = it.copy(
                stockCode = stockCode,
                date = date,
                type = type,
                price = if (type == "配息" || type == "配股") 0.0 else price,
                shares = shares,
                fee = fee,
                tax = tax,
                income = income,
                expense = expense,
                cashDividend = cashDividend,
                exDividendShares = exDividendShares,
                stockDividend = stockDividend,
                dividendShares = dividendShares,
                exRightsShares = exRightsShares,
                capitalReturn = capitalReturn,
                note = note,
                dividendIncome = dividendIncome
            )
            stockDao.updateTransaction(updatedTransaction)
        }
    }
}
