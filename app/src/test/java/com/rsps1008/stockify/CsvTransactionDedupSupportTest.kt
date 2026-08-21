package com.rsps1008.stockify

import com.rsps1008.stockify.data.CsvTransaction
import com.rsps1008.stockify.data.CsvTransactionDedupSupport
import com.rsps1008.stockify.data.StockTransaction
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTransactionDedupSupportTest {
    @Test
    fun filtersExistingAndRepeatedRowsButKeepsDistinctRecordTimes() {
        val existing = transaction(id = 41, recordTime = 100L)
        val imported = listOf(
            CsvTransaction("台積電", "2330", transaction = transaction(recordTime = 100L)),
            CsvTransaction("台積電", "2330", transaction = transaction(recordTime = 100L)),
            CsvTransaction("台積電", "2330", transaction = transaction(recordTime = 101L))
        )

        val result = CsvTransactionDedupSupport.filterNewTransactions(imported, listOf(existing))

        assertEquals(listOf(101L), result.map { it.transaction.recordTime })
    }

    private fun transaction(id: Int = 0, recordTime: Long) = StockTransaction(
        id = id,
        stockCode = "2330",
        accountId = 1,
        date = 1_000L,
        recordTime = recordTime,
        type = "買進",
        buyPrice = 100.0,
        buyShares = 10.0,
        expense = 1_000.0
    )
}
