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

    init {
        transactionId?.let {
            viewModelScope.launch {
                val transaction = stockDao.getTransactionById(it).firstOrNull()
                _transactionToEdit.value = transaction
                transaction?.let { tx -> _fee.value = tx.fee }
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

    fun calculateFee(price: Double, shares: Double) {
        if (price <= 0 || shares <= 0) {
            _fee.value = 0.0
            return
        }

        val (discount, minFeeRegular, minFeeOddLot) = feeSettings.value
        val transactionValue = price * shares
        val calculatedFee = transactionValue * 0.001425 * discount

        val minFee = if (shares % 1000 == 0.0) minFeeRegular else minFeeOddLot

        _fee.value = max(calculatedFee, minFee.toDouble()).roundToInt().toDouble()
    }

    fun addOrUpdateTransaction(
        stockName: String,
        stockCode: String,
        date: Long,
        type: String,
        price: Double,
        shares: Double,
        // fee: Double,  // Fee is now from state
        tax: Double = 0.0,
        income: Double = 0.0,
        expense: Double = 0.0,
        cashDividend: Double = 0.0,
        exDividendShares: Double = 0.0,
        stockDividend: Double = 0.0,
        dividendShares: Double = 0.0,
        exRightsShares: Double = 0.0,
        capitalReturn: Double = 0.0,
        note: String = ""
    ) {
        viewModelScope.launch {
            if (transactionId == null) {
                addTransaction(
                    stockName, stockCode, date, type, price, shares, _fee.value, tax, income, expense,
                    cashDividend, exDividendShares, stockDividend, dividendShares, exRightsShares,
                    capitalReturn, note
                )
            } else {
                updateTransaction(
                    date, type, price, shares, _fee.value, tax, income, expense, cashDividend,
                    exDividendShares, stockDividend, dividendShares, exRightsShares,
                    capitalReturn, note
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
        note: String
    ) {
        var stock = stockDao.getStockByCode(stockCode)

        if (stock == null) {
            val newStock = Stock(name = stockName, code = stockCode, market = "", industry = "")
            stockDao.insertStock(newStock)
            stock = stockDao.getStockByCode(stockCode)
        }

        stock?.let {
            val transaction = StockTransaction(
                stockId = it.id,
                date = date,
                recordTime = System.currentTimeMillis(),
                type = type,
                price = price,
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
                note = note
            )
            stockDao.insertTransaction(transaction)
        }
    }

    private suspend fun updateTransaction(
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
        note: String
    ) {
        _transactionToEdit.value?.let {
            val updatedTransaction = it.copy(
                date = date,
                type = type,
                price = price,
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
                note = note
            )
            stockDao.updateTransaction(updatedTransaction)
        }
    }
}