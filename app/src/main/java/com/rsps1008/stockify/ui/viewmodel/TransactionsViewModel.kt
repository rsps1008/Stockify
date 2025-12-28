package com.rsps1008.stockify.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rsps1008.stockify.data.StockDao
import com.rsps1008.stockify.ui.screens.TransactionUiState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class TransactionsViewModel(stockDao: StockDao) : ViewModel() {

    val transactions: StateFlow<List<TransactionUiState>> = combine(
        stockDao.getAllStocks(),
        stockDao.getAllTransactions()
    ) { stocks, transactions ->
        transactions.map { transaction ->
            val stock = stocks.find { it.code == transaction.stockCode }
            TransactionUiState(
                transaction = transaction,
                stockName = stock?.name ?: ""
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = emptyList()
    )
}