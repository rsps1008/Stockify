package com.rsps1008.stockify

import com.rsps1008.stockify.data.CsvTransaction
import com.rsps1008.stockify.data.CsvTransactionDedupSupport
import com.rsps1008.stockify.data.Stock
import com.rsps1008.stockify.data.StockKey
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

    @Test
    fun repairedLegacyMarketMatchesImportedTransactionInsteadOfDuplicatingIt() {
        val existing = transaction(id = 41, recordTime = 100L).copy(market = "US")
        val imported = CsvTransaction(
            stockName = "台積電",
            stockCode = "2330",
            transaction = transaction(recordTime = 100L).copy(market = "TW")
        )
        val aliases = CsvTransactionDedupSupport.marketRepairAliases(
            importedStockKeys = listOf(StockKey("TW", "2330")),
            existingStocksByCode = mapOf(
                "2330" to listOf(Stock(name = "台積電 ADR 舊資料", code = "2330", market = "US"))
            )
        )

        val result = CsvTransactionDedupSupport.filterNewTransactions(
            importedTransactions = listOf(imported),
            existingTransactions = listOf(existing),
            marketAliases = aliases
        )

        assertEquals(emptyList<CsvTransaction>(), result)
        assertEquals(
            listOf("TW"),
            CsvTransactionDedupSupport.canonicalizeExistingTransactions(
                listOf(existing), aliases
            ).map { it.market }
        )
    }

    @Test
    fun repairsNonCanonicalLegacyMarketEvenWhenCanonicalStockAlreadyExists() {
        val existing = transaction(id = 41, recordTime = 100L).copy(market = "US")
        val imported = CsvTransaction(
            stockName = "台積電",
            stockCode = "2330",
            transaction = transaction(recordTime = 100L).copy(market = "TW")
        )
        val aliases = CsvTransactionDedupSupport.marketRepairAliases(
            importedStockKeys = listOf(StockKey("TW", "2330")),
            existingStocksByCode = mapOf(
                "2330" to listOf(
                    Stock(name = "台積電", code = "2330", market = "TW"),
                    Stock(name = "台積電 ADR 舊資料", code = "2330", market = "US")
                )
            )
        )

        assertEquals(mapOf("US:2330" to "TW"), aliases)
        assertEquals(
            emptyList<CsvTransaction>(),
            CsvTransactionDedupSupport.filterNewTransactions(
                importedTransactions = listOf(imported),
                existingTransactions = listOf(existing),
                marketAliases = aliases
            )
        )
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
