package com.rsps1008.stockify.ui.viewmodel

import com.rsps1008.stockify.data.StockTransaction
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
}
