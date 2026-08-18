package com.rsps1008.stockify.ui.viewmodel

import com.rsps1008.stockify.data.Stock
import com.rsps1008.stockify.data.StockMarket
import com.rsps1008.stockify.data.StockTransaction
import com.rsps1008.stockify.data.TransactionListSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DividendInfoViewModelTest {

    @Test
    fun transactionRevisionSignature_changesWhenStockCodeChanges() {
        val original = StockTransaction(
            id = 7,
            stockCode = "2330",
            accountId = 1,
            date = 1_700_000_000_000L,
            recordTime = 1_700_000_000_001L,
            type = "配息",
            cashDividend = 2.0
        )
        val movedToAnotherStock = original.copy(stockCode = "2317")
        val stockCodes = setOf("2330", "2317")

        assertNotEquals(
            buildTransactionRevisionSignature(listOf(original), stockCodes),
            buildTransactionRevisionSignature(listOf(movedToAnotherStock), stockCodes)
        )
    }

    @Test
    fun taiwanStockRefs_ignoreHomeMarketModeAndRespectAccountAndDate() {
        val snapshot = TransactionListSnapshot(
            stocks = listOf(
                Stock(name = "台積電", code = "2330", market = StockMarket.TW),
                Stock(name = "Apple", code = "AAPL", market = StockMarket.US),
                Stock(name = "聯電", code = "2303", market = StockMarket.TW)
            ),
            transactions = listOf(
                StockTransaction(id = 1, stockCode = "2330", accountId = 1, date = 100L, recordTime = 100L, type = "買進"),
                StockTransaction(id = 2, stockCode = "AAPL", accountId = 1, date = 100L, recordTime = 100L, type = "買進"),
                StockTransaction(id = 3, stockCode = "2303", accountId = 2, date = 100L, recordTime = 100L, type = "買進"),
                StockTransaction(id = 4, stockCode = "2303", accountId = 1, date = 200L, recordTime = 200L, type = "買進")
            )
        )

        assertEquals(
            listOf(TaiwanStockRef("2330", "台積電")),
            buildTaiwanStockRefs(snapshot, accountId = 1, valuationDateMillis = 150L)
        )
        assertEquals(
            listOf(TaiwanStockRef("2330", "台積電"), TaiwanStockRef("2303", "聯電")),
            buildTaiwanStockRefs(snapshot, accountId = 1, valuationDateMillis = 250L)
        )
    }
}
