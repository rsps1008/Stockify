package com.rsps1008.stockify

import com.rsps1008.stockify.data.HistoricalTransactionCashFlowTimeline
import com.rsps1008.stockify.data.StockTransaction
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoricalTransactionCashFlowTimelineTest {
    @Test
    fun transactionCashFlowsAreCollectedOnceAndKeepOrderedPrefix() {
        val day = 24L * 60 * 60 * 1000
        val transactions = listOf(
            StockTransaction(
                stockCode = "2330", date = 0L, recordTime = 0L,
                type = "買進", expense = 10_000.0
            ),
            StockTransaction(
                stockCode = "2330", date = day * 2, recordTime = day * 2,
                type = "配息", cashDividend = 2.0, exDividendShares = 100.0,
                dividendIncome = 180.0
            ),
            StockTransaction(
                stockCode = "2330", date = day * 4, recordTime = day * 4,
                type = "賣出", income = 11_000.0
            )
        )
        val timeline = HistoricalTransactionCashFlowTimeline(
            transactions = transactions,
            transactionsAreOrdered = true
        )

        assertEquals(
            listOf(
                com.rsps1008.stockify.data.CashFlow(0L, -10_000.0)
            ),
            timeline.cashFlowsAt(day)
        )
        assertEquals(
            listOf(
                com.rsps1008.stockify.data.CashFlow(0L, -10_000.0),
                com.rsps1008.stockify.data.CashFlow(day * 2, 180.0),
                com.rsps1008.stockify.data.CashFlow(day * 4, 11_000.0)
            ),
            timeline.cashFlowsAt(day * 4)
        )
    }

    @Test
    fun transactionCashFlowsUseTheConfiguredDateOnlyMapping() {
        val transaction = StockTransaction(
            stockCode = "AAPL",
            market = "US",
            date = 1_000L,
            recordTime = 1_000L,
            type = "買進",
            expense = 100.0
        )
        val timeline = HistoricalTransactionCashFlowTimeline(
            transactions = listOf(transaction),
            transactionDateMapper = { it + 5_000L }
        )

        assertEquals(
            listOf(com.rsps1008.stockify.data.CashFlow(6_000L, -100.0)),
            timeline.cashFlowsAt(1_000L)
        )
    }
}
