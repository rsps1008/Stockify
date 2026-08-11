package com.rsps1008.stockify.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class TransactionListSnapshot(
    val stocks: List<Stock> = emptyList(),
    val transactions: List<StockTransaction> = emptyList()
)

/**
 * Keeps the transaction-list source data available while the application process lives.
 * Room remains the source of truth; its observable queries refresh this snapshot after
 * every insert, update, delete, import, or clear operation.
 */
class TransactionListRepository(
    stockDao: StockDao,
    applicationScope: CoroutineScope
) {
    val snapshot: StateFlow<TransactionListSnapshot> = combine(
        stockDao.getHeldStocks(),
        stockDao.getAllTransactions()
    ) { stocks, transactions ->
        TransactionListSnapshot(stocks = stocks, transactions = transactions)
    }.stateIn(
        scope = applicationScope,
        started = SharingStarted.Eagerly,
        initialValue = TransactionListSnapshot()
    )
}
