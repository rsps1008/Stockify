package com.rsps1008.stockify

import com.rsps1008.stockify.data.StockMarket
import com.rsps1008.stockify.data.StockTransaction
import com.rsps1008.stockify.data.openStockKeysAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenPositionSupportTest {

    @Test
    fun fullyClosedLongPositionIsExcluded() {
        val transactions = listOf(
            StockTransaction(
                stockCode = "2330",
                date = 1L,
                recordTime = 1L,
                type = "買進",
                buyPrice = 1.0,
                buyShares = 100.0,
                expense = 100.0
            ),
            StockTransaction(
                stockCode = "2330",
                date = 2L,
                recordTime = 2L,
                type = "賣出",
                sellPrice = 1.0,
                sellShares = 100.0,
                income = 100.0
            )
        )

        assertTrue(openStockKeysAt(transactions, valuationDate = 2L).isEmpty())
    }

    @Test
    fun outstandingMarginDebtKeepsStockOpenAfterSharesAreSold() {
        val transactions = listOf(
            StockTransaction(
                stockCode = "2330",
                date = 1L,
                recordTime = 1L,
                type = "融資買進",
                buyPrice = 1.0,
                buyShares = 100.0,
                expense = 100.0,
                marginPrincipal = 100.0,
                marginLotId = "margin-1"
            ),
            StockTransaction(
                stockCode = "2330",
                date = 2L,
                recordTime = 2L,
                type = "賣出",
                sellPrice = 1.0,
                sellShares = 100.0,
                income = 100.0
            )
        )

        assertEquals(setOf("TW:2330"), openStockKeysAt(transactions, valuationDate = 2L))
    }

    @Test
    fun outstandingShortPositionKeepsStockOpenWithoutLongShares() {
        val transactions = listOf(
            StockTransaction(
                stockCode = "AAPL",
                market = StockMarket.US,
                date = 1L,
                recordTime = 1L,
                type = "融券賣出",
                sellPrice = 10.0,
                sellShares = 10.0,
                income = 100.0,
                shortBorrowPrincipal = 100.0,
                shortLotId = "short-1"
            )
        )

        assertEquals(setOf("US:AAPL"), openStockKeysAt(transactions, valuationDate = 1L))
    }

    @Test
    fun futureTransactionsDoNotBecomeOpenBeforeValuationDate() {
        val futureBuy = StockTransaction(
            stockCode = "2330",
            date = 100L,
            recordTime = 100L,
            type = "買進",
            buyPrice = 1.0,
            buyShares = 100.0,
            expense = 100.0
        )

        assertFalse(openStockKeysAt(listOf(futureBuy), valuationDate = 99L).contains("TW:2330"))
    }
}
