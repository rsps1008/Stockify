package com.rsps1008.stockify.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class TransactionListSnapshot(
    val stocks: List<Stock> = emptyList(),
    val transactions: List<StockTransaction> = emptyList(),
    val accountId: Int = 0
)

/**
 * Keeps the transaction-list source data available while the application process lives.
 * Room remains the source of truth; its observable queries refresh this snapshot after
 * every insert, update, delete, import, or clear operation.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TransactionListRepository(
    stockDao: StockDao,
    activeAccountIdFlow: kotlinx.coroutines.flow.Flow<Int>,
    applicationScope: CoroutineScope
) {
    private val accountScopedTransactions = activeAccountIdFlow
        .distinctUntilChanged()
        .flatMapLatest { accountId ->
            val transactions = if (accountId == 0) {
                stockDao.getAllTransactions()
            } else {
                stockDao.getTransactionsForAccount(accountId)
            }
            transactions.map { accountId to it }
        }

    val snapshot: StateFlow<TransactionListSnapshot> = combine(
        stockDao.getHeldStocks(),
        accountScopedTransactions
    ) { stocks, scopedTransactions ->
        TransactionListSnapshot(
            stocks = stocks,
            transactions = scopedTransactions.second,
            accountId = scopedTransactions.first
        )
    }.stateIn(
        scope = applicationScope,
        started = SharingStarted.WhileSubscribed(
            stopTimeoutMillis = 5_000L,
            replayExpirationMillis = 0L
        ),
        initialValue = TransactionListSnapshot()
    )
}
