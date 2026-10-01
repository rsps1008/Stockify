package com.rsps1008.stockify

import com.rsps1008.stockify.data.StockTransaction
import com.rsps1008.stockify.data.orderedTransactionsByStock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class OrderedTransactionsFlowTest {
    @Test
    fun priceChangesReusePreparedTransactionsAndRoomChangesRebuildWithStableOrdering() = runBlocking {
        fun row(id: Int, time: Long, market: String = "TW") = StockTransaction(
            id = id, stockCode = "ABC", market = market, accountId = 1, type = "買進", date = 1L, recordTime = time)
        val transactions = MutableStateFlow(listOf(row(3, 2), row(2, 1), row(1, 1), row(4, 1, "US")))
        val prices = MutableStateFlow(100.0)
        val results = Channel<Pair<Map<String, List<StockTransaction>>, Double>>(Channel.UNLIMITED)
        val job = launch {
            combine(transactions.orderedTransactionsByStock(), prices) { rows, price -> rows to price }
                .collect { results.send(it) }
        }
        suspend fun next() = withTimeout(5_000) { results.receive() }
        try {
            val initial = next().first
            assertEquals(listOf(1, 2, 3), initial.getValue("TW:ABC").map { it.id })
            assertEquals(listOf(4), initial.getValue("US:ABC").map { it.id })
            prices.value = 120.0
            assertSame(initial, next().first)
            transactions.value = listOf(row(7, 0))
            val updated = next().first
            assertEquals(listOf(7), updated.getValue("TW:ABC").map { it.id })
            assertFalse(updated.containsKey("US:ABC"))
        } finally { job.cancel() }
    }
}
