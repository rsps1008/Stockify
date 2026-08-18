package com.rsps1008.stockify

import com.rsps1008.stockify.data.StockDao
import com.rsps1008.stockify.data.StockTransaction
import com.rsps1008.stockify.data.TransactionListRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class TransactionListRepositoryTest {

    @Test
    fun snapshotUsesRoomQueryForSelectedAccountAndAllAccounts() = runBlocking {
        val allTransactions = listOf(
            StockTransaction(id = 1, stockCode = "2330", accountId = 1, date = 1L, recordTime = 1L, type = "買進"),
            StockTransaction(id = 2, stockCode = "2330", accountId = 2, date = 2L, recordTime = 2L, type = "買進")
        )
        val accountTransactions = mapOf(2 to listOf(allTransactions[1]))
        val accountIdsQueried = mutableListOf<Int>()
        val activeAccountId = MutableStateFlow(2)
        val stockDao = proxyStockDao(
            allTransactions = flowOf(allTransactions),
            accountTransactions = accountTransactions,
            accountIdsQueried = accountIdsQueried
        )
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            val repository = TransactionListRepository(stockDao, activeAccountId, scope)

            val selectedSnapshot = withTimeout(1_000L) {
                repository.snapshot.first { it.accountId == 2 }
            }
            assertEquals(accountTransactions[2], selectedSnapshot.transactions)
            assertEquals(listOf(2), accountIdsQueried)

            activeAccountId.value = 0
            val allSnapshot = withTimeout(1_000L) {
                repository.snapshot.first { it.accountId == 0 }
            }
            assertEquals(allTransactions, allSnapshot.transactions)
        } finally {
            scope.coroutineContext[Job]?.cancel()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun proxyStockDao(
        allTransactions: Flow<List<StockTransaction>>,
        accountTransactions: Map<Int, List<StockTransaction>>,
        accountIdsQueried: MutableList<Int>
    ): StockDao = Proxy.newProxyInstance(
        StockDao::class.java.classLoader,
        arrayOf(StockDao::class.java)
    ) { _, method, args ->
        when (method.name) {
            "getHeldStocks" -> flowOf(emptyList<com.rsps1008.stockify.data.Stock>())
            "getAllTransactions" -> allTransactions
            "getTransactionsForAccount" -> {
                val accountId = args!![0] as Int
                accountIdsQueried += accountId
                flowOf(accountTransactions[accountId].orEmpty())
            }
            else -> error("Unexpected StockDao method: ${method.name}")
        }
    } as StockDao
}
