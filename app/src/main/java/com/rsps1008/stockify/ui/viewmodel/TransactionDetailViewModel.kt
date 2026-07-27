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
import kotlinx.coroutines.flow.first
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

    val canModifyTransaction: StateFlow<Boolean> = transaction.filterNotNull().flatMapLatest { tx ->
        val marginDependents = tx.marginLotId.takeIf { it.isNotBlank() }
            ?.let { lotId ->
                stockDao.getMarginRepaymentsForLot(lotId, tx.stockCode, tx.accountId)
                    .map { dependents -> dependents.isNotEmpty() }
            }
            ?: kotlinx.coroutines.flow.flowOf(false)
        val shortDependents = tx.shortLotId.takeIf { it.isNotBlank() }
            ?.let { lotId ->
                stockDao.getShortDependentsForLot(lotId, tx.stockCode, tx.accountId)
                    .map { dependents -> dependents.isNotEmpty() }
            }
            ?: kotlinx.coroutines.flow.flowOf(false)
        combine(marginDependents, shortDependents) { hasMargin, hasShort -> !hasMargin && !hasShort }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), false)

    fun deleteTransaction(onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val current = transaction.value
            if (current == null) {
                onResult("交易資料尚未載入完成")
                return@launch
            }
            val remainingTransactions = stockDao.getTransactionsForStock(current.stockCode)
                .first()
                .filter { it.accountId == current.accountId && it.id != current.id }
            if (!com.rsps1008.stockify.data.MarginCalculationSupport.hasValidRepaymentBalances(remainingTransactions)) {
                onResult("刪除後會破壞融資批次關聯，無法刪除")
                return@launch
            }
            if (!com.rsps1008.stockify.data.ShortSellingCalculationSupport.hasValidCoverBalances(remainingTransactions)) {
                onResult("刪除後會造成融券餘額或批次關聯錯誤，無法刪除")
                return@launch
            }
            stockDao.deleteTransaction(current)
            onResult(null)
        }
    }
}
