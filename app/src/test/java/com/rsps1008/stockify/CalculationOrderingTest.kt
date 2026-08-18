package com.rsps1008.stockify

import com.rsps1008.stockify.data.HoldingCalculationSupport
import com.rsps1008.stockify.data.MarginCalculationSupport
import com.rsps1008.stockify.data.ShortSellingCalculationSupport
import com.rsps1008.stockify.data.StockTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculationOrderingTest {

    @Test
    fun orderedFastPathRequiresAscendingTransactionOrder() {
        val transactions = listOf(
            StockTransaction(
                id = 1,
                stockCode = "2330",
                date = 1L,
                recordTime = 1L,
                type = "買進",
                buyShares = 100.0,
                expense = 100.0
            ),
            StockTransaction(
                id = 2,
                stockCode = "2330",
                date = 2L,
                recordTime = 2L,
                type = "賣出",
                sellShares = 100.0,
                sellPrice = 2.0,
                income = 200.0
            )
        )
        val ordered = transactions.sortedWith(
            compareBy<StockTransaction> { it.date }
                .thenBy { it.recordTime }
                .thenBy { it.id }
        )

        assertTrue(ordered.zipWithNext().all { (left, right) ->
            compareValuesBy(left, right, { it.date }, { it.recordTime }, { it.id }) <= 0
        })
        assertEquals(0.0, HoldingCalculationSupport.replayLongPosition(ordered, 2L, true).shares, 0.0)
    }

    @Test
    fun orderedCalculationFastPathMatchesSortingPath() {
        val transactions = listOf(
            StockTransaction(
                id = 4,
                stockCode = "2330",
                date = 4L,
                recordTime = 4L,
                type = "融資還款",
                expense = 40.0,
                marginRepayment = 40.0,
                marginRepaymentLotId = "margin-1"
            ),
            StockTransaction(
                id = 2,
                stockCode = "2330",
                date = 2L,
                recordTime = 2L,
                type = "融資買進",
                buyPrice = 1.0,
                buyShares = 100.0,
                expense = 100.0,
                marginPrincipal = 80.0,
                marginLotId = "margin-1"
            ),
            StockTransaction(
                id = 3,
                stockCode = "2330",
                date = 3L,
                recordTime = 3L,
                type = "融券賣出",
                sellPrice = 2.0,
                sellShares = 10.0,
                income = 20.0,
                shortBorrowPrincipal = 20.0,
                shortLotId = "short-1"
            ),
            StockTransaction(
                id = 5,
                stockCode = "2330",
                date = 5L,
                recordTime = 5L,
                type = "買券還券",
                expense = 10.0,
                shortCoverShares = 10.0,
                shortCoverLotId = "short-1"
            ),
            StockTransaction(
                id = 1,
                stockCode = "2330",
                date = 1L,
                recordTime = 1L,
                type = "買進",
                buyPrice = 1.0,
                buyShares = 20.0,
                expense = 20.0
            )
        )
        val ordered = transactions.sortedWith(
            compareBy<StockTransaction> { it.date }
                .thenBy { it.recordTime }
                .thenBy { it.id }
        )
        val valuationDate = 5L

        assertEquals(
            HoldingCalculationSupport.replayLongPosition(transactions, valuationDate),
            HoldingCalculationSupport.replayLongPosition(
                ordered,
                valuationDate,
                transactionsAreOrdered = true
            )
        )
        assertEquals(
            MarginCalculationSupport.calculate(transactions, valuationDate, dayCount = 365),
            MarginCalculationSupport.calculate(
                ordered,
                valuationDate,
                dayCount = 365,
                transactionsAreOrdered = true
            )
        )
        assertEquals(
            ShortSellingCalculationSupport.calculate(transactions, valuationDate, dayCount = 365),
            ShortSellingCalculationSupport.calculate(
                ordered,
                valuationDate,
                dayCount = 365,
                transactionsAreOrdered = true
            )
        )
        assertEquals(
            ShortSellingCalculationSupport.buildXirrCashFlows(
                transactions,
                valuationDate,
                currentPrice = 2.0,
                dayCount = 365
            ),
            ShortSellingCalculationSupport.buildXirrCashFlows(
                ordered,
                valuationDate,
                currentPrice = 2.0,
                dayCount = 365,
                transactionsAreOrdered = true
            )
        )
    }
}
