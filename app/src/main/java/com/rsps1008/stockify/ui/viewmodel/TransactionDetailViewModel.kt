package com.rsps1008.stockify.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rsps1008.stockify.data.Stock
import com.rsps1008.stockify.data.StockDao
import com.rsps1008.stockify.data.StockTransaction
import com.rsps1008.stockify.ui.screens.TransactionUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface TransactionDetailState {
    object Loading : TransactionDetailState
    object Missing : TransactionDetailState
    data class Ready(val value: TransactionUiState) : TransactionDetailState
}

internal fun buildTransactionDetailState(
    transaction: StockTransaction?,
    stock: Stock?
): TransactionDetailState = if (transaction == null || stock == null) {
    TransactionDetailState.Missing
} else {
    TransactionDetailState.Ready(
        TransactionUiState(
            transaction = transaction,
            stockName = stock.name,
            market = stock.market
        )
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionDetailViewModel(transactionId: Int, private val stockDao: StockDao) : ViewModel() {

    private sealed interface TransactionRecordState {
        object Loading : TransactionRecordState
        object Missing : TransactionRecordState
        data class Present(val transaction: StockTransaction) : TransactionRecordState
    }

    private val transactionState: StateFlow<TransactionRecordState> = stockDao.getTransactionById(transactionId)
        .map { transaction ->
            transaction?.let(TransactionRecordState::Present) ?: TransactionRecordState.Missing
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), TransactionRecordState.Loading)

    val transactionDetailState: StateFlow<TransactionDetailState> = transactionState
        .flatMapLatest { recordState ->
            when (recordState) {
                TransactionRecordState.Loading -> flowOf(TransactionDetailState.Loading)
                TransactionRecordState.Missing -> flowOf(TransactionDetailState.Missing)
                is TransactionRecordState.Present -> {
                    val transaction = recordState.transaction
                    stockDao.getStockByCodeFlow(transaction.stockCode, transaction.market)
                        .map { stock -> buildTransactionDetailState(transaction, stock) }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), TransactionDetailState.Loading)

    val canModifyTransaction: StateFlow<Boolean> = transactionState.flatMapLatest { recordState ->
        val tx = (recordState as? TransactionRecordState.Present)?.transaction
        if (tx == null) {
            return@flatMapLatest flowOf(false)
        }
        val marginDependents = tx.marginLotId.takeIf { it.isNotBlank() }
            ?.let { lotId ->
                stockDao.getMarginRepaymentsForLot(lotId, tx.stockCode, tx.market, tx.accountId)
                    .map { dependents -> dependents.isNotEmpty() }
            }
            ?: kotlinx.coroutines.flow.flowOf(false)
        val shortDependents = tx.shortLotId.takeIf { it.isNotBlank() }
            ?.let { lotId ->
                stockDao.getShortDependentsForLot(lotId, tx.stockCode, tx.market, tx.accountId)
                    .map { dependents -> dependents.isNotEmpty() }
            }
            ?: kotlinx.coroutines.flow.flowOf(false)
        combine(marginDependents, shortDependents) { hasMargin, hasShort -> !hasMargin && !hasShort }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), false)

    fun deleteTransaction(onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val current = when (val recordState = transactionState.value) {
                TransactionRecordState.Loading -> {
                    onResult("交易資料尚未載入完成")
                    return@launch
                }
                TransactionRecordState.Missing -> {
                    onResult("找不到這筆交易")
                    return@launch
                }
                is TransactionRecordState.Present -> recordState.transaction
            }
            val remainingTransactions = stockDao.getTransactionsForStock(current.stockCode, current.market)
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
            com.rsps1008.stockify.data.HoldingCalculationSupport
                .validateLongPositionBalances(remainingTransactions)
                ?.let {
                    onResult("刪除後會造成多頭持股餘額錯誤，無法刪除")
                    return@launch
                }
            stockDao.deleteTransaction(current)
            onResult(null)
        }
    }
}
