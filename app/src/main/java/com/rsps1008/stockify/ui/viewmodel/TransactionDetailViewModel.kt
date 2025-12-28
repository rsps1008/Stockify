package com.rsps1008.stockify.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rsps1008.stockify.data.StockDao
import com.rsps1008.stockify.data.StockTransaction
import com.rsps1008.stockify.ui.screens.TransactionUiState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TransactionDetailViewModel(transactionId: Int, private val stockDao: StockDao) : ViewModel() {

    private val transaction: StateFlow<StockTransaction?> = stockDao.getTransactionById(transactionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val transactionUiState: StateFlow<TransactionUiState?> = transaction.filterNotNull().flatMapLatest { tx ->
        stockDao.getStockById(tx.stockId).filterNotNull().map { stock ->
            TransactionUiState(tx, stock.name)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    fun deleteTransaction() {
        viewModelScope.launch {
            transaction.value?.let { stockDao.deleteTransaction(it) }
        }
    }

    fun updateTransaction(transaction: StockTransaction) {
        viewModelScope.launch {
            stockDao.updateTransaction(transaction)
        }
    }
}