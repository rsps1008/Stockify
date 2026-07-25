package com.rsps1008.stockify.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rsps1008.stockify.data.StockDao
import com.rsps1008.stockify.data.StockTransaction
import com.rsps1008.stockify.ui.screens.TransactionUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionDetailViewModel(transactionId: Int, private val stockDao: StockDao) : ViewModel() {

    private val transaction: StateFlow<StockTransaction?> = stockDao.getTransactionById(transactionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val transactionUiState: StateFlow<TransactionUiState?> = transaction.filterNotNull().flatMapLatest { tx ->
        stockDao.getStockByCodeFlow(tx.stockCode).filterNotNull().map { stock ->
            TransactionUiState(tx, stock.name, stock.market)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val hasMarginDependents: StateFlow<Boolean> = transaction.flatMapLatest { tx ->
        val marginDependents = tx?.marginLotId?.takeIf { it.isNotBlank() }
            ?.let { stockDao.getMarginRepaymentsForLot(it).map { dependents -> dependents.isNotEmpty() } }
            ?: kotlinx.coroutines.flow.flowOf(false)
        val shortDependents = tx?.shortLotId?.takeIf { it.isNotBlank() }
            ?.let { stockDao.getShortDependentsForLot(it).map { dependents -> dependents.isNotEmpty() } }
            ?: kotlinx.coroutines.flow.flowOf(false)
        combine(marginDependents, shortDependents) { hasMargin, hasShort -> hasMargin || hasShort }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), false)

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
