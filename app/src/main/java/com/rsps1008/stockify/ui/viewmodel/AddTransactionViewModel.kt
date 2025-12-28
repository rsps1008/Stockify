package com.rsps1008.stockify.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rsps1008.stockify.data.Stock
import com.rsps1008.stockify.data.StockDao
import com.rsps1008.stockify.data.StockTransaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AddTransactionViewModel(private val stockDao: StockDao, private val transactionId: Int?) : ViewModel() {

    private val _transactionToEdit = MutableStateFlow<StockTransaction?>(null)
    val transactionToEdit = _transactionToEdit.asStateFlow()

    init {
        transactionId?.let {
            viewModelScope.launch {
                _transactionToEdit.value = stockDao.getTransactionById(it).firstOrNull()
            }
        }
    }

    val stocks: StateFlow<List<Stock>> = stockDao.getAllStocks()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    fun addOrUpdateTransaction(
        stockName: String,
        stockCode: String,
        date: Long,
        type: String,
        price: Double,
        shares: Double,
        fee: Double
    ) {
        viewModelScope.launch {
            if (transactionId == null) {
                addTransaction(stockName, stockCode, date, type, price, shares, fee)
            } else {
                updateTransaction(date, type, price, shares, fee)
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
        fee: Double
    ) {
        var stock = stockDao.getStockByCode(stockCode)

        if (stock == null) {
            val newStock = Stock(name = stockName, code = stockCode)
            stockDao.insertStock(newStock)
            stock = stockDao.getStockByCode(stockCode)
        }

        stock?.let {
            val transaction = StockTransaction(
                stockId = it.id,
                date = date,
                type = type,
                price = price,
                shares = shares,
                fee = fee
            )
            stockDao.insertTransaction(transaction)
        }
    }

    private suspend fun updateTransaction(
        date: Long,
        type: String,
        price: Double,
        shares: Double,
        fee: Double
    ) {
        _transactionToEdit.value?.let {
            val updatedTransaction = it.copy(
                date = date,
                type = type,
                price = price,
                shares = shares,
                fee = fee
            )
            stockDao.updateTransaction(updatedTransaction)
        }
    }
}